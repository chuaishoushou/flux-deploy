package com.flux.deploy.deploy;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ResidualLockResolverDiagnoseTest {

    /** 最小 fake：每个目录预设的锁文件列表 + 哪些 path 视为存在 */
    static class FakeProbe implements ResidualLockResolver.RemoteProbe {
        Map<String, List<String>> locksByDir = new HashMap<>();
        Set<String> existingPaths = new HashSet<>();
        @Override public List<String> findResidualLocks(String dir, String pkg) { return locksByDir.getOrDefault(dir + "::" + pkg, List.of()); }
        @Override public boolean exists(String path) throws IOException { return existingPaths.contains(path); }
    }

    @Test
    void noResidual_returnsEmpty() throws Exception {
        FakeProbe probe = new FakeProbe();
        ResidualLockResolver r = new ResidualLockResolver(probe, "alice");
        List<ResidualLockDiagnosis> result = r.diagnose("/d/", "a.war");
        assertThat(result).isEmpty();
    }

    @Test
    void lockExists_originalMissing_suggestsRestoreLock() throws Exception {
        FakeProbe probe = new FakeProbe();
        probe.locksByDir.put("/d/::a.war", List.of("a.war__LOCK__alice_20260427_153012"));
        // 原包不存在
        ResidualLockResolver r = new ResidualLockResolver(probe, "alice");
        List<ResidualLockDiagnosis> result = r.diagnose("/d/", "a.war");
        assertThat(result).hasSize(1);
        ResidualLockDiagnosis d = result.get(0);
        assertThat(d.getSuggestion()).isEqualTo(ResidualLockDiagnosis.SuggestedAction.RESTORE_LOCK);
        assertThat(d.isOwnedByCurrentUser()).isTrue();
        assertThat(d.isOriginalPackageExists()).isFalse();
        assertThat(d.getOperator()).isEqualTo("alice");
    }

    @Test
    void lockExists_originalAlsoExists_suggestsDeleteLock() throws Exception {
        FakeProbe probe = new FakeProbe();
        probe.locksByDir.put("/d/::a.war", List.of("a.war__LOCK__alice_20260427_153012"));
        probe.existingPaths.add("/d/a.war");
        ResidualLockResolver r = new ResidualLockResolver(probe, "alice");
        List<ResidualLockDiagnosis> result = r.diagnose("/d/", "a.war");
        assertThat(result.get(0).getSuggestion()).isEqualTo(ResidualLockDiagnosis.SuggestedAction.DELETE_LOCK);
        assertThat(result.get(0).isOriginalPackageExists()).isTrue();
    }

    @Test
    void lockOwnedByOther_marksNotOwnedByCurrentUser() throws Exception {
        FakeProbe probe = new FakeProbe();
        probe.locksByDir.put("/d/::a.war", List.of("a.war__LOCK__bob_20260427_153012"));
        ResidualLockResolver r = new ResidualLockResolver(probe, "alice");
        List<ResidualLockDiagnosis> result = r.diagnose("/d/", "a.war");
        assertThat(result.get(0).isOwnedByCurrentUser()).isFalse();
        assertThat(result.get(0).getOperator()).isEqualTo("bob");
    }

    @Test
    void unparseableLockName_suggestsNeedsHuman() throws Exception {
        FakeProbe probe = new FakeProbe();
        probe.locksByDir.put("/d/::a.war", List.of("a.war__LOCK__weirdformat"));
        ResidualLockResolver r = new ResidualLockResolver(probe, "alice");
        List<ResidualLockDiagnosis> result = r.diagnose("/d/", "a.war");
        assertThat(result.get(0).getSuggestion()).isEqualTo(ResidualLockDiagnosis.SuggestedAction.NEEDS_HUMAN);
    }
}
