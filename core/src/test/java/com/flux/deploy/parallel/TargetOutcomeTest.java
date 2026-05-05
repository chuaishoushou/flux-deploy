package com.flux.deploy.parallel;

import com.flux.deploy.ftp.FtpErrorKind;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TargetOutcomeTest {

    @Test
    void success_hasNoErrorAndNoSuggestion() {
        TargetOutcome o = TargetOutcome.success("tm01srv1.war", "log content");
        assertThat(o.getStatus()).isEqualTo(TargetStatus.SUCCESS);
        assertThat(o.getTargetKey()).isEqualTo("tm01srv1.war");
        assertThat(o.getError()).isNull();
        assertThat(o.getErrorKind()).isNull();
        assertThat(o.getLogSegment()).isEqualTo("log content");
        assertThat(o.isCritical()).isFalse();
    }

    @Test
    void failed_carriesErrorAndKind() {
        RuntimeException err = new RuntimeException("boom");
        TargetOutcome o = TargetOutcome.failed("tm01srv2.war", err, FtpErrorKind.PROTOCOL, "log");
        assertThat(o.getStatus()).isEqualTo(TargetStatus.FAILED);
        assertThat(o.getError()).isSameAs(err);
        assertThat(o.getErrorKind()).isEqualTo(FtpErrorKind.PROTOCOL);
        assertThat(o.isCritical()).isFalse();
    }

    @Test
    void rollbackFailed_isCriticalLevel() {
        RuntimeException err = new RuntimeException("rollback boom");
        TargetOutcome o = TargetOutcome.rollbackFailed("tm01srv3.war", err, "log");
        assertThat(o.getStatus()).isEqualTo(TargetStatus.ROLLBACK_FAILED);
        assertThat(o.isCritical()).isTrue();
    }

    @Test
    void cancelled_isNotCritical() {
        TargetOutcome o = TargetOutcome.cancelled("tm01srv4.war", "partial log");
        assertThat(o.getStatus()).isEqualTo(TargetStatus.CANCELLED);
        assertThat(o.isCritical()).isFalse();
    }

    @Test
    void skipped_hasReason() {
        TargetOutcome o = TargetOutcome.skipped("tm01srv5.war", "backup failed earlier");
        assertThat(o.getStatus()).isEqualTo(TargetStatus.SKIPPED);
        assertThat(o.getLogSegment()).isEqualTo("backup failed earlier");
    }

    @Test
    void nullLogSegment_isNormalizedToEmpty() {
        TargetOutcome o = TargetOutcome.success("k", null);
        assertThat(o.getLogSegment()).isEmpty();
    }

    @Test
    void nullTargetKey_throws() {
        assertThatThrownBy(() -> TargetOutcome.success(null, "log"))
                .isInstanceOf(NullPointerException.class);
    }
}
