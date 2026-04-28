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
        String timestamp = LocalDateTime.now().format(LOCK_TIME_FMT);
        return packageName + LOCK_SEPARATOR + operator + "_" + timestamp;
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
