package com.flux.deploy.parallel;

import com.flux.deploy.config.FailureStrategy;
import com.flux.deploy.deploy.CancellationToken;
import com.flux.deploy.ftp.FtpErrorClassifier;
import com.flux.deploy.ftp.FtpErrorKind;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * 三阶段流水线执行器（download → embed → upload）
 *
 * <p>用于带宽受限、上下行通道独立的场景：把同方向 IO 拆到独立线程池，
 * 让"下载"和"上传"在时间上重叠，提升带宽利用率。</p>
 *
 * <p>与 {@link ParallelExecutor} 的差异：</p>
 * <ul>
 *   <li>ParallelExecutor: 每个 worker 顺序跑 D→E→U，多 worker 同时启动 → 同方向饱和</li>
 *   <li>PipelineExecutor: 把 D / E / U 拆到 3 个独立池，下载和上传可以重叠</li>
 * </ul>
 *
 * <p>安全保证：</p>
 * <ul>
 *   <li>每个目标走独立的 D-E-U（不复用任何嵌入产物，每个目标必须自己下载、自己嵌入、自己上传）</li>
 *   <li>跨 stage 资源（如 tempDir）由 {@link PipelineStages#cleanup} 在 target 完成时统一清理，
 *       无论 target 成功 / 失败 / 取消</li>
 *   <li>D / E / U 任一阶段失败 → ISOLATED 模式下单包标记 FAILED，由调用方决定是否做单包回滚</li>
 *   <li>三个池均在 finally 中 shutdown + awaitTermination，不强中断 IO</li>
 * </ul>
 *
 * @author xumanyi
 * @date 2026-05-02
 */
public final class PipelineExecutor {

    /** 线程池等待终止超时秒数 */
    private static final long SHUTDOWN_TIMEOUT_SECONDS = 60L;

    private PipelineExecutor() {
    }

    /**
     * 嵌入池规模常量。
     *
     * <p>本地补丁是秒级 CPU + 磁盘操作，并发处理无收益（瓶颈在 FTP 上传）。
     * 流水线的 embed 阶段始终单线程串行，避免一组生产者-消费者队列与并发副作用。</p>
     */
    private static final int EMBED_POOL_SIZE = 1;

    /**
     * 执行选项
     *
     * @param downloadParallelism 下载池并发数（占用下行带宽，≥ 1）
     * @param uploadParallelism   上传池并发数（占用上行带宽，≥ 1）
     * @param strategy            失败策略（决定单包失败是否传播取消）
     * @param token               取消令牌（用户主动停止）
     * @param phaseName           阶段名（用于线程命名 flux-deploy-&lt;phaseName&gt;-&lt;stage&gt;-&lt;seq&gt;）
     * @param realTimeSink        实时日志输出回调，{@code null} 时落到 outcome.logSegment 由调用方统一 flush
     * @author xumanyi
     * @date 2026-05-02
     */
    public record Options(
            int downloadParallelism,
            int uploadParallelism,
            FailureStrategy strategy,
            CancellationToken token,
            String phaseName,
            Consumer<String> realTimeSink
    ) {
        public Options {
            Objects.requireNonNull(strategy, "strategy");
            Objects.requireNonNull(token, "token");
            Objects.requireNonNull(phaseName, "phaseName");
            // realTimeSink 允许为 null：兼容旧调用，仍保留单包日志聚合到 outcome.logSegment
        }

        /** 未传 realTimeSink 时按 null 处理（即不开启实时 flush）。 */
        public Options(int downloadParallelism,
                       int uploadParallelism,
                       FailureStrategy strategy,
                       CancellationToken token,
                       String phaseName) {
            this(downloadParallelism, uploadParallelism, strategy, token, phaseName, null);
        }
    }

    /**
     * 三阶段任务接口
     *
     * <p>调用方负责把"端到端嵌入逻辑"拆成 download / embed / upload 三段，
     * 由 PipelineExecutor 在不同的池中调度执行，实现上下行重叠。</p>
     *
     * @param <T> 目标类型（如 FtpTargetSelection）
     * @param <D> download 阶段输出类型（通常封装 tempDir + downloadedFile）
     * @param <E> embed 阶段输出类型（通常封装 tempDir + outputFile）
     * @author xumanyi
     * @date 2026-05-02
     */
    public interface PipelineStages<T, D, E> {
        /**
         * 下载阶段：从远端拉取目标文件到本地
         *
         * @param target 当前目标
         * @param log    单包日志 buffer，调用方追加内容由执行器统一 flush
         * @return 下载结果，传给 embed 阶段
         * @throws Exception IO 失败 / 校验失败
         * @author xumanyi
         * @date 2026-05-02
         */
        D download(T target, StringBuilder log) throws Exception;

        /**
         * 嵌入阶段：在本地处理下载结果，生成待上传的产物
         *
         * @param target     当前目标
         * @param downloaded download 阶段的输出
         * @param log        单包日志 buffer
         * @return 嵌入结果，传给 upload 阶段
         * @throws Exception 嵌入失败
         * @author xumanyi
         * @date 2026-05-02
         */
        E embed(T target, D downloaded, StringBuilder log) throws Exception;

        /**
         * 上传阶段：把嵌入产物推送到远端目标位置
         *
         * @param target   当前目标
         * @param embedded embed 阶段的输出
         * @param log      单包日志 buffer
         * @throws Exception IO 失败 / 校验失败
         * @author xumanyi
         * @date 2026-05-02
         */
        void upload(T target, E embedded, StringBuilder log) throws Exception;

        /**
         * 资源清理钩子：每个 target 处理完毕后调用（无论成功 / 失败 / 取消）
         *
         * <p>用于清理 D / E 阶段创建的临时资源（如 tempDir）。
         * downloaded / embedded 可能为 null（前置 stage 失败时）。</p>
         *
         * @param target     当前目标
         * @param downloaded download 阶段的输出（可能为 null）
         * @param embedded   embed 阶段的输出（可能为 null）
         * @author xumanyi
         * @date 2026-05-02
         */
        default void cleanup(T target, D downloaded, E embedded) {
            // default no-op
        }
    }

    /**
     * 流水线执行
     *
     * @param opts      执行选项
     * @param targets   目标列表（按 keyMapper 生成的 key 索引返回 outcomes）
     * @param keyMapper 目标 → 唯一标识
     * @param stages    三阶段任务实现
     * @param <T>       目标类型
     * @param <D>       download 阶段输出类型
     * @param <E>       embed 阶段输出类型
     * @return Map&lt;targetKey, Outcome&gt;，输入顺序保留
     * @throws IllegalArgumentException 任一并发数 &lt; 1
     * @author xumanyi
     * @date 2026-05-02
     */
    public static <T, D, E> Map<String, TargetOutcome> execute(
            Options opts,
            List<T> targets,
            Function<T, String> keyMapper,
            PipelineStages<T, D, E> stages) {
        Objects.requireNonNull(opts, "opts");
        Objects.requireNonNull(targets, "targets");
        Objects.requireNonNull(keyMapper, "keyMapper");
        Objects.requireNonNull(stages, "stages");
        validateParallelism("downloadParallelism", opts.downloadParallelism());
        validateParallelism("uploadParallelism", opts.uploadParallelism());

        Map<String, TargetOutcome> outcomes = new LinkedHashMap<>();
        for (T t : targets) outcomes.put(keyMapper.apply(t), null);
        if (targets.isEmpty()) return outcomes;

        AtomicInteger dSeq = new AtomicInteger();
        AtomicInteger eSeq = new AtomicInteger();
        AtomicInteger uSeq = new AtomicInteger();
        ThreadFactory dTf = nameFactory(opts.phaseName(), "download", dSeq);
        ThreadFactory eTf = nameFactory(opts.phaseName(), "embed", eSeq);
        ThreadFactory uTf = nameFactory(opts.phaseName(), "upload", uSeq);

        ExecutorService dPool = Executors.newFixedThreadPool(
                Math.min(opts.downloadParallelism(), targets.size()), dTf);
        // 嵌入池规模写死为 1：本地补丁是秒级操作，并发处理无收益（详见 EMBED_POOL_SIZE 注释）
        ExecutorService ePool = Executors.newFixedThreadPool(
                Math.min(EMBED_POOL_SIZE, targets.size()), eTf);
        ExecutorService uPool = Executors.newFixedThreadPool(
                Math.min(opts.uploadParallelism(), targets.size()), uTf);
        try {
            CompletableFuture<?>[] futures = new CompletableFuture<?>[targets.size()];
            for (int i = 0; i < targets.size(); i++) {
                T target = targets.get(i);
                String key = keyMapper.apply(target);
                futures[i] = runOneTarget(opts, target, key, stages, outcomes, dPool, ePool, uPool);
            }
            CompletableFuture.allOf(futures).join();
        } finally {
            shutdownPool(dPool, "download");
            shutdownPool(ePool, "embed");
            shutdownPool(uPool, "upload");
        }

        // 防御：未填充 outcome 的视为 CANCELLED（理论上不会发生）
        synchronized (outcomes) {
            for (Map.Entry<String, TargetOutcome> e : outcomes.entrySet()) {
                if (e.getValue() == null) {
                    e.setValue(TargetOutcome.cancelled(e.getKey(), ""));
                }
            }
        }
        return outcomes;
    }

    /**
     * 启动单个目标的 D→E→U 流水线
     *
     * <p>把三个阶段串接为 CompletableFuture 链，并把成败汇总到 outcomes Map。
     * 各阶段在对应的 ExecutorService 上调度，CompletableFuture.thenApplyAsync
     * 会在前置 future 完成时自动触发后续 stage，并在异常时跳过后续。</p>
     *
     * @param opts     执行选项
     * @param target   当前目标
     * @param key      目标 key
     * @param stages   三阶段任务实现
     * @param outcomes 共享 outcome map（写入时同步）
     * @param dPool    下载池
     * @param ePool    嵌入池
     * @param uPool    上传池
     * @param <T>      目标类型
     * @param <D>      download 输出类型
     * @param <E>      embed 输出类型
     * @return 完成时填充 outcomes 的 future
     * @author xumanyi
     * @date 2026-05-02
     */
    @SuppressWarnings("unchecked")
    private static <T, D, E> CompletableFuture<Void> runOneTarget(
            Options opts, T target, String key,
            PipelineStages<T, D, E> stages,
            Map<String, TargetOutcome> outcomes,
            ExecutorService dPool, ExecutorService ePool, ExecutorService uPool) {

        StringBuilder log = new StringBuilder();
        // 已实时 flush 到 sink 的字符位置，stage 完成时把新增片段按整行推给 sink
        int[] flushedTo = {0};
        long startMs = System.currentTimeMillis();
        // 状态容器：用数组承载 D/E 中间结果，便于在 cleanup / handle 中访问
        Object[] state = new Object[2]; // [0]=downloaded, [1]=embedded

        CompletableFuture<D> downloadFuture = CompletableFuture.supplyAsync(() -> {
            checkCancelled(opts.token());
            try {
                D d = stages.download(target, log);
                state[0] = d;
                return d;
            } catch (Exception e) {
                throw new CompletionWrapper(e);
            } finally {
                flushDelta(log, flushedTo, opts.realTimeSink());
            }
        }, dPool);

        CompletableFuture<E> embedFuture = downloadFuture.thenApplyAsync(d -> {
            checkCancelled(opts.token());
            try {
                E e = stages.embed(target, d, log);
                state[1] = e;
                return e;
            } catch (Exception ex) {
                throw new CompletionWrapper(ex);
            } finally {
                flushDelta(log, flushedTo, opts.realTimeSink());
            }
        }, ePool);

        CompletableFuture<Void> uploadFuture = embedFuture.thenAcceptAsync(e -> {
            checkCancelled(opts.token());
            try {
                stages.upload(target, e, log);
            } catch (Exception ex) {
                throw new CompletionWrapper(ex);
            } finally {
                flushDelta(log, flushedTo, opts.realTimeSink());
            }
        }, uPool);

        return uploadFuture.handle((v, t) -> {
            // 无论成败都先做资源清理
            try {
                stages.cleanup(target, (D) state[0], (E) state[1]);
            } catch (Throwable cleanupErr) {
                log.append("WARN  [嵌入] cleanup 抛出异常：").append(cleanupErr.getMessage()).append('\n');
            }

            long elapsedMs = System.currentTimeMillis() - startMs;
            String elapsedStr = String.format(Locale.ROOT, "%.1f", elapsedMs / 1000.0);

            TargetOutcome out;
            // 启用了实时 flush 的调用方：单包日志已边产边推给 sink，outcome.logSegment 留空避免重复
            boolean realTime = opts.realTimeSink() != null;
            if (t == null) {
                String diagLine = "INFO  [嵌入] " + key + " 完成，耗时 " + elapsedStr + "s\n";
                log.append(diagLine);
                out = TargetOutcome.success(key, realTime ? "" : log.toString());
            } else {
                Throwable rootCause = unwrap(t);
                if (rootCause instanceof CancellationToken.CancellationException) {
                    String diagLine = "WARN  [嵌入] " + key + " 已取消，耗时 " + elapsedStr + "s\n";
                    log.append(diagLine);
                    out = TargetOutcome.cancelled(key, realTime ? "" : log.toString());
                } else {
                    FtpErrorKind kind = FtpErrorClassifier.classify(rootCause);
                    String stage = inferFailedStage(state);
                    String reason = rootCause.getMessage() != null
                            ? rootCause.getMessage() : rootCause.getClass().getSimpleName();
                    String diagLine = "ERROR [嵌入] " + key + " " + stage + "失败，耗时 "
                            + elapsedStr + "s，原因：" + reason + "\n";
                    log.append(diagLine);
                    out = TargetOutcome.failed(key, rootCause, kind, realTime ? "" : log.toString());
                    if (decideCancel(opts.strategy(), kind)) {
                        tryCancel(opts.token());
                    }
                }
            }

            // 把 handle 阶段追加的诊断行也实时 flush 出去
            flushDelta(log, flushedTo, opts.realTimeSink());

            synchronized (outcomes) {
                outcomes.put(key, out);
            }
            return null;
        });
    }

    /**
     * 把 log 缓冲中尚未 flush 的整行片段实时推给 sink
     *
     * <p>若 sink 为 {@code null}（旧调用），则只推进 flushedTo 索引，等同于禁用实时 flush。
     * 仅 flush 包含完整换行的片段；末尾未换行的尾巴留待下次。</p>
     *
     * @param log       共享日志缓冲
     * @param flushedTo 一元数组承载已 flush 字符位置（in/out 参数）
     * @param sink      实时输出回调，可为 {@code null}
     * @author xumanyi
     * @date 2026-05-07
     */
    private static void flushDelta(StringBuilder log, int[] flushedTo, Consumer<String> sink) {
        if (sink == null) return;
        int len;
        String chunk;
        synchronized (log) {
            len = log.length();
            if (len <= flushedTo[0]) return;
            int lastNewline = log.lastIndexOf("\n", len - 1);
            if (lastNewline < flushedTo[0]) return;
            chunk = log.substring(flushedTo[0], lastNewline + 1);
            flushedTo[0] = lastNewline + 1;
        }
        for (String line : chunk.split("\n", -1)) {
            if (!line.isEmpty()) sink.accept(line);
        }
    }

    /**
     * 根据 D/E 中间结果的填充情况推断失败发生在哪个阶段
     *
     * <p>state[0]=downloaded, state[1]=embedded：</p>
     * <ul>
     *   <li>downloaded == null → 失败发生在下载阶段</li>
     *   <li>downloaded != null && embedded == null → 失败发生在嵌入阶段</li>
     *   <li>downloaded != null && embedded != null → 失败发生在上传阶段</li>
     * </ul>
     *
     * @param state 中间状态数组 [downloaded, embedded]
     * @return "下载阶段" / "嵌入阶段" / "上传阶段"
     * @author xumanyi
     * @date 2026-05-02
     */
    private static String inferFailedStage(Object[] state) {
        if (state[0] == null) return "下载阶段";
        if (state[1] == null) return "嵌入阶段";
        return "上传阶段";
    }

    /**
     * 创建带语义命名的线程工厂
     *
     * @param phaseName 阶段名（如 "embed"）
     * @param stageName 子阶段（"download" / "embed" / "upload"）
     * @param seq       全局自增序号
     * @return 线程工厂
     * @author xumanyi
     * @date 2026-05-02
     */
    private static ThreadFactory nameFactory(String phaseName, String stageName, AtomicInteger seq) {
        return r -> {
            Thread th = new Thread(r,
                    "flux-deploy-" + phaseName + "-" + stageName + "-" + seq.incrementAndGet());
            th.setDaemon(true);
            return th;
        };
    }

    /**
     * 校验并发数 ≥ 1
     *
     * @param fieldName 字段名
     * @param value     值
     * @throws IllegalArgumentException &lt; 1
     * @author xumanyi
     * @date 2026-05-02
     */
    private static void validateParallelism(String fieldName, int value) {
        if (value < 1) {
            throw new IllegalArgumentException(fieldName + " 必须 ≥ 1，当前: " + value);
        }
    }

    /**
     * 关闭线程池：shutdown + awaitTermination，不强中断
     *
     * @param pool      线程池
     * @param stageName 阶段名（用于警告日志）
     * @author xumanyi
     * @date 2026-05-02
     */
    private static void shutdownPool(ExecutorService pool, String stageName) {
        pool.shutdown();
        try {
            if (!pool.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                System.err.println("[PipelineExecutor] " + stageName + " 池在 "
                        + SHUTDOWN_TIMEOUT_SECONDS + "s 内未完全终止");
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 在每个 stage 入口检查取消令牌，已取消则抛 CancellationException 中断流水线
     *
     * @param token 取消令牌
     * @throws CancellationToken.CancellationException 已取消
     * @author xumanyi
     * @date 2026-05-02
     */
    private static void checkCancelled(CancellationToken token) {
        if (token.isCancelled()) {
            throw new CancellationToken.CancellationException();
        }
    }

    /**
     * 决定本次失败是否触发取消令牌
     *
     * @param strategy 失败策略
     * @param kind     错误分类
     * @return true 表示应触发 cancel
     * @author xumanyi
     * @date 2026-05-02
     */
    private static boolean decideCancel(FailureStrategy strategy, FtpErrorKind kind) {
        if (strategy == FailureStrategy.ROLLBACK_ALL
                || strategy == FailureStrategy.KEEP_SUCCEEDED) {
            return true;
        }
        return FtpErrorClassifier.isGlobal(kind);
    }

    /**
     * 触发取消令牌（仅 {@link CancellationToken.Simple} 暴露 cancel 方法）
     *
     * @param token 取消令牌
     * @author xumanyi
     * @date 2026-05-02
     */
    private static void tryCancel(CancellationToken token) {
        if (token instanceof CancellationToken.Simple simple) {
            simple.cancel();
        }
    }

    /**
     * 把可能被 CompletionException / CompletionWrapper 包装的异常剥到根因
     *
     * @param t 原始异常
     * @return 根因
     * @author xumanyi
     * @date 2026-05-02
     */
    private static Throwable unwrap(Throwable t) {
        Throwable cur = t;
        while (cur != null) {
            if (cur instanceof CompletionWrapper && cur.getCause() != null) {
                cur = cur.getCause();
                continue;
            }
            if (cur instanceof java.util.concurrent.CompletionException && cur.getCause() != null) {
                cur = cur.getCause();
                continue;
            }
            return cur;
        }
        return t;
    }

    /**
     * 把 epoch 毫秒格式化为 HH:mm:ss
     *
     * @param epochMs epoch 毫秒
     * @return HH:mm:ss
     * @author xumanyi
     * @date 2026-05-02
     */
    private static String formatHms(long epochMs) {
        return java.time.LocalTime
                .ofInstant(java.time.Instant.ofEpochMilli(epochMs), java.time.ZoneId.systemDefault())
                .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"));
    }

    /**
     * 内部异常包装类：把 stage 函数抛的 checked 异常运送过 CompletableFuture 链
     *
     * @author xumanyi
     * @date 2026-05-02
     */
    private static final class CompletionWrapper extends RuntimeException {
        CompletionWrapper(Throwable cause) {
            super(cause);
        }
    }
}
