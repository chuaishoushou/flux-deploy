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

class PipelineExecutorTest {

    /**
     * 简化的 stages 实现：每个 stage 写一行日志、可选 sleep 模拟 IO
     */
    private static class SimpleStages implements PipelineExecutor.PipelineStages<String, String, String> {
        private final long downloadMs;
        private final long embedMs;
        private final long uploadMs;
        private final ConcurrentLinkedQueue<String> events = new ConcurrentLinkedQueue<>();

        SimpleStages(long d, long e, long u) {
            this.downloadMs = d;
            this.embedMs = e;
            this.uploadMs = u;
        }

        @Override
        public String download(String target, StringBuilder log) throws Exception {
            events.add("D-start:" + target + ":" + Thread.currentThread().getName());
            log.append("D ").append(target).append('\n');
            if (downloadMs > 0) Thread.sleep(downloadMs);
            events.add("D-end:" + target);
            return "downloaded-" + target;
        }

        @Override
        public String embed(String target, String downloaded, StringBuilder log) throws Exception {
            events.add("E-start:" + target + ":" + Thread.currentThread().getName());
            log.append("E ").append(target).append(" from ").append(downloaded).append('\n');
            if (embedMs > 0) Thread.sleep(embedMs);
            events.add("E-end:" + target);
            return "embedded-" + target;
        }

        @Override
        public void upload(String target, String embedded, StringBuilder log) throws Exception {
            events.add("U-start:" + target + ":" + Thread.currentThread().getName());
            log.append("U ").append(target).append(" payload ").append(embedded).append('\n');
            if (uploadMs > 0) Thread.sleep(uploadMs);
            events.add("U-end:" + target);
        }
    }

    @Test
    void allSuccess_eachStageRunsForEachTarget() {
        SimpleStages stages = new SimpleStages(0, 0, 0);
        PipelineExecutor.Options opts = new PipelineExecutor.Options(
                2, 2, FailureStrategy.ISOLATED, new CancellationToken.Simple(), "test");

        Map<String, TargetOutcome> outcomes = PipelineExecutor.execute(
                opts, List.of("a", "b", "c"), s -> s, stages);

        assertThat(outcomes).hasSize(3);
        for (String t : List.of("a", "b", "c")) {
            assertThat(outcomes.get(t).getStatus()).isEqualTo(TargetStatus.SUCCESS);
            assertThat(outcomes.get(t).getLogSegment())
                    .contains("D " + t)
                    .contains("E " + t)
                    .contains("U " + t);
        }
        // 每个 target 都跑了完整 D/E/U（不绑定具体线程序号，并发池可能分配不同线程）
        for (String t : List.of("a", "b", "c")) {
            assertThat(stages.events).contains("D-end:" + t, "E-end:" + t, "U-end:" + t);
            // 线程名前缀至少符合 download 池命名约定
            boolean hasDOnDownloadThread = stages.events.stream()
                    .anyMatch(e -> e.startsWith("D-start:" + t + ":flux-deploy-test-download-"));
            assertThat(hasDOnDownloadThread)
                    .as("download for " + t + " should run on a download-pool thread")
                    .isTrue();
        }
    }

    @Test
    void downloadAndUploadCanOverlap_pipelineIsTrue() throws Exception {
        // D=200ms, E=10ms, U=200ms
        // 串行 3 包: 3 × 410 = 1230ms
        // 流水线 3 包（D 池=1, U 池=1）：理论 ≈ D 阶段总耗时 + 最后一个 U
        //   = 3×200 + 200 = 800ms（最理想）
        // 至少应该明显 < 串行
        SimpleStages stages = new SimpleStages(200, 10, 200);
        PipelineExecutor.Options opts = new PipelineExecutor.Options(
                1, 1, FailureStrategy.ISOLATED, new CancellationToken.Simple(), "pipe");

        long t0 = System.currentTimeMillis();
        Map<String, TargetOutcome> outcomes = PipelineExecutor.execute(
                opts, List.of("a", "b", "c"), s -> s, stages);
        long elapsed = System.currentTimeMillis() - t0;

        assertThat(outcomes).hasSize(3);
        for (String t : List.of("a", "b", "c")) {
            assertThat(outcomes.get(t).getStatus()).isEqualTo(TargetStatus.SUCCESS);
        }

        // 严格证明流水线生效：总耗时 < 全串行（1230ms）的 80%
        // 流水线允许下载-上传重叠，理论上限接近 800ms
        // 给宽容余量：判定 < 1100ms 即可证明流水线有效
        assertThat(elapsed)
                .as("pipeline should be faster than serial (3*410=1230ms); got " + elapsed + "ms")
                .isLessThan(1100L);
    }

