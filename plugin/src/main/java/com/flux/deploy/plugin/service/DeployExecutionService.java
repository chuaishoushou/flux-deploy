package com.flux.deploy.plugin.service;

import com.flux.deploy.deploy.CancellationToken;
import com.flux.deploy.deploy.DeployPipeline;
import com.flux.deploy.deploy.ResidualLockDiagnosis;
import com.flux.deploy.deploy.ResidualLockResolver;
import com.flux.deploy.deploy.gates.NoteFileNames;
import com.flux.deploy.ftp.FtpLock;
import com.flux.deploy.ftp.FtpOperations;
import com.flux.deploy.ftp.FtpSession;
import com.flux.deploy.model.DeployConfig;
import com.flux.deploy.model.DeployResult;
import com.flux.deploy.plugin.model.DeployMode;
import com.flux.deploy.plugin.model.FtpTargetSelection;
import com.flux.deploy.plugin.model.PluginDeployConfig;
import com.flux.deploy.plugin.toolwindow.ResidualLockResolveDialog;
import com.flux.deploy.plugin.util.LogInterceptor;
import com.flux.deploy.util.WarEmbedUtil;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

import org.apache.commons.net.ftp.FTPFile;

import javax.swing.SwingUtilities;
import java.io.File;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * 部署执行服务
 *
 * <p>桥接插件 UI 与 CLI 的 DeployPipeline，提供完整的部署生命周期管理：
 * 编译、暂存包构建、事务性多目标部署（备份→加锁→上传→嵌入→解锁→版本记录）、
 * 手动回滚等功能。</p>
 *
 * <p>所有方法均为静态方法，通过 IDEA 的后台任务机制异步执行。</p>
 *
 * @author xumanyi
 * @date 2026-03-27
 */
public class DeployExecutionService {

    private DeployExecutionService() {}

    /** null 安全转空字符串 */
    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    /**
     * 把当前 IDEA 后台任务的 {@link ProgressIndicator} 适配成核心层的
     * {@link CancellationToken} 注入到 {@link DeployConfig}，
     * 让 pipeline 能在网关之间感知用户点击 IDE 取消按钮。
     *
     * <p>必须在每次 {@code new DeployPipeline(cfg).execute()} 之前调用，
     * 这样在 stage1 / stage2 的轮询点上 {@code isCancelled()} 才能返回 true。
     * 当 indicator 为 null（非任务上下文）时，token 永远返回 false，行为退化为不可取消。</p>
     *
     * @param cfg 即将交给 pipeline 的部署配置
     * @author xumanyi
     * @date 2026-04-29
     */
    /**
     * 当前正在执行 deploy 的 IDEA Project 引用。
     *
     * <p>在 {@link #execute} / {@link #executeLocalMode} 入口处由
     * {@link #setActiveProject(Project)} 写入，供深层嵌套方法（如 {@code executeWarEmbed}）
     * 在不增加方法签名的前提下读取，用来实例化 {@link RetryPromptDialog}。</p>
     *
     * <p>volatile 保证写后立即对其他线程可见；同一时刻只允许一个 deploy 任务在跑
     * （UI 入口已串行化），不存在并发覆写问题。</p>
     */
    private static volatile Project activeProject;

    /**
     * 备份阶段下载到本地的原 WAR 临时副本，按目标 relativePath 索引。
     *
     * <p>用途：备份阶段把远端原包下载到本地 temp 然后再上传到 backup 目录；
     * 嵌入阶段需要的"远端原包字节"和备份阶段下载的字节完全相同（远端在加锁前后内容不变，
     * 锁仅是 FTP rename）。把 temp 文件保留并登记到本表，嵌入阶段优先复用，
     * 跳过一次完整的远端下载，节省约 1/2 的嵌入阶段网络流量。</p>
     *
     * <p>生命周期：每次 {@link #execute} 入口处清空，备份阶段写入，嵌入阶段读取，
     * deploy 结束（成功 / 失败 / 取消）由入口 finally 统一删除并清空。</p>
     *
     * <p>静态字段在并发执行场景下不安全，但本插件 UI 入口已串行化（同一时刻只允许一个
     * deploy 任务在跑），与 {@link #activeProject} / {@link #currentCancelMode} 沿用同一假设。</p>
     *
     * @author xumanyi
     * @date 2026-05-08
     */
    private static final java.util.concurrent.ConcurrentMap<String, Path> backupLocalCopies =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * FTP 操作并发度（备份阶段并发数 + 嵌入阶段上传池规模）。
     *
     * <p>写死为 3：</p>
     * <ul>
     *   <li>实测 3 并发对常见 FTP 服务端能拿到接近线性加速且不触发 max-clients 限制；</li>
     *   <li>FTP 服务端连接上限由运维配置，与本机硬件无关，不应暴露给用户调；</li>
     *   <li>调大可能踩 421 / 拒连，调小白白浪费带宽。</li>
     * </ul>
     *
     * <p>本地补丁并发度由 {@link com.flux.deploy.parallel.PipelineExecutor} 内部固定为 1，
     * 不再暴露为可配项（瓶颈在 FTP，本地并发无收益）。
     * 嵌入阶段下载子池规模写死为 1：备份阶段已下载远端字节并保留在本地，
     * 嵌入阶段优先复用本地副本，真正走 FTP 下载只是少数 fallback。</p>
     */
    private static final int FTP_PARALLELISM = 3;

    /**
     * 设置当前活跃 Project。在每个 deploy 入口最开始调用，结束 finally 里清回 null。
     *
     * @param project IDEA Project，可为 null 表示清理
     * @author xumanyi
     * @date 2026-05-03
     */
    private static void setActiveProject(Project project) {
        activeProject = project;
    }

    /**
     * 清理备份阶段为复用而保留的所有本地原 WAR 副本。
     *
     * <p>在 {@link #execute} finally 阶段调用，保证不论部署成功 / 失败 / 取消都不留 temp 文件。
     * 删除失败仅静默忽略——OS 重启或 IDE 重启后系统会自动清理 temp 目录。</p>
     *
     * @author xumanyi
     * @date 2026-05-08
     */
    private static void clearBackupLocalCopies() {
        for (Path p : backupLocalCopies.values()) {
            if (p != null) {
                try { Files.deleteIfExists(p); } catch (Exception ignored) {}
            }
        }
        backupLocalCopies.clear();
    }

    private static void applyCancellationToken(DeployConfig cfg) {
        ProgressIndicator indicator = ProgressManager.getInstance().getProgressIndicator();
        cfg.setCancellationToken(new CancellationToken() {
            @Override
            public boolean isCancelled() {
                return (indicator != null && indicator.isCanceled())
                        || currentCancelMode != CancelMode.NONE;
            }

            @Override
            public void throwIfCancelled() {
                if (isCancelled()) {
                    throw new CancellationToken.CancellationException();
                }
            }
        });
        // 注入 IDE 弹窗版重试提示器；非 IDE 上下文（activeProject 为 null）时
        // DeployConfig 默认 abortAll，行为退化为非交互直接结束
        Project p = activeProject;
        if (p != null) {
            cfg.setRetryPrompter(new RetryPromptDialog(p));
        }
    }

    /**
     * 从 DeployResult 的 errors 里提取第一条错误信息，用于预检失败总结日志。
     * 没有错误列表或结果为空时返回 "未知原因"。
     *
     * @author xumanyi
     * @date 2026-04-21
     */
    private static String extractFirstErrorMessage(DeployResult result) {
        if (result == null) {
            return "预检返回空结果";
        }
        if (result.getErrors() != null && !result.getErrors().isEmpty()) {
            DeployResult.ErrorInfo first = result.getErrors().get(0);
            String gate = first.getGate() == null ? "" : "[" + first.getGate() + "] ";
            String target = first.getTarget() == null ? "" : first.getTarget() + " - ";
            String msg = first.getMessage() == null ? "" : first.getMessage();
            return gate + target + msg;
        }
        return "未知原因";
    }

    /**
     * 递归清理指定目录下的空子目录
     *
     * <p>自底向上：先递归清子目录，清完后再判当前目录本身是否为空；是则删。
     * {@code rootDir} 本身不会被删除，只清它下面的所有空后代目录。</p>
     *
     * @param ops         FTP 操作
     * @param client      FTPClient（用于 removeDirectory，FtpOperations 未直接暴露该 API）
     * @param rootDir     根目录，末尾带 /
     * @param logCallback 日志回调
     * @author xumanyi
     * @date 2026-04-19
     */
    /**
     * 在新建的 FTP 短连接里执行带返回值的操作；执行完毕自动关闭连接。
     *
     * <p>用于"每文件一连接"模式，避免长生命周期的控制通道在批量大文件传输间隙
     * 因服务端空闲超时被关闭（FTP 421）。所有涉及多文件 download/upload 的循环
     * 都应改用本方法逐文件取连接，禁止跨文件复用同一个 {@link FtpSession}。</p>
     *
     * @param host   FTP 主机
     * @param port   FTP 端口
     * @param user   FTP 用户名
     * @param pass   FTP 密码
     * @param action 在新连接上执行的操作，回调收到 {@link FtpSession} 与 {@link FtpOperations}
     * @param <T>    操作返回值类型
     * @return action 的返回值
     * @throws Exception 连接失败、认证失败或回调内部抛出的异常
     * @author xumanyi
     * @date 2026-04-28
     */
    private static <T> T withFreshFtpSession(
            String host, int port, String user, String pass,
            FtpAction<T> action) throws Exception {
        try (FtpSession session = new FtpSession(host, port)) {
            session.connect(user, pass);
            return action.run(session, new FtpOperations(session));
        }
    }

    /**
     * {@link #withFreshFtpSession(String, int, String, String, FtpAction)} 的 void 版本，
     * 用于不需要返回值的操作。
     *
     * <p>注意：void 版本和 T 版本必须使用不同方法名——Java 在隐式类型 lambda 上做重载解析时，
     * 无法在 {@code FtpAction<T>} 与 {@code FtpVoidAction} 之间确定唯一最具体方法。</p>
     *
     * @param host   FTP 主机
     * @param port   FTP 端口
     * @param user   FTP 用户名
     * @param pass   FTP 密码
     * @param action 在新连接上执行的操作
     * @throws Exception 连接失败、认证失败或回调内部抛出的异常
     * @author xumanyi
     * @date 2026-04-28
     */
    private static void runFreshFtpSession(
            String host, int port, String user, String pass,
            FtpVoidAction action) throws Exception {
        try (FtpSession session = new FtpSession(host, port)) {
            session.connect(user, pass);
            action.run(session, new FtpOperations(session));
        }
    }

    /**
     * {@link #withFreshFtpSession(String, int, String, String, FtpAction)} 使用的回调接口（带返回值）。
     *
     * @param <T> 返回值类型
     * @author xumanyi
     * @date 2026-04-28
     */
    @FunctionalInterface
    private interface FtpAction<T> {
        T run(FtpSession session, FtpOperations ops) throws Exception;
    }

    /**
     * {@link #runFreshFtpSession(String, int, String, String, FtpVoidAction)} 使用的回调接口（无返回值）。
     *
     * @author xumanyi
     * @date 2026-04-28
     */
    @FunctionalInterface
    private interface FtpVoidAction {
        void run(FtpSession session, FtpOperations ops) throws Exception;
    }

    private static void cleanEmptySubDirs(FtpOperations ops,
                                           org.apache.commons.net.ftp.FTPClient client,
                                           String rootDir,
                                           Consumer<String> logCallback) {
        try {
            List<FTPFile> entries = ops.listFiles(rootDir);
            for (FTPFile f : entries) {
                String name = f.getName();
                if (!f.isDirectory() || ".".equals(name) || "..".equals(name)) continue;
                String subPath = rootDir + name + "/";
                // 先递归清理子目录下的空孙子目录
                cleanEmptySubDirs(ops, client, subPath, logCallback);
                // 子级清完后重新检查本目录，空则删
                List<FTPFile> remaining = ops.listFiles(subPath);
                if (remaining.isEmpty()) {
                    try {
                        client.removeDirectory(subPath);
                        if (logCallback != null) {
                            logCallback.accept("INFO  [回滚] 空备份子目录已删除: " + subPath);
                        }
                    } catch (Exception ignored) {}
                }
            }
        } catch (Exception ignored) {}
    }

    /**
     * 在日志末尾写入失败总结卡片，便于用户一眼在最后看到结果而无需上翻日志
     *
     * @param reason 失败原因（简短描述）
     * @author xumanyi
     * @date 2026-04-19
     */
    private static void logFailureSummary(Consumer<String> logCallback, String reason) {
        logCallback.accept("\n╔══════════════════════════════╗");
        logCallback.accept("║          部署失败            ║");
        logCallback.accept("╚══════════════════════════════╝");
        logCallback.accept("原因：" + reason);
        // 失败兜底：把已成功（实际已被回滚）的包列出来，避免用户上翻日志
        List<String> succeeded = getLiveSucceededNames();
        if (!succeeded.isEmpty()) {
            logCallback.accept("已回滚 " + succeeded.size() + " 个包（曾上传成功）：");
            for (String n : succeeded) logCallback.accept("  · " + n);
        } else {
            logCallback.accept("已成功上传：无");
        }
        logCallback.accept("建议：按日志中的 ERROR 行定位具体步骤后再试。");
    }

    /**
     * 用户主动停止后的总结卡片：区分 KEEP_SUCCEEDED / ROLLBACK_ALL，列出保留 / 回滚的包。
     *
     * <p>与 {@link #logFailureSummary} 区分语义——这是用户主动行为，不是失败。</p>
     *
     * @param failedTarget 触发停止时正在处理的目标（用于"未完成"列表）；可为 null
     * @author xumanyi
     * @date 2026-04-29
     */
    private static void logStopSummary(Consumer<String> logCallback, String failedTarget) {
        boolean keep = currentCancelMode == CancelMode.KEEP_SUCCEEDED;
        List<String> succeeded = getLiveSucceededNames();
        int total = currentTotalTargets;
        logCallback.accept("\n╔══════════════════════════════╗");
        logCallback.accept("║          部署已停止           ║");
        logCallback.accept("╚══════════════════════════════╝");
        logCallback.accept("原因：用户主动停止");
        logCallback.accept("处理方式：" + (keep ? "保留已成功的包" : "回滚已成功的包"));
        if (succeeded.isEmpty()) {
            logCallback.accept((keep ? "保留" : "已回滚") + " 0 个包（停止时尚无包成功）");
        } else {
            logCallback.accept((keep ? "已保留" : "已回滚") + " " + succeeded.size() + " 个包：");
            for (String n : succeeded) logCallback.accept("  · " + n);
        }
        int remaining = Math.max(0, total - succeeded.size() - (failedTarget != null ? 1 : 0));
        if (failedTarget != null) {
            logCallback.accept("中断目标：" + failedTarget);
        }
        if (remaining > 0) {
            logCallback.accept("未处理：" + remaining + " 个");
        }
        if (keep) {
            logCallback.accept("提示：可点击「回滚」按钮事后撤销已保留的包。");
        }
    }

    // 编译相关辅助（saveAllDocuments / logArtifactInfo / runMavenPackage / JDK 探测 / Maven 配置探测）
    // 已整体移除：本插件不再触发任何编译，target/ 下的产物由用户自行通过 IDE Build (Cmd+F9) 或外部 mvn
    // 准备，缺失时由 UI 层 ArtifactPresenceValidator 在点击部署前以弹窗提前拒绝。

    /** 上次成功部署的备份目录 */
    private static volatile String lastBackupDir;
    /** 上次成功部署的已更新包列表：[remotePath, backupFilePath] */
    private static volatile List<String[]> lastUpdatedPackages;
    /** 上次部署的所有目标（用于回滚版本记录） */
    private static volatile List<FtpTargetSelection> lastAllTargets;
    /** 上次部署是否更新了版本记录 */
    private static volatile boolean lastUpdatedNote;
    /** 上次部署是否借用了已有备份（USE_EXISTING 策略），手动回滚时保留老备份不做清理 */
    private static volatile boolean lastBackupBorrowed;

