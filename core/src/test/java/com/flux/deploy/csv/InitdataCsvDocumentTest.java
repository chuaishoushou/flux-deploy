package com.flux.deploy.csv;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 守卫 {@link InitdataCsvDocument} 的 initdata 结构切分契约
 *
 * <p>表头 = 首个非注释非空记录（位置不固定，不能写死行号）；
 * BOM 识别与剥离；表头一致性比较忽略尾部空列；严格 UTF-8 解码。</p>
 */
class InitdataCsvDocumentTest {

    private static final String SAMPLE = """
            # ###########
            # # 标题说明
            # #序号,功能编号,类型
            SEQNUM,functionId,functionType
            1,F001,A
            2,F002,B
            """;

    @Test
    void parse_headerIsFirstNonCommentRecord() {
        InitdataCsvDocument doc = InitdataCsvDocument.parse(SAMPLE);
        assertThat(doc.hasHeader()).isTrue();
        assertThat(doc.getHeaderIndex()).isEqualTo(3);
        assertThat(doc.getHeaderFields()).containsExactly("SEQNUM", "functionId", "functionType");
        assertThat(doc.getDataRecords()).hasSize(2);
        assertThat(doc.getLeadingCommentLines()).hasSize(3);
    }

    @Test
    void parse_headerFieldsAreTrimmed() {
        InitdataCsvDocument doc = InitdataCsvDocument.parse("SEQNUM,interfaceId ,appSrvId\n1,I01,S01\n");
        // 真实数据存在表头字段带尾随空格的脏数据，必须 trim
        assertThat(doc.getHeaderFields()).containsExactly("SEQNUM", "interfaceId", "appSrvId");
    }

    @Test
    void parse_bomDetectedAndStripped() {
        InitdataCsvDocument doc = InitdataCsvDocument.parse("\uFEFFSEQNUM,v\n1,a\n");
        assertThat(doc.hasBom()).isTrue();
        assertThat(doc.getHeaderFields()).containsExactly("SEQNUM", "v");
    }

    @Test
    void parse_strictUtf8RejectsBrokenBytes() {
        byte[] broken = {(byte) 0xC3, (byte) 0x28, 'a', ',', 'b'};
        assertThatThrownBy(() -> InitdataCsvDocument.parse(broken))
                .isInstanceOf(java.nio.charset.CharacterCodingException.class);
    }

    @Test
    void parse_validUtf8Bytes() throws Exception {
        InitdataCsvDocument doc = InitdataCsvDocument.parse(
                "SEQNUM,descr\n1,中文描述\n".getBytes(StandardCharsets.UTF_8));
        assertThat(doc.getDataRecords().get(0).getFields()).containsExactly("1", "中文描述");
    }

    @Test
    void headerMatches_ignoresTrailingBlankColumns() {
        InitdataCsvDocument a = InitdataCsvDocument.parse("SEQNUM,v,\n1,a,\n");
        InitdataCsvDocument b = InitdataCsvDocument.parse("SEQNUM,v\n1,a\n");
        // 表头尾部多一个逗号（空列名）属保存工具差异，不算结构差异
        assertThat(a.headerMatches(b)).isTrue();
    }

    @Test
    void headerMatches_detectsRealStructureDifference() {
        InitdataCsvDocument a = InitdataCsvDocument.parse("SEQNUM,v,extra\n1,a,x\n");
        InitdataCsvDocument b = InitdataCsvDocument.parse("SEQNUM,v\n1,a\n");
        assertThat(a.headerMatches(b)).isFalse();
    }

    @Test
    void parse_allCommentsMeansNoHeader() {
        InitdataCsvDocument doc = InitdataCsvDocument.parse("# only\n# comments\n");
        assertThat(doc.hasHeader()).isFalse();
        assertThat(doc.getDataRecords()).isEmpty();
    }
}
