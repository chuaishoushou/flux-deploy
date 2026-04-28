package com.flux.deploy.util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * flux-deploy-cli 自有凭据缓存
 *
 * <p>缓存文件位置：{@code ~/.flux-deploy/credentials.toml}，权限 600。</p>
 * <p>与旧 AI Skill 的缓存完全独立，由本工具自行管理。</p>
 *
 * <p>生命周期：</p>
 * <ul>
 *   <li>首次 FTP 登录成功后自动保存</li>
 *   <li>后续运行自动复用</li>
 *   <li>登录失败时不覆盖已有缓存</li>
 * </ul>
 *
 * @author xumanyi
 * @date 2026-03-26
 */
public final class CredentialCache {

    /** 缓存目录 */
    private static final Path CACHE_DIR = Path.of(System.getProperty("user.home"), ".flux-deploy");

    /** 缓存文件 */
    private static final Path CACHE_FILE = CACHE_DIR.resolve("credentials.toml");

    /** 私有构造函数，防止实例化 */
    private CredentialCache() {}

    /**
     * 缓存条目，包含 FTP 连接所需的全部凭据信息
     */
    public static class CachedCredential {
        private final String ftpEndpoint;
        private final String host;
        private final int port;
        private final String username;
        private final String password;
        private final String verifiedAt;
        private final String lastWorkingDir;
        /** 协议：FTP / SFTP，默认 FTP（为后续 SFTP 支持预留字段） */
        private final String protocol;

        public CachedCredential(String ftpEndpoint, String host, int port,
                                String username, String password,
                                String verifiedAt, String lastWorkingDir) {
            this(ftpEndpoint, host, port, username, password, verifiedAt, lastWorkingDir, "FTP");
        }

        /**
         * 创建缓存条目（带协议字段）
         *
         * @param ftpEndpoint    端点（如 ftp://user@host:port/ 或 sftp://...）
         * @param host           主机
         * @param port           端口
         * @param username       用户名
         * @param password       密码（可能为加密格式）
         * @param verifiedAt     验证日期
         * @param lastWorkingDir 上次使用的远程工作目录
         * @param protocol       协议（FTP / SFTP），null 时按 FTP 处理
         * @author xumanyi
         * @date 2026-04-17
         */
        public CachedCredential(String ftpEndpoint, String host, int port,
                                String username, String password,
                                String verifiedAt, String lastWorkingDir,
                                String protocol) {
            this.ftpEndpoint = ftpEndpoint;
            this.host = host;
            this.port = port;
            this.username = username;
            this.password = password;
            this.verifiedAt = verifiedAt;
            this.lastWorkingDir = lastWorkingDir;
            this.protocol = (protocol == null || protocol.isBlank()) ? "FTP" : protocol.toUpperCase();
        }

        public String getFtpEndpoint() { return ftpEndpoint; }
        public String getHost() { return host; }
        public int getPort() { return port; }
        public String getUsername() { return username; }
        public String getPassword() { return password; }
        public String getVerifiedAt() { return verifiedAt; }
        public String getLastWorkingDir() { return lastWorkingDir; }
        public String getProtocol() { return protocol; }
    }

    /**
     * 获取缓存文件路径
     *
     * @return 缓存文件的 Path 对象
     * @author xumanyi
     * @date 2026-03-26
     */
    public static Path getCacheFile() {
        return CACHE_FILE;
    }

    /**
     * 获取默认凭据（什么参数都不传时使用）
     *
     * <p>如果缓存中只有一条记录，直接返回它（密码已解密）。
     * 多条记录时返回 null，需要用户指定。</p>
     */
    public static CachedCredential getDefault() {
        List<CachedCredential> all = loadAll();
        if (all.size() == 1) {
            try {
                return decryptCredential(all.get(0));
            } catch (RuntimeException e) {
                return null; // 该条解密失败（历史密钥不兼容），当作无默认处理
            }
        }
        return null;
    }

    /**
     * 按 host:port 和可选 username 查找缓存凭据
     *
     * <p>密码字段自动解密后返回。</p>
     *
     * @param host     FTP 主机（可为 null，此时按 username 匹配）
     * @param port     FTP 端口
     * @param username 用户名（可为 null，匹配任意用户名；有多个时返回 null）
     * @return 匹配的凭据（密码已解密），未找到或有歧义时返回 null
     * @author xumanyi
     * @date 2026-03-26
     */
    public static CachedCredential lookup(String host, int port, String username) {
        List<CachedCredential> all = loadAll();
        List<CachedCredential> matched = new ArrayList<>();

        for (CachedCredential c : all) {
            boolean hostMatch = (host == null || host.isBlank()) || endpointMatches(c.ftpEndpoint, host, port);
            boolean userMatch = (username == null || username.isBlank()) || username.equals(c.username);
            if (hostMatch && userMatch) {
                matched.add(c);
            }
        }

        if (matched.size() == 1) {
            return decryptCredential(matched.get(0));
        }
        return null;
    }

