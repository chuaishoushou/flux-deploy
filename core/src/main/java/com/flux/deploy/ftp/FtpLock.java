package com.flux.deploy.ftp;

import org.apache.commons.net.ftp.FTPFile;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * FTP rename-lock 机制
 *
 * <p>通过重命名远程包为 __LOCK__ 后缀格式实现独占锁，防止并发更新。</p>
 *
 * <p>锁文件名格式：{@code <package>__LOCK__<operator>_<yyyyMMdd_HHmmss>}</p>
 *
 * @author xumanyi
 * @date 2026-03-26
 */
public class FtpLock {

    private static final String LOCK_SEPARATOR = "__LOCK__";
    private static final DateTimeFormatter LOCK_TIME_FMT = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    /**
     * 默认锁 TTL（分钟）。
     *
     * <p>10 分钟覆盖典型部署单包耗时（&lt; 5 分钟）+ 一档余量。
     * 实际部署完成 / 失败回滚都会主动 unlock，不依赖 TTL；TTL 只用于异常退出场景的兜底清理。</p>
     */
    public static final int DEFAULT_TTL_MIN = 10;

    /** TTL 后缀标记，写在锁文件名末尾如 "_TTL10"，为锁加上过期时间 */
    private static final String TTL_PREFIX = "_TTL";

    private final FtpOperations ops;

    /**
     * 创建 FTP 锁操作实例
     *
     * @param ops FTP 操作对象
     * @author xumanyi
     * @date 2026-03-26
     */
    public FtpLock(FtpOperations ops) {
        this.ops = ops;
    }

    /**
     * 生成锁文件名
     *
     * @param packageName 原始包文件名
     * @param operator    操作人英文 id
     * @return 锁文件名
     * @author xumanyi
     * @date 2026-03-26
     */
    public static String buildLockName(String packageName, String operator) {
        return buildLockName(packageName, operator, DEFAULT_TTL_MIN);
    }

    /**
     * 生成带 TTL 后缀的锁文件名。
     *
     * <p>格式：{@code <package>__LOCK__<operator>_<yyyyMMdd_HHmmss>_TTL<minutes>}。
     * TTL 后缀供 {@link #parseExpireAt(String)} 在 Stage 0 自检时识别已过期的滞留锁，
     * 自动清理而不要求人工介入。</p>
     *
     * @param packageName 原始包文件名
     * @param operator    操作人英文 id
     * @param ttlMinutes  TTL 分钟数（&gt; 0）
     * @return 锁文件名
     * @author xumanyi
     * @date 2026-05-03
     */
    public static String buildLockName(String packageName, String operator, int ttlMinutes) {
        if (ttlMinutes <= 0) throw new IllegalArgumentException("ttlMinutes 必须 > 0");
        String timestamp = LocalDateTime.now().format(LOCK_TIME_FMT);
        return packageName + LOCK_SEPARATOR + operator + "_" + timestamp + TTL_PREFIX + ttlMinutes;
    }

    /**
     * 判断文件名是否为锁后缀包
     *
     * @param fileName 文件名
     * @return 包含 __LOCK__ 分隔符时返回 true
     * @author xumanyi
     * @date 2026-03-26
     */
    public static boolean isLockFile(String fileName) {
        return fileName != null && fileName.contains(LOCK_SEPARATOR);
    }

    /**
     * 从锁文件名中解析原始包名
     *
     * @param lockFileName 锁文件名
     * @return 原始包名，解析失败返回 null
     * @author xumanyi
     * @date 2026-03-26
     */
    public static String extractOriginalName(String lockFileName) {
        int idx = lockFileName.indexOf(LOCK_SEPARATOR);
        return idx > 0 ? lockFileName.substring(0, idx) : null;
    }

    /**
     * 从锁文件名中解析锁持有者和时间
     *
     * @param lockFileName 锁文件名
     * @return [operator, timestamp] 或 null
     * @author xumanyi
     * @date 2026-03-26
     */
    public static String[] parseLockInfo(String lockFileName) {
        int idx = lockFileName.indexOf(LOCK_SEPARATOR);
        if (idx < 0) {
            return null;
        }
        String suffix = lockFileName.substring(idx + LOCK_SEPARATOR.length());
        // 先剥可选的 _TTL<n> 尾巴：buildLockName 新格式追加在末尾，
        // 旧格式锁文件没这段，跳过即可
        int ttlIdx = suffix.lastIndexOf(TTL_PREFIX);
        if (ttlIdx > 0) {
            String ttlPart = suffix.substring(ttlIdx + TTL_PREFIX.length());
            // 验证 TTL 部分是纯数字，防止把业务名里碰巧出现的 _TTLxxx 误当 TTL 后缀剥掉
            if (!ttlPart.isEmpty() && ttlPart.chars().allMatch(Character::isDigit)) {
                suffix = suffix.substring(0, ttlIdx);
            }
        }
        // suffix 格式: operator_yyyyMMdd_HHmmss
        int lastUnderscore = suffix.lastIndexOf('_');
        int secondLastUnderscore = suffix.lastIndexOf('_', lastUnderscore - 1);
        if (secondLastUnderscore < 0) {
            return new String[]{suffix, ""};
        }
        String operator = suffix.substring(0, secondLastUnderscore);
        String time = suffix.substring(secondLastUnderscore + 1);
        return new String[]{operator, time};
    }

