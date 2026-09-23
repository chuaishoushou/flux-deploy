package com.flux.deploy.csv;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 守卫 {@link CsvMergeEngine} 的行级增量合并全矩阵契约
 *
 * <p>核心目标：只把"本次未提交改动的行"施加到目标包 CSV——
 * 新增（锚定位插入 / 撞键幂等替换 / 锚丢失追加末尾）、修改（原位替换 / 键丢失转新增）、
 * 删除（按键删除 / 键丢失跳过）；目标包中其他同事的行绝不能丢；
 * 未改动行原始字节保真；增量不可行时兜底整份覆盖且绝不抛异常。</p>
 */
class CsvMergeEngineTest {

    /** 测试用主键字典：BSM_FUNCTION 键 = functionId（organizationId 不在物理 CSV 中） */
    private static final CsvTableKeyRegistry REGISTRY = CsvTableKeyRegistry.load(List.of("""
            SEQNUM,tableName,fieldName,fieldType,keyFlag,showSequence
            N00001,BSM_FUNCTION,functionId,VARCHAR(40),Y,1
            N00002,BSM_FUNCTION,functionDescr,VARCHAR(100),N,2
            N00003,BSM_FUNCTION,functionType,VARCHAR(10),N,3
            N00004,W_ML,entityCode,VARCHAR(20),Y,4
            N00005,W_ML,languageId,VARCHAR(10),Y,5
            N00006,W_ML,textValue,VARCHAR(200),N,6
            """));

    private static final String FILE = "BsmFunctionInitData.csv";

    private static final String HEADER_BLOCK = """
            # # SEC系统 - 功能初始化数据
            # #序号,功能编号,功能描述,功能类型
            SEQNUM,functionId,functionDescr,functionType
            """;

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static String text(CsvMergeOutcome outcome) {
        return new String(outcome.getMergedBytes(), StandardCharsets.UTF_8);
    }

    // ==================== 行级矩阵 ====================

    @Test
    void modify_replacesRowInPlace_keepsColleagueRows() {
        String base = HEADER_BLOCK + "1,F001,旧描述,A\n2,F002,描述二,B\n";
        String mine = HEADER_BLOCK + "1,F001,新描述,A\n2,F002,描述二,B\n";
        // 目标包里有同事未提交 git 的行 F900，且行序与本地不同
        String target = HEADER_BLOCK + "1,F001,旧描述,A\n9,F900,同事的行,X\n2,F002,描述二,B\n";

        CsvMergeOutcome outcome = CsvMergeEngine.merge(
                bytes(target), bytes(mine), base, FILE, REGISTRY);

        assertThat(outcome.isMerged()).isTrue();
        assertThat(outcome.getReplacedCount()).isEqualTo(1);
        assertThat(text(outcome)).isEqualTo(
                HEADER_BLOCK + "1,F001,新描述,A\n9,F900,同事的行,X\n2,F002,描述二,B\n");
    }

    @Test
    void add_insertsAtAnchoredPosition() {
        String base = HEADER_BLOCK + "1,F001,一,A\n2,F002,二,B\n";
        // 在 F001 后插入新行 F150
        String mine = HEADER_BLOCK + "1,F001,一,A\n5,F150,新插入,C\n2,F002,二,B\n";
        String target = HEADER_BLOCK + "1,F001,一,A\n9,F900,同事的行,X\n2,F002,二,B\n";

        CsvMergeOutcome outcome = CsvMergeEngine.merge(
                bytes(target), bytes(mine), base, FILE, REGISTRY);

        assertThat(outcome.isMerged()).isTrue();
        assertThat(outcome.getAddedCount()).isEqualTo(1);
        // 锚 = F001 → 插到目标包中 F001 之后（同事行 F900 仍保留在其后）
        assertThat(text(outcome)).isEqualTo(HEADER_BLOCK
                + "1,F001,一,A\n5,F150,新插入,C\n9,F900,同事的行,X\n2,F002,二,B\n");
    }

