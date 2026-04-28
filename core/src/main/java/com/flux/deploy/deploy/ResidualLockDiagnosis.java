package com.flux.deploy.deploy;

import java.time.LocalDateTime;

/**
 * 残留锁诊断结果（不可变）
 *
 * <p>由 ResidualLockResolver.diagnose 产出，由 UI/CLI 展示给用户决策，
 * 由 ResidualLockResolver.apply 消费执行。</p>
 *
 * @author xumanyi
 * @date 2026-04-28
 */
public final class ResidualLockDiagnosis {

    /** 建议动作 */
    public enum SuggestedAction {
        /** 锁包在、原包不在 → rename 锁包回原名 */
        RESTORE_LOCK,
        /** 锁包在、原包也在 → 删锁包，保留新版本 */
        DELETE_LOCK,
        /** 状态异常，建议人工介入 */
        NEEDS_HUMAN
    }

    private final String lockFileName;
    private final String remoteDir;
    private final String originalPackageName;
    private final String operator;
    private final LocalDateTime lockedAt;
    private final boolean ownedByCurrentUser;
    private final boolean originalPackageExists;
    private final SuggestedAction suggestion;
    private final String reason;

    private ResidualLockDiagnosis(Builder b) {
        this.lockFileName = b.lockFileName;
        this.remoteDir = b.remoteDir;
        this.originalPackageName = b.originalPackageName;
        this.operator = b.operator;
        this.lockedAt = b.lockedAt;
        this.ownedByCurrentUser = b.ownedByCurrentUser;
        this.originalPackageExists = b.originalPackageExists;
        this.suggestion = b.suggestion;
        this.reason = b.reason;
    }

    public String getLockFileName() { return lockFileName; }
    public String getRemoteDir() { return remoteDir; }
    public String getOriginalPackageName() { return originalPackageName; }
    public String getOperator() { return operator; }
    public LocalDateTime getLockedAt() { return lockedAt; }
    public boolean isOwnedByCurrentUser() { return ownedByCurrentUser; }
    public boolean isOriginalPackageExists() { return originalPackageExists; }
    public SuggestedAction getSuggestion() { return suggestion; }
    public String getReason() { return reason; }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private String lockFileName;
        private String remoteDir;
        private String originalPackageName;
        private String operator;
        private LocalDateTime lockedAt;
        private boolean ownedByCurrentUser;
        private boolean originalPackageExists;
        private SuggestedAction suggestion;
        private String reason;

        public Builder lockFileName(String v) { this.lockFileName = v; return this; }
        public Builder remoteDir(String v) { this.remoteDir = v; return this; }
        public Builder originalPackageName(String v) { this.originalPackageName = v; return this; }
        public Builder operator(String v) { this.operator = v; return this; }
        public Builder lockedAt(LocalDateTime v) { this.lockedAt = v; return this; }
        public Builder ownedByCurrentUser(boolean v) { this.ownedByCurrentUser = v; return this; }
        public Builder originalPackageExists(boolean v) { this.originalPackageExists = v; return this; }
        public Builder suggestion(SuggestedAction v) { this.suggestion = v; return this; }
        public Builder reason(String v) { this.reason = v; return this; }

        public ResidualLockDiagnosis build() { return new ResidualLockDiagnosis(this); }
    }
}
