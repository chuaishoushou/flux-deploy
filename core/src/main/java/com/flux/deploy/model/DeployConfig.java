package com.flux.deploy.model;

import java.nio.file.Path;
import java.util.List;

/**
 * 部署配置（所有输入参数的聚合）
 *
 * <p>包含 FTP 连接信息、目标包信息、操作人信息、运行模式等全部部署参数。</p>
 *
 * @author xumanyi
 * @date 2026-03-26
 */
public class DeployConfig {

    /** FTP 主机地址 */
    private String host;

    /** FTP 端口 */
    private int port = 18080;

    /** FTP 用户名 */
    private String username;

    /** FTP 密码 */
    private String password;

    /** 远程工作目录（FTP 工作目录） */
    private String remoteDir;

    /** 本地暂存包文件路径列表 */
    private List<Path> localFiles;

    /** 目标包文件名列表（与 localFiles 一一对应） */
    private List<String> targetNames;

    /** 目标包在远程目录中的相对路径（如 tmssrv-1/tm01srv.war） */
    private List<String> targetRelativePaths;

    /** 任务号 */
    private String taskId;

    /** 客服号 */
    private String customerId;

    /** 操作人（英文 id） */
    private String operator;

    /** 备份目录名（默认自动生成 YYYYMMDD_operator） */
    private String backupDirName;

    /** 是否为 dry-run 模式 */
    private boolean dryRun;

    /** 是否跳过版本记录更新 */
    private boolean skipNote;

    /** 是否跳过备份（已在预备份阶段完成时设置） */
    private boolean skipBackup;

    /** 是否跳过加锁/解锁（已在外部统一加锁时设置） */
    private boolean skipLock;

    /** WAR 嵌入目标列表（JAR 更新时，需要嵌入到哪些 WAR 中） */
    private List<EmbedTarget> embedTargets;

    /** JAR 的 artifactId 前缀（用于匹配 WAR 中 WEB-INF/lib 的旧 jar） */
    private String jarArtifactId;

    /** 输出格式：json / text */
    private String outputFormat = "text";

    /** 更新模式：full / incremental（默认 full，整包替换） */
    private String mode = "full";

    /** 模块根目录（incremental 模式必填，供 mvn 编译与 StagingPackageBuilder 定位） */
    private String projectDir;

    /** 变更源文件列表（相对 projectDir），可含 git status 前缀 "M path" / "D path" 等 */
    private List<String> changedFiles;

    /**
     * WAR 嵌入目标，描述需要嵌入 JAR 的 WAR 包信息
     */
    public static class EmbedTarget {
        private final String warName;
        private final String warRelativePath;

        /**
         * 创建 WAR 嵌入目标
         *
         * @param warName          WAR 包文件名
         * @param warRelativePath  WAR 包在远程目录中的相对路径
     * @author xumanyi
     * @date 2026-03-26
     */
        public EmbedTarget(String warName, String warRelativePath) {
            this.warName = warName;
            this.warRelativePath = warRelativePath;
        }

        /** 获取 WAR 包文件名 */
        public String getWarName() { return warName; }
        /** 获取 WAR 包在远程目录中的相对路径 */
        public String getWarRelativePath() { return warRelativePath; }
    }

    // ========== Getters & Setters ==========

    public String getHost() { return host; }
    public void setHost(String host) { this.host = host; }

    public int getPort() { return port; }
    public void setPort(int port) { this.port = port; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }

    public String getRemoteDir() { return remoteDir; }
    public void setRemoteDir(String remoteDir) { this.remoteDir = remoteDir; }

    public List<Path> getLocalFiles() { return localFiles; }
    public void setLocalFiles(List<Path> localFiles) { this.localFiles = localFiles; }

    public List<String> getTargetNames() { return targetNames; }
    public void setTargetNames(List<String> targetNames) { this.targetNames = targetNames; }

    public List<String> getTargetRelativePaths() { return targetRelativePaths; }
    public void setTargetRelativePaths(List<String> targetRelativePaths) { this.targetRelativePaths = targetRelativePaths; }

    public String getTaskId() { return taskId; }
    public void setTaskId(String taskId) { this.taskId = taskId; }

    public String getCustomerId() { return customerId; }
    public void setCustomerId(String customerId) { this.customerId = customerId; }

    public String getOperator() { return operator; }
    public void setOperator(String operator) { this.operator = operator; }

    public String getBackupDirName() { return backupDirName; }
    public void setBackupDirName(String backupDirName) { this.backupDirName = backupDirName; }

    public boolean isDryRun() { return dryRun; }
    public void setDryRun(boolean dryRun) { this.dryRun = dryRun; }

    public boolean isSkipNote() { return skipNote; }
    public void setSkipNote(boolean skipNote) { this.skipNote = skipNote; }

    public boolean isSkipBackup() { return skipBackup; }
    public void setSkipBackup(boolean skipBackup) { this.skipBackup = skipBackup; }

    public boolean isSkipLock() { return skipLock; }
    public void setSkipLock(boolean skipLock) { this.skipLock = skipLock; }

    public List<EmbedTarget> getEmbedTargets() { return embedTargets; }
    public void setEmbedTargets(List<EmbedTarget> embedTargets) { this.embedTargets = embedTargets; }

    public String getJarArtifactId() { return jarArtifactId; }
    public void setJarArtifactId(String jarArtifactId) { this.jarArtifactId = jarArtifactId; }

    public String getOutputFormat() { return outputFormat; }
    public void setOutputFormat(String outputFormat) { this.outputFormat = outputFormat; }

    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = (mode == null || mode.isBlank()) ? "full" : mode; }

    public String getProjectDir() { return projectDir; }
    public void setProjectDir(String projectDir) { this.projectDir = projectDir; }

    public List<String> getChangedFiles() { return changedFiles; }
    public void setChangedFiles(List<String> changedFiles) { this.changedFiles = changedFiles; }
}
