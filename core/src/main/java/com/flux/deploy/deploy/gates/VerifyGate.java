package com.flux.deploy.deploy.gates;

import com.flux.deploy.deploy.Gate;
import com.flux.deploy.ftp.FtpOperations;
import com.flux.deploy.model.TargetPackage;
import com.flux.deploy.util.HashUtil;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 校验门禁
 *
 * <p>上传后重新下载远程包，与本地暂存包进行 SHA256 比对。</p>
 *
 * @author xumanyi
 * @date 2026-03-26
 */
public class VerifyGate implements Gate {

    private final FtpOperations ops;

    /**
     * 创建校验门禁实例
     *
     * @param ops FTP 操作对象
     * @author xumanyi
     * @date 2026-03-26
     */
    public VerifyGate(FtpOperations ops) {
        this.ops = ops;
    }

    /** {@inheritDoc} */
    @Override
    public String name() { return "verify"; }

    /**
     * 执行校验：计算本地包 SHA256，下载远程包计算 SHA256，比对是否一致
     *
     * @param target 目标包
     * @throws IOException    FTP 操作或文件 IO 失败
     * @throws GateException  SHA256 校验不一致
     * @author xumanyi
     * @date 2026-03-26
     */
    @Override
    public void execute(TargetPackage target) throws IOException, GateException {
        // 1. 计算本地暂存包 SHA256
        String localHash = HashUtil.sha256(target.getLocalStagingFile());
        target.setLocalSha256(localHash);

        // 2. 下载远程包到临时文件
        Path tempFile = Files.createTempFile("verify-", "-" + target.getPackageName());
        try {
            ops.download(target.getRemotePath(), tempFile);

            // 3. 计算远程包 SHA256
            String remoteHash = HashUtil.sha256(tempFile);
            target.setRemoteSha256(remoteHash);

            // 4. 比对
            if (!localHash.equals(remoteHash)) {
                throw new GateException(name(),
                        "SHA256 校验失败: 本地=" + localHash + ", 远程=" + remoteHash);
            }

            target.setStatus(TargetPackage.Status.VERIFIED);
            System.out.println("  [校验] SHA256 一致 " + localHash.substring(0, 16) + "...");

        } finally {
            Files.deleteIfExists(tempFile);
        }
    }
}
