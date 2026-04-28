package com.flux.deploy.cli;

import java.util.List;

/**
 * CLI JSON 配置文件的反序列化 POJO。
 *
 * <p>结构对应 {@code deploy.json} 的顶层对象，字段尽量扁平、命名贴近
 * {@link com.flux.deploy.model.DeployConfig}，便于 Skill / 人工直接拼装。</p>
 *
 * <pre>{@code
 * {
 *   "ftp":       { "host": "...", "port": 18080, "username": "...", "password": "..." },
 *   "remoteDir": "/开发/.../TMS V9.0-P7/",
 *   "targets":   [ { "localFile": "...", "name": "tm01srv.war", "relativePath": "..." } ],
 *   "meta":      { "taskId": "...", "customerId": "...", "operator": "...", "backupDirName": null },
 *   "options":   { "dryRun": false, "skipBackup": false, "skipLock": false, "skipNote": false,
 *                  "outputFormat": "json" },
 *   "jarArtifactId":  "tmsv6-tm10-srv",
 *   "embedTargets":   [ { "warName": "tm10srv.war", "warRelativePath": "tmssrv-1/tm10srv.war" } ]
 * }
 * }</pre>
 *
 * @author xumanyi
 * @date 2026-03-21
 */
public final class CliConfig {

    public Ftp ftp;
    public String remoteDir;
    public List<Target> targets;
    public Meta meta;
    public Options options;
    public String jarArtifactId;
    public List<Embed> embedTargets;

    /**
     * 更新模式：{@code full} | {@code incremental}，省略按 {@code full}。
     * <ul>
     *   <li>{@code full}：用户提供已编译好的 jar/war，CLI 不参与编译</li>
     *   <li>{@code incremental}：用户提供 {@code files} 变更列表，CLI 强制 mvn package 后按变更打补丁</li>
     * </ul>
     * 「文件来源」（手动选 / git 检测）由调用方（插件 UI / Skill）负责，CLI 只接最终列表。
     */
    public String mode;

    /**
     * incremental 模式必填：模块根目录（含 pom.xml），用作 mvn 工作目录。
     */
    public String projectDir;

    /**
     * incremental 模式必填：相对 {@code projectDir} 的变更文件列表；
     * 支持 Git 状态前缀（如 {@code "M src/main/java/Foo.java"}），也可裸路径。
     * <p>列表由调用方提供（用户手动勾选 / 插件 UI 预填 git status / Skill 跑 git status 后用户确认），
     * CLI 不再内置 git 检测。</p>
     */
    public List<String> files;

    /**
     * 可选：MAVEN_HOME；省略时 CLI 按 mvnw → MAVEN_HOME env → 交互式 shell which mvn 顺序查找。
     */
    public String mavenHome;

    /**
     * 可选：JAVA_HOME；省略时继承 CLI 进程环境。
     */
    public String javaHome;

    /** FTP 连接参数；{@code password} 字段可省略，改由 CLI 的 {@code --password-file} 提供。 */
    public static final class Ftp {
        public String host;
        public Integer port;
        public String username;
        public String password;
    }

    /** 单个目标包：本地暂存文件、远程文件名、可选的远程相对路径。 */
    public static final class Target {
        public String localFile;
        public String name;
        public String relativePath;
    }

    /** 部署元信息，用于备份目录命名与 note 追加。 */
    public static final class Meta {
        public String taskId;
        public String customerId;
        public String operator;
        public String backupDirName;
    }

    /** 运行时开关。 */
    public static final class Options {
        public Boolean dryRun;
        public Boolean skipBackup;
        public Boolean skipLock;
        public Boolean skipNote;
        public String outputFormat;
    }

    /** WAR 嵌入目标：仅当目标是 JAR 且需要嵌入某些 WAR 的 {@code WEB-INF/lib} 时使用。 */
    public static final class Embed {
        public String warName;
        public String warRelativePath;
    }
}
