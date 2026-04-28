package com.flux.deploy.deploy.gates;

import com.flux.deploy.deploy.Gate;
import com.flux.deploy.ftp.FtpOperations;
import com.flux.deploy.model.TargetPackage;

import java.io.IOException;

/**
 * 上传门禁
 *
 * <p>上传本地暂存包到临时远程文件名，再重命名为目标文件名。</p>
 * <p>发布前验证：原始文件名仍缺失，锁包仍在。</p>
 *
 * @author xumanyi
 * @date 2026-03-26
 */
public class UploadGate implements Gate {

    private final FtpOperations ops;

    /**
     * 创建上传门禁实例
     *
     * @param ops FTP 操作对象
     * @author xumanyi
     * @date 2026-03-26
     */
    public UploadGate(FtpOperations ops) {
        this.ops = ops;
    }

    /** {@inheritDoc} */
    @Override
    public String name() { return "upload"; }

    /**
     * 执行上传：先上传到临时文件名，再重命名为目标文件名，最后验证大小一致
     *
     * @param target 目标包
     * @throws IOException    FTP 操作失败
     * @throws GateException  发布前检查失败或上传大小不一致
     * @author xumanyi
     * @date 2026-03-26
     */
    @Override
    public void execute(TargetPackage target) throws IOException, GateException {
        String remoteDir = ensureTrailingSlash(target.getRemoteDir());

        // 发布前检查（仅在内部加锁模式下执行，外部加锁时 lockName 为 null）
        if (target.getLockName() != null) {
            // 1. 原始文件名应仍缺失（已被 rename 为锁包）
            if (ops.exists(target.getRemotePath())) {
                throw new GateException(name(),
                        "发布前原始包意外存在: " + target.getRemotePath()
                                + "，可能有其他操作干预，停止上传");
            }

            // 2. 锁包应仍在
            String lockPath = remoteDir + target.getLockName();
            if (!ops.exists(lockPath)) {
                throw new GateException(name(),
                        "发布前锁包丢失: " + lockPath + "，停止上传");
            }
        }

        // 3. 上传到临时文件名
        String tempName = target.getPackageName() + ".__UPLOADING__";
        String tempPath = remoteDir + tempName;
        ops.upload(target.getLocalStagingFile(), tempPath);

        // 4. 重命名为目标文件名
        try {
            ops.rename(tempPath, target.getRemotePath());
        } catch (IOException e) {
            // rename 失败，清理临时文件
            try { ops.delete(tempPath); } catch (IOException ignored) {}
            throw new GateException(name(),
                    "临时文件重命名失败: " + tempPath + " → " + target.getRemotePath(), e);
        }

        // 5. 验证远程文件存在且大小合理
        long remoteSize = ops.getFileSize(target.getRemotePath());
        if (remoteSize < 0) {
            throw new GateException(name(), "上传后远程文件不存在: " + target.getRemotePath());
        }

        long localSize = java.nio.file.Files.size(target.getLocalStagingFile());
        if (remoteSize != localSize) {
            throw new GateException(name(),
                    "上传大小不一致: 本地 " + localSize + " 字节, 远程 " + remoteSize + " 字节");
        }

        target.setStatus(TargetPackage.Status.UPLOADED);
        System.out.println("  [上传] " + target.getPackageName()
                + " (" + formatSize(remoteSize) + ")");
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

    /**
     * 将字节数格式化为可读的大小字符串
     *
     * @param bytes 字节数
     * @return 可读的大小字符串，如 "1.5 MB"
     * @author xumanyi
     * @date 2026-03-26
     */
    private static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
    }
}