    /**
     * 解密凭据中的密码字段
     *
     * @param c 原始凭据（密码为加密串）
     * @return 密码已解密的凭据副本
     * @author xumanyi
     * @date 2026-03-26
     */
    private static CachedCredential decryptCredential(CachedCredential c) {
        String decryptedPassword = CryptoUtil.decrypt(c.password);
        return new CachedCredential(c.ftpEndpoint, c.host, c.port,
                c.username, decryptedPassword, c.verifiedAt, c.lastWorkingDir, c.protocol);
    }

    /**
     * 加载所有缓存凭据（密码已解密），用于插件 UI 列出多账号
     *
     * @return 解密后的凭据列表，无缓存时返回空列表
     * @author xumanyi
     * @date 2026-04-17
     */
    public static List<CachedCredential> loadAllDecrypted() {
        List<CachedCredential> all = loadAll();
        List<CachedCredential> out = new ArrayList<>(all.size());
        for (CachedCredential c : all) {
            try {
                out.add(decryptCredential(c));
            } catch (RuntimeException e) {
                // 单条解密失败（通常是历史密钥）：跳过，不影响其他账号
                System.err.println("[CredentialCache] 跳过无法解密的条目: " + c.getFtpEndpoint()
                        + " reason=" + e.getMessage());
            }
        }
        return out;
    }

    /**
     * 删除指定凭据（按 host/port/username 精确匹配）
     *
     * @param host     主机
     * @param port     端口
     * @param username 用户名
     * @return true=已删除，false=未找到
     * @author xumanyi
     * @date 2026-04-17
     */
    public static boolean delete(String host, int port, String username) {
        List<CachedCredential> all = loadAll();
        List<CachedCredential> remaining = new ArrayList<>(all.size());
        boolean removed = false;
        for (CachedCredential c : all) {
            if (endpointMatches(c.ftpEndpoint, host, port) && username.equals(c.username)) {
                removed = true;
            } else {
                remaining.add(c);
            }
        }
        if (removed) writeAll(remaining);
        return removed;
    }

    /**
     * 登录成功后保存或更新凭据
     *
     * @param host       FTP 主机
     * @param port       FTP 端口
     * @param username   用户名
     * @param password   密码
     * @param workingDir 本次使用的远程工作目录（可为 null）
     * @author xumanyi
     * @date 2026-03-26
     */
    public static void saveOrUpdate(String host, int port, String username,
                                    String password, String workingDir) {
        saveOrUpdate(host, port, username, password, workingDir, "FTP");
    }

    /**
     * 保存或更新凭据（带协议字段）
     *
     * @param host       主机
     * @param port       端口
     * @param username   用户名
     * @param password   密码
     * @param workingDir 远程工作目录（可为 null）
     * @param protocol   协议（FTP / SFTP）
     * @author xumanyi
     * @date 2026-04-17
     */
    public static void saveOrUpdate(String host, int port, String username,
                                    String password, String workingDir, String protocol) {
        String proto = (protocol == null || protocol.isBlank()) ? "FTP" : protocol.toLowerCase();
        String endpoint = proto + "://" + username + "@" + host + ":" + port + "/";
        String today = LocalDate.now().toString();

        List<CachedCredential> all = loadAll();

        boolean found = false;
        List<CachedCredential> updated = new ArrayList<>();
        for (CachedCredential c : all) {
            if (endpointMatches(c.ftpEndpoint, host, port) && username.equals(c.username)) {
                updated.add(new CachedCredential(endpoint, host, port, username, password, today,
                        workingDir != null ? workingDir : c.lastWorkingDir, proto.toUpperCase()));
                found = true;
            } else {
                updated.add(c);
            }
        }

        if (!found) {
            updated.add(new CachedCredential(endpoint, host, port, username, password, today, workingDir,
                    proto.toUpperCase()));
        }

        writeAll(updated);
    }

    // ==================== 内部方法 ====================

    /**
     * 读取所有缓存条目
     *
     * @return 缓存条目列表，文件不存在或读取失败时返回空列表
     * @author xumanyi
     * @date 2026-03-26
     */
    static List<CachedCredential> loadAll() {
        if (!Files.isReadable(CACHE_FILE)) {
            return new ArrayList<>();
        }
        try {
            String content = Files.readString(CACHE_FILE);
            return parseToml(content);
        } catch (IOException e) {
            return new ArrayList<>();
        }
    }

