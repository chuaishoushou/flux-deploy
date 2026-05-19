package com.flux.deploy.config;

/**
 * 批量部署失败策略
 *
 * <p>三种语义：</p>
 * <ul>
 *   <li>ISOLATED - 失败的包独立回滚为旧版本，其他包继续执行（默认）</li>
 *   <li>ROLLBACK_ALL - 任一失败 → 中止 + 已成功的全部回滚</li>
 *   <li>KEEP_SUCCEEDED - 任一失败 → 中止 + 保留已成功的，备份目录保留供手动回滚</li>
 * </ul>
 *
 * @author xumanyi
 * @date 2026-05-02
 */
public enum FailureStrategy {
    ISOLATED,
    ROLLBACK_ALL,
    KEEP_SUCCEEDED;

    /**
     * 解析配置文件中的 failure_strategy.mode 字符串
     *
     * @param raw 配置文件原始值（大小写不敏感，下划线分隔）
     * @return 对应的策略枚举
     * @throws IllegalArgumentException raw 为 null 或不识别的值
     * @author xumanyi
     * @date 2026-05-02
     */
    public static FailureStrategy fromString(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("未知的 failure_strategy.mode: null");
        }
        String key = raw.trim().toLowerCase();
        switch (key) {
            case "isolated": return ISOLATED;
            case "rollback_all": return ROLLBACK_ALL;
            case "keep_succeeded": return KEEP_SUCCEEDED;
            default:
                throw new IllegalArgumentException(
                        "未知的 failure_strategy.mode: " + raw
                                + "（合法值：isolated / rollback_all / keep_succeeded）");
        }
    }

    /**
     * 配置文件不存在或缺省时使用的默认策略
     *
     * @return ISOLATED
     * @author xumanyi
     * @date 2026-05-02
     */
    public static FailureStrategy defaultStrategy() {
        return ISOLATED;
    }
}
