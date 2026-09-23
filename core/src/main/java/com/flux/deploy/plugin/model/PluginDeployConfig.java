package com.flux.deploy.plugin.model;

import java.util.List;

/**
 * 插件部署配置：聚合 UI 所有输入
 *
 * <p>从部署面板的各个 Section 收集用户输入，
 * 作为 {@link com.flux.deploy.plugin.service.DeployExecutionService} 的入参。</p>
 *
 * @author xumanyi
 * @date 2026-03-27
 */
public class PluginDeployConfig {

    /** 模块根目录路径 */
    private String modulePath;
    /** 产物文件名（如 scev6-utils-tms-10.0.0-SNAPSHOT.jar） */
    private String artifactFileName;
    /** 更新模式 */
    private DeployMode mode;
    /** 变更文件列表 */
    private List<String> changedFiles;
    /** 主目标包列表（支持同系统下多个同名 JAR 同时更新；单目标时长度为 1） */
    private List<FtpTargetSelection> mainTargets = new java.util.ArrayList<>();
    /** 任务号 */
    private String taskId;
    /** 客服号 */
    private String customerId;
    /** 操作人 */
    private String operator;
    /** 是否为预检模式 */
    private boolean dryRun;
    /** 是否更新版本记录 */
    private boolean updateNote = true;
    /** WAR 嵌入目标列表 */
    private List<FtpTargetSelection> embedTargets;
    /** 仅准备包，不上传 FTP */
    private boolean localOnly;
    /** 跳过备份 */
    private boolean skipBackup;
    /**
     * 备份冲突处理策略，仅在点击「打包并上传」前检测到同日同开发已有备份时生效；
     * 无冲突时此字段不参与逻辑。默认 OVERWRITE 兼容旧行为。
     */
    private BackupConflictStrategy backupConflictStrategy = BackupConflictStrategy.OVERWRITE;
    /** 部署目标模式（FTP / LOCAL），默认 FTP */
    private DeployTargetMode targetMode = DeployTargetMode.FTP;
    /** 本地模式目标信息（targetMode=LOCAL 时使用） */
    private LocalTargetSelection localTarget;

    /**
     * 获取模块根目录路径
     *
     * @return 模块根目录路径
     * @author xumanyi
     * @date 2026-03-27
     */
    public String getModulePath() { return modulePath; }

    /**
     * 设置模块根目录路径
     *
     * @param modulePath 模块根目录路径
     * @author xumanyi
     * @date 2026-03-27
     */
    public void setModulePath(String modulePath) { this.modulePath = modulePath; }

    /**
     * 获取产物文件名
     *
     * @return 产物文件名
     * @author xumanyi
     * @date 2026-03-27
     */
    public String getArtifactFileName() { return artifactFileName; }

    /**
     * 设置产物文件名
     *
     * @param artifactFileName 产物文件名
     * @author xumanyi
     * @date 2026-03-27
     */
    public void setArtifactFileName(String artifactFileName) { this.artifactFileName = artifactFileName; }

    /**
     * 获取更新模式
     *
     * @return 更新模式
     * @author xumanyi
     * @date 2026-03-27
     */
    public DeployMode getMode() { return mode; }

    /**
     * 设置更新模式
     *
     * @param mode 更新模式
     * @author xumanyi
     * @date 2026-03-27
     */
    public void setMode(DeployMode mode) { this.mode = mode; }

    /**
     * 获取变更文件列表
     *
     * @return 变更文件列表
     * @author xumanyi
     * @date 2026-03-27
     */
    public List<String> getChangedFiles() { return changedFiles; }

    /**
     * 设置变更文件列表
     *
     * @param changedFiles 变更文件列表
     * @author xumanyi
     * @date 2026-03-27
     */
    public void setChangedFiles(List<String> changedFiles) { this.changedFiles = changedFiles; }

    /**
     * 获取主目标包列表（可能多个同名 JAR 在不同子目录下）
     *
     * @return 主目标包列表
     * @author xumanyi
     * @date 2026-03-27
     */
    public List<FtpTargetSelection> getMainTargets() { return mainTargets; }

