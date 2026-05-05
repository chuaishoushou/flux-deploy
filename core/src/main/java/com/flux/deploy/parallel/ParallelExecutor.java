package com.flux.deploy.parallel;

import com.flux.deploy.config.FailureStrategy;
import com.flux.deploy.deploy.CancellationToken;
import com.flux.deploy.ftp.FtpErrorClassifier;
import com.flux.deploy.ftp.FtpErrorKind;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * 通用并行执行器
 *
 * <p>给定 List&lt;T&gt;、{@link Options}、单任务函数（接受 T + 单包日志 buffer，返回 {@link TargetOutcome}），
 * 在线程池中并发执行并返回 {@code Map<targetKey, TargetOutcome>}（保留输入顺序）。</p>
 *
 * <p>核心特性：</p>
 * <ul>
 *   <li>每个目标独立日志 buffer，任务完成时回写到 outcome，避免并发交织</li>
 *   <li>取消令牌前置检查：任务启动前若 token 已 cancelled → 直接 CANCELLED outcome</li>
 *   <li>异常自动分类：任务函数抛出的 {@code Throwable} 经 {@link FtpErrorClassifier} 分类，
 *       挂到 outcome.errorKind</li>
 *   <li>失败传播策略：
 *     <ul>
 *       <li>ISOLATED + 单包错误（PROTOCOL）→ 不传播，其他任务继续</li>
 *       <li>ISOLATED + 全局错误（SERVER_LIMIT/AUTH/NETWORK）→ 触发 token，未启动任务 CANCELLED</li>
 *       <li>ROLLBACK_ALL / KEEP_SUCCEEDED → 任一失败都触发 token</li>
 *     </ul>
 *   </li>
 *   <li>线程池在 finally 中 shutdown + awaitTermination（不强中断 FTP 数据传输）</li>
 *   <li>线程命名：{@code flux-deploy-<phaseName>-<seq>}，便于堆栈调试</li>
 * </ul>
 *
 * <p>线程安全：本类是无状态工具类，多次调用 execute 可安全并发。</p>
 *
 * @author claude
 * @date 2026-05-02
 */
public final class ParallelExecutor {

    /** 线程池等待终止超时秒数 */
    private static final long SHUTDOWN_TIMEOUT_SECONDS = 60L;

    private ParallelExecutor() {
    }

    /**
     * 执行选项
     *
     * @param parallelism 并发数（必须 ≥ 1）
     * @param strategy    失败策略，决定是否在单包失败时触发取消
     * @param token       取消令牌；执行过程中可能被本类调用 cancel()（仅 Simple 实现支持）
     * @param phaseName   阶段名，用于线程命名（如 "backup" / "embed"）
     * @author claude
     * @date 2026-05-02
     */
    public record Options(
            int parallelism,
            FailureStrategy strategy,
            CancellationToken token,
            String phaseName
    ) {
        public Options {
            Objects.requireNonNull(strategy, "strategy");
            Objects.requireNonNull(token, "token");
            Objects.requireNonNull(phaseName, "phaseName");
        }
    }

    /**
     * 单任务执行函数
     *
     * @param <T> 目标类型
     * @author claude
     * @date 2026-05-02
     */
    @FunctionalInterface
    public interface TaskFn<T> {
        /**
         * 执行单个目标
         *
         * @param target 当前目标
         * @param log    该目标专属的日志 buffer，调用方追加内容后由执行器写入 outcome.logSegment
         * @return 目标的执行结果（不可为 null）
         * @throws Exception 任意异常会被 {@link FtpErrorClassifier} 分类后挂在 FAILED outcome 上
         * @author claude
         * @date 2026-05-02
         */
        TargetOutcome run(T target, StringBuilder log) throws Exception;
    }

