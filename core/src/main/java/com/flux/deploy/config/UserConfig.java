package com.flux.deploy.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 用户偏好配置文件 ~/.flux-deploy/config.toml 的读取与校验
 *
 * <p>独立于 credentials.toml（凭证由 CLI 自动管理）。
 * 本文件由用户手动编辑，配置失败策略与重试次数。</p>
 *
 * <p>非法值（未知策略名、重试次数越界、类型错误）一律抛 {@link IllegalArgumentException}，
 * 不静默使用默认值，避免用户改错文件而不自知。</p>
 *
 * <p>示例：</p>
 * <pre>
 * [failure_strategy]
 * mode = "isolated"
 *
 * [retry]
 * embed_max_retries = 1  # 嵌入阶段失败包的串行重试次数（默认 1，0 = 不重试）
 * backup_max_retries = 1 # 备份阶段失败包的串行重试次数（默认 1，0 = 不重试）
 * </pre>
 *
 * <p><b>已移除的 [parallelism] 块（自 2026-05-08 起）</b>：</p>
 * <ul>
 *   <li>FTP 操作（备份下载/上传、嵌入阶段上传）并发度统一固定为 3。
 *       理由：FTP 服务端的 max-clients-per-ip 由运维配置，与本机硬件无关；
 *       3 是对常见 FTP 服务端能拿到接近线性加速且不触发拒连的安全值。</li>
 *   <li>本地补丁/嵌入并发度固定为 1（串行执行）。
 *       理由：单包补丁是秒级本地操作（解 zip / 替换 class / 写回），
 *       性能瓶颈在 FTP 上传，本地多线程也只是等 FTP，调大无收益。</li>
 *   <li>存量配置文件中的 {@code [parallelism]} 块会被静默忽略，不再读取也不再校验，
 *       不影响启动；新生成的默认模板里不再包含此块。</li>
 * </ul>
 *
 * <p>重试机制：流水线主流程跑完后，对失败包按配置次数串行重试（每次完整 D-E-U）。
 * AUTH / ROLLBACK_FAILED 类错误不参与重试。</p>
 *
 * @author xumanyi
 * @date 2026-05-02
 */
public final class UserConfig {

    /** 默认配置文件路径 */
    private static final Path DEFAULT_PATH =
            Path.of(System.getProperty("user.home"), ".flux-deploy", "config.toml");

    /** 重试次数合法范围下界（0 = 不重试） */
    private static final int MIN_RETRIES = 0;
    /** 重试次数合法范围上界（避免极端配置） */
    private static final int MAX_RETRIES = 5;
    /** 默认嵌入阶段重试次数 */
    private static final int DEFAULT_EMBED_MAX_RETRIES = 1;
    /** 默认备份阶段重试次数 */
    private static final int DEFAULT_BACKUP_MAX_RETRIES = 1;

    /**
     * 首次使用时自动写入的默认配置模板（带注释）。
     *
     * <p>所有字段的值都与代码内 DEFAULT_* 常量一致；模板中的注释帮助用户理解每个字段的语义。
     * 此模板由 {@link #load()} 在 {@code ~/.flux-deploy/config.toml} 不存在时静默创建。</p>
     */
    private static final String DEFAULT_TEMPLATE = ""
            + "# flux-deploy-plugin 用户偏好配置\n"
            + "# 由用户手动编辑；非法值（越界/未知策略名/类型错误）会直接终止部署并提示\n"
            + "# 修改后无需重启 IDE，下次点击「打包并上传」立即生效\n"
            + "\n"
            + "# 注：FTP 并发固定为 3（受服务端 max-clients 限制约束），本地补丁串行执行\n"
            + "# （性能瓶颈在 FTP 上传，本地并发无收益）。无需配置并发参数。\n"
            + "\n"
            + "[failure_strategy]\n"
            + "# 失败策略：\n"
            + "#   isolated       = 失败的包独立回滚为旧版本，其他包继续（推荐）\n"
            + "#   rollback_all   = 任一失败 → 整体回滚\n"
            + "#   keep_succeeded = 任一失败 → 中止 + 保留已成功的，备份目录保留供手动回滚\n"
            + "mode = \"isolated\"\n"
            + "\n"
            + "[retry]\n"
            + "# 失败包的串行重试次数（[0, 5] 范围内，0 = 不重试）\n"
            + "# 重试是串行执行的，每次完整 D-E-U；AUTH 错误不参与重试\n"
            + "embed_max_retries = 1   # 嵌入阶段重试次数\n"
            + "backup_max_retries = 1  # 备份阶段重试次数\n";

    private final FailureStrategy failureStrategy;
    private final int embedMaxRetries;
    private final int backupMaxRetries;

