package com.flux.deploy.cli;

import com.flux.deploy.maven.MavenRunner;
import com.flux.deploy.model.DeployConfig;
import com.flux.deploy.util.CredentialCache;
import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 加载 JSON 配置并映射为核心 {@link DeployConfig}。
 *
 * <p>校验由本类承担，失败时抛出 {@link InvalidConfigException} 并由 CLI 转为退出码 64。</p>
 *
 * @author xumanyi
 * @date 2026-03-21
 */
public final class ConfigLoader {

    private static final Gson GSON = new Gson();

    private ConfigLoader() {
    }

    /**
     * 从磁盘读取配置
     *
     * @param configPath     JSON 配置文件路径
     * @param passwordFile   可选的密码文件路径；若非空，读取首行并覆盖 ftp.password
     * @param dryRunOverride 若为 true，强制 options.dryRun = true
     * @return 构造完成的 {@link DeployConfig}
     * @throws InvalidConfigException 任一校验失败
     * @author xumanyi
     * @date 2026-03-21
     */
    public static DeployConfig load(Path configPath, Path passwordFile, boolean dryRunOverride) {
        CliConfig cli = readJson(configPath);

        // FTP 段
        requireNonNull(cli.ftp, "ftp");
        requireNonBlank(cli.ftp.host, "ftp.host");
        requireNonBlank(cli.ftp.username, "ftp.username");

        int port = cli.ftp.port == null ? 18080 : cli.ftp.port;
        String password = resolvePassword(cli.ftp.password, passwordFile, cli.ftp.host, port, cli.ftp.username);
        if (password == null || password.isEmpty()) {
            throw new InvalidConfigException(
                    "缺少密码：请通过 --password-file 提供，或确认 ~/.flux-deploy/credentials.toml 中有匹配的缓存凭据");
        }

        // 远程目录
        requireNonBlank(cli.remoteDir, "remoteDir");

        // 更新模式：full / incremental（空值视为 full）。
        // 「文件来源」由调用方负责（手动 / git 检测），CLI 只在 incremental 模式下接 files。
        String mode = cli.mode == null || cli.mode.isBlank() ? "full" : cli.mode.toLowerCase();
        if (!mode.equals("full") && !mode.equals("incremental")) {
            throw new InvalidConfigException("mode 非法，仅接受 full / incremental");
        }
        boolean needStaging = mode.equals("incremental");

        // targets 段
        if (cli.targets == null || cli.targets.isEmpty()) {
            throw new InvalidConfigException("targets 不能为空");
        }
        List<Path> localFiles = new ArrayList<>();
        List<String> names = new ArrayList<>();
        List<String> relatives = new ArrayList<>();
        boolean anyRelative = false;
        for (int i = 0; i < cli.targets.size(); i++) {
            CliConfig.Target t = cli.targets.get(i);
            if (t == null) {
                throw new InvalidConfigException("targets[" + i + "] 为 null");
            }
            Path local;
            if (needStaging) {
                // incremental：localFile 由流水线内 StagingPackageBuilder 动态生成，允许为空
                local = (t.localFile != null && !t.localFile.isBlank()) ? Paths.get(t.localFile) : null;
                // incremental 模式必须有目标包名（StagingPackageBuilder 需要下载它）
                requireNonBlank(t.name, "targets[" + i + "].name（incremental 模式必填）");
            } else {
                requireNonBlank(t.localFile, "targets[" + i + "].localFile");
                local = Paths.get(t.localFile);
                if (!Files.isRegularFile(local)) {
                    throw new InvalidConfigException("targets[" + i + "].localFile 不存在或不是文件: " + local);
                }
            }
            localFiles.add(local);
            names.add(t.name);
            relatives.add(t.relativePath);
            if (t.relativePath != null && !t.relativePath.isBlank()) {
                anyRelative = true;
            }
        }

        // 构造 DeployConfig
        DeployConfig cfg = new DeployConfig();
        cfg.setHost(cli.ftp.host);
        cfg.setPort(port);
        cfg.setUsername(cli.ftp.username);
        cfg.setPassword(password);
        cfg.setRemoteDir(cli.remoteDir);
        cfg.setLocalFiles(localFiles);
        cfg.setTargetNames(names);
        cfg.setTargetRelativePaths(anyRelative ? relatives : null);

        if (cli.meta != null) {
            cfg.setTaskId(cli.meta.taskId);
            cfg.setCustomerId(cli.meta.customerId);
            cfg.setOperator(cli.meta.operator);
            cfg.setBackupDirName(cli.meta.backupDirName);
        }
        if (cli.options != null) {
            cfg.setDryRun(Boolean.TRUE.equals(cli.options.dryRun));
            cfg.setSkipBackup(Boolean.TRUE.equals(cli.options.skipBackup));
            cfg.setSkipLock(Boolean.TRUE.equals(cli.options.skipLock));
            cfg.setSkipNote(Boolean.TRUE.equals(cli.options.skipNote));
            if (cli.options.outputFormat != null && !cli.options.outputFormat.isBlank()) {
                cfg.setOutputFormat(cli.options.outputFormat);
            }
        }
        if (dryRunOverride) {
            cfg.setDryRun(true);
        }
        cfg.setJarArtifactId(cli.jarArtifactId);
        cfg.setMode(mode);
        cfg.setProjectDir(cli.projectDir);

        // incremental 模式：校验 projectDir、要求 files 由调用方提供、强制 mvn 编译。
        // CLI 不再内置 git 检测——「文件来源」是调用方（插件 UI / Skill）的职责。
        if (needStaging) {
            if (cli.projectDir == null || cli.projectDir.isBlank()) {
                throw new InvalidConfigException("mode=incremental 需要 projectDir（含 pom.xml 的模块根）");
            }
            Path projectDir = Paths.get(cli.projectDir).toAbsolutePath().normalize();
            if (!Files.isDirectory(projectDir)) {
                throw new InvalidConfigException("projectDir 不存在或不是目录: " + projectDir);
            }
            if (!Files.isRegularFile(projectDir.resolve("pom.xml"))) {
                throw new InvalidConfigException("projectDir 内未找到 pom.xml: " + projectDir);
            }

            // 变更列表必须由调用方显式给出
            if (cli.files == null || cli.files.isEmpty()) {
                throw new InvalidConfigException(
                        "mode=incremental 需要 files（变更文件列表）；"
                      + "CLI 不内置 git 检测，由调用方（插件 UI / Skill）通过 git status 或手动指定产出");
            }
            cfg.setChangedFiles(cli.files);

            // 强制 mvn clean package -DskipTests，保证字节码与变更列表一致
            System.err.println("[ConfigLoader] 编译模块: " + projectDir);
            try {
                int exit = new MavenRunner(projectDir, cli.javaHome, cli.mavenHome, System.err::println)
                        .cleanPackage();
                if (exit != 0) {
                    throw new InvalidConfigException("mvn 编译失败，退出码 " + exit);
                }
            } catch (IOException | InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new InvalidConfigException("mvn 编译异常: " + e.getMessage());
            }
        }
        if (cli.embedTargets != null && !cli.embedTargets.isEmpty()) {
            List<DeployConfig.EmbedTarget> list = new ArrayList<>();
            for (CliConfig.Embed e : cli.embedTargets) {
                requireNonBlank(e == null ? null : e.warName, "embedTargets[].warName");
                list.add(new DeployConfig.EmbedTarget(e.warName, e.warRelativePath));
            }
            cfg.setEmbedTargets(list);
        }
        return cfg;
    }

