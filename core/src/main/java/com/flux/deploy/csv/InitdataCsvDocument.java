package com.flux.deploy.csv;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * FLUX initdata CSV 文档模型
 *
 * <p>按 initdata CSV 的结构约定切分文档：</p>
 * <ul>
 *   <li>头部注释块：数据表头之前的全部记录（'#' 注释行，含标题与中文列名说明）</li>
 *   <li>数据表头行：首条非注释、非空白记录（字段编码行，如 {@code SEQNUM,functionId,...}），
 *       位置不固定（注释块行数不定），必须按"首个非注释行"定位</li>
 *   <li>数据记录：表头之后的全部非注释、非空白记录</li>
 * </ul>
 *
 * <p>解析使用严格 UTF-8 解码（非法字节序列直接报错而非替换），
 * 保证增量合并绝不在编码不明的文件上静默产出损坏内容。</p>
 *
 * @author xumanyi
 * @date 2026-07-10
 */
public final class InitdataCsvDocument {

    /** UTF-8 BOM 字符 */
    private static final char BOM_CHAR = '\uFEFF';

    /** 全部记录（注释 / 表头 / 数据 / 空白，保持原始顺序） */
    private final List<CsvRecord> records;

    /** 表头记录在 {@link #records} 中的下标（无表头时为 -1） */
    private final int headerIndex;

    /** trim 后的表头字段名（无表头时为空列表） */
    private final List<String> headerFields;

    /** 原文是否带 UTF-8 BOM */
    private final boolean hasBom;

    /** 主导行终止符（取首条带终止符记录的终止符，缺省 "\n"） */
    private final String dominantTerminator;

    private InitdataCsvDocument(List<CsvRecord> records, int headerIndex,
                                List<String> headerFields, boolean hasBom,
                                String dominantTerminator) {
        this.records = records;
        this.headerIndex = headerIndex;
        this.headerFields = headerFields;
        this.hasBom = hasBom;
        this.dominantTerminator = dominantTerminator;
    }

    /**
     * 从字节解析文档（严格 UTF-8 解码）
     *
     * @param bytes CSV 文件字节
     * @return 文档模型
     * @throws CharacterCodingException 内容不是合法 UTF-8 时抛出（调用方转文件级兜底）
     * @author xumanyi
     * @date 2026-07-10
     */
    public static InitdataCsvDocument parse(byte[] bytes) throws CharacterCodingException {
        String text = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString();
        return parse(text);
    }

    /**
     * 从文本解析文档
     *
     * @param text CSV 完整文本（可带 BOM）
     * @return 文档模型
     * @author xumanyi
     * @date 2026-07-10
     */
    public static InitdataCsvDocument parse(String text) {
        boolean bom = !text.isEmpty() && text.charAt(0) == BOM_CHAR;
        String content = bom ? text.substring(1) : text;

        List<CsvRecord> records = CsvRecordScanner.scan(content);

        int headerIdx = -1;
        for (int i = 0; i < records.size(); i++) {
            if (records.get(i).isData()) {
                headerIdx = i;
                break;
            }
        }

        List<String> header = new ArrayList<>();
        if (headerIdx >= 0) {
            for (String f : records.get(headerIdx).getFields()) {
                header.add(f == null ? "" : f.trim());
            }
        }

        String terminator = "\n";
        for (CsvRecord r : records) {
            if (!r.getTerminator().isEmpty()) {
                terminator = r.getTerminator();
                break;
            }
        }

        return new InitdataCsvDocument(records, headerIdx, List.copyOf(header), bom, terminator);
    }

    /** 获取全部记录（保持原始顺序） */
    public List<CsvRecord> getRecords() { return records; }

    /** 获取表头记录下标（无表头时为 -1） */
    public int getHeaderIndex() { return headerIndex; }

    /** 是否存在表头（即存在至少一条非注释、非空白记录） */
    public boolean hasHeader() { return headerIndex >= 0; }

    /** 获取 trim 后的表头字段名列表 */
    public List<String> getHeaderFields() { return headerFields; }

    /** 原文是否带 UTF-8 BOM */
    public boolean hasBom() { return hasBom; }

    /** 获取主导行终止符 */
    public String getDominantTerminator() { return dominantTerminator; }

    /**
     * 获取全部数据记录（表头之后的非注释、非空白记录）
     *
     * @return 数据记录列表（保持文件内顺序）
     * @author xumanyi
     * @date 2026-07-10
     */
    public List<CsvRecord> getDataRecords() {
        List<CsvRecord> data = new ArrayList<>();
        if (headerIndex < 0) {
            return data;
        }
        for (int i = headerIndex + 1; i < records.size(); i++) {
            CsvRecord r = records.get(i);
            if (r.isData()) {
                data.add(r);
            }
        }
        return data;
    }

    /**
     * 获取表头之前的注释行原文列表（用于计算注释增量）
     *
     * @return 注释行原文（不含行终止符，保持顺序）
     * @author xumanyi
     * @date 2026-07-10
     */
    public List<String> getLeadingCommentLines() {
        List<String> comments = new ArrayList<>();
        int end = headerIndex >= 0 ? headerIndex : records.size();
        for (int i = 0; i < end; i++) {
            CsvRecord r = records.get(i);
            if (r.isComment()) {
                comments.add(r.getRawText());
            }
        }
        return comments;
    }

    /**
     * 表头一致性比较（忽略尾部连续空列名）
     *
     * <p>真实数据中存在表头行尾部多一个逗号（形成空列名）的情况，
     * 属于保存工具差异而非结构差异，比较前剥掉尾部空列，减少不必要的整份覆盖。</p>
     *
     * @param other 另一文档
     * @return true=表头结构一致
     * @author xumanyi
     * @date 2026-07-10
     */
    public boolean headerMatches(InitdataCsvDocument other) {
        return stripTrailingBlanks(headerFields).equals(stripTrailingBlanks(other.headerFields));
    }

    /**
     * 剥掉列表尾部连续的空白元素
     *
     * @param list 输入列表
     * @return 剥尾后的新列表
     * @author xumanyi
     * @date 2026-07-10
     */
    private static List<String> stripTrailingBlanks(List<String> list) {
        int end = list.size();
        while (end > 0 && list.get(end - 1).isBlank()) {
            end--;
        }
        return list.subList(0, end);
    }
}