    /**
     * 私有构造，外部通过 {@link #load()} / {@link #loadFrom(Path)} 创建
     *
     * @param strategy         失败策略
     * @param embedMaxRetries  嵌入阶段失败包的串行重试次数（0 = 不重试）
     * @param backupMaxRetries 备份阶段失败包的串行重试次数（0 = 不重试）
     * @author xumanyi
     * @date 2026-05-02
     */
    private UserConfig(FailureStrategy strategy, int embedMaxRetries, int backupMaxRetries) {
        this.failureStrategy = strategy;
        this.embedMaxRetries = embedMaxRetries;
        this.backupMaxRetries = backupMaxRetries;
    }

    /**
     * 从默认路径 ~/.flux-deploy/config.toml 加载配置
     *
     * <p>文件不存在时**首次使用会自动写入**带注释的默认模板，便于新用户发现可调项。
     * 创建失败（如权限不足）静默忽略，不影响部署——仍按代码内默认值返回。</p>
     *
     * @return 配置实例（文件不存在 / 创建失败时返回默认值）
     * @throws IllegalArgumentException 文件存在但内容非法
     * @author xumanyi
     * @date 2026-05-02
     */
    public static UserConfig load() {
        if (!Files.isReadable(DEFAULT_PATH)) {
            tryCreateDefaultTemplate(DEFAULT_PATH);
        }
        return loadFrom(DEFAULT_PATH);
    }

    /**
     * 默认配置文件是否存在（供调用方判断是否首次使用，便于打"已生成模板"日志）
     *
     * @return 默认路径下文件是否可读
     * @author xumanyi
     * @date 2026-05-02
     */
    public static boolean defaultConfigExists() {
        return Files.isReadable(DEFAULT_PATH);
    }

    /**
     * 默认配置文件路径
     *
     * @return ~/.flux-deploy/config.toml 的绝对路径
     * @author xumanyi
     * @date 2026-05-02
     */
    public static Path defaultConfigPath() {
        return DEFAULT_PATH;
    }

    /**
     * 静默尝试在指定路径写入默认模板
     *
     * <p>若父目录不存在则创建；写入后设权限 600（与 credentials.toml 一致）。
     * 任何失败都吞掉，不影响调用方流程。</p>
     *
     * @param path 目标路径
     * @author xumanyi
     * @date 2026-05-02
     */
    private static void tryCreateDefaultTemplate(Path path) {
        try {
            Path parent = path.getParent();
            if (parent != null && !Files.exists(parent)) {
                Files.createDirectories(parent);
                try {
                    Files.setPosixFilePermissions(parent,
                            PosixFilePermissions.fromString("rwx------"));
                } catch (UnsupportedOperationException ignored) {
                    // Windows 不支持 POSIX 权限
                }
            }
            Files.writeString(path, DEFAULT_TEMPLATE);
            try {
                Files.setPosixFilePermissions(path,
                        PosixFilePermissions.fromString("rw-------"));
            } catch (UnsupportedOperationException ignored) {
                // Windows 不支持 POSIX 权限
            }
        } catch (Exception ignored) {
            // 创建失败不影响部署：load 仍会返回代码内默认值
        }
    }

    /**
     * 从指定路径加载配置（用于测试）
     *
     * @param path TOML 文件路径
     * @return 配置实例
     * @throws IllegalArgumentException 内容非法
     * @author xumanyi
     * @date 2026-05-02
     */
    public static UserConfig loadFrom(Path path) {
        if (path == null || !Files.isReadable(path)) {
            return new UserConfig(FailureStrategy.defaultStrategy(),
                    DEFAULT_EMBED_MAX_RETRIES, DEFAULT_BACKUP_MAX_RETRIES);
        }
        String content;
        try {
            content = Files.readString(path);
        } catch (IOException e) {
            throw new IllegalArgumentException("无法读取配置文件: " + path + " - " + e.getMessage(), e);
        }

        // [parallelism] 块自 2026-05-08 起完全失效：旧配置中存在的字段被静默忽略，不再校验。
        // FTP 并发固定为 3、本地补丁串行执行，详见 DeployExecutionService 与 PipelineExecutor 内部常量。

        String modeRaw = parseStringField(content, "failure_strategy", "mode");
        FailureStrategy strategy = (modeRaw == null)
                ? FailureStrategy.defaultStrategy()
                : FailureStrategy.fromString(modeRaw);

        int embedMaxRetries = parseIntField(content, "retry", "embed_max_retries",
                DEFAULT_EMBED_MAX_RETRIES);
        validateRetries("retry.embed_max_retries", embedMaxRetries);

        int backupMaxRetries = parseIntField(content, "retry", "backup_max_retries",
                DEFAULT_BACKUP_MAX_RETRIES);
        validateRetries("retry.backup_max_retries", backupMaxRetries);

        return new UserConfig(strategy, embedMaxRetries, backupMaxRetries);
    }