    @Test
    void add_missingAnchorAppendsAtEnd() {
        String base = HEADER_BLOCK + "1,F001,一,A\n2,F002,二,B\n";
        String mine = HEADER_BLOCK + "1,F001,一,A\n5,F150,新插入,C\n2,F002,二,B\n";
        // 目标包中锚行 F001 已不存在（文件差异过大）→ 追加到数据区末尾，流程不得中断
        String target = HEADER_BLOCK + "2,F002,二,B\n9,F900,同事的行,X\n";

        CsvMergeOutcome outcome = CsvMergeEngine.merge(
                bytes(target), bytes(mine), base, FILE, REGISTRY);

        assertThat(outcome.isMerged()).isTrue();
        assertThat(outcome.getAppendedAtEndCount()).isEqualTo(1);
        assertThat(text(outcome)).isEqualTo(HEADER_BLOCK
                + "2,F002,二,B\n9,F900,同事的行,X\n5,F150,新插入,C\n");
    }

    @Test
    void add_firstDataRowInsertsAtDataAreaHead() {
        String base = HEADER_BLOCK + "2,F002,二,B\n";
        // 新行加在本地文件数据区第一行
        String mine = HEADER_BLOCK + "1,F001,新首行,A\n2,F002,二,B\n";
        String target = HEADER_BLOCK + "2,F002,二,B\n";

        CsvMergeOutcome outcome = CsvMergeEngine.merge(
                bytes(target), bytes(mine), base, FILE, REGISTRY);

        assertThat(text(outcome)).isEqualTo(HEADER_BLOCK + "1,F001,新首行,A\n2,F002,二,B\n");
    }

    @Test
    void add_existingKeyReplacedForIdempotentRedeploy() {
        String base = HEADER_BLOCK + "1,F001,一,A\n";
        String mine = HEADER_BLOCK + "1,F001,一,A\n5,F150,新行,C\n";
        // 第一次部署已把 F150 带上去了，重复部署不得产生重复行
        String target = HEADER_BLOCK + "1,F001,一,A\n5,F150,新行,C\n";

        CsvMergeOutcome outcome = CsvMergeEngine.merge(
                bytes(target), bytes(mine), base, FILE, REGISTRY);

        assertThat(outcome.isMerged()).isTrue();
        assertThat(outcome.getReplacedCount()).isEqualTo(1);
        assertThat(outcome.getAddedCount()).isZero();
        assertThat(text(outcome)).isEqualTo(HEADER_BLOCK + "1,F001,一,A\n5,F150,新行,C\n");
    }

    @Test
    void delete_removesRow_missingKeySkipped() {
        String base = HEADER_BLOCK + "1,F001,一,A\n2,F002,二,B\n3,F003,三,C\n";
        // 本地删了 F002 与 F003
        String mine = HEADER_BLOCK + "1,F001,一,A\n";
        // 目标包中 F003 已被别人删掉 → 跳过；F002 正常删除；同事行保留
        String target = HEADER_BLOCK + "1,F001,一,A\n2,F002,二,B\n9,F900,同事的行,X\n";

        CsvMergeOutcome outcome = CsvMergeEngine.merge(
                bytes(target), bytes(mine), base, FILE, REGISTRY);

        assertThat(outcome.isMerged()).isTrue();
        assertThat(outcome.getDeletedCount()).isEqualTo(1);
        assertThat(text(outcome)).isEqualTo(HEADER_BLOCK + "1,F001,一,A\n9,F900,同事的行,X\n");
        assertThat(outcome.getInfos()).anyMatch(s -> s.contains("已不存在"));
    }