    /**
     * 并行执行所有目标
     *
     * @param opts      执行选项
     * @param targets   目标列表（输入顺序保留在返回 Map 的 LinkedHashMap 中）
     * @param keyMapper 目标 → 唯一标识（同一批内不应重复）
     * @param task      单任务函数
     * @param <T>       目标类型
     * @return {@code Map<targetKey, Outcome>}；空目标列表返回空 Map
     * @throws IllegalArgumentException parallelism &lt; 1
     * @author claude
     * @date 2026-05-02
     */
    public static <T> Map<String, TargetOutcome> execute(
            Options opts,
            List<T> targets,
            Function<T, String> keyMapper,
            TaskFn<T> task) {
        Objects.requireNonNull(opts, "opts");
        Objects.requireNonNull(targets, "targets");
        Objects.requireNonNull(keyMapper, "keyMapper");
        Objects.requireNonNull(task, "task");
        if (opts.parallelism() < 1) {
            throw new IllegalArgumentException(
                    "parallelism 必须 ≥ 1，当前: " + opts.parallelism());
        }

        // LinkedHashMap 保留输入顺序；并发写入时统一加锁
        Map<String, TargetOutcome> outcomes = new LinkedHashMap<>();
        for (T t : targets) {
            outcomes.put(keyMapper.apply(t), null);
        }
        if (targets.isEmpty()) {
            return outcomes;
        }

        AtomicInteger seq = new AtomicInteger();
        ThreadFactory tf = r -> {
            Thread th = new Thread(r,
                    "flux-deploy-" + opts.phaseName() + "-" + seq.incrementAndGet());
            th.setDaemon(true);
            return th;
        };

        int workers = Math.min(opts.parallelism(), targets.size());
        ExecutorService executor = Executors.newFixedThreadPool(workers, tf);
        try {
            CompletableFuture<?>[] futures = new CompletableFuture<?>[targets.size()];
            for (int i = 0; i < targets.size(); i++) {
                final T target = targets.get(i);
                final String key = keyMapper.apply(target);
                futures[i] = CompletableFuture.runAsync(
                        () -> runOne(opts, target, key, task, outcomes),
                        executor);
            }
            // join 所有 future；exceptionally 已在 runOne 内吞掉
            CompletableFuture.allOf(futures).join();
        } finally {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    System.err.println("[ParallelExecutor] 线程池在 "
                            + SHUTDOWN_TIMEOUT_SECONDS + "s 内未完全终止");
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }

        // 防御：未填充 outcome 的目标视为 CANCELLED（理论上不会发生）
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
     * 单任务运行体（在线程池里被调用）
     *
     * @param opts     执行选项
     * @param target   当前目标
     * @param key      目标唯一标识
     * @param task     单任务函数
     * @param outcomes 共享 outcome Map（写入时同步）
     * @param <T>      目标类型
     * @author claude
     * @date 2026-05-02
     */
    private static <T> void runOne(Options opts, T target, String key,
                                    TaskFn<T> task,
                                    Map<String, TargetOutcome> outcomes) {
        StringBuilder log = new StringBuilder();
        // 记录 worker 线程名 + 起始时刻，便于在 flush 后用户从日志判断并发是否生效
        // （5 个 worker 起始时间相近 + 各自耗时 ≈ 总耗时 = 真并行；起始顺序递增 = 串行）
        long startMs = System.currentTimeMillis();
        String workerName = Thread.currentThread().getName();

        // 任务启动前再次检查取消令牌（覆盖 pre-cancelled 与跑时被其他任务取消两种）
        if (opts.token().isCancelled()) {
            putOutcome(outcomes, key, TargetOutcome.cancelled(key, log.toString()));
            return;
        }

        log.append("[并行] ").append(workerName)
                .append(" 开始: ").append(formatHms(startMs)).append('\n');

        TargetOutcome out;
        try {
            TargetOutcome ret = task.run(target, log);
            out = (ret != null) ? ret : TargetOutcome.success(key, log.toString());
        } catch (Throwable t) {
            FtpErrorKind kind = FtpErrorClassifier.classify(t);
            out = TargetOutcome.failed(key, t, kind, log.toString());
        }

        // 追加完成耗时诊断行（不依赖 EDT 时间戳，便于用户判断真实并发）
        long elapsedMs = System.currentTimeMillis() - startMs;
        String diagLine = "[并行] " + workerName
                + " 完成: " + formatHms(System.currentTimeMillis())
                + " (耗时 " + String.format(java.util.Locale.ROOT, "%.1f", elapsedMs / 1000.0) + "s)\n";
        out = augmentLogSegment(out, diagLine);

        // 处理失败传播：根据 strategy + errorKind 决定是否触发 token cancel
        if (out.getStatus() == TargetStatus.FAILED) {
            boolean shouldCancel = decideCancel(opts.strategy(), out.getErrorKind());
            if (shouldCancel) {
                tryCancel(opts.token());
            }
        }

        putOutcome(outcomes, key, out);
    }

    /**
     * 把附加日志行追加到 outcome 的 logSegment 末尾，返回新的 outcome
     *
     * <p>{@link TargetOutcome} 是不可变对象；本方法按 status 重建一个含合并 log 的新实例。</p>
     *
     * @param o        原 outcome
     * @param appendix 要追加的日志（含换行）
     * @return 新 outcome，若 status 不识别则返回原对象
     * @author claude
     * @date 2026-05-02
     */
    private static TargetOutcome augmentLogSegment(TargetOutcome o, String appendix) {
        String newLog = o.getLogSegment() + appendix;
        switch (o.getStatus()) {
            case SUCCESS:
                return TargetOutcome.success(o.getTargetKey(), newLog);
            case FAILED:
                return TargetOutcome.failed(o.getTargetKey(), o.getError(), o.getErrorKind(), newLog);
            case ROLLBACK_FAILED:
                return TargetOutcome.rollbackFailed(o.getTargetKey(), o.getError(), newLog);
            case CANCELLED:
                return TargetOutcome.cancelled(o.getTargetKey(), newLog);
            case SKIPPED:
                return TargetOutcome.skipped(o.getTargetKey(), newLog);
            default:
                return o;
        }
    }

    /**
     * 把 epoch 毫秒格式化为 HH:mm:ss
     *
     * @param epochMs epoch 毫秒
     * @return HH:mm:ss
     * @author claude
     * @date 2026-05-02
     */
    private static String formatHms(long epochMs) {
        return java.time.LocalTime
                .ofInstant(java.time.Instant.ofEpochMilli(epochMs), java.time.ZoneId.systemDefault())
                .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"));
    }

    /**
     * 决定本次失败是否触发取消令牌
     *
     * @param strategy 失败策略
     * @param kind     错误分类（可能为 null，例如 task 函数自己构造的 failed outcome 没指定 kind）
     * @return true 表示应触发 cancel
     * @author claude
     * @date 2026-05-02
     */
    private static boolean decideCancel(FailureStrategy strategy, FtpErrorKind kind) {
        if (strategy == FailureStrategy.ROLLBACK_ALL
                || strategy == FailureStrategy.KEEP_SUCCEEDED) {
            return true;
        }
        // ISOLATED：仅全局错误传播
        return FtpErrorClassifier.isGlobal(kind);
    }

    /**
     * 触发取消令牌（仅 {@link CancellationToken.Simple} 暴露 cancel 方法）
     *
     * @param token 取消令牌
     * @author claude
     * @date 2026-05-02
     */
    private static void tryCancel(CancellationToken token) {
        if (token instanceof CancellationToken.Simple simple) {
            simple.cancel();
        }
    }

    /**
     * 同步写入 outcome Map
     *
     * @param outcomes 共享 Map
     * @param key      目标 key
     * @param out      outcome
     * @author claude
     * @date 2026-05-02
     */
    private static void putOutcome(Map<String, TargetOutcome> outcomes,
                                    String key, TargetOutcome out) {
        synchronized (outcomes) {
            outcomes.put(key, out);
        }
    }
}
