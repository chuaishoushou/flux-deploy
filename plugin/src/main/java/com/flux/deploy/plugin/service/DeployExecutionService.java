package com.flux.deploy.plugin.service;

import com.flux.deploy.deploy.CancellationToken;
import com.flux.deploy.deploy.DeployPipeline;
import com.flux.deploy.deploy.ResidualLockDiagnosis;
import com.flux.deploy.deploy.ResidualLockResolver;
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
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.projectRoots.JavaSdk;
import com.intellij.openapi.projectRoots.JavaSdkType;
import com.intellij.openapi.projectRoots.JavaSdkVersion;
import com.intellij.openapi.projectRoots.ProjectJdkTable;
import com.intellij.openapi.projectRoots.Sdk;
import com.intellij.openapi.roots.ProjectRootManager;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.idea.maven.project.MavenGeneralSettings;
import org.jetbrains.idea.maven.project.MavenHomeType;
import org.jetbrains.idea.maven.project.MavenProject;
import org.jetbrains.idea.maven.project.MavenProjectsManager;
import org.jetbrains.idea.maven.project.StaticResolvedMavenHomeType;
import org.jetbrains.idea.maven.utils.MavenUtil;

import org.apache.commons.net.ftp.FTPFile;

import javax.swing.SwingUtilities;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
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
     * 取文件名最后一个扩展名之前的部分，用于解析 note 文件历史命名（{@code <去后缀>_update_note.txt}）。
     * 例如 {@code tm10srv.war} → {@code tm10srv}。无扩展名或点在开头时原样返回。
     *
     * @param name 文件名
     * @return 去掉最后一个扩展名后的文件名
     * @author xumanyi
     * @date 2026-04-30
     */
    private static String stripLastExtForNote(String name) {
        int dot = name.lastIndexOf('.');
        return (dot > 0) ? name.substring(0, dot) : name;
    }

    /** note 文件双文件合并标记前缀；canonical 内若含 "{@code 此前缀} + legacyName" 即视为已合并过该 legacy。 */
    private static final String NOTE_MERGE_MARKER_PREFIX = "==== 合并历史文件 ";

    /**
     * 把 {@code appended} 内容追加到 {@code base} 末尾，并写入合并标记行（与 NoteGate 等价）。
     * 标记行格式：{@code ==== 合并历史文件 <appendedName>（<bytes>B，于 yyyy-MM-dd HH:mm:ss） ====}
     *
     * @param base          作为基底的内容（较大）
     * @param appended      被追加的内容（较小）
     * @param appendedName  被追加文件的远端名（写入标记行供下次幂等识别）
     * @param appendedBytes 被追加内容的字节数
     * @return 合并后的完整字符串
     * @author xumanyi
     * @date 2026-04-30
     */
    private static String mergeNoteContents(String base, String appended,
                                            String appendedName, long appendedBytes) {
        java.time.format.DateTimeFormatter timeFmt =
                java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        String ts = java.time.LocalDateTime.now().format(timeFmt);
        StringBuilder sb = new StringBuilder(base);
        if (!base.isEmpty() && !base.endsWith("\n")) {
            sb.append("\n");
        }
        sb.append("\n");
        sb.append(NOTE_MERGE_MARKER_PREFIX).append(appendedName)
                .append("（").append(appendedBytes).append("B，于 ").append(ts).append("） ====\n");
        sb.append(appended);
        if (!appended.isEmpty() && !appended.endsWith("\n")) {
            sb.append("\n");
        }
        return sb.toString();
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
     * @author claude
     * @date 2026-04-29
     */
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
     * @author claude
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
     * @author claude
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
     * @author claude
     * @date 2026-04-28
     */
    @FunctionalInterface
    private interface FtpAction<T> {
        T run(FtpSession session, FtpOperations ops) throws Exception;
    }

    /**
     * {@link #runFreshFtpSession(String, int, String, String, FtpVoidAction)} 使用的回调接口（无返回值）。
     *
     * @author claude
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
                            logCallback.accept("[回滚] 空备份子目录已删除: " + subPath);
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
        logCallback.accept("║       ❌ 部署失败            ║");
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
        logCallback.accept("建议：按日志中的「✗」提示定位具体步骤后再试。");
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
        logCallback.accept("║       ■ 部署已停止           ║");
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

    /**
     * 保存所有 IDE 未保存的编辑器 buffer
     *
     * <p>Maven 构建读磁盘文件；若用户在 IDE 编辑了代码但没保存，
     * 外部 mvn 进程看到的是旧版代码。此方法在 EDT 上同步保存所有文档。</p>
     */
    private static void saveAllDocuments(Consumer<String> logCallback) {
        try {
            com.intellij.openapi.application.ApplicationManager.getApplication().invokeAndWait(() ->
                    com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().saveAllDocuments());
            logCallback.accept("[编译] 已保存所有未保存的编辑器 buffer");
        } catch (Exception e) {
            logCallback.accept("[编译] 保存 buffer 失败（继续）: " + e.getMessage());
        }
    }

    /**
     * 编译成功后记录产物信息（大小 + 最后修改时间），并告警可能的"陈旧"状态
     *
     * <p>若产物的 mtime 早于本次 mvn 启动时间，说明 mvn 根本没重新构建它，
     * 可能是产物名不匹配、mvn clean 失败或依赖缓存命中。</p>
     */
    private static void logArtifactInfo(String modulePath, String artifactFileName,
                                         long mvnStartMs, Consumer<String> logCallback) {
        if (artifactFileName == null || modulePath == null) return;
        try {
            Path artifact = Path.of(modulePath, "target", artifactFileName);
            if (!Files.isRegularFile(artifact)) {
                logCallback.accept("[编译][警告] 未在 target/ 下找到产物 "
                        + artifactFileName + "（请确认 pom.xml 中的 finalName）");
                return;
            }
            long size = Files.size(artifact);
            long mtime = Files.getLastModifiedTime(artifact).toMillis();
            String mtimeStr = new java.text.SimpleDateFormat("HH:mm:ss").format(new java.util.Date(mtime));
            String sizeStr = size < 1024 * 1024
                    ? String.format("%.1f KB", size / 1024.0)
                    : String.format("%.1f MB", size / (1024.0 * 1024.0));
            logCallback.accept("[编译] 产物: " + artifactFileName + " (" + sizeStr
                    + ", 修改于 " + mtimeStr + ")");

            if (mtime < mvnStartMs) {
                logCallback.accept("[编译][警告] 产物 mtime 早于 mvn 启动时间，"
                        + "可能未被本次构建更新！请检查 pom 产物名 / mvn clean 是否成功。");
            }
        } catch (Exception e) {
            logCallback.accept("[编译] 读取产物信息失败: " + e.getMessage());
        }
    }

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
            logCallback.accept("[回滚] 没有可回滚的部署记录");
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
                logCallback.accept("[回滚] 备份目录: " + lastBackupDir);
                logCallback.accept("[回滚] 需要恢复 " + lastUpdatedPackages.size() + " 个包");

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
                            logCallback.accept("[回滚] 已恢复: " + remotePath);
                            restoreSuccess++;
                        } finally {
                            Files.deleteIfExists(tempRestore);
                        }
                    } catch (Exception e) {
                        logCallback.accept("[回滚] 恢复失败: " + remotePath + " - " + e.getMessage());
                        restoreFail++;
                    }
                }

                // 2. 回滚版本记录
                if (lastUpdatedNote && lastAllTargets != null) {
                    logCallback.accept("[回滚] 回滚版本记录...");
                    rollbackNotes(lastAllTargets,
                            ftpHost, ftpPort, ftpUsername, ftpPassword, logCallback);
                }

                // 3. 清理备份（借用已有备份时跳过此步，老备份不是本次创建的）
                if (lastBackupBorrowed) {
                    logCallback.accept("[回滚] 借用已有备份作为回滚源，保留备份文件不做清理");
                } else {
                    // 清理阶段全是命令操作，无数据传输，复用一个短连接安全。
                    try {
                        runFreshFtpSession(ftpHost, ftpPort, ftpUsername, ftpPassword, (s, ops) -> {
                            for (String[] pair : lastUpdatedPackages) {
                                String backupFilePath = pair[1];
                                try {
                                    ops.delete(backupFilePath);
                                    logCallback.accept("[回滚] 已删除备份: " + backupFilePath);
                                } catch (Exception ignored) {}
                            }
                            // 递归清理空子目录（深层结构：{lastBackupDir}/{nested}/{subdir}/X.jar）
                            cleanEmptySubDirs(ops, s.getClient(), lastBackupDir, logCallback);

                            // 检查备份目录是否为空，空则删除目录
                            List<FTPFile> remaining = ops.listFiles(lastBackupDir);
                            if (remaining.isEmpty()) {
                                s.getClient().removeDirectory(lastBackupDir);
                                logCallback.accept("[回滚] 备份目录已空，已删除: " + lastBackupDir);
                            } else {
                                logCallback.accept("[回滚] 备份目录仍有 " + remaining.size()
                                        + " 个其他条目，保留目录");
                            }
                        });
                    } catch (Exception e) {
                        logCallback.accept("[回滚] 清理备份失败: " + e.getMessage());
                    }
                }

                // 4. 回滚总结
                logCallback.accept("\n========== 回滚总结 ==========");
                for (String[] pair : lastUpdatedPackages) {
                    logCallback.accept("  " + pair[0]);
                }
                if (restoreFail > 0) {
                    logCallback.accept("\n⚠ 回滚部分完成：" + restoreSuccess + " 成功, " + restoreFail + " 失败");
                } else {
                    logCallback.accept("\n✅ 回滚全部完成：" + restoreSuccess + " 个包已恢复");
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
            List<String[]> succeededSnap = currentSucceededUploads != null
                    ? new ArrayList<>(currentSucceededUploads) : new ArrayList<>();
            if (succeededSnap.isEmpty()) {
                logCallback.accept("[停止] 用户选择保留已成功，但当前尚无包成功；按回滚处理。");
                rollbackAll(backupDir, updatedPackages,
                        ftpHost, ftpPort, ftpUsername, ftpPassword, logCallback, backupBorrowed);
                return;
            }
            logCallback.accept("[停止] 用户选择保留已成功的 " + succeededSnap.size()
                    + " 个包；备份目录保留，可使用「回滚」按钮事后撤销");
            // 在 updatedPackages 中按 remotePath 关联备份路径，构造 manualRollback 用列表
            List<String[]> kept = new ArrayList<>();
            for (String[] succ : succeededSnap) {
                String rp = succ[0];
                for (String[] pair : updatedPackages) {
                    if (rp.equals(pair[0])) {
                        kept.add(new String[]{pair[0], pair[1]});
                        break;
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
            logCallback.accept("⚠ 无备份，无法自动回滚");
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
        ProgressManager.getInstance().run(new Task.Backgroundable(project, "FLUX 打包不上传", true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                indicator.setIndeterminate(true);
                try {
                    // 1. 编译（必须，用于生成 target/classes）
                    if (pluginConfig.getModulePath() == null) {
                        logCallback.accept("[错误] 未指定工程");
                        onComplete.accept(null);
                        return;
                    }
                    if (pluginConfig.isSkipCompile()) {
                        // 用户勾选"跳过编译"：直接复用 target/ 下已有产物。
                        // 产物存在性 / mtime 由 UI 提前提示，这里仅记日志方便回溯。
                        logCallback.accept("[编译] 已跳过（用户勾选『跳过编译』，直接使用 target/ 下已有产物）");
                        logArtifactInfo(pluginConfig.getModulePath(),
                                pluginConfig.getArtifactFileName(), 0L, logCallback);
                    } else {
                        // 先保存所有 IDE 未保存的 buffer，否则 mvn 读到的源码是旧版
                        saveAllDocuments(logCallback);
                        long mvnStartMs = System.currentTimeMillis();
                        logCallback.accept("[编译] mvn clean package -DskipTests ...");
                        boolean compiled = runMavenPackage(project, pluginConfig.getModulePath(), logCallback);
                        if (!compiled) {
                            logCallback.accept("[错误] 编译失败，打包不上传终止");
                            onComplete.accept(null);
                            return;
                        }
                        logCallback.accept("[编译] 编译成功");
                        logArtifactInfo(pluginConfig.getModulePath(),
                                pluginConfig.getArtifactFileName(), mvnStartMs, logCallback);
                    }

                    // 2. 执行本地补丁
                    com.flux.deploy.plugin.model.LocalTargetSelection lt = pluginConfig.getLocalTarget();
                    if (lt == null) {
                        logCallback.accept("[错误] 缺少本地目标信息");
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
                            pluginConfig.isSkipCompile(),
                            logCallback);
                    if (result == null || !result.isSuccess()) {
                        logFailureSummary(logCallback,
                                result == null ? "打包不上传失败" : result.getErrorMessage());
                    }
                    onComplete.accept(result);
                } catch (Exception e) {
                    logCallback.accept("[异常] " + e.getMessage());
                    logFailureSummary(logCallback, "打包不上传异常: " + e.getMessage());
                    onComplete.accept(null);
                }
            }
        });
    }

    public static void execute(Project project, PluginDeployConfig pluginConfig,
                               String ftpHost, int ftpPort, String ftpUsername, String ftpPassword,
                               Consumer<String> logCallback, Consumer<DeployResult> onCompleteRaw) {

        // 复位本次任务的"用户停止"上下文：模式 / 实时成功列表 / 总数 / dryRun 标志
        currentCancelMode = CancelMode.NONE;
        currentSucceededUploads = java.util.Collections.synchronizedList(new ArrayList<>());
        currentTotalTargets = 0;
        currentDryRun = pluginConfig.isDryRun();

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

                // 1. 编译项目（预检跳过编译，只检查 FTP 状态；用户勾选"跳过编译"也直接复用产物）
                if (!pluginConfig.isDryRun() && pluginConfig.getModulePath() != null) {
                    if (pluginConfig.isSkipCompile()) {
                        logCallback.accept("[编译] 已跳过（用户勾选『跳过编译』，直接使用 target/ 下已有产物）");
                        logArtifactInfo(pluginConfig.getModulePath(),
                                pluginConfig.getArtifactFileName(), 0L, logCallback);
                    } else {
                        // 先保存所有 IDE 未保存的 buffer
                        saveAllDocuments(logCallback);
                        long mvnStartMs = System.currentTimeMillis();
                        logCallback.accept("[编译] mvn clean package -DskipTests ...");
                        boolean compiled = runMavenPackage(project, pluginConfig.getModulePath(), logCallback);
                        if (!compiled) {
                            logCallback.accept("[错误] 编译失败，部署终止");
                            logFailureSummary(logCallback, "编译失败（mvn 退出码非 0）");
                            onComplete.accept(null);
                            return;
                        }
                        logCallback.accept("[编译] 编译成功");
                        logArtifactInfo(pluginConfig.getModulePath(),
                                pluginConfig.getArtifactFileName(), mvnStartMs, logCallback);
                    }
                } else if (pluginConfig.isDryRun()) {
                    logCallback.accept("[预检] 跳过编译，仅检查 FTP 状态");
                }

                // 2+3: 每个主目标的暂存包 / aligned war 放到 Phase 3 上传循环里按目标逐个构建
                //       这里仅构建一份不含主目标本地文件的基础 DeployConfig 供预检使用
                DeployConfig config = buildDeployConfig(pluginConfig, ftpHost, ftpPort, ftpUsername, ftpPassword, null);

                // 验证本地文件存在（预检时跳过，无主目标时跳过，跳过编译时跳过——
                // 静态资源走 src/ 源目录读取，target/{artifact}.jar 不会被使用）
                if (!config.isDryRun() && pluginConfig.getTarget() != null && !pluginConfig.isSkipCompile()) {
                    if (config.getLocalFiles() == null || config.getLocalFiles().isEmpty()) {
                        logCallback.accept("[错误] 未找到本地编译产物");
                        logFailureSummary(logCallback, "未找到本地编译产物");
                        onComplete.accept(null);
                        return;
                    }
                    for (Path lf : config.getLocalFiles()) {
                        if (!Files.exists(lf)) {
                            logCallback.accept("[错误] 本地文件不存在: " + lf);
                            logFailureSummary(logCallback, "本地文件不存在: " + lf.getFileName());
                            onComplete.accept(null);
                            return;
                        }
                        try {
                            logCallback.accept("[文件] " + lf.getFileName() + " (" + Files.size(lf) / 1024 + " KB)");
                        } catch (java.io.IOException ignored) {
                            logCallback.accept("[文件] " + lf.getFileName());
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
                                logCallback.accept("[预检] 通过");
                            } else {
                                String reason = extractFirstErrorMessage(result);
                                logCallback.accept("[预检] 未通过：" + reason);
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
                                    logCallback.accept("  WAR: " + et.getTargetName()
                                            + (exists ? " ✓ 存在 (" + size / 1024 / 1024 + " MB)" : " ✗ 不存在"));
                                }
                                DeployResult r = new DeployResult();
                                if (missing == 0) {
                                    logCallback.accept("[预检] 通过");
                                    r.markSuccess();
                                } else {
                                    String reason = missing + "/" + embedTargets0.size() + " 个 WAR 不存在";
                                    logCallback.accept("[预检] 未通过：" + reason);
                                    r.addError("preCheck", "embed", reason);
                                }
                                onComplete.accept(r);
                            }
                        } else {
                            logCallback.accept("[预检] 未通过：无目标包");
                            onComplete.accept(null);
                        }
                        return;
                    }

                    // === 事务性多目标部署流程 ===
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

                    logCallback.accept("开始部署，共 " + allTargets.size() + " 个目标包"
                            + (mainTargets.size() > 1 ? "（其中 " + mainTargets.size() + " 个同名主目标）" : "")
                            + "...");
                    for (FtpTargetSelection t : allTargets) {
                        logCallback.accept("  → " + t.getRelativePath());
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
                        logCallback.accept("\n━━ 正在准备上传产物（" + mainTargets.size() + " 个主目标） ━━");
                        for (FtpTargetSelection mt : mainTargets) {
                            try {
                                Path local = prepareLocalFileForMainTarget(
                                        pluginConfig, mt, isFullMode, artifactIsWar,
                                        ftpHost, ftpPort, ftpUsername, ftpPassword, logCallback);
                                if (local == null || !Files.exists(local)) {
                                    throw new java.io.IOException("产物为空或不存在");
                                }
                                preparedPerMain.put(mt, local);
                                logCallback.accept("  ✓ " + mt.getRelativePath()
                                        + " → " + local.getFileName() + " (" + formatSize(Files.size(local)) + ")");
                            } catch (Exception ex) {
                                logCallback.accept("✗ [" + mt.getRelativePath() + "] 产物构建失败: "
                                        + ex.getMessage());
                                logFailureSummary(logCallback, "产物构建失败，未执行任何远端变更");
                                onComplete.accept(null);
                                return;
                            }
                        }
                    }

                    // ── Phase 1: 备份（可选）──
                    String backupDir = null;
                    List<String[]> updatedPackages = new ArrayList<>();

                    com.flux.deploy.plugin.model.BackupConflictStrategy strategy =
                            pluginConfig.getBackupConflictStrategy();
                    // 借用标记：USE_EXISTING 下为 true，回滚时只恢复文件不清理老备份
                    final boolean backupBorrowed = !pluginConfig.isSkipBackup()
                            && strategy == com.flux.deploy.plugin.model.BackupConflictStrategy.USE_EXISTING;

                    if (!pluginConfig.isSkipBackup()) {
                        if (strategy == com.flux.deploy.plugin.model.BackupConflictStrategy.USE_EXISTING) {
                            // 使用已有备份：不做下载/上传，直接把已有备份路径登记为回滚源
                            logCallback.accept("\n━━ 使用已有备份作为回滚源（本次跳过备份步骤） ━━");
                            backupDir = computeExistingBackupDir(pluginConfig, allTargets);
                            for (FtpTargetSelection t : allTargets) {
                                String rp = t.getRemoteDir() + t.getRelativePath();
                                String bp = backupDir + backupSubDirFor(t) + t.getTargetName();
                                updatedPackages.add(new String[]{rp, bp});
                                logCallback.accept("[备份] 回滚源: " + bp);
                            }
                        } else {
                            String dirSuffix = strategy
                                    == com.flux.deploy.plugin.model.BackupConflictStrategy.NEW_DIR
                                    ? "(新增目录)" : "";
                            logCallback.accept("\n━━ 正在备份所有目标包 (" + allTargets.size()
                                    + " 个)" + dirSuffix + " ━━");
                            try {
                                backupDir = preBackupAll(pluginConfig, allTargets,
                                        ftpHost, ftpPort, ftpUsername, ftpPassword, logCallback);
                                logCallback.accept("✓ 备份完成");
                            } catch (Exception e) {
                                logCallback.accept("✗ 备份失败: " + e.getMessage());
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
                        logCallback.accept("\n⚠ 跳过备份（用户选择不备份，失败后无法自动回滚）");
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
                                logCallback.accept("  ✓ " + label + " (" + Files.size(dest) / 1024 + " KB)");
                            }

                            // 保存 WAR 嵌入包
                            if (hasEmbedTargets) {
                                String originalArtifact = pluginConfig.getArtifactFileName();
                                String artifactPrefix = extractArtifactPrefix(originalArtifact);
                                // 仅作为 FULL 模式下的"嵌入源 JAR"；增量模式会在循环内基于远程 WAR 内嵌 JAR 重新构建
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
                                        // 保持其余条目原字节与时间戳；与 executeWarEmbed 中 line 1199-1230 同语义
                                        Path jarToEmbed = freshLocalJar;
                                        if (isIncrementalEmbed) {
                                            Path extractedJar = tempDir.resolve("extracted-" + artifactPrefix + ".jar");
                                            extractEmbeddedJar(downloadedWar, artifactPrefix, extractedJar);
                                            if (Files.exists(extractedJar) && Files.size(extractedJar) > 0) {
                                                List<String> changedFiles = pluginConfig.getChangedFiles();
                                                if (changedFiles != null && !changedFiles.isEmpty()) {
                                                    StagingPackageBuilder patcher = new StagingPackageBuilder(
                                                            pluginConfig.getModulePath(),
                                                            originalArtifact,
                                                            changedFiles,
                                                            logCallback)
                                                            .setSkipCompile(pluginConfig.isSkipCompile());
                                                    Path patchedJar = patcher.patchExistingJar(extractedJar, tempDir);
                                                    if (patchedJar != null && Files.exists(patchedJar)) {
                                                        jarToEmbed = patchedJar;
                                                    }
                                                }
                                            }
                                        }

                                        Path embeddedWar = tempDir.resolve("embed-" + embedTarget.getTargetName());
                                        com.flux.deploy.util.WarEmbedUtil.embedJar(downloadedWar, jarToEmbed, artifactPrefix, embeddedWar);
                                        Path dest = outputDir.resolve(embedTarget.getTargetName());
                                        Files.copy(embeddedWar, dest, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                                        savedFiles.add(new String[]{dest.toString(), remotePath});
                                        logCallback.accept("  ✓ " + embedTarget.getTargetName() + " (" + Files.size(dest) / 1024 / 1024 + " MB)");
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
                            logCallback.accept("║        包准备完成            ║");
                            logCallback.accept("╚══════════════════════════════╝");
                            logCallback.accept("输出目录: " + outputDir);
                            for (String[] sf : savedFiles) {
                                logCallback.accept("  ✓ " + Path.of(sf[0]).getFileName());
                            }
                            logCallback.accept("\n手动上传目标：");
                            for (String[] sf : savedFiles) {
                                logCallback.accept("  " + Path.of(sf[0]).getFileName() + " → " + sf[1]);
                            }
                            logCallback.accept("\n备份目录（FTP）: " + backupDir);

                            DeployResult localResult = new DeployResult();
                            localResult.markSuccess();
                            onComplete.accept(localResult);
                        } catch (Exception e) {
                            logCallback.accept("✗ 保存失败: " + e.getMessage());
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
                                    logCallback.accept("[stage0] 对话框异常: " + swingEx.getMessage());
                                    logFailureSummary(logCallback, "Stage 0 对话框异常");
                                    onComplete.accept(null);
                                    return;
                                }
                                if (!proceed[0]) {
                                    logCallback.accept("[stage0] 用户取消");
                                    logFailureSummary(logCallback, "用户取消 Stage 0 残留锁处理");
                                    onComplete.accept(null);
                                    return;
                                }
                                for (ResidualLockDiagnosis d : selected) {
                                    try {
                                        resolver.apply(d);
                                        logCallback.accept("[stage0] 已清理: " + d.getLockFileName());
                                    } catch (java.io.IOException ie) {
                                        logCallback.accept("[stage0] 清理失败: " + ie.getMessage());
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
                                    logCallback.accept("[stage0] 仍有未处理的残留锁，部署中止");
                                    logFailureSummary(logCallback, "Stage 0 仍有未处理的残留锁");
                                    onComplete.accept(null);
                                    return;
                                }
                            }
                        } catch (java.io.IOException ftpEx) {
                            logCallback.accept("[stage0] FTP 错误: " + ftpEx.getMessage());
                            logFailureSummary(logCallback, "Stage 0 FTP 错误: " + ftpEx.getMessage());
                            onComplete.accept(null);
                            return;
                        }
                    }

                    // ── Phase 2: 加锁 ──
                    // 多目标时打 header + summary，单目标退化为单行 [加锁] X 已加锁，避免日志噪音
                    boolean multiTargets = allTargets.size() > 1;
                    if (multiTargets) {
                        logCallback.accept("\n━━ 正在加锁所有目标包 ━━");
                    }
                    List<String[]> lockedPackages = new ArrayList<>();
                    try {
                        preLockAll(allTargets, pluginConfig.getOperator(),
                                ftpHost, ftpPort, ftpUsername, ftpPassword,
                                logCallback, lockedPackages);
                        if (multiTargets) {
                            logCallback.accept("✓ 全部加锁完成，其他人暂时无法修改这些包");
                        }
                    } catch (Exception e) {
                        logCallback.accept("✗ 加锁失败: " + e.getMessage());
                        // 关键：先从备份恢复文件，再删锁文件
                        if (backupDir != null && !updatedPackages.isEmpty()) {
                            rollbackAll(backupDir, updatedPackages,
                                    ftpHost, ftpPort, ftpUsername, ftpPassword, logCallback, backupBorrowed);
                        } else {
                            logCallback.accept("⚠ 无备份，无法自动回滚");
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
                            logCallback.accept("\n━━ (" + mi + "/" + preparedPerMain.size()
                                    + ") 正在上传主目标 " + mt.getRelativePath() + " [" + tag + "] ━━");
                            try {
                                logCallback.accept("[上传] " + tag + " 源文件: " + localForThisTarget
                                        + " (" + formatSize(Files.size(localForThisTarget)) + ")");
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
                                        ? "■ 用户已请求停止，按所选模式处理已成功的包..."
                                        : "✗ [" + mt.getRelativePath() + "] 上传失败，处理已更新的包...");
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
                            logCallback.accept("✓ [" + mt.getRelativePath() + "] 上传成功");
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

                    if (hasEmbedTargets) {
                        logCallback.accept("\n━━ 正在嵌入 JAR 到 WAR (" + embedTargets.size() + " 个) ━━");

                        String originalArtifact = pluginConfig.getArtifactFileName();

                        // 防御：WAR 源不应有嵌入目标（UI 已过滤，代码再兜底一次）
                        if (originalArtifact != null
                                && originalArtifact.toLowerCase().endsWith(".war")) {
                            logCallback.accept("[警告] 源产物为 WAR，不应触发嵌入流程；已跳过 "
                                    + embedTargets.size() + " 个嵌入目标");
                            preUnlockAll(lockedPackages, ftpHost, ftpPort, ftpUsername, ftpPassword, logCallback);
                            // 版本记录仍按主目标流程处理，跳过嵌入即可
                            hasEmbedTargets = false;
                        }
                    }
                    if (hasEmbedTargets) {
                        String originalArtifact = pluginConfig.getArtifactFileName();
                        String artifactPrefix = extractArtifactPrefix(originalArtifact);

                        Path localJar = Path.of(pluginConfig.getModulePath(), "target", originalArtifact);
                        if (!Files.exists(localJar) && config.getLocalFiles() != null && !config.getLocalFiles().isEmpty()) {
                            localJar = config.getLocalFiles().get(0);
                        }
                        if (!Files.exists(localJar)) {
                            logCallback.accept("✗ 本地 JAR 文件不存在: " + localJar);
                            preUnlockAll(lockedPackages, ftpHost, ftpPort, ftpUsername, ftpPassword, logCallback);
                            logFailureSummary(logCallback, "嵌入阶段本地 JAR 不存在");
                            onComplete.accept(null);
                            return;
                        }
                        try {
                            logCallback.accept("[嵌入] 源 JAR: " + localJar + " ("
                                    + formatSize(Files.size(localJar)) + ")");
                        } catch (Exception ignored) {}

                        // lockedPackages 顺序与 allTargets 一致：[main0, main1, ..., embed0, embed1, ...]
                        // embed 部分从 mainTargets.size() 开始
                        int mainOffset = mainTargets.size();

                        boolean multiEmbed = embedTargets.size() > 1;
                        for (int ei = 0; ei < embedTargets.size(); ei++) {
                            FtpTargetSelection embedTarget = embedTargets.get(ei);
                            // 多包才打 (N/M) 进度行 + 完成回执，单包退化为该阶段 header 已包含的信息
                            if (multiEmbed) {
                                logCallback.accept("\n(" + (ei + 1) + "/" + embedTargets.size() + ") "
                                        + embedTarget.getTargetName());
                            }
                            try {
                                String[] lockInfo = lockedPackages.get(ei + mainOffset);
                                executeWarEmbed(pluginConfig, embedTarget,
                                        localJar, artifactPrefix,
                                        lockInfo[0], lockInfo[1],
                                        ftpHost, ftpPort, ftpUsername, ftpPassword,
                                        logCallback);

                                embedSuccess++;
                                if (multiEmbed) {
                                    logCallback.accept("✓ " + embedTarget.getTargetName() + " 完成");
                                }
                                // 登记到实时成功列表（供 UI 弹"如何收尾"对话框展示）
                                recordSucceededUpload(embedTarget);
                            } catch (Exception e) {
                                boolean userStop = currentCancelMode != CancelMode.NONE
                                        || e instanceof CancellationToken.CancellationException;
                                logCallback.accept(userStop
                                        ? "■ 用户已请求停止，按所选模式处理已成功的包..."
                                        : "✗ " + embedTarget.getTargetName() + " 失败: " + e.getMessage());
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
                    }

                    // ── 解锁 ──
                    // 同加锁：多目标才打 header + summary
                    boolean multiUnlock = lockedPackages.size() > 1;
                    if (multiUnlock) {
                        logCallback.accept("\n━━ 正在解锁所有目标包 ━━");
                    }
                    preUnlockAll(lockedPackages, ftpHost, ftpPort, ftpUsername, ftpPassword, logCallback);
                    if (multiUnlock) {
                        logCallback.accept("✓ 全部解锁完成");
                    }

                    // ── Phase 5: 版本记录 ──
                    if (pluginConfig.isUpdateNote()) {
                        logCallback.accept("\n━━ 正在更新版本记录 ━━");
                        try {
                            updateNoteForAll(pluginConfig, allTargets,
                                    ftpHost, ftpPort, ftpUsername, ftpPassword, logCallback);
                            logCallback.accept("✓ 全部版本记录更新完成");
                        } catch (Exception e) {
                            logCallback.accept("⚠ 版本记录更新失败: " + e.getMessage() + "（不影响已部署的包）");
                        }
                    }

                    // ── 总结 ──
                    // 与失败 / 停止两种结束态保持框体对称（都是 ╔═╗ + emoji + 短文案）：
                    //   ❌ 部署失败 / ■ 部署已停止 / ✅ 部署完成
                    // 框下分三段输出"已更新 N 个包：" + 路径列表 + 备份目录。
                    logCallback.accept("\n╔══════════════════════════════╗");
                    logCallback.accept("║       ✅ 部署完成            ║");
                    logCallback.accept("╚══════════════════════════════╝");
                    logCallback.accept("已更新 " + updatedPackages.size() + " 个包：");
                    // 远程路径以 RAW_LINE_MARK 开头分行输出；工具窗口会剥标记并跳过时间戳，列表对齐干净。
                    // 缩进 10 空格对齐到带时间戳行的内容列（"HH:mm:ss" 8 + 分隔 2）。
                    for (String[] entry : updatedPackages) {
                        String remotePath = entry[0];
                        logCallback.accept(com.flux.deploy.plugin.toolwindow.LogSectionPanel.RAW_LINE_MARK
                                + "          " + remotePath);
                    }
                    if (backupDir != null) {
                        logCallback.accept("备份目录：" + backupDir);
                    } else {
                        logCallback.accept("备份：未执行（用户选择跳过）");
                    }

                    // 保存回滚信息供手动回滚使用（仅在有备份时）
                    if (backupDir != null) {
                        lastBackupDir = backupDir;
                        lastUpdatedPackages = new ArrayList<>(updatedPackages);
                        lastAllTargets = new ArrayList<>(allTargets);
                        lastUpdatedNote = pluginConfig.isUpdateNote();
                        lastBackupBorrowed = backupBorrowed;
                    }

                    onComplete.accept(result);

                } catch (Exception e) {
                    logCallback.accept("[异常] " + e.getMessage());
                    logFailureSummary(logCallback, "未预期异常: " + e.getMessage());
                    onComplete.accept(null);
                } finally {
                    System.setOut(originalOut);
                    System.setErr(originalErr);
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
            // 全量模式：使用 target/ 下的编译产物
            // 跳过编译时此路径仅作为 buildTargets 的占位（增量 staging 会覆盖），文件可不存在
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
     * 在模块目录执行 mvn package -DskipTests
     *
     * <p>JDK 选择逻辑见 {@link #resolveJdkHomeForModule}：优先按目标模块 effective pom 的
     * {@code maven.compiler.release/target/source} 在 IDEA 全局 SDK 表里精确匹配；
     * pom 未声明编译版本时回退使用 IDEA Project SDK。
     * 不依赖系统 {@code JAVA_HOME} / {@code PATH}，即使 IDE 启动时环境变量指向 JRE，
     * Maven 子进程依然能拿到正确的 JDK 编译。</p>
     *
     * @return true=编译成功, false=编译失败（含 JDK 未就绪）
     * @author xumanyi
     * @date 2026-03-27
     */
    private static boolean runMavenPackage(Project project, String modulePath, Consumer<String> logCallback) {
        try {
            // 1. 解析 JDK：pom 驱动 + IDEA SDK 池精确匹配；resolveJdkHomeForModule 自身已输出
            //    单行决策结果（成功 1 行 / 失败抛异常带详细信息），不再外层重复打 path。
            String javaHome = resolveJdkHomeForModule(project, modulePath, logCallback);

            // 2. 查找 mvn：IDEA Maven 设置 → 项目 mvnw；不再回退到 env / shell PATH，
            //    避免子进程 fork 到与 IDEA 不一致的 Maven 安装（详见 findMvnCommand）。
            String mvnCmd = findMvnCommand(project, modulePath, logCallback);
            logCallback.accept("[编译] 使用 Maven: " + mvnCmd);

            // 3. 透传 IDEA Settings → Build Tools → Maven 里的"用户设置文件 / 本地仓库"覆盖项。
            //    IDEA 工具窗口跑 Maven 走 IDE embedder，这两项配置直接生效；fork 出去的 mvn 是
            //    独立子进程，必须用 -s / -Dmaven.repo.local 显式透传，否则会用错 settings.xml
            //    导致 SNAPSHOT parent POM 解析不到。
            List<String> cmd = new ArrayList<>(8);
            cmd.add(mvnCmd);
            cmd.add("clean");
            cmd.add("package");
            cmd.add("-DskipTests");
            cmd.addAll(resolveIdeaMavenCliOverrides(project, logCallback));

            // 去掉 -q：静默模式会抑制 "BUILD SUCCESS"，无法从日志判断构建是否真正跑完
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(Path.of(modulePath).toFile());
            pb.redirectErrorStream(true);

            // 4. 注入 JDK 到子进程环境：JAVA_HOME + bin 前置到 PATH，覆盖继承自 IDE 的脏环境
            Map<String, String> env = pb.environment();
            env.put("JAVA_HOME", javaHome);
            String binDir = javaHome + File.separator + "bin";
            // Windows 上 ProcessBuilder.environment() 的 key 是大小写不敏感的（系统特性），
            // 但为了兼容 case-sensitive 平台，显式把 Path / PATH 两种形式都覆盖
            String existingPath = env.getOrDefault("PATH", env.getOrDefault("Path", ""));
            String newPath = binDir + File.pathSeparator + existingPath;
            env.put("PATH", newPath);
            env.put("Path", newPath);

            Process process = pb.start();

            // 读取输出：只保留关键信息（BUILD 状态、错误、警告）
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String finalLine = line;
                    if (finalLine.contains("BUILD SUCCESS")
                            || finalLine.contains("BUILD FAILURE")
                            || finalLine.contains("[ERROR]")
                            || finalLine.contains("FAILURE")) {
                        logCallback.accept("[编译] " + finalLine);
                    }
                }
            }

            int exitCode = process.waitFor();
            logCallback.accept("[编译] mvn 退出码: " + exitCode);
            return exitCode == 0;

        } catch (IllegalStateException configErr) {
            // JDK / Maven 配置缺失或不可用 —— 配置性问题，与运行时异常分开打印方便一眼识别
            logCallback.accept("[编译失败] 配置缺失: " + configErr.getMessage());
            return false;
        } catch (Exception e) {
            logCallback.accept("[编译异常] " + e.getMessage());
            return false;
        }
    }

    /**
     * 编译时 JDK 解析：按目标模块 effective pom 的 {@code release/target/source} 在 IDEA 全局
     * SDK 表里精确匹配，匹配失败抛 {@link IllegalStateException} 让 {@link #runMavenPackage}
     * 走 catch 分支。<strong>严格不向上回退</strong>（不会用 JDK 17 替代 pom 要的 JDK 8），
     * 避免老 maven plugin 在高 JDK 上的 illegal-access 等运行期坑导致客户现场暴雷。
     *
     * <p>fallback 链：</p>
     * <ol>
     *   <li>pom 解析成功 + JDK 池精确匹配 → 使用匹配到的 JDK</li>
     *   <li>pom 未声明 source/target/release（IDEA 也没识别为 Maven 项目 / pom 缺失） →
     *       回退使用 Project SDK（保留旧行为，日志显式提示）</li>
     *   <li>pom 声明了但 JDK 池里无精确匹配 → 抛错，提示用户去
     *       Project Structure → Platform Settings → SDKs 里添加</li>
     * </ol>
     *
     * @param modulePath  待打包模块绝对路径
     * @param logCallback 决策过程逐步输出到部署日志，便于排查"为什么挑了这个 JDK"
     * @return JDK 根目录（含 {@code bin/javac}），可直接作为 {@code JAVA_HOME}
     * @throws IllegalStateException pom 要求的 JDK 在池里找不到，或回退路径下 Project SDK 也不可用
     * @author xumanyi
     * @date 2026-04-27
     */
    private static String resolveJdkHomeForModule(Project project, String modulePath,
                                                  Consumer<String> logCallback) {
        // 步骤 1：解析 effective pom 拿目标 Java 版本
        JavaSdkVersion required = resolveRequiredJavaVersionFromPom(project, modulePath, logCallback);

        // 步骤 2a：pom 没拿到 → 回退 Project SDK（旧行为）
        if (required == null) {
            String fallback = resolveProjectSdkHome(project);
            logCallback.accept("[编译] JDK: " + fallback + " (回退 Project SDK，pom 未声明编译版本)");
            return fallback;
        }

        // 步骤 2b：pom 拿到了 → 严格按版本去 IDEA SDK 池里精确匹配
        Sdk matched = findJdkByVersion(required);
        if (matched == null) {
            String pool = listAvailableJdkVersions();
            throw new IllegalStateException(
                    "未在 IDEA SDK 列表中找到 JDK " + required.getDescription()
                            + "（pom 要求该版本，严格模式不向上兼容）。"
                            + "请在 Project Structure → Platform Settings → SDKs 里添加该版本 JDK。"
                            + "当前池: " + pool);
        }
        String homePath = matched.getHomePath();
        if (homePath == null || homePath.isBlank()) {
            throw new IllegalStateException("匹配到的 JDK [" + matched.getName() + "] HomePath 为空");
        }
        logCallback.accept("[编译] JDK: " + matched.getName() + " (Java " + required.getDescription() + ")");
        return homePath;
    }

    /**
     * 通过 IDEA 内置 Maven 插件读取目标模块 effective pom 的目标 Java 版本。
     *
     * <p>优先级：{@code maven.compiler.release} &gt; {@code maven.compiler.target}
     * &gt; {@code maven.compiler.source}。Maven 模型由 IDEA Importer 在 Reload 时构建，
     * 已经处理 profile / parent 继承 / properties 占位符替换，无需自己解析 pom。</p>
     *
     * @return 解析出的 {@link JavaSdkVersion}；模块未被 IDEA 识别为 Maven 项目、
     *         pom.xml 不存在、或三个属性均未声明时返回 {@code null}
     * @author xumanyi
     * @date 2026-04-27
     */
    private static JavaSdkVersion resolveRequiredJavaVersionFromPom(Project project, String modulePath,
                                                                     Consumer<String> logCallback) {
        return ReadAction.compute(() -> {
            // 模块路径 → VirtualFile，refreshAndFindFileByPath 会感知磁盘最新状态（切分支后 pom 可能已变）
            VirtualFile moduleVf = LocalFileSystem.getInstance().refreshAndFindFileByPath(modulePath);
            if (moduleVf == null || !moduleVf.isDirectory()) {
                logCallback.accept("[编译][JDK] 模块目录无法解析为 VirtualFile: " + modulePath);
                return null;
            }
            VirtualFile pom = moduleVf.findChild("pom.xml");
            if (pom == null) {
                logCallback.accept("[编译][JDK] 模块根下未找到 pom.xml: " + modulePath);
                return null;
            }

            MavenProjectsManager mpm = MavenProjectsManager.getInstance(project);
            MavenProject mavenProject = mpm.findProject(pom);
            if (mavenProject == null) {
                logCallback.accept("[编译][JDK] IDEA 未将该模块识别为 Maven 项目，"
                        + "建议先执行 Maven 工具窗口 → Reload All Maven Projects");
                return null;
            }

            // 三个 level 全部走兼容方法。MavenProject#getReleaseLevel/getTargetLevel/getSourceLevel
            // 在 IDEA 2024.2+ 都已移除/改名，直接调用会触发 NoSuchMethodError。
            // 兼容方法按 properties → plugin <configuration> → 反射老 API 三层兜底，任何一层成功即用。
            String release = readLevelCompat(mavenProject, "release",
                    "maven.compiler.release", "getReleaseLevel");
            String target = readLevelCompat(mavenProject, "target",
                    "maven.compiler.target", "getTargetLevel");
            String source = readLevelCompat(mavenProject, "source",
                    "maven.compiler.source", "getSourceLevel");

            String picked = firstNonBlank(release, target, source);
            if (picked == null) {
                // 仅在无可用编译版本时打印诊断行；正常路径无需把内部值刷到用户日志
                logCallback.accept("[编译][JDK] effective pom 未声明编译版本: release=" + release
                        + ", target=" + target + ", source=" + source);
                return null;
            }
            JavaSdkVersion version = parseJavaSdkVersion(picked);
            if (version == null) {
                logCallback.accept("[编译][JDK] 无法识别的 Java 版本字符串: " + picked
                        + "（支持 1.5 ~ 1.9 / 5 ~ 25 等常见格式）");
            }
            return version;
        });
    }

    /**
     * 跨 IDEA 版本兼容地从 Maven 项目模型里拿编译目标版本（release / target / source 之一）。
     *
     * <p>{@code MavenProject#getReleaseLevel/getTargetLevel/getSourceLevel} 在 IDEA 2024.2+
     * 已移除/改名，直接调用会触发 {@link NoSuchMethodError}。本方法按以下顺序三层兜底：</p>
     * <ol>
     *   <li><strong>effective properties</strong>：覆盖 {@code <properties><maven.compiler.xxx>}
     *       写法（绝大多数项目）</li>
     *   <li><strong>maven-compiler-plugin 的 plugin configuration</strong>：覆盖
     *       {@code <plugin><configuration><release>} 写法（少数项目）</li>
     *   <li><strong>反射调老 IDEA API</strong>：兜底 IDEA 仍保留 getter 的版本，避免编译期硬绑</li>
     * </ol>
     *
     * @param configChildName plugin {@code <configuration>} 下的子节点名（如 {@code "release"}）
     * @param propertyKey     effective properties 的 key（如 {@code "maven.compiler.release"}）
     * @param getterName      MavenProject 上对应的反射 getter 名（如 {@code "getReleaseLevel"}）
     * @return 解析到的版本字符串；都拿不到返回 {@code null}
     * @author xumanyi
     * @date 2026-04-27
     */
    private static String readLevelCompat(MavenProject mavenProject, String configChildName,
                                          String propertyKey, String getterName) {
        // 步骤 1：从 effective properties 读
        String fromProps = mavenProject.getProperties().getProperty(propertyKey);
        if (fromProps != null && !fromProps.isBlank()) {
            return fromProps;
        }
        // 步骤 2：从 maven-compiler-plugin 的 <configuration> 读
        String fromPluginConfig = readCompilerPluginConfig(mavenProject, configChildName);
        if (fromPluginConfig != null && !fromPluginConfig.isBlank()) {
            return fromPluginConfig;
        }
        // 步骤 3：反射兜底（个别 IDEA 版本仍保留对应 getter）
        try {
            java.lang.reflect.Method m = mavenProject.getClass().getMethod(getterName);
            Object value = m.invoke(mavenProject);
            return value == null ? null : value.toString();
        } catch (NoSuchMethodException e) {
            // 当前 IDEA 版本没这个 API，正常忽略
            return null;
        } catch (Exception e) {
            // 反射调用本身异常（权限/参数等），不影响主流程，吞掉返回 null
            return null;
        }
    }

    /**
     * 用反射读 maven-compiler-plugin 的 {@code <configuration>} 下指定子节点的文本。
     *
     * <p>之所以全程用反射访问 {@code MavenPlugin}/{@code Element}：避免对 IDEA Maven 插件
     * 内部类型签名做编译期硬绑，未来 API 微调（如 jdom 版本切换）时不会引入新的
     * {@link NoSuchMethodError}。仅依赖 {@link MavenProject} 这个公共入口。</p>
     *
     * @param configChildName 子节点名（如 {@code "release"}/{@code "target"}/{@code "source"}）
     * @return 节点文本；插件未声明 / 节点不存在 / 反射失败均返回 {@code null}
     * @author xumanyi
     * @date 2026-04-27
     */
    private static String readCompilerPluginConfig(MavenProject mavenProject, String configChildName) {
        try {
            // MavenProject.findPlugin(String, String) 是 IDEA Maven 模块的稳定 API
            java.lang.reflect.Method findPlugin = mavenProject.getClass()
                    .getMethod("findPlugin", String.class, String.class);
            Object plugin = findPlugin.invoke(mavenProject,
                    "org.apache.maven.plugins", "maven-compiler-plugin");
            if (plugin == null) return null;

            // MavenPlugin.getConfigurationElement() 返回 jdom Element（部分版本可能为 jdom2）
            java.lang.reflect.Method getConfig = plugin.getClass().getMethod("getConfigurationElement");
            Object configElement = getConfig.invoke(plugin);
            if (configElement == null) return null;

            // jdom Element.getChildText(String) —— 在 jdom 1.x / 2.x 里方法签名一致
            java.lang.reflect.Method getChildText = configElement.getClass()
                    .getMethod("getChildText", String.class);
            Object text = getChildText.invoke(configElement, configChildName);
            return text == null ? null : text.toString();
        } catch (NoSuchMethodException e) {
            // 任意一层 API 在当前 IDEA 版本里不可用，跳过
            return null;
        } catch (Exception e) {
            // 反射调用异常一律视为"读不到"，不影响主流程
            return null;
        }
    }

    /**
     * 在 IDEA 全局 SDK 表中找精确匹配的 Java JDK。仅认 {@link JavaSdk} 实现，
     * 排除 IBM、自定义等非标准 JDK 类型，避免版本号判定不一致。
     *
     * @return 匹配到的 SDK；不存在返回 {@code null}
     * @author xumanyi
     * @date 2026-04-27
     */
    private static Sdk findJdkByVersion(JavaSdkVersion required) {
        JavaSdk javaSdk = JavaSdk.getInstance();
        for (Sdk sdk : ProjectJdkTable.getInstance().getAllJdks()) {
            if (!(sdk.getSdkType() instanceof JavaSdk)) continue;
            JavaSdkVersion version = javaSdk.getVersion(sdk);
            if (version == required) {
                return sdk;
            }
        }
        return null;
    }

    /**
     * 把 IDEA SDK 池里所有 Java JDK 列出来（名字 + 版本），用于匹配失败时提示用户。
     *
     * @return 形如 {@code [Temurin-17 (17), JDK 1.8.0_202 (1.8)]} 的字符串
     * @author xumanyi
     * @date 2026-04-27
     */
    private static String listAvailableJdkVersions() {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        JavaSdk javaSdk = JavaSdk.getInstance();
        for (Sdk sdk : ProjectJdkTable.getInstance().getAllJdks()) {
            if (!(sdk.getSdkType() instanceof JavaSdk)) continue;
            if (!first) sb.append(", ");
            JavaSdkVersion v = javaSdk.getVersion(sdk);
            sb.append(sdk.getName());
            if (v != null) sb.append(" (").append(v.getDescription()).append(")");
            first = false;
        }
        if (first) sb.append("无可用 Java JDK");
        sb.append("]");
        return sb.toString();
    }

    /**
     * 把 pom 里的版本字符串映射成 {@link JavaSdkVersion} 枚举。容错以下输入格式：
     * <ul>
     *   <li>{@code 1.5} ~ {@code 1.9}（老式 source/target 写法）</li>
     *   <li>{@code 5} ~ {@code 25}（JEP 223 之后的简写）</li>
     *   <li>{@code 11.0.2} 这种带补丁号（取主版本）</li>
     * </ul>
     *
     * @return 识别成功的枚举值；输入为空 / 数字越界 / 枚举常量不存在返回 {@code null}
     * @author xumanyi
     * @date 2026-04-27
     */
    private static JavaSdkVersion parseJavaSdkVersion(String pomVersion) {
        if (pomVersion == null) return null;
        String s = pomVersion.trim();
        if (s.isEmpty()) return null;
        // 老式写法 1.x → 取 x；新式 17/21 等保持原样
        if (s.startsWith("1.")) {
            s = s.substring(2);
        }
        // 取前缀连续数字，兼容 "11.0.2" 这种带补丁号的写法
        int end = 0;
        while (end < s.length() && Character.isDigit(s.charAt(end))) end++;
        if (end == 0) return null;
        int major;
        try {
            major = Integer.parseInt(s.substring(0, end));
        } catch (NumberFormatException e) {
            return null;
        }
        // JavaSdkVersion 常量名规则：5~9 走 JDK_1_x，10+ 走 JDK_x
        String enumName;
        if (major >= 5 && major <= 9) {
            enumName = "JDK_1_" + major;
        } else if (major >= 10) {
            enumName = "JDK_" + major;
        } else {
            return null;
        }
        try {
            return JavaSdkVersion.valueOf(enumName);
        } catch (IllegalArgumentException e) {
            // 当前 IDEA SDK 版本枚举里没有该常量（比如 IDEA 未升级却用了 JDK 25）
            return null;
        }
    }

    /**
     * 取多个字符串中第一个非空值。封装一层只是让调用点更易读。
     *
     * @author xumanyi
     * @date 2026-04-27
     */
    private static String firstNonBlank(String... values) {
        if (values == null) return null;
        for (String v : values) {
            if (v != null && !v.isBlank()) return v;
        }
        return null;
    }

    /**
     * 旧实现：从 IDEA Project Structure 取 Project SDK。仅在 pom 未声明编译版本时作为兜底，
     * 不再作为常规路径使用（保留以避免未配 pom 编译版本的老项目突然不可用）。
     *
     * @return JDK 根目录（含 {@code bin/javac}），可直接作为 {@code JAVA_HOME}
     * @throws IllegalStateException 未配 Project SDK / 不是 Java SDK / HomePath 为空
     * @author xumanyi
     * @date 2026-04-21
     */
    private static String resolveProjectSdkHome(Project project) {
        Sdk sdk = ProjectRootManager.getInstance(project).getProjectSdk();
        if (sdk == null) {
            throw new IllegalStateException("当前项目未配置 Project SDK，"
                    + "请在 Project Structure → Project → SDK 里选择一个 JDK");
        }
        if (!(sdk.getSdkType() instanceof JavaSdkType)) {
            throw new IllegalStateException("Project SDK 不是 Java SDK（当前为 "
                    + sdk.getSdkType().getName() + "），请改用 JDK");
        }
        String homePath = sdk.getHomePath();
        if (homePath == null || homePath.isBlank()) {
            throw new IllegalStateException("Project SDK [" + sdk.getName() + "] 的 HomePath 为空");
        }
        return homePath;
    }

    /**
     * 查找 mvn 可执行文件路径。
     *
     * <p>查找口径（Windows / macOS / Linux 通用）：</p>
     * <ol>
     *   <li>IDEA {@code Settings → Build, Execution, Deployment → Build Tools → Maven} 配置的
     *       Maven Home。覆盖 Bundled Maven 3 / Use Maven Wrapper / 自定义安装路径三种情形，
     *       与 IDEA 工具窗口完全一致。</li>
     *   <li>项目内 {@code mvnw / mvnw.cmd}（自模块向上 6 层目录搜索）。
     *       项目内文件，确定性来源，不依赖任何系统环境。</li>
     * </ol>
     *
     * <p><strong>故意不再回退环境变量（{@code MAVEN_HOME} / {@code M2_HOME} / shell {@code PATH} /
     * 裸 {@code mvn}）</strong>：这些来源极易让 fork 出去的子进程拿到一个和 IDEA 不一致的 Maven
     * 安装，进而读到不同的 {@code conf/settings.xml} 与本地仓库路径，导致 SNAPSHOT parent POM
     * 在 IDE 里看得见、子进程里找不到（典型例子：customer 私服 + 自定义本地仓库的项目）。
     * 两级都失败时显式抛 {@link IllegalStateException}，让用户去 IDEA 把 Maven Home 配齐，
     * 而不是用错的 mvn 跑半天才暴雷。</p>
     *
     * @param project     IDEA 当前项目
     * @param modulePath  待打包模块绝对路径，用于向上搜索 mvnw
     * @param logCallback 命中或失败时输出对应日志
     * @return mvn 可执行文件的绝对路径
     * @throws IllegalStateException IDEA Maven 设置和项目 mvnw 均未提供有效路径
     * @author xumanyi
     * @date 2026-04-30
     */
    private static String findMvnCommand(Project project, String modulePath, Consumer<String> logCallback) {
        boolean isWindows = System.getProperty("os.name", "").toLowerCase().contains("win");
        String mvnExe = isWindows ? "mvn.cmd" : "mvn";
        String mvnwExe = isWindows ? "mvnw.cmd" : "mvnw";

        // 1. 唯一首选：IDEA Maven 设置里配置的 Maven Home。
        //    Bundled Maven 3 / Use Maven Wrapper / 自定义路径都由 MavenUtil.resolveMavenHomeFile
        //    在反射里统一解析，行为与 IDEA 工具窗口口径一致。
        Path ideaConfiguredMvn = resolveMvnFromIdeaSettings(project, mvnExe, logCallback);
        if (ideaConfiguredMvn != null) return ideaConfiguredMvn.toString();

        // 2. 项目内 mvnw 包装脚本：项目级强约束（团队共享同一 Maven 版本）的常见做法，
        //    自模块向上 6 层目录搜索；属于项目内文件，不引入任何系统环境依赖。
        Path dir = Path.of(modulePath);
        for (int i = 0; i < 6 && dir != null; i++) {
            Path mvnw = dir.resolve(mvnwExe);
            if (Files.exists(mvnw)) {
                return mvnw.toString();
            }
            dir = dir.getParent();
        }

        throw new IllegalStateException(
                "未能解析出 Maven Home，请到 Settings → Build, Execution, Deployment → Build Tools → Maven 配置。");
    }

    /**
     * 读取 IDEA {@code Settings → Build, Execution, Deployment → Build Tools → Maven} 中
     * 配置的 Maven Home，并定位到 {@code bin/mvn(.cmd)}。
     *
     * <p>插件 {@code sinceBuild = 241}（IDEA 2024.1+），全部走 {@code org.jetbrains.idea.maven}
     * bundled plugin 的强类型 API，零反射：</p>
     * <ul>
     *   <li>{@link MavenGeneralSettings#getMavenHomeType()} 返回 {@link MavenHomeType}：
     *       {@code BundledMaven3} / {@code BundledMaven4} / {@code MavenInSpecificPath} 都属于
     *       {@link StaticResolvedMavenHomeType}；{@code MavenWrapper} 不是。</li>
     *   <li>{@link MavenUtil#getMavenHomeFile(StaticResolvedMavenHomeType)} 用统一逻辑把上面三类
     *       解析成实际目录（Bundled 用 {@code idea.home.path}/plugins/maven/lib/...，自定义路径
     *       直接用其 {@code path} 字段）。</li>
     * </ul>
     *
     * <p>仅当用户在 IDEA 选了 {@code MavenWrapper} 时本方法返回 {@code null}（wrapper 解析需要
     * 项目内 {@code .mvn/wrapper/maven-wrapper.properties}，由
     * {@link #findMvnCommand} 第 2 级 mvnw 通路统一处理）。</p>
     *
     * @param project     IDEA 当前项目
     * @param mvnExe      平台对应的可执行名（{@code mvn} / {@code mvn.cmd}）
     * @param logCallback 命中 / 解析失败均输出对应日志
     * @return Maven 可执行文件绝对路径；选了 Wrapper、目录无效或可执行不存在时返回 {@code null}
     * @author xumanyi
     * @date 2026-04-30
     */
    private static Path resolveMvnFromIdeaSettings(Project project, String mvnExe,
                                                    Consumer<String> logCallback) {
        if (project == null) return null;

        MavenGeneralSettings settings = MavenProjectsManager.getInstance(project).getGeneralSettings();
        MavenHomeType homeType = settings.getMavenHomeType();

        // MavenWrapper 不是 StaticResolvedMavenHomeType，需结合项目内 .mvn 配置才能解析；
        // 让 findMvnCommand 第 2 级（项目 mvnw）处理。
        if (!(homeType instanceof StaticResolvedMavenHomeType resolved)) {
            return null;
        }

        File homeDir = MavenUtil.getMavenHomeFile(resolved);
        if (homeDir == null || !homeDir.isDirectory()) {
            return null;
        }

        Path mvn = homeDir.toPath().resolve("bin").resolve(mvnExe);
        if (!Files.exists(mvn)) {
            return null;
        }
        return mvn;
    }

    /**
     * 把 IDEA {@code Settings → Build, Execution, Deployment → Build Tools → Maven} 中
     * "用户设置文件 / 本地仓库"两项的覆盖路径转成 fork 子进程的 mvn CLI 参数。
     *
     * <p>必要性：IDEA 工具窗口跑 Maven 走 IDE 内嵌 embedder，这两项配置在 IDE 内部直接读；
     * 而插件 {@link #runMavenPackage} 通过 {@link ProcessBuilder} fork 出去的
     * {@code mvn(.cmd)} 是独立子进程，不会去问 IDEA，仅按 Maven 自身规则读取
     * {@code <MAVEN_HOME>/conf/settings.xml} 与 {@code ~/.m2/settings.xml}。
     * 用户在 IDEA 里指定的自定义 settings 文件（如 {@code flux_settings.xml}）和自定义本地
     * 仓库都不会被子进程看到，导致解析不到只在私服 + 自定义仓库里的 SNAPSHOT parent POM。
     * 故必须用 {@code -s <file>} / {@code -Dmaven.repo.local=<dir>} 显式透传。</p>
     *
     * <p>语义：仅当对应输入框在 IDEA 里勾了"重写"且填了非空路径时输出对应参数；
     * 未勾选时返回空，让 mvn 子进程走自己的默认解析。
     * 不做存在性校验：路径无效时让 mvn 自己抛错，错误信息比这里二次包装更准确。</p>
     *
     * @param project     IDEA 当前项目；为 {@code null} 时返回空 list
     * @param logCallback 命中覆盖项时输出对应路径，便于排查"为什么子进程行为不一致"
     * @return 追加到 mvn 命令尾部的参数列表；未覆盖任何项时为空 list
     * @author xumanyi
     * @date 2026-04-30
     */
    private static List<String> resolveIdeaMavenCliOverrides(Project project, Consumer<String> logCallback) {
        if (project == null) return List.of();

        MavenGeneralSettings settings = MavenProjectsManager.getInstance(project).getGeneralSettings();
        List<String> args = new ArrayList<>(4);

        String userSettings = nullToEmpty(settings.getUserSettingsFile()).trim();
        if (!userSettings.isEmpty()) {
            args.add("-s");
            args.add(userSettings);
            logCallback.accept("[编译] settings.xml: " + userSettings);
        }

        String localRepo = nullToEmpty(settings.getLocalRepository()).trim();
        if (!localRepo.isEmpty()) {
            args.add("-Dmaven.repo.local=" + localRepo);
            logCallback.accept("[编译] 本地仓库: " + localRepo);
        }

        return args;
    }

    /**
     * 执行 WAR 嵌入：下载远程 WAR（通过锁名）→ 替换内部 jar → 校验 → 上传
     *
     * @param lockRemoteDir WAR 所在远程目录（加锁时记录的目录）
     * @param lockName      WAR 的锁文件名（原文件已被 rename 为此名）
     */
    private static void executeWarEmbed(PluginDeployConfig pluginConfig,
                                         FtpTargetSelection embedTarget,
                                         Path localJar, String artifactPrefix,
                                         String lockRemoteDir, String lockName,
                                         String host, int port, String username, String password,
                                         Consumer<String> logCallback) throws Exception {
        String remoteDir = embedTarget.getRemoteDir();
        String warRelPath = embedTarget.getRelativePath();
        String warName = embedTarget.getTargetName();

        Path tempDir = Files.createTempDirectory("war-embed-");
        try {
            // 1. 下载远程 WAR（使用锁名，因为原文件已在 Phase 2 被 rename 为锁文件）
            logCallback.accept("[嵌入] 下载远程 WAR: " + warName);
            Path downloadedWar = tempDir.resolve(warName);

            try (FtpSession session = new FtpSession(host, port)) {
                session.connect(username, password);
                FtpOperations ops = new FtpOperations(session);
                String lockPath = lockRemoteDir + lockName;
                ops.download(lockPath, downloadedWar);
                logCallback.accept("[嵌入] 下载完成: " + Files.size(downloadedWar) / 1024 / 1024 + " MB");
            }

            // 2. 替换内部 JAR
            Path outputWar = tempDir.resolve("embed-" + warName);
            Path jarToEmbed = localJar;

            // 增量/自动检索模式：不整个替换 JAR，只替换修改的 class
            com.flux.deploy.plugin.model.DeployMode mode = pluginConfig.getMode();
            if (mode != null && mode != com.flux.deploy.plugin.model.DeployMode.FULL) {
                // 不再打"增量模式：从 WAR 中提取嵌入 JAR..." / "已构建增量补丁 JAR" 两行说明性日志：
                // 紧跟其后的 [补丁] 行已能体现"按 class 增量"的语义，重复说明只是噪音。
                Path extractedJar = tempDir.resolve("extracted-" + artifactPrefix + ".jar");
                extractEmbeddedJar(downloadedWar, artifactPrefix, extractedJar);

                if (Files.exists(extractedJar) && Files.size(extractedJar) > 0) {
                    // 用 StagingPackageBuilder 的变更文件列表构建 classEntries
                    Path classesDir = Path.of(pluginConfig.getModulePath(), "target", "classes");
                    List<String> changedFiles = pluginConfig.getChangedFiles();
                    // 跳过编译时 target/classes 可能根本不存在（用户首次部署纯静态资源），
                    // 只要勾选了文件就允许走 patch 路径，让 StagingPackageBuilder 自己从源目录读
                    boolean canPatch = changedFiles != null && !changedFiles.isEmpty()
                            && (pluginConfig.isSkipCompile() || Files.isDirectory(classesDir));
                    if (canPatch) {
                        StagingPackageBuilder patcher = new StagingPackageBuilder(
                                pluginConfig.getModulePath(),
                                pluginConfig.getArtifactFileName(),
                                changedFiles,
                                logCallback
                        ).setSkipCompile(pluginConfig.isSkipCompile());
                        Path patchedJar = patcher.patchExistingJar(extractedJar, tempDir);
                        if (patchedJar != null && Files.exists(patchedJar)) {
                            jarToEmbed = patchedJar;
                        }
                    }
                }
            }

            WarEmbedUtil.EmbedResult embedResult = WarEmbedUtil.embedJar(
                    downloadedWar, jarToEmbed, artifactPrefix, outputWar);

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
     * 预备份所有目标包到共享备份目录
     *
     * @return 备份目录路径（用于回滚）
     * @author xumanyi
     * @date 2026-03-27
     */
    private static String preBackupAll(
            PluginDeployConfig pluginConfig,
            List<FtpTargetSelection> allTargets,
            String host, int port, String user, String pass,
            Consumer<String> logCallback) throws Exception {

        String backupDir;
        try (FtpSession session = new FtpSession(host, port)) {
            session.connect(user, pass);
            FtpOperations ops = new FtpOperations(session);

            // 解析子系统根目录（取第 3 级）
            FtpTargetSelection firstTarget = allTargets.get(0);
            String remoteDir = firstTarget.getRemoteDir();
            String systemRoot = resolveSystemRoot(remoteDir);
            String backupParent = systemRoot + "backup/";

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
        }

        // 逐个下载目标包到备份目录。
        // 关键约束：每个文件的 download/upload/size 验证都用独立 FTP 短连接，
        // 禁止跨文件复用同一个 FtpSession——长生命周期控制通道在批量大文件传输间隙
        // 会被服务端按空闲超时关闭并返回 421。
        // 同名包分散在不同子目录时，按 relativePath 的父目录在备份目录下分子目录，避免覆盖。
        Set<String> createdBackupSubDirs = new HashSet<>();
        for (FtpTargetSelection target : allTargets) {
            String remotePath = target.getRemoteDir() + target.getRelativePath();
            String subDir = backupSubDirFor(target);
            if (!subDir.isEmpty() && createdBackupSubDirs.add(subDir)) {
                // 子目录首次出现时建一次目录，使用独立短连接
                final String subDirPath = backupDir + subDir;
                runFreshFtpSession(host, port, user, pass,
                        (s, ops) -> ops.mkdirIfAbsent(subDirPath));
            }
            String backupFilePath = backupDir + subDir + target.getTargetName();

            String displayName = (subDir.isEmpty() ? "" : subDir) + target.getTargetName();
            logCallback.accept("[备份] " + displayName + " ...");

            Path tempBackup = Files.createTempFile("backup-", "-" + target.getTargetName());
            try {
                // 用独立连接下载
                runFreshFtpSession(host, port, user, pass,
                        (s, ops) -> ops.download(remotePath, tempBackup));

                long downloadedSize = Files.size(tempBackup);
                if (downloadedSize == 0) {
                    throw new Exception("下载的备份文件为空: " + remotePath);
                }

                // 用独立连接上传，并在同一会话内立即校验远端文件大小：
                // 同一连接做"上传+校验"既能复用握手，也能更强地证明上传完成且服务端可读。
                long backupSize = withFreshFtpSession(host, port, user, pass, (s, ops) -> {
                    ops.upload(tempBackup, backupFilePath);
                    return ops.getFileSize(backupFilePath);
                });

                if (backupSize != downloadedSize) {
                    throw new Exception("备份大小不一致: " + target.getTargetName()
                            + " (下载 " + downloadedSize + " 字节, 备份 " + backupSize + " 字节)");
                }

                logCallback.accept("[备份] " + displayName
                        + " -> " + backupFilePath + " (" + formatSize(backupSize) + ")");
            } finally {
                Files.deleteIfExists(tempBackup);
            }
        }

        return backupDir;
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
                    logCallback.accept("[回滚] 已恢复: " + remotePath);
                } finally {
                    Files.deleteIfExists(tempRestore);
                }
            } catch (Exception e) {
                logCallback.accept("[回滚] 恢复失败: " + remotePath + " - " + e.getMessage());
            }
        }

        if (borrowed) {
            logCallback.accept("[回滚] 借用已有备份作为回滚源，本次保留备份文件不做清理");
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
                        logCallback.accept("[回滚] 已删除备份: " + backupFilePath);
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
                    logCallback.accept("[回滚] 备份目录已空，已删除: " + backupDir);
                } else {
                    logCallback.accept("[回滚] 备份目录中仍有 " + remaining.size()
                            + " 个其他条目，保留目录: " + backupDir);
                }
            });
        } catch (Exception e) {
            logCallback.accept("[回滚] 清理备份失败: " + e.getMessage());
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
     */
    private static void updateNoteForAll(
            PluginDeployConfig pluginConfig,
            List<FtpTargetSelection> allTargets,
            String host, int port, String user, String pass,
            Consumer<String> logCallback) throws Exception {

        java.time.format.DateTimeFormatter timeFmt =
                java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        String now = java.time.LocalDateTime.now().format(timeFmt);

        // 每个 note 文件用独立短连接（exists/download/upload 三次操作绑在一个会话里），
        // 避免共用一个长会话在多包遍历期间被服务端 421。
        for (FtpTargetSelection target : allTargets) {
            String remoteDir = target.getRemoteDir();
            // 解析实际的包所在目录（考虑 relativePath 中的子目录）
            String relPath = target.getRelativePath();
            int lastSlash = relPath.lastIndexOf('/');
            String packageDir = lastSlash > 0
                    ? remoteDir + relPath.substring(0, lastSlash + 1)
                    : remoteDir;

            // 命名兼容：优先标准命名 <全名>_update_note.txt → 其次历史命名 <去后缀>_update_note.txt
            // → 都不存在则按标准命名新建。历史命名文件原地保留追加，不迁移。
            final String canonicalNoteName = target.getTargetName() + "_update_note.txt";
            final String legacyStem = stripLastExtForNote(target.getTargetName());
            final String legacyNoteName = legacyStem + "_update_note.txt";
            final boolean legacyDistinct = !legacyNoteName.equals(canonicalNoteName);
            final String canonicalNotePath = packageDir + canonicalNoteName;
            final String legacyNotePath = packageDir + legacyNoteName;

            Path tempNote = Files.createTempFile("note-", ".txt");
            Path tempLegacy = Files.createTempFile("note-legacy-", ".txt");
            try {
                // 构造新记录：
                //   取包|yyyy-MM-dd HH:mm:ss|{开发}|任务：{任务描述}|客服：{客服号}|{包名}
                //   传包|...（同上）
                String operator = nullToEmpty(pluginConfig.getOperator());
                String taskSeg = "任务：" + nullToEmpty(pluginConfig.getTaskId());
                String customerSeg = "客服：" + nullToEmpty(pluginConfig.getCustomerId());
                final String fetchRecord = String.join("|",
                        "取包", now, operator, taskSeg, customerSeg, target.getTargetName());
                final String uploadRecord = String.join("|",
                        "传包", now, operator, taskSeg, customerSeg, target.getTargetName());

                final String[] resolvedName = new String[]{canonicalNoteName};
                runFreshFtpSession(host, port, user, pass, (s, ops) -> {
                    boolean canonExists = ops.exists(canonicalNotePath);
                    boolean legacyExists = legacyDistinct && ops.exists(legacyNotePath);

                    // 1. 决定写入路径 + base 内容（处理双文件合并）
                    String notePath;
                    String baseContent;
                    boolean shouldDeleteLegacyAfter = false;

                    if (canonExists && legacyExists) {
                        ops.download(canonicalNotePath, tempNote);
                        ops.download(legacyNotePath, tempLegacy);
                        String canonContent = Files.readString(tempNote, java.nio.charset.StandardCharsets.UTF_8);
                        String legacyContent = Files.readString(tempLegacy, java.nio.charset.StandardCharsets.UTF_8);
                        long canonBytes = canonContent.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
                        long legacyBytes = legacyContent.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;

                        logCallback.accept("[说明] 检测到双文件: " + canonicalNoteName + "(" + canonBytes
                                + "B) + " + legacyNoteName + "(" + legacyBytes + "B)");

                        if (canonContent.contains(NOTE_MERGE_MARKER_PREFIX + legacyNoteName)) {
                            logCallback.accept("[说明] canonical 已含合并标记，跳过合并，仅清理 legacy 残留");
                            baseContent = canonContent;
                        } else if (canonBytes > legacyBytes) {
                            logCallback.accept("[说明] canonical 较大，作为 base，将 legacy 追加到末尾");
                            baseContent = mergeNoteContents(canonContent, legacyContent, legacyNoteName, legacyBytes);
                        } else if (legacyBytes > canonBytes) {
                            logCallback.accept("[说明] legacy 较大，作为 base，将 canonical 追加到末尾");
                            baseContent = mergeNoteContents(legacyContent, canonContent, canonicalNoteName, canonBytes);
                        } else {
                            // 大小相等 — 默认 canonical（标准命名）作为 base
                            logCallback.accept("[说明] 两文件大小相等，canonical 作为 base，将 legacy 追加到末尾");
                            baseContent = mergeNoteContents(canonContent, legacyContent, legacyNoteName, legacyBytes);
                        }

                        notePath = canonicalNotePath;
                        resolvedName[0] = canonicalNoteName;
                        shouldDeleteLegacyAfter = true;
                    } else if (canonExists) {
                        ops.download(canonicalNotePath, tempNote);
                        baseContent = Files.readString(tempNote, java.nio.charset.StandardCharsets.UTF_8);
                        notePath = canonicalNotePath;
                        resolvedName[0] = canonicalNoteName;
                    } else if (legacyExists) {
                        ops.download(legacyNotePath, tempLegacy);
                        baseContent = Files.readString(tempLegacy, java.nio.charset.StandardCharsets.UTF_8);
                        notePath = legacyNotePath;
                        resolvedName[0] = legacyNoteName;
                        logCallback.accept("[说明] 沿用历史命名文件: " + legacyNoteName);
                    } else {
                        baseContent = "";
                        notePath = canonicalNotePath;
                        resolvedName[0] = canonicalNoteName;
                    }

                    // 2. 拼接最终内容
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

                    // 3. 上传
                    Files.writeString(tempNote, finalContent, java.nio.charset.StandardCharsets.UTF_8);
                    long expectedBytes = Files.size(tempNote);
                    ops.upload(tempNote, notePath);
                    logCallback.accept("[说明] 已上传 " + resolvedName[0] + " (" + expectedBytes + "B)");

                    // 4. 涉及删除 legacy 时，re-download 校验远端字节数
                    if (shouldDeleteLegacyAfter) {
                        Path verify = Files.createTempFile("note-verify-", ".txt");
                        try {
                            ops.download(notePath, verify);
                            long actualBytes = Files.size(verify);
                            if (actualBytes != expectedBytes) {
                                logCallback.accept("[说明] 校验失败：远端 size=" + actualBytes
                                        + " 期望=" + expectedBytes + "，保留 legacy 不删，下次重试");
                                shouldDeleteLegacyAfter = false;
                            } else {
                                logCallback.accept("[说明] 校验通过：远端 size=" + actualBytes);
                            }
                        } finally {
                            Files.deleteIfExists(verify);
                        }
                    }

                    // 5. 校验通过后才删 legacy
                    if (shouldDeleteLegacyAfter) {
                        try {
                            ops.delete(legacyNotePath);
                            logCallback.accept("[说明] 已删除历史命名残留: " + legacyNoteName);
                        } catch (Exception delFail) {
                            logCallback.accept("[说明] 历史文件删除失败（下次部署会重试）: "
                                    + legacyNoteName + " - " + delFail.getMessage());
                        }
                    }
                });

                logCallback.accept("[说明] " + resolvedName[0] + " 已追加 2 条记录");
            } finally {
                Files.deleteIfExists(tempNote);
                Files.deleteIfExists(tempLegacy);
            }
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
    private static void preUnlockAll(List<String[]> lockedPackages,
                                      String host, int port, String user, String pass,
                                      Consumer<String> logCallback) {
        if (lockedPackages.isEmpty()) return;
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
                        ftpLock.restoreLock(remoteDir, lockName);
                        logCallback.accept("[解锁] " + (origName != null ? origName : lockName)
                                + " 原文件缺失，已从锁文件恢复以避免数据丢失");
                    }
                } catch (Exception e) {
                    logCallback.accept("[解锁] 解锁失败: " + lockName + " - " + e.getMessage()
                            + "（请人工检查 FTP 上 " + (origName != null ? origName : "原文件")
                            + " 是否存在；如缺失，可从备份目录手动恢复）");
                }
            }
        } catch (Exception e) {
            logCallback.accept("[解锁] FTP 连接失败: " + e.getMessage());
        }
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

            // 命名兼容：本次部署可能写入了标准命名或历史命名，回滚时按相同优先级解析。
            final String canonicalNoteName = target.getTargetName() + "_update_note.txt";
            final String legacyStem = stripLastExtForNote(target.getTargetName());
            final String legacyNoteName = legacyStem + "_update_note.txt";
            final boolean legacyDistinct = !legacyNoteName.equals(canonicalNoteName);
            final String canonicalNotePath = packageDir + canonicalNoteName;
            final String legacyNotePath = packageDir + legacyNoteName;

            // 用于异常日志的文件名（首选标准名，待会话里解析出实际名再覆盖）
            final String[] resolvedName = new String[]{canonicalNoteName};
            try {
                Path tempNote = Files.createTempFile("note-rollback-", ".txt");
                // action[0]: null=未修改; "delete"=note 文件本次部署首次创建，已整体删除; "trim"=已截除最后 2 条记录
                final String[] action = new String[]{null};
                final String[] resolvedPath = new String[]{null};
                try {
                    boolean modified = withFreshFtpSession(host, port, user, pass, (s, ops) -> {
                        String notePath;
                        if (ops.exists(canonicalNotePath)) {
                            notePath = canonicalNotePath;
                            resolvedName[0] = canonicalNoteName;
                        } else if (legacyDistinct && ops.exists(legacyNotePath)) {
                            notePath = legacyNotePath;
                            resolvedName[0] = legacyNoteName;
                        } else {
                            return Boolean.FALSE;
                        }
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
                            logCallback.accept("[回滚] " + resolvedName[0] + " 已删除（本次部署首次创建）");
                        } else {
                            logCallback.accept("[回滚] " + resolvedName[0] + " 已移除最后 2 条记录");
                        }
                    }
                } finally {
                    Files.deleteIfExists(tempNote);
                }
            } catch (Exception e) {
                logCallback.accept("[回滚] " + resolvedName[0] + " 回滚失败: " + e.getMessage());
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
                String systemRoot = resolveSystemRoot(t.getRemoteDir());
                String subDir = backupSubDirFor(t);
                String backupPath = systemRoot + "backup/" + dateStr + "_" + operator
                        + "/" + subDir + t.getTargetName();
                boolean exists = ops.exists(backupPath);
                if (logCallback != null) {
                    logCallback.accept("[备份检查] " + (exists ? "✓ 已存在" : "✗ 不存在") + " " + backupPath);
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
                changedFiles, logCallback)
                .setSkipCompile(pluginConfig.isSkipCompile());
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
        String systemRoot = resolveSystemRoot(allTargets.get(0).getRemoteDir());
        String dateStr = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        return systemRoot + "backup/" + dateStr + "_" + pluginConfig.getOperator() + "/";
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
     * 从文件名提取 artifactId 前缀
     * 例：scev6-utils-tms-10.0.0-SNAPSHOT.jar → scev6-utils-tms
     */
    /**
     * 从 WAR 包中提取匹配的嵌入 JAR 文件
     */
    private static void extractEmbeddedJar(Path warFile, String artifactPrefix, Path outputJar) throws Exception {
        try (java.util.jar.JarFile jar = new java.util.jar.JarFile(warFile.toFile())) {
            java.util.Enumeration<java.util.jar.JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                java.util.jar.JarEntry entry = entries.nextElement();
                String name = entry.getName();
                if (name.startsWith("WEB-INF/lib/") && !entry.isDirectory()) {
                    String libName = name.substring("WEB-INF/lib/".length());
                    if (libName.startsWith(artifactPrefix) && libName.endsWith(".jar")) {
                        try (java.io.InputStream is = jar.getInputStream(entry)) {
                            Files.copy(is, outputJar, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                        }
                        return;
                    }
                }
            }
        }
        throw new Exception("WAR 中未找到匹配 [" + artifactPrefix + "] 的嵌入 JAR");
    }

    private static String extractArtifactPrefix(String fileName) {
        if (fileName == null) return null;
        String name = fileName;
        int dotIdx = name.lastIndexOf('.');
        if (dotIdx > 0) name = name.substring(0, dotIdx);
        for (int i = 1; i < name.length(); i++) {
            if (name.charAt(i - 1) == '-' && Character.isDigit(name.charAt(i))) {
                return name.substring(0, i - 1);
            }
        }
        return name;
    }
}