    @Test
    void modify_missingTargetRowInsertedAsAdd() {
        String base = HEADER_BLOCK + "1,F001,一,A\n2,F002,旧,B\n";
        String mine = HEADER_BLOCK + "1,F001,一,A\n2,F002,新,B\n";
        // 我修改的 F002 在目标包中被别人删了 → 按新增插回（以我为准），锚 = F001
        String target = HEADER_BLOCK + "1,F001,一,A\n9,F900,同事的行,X\n";

        CsvMergeOutcome outcome = CsvMergeEngine.merge(
                bytes(target), bytes(mine), base, FILE, REGISTRY);

        assertThat(outcome.isMerged()).isTrue();
        assertThat(outcome.getAddedCount()).isEqualTo(1);
        assertThat(text(outcome)).isEqualTo(
                HEADER_BLOCK + "1,F001,一,A\n2,F002,新,B\n9,F900,同事的行,X\n");
        assertThat(outcome.getInfos()).anyMatch(s -> s.contains("按新增插入"));
    }

    @Test
    void keyRename_behavesAsDeletePlusAdd() {
        String base = HEADER_BLOCK + "1,F001,一,A\n2,F002,二,B\n";
        // 把 F002 的键改名为 F002X
        String mine = HEADER_BLOCK + "1,F001,一,A\n2,F002X,二,B\n";
        String target = HEADER_BLOCK + "1,F001,一,A\n2,F002,二,B\n9,F900,同事的行,X\n";

        CsvMergeOutcome outcome = CsvMergeEngine.merge(
                bytes(target), bytes(mine), base, FILE, REGISTRY);

        assertThat(outcome.isMerged()).isTrue();
        assertThat(text(outcome)).isEqualTo(
                HEADER_BLOCK + "1,F001,一,A\n2,F002X,二,B\n9,F900,同事的行,X\n");
    }

    // ==================== 复合键 / 特殊内容 ====================

    @Test
    void compositeKey_matchesOnBothColumns() {
        String header = "entityCode,languageId,textValue\n";
        String base = header + "C1,zh_CN,中文\nC1,en,English\n";
        String mine = header + "C1,zh_CN,中文新\nC1,en,English\n";
        // 同 entityCode 不同 languageId 是不同行：只有 zh_CN 行被替换
        String target = base;

        CsvMergeOutcome outcome = CsvMergeEngine.merge(
                bytes(target), bytes(mine), base, "W_ML.csv", REGISTRY);

        assertThat(outcome.isMerged()).isTrue();
        assertThat(outcome.getReplacedCount()).isEqualTo(1);
        assertThat(text(outcome)).isEqualTo(header + "C1,zh_CN,中文新\nC1,en,English\n");
    }

    @Test
    void quotedMultilineField_replacedAsWholeLogicalRecord() {
        String header = "SEQNUM,functionId,functionDescr,functionType\n";
        String base = header + "1,F001,\"SELECT a,\nFROM t\",A\n2,F002,二,B\n";
        String mine = header + "1,F001,\"SELECT a,\nFROM t2\",A\n2,F002,二,B\n";
        String target = base;

        CsvMergeOutcome outcome = CsvMergeEngine.merge(
                bytes(target), bytes(mine), base, FILE, REGISTRY);

        assertThat(outcome.isMerged()).isTrue();
        // 跨物理行的引号字段作为一条逻辑记录整体替换，不得劈裂
        assertThat(text(outcome)).isEqualTo(header + "1,F001,\"SELECT a,\nFROM t2\",A\n2,F002,二,B\n");
    }

    @Test
    void bomAndCrlf_preservedFromTarget() {
        String header = "SEQNUM,functionId,functionDescr,functionType\r\n";
        String base = header + "1,F001,一,A\r\n";
        String mine = header + "1,F001,新,A\n"; // 本地是 LF
        String target = "\uFEFF" + header + "1,F001,一,A\r\n9,F900,同事,X\r\n";

        CsvMergeOutcome outcome = CsvMergeEngine.merge(
                bytes(target), bytes(mine), base, FILE, REGISTRY);

        assertThat(outcome.isMerged()).isTrue();
        // BOM 跟随目标包保留；替换行换行符归一为目标包的 CRLF
        assertThat(text(outcome)).isEqualTo(
                "\uFEFF" + header + "1,F001,新,A\r\n9,F900,同事,X\r\n");
    }

