package com.flux.deploy.deploy;

import org.junit.jupiter.api.Test;
import java.time.LocalDateTime;
import static org.assertj.core.api.Assertions.assertThat;

class ResidualLockDiagnosisTest {
    @Test
    void buildsAndExposesAllFields() {
        ResidualLockDiagnosis d = ResidualLockDiagnosis.builder()
                .lockFileName("a.war__LOCK__bob_20260427_153012")
                .originalPackageName("a.war")
                .operator("bob")
                .lockedAt(LocalDateTime.of(2026, 4, 27, 15, 30, 12))
                .ownedByCurrentUser(true)
                .originalPackageExists(false)
                .suggestion(ResidualLockDiagnosis.SuggestedAction.RESTORE_LOCK)
                .reason("锁包在，原包不在")
                .build();

        assertThat(d.getLockFileName()).isEqualTo("a.war__LOCK__bob_20260427_153012");
        assertThat(d.getOriginalPackageName()).isEqualTo("a.war");
        assertThat(d.getOperator()).isEqualTo("bob");
        assertThat(d.isOwnedByCurrentUser()).isTrue();
        assertThat(d.isOriginalPackageExists()).isFalse();
        assertThat(d.getSuggestion()).isEqualTo(ResidualLockDiagnosis.SuggestedAction.RESTORE_LOCK);
        assertThat(d.getReason()).isEqualTo("锁包在，原包不在");
    }
}
