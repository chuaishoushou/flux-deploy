package com.flux.deploy.csv;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 守卫 {@link CsvTableKeyRegistry} 的主键解析与表识别契约
 *
 * <p>keyFlag=Y 归集（剔除 Descr / 多语言 '~' 脏标记、'*' 公共字段字典项）；
 * 两种文件名风格识别（TABLE_NAME 直接名 + 引擎/版本后缀变体、XxxInitData 驼峰）；
 * 列集合反查唯一命中才生效（歧义不猜）；键取"表主键 ∩ CSV 实际列"。</p>
 */
class CsvTableKeyRegistryTest {

    /** 模拟 tablefieldlist 内容（结构与真实文件一致：注释块 + 表头 + 数据行） */
    private static final String TABLEFIELDLIST = """
            # ###########
            # # 数据库表的字段
            SEQNUM,tableName,fieldName,fieldType,keyFlag,showSequence
            N00001,BSM_FUNCTION,organizationId,VARCHAR(20),Y,1
            N00002,BSM_FUNCTION,functionId,VARCHAR(40),Y,2
            N00003,BSM_FUNCTION,functionType,VARCHAR(10),N,3
            N00004,BSM_FUNCTION,showSequence,NUMBER,N,4
            N00005,T_BAS_PORT,organizationId,VARCHAR(20),Y,5
            N00006,T_BAS_PORT,portCode,VARCHAR(40),Y,6
            N00007,T_BAS_PORT,portDescr1,VARCHAR(100),N,7
            N00008,BMS_BAS_CHARGE_ML,chargeType,VARCHAR(20),Y,8
            N00009,BMS_BAS_CHARGE_ML,languageId,VARCHAR(10),Y,9
            N00010,BMS_BAS_CHARGE_ML,chargeTypeDescr,VARCHAR(100),Y,10
            N00011,T_RUL_DBSQL,dbsId,VARCHAR(40),Y,11
            N00012,T_RUL_DBSQL,jsonData,CLOB,N,12
            N00013,*,organizationId,VARCHAR(20),N,13
            N00014,BSM_FUNCTION_ACTION,functionId,VARCHAR(40),Y,14
            N00015,BSM_FUNCTION_ACTION,actionId,VARCHAR(40),Y,15
            N00016,BSM_CODETYPE,codeType,VARCHAR(20),Y,16
            N00017,ARCHIVE_DETAILS,archiveId,VARCHAR(40),Y,17
            """;

    private final CsvTableKeyRegistry registry = CsvTableKeyRegistry.load(List.of(TABLEFIELDLIST));

    @Test
    void load_buildsTables() {
        assertThat(registry.isEmpty()).isFalse();
        assertThat(registry.tableCount()).isEqualTo(7);
    }

    @Test
    void resolve_normalizedSuffixMatchForMissingModulePrefix() {
        // 真实失败案例：FunctionActionInitData → 表 BSM_FUNCTION_ACTION（文件名缺 BSM_ 前缀），
        // 归一化后缀唯一命中，复合主键 = functionId + actionId
        List<String> keys = registry.resolveKeyColumns("FunctionActionInitData.csv",
                List.of("SEQNUM", "functionId", "actionId", "actionDescr~zh_CN", "activeFlag"));
        assertThat(keys).containsExactly("functionId", "actionId");
    }

    @Test
    void resolve_normalizedExactMatchForCamelSplitAmbiguity() {
        // 真实失败案例：BsmCodeTypeInitData 驼峰转出 BSM_CODE_TYPE，真实表名是 BSM_CODETYPE，
        // 归一化（去下划线）后精确相等
        List<String> keys = registry.resolveKeyColumns("BsmCodeTypeInitData.csv",
                List.of("SEQNUM", "codeType", "codeTypeDescr~zh_CN"));
        assertThat(keys).containsExactly("codeType");
    }

    @Test
    void resolve_lowercaseInitDataTailStripped() {
        // 真实失败案例：ARCHIVE_DETAILS_initData（小写 i 的混合风格尾巴）→ 表 ARCHIVE_DETAILS
        List<String> keys = registry.resolveKeyColumns("ARCHIVE_DETAILS_initData.csv",
                List.of("SEQNUM", "archiveId", "archiveName"));
        assertThat(keys).containsExactly("archiveId");
    }

    @Test
    void resolve_directFileNameMatch() {
        List<String> keys = registry.resolveKeyColumns("T_BAS_PORT.csv",
                List.of("SEQNUM", "organizationId", "portCode", "portDescr1"));
        assertThat(keys).containsExactly("organizationId", "portCode");
    }

    @Test
    void resolve_engineSuffixVariant() {
        List<String> keys = registry.resolveKeyColumns("T_RUL_DBSQL-oracle.csv",
                List.of("SEQNUM", "dbsId", "jsonData"));
        assertThat(keys).containsExactly("dbsId");
    }

    @Test
    void resolve_versionSuffixVariant() {
        List<String> keys = registry.resolveKeyColumns("T_BAS_PORT_V3.9_20250707.csv",
                List.of("SEQNUM", "organizationId", "portCode"));
        assertThat(keys).containsExactly("organizationId", "portCode");
    }

    @Test
    void resolve_pascalCaseInitDataStyle() {
        // BsmFunctionInitData.csv → BSM_FUNCTION；物理 CSV 无 organizationId 列 → 交集只剩 functionId
        List<String> keys = registry.resolveKeyColumns("BsmFunctionInitData.csv",
                List.of("SEQNUM", "functionId", "functionDescr~zh_CN", "functionType"));
        assertThat(keys).containsExactly("functionId");
    }

    @Test
    void resolve_descriptiveColumnNeverBecomesKey() {
        // chargeTypeDescr 被脏标记为 keyFlag=Y，必须剔除；多语言表键 = chargeType + languageId
        List<String> keys = registry.resolveKeyColumns("BMS_BAS_CHARGE_ML.csv",
                List.of("SEQNUM", "chargeType", "languageId", "chargeTypeDescr"));
        assertThat(keys).containsExactly("chargeType", "languageId");
    }

    @Test
    void resolve_reverseLookupByColumnSet() {
        // 文件名完全对不上 → 列集合反查（唯一命中 BSM_FUNCTION）
        List<String> keys = registry.resolveKeyColumns("whatever.csv",
                List.of("SEQNUM", "functionId", "functionType", "showSequence"));
        assertThat(keys).containsExactly("functionId");
    }

    @Test
    void resolve_ambiguousReverseLookupReturnsEmpty() {
        // 只有 organizationId 一列，BSM_FUNCTION / T_BAS_PORT 都包含 → 歧义不猜
        List<String> keys = registry.resolveKeyColumns("unknown.csv",
                List.of("SEQNUM", "organizationId"));
        assertThat(keys).isEmpty();
    }

    @Test
    void resolve_unknownTableReturnsEmpty() {
        List<String> keys = registry.resolveKeyColumns("NO_SUCH_TABLE.csv",
                List.of("SEQNUM", "someColumn"));
        assertThat(keys).isEmpty();
    }

    @Test
    void load_toleratesBrokenContentAmongGoodOnes() {
        CsvTableKeyRegistry merged = CsvTableKeyRegistry.load(List.of(
                "# 全是注释没有表头\n# 第二行\n", TABLEFIELDLIST, ""));
        assertThat(merged.tableCount()).isEqualTo(7);
    }
}
