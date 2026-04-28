package com.flux.deploy.plugin.model;

/**
 * 本地模式目标选择：用户手动提供的包文件 + 输出目录
 *
 * @author xumanyi
 * @date 2026-04-17
 */
public class LocalTargetSelection {

    /** 用户选择的本地包绝对路径（jar / war） */
    private final String packagePath;
    /** 输出目录绝对路径（新包写入此目录） */
    private final String outputDir;

    public LocalTargetSelection(String packagePath, String outputDir) {
        this.packagePath = packagePath;
        this.outputDir = outputDir;
    }

    public String getPackagePath() { return packagePath; }

    public String getOutputDir() { return outputDir; }
}
