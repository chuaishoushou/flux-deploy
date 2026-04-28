package com.flux.deploy.plugin.model;

/**
 * 备份冲突处理策略
 *
 * <p>点击「打包并上传」时，若检测到 {@code backup/yyyyMMdd_{开发}/} 下已有同日同开发的备份包，
 * 用户需要选择一种处理方式。</p>
 *
 * @author xumanyi
 * @date 2026-04-18
 */
public enum BackupConflictStrategy {

    /** 覆盖已有备份：使用当前远端包覆盖老备份，原始远端包将永久丢失（默认兼容旧行为） */
    OVERWRITE("覆盖备份"),

    /** 使用已有备份作为回滚源：本次跳过备份步骤，保留更早的远端原始包作为回滚版本 */
    USE_EXISTING("使用原始备份"),

    /** 新增备份目录：创建 {@code yyyyMMdd_{开发}_v2 / _v3 / ...} 并行备份目录，两份备份都保留 */
    NEW_DIR("新增备份目录");

    private final String displayName;

    BackupConflictStrategy(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() { return displayName; }
}