    /**
     * 设置主目标列表
     *
     * @param mainTargets 主目标列表；null 按空处理
     * @author xumanyi
     * @date 2026-03-27
     */
    public void setMainTargets(List<FtpTargetSelection> mainTargets) {
        this.mainTargets = mainTargets != null ? mainTargets : new java.util.ArrayList<>();
    }

    /**
     * 返回第一个主目标（兼容旧代码）。若没有主目标返回 null。
     *
     * @return 第一个主目标或 null
     * @author xumanyi
     * @date 2026-03-27
     */
    public FtpTargetSelection getTarget() {
        return mainTargets == null || mainTargets.isEmpty() ? null : mainTargets.get(0);
    }

    /**
     * 兼容旧代码：以单目标形式设置。内部转为长度 1 的 mainTargets。
     *
     * @param target 主目标；null 时清空
     * @author xumanyi
     * @date 2026-03-27
     */
    public void setTarget(FtpTargetSelection target) {
        this.mainTargets = new java.util.ArrayList<>();
        if (target != null) this.mainTargets.add(target);
    }

    /**
     * 获取任务号
     *
     * @return 任务号
     * @author xumanyi
     * @date 2026-03-27
     */
    public String getTaskId() { return taskId; }

    /**
     * 设置任务号
     *
     * @param taskId 任务号
     * @author xumanyi
     * @date 2026-03-27
     */
    public void setTaskId(String taskId) { this.taskId = taskId; }

    /**
     * 获取客服号
     *
     * @return 客服号
     * @author xumanyi
     * @date 2026-03-27
     */
    public String getCustomerId() { return customerId; }

    /**
     * 设置客服号
     *
     * @param customerId 客服号
     * @author xumanyi
     * @date 2026-03-27
     */
    public void setCustomerId(String customerId) { this.customerId = customerId; }

    /**
     * 获取操作人
     *
     * @return 操作人
     * @author xumanyi
     * @date 2026-03-27
     */
    public String getOperator() { return operator; }

    /**
     * 设置操作人
     *
     * @param operator 操作人
     * @author xumanyi
     * @date 2026-03-27
     */
    public void setOperator(String operator) { this.operator = operator; }

    /**
     * 判断是否为预检模式
     *
     * @return 是否为预检模式
     * @author xumanyi
     * @date 2026-03-27
     */
    public boolean isDryRun() { return dryRun; }

    /**
     * 设置是否为预检模式
     *
     * @param dryRun 是否为预检模式
     * @author xumanyi
     * @date 2026-03-27
     */
    public void setDryRun(boolean dryRun) { this.dryRun = dryRun; }

    /**
     * 判断是否更新版本记录
     *
     * @return 是否更新版本记录
     * @author xumanyi
     * @date 2026-03-27
     */
    public boolean isUpdateNote() { return updateNote; }

    /**
     * 设置是否更新版本记录
     *
     * @param updateNote 是否更新版本记录
     * @author xumanyi
     * @date 2026-03-27
     */
    public void setUpdateNote(boolean updateNote) { this.updateNote = updateNote; }

    /**
     * 获取 WAR 嵌入目标列表
     *
     * @return WAR 嵌入目标列表
     * @author xumanyi
     * @date 2026-03-27
     */
    public List<FtpTargetSelection> getEmbedTargets() { return embedTargets; }

    /**
     * 设置 WAR 嵌入目标列表
     *
     * @param embedTargets WAR 嵌入目标列表
     * @author xumanyi
     * @date 2026-03-27
     */
    public void setEmbedTargets(List<FtpTargetSelection> embedTargets) { this.embedTargets = embedTargets; }

    /**
     * 判断是否仅准备包（不上传 FTP）
     *
     * @return 是否仅准备包
     * @author xumanyi
     * @date 2026-03-27
     */
    public boolean isLocalOnly() { return localOnly; }