    @Test
    void newComment_appendedToTargetCommentBlock() {
        String base = HEADER_BLOCK + "1,F001,一,A\n";
        // 本地在注释块新加了一行版本注释
        String mine = "# # SEC系统 - 功能初始化数据\n# #序号,功能编号,功能描述,功能类型\n"
                + "#VERNO,V20260710_01,#NEW,XUMY\n"
                + "SEQNUM,functionId,functionDescr,functionType\n"
                + "1,F001,新,A\n";
        String target = HEADER_BLOCK + "1,F001,一,A\n";

        CsvMergeOutcome outcome = CsvMergeEngine.merge(
                bytes(target), bytes(mine), base, FILE, REGISTRY);

        assertThat(outcome.isMerged()).isTrue();
        assertThat(text(outcome)).contains("#VERNO,V20260710_01,#NEW,XUMY\n");
        // 注释插在表头行之前
        assertThat(text(outcome).indexOf("#VERNO"))
                .isLessThan(text(outcome).indexOf("SEQNUM,functionId"));
    }

    @Test
    void noEffectiveChange_keepsTargetByteIdentical() {
        String content = HEADER_BLOCK + "1,F001,\"带,逗号\",A\n2,F002,二,B";
        // base 与 mine 内容一致（引号风格也一致）→ 目标包一个字节都不动（含末行无换行）
        CsvMergeOutcome outcome = CsvMergeEngine.merge(
                bytes(content), bytes(content), content, FILE, REGISTRY);

        assertThat(outcome.isMerged()).isTrue();
        assertThat(outcome.getMergedBytes()).isEqualTo(bytes(content));
    }

    // ==================== 文件级兜底（永不中断） ====================

    @Test
    void fallback_headerMismatchWithTarget() {
        String base = HEADER_BLOCK + "1,F001,一,A\n";
        String mine = HEADER_BLOCK + "1,F001,新,A\n";
        // 目标包是老结构（少一列）→ 行替换必错位，必须整份覆盖
        String target = "SEQNUM,functionId,functionDescr\n1,F001,一\n";

        CsvMergeOutcome outcome = CsvMergeEngine.merge(
                bytes(target), bytes(mine), base, FILE, REGISTRY);

        assertThat(outcome.isMerged()).isFalse();
        assertThat(outcome.getFallbackReason()).contains("表头");
    }

    @Test
    void fallback_headerStructureChangeInMine() {
        String base = "SEQNUM,functionId,functionDescr\n1,F001,一\n";
        // 本次改动本身加了一列 = 结构变更
        String mine = HEADER_BLOCK + "1,F001,一,A\n";
        String target = HEADER_BLOCK + "1,F001,一,A\n";

        CsvMergeOutcome outcome = CsvMergeEngine.merge(
                bytes(target), bytes(mine), base, FILE, REGISTRY);

        assertThat(outcome.isMerged()).isFalse();
        assertThat(outcome.getFallbackReason()).contains("结构变更");
    }

    @Test
    void fallback_unknownTable() {
        String header = "SEQNUM,mysteryCol\n";
        CsvMergeOutcome outcome = CsvMergeEngine.merge(
                bytes(header + "1,a\n"), bytes(header + "1,b\n"), header + "1,a\n",
                "UNKNOWN_TABLE.csv", REGISTRY);

        assertThat(outcome.isMerged()).isFalse();
        assertThat(outcome.getFallbackReason()).contains("主键");
    }

    @Test
    void duplicateKeyInMine_usesLaterRowWithoutValidation() {
        String base = HEADER_BLOCK + "1,F001,一,A\n";
        String mine = HEADER_BLOCK + "1,F001,一,A\n2,F001,重复键,B\n";
        String target = HEADER_BLOCK + "1,F001,一,A\n";

        CsvMergeOutcome outcome = CsvMergeEngine.merge(
                bytes(target), bytes(mine), base, FILE, REGISTRY);

        assertThat(outcome.isMerged()).isTrue();
        assertThat(outcome.getWarnings()).isEmpty();
        assertThat(text(outcome)).isEqualTo(HEADER_BLOCK + "2,F001,重复键,B\n");
    }