    /**
     * 解析锁文件的过期时间戳。
     *
     * <p>新格式（{@code __LOCK__<op>_<ts>_TTL<n>}）按 {@code ts + n 分钟} 计算；
     * 旧格式（无 TTL 后缀）按 {@link #DEFAULT_TTL_MIN} 兜底，保证向后兼容
     * 旧版插件留下的滞留锁也能被新版自检清理。</p>
     *
     * @param lockFileName 锁文件名
     * @return 过期时间；时间戳无法解析时返回 {@code null}
     * @author xumanyi
     * @date 2026-05-03
     */
    public static LocalDateTime parseExpireAt(String lockFileName) {
        if (lockFileName == null) return null;
        int idx = lockFileName.indexOf(LOCK_SEPARATOR);
        if (idx < 0) return null;
        String suffix = lockFileName.substring(idx + LOCK_SEPARATOR.length());

        int ttlMinutes = DEFAULT_TTL_MIN;
        int ttlIdx = suffix.lastIndexOf(TTL_PREFIX);
        if (ttlIdx > 0) {
            String ttlPart = suffix.substring(ttlIdx + TTL_PREFIX.length());
            if (!ttlPart.isEmpty() && ttlPart.chars().allMatch(Character::isDigit)) {
                try {
                    ttlMinutes = Integer.parseInt(ttlPart);
                } catch (NumberFormatException ignored) {
                    // chars().allMatch(isDigit) 已保证不会抛，这里只是防御
                }
                suffix = suffix.substring(0, ttlIdx);
            }
        }

        String[] info = parseLockInfo(lockFileName);
        if (info == null || info[1] == null || info[1].isEmpty()) return null;
        try {
            return LocalDateTime.parse(info[1], LOCK_TIME_FMT).plusMinutes(ttlMinutes);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 检查目标包目录中是否存在残留锁
     *
     * @param remoteDir   远程目录路径
     * @param packageName 目标包文件名
     * @return 残留锁文件名列表
     * @author xumanyi
     * @date 2026-03-26
     */
    public List<String> findResidualLocks(String remoteDir, String packageName) throws IOException {
        List<FTPFile> files = ops.listFiles(remoteDir);
        List<String> locks = new ArrayList<>();
        String lockPrefix = packageName + LOCK_SEPARATOR;
        for (FTPFile f : files) {
            String name = FtpSession.decodeRemotePath(f.getName());
            if (name.startsWith(lockPrefix) && f.isFile()) {
                locks.add(name);
            }
        }
        return locks;
    }

    /**
     * 对目标包加锁：重命名原包为锁文件名
     *
     * @param remoteDir   远程目录路径
     * @param packageName 原始包文件名
     * @param operator    操作人英文 id
     * @return 锁文件名
     * @author xumanyi
     * @date 2026-03-26
     */
    public String acquireLock(String remoteDir, String packageName, String operator) throws IOException {
        String lockName = buildLockName(packageName, operator);
        String fromPath = ensureTrailingSlash(remoteDir) + packageName;
        String toPath = ensureTrailingSlash(remoteDir) + lockName;

        ops.rename(fromPath, toPath);

        // 验证：原包应已消失，锁包应已存在
        if (ops.exists(fromPath)) {
            throw new IOException("加锁后原始包仍然存在: " + fromPath);
        }
        if (!ops.exists(toPath)) {
            throw new IOException("加锁后锁包不存在: " + toPath);
        }

        return lockName;
    }

    /**
     * 释放锁：删除精确锁包
     *
     * @param remoteDir 远程目录路径
     * @param lockName  精确锁文件名
     * @author xumanyi
     * @date 2026-03-26
     */
    public void releaseLock(String remoteDir, String lockName) throws IOException {
        String lockPath = ensureTrailingSlash(remoteDir) + lockName;
        ops.delete(lockPath);
    }

    /**
     * 恢复锁包为原始文件名（回滚用）
     *
     * @param remoteDir 远程目录路径
     * @param lockName  精确锁文件名
     * @author xumanyi
     * @date 2026-03-26
     */
    public void restoreLock(String remoteDir, String lockName) throws IOException {
        String originalName = extractOriginalName(lockName);
        if (originalName == null) {
            throw new IOException("无法从锁文件名解析原始包名: " + lockName);
        }
        String lockPath = ensureTrailingSlash(remoteDir) + lockName;
        String originalPath = ensureTrailingSlash(remoteDir) + originalName;

        ops.rename(lockPath, originalPath);
    }

    /**
     * 确保路径以 / 结尾
     *
     * @param path 原始路径
     * @return 以 / 结尾的路径
     * @author xumanyi
     * @date 2026-03-26
     */
    private static String ensureTrailingSlash(String path) {
        return path.endsWith("/") ? path : path + "/";
    }
}
