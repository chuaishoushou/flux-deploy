package com.flux.deploy.model;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class StatusEnumTest {
    @Test
    void hasSkippedStatus() {
        assertThat(TargetPackage.Status.SKIPPED).isNotNull();
    }
    @Test
    void hasFailedNeedsManualStatus() {
        assertThat(TargetPackage.Status.FAILED_NEEDS_MANUAL).isNotNull();
    }
    @Test
    void deployResultHasCancelledFlag() {
        DeployResult r = new DeployResult();
        assertThat(r.isCancelled()).isFalse();
        r.setCancelled(true);
        assertThat(r.isCancelled()).isTrue();
    }
}
