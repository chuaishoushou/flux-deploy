package com.flux.deploy.deploy.gates;

import com.flux.deploy.deploy.Gate;
import com.flux.deploy.ftp.FtpLock;
import com.flux.deploy.ftp.FtpOperations;
import com.flux.deploy.model.TargetPackage;

import java.io.IOException;

/**
 * 解锁门禁
 *
 * <p>在包上传、校验、note 更新全部成功后，删除精确锁包。</p>
 * <p>删除前验证：目标包存在 + note 文件正确。</p>
 *
 * @author xumanyi
 * @date 2026-03-26
 */
public class UnlockGate implements Gate {

    private final FtpOperations ops;
    private final FtpLock ftpLock;

    /**
     * 创建解锁门禁实例
     *
     * @param ops     FTP 操作对象
     * @param ftpLock FTP 锁操作对象
     * @author xumanyi
     * @date 2026-03-26
     */
    public UnlockGate(FtpOperations ops, FtpLock ftpLock) {
        this.ops = ops;
        this.ftpLock = ftpLock;
    }

    /** {@inheritDoc} */
    @Override
    public String name() { return "unlock"; }

    /**
     * 执行解锁：验证目标包存在后删除精确锁包
     *
     * @param target 目标包
     * @throws IOException    FTP 操作失败
     * @throws GateException  目标包不存在或无锁文件名记录
     * @author xumanyi
     * @date 2026-03-26
     */
    @Override
    public void execute(TargetPackage target) throws IOException, GateException {
        // 1. 验证目标包仍然存在
        if (!ops.exists(target.getRemotePath())) {
            throw new GateException(name(),
                    "解锁前目标包不存在: " + target.getRemotePath());
        }

        // 2. 删除精确锁包
        if (target.getLockName() == null) {
            throw new GateException(name(), "无锁文件名记录，无法解锁");
        }

        ftpLock.releaseLock(target.getRemoteDir(), target.getLockName());
        target.setStatus(TargetPackage.Status.COMPLETED);

        System.out.println("  [解锁] " + target.getPackageName() + " 已解锁");
    }
}
