package com.flux.deploy.parallel;

import com.flux.deploy.config.FailureStrategy;
import com.flux.deploy.deploy.CancellationToken;
import com.flux.deploy.ftp.FtpErrorKind;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ParallelExecutorTest {

    @Test
    void allSuccess_returnsOutcomeForEveryTarget() {
        List<String> targets = List.of("a", "b", "c", "d", "e");
        ConcurrentLinkedQueue<String> seen = new ConcurrentLinkedQueue<>();
        ParallelExecutor.Options opts = new ParallelExecutor.Options(
                3, FailureStrategy.ISOLATED, new CancellationToken.Simple(), "test");

        Map<String, TargetOutcome> outcomes = ParallelExecutor.execute(
                opts, targets, s -> s,
                (target, log) -> {
                    seen.add(target);
                    log.append("processed ").append(target);
                    return TargetOutcome.success(target, log.toString());
                });

        assertThat(outcomes).hasSize(5);
        for (String t : targets) {
            assertThat(outcomes.get(t).getStatus()).isEqualTo(TargetStatus.SUCCESS);
            assertThat(outcomes.get(t).getLogSegment()).contains("processed " + t);
        }
        assertThat(seen).containsExactlyInAnyOrderElementsOf(targets);
    }

    @Test
    void parallelism1_runsSerially() {
        AtomicInteger maxConcurrent = new AtomicInteger();
        AtomicInteger current = new AtomicInteger();
        ParallelExecutor.Options opts = new ParallelExecutor.Options(
                1, FailureStrategy.ISOLATED, new CancellationToken.Simple(), "test");

        ParallelExecutor.execute(
                opts, List.of(1, 2, 3, 4, 5), i -> "t" + i,
                (n, log) -> {
                    int now = current.incrementAndGet();
                    maxConcurrent.updateAndGet(prev -> Math.max(prev, now));
                    try { Thread.sleep(20); } catch (InterruptedException ignored) {}
                    current.decrementAndGet();
                    return TargetOutcome.success("t" + n, "");
                });

        assertThat(maxConcurrent.get()).isEqualTo(1);
    }

    @Test
    void parallelism3_actuallyRunsConcurrently() throws Exception {
        AtomicInteger maxConcurrent = new AtomicInteger();
        AtomicInteger current = new AtomicInteger();
        ParallelExecutor.Options opts = new ParallelExecutor.Options(
                3, FailureStrategy.ISOLATED, new CancellationToken.Simple(), "test");

        ParallelExecutor.execute(
                opts, List.of(1, 2, 3, 4, 5, 6), i -> "t" + i,
                (n, log) -> {
                    int now = current.incrementAndGet();
                    maxConcurrent.updateAndGet(prev -> Math.max(prev, now));
                    try { Thread.sleep(50); } catch (InterruptedException ignored) {}
                    current.decrementAndGet();
                    return TargetOutcome.success("t" + n, "");
                });

        assertThat(maxConcurrent.get()).isGreaterThan(1).isLessThanOrEqualTo(3);
    }

    @Test
    void isolatedFailure_otherTargetsKeepRunning_whenErrorIsPerPackage() {
        // Task 函数自己回滚后返回 FAILED；ParallelExecutor 看到 PROTOCOL 不传播
        ParallelExecutor.Options opts = new ParallelExecutor.Options(
                3, FailureStrategy.ISOLATED, new CancellationToken.Simple(), "test");

        Map<String, TargetOutcome> outcomes = ParallelExecutor.execute(
                opts, List.of("a", "b", "c", "d", "e"), s -> s,
                (t, log) -> {
                    if (t.equals("c")) {
                        return TargetOutcome.failed(t,
                                new java.io.IOException("550 simulated"),
                                FtpErrorKind.PROTOCOL,
                                "rolled back");
                    }
                    return TargetOutcome.success(t, "ok");
                });

        assertThat(outcomes.get("a").getStatus()).isEqualTo(TargetStatus.SUCCESS);
        assertThat(outcomes.get("b").getStatus()).isEqualTo(TargetStatus.SUCCESS);
        assertThat(outcomes.get("c").getStatus()).isEqualTo(TargetStatus.FAILED);
        assertThat(outcomes.get("d").getStatus()).isEqualTo(TargetStatus.SUCCESS);
        assertThat(outcomes.get("e").getStatus()).isEqualTo(TargetStatus.SUCCESS);
    }

    @Test
    void taskThrows_isClassifiedAndCapturedAsFailedOutcome() {
        // Task 函数抛异常（未自己处理回滚）→ ParallelExecutor 用 classify 兜底为 FAILED
        ParallelExecutor.Options opts = new ParallelExecutor.Options(
                3, FailureStrategy.ISOLATED, new CancellationToken.Simple(), "test");

        Map<String, TargetOutcome> outcomes = ParallelExecutor.execute(
                opts, List.of("a", "b"), s -> s,
                (t, log) -> {
                    if (t.equals("a")) {
                        throw new java.io.IOException("550 boom");
                    }
                    return TargetOutcome.success(t, "ok");
                });

        assertThat(outcomes.get("a").getStatus()).isEqualTo(TargetStatus.FAILED);
        assertThat(outcomes.get("a").getErrorKind()).isEqualTo(FtpErrorKind.PROTOCOL);
        assertThat(outcomes.get("a").getError()).isInstanceOf(java.io.IOException.class);
    }

    @Test
    void globalError_triggersCancelTokenAndStopsLaterTargets() {
        // parallelism=1 + a 失败 with 421 → b/c/d/e 全 CANCELLED
        ParallelExecutor.Options opts = new ParallelExecutor.Options(
                1, FailureStrategy.ISOLATED, new CancellationToken.Simple(), "test");

        Map<String, TargetOutcome> outcomes = ParallelExecutor.execute(
                opts, List.of("a", "b", "c", "d", "e"), s -> s,
                (t, log) -> {
                    if (t.equals("a")) {
                        throw new java.io.IOException("FTP 421 too many connections");
                    }
                    return TargetOutcome.success(t, "ok");
                });

        assertThat(outcomes.get("a").getStatus()).isEqualTo(TargetStatus.FAILED);
        assertThat(outcomes.get("a").getErrorKind()).isEqualTo(FtpErrorKind.SERVER_LIMIT);
        for (String t : List.of("b", "c", "d", "e")) {
            assertThat(outcomes.get(t).getStatus())
                    .as("target " + t + " should be CANCELLED after global error")
                    .isEqualTo(TargetStatus.CANCELLED);
        }
    }

    @Test
    void rollbackAllStrategy_taskFailureCancelsLaterTargets() {
        // ROLLBACK_ALL 模式下：单包失败也应触发取消（即使错误类型是 PROTOCOL）
        ParallelExecutor.Options opts = new ParallelExecutor.Options(
                1, FailureStrategy.ROLLBACK_ALL, new CancellationToken.Simple(), "test");

        Map<String, TargetOutcome> outcomes = ParallelExecutor.execute(
                opts, List.of("a", "b", "c"), s -> s,
                (t, log) -> {
                    if (t.equals("a")) {
                        throw new java.io.IOException("550 something");
                    }
                    return TargetOutcome.success(t, "ok");
                });

        assertThat(outcomes.get("a").getStatus()).isEqualTo(TargetStatus.FAILED);
        assertThat(outcomes.get("b").getStatus()).isEqualTo(TargetStatus.CANCELLED);
        assertThat(outcomes.get("c").getStatus()).isEqualTo(TargetStatus.CANCELLED);
    }

    @Test
    void preCancelled_allTargetsAreCancelled() {
        CancellationToken.Simple token = new CancellationToken.Simple();
        token.cancel();
        ParallelExecutor.Options opts = new ParallelExecutor.Options(
                3, FailureStrategy.ISOLATED, token, "test");

        Map<String, TargetOutcome> outcomes = ParallelExecutor.execute(
                opts, List.of("a", "b", "c"), s -> s,
                (t, log) -> TargetOutcome.success(t, "should not run"));

        for (String t : List.of("a", "b", "c")) {
            assertThat(outcomes.get(t).getStatus()).isEqualTo(TargetStatus.CANCELLED);
            assertThat(outcomes.get(t).getLogSegment()).isEmpty();
        }
    }

    @Test
    void logSegment_isPerTarget_andFlushedOnSuccess() {
        ParallelExecutor.Options opts = new ParallelExecutor.Options(
                3, FailureStrategy.ISOLATED, new CancellationToken.Simple(), "test");

        Map<String, TargetOutcome> outcomes = ParallelExecutor.execute(
                opts, List.of("a", "b"), s -> s,
                (t, log) -> {
                    log.append("step1 of ").append(t).append("\n");
                    log.append("step2 of ").append(t);
                    return TargetOutcome.success(t, log.toString());
                });

        assertThat(outcomes.get("a").getLogSegment())
                .contains("step1 of a")
                .contains("step2 of a")
                .doesNotContain("step1 of b");
        assertThat(outcomes.get("b").getLogSegment())
                .contains("step1 of b")
                .doesNotContain("step1 of a");
    }

    @Test
    void invalidParallelism_throws() {
        ParallelExecutor.Options opts = new ParallelExecutor.Options(
                0, FailureStrategy.ISOLATED, new CancellationToken.Simple(), "test");
        assertThatThrownBy(() ->
                ParallelExecutor.execute(opts, List.of("a"), s -> s,
                        (t, log) -> TargetOutcome.success(t, "")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("parallelism");
    }

    @Test
    void emptyTargets_returnsEmptyMap() {
        ParallelExecutor.Options opts = new ParallelExecutor.Options(
                3, FailureStrategy.ISOLATED, new CancellationToken.Simple(), "test");

        Map<String, TargetOutcome> outcomes = ParallelExecutor.execute(
                opts, List.of(), (Object s) -> s.toString(),
                (t, log) -> TargetOutcome.success("never", ""));

        assertThat(outcomes).isEmpty();
    }

    @Test
    void rollbackFailedOutcome_isPreservedThrough() {
        ParallelExecutor.Options opts = new ParallelExecutor.Options(
                3, FailureStrategy.ISOLATED, new CancellationToken.Simple(), "test");

        Map<String, TargetOutcome> outcomes = ParallelExecutor.execute(
                opts, List.of("a"), s -> s,
                (t, log) -> TargetOutcome.rollbackFailed(t,
                        new RuntimeException("rollback boom"), "log"));

        assertThat(outcomes.get("a").getStatus()).isEqualTo(TargetStatus.ROLLBACK_FAILED);
        assertThat(outcomes.get("a").isCritical()).isTrue();
    }
}
