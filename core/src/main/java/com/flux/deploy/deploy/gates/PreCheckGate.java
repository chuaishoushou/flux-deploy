package com.flux.deploy.deploy.gates;

import com.flux.deploy.deploy.Gate;
import com.flux.deploy.ftp.FtpOperations;
import com.flux.deploy.model.TargetPackage;

import java.io.IOException;
import java.nio.file.Files;

/**
 * 预检门禁
 *
 * <p>验证本地暂存包存在、远程目标包存在。</p>
 *
 * @author xumanyi
 * @date 2026-03-26
 */
public class PreCheckGate implements Gate {

    private final FtpOperations ops;

    /**
     * 创建预检门禁实例
     *
     * @param ops FTP 操作对象
     * @author xumanyi
     * @date 2026-03-26
     */
    public PreCheckGate(FtpOperations ops) {
        this.ops = ops;
    }

    /** {@inheritDoc} */
    @Override
    public String name() { return "precheck"; }

    /**
     * 执行预检：验证本地暂存包存在且非空，远程目标包存在
     *
     * @param target 目标包
     * @throws IOException    FTP 操作失败
     * @throws GateException  本地包不存在/为空，或远程包不存在
     * @author xumanyi
     * @date 2026-03-26
     */
    @Override
    public void execute(TargetPackage target) throws IOException, GateException {
        // 1. 验证本地暂存包存在
        if (target.getLocalStagingFile() == null || !Files.exists(target.getLocalStagingFile())) {
            throw new GateException(name(), "本地暂存包不存在: " + target.getLocalStagingFile());
        }
        long localSize = Files.size(target.getLocalStagingFile());
        if (localSize == 0) {
            throw new GateException(name(), "本地暂存包为空文件: " + target.getLocalStagingFile());
        }

        // 2. 验证远程目标包存在
        long remoteSize = ops.getFileSize(target.getRemotePath());
        if (remoteSize < 0) {
            throw new GateException(name(), "远程目标包不存在: " + target.getRemotePath());
        }

        System.out.println("  [预检] " + target.getPackageName()
                + " - 本地: " + formatSize(localSize)
                + ", 远程: " + formatSize(remoteSize));
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
