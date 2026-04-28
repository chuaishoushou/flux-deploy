package com.flux.deploy.util;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * SHA256 哈希工具
 *
 * <p>提供文件级别的 SHA256 哈希计算能力，用于部署后的完整性校验。</p>
 *
 * @author xumanyi
 * @date 2026-03-26
 */
public final class HashUtil {

    /** 私有构造函数，防止实例化 */
    private HashUtil() {}

    /**
     * 计算文件的 SHA256 哈希值
     *
     * @param file 文件路径
     * @return 小写十六进制哈希字符串
     * @author xumanyi
     * @date 2026-03-26
     */
    public static String sha256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            try (InputStream in = Files.newInputStream(file)) {
                int read;
                while ((read = in.read(buffer)) != -1) {
                    digest.update(buffer, 0, read);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 算法不可用", e);
        }
    }
}
