package com.flux.deploy.ftp;

import org.apache.commons.net.ftp.FTP;
import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.net.ftp.FTPReply;

import java.io.Closeable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * FTP 连接会话管理
 *
 * <p>封装 FTP 连接生命周期：连接、认证、被动模式、UTF-8 编码、超时控制。</p>
 * <p>实现 Closeable 以支持 try-with-resources。</p>
 *
 * @author xumanyi
 * @date 2026-03-26
 */
public class FtpSession implements Closeable {

    private static final int DEFAULT_CONNECT_TIMEOUT_MS = 15_000;
    private static final int DEFAULT_DATA_TIMEOUT_MS = 300_000;
    private static final Duration CONTROL_KEEP_ALIVE_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration CONTROL_KEEP_ALIVE_REPLY_TIMEOUT = Duration.ofSeconds(10);
    private static final int BUFFER_SIZE = 1024 * 1024;

    private final FTPClient ftpClient;
    private final String host;
    private final int port;
    private boolean connected;

    // 缓存最近一次 connect() 用的凭证，供 reconnect() 使用。
    // 仅在内存中保留，对象销毁后即随 GC 释放，不落盘、不打日志、不出 toString。
    private String lastUsername;
    private String lastPassword;

    /**
     * 创建 FTP 会话实例
     *
     * @param host FTP 主机地址
     * @param port FTP 端口号
     * @author xumanyi
     * @date 2026-03-26
     */
    public FtpSession(String host, int port) {
        this.host = host;
        this.port = port;
        this.ftpClient = new FTPClient();
        this.connected = false;
    }

    /**
     * 连接并认证 FTP 服务器
     *
     * @param username FTP 用户名
     * @param password FTP 密码
     * @throws IOException 连接或认证失败
     * @author xumanyi
     * @date 2026-03-26
     */
    public void connect(String username, String password) throws IOException {
        // 缓存凭证供 reconnect() 复用；后续即便 connect 抛错也不清，留给 reconnect 自己再失败一次
        this.lastUsername = username;
        this.lastPassword = password;
        ftpClient.setConnectTimeout(DEFAULT_CONNECT_TIMEOUT_MS);
        ftpClient.setDefaultTimeout(DEFAULT_CONNECT_TIMEOUT_MS);
        ftpClient.setControlEncoding(StandardCharsets.UTF_8.name());

        ftpClient.connect(host, port);
        int replyCode = ftpClient.getReplyCode();
        if (!FTPReply.isPositiveCompletion(replyCode)) {
            ftpClient.disconnect();
            throw new IOException("FTP 连接被拒绝，响应码: " + replyCode);
        }

        boolean loginOk = ftpClient.login(username, password);
        if (!loginOk) {
            ftpClient.disconnect();
            throw new IOException("FTP 认证失败，用户名或密码错误");
        }

        // 被动模式（适用于 NAT/防火墙环境）
        ftpClient.enterLocalPassiveMode();
        // 二进制传输模式
        ftpClient.setFileType(FTP.BINARY_FILE_TYPE);
        // 数据传输超时
        ftpClient.setDataTimeout(Duration.ofMillis(DEFAULT_DATA_TIMEOUT_MS));
        // 单次大文件 storeFile/retrieveFile 期间，commons-net 会按此间隔在控制通道上自动发 NOOP，
        // 避免服务端因数据传输期间控制通道空闲而提前关闭连接（FTP 421）。
        // 注意：此机制仅在单次数据传输期间生效，不会在两次 FTP 命令之间做心跳。
        ftpClient.setControlKeepAliveTimeout(CONTROL_KEEP_ALIVE_TIMEOUT);
        ftpClient.setControlKeepAliveReplyTimeout(CONTROL_KEEP_ALIVE_REPLY_TIMEOUT);
        // 缓冲区
        ftpClient.setBufferSize(BUFFER_SIZE);

        this.connected = true;
    }

