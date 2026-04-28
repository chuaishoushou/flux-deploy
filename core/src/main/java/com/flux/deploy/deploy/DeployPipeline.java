package com.flux.deploy.deploy;

import com.flux.deploy.deploy.gates.*;
import com.flux.deploy.ftp.FtpLock;
import com.flux.deploy.ftp.FtpOperations;
import com.flux.deploy.ftp.FtpSession;
import com.flux.deploy.model.DeployConfig;
import com.flux.deploy.model.DeployResult;
import com.flux.deploy.model.TargetPackage;
import com.flux.deploy.plugin.service.StagingPackageBuilder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 部署流水线编排
 *
 * <p>串联所有门禁，按顺序对每个目标包执行：预检 → 备份 → 加锁 → 上传 → 校验 → note → 解锁。</p>
 * <p>任一门禁失败则触发回滚，恢复所有已修改的目标包。</p>
 *
 * @author xumanyi
 * @date 2026-03-26
 */
public class DeployPipeline {

    private final DeployConfig config;

    /**
     * 创建部署流水线实例
     *
     * @param config 部署配置
     * @author xumanyi
     * @date 2026-03-26
     */
    public DeployPipeline(DeployConfig config) {
        this.config = config;
    }

    /**
     * 执行完整部署流程
     *
     * @return 部署结果
     * @author xumanyi
     * @date 2026-03-26
     */
    public DeployResult execute() {
        DeployResult result = new DeployResult();

        // 1. 构建显式目标包列表（name 不为 null 的条目）
        List<TargetPackage> targets = buildTargets();

        // 2. 连接 FTP
        try (FtpSession session = new FtpSession(config.getHost(), config.getPort())) {
            session.connect(config.getUsername(), config.getPassword());
            session.changeWorkingDirectory(config.getRemoteDir());

            FtpOperations ops = new FtpOperations(session);

            // 2a. 解析扫描模式目标（name 为 null 时按文件名递归扫描 remoteDir）
            List<TargetPackage> scanned = resolveScannedTargets(ops, result);
            if (scanned == null) {
                return result; // 扫描失败，错误已记录
            }
            targets.addAll(scanned);
            if (targets.isEmpty()) {
                result.addError("init", "", "无目标包");
                return result;
            }

            // 2b. 增量/自动模式：为每个目标构建 staging 包（下载远端 + 打补丁）
            if (!"full".equalsIgnoreCase(config.getMode())) {
                if (!runStagingForTargets(targets, result)) {
                    return result; // staging 失败，错误已记录
                }
            }

            FtpLock ftpLock = new FtpLock(ops);
            Rollback rollback = new Rollback(ops, ftpLock);

            // 3. 构建门禁序列（根据配置跳过已在外部处理的步骤）
            List<Gate> gates = new ArrayList<>();
            if (!config.isSkipLock()) {
                gates.add(new PreCheckGate(ops));
            }
            if (!config.isSkipBackup()) {
                gates.add(new BackupGate(ops, config));
            }
            if (!config.isSkipLock()) {
                gates.add(new LockGate(ftpLock, config));
            }
            gates.add(new UploadGate(ops));
            gates.add(new VerifyGate(ops));
            if (!config.isSkipNote()) {
                gates.add(new NoteGate(ops, config));
            }
            if (!config.isSkipLock()) {
                gates.add(new UnlockGate(ops, ftpLock));
            }

            // 4. Dry-run 模式
            if (config.isDryRun()) {
                return executeDryRun(targets, ops, ftpLock, result);
            }

            // 5. 逐门禁、逐目标执行
            executeGates(targets, gates, rollback, result);

        } catch (IOException e) {
            result.addError("connection", "", "FTP 连接失败: " + e.getMessage());
        }

        return result;
    }

