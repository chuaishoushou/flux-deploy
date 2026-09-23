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

    private FtpOperations ops;
    private FtpLock ftpLock;

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
     * 重绑定 FTP 操作与锁（用于 Stage 2 IO 异常后重连场景）
     *
     * @param newOps  新的 FTP 操作对象
     * @param newLock 新的 FTP 锁操作对象
     * @author xumanyi
     * @date 2026-04-29
     */
    public void rebind(FtpOperations newOps, FtpLock newLock) {
        this.ops = newOps;
        this.ftpLock = newLock;
    }

    /**
     * 根据目标包当前状态决定并执行回滚操作
     *
     * <p>策略矩阵（漏洞 H1 / H6 修复后）：</p>
     * <ul>
     *   <li><b>LOCKED</b>：原路径必为空（包已 rename 为锁包），rename 锁包回原名安全（无目标冲突）。</li>
     *   <li><b>UPLOADED</b>：原路径已被新包字节占用，多数 FTP 服务端（vsftpd/proftpd/IIS）拒绝
     *       rename 到已存在路径返回 550。优先 {@link #restoreFromBackup}（先下载备份覆盖远端，再删锁包），
     *       无备份时退化为"先 delete 原路径再 restoreLock"。</li>
     *   <li><b>VERIFIED / NOTE_UPDATED</b>：必走 {@link #restoreFromBackup}（业务包已通过校验，
     *       rename 锁包回原名仍会撞已存在的新包字节）。</li>
     *   <li><b>COMPLETED</b>：终态不再回滚（解锁阶段已发生不可逆动作如 note 同步、原锁包 delete）。</li>
     * </ul>
     *
     * <p>对 {@code skipLock} 模式（{@code lockName==null}）：LOCKED 状态不可能出现；UPLOADED 状态
     * 业务包已被 STOR 直接覆盖，唯一恢复路径是 {@link #restoreFromBackup}（要求 BackupGate 已跑过）。
     * 既无备份又无锁包时，返回 false 让 {@code attemptRollback} 标记 attempted=false，避免假成功。</p>
     *
     * @param target 目标包
     * @return true 如果执行了回滚操作，false 表示无需回滚或无法回滚
     * @throws Exception 回滚操作失败
     * @author xumanyi
     * @date 2026-03-26
     */
    public boolean rollbackTarget(TargetPackage target) throws Exception {
        TargetPackage.Status status = target.getStatus();
        // 新建目标（远端原本无同名文件）：上传后的回滚 = 删除新文件恢复"不存在"原状；
        // 备份与锁天然缺位，不适用下方矩阵。LOCKED 状态对新建目标不可能出现。
        if (target.isCreateNew()) {
            switch (status) {
                case UPLOADED:
                case VERIFIED:
                case NOTE_UPDATED:
                    deleteIfExistsSilent(target.getRemotePath());
                    restoreNoteIfNeeded(target);
                    target.setStatus(TargetPackage.Status.ROLLED_BACK);
                    return true;
                default:
                    return false;
            }
        }
        switch (status) {
            case LOCKED:
                // 原路径已空，rename 安全
                if (target.getLockName() != null) {
                    ftpLock.restoreLock(target.getRemoteDir(), target.getLockName());
                    target.setStatus(TargetPackage.Status.ROLLED_BACK);
                    return true;
                }
                // skipLock 模式下 LOCKED 不可能出现；落到这里说明状态机异常，无法恢复
                return false;
            case UPLOADED:
                // 原路径已被新包字节占用，rename 锁包回去会撞——优先备份恢复
                if (target.getBackupRemotePath() != null) {
                    restoreFromBackup(target);
                    target.setStatus(TargetPackage.Status.ROLLED_BACK);
                    return true;
                }
                // 没备份：唯一可走的路径是先删新包再 rename 锁包回去（前提是有锁包）
                if (target.getLockName() != null) {
                    deleteIfExistsSilent(target.getRemotePath());
                    ftpLock.restoreLock(target.getRemoteDir(), target.getLockName());
                    target.setStatus(TargetPackage.Status.ROLLED_BACK);
                    return true;
                }
                // skipLock + 无备份：什么都恢复不了，return false 让 attemptRollback 标 attempted=false
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
     * 静默删除远端文件：不存在或删除失败都不抛，仅打日志。用于 UPLOADED + 无备份的兜底路径，
     * 在 restoreLock rename 之前清出目标位置，避免被 FTP 服务端 550 拒绝。
     *
     * @param remotePath 远端绝对路径
     * @author xumanyi
     * @date 2026-07-01
     */
    private void deleteIfExistsSilent(String remotePath) {
        try {
            if (ops.exists(remotePath)) {
                ops.delete(remotePath);
            }
        } catch (Exception e) {
            System.err.println("[rollback] 删除 " + remotePath + " 失败（继续尝试 rename）: " + e.getMessage());
        }
    }

    /**
     * 从备份路径下载文件并上传恢复到目标包原路径，同时清理残留锁包；
     * 漏洞 H3 修复：若 NoteGate 已写过（target.noteRemotePath != null），同时撤销 note 文件。
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
            // 备份下载要核对字节数：半截备份写回线上，比不回滚更糟
            long backupSize = ops.getFileSize(target.getBackupRemotePath());
            long downloaded = ops.download(target.getBackupRemotePath(), tempFile,
                    "[回滚] 取备份 " + target.getPackageName(),
                    msg -> System.out.println("  " + msg));
            if (backupSize >= 0 && downloaded != backupSize) {
                throw new java.io.IOException("备份下载不完整: " + target.getBackupRemotePath()
                        + "，备份 " + backupSize + " B，实得 " + downloaded + " B");
            }
            ops.uploadAtomic(tempFile, target.getRemotePath(),
                    "[回滚] 恢复 " + target.getPackageName(),
                    msg -> System.out.println("  " + msg));
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

        // 漏洞 H3：撤销 NoteGate 的写入
        //   - snapshot != null：原文件被覆盖前的字节存在，STOR 写回（保留原编码）
        //   - snapshot == null：NoteGate 是新建的 canonical 文件，delete 即可
        // noteRemotePath == null 时 NoteGate 还没跑（VERIFIED 等更早的状态），跳过
        restoreNoteIfNeeded(target);
    }

    /**
     * 撤销 NoteGate 写入：按 snapshot 字节数据回写原文件，或在新建场景下 delete 文件。
     *
     * <p>异常被吞掉只记日志：业务包字节已恢复成功就算回滚成功，note 文件即便残留新内容，
     * 影响也只是版本记录不一致（业务可用，DevOps 可手动清理）；如果因 note 异常把整个
     * 回滚标失败，反而抹掉业务包成功恢复的事实。</p>
     *
     * @param target 目标包
     * @author xumanyi
     * @date 2026-07-01
     */
    private void restoreNoteIfNeeded(TargetPackage target) {
        String notePath = target.getNoteRemotePath();
        if (notePath == null) return;
        try {
            byte[] snapshot = target.getNoteSnapshotBytes();
            if (snapshot == null) {
                // NoteGate 新建场景：原文件不存在，删除写入的新 canonical
                if (ops.exists(notePath)) {
                    ops.delete(notePath);
                    System.out.println("  [回滚] 删除新建 note: " + notePath);
                }
            } else {
                // NoteGate 覆盖场景：把原字节写回
                java.nio.file.Path tempNote = java.nio.file.Files.createTempFile("rollback-note-", ".bin");
                try {
                    java.nio.file.Files.write(tempNote, snapshot);
                    ops.uploadAtomic(tempNote, notePath);
                    System.out.println("  [回滚] 恢复 note 原字节 (" + snapshot.length + " B): " + notePath);
                } finally {
                    java.nio.file.Files.deleteIfExists(tempNote);
                }
            }
        } catch (Exception e) {
            System.err.println("[rollback] 撤销 note 失败（业务包已回滚成功，note 需手动核对）: "
                    + notePath + " - " + e.getMessage());
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