    /**
     * 失败策略
     *
     * @return ISOLATED / ROLLBACK_ALL / KEEP_SUCCEEDED
     * @author xumanyi
     * @date 2026-05-02
     */
    public FailureStrategy getFailureStrategy() {
        return failureStrategy;
    }

    /**
     * 嵌入阶段失败包的串行重试次数
     *
     * @return [0, 5] 范围内的整数（0 = 不重试）
     * @author xumanyi
     * @date 2026-05-02
     */
    public int getEmbedMaxRetries() {
        return embedMaxRetries;
    }

    /**
     * 备份阶段失败包的串行重试次数
     *
     * @return [0, 5] 范围内的整数（0 = 不重试）
     * @author xumanyi
     * @date 2026-05-02
     */
    public int getBackupMaxRetries() {
        return backupMaxRetries;
    }

    /**
     * 校验重试次数在合法范围
     *
     * @param fieldName 字段全名
     * @param value     实际值
     * @throws IllegalArgumentException 越界
     * @author xumanyi
     * @date 2026-05-02
     */
    private static void validateRetries(String fieldName, int value) {
        if (value < MIN_RETRIES || value > MAX_RETRIES) {
            throw new IllegalArgumentException(
                    fieldName + " 必须在 [0, 5] 范围内，当前值: " + value);
        }
    }

    /**
     * 从 TOML 文本中按 [section] / key 提取整数字段
     *
     * @param content      TOML 全文
     * @param section      章节名
     * @param key          字段名
     * @param defaultValue section 或 key 不存在时使用
     * @return 解析得到的整数
     * @throws IllegalArgumentException 字段存在但不是合法整数
     * @author xumanyi
     * @date 2026-05-02
     */
    private static int parseIntField(String content, String section, String key, int defaultValue) {
        String raw = parseStringOrIntField(content, section, key);
        if (raw == null) return defaultValue;
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    section + "." + key + " 必须是整数，当前值: " + raw);
        }
    }

    /**
     * 从 TOML 文本中按 [section] / key 提取字符串字段
     *
     * @param content TOML 全文
     * @param section 章节名
     * @param key     字段名
     * @return 字段值（不含引号）；不存在返回 null
     * @author xumanyi
     * @date 2026-05-02
     */
    private static String parseStringField(String content, String section, String key) {
        return parseStringOrIntField(content, section, key);
    }

    /**
     * 简易 TOML 单字段解析（不支持嵌套 / 数组 / 多行字符串）
     *
     * <p>限定 [section] 段内查找：从 {@code [section]} 行起，到下一个 {@code [xxx]} 行止。
     * 支持行尾注释（# 起始；位于双引号内的 # 仍为值的一部分）。</p>
     *
     * @param content TOML 全文
     * @param section 章节名
     * @param key     字段名
     * @return 字段值（去除可能的引号 + 行尾注释）；不存在返回 null
     * @author xumanyi
     * @date 2026-05-02
     */
    private static String parseStringOrIntField(String content, String section, String key) {
        Pattern sectionPat = Pattern.compile(
                "(?ms)^\\s*\\[\\s*" + Pattern.quote(section) + "\\s*]\\s*$(.*?)(^\\s*\\[|\\z)");
        Matcher sm = sectionPat.matcher(content);
        if (!sm.find()) return null;
        String body = sm.group(1);
        Pattern keyPat = Pattern.compile(
                "(?m)^\\s*" + Pattern.quote(key) + "\\s*=\\s*(.+?)\\s*$");
        Matcher km = keyPat.matcher(body);
        if (!km.find()) return null;
        String val = km.group(1).trim();

        // 去除行尾注释（"# 之后到行尾"为注释，但若 # 在双引号内则属于值）
        val = stripLineComment(val).trim();

        // 剥外层双引号
        if (val.startsWith("\"") && val.endsWith("\"") && val.length() >= 2) {
            val = val.substring(1, val.length() - 1);
        }
        return val;
    }

    /**
     * 去除字符串中"非引号内的 # 起的"行尾注释
     *
     * @param s 原始值字符串（可能含行尾注释）
     * @return 去掉注释后的字符串；无注释返回原串
     * @author xumanyi
     * @date 2026-05-02
     */
    private static String stripLineComment(String s) {
        boolean inQuote = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"') {
                inQuote = !inQuote;
            } else if (c == '#' && !inQuote) {
                return s.substring(0, i);
            }
        }
        return s;
    }
}
