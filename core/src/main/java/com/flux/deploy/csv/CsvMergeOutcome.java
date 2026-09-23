package com.flux.deploy.csv;

import java.util.ArrayList;
import java.util.List;

/**
 * CSV 增量合并结果
 *
 * <p>两种结局：</p>
 * <ul>
 *   <li>{@link Mode#MERGED} —— 合并成功，{@link #getMergedBytes()} 为写入包内的最终字节</li>
 *   <li>{@link Mode#FALLBACK_OVERWRITE} —— 增量不可行（无基线 / 表头不一致 / 主键未识别 /
 *       解析异常等），调用方退回原有的整份覆盖行为并记录原因，部署流程不中断</li>
 * </ul>
 *
 * @author xumanyi
 * @date 2026-07-10
 */
public final class CsvMergeOutcome {

    /**
     * 合并结局模式
     */
    public enum Mode {
        /** 增量合并成功 */
        MERGED,
        /** 增量不可行，退回整份覆盖 */
        FALLBACK_OVERWRITE
    }

    /** 结局模式 */
    private final Mode mode;

    /** 兜底原因（仅 FALLBACK_OVERWRITE 时非空） */
    private final String fallbackReason;

    /** 合并后的最终字节（仅 MERGED 时非空） */
    private final byte[] mergedBytes;

    /** 新增行数（含"修改行在目标包中不存在→按新增插入"的行） */
    private final int addedCount;

    /** 整行替换数（含幂等重部署时"新增遇已存在键→替换"） */
    private final int replacedCount;

    /** 删除行数 */
    private final int deletedCount;

    /** 未找到原位置而追加到末尾的行数 */
    private final int appendedAtEndCount;

    /** 目标包中原样保留的数据行数 */
    private final int keptCount;

    /** 警告信息（合并过程中的非阻断提示） */
    private final List<String> warnings;

    /** 提示信息（幂等替换、删除跳过、追加至末尾等降级说明） */
    private final List<String> infos;

    private CsvMergeOutcome(Mode mode, String fallbackReason, byte[] mergedBytes,
                            int addedCount, int replacedCount, int deletedCount,
                            int appendedAtEndCount, int keptCount,
                            List<String> warnings, List<String> infos) {
        this.mode = mode;
        this.fallbackReason = fallbackReason;
        this.mergedBytes = mergedBytes;
        this.addedCount = addedCount;
        this.replacedCount = replacedCount;
        this.deletedCount = deletedCount;
        this.appendedAtEndCount = appendedAtEndCount;
        this.keptCount = keptCount;
        this.warnings = List.copyOf(warnings);
        this.infos = List.copyOf(infos);
    }

    /**
     * 构造"退回整份覆盖"结果
     *
     * @param reason 兜底原因（写入部署日志）
     * @return 兜底结果
     * @author xumanyi
     * @date 2026-07-10
     */
    public static CsvMergeOutcome fallback(String reason) {
        return new CsvMergeOutcome(Mode.FALLBACK_OVERWRITE, reason, null,
                0, 0, 0, 0, 0, List.of(), List.of());
    }

    /**
     * 构造"合并成功"结果
     *
     * @param mergedBytes        合并后的最终字节
     * @param addedCount         新增行数
     * @param replacedCount      替换行数
     * @param deletedCount       删除行数
     * @param appendedAtEndCount 追加至末尾的行数
     * @param keptCount          保留的目标包原有数据行数
     * @param warnings           警告信息
     * @param infos              提示信息
     * @return 合并成功结果
     * @author xumanyi
     * @date 2026-07-10
     */
    public static CsvMergeOutcome merged(byte[] mergedBytes,
                                         int addedCount, int replacedCount, int deletedCount,
                                         int appendedAtEndCount, int keptCount,
                                         List<String> warnings, List<String> infos) {
        return new CsvMergeOutcome(Mode.MERGED, null, mergedBytes,
                addedCount, replacedCount, deletedCount, appendedAtEndCount, keptCount,
                warnings, infos);
    }

    /** 获取结局模式 */
    public Mode getMode() { return mode; }

    /** 是否合并成功 */
    public boolean isMerged() { return mode == Mode.MERGED; }

    /** 获取兜底原因（仅兜底时非空） */
    public String getFallbackReason() { return fallbackReason; }

    /** 获取合并后的最终字节（仅合并成功时非空） */
    public byte[] getMergedBytes() { return mergedBytes; }

    /** 获取新增行数 */
    public int getAddedCount() { return addedCount; }

    /** 获取替换行数 */
    public int getReplacedCount() { return replacedCount; }

    /** 获取删除行数 */
    public int getDeletedCount() { return deletedCount; }

    /** 获取追加至末尾的行数 */
    public int getAppendedAtEndCount() { return appendedAtEndCount; }

    /** 获取保留的目标包原有数据行数 */
    public int getKeptCount() { return keptCount; }

    /** 获取警告信息列表 */
    public List<String> getWarnings() { return warnings; }

    /** 获取提示信息列表 */
    public List<String> getInfos() { return infos; }

    /**
     * 生成单行摘要（写入部署日志）
     *
     * @param fileName CSV 文件名
     * @return 摘要文本
     * @author xumanyi
     * @date 2026-07-10
     */
    public String summaryLine(String fileName) {
        if (mode == Mode.FALLBACK_OVERWRITE) {
            return "[CSV增量] " + fileName + " 无法增量合并（" + fallbackReason + "），已整份覆盖";
        }
        StringBuilder sb = new StringBuilder("[CSV增量] ").append(fileName)
                .append("：新增 ").append(addedCount)
                .append("、替换 ").append(replacedCount)
                .append("、删除 ").append(deletedCount)
                .append("，保留目标包原有 ").append(keptCount).append(" 行");
        if (appendedAtEndCount > 0) {
            sb.append("（其中 ").append(appendedAtEndCount).append(" 行未找到原位置，已追加至末尾）");
        }
        return sb.toString();
    }

    /**
     * 汇总全部日志行（摘要 + 提示 + 警告），供调用方逐行输出
     *
     * @param fileName CSV 文件名
     * @return 日志行列表
     * @author xumanyi
     * @date 2026-07-10
     */
    public List<String> allLogLines(String fileName) {
        List<String> lines = new ArrayList<>();
        lines.add(summaryLine(fileName));
        for (String info : infos) {
            lines.add("[CSV增量] " + fileName + " " + info);
        }
        for (String warning : warnings) {
            lines.add("[CSV增量][警告] " + fileName + " " + warning);
        }
        return lines;
    }
}
