package com.flux.deploy.deploy;

import com.flux.deploy.ftp.FtpLock;
import com.flux.deploy.ftp.FtpOperations;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Stage 0 残留锁诊断与清理。
 *
 * <p>从 LockGate 内部 findResidualLocks 抽出的独立步骤，
 * 在 Stage 1 之前先扫描所有 remoteDir 的残留锁，结构化诊断并由 UI/CLI 决定清理方式。</p>
 *
 * @author xumanyi
 * @date 2026-04-28
 */
public class ResidualLockResolver {

    /** 远端探测的最小接口，方便单元测试用 fake 注入 */
    public interface RemoteProbe {
        List<String> findResidualLocks(String remoteDir, String packageName) throws IOException;
        boolean exists(String path) throws IOException;
    }

    private static final DateTimeFormatter LOCK_TIME_FMT = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    private final RemoteProbe probe;
    private final String currentOperator;

    public ResidualLockResolver(RemoteProbe probe, String currentOperator) {
        this.probe = probe;
        this.currentOperator = currentOperator;
    }

    /** 适配真实 FTP：把 FtpOperations + FtpLock 包成 RemoteProbe */
    public static RemoteProbe wrap(FtpOperations ops, FtpLock lock) {
        return new RemoteProbe() {
            @Override public List<String> findResidualLocks(String dir, String pkg) throws IOException {
                return lock.findResidualLocks(dir, pkg);
            }
            @Override public boolean exists(String path) throws IOException {
                return ops.exists(path);
            }
        };
    }

    /**
     * 诊断单个 (remoteDir, packageName) 的残留锁。
     */
    public List<ResidualLockDiagnosis> diagnose(String remoteDir, String packageName) throws IOException {
        List<String> locks = probe.findResidualLocks(remoteDir, packageName);
        List<ResidualLockDiagnosis> result = new ArrayList<>();
        for (String lockName : locks) {
            result.add(diagnoseOne(remoteDir, packageName, lockName));
        }
        return result;
    }

    private ResidualLockDiagnosis diagnoseOne(String remoteDir, String packageName, String lockName) throws IOException {
        ResidualLockDiagnosis.Builder b = ResidualLockDiagnosis.builder()
                .lockFileName(lockName)
                .remoteDir(remoteDir)
                .originalPackageName(packageName);

        String[] info = FtpLock.parseLockInfo(lockName);
        if (info == null) {
            return b.suggestion(ResidualLockDiagnosis.SuggestedAction.NEEDS_HUMAN)
                    .reason("锁文件名格式异常，无法解析 operator/time")
                    .ownedByCurrentUser(false)
                    .build();
        }
        String operator = info[0];
        LocalDateTime ts;
        try {
            ts = LocalDateTime.parse(info[1], LOCK_TIME_FMT);
        } catch (Exception e) {
            ts = null;
        }
        boolean owned = currentOperator != null && currentOperator.equals(operator);

        if (ts == null) {
            return b.operator(operator)
                    .ownedByCurrentUser(owned)
                    .suggestion(ResidualLockDiagnosis.SuggestedAction.NEEDS_HUMAN)
                    .reason("锁文件名时间戳无法解析，格式异常: " + info[1])
                    .build();
        }

        boolean originalExists = probe.exists(ensureSlash(remoteDir) + packageName);

        ResidualLockDiagnosis.SuggestedAction action;
        String reason;
        if (originalExists) {
            action = ResidualLockDiagnosis.SuggestedAction.DELETE_LOCK;
            reason = "锁包与原包同时存在，新版本已发布；建议删锁保留新版本";
        } else {
            action = ResidualLockDiagnosis.SuggestedAction.RESTORE_LOCK;
            reason = "锁包存在，原包不存在；建议恢复锁包为原文件名";
        }

        return b.operator(operator)
                .lockedAt(ts)
                .ownedByCurrentUser(owned)
                .originalPackageExists(originalExists)
                .suggestion(action)
                .reason(reason)
                .build();
    }

    private static String ensureSlash(String p) { return p.endsWith("/") ? p : p + "/"; }
}
