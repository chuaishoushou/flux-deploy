package com.flux.deploy.deploy;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ResidualLockResolverDiagnoseTest {

    /**
     * 用"当前时间附近"的时间戳生成锁文件名，避免 TTL 过期升级影响 suggestion。
     * 默认 TTL=10 分钟，这里取 now 保证测试运行时锁仍未过期。
     */
    private static String freshLockName(String pkg, String operator) {
        String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        return pkg + "__LOCK__" + operator + "_" + ts;
    }

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
        // 用 now 时间戳避免 TTL 升级覆盖 suggestion；本测试只验证基础诊断逻辑
        String lockName = freshLockName("a.war", "alice");
        probe.locksByDir.put("/d/::a.war", List.of(lockName));
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
        probe.locksByDir.put("/d/::a.war", List.of(freshLockName("a.war", "alice")));
        probe.existingPaths.add("/d/a.war");
        ResidualLockResolver r = new ResidualLockResolver(probe, "alice");
        List<ResidualLockDiagnosis> result = r.diagnose("/d/", "a.war");
        assertThat(result.get(0).getSuggestion()).isEqualTo(ResidualLockDiagnosis.SuggestedAction.DELETE_LOCK);
        assertThat(result.get(0).isOriginalPackageExists()).isTrue();
    }

    @Test
    void lockOwnedByOther_marksNotOwnedByCurrentUser() throws Exception {
        FakeProbe probe = new FakeProbe();
        probe.locksByDir.put("/d/::a.war", List.of(freshLockName("a.war", "bob")));
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

    @Test
    void multipleResidualLocks_eachDiagnosedIndependently() throws Exception {
        FakeProbe probe = new FakeProbe();
        String lockAlice = freshLockName("a.war", "alice");
        String lockBob = freshLockName("a.war", "bob");
        probe.locksByDir.put("/d/::a.war", List.of(lockAlice, lockBob));
        // 原包不存在
        ResidualLockResolver r = new ResidualLockResolver(probe, "alice");
        List<ResidualLockDiagnosis> result = r.diagnose("/d/", "a.war");

        assertThat(result).hasSize(2);
        // 每个锁都被独立诊断（owner 不同）
        assertThat(result.get(0).getLockFileName()).isEqualTo(lockAlice);
        assertThat(result.get(0).isOwnedByCurrentUser()).isTrue();
        assertThat(result.get(0).getOperator()).isEqualTo("alice");

        assertThat(result.get(1).getLockFileName()).isEqualTo(lockBob);
        assertThat(result.get(1).isOwnedByCurrentUser()).isFalse();
        assertThat(result.get(1).getOperator()).isEqualTo("bob");
    }

    @Test
    void expiredLock_isUpgradedToDeleteLock_evenIfOriginalMissing() throws Exception {
        FakeProbe probe = new FakeProbe();
        // 用 1 分钟前生成的锁名，TTL=10 分钟仍未过期；改用 30 分钟前生成的锁名 → 已过期
        String oldTs = LocalDateTime.now().minusMinutes(30)
                .format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        String oldLock = "a.war__LOCK__alice_" + oldTs;
        probe.locksByDir.put("/d/::a.war", List.of(oldLock));
        ResidualLockResolver r = new ResidualLockResolver(probe, "alice");
        List<ResidualLockDiagnosis> result = r.diagnose("/d/", "a.war");
        assertThat(result.get(0).getSuggestion())
                .isEqualTo(ResidualLockDiagnosis.SuggestedAction.DELETE_LOCK);
        assertThat(result.get(0).getReason()).contains("已过期");
    }
}