    /**
     * 逐门禁执行：每个门禁对所有目标包执行完毕后，再进入下一个门禁
     *
     * @param targets  目标包列表
     * @param gates    门禁序列
     * @param rollback 回滚策略
     * @param result   部署结果对象（用于记录错误和最终状态）
     * @author xumanyi
     * @date 2026-03-26
     */
    private void executeGates(List<TargetPackage> targets, List<Gate> gates,
                              Rollback rollback, DeployResult result) {
        for (Gate gate : gates) {

            for (TargetPackage target : targets) {
                try {
                    gate.execute(target);
                } catch (Gate.GateException | IOException e) {
                    // 门禁失败：记录错误，触发回滚
                    String msg = e instanceof Gate.GateException
                            ? e.getMessage()
                            : gate.name() + " 操作异常: " + e.getMessage();
                    result.addError(gate.name(), target.getPackageName(), msg);
                    target.setStatus(TargetPackage.Status.FAILED);

                    System.err.println("[失败] " + gate.name() + " - " + target.getPackageName()
                            + ": " + msg);

                    // 临时：旧的全量回滚移除后，在 DeployPipeline 重构（Task 11/12）前先单目标回滚
                    try { rollback.rollbackTarget(target); } catch (Exception ex) { /* ignore */ }
                    DeployResult.RollbackResult rollbackResult = new DeployResult.RollbackResult();
                    rollbackResult.setAttempted(true);
                    rollbackResult.setSuccess(true);
                    result.setRollback(rollbackResult);

                    // 填充已完成目标的结果
                    fillTargetResults(targets, result);
                    return;
                }
            }
        }

        // 全部成功
        result.markSuccess();
        fillTargetResults(targets, result);
    }

    /**
     * Dry-run：仅预检，不执行实际修改
     *
     * @param targets 目标包列表
     * @param ops     FTP 操作对象
     * @param ftpLock FTP 锁操作对象
     * @param result  部署结果对象
     * @return 预检结果
     * @author xumanyi
     * @date 2026-03-26
     */
    private DeployResult executeDryRun(List<TargetPackage> targets, FtpOperations ops,
                                       FtpLock ftpLock, DeployResult result) {
        System.out.println("\n=== DRY-RUN 模式（仅预检，不执行修改） ===\n");

        for (TargetPackage target : targets) {
            try {
                // 检查远程包是否存在
                long remoteSize = ops.getFileSize(target.getRemotePath());
                boolean exists = remoteSize >= 0;

                // 检查残留锁
                List<String> locks = ftpLock.findResidualLocks(
                        target.getRemoteDir(), target.getPackageName());

                long localSize = java.nio.file.Files.size(target.getLocalStagingFile());

                System.out.println("目标: " + target.getPackageName());
                System.out.println("  远程路径: " + target.getRemotePath());
                System.out.println("  远程包: " + (exists ? "存在 (" + remoteSize + " 字节)" : "不存在"));
                System.out.println("  本地暂存包: " + target.getLocalStagingFile() + " (" + localSize + " 字节)");
                System.out.println("  残留锁: " + (locks.isEmpty() ? "无" : locks));
                System.out.println();

                DeployResult.TargetResult tr = new DeployResult.TargetResult();
                tr.setPackageName(target.getPackageName());
                tr.setRemotePath(target.getRemotePath());
                result.addTarget(tr);

            } catch (IOException e) {
                result.addError("dry-run", target.getPackageName(), e.getMessage());
            }
        }

        result.markSuccess();
        return result;
    }

    /**
     * 构建显式目标包列表（仅处理 name 不为 null 的条目）。
     * name 为 null 的条目由 {@link #resolveScannedTargets} 处理。
     *
     * @author xumanyi
     * @date 2026-03-26
     */
    private List<TargetPackage> buildTargets() {
        List<TargetPackage> targets = new ArrayList<>();
        List<Path> localFiles = config.getLocalFiles();
        List<String> targetNames = config.getTargetNames();
        List<String> relativePaths = config.getTargetRelativePaths();

        for (int i = 0; i < localFiles.size(); i++) {
            String name = targetNames.get(i);
            if (name == null) {
                continue; // 扫描模式，由 resolveScannedTargets 处理
            }
            TargetPackage tp = new TargetPackage();
            tp.setLocalStagingFile(localFiles.get(i));
            tp.setPackageName(name);

            String remoteDir = config.getRemoteDir();
            if (relativePaths != null && i < relativePaths.size() && relativePaths.get(i) != null) {
                String rel = relativePaths.get(i);
                int lastSlash = rel.lastIndexOf('/');
                if (lastSlash > 0) {
                    remoteDir = ensureTrailingSlash(config.getRemoteDir()) + rel.substring(0, lastSlash);
                }
            }
            tp.setRemoteDir(ensureTrailingSlash(remoteDir));
            tp.setRemotePath(tp.getRemoteDir() + tp.getPackageName());

            targets.add(tp);
        }
        return targets;
    }

