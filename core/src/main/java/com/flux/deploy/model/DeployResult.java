package com.flux.deploy.model;


import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 部署结果（JSON 输出结构）
 *
 * <p>包含部署成功/失败状态、各目标包结果、回滚信息和错误列表。</p>
 *
 * @author xumanyi
 * @date 2026-03-26
 */
public class DeployResult {

    private boolean success;
    private LocalDateTime timestamp;
    private List<TargetResult> targets = new ArrayList<>();
    private RollbackResult rollback;
    private List<ErrorInfo> errors = new ArrayList<>();
    /** 是否因用户主动取消而结束 */
    private boolean cancelled;

    /**
     * 创建部署结果实例，自动记录当前时间戳
     */
    public DeployResult() {
        this.timestamp = LocalDateTime.now();
    }

    // ========== 便捷方法 ==========

    /**
     * 添加目标包结果
     *
     * @param target 目标包结果
     * @author xumanyi
     * @date 2026-03-26
     */
    public void addTarget(TargetResult target) {
        targets.add(target);
    }

    /**
     * 添加错误信息并标记部署为失败
     *
     * @param gate    门禁名称
     * @param target  目标包名
     * @param message 错误信息
     * @author xumanyi
     * @date 2026-03-26
     */
    public void addError(String gate, String target, String message) {
        errors.add(new ErrorInfo(gate, target, message));
        this.success = false;
    }

    /**
     * 标记部署为成功
     */
    public void markSuccess() {
        this.success = true;
    }

    // ========== Getters & Setters ==========

    public boolean isSuccess() { return success; }
    public void setSuccess(boolean success) { this.success = success; }

    public LocalDateTime getTimestamp() { return timestamp; }
    public void setTimestamp(LocalDateTime timestamp) { this.timestamp = timestamp; }

    public List<TargetResult> getTargets() { return targets; }
    public void setTargets(List<TargetResult> targets) { this.targets = targets; }

    public RollbackResult getRollback() { return rollback; }
    public void setRollback(RollbackResult rollback) { this.rollback = rollback; }

    public List<ErrorInfo> getErrors() { return errors; }
    public void setErrors(List<ErrorInfo> errors) { this.errors = errors; }

    public boolean isCancelled() { return cancelled; }
    public void setCancelled(boolean cancelled) { this.cancelled = cancelled; }

    // ========== 内部结构 ==========

    /**
     * 单个目标包的部署结果
     */
        public static class TargetResult {
        private String packageName;
        private String remotePath;
        private String backupPath;
        private String localSha256;
        private String remoteSha256;
        private boolean verified;
        private boolean noteUpdated;
        private boolean noteBackupSynced;

        public String getPackageName() { return packageName; }
        public void setPackageName(String packageName) { this.packageName = packageName; }
        public String getRemotePath() { return remotePath; }
        public void setRemotePath(String remotePath) { this.remotePath = remotePath; }
        public String getBackupPath() { return backupPath; }
        public void setBackupPath(String backupPath) { this.backupPath = backupPath; }
        public String getLocalSha256() { return localSha256; }
        public void setLocalSha256(String localSha256) { this.localSha256 = localSha256; }
        public String getRemoteSha256() { return remoteSha256; }
        public void setRemoteSha256(String remoteSha256) { this.remoteSha256 = remoteSha256; }
        public boolean isVerified() { return verified; }
        public void setVerified(boolean verified) { this.verified = verified; }
        public boolean isNoteUpdated() { return noteUpdated; }
        public void setNoteUpdated(boolean noteUpdated) { this.noteUpdated = noteUpdated; }
        public boolean isNoteBackupSynced() { return noteBackupSynced; }
        public void setNoteBackupSynced(boolean noteBackupSynced) { this.noteBackupSynced = noteBackupSynced; }
    }

    /**
     * 回滚操作结果
     */
        public static class RollbackResult {
        private boolean attempted;
        private boolean success;
        private List<String> restoredPackages = new ArrayList<>();

        public boolean isAttempted() { return attempted; }
        public void setAttempted(boolean attempted) { this.attempted = attempted; }
        public boolean isSuccess() { return success; }
        public void setSuccess(boolean success) { this.success = success; }
        public List<String> getRestoredPackages() { return restoredPackages; }
        public void setRestoredPackages(List<String> restoredPackages) { this.restoredPackages = restoredPackages; }
    }

    /**
     * 错误信息（记录哪个门禁、哪个目标包出了什么错）
     */
        public static class ErrorInfo {
        private String gate;
        private String target;
        private String message;

        /** 默认构造函数（Jackson 反序列化用） */
        public ErrorInfo() {}

        /**
         * 创建错误信息
         *
         * @param gate    门禁名称
         * @param target  目标包名
         * @param message 错误描述
     * @author xumanyi
     * @date 2026-03-26
     */
        public ErrorInfo(String gate, String target, String message) {
            this.gate = gate;
            this.target = target;
            this.message = message;
        }

        public String getGate() { return gate; }
        public void setGate(String gate) { this.gate = gate; }
        public String getTarget() { return target; }
        public void setTarget(String target) { this.target = target; }
        public String getMessage() { return message; }
        public void setMessage(String message) { this.message = message; }
    }
}