    @Test
    void fallback_emptyKeyRowInMine() {
        String base = HEADER_BLOCK + "1,F001,一,A\n";
        String mine = HEADER_BLOCK + "1,F001,一,A\n2,,无键行,B\n";
        String target = HEADER_BLOCK + "1,F001,一,A\n";

        CsvMergeOutcome outcome = CsvMergeEngine.merge(
                bytes(target), bytes(mine), base, FILE, REGISTRY);

        assertThat(outcome.isMerged()).isFalse();
        assertThat(outcome.getFallbackReason()).contains("主键列为空");
    }

    @Test
    void fallback_targetNotUtf8() {
        byte[] gbkLike = {(byte) 0xD6, (byte) 0xD0, ',', 'a', '\n'};
        String base = HEADER_BLOCK + "1,F001,一,A\n";
        String mine = HEADER_BLOCK + "1,F001,新,A\n";

        CsvMergeOutcome outcome = CsvMergeEngine.merge(
                gbkLike, bytes(mine), base, FILE, REGISTRY);

        assertThat(outcome.isMerged()).isFalse();
        assertThat(outcome.getFallbackReason()).contains("UTF-8");
    }

    @Test
    void fallback_nullInputsNeverThrow() {
        // 任何输入都不允许抛异常——最坏结果是兜底整份覆盖
        assertThat(CsvMergeEngine.merge(null, null, null, null, null).isMerged()).isFalse();
        assertThat(CsvMergeEngine.merge(bytes("a"), bytes("b"), "", "x.csv", REGISTRY)
                .isMerged()).isFalse();
    }

    // ==================== 重复内容不校验 ====================

    @Test
    void duplicateKeyInTarget_updatesAllMatchesWithoutValidation() {
        String base = HEADER_BLOCK + "1,F001,旧,A\n";
        String mine = HEADER_BLOCK + "1,F001,新,A\n";
        // 目标包同键两行：插件只做更新，不做重复内容校验，也不清洗成一行。
        String target = HEADER_BLOCK + "1,F001,旧,A\n1,F001,旧副本,A\n9,F900,同事,X\n";

        CsvMergeOutcome outcome = CsvMergeEngine.merge(
                bytes(target), bytes(mine), base, FILE, REGISTRY);

        assertThat(outcome.isMerged()).isTrue();
        assertThat(text(outcome)).isEqualTo(HEADER_BLOCK
                + "1,F001,新,A\n1,F001,新,A\n9,F900,同事,X\n");
        assertThat(outcome.getWarnings()).isEmpty();
    }

    @Test
    void unrelatedDuplicateKeysRemainSilent() {
        String base = HEADER_BLOCK + "1,F001,一,A\n";
        String mine = HEADER_BLOCK + "1,F001,新,A\n";
        // 目标包里本来就有一对与本次改动无关的重复键 F800 → 原样保留，不告警
        String target = HEADER_BLOCK + "1,F001,一,A\n8,F800,甲,X\n8,F800,乙,X\n";

        CsvMergeOutcome outcome = CsvMergeEngine.merge(
                bytes(target), bytes(mine), base, FILE, REGISTRY);

        assertThat(outcome.isMerged()).isTrue();
        assertThat(outcome.getWarnings()).isEmpty();
        assertThat(text(outcome)).contains("8,F800,甲,X\n8,F800,乙,X\n");
    }

    @Test
    void summaryLine_readableForBothModes() {
        CsvMergeOutcome merged = CsvMergeOutcome.merged(bytes("x"), 2, 1, 0, 1, 130,
                List.of(), List.of());
        assertThat(merged.summaryLine("a.csv"))
                .contains("新增 2").contains("替换 1").contains("删除 0")
                .contains("130 行").contains("追加至末尾");
        assertThat(CsvMergeOutcome.fallback("原因X").summaryLine("a.csv"))
                .contains("原因X").contains("整份覆盖");
    }
}
