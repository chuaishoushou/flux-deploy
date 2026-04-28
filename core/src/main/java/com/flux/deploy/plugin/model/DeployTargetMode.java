package com.flux.deploy.plugin.model;

/**
 * 部署目标模式
 *
 * <p>FTP 模式：连接 FTP 选择远程目标包（默认）。
 * LOCAL 模式：用户提供本地 jar/war 包，不走 FTP，仅在本地生成打补丁后的新包。</p>
 *
 * @author xumanyi
 * @date 2026-04-17
 */
public enum DeployTargetMode {
    /** FTP 模式 */
    FTP("FTP 模式"),
    /** 本地包模式 */
    LOCAL("本地模式");

    private final String displayName;

    DeployTargetMode(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() { return displayName; }
}