    /**
     * 手动回滚上次部署
     *
     * <p>恢复所有已更新包为备份版本，撤销版本记录，删除备份目录。
     * 回滚完成后清除回滚数据（只能回滚一次）。</p>
     *
     * @param project     IDEA 项目
     * @param ftpHost     FTP 主机
     * @param ftpPort     FTP 端口
     * @param ftpUsername  FTP 用户名
     * @param ftpPassword  FTP 密码
     * @param logCallback 日志回调
     * @param onComplete  完成回调（在 EDT 线程执行）
     * @author xumanyi
     * @date 2026-03-27
     */
    public static void manualRollback(Project project,
                                       String ftpHost, int ftpPort, String ftpUsername, String ftpPassword,
                                       Consumer<String> logCallback, Runnable onComplete) {
        if (lastBackupDir == null || lastUpdatedPackages == null || lastUpdatedPackages.isEmpty()) {
            logCallback.accept("INFO  [回滚] 没有可回滚的部署记录");
            onComplete.run();
            return;
        }

        ProgressManager.getInstance().run(new Task.Backgroundable(project, "FLUX 回滚", true) {
            /**
             * 在后台线程执行回滚操作
             *
             * @param indicator 进度指示器
             * @author xumanyi
             * @date 2026-03-27
             */
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                logCallback.accept("=== 开始回滚 ===");
                logCallback.accept("INFO  [回滚] 备份目录: " + lastBackupDir);
                logCallback.accept("INFO  [回滚] 需要恢复 " + lastUpdatedPackages.size() + " 个包");

                int restoreSuccess = 0;
                int restoreFail = 0;

                // 1. 恢复所有包：逐文件独立短连接，与 preBackupAll / rollbackAll 一致，
                //    避免长会话在多包传输间隙被服务端 421。
                for (String[] pair : lastUpdatedPackages) {
                    final String remotePath = pair[0];
                    final String backupFilePath = pair[1];
                    try {
                        Path tempRestore = Files.createTempFile("restore-", ".tmp");
                        try {
                            runFreshFtpSession(ftpHost, ftpPort, ftpUsername, ftpPassword,
                                    (s, ops) -> {
                                        ops.download(backupFilePath, tempRestore);
                                        ops.upload(tempRestore, remotePath);
                                    });
                            logCallback.accept("INFO  [回滚] 已恢复: " + remotePath);
                            restoreSuccess++;
                        } finally {
                            Files.deleteIfExists(tempRestore);
                        }
                    } catch (Exception e) {
                        logCallback.accept("INFO  [回滚] 恢复失败: " + remotePath + " - " + e.getMessage());
                        restoreFail++;
                    }
                }

                // 2. 回滚版本记录
                if (lastUpdatedNote && lastAllTargets != null) {
                    logCallback.accept("INFO  [回滚] 回滚版本记录...");
                    rollbackNotes(lastAllTargets,
                            ftpHost, ftpPort, ftpUsername, ftpPassword, logCallback);
                }

                // 3. 清理备份（借用已有备份时跳过此步，老备份不是本次创建的）
                if (lastBackupBorrowed) {
                    logCallback.accept("INFO  [回滚] 借用已有备份作为回滚源，保留备份文件不做清理");
                } else {
                    // 清理阶段全是命令操作，无数据传输，复用一个短连接安全。
                    try {
                        runFreshFtpSession(ftpHost, ftpPort, ftpUsername, ftpPassword, (s, ops) -> {
                            for (String[] pair : lastUpdatedPackages) {
                                String backupFilePath = pair[1];
                                try {
                                    ops.delete(backupFilePath);
                                    logCallback.accept("INFO  [回滚] 已删除备份: " + backupFilePath);
                                } catch (Exception ignored) {}
                            }
                            // 递归清理空子目录（深层结构：{lastBackupDir}/{nested}/{subdir}/X.jar）
                            cleanEmptySubDirs(ops, s.getClient(), lastBackupDir, logCallback);

                            // 检查备份目录是否为空，空则删除目录
                            List<FTPFile> remaining = ops.listFiles(lastBackupDir);
                            if (remaining.isEmpty()) {
                                s.getClient().removeDirectory(lastBackupDir);
                                logCallback.accept("INFO  [回滚] 备份目录已空，已删除: " + lastBackupDir);
                            } else {
                                logCallback.accept("INFO  [回滚] 备份目录仍有 " + remaining.size()
                                        + " 个其他条目，保留目录");
                            }
                        });
                    } catch (Exception e) {
                        logCallback.accept("INFO  [回滚] 清理备份失败: " + e.getMessage());
                    }
                }

                // 4. 回滚总结
                logCallback.accept("\n========== 回滚总结 ==========");
                for (String[] pair : lastUpdatedPackages) {
                    logCallback.accept("  " + pair[0]);
                }
                if (restoreFail > 0) {
                    logCallback.accept("WARN  [回滚] 回滚部分完成，成功 " + restoreSuccess + " 个，失败 " + restoreFail + " 个");
                } else {
                    logCallback.accept("INFO  [回滚] 回滚全部完成，已恢复 " + restoreSuccess + " 个包");
                }

                // 清除回滚信息（只能回滚一次）
                lastBackupDir = null;
                lastUpdatedPackages = null;
                lastAllTargets = null;
                lastUpdatedNote = false;
                lastBackupBorrowed = false;

                SwingUtilities.invokeLater(onComplete);
            }
        });
    }

    /**
     * 是否有可回滚的部署
     *
     * @return {@code true} 表示存在可回滚的部署记录
     * @author xumanyi
     * @date 2026-03-27
     */
    public static boolean hasRollbackData() {
        return lastBackupDir != null && lastUpdatedPackages != null && !lastUpdatedPackages.isEmpty();
    }

    // ═══════════════════════════════════════════════════════════════
    //  用户主动停止：模式枚举 + 进行中状态（供 UI 弹"如何收尾"对话框用）
    // ═══════════════════════════════════════════════════════════════

    /**
     * 用户主动停止时选择的收尾模式
     *
     * <ul>
     *   <li>NONE：未取消（默认）。IDEA 进度条 cancel 也走 NONE，catch 块按 ROLLBACK_ALL 兜底。</li>
     *   <li>ROLLBACK_ALL：停止并回滚已成功的包。语义同失败兜底。</li>
     *   <li>KEEP_SUCCEEDED：停止但保留已成功的包。备份目录保留，登记到 lastUpdatedPackages 供事后手动回滚。</li>
     * </ul>
     */
    public enum CancelMode { NONE, ROLLBACK_ALL, KEEP_SUCCEEDED }

    /** 当前部署任务的取消模式（UI 点击停止按钮时设置；execute 入口重置为 NONE） */
    private static volatile CancelMode currentCancelMode = CancelMode.NONE;
    /** 当前部署任务已成功上传/嵌入的包列表（实时追加，UI 弹窗读取展示用）。null 表示当前没有部署任务 */
    private static volatile List<String[]> currentSucceededUploads;
    /** 当前部署任务的目标总数（主目标 + 嵌入目标）。0 表示当前没有部署任务 */
    private static volatile int currentTotalTargets;
    /** 当前部署任务是否为 dryRun（预检不需要弹窗收尾） */
    private static volatile boolean currentDryRun;

    /**
     * 用户在 UI 上点击停止后，设置收尾模式并触发 cancel 信号。
     *
     * <p>由 EDT 调用；后台线程在下一个网关感知到 cancel，进入 catch 块按 mode 分支处理。
     * 同一次部署内重复调用以最后一次为准。</p>
     *
     * @param mode ROLLBACK_ALL 或 KEEP_SUCCEEDED；NONE 等于不操作
     * @author xumanyi
     * @date 2026-04-29
     */
    public static void requestStop(CancelMode mode) {
        if (mode == null || mode == CancelMode.NONE) return;
        currentCancelMode = mode;
    }

    /**
     * UI 弹窗读取：当前已成功上传/嵌入的包名列表（仅文件名，便于直接展示）。
     *
     * @return 不可变快照；无活动任务返回空列表
     * @author xumanyi
     * @date 2026-04-29
     */
    public static List<String> getLiveSucceededNames() {
        List<String[]> snap = currentSucceededUploads;
        if (snap == null || snap.isEmpty()) return java.util.Collections.emptyList();
        List<String> names = new ArrayList<>(snap.size());
        synchronized (snap) {
            for (String[] pair : snap) {
                String rp = pair[0];
                int slash = rp.lastIndexOf('/');
                names.add(slash >= 0 ? rp.substring(slash + 1) : rp);
            }
        }
        return names;
    }

    /** UI 弹窗读取：当前部署的目标总数（主目标 + 嵌入目标） */
    public static int getLiveTotalTargets() { return currentTotalTargets; }

    /** UI 弹窗读取：当前是否为预检（预检不应弹"如何收尾"对话框） */
    public static boolean isCurrentDryRun() { return currentDryRun; }

    /** UI 读取：当前是否已请求停止（按钮变 STOPPING 态后用于幂等防抖） */
    public static boolean isStopRequested() { return currentCancelMode != CancelMode.NONE; }

    /**
     * 后台流程在每次主目标上传成功 / 嵌入成功后调用，把目标登记到实时成功列表，
     * 供 EDT 上的"如何收尾"对话框读取展示。
     *
     * @param target 已成功的目标
     * @author xumanyi
     * @date 2026-04-29
     */
    private static void recordSucceededUpload(FtpTargetSelection target) {
        List<String[]> sink = currentSucceededUploads;
        if (sink == null || target == null) return;
        String rp = target.getRemoteDir() + target.getRelativePath();
        sink.add(new String[]{rp, null});
    }

    /**
     * 在主目标上传 / WAR 嵌入失败 / 用户取消时执行收尾：根据 {@link #currentCancelMode} 决定是否回滚。
     *
     * <p>分支说明：
     * <ul>
     *   <li>KEEP_SUCCEEDED（用户选择"保留已成功"）：不调用 rollbackAll，将已成功的子集
     *       登记到 {@code lastUpdatedPackages} 供事后手动回滚使用，备份目录保留。
     *       若当前实际尚无包成功，等价于 ROLLBACK_ALL。</li>
     *   <li>NONE / ROLLBACK_ALL：调用 rollbackAll 还原所有备份注册的包（保持失败兜底默认行为）。</li>
     * </ul>
     * USE_EXISTING 借用备份（{@code backupBorrowed=true}）下，KEEP_SUCCEEDED 仍走默认回滚——
     * 借用备份的语义是"复用他人备份做回滚源"，本任务不应改变其归属。</p>
     *
     * @param backupDir       备份根目录；null 表示未备份，无法回滚
     * @param updatedPackages 已注册到回滚清单的所有包：[remotePath, backupFilePath]
     * @param backupBorrowed  备份是否借用自他人（USE_EXISTING）
     * @param allTargets      本次部署的全部目标（用于 KEEP 路径登记 lastAllTargets）
     * @author xumanyi
     * @date 2026-04-29
     */
    private static void abortPartial(
            String backupDir,
            List<String[]> updatedPackages,
            boolean backupBorrowed,
            List<FtpTargetSelection> allTargets,
            String ftpHost, int ftpPort, String ftpUsername, String ftpPassword,
            Consumer<String> logCallback) {

        if (currentCancelMode == CancelMode.KEEP_SUCCEEDED
                && !backupBorrowed && backupDir != null) {
            // currentSucceededUploads 是 synchronizedList，迭代时必须显式同步源 list 才安全
            List<String[]> succeededSnap;
            if (currentSucceededUploads != null) {
                synchronized (currentSucceededUploads) {
                    succeededSnap = new ArrayList<>(currentSucceededUploads);
                }
            } else {
                succeededSnap = new ArrayList<>();
            }
            if (succeededSnap.isEmpty()) {
                logCallback.accept("INFO  [部署] 用户选择保留已成功，但当前尚无包成功；按回滚处理。");
                rollbackAll(backupDir, updatedPackages,
                        ftpHost, ftpPort, ftpUsername, ftpPassword, logCallback, backupBorrowed);
                return;
            }
            logCallback.accept("INFO  [部署] 用户选择保留已成功的 " + succeededSnap.size()
                    + " 个包；备份目录保留，可使用「回滚」按钮事后撤销");
            // 在 updatedPackages 中按 remotePath 关联备份路径，构造 manualRollback 用列表。
            // updatedPackages 是 synchronizedList：嵌套迭代必须同步源 list 才安全。
            List<String[]> kept = new ArrayList<>();
            synchronized (updatedPackages) {
                for (String[] succ : succeededSnap) {
                    String rp = succ[0];
                    for (String[] pair : updatedPackages) {
                        if (rp.equals(pair[0])) {
                            kept.add(new String[]{pair[0], pair[1]});
                            break;
                        }
                    }
                }
            }
            lastBackupDir = backupDir;
            lastUpdatedPackages = kept;
            lastAllTargets = new ArrayList<>(allTargets);
            lastUpdatedNote = false;
            lastBackupBorrowed = backupBorrowed;
            return;
        }
        if (backupDir != null && !updatedPackages.isEmpty()) {
            rollbackAll(backupDir, updatedPackages,
                    ftpHost, ftpPort, ftpUsername, ftpPassword, logCallback, backupBorrowed);
        } else {
            logCallback.accept("WARN  [回滚] 无备份，无法自动回滚");
        }
    }

    /**
     * 执行部署
     *
     * @param project       IDEA 项目
     * @param pluginConfig  插件配置（来自 UI）
     * @param ftpHost       已连接的 FTP 主机
     * @param ftpPort       已连接的 FTP 端口
     * @param ftpUsername    已连接的 FTP 用户名
     * @param ftpPassword   已连接的 FTP 密码
     * @param logCallback   日志回调（输出到日志面板）
     * @param onComplete    完成回调
     * @author xumanyi
     * @date 2026-03-27
     */
    /**
     * 本地模式执行：编译工程 → 调用 {@link LocalPackagePatchService} 打补丁 → 回调结果
     *
     * <p>与 {@link #execute} 不同，本方法不走 FTP、不备份、不写版本记录、不可回滚。
     * 预检（dryRun）已在 UI 层通过 {@link LocalPackagePatchService#preCheck} 独立完成，
     * 故本方法不处理 dryRun 分支。</p>
     *
     * @param project       IDEA 项目
     * @param pluginConfig  插件配置（要求 targetMode=LOCAL，已填充 localTarget）
     * @param logCallback   日志回调
     * @param onComplete    执行完成回调（在后台线程执行，包含本地补丁结果）
     * @author xumanyi
     * @date 2026-04-17
     */
    public static void executeLocalMode(Project project, PluginDeployConfig pluginConfig,
                                         Consumer<String> logCallback,
                                         Consumer<LocalPackagePatchService.LocalPatchResult> onComplete) {
        ProgressManager.getInstance().run(new Task.Backgroundable(project, "FLUX 本地打包", true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                indicator.setIndeterminate(true);
                setActiveProject(project);
                try {
                    // 1. 工程基础校验（编译产物存在性已在 UI 层 ArtifactPresenceValidator 提前拒绝过，这里只兜底）
                    if (pluginConfig.getModulePath() == null) {
                        logCallback.accept("ERROR [部署] 未指定工程");
                        onComplete.accept(null);
                        return;
                    }
                    logCallback.accept("INFO  [部署] 插件不再触发 mvn；直接使用 target/ 下已有产物");

                    // 2. 执行本地补丁
                    com.flux.deploy.plugin.model.LocalTargetSelection lt = pluginConfig.getLocalTarget();
                    if (lt == null) {
                        logCallback.accept("ERROR [部署] 缺少本地目标信息");
                        onComplete.accept(null);
                        return;
                    }
                    LocalPackagePatchService.LocalPatchResult result = LocalPackagePatchService.execute(
                            pluginConfig.getMode(),
                            pluginConfig.getModulePath(),
                            pluginConfig.getArtifactFileName(),
                            pluginConfig.getChangedFiles(),
                            lt.getPackagePath(),
                            lt.getOutputDir(),
                            logCallback);
                    if (result == null || !result.isSuccess()) {
                        logFailureSummary(logCallback,
                                result == null ? "本地打包失败" : result.getErrorMessage());
                    }
                    onComplete.accept(result);
                } catch (Exception e) {
                    logCallback.accept("ERROR [部署] " + e.getMessage());
                    logFailureSummary(logCallback, "本地打包异常: " + e.getMessage());
                    onComplete.accept(null);
                } finally {
                    setActiveProject(null);
                }
            }
        });
    }

    public static void execute(Project project, PluginDeployConfig pluginConfig,
                               String ftpHost, int ftpPort, String ftpUsername, String ftpPassword,
                               Consumer<String> logCallback, Consumer<DeployResult> onCompleteRaw) {

        // 加载用户偏好配置 ~/.flux-deploy/config.toml；非法值（越界 / 未知策略名）直接终止部署
        // 不静默使用默认值，避免用户改错文件而不自知。
        // 首次使用：load() 会自动生成带注释的默认模板，便于新用户发现可调项
        boolean configExistedBeforeLoad = com.flux.deploy.config.UserConfig.defaultConfigExists();
        final com.flux.deploy.config.UserConfig userConfig;
        try {
            userConfig = com.flux.deploy.config.UserConfig.load();
        } catch (IllegalArgumentException ex) {
            logCallback.accept("ERROR [部署] 配置文件错误：" + ex.getMessage());
            logFailureSummary(logCallback,
                    "用户配置文件 ~/.flux-deploy/config.toml 内容非法，已终止部署");
            onCompleteRaw.accept(null);
            return;
        }
        // 首次部署且模板成功创建 → 给用户一次性提示，便于发现可调项
        if (!configExistedBeforeLoad && com.flux.deploy.config.UserConfig.defaultConfigExists()) {
            logCallback.accept("INFO  [部署] 首次使用，已在 "
                    + com.flux.deploy.config.UserConfig.defaultConfigPath()
                    + " 生成默认配置模板");
            logCallback.accept("INFO  [部署] 如需自定义并发数 / 失败策略 / 重试次数，"
                    + "可手动编辑该文件后再次部署");
        }

        // 复位本次任务的"用户停止"上下文：模式 / 实时成功列表 / 总数 / dryRun 标志
        currentCancelMode = CancelMode.NONE;
        currentSucceededUploads = java.util.Collections.synchronizedList(new ArrayList<>());
        currentTotalTargets = 0;
        currentDryRun = pluginConfig.isDryRun();

        // 清空备份-嵌入复用副本登记表：上一次 deploy 残留的副本（如有）已由对应 finally 清理，
        // 这里再 clear 一次保证本次从空状态开始
        clearBackupLocalCopies();

        // 包裹 onComplete：任务结束（正常 / 异常 / 取消）后清理停止上下文，避免下次部署残留状态
        Consumer<DeployResult> onComplete = result -> {
            try {
                onCompleteRaw.accept(result);
            } finally {
                currentCancelMode = CancelMode.NONE;
                currentSucceededUploads = null;
                currentTotalTargets = 0;
                currentDryRun = false;
            }
        };

        ProgressManager.getInstance().run(new Task.Backgroundable(project, "FLUX Deploy", true) {
            /**
             * 在后台线程执行部署操作
             *
             * @param indicator 进度指示器
             * @author xumanyi
             * @date 2026-03-27
             */
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                indicator.setIndeterminate(true);
                setActiveProject(project);

                // 1. 编译项目（预检跳过编译，只检查 FTP 状态；
                //    插件不再触发任何 mvn / IDE 编译。target/classes 与 target/<artifact> 的存在性
                //    已由 UI 层 ArtifactPresenceValidator 在点击部署前以弹窗强制要求过；这里仅记录一行
                //    日志说明流程，dryRun 下连这行也跳过。
                if (!pluginConfig.isDryRun() && pluginConfig.getModulePath() != null) {
                    logCallback.accept("INFO  [部署] 插件不再触发 mvn；直接使用 target/ 下已有产物");
                } else if (pluginConfig.isDryRun()) {
                    logCallback.accept("INFO  [预检] 跳过编译检查，仅检查 FTP 状态");
                }

                // 2+3: 每个主目标的暂存包 / aligned war 放到 Phase 3 上传循环里按目标逐个构建
                //       这里仅构建一份不含主目标本地文件的基础 DeployConfig 供预检使用
                DeployConfig config = buildDeployConfig(pluginConfig, ftpHost, ftpPort, ftpUsername, ftpPassword, null);

                // 验证本地文件存在（预检时跳过，无主目标时跳过；INCREMENTAL 暂存包路径在
                // Phase 3 内逐个构建，到这一步 localFiles 仍是 buildDeployConfig 给出的 target/<artifact>.jar
                // 占位，FULL 模式上传它，INCREMENTAL 会被 staging 覆盖）
                if (!config.isDryRun() && pluginConfig.getTarget() != null
                        && pluginConfig.getMode() == DeployMode.FULL) {
                    if (config.getLocalFiles() == null || config.getLocalFiles().isEmpty()) {
                        logCallback.accept("ERROR [部署] 未找到本地编译产物");
                        logFailureSummary(logCallback, "未找到本地编译产物");
                        onComplete.accept(null);
                        return;
                    }
                    for (Path lf : config.getLocalFiles()) {
                        if (!Files.exists(lf)) {
                            logCallback.accept("ERROR [部署] 本地文件不存在: " + lf);
                            logFailureSummary(logCallback, "本地文件不存在: " + lf.getFileName());
                            onComplete.accept(null);
                            return;
                        }
                        try {
                            logCallback.accept("INFO  [预检] " + lf.getFileName() + " (" + Files.size(lf) / 1024 + " KB)");
                        } catch (java.io.IOException ignored) {
                            logCallback.accept("INFO  [预检] " + lf.getFileName());
                        }
                    }
                }

                // 3. 拦截 System.out
                PrintStream originalOut = System.out;
                PrintStream originalErr = System.err;
                LogInterceptor interceptor = new LogInterceptor(originalOut, logCallback);
                LogInterceptor errInterceptor = new LogInterceptor(originalErr, logCallback);

                try {
                    System.setOut(interceptor);
                    System.setErr(errInterceptor);

                    // === Dry-run 模式 ===
                    if (config.isDryRun()) {
                        // 外层 panel 已输出 "开始预检..."，此处不重复
                        FtpTargetSelection mainTarget0 = pluginConfig.getTarget();
                        List<FtpTargetSelection> embedTargets0 = pluginConfig.getEmbedTargets();
                        boolean hasEmbed0 = embedTargets0 != null && !embedTargets0.isEmpty();

                        if (mainTarget0 != null) {
                            // 有主目标：用 pipeline 预检
                            applyCancellationToken(config);
                            DeployPipeline pipeline = new DeployPipeline(config);
                            DeployResult result = pipeline.execute();
                            // 总结日志：成功 / 失败一律输出一行
                            if (result != null && result.isSuccess()) {
                                logCallback.accept("INFO  [预检] 通过");
                            } else {
                                String reason = extractFirstErrorMessage(result);
                                logCallback.accept("INFO  [预检] 未通过：" + reason);
                            }
                            onComplete.accept(result);
                        } else if (hasEmbed0) {
                            // 无主目标，只有嵌入目标：直接检查 FTP 状态
                            try (com.flux.deploy.ftp.FtpSession checkSession =
                                    new com.flux.deploy.ftp.FtpSession(ftpHost, ftpPort)) {
                                checkSession.connect(ftpUsername, ftpPassword);
                                com.flux.deploy.ftp.FtpOperations checkOps =
                                        new com.flux.deploy.ftp.FtpOperations(checkSession);
                                int missing = 0;
                                for (FtpTargetSelection et : embedTargets0) {
                                    String rp = et.getRemoteDir() + et.getRelativePath();
                                    long size = checkOps.getFileSize(rp);
                                    boolean exists = size >= 0;
                                    if (!exists) missing++;
                                    logCallback.accept(exists
                                            ? "INFO  [预检] " + et.getTargetName()
                                                    + " 存在，大小 " + size / 1024 / 1024 + " MB"
                                            : "ERROR [预检] " + et.getTargetName() + " 不存在");
                                }
                                DeployResult r = new DeployResult();
                                if (missing == 0) {
                                    logCallback.accept("INFO  [预检] 通过");
                                    r.markSuccess();
                                } else {
                                    String reason = missing + "/" + embedTargets0.size() + " 个 WAR 不存在";
                                    logCallback.accept("INFO  [预检] 未通过：" + reason);
                                    r.addError("preCheck", "embed", reason);
                                }
                                onComplete.accept(r);
                            }
                        } else {
                            logCallback.accept("INFO  [预检] 未通过：无目标包");
                            onComplete.accept(null);
                        }
                        return;
                    }

                    // === 事务性多目标部署流程 ===
                    final long deployStartMs = System.currentTimeMillis();
                    List<FtpTargetSelection> embedTargets = pluginConfig.getEmbedTargets();
                    boolean hasEmbedTargets = embedTargets != null && !embedTargets.isEmpty();

                    // 多主目标：同系统下多个同名 JAR 分散在不同子目录时，独立处理每一个
                    List<FtpTargetSelection> mainTargets = pluginConfig.getMainTargets();
                    FtpTargetSelection mainTarget = mainTargets.isEmpty() ? null : mainTargets.get(0);
                    List<FtpTargetSelection> allTargets = new ArrayList<>();
                    allTargets.addAll(mainTargets);
                    if (hasEmbedTargets) allTargets.addAll(embedTargets);

                    // 登记本次任务总目标数（UI 弹"如何收尾"对话框时展示 N/M 用）
                    currentTotalTargets = allTargets.size();

                    logCallback.accept("INFO  [部署] 部署开始，目标包 " + allTargets.size() + " 个"
                            + (mainTargets.size() > 1 ? "，其中 " + mainTargets.size() + " 个同名主目标" : ""));
                    for (FtpTargetSelection t : allTargets) {
                        logCallback.accept("INFO  [部署] 目标 " + t.getRelativePath());
                    }

                    // ── Phase 0.5: 预构建每个主目标对应的本地上传文件 ──
                    //   必须在加锁之前完成：加锁会把远端原文件 rename 为锁名，
                    //   此时 StagingPackageBuilder.build() 从原路径下载会 550 File not found。
                    //   整包 + JAR 不下载原包无此问题，但为保持分支统一都在此阶段解决。
                    com.flux.deploy.plugin.model.DeployMode depMode = pluginConfig.getMode();
                    boolean isFullMode = depMode == com.flux.deploy.plugin.model.DeployMode.FULL;
                    boolean artifactIsWar = pluginConfig.getArtifactFileName() != null
                            && pluginConfig.getArtifactFileName().toLowerCase().endsWith(".war");
                    // 使用 LinkedHashMap 保持主目标顺序
                    java.util.Map<FtpTargetSelection, Path> preparedPerMain = new java.util.LinkedHashMap<>();
                    if (!mainTargets.isEmpty()) {
                        long prepStart = System.currentTimeMillis();
                        logCallback.accept("INFO  [准备] 准备开始，主目标 " + mainTargets.size() + " 个");
                        for (FtpTargetSelection mt : mainTargets) {
                            try {
                                Path local = prepareLocalFileForMainTarget(
                                        pluginConfig, mt, isFullMode, artifactIsWar,
                                        ftpHost, ftpPort, ftpUsername, ftpPassword, logCallback);
                                if (local == null || !Files.exists(local)) {
                                    throw new java.io.IOException("产物为空或不存在");
                                }
                                preparedPerMain.put(mt, local);
                                logCallback.accept("INFO  [准备] " + mt.getRelativePath()
                                        + " 完成，产物 " + local.getFileName()
                                        + "，大小 " + formatSize(Files.size(local)));
                            } catch (Exception ex) {
                                logCallback.accept("ERROR [准备] " + mt.getRelativePath()
                                        + " 产物构建失败：" + ex.getMessage());
                                logFailureSummary(logCallback, "产物构建失败，未执行任何远端变更");
                                onComplete.accept(null);
                                return;
                            }
                        }
                        logCallback.accept("INFO  [准备] 准备完成，阶段耗时 "
                                + formatElapsed(System.currentTimeMillis() - prepStart));
                    }

                    // ── Phase 1: 备份（可选）──
                    String backupDir = null;
                    // 多线程并行备份后会从多个 worker 线程 add；用 synchronizedList 保证线程安全。
                    // 串行路径下行为与 ArrayList 一致；额外的 monitor 开销可忽略。
                    List<String[]> updatedPackages =
                            java.util.Collections.synchronizedList(new ArrayList<>());

                    com.flux.deploy.plugin.model.BackupConflictStrategy strategy =
                            pluginConfig.getBackupConflictStrategy();
                    // 借用标记：USE_EXISTING 下为 true，回滚时只恢复文件不清理老备份
                    final boolean backupBorrowed = !pluginConfig.isSkipBackup()
                            && strategy == com.flux.deploy.plugin.model.BackupConflictStrategy.USE_EXISTING;

                    if (!pluginConfig.isSkipBackup()) {
                        if (strategy == com.flux.deploy.plugin.model.BackupConflictStrategy.USE_EXISTING) {
                            // 使用已有备份：不做下载/上传，直接把已有备份路径登记为回滚源
                            logCallback.accept("INFO  [备份] 沿用已有备份作为回滚源，本次跳过备份步骤");
                            backupDir = computeExistingBackupDir(pluginConfig, allTargets);
                            for (FtpTargetSelection t : allTargets) {
                                String rp = t.getRemoteDir() + t.getRelativePath();
                                String bp = backupDir + backupSubDirFor(t) + t.getTargetName();
                                updatedPackages.add(new String[]{rp, bp});
                                logCallback.accept("INFO  [备份] 回滚源 " + bp);
                            }
                        } else {
                            String dirSuffix = strategy
                                    == com.flux.deploy.plugin.model.BackupConflictStrategy.NEW_DIR
                                    ? "，使用新增目录" : "";
                            long bkStart = System.currentTimeMillis();
                            logCallback.accept("INFO  [备份] 备份开始，目标 " + allTargets.size()
                                    + " 个" + dirSuffix);
                            try {
                                // 备份并发度写死为 FTP_PARALLELISM（=3）。详见常量注释。
                                backupDir = preBackupAll(pluginConfig, allTargets,
                                        FTP_PARALLELISM,
                                        userConfig.getBackupMaxRetries(),
                                        ftpHost, ftpPort, ftpUsername, ftpPassword, logCallback);
                                logCallback.accept("INFO  [备份] 备份完成，成功 " + allTargets.size()
                                        + " 个，阶段耗时 "
                                        + formatElapsed(System.currentTimeMillis() - bkStart));
                            } catch (Exception e) {
                                logCallback.accept("ERROR [备份] 备份失败：" + e.getMessage());
                                logFailureSummary(logCallback, "备份失败，未执行后续步骤");
                                onComplete.accept(null);
                                return;
                            }

                            // 注册回滚列表：备份路径带 relativeDir 子目录，与 preBackupAll 一致
                            for (FtpTargetSelection t : allTargets) {
                                String rp = t.getRemoteDir() + t.getRelativePath();
                                String bp = backupDir + backupSubDirFor(t) + t.getTargetName();
                                updatedPackages.add(new String[]{rp, bp});
                            }
                        }
                    } else {
                        logCallback.accept("WARN  [备份] 跳过备份（用户选择不备份，失败后无法自动回滚）");
                        // 跳过备份时也记录目标包路径（用于日志总结，无备份路径）
                        for (FtpTargetSelection t : allTargets) {
                            String rp = t.getRemoteDir() + t.getRelativePath();
                            updatedPackages.add(new String[]{rp, null});
                        }
                    }

                    // ═══ localOnly 模式：仅保存包到本地，不上传 FTP ═══
                    if (pluginConfig.isLocalOnly()) {
                        logCallback.accept("\n━━ 保存最终包到本地 ━━");
                        try {
                            Path outputDir = Path.of(pluginConfig.getModulePath(), "target", "flux-deploy-output");
                            Files.createDirectories(outputDir);
                            // 清空旧文件
                            try (var stream = Files.list(outputDir)) {
                                stream.forEach(f -> { try { Files.deleteIfExists(f); } catch (Exception ignored) {} });
                            }

                            List<String[]> savedFiles = new ArrayList<>(); // [localPath, remotePath]

                            // 保存主目标：用 Phase 0.5 准备好的 staging/aligned 包，与 FTP 上传保持一致。
                            // 不能直接用 config.getLocalFiles()——那是 maven 全量产物，会绕过增量补丁，
                            // 导致输出包变成"远程包内所有 entry 全替换为本次编译版本（时间戳全部刷新）"，
                            // 与"增量更新仅替换变更 class"语义不符。
                            for (java.util.Map.Entry<FtpTargetSelection, Path> ent : preparedPerMain.entrySet()) {
                                FtpTargetSelection mt = ent.getKey();
                                Path src = ent.getValue();
                                String targetName = mt.getTargetName();
                                Path dest = outputDir.resolve(targetName);
                                // 多主目标同名时按 relativePath 派生子目录避免覆盖
                                if (preparedPerMain.size() > 1) {
                                    String subDir = backupSubDirFor(mt);
                                    if (!subDir.isEmpty()) {
                                        dest = outputDir.resolve(subDir).resolve(targetName);
                                        Files.createDirectories(dest.getParent());
                                    }
                                }
                                Files.copy(src, dest, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                                String remotePath = mt.getRemoteDir() + mt.getRelativePath();
                                savedFiles.add(new String[]{dest.toString(), remotePath});
                                String label = preparedPerMain.size() > 1 ? mt.getRelativePath() : targetName;
                                logCallback.accept("INFO  [本地] " + label + "，大小 " + Files.size(dest) / 1024 + " KB");
                            }

                            // 保存 WAR 嵌入包
                            if (hasEmbedTargets) {
                                String originalArtifact = pluginConfig.getArtifactFileName();
                                // 仅作为 FULL 模式下的"嵌入源 JAR"；增量模式会在循环内基于远程 WAR 内嵌 JAR 重新构建
                                // 不再在此处计算 artifactPrefix —— 每个 war 各自调用 resolveEmbedTargetJarName
                                // 解析自己 lib 内的完整 jar 文件名，确保 extract / embedJar 用同一个完整名
                                Path freshLocalJar = Path.of(pluginConfig.getModulePath(), "target", originalArtifact);
                                com.flux.deploy.plugin.model.DeployMode embedMode = pluginConfig.getMode();
                                boolean isIncrementalEmbed = embedMode != null
                                        && embedMode != com.flux.deploy.plugin.model.DeployMode.FULL;

                                for (FtpTargetSelection embedTarget : embedTargets) {
                                    logCallback.accept("  嵌入: " + embedTarget.getTargetName() + "...");
                                    String remoteDir = embedTarget.getRemoteDir();
                                    String warRelPath = embedTarget.getRelativePath();
                                    String remotePath = remoteDir + warRelPath;

                                    // 下载远程 WAR → 嵌入 JAR → 保存到输出目录
                                    Path tempDir = Files.createTempDirectory("embed-local-");
                                    try {
                                        Path downloadedWar = tempDir.resolve(embedTarget.getTargetName());
                                        try (com.flux.deploy.ftp.FtpSession session =
                                                new com.flux.deploy.ftp.FtpSession(ftpHost, ftpPort)) {
                                            session.connect(ftpUsername, ftpPassword);
                                            new com.flux.deploy.ftp.FtpOperations(session).download(remotePath, downloadedWar);
                                        }

                                        // 增量/自动检索模式：以远程 WAR 内嵌 JAR 为基准做 patch，仅替换变更 class，
                                        // 保持其余条目原字节与时间戳；与 executeWarEmbed 中 embed loop 同语义
                                        Path jarToEmbed = freshLocalJar;
                                        StagingPackageBuilder.PatchManifest perWarManifest = null;
                                        // 先在本 war 内精确解析目标 lib 文件名（按完整产物名校验版本一致），
                                        // 后续 extract 与 embedJar 必须用同一个完整文件名，杜绝错位
                                        String targetJarName = resolveEmbedTargetJarName(downloadedWar, originalArtifact);
                                        if (isIncrementalEmbed) {
                                            Path extractedJar = tempDir.resolve("extracted-" + targetJarName);
                                            extractEmbeddedJar(downloadedWar, targetJarName, extractedJar);
                                            if (Files.exists(extractedJar) && Files.size(extractedJar) > 0) {
                                                List<String> changedFiles = pluginConfig.getChangedFiles();
                                                if (changedFiles != null && !changedFiles.isEmpty()) {
                                                    StagingPackageBuilder patcher = new StagingPackageBuilder(
                                                            pluginConfig.getModulePath(),
                                                            originalArtifact,
                                                            changedFiles,
                                                            logCallback);
                                                    StagingPackageBuilder.PatchOutcome outcome =
                                                            patcher.patchExistingJar(extractedJar, tempDir);
                                                    if (outcome != null && Files.exists(outcome.getPatchedJar())) {
                                                        jarToEmbed = outcome.getPatchedJar();
                                                        perWarManifest = outcome.getManifest();
                                                    }
                                                }
                                            }
                                        }

                                        Path embeddedWar = tempDir.resolve("embed-" + embedTarget.getTargetName());
                                        com.flux.deploy.util.WarEmbedUtil.embedJar(downloadedWar, jarToEmbed, targetJarName, embeddedWar);
                                        Path dest = outputDir.resolve(embedTarget.getTargetName());
                                        Files.copy(embeddedWar, dest, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                                        savedFiles.add(new String[]{dest.toString(), remotePath});
                                        logCallback.accept("INFO  [本地] " + embedTarget.getTargetName() + "，大小 " + Files.size(dest) / 1024 / 1024 + " MB");
                                        // 输出本 war 包内变更明细（操作人员可立即对账）
                                        logPerWarPatchManifest(logCallback, embedTarget.getTargetName(), perWarManifest);
                                    } finally {
                                        // 清理临时目录
                                        try { Files.walkFileTree(tempDir, new java.nio.file.SimpleFileVisitor<>() {
                                            @Override public java.nio.file.FileVisitResult visitFile(Path f, java.nio.file.attribute.BasicFileAttributes a) throws java.io.IOException { Files.delete(f); return java.nio.file.FileVisitResult.CONTINUE; }
                                            @Override public java.nio.file.FileVisitResult postVisitDirectory(Path d, java.io.IOException e) throws java.io.IOException { Files.delete(d); return java.nio.file.FileVisitResult.CONTINUE; }
                                        }); } catch (Exception ignored) {}
                                    }
                                }
                            }

                            // 总结
                            logCallback.accept("\n╔══════════════════════════════╗");
                            logCallback.accept("║          包准备完成           ║");
                            logCallback.accept("╚══════════════════════════════╝");
                            logCallback.accept("输出目录: " + outputDir);
                            for (String[] sf : savedFiles) {
                                logCallback.accept(com.flux.deploy.plugin.toolwindow.LogSectionPanel.RAW_LINE_MARK
                                        + "          " + Path.of(sf[0]).getFileName());
                            }
                            logCallback.accept("\n手动上传目标：");
                            for (String[] sf : savedFiles) {
                                logCallback.accept(com.flux.deploy.plugin.toolwindow.LogSectionPanel.RAW_LINE_MARK
                                        + "          " + Path.of(sf[0]).getFileName() + " → " + sf[1]);
                            }
                            logCallback.accept("\n备份目录（FTP）: " + backupDir);

                            DeployResult localResult = new DeployResult();
                            localResult.markSuccess();
                            onComplete.accept(localResult);
                        } catch (Exception e) {
                            logCallback.accept("ERROR [本地] 保存失败：" + e.getMessage());
                            onComplete.accept(null);
                        }
                        return; // localOnly 模式到此结束
                    }

                    // ── Stage 0 (IDE)：弹窗确认残留锁处理 ──
                    // 在 Phase 2 加锁之前先排查残留锁；若存在，让用户在 IDE 弹窗中勾选要清理的锁。
                    // 用户取消或仍有残留 → 中止部署；用户清理完成 → 后续每个 targetConfig 设
                    // EXTERNAL_RESOLVED，使核心 pipeline 内部 Stage 0 直接跳过（与 IDE 已处理一致）。
                    java.util.List<ResidualLockDiagnosis> stage0All = new java.util.ArrayList<>();
                    java.util.List<String[]> stage0Targets = new java.util.ArrayList<>();
                    for (FtpTargetSelection t : allTargets) {
                        String rp = t.getRemoteDir() + t.getRelativePath();
                        int lastSlash = rp.lastIndexOf('/');
                        String dir = lastSlash > 0 ? rp.substring(0, lastSlash + 1) : t.getRemoteDir();
                        if (!dir.endsWith("/")) dir = dir + "/";
                        stage0Targets.add(new String[]{dir, t.getTargetName()});
                    }
                    if (!stage0Targets.isEmpty()) {
                        try (FtpSession s0 = new FtpSession(ftpHost, ftpPort)) {
                            s0.connect(ftpUsername, ftpPassword);
                            FtpOperations s0Ops = new FtpOperations(s0);
                            FtpLock s0Lock = new FtpLock(s0Ops);
                            ResidualLockResolver resolver = new ResidualLockResolver(
                                    ResidualLockResolver.wrap(s0Ops, s0Lock), pluginConfig.getOperator());
                            for (String[] t : stage0Targets) {
                                stage0All.addAll(resolver.diagnose(t[0], t[1]));
                            }
                            if (!stage0All.isEmpty()) {
                                java.util.List<ResidualLockDiagnosis> finalAll = stage0All;
                                boolean[] proceed = {false};
                                java.util.List<ResidualLockDiagnosis> selected = new java.util.ArrayList<>();
                                try {
                                    SwingUtilities.invokeAndWait(() -> {
                                        ResidualLockResolveDialog dialog =
                                                new ResidualLockResolveDialog(project, finalAll);
                                        if (dialog.showAndGet()) {
                                            selected.addAll(dialog.getSelected());
                                            proceed[0] = true;
                                        }
                                    });
                                } catch (Exception swingEx) {
                                    logCallback.accept("INFO  [加锁] 对话框异常: " + swingEx.getMessage());
                                    logFailureSummary(logCallback, "Stage 0 对话框异常");
                                    onComplete.accept(null);
                                    return;
                                }
                                if (!proceed[0]) {
                                    logCallback.accept("INFO  [加锁] 用户取消");
                                    logFailureSummary(logCallback, "用户取消 Stage 0 残留锁处理");
                                    onComplete.accept(null);
                                    return;
                                }
                                for (ResidualLockDiagnosis d : selected) {
                                    try {
                                        resolver.apply(d);
                                        logCallback.accept("INFO  [加锁] 已清理: " + d.getLockFileName());
                                    } catch (java.io.IOException ie) {
                                        logCallback.accept("INFO  [加锁] 清理失败: " + ie.getMessage());
                                        logFailureSummary(logCallback, "Stage 0 清理失败: " + ie.getMessage());
                                        onComplete.accept(null);
                                        return;
                                    }
                                }
                                // 复检：仍有未处理的残留锁则中止
                                boolean stillHasResidual = false;
                                for (String[] t : stage0Targets) {
                                    if (!resolver.diagnose(t[0], t[1]).isEmpty()) {
                                        stillHasResidual = true;
                                        break;
                                    }
                                }
                                if (stillHasResidual) {
                                    logCallback.accept("INFO  [加锁] 仍有未处理的残留锁，部署中止");
                                    logFailureSummary(logCallback, "Stage 0 仍有未处理的残留锁");
                                    onComplete.accept(null);
                                    return;
                                }
                            }
                        } catch (java.io.IOException ftpEx) {
                            logCallback.accept("INFO  [加锁] FTP 错误: " + ftpEx.getMessage());
                            logFailureSummary(logCallback, "Stage 0 FTP 错误: " + ftpEx.getMessage());
                            onComplete.accept(null);
                            return;
                        }
                    }

                    // ── Phase 2: 加锁 ──
                    // 多目标时打 header + summary，单目标退化为单行 [加锁] X 已加锁，避免日志噪音
                    boolean multiTargets = allTargets.size() > 1;
                    long lockStart = System.currentTimeMillis();
                    if (multiTargets) {
                        logCallback.accept("INFO  [加锁] 加锁开始");
                    }
                    List<String[]> lockedPackages = new ArrayList<>();
                    try {
                        preLockAll(allTargets, pluginConfig.getOperator(),
                                ftpHost, ftpPort, ftpUsername, ftpPassword,
                                logCallback, lockedPackages);
                        if (multiTargets) {
                            logCallback.accept("INFO  [加锁] 加锁完成，阶段耗时 "
                                    + formatElapsed(System.currentTimeMillis() - lockStart));
                        }
                    } catch (Exception e) {
                        logCallback.accept("ERROR [加锁] 加锁失败：" + e.getMessage());
                        // 关键：先从备份恢复文件，再删锁文件
                        if (backupDir != null && !updatedPackages.isEmpty()) {
                            rollbackAll(backupDir, updatedPackages,
                                    ftpHost, ftpPort, ftpUsername, ftpPassword, logCallback, backupBorrowed);
                        } else {
                            logCallback.accept("WARN  [加锁] 无备份，无法自动回滚");
                        }
                        preUnlockAll(lockedPackages, ftpHost, ftpPort, ftpUsername, ftpPassword, logCallback);
                        logFailureSummary(logCallback, "加锁失败（可能目标包已被他人锁定）");
                        onComplete.accept(null);
                        return;
                    }

                    // ── Phase 3: 上传主目标（使用预构建好的本地文件，每个目标独立上传）──
                    DeployResult result = new DeployResult();
                    if (!mainTargets.isEmpty()) {
                        String tag = isFullMode ? "整包更新" : "打补丁";
                        int mi = 0;
                        for (java.util.Map.Entry<FtpTargetSelection, Path> entry : preparedPerMain.entrySet()) {
                            mi++;
                            FtpTargetSelection mt = entry.getKey();
                            Path localForThisTarget = entry.getValue();
                            logCallback.accept("INFO  [上传] " + mi + "/" + preparedPerMain.size()
                                    + " 主目标 " + mt.getRelativePath() + "，模式 " + tag);
                            try {
                                logCallback.accept("INFO  [上传] 源文件 " + localForThisTarget
                                        + "，大小 " + formatSize(Files.size(localForThisTarget)));
                            } catch (Exception ignored) {}

                            DeployConfig targetConfig = buildDeployConfig(
                                    pluginConfig, ftpHost, ftpPort, ftpUsername, ftpPassword, null);
                            targetConfig.setRemoteDir(mt.getRemoteDir());
                            targetConfig.setTargetNames(List.of(mt.getTargetName()));
                            targetConfig.setTargetRelativePaths(List.of(mt.getRelativePath()));
                            targetConfig.setLocalFiles(List.of(localForThisTarget));
                            targetConfig.setSkipBackup(true);
                            targetConfig.setSkipNote(true);
                            targetConfig.setSkipLock(true);
                            // IDE 已在 Phase 0 处理过残留锁，告知 pipeline 直接跳过其内部 Stage 0
                            targetConfig.setResidualLockPolicy(DeployConfig.ResidualLockPolicy.EXTERNAL_RESOLVED);

                            applyCancellationToken(targetConfig);
                            DeployPipeline pipeline = new DeployPipeline(targetConfig);
                            DeployResult thisResult = pipeline.execute();

                            if (!thisResult.isSuccess()) {
                                boolean userStop = currentCancelMode != CancelMode.NONE;
                                logCallback.accept(userStop
                                        ? "WARN  [上传] 用户请求停止，按所选模式处理已成功的包"
                                        : "ERROR [上传] " + mt.getRelativePath() + " 上传失败，开始处理已更新的包");
                                abortPartial(backupDir, updatedPackages, backupBorrowed, allTargets,
                                        ftpHost, ftpPort, ftpUsername, ftpPassword, logCallback);
                                preUnlockAll(lockedPackages, ftpHost, ftpPort, ftpUsername, ftpPassword, logCallback);
                                if (userStop) {
                                    logStopSummary(logCallback, mt.getRelativePath());
                                } else {
                                    logFailureSummary(logCallback, "主目标上传失败 - " + mt.getRelativePath());
                                }
                                onComplete.accept(thisResult);
                                return;
                            }
                            logCallback.accept("INFO  [上传] " + mt.getRelativePath() + " 上传成功");
                            // 登记到实时成功列表（供 UI 弹"如何收尾"对话框展示）
                            recordSucceededUpload(mt);
                            result = thisResult;
                        }
                    } else if (hasEmbedTargets) {
                        // 没有独立主目标，只走嵌入；嵌入阶段自己有 header，这里不再额外打"无独立目标包"
                        result.markSuccess();
                    }

                    // ── Phase 4: WAR 嵌入 ──
                    int embedSuccess = 0;
                    // 嵌入并行路径的 outcomes（仅并行路径填充；串行路径下保持 null）。
                    // 用于 ISOLATED 模式下"部分成功"终态日志的失败列表与建议渲染。
                    java.util.Map<String, com.flux.deploy.parallel.TargetOutcome> embedOutcomes = null;
                    // 重试后成功的目标 key 集合（终态日志区分"首次成功"与"重试后成功"）
                    java.util.Set<String> retrySucceededKeys = java.util.Collections.emptySet();

                    long embedStart = System.currentTimeMillis();
                    if (hasEmbedTargets) {
                        logCallback.accept("INFO  [嵌入] 嵌入开始，目标 " + embedTargets.size() + " 个");

                        String originalArtifact = pluginConfig.getArtifactFileName();

                        // 防御：WAR 源不应有嵌入目标（UI 已过滤，代码再兜底一次）
                        if (originalArtifact != null
                                && originalArtifact.toLowerCase().endsWith(".war")) {
                            logCallback.accept("WARN  [嵌入] 源产物为 WAR，不应触发嵌入流程，已跳过 "
                                    + embedTargets.size() + " 个嵌入目标");
                            preUnlockAll(lockedPackages, ftpHost, ftpPort, ftpUsername, ftpPassword, logCallback);
                            // 版本记录仍按主目标流程处理，跳过嵌入即可
                            hasEmbedTargets = false;
                        }
                    }
                    if (hasEmbedTargets) {
                        String originalArtifact = pluginConfig.getArtifactFileName();
                        // 不再在此处算 artifactPrefix —— 每个 war 进入嵌入流程时各自调用
                        // resolveEmbedTargetJarName(downloadedWar, originalArtifact) 解析自己 lib 内
                        // 的完整 jar 文件名，让 extract / embedJar 用同一个完整名（杜绝错位）

                        Path localJar = Path.of(pluginConfig.getModulePath(), "target", originalArtifact);
                        if (!Files.exists(localJar) && config.getLocalFiles() != null && !config.getLocalFiles().isEmpty()) {
                            localJar = config.getLocalFiles().get(0);
                        }
                        // localJar 仅在"必走整包兜底"场景下才被消费：
                        //   - FULL 模式：jarToEmbed 永远 = localJar；
                        //   - INCREMENTAL 但 changedFiles 为空：canPatch=false，executeWarEmbed 内部
                        //     回退到 localJar。
                        // INCREMENTAL+changedFiles 非空（含纯静态文件）走 patchExistingJar，源 artifact
                        // 不消费——其中静态资源直接从源文件读取，无需 mvn package 也能成功。
                        boolean changedFilesEmpty = pluginConfig.getChangedFiles() == null
                                || pluginConfig.getChangedFiles().isEmpty();
                        boolean mustHaveLocalJar = pluginConfig.getMode() == DeployMode.FULL
                                || changedFilesEmpty;
                        if (mustHaveLocalJar && !Files.exists(localJar)) {
                            logCallback.accept("ERROR [嵌入] 本地 JAR 文件不存在 " + localJar);
                            preUnlockAll(lockedPackages, ftpHost, ftpPort, ftpUsername, ftpPassword, logCallback);
                            logFailureSummary(logCallback, "嵌入阶段本地 JAR 不存在");
                            onComplete.accept(null);
                            return;
                        }
                        if (Files.exists(localJar)) {
                            try {
                                logCallback.accept("INFO  [嵌入] 源 JAR " + localJar
                                        + "，大小 " + formatSize(Files.size(localJar)));
                            } catch (Exception ignored) {}
                        }

                        // lockedPackages 顺序与 allTargets 一致：[main0, main1, ..., embed0, embed1, ...]
                        // embed 部分从 mainTargets.size() 开始
                        int mainOffset = mainTargets.size();

                        // 双路径分流：单文件 → 走串行循环；多目标 → 走 PipelineExecutor 流水线。
                        // 流水线 embed 池规模由 PipelineExecutor 内部固定为 1（本地补丁是秒级，
                        // 无需并行），但 download / upload 池仍可跨包重叠，对多目标仍有可观提速。
                        boolean shouldParallelizeEmbed = embedTargets.size() > 1;

                        if (!shouldParallelizeEmbed) {
                            boolean multiEmbed = embedTargets.size() > 1;
                            // 串行路径同样接入 FailureStrategy，与并行路径行为对齐：
                            //   - ISOLATED：单包失败仅标记该包，继续处理其他目标；
                            //     该包的锁会在最后 preUnlockAll 时按"原文件缺失 → restoreLock"逻辑
                            //     自动回滚为原始 WAR，与并行路径单包回滚等价
                            //   - ROLLBACK_ALL / KEEP_SUCCEEDED：保持原有"中止整批"行为
                            com.flux.deploy.config.FailureStrategy embedStrategy =
                                    userConfig.getFailureStrategy();
                            // 串行 ISOLATED 模式下收集失败的 embedTarget relativePath，
                            // 用于终态判定 partialSuccess + 总结输出"⚠ 部分成功"
                            java.util.Set<String> serialFailedKeys = new java.util.LinkedHashSet<>();
                            // 失败原因记录，用于总结时打印每个失败包的具体错误
                            java.util.Map<String, String> serialFailReasons = new java.util.LinkedHashMap<>();

                            for (int ei = 0; ei < embedTargets.size(); ei++) {
                                FtpTargetSelection embedTarget = embedTargets.get(ei);
                                // 多包才打 (N/M) 进度行 + 完成回执，单包退化为该阶段 header 已包含的信息
                                if (multiEmbed) {
                                    logCallback.accept("INFO  [嵌入] " + (ei + 1) + "/" + embedTargets.size()
                                            + " " + embedTarget.getTargetName());
                                }
                                try {
                                    String[] lockInfo = lockedPackages.get(ei + mainOffset);
                                    executeWarEmbed(pluginConfig, embedTarget,
                                            localJar, originalArtifact,
                                            lockInfo[0], lockInfo[1],
                                            ftpHost, ftpPort, ftpUsername, ftpPassword,
                                            logCallback);

                                    embedSuccess++;
                                    if (multiEmbed) {
                                        logCallback.accept("INFO  [嵌入] " + embedTarget.getTargetName() + " 完成");
                                    }
                                    // 登记到实时成功列表（供 UI 弹"如何收尾"对话框展示）
                                    recordSucceededUpload(embedTarget);
                                } catch (Exception e) {
                                    boolean userStop = currentCancelMode != CancelMode.NONE
                                            || e instanceof CancellationToken.CancellationException;

                                    // 决策抽到 EmbedFailureDecision，让串行 / 并行走同一套规则；
                                    // 测试由 EmbedFailureDecisionTest 覆盖
                                    com.flux.deploy.config.EmbedFailureDecision decision =
                                            com.flux.deploy.config.EmbedFailureDecision.decide(embedStrategy, userStop);
                                    if (decision == com.flux.deploy.config.EmbedFailureDecision.CONTINUE_ISOLATED) {
                                        serialFailedKeys.add(embedTarget.getRelativePath());
                                        serialFailReasons.put(embedTarget.getRelativePath(),
                                                e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
                                        logCallback.accept("ERROR [嵌入] " + embedTarget.getTargetName()
                                                + " 失败：" + e.getMessage()
                                                + "（ISOLATED：跳过此包，继续处理其他目标）");
                                        // 明确给操作员一行"远端不会被改坏"的语义日志：
                                        // executeWarEmbed 失败 ⇒ 没有上传 ⇒ Phase 4 preUnlockAll 会
                                        // 走 restoreLock 把锁文件 rename 回原名，远端 WAR 与本批次开始前一致。
                                        logCallback.accept("INFO  [回滚] " + embedTarget.getTargetName()
                                                + " 嵌入未完成，远端将保持原 WAR（解锁阶段恢复）");
                                        continue;
                                    }

                                    // 用户停止 / ROLLBACK_ALL / KEEP_SUCCEEDED → 中止整批
                                    logCallback.accept(userStop
                                            ? "WARN  [嵌入] 用户请求停止，按所选模式处理已成功的包"
                                            : "ERROR [嵌入] " + embedTarget.getTargetName() + " 失败：" + e.getMessage());
                                    abortPartial(backupDir, updatedPackages, backupBorrowed, allTargets,
                                            ftpHost, ftpPort, ftpUsername, ftpPassword, logCallback);
                                    preUnlockAll(lockedPackages, ftpHost, ftpPort, ftpUsername, ftpPassword, logCallback);
                                    if (userStop) {
                                        logStopSummary(logCallback, embedTarget.getTargetName());
                                    } else {
                                        logFailureSummary(logCallback, "WAR 嵌入失败 - " + embedTarget.getTargetName());
                                    }
                                    onComplete.accept(null);
                                    return;
                                }
                            }

                            // 串行 ISOLATED 模式：把失败明细汇总成最小 outcome map，
                            // 与并行路径产物结构对齐，让后续 partialSuccess 判定 + 总结输出统一走同一套代码
                            if (!serialFailedKeys.isEmpty()) {
                                java.util.LinkedHashMap<String, com.flux.deploy.parallel.TargetOutcome> mergedOutcomes =
                                        new java.util.LinkedHashMap<>();
                                if (embedOutcomes != null) {
                                    mergedOutcomes.putAll(embedOutcomes);
                                }
                                for (String key : serialFailedKeys) {
                                    String reason = serialFailReasons.getOrDefault(key, "");
                                    mergedOutcomes.put(key, com.flux.deploy.parallel.TargetOutcome.failed(
                                            key, new RuntimeException(reason),
                                            com.flux.deploy.ftp.FtpErrorClassifier.classify(new RuntimeException(reason)),
                                            ""));
                                }
                                embedOutcomes = mergedOutcomes;
                            }
                        } else {
                            // 并行路径：用 PipelineExecutor 跑流水线（download / upload 两个 FTP 池
                            // 跨包重叠，本地补丁串行）。详见 FTP_PARALLELISM 常量注释。
                            EmbedParallelOutcome res = runEmbedPhaseParallel(
                                    pluginConfig, embedTargets, localJar, originalArtifact,
                                    lockedPackages, mainOffset, updatedPackages,
                                    userConfig.getFailureStrategy(),
                                    ftpHost, ftpPort, ftpUsername, ftpPassword, logCallback);
                            embedSuccess += res.successCount;
                            embedOutcomes = res.outcomes;

                            // ISOLATED 模式：失败的已单包回滚，其他成功的保留 → 直接进入解锁+版本记录
                            // ROLLBACK_ALL/KEEP_SUCCEEDED 模式：失败 → 走 abortPartial + 失败结束
                            if (res.shouldAbort) {
                                boolean userStop = currentCancelMode != CancelMode.NONE;
                                logCallback.accept(userStop
                                        ? "WARN  [嵌入] 用户请求停止，按所选模式处理已成功的包"
                                        : "ERROR [嵌入] 嵌入阶段失败，" + res.failedCount + " 个包失败，按 "
                                                + userConfig.getFailureStrategy() + " 策略处理");
                                abortPartial(backupDir, updatedPackages, backupBorrowed, allTargets,
                                        ftpHost, ftpPort, ftpUsername, ftpPassword, logCallback);
                                preUnlockAll(lockedPackages, ftpHost, ftpPort, ftpUsername, ftpPassword, logCallback);
                                if (userStop) {
                                    logStopSummary(logCallback, "嵌入阶段");
                                } else {
                                    logFailureSummary(logCallback,
                                            "WAR 嵌入失败 - " + res.failedCount + " 个包未成功");
                                }
                                onComplete.accept(null);
                                return;
                            }

                            // ISOLATED 模式 + 有失败包 + 配置了重试 → 串行重试
                            // 时机：在主流程嵌入完成 → 解锁之前。失败包的锁文件仍在远端，
                            //       executeWarEmbed 可正常工作；重试成功后该包的锁文件被替换为新 WAR；
                            //       重试仍失败则保留锁文件，由后续解锁阶段从锁文件恢复（兜底保数据）。
                            if (res.failedCount > 0
                                    && userConfig.getEmbedMaxRetries() > 0
                                    && currentCancelMode == CancelMode.NONE) {
                                EmbedRetryOutcome retryRes = retryFailedEmbedSerially(
                                        embedTargets, embedOutcomes, pluginConfig,
                                        localJar, originalArtifact,
                                        lockedPackages, mainOffset,
                                        userConfig.getEmbedMaxRetries(),
                                        ftpHost, ftpPort, ftpUsername, ftpPassword,
                                        logCallback);
                                embedSuccess += retryRes.retrySucceededKeys.size();
                                // 把"重试成功的 key 集合"挂到外层变量，供终态日志渲染
                                retrySucceededKeys = retryRes.retrySucceededKeys;
                            }
                        }
                    }

                    // 嵌入阶段结束日志（仅在确实进入嵌入阶段时打印）
                    if (hasEmbedTargets) {
                        logCallback.accept("INFO  [嵌入] 嵌入完成，成功 " + embedSuccess + " 个，阶段耗时 "
                                + formatElapsed(System.currentTimeMillis() - embedStart));
                    }

                    // ── 解锁 ──
                    // 同加锁：多目标才打 header + summary
                    boolean multiUnlock = lockedPackages.size() > 1;
                    long unlockStart = System.currentTimeMillis();
                    if (multiUnlock) {
                        logCallback.accept("INFO  [解锁] 解锁开始");
                    }
                    java.util.Set<String> restoreFailedLockNames = preUnlockAll(
                            lockedPackages, ftpHost, ftpPort, ftpUsername, ftpPassword, logCallback);
                    if (multiUnlock) {
                        logCallback.accept("INFO  [解锁] 解锁完成，阶段耗时 "
                                + formatElapsed(System.currentTimeMillis() - unlockStart));
                    }
                    // restoreLock 失败 → 远端只有孤悬锁文件、没有原 WAR：把对应 target 的 outcome
                    // 升级为 ROLLBACK_FAILED，让总结里能区分"⚠ 已恢复" / "❌ 需人工干预"。
                    // lockedPackages 与 allTargets 同序（preLockAll 顺序遍历 allTargets 注册）。
                    if (!restoreFailedLockNames.isEmpty() && embedOutcomes != null) {
                        embedOutcomes = upgradeRestoreFailedOutcomes(
                                embedOutcomes, restoreFailedLockNames, lockedPackages, allTargets);
                    }

                    // ── Phase 5: 版本记录 ──
                    // 嵌入全部失败时，note 阶段每个包都会被 skippedNoteKeys 跳过 → 空跑，
                    // 仅产生"说明更新开始/跳过 X/说明更新完成"三行噪音，直接整段跳过。
                    boolean allEmbedFailed = embedOutcomes != null && !embedOutcomes.isEmpty()
                            && collectFailedRelativePaths(embedOutcomes).size() == embedOutcomes.size();
                    if (pluginConfig.isUpdateNote() && !allEmbedFailed) {
                        long noteStart = System.currentTimeMillis();
                        logCallback.accept("INFO  [说明] 说明更新开始");
                        // ISOLATED 部分失败时，跳过失败包的 note 追加：失败包远端已被
                        // preUnlockAll 的 restoreLock 还原为原 WAR，写 note 会产生"取包/传包"幻象。
                        java.util.Set<String> skippedNoteKeys = collectFailedRelativePaths(embedOutcomes);
                        try {
                            updateNoteForAll(pluginConfig, allTargets, skippedNoteKeys,
                                    ftpHost, ftpPort, ftpUsername, ftpPassword, logCallback);
                            logCallback.accept("INFO  [说明] 说明更新完成，阶段耗时 "
                                    + formatElapsed(System.currentTimeMillis() - noteStart));
                        } catch (Exception e) {
                            logCallback.accept("WARN  [说明] 说明更新失败：" + e.getMessage() + "（不影响已部署的包）");
                        }
                    }

                    // ── 总结 ──
                    // 与失败 / 停止两种结束态保持框体对称（都是 ╔═╗ + emoji + 短文案）：
                    //   ❌ 部署失败 / ■ 部署已停止 / ✅ 部署完成 / ⚠ 部分成功
                    // 框下分三段输出："已更新 N 个包：" + 路径列表 + 备份目录。
                    //
                    // 终态判定（仅 ISOLATED 模式 + 嵌入阶段并行 + 有失败时才会进入"部分成功"分支；
                    // ROLLBACK_ALL/KEEP_SUCCEEDED/串行路径任一失败都已经在前面 abortPartial → return 走完）：
                    //   - embedOutcomes != null 且有 FAILED/ROLLBACK_FAILED → ⚠ 部分成功
                    //   - 否则 → ✅ 部署完成（行为与改造前完全一致）
                    int isolatedFailedCount = 0;
                    int isolatedCriticalCount = 0;
                    if (embedOutcomes != null) {
                        for (com.flux.deploy.parallel.TargetOutcome o : embedOutcomes.values()) {
                            if (o.getStatus() == com.flux.deploy.parallel.TargetStatus.FAILED) {
                                isolatedFailedCount++;
                            } else if (o.getStatus() == com.flux.deploy.parallel.TargetStatus.ROLLBACK_FAILED) {
                                isolatedCriticalCount++;
                            }
                        }
                    }
                    boolean partialSuccess = (isolatedFailedCount + isolatedCriticalCount) > 0;

                    // 预计算：retrySucceededKeys (relativePath) → 远端路径集合，便于在已生效列表上加 🔄 标记
                    java.util.Set<String> retrySuccessRemotes = new java.util.HashSet<>();
                    if (!retrySucceededKeys.isEmpty() && embedOutcomes != null) {
                        for (String key : retrySucceededKeys) {
                            for (FtpTargetSelection t : embedTargets) {
                                if (t.getRelativePath().equals(key)) {
                                    retrySuccessRemotes.add(t.getRemoteDir() + t.getRelativePath());
                                    break;
                                }
                            }
                        }
                    }

                    long deployElapsedMs = System.currentTimeMillis() - deployStartMs;
                    // actuallySucceeded == 0 时区分"全部失败"与"部分成功"，避免 0/N 仍打"部分成功"的误导
                    int totalEmbed = embedOutcomes == null ? 0 : embedOutcomes.size();
                    int actuallySucceeded = totalEmbed - isolatedFailedCount - isolatedCriticalCount;
                    boolean allFailed = partialSuccess && actuallySucceeded == 0;
                    if (allFailed) {
                        logCallback.accept("ERROR [部署] 全部失败，成功 0 / " + totalEmbed
                                + "，总耗时 " + formatElapsed(deployElapsedMs));
                    } else if (partialSuccess) {
                        logCallback.accept("WARN  [部署] 部分成功，成功 " + actuallySucceeded + " / "
                                + totalEmbed + "，总耗时 " + formatElapsed(deployElapsedMs));
                    } else {
                        logCallback.accept("INFO  [部署] 部署完成，成功 " + updatedPackages.size()
                                + " 个，总耗时 " + formatElapsed(deployElapsedMs));
                    }
                    logCallback.accept("\n╔══════════════════════════════╗");
                    if (allFailed) {
                        logCallback.accept("║      全部失败 0/" + totalEmbed
                                + "             ║");
                    } else if (partialSuccess) {
                        logCallback.accept("║      部分成功 " + actuallySucceeded + "/"
                                + totalEmbed + "             ║");
                    } else {
                        logCallback.accept("║          部署完成            ║");
                    }
                    logCallback.accept("╚══════════════════════════════╝");

                    // 已生效的包路径列表（成功路径与改造前格式一致）
                    if (partialSuccess) {
                        // 仅打实际成功的子集（updatedPackages 是"备份阶段就登记的全部"，含失败）
                        java.util.Set<String> failedRemotes = new java.util.HashSet<>();
                        for (com.flux.deploy.parallel.TargetOutcome o : embedOutcomes.values()) {
                            if (o.getStatus() != com.flux.deploy.parallel.TargetStatus.SUCCESS) {
                                // 用 relativePath 做 key，从 embedTargets 里反查 remotePath
                                for (FtpTargetSelection t : embedTargets) {
                                    if (t.getRelativePath().equals(o.getTargetKey())) {
                                        failedRemotes.add(t.getRemoteDir() + t.getRelativePath());
                                        break;
                                    }
                                }
                            }
                        }
                        int succeededTotal = updatedPackages.size() - failedRemotes.size();
                        logCallback.accept(com.flux.deploy.plugin.toolwindow.LogSectionPanel.RAW_LINE_MARK
                                + "          已生效 " + succeededTotal + " 个包"
                                + (retrySuccessRemotes.isEmpty() ? "："
                                        : "（含 " + retrySuccessRemotes.size() + " 个重试后成功）："));
                        synchronized (updatedPackages) {
                            for (String[] entry : updatedPackages) {
                                if (failedRemotes.contains(entry[0])) continue;
                                String marker = retrySuccessRemotes.contains(entry[0])
                                        ? "  (重试后成功)" : "";
                                logCallback.accept(com.flux.deploy.plugin.toolwindow.LogSectionPanel.RAW_LINE_MARK
                                        + "          " + entry[0] + marker);
                            }
                        }
                        // 最终失败列表（重试 N 轮后仍失败的；首次失败但重试成功的不在此列）
                        logCallback.accept(com.flux.deploy.plugin.toolwindow.LogSectionPanel.RAW_LINE_MARK
                                + "          最终失败 " + isolatedFailedCount + " 个包：");
                        com.flux.deploy.ftp.FtpErrorKind firstSuggestionKind = null;
                        // 标记：所有失败是否都是"目标 WAR 不含该 JAR"类（用户选错目标，不是部署系统问题）
                        // → 用于把通用"建议重新部署"提醒切换为更具针对性的"检查目标选择"提醒
                        boolean allFailuresAreMissingJar = isolatedFailedCount > 0;
                        for (com.flux.deploy.parallel.TargetOutcome o : embedOutcomes.values()) {
                            if (o.getStatus() == com.flux.deploy.parallel.TargetStatus.FAILED) {
                                String reason = o.getError() != null && o.getError().getMessage() != null
                                        ? o.getError().getMessage() : "未知错误";
                                logCallback.accept(com.flux.deploy.plugin.toolwindow.LogSectionPanel.RAW_LINE_MARK
                                        + "          " + o.getTargetKey() + " —— " + reason);
                                if (firstSuggestionKind == null && o.getErrorKind() != null) {
                                    firstSuggestionKind = o.getErrorKind();
                                }
                                if (!isMissingJarFailure(reason)) {
                                    allFailuresAreMissingJar = false;
                                }
                            }
                        }
                        if (isolatedCriticalCount > 0) {
                            logCallback.accept("ERROR [部署] 严重（回滚失败） " + isolatedCriticalCount + " 个包：");
                            for (com.flux.deploy.parallel.TargetOutcome o : embedOutcomes.values()) {
                                if (o.getStatus() == com.flux.deploy.parallel.TargetStatus.ROLLBACK_FAILED) {
                                    String reason = o.getError() != null && o.getError().getMessage() != null
                                            ? o.getError().getMessage() : "回滚失败";
                                    logCallback.accept(com.flux.deploy.plugin.toolwindow.LogSectionPanel.RAW_LINE_MARK
                                            + "          " + o.getTargetKey() + " —— " + reason);
                                }
                            }
                            logCallback.accept("ERROR [部署] " + isolatedCriticalCount
                                    + " 个包回滚失败，需从备份目录手动恢复，备份目录已强制保留");
                        }
                        if (firstSuggestionKind != null) {
                            String suggestion = com.flux.deploy.ftp.FtpErrorClassifier
                                    .suggestionFor(firstSuggestionKind);
                            if (suggestion != null) {
                                logCallback.accept("INFO  [部署] 建议：" + suggestion);
                            }
                        }
                        // 提醒分两类：
                        //   - 全部失败都是"WAR 不含目标 JAR" → 重新部署也是同样错，应让用户检查目标选择
                        //   - 否则 → 通用提醒：远端已恢复为旧版本，建议重试失败包
                        if (allFailuresAreMissingJar) {
                            logCallback.accept("WARN  [部署] 这些 WAR 内不存在目标 JAR 文件，"
                                    + "请检查是否选错了部署目标，远端文件未变更");
                        } else if (allFailed) {
                            logCallback.accept("WARN  [部署] 所有包均未生效，远端文件已自动恢复为旧版本，"
                                    + "请排查失败原因后重新部署");
                        } else {
                            logCallback.accept("WARN  [部署] 部分包未生效，远端文件已自动恢复为旧版本，"
                                    + "建议单独重新部署失败的包以保证集群版本一致");
                        }
                    } else {
                        logCallback.accept(com.flux.deploy.plugin.toolwindow.LogSectionPanel.RAW_LINE_MARK
                                + "          已更新 " + updatedPackages.size() + " 个包"
                                + (retrySuccessRemotes.isEmpty() ? "："
                                        : "（含 " + retrySuccessRemotes.size() + " 个重试后成功）："));
                        // 远程路径以 RAW_LINE_MARK 开头分行输出；工具窗口会剥标记并跳过时间戳，列表对齐干净。
                        // 缩进 10 空格对齐到带时间戳行的内容列（"HH:mm:ss" 8 + 分隔 2）。
                        synchronized (updatedPackages) {
                            for (String[] entry : updatedPackages) {
                                String remotePath = entry[0];
                                String marker = retrySuccessRemotes.contains(remotePath)
                                        ? "  🔄 (重试后成功)" : "";
                                logCallback.accept(com.flux.deploy.plugin.toolwindow.LogSectionPanel.RAW_LINE_MARK
                                        + "          " + remotePath + marker);
                            }
                        }
                    }
                    if (backupDir != null) {
                        logCallback.accept(com.flux.deploy.plugin.toolwindow.LogSectionPanel.RAW_LINE_MARK
                                + "          备份目录：" + backupDir);
                    } else {
                        logCallback.accept(com.flux.deploy.plugin.toolwindow.LogSectionPanel.RAW_LINE_MARK
                                + "          备份：未执行（用户选择跳过）");
                    }
                    logCallback.accept(com.flux.deploy.plugin.toolwindow.LogSectionPanel.RAW_LINE_MARK
                            + "          总耗时：" + formatElapsed(deployElapsedMs));

                    // 保存回滚信息供手动回滚使用（仅在有备份时）
                    if (backupDir != null) {
                        lastBackupDir = backupDir;
                        // updatedPackages 是 synchronizedList：迭代复制时需显式同步源 list
                        synchronized (updatedPackages) {
                            lastUpdatedPackages = new ArrayList<>(updatedPackages);
                        }
                        lastAllTargets = new ArrayList<>(allTargets);
                        lastUpdatedNote = pluginConfig.isUpdateNote();
                        lastBackupBorrowed = backupBorrowed;
                    }

                    onComplete.accept(result);

                } catch (Exception e) {
                    logCallback.accept("ERROR [部署] " + e.getMessage());
                    logFailureSummary(logCallback, "未预期异常: " + e.getMessage());
                    onComplete.accept(null);
                } finally {
                    System.setOut(originalOut);
                    System.setErr(originalErr);
                    // 删除备份阶段为嵌入复用而保留的本地原 WAR 副本（每次 deploy 末尾都清，
                    // 不论成功/失败/取消，避免 temp 残留）
                    clearBackupLocalCopies();
                }
            }
        });
    }

    /**
     * 从插件配置构建 CLI DeployConfig
     */
    private static DeployConfig buildDeployConfig(PluginDeployConfig pluginConfig,
                                                   String host, int port, String username, String password,
                                                   Path stagingPackage) {
        DeployConfig config = new DeployConfig();

        // FTP 连接信息
        config.setHost(host);
        config.setPort(port);
        config.setUsername(username);
        config.setPassword(password);

        // 目标信息
        FtpTargetSelection target = pluginConfig.getTarget();
        if (target != null) {
            config.setRemoteDir(target.getRemoteDir());
            config.setTargetNames(List.of(target.getTargetName()));
            config.setTargetRelativePaths(List.of(target.getRelativePath()));
        } else if (pluginConfig.getEmbedTargets() != null && !pluginConfig.getEmbedTargets().isEmpty()) {
            // 无主目标，从嵌入目标取远程目录（用于 FTP 连接和预检）
            FtpTargetSelection firstEmbed = pluginConfig.getEmbedTargets().get(0);
            config.setRemoteDir(firstEmbed.getRemoteDir());
            config.setTargetNames(List.of());
            config.setTargetRelativePaths(List.of());
            config.setLocalFiles(List.of());
        } else {
            config.setTargetNames(List.of());
            config.setTargetRelativePaths(List.of());
        }

        // 本地文件：增量/自动检索用暂存包，全量用编译产物
        if (stagingPackage != null) {
            // 增量/自动检索模式：使用暂存包
            config.setLocalFiles(List.of(stagingPackage));
        } else if (pluginConfig.getArtifactFileName() != null && pluginConfig.getModulePath() != null) {
            // 全量模式：使用 target/ 下的编译产物（用户负责提前 mvn package；缺失由 ArtifactPresenceValidator 拦下）
            Path targetDir = Path.of(pluginConfig.getModulePath(), "target");
            Path artifactPath = targetDir.resolve(pluginConfig.getArtifactFileName());
            config.setLocalFiles(List.of(artifactPath));
        } else {
            config.setLocalFiles(List.of());
        }

        // 元信息
        config.setOperator(pluginConfig.getOperator());
        config.setDryRun(pluginConfig.isDryRun());
        config.setSkipNote(!pluginConfig.isUpdateNote());
        if (pluginConfig.isUpdateNote()) {
            config.setTaskId(pluginConfig.getTaskId());
            config.setCustomerId(pluginConfig.getCustomerId());
        }

        return config;
    }


    /**
     * 执行 WAR 嵌入：下载远程 WAR（通过锁名）→ 替换内部 jar → 校验 → 上传
     *
     * <p>形参 {@code artifactFileName} 是源工程产物的<b>完整文件名</b>（含扩展名），
     * 用于在每个 war 内独立解析"应被替换的 lib jar 完整文件名"，确保
     * extract / embedJar 在同一个完整 jar 名上做。<br>
     * 旧版形参为 {@code artifactPrefix}（artifactId 前缀），全链路按 startsWith 模糊匹配，
     * 已确认会误命中同前缀 sibling jar 导致主包被错误覆盖。已废止。</p>
     *
     * @param lockRemoteDir WAR 所在远程目录（加锁时记录的目录）
     * @param lockName      WAR 的锁文件名（原文件已被 rename 为此名）
     */
    private static void executeWarEmbed(PluginDeployConfig pluginConfig,
                                         FtpTargetSelection embedTarget,
                                         Path localJar, String artifactFileName,
                                         String lockRemoteDir, String lockName,
                                         String host, int port, String username, String password,
                                         Consumer<String> logCallback) throws Exception {
        String remoteDir = embedTarget.getRemoteDir();
        String warRelPath = embedTarget.getRelativePath();
        String warName = embedTarget.getTargetName();

        Path tempDir = Files.createTempDirectory("war-embed-");
        try {
            Path downloadedWar = tempDir.resolve(warName);
            // 1. 优先复用备份阶段下载到本地的原 WAR 副本（节省一次相同字节的远端下载）；
            //    缺失时回退到 FTP 下载锁文件（lock rename 不改字节，与原 WAR 等价）。
            Path reusable = backupLocalCopies.get(embedTarget.getRelativePath());
            if (reusable != null && Files.isRegularFile(reusable) && Files.size(reusable) > 0) {
                Files.copy(reusable, downloadedWar,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                logCallback.accept("[嵌入] 复用备份阶段本地副本: " + warName
                        + " (" + Files.size(downloadedWar) / 1024 / 1024 + " MB)");
            } else {
                logCallback.accept("[嵌入] 下载远程 WAR: " + warName);
                try (FtpSession session = new FtpSession(host, port)) {
                    session.connect(username, password);
                    FtpOperations ops = new FtpOperations(session);
                    String lockPath = lockRemoteDir + lockName;
                    ops.download(lockPath, downloadedWar);
                    logCallback.accept("[嵌入] 下载完成: " + Files.size(downloadedWar) / 1024 / 1024 + " MB");
                }
            }

            // 2. 在本 war 内精确解析目标 lib 文件名（按完整产物名校验版本一致），
            //    后续 extract 与 embedJar 都用这一个完整文件名，杜绝错位
            String targetJarName = resolveEmbedTargetJarName(downloadedWar, artifactFileName);

            // 3. 替换内部 JAR
            Path outputWar = tempDir.resolve("embed-" + warName);
            Path jarToEmbed = localJar;
            StagingPackageBuilder.PatchManifest perWarManifest = null;

            // 增量/自动检索模式：不整个替换 JAR，只替换修改的 class
            com.flux.deploy.plugin.model.DeployMode mode = pluginConfig.getMode();
            if (mode != null && mode != com.flux.deploy.plugin.model.DeployMode.FULL) {
                // 不再打"增量模式：从 WAR 中提取嵌入 JAR..." / "已构建增量补丁 JAR" 两行说明性日志：
                // 紧跟其后的 [补丁] 行已能体现"按 class 增量"的语义，重复说明只是噪音。
                Path extractedJar = tempDir.resolve("extracted-" + targetJarName);
                extractEmbeddedJar(downloadedWar, targetJarName, extractedJar);

                if (Files.exists(extractedJar) && Files.size(extractedJar) > 0) {
                    // 用 StagingPackageBuilder 的变更文件列表构建 classEntries
                    // 全静态资源场景下不依赖 target/classes（StagingPackageBuilder 直读源文件）；
                    // .class 缺失情形已由 ArtifactPresenceValidator 在 UI 层提前拦下。
                    List<String> changedFiles = pluginConfig.getChangedFiles();
                    boolean canPatch = changedFiles != null && !changedFiles.isEmpty();
                    if (canPatch) {
                        StagingPackageBuilder patcher = new StagingPackageBuilder(
                                pluginConfig.getModulePath(),
                                pluginConfig.getArtifactFileName(),
                                changedFiles,
                                logCallback
                        );
                        StagingPackageBuilder.PatchOutcome outcome =
                                patcher.patchExistingJar(extractedJar, tempDir);
                        if (outcome != null && Files.exists(outcome.getPatchedJar())) {
                            jarToEmbed = outcome.getPatchedJar();
                            perWarManifest = outcome.getManifest();
                        }
                    }
                }
            }

            WarEmbedUtil.EmbedResult embedResult = WarEmbedUtil.embedJar(
                    downloadedWar, jarToEmbed, targetJarName, outputWar);

            // 不再 logCallback.accept(embedResult.getMessage())：WarEmbedUtil 内部已通过
            // System.out.println("  [嵌入] 校验通过…")（被 LogInterceptor 捕获）输出过同样
            // 的文案，再 logCallback 一次会让用户看到两条一字不差的"校验通过"。
            if (!embedResult.isVerified()) {
                throw new Exception("WAR 嵌入校验失败: " + embedResult.getMessage());
            }

            // 3. 用修改后的 WAR 执行部署流程（skipBackup + skipNote，加锁→上传→校验→解锁）
            //    不再打"开始上传嵌入后的 WAR..."：紧接着的 [上传]/[校验] 自带含义。
            DeployConfig embedConfig = new DeployConfig();
            embedConfig.setHost(host);
            embedConfig.setPort(port);
            embedConfig.setUsername(username);
            embedConfig.setPassword(password);
            embedConfig.setRemoteDir(remoteDir);
            embedConfig.setTargetNames(List.of(warName));
            embedConfig.setTargetRelativePaths(List.of(warRelPath));
            embedConfig.setLocalFiles(List.of(outputWar));
            embedConfig.setOperator(pluginConfig.getOperator());
            embedConfig.setSkipBackup(true);
            embedConfig.setSkipNote(true);
            embedConfig.setSkipLock(true);

            applyCancellationToken(embedConfig);
            DeployPipeline embedPipeline = new DeployPipeline(embedConfig);
            DeployResult embedDeployResult = embedPipeline.execute();

            if (embedDeployResult.isSuccess()) {
                logCallback.accept("[嵌入] " + warName + " 更新成功");
                // 输出本 war 包内变更明细（操作人员可立即对账）
                logPerWarPatchManifest(logCallback, warName, perWarManifest);
            } else {
                throw new Exception(warName + " 部署流程失败");
            }

        } finally {
            // 清理临时文件
            try {
                Files.walkFileTree(tempDir, new java.nio.file.SimpleFileVisitor<>() {
                    /**
                     * 访问文件时删除该文件
                     *
                     * @param file  当前文件
                     * @param attrs 文件属性
                     * @return 继续遍历
                     * @throws java.io.IOException 删除失败
                     * @author xumanyi
                     * @date 2026-03-27
                     */
                    @Override
                    public java.nio.file.FileVisitResult visitFile(Path file, java.nio.file.attribute.BasicFileAttributes attrs) throws java.io.IOException {
                        Files.delete(file);
                        return java.nio.file.FileVisitResult.CONTINUE;
                    }
                    /**
                     * 访问目录后删除该目录
                     *
                     * @param dir 当前目录
                     * @param exc 遍历异常
                     * @return 继续遍历
                     * @throws java.io.IOException 删除失败
                     * @author xumanyi
                     * @date 2026-03-27
                     */
                    @Override
                    public java.nio.file.FileVisitResult postVisitDirectory(Path dir, java.io.IOException exc) throws java.io.IOException {
                        Files.delete(dir);
                        return java.nio.file.FileVisitResult.CONTINUE;
                    }
                });
            } catch (Exception ignored) {}
        }
    }

    /**
     * 嵌入阶段并行执行的聚合结果
     *
     * @author xumanyi
     * @date 2026-05-02
     */
    private static final class EmbedParallelOutcome {
        /** 成功嵌入的包数量 */
        final int successCount;
        /** 失败的包数量（含 ROLLBACK_FAILED） */
        final int failedCount;
        /** 是否需要走主流程 abortPartial（ROLLBACK_ALL / KEEP_SUCCEEDED 模式下失败时为 true） */
        final boolean shouldAbort;
        /** 详细 outcomes（按目标 key 索引），供终态日志渲染失败列表与建议 */
        final java.util.Map<String, com.flux.deploy.parallel.TargetOutcome> outcomes;

        /**
         * @param successCount 成功包数
         * @param failedCount  失败包数
         * @param shouldAbort  是否触发主流程整体回滚
         * @param outcomes     详细 outcomes
         * @author xumanyi
         * @date 2026-05-02
         */
        EmbedParallelOutcome(int successCount, int failedCount, boolean shouldAbort,
                              java.util.Map<String, com.flux.deploy.parallel.TargetOutcome> outcomes) {
            this.successCount = successCount;
            this.failedCount = failedCount;
            this.shouldAbort = shouldAbort;
            this.outcomes = outcomes;
        }
    }

    /**
     * download 阶段输出：tempDir + 已下载的 WAR 路径
     *
     * @author xumanyi
     * @date 2026-05-02
     */
    private record EmbedDownload(Path tempDir, Path downloadedWar) {}

    /**
     * embed 阶段输出：嵌入完成的待上传 WAR 路径（位于 tempDir 内）+ 本 war 包内变更明细
     *
     * <p>{@code manifest} 在 FULL 模式或无差异 patch 时为 null；增量模式下携带
     * {@link StagingPackageBuilder.PatchManifest}，供 upload 成功后输出"包内更新明细"。</p>
     *
     * @author xumanyi
     * @date 2026-05-02
     */
    private record EmbedTransform(Path outputWar, StagingPackageBuilder.PatchManifest manifest) {}

    /**
     * 嵌入阶段并行执行（流水线版本）
     *
     * <p>把"下载 / 嵌入 / 上传"拆到三个独立线程池调度（{@link com.flux.deploy.parallel.PipelineExecutor}），
     * 在上下行带宽独立的链路上能让 download 和 upload 在时间上重叠，提升带宽利用率。</p>
     *
     * <p>安全保证：</p>
     * <ul>
     *   <li>每个目标独立完成自己的 D-E-U（下载它自己 → 嵌入到它自己 → 上传到它自己），
     *       不复用任何嵌入产物</li>
     *   <li>tempDir 由 stages.cleanup 在 target 完成时（无论成败）统一清理</li>
     *   <li>ISOLATED 模式：upload 失败 → 单包回滚（同串行路径），其他任务继续</li>
     *   <li>ROLLBACK_ALL / KEEP_SUCCEEDED：任一失败 → 触发取消令牌</li>
     * </ul>
     *
     * @param pluginConfig     部署配置
     * @param embedTargets     嵌入目标列表
     * @param localJar         本地源 JAR
     * @param artifactFileName 产物完整文件名（含扩展名；用于在 war 内精确解析 lib jar 全名）
     * @param lockedPackages   加锁信息列表（顺序：main0..mainN, embed0..embedM）
     * @param mainOffset       embed 起始下标
     * @param updatedPackages  备份阶段注册的回滚清单（用于查找单包备份路径）
     * @param strategy         失败策略
     * @param host             FTP 主机
     * @param port             FTP 端口
     * @param username         FTP 用户名
     * @param password         FTP 密码
     * @param logCallback      日志回调
     * @return 聚合结果
     * @author xumanyi
     * @date 2026-05-02
     */
    private static EmbedParallelOutcome runEmbedPhaseParallel(
            PluginDeployConfig pluginConfig,
            List<FtpTargetSelection> embedTargets,
            Path localJar, String artifactFileName,
            List<String[]> lockedPackages, int mainOffset,
            List<String[]> updatedPackages,
            com.flux.deploy.config.FailureStrategy strategy,
            String host, int port, String username, String password,
            Consumer<String> logCallback) {

        com.flux.deploy.deploy.CancellationToken.Simple parallelToken =
                new com.flux.deploy.deploy.CancellationToken.Simple();

        // 预建 lockInfo 索引：避免每次 download 都做 list.indexOf O(n) 查找
        java.util.Map<String, String[]> lockInfoByKey = new java.util.HashMap<>();
        for (int i = 0; i < embedTargets.size(); i++) {
            lockInfoByKey.put(embedTargets.get(i).getRelativePath(),
                    lockedPackages.get(i + mainOffset));
        }

        // 跨阶段标记表：upload 阶段单包回滚失败时把 key 放入此 map，
        // PipelineExecutor 完成后由调用方修正对应 outcome 为 ROLLBACK_FAILED
        final java.util.concurrent.ConcurrentHashMap<String, Boolean> rollbackFailedKeys =
                new java.util.concurrent.ConcurrentHashMap<>();

        com.flux.deploy.parallel.PipelineExecutor.PipelineStages<FtpTargetSelection, EmbedDownload, EmbedTransform> stages =
                new com.flux.deploy.parallel.PipelineExecutor.PipelineStages<>() {

                    @Override
                    public EmbedDownload download(FtpTargetSelection target, StringBuilder log) throws Exception {
                        // 用户取消（通过 currentCancelMode）→ 立即抛 CancellationException
                        if (currentCancelMode != CancelMode.NONE) {
                            throw new com.flux.deploy.deploy.CancellationToken.CancellationException();
                        }
                        String[] lockInfo = lockInfoByKey.get(target.getRelativePath());
                        if (lockInfo == null) {
                            throw new IllegalStateException(
                                    "未找到 " + target.getRelativePath() + " 的加锁信息");
                        }
                        Path tempDir = Files.createTempDirectory("war-embed-");
                        try {
                            Path downloadedWar = tempDir.resolve(target.getTargetName());
                            // 优先复用备份阶段下载到本地的原 WAR 副本，跳过一次相同字节的远端下载
                            Path reusable = backupLocalCopies.get(target.getRelativePath());
                            if (reusable != null && Files.isRegularFile(reusable) && Files.size(reusable) > 0) {
                                Files.copy(reusable, downloadedWar,
                                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                                log.append("[嵌入] 复用备份阶段本地副本: ")
                                        .append(target.getTargetName())
                                        .append(" (").append(Files.size(downloadedWar) / 1024 / 1024)
                                        .append(" MB)\n");
                            } else {
                                log.append("[嵌入] 下载远程 WAR: ").append(target.getTargetName()).append('\n');
                                try (FtpSession session = new FtpSession(host, port)) {
                                    session.connect(username, password);
                                    FtpOperations ops = new FtpOperations(session);
                                    String lockPath = lockInfo[0] + lockInfo[1];
                                    ops.download(lockPath, downloadedWar);
                                    log.append("[嵌入] 下载完成: ")
                                            .append(Files.size(downloadedWar) / 1024 / 1024)
                                            .append(" MB\n");
                                }
                            }
                            return new EmbedDownload(tempDir, downloadedWar);
                        } catch (Exception e) {
                            // download 失败：立即清理 tempDir（cleanup 拿不到 EmbedDownload 引用）
                            cleanupTempDirSilent(tempDir);
                            throw e;
                        }
                    }

                    @Override
                    public EmbedTransform embed(FtpTargetSelection target, EmbedDownload d, StringBuilder log)
                            throws Exception {
                        if (currentCancelMode != CancelMode.NONE) {
                            throw new com.flux.deploy.deploy.CancellationToken.CancellationException();
                        }
                        try {
                            return doEmbedStage(target, d, log);
                        } catch (Exception ex) {
                            // ISOLATED：embed 阶段失败 → 没有上传，远端仍是锁文件，
                            // Phase 4 preUnlockAll 会走 restoreLock 把锁文件 rename 回原名。
                            // 这里给操作员一行明确语义日志，避免只看到末尾"原文件缺失，已从锁文件恢复"
                            // 这种"异常自愈"语境的措辞。
                            if (strategy == com.flux.deploy.config.FailureStrategy.ISOLATED
                                    && !(ex instanceof com.flux.deploy.deploy.CancellationToken.CancellationException)) {
                                log.append("INFO  [回滚] ").append(target.getTargetName())
                                        .append(" 嵌入未完成，远端将保持原 WAR（解锁阶段恢复）\n");
                            }
                            throw ex;
                        }
                    }

                    /** embed 主体：抽出嵌入逻辑以便外层 try/catch 不影响可读性 */
                    private EmbedTransform doEmbedStage(FtpTargetSelection target, EmbedDownload d, StringBuilder log)
                            throws Exception {
                        Path outputWar = d.tempDir().resolve("embed-" + target.getTargetName());
                        Path jarToEmbed = localJar;
                        StagingPackageBuilder.PatchManifest perWarManifest = null;

                        // 在本 war 内精确解析目标 lib 文件名（按完整产物名校验版本一致）；
                        // 后续 extract / embedJar 都用同一个完整文件名，杜绝错位
                        String targetJarName = resolveEmbedTargetJarName(d.downloadedWar(), artifactFileName);

                        // 增量/自动检索模式：不整个替换 JAR，只替换修改的 class
                        com.flux.deploy.plugin.model.DeployMode mode = pluginConfig.getMode();
                        if (mode != null && mode != com.flux.deploy.plugin.model.DeployMode.FULL) {
                            Path extractedJar = d.tempDir().resolve("extracted-" + targetJarName);
                            extractEmbeddedJar(d.downloadedWar(), targetJarName, extractedJar);
                            if (Files.exists(extractedJar) && Files.size(extractedJar) > 0) {
                                // .class 存在性已由 UI 层 ArtifactPresenceValidator 在点击部署前强制校验，
                                // 这里只看勾选清单是否非空就允许走 patch；StagingPackageBuilder 内部
                                // 直读源文件解析资源，不依赖 target/classes。
                                List<String> changedFiles = pluginConfig.getChangedFiles();
                                boolean canPatch = changedFiles != null && !changedFiles.isEmpty();
                                if (canPatch) {
                                    Consumer<String> bufLog = line -> log.append(line).append('\n');
                                    StagingPackageBuilder patcher = new StagingPackageBuilder(
                                            pluginConfig.getModulePath(),
                                            pluginConfig.getArtifactFileName(),
                                            changedFiles,
                                            bufLog
                                    );
                                    StagingPackageBuilder.PatchOutcome outcome =
                                            patcher.patchExistingJar(extractedJar, d.tempDir());
                                    if (outcome != null && Files.exists(outcome.getPatchedJar())) {
                                        jarToEmbed = outcome.getPatchedJar();
                                        perWarManifest = outcome.getManifest();
                                    }
                                }
                            }
                        }

                        WarEmbedUtil.EmbedResult embedResult = WarEmbedUtil.embedJar(
                                d.downloadedWar(), jarToEmbed, targetJarName, outputWar);
                        if (!embedResult.isVerified()) {
                            throw new Exception("WAR 嵌入校验失败: " + embedResult.getMessage());
                        }
                        return new EmbedTransform(outputWar, perWarManifest);
                    }

                    @Override
                    public void upload(FtpTargetSelection target, EmbedTransform e, StringBuilder log) throws Exception {
                        if (currentCancelMode != CancelMode.NONE) {
                            throw new com.flux.deploy.deploy.CancellationToken.CancellationException();
                        }
                        try {
                            DeployConfig embedConfig = new DeployConfig();
                            embedConfig.setHost(host);
                            embedConfig.setPort(port);
                            embedConfig.setUsername(username);
                            embedConfig.setPassword(password);
                            embedConfig.setRemoteDir(target.getRemoteDir());
                            embedConfig.setTargetNames(List.of(target.getTargetName()));
                            embedConfig.setTargetRelativePaths(List.of(target.getRelativePath()));
                            embedConfig.setLocalFiles(List.of(e.outputWar()));
                            embedConfig.setOperator(pluginConfig.getOperator());
                            embedConfig.setSkipBackup(true);
                            embedConfig.setSkipNote(true);
                            embedConfig.setSkipLock(true);
                            applyCancellationToken(embedConfig);
                            DeployPipeline embedPipeline = new DeployPipeline(embedConfig);
                            DeployResult embedDeployResult = embedPipeline.execute();
                            if (!embedDeployResult.isSuccess()) {
                                throw new Exception(target.getTargetName() + " 部署流程失败");
                            }
                            log.append("INFO  [嵌入] ").append(target.getTargetName()).append(" 更新成功\n");
                            // 输出本 war 包内变更明细（操作人员可立即对账）
                            // 走 PipelineExecutor 提供的 log buffer，跟随该 target 流水线统一刷出
                            appendPerWarPatchManifest(log, target.getTargetName(), e.manifest());
                            recordSucceededUpload(target);
                        } catch (Exception ex) {
                            // ISOLATED：upload 失败 → 单包回滚（与原串行路径一致）
                            if (strategy == com.flux.deploy.config.FailureStrategy.ISOLATED
                                    && !(ex instanceof com.flux.deploy.deploy.CancellationToken.CancellationException)) {
                                String remotePath = target.getRemoteDir() + target.getRelativePath();
                                String backupPath = lookupBackupPath(updatedPackages, remotePath);
                                log.append("ERROR [嵌入] ").append(target.getTargetName())
                                        .append(" 嵌入失败：").append(ex.getMessage()).append('\n');
                                if (backupPath != null) {
                                    try {
                                        rollbackSingleTarget(remotePath, backupPath,
                                                host, port, username, password);
                                        log.append("INFO  [回滚] 已恢复 ").append(remotePath).append('\n');
                                    } catch (Exception rollbackErr) {
                                        // 回滚失败：标记 key，由调用方修正 outcome 为 ROLLBACK_FAILED
                                        rollbackFailedKeys.put(target.getRelativePath(), Boolean.TRUE);
                                        log.append("ERROR [回滚] 单包回滚失败：")
                                                .append(rollbackErr.getMessage()).append('\n');
                                    }
                                } else {
                                    log.append("WARN  [回滚] 未找到 ").append(remotePath)
                                            .append(" 的备份路径，无法单包回滚\n");
                                }
                            }
                            throw ex;
                        }
                    }

                    @Override
                    public void cleanup(FtpTargetSelection target, EmbedDownload d, EmbedTransform e) {
                        // download 成功后 tempDir 由 EmbedDownload 持有；此处统一清理
                        if (d != null) {
                            cleanupTempDirSilent(d.tempDir());
                        }
                    }
                };

        // download 池规模写死为 1：备份阶段已下载远端字节并保留在本地，嵌入阶段优先复用本地副本，
        //   真正走 FTP 下载只是少数 fallback（备份被跳过 / 本地副本意外缺失）。
        // upload 池规模 = FTP_PARALLELISM（FTP 操作并发上限，详见常量注释）。
        // embed 池规模由 PipelineExecutor 内部固定为 1（本地补丁串行）。
        com.flux.deploy.parallel.PipelineExecutor.Options opts =
                new com.flux.deploy.parallel.PipelineExecutor.Options(
                        1, FTP_PARALLELISM,
                        strategy, parallelToken, "embed", logCallback);

        java.util.Map<String, com.flux.deploy.parallel.TargetOutcome> outcomes =
                com.flux.deploy.parallel.PipelineExecutor.execute(
                        opts, embedTargets, FtpTargetSelection::getRelativePath, stages);

        // 修正 ROLLBACK_FAILED outcomes：upload 阶段单包回滚失败的目标，把状态从 FAILED 提升为 ROLLBACK_FAILED
        for (String key : rollbackFailedKeys.keySet()) {
            com.flux.deploy.parallel.TargetOutcome orig = outcomes.get(key);
            if (orig != null && orig.getStatus() == com.flux.deploy.parallel.TargetStatus.FAILED) {
                outcomes.put(key, com.flux.deploy.parallel.TargetOutcome.rollbackFailed(
                        key, orig.getError(), orig.getLogSegment()));
            }
        }

        // 顺序 flush 单包日志，保持目标内日志连续
        for (com.flux.deploy.parallel.TargetOutcome o : outcomes.values()) {
            String seg = o.getLogSegment();
            if (!seg.isEmpty()) {
                for (String line : seg.split("\n")) {
                    if (!line.isEmpty()) logCallback.accept(line);
                }
            }
        }

        // 聚合统计
        int success = 0, failed = 0;
        for (com.flux.deploy.parallel.TargetOutcome o : outcomes.values()) {
            switch (o.getStatus()) {
                case SUCCESS:
                    success++;
                    break;
                case FAILED:
                case ROLLBACK_FAILED:
                case CANCELLED:
                case SKIPPED:
                    failed++;
                    break;
            }
        }

        // 决定是否让主流程整体回滚：
        //   ISOLATED 下：失败的已单包回滚，主流程不再走 abortPartial
        //   ROLLBACK_ALL / KEEP_SUCCEEDED 下：任一失败 → 主流程 abortPartial
        boolean shouldAbort = (failed > 0)
                && strategy != com.flux.deploy.config.FailureStrategy.ISOLATED;
        return new EmbedParallelOutcome(success, failed, shouldAbort, outcomes);
    }

    /**
     * 把毫秒时长格式化为"M 分 S 秒"（便于终态日志显示总耗时）
     *
     * @param elapsedMs 耗时毫秒
     * @return 例如 "9 分 10 秒" 或 "45 秒"
     * @author xumanyi
     * @date 2026-05-02
     */
    private static String formatElapsed(long elapsedMs) {
        long totalSeconds = elapsedMs / 1000;
        long minutes = totalSeconds / 60;
        long seconds = totalSeconds % 60;
        if (minutes <= 0) {
            return seconds + " 秒";
        }
        return minutes + " 分 " + seconds + " 秒";
    }

    /**
     * 静默清理临时目录（供流水线 cleanup 钩子使用）
     *
     * <p>遇到任何异常都吞掉（仅记录到 stderr），避免清理失败干扰主流程返回的 outcome。</p>
     *
     * @param tempDir 待清理的临时目录
     * @author xumanyi
     * @date 2026-05-02
     */
    private static void cleanupTempDirSilent(Path tempDir) {
        if (tempDir == null) return;
        try {
            Files.walkFileTree(tempDir, new java.nio.file.SimpleFileVisitor<>() {
                @Override
                public java.nio.file.FileVisitResult visitFile(Path file,
                                                                 java.nio.file.attribute.BasicFileAttributes attrs)
                        throws java.io.IOException {
                    Files.delete(file);
                    return java.nio.file.FileVisitResult.CONTINUE;
                }

                @Override
                public java.nio.file.FileVisitResult postVisitDirectory(Path dir, java.io.IOException exc)
                        throws java.io.IOException {
                    Files.delete(dir);
                    return java.nio.file.FileVisitResult.CONTINUE;
                }
            });
        } catch (Exception ignored) {
            // 清理失败不影响主流程；最坏情况是临时目录残留，下次 OS 重启会清除
        }
    }

    /**
     * 嵌入失败包的串行重试结果
     *
     * @author xumanyi
     * @date 2026-05-02
     */
    private static final class EmbedRetryOutcome {
        /** 原本失败、重试后成功的目标 key 集合 */
        final java.util.Set<String> retrySucceededKeys;
        /** 重试 N 轮后仍失败的目标 key 集合 */
        final java.util.Set<String> finalFailedKeys;
        /** 重试期间又触发了 ROLLBACK_FAILED 的 key 集合（罕见） */
        final java.util.Set<String> retryRollbackFailedKeys;

        EmbedRetryOutcome(java.util.Set<String> retrySucceededKeys,
                           java.util.Set<String> finalFailedKeys,
                           java.util.Set<String> retryRollbackFailedKeys) {
            this.retrySucceededKeys = retrySucceededKeys;
            this.finalFailedKeys = finalFailedKeys;
            this.retryRollbackFailedKeys = retryRollbackFailedKeys;
        }
    }

    /**
     * 嵌入阶段失败包的串行重试
     *
     * <p>设计要点：</p>
     * <ul>
     *   <li>**串行执行**：一次只重试一个包，避免再次堆积 FTP 服务器压力</li>
     *   <li>**复用 executeWarEmbed**：每包仍走完整 D-E-U，保持单包独立性</li>
     *   <li>**锁文件状态**：本方法在主流程"嵌入完成"和"解锁"之间调用，
     *       失败包的锁文件仍在远端，executeWarEmbed 可正常工作</li>
     *   <li>**AUTH 不重试**：认证失败重试也是失败，避免浪费时间</li>
     *   <li>**ROLLBACK_FAILED 不重试**：已严重状态，重试可能让情况更糟</li>
     *   <li>**重试间 2 秒延迟**：给 FTP 服务器缓冲时间</li>
     *   <li>**重试成功后修正 outcomes**：把对应 key 的状态从 FAILED → SUCCESS，
     *       并 recordSucceededUpload 让 UI"已成功"列表也包含</li>
     * </ul>
     *
     * @param embedTargets      嵌入目标列表
     * @param embedOutcomes     主流程产出的 outcomes（本方法直接修改其中失败 key 的状态）
     * @param pluginConfig      部署配置
     * @param localJar          本地源 JAR
     * @param artifactFileName  产物完整文件名（含扩展名；用于在 war 内精确解析 lib jar 全名）
     * @param lockedPackages    加锁信息列表
     * @param mainOffset        embed 起始下标
     * @param maxRetries        最大重试次数（来自 UserConfig，0 = 不重试）
     * @param host              FTP 主机
     * @param port              FTP 端口
     * @param username          FTP 用户名
     * @param password          FTP 密码
     * @param logCallback       日志回调
     * @return 重试结果
     * @author xumanyi
     * @date 2026-05-02
     */
    private static EmbedRetryOutcome retryFailedEmbedSerially(
            List<FtpTargetSelection> embedTargets,
            java.util.Map<String, com.flux.deploy.parallel.TargetOutcome> embedOutcomes,
            PluginDeployConfig pluginConfig,
            Path localJar, String artifactFileName,
            List<String[]> lockedPackages, int mainOffset,
            int maxRetries,
            String host, int port, String username, String password,
            Consumer<String> logCallback) {

        java.util.Set<String> retrySucceeded = new java.util.HashSet<>();
        java.util.Set<String> finalFailed = new java.util.HashSet<>();
        java.util.Set<String> retryRollbackFailed = new java.util.HashSet<>();

        if (maxRetries <= 0) {
            // 不重试：把当前所有 FAILED / ROLLBACK_FAILED 视为最终失败
            for (com.flux.deploy.parallel.TargetOutcome o : embedOutcomes.values()) {
                if (o.getStatus() == com.flux.deploy.parallel.TargetStatus.FAILED) {
                    finalFailed.add(o.getTargetKey());
                } else if (o.getStatus() == com.flux.deploy.parallel.TargetStatus.ROLLBACK_FAILED) {
                    retryRollbackFailed.add(o.getTargetKey());
                }
            }
            return new EmbedRetryOutcome(retrySucceeded, finalFailed, retryRollbackFailed);
        }

        // 收集首轮失败的可重试目标：FAILED + 非 AUTH 错误 + 非 CancellationException
        // ROLLBACK_FAILED 不参与重试（已严重）
        java.util.LinkedHashMap<String, FtpTargetSelection> retryQueue =
                collectRetryableTargets(embedTargets, embedOutcomes, retryRollbackFailed);

        if (retryQueue.isEmpty()) {
            // 所有失败要么是 AUTH 不可重试要么已 ROLLBACK_FAILED
            for (com.flux.deploy.parallel.TargetOutcome o : embedOutcomes.values()) {
                if (o.getStatus() == com.flux.deploy.parallel.TargetStatus.FAILED) {
                    finalFailed.add(o.getTargetKey());
                }
            }
            return new EmbedRetryOutcome(retrySucceeded, finalFailed, retryRollbackFailed);
        }

        // 预建索引：key → embedTarget index（找 lockInfo 用）
        java.util.Map<String, Integer> indexByKey = new java.util.HashMap<>();
        for (int i = 0; i < embedTargets.size(); i++) {
            indexByKey.put(embedTargets.get(i).getRelativePath(), i);
        }

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            if (retryQueue.isEmpty()) break;

            logCallback.accept("\n━━ 重试失败包 (第 " + attempt + " 轮，"
                    + retryQueue.size() + " 个) ━━");

            java.util.Iterator<java.util.Map.Entry<String, FtpTargetSelection>> it =
                    retryQueue.entrySet().iterator();
            while (it.hasNext()) {
                // 用户取消立即停止重试
                if (currentCancelMode != CancelMode.NONE) {
                    logCallback.accept("[重试] 用户已取消，跳过剩余 " + retryQueue.size() + " 个包");
                    break;
                }

                java.util.Map.Entry<String, FtpTargetSelection> entry = it.next();
                String key = entry.getKey();
                FtpTargetSelection target = entry.getValue();
                Integer idx = indexByKey.get(key);
                if (idx == null) {
                    logCallback.accept("[重试] 跳过 " + key + "（未找到加锁信息）");
                    it.remove();
                    finalFailed.add(key);
                    continue;
                }
                String[] lockInfo = lockedPackages.get(idx + mainOffset);

                logCallback.accept("[重试] " + target.getTargetName() + " ...");
                try {
                    executeWarEmbed(pluginConfig, target,
                            localJar, artifactFileName,
                            lockInfo[0], lockInfo[1],
                            host, port, username, password,
                            logCallback);
                    // 重试成功：登记 + 修正 outcome + 从队列移除
                    recordSucceededUpload(target);
                    com.flux.deploy.parallel.TargetOutcome orig = embedOutcomes.get(key);
                    String mergedLog = (orig != null ? orig.getLogSegment() : "")
                            + "[重试] 第 " + attempt + " 次重试成功\n";
                    embedOutcomes.put(key, com.flux.deploy.parallel.TargetOutcome.success(key, mergedLog));
                    retrySucceeded.add(key);
                    it.remove();
                    logCallback.accept("INFO  [嵌入] 重试成功 " + target.getTargetName());
                } catch (Exception e) {
                    // 重试失败：判断是否要再重试
                    com.flux.deploy.ftp.FtpErrorKind kind =
                            com.flux.deploy.ftp.FtpErrorClassifier.classify(e);
                    String reason = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                    if (kind == com.flux.deploy.ftp.FtpErrorKind.AUTH) {
                        // AUTH 错误不再重试
                        logCallback.accept("ERROR [嵌入] " + target.getTargetName()
                                + " 重试遇到认证失败，放弃重试，原因：" + reason);
                        it.remove();
                        finalFailed.add(key);
                    } else if (attempt >= maxRetries) {
                        // 最后一轮：明确告知用户"已达最大重试次数，最终标记为失败"
                        logCallback.accept("ERROR [嵌入] " + target.getTargetName()
                                + " 已达最大重试次数 " + maxRetries + "，最终标记为失败，原因：" + reason);
                        it.remove();
                        finalFailed.add(key);
                    } else {
                        // 还有下一轮：明确显示"第 N 次失败，将再次重试"
                        logCallback.accept("WARN  [嵌入] " + target.getTargetName()
                                + " 第 " + attempt + " 次重试失败，将进入下一轮，原因：" + reason);
                        // 保留在 retryQueue 等待下一轮
                    }
                }

                // 重试间 2 秒延迟，给 FTP 服务器缓冲
                if (it.hasNext() || attempt < maxRetries) {
                    try {
                        Thread.sleep(2000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }

        // 把仍在 retryQueue 中的（理论上为空，但防御）也算最终失败
        for (String key : retryQueue.keySet()) {
            finalFailed.add(key);
        }

        return new EmbedRetryOutcome(retrySucceeded, finalFailed, retryRollbackFailed);
    }

    /**
     * 从 outcomes 里收集可重试的失败目标
     *
     * <p>排除：</p>
     * <ul>
     *   <li>非 FAILED 状态（成功 / 取消 / 跳过等）</li>
     *   <li>错误类型为 AUTH（认证失败重试也是失败）</li>
     *   <li>ROLLBACK_FAILED（提取到独立集合 retryRollbackFailed）</li>
     *   <li>CancellationException 类异常（用户取消产生的失败）</li>
     * </ul>
     *
     * @param embedTargets         嵌入目标列表
     * @param embedOutcomes        outcomes
     * @param retryRollbackFailed  out: 已 ROLLBACK_FAILED 的 key 集合（不重试）
     * @return key → target 的 LinkedHashMap（保留输入顺序）
     * @author xumanyi
     * @date 2026-05-02
     */
    private static java.util.LinkedHashMap<String, FtpTargetSelection> collectRetryableTargets(
            List<FtpTargetSelection> embedTargets,
            java.util.Map<String, com.flux.deploy.parallel.TargetOutcome> embedOutcomes,
            java.util.Set<String> retryRollbackFailed) {

        java.util.LinkedHashMap<String, FtpTargetSelection> queue = new java.util.LinkedHashMap<>();
        java.util.Map<String, FtpTargetSelection> targetByKey = new java.util.HashMap<>();
        for (FtpTargetSelection t : embedTargets) {
            targetByKey.put(t.getRelativePath(), t);
        }

        for (com.flux.deploy.parallel.TargetOutcome o : embedOutcomes.values()) {
            String key = o.getTargetKey();
            FtpTargetSelection target = targetByKey.get(key);
            if (target == null) continue;
            switch (o.getStatus()) {
                case FAILED:
                    com.flux.deploy.ftp.FtpErrorKind kind = o.getErrorKind();
                    Throwable err = o.getError();
                    if (kind == com.flux.deploy.ftp.FtpErrorKind.AUTH) {
                        // AUTH 不重试，直接归入最终失败
                        continue;
                    }
                    if (err instanceof com.flux.deploy.deploy.CancellationToken.CancellationException) {
                        // 用户取消产生的失败不重试
                        continue;
                    }
                    queue.put(key, target);
                    break;
                case ROLLBACK_FAILED:
                    retryRollbackFailed.add(key);
                    break;
                default:
                    // SUCCESS / CANCELLED / SKIPPED 不重试
                    break;
            }
        }
        return queue;
    }

    /**
     * 单包回滚：把备份文件恢复到原远程位置
     *
     * <p>用于 ISOLATED 模式下嵌入失败时单独恢复该包，不影响其他并行任务。</p>
     *
     * @param remotePath     原始远程路径
     * @param backupFilePath 备份文件远程路径
     * @param host           FTP 主机
     * @param port           FTP 端口
     * @param user           FTP 用户名
     * @param pass           FTP 密码
     * @throws Exception 下载或上传失败 → 调用方将该包标记为 ROLLBACK_FAILED
     * @author xumanyi
     * @date 2026-05-02
     */
    /**
     * 判定失败原因是否属于"目标 WAR 不含目标 JAR"。
     *
     * <p>用于在终态提醒里区分两类失败：</p>
     * <ul>
     *   <li>用户选错部署目标（WAR 真的没那个 JAR）→ 重试同样错，应提示检查目标</li>
     *   <li>系统/网络问题 → 重试可能成功，提示重新部署</li>
     * </ul>
     *
     * <p>匹配两个错误源的固定文案：</p>
     * <ul>
     *   <li>{@link com.flux.deploy.util.WarEmbedUtil#embedJar} → "目标 WAR 内不存在 ... 的 JAR 文件"</li>
     *   <li>{@code extractEmbeddedJar} → 同上</li>
     * </ul>
     *
     * @param reason 失败原因消息（取自 TargetOutcome.error.message）
     * @return true 表示该失败属于"WAR 不含 JAR"类
     * @author xumanyi
     * @date 2026-05-04
     */
    static boolean isMissingJarFailure(String reason) {
        if (reason == null) return false;
        // 所有"WAR 内找不到与源产物文件名完全一致的 JAR"路径统一以该前缀开头
        return reason.contains("目标 WAR 内不存在");
    }

    private static void rollbackSingleTarget(String remotePath, String backupFilePath,
                                              String host, int port, String user, String pass) throws Exception {
        Path tempRestore = Files.createTempFile("restore-", ".tmp");
        try {
            runFreshFtpSession(host, port, user, pass, (s, ops) -> {
                ops.download(backupFilePath, tempRestore);
                ops.upload(tempRestore, remotePath);
            });
        } finally {
            Files.deleteIfExists(tempRestore);
        }
    }

    /**
     * 在 updatedPackages 中按 remotePath 查找对应的备份路径
     *
     * <p>updatedPackages 可能是 synchronizedList，迭代时需外部同步。</p>
     *
     * @param updatedPackages 备份阶段注册的回滚清单（[remotePath, backupFilePath]）
     * @param remotePath      远程路径
     * @return 备份文件路径；未找到返回 null
     * @author xumanyi
     * @date 2026-05-02
     */
    /**
     * 从 embedOutcomes 里提取本批次"未成功"目标的 relativePath 集合
     *
     * <p>"未成功" = FAILED / ROLLBACK_FAILED / CANCELLED / SKIPPED 任一状态。
     * 用于在 Phase 5 写 note 时跳过这些目标，避免给远端实际未变更的包写"取包/传包"幻象记录。</p>
     *
     * @param embedOutcomes 嵌入阶段产物（可为 null：未走嵌入路径或无失败时）
     * @return 未成功目标的 relativePath 集合；embedOutcomes 为 null 或全部成功时返回空集合
     * @author xumanyi
     * @date 2026-05-08
     */
    private static java.util.Set<String> collectFailedRelativePaths(
            java.util.Map<String, com.flux.deploy.parallel.TargetOutcome> embedOutcomes) {
        if (embedOutcomes == null || embedOutcomes.isEmpty()) {
            return java.util.Collections.emptySet();
        }
        java.util.Set<String> set = new java.util.LinkedHashSet<>();
        for (java.util.Map.Entry<String, com.flux.deploy.parallel.TargetOutcome> e : embedOutcomes.entrySet()) {
            com.flux.deploy.parallel.TargetStatus s = e.getValue().getStatus();
            if (s != com.flux.deploy.parallel.TargetStatus.SUCCESS) {
                set.add(e.getKey());
            }
        }
        return set;
    }

    /**
     * 把 preUnlockAll 中 restoreLock 失败的 target 升级为 ROLLBACK_FAILED 状态
     *
     * <p>触发条件：嵌入/上传未成功 → 远端原文件不存在 → preUnlockAll 走 restoreLock 分支 → rename 抛错。
     * 这种情形下远端只剩一个孤悬的锁文件，没有原 WAR，必须人工干预。原 outcome 可能是 FAILED（嵌入/上传失败）
     * 或 SUCCESS（极小概率：上传成功后清理锁失败误判，但 attemptedRestore 路径下 origExists=false 不会走到）。</p>
     *
     * <p>映射规则：{@code lockedPackages} 与 {@code allTargets} 同序（preLockAll 顺序遍历 allTargets 注册），
     * 因此 lockName 第 i 项对应 allTargets 第 i 项的 relativePath。</p>
     *
     * @param embedOutcomes        嵌入阶段 outcome 表（key = relativePath）
     * @param restoreFailedLockNames preUnlockAll 报回的 restoreLock 失败 lockName 集合
     * @param lockedPackages       全部锁记录（与 allTargets 同序）
     * @param allTargets           全部目标（顺序：main + embed）
     * @return 升级后的 outcome 表（保留输入的插入顺序）
     * @author xumanyi
     * @date 2026-05-08
     */
    private static java.util.Map<String, com.flux.deploy.parallel.TargetOutcome> upgradeRestoreFailedOutcomes(
            java.util.Map<String, com.flux.deploy.parallel.TargetOutcome> embedOutcomes,
            java.util.Set<String> restoreFailedLockNames,
            List<String[]> lockedPackages,
            List<FtpTargetSelection> allTargets) {
        if (restoreFailedLockNames.isEmpty()) return embedOutcomes;
        // 顺序对位：lockedPackages.get(i) 对应 allTargets.get(i)（preLockAll 保证）
        java.util.Set<String> failedRelativePaths = new java.util.LinkedHashSet<>();
        int n = Math.min(lockedPackages.size(), allTargets.size());
        for (int i = 0; i < n; i++) {
            String lockName = lockedPackages.get(i)[1];
            if (restoreFailedLockNames.contains(lockName)) {
                failedRelativePaths.add(allTargets.get(i).getRelativePath());
            }
        }
        if (failedRelativePaths.isEmpty()) return embedOutcomes;
        java.util.LinkedHashMap<String, com.flux.deploy.parallel.TargetOutcome> upgraded =
                new java.util.LinkedHashMap<>(embedOutcomes);
        for (String relPath : failedRelativePaths) {
            com.flux.deploy.parallel.TargetOutcome orig = upgraded.get(relPath);
            // embedOutcomes 仅包含嵌入目标；主目标走不同路径，不在此处理
            if (orig == null) continue;
            if (orig.getStatus() == com.flux.deploy.parallel.TargetStatus.ROLLBACK_FAILED) continue;
            Throwable err = orig.getError() != null
                    ? orig.getError()
                    : new RuntimeException("restoreLock 失败：远端孤悬锁文件，需人工干预");
            upgraded.put(relPath, com.flux.deploy.parallel.TargetOutcome.rollbackFailed(
                    relPath, err, orig.getLogSegment()));
        }
        return upgraded;
    }

    private static String lookupBackupPath(List<String[]> updatedPackages, String remotePath) {
        synchronized (updatedPackages) {
            for (String[] pair : updatedPackages) {
                if (remotePath.equals(pair[0])) {
                    return pair[1];
                }
            }
        }
        return null;
    }

    /**
     * 预备份所有目标包到共享备份目录
     *
     * <p>双路径分流：</p>
     * <ul>
     *   <li>{@code parallelism == 1} 或单目标 → 走原有串行循环（行为与改造前完全一致）</li>
     *   <li>{@code parallelism > 1} 且多目标 → 用 {@link com.flux.deploy.parallel.ParallelExecutor}
     *       并发执行，但**任一失败即整体抛异常**（备份阶段不做 isolated 单包语义，
     *       因为备份失败几乎都是基础设施问题，单包跳过没有业务意义）</li>
     * </ul>
     *
     * @param pluginConfig 部署配置
     * @param allTargets   待备份的所有目标
     * @param parallelism  备份并发数（1 = 关闭并行，走原串行路径）
     * @param host         FTP 主机
     * @param port         FTP 端口
     * @param user         FTP 用户名
     * @param pass         FTP 密码
     * @param logCallback  日志回调
     * @return 备份目录路径（用于回滚）
     * @throws Exception 任一包备份失败 → 整体抛出，调用方走"备份失败"分支
     * @author xumanyi
     * @date 2026-03-27
     */
    private static String preBackupAll(
            PluginDeployConfig pluginConfig,
            List<FtpTargetSelection> allTargets,
            int parallelism,
            int maxRetries,
            String host, int port, String user, String pass,
            Consumer<String> logCallback) throws Exception {

        String backupDir;
        try (FtpSession session = new FtpSession(host, port)) {
            session.connect(user, pass);
            FtpOperations ops = new FtpOperations(session);

            // 解析备份父目录：优先用户自定义；否则按前 3 级派生
            FtpTargetSelection firstTarget = allTargets.get(0);
            String remoteDir = firstTarget.getRemoteDir();
            String backupParent = resolveBackupRoot(pluginConfig, remoteDir);

            // 确保 backup/ 目录存在
            ops.mkdirIfAbsent(backupParent);

            // 确定备份子目录名（自动处理同名冲突）
            DateTimeFormatter dateFmt = DateTimeFormatter.ofPattern("yyyyMMdd");
            String baseName = LocalDate.now().format(dateFmt) + "_" + pluginConfig.getOperator();
            String backupDirName = resolveBackupDirName(ops, backupParent, baseName, pluginConfig);
            backupDir = backupParent + backupDirName + "/";
            ops.mkdirIfAbsent(backupDir);
            if (!baseName.equals(backupDirName)) {
                logCallback.accept("[备份] 采用新增目录策略，备份目录名: " + backupDirName);
            }

            logCallback.accept("[备份] 备份目录: " + backupDir);

            // 在并发开始前，把所有目标需要的子目录全部创建好。
            // 同一个 FTP session 内顺序 mkdir，避免并发场景下多线程同时 mkdir 相同路径
            // 导致的 HashSet 不安全 / 服务端响应码竞态。
            precreateBackupSubDirs(ops, backupDir, allTargets);
        }

        // 逐个下载目标包到备份目录。
        // 关键约束：每个文件的 download/upload/size 验证都用独立 FTP 短连接，
        // 禁止跨文件复用同一个 FtpSession——长生命周期控制通道在批量大文件传输间隙
        // 会被服务端按空闲超时关闭并返回 421。
        // 子目录已在上面预创建，循环内只做文件传输。
        boolean shouldParallelize = allTargets.size() > 1 && parallelism > 1;
        if (!shouldParallelize) {
            // 串行路径：保持原有行为，任一失败立即抛出
            for (FtpTargetSelection target : allTargets) {
                backupSingleTarget(target, backupDir, host, port, user, pass, logCallback);
            }
            return backupDir;
        }

        // 并行路径：备份阶段失败必须整体失败，因此 strategy 固定 ROLLBACK_ALL（任一失败传播取消）
        com.flux.deploy.deploy.CancellationToken.Simple parallelToken =
                new com.flux.deploy.deploy.CancellationToken.Simple();
        com.flux.deploy.parallel.ParallelExecutor.Options opts =
                new com.flux.deploy.parallel.ParallelExecutor.Options(
                        parallelism,
                        com.flux.deploy.config.FailureStrategy.ROLLBACK_ALL,
                        parallelToken,
                        "backup");
        java.util.Map<String, com.flux.deploy.parallel.TargetOutcome> outcomes =
                com.flux.deploy.parallel.ParallelExecutor.execute(
                        opts, allTargets,
                        t -> t.getRelativePath(),
                        (target, log) -> {
                            // 直接写到 logCallback 实现实时 flush；outcome.logSegment 留空避免重复
                            backupSingleTarget(target, backupDir, host, port, user, pass, logCallback);
                            return com.flux.deploy.parallel.TargetOutcome.success(
                                    target.getRelativePath(), "");
                        });

        // 并发完成后顺序 flush 单包日志，保持目标内日志连续
        for (com.flux.deploy.parallel.TargetOutcome o : outcomes.values()) {
            String seg = o.getLogSegment();
            if (!seg.isEmpty()) {
                for (String line : seg.split("\n")) {
                    if (!line.isEmpty()) logCallback.accept(line);
                }
            }
        }

        // 收集失败包列表
        java.util.LinkedHashMap<String, FtpTargetSelection> targetByKey = new java.util.LinkedHashMap<>();
        for (FtpTargetSelection t : allTargets) {
            targetByKey.put(t.getRelativePath(), t);
        }
        java.util.LinkedHashMap<String, FtpTargetSelection> failedTargets = new java.util.LinkedHashMap<>();
        for (com.flux.deploy.parallel.TargetOutcome o : outcomes.values()) {
            if (o.getStatus() != com.flux.deploy.parallel.TargetStatus.SUCCESS) {
                // AUTH 错误立即放弃（无意义重试）
                if (o.getErrorKind() == com.flux.deploy.ftp.FtpErrorKind.AUTH) {
                    Throwable rootCause = o.getError();
                    String reason = rootCause != null && rootCause.getMessage() != null
                            ? rootCause.getMessage() : "认证失败";
                    throw new Exception("备份失败: " + o.getTargetKey()
                            + " - " + reason + "（认证错误不重试）", rootCause);
                }
                FtpTargetSelection target = targetByKey.get(o.getTargetKey());
                if (target != null) {
                    failedTargets.put(o.getTargetKey(), target);
                }
            }
        }

        // 串行重试失败包
        if (!failedTargets.isEmpty() && maxRetries > 0) {
            retryFailedBackupSerially(failedTargets, backupDir, maxRetries,
                    host, port, user, pass, logCallback);
        }

        // 重试后仍失败 → 整体抛异常
        if (!failedTargets.isEmpty()) {
            FtpTargetSelection firstFailed = failedTargets.values().iterator().next();
            com.flux.deploy.parallel.TargetOutcome origOutcome = outcomes.get(firstFailed.getRelativePath());
            Throwable rootCause = origOutcome != null ? origOutcome.getError() : null;
            String reason = rootCause != null && rootCause.getMessage() != null
                    ? rootCause.getMessage() : "重试 " + maxRetries + " 次后仍失败";
            throw new Exception("备份失败: " + firstFailed.getRelativePath() + " - " + reason, rootCause);
        }

        return backupDir;
    }

    /**
     * 备份阶段失败包的串行重试
     *
     * <p>对失败包逐个调用 {@link #backupSingleTarget} 重试。重试成功的从 failedTargets 中移除。
     * 重试间 2 秒延迟，给 FTP 服务器缓冲。</p>
     *
     * <p>与嵌入阶段重试的差异：备份阶段没有 ISOLATED 语义，重试仍失败将由调用方抛异常导致整体失败。</p>
     *
     * @param failedTargets 待重试的目标（map 由本方法直接修改：成功的会被移除）
     * @param backupDir     备份根目录（含尾部 /）
     * @param maxRetries    最大重试次数
     * @param host          FTP 主机
     * @param port          FTP 端口
     * @param user          FTP 用户名
     * @param pass          FTP 密码
     * @param logCallback   日志回调
     * @author xumanyi
     * @date 2026-05-02
     */
    private static void retryFailedBackupSerially(
            java.util.LinkedHashMap<String, FtpTargetSelection> failedTargets,
            String backupDir,
            int maxRetries,
            String host, int port, String user, String pass,
            Consumer<String> logCallback) {
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            if (failedTargets.isEmpty()) break;

            logCallback.accept("\n━━ 重试备份失败包 (第 " + attempt + " 轮，"
                    + failedTargets.size() + " 个) ━━");

            java.util.Iterator<java.util.Map.Entry<String, FtpTargetSelection>> it =
                    failedTargets.entrySet().iterator();
            while (it.hasNext()) {
                if (currentCancelMode != CancelMode.NONE) {
                    logCallback.accept("[重试] 用户已取消，跳过剩余 " + failedTargets.size() + " 个包");
                    return;
                }

                FtpTargetSelection target = it.next().getValue();
                logCallback.accept("INFO  [备份] 重试 " + target.getTargetName());
                try {
                    backupSingleTarget(target, backupDir, host, port, user, pass, logCallback);
                    it.remove();
                    logCallback.accept("INFO  [备份] 重试成功 " + target.getTargetName());
                } catch (Exception e) {
                    com.flux.deploy.ftp.FtpErrorKind kind =
                            com.flux.deploy.ftp.FtpErrorClassifier.classify(e);
                    String reason = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                    if (kind == com.flux.deploy.ftp.FtpErrorKind.AUTH) {
                        // AUTH 错误立即放弃，让上层抛失败
                        logCallback.accept("ERROR [备份] " + target.getTargetName()
                                + " 重试遇到认证失败，放弃，原因：" + reason);
                        return;
                    }
                    if (attempt >= maxRetries) {
                        // 最后一轮失败：明确告知"已达最大重试次数"
                        logCallback.accept("ERROR [备份] " + target.getTargetName()
                                + " 已达最大备份重试次数 " + maxRetries + "，最终标记为失败，原因：" + reason);
                    } else {
                        logCallback.accept("WARN  [备份] " + target.getTargetName()
                                + " 第 " + attempt + " 次备份重试失败，将进入下一轮，原因：" + reason);
                    }
                }

                // 重试间 2 秒延迟，给 FTP 服务器缓冲
                if (it.hasNext() || attempt < maxRetries) {
                    try {
                        Thread.sleep(2000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        }
    }

    /**
     * 在主线程串行预创建所有备份子目录
     *
     * <p>把"目录管理"和"文件传输"在阶段上分离：</p>
     * <ul>
     *   <li>避免并发备份时多个 worker 线程同时 mkdir 相同路径产生的服务端响应码竞态</li>
     *   <li>避免在 worker 线程里维护非线程安全的 HashSet 来去重</li>
     * </ul>
     *
     * @param ops        已建连接的 FTP 操作（短连接复用，调用方负责关闭）
     * @param backupDir  备份根目录（含尾部 /）
     * @param allTargets 全部目标列表
     * @throws IOException 创建目录失败
     * @author xumanyi
     * @date 2026-05-02
     */
    private static void precreateBackupSubDirs(FtpOperations ops,
                                                 String backupDir,
                                                 List<FtpTargetSelection> allTargets) throws java.io.IOException {
        Set<String> created = new HashSet<>();
        for (FtpTargetSelection target : allTargets) {
            String subDir = backupSubDirFor(target);
            if (!subDir.isEmpty() && created.add(subDir)) {
                ops.mkdirIfAbsent(backupDir + subDir);
            }
        }
    }

    /**
     * 备份单个目标包到备份目录
     *
     * <p>独立短连接：下载用一个 session，上传 + 校验大小用一个 session，
     * 两次 session 各自 try-with-resources。失败时抛 Exception 由调用方处理。</p>
     *
     * @param target      待备份的目标
     * @param backupDir   备份根目录（含尾部 /；子目录已预先创建）
     * @param host        FTP 主机
     * @param port        FTP 端口
     * @param user        FTP 用户名
     * @param pass        FTP 密码
     * @param logCallback 日志回调（线程安全要求由调用方保证）
     * @throws Exception 任意 IO / 校验失败
     * @author xumanyi
     * @date 2026-05-02
     */
    private static void backupSingleTarget(FtpTargetSelection target,
                                             String backupDir,
                                             String host, int port, String user, String pass,
                                             Consumer<String> logCallback) throws Exception {
        String remotePath = target.getRemoteDir() + target.getRelativePath();
        String subDir = backupSubDirFor(target);
        String backupFilePath = backupDir + subDir + target.getTargetName();
        String displayName = (subDir.isEmpty() ? "" : subDir) + target.getTargetName();

        logCallback.accept("[备份] " + displayName + " ...");

        // 备份阶段下载到本地的 temp 文件不再立刻删除：登记到 backupLocalCopies 后由
        // 嵌入阶段直接复用，跳过一次相同字节的远端下载（"1 下 + 2 上"优化）。
        // 本方法只在异常路径下负责删 temp；成功路径上 temp 的所有权移交给入口 finally 统一清理。
        Path tempBackup = Files.createTempFile("backup-", "-" + target.getTargetName());
        boolean handedOff = false;
        try {
            runFreshFtpSession(host, port, user, pass,
                    (s, ops) -> ops.download(remotePath, tempBackup));

            long downloadedSize = Files.size(tempBackup);
            if (downloadedSize == 0) {
                throw new Exception("下载的备份文件为空: " + remotePath);
            }

            long backupSize = withFreshFtpSession(host, port, user, pass, (s, ops) -> {
                ops.upload(tempBackup, backupFilePath);
                return ops.getFileSize(backupFilePath);
            });

            if (backupSize != downloadedSize) {
                throw new Exception("备份大小不一致: " + target.getTargetName()
                        + " (下载 " + downloadedSize + " 字节, 备份 " + backupSize + " 字节)");
            }

            // 登记本地副本：以 relativePath 为 key（嵌入阶段 / 重试 / executeWarEmbed 都用同一 key 取）
            // 重试场景下同 key 可能多次进入：旧副本先删再覆盖，避免 temp 残留
            Path prev = backupLocalCopies.put(target.getRelativePath(), tempBackup);
            if (prev != null) {
                try { Files.deleteIfExists(prev); } catch (Exception ignored) {}
            }
            handedOff = true;

            logCallback.accept("[备份] " + displayName
                    + " -> " + backupFilePath + " (" + formatSize(backupSize) + ")");
        } finally {
            // 异常路径或未登记成功 → 删除 temp，避免泄漏
            if (!handedOff) {
                try { Files.deleteIfExists(tempBackup); } catch (Exception ignored) {}
            }
        }
    }

    /**
     * 回滚所有已更新的包，并删除备份目录
     */
    /**
     * 回滚所有已更新的包
     *
     * @param borrowed 是否为借用已有备份（USE_EXISTING 策略下为 true）。
     *                 true 时只恢复远端文件，不删除备份（老备份不是本次创建的，须保留）。
     */
    private static void rollbackAll(
            String backupDir,
            List<String[]> updatedPackages,
            String host, int port, String user, String pass,
            Consumer<String> logCallback, boolean borrowed) {

        // 恢复每个已更新的包：逐文件独立短连接，
        // 同 preBackupAll 一致，避免长会话在多包数据传输期间被服务端 421。
        for (String[] pair : updatedPackages) {
            final String remotePath = pair[0];
            final String backupFilePath = pair[1];
            try {
                Path tempRestore = Files.createTempFile("restore-", ".tmp");
                try {
                    runFreshFtpSession(host, port, user, pass, (s, ops) -> {
                        ops.download(backupFilePath, tempRestore);
                        ops.upload(tempRestore, remotePath);
                    });
                    logCallback.accept("INFO  [回滚] 已恢复: " + remotePath);
                } finally {
                    Files.deleteIfExists(tempRestore);
                }
            } catch (Exception e) {
                logCallback.accept("INFO  [回滚] 恢复失败: " + remotePath + " - " + e.getMessage());
            }
        }

        if (borrowed) {
            logCallback.accept("INFO  [回滚] 借用已有备份作为回滚源，本次保留备份文件不做清理");
            return;
        }

        // 清理阶段全是命令操作（delete / listFiles / removeDirectory），无数据传输，
        // 控制通道始终在用，复用一个短连接是安全的。
        try {
            runFreshFtpSession(host, port, user, pass, (s, ops) -> {
                // 仅删除本次备份的文件（不影响同目录下其他包的备份）
                for (String[] pair : updatedPackages) {
                    String backupFilePath = pair[1];
                    try {
                        ops.delete(backupFilePath);
                        logCallback.accept("INFO  [回滚] 已删除备份: " + backupFilePath);
                    } catch (Exception ignored) {
                        // 备份文件可能不存在（备份阶段失败时），忽略
                    }
                }
                // 递归清理空子目录（深层结构：{backupDir}/{nested}/{subdir}/X.jar）
                cleanEmptySubDirs(ops, s.getClient(), backupDir, logCallback);

                // 检查备份目录是否为空，空则删除目录
                List<FTPFile> remaining = ops.listFiles(backupDir);
                if (remaining.isEmpty()) {
                    s.getClient().removeDirectory(backupDir);
                    logCallback.accept("INFO  [回滚] 备份目录已空，已删除: " + backupDir);
                } else {
                    logCallback.accept("INFO  [回滚] 备份目录中仍有 " + remaining.size()
                            + " 个其他条目，保留目录: " + backupDir);
                }
            });
        } catch (Exception e) {
            logCallback.accept("INFO  [回滚] 清理备份失败: " + e.getMessage());
        }
    }

    /**
     * 兼容旧调用签名（默认非借用）
     */
    private static void rollbackAll(
            String backupDir,
            List<String[]> updatedPackages,
            String host, int port, String user, String pass,
            Consumer<String> logCallback) {
        rollbackAll(backupDir, updatedPackages, host, port, user, pass, logCallback, false);
    }

    /**
     * 为所有目标更新版本记录（NoteGate 等价逻辑）
     *
     * <p>ISOLATED 模式部分失败时：失败包远端已经被解锁阶段 restoreLock 还原为原 WAR，
     * 实际产物没有变更；如果仍给它们追加"取包/传包"会与远端事实不一致，运维对账抓瞎。
     * 通过 {@code skippedRelativePaths} 把失败包的 relativePath 排除掉，note 只追加给真实成功的目标。</p>
     *
     * @param skippedRelativePaths 不写 note 的目标 relativePath 集合（可为 null/空，表示全量写入）
     * @author xumanyi
     * @date 2026-05-08
     */
    private static void updateNoteForAll(
            PluginDeployConfig pluginConfig,
            List<FtpTargetSelection> allTargets,
            java.util.Set<String> skippedRelativePaths,
            String host, int port, String user, String pass,
            Consumer<String> logCallback) throws Exception {

        java.time.format.DateTimeFormatter timeFmt =
                java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd HH:mm");
        String now = java.time.LocalDateTime.now().format(timeFmt);

        // 每个 note 文件用独立短连接（exists/download/upload 三次操作绑在一个会话里），
        // 避免共用一个长会话在多包遍历期间被服务端 421。
        for (FtpTargetSelection target : allTargets) {
            // ISOLATED 失败包跳过：远端已 restoreLock 回原 WAR，写 note 会产生"取包/传包"幻象记录
            if (skippedRelativePaths != null
                    && skippedRelativePaths.contains(target.getRelativePath())) {
                logCallback.accept("INFO  [说明] 跳过 " + target.getTargetName()
                        + "（该包本次未成功更新，不追加版本记录）");
                continue;
            }
            String remoteDir = target.getRemoteDir();
            // 解析实际的包所在目录（考虑 relativePath 中的子目录）
            String relPath = target.getRelativePath();
            int lastSlash = relPath.lastIndexOf('/');
            String packageDir = lastSlash > 0
                    ? remoteDir + relPath.substring(0, lastSlash + 1)
                    : remoteDir;

            // 命名兼容：canonical 为复数 <全名>_update_notes.txt；扫描包所在目录、按
            // NoteFileNames.isNoteCandidate 谓词筛选候选 → pickPrimary 选目标 → 原地追加。
            // 不做合并/迁移/删除，已有文件名一律保留。
            final String canonicalNoteName = NoteFileNames.canonicalName(target.getTargetName());
            final String canonicalNotePath = packageDir + canonicalNoteName;
            final String packageNameForMatch = target.getTargetName();
            final String projectRootForSearch = target.getRemoteDir();

            Path tempNote = Files.createTempFile("note-", ".txt");
            try {
                // 构造新记录（标准格式，标签 + 中文冒号 + 值，字段间空格分隔）：
                //   取包时间：yyyyMMdd HH:mm 开发：{开发} 任务：{任务描述} 客服：{客服号} 包名称：{包名}
                //   传包时间：...（同上）
                String operator = nullToEmpty(pluginConfig.getOperator());
                String taskId = nullToEmpty(pluginConfig.getTaskId());
                String customerId = nullToEmpty(pluginConfig.getCustomerId());
                final String fetchRecord = "取包时间：" + now
                        + " 开发：" + operator
                        + " 任务：" + taskId
                        + " 客服：" + customerId
                        + " 包名称：" + target.getTargetName();
                final String uploadRecord = "传包时间：" + now
                        + " 开发：" + operator
                        + " 任务：" + taskId
                        + " 客服：" + customerId
                        + " 包名称：" + target.getTargetName();

                final String[] resolvedName = new String[]{canonicalNoteName};
                runFreshFtpSession(host, port, user, pass, (s, ops) -> {
                    // 1. 扫描包所在目录，按 NoteFileNames.isNoteCandidate 谓词筛选所有候选
                    java.util.List<FTPFile> dirListing = ops.listFiles(
                            stripTrailingSlashForList(packageDir));
                    java.util.List<RemoteNoteEntry> candidates = new java.util.ArrayList<>();
                    for (FTPFile entry : dirListing) {
                        if (entry == null || !entry.isFile()) continue;
                        String fname = entry.getName();
                        if (!NoteFileNames.isNoteCandidate(packageNameForMatch, fname)) continue;
                        String fpath = packageDir + fname;
                        candidates.add(new RemoteNoteEntry(
                                fname, fpath, downloadNoteString(ops, fpath)));
                    }

                    // 2. 选目标文件：优先 canonical 精确匹配，否则字节最多者；
                    //    都没有就按目录里别的包 .txt 的命名风格推一个新文件名。
                    RemoteNoteEntry primary = pickPrimaryEntry(candidates, canonicalNoteName);
                    String writeName;
                    String writePath;
                    String baseContent;
                    if (primary == null) {
                        java.util.List<String> dirFileNames = new java.util.ArrayList<>();
                        for (FTPFile e : dirListing) {
                            if (e != null && e.isFile()) dirFileNames.add(e.getName());
                        }
                        String inferred = NoteFileNames.inferNoteFilenameForNewPackage(
                                packageNameForMatch, dirFileNames);
                        // 当前目录推不出 → 向上最多 3 层（且不超过 remoteDir）找模板
                        if (inferred == null) {
                            inferred = findTemplateUpward(ops, packageDir,
                                    projectRootForSearch, packageNameForMatch, logCallback);
                        }
                        writeName = (inferred != null) ? inferred : canonicalNoteName;
                        writePath = packageDir + writeName;
                        baseContent = "";
                        if (inferred != null && !inferred.equals(canonicalNoteName)) {
                            logCallback.accept("[说明] 目录里无当前包 note，参考已有 .txt 命名风格新建: "
                                    + writeName);
                        } else if (inferred == null) {
                            logCallback.accept("[说明] 当前目录及向上 3 层都未找到模板，使用默认命名: "
                                    + canonicalNoteName);
                        }
                    } else {
                        writeName = primary.name;
                        writePath = primary.path;
                        baseContent = primary.content;
                        if (candidates.size() > 1) {
                            logCallback.accept("[说明] 检测到 " + candidates.size()
                                    + " 个匹配文件，原地追加到字节最多的: " + writeName);
                        }
                    }
                    resolvedName[0] = writeName;

                    // 3. 拼接最终内容
                    StringBuilder sb = new StringBuilder(baseContent);
                    if (!baseContent.isEmpty()) {
                        if (!baseContent.endsWith("\n")) {
                            sb.append("\n");
                        }
                        sb.append("\n");
                    }
                    sb.append(fetchRecord).append("\n");
                    sb.append(uploadRecord).append("\n");
                    String finalContent = sb.toString();

                    // 4. 上传到 writePath（原地覆盖，或新建 canonical）
                    Files.writeString(tempNote, finalContent,
                            java.nio.charset.StandardCharsets.UTF_8);
                    long expectedBytes = Files.size(tempNote);
                    ops.upload(tempNote, writePath);
                    logCallback.accept("[说明] 已上传 " + writeName
                            + " (" + expectedBytes + "B)");
                });

                logCallback.accept("[说明] " + resolvedName[0] + " 已追加 2 条记录");
            } finally {
                Files.deleteIfExists(tempNote);
            }
        }
    }

    /**
     * 在当前目录推不出模板时，向上最多 3 层目录搜模板，逐层扫"该层目录直接文件 + 该层每个子目录"。
     * 不超过 {@code projectRoot}（target.remoteDir，FTP 上的项目根）。命中即返回，未命中返回 null。
     *
     * @param ops              当前 FTP 会话
     * @param packageDir       包所在目录（带末尾 /）
     * @param projectRoot      项目根（带末尾 /），向上搜的天花板
     * @param currentPkgFullName 当前包全名（用于 stem 排除）
     * @param logCallback      日志回调
     * @return 推断出的新文件名；未命中返回 null
     * @throws java.io.IOException FTP list 失败
     * @author xumanyi
     * @date 2026-05-12
     */
    private static String findTemplateUpward(
            FtpOperations ops,
            String packageDir,
            String projectRoot,
            String currentPkgFullName,
            Consumer<String> logCallback) throws java.io.IOException {
        String dir = stripTrailingSlashForList(packageDir);
        String ceiling = stripTrailingSlashForList(projectRoot);
        for (int level = 1; level <= 3; level++) {
            int slash = dir.lastIndexOf('/');
            if (slash < 0) break;
            String parent = dir.substring(0, slash);
            // 不超过项目根
            if (parent.length() < ceiling.length()) break;
            if (!parent.equals(ceiling)
                    && (!parent.startsWith(ceiling)
                            || parent.charAt(ceiling.length()) != '/')) break;
            logCallback.accept("[说明] 当前层无模板，向上扫第 " + level + " 层: " + parent);
            java.util.List<FTPFile> listing = ops.listFiles(parent);
            // 先扫该层目录下直接的文件
            java.util.List<String> directFiles = new java.util.ArrayList<>();
            for (FTPFile f : listing) {
                if (f != null && f.isFile()) directFiles.add(f.getName());
            }
            String inferred = NoteFileNames.inferNoteFilenameForNewPackage(
                    currentPkgFullName, directFiles);
            if (inferred != null) {
                logCallback.accept("[说明] 在 " + parent + " 找到模板");
                return inferred;
            }
            // 再扫该层每个子目录（不递归更深）
            for (FTPFile sub : listing) {
                if (sub == null || !sub.isDirectory()) continue;
                String subName = sub.getName();
                if (".".equals(subName) || "..".equals(subName)) continue;
                String subPath = parent + "/" + subName;
                if (subPath.equals(dir)) continue; // 已经从这扫出来过
                java.util.List<FTPFile> subListing = ops.listFiles(subPath);
                java.util.List<String> subFiles = new java.util.ArrayList<>();
                for (FTPFile f : subListing) {
                    if (f != null && f.isFile()) subFiles.add(f.getName());
                }
                inferred = NoteFileNames.inferNoteFilenameForNewPackage(
                        currentPkgFullName, subFiles);
                if (inferred != null) {
                    logCallback.accept("[说明] 在 " + subPath + " 找到模板");
                    return inferred;
                }
            }
            dir = parent;
            if (parent.equals(ceiling)) break; // 已经扫到项目根，停
        }
        return null;
    }

    /**
     * 从候选文件里选目标：优先精确匹配 canonical；否则字节最多者；候选为空返回 null。
     *
     * @param candidates 命中谓词的所有候选
     * @param canonicalName canonical 文件名
     * @return 选中的目标，或 null
     * @author xumanyi
     * @date 2026-05-11
     */
    private static RemoteNoteEntry pickPrimaryEntry(
            java.util.List<RemoteNoteEntry> candidates, String canonicalName) {
        if (candidates.isEmpty()) return null;
        for (RemoteNoteEntry rn : candidates) {
            if (rn.name.equals(canonicalName)) return rn;
        }
        RemoteNoteEntry largest = candidates.get(0);
        for (int i = 1; i < candidates.size(); i++) {
            RemoteNoteEntry c = candidates.get(i);
            int cmp = Long.compare(
                    c.content.getBytes(java.nio.charset.StandardCharsets.UTF_8).length,
                    largest.content.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
            if (cmp > 0 || (cmp == 0 && c.name.compareTo(largest.name) < 0)) {
                largest = c;
            }
        }
        return largest;
    }

    /**
     * 去掉路径末尾的 /，根目录 {@code "/"} 保留不变（FTP listFiles 不接受空路径）。
     *
     * @param path 原始路径
     * @return 去掉末尾 / 的路径
     * @author xumanyi
     * @date 2026-05-11
     */
    private static String stripTrailingSlashForList(String path) {
        if (path.length() > 1 && path.endsWith("/")) {
            return path.substring(0, path.length() - 1);
        }
        return path;
    }

    /**
     * 下载远端 note 文件并以 UTF-8 字符串形式返回。临时文件用后即删。
     *
     * @param ops        当前 FTP 会话
     * @param remotePath 远端文件绝对路径
     * @return UTF-8 解码后的文本内容
     * @throws java.io.IOException 下载或读取失败
     * @author xumanyi
     * @date 2026-05-11
     */
    private static String downloadNoteString(FtpOperations ops, String remotePath)
            throws java.io.IOException {
        Path tmp = Files.createTempFile("note-dl-", ".txt");
        try {
            ops.download(remotePath, tmp);
            return Files.readString(tmp, java.nio.charset.StandardCharsets.UTF_8);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    /** 远端一份现存的 note 文件（pickPrimaryEntry 候选）。 */
    private static final class RemoteNoteEntry {
        final String name;
        final String path;
        final String content;

        RemoteNoteEntry(String name, String path, String content) {
            this.name = name;
            this.path = path;
            this.content = content;
        }
    }

    /**
     * 预加锁所有目标包
     *
     * <p>对每个目标包执行 rename 加锁，防止并发修改。</p>
     */
    private static void preLockAll(List<FtpTargetSelection> allTargets, String operator,
                                    String host, int port, String user, String pass,
                                    Consumer<String> logCallback,
                                    List<String[]> lockedPackages) throws Exception {
        try (FtpSession session = new FtpSession(host, port)) {
            session.connect(user, pass);
            FtpOperations ops = new FtpOperations(session);
            com.flux.deploy.ftp.FtpLock ftpLock = new com.flux.deploy.ftp.FtpLock(ops);

            for (FtpTargetSelection target : allTargets) {
                String remotePath = target.getRemoteDir() + target.getRelativePath();
                String remoteDir = remotePath.substring(0, remotePath.lastIndexOf('/') + 1);
                String packageName = target.getTargetName();

                String lockName = ftpLock.acquireLock(remoteDir, packageName, operator);
                lockedPackages.add(new String[]{remoteDir, lockName});
                logCallback.accept("[加锁] " + packageName + " 已加锁");
            }
        }
    }

    /**
     * 解锁所有已加锁的包
     */
    private static java.util.Set<String> preUnlockAll(List<String[]> lockedPackages,
                                      String host, int port, String user, String pass,
                                      Consumer<String> logCallback) {
        // 返回值：restoreLock 失败的 lockName 集合
        // 上层用它把对应 target 的 outcome 升级为 ROLLBACK_FAILED，
        // 因为这种情况下远端只剩一个孤悬的锁文件，没有原 WAR——必须人工干预。
        java.util.Set<String> restoreFailedLockNames = new java.util.LinkedHashSet<>();
        if (lockedPackages.isEmpty()) return restoreFailedLockNames;
        try (FtpSession session = new FtpSession(host, port)) {
            session.connect(user, pass);
            FtpOperations ops = new FtpOperations(session);
            com.flux.deploy.ftp.FtpLock ftpLock = new com.flux.deploy.ftp.FtpLock(ops);

            for (String[] pair : lockedPackages) {
                String remoteDir = pair[0];
                String lockName = pair[1];
                String origName = com.flux.deploy.ftp.FtpLock.extractOriginalName(lockName);
                String dirSlash = remoteDir.endsWith("/") ? remoteDir : remoteDir + "/";
                String origPath = origName == null ? null : dirSlash + origName;
                // 标记本轮是否走了"原文件缺失 → restoreLock"分支：
                // 走了就是 ROLLBACK 路径，失败必须升级 outcome；走 releaseLock 失败属于"已成功上传后清理失败"
                // （远端 WAR 是新版且独立存在），不影响业务，无需升级。
                boolean attemptedRestore = false;
                try {
                    // 清理 UploadGate 上传中断遗留的 .__UPLOADING__ 临时文件，避免堆积
                    if (origName != null) {
                        String tmpUploading = dirSlash + origName + ".__UPLOADING__";
                        if (ops.exists(tmpUploading)) {
                            try { ops.delete(tmpUploading); } catch (Exception ignored) {}
                            logCallback.accept("[解锁] 清理上传中断的临时文件: "
                                    + origName + ".__UPLOADING__");
                        }
                    }
                    // 数据安全：releaseLock 是 delete 操作。如果此刻原文件名在 FTP 上不存在，
                    // 锁文件就是该包当前的唯一副本——直接 delete 会造成不可恢复的数据丢失。
                    // 这种情况会发生在：pipeline 上传中途被取消 / 失败但未触发 rollbackAll
                    // / KEEP_SUCCEEDED 模式下未上传成功的目标。
                    // 此时必须 rename(锁文件 → 原名) 以恢复原始文件。
                    boolean origExists = origPath != null && ops.exists(origPath);
                    if (origExists) {
                        ftpLock.releaseLock(remoteDir, lockName);
                        logCallback.accept("[解锁] " + (origName != null ? origName
                                : lockName.split("__LOCK__")[0]) + " 已解锁");
                    } else {
                        // 防止数据丢失：锁文件就是原始内容，rename 回原名
                        attemptedRestore = true;
                        ftpLock.restoreLock(remoteDir, lockName);
                        logCallback.accept("[解锁] " + (origName != null ? origName : lockName)
                                + " 已从锁文件还原原 WAR");
                    }
                } catch (Exception e) {
                    if (attemptedRestore) {
                        // 关键失败：远端 = 孤悬锁文件，没有原 WAR —— 必须升级为 ROLLBACK_FAILED
                        restoreFailedLockNames.add(lockName);
                        logCallback.accept("ERROR [解锁] " + (origName != null ? origName : lockName)
                                + " 锁文件恢复失败：" + e.getMessage()
                                + "（远端目前只有锁文件，没有原 WAR，需要人工从备份目录恢复）");
                    } else {
                        logCallback.accept("[解锁] 解锁失败: " + lockName + " - " + e.getMessage()
                                + "（请人工检查 FTP 上 " + (origName != null ? origName : "原文件")
                                + " 是否存在；如缺失，可从备份目录手动恢复）");
                    }
                }
            }
        } catch (Exception e) {
            logCallback.accept("[解锁] FTP 连接失败: " + e.getMessage());
        }
        return restoreFailedLockNames;
    }

    /**
     * 回滚版本记录：删除每个包 note 文件的最后 2 行（取包+传包记录）
     */
    private static void rollbackNotes(List<FtpTargetSelection> allTargets,
                                       String host, int port, String user, String pass,
                                       Consumer<String> logCallback) {
        // 每个 note 文件独立短连接，与 updateNoteForAll 一致，避免长会话被服务端 421。
        for (FtpTargetSelection target : allTargets) {
            String remoteDir = target.getRemoteDir();
            String relPath = target.getRelativePath();
            int lastSlash = relPath.lastIndexOf('/');
            String packageDir = lastSlash > 0
                    ? remoteDir + relPath.substring(0, lastSlash + 1)
                    : remoteDir;

            // 命名兼容：deploy 是"原地追加到选中的文件"，回滚要找回同一个被选中的文件。
            // 用与 updateNoteForAll 完全一致的 pickPrimaryEntry 逻辑（优先 canonical 精确名，
            // 否则字节最多者）从目录扫描结果里挑出 note 文件再 trim 最后 2 行。
            final String canonicalNoteName = NoteFileNames.canonicalName(target.getTargetName());
            final String packageNameForMatch = target.getTargetName();

            // 用于异常日志的文件名（首选标准名，待会话里解析出实际名再覆盖）
            final String[] resolvedName = new String[]{canonicalNoteName};
            try {
                Path tempNote = Files.createTempFile("note-rollback-", ".txt");
                // action[0]: null=未修改; "delete"=note 文件本次部署首次创建，已整体删除; "trim"=已截除最后 2 条记录
                final String[] action = new String[]{null};
                final String[] resolvedPath = new String[]{null};
                try {
                    boolean modified = withFreshFtpSession(host, port, user, pass, (s, ops) -> {
                        // 用和 updateNoteForAll 一致的 pickPrimaryEntry 找回被写入的文件
                        java.util.List<FTPFile> dirListing = ops.listFiles(
                                stripTrailingSlashForList(packageDir));
                        java.util.List<RemoteNoteEntry> candidates = new java.util.ArrayList<>();
                        for (FTPFile entry : dirListing) {
                            if (entry == null || !entry.isFile()) continue;
                            String fname = entry.getName();
                            if (!NoteFileNames.isNoteCandidate(packageNameForMatch, fname)) continue;
                            String fpath = packageDir + fname;
                            candidates.add(new RemoteNoteEntry(
                                    fname, fpath, downloadNoteString(ops, fpath)));
                        }
                        RemoteNoteEntry primary = pickPrimaryEntry(candidates, canonicalNoteName);
                        if (primary == null) {
                            return Boolean.FALSE;
                        }
                        String notePath = primary.path;
                        resolvedName[0] = primary.name;
                        resolvedPath[0] = notePath;
                        ops.download(notePath, tempNote);
                        String content = Files.readString(tempNote, java.nio.charset.StandardCharsets.UTF_8);

                        // 删除末尾的空行 + 最后 2 条记录（取包+传包）
                        String[] lines = content.split("\n", -1);
                        // 从末尾去掉空行
                        int end = lines.length;
                        while (end > 0 && lines[end - 1].trim().isEmpty()) {
                            end--;
                        }
                        // 去掉最后 2 行（取包 + 传包）
                        int newEnd = Math.max(0, end - 2);
                        // 再去掉分隔空行
                        while (newEnd > 0 && lines[newEnd - 1].trim().isEmpty()) {
                            newEnd--;
                        }

                        if (newEnd == 0) {
                            // 截除后无任何历史内容，说明本次部署是该 note 文件的首次创建，
                            // 回滚时应整体删除文件，而非保留 0 字节空文件。
                            ops.delete(notePath);
                            action[0] = "delete";
                            return Boolean.TRUE;
                        }

                        StringBuilder sb = new StringBuilder();
                        for (int i = 0; i < newEnd; i++) {
                            sb.append(lines[i]).append("\n");
                        }

                        Files.writeString(tempNote, sb.toString(), java.nio.charset.StandardCharsets.UTF_8);
                        ops.upload(tempNote, notePath);
                        action[0] = "trim";
                        return Boolean.TRUE;
                    });
                    if (modified) {
                        if ("delete".equals(action[0])) {
                            logCallback.accept("INFO  [回滚] " + resolvedName[0] + " 已删除（本次部署首次创建）");
                        } else {
                            logCallback.accept("INFO  [回滚] " + resolvedName[0] + " 已移除最后 2 条记录");
                        }
                    }
                } finally {
                    Files.deleteIfExists(tempNote);
                }
            } catch (Exception e) {
                logCallback.accept("INFO  [回滚] " + resolvedName[0] + " 回滚失败: " + e.getMessage());
            }
        }
    }

    /**
     * 从 remoteDir 解析子系统根目录（第 3 级）
     */
    /**
     * 检查指定目标的备份是否已经存在
     *
     * <p>备份路径规则：{@code {systemRoot}/backup/yyyyMMdd_{operator}/{targetName}}。
     * 若今日已有该开发人员对同一目标的备份，再次备份会覆盖，原远端包会永久丢失。</p>
     *
     * <p>本方法以当前日期和给定 operator 计算路径并调用 FTP {@code exists} 做单次判断，
     * 调用者应在后台线程中执行以避免阻塞 UI。</p>
     *
     * @param host     FTP 主机
     * @param port     FTP 端口
     * @param user     FTP 用户名
     * @param pass     FTP 密码
     * @param operator 开发人员名字（备份子目录命名用）
     * @param targets  所有待备份的目标（主 + 嵌入）
     * @return 已存在备份的目标名列表；无冲突时返回空列表
     * @throws java.io.IOException FTP 连接或查询失败
     * @author xumanyi
     * @date 2026-04-17
     */
    public static List<String> detectExistingBackups(
            String host, int port, String user, String pass,
            String operator, List<FtpTargetSelection> targets) throws java.io.IOException {
        return detectExistingBackups(host, port, user, pass, operator, targets, null);
    }

    /**
     * 同上，带日志回调，便于在 UI 侧看到每一条备份路径的查询结果
     */
    public static List<String> detectExistingBackups(
            String host, int port, String user, String pass,
            String operator, List<FtpTargetSelection> targets,
            Consumer<String> logCallback) throws java.io.IOException {
        return detectExistingBackups(host, port, user, pass, operator, targets, null, logCallback);
    }

    /**
     * 同 {@link #detectExistingBackups(String, int, String, String, String, List, Consumer)}，
     * 但允许传入 customBackupRoot 覆盖默认备份父目录。
     *
     * @param customBackupRoot 用户自定义备份根；null/空则走默认派生
     * @param logCallback      日志回调；可为 null
     * @return 已存在备份的目标名列表；无冲突时返回空列表
     * @throws java.io.IOException FTP 连接或查询失败
     * @author xumanyi
     * @date 2026-05-02
     */
    public static List<String> detectExistingBackups(
            String host, int port, String user, String pass,
            String operator, List<FtpTargetSelection> targets,
            String customBackupRoot,
            Consumer<String> logCallback) throws java.io.IOException {
        List<String> conflicts = new ArrayList<>();
        if (targets == null || targets.isEmpty()) {
            if (logCallback != null) logCallback.accept("[备份检查] 无目标，跳过");
            return conflicts;
        }
        if (operator == null || operator.isEmpty()) {
            if (logCallback != null) logCallback.accept("[备份检查] 开发人字段为空，跳过检查（建议填写开发以启用冲突检测）");
            return conflicts;
        }

        try (FtpSession session = new FtpSession(host, port)) {
            session.connect(user, pass);
            FtpOperations ops = new FtpOperations(session);

            DateTimeFormatter dateFmt = DateTimeFormatter.ofPattern("yyyyMMdd");
            String dateStr = LocalDate.now().format(dateFmt);

            for (FtpTargetSelection t : targets) {
                String backupParent;
                if (customBackupRoot != null && !customBackupRoot.isBlank()) {
                    backupParent = customBackupRoot.endsWith("/") ? customBackupRoot : customBackupRoot + "/";
                } else {
                    backupParent = resolveSystemRoot(t.getRemoteDir()) + "backup/";
                }
                String subDir = backupSubDirFor(t);
                String backupPath = backupParent + dateStr + "_" + operator
                        + "/" + subDir + t.getTargetName();
                boolean exists = ops.exists(backupPath);
                if (logCallback != null) {
                    logCallback.accept((exists ? "INFO  [备份] 已存在 " : "INFO  [备份] 不存在 ") + backupPath);
                }
                if (exists) {
                    conflicts.add((subDir.isEmpty() ? "" : subDir) + t.getTargetName());
                }
            }
        }
        return conflicts;
    }

    /**
     * 为单个主目标准备本地文件（用于上传至该目标）
     *
     * <p>FULL + WAR：对该目标做 {@code alignWarLibs}；FULL + JAR：直接返回 target/X.jar；
     * INCREMENTAL：为该目标构建独立暂存包（下载该目标远端包、替换 class、输出）。</p>
     *
     * <p>多个主目标时，每个都调用一次以保证各自产物独立、互不干扰。</p>
     *
     * @author xumanyi
     * @date 2026-04-18
     */
    private static Path prepareLocalFileForMainTarget(
            PluginDeployConfig pluginConfig, FtpTargetSelection mainTarget,
            boolean isFull, boolean artifactIsWar,
            String host, int port, String user, String pass,
            Consumer<String> logCallback) throws Exception {
        String remotePath = mainTarget.getRemoteDir() + mainTarget.getRelativePath();
        Path freshArtifact = Path.of(pluginConfig.getModulePath(), "target",
                pluginConfig.getArtifactFileName());

        if (isFull) {
            if (artifactIsWar) {
                // WAR lib 对齐：以该目标的远端 war 为基准
                logCallback.accept("[整包] 对目标 " + mainTarget.getRelativePath()
                        + " 做 WAR lib 对齐...");
                Path aligned = StagingPackageBuilder.alignWarLibs(
                        freshArtifact, host, port, user, pass, remotePath, logCallback);
                if (aligned == null || !Files.isRegularFile(aligned) || Files.size(aligned) == 0) {
                    throw new java.io.IOException("WAR lib 对齐产物为空或不存在: " + aligned);
                }
                // 同一模块多次对齐会产生同名 __aligned_X.war，需要 rename 避免后目标覆盖前目标
                Path renamed = aligned.resolveSibling("__aligned_" + sanitizeForFs(
                        mainTarget.getRelativePath()) + "_" + aligned.getFileName());
                Files.move(aligned, renamed, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                return renamed;
            }
            // FULL + JAR：直接用 target/X.jar
            return freshArtifact;
        }

        // 增量 / 自动检索：为该目标独立构建暂存包
        List<String> changedFiles = pluginConfig.getChangedFiles();
        if (changedFiles == null || changedFiles.isEmpty()) {
            throw new java.io.IOException("增量模式下未指定变更文件");
        }
        StagingPackageBuilder builder = new StagingPackageBuilder(
                pluginConfig.getModulePath(), pluginConfig.getArtifactFileName(),
                changedFiles, logCallback);
        Path staging = builder.build(host, port, user, pass, remotePath);
        if (staging == null) {
            throw new java.io.IOException("暂存包构建失败");
        }
        // 同一模块多次构建会产生同名 __staging_X.jar，需要 rename 避免后目标覆盖前目标
        Path renamed = staging.resolveSibling("__staging_" + sanitizeForFs(
                mainTarget.getRelativePath()) + "_" + staging.getFileName());
        Files.move(staging, renamed, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        return renamed;
    }

    /** 将相对路径中的分隔符等转为文件名安全形式 */
    private static String sanitizeForFs(String s) {
        if (s == null) return "";
        return s.replaceAll("[/\\\\:*?\"<>|]", "_");
    }

    /**
     * 计算目标在备份目录下的子目录（以 / 结尾，无父目录时返回空串）
     *
     * <p>例：</p>
     * <ul>
     *   <li>relativePath = {@code "shared-edp/X.jar"} → {@code "shared-edp/"}</li>
     *   <li>relativePath = {@code "X.jar"} → {@code ""}</li>
     * </ul>
     *
     * @author xumanyi
     * @date 2026-04-18
     */
    private static String backupSubDirFor(FtpTargetSelection target) {
        String rel = target.getRelativePath();
        if (rel == null) return "";
        int lastSlash = rel.lastIndexOf('/');
        if (lastSlash <= 0) return "";
        return rel.substring(0, lastSlash + 1);
    }

    private static String resolveSystemRoot(String remoteDir) {
        String trimmed = remoteDir.replaceAll("^/+", "").replaceAll("/+$", "");
        String[] parts = trimmed.split("/");
        if (parts.length >= 3) {
            return "/" + parts[0] + "/" + parts[1] + "/" + parts[2] + "/";
        }
        return "/" + trimmed + "/";
    }

    /**
     * 计算备份根目录
     *
     * <p>优先返回 {@code pluginConfig.customBackupRoot}（用户自定义）；为空时
     * 回退到 {@code resolveSystemRoot(remoteDir) + "backup/"} 默认派生路径。</p>
     *
     * <p>返回值统一以 / 结尾，便于下游拼接 {@code yyyyMMdd_{operator}/}。</p>
     *
     * @param pluginConfig 插件部署配置（取 customBackupRoot）
     * @param remoteDir    第一个目标的 remoteDir（用于默认派生 fallback）
     * @return 备份根目录（FTP 绝对路径，以 / 结尾）
     * @author xumanyi
     * @date 2026-05-02
     */
    private static String resolveBackupRoot(PluginDeployConfig pluginConfig, String remoteDir) {
        String custom = pluginConfig == null ? null : pluginConfig.getCustomBackupRoot();
        if (custom != null && !custom.isBlank()) {
            return custom.endsWith("/") ? custom : custom + "/";
        }
        return resolveSystemRoot(remoteDir) + "backup/";
    }

    /**
     * 确定备份目录名
     *
     * <p>默认行为（OVERWRITE / USE_EXISTING 策略）：直接复用当天同开发的备份目录，
     * 其中同名包会被覆盖；NEW_DIR 策略：检查 {@code backupParent} 已有目录，
     * 按 {@code _v2 / _v3 / ...} 递增后缀产出不冲突的新目录名。</p>
     *
     * @author xumanyi
     * @date 2026-04-18
     */
    private static String resolveBackupDirName(FtpOperations ops, String backupParent, String baseName,
                                                PluginDeployConfig pluginConfig)
            throws java.io.IOException {
        com.flux.deploy.plugin.model.BackupConflictStrategy strategy =
                pluginConfig.getBackupConflictStrategy();
        if (strategy != com.flux.deploy.plugin.model.BackupConflictStrategy.NEW_DIR) {
            return baseName;
        }
        // NEW_DIR：找到首个不存在的 baseName_v{N}
        List<FTPFile> existing = ops.listFiles(backupParent);
        if (!dirExists(existing, baseName)) return baseName;
        int n = 2;
        while (dirExists(existing, baseName + "_v" + n)) n++;
        return baseName + "_v" + n;
    }

    /**
     * 兼容旧调用：按默认策略（不 NEW_DIR）解析备份目录名
     */
    private static String resolveBackupDirName(FtpOperations ops, String backupParent, String baseName)
            throws java.io.IOException {
        return baseName;
    }

    /**
     * 计算 USE_EXISTING 策略下的备份目录路径（不创建、不写入，只用于登记回滚源）
     *
     * <p>路径规则与 {@link #preBackupAll} 一致：{@code {systemRoot}backup/yyyyMMdd_{开发}/}</p>
     *
     * @author xumanyi
     * @date 2026-04-18
     */
    private static String computeExistingBackupDir(PluginDeployConfig pluginConfig,
                                                    List<FtpTargetSelection> allTargets) {
        if (allTargets.isEmpty()) return "";
        String backupParent = resolveBackupRoot(pluginConfig, allTargets.get(0).getRemoteDir());
        String dateStr = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        return backupParent + dateStr + "_" + pluginConfig.getOperator() + "/";
    }

    /** 检查目录列表中是否存在指定名称的目录 */
    private static boolean dirExists(List<FTPFile> files, String dirName) {
        for (FTPFile f : files) {
            if (f.isDirectory() && dirName.equals(f.getName())) {
                return true;
            }
        }
        return false;
    }

    /** 将字节数格式化为可读的大小字符串（B/KB/MB） */
    private static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
    }

    /**
     * 把单次嵌入产生的 {@link StagingPackageBuilder.PatchManifest} 渲染为日志，逐条输出
     * "<warName> 包内更新明细"。每条用 ~ / + / - 标识替换 / 新增 / 删除，便于操作人员
     * 在每个目标包完成时立即对账。
     *
     * @param logCallback 日志回调
     * @param warName     目标 war 名（用于每条日志的归属）
     * @param manifest    本次嵌入对内嵌 jar 做的变更明细；为 null（FULL 整包模式）时打印简单标记
     * @author xumanyi
     * @date 2026-05-07
     */
    private static void logPerWarPatchManifest(Consumer<String> logCallback, String warName,
                                                StagingPackageBuilder.PatchManifest manifest) {
        if (manifest == null || manifest.isEmpty()) {
            // FULL 模式 / 无差异 patch：嵌入的是整包新 jar，包内没有"逐 entry"明细可列
            logCallback.accept("[嵌入] " + warName + " 包内变更：整包替换内嵌 JAR（FULL 模式）");
            return;
        }
        logCallback.accept("[嵌入] " + warName + " 包内更新明细 (共 "
                + manifest.total() + " 项):");
        for (String e : manifest.getReplaced()) logCallback.accept("    替换 " + e);
        for (String e : manifest.getAdded())    logCallback.accept("    新增 " + e);
        for (String e : manifest.getDeleted())  logCallback.accept("    删除 " + e);
    }

    /**
     * {@link #logPerWarPatchManifest} 的并行流水线版本：写入 {@code StringBuilder} 而非
     * 直接调 logCallback，让本 target 的所有日志由 {@link com.flux.deploy.parallel.PipelineExecutor}
     * 在该 target 流水线收尾时一次性 flush（避免与其他 target 的日志交错）。
     *
     * @param log      {@link com.flux.deploy.parallel.PipelineExecutor.PipelineStages} 提供的 per-target log buffer
     * @param warName  目标 war 名
     * @param manifest 变更明细；为 null 时打印 FULL 模式标记
     * @author xumanyi
     * @date 2026-05-07
     */
    private static void appendPerWarPatchManifest(StringBuilder log, String warName,
                                                   StagingPackageBuilder.PatchManifest manifest) {
        if (manifest == null || manifest.isEmpty()) {
            log.append("[嵌入] ").append(warName).append(" 包内变更：整包替换内嵌 JAR（FULL 模式）\n");
            return;
        }
        log.append("[嵌入] ").append(warName).append(" 包内更新明细 (共 ")
                .append(manifest.total()).append(" 项):\n");
        for (String e : manifest.getReplaced()) log.append("    替换 ").append(e).append('\n');
        for (String e : manifest.getAdded())    log.append("    新增 ").append(e).append('\n');
        for (String e : manifest.getDeleted())  log.append("    删除 ").append(e).append('\n');
    }

    /**
     * 在 war 内按"产物完整文件名"精确解析嵌入 jar 的真实 lib 文件名
     *
     * <p>所有 jar→war 嵌入流程在 download war 之后、第一次调用
     * {@link #extractEmbeddedJar} 或 {@link com.flux.deploy.util.WarEmbedUtil#embedJar} 之前，
     * 必须先经由本方法决定 {@code targetJarName}。两步都用同一个完整文件名调用，
     * 杜绝"抽 X 写回 Y"的错位。</p>
     *
     * <p><b>定位策略</b>（与本地模式
     * {@link com.flux.deploy.plugin.service.LocalPackagePatchService} 的场景三严格保持一致）：</p>
     * <ol>
     *   <li>用 artifact 前缀枚举 war 内所有形如 {@code prefix-{数字}*.jar} 的候选；</li>
     *   <li>从候选里挑"去扩展名后与产物完整文件名相等"的那个（不区分大小写）；</li>
     *   <li>挑不到（无候选 / 无版本一致的）→ 直接抛错失败该 target，禁止任何兜底。</li>
     * </ol>
     *
     * @param warFile          下载到本地的远程 war
     * @param artifactFileName 源工程产物完整文件名（含 .jar / .war 扩展名）
     * @return war 内 WEB-INF/lib 下与 artifactFileName 严格匹配的完整 jar 文件名
     * @throws java.io.IOException 找不到候选或版本不一致
     * @author xumanyi
     * @date 2026-05-07
     */
    private static String resolveEmbedTargetJarName(Path warFile, String artifactFileName)
            throws java.io.IOException {
        String prefix = com.flux.deploy.plugin.service.LocalPackagePatchService
                .extractArtifactPrefix(artifactFileName);
        java.util.List<String> candidates = com.flux.deploy.plugin.service.LocalPackagePatchService
                .collectInnerLibJars(warFile, prefix);
        if (candidates.isEmpty()) {
            throw new java.io.IOException("目标 WAR 内不存在 " + artifactFileName
                    + "（WAR=" + warFile.getFileName() + "，WEB-INF/lib 下没有同名 JAR）");
        }
        String picked = com.flux.deploy.plugin.service.LocalPackagePatchService
                .pickVersionMatching(candidates, artifactFileName);
        if (picked == null) {
            throw new java.io.IOException("目标 WAR 内不存在 " + artifactFileName
                    + "（WAR=" + warFile.getFileName() + "，WEB-INF/lib 下只有版本不一致的 "
                    + candidates + "，必须文件名完全一致才能替换）");
        }
        return picked;
    }

    /**
     * 从 WAR 包的 {@code WEB-INF/lib/} 下精确抽出指定文件名的嵌入 JAR
     *
     * <p><b>精确匹配契约</b>：{@code targetJarName} 必须是 lib 下的完整文件名（含 .jar 扩展名）。
     * 历史遗留实现是按 artifactId 前缀（{@code startsWith}）遍历找第一个匹配，
     * maven 多模块项目里会把同前缀 sibling jar 误命中，配合 {@link com.flux.deploy.util.WarEmbedUtil#embedJar}
     * 同样的前缀匹配，造成"抽出 X、写回 Y"——主包被错误的 patch 包覆盖。已废止前缀匹配。</p>
     *
     * <p>调用方应先用
     * {@link com.flux.deploy.plugin.service.LocalPackagePatchService#collectInnerLibJars}
     * + {@link com.flux.deploy.plugin.service.LocalPackagePatchService#pickVersionMatching}
     * 决定要抽哪个完整 jar 名（必须与后续传给 {@link com.flux.deploy.util.WarEmbedUtil#embedJar}
     * 的 {@code targetJarName} 完全一致），再调用本方法。</p>
     *
     * @param warFile        本地 WAR 文件
     * @param targetJarName  WEB-INF/lib 下要抽出的 JAR 完整文件名（含 .jar 扩展名）
     * @param outputJar      抽出后的写入路径
     * @throws Exception 当 war 内不存在该完整文件名时抛错（拒绝任何 prefix 兜底）
     * @author xumanyi
     * @date 2026-05-07
     */
    private static void extractEmbeddedJar(Path warFile, String targetJarName, Path outputJar) throws Exception {
        if (targetJarName == null || targetJarName.isEmpty() || !targetJarName.endsWith(".jar")) {
            throw new IllegalArgumentException(
                    "targetJarName 必须是完整 jar 文件名（含 .jar 扩展名），实际: " + targetJarName);
        }
        String entryPath = "WEB-INF/lib/" + targetJarName;
        try (java.util.jar.JarFile jar = new java.util.jar.JarFile(warFile.toFile())) {
            java.util.jar.JarEntry entry = jar.getJarEntry(entryPath);
            if (entry == null || entry.isDirectory()) {
                throw new Exception("目标 WAR 内不存在条目 " + entryPath
                        + "（必须按完整文件名精确匹配，禁止 prefix 兜底）");
            }
            try (java.io.InputStream is = jar.getInputStream(entry)) {
                Files.copy(is, outputJar, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }
}