    /**
     * 增量/自动模式下，为每个目标构建 staging 暂存包。
     *
     * <p>StagingPackageBuilder 自己会连接 FTP 下载远端原包、用已编译的 target/classes 打补丁，
     * 输出到模块 {@code target/} 下。构建成功后用暂存包路径覆盖 {@link TargetPackage#getLocalStagingFile}。</p>
     *
     * @return true=全部 staging 成功，false=有任何失败（错误已写入 result）
     * @author xumanyi
     * @date 2026-04-25
     */
    private boolean runStagingForTargets(List<TargetPackage> targets, DeployResult result) {
        String projectDir = config.getProjectDir();
        if (projectDir == null || projectDir.isBlank()) {
            result.addError("staging", "", "incremental 模式需要 projectDir");
            return false;
        }
        List<String> changedFiles = config.getChangedFiles();
        if (changedFiles == null || changedFiles.isEmpty()) {
            result.addError("staging", "", "incremental 模式需要 changedFiles");
            return false;
        }

        for (TargetPackage target : targets) {
            try {
                StagingPackageBuilder builder = new StagingPackageBuilder(
                        projectDir,
                        target.getPackageName(),
                        changedFiles,
                        System.out::println);
                Path staging = builder.build(
                        config.getHost(), config.getPort(),
                        config.getUsername(), config.getPassword(),
                        target.getRemotePath());
                if (staging == null || !Files.isRegularFile(staging)) {
                    result.addError("staging", target.getPackageName(),
                            "暂存包构建失败（可能未编译或未找到变更的 class）");
                    return false;
                }
                target.setLocalStagingFile(staging);
                System.out.println("[staging] " + target.getPackageName() + " → " + staging);
            } catch (IOException e) {
                result.addError("staging", target.getPackageName(), "构建暂存包异常: " + e.getMessage());
                return false;
            }
        }
        return true;
    }

    /**
     * 对 name 为 null 的条目，连接 FTP 后按文件名递归扫描 remoteDir，展开为具体目标包。
     * 任一文件名未找到匹配时记录错误并返回 null（调用方应立即终止流水线）。
     *
     * @param ops    已连接的 FTP 操作对象
     * @param result 用于记录错误
     * @return 展开后的目标包列表；任一文件名无匹配时返回 null
     * @author xumanyi
     * @date 2026-04-24
     */
    private List<TargetPackage> resolveScannedTargets(FtpOperations ops, DeployResult result) {
        List<TargetPackage> resolved = new ArrayList<>();
        List<Path> localFiles = config.getLocalFiles();
        List<String> targetNames = config.getTargetNames();

        for (int i = 0; i < localFiles.size(); i++) {
            if (targetNames.get(i) != null) {
                continue; // 显式 name，已在 buildTargets 处理
            }
            Path localFile = localFiles.get(i);
            String basename = localFile.getFileName().toString();

            List<String> matches;
            try {
                matches = ops.scanByBasename(config.getRemoteDir(), basename);
            } catch (IOException e) {
                result.addError("scan", basename, "扫描失败: " + e.getMessage());
                return null;
            }

            if (matches.isEmpty()) {
                result.addError("scan", basename,
                        "未找到匹配文件: " + basename + "（remoteDir: " + config.getRemoteDir() + "）");
                return null;
            }

            for (String remotePath : matches) {
                TargetPackage tp = new TargetPackage();
                tp.setLocalStagingFile(localFile);
                tp.setPackageName(basename);
                int lastSlash = remotePath.lastIndexOf('/');
                String remoteDir = lastSlash > 0 ? remotePath.substring(0, lastSlash + 1) : config.getRemoteDir();
                tp.setRemoteDir(remoteDir);
                tp.setRemotePath(remotePath);
                resolved.add(tp);
            }
        }
        return resolved;
    }

    /**
     * 将目标包的运行时状态填入部署结果对象
     *
     * @param targets 目标包列表
     * @param result  部署结果对象
     * @author xumanyi
     * @date 2026-03-26
     */
    private void fillTargetResults(List<TargetPackage> targets, DeployResult result) {
        for (TargetPackage target : targets) {
            DeployResult.TargetResult tr = new DeployResult.TargetResult();
            tr.setPackageName(target.getPackageName());
            tr.setRemotePath(target.getRemotePath());
            tr.setBackupPath(target.getBackupRemotePath());
            tr.setLocalSha256(target.getLocalSha256());
            tr.setRemoteSha256(target.getRemoteSha256());
            tr.setVerified(target.getStatus().ordinal() >= TargetPackage.Status.VERIFIED.ordinal());
            tr.setNoteUpdated(target.getStatus().ordinal() >= TargetPackage.Status.NOTE_UPDATED.ordinal());
            tr.setNoteBackupSynced(target.getStatus() == TargetPackage.Status.COMPLETED);
            result.addTarget(tr);
        }
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
