package com.flux.deploy.deploy;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ResidualLockResolverApplyTest {

    static class RecordingProbe implements ResidualLockResolver.RemoteProbe {
        List<String> ops = new ArrayList<>();
        @Override public List<String> findResidualLocks(String d, String p) { return List.of(); }
        @Override public boolean exists(String p) { return false; }
        @Override public void rename(String from, String to) { ops.add("rename " + from + " -> " + to); }
        @Override public void delete(String p) { ops.add("delete " + p); }
    }

    @Test
    void applyRestoreLock_renamesLockToOriginal() throws Exception {
        RecordingProbe probe = new RecordingProbe();
        ResidualLockResolver r = new ResidualLockResolver(probe, "alice");

        ResidualLockDiagnosis d = ResidualLockDiagnosis.builder()
                .lockFileName("a.war__LOCK__alice_20260427_153012")
                .originalPackageName("a.war")
                .remoteDir("/d/")
                .suggestion(ResidualLockDiagnosis.SuggestedAction.RESTORE_LOCK)
                .build();

        r.apply(d);

        assertThat(probe.ops).containsExactly("rename /d/a.war__LOCK__alice_20260427_153012 -> /d/a.war");
    }

    @Test
    void applyDeleteLock_deletesLockOnly() throws Exception {
        RecordingProbe probe = new RecordingProbe();
        ResidualLockResolver r = new ResidualLockResolver(probe, "alice");

        ResidualLockDiagnosis d = ResidualLockDiagnosis.builder()
                .lockFileName("a.war__LOCK__alice_20260427_153012")
                .originalPackageName("a.war")
                .remoteDir("/d/")
                .suggestion(ResidualLockDiagnosis.SuggestedAction.DELETE_LOCK)
                .build();

        r.apply(d);

        assertThat(probe.ops).containsExactly("delete /d/a.war__LOCK__alice_20260427_153012");
    }

    @Test
    void applyNeedsHuman_throws() {
        RecordingProbe probe = new RecordingProbe();
        ResidualLockResolver r = new ResidualLockResolver(probe, "alice");

        ResidualLockDiagnosis d = ResidualLockDiagnosis.builder()
                .lockFileName("garbage")
                .originalPackageName("a.war")
                .remoteDir("/d/")
                .suggestion(ResidualLockDiagnosis.SuggestedAction.NEEDS_HUMAN)
                .build();

        assertThatThrownBy(() -> r.apply(d))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("人工");
    }
}