    /**
     * 将所有缓存条目写入 TOML 文件，密码字段自动加密
     *
     * @param credentials 缓存条目列表
     * @author xumanyi
     * @date 2026-03-26
     */
    private static void writeAll(List<CachedCredential> credentials) {
        try {
            // 确保目录存在
            if (!Files.exists(CACHE_DIR)) {
                Files.createDirectories(CACHE_DIR);
                try {
                    Files.setPosixFilePermissions(CACHE_DIR,
                            PosixFilePermissions.fromString("rwx------"));
                } catch (UnsupportedOperationException ignored) {
                    // Windows 不支持 POSIX 权限
                }
            }

            // 生成 TOML 内容
            StringBuilder sb = new StringBuilder();
            sb.append("# flux-deploy-cli 凭据缓存\n");
            sb.append("# 此文件由 flux-deploy-cli 自动管理，请勿手动编辑\n");
            sb.append("cache_version = 2\n");

            for (CachedCredential c : credentials) {
                sb.append("\n[[credential]]\n");
                sb.append("ftp_endpoint = \"").append(c.ftpEndpoint).append("\"\n");
                sb.append("protocol = \"").append(c.protocol == null ? "FTP" : c.protocol).append("\"\n");
                sb.append("username = \"").append(c.username).append("\"\n");
                String encPassword = CryptoUtil.isEncrypted(c.password)
                        ? c.password
                        : CryptoUtil.encrypt(c.password);
                sb.append("password = \"").append(encPassword).append("\"\n");
                sb.append("verified_at = \"").append(c.verifiedAt).append("\"\n");
                if (c.lastWorkingDir != null) {
                    sb.append("last_working_dir = \"").append(c.lastWorkingDir).append("\"\n");
                }
            }

            Files.writeString(CACHE_FILE, sb.toString());

            // 设置文件权限 600
            try {
                Files.setPosixFilePermissions(CACHE_FILE,
                        PosixFilePermissions.fromString("rw-------"));
            } catch (UnsupportedOperationException ignored) {
                // Windows 不支持 POSIX 权限
            }

        } catch (IOException e) {
            System.err.println("[警告] 凭据缓存写入失败: " + e.getMessage());
        }
    }

    /**
     * 简易 TOML 解析，按 [[credential]] 分段提取凭据信息
     *
     * @param content TOML 文件内容
     * @return 解析得到的缓存条目列表
     * @author xumanyi
     * @date 2026-03-26
     */
    static List<CachedCredential> parseToml(String content) {
        List<CachedCredential> result = new ArrayList<>();
        String[] sections = content.split("\\[\\[credential]]");
        for (int i = 1; i < sections.length; i++) {
            String section = sections[i];
            String endpoint = extractValue(section, "ftp_endpoint");
            String user = extractValue(section, "username");
            String pass = extractValue(section, "password");
            String verified = extractValue(section, "verified_at");
            String workDir = extractValue(section, "last_working_dir");
            String protocol = extractValue(section, "protocol");

            if (endpoint != null && user != null && pass != null) {
                // 从 endpoint 解析 host 和 port
                String[] hp = parseHostPort(endpoint);
                // 协议回填：新字段为空时按 endpoint scheme 推断
                String resolvedProto = protocol;
                if (resolvedProto == null || resolvedProto.isBlank()) {
                    resolvedProto = endpoint.toLowerCase().startsWith("sftp://") ? "SFTP" : "FTP";
                }
                result.add(new CachedCredential(endpoint, hp[0], Integer.parseInt(hp[1]),
                        user, pass, verified, workDir, resolvedProto));
            }
        }
        return result;
    }

    /**
     * 从 TOML 段落中提取指定 key 的值
     *
     * @param section TOML 段落文本
     * @param key     要提取的键名
     * @return 键对应的字符串值；未找到时返回 null
     * @author xumanyi
     * @date 2026-03-26
     */
    private static String extractValue(String section, String key) {
        Pattern p = Pattern.compile(key + "\\s*=\\s*\"([^\"]*)\"");
        Matcher m = p.matcher(section);
        return m.find() ? m.group(1) : null;
    }

    /**
     * 判断 endpoint 是否匹配指定的 host 和 port
     *
     * @param endpoint 凭据中存储的 endpoint 字符串
     * @param host     目标主机名或 IP
     * @param port     目标端口
     * @return 匹配时返回 true
     * @author xumanyi
     * @date 2026-03-26
     */
    private static boolean endpointMatches(String endpoint, String host, int port) {
        if (endpoint == null) return false;
        return endpoint.contains(host + ":" + port);
    }

    /**
     * 从 ftp://user@host:port/ 格式解析 [host, port]
     *
     * @param endpoint endpoint 字符串，格式如 {@code ftp://user@host:port/} 或 {@code ftp://host:port/}
     * @return 长度为 2 的数组 {@code [host, port]}；无端口时 port 默认为 "21"
     * @author xumanyi
     * @date 2026-03-26
     */
    private static String[] parseHostPort(String endpoint) {
        // ftp://user@host:port/ 或 ftp://host:port/
        String stripped = endpoint.replaceFirst("^ftp://", "");
        // 去掉 user@
        if (stripped.contains("@")) {
            stripped = stripped.substring(stripped.indexOf('@') + 1);
        }
        // 去掉尾部 /
        stripped = stripped.replaceFirst("/.*$", "");
        // host:port
        String[] parts = stripped.split(":");
        if (parts.length == 2) {
            return parts;
        }
        return new String[]{parts[0], "21"};
    }
}
