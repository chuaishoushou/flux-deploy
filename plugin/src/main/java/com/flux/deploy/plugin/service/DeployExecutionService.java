package com.flux.deploy.plugin.service;

import com.flux.deploy.deploy.CancellationToken;
import com.flux.deploy.deploy.DeployPipeline;
import com.flux.deploy.deploy.ResidualLockDiagnosis;
import com.flux.deploy.deploy.ResidualLockResolver;
import com.flux.deploy.deploy.gates.NoteCharsetReader;
import com.flux.deploy.deploy.gates.NoteFileNames;
import com.flux.deploy.ftp.FtpLock;
import com.flux.deploy.ftp.FtpOperations;
import com.flux.deploy.ftp.FtpSession;
import com.flux.deploy.ftp.RetryPolicy;
import com.flux.deploy.ftp.RetryUserPrompter;
import com.flux.deploy.model.DeployConfig;
import com.flux.deploy.model.DeployResult;
import com.flux.deploy.plugin.model.DeployMode;
import com.flux.deploy.plugin.model.FtpTargetSelection;
import com.flux.deploy.plugin.model.PluginDeployConfig;
import com.flux.deploy.plugin.util.LogInterceptor;
import com.flux.deploy.util.WarEmbedUtil;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
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

    /**
     * 本次部署生成的 Vue 模块 zip 临时目录登记表（每次 buildModuleZip 一个独立目录）。
     *
     * <p>与 {@link #backupLocalCopies} 同一生命周期模式：Phase 0.5 生成时登记，
     * {@link #execute} finally 阶段统一删除（zip + 所在临时目录），不论成功 / 失败 / 取消。
     * 静态字段安全性同上——deploy 任务已由 UI 入口串行化。</p>
     */
    private static final java.util.List<Path> vueZipTempDirs =
            java.util.Collections.synchronizedList(new ArrayList<>());

    /**
     * 清理本次部署生成的 Vue 模块 zip 临时目录。
     *
     * <p>删除失败静默忽略，OS 会周期性清理系统临时目录。</p>
     *
     * @author xumanyi
     * @date 2026-08-13
     */
    private static void clearVueZipTempDirs() {
        synchronized (vueZipTempDirs) {
            for (Path dir : vueZipTempDirs) {
                if (dir == null) continue;
                try (var stream = Files.walk(dir)) {
                    stream.sorted(java.util.Comparator.reverseOrder())
                            .forEach(p -> { try { Files.deleteIfExists(p); } catch (Exception ignored) {} });
                } catch (Exception ignored) {
                    // 目录已不存在或遍历失败都无碍
                }
            }
            vueZipTempDirs.clear();
        }
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
            logCallback.accept("已回滚 " + succeeded.size() + " 个包，曾上传成功：");
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
            logCallback.accept((keep ? "保留" : "已回滚") + " 0 个包，停止时尚无包成功");
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

    // 编译相关辅助（logArtifactInfo / runMavenPackage / JDK 探测 / Maven 配置探测）
    // 已整体移除：本插件不再触发任何编译，target/ 下的产物由用户自行准备，
    // 缺失时由 UI 层 ArtifactPresenceValidator 在点击部署前以弹窗提前拒绝。

    /**
     * 部署前把所有编辑器中未保存的修改写入磁盘
     *
     * <p>Git 变更面板对"编辑器已改、尚未落盘"的文件同样显示为已变更，
     * 但打包直读磁盘源文件——不先保存会把改动前的旧内容打进包
     * （实测：CSV 新增行未保存时，行级增量合并如实报告零变化，改动被静默遗漏）。</p>
     *
     * <p>线程约束：保存要求"EDT + write-intent 锁 + write-safe 上下文"三者齐备。
     * 本方法必须从<b>后台线程</b>调用（部署后台任务开头）——{@code invokeAndWait}
     * 从后台切回 EDT 的代码天然处于 write-safe 上下文；若在 EDT 的 invokeLater
     * 回调里直接保存，会触发 TransactionGuard 的 "Write-unsafe context" 报错。
     * {@code WriteIntentReadAction}（241 起可用）负责补齐 write-intent 锁。
     * 保存动作任何异常只降级为警告（退回改动未落盘的旧行为），绝不中断部署。</p>
     *
     * @param logCallback 日志回调（保存失败时提醒用户手动保存）
     * @author xumanyi
     * @date 2026-07-12
     */
    private static void saveAllDocumentsBeforeDeploy(Consumer<String> logCallback) {
        Runnable rawSave = () ->
                com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().saveAllDocuments();
        Runnable onEdt = () -> {
            try {
                // 新平台（2024.2+）EDT 上不再自动持 write-intent 锁，需显式补；
                // WriteIntentReadAction 自 241 起提供，本插件全部受支持版本均可用。
                com.intellij.openapi.application.WriteIntentReadAction.run((Runnable) rawSave);
            } catch (LinkageError apiGone) {
                // 版本兼容兜底：万一未来某版本移除/改签名该实验 API（LinkageError），
                // 退回旧平台可用的直接保存（旧平台 EDT 本就持锁）。
                rawSave.run();
            }
        };
        try {
            com.intellij.openapi.application.Application app =
                    com.intellij.openapi.application.ApplicationManager.getApplication();
            if (app.isDispatchThread()) {
                // 兜底路径：EDT 直调无法保证 write-safe，仅在误用时保持"能保存"的行为
                onEdt.run();
            } else {
                app.invokeAndWait(onEdt);
            }
        } catch (Throwable t) {
            logCallback.accept("WARN  [部署] 自动保存未保存文件失败，请先手动保存再部署："
                    + t.getClass().getSimpleName()
                    + (t.getMessage() == null ? "" : " " + t.getMessage()));
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
     * 上次回滚是否留有未恢复的文件（备份仍完整保留、清单可重试）。
     *
     * <p>为 true 时远端处于"部分是新版本"的状态，而这些文件的原始版本只存在于备份里：
     * 既不能被新一次部署的复位逻辑抹掉回滚清单，也不该让用户在没有被告知的情况下
     * 用"覆盖备份"策略把唯一的原始版本冲掉。</p>
     */
    private static volatile boolean lastRollbackIncomplete;

    /**
     * 预检阶段用户为"模糊匹配不到版本记录文件"的包手动指定的 note 文件名。
     *
     * <p>key = {@code remoteDir + relativePath}（目标包全路径，与 uploadFinishTimes 的 key 口径一致）；
     * value = 用户在 {@link com.flux.deploy.plugin.toolwindow.NoteFileSelectDialog} 里选定的文件名
     * （可能是目录里已有文件，也可能是要新建的名字——写入逻辑统一"存在即追加、不存在即新建"，无需区分）。</p>
     *
     * <p>生命周期：每次预检（dryRun）开始时清空，由 note 审计按需重新登记；
     * 紧随其后的真部署（执行更新链路必先预检）在写版本记录时消费。</p>
     */
    private static final java.util.concurrent.ConcurrentHashMap<String, String> manualNoteSelections =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * 上次成功部署时 {@link #manualNoteSelections} 的快照，供手动回滚精确定位 note 文件。
     *
     * <p>手动指定的文件名可能完全不符合模糊匹配谓词，回滚时若仍按谓词扫描会找不到；
     * 部署登记 lastAllTargets 时同步快照，避免后续新预检清空活表后旧部署无法回滚。</p>
     */
    private static volatile java.util.Map<String, String> lastManualNoteSelections =
            java.util.Collections.emptyMap();

    /**
     * 回滚清单条目（String[]{remotePath, backupPath, mark}）第三元素的「新建目标」标记。
     *
     * <p>新建目标（Vue 模块 zip 首次投放）远端原本没有文件：无备份路径，回滚动作是
     * 删除已上传的新文件。覆盖型目标 + 用户跳过备份的条目同样 backupPath=null，但绝不能删
     * （原包已被覆盖，删除等于丢包），两者靠本标记区分。</p>
     */
    private static final String CREATE_NEW_ENTRY_MARK = "NEW";

    /**
     * 判断回滚清单条目是否为「新建目标」条目
     *
     * @param pair 条目（[remotePath, backupPath] 或 [remotePath, backupPath, mark]）
     * @return true 表示新建目标（回滚 = 删除远端新文件）
     * @author xumanyi
     * @date 2026-08-13
     */
    private static boolean isCreateNewEntry(String[] pair) {
        return pair != null && pair.length > 2 && CREATE_NEW_ENTRY_MARK.equals(pair[2]);
    }

    /**
     * 取回滚清单条目记录的原始修改时间（MDTM UTC 串，更新前抓取）
     *
     * @param pair 回滚清单条目
     * @return 原始修改时间；未记录时返回 {@code null}
     * @author xumanyi
     * @date 2026-08-14
     */
    private static String originalMtimeOf(String[] pair) {
        return pair != null && pair.length > 3 ? pair[3] : null;
    }

    /**
     * 判断回滚清单中是否含「新建目标」条目
     *
     * @param updatedPackages 回滚清单（可为 null）
     * @return true 表示至少有一个新建目标条目
     * @author xumanyi
     * @date 2026-08-13
     */
    private static boolean hasCreateNewEntry(List<String[]> updatedPackages) {
        if (updatedPackages == null) return false;
        synchronized (updatedPackages) {
            for (String[] pair : updatedPackages) {
                if (isCreateNewEntry(pair)) return true;
            }
        }
        return false;
    }

    /**
     * 带一次重试的短连接执行：失败后换新连接立即重试一次。
     *
     * <p>大批量逐文件操作（整包回滚 482 个文件级恢复）下，服务端对高频重连偶发
     * 直接断开（Connection closed without indication）——瞬时故障，换连接重试即恢复。</p>
     *
     * @param host FTP 主机
     * @param port FTP 端口
     * @param user FTP 用户名
     * @param pass FTP 密码
     * @param body 会话内操作
     * @throws Exception 重试后仍失败
     * @author xumanyi
     * @date 2026-08-13
     */
    private static void runFreshFtpSessionWithRetry(String host, int port, String user, String pass,
            FtpVoidAction body) throws Exception {
        try {
            runFreshFtpSession(host, port, user, pass, body);
        } catch (Exception first) {
            runFreshFtpSession(host, port, user, pass, body);
        }
    }

    /**
     * 判断某远端路径是否在本次部署中真实完成过上传（进行中回滚的删除保护）。
     *
     * <p>新建目标条目在 Phase 1 就登记进回滚清单，早于实际上传：中途失败触发的
     * rollbackAll 不能凭条目就删远端文件——若本次尚未上传（或他人抢先创建了同名文件），
     * 删除动作会误伤不属于本次部署的字节。仅 {@link #recordSucceededUpload} 登记过的
     * 路径可删。任务级 {@code currentSucceededUploads} 已清空时（如部署结束后的
     * 手动回滚路径）返回 true——那时回滚清单本身只含成功上传的目标。</p>
     *
     * @param remotePath 远端绝对路径
     * @return true 表示本次部署确实上传过该路径（或已无任务级登记可查）
     * @author xumanyi
     * @date 2026-08-13
     */
    private static boolean wasUploadedThisRun(String remotePath) {
        List<String[]> sink = currentSucceededUploads;
        if (sink == null) return true;
        synchronized (sink) {
            for (String[] rec : sink) {
                if (rec != null && rec.length > 0 && remotePath.equals(rec[0])) {
                    return true;
                }
            }
        }
        return false;
    }

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
        if (!hasRollbackData()) {
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
                if (lastBackupDir != null) {
                    logCallback.accept("INFO  [回滚] 备份目录：" + lastBackupDir);
                }
                logCallback.accept("INFO  [回滚] 需要恢复 " + lastUpdatedPackages.size() + " 个包");

                int restoreSuccess = 0;
                int restoreFail = 0;
                int skippedNoBackup = 0;
                // 未能恢复的条目：备份保留、登记为下一次「回滚」只重做这些文件
                List<String[]> failed = new ArrayList<>();

                // 1. 恢复所有包：逐文件独立短连接，与 preBackupAll / rollbackAll 一致，
                //    避免长会话在多包传输间隙被服务端 421。
                for (String[] pair : lastUpdatedPackages) {
                    final String remotePath = pair[0];
                    final String backupFilePath = pair[1];
                    if (isCreateNewEntry(pair)) {
                        // 新建目标：回滚 = 删除本次首次投放的新文件
                        try {
                            runFreshFtpSessionWithRetry(ftpHost, ftpPort, ftpUsername, ftpPassword,
                                    (s, ops) -> {
                                        if (ops.exists(remotePath)) {
                                            ops.delete(remotePath);
                                        }
                                    });
                            logCallback.accept("INFO  [回滚] 已删除新建文件: " + remotePath);
                            restoreSuccess++;
                        } catch (Exception e) {
                            logCallback.accept("WARN  [回滚] 删除新建文件失败: " + remotePath
                                    + " - " + e.getMessage());
                            restoreFail++;
                            failed.add(pair.clone());
                        }
                        continue;
                    }
                    if (backupFilePath == null) {
                        // 覆盖型目标但无备份：无从恢复。登记阶段已过滤（无备份时只登记新建目标），
                        // 走到这里属防御路径，如实告警而不是静默跳过
                        logCallback.accept("WARN  [回滚] " + remotePath
                                + " 无备份，无法恢复，远端保持当前版本");
                        skippedNoBackup++;
                        continue;
                    }
                    try {
                        // 逐环节校验字节数（备份存在 → 下载完整 → 远端写回完整），半截恢复不算成功
                        restoreFileFromBackup(backupFilePath, remotePath, originalMtimeOf(pair),
                                ftpHost, ftpPort, ftpUsername, ftpPassword, logCallback);
                        logCallback.accept("INFO  [回滚] 已恢复: " + remotePath);
                        restoreSuccess++;
                    } catch (Exception e) {
                        logCallback.accept("WARN  [回滚] 恢复失败: " + remotePath + " - " + e.getMessage());
                        restoreFail++;
                        failed.add(pair.clone());
                    }
                }

                // 2. 回滚版本记录
                if (lastUpdatedNote && lastAllTargets != null) {
                    logCallback.accept("INFO  [回滚] 回滚版本记录...");
                    rollbackNotes(lastAllTargets,
                            ftpHost, ftpPort, ftpUsername, ftpPassword, logCallback);
                }

                // 3. 清理备份（借用已有备份时跳过此步，老备份不是本次创建的；
                //    全部为新建目标时没有备份目录，也无需清理；
                //    有文件未恢复成功时绝不清理——备份是它们唯一的原始版本）
                if (restoreFail > 0) {
                    logCallback.accept("WARN  [回滚] 有文件未恢复成功，本次不清理备份");
                } else if (lastBackupBorrowed) {
                    logCallback.accept("INFO  [回滚] 借用已有备份作为回滚源，保留备份文件不做清理");
                } else if (lastBackupDir != null) {
                    // 清理阶段全是命令操作，无数据传输，复用一个短连接安全。
                    try {
                        runFreshFtpSession(ftpHost, ftpPort, ftpUsername, ftpPassword, (s, ops) -> {
                            for (String[] pair : lastUpdatedPackages) {
                                String backupFilePath = pair[1];
                                if (backupFilePath == null) continue;
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
                if (restoreFail > 0 || skippedNoBackup > 0) {
                    StringBuilder sb = new StringBuilder("WARN  [回滚] 回滚部分完成，成功 ")
                            .append(restoreSuccess).append(" 个");
                    if (restoreFail > 0) sb.append("，失败 ").append(restoreFail).append(" 个");
                    if (skippedNoBackup > 0) {
                        sb.append("，").append(skippedNoBackup).append(" 个无备份未恢复");
                    }
                    logCallback.accept(sb.toString());
                } else {
                    logCallback.accept("INFO  [回滚] 回滚全部完成，已恢复 " + restoreSuccess + " 个包");
                }

                if (!failed.isEmpty()) {
                    // 有失败：备份已保留，只把失败条目留作下一次回滚的数据（成功的不再重做），
                    // 版本记录已在上面回滚过一次，下次不再重复
                    keepBackupForFailedRestores(failed, lastBackupDir, lastBackupBorrowed, logCallback);
                } else {
                    // 全部成功才清除回滚信息（只能回滚一次）
                    clearRollbackData();
                }

                SwingUtilities.invokeLater(onComplete);
            }
        });
    }

    /**
     * 清除「上次部署」的回滚数据（回滚入口据此置灰）。
     *
     * <p>用于回滚已执行完的场景——留着旧指针会让用户再点一次回滚，
     * 用已经用过的备份把远端又盖一遍。</p>
     *
     * @author xumanyi
     * @date 2026-08-14
     */
    public static void clearRollbackData() {
        lastBackupDir = null;
        lastUpdatedPackages = null;
        lastAllTargets = null;
        lastUpdatedNote = false;
        lastBackupBorrowed = false;
        lastRollbackIncomplete = false;
    }

    /**
     * 上次回滚是否留有未恢复的文件（远端处于部分新版本、原始版本只在备份里）
     *
     * @return true 表示有未完成的回滚，UI 应在开始新一次更新前提醒用户
     * @author xumanyi
     * @date 2026-09-23
     */
    public static boolean hasIncompleteRollback() {
        return lastRollbackIncomplete;
    }

    /**
     * 未完成回滚的一句话说明（文件数 + 备份目录），供 UI 提示使用
     *
     * @return 说明文本；没有未完成回滚时返回 null
     * @author xumanyi
     * @date 2026-09-23
     */
    public static String incompleteRollbackSummary() {
        if (!lastRollbackIncomplete) return null;
        int n = lastUpdatedPackages == null ? 0 : lastUpdatedPackages.size();
        return "上次回滚有 " + n + " 个文件未恢复成功"
                + (lastBackupDir == null ? "" : "，它们的原始版本只保留在备份目录 " + lastBackupDir);
    }

    /**
     * 是否有可回滚的部署
     *
     * @return {@code true} 表示存在可回滚的部署记录
     * @author xumanyi
     * @date 2026-03-27
     */
    public static boolean hasRollbackData() {
        if (lastUpdatedPackages == null || lastUpdatedPackages.isEmpty()) return false;
        // 有备份可恢复，或含新建目标（可删除新文件）——两者任一即可回滚
        return lastBackupDir != null || hasCreateNewEntry(lastUpdatedPackages);
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
    /** 当前部署任务每个目标"上传/嵌入完成"的实际时刻，按 remoteDir+relativePath 索引；供版本记录的传包时间使用。null 表示当前无部署任务。 */
    private static volatile java.util.concurrent.ConcurrentMap<String, java.time.LocalDateTime> currentUploadFinishTimes;
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
     * UI 读取：上次部署是否有备份可用于回滚。
     *
     * <p>无备份也可能有回滚数据（本次新增文件的回滚 = 删除，不依赖备份）；
     * 回滚确认框据此切换文案，如实说明能力边界。</p>
     *
     * @return true 表示上次部署登记了备份目录
     * @author xumanyi
     * @date 2026-08-14
     */
    public static boolean lastRunHadBackup() { return lastBackupDir != null; }

    /**
     * UI 读取：上次部署回滚清单中"新建文件"条目数（回滚 = 删除这些文件）
     *
     * @return 新建条目数；无回滚数据时为 0
     * @author xumanyi
     * @date 2026-08-14
     */
    public static int lastRunNewFileCount() {
        List<String[]> snap = lastUpdatedPackages;
        if (snap == null) return 0;
        int n = 0;
        synchronized (snap) {
            for (String[] pair : snap) {
                if (isCreateNewEntry(pair)) n++;
            }
        }
        return n;
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

    /** 当前是否为 Vue 目录直更任务（停止弹窗据此切换为"整体回滚"语义） */
    private static volatile boolean currentVueDirDeploy;

    /**
     * UI 弹窗读取：当前任务是否为 Vue 目录直更。
     *
     * <p>Vue 目录直更的成功登记是<b>逐文件</b>的（删除保护需要），不能按"包"展示；
     * 且其停止语义为整体回滚（不支持保留部分模块），停止弹窗应只给
     * 「继续 / 停止并回滚全部」两项。</p>
     *
     * @return true 表示 Vue 目录直更任务进行中
     * @author xumanyi
     * @date 2026-08-14
     */
    public static boolean isCurrentVueDirDeploy() { return currentVueDirDeploy; }

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
        // 记录该包"上传/嵌入完成"的实际时刻，供版本记录的传包时间使用（每包独立，多包不串味）
        java.util.concurrent.ConcurrentMap<String, java.time.LocalDateTime> finishTimes = currentUploadFinishTimes;
        if (finishTimes != null) {
            finishTimes.put(rp, java.time.LocalDateTime.now());
        }
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
                && !backupBorrowed
                && (backupDir != null || hasCreateNewEntry(updatedPackages))) {
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
                            // 原样保留条目（含新建目标的 NEW 标记），事后手动回滚才能按正确语义处理
                            kept.add(pair.clone());
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
        if ((backupDir != null || hasCreateNewEntry(updatedPackages))
                && !updatedPackages.isEmpty()) {
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
                // 本地打包同样直读源文件，先把编辑器里未保存的修改落盘（后台线程发起才是 write-safe）
                saveAllDocumentsBeforeDeploy(logCallback);
                try {
                    // 1. 工程基础校验（编译产物存在性已在 UI 层 ArtifactPresenceValidator 提前拒绝过，这里只兜底）
                    if (pluginConfig.getModulePath() == null) {
                        logCallback.accept("ERROR [部署] 未指定工程");
                        onComplete.accept(null);
                        return;
                    }

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

        // 复位本次任务的"用户停止"上下文：模式 / 实时成功列表 / 完成时刻表 / 总数 / dryRun 标志
        currentCancelMode = CancelMode.NONE;
        currentSucceededUploads = java.util.Collections.synchronizedList(new ArrayList<>());
        currentUploadFinishTimes = new java.util.concurrent.ConcurrentHashMap<>();
        currentTotalTargets = 0;
        currentDryRun = pluginConfig.isDryRun();

        // 清空备份-嵌入复用副本登记表：上一次 deploy 残留的副本（如有）已由对应 finally 清理，
        // 这里再 clear 一次保证本次从空状态开始
        clearBackupLocalCopies();
        clearVueZipTempDirs();

        // 复位上一次部署的回滚数据：若本次部署中途失败而未登记新的 lastBackupDir，
        // 旧的残留指针会让 hasRollbackData() 误判为可回滚，用户点回滚会用旧备份覆盖
        // 本次已动过的远端文件（漏洞 H7）。新一次部署必须在登记前先清旧。
        // 例外：上次回滚有文件没恢复成功——那份清单指向的是"远端还停在新版本、
        // 原始版本只在备份里"的真实待办，抹掉就等于放弃这些文件的恢复途径，必须留着
        if (lastRollbackIncomplete) {
            logCallback.accept("WARN  [回滚] 上次回滚仍有 "
                    + (lastUpdatedPackages == null ? 0 : lastUpdatedPackages.size())
                    + " 个文件未恢复，保留其可重试的回滚清单与备份");
        } else {
            lastBackupDir = null;
            lastUpdatedPackages = null;
            lastAllTargets = null;
            lastUpdatedNote = false;
            lastBackupBorrowed = false;
        }
        lastManualNoteSelections = java.util.Collections.emptyMap();

        // 预检开始即清空手动指定的 note 文件登记：本次预检的 note 审计按需重新登记，
        // 紧随其后的真部署（dryRun=false）不清，消费预检留下的选择结果
        if (pluginConfig.isDryRun()) {
            manualNoteSelections.clear();
        }

        // 包裹 onComplete：任务结束（正常 / 异常 / 取消）后清理停止上下文，避免下次部署残留状态
        Consumer<DeployResult> onComplete = result -> {
            try {
                onCompleteRaw.accept(result);
            } finally {
                currentCancelMode = CancelMode.NONE;
                currentSucceededUploads = null;
                currentUploadFinishTimes = null;
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

                // 0. 部署前先把编辑器里未保存的修改落盘：Git 变更面板对未保存的编辑同样显示
                //    "已变更"，但打包读取的是磁盘文件，不先保存会把改动前的旧内容打进包。
                //    必须从后台线程发起（invokeAndWait 切回 EDT 才是 write-safe 上下文，
                //    在 EDT 的 invokeLater 回调里直接保存会触发 TransactionGuard 报错）。
                saveAllDocumentsBeforeDeploy(logCallback);

                // 1. 编译项目（预检跳过编译，只检查 FTP 状态；
                //    插件不再触发任何 mvn / IDE 编译。target/classes 与 target/<artifact> 的存在性
                //    已由 UI 层 ArtifactPresenceValidator 在点击部署前以弹窗强制要求过。
                //    原"[预检] 跳过编译检查..."属于流程已定义的固有行为，每次都打无信息增量，已删。

                // 1.5 收集 CSV 行级增量合并输入（git 基线 + 主键字典）；
                //     无 CSV 变更时为 null（零开销），收集失败只降级为整份覆盖不中断
                pluginConfig.setCsvMergePlan(CsvMergePlanBuilder.build(
                        project, pluginConfig.getModulePath(),
                        pluginConfig.getChangedFiles(), logCallback));

                // 2+3: 每个主目标的暂存包 / aligned war 放到 Phase 3 上传循环里按目标逐个构建
                //       这里仅构建一份不含主目标本地文件的基础 DeployConfig 供预检使用
                DeployConfig config = buildDeployConfig(pluginConfig, ftpHost, ftpPort, ftpUsername, ftpPassword, null);

                // 验证本地文件存在（预检时跳过，无主目标时跳过；INCREMENTAL 暂存包路径在
                // Phase 3 内逐个构建，到这一步 localFiles 仍是 buildDeployConfig 给出的 target/<artifact>.jar
                // 占位，FULL 模式上传它，INCREMENTAL 会被 staging 覆盖）
                if (!config.isDryRun() && pluginConfig.getTarget() != null
                        && pluginConfig.getMode() == DeployMode.FULL
                        && pluginConfig.getSourceProjectType()
                                != com.flux.deploy.plugin.model.SourceProjectType.VUE) {
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
                            logCallback.accept("INFO  [预检] 本地产物 " + lf.getFileName() + "，" + Files.size(lf) / 1024 + " KB");
                        } catch (java.io.IOException ignored) {
                            logCallback.accept("INFO  [预检] 本地产物 " + lf.getFileName());
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
                        // Vue 源：主目标是服务包里的模块目录（可能含「新建投放」目标），
                        // 不走单文件 pipeline 预检，改为逐个核对远端目录状态：
                        // 覆盖目标要求远端存在，新建目标要求远端不存在；非目录目标（zip）直接拦截
                        if (pluginConfig.getSourceProjectType()
                                == com.flux.deploy.plugin.model.SourceProjectType.VUE) {
                            DeployResult vueResult = preCheckVueTargets(
                                    pluginConfig, ftpHost, ftpPort, ftpUsername, ftpPassword, logCallback);
                            if (vueResult.isSuccess() && pluginConfig.isUpdateNote()) {
                                NoteAuditFailure noteFail = auditNoteFiles(project, pluginConfig,
                                        ftpHost, ftpPort, ftpUsername, ftpPassword, logCallback);
                                if (noteFail != null) {
                                    DeployResult failed = new DeployResult();
                                    failed.addError("note-audit", noteFail.pkgName, noteFail.reason);
                                    vueResult = failed;
                                }
                            }
                            if (vueResult.isSuccess()) {
                                if (pluginConfig.isUpdateNote()) {
                                    logCallback.accept("INFO  [预检] 版本记录文件检查通过");
                                }
                                logCallback.accept("INFO  [预检] 通过");
                            } else {
                                logCallback.accept("INFO  [预检] 未通过："
                                        + extractFirstErrorMessage(vueResult));
                            }
                            onComplete.accept(vueResult);
                            return;
                        }
                        // 外层 panel 已输出 "开始预检..."，此处不重复
                        FtpTargetSelection mainTarget0 = pluginConfig.getTarget();
                        List<FtpTargetSelection> embedTargets0 = pluginConfig.getEmbedTargets();
                        boolean hasEmbed0 = embedTargets0 != null && !embedTargets0.isEmpty();

                        if (mainTarget0 != null) {
                            // 有主目标：用 pipeline 预检
                            applyCancellationToken(config);
                            DeployPipeline pipeline = new DeployPipeline(config);
                            DeployResult result = pipeline.execute();
                            // Note 审计：当用户勾选了「更新版本记录」且前置预检通过时，
                            // 扫描每个目标包目录的 .txt 候选：命中 ≥2 个弹冲突框 + 预检失败；
                            // 命中 0 个弹选择框让用户手动指定 / 新建，取消则预检失败。
                            if (result != null && result.isSuccess() && pluginConfig.isUpdateNote()) {
                                NoteAuditFailure noteFail = auditNoteFiles(project, pluginConfig,
                                        ftpHost, ftpPort, ftpUsername, ftpPassword, logCallback);
                                if (noteFail != null) {
                                    DeployResult failed = new DeployResult();
                                    failed.addError("note-audit", noteFail.pkgName, noteFail.reason);
                                    result = failed;
                                }
                            }
                            // 总结日志：成功 / 失败一律输出一行
                            if (result != null && result.isSuccess()) {
                                logCallback.accept("INFO  [预检] 远端目标包均存在，可正常更新");
                                if (pluginConfig.isUpdateNote()) {
                                    logCallback.accept("INFO  [预检] 版本记录文件检查通过");
                                }
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
                                    // Note 审计：同上，勾选了「更新版本记录」才扫
                                    NoteAuditFailure noteFail = pluginConfig.isUpdateNote()
                                            ? auditNoteFiles(project, pluginConfig,
                                                    ftpHost, ftpPort, ftpUsername, ftpPassword, logCallback)
                                            : null;
                                    if (noteFail != null) {
                                        logCallback.accept("INFO  [预检] 未通过："
                                                + noteFail.reason + " - " + noteFail.pkgName);
                                        r.addError("note-audit", noteFail.pkgName, noteFail.reason);
                                    } else {
                                        logCallback.accept("INFO  [预检] 远端目标包均存在，版本记录文件检查通过");
                                        logCallback.accept("INFO  [预检] 通过");
                                        r.markSuccess();
                                    }
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

                    // === Vue 目录直更模式 ===
                    // 客服 FTP 上 Vue 服务包的主流形态是解包目录（{content}/{模块号}/...）：
                    // 目标树勾选的是模块目录时走独立链路（逐文件备份 → 覆盖上传 → 逐文件校验 →
                    // 版本记录 → 失败回滚），不经过单文件 pipeline。本地打包（localOnly）仍走
                    // 下方通用流程按模块出 zip。
                    {
                        List<FtpTargetSelection> vueMains = pluginConfig.getMainTargets();
                        boolean hasDirTargets = vueMains.stream()
                                .anyMatch(FtpTargetSelection::isVueModuleDir);
                        boolean vueSource = pluginConfig.getSourceProjectType()
                                == com.flux.deploy.plugin.model.SourceProjectType.VUE;
                        if (vueSource && !pluginConfig.isLocalOnly()) {
                            // Vue 更新只认服务包目录：没有模块目录目标 / 混进 zip 目标都明确失败，
                            // 绝不退回 zip 上传链路（曾把模块 zip 投到系统根目录）
                            boolean mixed = vueMains.stream().anyMatch(t -> !t.isVueModuleDir());
                            if (!hasDirTargets || mixed) {
                                logCallback.accept("ERROR [部署] Vue 更新只支持服务包目录（模块目录逐文件覆盖）："
                                        + (hasDirTargets ? "目标里混有 zip，请取消勾选 zip 后重试"
                                                : "未勾选任何模块目录目标，请先定位服务包目录"));
                                logFailureSummary(logCallback, "Vue 目标形态不符，未执行任何远端变更");
                                onComplete.accept(null);
                                return;
                            }
                            runVueDirDeploy(pluginConfig, vueMains,
                                    ftpHost, ftpPort, ftpUsername, ftpPassword,
                                    logCallback, onComplete);
                            return;
                        }
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

                    logCallback.accept("INFO  [部署] 开始执行更新，共 " + allTargets.size() + " 个目标"
                            + (mainTargets.size() > 1 ? "，其中 " + mainTargets.size() + " 个同名主目标" : ""));
                    // 单目标时不再逐个列出（后续准备/上传阶段会带路径）；多目标时列出有助于全局确认
                    if (allTargets.size() > 1) {
                        for (FtpTargetSelection t : allTargets) {
                            logCallback.accept("INFO  [部署] " + t.getRelativePath());
                        }
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
                        // "主目标"是内部术语，对用户透出为"本地更新包"更直观（jar 类目标在此构建，war 嵌入目标走 [嵌入] 阶段）
                        logCallback.accept("INFO  [准备] 开始：生成本地更新包，共 " + mainTargets.size() + " 个");
                        for (FtpTargetSelection mt : mainTargets) {
                            try {
                                Path local = prepareLocalFileForMainTarget(
                                        pluginConfig, mt, isFullMode, artifactIsWar,
                                        ftpHost, ftpPort, ftpUsername, ftpPassword, logCallback);
                                if (local == null || !Files.exists(local)) {
                                    throw new java.io.IOException("产物为空或不存在");
                                }
                                preparedPerMain.put(mt, local);
                                // 此处不再单独打"xxx 完成"：暂存包阶段已经输出过 [暂存包] 应用补丁完成 (大小)，
                                // 再打"[准备] xxx 完成，产物 __staging_..."属于内部产物名 + 大小两次重复
                            } catch (Exception ex) {
                                logCallback.accept("ERROR [准备] " + mt.getRelativePath()
                                        + " 产物构建失败：" + ex.getMessage());
                                logFailureSummary(logCallback, "产物构建失败，未执行任何远端变更");
                                onComplete.accept(null);
                                return;
                            }
                        }
                        logCallback.accept("INFO  [准备] 更新包准备完成，耗时 "
                                + formatElapsed(System.currentTimeMillis() - prepStart));
                    }

                    // ── Phase 1: 备份（可选）──
                    String backupDir = null;
                    // 多线程并行备份后会从多个 worker 线程 add；用 synchronizedList 保证线程安全。
                    // 串行路径下行为与 ArrayList 一致；额外的 monitor 开销可忽略。
                    List<String[]> updatedPackages =
                            java.util.Collections.synchronizedList(new ArrayList<>());

                    // 新建目标（Vue 模块 zip 首次投放）远端没有原文件：无从备份、无从加锁，
                    // 回滚语义 = 删除新文件。备份/加锁阶段只处理覆盖型目标。
                    List<FtpTargetSelection> backupableTargets = new ArrayList<>();
                    List<FtpTargetSelection> createNewTargets = new ArrayList<>();
                    for (FtpTargetSelection t : allTargets) {
                        if (t.isCreateNew()) createNewTargets.add(t); else backupableTargets.add(t);
                    }
                    if (!createNewTargets.isEmpty()) {
                        logCallback.accept("INFO  [部署] 其中 " + createNewTargets.size()
                                + " 个为新建目标（远端首次投放，无备份/加锁环节，失败时回滚 = 删除新文件）");
                    }

                    com.flux.deploy.plugin.model.BackupConflictStrategy strategy =
                            pluginConfig.getBackupConflictStrategy();
                    // 借用标记：USE_EXISTING 下为 true，回滚时只恢复文件不清理老备份
                    final boolean backupBorrowed = !pluginConfig.isSkipBackup()
                            && strategy == com.flux.deploy.plugin.model.BackupConflictStrategy.USE_EXISTING;

                    // Vue 源的本地打包只产 zip 文件、不动远端，无需备份阶段
                    // （目录目标的 remotePath 是目录，preBackupAll 的单文件下载也不适用）
                    boolean vueLocalOnly = pluginConfig.isLocalOnly()
                            && pluginConfig.getSourceProjectType()
                                    == com.flux.deploy.plugin.model.SourceProjectType.VUE;
                    if (!pluginConfig.isSkipBackup() && !backupableTargets.isEmpty() && !vueLocalOnly) {
                        if (strategy == com.flux.deploy.plugin.model.BackupConflictStrategy.USE_EXISTING) {
                            // 使用已有备份：不做下载/上传，直接把已有备份路径登记为回滚源
                            logCallback.accept("INFO  [备份] 沿用已有备份作为回滚源，本次跳过备份步骤");
                            backupDir = computeExistingBackupDir(pluginConfig, backupableTargets);
                            for (FtpTargetSelection t : backupableTargets) {
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
                            logCallback.accept("INFO  [备份] 开始，共 " + backupableTargets.size()
                                    + " 个目标" + dirSuffix);
                            try {
                                // 备份并发度写死为 FTP_PARALLELISM（=3）。详见常量注释。
                                backupDir = preBackupAll(pluginConfig, backupableTargets,
                                        FTP_PARALLELISM,
                                        userConfig.getBackupMaxRetries(),
                                        ftpHost, ftpPort, ftpUsername, ftpPassword, logCallback);
                                logCallback.accept("INFO  [备份] 完成，成功 " + backupableTargets.size()
                                        + "/" + backupableTargets.size() + "，耗时 "
                                        + formatElapsed(System.currentTimeMillis() - bkStart));
                            } catch (Exception e) {
                                logCallback.accept("ERROR [备份] 备份失败：" + e.getMessage());
                                logFailureSummary(logCallback, "备份失败，未执行后续步骤");
                                onComplete.accept(null);
                                return;
                            }

                            // 注册回滚列表：备份路径带 relativeDir 子目录，与 preBackupAll 一致
                            for (FtpTargetSelection t : backupableTargets) {
                                String rp = t.getRemoteDir() + t.getRelativePath();
                                String bp = backupDir + backupSubDirFor(t) + t.getTargetName();
                                updatedPackages.add(new String[]{rp, bp});
                            }
                        }
                    } else {
                        if (pluginConfig.isSkipBackup()) {
                            logCallback.accept("WARN  [备份] 跳过备份：用户选择不备份，失败后将无法自动回滚");
                        } else {
                            logCallback.accept("INFO  [备份] 本次目标均为新建目标，无原包可备份");
                        }
                        // 跳过备份时也记录覆盖型目标包路径（用于日志总结，无备份路径）
                        for (FtpTargetSelection t : backupableTargets) {
                            String rp = t.getRemoteDir() + t.getRelativePath();
                            updatedPackages.add(new String[]{rp, null});
                        }
                    }
                    // 补记原始修改时间（MDTM，UTC 串）：上传尚未发生，此刻远端还是原文件。
                    // 回滚恢复内容后写回该时间，避免"回滚后时间戳变成回滚时刻"。
                    // 仅对有备份可恢复的覆盖型条目有意义；抓取失败按尽力而为处理
                    if (!updatedPackages.isEmpty() && !pluginConfig.isLocalOnly()) {
                        try {
                            runFreshFtpSession(ftpHost, ftpPort, ftpUsername, ftpPassword, (s, ops) -> {
                                synchronized (updatedPackages) {
                                    for (int i = 0; i < updatedPackages.size(); i++) {
                                        String[] p = updatedPackages.get(i);
                                        if (isCreateNewEntry(p) || p[1] == null) continue;
                                        updatedPackages.set(i, new String[]{p[0], p[1], null,
                                                ops.getModificationTime(p[0])});
                                    }
                                }
                            });
                        } catch (Exception e) {
                            logCallback.accept("INFO  [备份] 原始时间戳记录失败（回滚将不恢复时间戳）："
                                    + e.getMessage());
                        }
                    }

                    // 新建目标登记为带 NEW 标记的条目：回滚阶段据此删除已上传的新文件
                    // （覆盖型 + 跳过备份的 [rp, null] 条目绝不能删——原包已被覆盖，删除等于丢包）
                    for (FtpTargetSelection t : createNewTargets) {
                        String rp = t.getRemoteDir() + t.getRelativePath();
                        updatedPackages.add(new String[]{rp, null, CREATE_NEW_ENTRY_MARK});
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
                                    logCallback.accept("INFO  [本地] 嵌入 " + embedTarget.getTargetName() + "...");
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
                                                    patcher.setCsvMergePlan(pluginConfig.getCsvMergePlan());
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
                                        + Path.of(sf[0]).getFileName().toString());
                            }
                            logCallback.accept("\n手动上传目标：");
                            for (String[] sf : savedFiles) {
                                logCallback.accept(com.flux.deploy.plugin.toolwindow.LogSectionPanel.RAW_LINE_MARK
                                        + Path.of(sf[0]).getFileName().toString() + "，目标 " + sf[1]);
                            }
                            logCallback.accept("\n备份目录 FTP：" + backupDir);

                            DeployResult localResult = new DeployResult();
                            localResult.markSuccess();
                            onComplete.accept(localResult);
                        } catch (Exception e) {
                            logCallback.accept("ERROR [本地] 保存失败：" + e.getMessage());
                            onComplete.accept(null);
                        }
                        return; // localOnly 模式到此结束
                    }

                    // ── Stage 0 残留锁处理已前移到「确认执行」之前 ──
                    // 残留锁的扫描 + 用户勾选清理已前移到 UI 层、部署确认对话框之前完成
                    // （见 DeployToolWindowPanel#resolveResidualLocksAndProceed），使用户点完
                    // 「确认执行」后只剩纯执行步骤，不再被残留锁弹窗打断。后续每个 targetConfig 仍设
                    // EXTERNAL_RESOLVED，使核心 pipeline 内部 Stage 0 直接跳过（与 UI 已处理一致）。

                    // ── Phase 2: 加锁 ──
                    // 多目标时打 header + summary，单目标退化为单行 [加锁] X 已加锁，避免日志噪音
                    boolean multiTargets = allTargets.size() > 1;
                    long lockStart = System.currentTimeMillis();
                    if (multiTargets) {
                        logCallback.accept("INFO  [加锁] 开始");
                    }
                    List<String[]> lockedPackages = new ArrayList<>();
                    try {
                        // 新建目标远端无原文件，无从 rename 加锁；上传阶段的 .__UPLOADING__ 临时名
                        // + rename 保证其原子性。仅对覆盖型目标加锁（Vue 新建场景无嵌入目标，
                        // 不影响嵌入阶段按 mainTargets.size() 偏移读 lockedPackages 的约定）。
                        preLockAll(backupableTargets, pluginConfig.getOperator(),
                                ftpHost, ftpPort, ftpUsername, ftpPassword,
                                logCallback, lockedPackages);
                        if (multiTargets) {
                            logCallback.accept("INFO  [加锁] 完成，耗时 "
                                    + formatElapsed(System.currentTimeMillis() - lockStart));
                        }
                    } catch (Exception e) {
                        logCallback.accept("ERROR [加锁] 失败：" + e.getMessage());
                        // 关键：先从备份恢复文件，再删锁文件
                        if (backupDir != null && !updatedPackages.isEmpty()) {
                            rollbackAll(backupDir, updatedPackages,
                                    ftpHost, ftpPort, ftpUsername, ftpPassword, logCallback, backupBorrowed);
                        } else {
                            logCallback.accept("WARN  [加锁] 无备份，无法自动回滚");
                        }
                        preUnlockAll(lockedPackages, ftpHost, ftpPort, ftpUsername, ftpPassword, logCallback);
                        logFailureSummary(logCallback, "加锁失败，可能目标包已被他人锁定");
                        onComplete.accept(null);
                        return;
                    }

                    // ── Phase 3: 更新主目标（使用预构建好的本地文件，每个目标独立上传）──
                    DeployResult result = new DeployResult();
                    if (!mainTargets.isEmpty()) {
                        String tag = isFullMode ? "整包更新" : "打补丁";
                        long updateStart = System.currentTimeMillis();
                        logCallback.accept("INFO  [更新] 开始：上传更新包并校验远端内容，共 "
                                + preparedPerMain.size() + " 个目标");
                        int mi = 0;
                        for (java.util.Map.Entry<FtpTargetSelection, Path> entry : preparedPerMain.entrySet()) {
                            mi++;
                            FtpTargetSelection mt = entry.getKey();
                            Path localForThisTarget = entry.getValue();
                            String sizeText;
                            try {
                                sizeText = formatSize(Files.size(localForThisTarget));
                            } catch (Exception ex) {
                                sizeText = "?";
                            }
                            String remotePath = mt.getRemoteDir() + mt.getRelativePath();
                            logCallback.accept("INFO  [更新] " + mi + "/" + preparedPerMain.size()
                                    + " " + tag + "：" + mt.getRelativePath());
                            logCallback.accept("INFO  [更新] 本地更新包：" + localForThisTarget
                                    + "（" + sizeText + "）");
                            logCallback.accept("INFO  [更新] 远程目标：" + remotePath);
                            logCallback.accept("INFO  [上传] " + mi + "/" + preparedPerMain.size()
                                    + " " + mt.getRelativePath()
                                    + "，" + sizeText + "，" + tag);

                            DeployConfig targetConfig = buildDeployConfig(
                                    pluginConfig, ftpHost, ftpPort, ftpUsername, ftpPassword, null);
                            targetConfig.setRemoteDir(mt.getRemoteDir());
                            targetConfig.setTargetNames(List.of(mt.getTargetName()));
                            targetConfig.setTargetRelativePaths(List.of(mt.getRelativePath()));
                            targetConfig.setLocalFiles(List.of(localForThisTarget));
                            targetConfig.setSkipBackup(true);
                            targetConfig.setSkipNote(true);
                            targetConfig.setSkipLock(true);
                            // 新建目标：预检改为要求远端不存在同名文件（防他人抢先上传后被覆盖）
                            targetConfig.setCreateNewFlags(List.of(mt.isCreateNew()));
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
                            logCallback.accept("INFO  [更新] 上传并校验通过：" + remotePath);
                            // 登记到实时成功列表（供 UI 弹"如何收尾"对话框展示）
                            recordSucceededUpload(mt);
                            result = thisResult;
                        }
                        logCallback.accept("INFO  [更新] 完成，成功 " + preparedPerMain.size() + "/"
                                + preparedPerMain.size() + "，耗时 "
                                + formatElapsed(System.currentTimeMillis() - updateStart));
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
                        logCallback.accept("INFO  [嵌入] 开始，共 " + embedTargets.size() + " 个目标");

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
                                                + "，ISOLATED 策略跳过此包，继续处理其他目标");
                                        // 明确给操作员一行"远端不会被改坏"的语义日志：
                                        // executeWarEmbed 失败 ⇒ 没有上传 ⇒ Phase 4 preUnlockAll 会
                                        // 走 restoreLock 把锁文件 rename 回原名，远端 WAR 与本批次开始前一致。
                                        logCallback.accept("INFO  [回滚] " + embedTarget.getTargetName()
                                                + " 嵌入未完成，远端将保持原 WAR，解锁阶段恢复");
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
                        logCallback.accept("INFO  [嵌入] 完成，成功 " + embedSuccess + "/"
                                + embedTargets.size() + "，耗时 "
                                + formatElapsed(System.currentTimeMillis() - embedStart));
                    }

                    // ── 解锁 ──
                    // 同加锁：多目标才打 header + summary
                    boolean multiUnlock = lockedPackages.size() > 1;
                    long unlockStart = System.currentTimeMillis();
                    if (multiUnlock) {
                        logCallback.accept("INFO  [解锁] 开始");
                    }
                    java.util.Set<String> restoreFailedLockNames = preUnlockAll(
                            lockedPackages, ftpHost, ftpPort, ftpUsername, ftpPassword, logCallback);
                    if (multiUnlock) {
                        logCallback.accept("INFO  [解锁] 完成，耗时 "
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
                        logCallback.accept("INFO  [说明] 开始：写入版本更新记录文件");
                        // ISOLATED 部分失败时，跳过失败包的 note 追加：失败包远端已被
                        // preUnlockAll 的 restoreLock 还原为原 WAR，写 note 会产生"取包/传包"幻象。
                        java.util.Set<String> skippedNoteKeys = collectFailedRelativePaths(embedOutcomes);
                        try {
                            updateNoteForAll(pluginConfig, allTargets, skippedNoteKeys,
                                    deployStartMs, currentUploadFinishTimes,
                                    ftpHost, ftpPort, ftpUsername, ftpPassword, logCallback);
                            logCallback.accept("INFO  [说明] 完成，耗时 "
                                    + formatElapsed(System.currentTimeMillis() - noteStart));
                        } catch (Exception e) {
                            logCallback.accept("WARN  [说明] 更新失败：" + e.getMessage() + "，不影响已部署的包");
                        }
                    }

                    // ── 总结 ──
                    // 与失败 / 停止两种结束态保持框体对称（都是 ╔═╗ + emoji + 短文案）：
                    //   ❌ 部署失败 / ■ 部署已停止 / ✅ 部署完成 / ⚠ 部分成功
                    // 框下分三段输出："已更新 N 个包，耗时 X" + "更新包"路径列表 + 备份目录。
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
                        logCallback.accept("ERROR [部署] 全部失败，成功 0/" + totalEmbed
                                + "，耗时 " + formatElapsed(deployElapsedMs));
                    } else if (partialSuccess) {
                        logCallback.accept("WARN  [部署] 部分成功，成功 " + actuallySucceeded + "/"
                                + totalEmbed + "，耗时 " + formatElapsed(deployElapsedMs));
                    }
                    // 完全成功路径：不再单独打"[部署] 部署完成..."，避免与下方框体 + 列表重复
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
                                + "已生效 " + succeededTotal + " 个包"
                                + (retrySuccessRemotes.isEmpty() ? ""
                                        : "，含 " + retrySuccessRemotes.size() + " 个重试后成功"));
                        logUpdatedPackageLines(logCallback, updatedPackages, failedRemotes, retrySuccessRemotes);
                        // 最终失败列表（重试 N 轮后仍失败的；首次失败但重试成功的不在此列）
                        logCallback.accept(com.flux.deploy.plugin.toolwindow.LogSectionPanel.RAW_LINE_MARK
                                + "最终失败 " + isolatedFailedCount + " 个包：");
                        com.flux.deploy.ftp.FtpErrorKind firstSuggestionKind = null;
                        // 标记：所有失败是否都是"目标 WAR 不含该 JAR"类（用户选错目标，不是部署系统问题）
                        // → 用于把通用"建议重新部署"提醒切换为更具针对性的"检查目标选择"提醒
                        boolean allFailuresAreMissingJar = isolatedFailedCount > 0;
                        for (com.flux.deploy.parallel.TargetOutcome o : embedOutcomes.values()) {
                            if (o.getStatus() == com.flux.deploy.parallel.TargetStatus.FAILED) {
                                String reason = o.getError() != null && o.getError().getMessage() != null
                                        ? o.getError().getMessage() : "未知错误";
                                logCallback.accept(com.flux.deploy.plugin.toolwindow.LogSectionPanel.RAW_LINE_MARK
                                        + o.getTargetKey() + "：" + reason);
                                if (firstSuggestionKind == null && o.getErrorKind() != null) {
                                    firstSuggestionKind = o.getErrorKind();
                                }
                                if (!isMissingJarFailure(reason)) {
                                    allFailuresAreMissingJar = false;
                                }
                            }
                        }
                        if (isolatedCriticalCount > 0) {
                            logCallback.accept("ERROR [部署] 严重回滚失败 " + isolatedCriticalCount + " 个包：");
                            for (com.flux.deploy.parallel.TargetOutcome o : embedOutcomes.values()) {
                                if (o.getStatus() == com.flux.deploy.parallel.TargetStatus.ROLLBACK_FAILED) {
                                    String reason = o.getError() != null && o.getError().getMessage() != null
                                            ? o.getError().getMessage() : "回滚失败";
                                    logCallback.accept(com.flux.deploy.plugin.toolwindow.LogSectionPanel.RAW_LINE_MARK
                                            + o.getTargetKey() + "：" + reason);
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
                                + "已更新 " + updatedPackages.size() + " 个包，耗时 "
                                + formatElapsed(deployElapsedMs)
                                + (retrySuccessRemotes.isEmpty() ? ""
                                        : "，含 " + retrySuccessRemotes.size() + " 个重试后成功"));
                        logUpdatedPackageLines(logCallback, updatedPackages, null, retrySuccessRemotes);
                    }
                    if (backupDir != null) {
                        logCallback.accept(com.flux.deploy.plugin.toolwindow.LogSectionPanel.RAW_LINE_MARK
                                + "备份目录：" + backupDir);
                    } else {
                        logCallback.accept(com.flux.deploy.plugin.toolwindow.LogSectionPanel.RAW_LINE_MARK
                                + "备份：未执行，用户选择跳过");
                    }
                    // 总耗时已合并到"已更新 N 个包，耗时 X"行，不再单独打末尾

                    // 保存回滚信息供手动回滚使用（有备份，或含可删除的新建目标时）
                    if (backupDir != null || hasCreateNewEntry(updatedPackages)) {
                        lastBackupDir = backupDir;
                        // updatedPackages 是 synchronizedList：迭代复制时需显式同步源 list。
                        // 无备份（backupDir==null）时只登记新建目标条目：覆盖型目标此时无备份可恢复，
                        // 混入回滚清单会让「回滚」按钮承诺它恢复不了的东西（版本记录也会被误截）
                        synchronized (updatedPackages) {
                            if (backupDir == null) {
                                List<String[]> onlyNew = new ArrayList<>();
                                for (String[] pair : updatedPackages) {
                                    if (isCreateNewEntry(pair)) onlyNew.add(pair);
                                }
                                lastUpdatedPackages = onlyNew;
                            } else {
                                lastUpdatedPackages = new ArrayList<>(updatedPackages);
                            }
                        }
                        if (backupDir == null) {
                            List<FtpTargetSelection> onlyNewTargets = new ArrayList<>();
                            for (FtpTargetSelection t : allTargets) {
                                if (t.isCreateNew()) onlyNewTargets.add(t);
                            }
                            lastAllTargets = onlyNewTargets;
                        } else {
                            lastAllTargets = new ArrayList<>(allTargets);
                        }
                        lastUpdatedNote = pluginConfig.isUpdateNote();
                        lastBackupBorrowed = backupBorrowed;
                        // 快照手动指定的 note 文件登记：后续新预检会清空活表，
                        // 回滚版本记录必须以本次部署实际消费的登记为准
                        lastManualNoteSelections = new java.util.HashMap<>(manualNoteSelections);
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
                    // 删除本次生成的 Vue 模块 zip 临时目录（localOnly 已先复制到输出目录）
                    clearVueZipTempDirs();
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

        // CSV 行级增量合并计划（null 时 CSV 保持整份覆盖）
        config.setCsvMergePlan(pluginConfig.getCsvMergePlan());

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
                logCallback.accept("[嵌入] 复用备份阶段本地副本 " + warName
                        + "，" + formatSize(Files.size(downloadedWar)));
            } else {
                try (FtpSession session = new FtpSession(host, port)) {
                    session.connect(username, password);
                    FtpOperations ops = new FtpOperations(session);
                    String lockPath = lockRemoteDir + lockName;
                    ops.download(lockPath, downloadedWar,
                            "[嵌入] 下载 " + warName, logCallback);
                    logCallback.accept("[嵌入] 下载远程 WAR：" + warName
                            + "，" + formatSize(Files.size(downloadedWar)));
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
                        patcher.setCsvMergePlan(pluginConfig.getCsvMergePlan());
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
                // 不再单独打 "xxx 更新成功"：[校验] SHA256 一致 + [解锁] xxx 已是充分标志
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
                                log.append("[嵌入] 复用备份阶段本地副本 ")
                                        .append(target.getTargetName())
                                        .append("，").append(formatSize(Files.size(downloadedWar)))
                                        .append("\n");
                            } else {
                                try (FtpSession session = new FtpSession(host, port)) {
                                    session.connect(username, password);
                                    FtpOperations ops = new FtpOperations(session);
                                    String lockPath = lockInfo[0] + lockInfo[1];
                                    ops.download(lockPath, downloadedWar);
                                    log.append("[嵌入] 下载远程 WAR：")
                                            .append(target.getTargetName())
                                            .append("，")
                                            .append(formatSize(Files.size(downloadedWar)))
                                            .append("\n");
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
                                        .append(" 嵌入未完成，远端将保持原 WAR，解锁阶段恢复\n");
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
                                    patcher.setCsvMergePlan(pluginConfig.getCsvMergePlan());
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
                            // 不再单独打 "xxx 更新成功"：[校验] SHA256 一致 + 包内变更明细 已是充分标志
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
                                                lookupOriginalMtime(updatedPackages, remotePath),
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

        // 决定是否让主流程整体回滚（漏洞 H5 修复）：
        //   失败数 = 0：从不 abort
        //   失败数 > 0：交给 EmbedFailureDecision.decide 与串行路径统一判定
        //               - 用户主动停止（currentCancelMode != NONE）→ ABORT_BATCH（用户意图压过策略）
        //               - ISOLATED → CONTINUE_ISOLATED，主流程不走 abortPartial
        //               - ROLLBACK_ALL / KEEP_SUCCEEDED → ABORT_BATCH
        // 历史实现自造 shouldAbort 漏看了"用户取消 + ISOLATED + 多目标"路径，与串行行为不一致。
        boolean userStop = currentCancelMode != CancelMode.NONE;
        boolean shouldAbort = (failed > 0)
                && com.flux.deploy.config.EmbedFailureDecision.decide(strategy, userStop)
                        == com.flux.deploy.config.EmbedFailureDecision.ABORT_BATCH;
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
     * 输出部署完成摘要里的更新包列表。
     *
     * <p>单包时使用 {@code 更新包：/path/to/pkg.jar}，多包时先输出 {@code 更新包：}
     * 再逐行列出路径，避免超长路径把摘要行撑得难读。</p>
     *
     * @param logCallback         日志输出回调
     * @param updatedPackages     已登记的更新包列表（[remotePath, backupFilePath]）
     * @param excludedRemotes     需要排除的远端路径集合（部分成功时为失败包；可为 null）
     * @param retrySuccessRemotes 重试后成功的远端路径集合（用于追加标记；可为空）
     */
    private static void logUpdatedPackageLines(Consumer<String> logCallback,
                                               List<String[]> updatedPackages,
                                               Set<String> excludedRemotes,
                                               Set<String> retrySuccessRemotes) {
        List<String> lines = new ArrayList<>();
        synchronized (updatedPackages) {
            for (String[] entry : updatedPackages) {
                String remotePath = entry[0];
                if (excludedRemotes != null && excludedRemotes.contains(remotePath)) {
                    continue;
                }
                String marker = retrySuccessRemotes != null && retrySuccessRemotes.contains(remotePath)
                        ? "  🔄 重试后成功" : "";
                lines.add(remotePath + marker);
            }
        }

        String raw = String.valueOf(com.flux.deploy.plugin.toolwindow.LogSectionPanel.RAW_LINE_MARK);
        if (lines.isEmpty()) {
            logCallback.accept(raw + "更新包：无");
            return;
        }
        if (lines.size() == 1) {
            logCallback.accept(raw + "更新包：" + lines.get(0));
            return;
        }
        logCallback.accept(raw + "更新包：");
        for (String line : lines) {
            logCallback.accept(raw + line);
        }
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
                    logCallback.accept("[重试] 跳过 " + key + "，未找到加锁信息");
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
                                              String originalMtime,
                                              String host, int port, String user, String pass) throws Exception {
        Path tempRestore = Files.createTempFile("restore-", ".tmp");
        try {
            runFreshFtpSessionWithRetry(host, port, user, pass, (s, ops) -> {
                long expected = ops.getFileSize(backupFilePath);
                if (expected < 0) {
                    throw new java.io.IOException("备份文件不存在: " + backupFilePath);
                }
                long got = ops.download(backupFilePath, tempRestore);
                if (got != expected) {
                    throw new java.io.IOException("备份下载不完整: " + backupFilePath
                            + "，期望 " + expected + " B，实得 " + got + " B");
                }
                // 原子发布：回滚写回半传会让线上文件比回滚前更糟，且备份即将被消费
                ops.uploadAtomic(tempRestore, remotePath);
                // 写回更新前的原始修改时间（尽力而为），与整体回滚口径一致
                ops.setModificationTime(remotePath, originalMtime);
            });
        } finally {
            Files.deleteIfExists(tempRestore);
        }
    }

    /**
     * 在回滚清单中按 remotePath 查找条目记录的原始修改时间
     *
     * @param updatedPackages 回滚清单
     * @param remotePath      远程路径
     * @return 原始修改时间（MDTM UTC 串）；未记录返回 null
     * @author xumanyi
     * @date 2026-08-14
     */
    private static String lookupOriginalMtime(List<String[]> updatedPackages, String remotePath) {
        if (updatedPackages == null) return null;
        synchronized (updatedPackages) {
            for (String[] pair : updatedPackages) {
                if (pair != null && pair.length > 0 && remotePath.equals(pair[0])) {
                    return originalMtimeOf(pair);
                }
            }
        }
        return null;
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
                logCallback.accept("[备份] 采用新增目录策略，备份目录名：" + backupDirName);
            }

            logCallback.accept("[备份] 备份目录：" + backupDir);

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
                    throw new Exception("备份失败：" + o.getTargetKey()
                            + " - " + reason + "，认证错误不重试", rootCause);
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

        // 备份阶段下载到本地的 temp 文件不再立刻删除：登记到 backupLocalCopies 后由
        // 嵌入阶段直接复用，跳过一次相同字节的远端下载（"1 下 + 2 上"优化）。
        // 本方法只在异常路径下负责删 temp；成功路径上 temp 的所有权移交给入口 finally 统一清理。
        Path tempBackup = Files.createTempFile("backup-", "-" + target.getTargetName());
        boolean handedOff = false;
        try {
            // 下载字节数与远端大小核对：半截下载同样非空，只看"非空"会把截断的内容当成有效备份
            runFreshFtpSessionWithRetry(host, port, user, pass, (s, ops) -> {
                long remoteSize = ops.getFileSize(remotePath);
                if (remoteSize < 0) {
                    throw new java.io.IOException("远端包不存在，无法备份: " + remotePath);
                }
                long got = ops.download(remotePath, tempBackup);
                if (got != remoteSize) {
                    throw new java.io.IOException("备份下载不完整: " + remotePath
                            + "，远端 " + remoteSize + " B，实得 " + got + " B");
                }
            });

            long downloadedSize = Files.size(tempBackup);
            if (downloadedSize == 0) {
                throw new Exception("下载的备份文件为空: " + remotePath);
            }

            // 备份文件走临时名 + rename 发布：半截上传不会变成一个"看着像备份"的文件
            long backupSize = withFreshFtpSession(host, port, user, pass, (s, ops) -> {
                uploadFileSafely(s, ops, tempBackup, backupFilePath,
                        RetryUserPrompter.abortAll(), logCallback);
                return ops.getFileSize(backupFilePath);
            });

            if (backupSize != downloadedSize) {
                throw new Exception("备份大小不一致：" + target.getTargetName()
                        + "，下载 " + downloadedSize + " 字节，备份 " + backupSize + " 字节");
            }

            // 登记本地副本：以 relativePath 为 key（嵌入阶段 / 重试 / executeWarEmbed 都用同一 key 取）
            // 重试场景下同 key 可能多次进入：旧副本先删再覆盖，避免 temp 残留
            Path prev = backupLocalCopies.put(target.getRelativePath(), tempBackup);
            if (prev != null) {
                try { Files.deleteIfExists(prev); } catch (Exception ignored) {}
            }
            handedOff = true;

            logCallback.accept("[备份] " + displayName
                    + "  " + formatSize(backupSize));
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

        // 未能恢复的条目：备份是这些文件唯一的原始版本，只要有一个失败就不清理备份，
        // 并把失败条目登记为「上次部署」回滚数据，网络恢复后可再点「回滚」只重做这些文件
        List<String[]> failed = new ArrayList<>();
        // 恢复每个已更新的包：逐文件独立短连接，
        // 同 preBackupAll 一致，避免长会话在多包数据传输期间被服务端 421。
        for (String[] pair : updatedPackages) {
            final String remotePath = pair[0];
            final String backupFilePath = pair[1];
            if (isCreateNewEntry(pair)) {
                // 新建目标：远端原本不存在该文件，回滚 = 删除已上传的新文件。
                // 仅删除本次部署真实上传过的路径——条目在上传前就已登记，
                // 未上传（或他人抢先创建同名文件）时删除会误伤非本次部署的字节
                if (!wasUploadedThisRun(remotePath)) {
                    logCallback.accept("INFO  [回滚] 新建目标本次未上传，无需处理: " + remotePath);
                    continue;
                }
                try {
                    runFreshFtpSessionWithRetry(host, port, user, pass, (s, ops) -> {
                        if (ops.exists(remotePath)) {
                            ops.delete(remotePath);
                            logCallback.accept("INFO  [回滚] 已删除新建文件: " + remotePath);
                        }
                    });
                } catch (Exception e) {
                    logCallback.accept("WARN  [回滚] 删除新建文件失败: " + remotePath
                            + " - " + e.getMessage());
                    failed.add(pair.clone());
                }
                continue;
            }
            if (backupFilePath == null) {
                // 覆盖型目标但没有备份（用户跳过备份）：无从恢复，保持现状
                continue;
            }
            try {
                restoreFileFromBackup(backupFilePath, remotePath, originalMtimeOf(pair),
                        host, port, user, pass, logCallback);
                logCallback.accept("INFO  [回滚] 已恢复: " + remotePath);
            } catch (Exception e) {
                logCallback.accept("WARN  [回滚] 恢复失败: " + remotePath + " - " + e.getMessage());
                failed.add(pair.clone());
            }
        }

        if (!failed.isEmpty()) {
            keepBackupForFailedRestores(failed, backupDir, borrowed, logCallback);
            return;
        }

        // 全部恢复成功：远端已回到部署前状态，旧的回滚指针必须清掉——
        // 留着会让「回滚」按钮亮着，再点一次就是用备份把远端又盖一遍
        clearRollbackData();
        if (borrowed) {
            logCallback.accept("INFO  [回滚] 借用已有备份作为回滚源，本次保留备份文件不做清理");
            return;
        }
        // 全部为新建目标时没有备份目录，无清理阶段
        if (backupDir == null) {
            return;
        }

        // 清理阶段全是命令操作（delete / listFiles / removeDirectory），无数据传输，
        // 控制通道始终在用，复用一个短连接是安全的。
        try {
            runFreshFtpSession(host, port, user, pass, (s, ops) -> {
                // 仅删除本次备份的文件（不影响同目录下其他包的备份）
                for (String[] pair : updatedPackages) {
                    String backupFilePath = pair[1];
                    if (backupFilePath == null) continue;
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
     * 从备份恢复单个远端文件，并逐环节校验字节数。
     *
     * <p>备份文件必须存在并取其大小 → 下载后核对字节数 → 上传到远端 → 再核对远端大小，
     * 任一环节不一致即抛出：调用方按"恢复失败"处理并保留备份。不加校验的话，备份下载
     * 半截或上传半截都会被当成"已恢复"，线上留下损坏文件且备份随后被清理——
     * 这正是"更新失败造成包丢失"的路径。修改时间写回为尽力而为，不影响恢复结果。</p>
     *
     * @param backupFilePath 备份文件远端路径
     * @param remotePath     待恢复的远端文件路径
     * @param originalMtime  更新前记录的原始修改时间（MDTM UTC 串，可为 null）
     * @param host           FTP 主机
     * @param port           FTP 端口
     * @param user           FTP 用户名
     * @param pass           FTP 密码
     * @throws Exception 备份缺失 / 传输不完整 / FTP 失败（已换连接重试一次）
     * @author xumanyi
     * @date 2026-09-22
     */
    private static void restoreFileFromBackup(String backupFilePath, String remotePath,
                                              String originalMtime,
                                              String host, int port, String user, String pass,
                                              Consumer<String> logCallback)
            throws Exception {
        Path tempRestore = Files.createTempFile("restore-", ".tmp");
        try {
            runFreshFtpSessionWithRetry(host, port, user, pass, (s, ops) -> {
                long expected = ops.getFileSize(backupFilePath);
                if (expected < 0) {
                    throw new java.io.IOException("备份文件不存在: " + backupFilePath);
                }
                long got = ops.download(backupFilePath, tempRestore);
                if (got != expected) {
                    throw new java.io.IOException("备份下载不完整: " + backupFilePath
                            + "，期望 " + expected + " B，实得 " + got + " B");
                }
                // 回滚写回同样走临时名 + 续传 + rename：回滚时网络往往仍不稳，
                // 原地覆盖一旦半传，线上文件比回滚前更糟且备份已被消费
                // （prompter 用 abortAll：回滚阶段不弹窗打断，失败由调用方保留备份并登记重试）
                uploadFileSafely(s, ops, tempRestore, remotePath,
                        RetryUserPrompter.abortAll(), logCallback);
                long after = ops.getFileSize(remotePath);
                if (after != expected) {
                    throw new java.io.IOException("恢复后远端大小不一致: " + remotePath
                            + "，期望 " + expected + " B，远端 " + after + " B");
                }
                // 写回更新前的原始修改时间（尽力而为，失败不影响恢复）
                ops.setModificationTime(remotePath, originalMtime);
            });
        } finally {
            Files.deleteIfExists(tempRestore);
        }
    }

    /**
     * 回滚有文件未能恢复时的收尾：保留整个备份、登记失败条目为可再次回滚的数据、
     * 在日志里列出"远端 ← 备份"清单供人工处理。
     *
     * <p>失败原因多半是网络（上传失败后紧接着回滚，网络往往还没恢复）：此时清理备份
     * 等于把这些文件唯一的原始版本一起删掉。登记后「回滚」按钮保持可用，网络恢复再点一次
     * 只重做失败的文件。</p>
     *
     * @param failed      未能恢复的条目（回滚清单格式）
     * @param backupDir   备份目录（可为 null：全部为新建目标）
     * @param borrowed    备份是否借用自已有目录
     * @param logCallback 日志回调
     * @author xumanyi
     * @date 2026-09-22
     */
    private static void keepBackupForFailedRestores(List<String[]> failed, String backupDir,
                                                    boolean borrowed, Consumer<String> logCallback) {
        logCallback.accept("ERROR [回滚] " + failed.size() + " 个文件未能恢复，备份已完整保留"
                + (backupDir != null ? "：" + backupDir : "")
                + "。网络恢复后可再次点「回滚」重试，或按下列清单人工恢复（远端 ← 备份）：");
        for (String[] pair : failed) {
            logCallback.accept(com.flux.deploy.plugin.toolwindow.LogSectionPanel.RAW_LINE_MARK
                    + "  " + pair[0]
                    + (isCreateNewEntry(pair) ? "  （本次新建，应删除）" : "  ←  " + pair[1]));
        }
        lastBackupDir = backupDir;
        lastUpdatedPackages = new ArrayList<>(failed);
        lastAllTargets = null;
        lastUpdatedNote = false;
        lastBackupBorrowed = borrowed;
        lastRollbackIncomplete = true;
    }

    /**
     * 校验备份覆盖度：递归列出备份目录，核对期望的每个文件都在备份里（可选核对字节数一致）。
     *
     * <p>备份是更新失败时唯一的回滚源，任何缺失 / 大小不一致都意味着回滚会用不完整的
     * 备份覆盖线上文件，必须在上传前发现并中止（此时远端零变更）。一次递归 LIST 的开销
     * 只与目录数相关，比逐文件 SIZE 便宜得多。</p>
     *
     * @param expectedByContent content 相对路径 → （文件相对路径 → 期望字节数，null 表示只要求存在）
     * @param backupDir         备份根目录（以 / 结尾）
     * @param requireSize       是否要求字节数一致（新鲜镜像 true；借用的已有备份只要求存在）
     * @param ops               FTP 操作
     * @return 不通过的文件清单（含原因），空表示通过
     * @throws java.io.IOException FTP 操作失败
     * @author xumanyi
     * @date 2026-09-22
     */
    private static List<String> verifyBackupCoverage(
            java.util.Map<String, java.util.Map<String, Long>> expectedByContent,
            String backupDir, boolean requireSize, FtpOperations ops) throws java.io.IOException {
        List<String> problems = new ArrayList<>();
        for (java.util.Map.Entry<String, java.util.Map<String, Long>> e : expectedByContent.entrySet()) {
            String contentRel = e.getKey();
            String backupBase = backupDir + (contentRel.isEmpty() ? "" : contentRel + "/");
            java.util.Map<String, Long> actual = new java.util.LinkedHashMap<>();
            try {
                listRemoteFilesRecursive(ops, backupBase, "", actual, 0);
            } catch (java.io.IOException listEx) {
                // 备份目录本身列不出来（不存在 / 网络）：整组文件按缺失计，交由上层中止
                problems.add(backupBase + "（无法列出备份目录：" + listEx.getMessage() + "）");
                continue;
            }
            // 远端大小写可能不一致，按小写键核对
            java.util.Map<String, Long> actualLower = new java.util.HashMap<>();
            for (java.util.Map.Entry<String, Long> a : actual.entrySet()) {
                actualLower.put(a.getKey().toLowerCase(java.util.Locale.ROOT), a.getValue());
            }
            for (java.util.Map.Entry<String, Long> exp : e.getValue().entrySet()) {
                Long size = actualLower.get(exp.getKey().toLowerCase(java.util.Locale.ROOT));
                if (size == null) {
                    problems.add(backupBase + exp.getKey() + "（备份中缺失）");
                } else if (requireSize && exp.getValue() != null && !exp.getValue().equals(size)) {
                    problems.add(backupBase + exp.getKey() + "（大小不一致：原 "
                            + exp.getValue() + " B，备份 " + size + " B）");
                }
            }
        }
        return problems;
    }

    /**
     * 备份覆盖度校验未通过时的统一中止：日志列出前若干条问题，抛出异常终止部署（远端零变更）。
     *
     * @param problems    不通过的文件清单
     * @param what        备份描述（"本次镜像" / "沿用的已有备份"）
     * @param logCallback 日志回调
     * @throws java.io.IOException 始终抛出
     * @author xumanyi
     * @date 2026-09-22
     */
    private static void abortOnBackupProblems(List<String> problems, String what,
                                              Consumer<String> logCallback) throws java.io.IOException {
        logCallback.accept("ERROR [备份] " + what + "校验未通过：" + problems.size()
                + " 个文件缺失或大小不一致，为避免更新失败后用不完整的备份覆盖线上文件，已中止（远端零变更）");
        int shown = 0;
        for (String p : problems) {
            if (shown++ >= 20) {
                logCallback.accept(com.flux.deploy.plugin.toolwindow.LogSectionPanel.RAW_LINE_MARK
                        + "  ...等共 " + problems.size() + " 个");
                break;
            }
            logCallback.accept(com.flux.deploy.plugin.toolwindow.LogSectionPanel.RAW_LINE_MARK + "  " + p);
        }
        throw new java.io.IOException(what + "校验未通过（" + problems.size() + " 个文件），已中止更新");
    }

    /**
     * 为所有目标更新版本记录（NoteGate 等价逻辑）
     *
     * <p>文件定位优先级：预检阶段用户手动指定的文件名（{@link #manualNoteSelections}，
     * 模糊匹配不到时弹窗选定，存在即追加、不存在即按该名新建）优先；
     * 未指定时按 {@link NoteFileNames#isNoteCandidate} 谓词模糊匹配（原有逻辑）。</p>
     *
     * <p>ISOLATED 模式部分失败时：失败包远端已经被解锁阶段 restoreLock 还原为原 WAR，
     * 实际产物没有变更；如果仍给它们追加"取包/传包"会与远端事实不一致，运维对账抓瞎。
     * 通过 {@code skippedRelativePaths} 把失败包的 relativePath 排除掉，note 只追加给真实成功的目标。</p>
     *
     * <p>时间语义：取包时间 = 本次部署开始执行的实际时刻（{@code deployStartMs}，所有包共用）；
     * 传包时间 = 该包自己上传/嵌入完成的实际时刻（{@code uploadFinishTimes} 按 remoteDir+relativePath 查），
     * 多包各记各的、不串味。极端情况下某包查不到完成时刻时，兜底用写记录当下的实际时刻。</p>
     *
     * @param skippedRelativePaths 不写 note 的目标 relativePath 集合（可为 null/空，表示全量写入）
     * @param deployStartMs        本次部署开始执行的时刻（epoch millis），用作所有包的取包时间
     * @param uploadFinishTimes    各目标上传/嵌入完成的实际时刻，按 remoteDir+relativePath 索引；可为 null
     * @author xumanyi
     * @date 2026-05-28
     */
    private static void updateNoteForAll(
            PluginDeployConfig pluginConfig,
            List<FtpTargetSelection> allTargets,
            java.util.Set<String> skippedRelativePaths,
            long deployStartMs,
            java.util.Map<String, java.time.LocalDateTime> uploadFinishTimes,
            String host, int port, String user, String pass,
            Consumer<String> logCallback) throws Exception {

        java.time.format.DateTimeFormatter timeFmt =
                java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd HH:mm");
        // 取包时间：全局——本次部署开始执行的实际时刻，所有包共用
        String fetchTime = java.time.LocalDateTime.ofInstant(
                java.time.Instant.ofEpochMilli(deployStartMs),
                java.time.ZoneId.systemDefault()).format(timeFmt);
        // 传包时间兜底：某目标未记录完成时刻时，用写记录当下的实际时刻（绝不用占位/假时间）
        String noteNow = java.time.LocalDateTime.now().format(timeFmt);

        // 每个 note 文件用独立短连接（exists/download/upload 三次操作绑在一个会话里），
        // 避免共用一个长会话在多包遍历期间被服务端 421。
        for (FtpTargetSelection target : allTargets) {
            // ISOLATED 失败包跳过：远端已 restoreLock 回原 WAR，写 note 会产生"取包/传包"幻象记录
            if (skippedRelativePaths != null
                    && skippedRelativePaths.contains(target.getRelativePath())) {
                logCallback.accept("INFO  [说明] 跳过 " + target.getTargetName()
                        + "，该包本次未成功更新，不追加版本记录");
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
            final String packageNameForMatch = target.getTargetName();
            // 预检阶段用户手动指定的 note 文件名（模糊匹配不到时弹窗选定）；null 表示走谓词匹配
            final String manualNoteName = manualNoteSelections.get(remoteDir + relPath);

            Path tempNote = Files.createTempFile("note-", ".txt");
            try {
                // 构造新记录（标准格式，标签 + 中文冒号 + 值，字段间空格分隔）：
                //   取包时间：yyyyMMdd HH:mm 开发：{开发} 任务：{任务描述} 客服：{客服号} 包名称：{包名}
                //   传包时间：...（同上）
                String operator = nullToEmpty(pluginConfig.getOperator());
                String taskId = nullToEmpty(pluginConfig.getTaskId());
                String customerId = nullToEmpty(pluginConfig.getCustomerId());
                // 传包时间：该包自己上传/嵌入完成的实际时刻（key 与 recordSucceededUpload 一致）
                String targetKey = remoteDir + relPath;
                java.time.LocalDateTime finishTime =
                        uploadFinishTimes == null ? null : uploadFinishTimes.get(targetKey);
                String uploadTime = finishTime != null ? finishTime.format(timeFmt) : noteNow;
                final String fetchRecord = "取包时间：" + fetchTime
                        + " 开发：" + operator
                        + " 任务：" + taskId
                        + " 客服：" + customerId
                        + " 包名称：" + target.getTargetName();
                final String uploadRecord = "传包时间：" + uploadTime
                        + " 开发：" + operator
                        + " 任务：" + taskId
                        + " 客服：" + customerId
                        + " 包名称：" + target.getTargetName();

                final String[] resolvedName = new String[]{canonicalNoteName};
                // 把上传字节数从 lambda 内带出，供合并后的"已追加 X 条 (Y B)"日志使用
                final long[] expectedBytesHolder = new long[1];
                runFreshFtpSession(host, port, user, pass, (s, ops) -> {
                    // 1. 扫描包所在目录（listing 同时用于手动指定文件的存在性判断与谓词筛选）
                    java.util.List<FTPFile> dirListing = ops.listFiles(
                            stripTrailingSlashForList(packageDir));
                    String writeName;
                    String writePath;
                    String baseContent;
                    if (manualNoteName != null) {
                        // 用户手动指定（预检弹窗选定）：跳过谓词匹配直接定位该文件，
                        // 存在则原地追加，不存在（用户选了新建 / 自定义命名）则按该名新建
                        writeName = manualNoteName;
                        writePath = packageDir + manualNoteName;
                        boolean exists = false;
                        for (FTPFile entry : dirListing) {
                            if (entry != null && entry.isFile()
                                    && manualNoteName.equals(entry.getName())) {
                                exists = true;
                                break;
                            }
                        }
                        baseContent = exists
                                ? downloadNoteString(ops, writePath, writeName, logCallback)
                                : "";
                        logCallback.accept(exists
                                ? "[说明] 使用用户指定的版本记录文件：" + writeName
                                : "[说明] 新建用户指定的版本记录文件：" + writeName);
                    } else {
                        // 2. 谓词模糊匹配（原有逻辑）：筛候选 → pickPrimary 选目标；
                        //    优先 canonical 精确匹配，否则字节最多者；都没有就用 canonical 新建。
                        java.util.List<RemoteNoteEntry> candidates = new java.util.ArrayList<>();
                        for (FTPFile entry : dirListing) {
                            if (entry == null || !entry.isFile()) continue;
                            String fname = entry.getName();
                            if (!NoteFileNames.isNoteCandidate(packageNameForMatch, fname)) continue;
                            String fpath = packageDir + fname;
                            candidates.add(new RemoteNoteEntry(
                                    fname, fpath, downloadNoteString(ops, fpath, fname, logCallback)));
                        }
                        RemoteNoteEntry primary = pickPrimaryEntry(candidates, canonicalNoteName);
                        if (primary == null) {
                            writeName = canonicalNoteName;
                            writePath = packageDir + writeName;
                            baseContent = "";
                            logCallback.accept("[说明] 目录无当前包的版本记录文件，使用默认命名：" + writeName);
                        } else {
                            writeName = primary.name;
                            writePath = primary.path;
                            baseContent = primary.content;
                            if (candidates.size() > 1) {
                                logCallback.accept("[说明] 检测到 " + candidates.size()
                                        + " 个版本记录文件，原地追加到字节最多的：" + writeName);
                            }
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

                    // 4. 发布到 writePath：临时名 → 续传 → 校验 → rename。
                    //    版本记录是"读出全文 + 追加两行后整份写回"，原地覆盖一旦半传就会
                    //    截断历史记录，而它位于服务包目录之外、不在整包备份范围内，无从恢复
                    Files.writeString(tempNote, finalContent,
                            java.nio.charset.StandardCharsets.UTF_8);
                    expectedBytesHolder[0] = Files.size(tempNote);
                    uploadFileSafely(s, ops, tempNote, writePath, retryPrompter(), logCallback);
                });

                logCallback.accept("[说明] " + resolvedName[0] + " 已追加 2 条记录，"
                        + expectedBytesHolder[0] + " B");
            } finally {
                Files.deleteIfExists(tempNote);
            }
        }
    }

    /**
     * 从候选文件里选目标：0 个返回 null（调用者新建 canonical）；1 个直接返回；
     * ≥2 个属于冲突——本应由预检阶段（auditNoteConflicts）拦截并要求用户手动清理；落到此处属异常路径，直接抛错避免误写。
     *
     * @param candidates 命中谓词的所有候选
     * @param canonicalName canonical 文件名（仅用于异常信息）
     * @return 选中的目标，或 null
     * @throws IllegalStateException 候选 ≥2 时（应在预检拦截）
     * @author xumanyi
     * @date 2026-05-11
     */
    private static RemoteNoteEntry pickPrimaryEntry(
            java.util.List<RemoteNoteEntry> candidates, String canonicalName) {
        if (candidates.isEmpty()) return null;
        if (candidates.size() == 1) return candidates.get(0);
        StringBuilder names = new StringBuilder();
        for (RemoteNoteEntry rn : candidates) {
            if (names.length() > 0) names.append(", ");
            names.append(rn.name);
        }
        throw new IllegalStateException(
                "版本记录候选 ≥2，预检阶段应已拦截，请手动清理后重试，canonical=" + canonicalName + "，候选=[" + names + "]");
    }

    /**
     * 预检 note 审计：扫描每个目标包所在目录的 .txt 文件，拦截两类异常情况。
     *
     * <ul>
     *   <li><b>命中 ≥2 个候选</b>：冲突，弹错误框要求手动清理，预检失败（原有行为）；</li>
     *   <li><b>命中 0 个候选</b>：模糊匹配失败（命名差异过大或确实没有），弹
     *       {@link com.flux.deploy.plugin.toolwindow.NoteFileSelectDialog} 列出该目录下全部
     *       .txt 文件让用户单选，或按可编辑的标准命名新建；确定后登记到
     *       {@link #manualNoteSelections} 供执行阶段消费，取消则预检失败、中止本次更新。</li>
     * </ul>
     *
     * <p>恰好 1 个候选 = 模糊匹配成功，保持原有静默追加逻辑，不弹任何窗。</p>
     *
     * <p>调用条件：仅在用户勾选了「更新版本记录」（{@link PluginDeployConfig#isUpdateNote()}）时调用；
     * 由 {@code dryRun} 分支在 {@link DeployPipeline#execute()} 通过后触发。</p>
     *
     * <p>实现分两阶段：阶段 1 在 FTP 会话内一次性扫描收集所有目标的候选与目录 .txt 清单并关闭会话；
     * 阶段 2 纯 UI 交互（先拦全部冲突，再逐包弹选择框），弹窗等待用户期间不占用 FTP 连接，
     * 避免长时间思考导致会话被服务端超时断开。</p>
     *
     * <p>扫描为纯只读操作（{@link FtpOperations#listFiles}）。单个目标 listFiles 失败时记 WARN 日志、
     * 跳过该目标，不阻断整体（FTP 真不通会被前序 PreCheckGate 拦截）。</p>
     *
     * @return 审计失败信息（含包名与原因，已完成弹窗交互）；全部通过返回 null
     * @author xumanyi
     * @date 2026-07-18
     */
    private static NoteAuditFailure auditNoteFiles(
            Project project,
            PluginDeployConfig pluginConfig,
            String ftpHost, int ftpPort, String ftpUsername, String ftpPassword,
            Consumer<String> logCallback) {
        java.util.List<FtpTargetSelection> toCheck = new ArrayList<>();
        if (pluginConfig.getSourceProjectType()
                == com.flux.deploy.plugin.model.SourceProjectType.VUE
                && pluginConfig.getMainTargets() != null) {
            // Vue：模块目录目标聚合为工程包级 note 目标（{content}_update_notes.txt）
            toCheck.addAll(buildVueNoteTargets(pluginConfig, pluginConfig.getMainTargets()));
        } else if (pluginConfig.getMainTargets() != null) {
            toCheck.addAll(pluginConfig.getMainTargets());
        }
        if (pluginConfig.getEmbedTargets() != null) toCheck.addAll(pluginConfig.getEmbedTargets());
        if (toCheck.isEmpty()) return null;

        // ── 阶段 1：FTP 会话内扫描收集（候选命中 + 目录全部 .txt），随后立即释放连接 ──
        java.util.List<NoteScanResult> scans = new ArrayList<>();
        try (FtpSession session = new FtpSession(ftpHost, ftpPort)) {
            session.connect(ftpUsername, ftpPassword);
            FtpOperations ops = new FtpOperations(session);
            for (FtpTargetSelection target : toCheck) {
                String remoteDir = target.getRemoteDir();
                if (remoteDir == null) continue;
                if (!remoteDir.endsWith("/")) remoteDir = remoteDir + "/";
                String relPath = target.getRelativePath() == null ? "" : target.getRelativePath();
                int lastSlash = relPath.lastIndexOf('/');
                String packageDir = lastSlash > 0
                        ? remoteDir + relPath.substring(0, lastSlash + 1)
                        : remoteDir;
                String pkgName = target.getTargetName();

                java.util.List<FTPFile> listing;
                try {
                    listing = ops.listFiles(stripTrailingSlashForList(packageDir));
                } catch (java.io.IOException e) {
                    logCallback.accept("WARN  [预检] note 审计跳过 " + pkgName + "：" + e.getMessage());
                    continue;
                }
                java.util.List<String> hits = new ArrayList<>();
                java.util.List<String> allTxt = new ArrayList<>();
                for (FTPFile f : listing) {
                    if (f == null || !f.isFile()) continue;
                    String fname = f.getName();
                    if (fname != null && fname.toLowerCase().endsWith(".txt")) {
                        allTxt.add(fname);
                    }
                    if (NoteFileNames.isNoteCandidate(pkgName, fname)) {
                        hits.add(fname);
                    }
                }
                scans.add(new NoteScanResult(target, pkgName, packageDir, hits, allTxt));
            }
        } catch (Exception e) {
            logCallback.accept("WARN  [预检] 版本记录审计失败，跳过，不阻断部署：" + e.getMessage());
        }

        // ── 阶段 2a：先拦全部冲突（≥2 候选），避免用户先做了手动选择、又因后续包冲突白选一场 ──
        for (NoteScanResult scan : scans) {
            if (scan.hits.size() >= 2) {
                logCallback.accept("ERROR [预检] " + scan.pkgName + " 命中 " + scan.hits.size()
                        + " 个 note 文件，需手动清理: " + String.join(", ", scan.hits));
                showNoteConflictDialog(scan.pkgName, scan.hits);
                return new NoteAuditFailure(scan.pkgName, "note 文件冲突");
            }
        }

        // ── 阶段 2b：未匹配（0 候选）的包逐个弹选择框，选择结果登记给执行阶段 ──
        for (NoteScanResult scan : scans) {
            if (!scan.hits.isEmpty()) continue;
            // 新建目标（Vue 首次投放）：目录里必然没有它的版本记录文件，
            // 直接按规范命名新建，不弹选择框打断用户
            if (scan.target.isCreateNew()) {
                String canonical = NoteFileNames.canonicalName(scan.pkgName);
                String key = scan.target.getRemoteDir() + scan.target.getRelativePath();
                manualNoteSelections.put(key, canonical);
                logCallback.accept("INFO  [预检] " + scan.pkgName
                        + " 为新建目标，版本记录文件将新建：" + canonical);
                continue;
            }
            logCallback.accept("WARN  [预检] " + scan.pkgName
                    + " 未匹配到版本记录文件，等待用户选择（目录内共 "
                    + scan.allTxtFiles.size() + " 个 TXT 文件）");
            NoteFileChoice choice = showNoteFileSelectDialog(
                    project, scan.pkgName, scan.packageDir, scan.allTxtFiles);
            if (choice == null) {
                logCallback.accept("ERROR [预检] " + scan.pkgName
                        + " 用户取消了版本记录文件选择，中止本次更新");
                return new NoteAuditFailure(scan.pkgName, "用户取消了版本记录文件选择");
            }
            String key = scan.target.getRemoteDir() + scan.target.getRelativePath();
            manualNoteSelections.put(key, choice.fileName);
            logCallback.accept(choice.createNew
                    ? "INFO  [预检] " + scan.pkgName + " 版本记录文件将新建：" + choice.fileName
                    : "INFO  [预检] " + scan.pkgName + " 版本记录文件由用户指定：" + choice.fileName
                            + "，更新时原地追加");
        }
        return null;
    }

    /**
     * 在 EDT 上弹出版本记录文件选择对话框并同步等待用户操作。
     *
     * @param project    IDEA 项目（对话框定位用）
     * @param pkgName    目标包名（含扩展名）
     * @param packageDir 包所在远端目录（展示用）
     * @param txtFiles   该目录下全部 .txt 文件名
     * @return 用户的选择（文件名 + 是否新建）；用户取消返回 null
     * @author xumanyi
     * @date 2026-07-18
     */
    private static NoteFileChoice showNoteFileSelectDialog(
            Project project, String pkgName, String packageDir, java.util.List<String> txtFiles) {
        final NoteFileChoice[] holder = new NoteFileChoice[1];
        ApplicationManager.getApplication().invokeAndWait(() -> {
            com.flux.deploy.plugin.toolwindow.NoteFileSelectDialog dialog =
                    new com.flux.deploy.plugin.toolwindow.NoteFileSelectDialog(
                            project, pkgName, packageDir, txtFiles,
                            NoteFileNames.canonicalName(pkgName));
            if (dialog.showAndGet()) {
                holder[0] = new NoteFileChoice(dialog.getSelectedFileName(), dialog.isCreateNew());
            }
        });
        return holder[0];
    }

    /** note 审计失败信息：失败的包名 + 面向用户的失败原因（用于日志与 DeployResult error）。 */
    private static final class NoteAuditFailure {
        final String pkgName;
        final String reason;

        NoteAuditFailure(String pkgName, String reason) {
            this.pkgName = pkgName;
            this.reason = reason;
        }
    }

    /** 单个目标包的 note 扫描结果：候选命中列表 + 目录下全部 .txt 清单（供选择弹窗展示）。 */
    private static final class NoteScanResult {
        final FtpTargetSelection target;
        final String pkgName;
        final String packageDir;
        final java.util.List<String> hits;
        final java.util.List<String> allTxtFiles;

        NoteScanResult(FtpTargetSelection target, String pkgName, String packageDir,
                       java.util.List<String> hits, java.util.List<String> allTxtFiles) {
            this.target = target;
            this.pkgName = pkgName;
            this.packageDir = packageDir;
            this.hits = hits;
            this.allTxtFiles = allTxtFiles;
        }
    }

    /** 用户在选择弹窗里的最终决定：目标文件名 + 是否属于新建（仅影响日志文案）。 */
    private static final class NoteFileChoice {
        final String fileName;
        final boolean createNew;

        NoteFileChoice(String fileName, boolean createNew) {
            this.fileName = fileName;
            this.createNew = createNew;
        }
    }

    /**
     * 在 EDT 上弹出 note 文件冲突对话框（标准 ErrorDialog，单按钮）。
     *
     * @param pkgName 目标包名
     * @param hits    命中的所有 note 文件名（含扩展）
     * @author xumanyi
     * @date 2026-05-26
     */
    private static void showNoteConflictDialog(String pkgName, java.util.List<String> hits) {
        StringBuilder body = new StringBuilder();
        body.append("目标包 ").append(pkgName).append(" 远端命中多个 note 文件：\n");
        for (String name : hits) {
            body.append("\n  • ").append(name);
        }
        body.append("\n\n请手动保留其中一个后重试。");
        final String message = body.toString();
        ApplicationManager.getApplication().invokeAndWait(
                () -> com.flux.deploy.plugin.util.FluxDialogs.error(
                        (Project) null, message, "版本记录文件冲突"));
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
     * 下载远端 note 文件并宽容解码为字符串。临时文件用后即删；文件非 UTF-8 时自动回退 GB18030，
     * 绝不因编码非法而抛异常中断「说明」阶段。
     *
     * @param ops         当前 FTP 会话
     * @param remotePath  远端文件绝对路径
     * @param displayName 用于日志的文件名
     * @param log         日志回调，文件非 UTF-8 时输出一条说明
     * @return 解码后的文本内容（写回时统一转 UTF-8）
     * @throws java.io.IOException 下载或读取失败
     * @author xumanyi
     * @date 2026-05-11
     */
    private static String downloadNoteString(FtpOperations ops, String remotePath,
            String displayName, Consumer<String> log) throws java.io.IOException {
        Path tmp = Files.createTempFile("note-dl-", ".txt");
        try {
            ops.download(remotePath, tmp);
            return NoteCharsetReader.readLenient(tmp, displayName, log);
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
                logCallback.accept("[加锁] " + packageName);
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
                                : lockName.split("__LOCK__")[0]));
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
                                + "，远端目前只有锁文件，没有原 WAR，需要人工从备份目录恢复");
                    } else {
                        logCallback.accept("[解锁] 解锁失败：" + lockName + " - " + e.getMessage()
                                + "，请人工检查 FTP 上 " + (origName != null ? origName : "原文件")
                                + " 是否存在；如缺失，可从备份目录手动恢复");
                    }
                }
            }
        } catch (Exception e) {
            logCallback.accept("[解锁] FTP 连接失败：" + e.getMessage());
        }
        return restoreFailedLockNames;
    }

    /**
     * 回滚版本记录：删除每个包 note 文件的最后 2 行（取包+传包记录）
     *
     * <p>文件定位与 {@link #updateNoteForAll} 对称：部署时若消费了用户手动指定的文件名
     * （{@link #lastManualNoteSelections} 快照），回滚按精确名找回同一文件（手动指定的名字
     * 可能完全不符合谓词，扫描找不回）；否则按谓词扫描 + pickPrimaryEntry（原有逻辑）。</p>
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
            // 部署时用户手动指定的 note 文件名（快照）；非 null 时按精确名找回
            final String manualNoteName = lastManualNoteSelections.get(remoteDir + relPath);

            // 用于异常日志的文件名（首选标准名，待会话里解析出实际名再覆盖）
            final String[] resolvedName = new String[]{canonicalNoteName};
            try {
                Path tempNote = Files.createTempFile("note-rollback-", ".txt");
                // action[0]: null=未修改; "delete"=note 文件本次部署首次创建，已整体删除; "trim"=已截除最后 2 条记录
                final String[] action = new String[]{null};
                final String[] resolvedPath = new String[]{null};
                try {
                    boolean modified = withFreshFtpSession(host, port, user, pass, (s, ops) -> {
                        // 用和 updateNoteForAll 一致的定位逻辑找回被写入的文件
                        java.util.List<FTPFile> dirListing = ops.listFiles(
                                stripTrailingSlashForList(packageDir));
                        RemoteNoteEntry primary;
                        if (manualNoteName != null) {
                            // 部署时写的是用户手动指定的文件，按精确名找回
                            primary = null;
                            for (FTPFile entry : dirListing) {
                                if (entry == null || !entry.isFile()) continue;
                                if (!manualNoteName.equals(entry.getName())) continue;
                                String fpath = packageDir + manualNoteName;
                                primary = new RemoteNoteEntry(manualNoteName, fpath,
                                        downloadNoteString(ops, fpath, manualNoteName, logCallback));
                                break;
                            }
                        } else {
                            // 谓词扫描 + pickPrimaryEntry（原有逻辑）
                            java.util.List<RemoteNoteEntry> candidates = new java.util.ArrayList<>();
                            for (FTPFile entry : dirListing) {
                                if (entry == null || !entry.isFile()) continue;
                                String fname = entry.getName();
                                if (!NoteFileNames.isNoteCandidate(packageNameForMatch, fname)) continue;
                                String fpath = packageDir + fname;
                                candidates.add(new RemoteNoteEntry(
                                        fname, fpath, downloadNoteString(ops, fpath, fname, logCallback)));
                            }
                            primary = pickPrimaryEntry(candidates, canonicalNoteName);
                        }
                        if (primary == null) {
                            return Boolean.FALSE;
                        }
                        String notePath = primary.path;
                        resolvedName[0] = primary.name;
                        resolvedPath[0] = notePath;
                        ops.download(notePath, tempNote);
                        String content = NoteCharsetReader.readLenient(
                                tempNote, primary.name, logCallback);

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
                        // 原子发布：截除记录同样是整份写回，半传会把历史记录截断且无副本
                        ops.uploadAtomic(tempNote, notePath);
                        action[0] = "trim";
                        return Boolean.TRUE;
                    });
                    if (modified) {
                        if ("delete".equals(action[0])) {
                            logCallback.accept("INFO  [回滚] " + resolvedName[0] + " 已删除，本次部署首次创建");
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
            if (logCallback != null) logCallback.accept("[备份检查] 开发人字段为空，跳过检查，建议填写开发以启用冲突检测");
            return conflicts;
        }

        try (FtpSession session = new FtpSession(host, port)) {
            session.connect(user, pass);
            FtpOperations ops = new FtpOperations(session);

            DateTimeFormatter dateFmt = DateTimeFormatter.ofPattern("yyyyMMdd");
            String dateStr = LocalDate.now().format(dateFmt);

            // Vue 目录目标的备份条目是目录，逐个 exists（只认文件）查不出来：
            // 按父目录 LIST 一次并缓存，目录/文件条目都能命中，多模块共享同一次 LIST
            java.util.Map<String, List<FTPFile>> parentListingCache = new java.util.HashMap<>();
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
                boolean exists;
                if (t.isVueModuleDir()) {
                    String parentPath = backupParent + dateStr + "_" + operator + "/" + subDir;
                    List<FTPFile> entries = parentListingCache.get(parentPath);
                    if (entries == null) {
                        entries = ops.listFiles(parentPath);
                        parentListingCache.put(parentPath, entries);
                    }
                    exists = false;
                    for (FTPFile f : entries) {
                        if (f != null && t.getTargetName().equals(f.getName())) {
                            exists = true;
                            break;
                        }
                    }
                } else {
                    exists = ops.exists(backupPath);
                }
                if (logCallback != null && exists) {
                    logCallback.accept("INFO  [备份] 发现已有备份：" + backupPath);
                }
                if (exists) {
                    conflicts.add((subDir.isEmpty() ? "" : subDir) + t.getTargetName());
                }
            }
        }
        return conflicts;
    }

    /**
     * 计算单个目标包所在目录（用于残留锁扫描）。
     *
     * <p>取 {@code remoteDir + relativePath} 的父目录并保证以 {@code /} 结尾。</p>
     *
     * @param t 目标包
     * @return 目标包所在目录（以 / 结尾）
     * @author xumanyi
     * @date 2026-05-28
     */
    private static String lockDirFor(FtpTargetSelection t) {
        String rp = t.getRemoteDir() + t.getRelativePath();
        int lastSlash = rp.lastIndexOf('/');
        String dir = lastSlash > 0 ? rp.substring(0, lastSlash + 1) : t.getRemoteDir();
        return dir.endsWith("/") ? dir : dir + "/";
    }

    /**
     * 诊断所有目标包目录下的残留锁。
     *
     * <p>供 UI 层在<b>部署确认对话框之前</b>调用，把残留锁的发现与处理统一前移到确认之前，
     * 避免用户点「确认执行」后才被残留锁弹窗打断。返回合并后的诊断列表，由 UI 决定是否弹窗清理。</p>
     *
     * @param host        FTP 主机
     * @param port        FTP 端口
     * @param user        FTP 用户名
     * @param pass        FTP 密码
     * @param operator    当前开发（用于判定锁归属）
     * @param targets     待扫描的目标包（主 + 嵌入）
     * @param logCallback 日志回调，可为 null
     * @return 所有目标合并后的残留锁诊断列表；无残留时为空列表
     * @throws java.io.IOException FTP 连接或扫描失败
     * @author xumanyi
     * @date 2026-05-28
     */
    public static List<ResidualLockDiagnosis> diagnoseResidualLocks(
            String host, int port, String user, String pass,
            String operator, List<FtpTargetSelection> targets,
            Consumer<String> logCallback) throws java.io.IOException {
        List<ResidualLockDiagnosis> all = new ArrayList<>();
        if (targets == null || targets.isEmpty()) {
            return all;
        }
        try (FtpSession session = new FtpSession(host, port)) {
            session.connect(user, pass);
            FtpOperations ops = new FtpOperations(session);
            FtpLock lock = new FtpLock(ops);
            ResidualLockResolver resolver = new ResidualLockResolver(
                    ResidualLockResolver.wrap(ops, lock), operator);
            for (FtpTargetSelection t : targets) {
                all.addAll(resolver.diagnose(lockDirFor(t), t.getTargetName()));
            }
        }
        return all;
    }

    /**
     * 应用用户勾选的残留锁清理动作，并复检剩余残留。
     *
     * <p>与 {@link #diagnoseResidualLocks} 配套，供 UI 层在确认对话框之前调用：用户在弹窗中
     * 勾选要清理的诊断后，逐条 apply（删锁 / 恢复原文件名），随后重新扫描所有目标，返回仍存在的残留锁。</p>
     *
     * @param host        FTP 主机
     * @param port        FTP 端口
     * @param user        FTP 用户名
     * @param pass        FTP 密码
     * @param operator    当前开发
     * @param targets     待复检的目标包（主 + 嵌入）
     * @param selected    用户勾选要清理的诊断；为 null 视为不清理仅复检
     * @param logCallback 日志回调，可为 null
     * @return 清理后仍存在的残留锁；为空表示已清理干净
     * @throws java.io.IOException FTP 连接失败，或某条清理动作失败
     * @author xumanyi
     * @date 2026-05-28
     */
    public static List<ResidualLockDiagnosis> applyResidualLockResolution(
            String host, int port, String user, String pass,
            String operator, List<FtpTargetSelection> targets,
            List<ResidualLockDiagnosis> selected,
            Consumer<String> logCallback) throws java.io.IOException {
        List<ResidualLockDiagnosis> remaining = new ArrayList<>();
        try (FtpSession session = new FtpSession(host, port)) {
            session.connect(user, pass);
            FtpOperations ops = new FtpOperations(session);
            FtpLock lock = new FtpLock(ops);
            ResidualLockResolver resolver = new ResidualLockResolver(
                    ResidualLockResolver.wrap(ops, lock), operator);
            if (selected != null) {
                for (ResidualLockDiagnosis d : selected) {
                    resolver.apply(d);
                    if (logCallback != null) {
                        logCallback.accept("INFO  [残留锁] 已清理: " + d.getLockFileName());
                    }
                }
            }
            if (targets != null) {
                for (FtpTargetSelection t : targets) {
                    remaining.addAll(resolver.diagnose(lockDirFor(t), t.getTargetName()));
                }
            }
        }
        return remaining;
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
        // Vue 源：目标是模块 zip，由本地 dist/umd/{模块号}/ 现打现传，不依赖 target/ 产物
        if (pluginConfig.getSourceProjectType()
                == com.flux.deploy.plugin.model.SourceProjectType.VUE) {
            return prepareVueZipForMainTarget(pluginConfig, mainTarget, logCallback);
        }
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
        builder.setCsvMergePlan(pluginConfig.getCsvMergePlan());
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

    /**
     * Vue 源：为单个主目标构建模块更新包 zip
     *
     * <p>目标 → 模块路由：按目标包名（兼容新旧两代命名）反解模块号；解析不出且本次只勾选了
     * 一个模块时路由到该模块（允许 FTP 上的 zip 被重命名过）；其余情况报错中止，
     * 避免把 A 模块的内容传到 B 模块的包上。</p>
     *
     * @param pluginConfig 插件配置（取 vueContent / vueModules / modulePath）
     * @param mainTarget   主目标
     * @param logCallback  日志回调
     * @return 生成的 zip 路径（每次调用独立临时目录，多目标互不覆盖）
     * @throws Exception 路由失败或打包失败
     * @author xumanyi
     * @date 2026-08-13
     */
    private static Path prepareVueZipForMainTarget(
            PluginDeployConfig pluginConfig, FtpTargetSelection mainTarget,
            Consumer<String> logCallback) throws Exception {
        String content = pluginConfig.getVueContent();
        List<String> modules = pluginConfig.getVueModules();
        if (content == null || content.isBlank() || modules == null || modules.isEmpty()) {
            throw new java.io.IOException("Vue 部署缺少上下文名或模块选择");
        }
        // 目录目标（本地打包场景走到这里）：targetName 即模块号
        String moduleId = mainTarget.isVueModuleDir()
                ? mainTarget.getTargetName()
                : VueProjectResolver.moduleIdFromZipName(content, mainTarget.getTargetName());
        if (moduleId == null && modules.size() == 1) {
            moduleId = modules.get(0);
        }
        if (moduleId == null) {
            throw new java.io.IOException("无法从目标包名 " + mainTarget.getTargetName()
                    + " 解析模块号（命名应为 " + content + "_模块号.zip），且本次勾选了多个模块无法唯一路由");
        }
        // 归一化到源工程勾选列表里的规范大小写：FTP 包名可能大小写不一（如 T0107），
        // 直接拿它当 dist 目录名/zip 条目前缀会在大小写不敏感文件系统上打出错误路径
        final String resolvedId = moduleId;
        String canonicalId = null;
        for (String m : modules) {
            if (m.equalsIgnoreCase(resolvedId)) {
                canonicalId = m;
                break;
            }
        }
        if (canonicalId == null) {
            throw new java.io.IOException("目标包 " + mainTarget.getTargetName()
                    + " 对应模块 " + resolvedId + " 未在源工程中勾选");
        }
        Path zip = VueZipBuilder.buildModuleZip(
                Path.of(pluginConfig.getModulePath()), content, canonicalId, logCallback);
        // 登记临时目录，deploy 收尾 finally 统一清理
        vueZipTempDirs.add(zip.getParent());
        return zip;
    }

    /**
     * 登记一条"已成功上传"的远端路径（目录直更逐文件登记，供回滚删除保护判定）
     *
     * @param remotePath 远端绝对路径
     * @author xumanyi
     * @date 2026-08-13
     */
    private static void recordSucceededUploadPath(String remotePath) {
        List<String[]> sink = currentSucceededUploads;
        if (sink == null || remotePath == null) return;
        sink.add(new String[]{remotePath, null});
    }

    /**
     * 递归列出远端目录下的全部文件（相对路径 → 字节数）
     *
     * @param ops       FTP 操作
     * @param absDir    远端目录绝对路径（以 / 结尾）
     * @param relPrefix 相对路径前缀（顶层传 ""）
     * @param result    结果收集（相对路径 → size）
     * @param depth     当前深度（顶层 0，最大 6 层防失控）
     * @throws java.io.IOException FTP 操作失败
     * @author xumanyi
     * @date 2026-08-13
     */
    private static void listRemoteFilesRecursive(FtpOperations ops, String absDir, String relPrefix,
            java.util.Map<String, Long> result, int depth) throws java.io.IOException {
        // 上限命中必须显式失败：整包清单是备份范围与"新增文件"判定的依据，
        // 静默截断会让清单外的既有文件漏备份、被误标 NEW，回滚时误删且无备份可救
        if (depth > 6) {
            throw new java.io.IOException("目录层级超过 6 层，中止清单收集（清单不完整会导致漏备份/误判新增）: " + absDir);
        }
        if (result.size() > 20000) {
            throw new java.io.IOException("工程包文件数超过 20000，中止清单收集（清单不完整会导致漏备份/误判新增）");
        }
        // 部分 FTP 服务端对带尾斜杠的 LIST 返回空，统一剥掉（与 note 审计口径一致）
        String listPath = absDir.length() > 1 && absDir.endsWith("/")
                ? absDir.substring(0, absDir.length() - 1) : absDir;
        for (org.apache.commons.net.ftp.FTPFile f : ops.listFiles(listPath)) {
            if (f == null) continue;
            String name = f.getName();
            if (".".equals(name) || "..".equals(name)) continue;
            if (f.isFile()) {
                result.put(relPrefix + name, f.getSize());
            } else if (f.isDirectory()) {
                listRemoteFilesRecursive(ops, absDir + name + "/",
                        relPrefix + name + "/", result, depth + 1);
            }
        }
    }

    /**
     * Vue 目录直更主流程：把本地 {@code dist/umd/{模块号}/} 逐文件覆盖到 FTP 上
     * 解包形态的模块目录（{content}/{模块号}/...）。
     *
     * <p>阶段：备份（逐文件镜像到 backup 目录，新建模块无备份）→ 上传（子目录按需创建，
     * manifest.json 最后传——它是模块入口清单，最后切换可把"半新半旧"窗口压到最小）→
     * 校验（逐文件远端字节数比对）→ 版本记录（{content} 目录下 {模块号}_update_notes.txt，
     * 复用单文件链路的写入逻辑）。任一阶段失败即整体回滚：被覆盖文件从备份写回、
     * 本次新增文件删除。成功后登记手动回滚数据。</p>
     *
     * <p>不做目录级加锁：rename 目录会让线上引用瞬断，且解包目录本就以"覆盖发布"为惯例；
     * 上传顺序（manifest 最后）+ 失败回滚承担一致性职责。</p>
     *
     * @param pluginConfig 插件配置（VUE 源）
     * @param dirTargets   模块目录目标列表（全部 isVueModuleDir=true）
     * @param host         FTP 主机
     * @param port         FTP 端口
     * @param user         FTP 用户名
     * @param pass         FTP 密码
     * @param logCallback  日志回调
     * @param onComplete   完成回调
     * @author xumanyi
     * @date 2026-08-13
     */
    private static void runVueDirDeploy(PluginDeployConfig pluginConfig,
            List<FtpTargetSelection> dirTargets,
            String host, int port, String user, String pass,
            Consumer<String> logCallback, Consumer<DeployResult> onComplete) {
        final long deployStartMs = System.currentTimeMillis();
        currentTotalTargets = dirTargets.size();
        currentVueDirDeploy = true;
        String content = pluginConfig.getVueContent();
        Path projectRoot = Path.of(pluginConfig.getModulePath());
        logCallback.accept("INFO  [部署] Vue 更新：" + dirTargets.size()
                + " 个模块（" + content + "）");

        // 回滚清单（逐文件）：[远端文件, 备份文件] 或 [远端文件, null, NEW]
        List<String[]> updatedFiles = java.util.Collections.synchronizedList(new ArrayList<>());
        // 已完成目录切换的模块：失败时用旧目录快速换回，成功后搬回历史文件并清理旧目录
        List<VueModuleSwitch> switchedModules = new ArrayList<>();
        String backupDir = null;
        // USE_EXISTING 策略下为 true：备份借用自已有目录，回滚时只恢复文件不清理备份
        boolean backupBorrowed = false;

        try {
            // ── Phase 0: 自动构建（每次更新都重新构建勾选的模块） ──
            // 不做"仅构建未构建/过期"的增量判断：过期判断基于模块目录 mtime，感知不到
            // src/modules 之外共享代码的改动；每次强制重建才能保证不把旧产物更新上去
            List<VueProjectResolver.VueModule> allModules = VueProjectResolver.listModules(projectRoot);
            List<String> needBuild = new ArrayList<>();
            for (FtpTargetSelection t : dirTargets) {
                for (VueProjectResolver.VueModule m : allModules) {
                    if (m.id.equalsIgnoreCase(t.getTargetName())) {
                        needBuild.add(m.id);
                        break;
                    }
                }
            }
            if (!needBuild.isEmpty()) {
                long buildStart = System.currentTimeMillis();
                logCallback.accept("INFO  [构建] 自动构建 " + needBuild.size() + " 个模块："
                        + String.join("、", needBuild) + "（build:one --force，无 git 副作用）");
                VueModuleBuildRunner.buildModules(projectRoot, needBuild,
                        line -> logCallback.accept(
                                com.flux.deploy.plugin.toolwindow.LogSectionPanel.RAW_LINE_MARK + line),
                        () -> currentCancelMode != CancelMode.NONE);
                // 构建后逐模块复验产物就绪
                for (String id : needBuild) {
                    if (!Files.isRegularFile(projectRoot.resolve(VueProjectResolver.DIST_UMD_DIR)
                            .resolve(id).resolve("manifest.json"))) {
                        throw new java.io.IOException("模块 " + id
                                + " 构建后仍无 manifest.json，请检查上方构建输出");
                    }
                }
                logCallback.accept("INFO  [构建] 完成，耗时 "
                        + formatElapsed(System.currentTimeMillis() - buildStart));
            }

            // ── 准备：收集每个模块的本地文件清单（应用排除清单；manifest.json 排最后） ──
            java.util.Map<String, List<String>> excludedByModule = pluginConfig.getVueExcludedFiles();
            java.util.Map<FtpTargetSelection, List<Path>> filesByTarget = new java.util.LinkedHashMap<>();
            for (FtpTargetSelection t : dirTargets) {
                Path localDir = projectRoot.resolve(VueProjectResolver.DIST_UMD_DIR)
                        .resolve(t.getTargetName());
                if (!Files.isRegularFile(localDir.resolve("manifest.json"))) {
                    throw new java.io.IOException("模块 " + t.getTargetName()
                            + " 产物缺失（无 manifest.json）: " + localDir);
                }
                List<String> excludedList = excludedByModule == null ? null
                        : excludedByModule.get(t.getRelativePath());
                java.util.Set<String> excluded = excludedList == null
                        ? java.util.Set.of() : new java.util.HashSet<>(excludedList);
                List<Path> files = new ArrayList<>();
                try (var stream = Files.walk(localDir)) {
                    stream.filter(Files::isRegularFile)
                            .filter(p -> {
                                String n = p.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
                                return !n.endsWith(".zip") && !".ds_store".equals(n);
                            })
                            .filter(p -> !excluded.contains(localDir.relativize(p).toString()
                                    .replace('\\', '/')))
                            .forEach(files::add);
                }
                if (files.isEmpty()) {
                    throw new java.io.IOException("模块 " + t.getTargetName()
                            + " 的产物文件被全部取消勾选，没有可上传内容");
                }
                if (!excluded.isEmpty()) {
                    logCallback.accept("INFO  [部署] 模块 " + t.getTargetName() + "：按勾选排除 "
                            + excluded.size() + " 个文件，上传 " + files.size() + " 个");
                }
                // 排序保证稳定；manifest.json（未被排除时）挪到最后上传
                files.sort(java.util.Comparator.comparing(p -> localDir.relativize(p).toString()));
                Path manifest = localDir.resolve("manifest.json");
                if (files.remove(manifest)) {
                    files.add(manifest);
                }
                filesByTarget.put(t, files);
            }

            // ── Phase 0.9: 现场自愈（必须早于备份）──
            // 上次更新若在切换中途崩掉，远端可能留着 .__NEW__ / .__OLD__ 影子目录，
            // 甚至正式模块目录缺失。先把现场恢复成"上次更新前"的样子，再据此做备份与比对；
            // 放到备份之后做的话，自愈带来的变化会被并发闸门误判成"他人改动"
            runFreshFtpSessionWithRetry(host, port, user, pass, (s, ops) -> {
                for (FtpTargetSelection t : dirTargets) {
                    cleanupShadowLeftovers(s, ops, t.getRemoteDir() + t.getRelativePath() + "/",
                            logCallback);
                }
            });

            // ── Phase 1: 备份（整工程包镜像 —— 按团队约定备份整个 content 目录，
            //    不只备份本次更新的模块；FTP 会话复用 + 断线重连控制零散小文件的耗时） ──
            // 去重出本次涉及的 content 目录（模块目录的父级，如 sce-vcom-test/sec/A06SysBizWebVue）
            java.util.LinkedHashMap<String, String> contentDirRels = new java.util.LinkedHashMap<>();
            for (FtpTargetSelection t : dirTargets) {
                String rel = t.getRelativePath();
                int slash = rel.lastIndexOf('/');
                String contentRel = slash > 0 ? rel.substring(0, slash) : "";
                contentDirRels.putIfAbsent(contentRel, t.getRemoteDir());
            }
            // 整包现状清单（相对 content 目录的路径 → 字节数）：备份与"新增文件识别"共用
            java.util.Map<String, java.util.Map<String, Long>> contentListings =
                    new java.util.LinkedHashMap<>();
            logCallback.accept("INFO  [备份] 读取远端工程包现状清单...");
            for (java.util.Map.Entry<String, String> e : contentDirRels.entrySet()) {
                checkVueDirCancelled();
                String contentAbs = e.getValue() + (e.getKey().isEmpty() ? "" : e.getKey() + "/");
                java.util.Map<String, Long> listing = new java.util.LinkedHashMap<>();
                // 整包清单是备份范围与"新增文件"判定的依据，网络抖断时换连接重来一次
                // （清单是全量重建，重试不会产生重复项）
                runFreshFtpSessionWithRetry(host, port, user, pass, (s, ops) -> {
                    listing.clear();
                    listRemoteFilesRecursive(ops, contentAbs, "", listing, 0);
                });
                contentListings.put(e.getKey(), listing);
            }
            int contentFileCount = 0;
            long contentByteCount = 0;
            for (java.util.Map<String, Long> listing : contentListings.values()) {
                contentFileCount += listing.size();
                for (Long sz : listing.values()) {
                    if (sz != null && sz > 0) contentByteCount += sz;
                }
            }
            logCallback.accept("INFO  [备份] 工程包现状：" + contentFileCount + " 个文件，共 "
                    + formatSize(contentByteCount));

            // 派生每个模块目标的远端现状（识别本次"新增"文件、回滚可删）
            java.util.Map<String, java.util.Map<String, Long>> remoteFilesByTarget =
                    new java.util.HashMap<>();
            for (FtpTargetSelection t : dirTargets) {
                String rel = t.getRelativePath();
                int slash = rel.lastIndexOf('/');
                String contentRel = slash > 0 ? rel.substring(0, slash) : "";
                String modulePrefix = t.getTargetName() + "/";
                java.util.Map<String, Long> moduleFiles = new java.util.LinkedHashMap<>();
                for (java.util.Map.Entry<String, Long> f
                        : contentListings.getOrDefault(contentRel, java.util.Map.of()).entrySet()) {
                    if (f.getKey().regionMatches(true, 0, modulePrefix, 0, modulePrefix.length())) {
                        // 键统一转小写：文件级"是否已存在"（NEW 判定）与模块前缀匹配同口径
                        // 忽略大小写，避免远端大小写不一致的既有文件被误标 NEW、回滚误删
                        moduleFiles.put(f.getKey().substring(modulePrefix.length())
                                .toLowerCase(java.util.Locale.ROOT), f.getValue());
                    }
                }
                remoteFilesByTarget.put(rel, moduleFiles);
            }
            // 本次真正会被写入的远端路径：只有它们在回滚时需要写回，
            // 也只有它们值得逐个去问原始修改时间（整包问一遍要几百次往返、白等半分钟）
            java.util.Set<String> willOverwritePaths = new java.util.LinkedHashSet<>();
            for (java.util.Map.Entry<FtpTargetSelection, List<Path>> fe : filesByTarget.entrySet()) {
                FtpTargetSelection t = fe.getKey();
                Path localDirOfTarget = projectRoot.resolve(VueProjectResolver.DIST_UMD_DIR)
                        .resolve(t.getTargetName());
                String moduleAbs = t.getRemoteDir() + t.getRelativePath() + "/";
                for (Path f : fe.getValue()) {
                    willOverwritePaths.add(moduleAbs
                            + localDirOfTarget.relativize(f).toString().replace('\\', '/'));
                }
            }

            if (!pluginConfig.isSkipBackup()) {
                com.flux.deploy.plugin.model.BackupConflictStrategy strategy =
                        pluginConfig.getBackupConflictStrategy();
                String dateOperator = new java.text.SimpleDateFormat("yyyyMMdd").format(new java.util.Date())
                        + "_" + nullToEmpty(pluginConfig.getOperator());
                String backupParent = resolveBackupRoot(pluginConfig, dirTargets.get(0).getRemoteDir());
                if (strategy == com.flux.deploy.plugin.model.BackupConflictStrategy.USE_EXISTING) {
                    // 使用已有备份：不做镜像，把已有备份中的对应路径登记为回滚源
                    backupBorrowed = true;
                    backupDir = backupParent + dateOperator + "/";
                    logCallback.accept("INFO  [备份] 沿用已有备份作为回滚源，不再重复备份：" + backupDir);
                    List<String[]> borrowedEntries = new ArrayList<>();
                    for (java.util.Map.Entry<String, String> e : contentDirRels.entrySet()) {
                        String contentRel = e.getKey();
                        String contentAbs = e.getValue() + (contentRel.isEmpty() ? "" : contentRel + "/");
                        String backupBase = backupDir + (contentRel.isEmpty() ? "" : contentRel + "/");
                        for (String rel : contentListings
                                .getOrDefault(contentRel, java.util.Map.of()).keySet()) {
                            borrowedEntries.add(new String[]{contentAbs + rel, backupBase + rel,
                                    null, null});
                        }
                    }
                    // 借用的备份必须覆盖本次会被覆盖的每个文件（远端已存在且本地要上传的），
                    // 否则更新失败时这些文件无从恢复：上传前先校验，缺一个就中止（远端零变更）
                    java.util.Map<String, java.util.Map<String, Long>> mustCover =
                            new java.util.LinkedHashMap<>();
                    for (java.util.Map.Entry<FtpTargetSelection, List<Path>> fe : filesByTarget.entrySet()) {
                        FtpTargetSelection t = fe.getKey();
                        String rel = t.getRelativePath();
                        int slash = rel.lastIndexOf('/');
                        String contentRel = slash > 0 ? rel.substring(0, slash) : "";
                        java.util.Map<String, Long> existing = remoteFilesByTarget
                                .getOrDefault(rel, java.util.Map.of());
                        Path localDir = projectRoot.resolve(VueProjectResolver.DIST_UMD_DIR)
                                .resolve(t.getTargetName());
                        for (Path f : fe.getValue()) {
                            String fileRel = localDir.relativize(f).toString().replace('\\', '/');
                            if (existing.containsKey(fileRel.toLowerCase(java.util.Locale.ROOT))) {
                                mustCover.computeIfAbsent(contentRel, k -> new java.util.LinkedHashMap<>())
                                        .put(t.getTargetName() + "/" + fileRel, null);
                            }
                        }
                    }
                    final List<String> coverageProblems = new ArrayList<>();
                    if (!mustCover.isEmpty()) {
                        logCallback.accept("INFO  [备份] 核对已有备份是否覆盖本次要改的文件...");
                        final String borrowedDir = backupDir;
                        runFreshFtpSessionWithRetry(host, port, user, pass, (s, ops) ->
                                coverageProblems.addAll(
                                        verifyBackupCoverage(mustCover, borrowedDir, false, ops)));
                    }
                    if (!coverageProblems.isEmpty()) {
                        abortOnBackupProblems(coverageProblems,
                                "沿用的已有备份（请改选「覆盖备份」或「新建备份目录」）", logCallback);
                    }
                    logCallback.accept("INFO  [备份] 核对通过：本次要改的文件都能从该备份恢复");
                    // 上传尚未发生，此刻远端还是原文件：补记原始修改时间供回滚写回。
                    // 只问本次会被覆盖的那些文件——整包逐个问要几百次往返，而回滚也只写回这些
                    try {
                        runFreshFtpSession(host, port, user, pass, (s, ops) -> {
                            for (String[] en : borrowedEntries) {
                                if (!willOverwritePaths.contains(en[0])) continue;
                                en[3] = ops.getModificationTime(en[0]);
                            }
                        });
                    } catch (Exception mtEx) {
                        logCallback.accept("INFO  [备份] 原始时间戳记录失败（回滚将不恢复时间戳）："
                                + mtEx.getMessage());
                    }
                    updatedFiles.addAll(borrowedEntries);
                } else {
                    // NEW_DIR：目录名递增 _v2/_v3 并行保留；OVERWRITE（默认）：复用当天目录覆盖
                    final String[] dirName = {dateOperator};
                    if (strategy == com.flux.deploy.plugin.model.BackupConflictStrategy.NEW_DIR) {
                        runFreshFtpSessionWithRetry(host, port, user, pass, (s, ops) ->
                                dirName[0] = resolveBackupDirName(ops, backupParent, dateOperator,
                                        pluginConfig));
                    }
                    backupDir = backupParent + dirName[0] + "/";
                    long bkStart = System.currentTimeMillis();
                    int totalFiles = 0;
                    long totalBackupBytes = 0;
                    for (java.util.Map<String, Long> listing : contentListings.values()) {
                        totalFiles += listing.size();
                        for (Long sz : listing.values()) {
                            if (sz != null && sz > 0) totalBackupBytes += sz;
                        }
                    }
                    logCallback.accept("INFO  [备份] 开始：镜像整个工程包（" + totalFiles
                            + " 个文件，共 " + formatSize(totalBackupBytes) + "）到 " + backupDir);
                    com.flux.deploy.ftp.TransferProgress.BatchProgress bkProgress =
                            com.flux.deploy.ftp.TransferProgress.batch("INFO  [备份]",
                                    totalBackupBytes, totalFiles, logCallback);
                    int backedUpFiles = mirrorContentDirsToBackup(contentDirRels, contentListings,
                            backupDir, updatedFiles, willOverwritePaths, bkProgress,
                            host, port, user, pass, logCallback);
                    logCallback.accept("INFO  [备份] 完成，" + backedUpFiles + " 个文件（"
                            + formatSize(totalBackupBytes) + "），耗时 "
                            + formatElapsed(System.currentTimeMillis() - bkStart));
                    // 镜像完整性校验：递归列出备份目录，逐文件核对存在且字节数一致。
                    // 备份是回滚的唯一来源，半截备份比没有备份更危险（回滚会用它覆盖线上文件）
                    final String freshDir = backupDir;
                    final List<String> mirrorProblems = new ArrayList<>();
                    runFreshFtpSessionWithRetry(host, port, user, pass, (s, ops) ->
                            mirrorProblems.addAll(
                                    verifyBackupCoverage(contentListings, freshDir, true, ops)));
                    if (!mirrorProblems.isEmpty()) {
                        // 不删备份目录里的东西（错误路径下不做任何远端删除），只把风险说清楚
                        logCallback.accept("WARN  [备份] 备份目录 " + backupDir
                                + " 中上述文件不可信，下次更新请勿对它选择「沿用已有备份」");
                        abortOnBackupProblems(mirrorProblems, "本次备份镜像", logCallback);
                    }
                    logCallback.accept("INFO  [备份] 镜像完整性校验通过（" + backedUpFiles + " 个文件逐一核对）");
                }
            } else {
                logCallback.accept("WARN  [备份] 跳过备份：用户选择不备份，"
                        + "被覆盖的文件将无法自动回滚");
            }

            // ── Phase 2: 上传并发布（逐模块串行；新内容先落影子目录，校验齐备后 rename 切换） ──
            long upStart = System.currentTimeMillis();
            int mi = 0;
            // 文件级进度：几百个小文件逐个传时，单文件的字节进度看不出整体进展，
            // 按"已传 N/M 个文件 + 总字节"每 5 秒汇报一次才知道整体走到哪了
            int totalUploadFiles = 0;
            long totalUploadBytes = 0;
            for (List<Path> fs : filesByTarget.values()) {
                totalUploadFiles += fs.size();
                for (Path f : fs) {
                    totalUploadBytes += Files.size(f);
                }
            }
            logCallback.accept("INFO  [上传] 开始：" + totalUploadFiles + " 个文件，共 "
                    + formatSize(totalUploadBytes));
            com.flux.deploy.ftp.TransferProgress.BatchProgress upProgress =
                    com.flux.deploy.ftp.TransferProgress.batch("INFO  [上传]",
                            totalUploadBytes, totalUploadFiles, logCallback);
            for (java.util.Map.Entry<FtpTargetSelection, List<Path>> entry : filesByTarget.entrySet()) {
                mi++;
                FtpTargetSelection t = entry.getKey();
                List<Path> files = entry.getValue();
                Path localDir = projectRoot.resolve(VueProjectResolver.DIST_UMD_DIR)
                        .resolve(t.getTargetName());
                String remoteModuleDir = t.getRemoteDir() + t.getRelativePath() + "/";
                java.util.Map<String, Long> remoteExisting = remoteFilesByTarget
                        .getOrDefault(t.getRelativePath(), java.util.Map.of());
                logCallback.accept("INFO  [上传] " + mi + "/" + dirTargets.size() + " 模块 "
                        + t.getTargetName() + "：" + files.size() + " 个文件 → " + remoteModuleDir
                        + (t.isCreateNew() ? "（新建投放）" : ""));
                // 远端有、本地 dist 没有的历史文件：不删除（manifest 不引用即无害），
                // 切换完成后从旧目录搬回来。有排除项时该统计会把被排除文件误算成
                // "历史残留"，不适用
                boolean hasExclusions = excludedByModule != null
                        && excludedByModule.get(t.getRelativePath()) != null
                        && !excludedByModule.get(t.getRelativePath()).isEmpty();
                if (!hasExclusions) {
                    java.util.Set<String> localRels = new java.util.HashSet<>();
                    for (Path f : files) {
                        localRels.add(localDir.relativize(f).toString().replace('\\', '/')
                                .toLowerCase(java.util.Locale.ROOT));
                    }
                    int staleCount = 0;
                    for (String rel : remoteExisting.keySet()) {
                        if (!localRels.contains(rel)) staleCount++;
                    }
                    if (staleCount > 0) {
                        logCallback.accept("INFO  [上传] 模块 " + t.getTargetName() + " 远端有 "
                                + staleCount + " 个本地产物中不存在的历史文件，切换后原样保留");
                    }
                }
                List<String> excludedList2 = excludedByModule == null ? null
                        : excludedByModule.get(t.getRelativePath());
                java.util.Set<String> excludedRels = excludedList2 == null
                        ? java.util.Set.of() : new java.util.LinkedHashSet<>(excludedList2);
                switchedModules.add(publishModuleViaShadowDir(files, localDir, remoteModuleDir,
                        remoteExisting, excludedRels, updatedFiles,
                        upProgress, host, port, user, pass, logCallback));
                // 传包完成时刻（版本记录的传包时间按模块记）
                java.util.concurrent.ConcurrentMap<String, java.time.LocalDateTime> finishTimes =
                        currentUploadFinishTimes;
                if (finishTimes != null) {
                    finishTimes.put(t.getRemoteDir() + t.getRelativePath(),
                            java.time.LocalDateTime.now());
                }
            }
            logCallback.accept("INFO  [上传] 完成并切换，" + totalUploadFiles + " 个文件（"
                    + formatSize(totalUploadBytes) + "），耗时 "
                    + formatElapsed(System.currentTimeMillis() - upStart));

            // ── Phase 3: 校验（逐文件远端字节数比对；按模块目录递归 LIST 一次取全量清单，
            //    不再逐文件 SIZE——数百个小文件逐个往返在慢网络下既慢又容易中途断连） ──
            logCallback.accept("INFO  [校验] 开始：逐文件核对远端字节数");
            for (java.util.Map.Entry<FtpTargetSelection, List<Path>> entry : filesByTarget.entrySet()) {
                checkVueDirCancelled();
                FtpTargetSelection t = entry.getKey();
                Path localDir = projectRoot.resolve(VueProjectResolver.DIST_UMD_DIR)
                        .resolve(t.getTargetName());
                String remoteModuleDir = t.getRemoteDir() + t.getRelativePath() + "/";
                java.util.Map<String, Long> remoteNow = new java.util.LinkedHashMap<>();
                runFreshFtpSessionWithRetry(host, port, user, pass, (s, ops) -> {
                    remoteNow.clear();
                    listRemoteFilesRecursive(ops, remoteModuleDir, "", remoteNow, 0);
                });
                java.util.Map<String, Long> remoteLower = new java.util.HashMap<>();
                for (java.util.Map.Entry<String, Long> r : remoteNow.entrySet()) {
                    remoteLower.put(r.getKey().toLowerCase(java.util.Locale.ROOT), r.getValue());
                }
                for (Path file : entry.getValue()) {
                    String rel = localDir.relativize(file).toString().replace('\\', '/');
                    Long remoteSize = remoteLower.get(rel.toLowerCase(java.util.Locale.ROOT));
                    long localSize = Files.size(file);
                    if (remoteSize == null || remoteSize != localSize) {
                        throw new java.io.IOException("校验不一致 " + remoteModuleDir + rel
                                + "：本地 " + localSize + " B，远端 "
                                + (remoteSize == null ? "不存在" : remoteSize + " B"));
                    }
                }
                logCallback.accept("INFO  [校验] 模块 " + t.getTargetName() + " 通过（"
                        + entry.getValue().size() + " 个文件）");
            }

            // ── Phase 4: 版本记录（工程包级：{content}_update_notes.txt 放工程包目录旁，
            //    一次更新追加一条记录，不按模块拆散） ──
            List<FtpTargetSelection> noteTargets = buildVueNoteTargets(pluginConfig, dirTargets);
            java.util.concurrent.ConcurrentMap<String, java.time.LocalDateTime> noteFinishTimes =
                    currentUploadFinishTimes;
            if (noteFinishTimes != null) {
                java.time.LocalDateTime doneAt = java.time.LocalDateTime.now();
                for (FtpTargetSelection nt : noteTargets) {
                    noteFinishTimes.put(nt.getRemoteDir() + nt.getRelativePath(), doneAt);
                }
            }
            boolean noteWritten = false;
            if (pluginConfig.isUpdateNote()) {
                // 逐个工程包写，失败时知道哪些已写入需要撤销。
                // 版本记录写不进去 = 本次更新失败：不保留"业务文件已更新但没有记录"的中间状态，
                // 撤销已写入的记录后抛出，由下面的 catch 把业务文件一并回滚
                List<FtpTargetSelection> notesDone = new ArrayList<>();
                try {
                    for (FtpTargetSelection nt : noteTargets) {
                        updateNoteForAll(pluginConfig, List.of(nt), null, deployStartMs,
                                currentUploadFinishTimes, host, port, user, pass, logCallback);
                        notesDone.add(nt);
                    }
                    noteWritten = true;
                } catch (Exception noteEx) {
                    logCallback.accept("ERROR [说明] 版本记录写入失败，本次更新按失败处理："
                            + noteEx.getMessage());
                    if (!notesDone.isEmpty()) {
                        // rollbackNotes 按 lastManualNoteSelections 找回用户手动指定的记录文件，
                        // 进行中回滚要先把本次的选择喂给它，否则手动指定的文件名扫描不回来
                        lastManualNoteSelections = new java.util.HashMap<>(manualNoteSelections);
                        logCallback.accept("INFO  [回滚] 撤销已写入的 " + notesDone.size()
                                + " 份版本记录");
                        rollbackNotes(notesDone, host, port, user, pass, logCallback);
                    }
                    throw noteEx;
                }
            }

            // ── 成功收尾 ──
            // 历史文件回位与旧目录清理放在这里：此前任一阶段失败都要靠旧目录做完整快照回滚
            carryOverLegacyFiles(switchedModules, host, port, user, pass, logCallback);
            logCallback.accept("\n╔══════════════════════════════╗");
            logCallback.accept("║   Vue 更新完成               ║");
            logCallback.accept("╚══════════════════════════════╝");
            for (FtpTargetSelection t : dirTargets) {
                logCallback.accept(com.flux.deploy.plugin.toolwindow.LogSectionPanel.RAW_LINE_MARK
                        + t.getRemoteDir() + t.getRelativePath()
                        + (t.isCreateNew() ? "（新建投放）" : ""));
            }
            if (backupDir != null) {
                logCallback.accept(com.flux.deploy.plugin.toolwindow.LogSectionPanel.RAW_LINE_MARK
                        + "备份目录：" + backupDir);
            }
            if (backupDir != null || hasCreateNewEntry(updatedFiles)) {
                lastBackupDir = backupDir;
                // 事后手动回滚的清单只登记「本次真实触碰过」的文件，与进行中回滚同口径：
                // 备份阶段按团队约定镜像了整个工程包，整包都在 updatedFiles 里，但"备份过"
                // 不等于"改过"——整包写回会把本次没动的模块也回退到部署前，抹掉他人期间
                // 对同包其他模块的更新，且要传数千个无谓文件
                synchronized (updatedFiles) {
                    List<String[]> touched = new ArrayList<>();
                    int untouched = 0;
                    for (String[] pair : updatedFiles) {
                        boolean isNew = isCreateNewEntry(pair);
                        if (isNew || (backupDir != null && wasUploadedThisRun(pair[0]))) {
                            touched.add(pair);
                        } else if (!isNew) {
                            untouched++;
                        }
                    }
                    lastUpdatedPackages = touched;
                    if (untouched > 0) {
                        logCallback.accept("INFO  [回滚] 可回滚本次变更的 " + touched.size()
                                + " 个文件（备份含整包 " + (touched.size() + untouched)
                                + " 个，其余不受影响）");
                    }
                }
                // 版本记录回滚要按工程包级目标定位 note 文件；写入失败时不得标记
                // （否则手动回滚会削掉上一次部署追加的记录）
                lastAllTargets = new ArrayList<>(noteTargets);
                lastUpdatedNote = noteWritten;
                lastBackupBorrowed = backupBorrowed;
                lastManualNoteSelections = new java.util.HashMap<>(manualNoteSelections);
            }
            DeployResult ok = new DeployResult();
            ok.markSuccess();
            onComplete.accept(ok);
        } catch (Exception e) {
            boolean userStop = currentCancelMode != CancelMode.NONE;
            logCallback.accept(userStop
                    ? "WARN  [部署] 用户请求停止，开始回滚已变更的文件"
                    : "ERROR [部署] Vue 目录直更失败：" + e.getMessage());
            // 进行中回滚只恢复本次真实触碰过（上传登记过）的文件：
            // 回滚清单在备份阶段就登记了整包（作为回滚源），但"已登记"不等于"已变更"——
            // 全量写回会把未触碰文件也覆盖一遍（USE_EXISTING 下更是用旧备份回归它们）
            // 已切换的模块优先整目录换回（两次 rename，比逐文件写回快几个数量级）；
            // 换回成功的模块，其文件不必再走备份恢复
            java.util.Set<String> restoredDirs =
                    restoreSwitchedModules(switchedModules, host, port, user, pass, logCallback);
            List<String[]> touched = new ArrayList<>();
            int untouched = 0;
            synchronized (updatedFiles) {
                for (String[] pair : updatedFiles) {
                    if (isCreateNewEntry(pair) || wasUploadedThisRun(pair[0])) {
                        boolean coveredByDirRestore = false;
                        for (String dir : restoredDirs) {
                            if (pair[0] != null && pair[0].startsWith(dir)) {
                                coveredByDirRestore = true;
                                break;
                            }
                        }
                        if (!coveredByDirRestore) {
                            touched.add(pair);
                        }
                    } else {
                        untouched++;
                    }
                }
            }
            if (!touched.isEmpty()) {
                if (untouched > 0) {
                    logCallback.accept("INFO  [回滚] 本次实际触碰 " + touched.size()
                            + " 个文件，其余 " + untouched + " 个未变更、无需恢复");
                }
                rollbackAll(backupDir, touched, host, port, user, pass, logCallback, backupBorrowed);
            } else {
                logCallback.accept("INFO  [回滚] 远端尚未发生任何变更，无需回滚");
            }
            // 传到一半中断的 .__UPLOADING__ 临时文件不属于业务内容，清掉避免堆在模块目录里
            List<String> touchedPaths = new ArrayList<>();
            for (String[] pair : touched) {
                touchedPaths.add(pair[0]);
            }
            cleanupUploadTemps(touchedPaths, host, port, user, pass, logCallback);
            logFailureSummary(logCallback, userStop
                    ? "用户停止，已回滚本次变更"
                    : "Vue 目录直更失败，已回滚本次变更：" + e.getMessage());
            onComplete.accept(null);
        } finally {
            currentVueDirDeploy = false;
        }
    }

    /**
     * 备份单个远端文件到指定备份路径：下载核对字节数 → 原子发布到备份路径。
     *
     * @param ops        FTP 操作
     * @param remoteFile 远端源文件
     * @param backupPath 备份目标路径
     * @param temp       复用的本地临时文件
     * @throws java.io.IOException 下载不完整或备份失败
     * @author xumanyi
     * @date 2026-09-23
     */
    private static void backupOneRemoteFile(FtpOperations ops, String remoteFile,
                                            String backupPath, Path temp)
            throws java.io.IOException {
        long expected = ops.getFileSize(remoteFile);
        if (expected < 0) {
            throw new java.io.IOException("远端文件不存在，无法备份: " + remoteFile);
        }
        long got = ops.download(remoteFile, temp);
        if (got != expected) {
            throw new java.io.IOException("备份下载不完整: " + remoteFile
                    + "，远端 " + expected + " B，实得 " + got + " B");
        }
        ops.uploadAtomic(temp, backupPath);
    }

    /** 影子目录后缀（与 core 同一常量，避免两处定义漂移） */
    private static final String SHADOW_NEW_SUFFIX = FtpOperations.SHADOW_NEW_SUFFIX;
    /** 旧版本目录后缀（与 core 同一常量，避免两处定义漂移） */
    private static final String SHADOW_OLD_SUFFIX = FtpOperations.SHADOW_OLD_SUFFIX;

    /**
     * 一个模块的目录切换记录：失败时用 {@code oldDir} 快速换回，成功后清理 {@code oldDir}。
     *
     * @author xumanyi
     * @date 2026-09-23
     */
    private static final class VueModuleSwitch {
        /** 正式模块目录（以 / 结尾） */
        final String moduleDir;
        /** 旧版本目录（以 / 结尾）；新建投放没有旧版本时为 null */
        final String oldDir;
        /** 该模块本次写入的最终路径（切换后才登记，用于回滚） */
        final List<String> writtenPaths;

        VueModuleSwitch(String moduleDir, String oldDir, List<String> writtenPaths) {
            this.moduleDir = moduleDir;
            this.oldDir = oldDir;
            this.writtenPaths = writtenPaths;
        }
    }

    /**
     * 判断远端目录是否存在（{@link FtpOperations#exists} 只认文件，目录要看父目录列表）
     *
     * @param ops     FTP 操作
     * @param dirPath 目录路径（可带尾斜杠）
     * @return true 表示该目录存在
     * @throws java.io.IOException 列目录失败
     * @author xumanyi
     * @date 2026-09-23
     */
    private static boolean remoteDirExists(FtpOperations ops, String dirPath)
            throws java.io.IOException {
        String path = dirPath.endsWith("/") ? dirPath.substring(0, dirPath.length() - 1) : dirPath;
        int slash = path.lastIndexOf('/');
        if (slash <= 0) return false;
        String parent = path.substring(0, slash + 1);
        String name = path.substring(slash + 1);
        for (FTPFile f : ops.listFiles(stripTrailingSlashForList(parent))) {
            if (f != null && f.isDirectory() && name.equals(f.getName())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 递归删除一棵影子目录树。
     *
     * <p><b>安全约束</b>：只接受以 {@link #SHADOW_NEW_SUFFIX} / {@link #SHADOW_OLD_SUFFIX}
     * 结尾的目录——这两类目录只由本插件创建，删错业务目录的代价无法承受，
     * 传入其它路径直接抛异常而不是"尽力删一下"。</p>
     *
     * @param session 当前会话（取 FTPClient 删目录）
     * @param ops     FTP 操作
     * @param dir     待删目录（以 / 结尾）
     * @param depth   当前递归深度（顶层传 0，上限 8）
     * @throws java.io.IOException 路径不合法或删除失败
     * @author xumanyi
     * @date 2026-09-23
     */
    private static void deleteRemoteTree(FtpSession session, FtpOperations ops, String dir, int depth)
            throws java.io.IOException {
        String path = dir.endsWith("/") ? dir.substring(0, dir.length() - 1) : dir;
        // 只在入口校验：子目录（dialogs/ 之类）当然不会以影子后缀结尾，
        // 在递归里一起校验会把正常的子目录清理误判成越权删除
        if (depth == 0 && !path.endsWith(SHADOW_NEW_SUFFIX) && !path.endsWith(SHADOW_OLD_SUFFIX)) {
            throw new java.io.IOException("[安全] 递归删除只允许作用于影子目录，拒绝: " + dir);
        }
        if (depth > 8) {
            throw new java.io.IOException("影子目录层级超过 8 层，中止删除: " + dir);
        }
        for (FTPFile f : ops.listFiles(path)) {
            if (f == null) continue;
            String name = f.getName();
            if (".".equals(name) || "..".equals(name)) continue;
            if (f.isDirectory()) {
                deleteRemoteTree(session, ops, path + "/" + name + "/", depth + 1);
            } else {
                ops.delete(path + "/" + name);
            }
        }
        if (!session.getClient().removeDirectory(path)) {
            throw new java.io.IOException("删除影子目录失败: " + path
                    + "（响应: " + session.getClient().getReplyString().trim() + "）");
        }
    }

    /**
     * 开工前清理上次中断遗留的影子目录，并自愈"切换切到一半"的现场。
     *
     * <p>三种残留：</p>
     * <ul>
     *   <li>只有 {@code .__NEW__}：上次在传新内容时中断，正式目录没动过 → 整树删除；</li>
     *   <li>有 {@code .__OLD__} 且正式目录不存在：上次切换切到一半（旧的已改名、新的没就位）
     *       → 把旧目录改回正式名，恢复到更新前的状态；</li>
     *   <li>有 {@code .__OLD__} 且正式目录也在：上次切换完成但没来得及清理 → 整树删除。</li>
     * </ul>
     *
     * @param session     当前会话
     * @param ops         FTP 操作
     * @param moduleDir   正式模块目录（以 / 结尾）
     * @param logCallback 日志回调
     * @throws java.io.IOException 清理 / 自愈失败
     * @author xumanyi
     * @date 2026-09-23
     */
    private static void cleanupShadowLeftovers(FtpSession session, FtpOperations ops,
                                               String moduleDir, Consumer<String> logCallback)
            throws java.io.IOException {
        String base = moduleDir.endsWith("/")
                ? moduleDir.substring(0, moduleDir.length() - 1) : moduleDir;
        String newDir = base + SHADOW_NEW_SUFFIX + "/";
        String oldDir = base + SHADOW_OLD_SUFFIX + "/";
        if (remoteDirExists(ops, newDir)) {
            logCallback.accept("INFO  [上传] 清理上次中断遗留的新版本目录：" + newDir);
            deleteRemoteTree(session, ops, newDir, 0);
        }
        if (remoteDirExists(ops, oldDir)) {
            if (remoteDirExists(ops, moduleDir)) {
                logCallback.accept("INFO  [上传] 清理上次更新遗留的旧版本目录：" + oldDir);
                deleteRemoteTree(session, ops, oldDir, 0);
            } else {
                logCallback.accept("WARN  [上传] 检测到上次切换未完成（正式目录缺失），"
                        + "已将旧版本目录改回：" + oldDir + " → " + moduleDir);
                ops.rename(stripTrailingSlashForList(oldDir), stripTrailingSlashForList(moduleDir));
            }
        }
    }

    /** 上传中的临时文件后缀（与 core 的原子发布同一常量，避免两处定义漂移） */
    private static final String UPLOADING_SUFFIX = FtpOperations.UPLOADING_SUFFIX;

    /**
     * 取交互式重试提示器：自动退避预算耗尽后弹窗让用户选「继续重试 / 结束」。
     *
     * <p>非 IDE 上下文（activeProject 为 null）退化为直接结束——结束即失败并回滚，
     * 不存在"跳过这个文件继续"之类的中间状态。</p>
     *
     * @return 重试提示器
     * @author xumanyi
     * @date 2026-09-23
     */
    private static RetryUserPrompter retryPrompter() {
        Project p = activeProject;
        return p != null ? new RetryPromptDialog(p) : RetryUserPrompter.abortAll();
    }

    /**
     * 执行一个 FTP 控制命令，失败则重连一次再执行一次。
     *
     * <p>用于 exists / delete / mkdirs / rename 这类<b>幂等的单条控制命令</b>：
     * 长会话在文件之间空闲时可能已被服务端掐断，第一条命令才发现。数据传输不走这里
     * （传输有自己的断点续传重试）。</p>
     *
     * @param session 当前会话（重连在其上进行）
     * @param action  待执行的命令
     * @throws java.io.IOException 重连后仍失败
     * @author xumanyi
     * @date 2026-09-23
     */
    private static void withReconnect(FtpSession session, FtpCommand action)
            throws java.io.IOException {
        try {
            action.run();
        } catch (java.io.IOException first) {
            session.reconnect();
            action.run();
        }
    }

    /**
     * {@link #withReconnect} 使用的命令接口。
     *
     * @author xumanyi
     * @date 2026-09-23
     */
    @FunctionalInterface
    private interface FtpCommand {
        void run() throws java.io.IOException;
    }

    /**
     * 安全上传单个文件：临时名 → 断点续传 → 字节数校验 → rename 发布为最终名。
     *
     * <p>三重保障：</p>
     * <ol>
     *   <li><b>不原地覆盖</b>：先传到 {@code <文件名>.__UPLOADING__}，传完校验通过才 rename。
     *       传输中断时线上文件始终是完整的旧版本，不会出现"半新半旧"的可见窗口；</li>
     *   <li><b>断点续传 + 断线重连</b>：{@link FtpOperations#uploadResumable} 按
     *       {@link RetryPolicy#networkDefault()}（3 次，2s/5s/10s 退避）重连续传，
     *       预算耗尽由 {@code prompter} 决定继续还是结束；结束即失败，不做降级。
     *       前后的控制命令各自带一次重连重试（{@link #withReconnect}）；</li>
     *   <li><b>陈旧临时文件先删</b>：上次中断遗留的同名临时文件属于别的版本，
     *       续传会拼出混合体，必须先删除再传。</li>
     * </ol>
     *
     * @param session     当前会话（重连用）
     * @param ops         FTP 操作
     * @param local       本地文件
     * @param remoteFinal 远端最终路径
     * @param prompter    重试预算耗尽时的决策者
     * @param log         日志回调
     * @throws java.io.IOException 上传 / 校验 / 发布失败
     * @author xumanyi
     * @date 2026-09-23
     */
    private static void uploadFileSafely(FtpSession session, FtpOperations ops, Path local,
                                         String remoteFinal, RetryUserPrompter prompter,
                                         Consumer<String> log) throws java.io.IOException {
        String tempPath = remoteFinal + UPLOADING_SUFFIX;
        long localSize = Files.size(local);
        withReconnect(session, () -> {
            if (ops.exists(tempPath)) {
                log.accept("INFO  [上传] 清理上次中断遗留的临时文件：" + tempPath);
                ops.delete(tempPath);
            }
        });
        ops.uploadResumable(local, tempPath, RetryPolicy.networkDefault(), prompter,
                msg -> log.accept(
                        com.flux.deploy.plugin.toolwindow.LogSectionPanel.RAW_LINE_MARK + msg));
        // 查大小与发布分开做：大小不一致是"传坏了"的结论，不该被当成网络错误再重连重试一遍
        long[] uploaded = new long[1];
        withReconnect(session, () -> uploaded[0] = ops.getFileSize(tempPath));
        if (uploaded[0] != localSize) {
            try { ops.delete(tempPath); } catch (java.io.IOException ignored) { }
            throw new java.io.IOException("上传字节数不一致: " + remoteFinal
                    + "，本地 " + localSize + " B，远端 " + uploaded[0] + " B");
        }
        withReconnect(session, () -> publishTempToFinal(ops, tempPath, remoteFinal, log));
    }

    /** 走断点续传的文件大小门槛：小于此值重传一遍比续传的两次问询还便宜 */
    private static final long RESUMABLE_MIN_BYTES = 1024L * 1024;

    /**
     * 上传一个文件到影子目录。
     *
     * <p>影子目录整体就是"临时"的，切换前还会整目录校验一遍，所以这里不再给每个文件
     * 套一层 {@code .__UPLOADING__} 临时名，也不逐文件查字节数——那样每个文件要 7~9 次
     * FTP 往返，几百个小文件在正常网络下就要十几分钟。</p>
     *
     * <p>小文件（&lt; {@link #RESUMABLE_MIN_BYTES}）失败后按 2s/5s/10s 退避重连整文件重传
     * （几十 KB 重传的代价低于续传要多问的两次）；大文件走断点续传，从断点继续。</p>
     *
     * @param session  当前会话
     * @param ops      FTP 操作
     * @param local    本地文件
     * @param remote   影子目录内的目标路径
     * @param prompter 大文件重试预算耗尽时的决策者
     * @param log      日志回调
     * @throws java.io.IOException 重试后仍失败
     * @author xumanyi
     * @date 2026-09-23
     */
    private static void uploadIntoShadowDir(FtpSession session, FtpOperations ops, Path local,
                                            String remote, RetryUserPrompter prompter,
                                            Consumer<String> log) throws java.io.IOException {
        if (Files.size(local) >= RESUMABLE_MIN_BYTES) {
            ops.uploadResumable(local, remote, RetryPolicy.networkDefault(), prompter,
                    msg -> log.accept(
                            com.flux.deploy.plugin.toolwindow.LogSectionPanel.RAW_LINE_MARK + msg));
            return;
        }
        RetryPolicy policy = RetryPolicy.networkDefault();
        java.io.IOException last = null;
        for (int attempt = 1; attempt <= policy.maxAttempts(); attempt++) {
            try {
                ops.upload(local, remote);
                return;
            } catch (java.io.IOException e) {
                last = e;
                if (attempt >= policy.maxAttempts()) break;
                long waitSec = policy.backoffFor(attempt).toSeconds();
                log.accept("WARN  [上传] " + local.getFileName() + " 传输中断，" + waitSec
                        + "s 后重连重试（剩 " + (policy.maxAttempts() - attempt) + " 次）："
                        + e.getMessage());
                sleepInterruptibly(policy.backoffFor(attempt));
                session.reconnect();
            }
        }
        throw last;
    }

    /**
     * 可中断的退避等待：被中断时恢复中断标志并抛出，让上层按失败处理
     *
     * @param duration 等待时长
     * @throws java.io.IOException 等待被中断
     * @author xumanyi
     * @date 2026-09-23
     */
    private static void sleepInterruptibly(java.time.Duration duration) throws java.io.IOException {
        try {
            Thread.sleep(Math.max(0, duration.toMillis()));
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new java.io.IOException("等待重试被中断", ie);
        }
    }

    /**
     * 切换前整目录校验：递归列一次影子目录，逐文件核对字节数。
     *
     * <p>把"每文件一次 SIZE"换成"每模块一次递归 LIST"，既快得多，又把校验点提前到切换之前——
     * 有任何一个文件不对就不切换，线上一个字节都不受影响。</p>
     *
     * @param ops      FTP 操作
     * @param shadowDir 影子目录（以 / 结尾）
     * @param expected 期望内容（影子目录内相对路径 → 字节数）
     * @throws java.io.IOException 校验不通过或列目录失败
     * @author xumanyi
     * @date 2026-09-23
     */
    private static void verifyShadowDir(FtpOperations ops, String shadowDir,
                                        java.util.Map<String, Long> expected)
            throws java.io.IOException {
        java.util.Map<String, Long> actual = new java.util.LinkedHashMap<>();
        listRemoteFilesRecursive(ops, shadowDir, "", actual, 0);
        java.util.Map<String, Long> actualLower = new java.util.HashMap<>();
        for (java.util.Map.Entry<String, Long> e : actual.entrySet()) {
            actualLower.put(e.getKey().toLowerCase(java.util.Locale.ROOT), e.getValue());
        }
        for (java.util.Map.Entry<String, Long> e : expected.entrySet()) {
            Long got = actualLower.get(e.getKey().toLowerCase(java.util.Locale.ROOT));
            if (got == null) {
                throw new java.io.IOException("新版本目录缺少文件: " + shadowDir + e.getKey());
            }
            if (!got.equals(e.getValue())) {
                throw new java.io.IOException("新版本目录文件大小不一致: " + shadowDir + e.getKey()
                        + "，应为 " + e.getValue() + " B，实际 " + got + " B");
            }
        }
    }

    /**
     * 把校验通过的临时文件发布为最终文件（rename）。
     *
     * <p>rename 覆盖已存在文件的行为各家 FTP 服务端不一致：直接 rename 失败且目标确实存在时，
     * 删除旧文件后再 rename 一次。旧文件此刻已在本次备份里，两条命令之间的空窗即使失败，
     * 回滚也能从备份恢复；目标不存在的失败属真失败，清掉临时文件后原样抛出。</p>
     *
     * @param ops       FTP 操作
     * @param tempPath  临时文件路径
     * @param finalPath 最终路径
     * @param log       日志回调
     * @throws java.io.IOException 发布失败
     * @author xumanyi
     * @date 2026-09-23
     */
    private static void publishTempToFinal(FtpOperations ops, String tempPath, String finalPath,
                                           Consumer<String> log) throws java.io.IOException {
        try {
            ops.rename(tempPath, finalPath);
            return;
        } catch (java.io.IOException renameErr) {
            if (!ops.exists(finalPath)) {
                try { ops.delete(tempPath); } catch (java.io.IOException ignored) { }
                throw renameErr;
            }
        }
        // 服务端不允许 RNTO 覆盖已存在文件：删旧再改名。属实现细节，不往日志里写，
        // 失败时的异常信息里已经说清楚发生了什么
        ops.delete(finalPath);
        try {
            ops.rename(tempPath, finalPath);
        } catch (java.io.IOException e2) {
            throw new java.io.IOException("发布失败（旧文件已删除，需从备份回滚恢复）: " + finalPath
                    + " - " + e2.getMessage(), e2);
        }
    }

    /**
     * 清理本次上传可能遗留的 {@code .__UPLOADING__} 临时文件（失败路径的尽力而为收尾）。
     *
     * <p>只删本次触碰过的路径对应的临时文件，不扫描、不波及任何业务文件；
     * 删不掉也只记一行日志——下次更新同一文件时会先删陈旧临时文件。</p>
     *
     * @param finalPaths  本次触碰过的最终路径
     * @param host        FTP 主机
     * @param port        FTP 端口
     * @param user        FTP 用户名
     * @param pass        FTP 密码
     * @param logCallback 日志回调
     * @author xumanyi
     * @date 2026-09-23
     */
    private static void cleanupUploadTemps(java.util.Collection<String> finalPaths,
                                           String host, int port, String user, String pass,
                                           Consumer<String> logCallback) {
        if (finalPaths == null || finalPaths.isEmpty()) return;
        try {
            runFreshFtpSession(host, port, user, pass, (s, ops) -> {
                int removed = 0;
                for (String p : finalPaths) {
                    String temp = p + UPLOADING_SUFFIX;
                    try {
                        if (ops.exists(temp)) {
                            ops.delete(temp);
                            removed++;
                        }
                    } catch (Exception ignored) {
                        // 单个临时文件删不掉不影响收尾，下次更新该文件时会再清一次
                    }
                }
                if (removed > 0) {
                    logCallback.accept("INFO  [收尾] 已清理 " + removed + " 个上传临时文件");
                }
            });
        } catch (Exception e) {
            logCallback.accept("INFO  [收尾] 上传临时文件清理未完成（不影响远端一致性）："
                    + e.getMessage());
        }
    }

    /**
     * 上传前的并发变更闸门：重新列一次远端模块目录，与备份阶段抓取的现状比对。
     *
     * <p>备份之后、上传之前若他人动过这个模块（抢先创建了我们视为"新增"的文件、
     * 或改动 / 删除了已备份的文件），继续覆盖会把他人的内容无备份地抹掉。
     * 只比对"本次将要写入的那些文件"，命中即中止（此时该模块远端零变更），
     * 提示刷新目标列表后重试——不做任何"跳过该文件继续"的降级。</p>
     *
     * <p>列目录失败（网络）原样抛出，由上层按失败处理，绝不当成"目录是空的"。</p>
     *
     * @param remoteModuleDir 远端模块目录（以 / 结尾）
     * @param expected        备份阶段抓取的该模块现状（小写相对路径 → 字节数）
     * @param willUpload      本次将写入的文件（小写相对路径）
     * @param ops             FTP 操作
     * @throws java.io.IOException 检出并发变更，或 FTP 失败
     * @author xumanyi
     * @date 2026-09-23
     */
    private static void guardModuleUnchanged(String remoteModuleDir,
                                             java.util.Map<String, Long> expected,
                                             java.util.Set<String> willUpload,
                                             FtpOperations ops) throws java.io.IOException {
        // 新建投放时模块目录尚不存在：listFiles 返回空清单，now 为空，下面的比对自然通过
        java.util.Map<String, Long> now = new java.util.LinkedHashMap<>();
        listRemoteFilesRecursive(ops, remoteModuleDir, "", now, 0);
        java.util.Map<String, Long> nowLower = new java.util.HashMap<>();
        for (java.util.Map.Entry<String, Long> e : now.entrySet()) {
            nowLower.put(e.getKey().toLowerCase(java.util.Locale.ROOT), e.getValue());
        }
        for (String rel : willUpload) {
            Long before = expected.get(rel);
            Long current = nowLower.get(rel);
            if (before == null && current != null) {
                throw new java.io.IOException("并发变更：" + remoteModuleDir + rel
                        + " 在本次备份之后被他人创建，覆盖会抹掉对方内容且无备份可恢复"
                        + "，已中止（请刷新目标列表后重试）");
            }
            if (before != null && current == null) {
                throw new java.io.IOException("并发变更：" + remoteModuleDir + rel
                        + " 在本次备份之后被他人删除，已中止（请刷新目标列表后重试）");
            }
            if (before != null && !before.equals(current)) {
                throw new java.io.IOException("并发变更：" + remoteModuleDir + rel
                        + " 在本次备份之后被他人修改（备份时 " + before + " B，现在 " + current
                        + " B），覆盖会抹掉对方改动，已中止（请刷新目标列表后重试）");
            }
        }
    }

    /**
     * 用「影子目录」方式发布一个模块：新内容先整体落在旁边的临时目录，校验齐备后两次 rename 切换。
     *
     * <p>为什么不逐文件覆盖：模块是几十到几百个互相引用的 js/css，逐个覆盖时一旦中断，
     * 线上就是"一半新一半旧"，得靠回滚收拾；影子目录把不一致窗口压到两次 rename 的瞬间。</p>
     *
     * <p>步骤：</p>
     * <ol>
     *   <li>清理上次遗留的影子目录并自愈"切到一半"的现场（{@link #cleanupShadowLeftovers}）；</li>
     *   <li>并发闸门：备份之后被他人动过就中止，此时模块目录一个字节没动；</li>
     *   <li>本次产物逐个上传到 {@code <模块目录>.__NEW__/}（原子发布 + 断点续传）；</li>
     *   <li>用户取消勾选的文件把远端旧版本<b>复制</b>进新目录——它们仍被 manifest 引用，
     *       切换瞬间必须在位；</li>
     *   <li>切换：模块目录 → {@code .__OLD__}，{@code .__NEW__} → 模块目录（毫秒级）。
     *       切换中途失败立即把旧目录改回来，绝不留下"模块目录不存在"的现场；</li>
     *   <li>切换成功后才登记回滚信息——没切换的模块等于没动过，失败时无需回滚。</li>
     * </ol>
     *
     * <p>远端那些本地产物里没有的历史文件留在 {@code .__OLD__} 里，等整个部署成功后再搬回来
     * （见 {@link #carryOverLegacyFiles}）：回滚期间旧目录必须是一份完整快照，
     * 提前搬走会让"换回旧目录"这条最快的恢复路径不再可靠。</p>
     *
     * @param files            本次要上传的文件（manifest.json 已排在最后）
     * @param localDir         本地模块产物目录（dist/umd/{模块号}）
     * @param remoteModuleDir  远端模块目录（以 / 结尾）
     * @param remoteExisting   备份阶段抓取的该模块现状（小写相对路径 → 字节数）
     * @param excludedRels     用户取消勾选、需保留远端旧版本的文件（相对路径）
     * @param updatedFiles     回滚清单（本次新增的文件登记 NEW 条目）
     * @param progress         批量进度
     * @param host             FTP 主机
     * @param port             FTP 端口
     * @param user             FTP 用户名
     * @param pass             FTP 密码
     * @param logCallback      日志回调
     * @return 切换记录（失败时用其旧目录快速换回，成功后清理）
     * @throws Exception 上传 / 切换失败、检出并发变更或用户取消
     * @author xumanyi
     * @date 2026-09-23
     */
    private static VueModuleSwitch publishModuleViaShadowDir(
            List<Path> files, Path localDir, String remoteModuleDir,
            java.util.Map<String, Long> remoteExisting, java.util.Set<String> excludedRels,
            List<String[]> updatedFiles,
            com.flux.deploy.ftp.TransferProgress.BatchProgress progress,
            String host, int port, String user, String pass,
            Consumer<String> logCallback) throws Exception {
        RetryUserPrompter prompter = retryPrompter();
        String base = stripTrailingSlashForList(remoteModuleDir);
        final String newDir = base + SHADOW_NEW_SUFFIX + "/";
        final String oldDir = base + SHADOW_OLD_SUFFIX + "/";
        java.util.Set<String> willUpload = new java.util.LinkedHashSet<>();
        for (Path f : files) {
            willUpload.add(localDir.relativize(f).toString().replace('\\', '/')
                    .toLowerCase(java.util.Locale.ROOT));
        }
        final FtpSession session = new FtpSession(host, port);
        session.connect(user, pass);
        final FtpOperations ops = new FtpOperations(session);
        // 单文件进度交给 uploadIntoShadowDir 按大小决定（大文件才报），
        // 默认的逐文件进度会把批量节奏打散
        ops.setProgressLog(null);
        final java.util.Set<String> ensuredDirs = new java.util.HashSet<>();
        boolean renamedToOld = false;
        boolean switched = false;
        try {
            cleanupShadowLeftovers(session, ops, remoteModuleDir, logCallback);
            // 闸门在建任何目录之前：检出并发变更时该模块远端一个字节都没动
            guardModuleUnchanged(remoteModuleDir, remoteExisting, willUpload, ops);
            withReconnect(session, () -> ops.mkdirs(newDir));
            ensuredDirs.add(newDir);

            // 1) 本次产物 → 新目录
            List<String> writtenRels = new ArrayList<>();
            java.util.Map<String, Long> expectedInShadow = new java.util.LinkedHashMap<>();
            for (Path file : files) {
                checkVueDirCancelled();
                String rel = localDir.relativize(file).toString().replace('\\', '/');
                String target = newDir + rel;
                ensureRemoteParentDir(session, ops, ensuredDirs, target);
                uploadIntoShadowDir(session, ops, file, target, prompter, logCallback);
                writtenRels.add(rel);
                expectedInShadow.put(rel, Files.size(file));
                progress.onFileDone(Files.size(file));
            }

            // 2) 取消勾选的文件：把远端旧版本复制进新目录（切换后它们仍是旧内容，但必须在位）
            int carried = 0;
            for (String rel : excludedRels) {
                Long size = remoteExisting.get(rel.toLowerCase(java.util.Locale.ROOT));
                if (size == null) continue; // 远端本来就没有（排除的是本地新增文件）
                checkVueDirCancelled();
                Path temp = Files.createTempFile("vue-keep-", ".tmp");
                try {
                    String from = remoteModuleDir + rel;
                    String to = newDir + rel;
                    ensureRemoteParentDir(session, ops, ensuredDirs, to);
                    long got = ops.download(from, temp);
                    if (got != size) {
                        throw new java.io.IOException("保留文件下载不完整: " + from
                                + "，远端 " + size + " B，实得 " + got + " B");
                    }
                    uploadIntoShadowDir(session, ops, temp, to, prompter, logCallback);
                    expectedInShadow.put(rel, size);
                    carried++;
                } finally {
                    Files.deleteIfExists(temp);
                }
            }
            if (carried > 0) {
                logCallback.accept("INFO  [上传] 取消勾选的 " + carried
                        + " 个文件已按远端旧版本保留在新目录中");
            }

            // 3) 切换前整目录校验：任何一个文件不对就不切换，线上零影响
            checkVueDirCancelled();
            withReconnect(session, () -> verifyShadowDir(ops, newDir, expectedInShadow));

            // 4) 切换（两次 rename，毫秒级）
            checkVueDirCancelled();
            // 只要正式目录存在就先让位（新建投放遇到上次回滚残留的空目录时同理），
            // 否则 rename 会撞上已存在的目标名
            if (remoteDirExists(ops, remoteModuleDir)) {
                withReconnect(session, () ->
                        ops.rename(stripTrailingSlashForList(remoteModuleDir),
                                stripTrailingSlashForList(oldDir)));
                renamedToOld = true;
            }
            withReconnect(session, () ->
                    ops.rename(stripTrailingSlashForList(newDir),
                            stripTrailingSlashForList(remoteModuleDir)));
            switched = true;
            if (renamedToOld) {
                logCallback.accept("INFO  [上传] 已切换为新版本，旧版本暂存在 "
                        + stripTrailingSlashForList(oldDir) + "（更新成功后自动清理）");
            }

            // 5) 切换成功才登记：未切换的模块等于没动过，失败时不该被回滚碰
            List<String> writtenPaths = new ArrayList<>();
            for (String rel : writtenRels) {
                String finalPath = remoteModuleDir + rel;
                writtenPaths.add(finalPath);
                recordSucceededUploadPath(finalPath);
                if (!remoteExisting.containsKey(rel.toLowerCase(java.util.Locale.ROOT))) {
                    updatedFiles.add(new String[]{finalPath, null, CREATE_NEW_ENTRY_MARK});
                }
            }
            return new VueModuleSwitch(remoteModuleDir, renamedToOld ? oldDir : null, writtenPaths);
        } catch (Exception e) {
            if (!switched) {
                // 切到一半：旧目录已改名但新目录没就位 —— 立刻把旧目录改回来
                if (renamedToOld) {
                    try {
                        if (!remoteDirExists(ops, remoteModuleDir) && remoteDirExists(ops, oldDir)) {
                            ops.rename(stripTrailingSlashForList(oldDir),
                                    stripTrailingSlashForList(remoteModuleDir));
                            logCallback.accept("WARN  [上传] 切换未完成，已把旧版本目录改回："
                                    + oldDir + " → " + remoteModuleDir);
                        }
                    } catch (Exception restoreEx) {
                        logCallback.accept("ERROR [上传] 切换未完成且旧版本目录改回失败："
                                + restoreEx.getMessage() + "，请人工把 " + oldDir + " 改名为 "
                                + remoteModuleDir);
                    }
                }
                // 新目录里的内容本次没生效，清掉（失败也只留一堆无人引用的临时文件）
                try {
                    if (remoteDirExists(ops, newDir)) {
                        deleteRemoteTree(session, ops, newDir, 0);
                    }
                } catch (Exception cleanEx) {
                    logCallback.accept("INFO  [上传] 新版本目录清理未完成（不影响线上内容）："
                            + cleanEx.getMessage());
                }
            }
            throw e;
        } finally {
            try { session.close(); } catch (Exception ignored) { }
        }
    }

    /**
     * 确保远端文件的父目录已创建（先建目录再记账，避免重连重试时跳过 mkdirs）
     *
     * @param session     当前会话
     * @param ops         FTP 操作
     * @param ensuredDirs 已创建目录的记账集合
     * @param remoteFile  远端文件路径
     * @throws java.io.IOException 建目录失败
     * @author xumanyi
     * @date 2026-09-23
     */
    private static void ensureRemoteParentDir(FtpSession session, FtpOperations ops,
                                              java.util.Set<String> ensuredDirs, String remoteFile)
            throws java.io.IOException {
        String parent = remoteFile.substring(0, remoteFile.lastIndexOf('/') + 1);
        if (ensuredDirs.contains(parent)) return;
        withReconnect(session, () -> ops.mkdirs(parent));
        ensuredDirs.add(parent);
    }

    /**
     * 部署成功后把旧目录里的历史文件（本地产物中没有的）搬回模块目录，并清理旧目录。
     *
     * <p>用 rename 搬运（服务端元数据操作，不传字节）。放在部署成功之后做，是因为回滚期间
     * 旧目录必须保持完整快照；这些文件不被新 manifest 引用，晚几十秒到位无影响。</p>
     *
     * <p>搬运或清理失败不影响本次更新结果：新版本已经在线上跑，如实告警让人工处理即可。</p>
     *
     * @param switches    本次已完成切换的模块
     * @param host        FTP 主机
     * @param port        FTP 端口
     * @param user        FTP 用户名
     * @param pass        FTP 密码
     * @param logCallback 日志回调
     * @author xumanyi
     * @date 2026-09-23
     */
    private static void carryOverLegacyFiles(List<VueModuleSwitch> switches,
                                             String host, int port, String user, String pass,
                                             Consumer<String> logCallback) {
        List<VueModuleSwitch> withOld = new ArrayList<>();
        for (VueModuleSwitch sw : switches) {
            if (sw.oldDir != null) withOld.add(sw);
        }
        if (withOld.isEmpty()) return;
        List<String> cleaned = new ArrayList<>();
        try {
            runFreshFtpSessionWithRetry(host, port, user, pass, (s, ops) -> {
                for (VueModuleSwitch sw : withOld) {
                    if (!remoteDirExists(ops, sw.oldDir)) continue;
                    java.util.Map<String, Long> oldFiles = new java.util.LinkedHashMap<>();
                    listRemoteFilesRecursive(ops, sw.oldDir, "", oldFiles, 0);
                    java.util.Map<String, Long> nowFiles = new java.util.LinkedHashMap<>();
                    listRemoteFilesRecursive(ops, sw.moduleDir, "", nowFiles, 0);
                    java.util.Set<String> nowLower = new java.util.HashSet<>();
                    for (String rel : nowFiles.keySet()) {
                        nowLower.add(rel.toLowerCase(java.util.Locale.ROOT));
                    }
                    int moved = 0;
                    java.util.Set<String> ensured = new java.util.HashSet<>();
                    for (String rel : oldFiles.keySet()) {
                        if (nowLower.contains(rel.toLowerCase(java.util.Locale.ROOT))) continue;
                        String to = sw.moduleDir + rel;
                        String parent = to.substring(0, to.lastIndexOf('/') + 1);
                        if (ensured.add(parent)) {
                            ops.mkdirs(parent);
                        }
                        ops.rename(sw.oldDir + rel, to);
                        moved++;
                    }
                    String moduleName = stripTrailingSlashForList(sw.moduleDir)
                            .substring(stripTrailingSlashForList(sw.moduleDir)
                                    .lastIndexOf('/') + 1);
                    if (moved > 0) {
                        logCallback.accept("INFO  [收尾] 模块 " + moduleName + "：" + moved
                                + " 个本地产物中没有的历史文件已搬回");
                    }
                    deleteRemoteTree(s, ops, sw.oldDir, 0);
                    cleaned.add(moduleName);
                }
            });
            logCallback.accept("INFO  [收尾] 旧版本目录已清理（" + String.join("、", cleaned) + "）");
        } catch (Exception e) {
            StringBuilder dirs = new StringBuilder();
            for (VueModuleSwitch sw : withOld) {
                if (dirs.length() > 0) dirs.append('、');
                dirs.append(sw.oldDir);
            }
            logCallback.accept("WARN  [收尾] 旧版本目录未清理完，不影响本次更新（新版本已生效）："
                    + e.getMessage());
            logCallback.accept("WARN  [收尾] 可人工删除：" + dirs);
        }
    }

    /**
     * 失败时用旧目录快速换回已切换的模块（比从备份逐文件写回快几个数量级）。
     *
     * <p>换回成功的模块，其本次写入的路径不必再走备份恢复——调用方据返回结果把它们从
     * 逐文件回滚清单里剔除。换回失败的模块保持原样，由备份逐文件恢复兜底。</p>
     *
     * @param switches    本次已完成切换的模块（含旧目录）
     * @param host        FTP 主机
     * @param port        FTP 端口
     * @param user        FTP 用户名
     * @param pass        FTP 密码
     * @param logCallback 日志回调
     * @return 已成功换回的模块目录集合
     * @author xumanyi
     * @date 2026-09-23
     */
    private static java.util.Set<String> restoreSwitchedModules(List<VueModuleSwitch> switches,
                                                                String host, int port,
                                                                String user, String pass,
                                                                Consumer<String> logCallback) {
        java.util.Set<String> restored = new java.util.LinkedHashSet<>();
        for (VueModuleSwitch sw : switches) {
            if (sw.oldDir == null) continue;
            try {
                runFreshFtpSessionWithRetry(host, port, user, pass, (s, ops) -> {
                    if (!remoteDirExists(ops, sw.oldDir)) {
                        throw new java.io.IOException("旧版本目录已不存在: " + sw.oldDir);
                    }
                    // 新内容先挪进 .__NEW__ 名下再整树删除：始终满足"递归删除只作用于影子目录"
                    String base = stripTrailingSlashForList(sw.moduleDir);
                    String discardDir = base + SHADOW_NEW_SUFFIX + "/";
                    if (remoteDirExists(ops, discardDir)) {
                        deleteRemoteTree(s, ops, discardDir, 0);
                    }
                    if (remoteDirExists(ops, sw.moduleDir)) {
                        ops.rename(base, stripTrailingSlashForList(discardDir));
                    }
                    ops.rename(stripTrailingSlashForList(sw.oldDir), base);
                    if (remoteDirExists(ops, discardDir)) {
                        deleteRemoteTree(s, ops, discardDir, 0);
                    }
                });
                restored.add(sw.moduleDir);
                logCallback.accept("INFO  [回滚] 模块目录已整体换回更新前的版本：" + sw.moduleDir);
            } catch (Exception e) {
                logCallback.accept("WARN  [回滚] 模块目录换回失败，改用备份逐文件恢复："
                        + sw.moduleDir + " - " + e.getMessage());
            }
        }
        return restored;
    }

    /**
     * 把各 content 目录的全部远端文件镜像到备份目录（整工程包备份）。
     *
     * <p>零散小文件逐个"下载 + 上传"是主要耗时来源：这里复用同一个 FTP 会话（避免
     * 每文件重连的握手开销），长会话被服务端 421 断开时按 2s/5s/10s 退避换新会话重试当前文件；
     * 进度由 {@code progress} 每 5 秒汇报一次。</p>
     *
     * @param contentDirRels  content 目录相对路径 → remoteDir 前缀
     * @param contentListings content 目录相对路径 → 其下全部文件清单（rel → 字节数）
     * @param backupDir       备份根目录（以 / 结尾）
     * @param updatedFiles    回滚清单收集（[远端文件, 备份文件]）
     * @param willOverwritePaths 本次会被覆盖的远端路径（只对它们记录原始修改时间）
     * @param progress        批量进度（每 5 秒汇报一次已备份文件数与字节数）
     * @param host            FTP 主机
     * @param port            FTP 端口
     * @param user            FTP 用户名
     * @param pass            FTP 密码
     * @param logCallback     日志回调
     * @return 完成镜像的文件数
     * @throws Exception 备份失败（重试后仍失败）或用户取消
     * @author xumanyi
     * @date 2026-08-13
     */
    private static int mirrorContentDirsToBackup(
            java.util.Map<String, String> contentDirRels,
            java.util.Map<String, java.util.Map<String, Long>> contentListings,
            String backupDir, List<String[]> updatedFiles,
            java.util.Set<String> willOverwritePaths,
            com.flux.deploy.ftp.TransferProgress.BatchProgress progress,
            String host, int port, String user, String pass,
            Consumer<String> logCallback) throws Exception {
        int done = 0;
        RetryPolicy policy = RetryPolicy.networkDefault();
        RetryUserPrompter prompter = RetryUserPrompter.abortAll();
        FtpSession session = new FtpSession(host, port);
        session.connect(user, pass);
        FtpOperations ops = new FtpOperations(session);
        // 关掉单文件进度：这里是几百个小文件的批量搬运，整体进度每 5 秒已汇报一次，
        // 个别慢文件再插一行"已传 X KB"只会打断节奏，也看不出整体走到哪
        ops.setProgressLog(null);
        java.util.Set<String> ensuredDirs = new java.util.HashSet<>();
        try {
            for (java.util.Map.Entry<String, String> e : contentDirRels.entrySet()) {
                String contentRel = e.getKey();
                String contentAbs = e.getValue() + (contentRel.isEmpty() ? "" : contentRel + "/");
                String backupBase = backupDir + (contentRel.isEmpty() ? "" : contentRel + "/");
                for (java.util.Map.Entry<String, Long> fe : contentListings
                        .getOrDefault(contentRel, java.util.Map.of()).entrySet()) {
                    checkVueDirCancelled();
                    String rel = fe.getKey();
                    long expectedSize = fe.getValue() == null ? -1 : fe.getValue();
                    String remoteFile = contentAbs + rel;
                    String backupFile = backupBase + rel;
                    String parentDir = backupFile.substring(0, backupFile.lastIndexOf('/') + 1);
                    Path temp = Files.createTempFile("vuedir-bk-", ".tmp");
                    String originalMtime = null;
                    try {
                        // 单文件多轮尝试：每轮失败按 2s/5s/10s 退避后换新会话重来。
                        // 备份是回滚的唯一来源，这里宁可多等也不能漏备份；预算耗尽即中止整个部署
                        // （此时业务文件一个字节都没动），绝不"跳过这个文件继续备份"
                        java.io.IOException lastErr = null;
                        for (int attempt = 1; attempt <= policy.maxAttempts(); attempt++) {
                            try {
                                if (ensuredDirs.add(parentDir)) {
                                    ops.mkdirs(parentDir);
                                }
                                // 记录原始修改时间（MDTM，UTC 串）：回滚恢复后写回，
                                // 避免回滚把未变化内容的时间戳刷成回滚时刻。
                                // 只问本次会被覆盖的文件，整包逐个问要几百次多余往返
                                originalMtime = willOverwritePaths.contains(remoteFile)
                                        ? ops.getModificationTime(remoteFile) : null;
                                checkDownloadedSize(remoteFile,
                                        ops.download(remoteFile, temp), expectedSize);
                                // 备份文件同样临时名 + rename 发布：当天重复备份（OVERWRITE）时，
                                // 新备份完整传完才替换旧备份，中途断网不会留下半截备份
                                uploadFileSafely(session, ops, temp, backupFile,
                                        prompter, logCallback);
                                lastErr = null;
                                break;
                            } catch (java.io.IOException ioe) {
                                lastErr = ioe;
                                if (attempt >= policy.maxAttempts()) break;
                                logCallback.accept("WARN  [备份] " + rel + " 失败，"
                                        + policy.backoffFor(attempt).toSeconds() + "s 后换连接重试（剩 "
                                        + (policy.maxAttempts() - attempt) + " 次）：" + ioe.getMessage());
                                try {
                                    Thread.sleep(policy.backoffFor(attempt).toMillis());
                                } catch (InterruptedException ie) {
                                    Thread.currentThread().interrupt();
                                    throw new java.io.IOException("备份等待重试被中断", ie);
                                }
                                checkVueDirCancelled();
                                try { session.close(); } catch (Exception ignored) { }
                                session = new FtpSession(host, port);
                                session.connect(user, pass);
                                ops = new FtpOperations(session);
                                ops.setProgressLog(null);
                                ensuredDirs.clear();
                            }
                        }
                        if (lastErr != null) {
                            throw lastErr;
                        }
                        updatedFiles.add(new String[]{remoteFile, backupFile, null, originalMtime});
                        done++;
                        progress.onFileDone(expectedSize);
                    } finally {
                        Files.deleteIfExists(temp);
                    }
                }
            }
        } finally {
            try { session.close(); } catch (Exception ignored) { }
        }
        return done;
    }

    /**
     * 备份下载字节数核对：与清单里的远端大小不一致即抛出（半截下载 / 备份期间文件被他人改动），
     * 由镜像循环的重连重试兜住一次，仍不一致则中止部署（此时远端零变更）。
     *
     * @param remoteFile   远端文件路径
     * @param downloaded   实际下载字节数
     * @param expectedSize 清单中的远端大小（未知传 -1，跳过核对）
     * @throws java.io.IOException 字节数不一致
     * @author xumanyi
     * @date 2026-09-22
     */
    private static void checkDownloadedSize(String remoteFile, long downloaded, long expectedSize)
            throws java.io.IOException {
        if (expectedSize >= 0 && downloaded != expectedSize) {
            throw new java.io.IOException("备份下载不完整或远端文件已变化: " + remoteFile
                    + "，清单 " + expectedSize + " B，实得 " + downloaded + " B");
        }
    }

    /**
     * 把 Vue 主目标转换为版本记录用的目标列表：模块目录目标聚合为<b>工程包级</b>条目。
     *
     * <p>Vue 的版本记录按工程包（content）一份，命名 {@code {content}_update_notes.txt}、
     * 放在工程包目录旁边（与 jar/war「记录放包旁」的惯例一致）；一次更新追加一条记录，
     * 不按模块拆散。zip 形态目标保持原样（zip 本身就是包）。</p>
     *
     * @param pluginConfig 插件配置（取 vueContent）
     * @param mains        主目标列表
     * @return 版本记录目标列表（content 目录去重聚合）
     * @author xumanyi
     * @date 2026-08-13
     */
    private static List<FtpTargetSelection> buildVueNoteTargets(
            PluginDeployConfig pluginConfig, List<FtpTargetSelection> mains) {
        String content = pluginConfig.getVueContent();
        List<FtpTargetSelection> result = new ArrayList<>();
        java.util.Set<String> seenContentRels = new java.util.HashSet<>();
        for (FtpTargetSelection t : mains) {
            if (t == null) continue;
            if (t.isVueModuleDir() && content != null && !content.isBlank()) {
                String rel = t.getRelativePath();
                int slash = rel.lastIndexOf('/');
                String contentRel = slash > 0 ? rel.substring(0, slash) : rel;
                if (seenContentRels.add(contentRel)) {
                    result.add(new FtpTargetSelection(
                            t.getProject(), t.getSystem(), content, contentRel));
                }
            } else {
                result.add(t);
            }
        }
        return result;
    }

    /**
     * 目录直更的取消检查：用户点了「停止」即抛异常中断当前阶段，统一走失败回滚路径
     *
     * @throws java.io.IOException 用户已请求停止
     * @author xumanyi
     * @date 2026-08-13
     */
    private static void checkVueDirCancelled() throws java.io.IOException {
        if (currentCancelMode != CancelMode.NONE) {
            throw new java.io.IOException("用户请求停止");
        }
    }

    /**
     * Vue 源的 dry-run 预检：逐个主目标核对远端状态
     *
     * <p>Vue 更新只认服务包目录（模块目录逐文件覆盖）：任何非目录目标（zip）直接拦截，
     * 不做兜底。覆盖型目标要求远端模块目录存在；新建目标要求远端不存在（他人抢先投放时中止，
     * 让用户刷新目标树确认后再操作）。远端核对失败先换连接重试一次（网络不稳时的瞬时故障），
     * 仍失败才判预检失败。同时校验本地模块产物（manifest.json）就绪。</p>
     *
     * @param pluginConfig 插件配置
     * @param host         FTP 主机
     * @param port         FTP 端口
     * @param user         FTP 用户名
     * @param pass         FTP 密码
     * @param logCallback  日志回调
     * @return 预检结果（全部通过时 markSuccess）
     * @author xumanyi
     * @date 2026-08-13
     */
    private static DeployResult preCheckVueTargets(
            PluginDeployConfig pluginConfig,
            String host, int port, String user, String pass,
            Consumer<String> logCallback) {
        DeployResult result = new DeployResult();
        List<FtpTargetSelection> mains = pluginConfig.getMainTargets();
        if (mains == null || mains.isEmpty()) {
            result.addError("preCheck", "vue", "未选择目标包");
            logCallback.accept("ERROR [预检] 未选择目标包");
            return result;
        }
        // 本地产物：每个目标对应模块的 dist/umd/{模块号}/manifest.json 必须存在
        String content = pluginConfig.getVueContent();
        List<String> modules = pluginConfig.getVueModules();
        com.flux.deploy.plugin.util.ArtifactFreshnessChecker.Result freshness =
                com.flux.deploy.plugin.util.ArtifactFreshnessChecker.checkVueModules(
                        pluginConfig.getModulePath(), modules);
        if (!freshness.isFresh()) {
            // 预检只提示不拦截（UI 层点击阶段已弹过确认框），保留日志线索
            logCallback.accept("WARN  [预检] " + freshness.staleSources.size()
                    + " 个模块源码晚于构建产物，请确认已重新构建");
        }
        // Vue 更新只认服务包目录：非目录目标（zip）一律拦截，绝不走 zip 上传链路
        for (FtpTargetSelection t : mains) {
            if (!t.isVueModuleDir()) {
                logCallback.accept("ERROR [预检] Vue 更新只支持服务包目录（模块目录逐文件覆盖），目标 "
                        + t.getTargetName() + " 不是模块目录，已中止");
                result.addError("preCheck", t.getTargetName(),
                        "Vue 更新只支持服务包目录，不支持 zip 目标");
                return result;
            }
        }
        // 远端核对：网络不稳时单次 LIST 可能超时 / 被服务端掐断，换一条连接重试一次
        // （从头核对，已核对过的目标只是重打一遍日志），仍失败才判预检失败
        Exception lastErr = null;
        for (int attempt = 1; attempt <= 2; attempt++) {
            try (FtpSession session = new FtpSession(host, port)) {
                session.connect(user, pass);
                DeployResult failure = checkVueDirTargetsRemote(mains, new FtpOperations(session), logCallback);
                if (failure != null) {
                    return failure;
                }
                lastErr = null;
                break;
            } catch (Exception e) {
                lastErr = e;
                if (attempt == 1) {
                    logCallback.accept("WARN  [预检] FTP 核对中断，换连接重试一次：" + e.getMessage());
                }
            }
        }
        if (lastErr != null) {
            logCallback.accept("ERROR [预检] FTP 核对失败（已重试一次）：" + lastErr.getMessage());
            result.addError("preCheck", "vue", "FTP 核对失败: " + lastErr.getMessage());
            return result;
        }
        logCallback.accept("INFO  [预检] Vue 目标核对通过（" + content + "，"
                + mains.size() + " 个目标）");
        result.markSuccess();
        return result;
    }

    /**
     * 逐个核对 Vue 模块目录目标的远端状态（预检的 FTP 部分，可整体重试）
     *
     * <p>核对父目录（content 目录）里模块目录的存在性与 createNew 一致：覆盖型目标要求
     * 模块目录存在；新建目标要求不存在（空目录放行——上次新建投放失败的回滚只删文件不删目录）。</p>
     *
     * @param mains       主目标（均为模块目录目标）
     * @param ops         当前 FTP 会话的操作对象
     * @param logCallback 日志回调
     * @return 核对未通过时的失败结果；全部通过返回 null
     * @throws java.io.IOException FTP 操作失败（由调用方决定重试）
     * @author xumanyi
     * @date 2026-09-22
     */
    private static DeployResult checkVueDirTargetsRemote(List<FtpTargetSelection> mains,
                                                         FtpOperations ops,
                                                         Consumer<String> logCallback)
            throws java.io.IOException {
        for (FtpTargetSelection t : mains) {
            String rp = t.getRemoteDir() + t.getRelativePath();
            String rel = t.getRelativePath();
            int slash = rel.lastIndexOf('/');
            String parentDir = slash > 0 ? t.getRemoteDir() + rel.substring(0, slash) : t.getRemoteDir();
            String moduleDirName = slash > 0 ? rel.substring(slash + 1) : rel;
            boolean dirExists = false;
            for (org.apache.commons.net.ftp.FTPFile f : ops.listFiles(parentDir)) {
                if (f != null && f.isDirectory() && moduleDirName.equalsIgnoreCase(f.getName())) {
                    dirExists = true;
                    break;
                }
            }
            if (t.isCreateNew() && dirExists) {
                // 空目录放行：上一次新建投放失败后的回滚只删文件不删目录，
                // 残留空目录不代表他人已投放，不应阻塞重试
                boolean dirEmpty = ops.listFiles(rp).stream()
                        .noneMatch(f -> f != null
                                && !".".equals(f.getName()) && !"..".equals(f.getName()));
                if (dirEmpty) {
                    logCallback.accept("INFO  [预检] 新建模块目录已存在但为空目录"
                            + "（疑似上次回滚残留），按可投放处理：" + rp);
                } else {
                    logCallback.accept("ERROR [预检] 新建模块目录已存在且非空：" + rp
                            + "（可能他人已投放），请刷新目标列表后重试");
                    DeployResult failure = new DeployResult();
                    failure.addError("preCheck", t.getTargetName(), "新建模块目录远端已存在");
                    return failure;
                }
            }
            if (!t.isCreateNew() && !dirExists) {
                // 上次更新在目录切换中途崩过：旧版本还完整躺在 .__OLD__ 里，
                // 执行更新时的现场自愈会把它改回正式名，这里如实说明并放行，
                // 不让用户对着"目录不存在"无从下手
                boolean oldDirPresent = false;
                for (org.apache.commons.net.ftp.FTPFile f : ops.listFiles(parentDir)) {
                    if (f != null && f.isDirectory()
                            && (moduleDirName + FtpOperations.SHADOW_OLD_SUFFIX)
                                    .equalsIgnoreCase(f.getName())) {
                        oldDirPresent = true;
                        break;
                    }
                }
                if (oldDirPresent) {
                    logCallback.accept("WARN  [预检] " + rp + " 缺失，但检测到上次更新中断留下的"
                            + "旧版本目录 " + rp + FtpOperations.SHADOW_OLD_SUFFIX
                            + "，执行更新时会先把它恢复为正式目录再更新");
                    continue;
                }
                logCallback.accept("ERROR [预检] 远程模块目录不存在：" + rp
                        + "，请刷新目标列表后重试");
                DeployResult failure = new DeployResult();
                failure.addError("preCheck", t.getTargetName(), "远程模块目录不存在");
                return failure;
            }
            logCallback.accept("INFO  [预检] 模块目录 " + t.getTargetName()
                    + (t.isCreateNew() ? " 为新建投放，远端确认不存在" : " 存在，将逐文件覆盖更新"));
        }
        return null;
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

    /**
     * 在扫描根范围内有界探测共享库的部署落点：含 {@code lib/{libName}.umd.js} 的目录
     * （即 login 壳应用根）。
     *
     * <p>BFS 逐层 LIST，跳过备份目录；命中候选后不再下钻其子树。深度与访问量有界，
     * 防止在超大目录树上失控。</p>
     *
     * @param host     FTP 主机
     * @param port     FTP 端口
     * @param user     FTP 用户名
     * @param pass     FTP 密码
     * @param scanRoot 扫描根绝对路径（以 / 结尾）
     * @param libName  库名（如 sce-vcom-components）
     * @return 候选目录的相对路径列表（相对 scanRoot，如 common/sce-vcom-login）
     * @throws java.io.IOException FTP 操作失败
     * @author xumanyi
     * @date 2026-08-14
     */
    public static List<String> findSharedLibLoginDirs(
            String host, int port, String user, String pass,
            String scanRoot, String libName) throws java.io.IOException {
        final String libFile = libName + ".umd.js";
        List<String> candidates = new ArrayList<>();
        try (FtpSession session = new FtpSession(host, port)) {
            session.connect(user, pass);
            FtpOperations ops = new FtpOperations(session);
            java.util.ArrayDeque<String> queue = new java.util.ArrayDeque<>();
            queue.add("");
            int visited = 0;
            while (!queue.isEmpty() && visited < 200 && candidates.size() < 10) {
                String rel = queue.poll();
                // 深度上限 4 层：login 壳通常在系统根下 1~2 层（common/sce-vcom-login）
                if (!rel.isEmpty() && rel.split("/").length > 4) continue;
                visited++;
                String abs = scanRoot + (rel.isEmpty() ? "" : rel + "/");
                List<String> subDirs = new ArrayList<>();
                boolean hasLibDir = false;
                for (FTPFile f : ops.listFiles(abs)) {
                    if (f == null || !f.isDirectory()) continue;
                    String name = f.getName();
                    if (".".equals(name) || "..".equals(name)) continue;
                    if (com.flux.deploy.ftp.FtpOperations.BACKUP_DIR_NAMES
                            .contains(name.toLowerCase(java.util.Locale.ROOT))) {
                        continue;
                    }
                    if ("lib".equals(name)) hasLibDir = true;
                    subDirs.add(name);
                }
                if (hasLibDir) {
                    boolean hit = false;
                    for (FTPFile f : ops.listFiles(abs + "lib/")) {
                        if (f != null && f.isFile() && libFile.equalsIgnoreCase(f.getName())) {
                            hit = true;
                            break;
                        }
                    }
                    if (hit) {
                        candidates.add(rel);
                        continue; // 命中即为 login 根，不再下钻
                    }
                }
                for (String name : subDirs) {
                    queue.add(rel.isEmpty() ? name : rel + "/" + name);
                }
            }
        }
        return candidates;
    }

    /**
     * 共享库更新：自动构建 → 备份 → 上传 {@code lib/{libName}.umd.js} → 刷新
     * {@code index.html} 缓存参数 → 登记回滚数据。
     *
     * <p>缓存参数：index.html 以 {@code lib/{lib}.umd.js?v1={时间戳}} 引用库文件，
     * 只换文件不改 v1 时浏览器继续用缓存旧库；这里只改被更新库的 v1，其他库不动。
     * index.html 里找不到 v1 参数时如实告警、不改写（人工核对缓存策略）。</p>
     *
     * <p>备份粒度 = 实际改动的两个文件（库文件 + index.html），存放于
     * {@code {systemRoot}backup/{yyyyMMdd_HHmmss}_{操作人}_{库名}/}；
     * 完成后登记回滚清单，「回滚上次部署」可恢复两文件及原始修改时间。</p>
     *
     * @param project        IDEA 项目（后台任务宿主）
     * @param projectRoot    共享库工程本地根目录
     * @param libName        库名（package.json 的 name）
     * @param targetLoginAbs 目标 login 目录绝对路径（以 / 结尾）
     * @param operator       操作人（备份目录命名用，可空）
     * @param host           FTP 主机
     * @param port           FTP 端口
     * @param user           FTP 用户名
     * @param pass           FTP 密码
     * @param logCallback    日志回调
     * @param onComplete     完成回调（EDT；参数 = 是否成功）
     * @author xumanyi
     * @date 2026-08-14
     */
    public static void executeSharedLibUpdate(Project project,
            java.nio.file.Path projectRoot, String libName, String targetLoginAbs,
            String operator, String host, int port, String user, String pass,
            Consumer<String> logCallback, Consumer<Boolean> onComplete) {
        // 旧的回滚数据对应的是别的目标，本次更新前先作废，避免误回滚
        clearRollbackData();
        ProgressManager.getInstance().run(new Task.Backgroundable(project, "FLUX 共享库更新", true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                long start = System.currentTimeMillis();
                boolean libUploaded = false;
                boolean indexUploaded = false;
                String backupDir = null;
                String[] mdtms = {null, null};
                final String remoteLib = targetLoginAbs + "lib/" + libName + ".umd.js";
                final String remoteIndex = targetLoginAbs + "index.html";
                try {
                    // ── 1. 自动构建 ──
                    logCallback.accept("=== 开始共享库更新：" + libName + " ===");
                    logCallback.accept("INFO  [构建] npm run build-only（" + projectRoot + "）");
                    long buildStart = System.currentTimeMillis();
                    VueModuleBuildRunner.runNpmScript(projectRoot, "build-only",
                            line -> logCallback.accept(
                                    com.flux.deploy.plugin.toolwindow.LogSectionPanel.RAW_LINE_MARK
                                            + line),
                            indicator::isCanceled);
                    java.nio.file.Path localUmd = projectRoot.resolve(
                            VueProjectResolver.sharedLibUmdRelPath(libName));
                    if (!Files.isRegularFile(localUmd)) {
                        throw new java.io.IOException("构建完成但产物缺失：" + localUmd);
                    }
                    long localSize = Files.size(localUmd);
                    logCallback.accept("INFO  [构建] 完成，产物 " + localUmd.getFileName()
                            + " " + String.format("%.1f", localSize / 1024.0 / 1024.0) + " MB，耗时 "
                            + formatElapsed(System.currentTimeMillis() - buildStart));

                    FtpSession session = new FtpSession(host, port);
                    try {
                        session.connect(user, pass);
                        FtpOperations ops = new FtpOperations(session);

                        // ── 2. 远端核对 ──
                        if (!ops.exists(remoteLib)) {
                            throw new java.io.IOException("目标目录不含 lib/" + libName
                                    + ".umd.js，不是有效的 login 包：" + targetLoginAbs);
                        }
                        if (!ops.exists(remoteIndex)) {
                            throw new java.io.IOException("目标目录缺少 index.html：" + targetLoginAbs);
                        }
                        mdtms[0] = ops.getModificationTime(remoteLib);
                        mdtms[1] = ops.getModificationTime(remoteIndex);
                        java.nio.file.Path idxTemp = Files.createTempFile("sharedlib-idx-", ".html");
                        String indexContent;
                        try {
                            ops.download(remoteIndex, idxTemp);
                            indexContent = Files.readString(idxTemp,
                                    java.nio.charset.StandardCharsets.UTF_8);
                        } finally {
                            Files.deleteIfExists(idxTemp);
                        }
                        String libRef = "lib/" + libName + ".umd.js";
                        if (!indexContent.contains(libRef)) {
                            throw new java.io.IOException(
                                    "index.html 未引用 " + libRef + "，中止更新（请人工确认目标）");
                        }
                        logCallback.accept("INFO  [预检] 目标核对通过：" + targetLoginAbs);
                        if (indicator.isCanceled()) {
                            throw new java.io.IOException("用户取消（远端未变更）");
                        }

                        // ── 3. 备份（库文件 + index.html） ──
                        String ts = new java.text.SimpleDateFormat("yyyyMMdd_HHmmss")
                                .format(new java.util.Date());
                        String op = nullToEmpty(operator);
                        backupDir = resolveSystemRoot(targetLoginAbs) + "backup/" + ts
                                + (op.isEmpty() ? "" : "_" + op) + "_" + libName + "/";
                        ops.mkdirs(backupDir + "lib/");
                        java.nio.file.Path bkTemp = Files.createTempFile("sharedlib-bk-", ".tmp");
                        try {
                            // 备份同样"下载核对字节数 + 原子发布"：备份写坏了就没有第二份
                            backupOneRemoteFile(ops, remoteLib,
                                    backupDir + "lib/" + libName + ".umd.js", bkTemp);
                            backupOneRemoteFile(ops, remoteIndex,
                                    backupDir + "index.html", bkTemp);
                        } finally {
                            Files.deleteIfExists(bkTemp);
                        }
                        logCallback.accept("INFO  [备份] 已备份原库文件与 index.html 到 " + backupDir);

                        // ── 4. 上传新库文件并校验字节数（原子发布：传输中断时线上仍是完整旧库，
                        //    不会出现半截库文件把整个前端打挂） ──
                        ops.uploadAtomic(localUmd, remoteLib);
                        libUploaded = true;
                        long remoteSize = ops.getFileSize(remoteLib);
                        if (remoteSize != localSize) {
                            throw new java.io.IOException("上传校验不一致：本地 " + localSize
                                    + " B，远端 " + remoteSize + " B");
                        }
                        logCallback.accept("INFO  [上传] " + libRef + " 更新完成（"
                                + remoteSize + " B，校验通过）");

                        // ── 5. 刷新 index.html 缓存参数（只改本库的 v1） ──
                        long now = System.currentTimeMillis();
                        java.util.regex.Pattern vp = java.util.regex.Pattern.compile(
                                java.util.regex.Pattern.quote(libRef) + "\\?v1=\\d+");
                        java.util.regex.Matcher vm = vp.matcher(indexContent);
                        if (vm.find()) {
                            String updated = vm.replaceAll(
                                    java.util.regex.Matcher.quoteReplacement(libRef + "?v1=" + now));
                            java.nio.file.Path upTemp = Files.createTempFile("sharedlib-idx-", ".html");
                            try {
                                Files.writeString(upTemp, updated,
                                        java.nio.charset.StandardCharsets.UTF_8);
                                // index.html 是入口页，整份写回半传即打挂站点，必须原子发布
                                ops.uploadAtomic(upTemp, remoteIndex);
                                indexUploaded = true;
                            } finally {
                                Files.deleteIfExists(upTemp);
                            }
                            logCallback.accept("INFO  [缓存] index.html 已刷新 " + libRef
                                    + " 的 v1=" + now + "（其他库的缓存参数不动）");
                        } else {
                            logCallback.accept("WARN  [缓存] index.html 引用 " + libRef
                                    + " 未带 ?v1= 缓存参数，未改写；浏览器可能继续用缓存旧库，请人工核对");
                        }
                    } finally {
                        try { session.close(); } catch (Exception ignored) { }
                    }

                    // ── 6. 登记回滚数据 ──
                    registerSharedLibRollback(backupDir, remoteLib, remoteIndex,
                            indexUploaded, mdtms, libName);
                    logCallback.accept("\n╔══════════════════════════════╗");
                    logCallback.accept("║   共享库更新完成             ║");
                    logCallback.accept("╚══════════════════════════════╝");
                    logCallback.accept(com.flux.deploy.plugin.toolwindow.LogSectionPanel.RAW_LINE_MARK
                            + remoteLib);
                    logCallback.accept(com.flux.deploy.plugin.toolwindow.LogSectionPanel.RAW_LINE_MARK
                            + "备份目录：" + backupDir);
                    logCallback.accept("INFO  [完成] 耗时 "
                            + formatElapsed(System.currentTimeMillis() - start)
                            + "；版本记录未自动改动，如需登记请人工补记");
                    SwingUtilities.invokeLater(() -> onComplete.accept(true));
                } catch (Exception e) {
                    logCallback.accept("ERROR [更新] 共享库更新失败：" + e.getMessage());
                    if (libUploaded) {
                        // 库文件已覆盖：登记回滚数据让「回滚」按钮可恢复，并如实告知现场状态
                        registerSharedLibRollback(backupDir, remoteLib, remoteIndex,
                                indexUploaded, mdtms, libName);
                        logCallback.accept("WARN  [更新] 远端库文件已被覆盖，"
                                + "可点「回滚」恢复备份版本（备份在 " + backupDir + "）");
                    } else {
                        logCallback.accept("INFO  [更新] 远端未发生变更");
                    }
                    SwingUtilities.invokeLater(() -> onComplete.accept(false));
                }
            }
        });
    }

    /**
     * 登记共享库更新的回滚清单（库文件必登记；index.html 仅在实际改写后登记）
     *
     * @param backupDir     备份目录（以 / 结尾）
     * @param remoteLib     远端库文件绝对路径
     * @param remoteIndex   远端 index.html 绝对路径
     * @param indexUploaded index.html 是否被改写上传
     * @param mdtms         [库文件原始修改时间, index.html 原始修改时间]（MDTM 串，可为 null）
     * @param libName       库名
     * @author xumanyi
     * @date 2026-08-14
     */
    private static void registerSharedLibRollback(String backupDir, String remoteLib,
            String remoteIndex, boolean indexUploaded, String[] mdtms, String libName) {
        if (backupDir == null) return;
        List<String[]> entries = new ArrayList<>();
        entries.add(new String[]{remoteLib, backupDir + "lib/" + libName + ".umd.js",
                null, mdtms[0]});
        if (indexUploaded) {
            entries.add(new String[]{remoteIndex, backupDir + "index.html", null, mdtms[1]});
        }
        lastBackupDir = backupDir;
        lastUpdatedPackages = entries;
        lastAllTargets = new ArrayList<>();
        lastUpdatedNote = false;
        lastBackupBorrowed = false;
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
            logCallback.accept("[嵌入] " + warName + " 包内变更：整包替换内嵌 JAR，FULL 模式");
            return;
        }
        logCallback.accept("[嵌入] " + warName + " 变更清单，共 "
                + manifest.total() + " 项：");
        for (String e : manifest.getReplaced()) logCallback.accept("INFO  [嵌入] 替换 " + e);
        for (String e : manifest.getAdded())    logCallback.accept("INFO  [嵌入] 新增 " + e);
        for (String e : manifest.getDeleted())  logCallback.accept("INFO  [嵌入] 删除 " + e);
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
            log.append("[嵌入] ").append(warName).append(" 包内变更：整包替换内嵌 JAR，FULL 模式\n");
            return;
        }
        log.append("[嵌入] ").append(warName).append(" 变更清单，共 ")
                .append(manifest.total()).append(" 项：\n");
        for (String e : manifest.getReplaced()) log.append("INFO  [嵌入] 替换 ").append(e).append('\n');
        for (String e : manifest.getAdded())    log.append("INFO  [嵌入] 新增 ").append(e).append('\n');
        for (String e : manifest.getDeleted())  log.append("INFO  [嵌入] 删除 ").append(e).append('\n');
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
                    + "，WAR=" + warFile.getFileName() + "，WEB-INF/lib 下没有同名 JAR");
        }
        String picked = com.flux.deploy.plugin.service.LocalPackagePatchService
                .pickVersionMatching(candidates, artifactFileName);
        if (picked == null) {
            throw new java.io.IOException("目标 WAR 内不存在 " + artifactFileName
                    + "，WAR=" + warFile.getFileName() + "，WEB-INF/lib 下只有版本不一致的 "
                    + candidates + "，必须文件名完全一致才能替换");
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
                    "targetJarName 必须是完整 jar 文件名，含 .jar 扩展名，实际：" + targetJarName);
        }
        String entryPath = "WEB-INF/lib/" + targetJarName;
        try (java.util.jar.JarFile jar = new java.util.jar.JarFile(warFile.toFile())) {
            java.util.jar.JarEntry entry = jar.getJarEntry(entryPath);
            if (entry == null || entry.isDirectory()) {
                throw new Exception("目标 WAR 内不存在条目 " + entryPath
                        + "，必须按完整文件名精确匹配，禁止 prefix 兜底");
            }
            try (java.io.InputStream is = jar.getInputStream(entry)) {
                Files.copy(is, outputJar, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }
}
