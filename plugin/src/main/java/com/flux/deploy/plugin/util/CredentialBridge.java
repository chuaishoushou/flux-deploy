package com.flux.deploy.plugin.util;

import com.flux.deploy.util.CredentialCache;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * 凭据桥接：复用 CLI 的凭据缓存
 *
 * <p>优先读取 CLI 已有的 ~/.flux-deploy/credentials.toml，
 * 实现插件与 CLI 之间的 FTP 凭据共享，并支持多账号列出、删除、切换。</p>
 *
 * @author xumanyi
 * @date 2026-03-27
 */
public class CredentialBridge {

    private CredentialBridge() {}

    /**
     * 加载默认凭据（唯一一条时自动返回，多条时返回最近验证过的那条）
     *
     * @return 缓存凭据（密码已解密），无缓存则返回 null
     * @author xumanyi
     * @date 2026-03-27
     */
    @Nullable
    public static CredentialCache.CachedCredential loadCachedCredential() {
        try {
            CredentialCache.CachedCredential single = CredentialCache.getDefault();
            if (single != null) return single;
            // 多条时挑最近验证过的一条
            List<CredentialCache.CachedCredential> all = CredentialCache.loadAllDecrypted();
            CredentialCache.CachedCredential best = null;
            for (CredentialCache.CachedCredential c : all) {
                if (best == null) { best = c; continue; }
                if (compareVerified(c.getVerifiedAt(), best.getVerifiedAt()) > 0) best = c;
            }
            return best;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 列出所有已缓存凭据（密码已解密）
     *
     * @return 所有已缓存凭据列表；解密失败或无缓存时返回空列表
     * @author xumanyi
     * @date 2026-03-27
     */
    public static List<CredentialCache.CachedCredential> listAll() {
        try {
            return CredentialCache.loadAllDecrypted();
        } catch (Exception e) {
            return List.of();
        }
    }

    /**
     * 保存 / 更新凭据
     *
     * @param host     主机
     * @param port     端口
     * @param username 用户名
     * @param password 密码（加密存储）
     * @param protocol 协议（FTP / SFTP，null 按 FTP）
     * @author xumanyi
     * @date 2026-04-17
     */
    public static void saveCredential(String host, int port, String username, String password,
                                       String protocol) {
        try {
            CredentialCache.saveOrUpdate(host, port, username, password, null, protocol);
        } catch (Exception ignored) {}
    }

    /**
     * 兼容旧签名，协议固定为 FTP
     *
     * @param host     主机
     * @param port     端口
     * @param username 用户名
     * @param password 密码（加密存储）
     * @author xumanyi
     * @date 2026-03-27
     */
    public static void saveCredential(String host, int port, String username, String password) {
        saveCredential(host, port, username, password, "FTP");
    }

    /**
     * 删除指定凭据
     *
     * @param host     主机
     * @param port     端口
     * @param username 用户名
     * @return true=已删除，false=未找到匹配条目
     * @author xumanyi
     * @date 2026-03-27
     */
    public static boolean deleteCredential(String host, int port, String username) {
        try {
            return CredentialCache.delete(host, port, username);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 比较 verifiedAt 字符串（ISO 日期格式，直接字典序即可）
     *
     * @param a 第一个 verifiedAt 字符串，可为 null
     * @param b 第二个 verifiedAt 字符串，可为 null
     * @return 字典序比较结果；null 视为空串
     * @author xumanyi
     * @date 2026-03-27
     */
    private static int compareVerified(String a, String b) {
        if (a == null) a = "";
        if (b == null) b = "";
        return a.compareTo(b);
    }
}
