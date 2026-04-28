package com.flux.deploy.util;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.security.spec.KeySpec;
import java.util.Base64;

/**
 * AES-256-GCM 加解密工具
 *
 * <p>用于凭据缓存中密码字段的加密存储。</p>
 *
 * <p>密钥派生方式：基于当前机器用户名 + 固定盐 + 设备指纹，通过 PBKDF2 派生 AES-256 密钥。
 * 这样同一台机器同一用户解密一致，换机器或换用户无法解密。</p>
 *
 * <p>加密输出格式：{@code ENC(base64(iv + ciphertext + tag))}</p>
 *
 * @author xumanyi
 * @date 2026-03-26
 */
public final class CryptoUtil {

    private static final String AES_ALGO = "AES/GCM/NoPadding";
    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_LENGTH = 128;
    private static final int AES_KEY_LENGTH = 256;
    private static final int PBKDF2_ITERATIONS = 100_000;
    private static final String ENC_PREFIX = "ENC(";
    private static final String ENC_SUFFIX = ")";

    /** 私有构造函数，防止实例化 */
    private CryptoUtil() {}

    /**
     * 判断字符串是否为加密格式（以 ENC(...) 包裹）
     *
     * @param value 待检查的字符串
     * @return 符合加密格式返回 true
     * @author xumanyi
     * @date 2026-03-26
     */
    public static boolean isEncrypted(String value) {
        return value != null && value.startsWith(ENC_PREFIX) && value.endsWith(ENC_SUFFIX);
    }

    /**
     * 加密明文密码
     *
     * @param plaintext 明文密码
     * @return 加密后的字符串，格式 ENC(base64(...))
     * @author xumanyi
     * @date 2026-03-26
     */
    public static String encrypt(String plaintext) {
        try {
            SecretKey key = deriveKey(stableUser());
            Cipher cipher = Cipher.getInstance(AES_ALGO);

            byte[] iv = new byte[GCM_IV_LENGTH];
            new SecureRandom().nextBytes(iv);
            GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
            cipher.init(Cipher.ENCRYPT_MODE, key, spec);

            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(java.nio.charset.StandardCharsets.UTF_8));

            // iv + ciphertext 拼接
            ByteBuffer buffer = ByteBuffer.allocate(iv.length + ciphertext.length);
            buffer.put(iv);
            buffer.put(ciphertext);

            return ENC_PREFIX + Base64.getEncoder().encodeToString(buffer.array()) + ENC_SUFFIX;

        } catch (Exception e) {
            throw new RuntimeException("加密失败: " + e.getMessage(), e);
        }
    }

    /**
     * 解密加密密码
     *
     * @param encrypted 加密字符串，格式 ENC(base64(...))
     * @return 明文密码
     * @author xumanyi
     * @date 2026-03-26
     */
    public static String decrypt(String encrypted) {
        if (!isEncrypted(encrypted)) {
            // 未加密，直接返回（兼容旧的明文缓存）
            return encrypted;
        }

        String base64 = encrypted.substring(ENC_PREFIX.length(), encrypted.length() - ENC_SUFFIX.length());
        byte[] data = Base64.getDecoder().decode(base64);
        ByteBuffer buffer = ByteBuffer.wrap(data);
        byte[] iv = new byte[GCM_IV_LENGTH];
        buffer.get(iv);
        byte[] ciphertext = new byte[buffer.remaining()];
        buffer.get(ciphertext);

        // 主解密：用稳定用户标识（user.home 的 basename，不受 JVM -Duser.name 覆盖影响）
        Exception primary;
        try {
            return doDecrypt(iv, ciphertext, deriveKey(stableUser()));
        } catch (Exception e) {
            primary = e;
        }
        // 回退：兼容使用 user.name（System property）派生的旧条目
        try {
            return doDecrypt(iv, ciphertext, deriveKey(System.getProperty("user.name")));
        } catch (Exception ignored) {
            throw new RuntimeException("解密失败（可能密钥不匹配或缓存已损坏）: " + primary.getMessage(), primary);
        }
    }

    private static String doDecrypt(byte[] iv, byte[] ciphertext, SecretKey key) throws Exception {
        Cipher cipher = Cipher.getInstance(AES_ALGO);
        GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
        cipher.init(Cipher.DECRYPT_MODE, key, spec);
        byte[] plaintext = cipher.doFinal(ciphertext);
        return new String(plaintext, java.nio.charset.StandardCharsets.UTF_8);
    }

    /**
     * 基于机器特征派生 AES 密钥
     *
     * <p>同一台机器、同一用户 → 相同密钥 → 可以解密。</p>
     * <p>不同机器或不同用户 → 不同密钥 → 无法解密。</p>
     *
     * @return AES-256 密钥
     * @throws Exception 密钥派生失败
     * @author xumanyi
     * @date 2026-03-26
     */
    /**
     * 用 {@code user.home} 的 basename 作为稳定用户标识；
     * 不依赖 {@code user.name} 系统属性，避免 IDE 等宿主通过 {@code -Duser.name=xxx} 覆盖时派生出不同密钥。
     */
    private static String stableUser() {
        String homeDir = System.getProperty("user.home");
        if (homeDir == null || homeDir.isEmpty()) {
            return "unknown";
        }
        Path home = Path.of(homeDir);
        Path filename = home.getFileName();
        return filename != null ? filename.toString() : "unknown";
    }

    private static SecretKey deriveKey(String username) throws Exception {
        String homeDir = System.getProperty("user.home");
        String passphrase = "flux-deploy:" + username + ":" + homeDir;
        byte[] salt = ("flux-deploy-cli-salt-" + username).getBytes(java.nio.charset.StandardCharsets.UTF_8);

        SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        KeySpec spec = new PBEKeySpec(passphrase.toCharArray(), salt, PBKDF2_ITERATIONS, AES_KEY_LENGTH);
        SecretKey tmp = factory.generateSecret(spec);
        return new SecretKeySpec(tmp.getEncoded(), "AES");
    }
}
