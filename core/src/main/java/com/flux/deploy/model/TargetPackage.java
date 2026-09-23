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
     * NoteGate 写入前的远端 note 文件字节快照（漏洞 H3 修复）。
     *
     * <p>{@link com.flux.deploy.deploy.gates.NoteGate#execute} 在 upload 写入新内容之前会把
     * 当前远端 note 文件原始字节下载并放入此字段；回滚阶段
     * {@link com.flux.deploy.deploy.Rollback#rollbackTarget} 在 NOTE_UPDATED 状态额外把
     * 快照上传覆盖 {@link #noteRemotePath}，撤销 NoteGate 的写入，避免业务包回滚到旧版本而
     * note 文件残留新版记录的跨文件不一致。</p>
     *
     * <p>取值含义：</p>
     * <ul>
     *   <li><b>null</b>：NoteGate 尚未跑，或本次为新建 canonical 文件（无旧字节可还原）。
     *       后者由 {@link #noteRemotePath} 非空但快照为 null 区分——回滚时改为 delete remote 文件。</li>
     *   <li><b>非空字节数组</b>：远端 note 文件被覆盖前的原始字节，回滚时用裸 STOR 写回。</li>
     * </ul>
     */
    private byte[] noteSnapshotBytes;

    /**
     * NoteGate 写入目标路径（漏洞 H3 修复）。
     *
     * <p>NoteGate 在选定 writePath（覆盖已存在的 primary 或新建 canonical）后立刻写入此字段；
     * 回滚阶段读取此字段判断是否需要恢复 note。</p>
     *
     * <p>null 表示 NoteGate 还没跑，无需 note 回滚。</p>
     */
    private String noteRemotePath;

    /**
     * 是否为「新建目标」：远端原本不存在同名文件（Vue 模块 zip 首次投放场景）。
     *
     * <p>true 时预检跳过远端存在性校验；备份/锁天然缺位；回滚语义 = 删除已上传的新文件
     * （见 {@link com.flux.deploy.deploy.Rollback#rollbackTarget}）。</p>
     */
    private boolean createNew;

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

    public byte[] getNoteSnapshotBytes() { return noteSnapshotBytes; }
    public void setNoteSnapshotBytes(byte[] noteSnapshotBytes) { this.noteSnapshotBytes = noteSnapshotBytes; }

    public String getNoteRemotePath() { return noteRemotePath; }
    public void setNoteRemotePath(String noteRemotePath) { this.noteRemotePath = noteRemotePath; }

    public boolean isCreateNew() { return createNew; }
    public void setCreateNew(boolean createNew) { this.createNew = createNew; }
}