    /**
     * 断线重连：先 disconnect 当前 socket，再用上次成功的凭证 connect 一次。
     *
     * <p>用于 NETWORK 类错误后的重试场景：旧的控制连接 socket 多半已经半死状态
     * （RST / FIN_WAIT），继续复用会触发同样的 IOException。必须显式销毁
     * {@link FTPClient} 内部 socket 后重建。</p>
     *
     * <p>未曾成功 connect 过的实例调用本方法直接抛 {@link IOException}，避免误用。</p>
     *
     * @throws IOException 重连失败（凭证还没设、目标主机彻底不可达等）
     * @author claude
     * @date 2026-05-03
     */
    public void reconnect() throws IOException {
        if (lastUsername == null) {
            throw new IOException("FtpSession 从未成功 connect 过，无法 reconnect");
        }
        try {
            if (ftpClient.isConnected()) {
                try { ftpClient.disconnect(); } catch (IOException ignored) {
                    // disconnect 自身异常不影响重连流程，旧 socket 状态本就不可信
                }
            }
        } finally {
            connected = false;
        }
        connect(lastUsername, lastPassword);
    }

    /**
     * 切换远程工作目录
     *
     * @param remotePath 远程目录路径
     * @throws IOException cwd 失败（目录不存在或权限不足）
     * @author xumanyi
     * @date 2026-03-26
     */
    public void changeWorkingDirectory(String remotePath) throws IOException {
        ensureConnected();
        // controlEncoding 已设为 UTF-8，FTPClient 会自动处理编码
        boolean ok = ftpClient.changeWorkingDirectory(remotePath);
        if (!ok) {
            throw new IOException("无法切换到远程目录: " + remotePath
                    + " (响应: " + ftpClient.getReplyString().trim() + ")");
        }
    }

    /**
     * 获取当前工作目录
     *
     * @return 当前工作目录路径（已 UTF-8 解码），获取失败返回 null
     * @throws IOException FTP 操作失败
     * @author xumanyi
     * @date 2026-03-26
     */
    public String printWorkingDirectory() throws IOException {
        ensureConnected();
        String pwd = ftpClient.printWorkingDirectory();
        if (pwd != null) {
            // 反向解码
            return new String(pwd.getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8);
        }
        return null;
    }

    /**
     * 获取底层 FTPClient 实例（供 FtpOperations 使用）
     *
     * @return FTPClient 实例
     * @author xumanyi
     * @date 2026-03-26
     */
    public FTPClient getClient() {
        return ftpClient;
    }

    /**
     * 检查 FTP 连接是否有效
     *
     * @return 连接有效返回 true
     * @author xumanyi
     * @date 2026-03-26
     */
    public boolean isConnected() {
        return connected && ftpClient.isConnected();
    }

    /**
     * 对中文远程路径进行 ISO-8859-1 编码（FTP 协议要求）
     *
     * @param path 原始路径（UTF-8）
     * @return ISO-8859-1 编码后的路径
     * @author xumanyi
     * @date 2026-03-26
     */
    public static String encodeRemotePath(String path) {
        return new String(path.getBytes(StandardCharsets.UTF_8), StandardCharsets.ISO_8859_1);
    }

    /**
     * 对 FTP 返回的路径进行 UTF-8 解码
     *
     * @param path ISO-8859-1 编码的路径
     * @return UTF-8 解码后的路径
     * @author xumanyi
     * @date 2026-03-26
     */
    public static String decodeRemotePath(String path) {
        return new String(path.getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8);
    }

    /**
     * 关闭 FTP 连接，先尝试 logout 再断开
     *
     * @throws IOException 断开连接失败
     * @author xumanyi
     * @date 2026-03-26
     */
    @Override
    public void close() throws IOException {
        if (ftpClient.isConnected()) {
            try {
                ftpClient.logout();
            } catch (IOException ignored) {
                // logout 失败不影响断开
            }
            ftpClient.disconnect();
        }
        connected = false;
    }

    /**
     * 检查连接状态，未连接时抛出异常
     *
     * @throws IOException FTP 未连接时抛出
     * @author xumanyi
     * @date 2026-03-26
     */
    private void ensureConnected() throws IOException {
        if (!isConnected()) {
            throw new IOException("FTP 未连接");
        }
    }
}
