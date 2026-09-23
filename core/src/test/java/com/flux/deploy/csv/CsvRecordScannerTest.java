package com.flux.deploy.csv;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 守卫 {@link CsvRecordScanner} 的 RFC-4180 逻辑记录扫描契约
 *
 * <p>关键场景：引号字段跨物理行（T_RUL_DBSQL 的多行 SQL）、{@code ""} 转义、
 * 注释行不进引号状态机（注释中奇数个引号不得吞掉后续数据行）、
 * 原始文本与行终止符保真（输出侧字节还原的基础）。</p>
 */
class CsvRecordScannerTest {

    @Test
    void scan_basicCommaSeparated() {
        List<CsvRecord> records = CsvRecordScanner.scan("a,b,c\n1,2,3\n");
        assertThat(records).hasSize(2);
        assertThat(records.get(0).getFields()).containsExactly("a", "b", "c");
        assertThat(records.get(1).getFields()).containsExactly("1", "2", "3");
    }

    @Test
    void scan_quotedFieldWithCommaAndEscapedQuote() {
        List<CsvRecord> records = CsvRecordScanner.scan("x,\"a,b\",\"he said \"\"hi\"\"\"\n");
        assertThat(records).hasSize(1);
        assertThat(records.get(0).getFields()).containsExactly("x", "a,b", "he said \"hi\"");
        // 原始文本保真：引号原样保留
        assertThat(records.get(0).getRawText()).isEqualTo("x,\"a,b\",\"he said \"\"hi\"\"\"");
    }

    @Test
    void scan_quotedFieldSpanningPhysicalLines() {
        String content = "1,\"SELECT a,\nFROM t\nWHERE x=1\",tail\n2,plain,end\n";
        List<CsvRecord> records = CsvRecordScanner.scan(content);
        // 跨 3 个物理行的引号字段必须归为一条逻辑记录
        assertThat(records).hasSize(2);
        assertThat(records.get(0).getFields())
                .containsExactly("1", "SELECT a,\nFROM t\nWHERE x=1", "tail");
        assertThat(records.get(1).getFields()).containsExactly("2", "plain", "end");
    }

    @Test
    void scan_crlfAndMissingFinalTerminator() {
        List<CsvRecord> records = CsvRecordScanner.scan("a,b\r\nc,d");
        assertThat(records).hasSize(2);
        assertThat(records.get(0).getTerminator()).isEqualTo("\r\n");
        assertThat(records.get(1).getTerminator()).isEmpty();
        assertThat(records.get(1).getRawText()).isEqualTo("c,d");
    }

    @Test
    void scan_commentLineWithOddQuotesDoesNotSwallowNextLine() {
        String content = "# 说明(\"英文\" 引号不闭合: \"\nSEQNUM,functionId\n1,F001\n";
        List<CsvRecord> records = CsvRecordScanner.scan(content);
        assertThat(records).hasSize(3);
        assertThat(records.get(0).isComment()).isTrue();
        assertThat(records.get(1).getFields()).containsExactly("SEQNUM", "functionId");
        assertThat(records.get(2).getFields()).containsExactly("1", "F001");
    }

    @Test
    void scan_emptyAndBlankLines() {
        List<CsvRecord> records = CsvRecordScanner.scan("a,b\n\n c,d\n");
        assertThat(records).hasSize(3);
        assertThat(records.get(1).isBlank()).isTrue();
        assertThat(records.get(1).isData()).isFalse();
    }

    @Test
    void scan_rawTextRoundTrip() {
        String content = "# 注释\nSEQNUM,v\n1,\"a,b\"\n2,c";
        List<CsvRecord> records = CsvRecordScanner.scan(content);
        StringBuilder sb = new StringBuilder();
        for (CsvRecord r : records) {
            sb.append(r.getRawText()).append(r.getTerminator());
        }
        // rawText + terminator 拼接必须完整还原原文（字节保真基础）
        assertThat(sb.toString()).isEqualTo(content);
    }
}