    @Test
    void downloadFailure_skipsEmbedAndUpload_otherTargetsContinue() {
        // a 在 download 阶段失败，b/c 应正常完成
        SimpleStages stages = new SimpleStages(0, 0, 0) {
            @Override
            public String download(String target, StringBuilder log) throws Exception {
                if (target.equals("a")) {
                    throw new java.io.IOException("550 simulated download failure");
                }
                return super.download(target, log);
            }
        };
        PipelineExecutor.Options opts = new PipelineExecutor.Options(
                2, 2, FailureStrategy.ISOLATED, new CancellationToken.Simple(), "test");

        Map<String, TargetOutcome> outcomes = PipelineExecutor.execute(
                opts, List.of("a", "b", "c"), s -> s, stages);

        assertThat(outcomes.get("a").getStatus()).isEqualTo(TargetStatus.FAILED);
        assertThat(outcomes.get("a").getErrorKind()).isEqualTo(FtpErrorKind.PROTOCOL);
        // a 不应触发 E / U（cleanup 内 downloaded=null）
        assertThat(stages.events).doesNotContain("E-start:a", "U-start:a");

        // b, c 仍然成功
        assertThat(outcomes.get("b").getStatus()).isEqualTo(TargetStatus.SUCCESS);
        assertThat(outcomes.get("c").getStatus()).isEqualTo(TargetStatus.SUCCESS);
    }

    @Test
    void embedFailure_skipsUpload_otherTargetsContinue() {
        SimpleStages stages = new SimpleStages(0, 0, 0) {
            @Override
            public String embed(String target, String downloaded, StringBuilder log) throws Exception {
                if (target.equals("b")) {
                    throw new RuntimeException("embed boom");
                }
                return super.embed(target, downloaded, log);
            }
        };
        PipelineExecutor.Options opts = new PipelineExecutor.Options(
                2, 2, FailureStrategy.ISOLATED, new CancellationToken.Simple(), "test");

        Map<String, TargetOutcome> outcomes = PipelineExecutor.execute(
                opts, List.of("a", "b", "c"), s -> s, stages);

        // b 在 E 失败 → 不会进 U
        assertThat(outcomes.get("b").getStatus()).isEqualTo(TargetStatus.FAILED);
        boolean bUploadStarted = stages.events.stream().anyMatch(s -> s.startsWith("U-start:b"));
        assertThat(bUploadStarted).isFalse();

        // a, c 完整流水
        assertThat(outcomes.get("a").getStatus()).isEqualTo(TargetStatus.SUCCESS);
        assertThat(outcomes.get("c").getStatus()).isEqualTo(TargetStatus.SUCCESS);
    }

    @Test
    void uploadFailure_marksFailedAndOtherContinue() {
        SimpleStages stages = new SimpleStages(0, 0, 0) {
            @Override
            public void upload(String target, String embedded, StringBuilder log) throws Exception {
                if (target.equals("c")) {
                    throw new java.io.IOException("550 upload boom");
                }
                super.upload(target, embedded, log);
            }
        };
        PipelineExecutor.Options opts = new PipelineExecutor.Options(
                2, 2, FailureStrategy.ISOLATED, new CancellationToken.Simple(), "test");

        Map<String, TargetOutcome> outcomes = PipelineExecutor.execute(
                opts, List.of("a", "b", "c"), s -> s, stages);

        assertThat(outcomes.get("c").getStatus()).isEqualTo(TargetStatus.FAILED);
        assertThat(outcomes.get("a").getStatus()).isEqualTo(TargetStatus.SUCCESS);
        assertThat(outcomes.get("b").getStatus()).isEqualTo(TargetStatus.SUCCESS);
    }

    @Test
    void cleanupAlwaysCalled_evenOnFailure() {
        AtomicInteger cleanupCount = new AtomicInteger();
        AtomicInteger downloadedNullCount = new AtomicInteger();
        AtomicInteger embeddedNullCount = new AtomicInteger();
        SimpleStages stages = new SimpleStages(0, 0, 0) {
            @Override
            public String download(String target, StringBuilder log) throws Exception {
                if (target.equals("a")) throw new RuntimeException("d-fail");
                return super.download(target, log);
            }

            @Override
            public String embed(String target, String downloaded, StringBuilder log) throws Exception {
                if (target.equals("b")) throw new RuntimeException("e-fail");
                return super.embed(target, downloaded, log);
            }

            @Override
            public void cleanup(String target, String downloaded, String embedded) {
                cleanupCount.incrementAndGet();
                if (downloaded == null) downloadedNullCount.incrementAndGet();
                if (embedded == null) embeddedNullCount.incrementAndGet();
            }
        };
        PipelineExecutor.Options opts = new PipelineExecutor.Options(
                2, 2, FailureStrategy.ISOLATED, new CancellationToken.Simple(), "test");

        PipelineExecutor.execute(opts, List.of("a", "b", "c"), s -> s, stages);

        // 每个 target 都触发一次 cleanup
        assertThat(cleanupCount.get()).isEqualTo(3);
        // a 在 D 阶段失败 → downloaded=null, embedded=null
        // b 在 E 阶段失败 → downloaded=非null, embedded=null
        // c 全成功 → 都非 null
        assertThat(downloadedNullCount.get()).isEqualTo(1); // a
        assertThat(embeddedNullCount.get()).isEqualTo(2);   // a + b
    }

