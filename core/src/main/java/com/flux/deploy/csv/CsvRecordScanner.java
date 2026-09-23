package com.flux.deploy.csv;

import java.util.ArrayList;
import java.util.List;

/**
 * RFC-4180 CSV 逻辑记录扫描器
 *
 * <p>自实现的精简扫描器（不引入第三方 CSV 库，避免离线构建缓存缺失风险），支持：</p>
 * <ul>
 *   <li>引号包裹字段内的逗号与换行（一条逻辑记录跨多个物理行）</li>
 *   <li>{@code ""} 转义的双引号</li>
 *   <li>CRLF / LF / CR 三种行终止符，末行无终止符</li>
 *   <li>注释行（行首为 '#'）直接扫描到行尾，不进入引号状态机——
 *       防止注释文本中出现的奇数个引号把后续数据行"吞"进同一条记录</li>
 * </ul>
 *
 * <p>只做解析不做写出：输出侧由调用方拼接 {@link CsvRecord#getRawText()} 原文，
 * 保证未改动内容的字节保真。</p>
 *
 * @author xumanyi
 * @date 2026-07-10
 */
public final class CsvRecordScanner {

    private CsvRecordScanner() {}

    /**
     * 将 CSV 文本扫描为逻辑记录列表
     *
     * <p>输入应为已剥除 BOM 的完整文本。空文件返回空列表。</p>
     *
     * @param content CSV 完整文本
     * @return 逻辑记录列表（含注释行、空白行，保持原始顺序）
     * @author xumanyi
     * @date 2026-07-10
     */
    public static List<CsvRecord> scan(String content) {
        List<CsvRecord> records = new ArrayList<>();
        int len = content.length();
        int pos = 0;

        while (pos < len) {
            // 注释行：从行首 '#' 直接扫到行尾，不解析引号
            if (content.charAt(pos) == '#') {
                pos = scanCommentLine(content, pos, records);
            } else {
                pos = scanDataRecord(content, pos, records);
            }
        }
        return records;
    }

    /**
     * 扫描一条注释行（行首为 '#'，到最近的行终止符为止）
     *
     * @param content 完整文本
     * @param start   注释行起始位置
     * @param records 记录收集列表
     * @return 下一条记录的起始位置
     * @author xumanyi
     * @date 2026-07-10
     */
    private static int scanCommentLine(String content, int start, List<CsvRecord> records) {
        int len = content.length();
        int i = start;
        while (i < len && content.charAt(i) != '\n' && content.charAt(i) != '\r') {
            i++;
        }
        String raw = content.substring(start, i);
        String terminator = readTerminator(content, i);
        records.add(new CsvRecord(List.of(raw), raw, terminator));
        return i + terminator.length();
    }

    /**
     * 扫描一条数据记录（RFC-4180 状态机，支持引号字段跨物理行）
     *
     * @param content 完整文本
     * @param start   记录起始位置
     * @param records 记录收集列表
     * @return 下一条记录的起始位置
     * @author xumanyi
     * @date 2026-07-10
     */
    private static int scanDataRecord(String content, int start, List<CsvRecord> records) {
        int len = content.length();
        List<String> fields = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean inQuotes = false;
        int i = start;

        while (i < len) {
            char c = content.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < len && content.charAt(i + 1) == '"') {
                        // "" 转义为一个双引号
                        field.append('"');
                        i += 2;
                        continue;
                    }
                    inQuotes = false;
                    i++;
                } else {
                    field.append(c);
                    i++;
                }
            } else {
                if (c == '"') {
                    inQuotes = true;
                    i++;
                } else if (c == ',') {
                    fields.add(field.toString());
                    field.setLength(0);
                    i++;
                } else if (c == '\n' || c == '\r') {
                    break; // 记录结束
                } else {
                    field.append(c);
                    i++;
                }
            }
        }

        fields.add(field.toString());
        String raw = content.substring(start, i);
        String terminator = readTerminator(content, i);
        records.add(new CsvRecord(fields, raw, terminator));
        return i + terminator.length();
    }

    /**
     * 读取指定位置的行终止符
     *
     * @param content 完整文本
     * @param pos     终止符起始位置
     * @return "\r\n" / "\n" / "\r"，位置越界（文件末尾无换行）时为空串
     * @author xumanyi
     * @date 2026-07-10
     */
    private static String readTerminator(String content, int pos) {
        if (pos >= content.length()) {
            return "";
        }
        char c = content.charAt(pos);
        if (c == '\r') {
            return (pos + 1 < content.length() && content.charAt(pos + 1) == '\n') ? "\r\n" : "\r";
        }
        return c == '\n' ? "\n" : "";
    }
}