    /**
     * 设置是否仅准备包（不上传 FTP）
     *
     * @param localOnly 是否仅准备包
     * @author xumanyi
     * @date 2026-03-27
     */
    public void setLocalOnly(boolean localOnly) { this.localOnly = localOnly; }

    /**
     * 判断是否跳过备份
     *
     * @return 是否跳过备份
     * @author xumanyi
     * @date 2026-03-27
     */
    public boolean isSkipBackup() { return skipBackup; }

    /**
     * 设置是否跳过备份
     *
     * @param skipBackup 是否跳过备份
     * @author xumanyi
     * @date 2026-03-27
     */
    public void setSkipBackup(boolean skipBackup) { this.skipBackup = skipBackup; }

    /**
     * 获取部署目标模式
     *
     * @return 部署目标模式
     * @author xumanyi
     * @date 2026-03-27
     */
    public DeployTargetMode getTargetMode() { return targetMode; }

    /**
     * 设置部署目标模式
     *
     * @param targetMode 部署目标模式
     * @author xumanyi
     * @date 2026-03-27
     */
    public void setTargetMode(DeployTargetMode targetMode) {
        this.targetMode = targetMode == null ? DeployTargetMode.FTP : targetMode;
    }

    /**
     * 获取本地模式目标信息
     *
     * @return 本地模式目标信息
     * @author xumanyi
     * @date 2026-03-27
     */
    public LocalTargetSelection getLocalTarget() { return localTarget; }

    /**
     * 设置本地模式目标信息
     *
     * @param localTarget 本地模式目标信息
     * @author xumanyi
     * @date 2026-03-27
     */
    public void setLocalTarget(LocalTargetSelection localTarget) { this.localTarget = localTarget; }

    /**
     * 获取备份冲突处理策略
     *
     * @return 备份冲突处理策略
     * @author xumanyi
     * @date 2026-03-27
     */
    public BackupConflictStrategy getBackupConflictStrategy() { return backupConflictStrategy; }

    /**
     * 设置备份冲突处理策略
     *
     * @param strategy 备份冲突处理策略；null 按 OVERWRITE
     * @author xumanyi
     * @date 2026-03-27
     */
    public void setBackupConflictStrategy(BackupConflictStrategy strategy) {
        this.backupConflictStrategy = strategy == null ? BackupConflictStrategy.OVERWRITE : strategy;
    }

    /**
     * 自定义备份根目录（FTP 绝对路径）。
     *
     * <p>用户在 {@code BackupLocationDialog} 中选定的目录原样保存，
     * 不含日期/操作人子目录后缀。{@code DeployExecutionService.preBackupAll}
     * 会基于此路径计算最终备份目录 {@code customBackupRoot/yyyyMMdd_{operator}/}。</p>
     *
     * <p>为 null 或空字符串时表示用户未自定义，按 {@code resolveSystemRoot} 默认派生
     * （{前3级}/backup/）。</p>
     */
    private String customBackupRoot;

    /**
     * 获取自定义备份根目录
     *
     * @return 自定义备份根；未设置时返回 null
     * @author xumanyi
     * @date 2026-05-02
     */
    public String getCustomBackupRoot() { return customBackupRoot; }

    /**
     * 设置自定义备份根目录
     *
     * @param customBackupRoot 自定义备份根（FTP 绝对路径，含尾部 /）；null 表示走默认派生
     * @author xumanyi
     * @date 2026-05-02
     */
    public void setCustomBackupRoot(String customBackupRoot) {
        this.customBackupRoot = customBackupRoot;
    }

    /**
     * CSV 增量合并计划（可为 null）
     *
     * <p>部署启动时由插件层收集（git 未提交变更的 .csv 基线内容 + tablefieldlist
     * 业务主键字典）。非空时增量部署对 .csv 条目做行级合并，防止整份覆盖
     * 删除目标包中其他同事尚未提交 git 的行数据。</p>
     */
    private com.flux.deploy.csv.CsvMergePlan csvMergePlan;