    @Test
    void globalError_triggersCancelToken() {
        // a 抛 421 → 全局错误 → 触发取消 → 后续未启动的应取消
        SimpleStages stages = new SimpleStages(50, 0, 0) {
            @Override
            public String download(String target, StringBuilder log) throws Exception {
                if (target.equals("a")) {
                    throw new java.io.IOException("421 too many connections");
                }
                return super.download(target, log);
            }
        };
        PipelineExecutor.Options opts = new PipelineExecutor.Options(
                1, 1, FailureStrategy.ISOLATED, new CancellationToken.Simple(), "test");

        Map<String, TargetOutcome> outcomes = PipelineExecutor.execute(
                opts, List.of("a", "b", "c"), s -> s, stages);

        assertThat(outcomes.get("a").getStatus()).isEqualTo(TargetStatus.FAILED);
        assertThat(outcomes.get("a").getErrorKind()).isEqualTo(FtpErrorKind.SERVER_LIMIT);
        // download 池=1 → b/c 在 a 之后排队 → a 抛 SERVER_LIMIT → token cancel → b/c 在 download 入口被取消
        for (String t : List.of("b", "c")) {
            assertThat(outcomes.get(t).getStatus())
                    .as("target " + t + " should be CANCELLED after global error")
                    .isEqualTo(TargetStatus.CANCELLED);
        }
    }

    @Test
    void preCancelled_allTargetsCancelled() {
        CancellationToken.Simple token = new CancellationToken.Simple();
        token.cancel();
        SimpleStages stages = new SimpleStages(0, 0, 0);
        PipelineExecutor.Options opts = new PipelineExecutor.Options(
                2, 2, FailureStrategy.ISOLATED, token, "test");

        Map<String, TargetOutcome> outcomes = PipelineExecutor.execute(
                opts, List.of("a", "b"), s -> s, stages);

        for (String t : List.of("a", "b")) {
            assertThat(outcomes.get(t).getStatus()).isEqualTo(TargetStatus.CANCELLED);
        }
    }

    @Test
    void invalidParallelism_throws() {
        PipelineExecutor.Options opts = new PipelineExecutor.Options(
                0, 1, FailureStrategy.ISOLATED, new CancellationToken.Simple(), "test");
        assertThatThrownBy(() -> PipelineExecutor.execute(
                opts, List.of("a"), s -> s, new SimpleStages(0, 0, 0)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("downloadParallelism");
    }

    @Test
    void emptyTargets_returnsEmptyMap() {
        PipelineExecutor.Options opts = new PipelineExecutor.Options(
                2, 2, FailureStrategy.ISOLATED, new CancellationToken.Simple(), "test");
        Map<String, TargetOutcome> outcomes = PipelineExecutor.execute(
                opts, List.of(), (Object s) -> s.toString(),
                new PipelineExecutor.PipelineStages<Object, Object, Object>() {
                    public Object download(Object t, StringBuilder l) { return null; }
                    public Object embed(Object t, Object d, StringBuilder l) { return null; }
                    public void upload(Object t, Object e, StringBuilder l) {}
                });
        assertThat(outcomes).isEmpty();
    }

    @Test
    void threadNamingIsStageAware() {
        // 验证三个池的线程命名互不相同
        ConcurrentLinkedQueue<String> dThreads = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<String> eThreads = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<String> uThreads = new ConcurrentLinkedQueue<>();
        PipelineExecutor.PipelineStages<String, String, String> stages =
                new PipelineExecutor.PipelineStages<>() {
                    public String download(String t, StringBuilder l) {
                        dThreads.add(Thread.currentThread().getName());
                        return t;
                    }
                    public String embed(String t, String d, StringBuilder l) {
                        eThreads.add(Thread.currentThread().getName());
                        return t;
                    }
                    public void upload(String t, String e, StringBuilder l) {
                        uThreads.add(Thread.currentThread().getName());
                    }
                };
        PipelineExecutor.Options opts = new PipelineExecutor.Options(
                1, 1, FailureStrategy.ISOLATED, new CancellationToken.Simple(), "naming");

        PipelineExecutor.execute(opts, List.of("a"), s -> s, stages);

        assertThat(dThreads).allSatisfy(n -> assertThat(n).contains("flux-deploy-naming-download"));
        assertThat(eThreads).allSatisfy(n -> assertThat(n).contains("flux-deploy-naming-embed"));
        assertThat(uThreads).allSatisfy(n -> assertThat(n).contains("flux-deploy-naming-upload"));
    }
}
