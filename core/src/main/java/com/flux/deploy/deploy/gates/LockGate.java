package com.flux.deploy.deploy.gates;

import com.flux.deploy.deploy.Gate;
import com.flux.deploy.ftp.FtpLock;
import com.flux.deploy.model.DeployConfig;
import com.flux.deploy.model.TargetPackage;

import java.io.IOException;
import java.util.List;

/**
 * 加锁门禁
 *
 * <p>通过重命名远程原包获取独占锁，防止并发更新。</p>
 * <p>如发现残留锁包，立即停止并报告。</p>
 *
 * @author xumanyi
 * @date 2026-03-26
 */
public class LockGate implements Gate {

    private final FtpLock ftpLock;
    private final DeployConfig config;

    /**
     * 创建加锁门禁实例
     *
     * @param ftpLock FTP 锁操作对象
     * @param config  部署配置
     * @author xumanyi
     * @date 2026-03-26
     */
    public LockGate(FtpLock ftpLock, DeployConfig config) {
        this.ftpLock = ftpLock;
        this.config = config;
    }

    /** {@inheritDoc} */
    @Override
    public String name() { return "lock"; }

    /**
     * 执行加锁：检查残留锁后对目标包进行重命名加锁
     *
     * @param target 目标包
     * @throws IOException    FTP 操作失败
     * @throws GateException  发现残留锁或加锁失败
     * @author xumanyi
     * @date 2026-03-26
     */
    @Override
    public void execute(TargetPackage target) throws IOException, GateException {
        String remoteDir = target.getRemoteDir();

        // 1. 检查残留锁
        List<String> residualLocks = ftpLock.findResidualLocks(remoteDir, target.getPackageName());
        if (!residualLocks.isEmpty()) {
            StringBuilder msg = new StringBuilder("发现残留锁包，可能存在未完成的更新：\n");
            for (String lockFile : residualLocks) {
                String[] info = FtpLock.parseLockInfo(lockFile);
                if (info != null) {
                    msg.append("  - ").append(lockFile)
                            .append(" (持有者: ").append(info[0])
                            .append(", 时间: ").append(info[1]).append(")\n");
                } else {
                    msg.append("  - ").append(lockFile).append("\n");
                }
            }
            msg.append("请先处理残留锁包后再执行更新");
            throw new GateException(name(), msg.toString());
        }

        // 2. 加锁
        String lockName = ftpLock.acquireLock(remoteDir, target.getPackageName(), config.getOperator());
        target.setLockName(lockName);
        target.setStatus(TargetPackage.Status.LOCKED);

        System.out.println("  [加锁] " + target.getPackageName() + " → " + lockName);
    }
}