    /** 源工程类型（默认 MAVEN；VUE 时产物为按模块打出的 zip） */
    private SourceProjectType sourceProjectType = SourceProjectType.MAVEN;

    /** Vue 工程上下文名（serve.yaml 的 content 值，如 tm01webVue；仅 VUE 类型使用） */
    private String vueContent;

    /** 选中的 Vue 业务模块号列表（如 [t0103, t0107]；仅 VUE 类型使用） */
    private List<String> vueModules;

    /**
     * Vue 目录直更的文件排除清单（模块目录 relativePath → 被用户取消勾选的产物相对路径列表）。
     *
     * <p>选择语义是排除式：默认整包（全部产物），用户在目标树的模块文件视图里做减法。
     * 用排除而非包含记录，是为了让"自动构建后新出现的产物文件"天然纳入上传
     * （新文件不可能出现在旧的排除清单里），避免 manifest 引用的新文件缺失。
     * 键缺失或值为空 = 该模块整包上传。</p>
     */
    private java.util.Map<String, List<String>> vueExcludedFiles;

    /**
     * 获取 Vue 目录直更的文件排除清单
     *
     * @return 排除清单；未设置为 null
     * @author xumanyi
     * @date 2026-08-13
     */
    public java.util.Map<String, List<String>> getVueExcludedFiles() { return vueExcludedFiles; }

    /**
     * 设置 Vue 目录直更的文件排除清单
     *
     * @param vueExcludedFiles 排除清单（模块目录 relativePath → 排除的产物相对路径）
     * @author xumanyi
     * @date 2026-08-13
     */
    public void setVueExcludedFiles(java.util.Map<String, List<String>> vueExcludedFiles) {
        this.vueExcludedFiles = vueExcludedFiles;
    }

    /**
     * 获取源工程类型
     *
     * @return 源工程类型（永不为 null）
     * @author xumanyi
     * @date 2026-08-13
     */
    public SourceProjectType getSourceProjectType() { return sourceProjectType; }

    /**
     * 设置源工程类型
     *
     * @param sourceProjectType 源工程类型；null 按 MAVEN
     * @author xumanyi
     * @date 2026-08-13
     */
    public void setSourceProjectType(SourceProjectType sourceProjectType) {
        this.sourceProjectType = sourceProjectType == null ? SourceProjectType.MAVEN : sourceProjectType;
    }

    /**
     * 获取 Vue 工程上下文名
     *
     * @return content 值；非 VUE 工程为 null
     * @author xumanyi
     * @date 2026-08-13
     */
    public String getVueContent() { return vueContent; }

    /**
     * 设置 Vue 工程上下文名
     *
     * @param vueContent serve.yaml 的 content 值
     * @author xumanyi
     * @date 2026-08-13
     */
    public void setVueContent(String vueContent) { this.vueContent = vueContent; }

    /**
     * 获取选中的 Vue 模块号列表
     *
     * @return 模块号列表；非 VUE 工程为 null
     * @author xumanyi
     * @date 2026-08-13
     */
    public List<String> getVueModules() { return vueModules; }

    /**
     * 设置选中的 Vue 模块号列表
     *
     * @param vueModules 模块号列表
     * @author xumanyi
     * @date 2026-08-13
     */
    public void setVueModules(List<String> vueModules) { this.vueModules = vueModules; }

    /**
     * 获取 CSV 增量合并计划
     *
     * @return 合并计划；未收集或无 CSV 变更时为 null
     * @author xumanyi
     * @date 2026-07-10
     */
    public com.flux.deploy.csv.CsvMergePlan getCsvMergePlan() { return csvMergePlan; }

    /**
     * 设置 CSV 增量合并计划
     *
     * @param csvMergePlan 合并计划；null 表示 CSV 保持整份覆盖
     * @author xumanyi
     * @date 2026-07-10
     */
    public void setCsvMergePlan(com.flux.deploy.csv.CsvMergePlan csvMergePlan) {
        this.csvMergePlan = csvMergePlan;
    }
}
