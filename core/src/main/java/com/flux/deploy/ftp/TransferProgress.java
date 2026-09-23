package com.flux.deploy.ftp;

import java.io.FilterInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * FTP 传输进度节流日志
 *
 * <p>把上传（InputStream）或下载（OutputStream）的流包一层计数流，每隔
 * {@link #LOG_INTERVAL_MS} 打一条进度：总大小、已传、剩余、百分比、瞬时速率、预计还需时间。
 * 大文件传输期间不再整段静默；小文件（传输不足一个间隔）一条进度都不打，不会刷屏。</p>
 *
 * <p>{@link BatchProgress} 是同款节奏的批量版：成百个小文件逐个传时，单文件的字节进度
 * 看不出整体进展，按"已传 N/M 个文件 + 总字节进度"汇报才知道整体走到哪了。</p>
 *
 * <p>此外，总量 ≥ {@link #ANNOUNCE_THRESHOLD} 的传输在开始时立即打一条
 * "开始，共 X MB" 头行，让操作人员知道接下来的静默是在传大文件而非卡死。</p>
 *
 * <p>线程模型：计数流仅在传输线程内被调用，无并发，不加锁。</p>
 *
 * @author xumanyi
 * @date 2026-08-26
 */
public final class TransferProgress {

    /** 进度日志最小间隔（毫秒） */
    public static final long LOG_INTERVAL_MS = 5_000;

    /** 打"开始，共 X"头行的总量门槛：低于此大小的传输静默完成即可 */
    public static final long ANNOUNCE_THRESHOLD = 8L * 1024 * 1024;

    private TransferProgress() {
    }

    /**
     * 包装上传输入流：storeFile 每读一段就累计进度并按间隔输出日志
     *
     * @param in      原始输入流（应已定位到续传起点之后）
     * @param initial 续传起点字节数（已在远端的部分，从头传为 0）
     * @param total   本地文件总大小（字节）
     * @param label   日志前缀（如 {@code [上传] logincenter.war}）
     * @param log     日志回调，为 null 时原样返回不包装
     * @return 计数输入流
     * @author xumanyi
     * @date 2026-08-26
     */
    public static InputStream wrapUpload(InputStream in, long initial, long total,
                                         String label, Consumer<String> log) {
        if (log == null) {
            return in;
        }
        Tracker tracker = new Tracker(label, initial, total, log,
                LOG_INTERVAL_MS, System::nanoTime);
        return new FilterInputStream(in) {
            /**
             * 读单字节并累计进度
             *
             * @return 读到的字节，流尾为 -1
             * @throws IOException 底层读失败
             * @author xumanyi
             * @date 2026-08-26
             */
            @Override
            public int read() throws IOException {
                int b = super.read();
                if (b >= 0) {
                    tracker.onBytes(1);
                }
                return b;
            }

            /**
             * 读一段字节并累计进度
             *
             * @param buf 目标缓冲区
             * @param off 写入起始偏移
             * @param len 最多读取字节数
             * @return 实际读到的字节数，流尾为 -1
             * @throws IOException 底层读失败
             * @author xumanyi
             * @date 2026-08-26
             */
            @Override
            public int read(byte[] buf, int off, int len) throws IOException {
                int n = super.read(buf, off, len);
                if (n > 0) {
                    tracker.onBytes(n);
                }
                return n;
            }
        };
    }

    /**
     * 包装下载输出流：每写一段就累计进度并按间隔输出日志
     *
     * @param out   原始输出流
     * @param total 远端文件总大小（字节），未知时传 ≤0（进度行降级为只报已传字节与速率）
     * @param label 日志前缀（如 {@code [备份] 下载 logincenter.war}）
     * @param log   日志回调，为 null 时原样返回不包装
     * @return 计数输出流
     * @author xumanyi
     * @date 2026-08-26
     */
    public static OutputStream wrapDownload(OutputStream out, long total,
                                            String label, Consumer<String> log) {
        if (log == null) {
            return out;
        }
        Tracker tracker = new Tracker(label, 0, total, log,
                LOG_INTERVAL_MS, System::nanoTime);
        return new FilterOutputStream(out) {
            /**
             * 写单字节并累计进度
             *
             * @param b 待写字节
             * @throws IOException 底层写失败
             * @author xumanyi
             * @date 2026-08-26
             */
            @Override
            public void write(int b) throws IOException {
                out.write(b);
                tracker.onBytes(1);
            }

            /**
             * 写一段字节并累计进度（绕开 FilterOutputStream 默认的逐字节转发）
             *
             * @param buf 源缓冲区
             * @param off 读取起始偏移
             * @param len 写入字节数
             * @throws IOException 底层写失败
             * @author xumanyi
             * @date 2026-08-26
             */
            @Override
            public void write(byte[] buf, int off, int len) throws IOException {
                out.write(buf, off, len);
                tracker.onBytes(len);
            }
        };
    }

    /**
     * 进度累计与节流输出
     *
     * <p>构造时若总量过门槛立即打"开始"头行；之后每满一个时间间隔打一条进度行，
     * 速率取最近一个间隔内的瞬时值，预计剩余时间由瞬时速率推算。</p>
     *
     * @author xumanyi
     * @date 2026-08-26
     */
    static final class Tracker {

        private final String label;
        private final long total;
        private final Consumer<String> log;
        private final long intervalNanos;
        private final LongSupplier clock;

        /** 累计已传字节（含续传起点） */
        private long transferred;
        /** 上一条进度行的时刻（纳秒） */
        private long lastLogNanos;
        /** 上一条进度行时的已传字节 */
        private long lastLogBytes;

        /**
         * 创建进度追踪器
         *
         * @param label      日志前缀
         * @param initial    起始已传字节（续传场景 &gt; 0）
         * @param total      总字节数，未知传 ≤0
         * @param log        日志回调
         * @param intervalMs 进度行最小间隔（毫秒）
         * @param clock      纳秒时钟（测试可注入）
         * @author xumanyi
         * @date 2026-08-26
         */
        Tracker(String label, long initial, long total, Consumer<String> log,
                long intervalMs, LongSupplier clock) {
            this.label = label;
            this.total = total;
            this.log = log;
            this.intervalNanos = intervalMs * 1_000_000L;
            this.clock = clock;
            this.transferred = initial;
            this.lastLogNanos = clock.getAsLong();
            this.lastLogBytes = initial;
            if (total >= ANNOUNCE_THRESHOLD) {
                log.accept(label + " 开始传输，共 " + formatSize(total)
                        + (initial > 0 ? "，从 " + formatSize(initial) + " 处续传" : ""));
            }
        }

        /**
         * 累计新传输的字节，满间隔时输出一条进度行
         *
         * @param n 本次传输字节数
         * @author xumanyi
         * @date 2026-08-26
         */
        void onBytes(long n) {
            transferred += n;
            long now = clock.getAsLong();
            long elapsed = now - lastLogNanos;
            if (elapsed < intervalNanos) {
                return;
            }
            double seconds = elapsed / 1_000_000_000.0;
            long windowBytes = transferred - lastLogBytes;
            double bytesPerSec = seconds > 0 ? windowBytes / seconds : 0;

            log.accept(buildLine(label, transferred, total, bytesPerSec, null));

            lastLogNanos = now;
            lastLogBytes = transferred;
        }

        /**
         * 返回累计已传字节（含续传起点），供测试断言
         *
         * @return 已传字节数
         * @author xumanyi
         * @date 2026-08-26
         */
        long transferred() {
            return transferred;
        }
    }

    /**
     * 拼一条进度行：总大小 → 已传（百分比）→ 剩余 → 速率 → 预计还需。
     *
     * <p>统一单文件与批量两种进度的文案，避免两处各写一套读起来不一样。</p>
     *
     * @param label       行首标签（含日志级别前缀）
     * @param transferred 已传字节
     * @param total       总字节，未知传 ≤0（降级为只报已传与速率）
     * @param bytesPerSec 最近一个间隔的瞬时速率
     * @param filesPart   文件计数片段（如 {@code "进度 12/48 个文件"}），无则传 null
     * @return 进度行文本
     * @author xumanyi
     * @date 2026-09-23
     */
    static String buildLine(String label, long transferred, long total,
                            double bytesPerSec, String filesPart) {
        StringBuilder line = new StringBuilder(label).append(' ');
        if (filesPart != null) {
            line.append(filesPart).append('，');
        }
        if (total > 0) {
            long remaining = Math.max(0, total - transferred);
            int percent = (int) (transferred * 100 / total);
            line.append("共 ").append(formatSize(total))
                    .append("，已传 ").append(formatSize(transferred))
                    .append(" (").append(percent).append("%)")
                    .append("，剩余 ").append(formatSize(remaining));
        } else {
            line.append("已传 ").append(formatSize(transferred));
        }
        line.append("，").append(formatSize((long) bytesPerSec)).append("/s");
        if (total > 0 && bytesPerSec > 0 && transferred < total) {
            long etaSec = (long) ((total - transferred) / bytesPerSec);
            line.append("，预计还需 ").append(formatEta(etaSec));
        }
        return line.toString();
    }

    /**
     * 创建批量传输进度器（成百个小文件逐个传的场景）
     *
     * @param label      行首标签（含日志级别前缀，如 {@code "INFO  [上传]"}）
     * @param totalBytes 本批文件总字节数，未知传 ≤0
     * @param totalFiles 本批文件总数
     * @param log        日志回调
     * @return 进度器
     * @author xumanyi
     * @date 2026-09-23
     */
    public static BatchProgress batch(String label, long totalBytes, int totalFiles,
                                      Consumer<String> log) {
        return new BatchProgress(label, totalBytes, totalFiles, log,
                LOG_INTERVAL_MS, System::nanoTime);
    }

    /**
     * 批量传输进度：按同一时间间隔节流汇报"已传 N/M 个文件 + 总字节进度"。
     *
     * <p>单文件的字节进度在"几百个小文件"场景里没有意义（每个都不足一个间隔），
     * 这里按整批累计，最后一个文件完成时无论间隔都补一条收尾行，
     * 保证小批量也能看到一次完整的总量汇报。</p>
     *
     * <p>线程模型：由单个传输线程顺序调用，无并发，不加锁。</p>
     *
     * @author xumanyi
     * @date 2026-09-23
     */
    public static final class BatchProgress {

        private final String label;
        private final long totalBytes;
        private final int totalFiles;
        private final Consumer<String> log;
        private final long intervalNanos;
        private final LongSupplier clock;

        private long doneBytes;
        private int doneFiles;
        private long lastLogNanos;
        private long lastLogBytes;

        /**
         * @param label      行首标签
         * @param totalBytes 总字节数，未知传 ≤0
         * @param totalFiles 总文件数
         * @param log        日志回调
         * @param intervalMs 进度行最小间隔（毫秒）
         * @param clock      纳秒时钟（测试可注入）
         * @author xumanyi
         * @date 2026-09-23
         */
        BatchProgress(String label, long totalBytes, int totalFiles, Consumer<String> log,
                      long intervalMs, LongSupplier clock) {
            this.label = label;
            this.totalBytes = totalBytes;
            this.totalFiles = totalFiles;
            this.log = log;
            this.intervalNanos = intervalMs * 1_000_000L;
            this.clock = clock;
            this.lastLogNanos = clock.getAsLong();
        }

        /**
         * 记一个文件传输完成，满间隔或全部完成时输出一条进度行
         *
         * @param bytes 该文件字节数（未知传 0）
         * @author xumanyi
         * @date 2026-09-23
         */
        public void onFileDone(long bytes) {
            doneBytes += Math.max(0, bytes);
            doneFiles++;
            long now = clock.getAsLong();
            long elapsed = now - lastLogNanos;
            boolean last = doneFiles >= totalFiles;
            if (elapsed < intervalNanos && !last) {
                return;
            }
            double seconds = elapsed / 1_000_000_000.0;
            double bytesPerSec = seconds > 0 ? (doneBytes - lastLogBytes) / seconds : 0;
            log.accept(buildLine(label, doneBytes, totalBytes, bytesPerSec,
                    "进度 " + doneFiles + "/" + totalFiles + " 个文件"));
            lastLogNanos = now;
            lastLogBytes = doneBytes;
        }
    }

    /**
     * 将字节数格式化为可读的大小字符串
     *
     * @param bytes 字节数
     * @return 可读的大小字符串，如 "1.5 MB"
     * @author xumanyi
     * @date 2026-08-26
     */
    static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
    }

    /**
     * 将剩余秒数格式化为可读时长
     *
     * @param seconds 剩余秒数
     * @return 可读时长，如 "45 秒" / "3 分 12 秒"
     * @author xumanyi
     * @date 2026-08-26
     */
    static String formatEta(long seconds) {
        if (seconds < 60) return seconds + " 秒";
        return (seconds / 60) + " 分 " + (seconds % 60) + " 秒";
    }
}
