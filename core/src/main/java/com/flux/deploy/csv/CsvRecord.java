package com.flux.deploy.csv;

import java.util.List;

/**
 * CSV 逻辑记录
 *
 * <p>一条"逻辑记录"可能跨越多个物理文本行（字段内含引号包裹的换行，
 * 如 T_RUL_DBSQL 的多行 SQL 字段），因此不能按文本行处理 CSV，必须以逻辑记录为单位。</p>
 *
 * <p>为实现"未改动的行保留原始字节"（尽可能少改目标包 CSV），
 * 记录同时保留解析后的字段值与原始文本两种形态：</p>
 * <ul>
 *   <li>{@link #getFields()} —— RFC-4180 解析后的字段值，用于键匹配与内容比较</li>
 *   <li>{@link #getRawText()} —— 原始文本（不含行终止符），输出时原样写回，
 *       不重新序列化，避免引号风格被改写产生无意义差异</li>
 * </ul>
 *
 * @author xumanyi
 * @date 2026-07-10
 */
public final class CsvRecord {

    /** RFC-4180 解析后的字段值列表 */
    private final List<String> fields;

    /** 原始文本（不含行终止符），输出时原样写回 */
    private final String rawText;

    /** 行终止符（"\n" / "\r\n" / "\r"，文件末行无换行时为空串） */
    private final String terminator;

    /** 是否为注释记录（原始文本以 '#' 开头） */
    private final boolean comment;

    /**
     * 构造 CSV 逻辑记录
     *
     * @param fields     解析后的字段值
     * @param rawText    原始文本（不含行终止符）
     * @param terminator 行终止符（可为空串）
     * @author xumanyi
     * @date 2026-07-10
     */
    public CsvRecord(List<String> fields, String rawText, String terminator) {
        this.fields = List.copyOf(fields);
        this.rawText = rawText;
        this.terminator = terminator;
        this.comment = !rawText.isEmpty() && rawText.charAt(0) == '#';
    }

    /** 获取解析后的字段值列表 */
    public List<String> getFields() { return fields; }

    /** 获取原始文本（不含行终止符） */
    public String getRawText() { return rawText; }

    /** 获取行终止符（文件末行无换行时为空串） */
    public String getTerminator() { return terminator; }

    /** 是否为注释记录（以 '#' 开头） */
    public boolean isComment() { return comment; }

    /** 是否为空白记录（原始文本为空或全空白字符） */
    public boolean isBlank() { return rawText.isBlank(); }

    /** 是否为数据记录（非注释且非空白） */
    public boolean isData() { return !comment && !isBlank(); }

    /**
     * 以新的行终止符复制本记录（字段与原始文本不变）
     *
     * <p>用于把"我的 CSV"中的行写入目标包 CSV 时，将行终止符归一到目标包的主导风格。</p>
     *
     * @param newTerminator 新的行终止符
     * @return 复制出的新记录
     * @author xumanyi
     * @date 2026-07-10
     */
    public CsvRecord withTerminator(String newTerminator) {
        return new CsvRecord(fields, rawText, newTerminator);
    }
}
