package com.flux.deploy.model;

import java.nio.file.Path;

/**
 * 目标包信息（运行时状态跟踪）
 *
 * <p>在部署流水线执行过程中，跟踪每个目标包从 PENDING 到 COMPLETED 的全生命周期状态，
 * 以及备份路径、锁文件名、SHA256 哈希等关键信息。</p>
 *
 * @author xumanyi
 * @date 2026-03-26
 */
public class TargetPackage {

    /** 包文件名（如 tm01srv.war） */
    private String packageName;

    /** 包在远程目录中的完整路径 */
    private String remotePath;

    /** 包所在的远程目录 */
    private String remoteDir;

    /** 本地暂存包路径 */
    private Path localStagingFile;

    /**
     * 备份阶段下载到本地的远端原包副本（"1 下 + 2 上"复用）。
     *
     * <p>{@link com.flux.deploy.deploy.gates.BackupGate} 完成 download → upload-to-backup 后
     * 不再立刻删除本地 temp 文件，而是把路径挂在本字段上交给后续门禁（如 StagingPackageBuilder）
     * 复用，避免对同一字节再做一次远端下载。流水线收尾必须遍历 targets 删除这些 temp 文件。</p>
     */
    private Path localOriginalCopy;

    /** 备份路径（远程 FTP） */
    private String backupRemotePath;

    /** 锁文件名（加锁后设置） */
    private String lockName;

    /** 包状态 */
    private Status status = Status.PENDING;

    /** 本地暂存包 SHA256 */
    private String localSha256;

    /** 远程上传后 SHA256 */
    private String remoteSha256;

    /**
     * 目标包生命周期状态枚举
     *
     * <p>状态按部署流程顺序排列，ordinal 值用于判断已到达的阶段。</p>
     */
    public enum Status {
        /** 待处理 */
        PENDING,
        /** 已备份 */
        BACKED_UP,
        /** 已加锁 */
        LOCKED,
        /** 已上传 */
        UPLOADED,
        /** 已校验 */
        VERIFIED,
        /** note 已更新 */
        NOTE_UPDATED,
        /** 已解锁（完成） */
        COMPLETED,
        /** 已回滚 */
        ROLLED_BACK,
        /** 因前序失败被跳过（fail-fast 后未开始） */
        SKIPPED,
        /** 失败且自动回滚未完成，需人工介入 */
        FAILED_NEEDS_MANUAL,
        /** 失败 */
        FAILED
    }

    // ========== Getters & Setters ==========

    public String getPackageName() { return packageName; }
    public void setPackageName(String packageName) { this.packageName = packageName; }

    public String getRemotePath() { return remotePath; }
    public void setRemotePath(String remotePath) { this.remotePath = remotePath; }

    public String getRemoteDir() { return remoteDir; }
    public void setRemoteDir(String remoteDir) { this.remoteDir = remoteDir; }

    public Path getLocalStagingFile() { return localStagingFile; }
    public void setLocalStagingFile(Path localStagingFile) { this.localStagingFile = localStagingFile; }

    public Path getLocalOriginalCopy() { return localOriginalCopy; }
    public void setLocalOriginalCopy(Path localOriginalCopy) { this.localOriginalCopy = localOriginalCopy; }

    public String getBackupRemotePath() { return backupRemotePath; }
    public void setBackupRemotePath(String backupRemotePath) { this.backupRemotePath = backupRemotePath; }

    public String getLockName() { return lockName; }
    public void setLockName(String lockName) { this.lockName = lockName; }

    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }

    public String getLocalSha256() { return localSha256; }
    public void setLocalSha256(String localSha256) { this.localSha256 = localSha256; }

    public String getRemoteSha256() { return remoteSha256; }
    public void setRemoteSha256(String remoteSha256) { this.remoteSha256 = remoteSha256; }
}
