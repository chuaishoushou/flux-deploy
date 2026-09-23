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
     * <p>漏洞 H4 修复：releaseLock 自带最多 3 次重试，仍失败时**升级为 COMPLETED + WARN
     * 而不抛 IOException**。原因：到这一步业务包 + note 均已成功落地，残留的锁包只是清理
     * 工作没完成；如果让 IOException 冒到 DeployPipeline.handleIoException，会被识别为
     * NOTE_UPDATED 状态触发 restoreFromBackup，把**已经发布成功的部署内容错误地撤回到
     * 旧版本**。残留锁不破坏业务，下次部署的 Stage 0 残留锁扫描会自动判 DELETE_LOCK 清除
     * （锁是本 operator 的、原路径已存在新包字节 → ResidualLockResolver 判 DELETE_LOCK）。</p>
     *
     * @param target 目标包
     * @throws IOException    仅在 ops.exists 等前置 IO 失败时抛出
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

        if (releaseLockWithRetries(target)) {
            target.setStatus(TargetPackage.Status.COMPLETED);
            System.out.println("  [解锁] " + target.getPackageName() + " 已解锁");
        } else {
            // 锁删不掉但部署本身已成功：升级为 COMPLETED，残留锁交给下次 Stage 0 兜底
            target.setStatus(TargetPackage.Status.COMPLETED);
            System.err.println("  [解锁][WARN] " + target.getPackageName()
                    + " 部署已成功但锁包未能删除（残留锁: " + target.getLockName()
                    + "），下次部署 Stage 0 将自动清理。绝不回滚已成功的业务包。");
        }
    }

    /** 重试预算：1 + 2 + 5 = 8 秒，覆盖典型 FTP 短暂抖动。 */
    private static final int RELEASE_MAX_ATTEMPTS = 3;
    private static final long[] RELEASE_BACKOFF_MS = {1_000L, 2_000L, 5_000L};

    /**
     * 带退避重试的锁删除。
     *
     * <p>每次重试前先确认锁包是否还在：若锁包已不存在（外部清理 / 上次重试实际成功但响应丢失），
     * 视同删除成功。仅捕获 IOException，其它运行时异常照常向上传播。</p>
     *
     * @param target 目标包
     * @return true=锁已不在远端；false=多次重试后仍存在
     * @author xumanyi
     * @date 2026-07-01
     */
    private boolean releaseLockWithRetries(TargetPackage target) {
        String lockDir = target.getRemoteDir();
        String lockName = target.getLockName();
        String lockPath = ensureTrailingSlash(lockDir) + lockName;
        IOException lastFailure = null;
        for (int attempt = 0; attempt < RELEASE_MAX_ATTEMPTS; attempt++) {
            try {
                if (!ops.exists(lockPath)) {
                    return true;
                }
                ftpLock.releaseLock(lockDir, lockName);
                return true;
            } catch (IOException ex) {
                lastFailure = ex;
                System.err.println("  [解锁] 第 " + (attempt + 1) + " 次尝试失败: "
                        + ex.getMessage() + (attempt + 1 < RELEASE_MAX_ATTEMPTS
                                ? "，退避 " + (RELEASE_BACKOFF_MS[attempt] / 1000) + "s 后重试"
                                : ""));
                if (attempt + 1 < RELEASE_MAX_ATTEMPTS) {
                    try {
                        Thread.sleep(RELEASE_BACKOFF_MS[attempt]);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return false;
                    }
                }
            }
        }
        if (lastFailure != null) {
            System.err.println("  [解锁] 重试 " + RELEASE_MAX_ATTEMPTS + " 次仍失败: " + lastFailure.getMessage());
        }
        return false;
    }

    private static String ensureTrailingSlash(String path) {
        return path.endsWith("/") ? path : path + "/";
    }
}
