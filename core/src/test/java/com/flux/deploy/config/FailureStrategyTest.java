package com.flux.deploy.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FailureStrategyTest {

    @Test
    void fromString_recognizesIsolated() {
        assertThat(FailureStrategy.fromString("isolated")).isEqualTo(FailureStrategy.ISOLATED);
    }

    @Test
    void fromString_recognizesRollbackAll() {
        assertThat(FailureStrategy.fromString("rollback_all")).isEqualTo(FailureStrategy.ROLLBACK_ALL);
    }

    @Test
    void fromString_recognizesKeepSucceeded() {
        assertThat(FailureStrategy.fromString("keep_succeeded")).isEqualTo(FailureStrategy.KEEP_SUCCEEDED);
    }

    @Test
    void fromString_isCaseInsensitive() {
        assertThat(FailureStrategy.fromString("ISOLATED")).isEqualTo(FailureStrategy.ISOLATED);
        assertThat(FailureStrategy.fromString("Rollback_All")).isEqualTo(FailureStrategy.ROLLBACK_ALL);
    }

    @Test
    void fromString_throwsOnUnknown() {
        assertThatThrownBy(() -> FailureStrategy.fromString("continue_on_error"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未知的 failure_strategy.mode");
    }

    @Test
    void fromString_throwsOnNull() {
        assertThatThrownBy(() -> FailureStrategy.fromString(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void defaultStrategy_isIsolated() {
        assertThat(FailureStrategy.defaultStrategy()).isEqualTo(FailureStrategy.ISOLATED);
    }
}