    /**
     * 读取 JSON 并反序列化为 {@link CliConfig}
     *
     * @param path JSON 配置文件路径
     * @return 反序列化后的 {@link CliConfig} 对象
     * @throws InvalidConfigException 文件不存在、JSON 语法错误或读取失败
     * @author xumanyi
     * @date 2026-03-21
     */
    private static CliConfig readJson(Path path) {
        Objects.requireNonNull(path, "config path");
        if (!Files.isRegularFile(path)) {
            throw new InvalidConfigException("配置文件不存在: " + path);
        }
        try {
            String json = Files.readString(path, StandardCharsets.UTF_8);
            CliConfig cfg = GSON.fromJson(json, CliConfig.class);
            if (cfg == null) {
                throw new InvalidConfigException("配置文件为空或非法 JSON: " + path);
            }
            return cfg;
        } catch (JsonSyntaxException e) {
            throw new InvalidConfigException("JSON 语法错误 (" + path + "): " + e.getMessage());
        } catch (IOException e) {
            throw new InvalidConfigException("读取配置文件失败 (" + path + "): " + e.getMessage());
        }
    }

    /**
     * 按优先级解析 FTP 密码：{@code --password-file} > JSON {@code ftp.password} > 本机凭据缓存
     *
     * <p>凭据缓存（{@code ~/.flux-deploy/credentials.toml}）由 CLI 自身管理，密码已加密存储；
     * 此处通过 {@link CredentialCache#lookup} 自动解密后返回明文。</p>
     *
     * @param inline       JSON 中内联的密码，可为 null
     * @param passwordFile 密码文件路径，可为 null
     * @param host         FTP 主机（用于凭据缓存匹配）
     * @param port         FTP 端口（用于凭据缓存匹配）
     * @param username     FTP 用户名（用于凭据缓存匹配）
     * @return 密码明文；未找到时返回 null
     * @author xumanyi
     * @date 2026-03-21
     */
    private static String resolvePassword(String inline, Path passwordFile,
                                          String host, int port, String username) {
        if (passwordFile != null) {
            if (!Files.isRegularFile(passwordFile)) {
                throw new InvalidConfigException("--password-file 不存在: " + passwordFile);
            }
            try {
                List<String> lines = Files.readAllLines(passwordFile, StandardCharsets.UTF_8);
                for (String line : lines) {
                    if (line != null && !line.isEmpty()) {
                        return line;
                    }
                }
                throw new InvalidConfigException("--password-file 内容为空: " + passwordFile);
            } catch (IOException e) {
                throw new InvalidConfigException("读取 --password-file 失败: " + e.getMessage());
            }
        }
        if (inline != null && !inline.isBlank()) {
            return inline;
        }
        // 查本机凭据缓存（自动解密；解密失败时降级为空，不 crash）
        try {
            CredentialCache.CachedCredential cached = CredentialCache.lookup(host, port, username);
            if (cached != null && cached.getPassword() != null && !cached.getPassword().isBlank()) {
                return cached.getPassword();
            }
        } catch (RuntimeException ignored) {
            // 缓存密码解密失败（可能是旧版工具写入），忽略并提示用户重新提供
        }
        return null;
    }

    /**
     * 要求值非 null，否则抛 {@link InvalidConfigException}
     *
     * @param v     待检查的值
     * @param field 字段名（用于异常信息）
     * @author xumanyi
     * @date 2026-03-21
     */
    private static void requireNonNull(Object v, String field) {
        if (v == null) {
            throw new InvalidConfigException("缺少字段: " + field);
        }
    }

    /**
     * 要求字符串非 null 且非空白
     *
     * @param v     待检查的字符串
     * @param field 字段名（用于异常信息）
     * @author xumanyi
     * @date 2026-03-21
     */
    private static void requireNonBlank(String v, String field) {
        if (v == null || v.isBlank()) {
            throw new InvalidConfigException("缺少字段: " + field);
        }
    }

    /** 配置校验失败异常；由 CLI 统一映射为退出码 64。 */
    public static final class InvalidConfigException extends RuntimeException {
        public InvalidConfigException(String message) {
            super(message);
        }
    }
}
