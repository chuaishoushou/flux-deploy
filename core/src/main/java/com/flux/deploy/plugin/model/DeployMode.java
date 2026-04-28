package com.flux.deploy.plugin.model;

/**
 * 更新模式枚举
 *
 * <p>插件 UI 只暴露两档：</p>
 * <ul>
 *   <li>{@link #FULL} - 整包更新，将本地编译产物完整替换远程包</li>
 *   <li>{@link #INCREMENTAL} - 增量更新，列出可部署文件，Git 变更默认勾选，用户可调整</li>
 * </ul>
 *
 * <p>{@link #AUTO_DETECT} 仅供 CLI {@code patch-local} 子命令内部使用，
 * 表达"用 git status 自动取变更列表"的语义；插件 UI 不暴露这一项。</p>
 *
 * @author xumanyi
 * @date 2026-03-27
 */
public enum DeployMode {

    /** 整包更新：用户已编译好，CLI 不参与编译，整包替换 */
    FULL("整包更新"),
    /** 增量更新：列出可部署文件，Git 变更默认勾选，用户可在此基础上手动调整 */
    INCREMENTAL("增量更新"),
    /** （CLI 内部）仅供 {@code patch-local} 子命令在命令行下表达 "git 自动检测" 语义；插件 UI 不暴露 */
    AUTO_DETECT("自动检测变更（CLI 内部）");

    private final String displayName;

    /**
     * 构造更新模式
     *
     * @param displayName 显示名称（中文）
     * @author xumanyi
     * @date 2026-03-27
     */
    DeployMode(String displayName) {
        this.displayName = displayName;
    }

    /**
     * 返回显示名称
     *
     * @return 中文显示名称
     * @author xumanyi
     * @date 2026-03-27
     */
    @Override
    public String toString() {
        return displayName;
    }
}
