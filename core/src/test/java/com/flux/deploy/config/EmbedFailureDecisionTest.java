package com.flux.deploy.config;

import org.junit.jupiter.api.Test;

import static com.flux.deploy.config.EmbedFailureDecision.ABORT_BATCH;
import static com.flux.deploy.config.EmbedFailureDecision.CONTINUE_ISOLATED;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * EmbedFailureDecision 单元测试。
 *
 * <p>覆盖矩阵（策略 × 用户是否停止）：</p>
 * <pre>
 *                       userStop=false        userStop=true
 *  ISOLATED             CONTINUE_ISOLATED     ABORT_BATCH
 *  ROLLBACK_ALL         ABORT_BATCH           ABORT_BATCH
 *  KEEP_SUCCEEDED       ABORT_BATCH           ABORT_BATCH
 *  null                 CONTINUE_ISOLATED     ABORT_BATCH
 * </pre>
 *
 * @author xumanyi
 * @date 2026-05-04
 */
class EmbedFailureDecisionTest {

    @Test
    void isolated_withoutUserStop_continues() {
        assertEquals(CONTINUE_ISOLATED,
                EmbedFailureDecision.decide(FailureStrategy.ISOLATED, false));
    }

    @Test
    void isolated_withUserStop_aborts() {
        // 用户主动停止时，无视 ISOLATED 策略，应一律中止整批
        assertEquals(ABORT_BATCH,
                EmbedFailureDecision.decide(FailureStrategy.ISOLATED, true));
    }

    @Test
    void rollbackAll_withoutUserStop_aborts() {
        assertEquals(ABORT_BATCH,
                EmbedFailureDecision.decide(FailureStrategy.ROLLBACK_ALL, false));
    }

    @Test
    void rollbackAll_withUserStop_aborts() {
        assertEquals(ABORT_BATCH,
                EmbedFailureDecision.decide(FailureStrategy.ROLLBACK_ALL, true));
    }

    @Test
    void keepSucceeded_withoutUserStop_aborts() {
        assertEquals(ABORT_BATCH,
                EmbedFailureDecision.decide(FailureStrategy.KEEP_SUCCEEDED, false));
    }

    @Test
    void keepSucceeded_withUserStop_aborts() {
        assertEquals(ABORT_BATCH,
                EmbedFailureDecision.decide(FailureStrategy.KEEP_SUCCEEDED, true));
    }

    @Test
    void nullStrategy_withoutUserStop_treatedAsIsolated() {
        // FailureStrategy.fromString 在未识别值时也返回 ISOLATED，保持一致兜底
        assertEquals(CONTINUE_ISOLATED,
                EmbedFailureDecision.decide(null, false));
    }

    @Test
    void nullStrategy_withUserStop_aborts() {
        assertEquals(ABORT_BATCH,
                EmbedFailureDecision.decide(null, true));
    }
}
