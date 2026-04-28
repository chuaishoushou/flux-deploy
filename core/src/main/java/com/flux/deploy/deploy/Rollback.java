package com.flux.deploy.deploy;

import com.flux.deploy.ftp.FtpLock;
import com.flux.deploy.ftp.FtpOperations;
import com.flux.deploy.model.TargetPackage;

/**
 * 回滚策略
 *
 * <p>根据各目标包当前状态决定回滚方式：</p>
 * <ul>
 *   <li>已加锁但未上传 → rename 锁包恢复原名</li>
 *   <li>已上传但校验失败 → 优先 rename 锁包恢复；若锁包已删除则用备份恢复</li>
 * </ul>
 *
 * @author xumanyi
 * @date 2026-03-26
 */
public class Rollback {

    private final FtpOperations ops;
    private final FtpLock ftpLock;

    /**
     * 创建回滚策略实例
     *
     * @param ops     FTP 操作对象
     * @param ftpLock FTP 锁操作对象
     * @author xumanyi
     * @date 2026-03-26
     */
    public Rollback(FtpOperations ops, FtpLock ftpLock) {
        this.ops = ops;
        this.ftpLock = ftpLock;
    }

    /**
     * 根据目标包当前状态决定并执行回滚操作
     *
     * <p>仅对 LOCKED/UPLOADED（rename 锁包恢复）和 VERIFIED/NOTE_UPDATED（备份恢复）两组状态生效。
     * COMPLETED 视为终态不再回滚（解锁阶段已发生不可逆动作如 note 同步、原锁包删除）。</p>
     *
     * @param target 目标包
     * @return true 如果执行了回滚操作，false 表示无需回滚
     * @throws Exception 回滚操作失败
     * @author xumanyi
     * @date 2026-03-26
     */
    public boolean rollbackTarget(TargetPackage target) throws Exception {
        TargetPackage.Status status = target.getStatus();
        switch (status) {
            case LOCKED:
            case UPLOADED:
                if (target.getLockName() != null) {
                    ftpLock.restoreLock(target.getRemoteDir(), target.getLockName());
                    target.setStatus(TargetPackage.Status.ROLLED_BACK);
                    return true;
                }
                return false;
            case VERIFIED:
            case NOTE_UPDATED:
                if (target.getBackupRemotePath() != null) {
                    restoreFromBackup(target);
                    target.setStatus(TargetPackage.Status.ROLLED_BACK);
                    return true;
                }
                return false;
            default:
                // PENDING / BACKED_UP / COMPLETED / FAILED / SKIPPED / ROLLED_BACK / FAILED_NEEDS_MANUAL
                return false;
        }
    }

    /**
     * 从备份路径下载文件并上传恢复到目标包原路径，同时清理残留锁包
     *
     * @param target 目标包（需包含备份路径信息）
     * @throws Exception 下载或上传失败
     * @author xumanyi
     * @date 2026-03-26
     */
    private void restoreFromBackup(TargetPackage target) throws Exception {
        // 下载备份到临时本地文件，再上传到原路径
        java.nio.file.Path tempFile = java.nio.file.Files.createTempFile("rollback-", "-" + target.getPackageName());
        try {
            ops.download(target.getBackupRemotePath(), tempFile);
            ops.upload(tempFile, target.getRemotePath());
        } finally {
            java.nio.file.Files.deleteIfExists(tempFile);
        }

        // 如果锁包还在，删除它
        if (target.getLockName() != null) {
            try {
                String lockPath = ensureTrailingSlash(target.getRemoteDir()) + target.getLockName();
                if (ops.exists(lockPath)) {
                    ops.delete(lockPath);
                }
            } catch (Exception ignored) {
                // 锁清理失败不影响回滚结果
            }
        }
    }

    /**
     * 确保路径以 / 结尾
     *
     * @param path 原始路径
     * @return 以 / 结尾的路径
     * @author xumanyi
     * @date 2026-03-26
     */
    private static String ensureTrailingSlash(String path) {
        return path.endsWith("/") ? path : path + "/";
    }
}
