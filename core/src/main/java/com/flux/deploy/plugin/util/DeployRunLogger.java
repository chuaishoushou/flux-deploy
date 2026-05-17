package com.flux.deploy.plugin.util;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 部署执行 session 日志写入器：每次部署对应一个 session 文件。
 *
 * <p>生命周期：
 * <ol>
 *   <li>{@link #open(DeployRunMeta)} 或 {@link #openAt(Path, DeployRunMeta)} 在
 *       {@code <root>/logs/{packageFileName}/} 下创建
 *       {@code yyyy-MM-dd_HHmmss_RUNNING.log} 并写入 header</li>
 *   <li>过程中 {@link #info(String)} / {@link #warn(String)} /
 *       {@link #error(String)} 追加行</li>
 *   <li>{@link #closeWith(DeployRunStatus)} 写 footer、关闭流、把 RUNNING 文件
 *       rename 成对应终态后缀（OK / FAIL / FAIL_RB / CANCEL）</li>
 * </ol>
 *
 * <p>线程安全：所有写入与状态切换方法均通过 {@code synchronized} 保护。</p>
 *
 * <p>注意：进程异常退出未调用 closeWith 时，{@code _RUNNING.log} 会留在原地，
 * 这本身就是"未正常结束"的信号；不引入启动期扫描自动归类，
 * 因为无法可靠区分崩溃与用户强 kill。</p>
 *
 * @author xumanyi
 * @date 2026-05-07
 */
public final class DeployRunLogger {

    private static final DateTimeFormatter FILE_TS = DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmmss");
    private static final DateTimeFormatter LINE_TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    private final Path runningFile;
    private final DeployRunMeta meta;
    private BufferedWriter writer;
    private boolean closed;

    private DeployRunLogger(Path runningFile, DeployRunMeta meta) throws IOException {
        this.runningFile = runningFile;
        this.meta = meta;
        Files.createDirectories(runningFile.getParent());
        this.writer = Files.newBufferedWriter(runningFile, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        writeHeader();
    }

    /**
     * 生产入口：在用户工作区 {@code ~/.flux-deploy/logs/} 下创建 session。
     *
     * @param meta 部署元数据（用于 header 与文件命名）
     * @return 已打开的 logger
     * @throws IOException 创建目录或文件失败时抛出
     * @author xumanyi
     * @date 2026-05-07
     */
    public static DeployRunLogger open(DeployRunMeta meta) throws IOException {
        Path workspace = Path.of(System.getProperty("user.home"), ".flux-deploy");
        return openAt(workspace, meta);
    }

    /**
     * 测试 / 自定义入口：在指定 workspace root 下创建 session（root 下会出现 {@code logs/}）。
     *
     * @param workspaceRoot 工作区根目录（生产为 ~/.flux-deploy）
     * @param meta          部署元数据
     * @return 已打开的 logger
     * @throws IOException              创建失败时抛出
     * @throws IllegalArgumentException 包名含非法字符（路径分隔符等）
     * @author xumanyi
     * @date 2026-05-07
     */
    public static DeployRunLogger openAt(Path workspaceRoot, DeployRunMeta meta) throws IOException {
        validatePackageName(meta.packageFileName());
        String fileName = meta.startTime().format(FILE_TS) + "_" + DeployRunStatus.RUNNING.name() + ".log";
        Path runningFile = workspaceRoot
                .resolve("logs")
                .resolve(meta.packageFileName())
                .resolve(fileName);
        return new DeployRunLogger(runningFile, meta);
    }

    private static void validatePackageName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("packageFileName 不能为空");
        }
        if (name.contains("/") || name.contains("\\") || name.contains("..")) {
            throw new IllegalArgumentException("packageFileName 含非法路径字符: " + name);
        }
    }

    private void writeHeader() throws IOException {
        writeLineRaw("INFO", "=== 部署开始 ===");
        writeLineRaw("INFO", "包: " + meta.packageFileName());
        writeLineRaw("INFO", "远端: " + meta.remoteTarget());
        writeLineRaw("INFO", "操作者: " + meta.operator());
        writeLineRaw("INFO", "IDE: " + meta.ideVersion());
        writeLineRaw("INFO", "插件: " + meta.pluginVersion());
    }

    private void writeLineRaw(String level, String msg) throws IOException {
        if (closed || writer == null) return;
        writer.write("[" + LocalDateTime.now().format(LINE_TS) + "] [" + level + "] " + msg);
        writer.newLine();
        writer.flush();
    }

    /**
     * 写入 INFO 行（写失败静默吞掉，日志非业务关键路径）。
     *
     * @param message 消息内容
     * @author xumanyi
     * @date 2026-05-07
     */
    public synchronized void info(String message) {
        try { writeLineRaw("INFO", message); } catch (IOException ignored) {}
    }

    /**
     * 写入 WARN 行（写失败静默吞掉，日志非业务关键路径）。
     *
     * @param message 消息内容
     * @author xumanyi
     * @date 2026-05-07
     */
    public synchronized void warn(String message) {
        try { writeLineRaw("WARN", message); } catch (IOException ignored) {}
    }

    /**
     * 写入 ERROR 行（写失败静默吞掉，日志非业务关键路径）。
     *
     * @param message 消息内容
     * @author xumanyi
     * @date 2026-05-07
     */
    public synchronized void error(String message) {
        try { writeLineRaw("ERROR", message); } catch (IOException ignored) {}
    }

    /**
     * 终态切换：写 footer、关闭流，把 RUNNING 文件 rename 成终态后缀文件。
     *
     * <p>幂等：重复调用第二次起 no-op。RUNNING 状态不可作为终态（拒绝）。
     * Rename 失败（罕见，磁盘满 / 权限）则保留 RUNNING 名，避免引入额外分支。</p>
     *
     * @param status 终态（OK / FAIL / FAIL_RB / CANCEL）
     * @throws IllegalArgumentException 传入 RUNNING 时
     * @author xumanyi
     * @date 2026-05-07
     */
    public synchronized void closeWith(DeployRunStatus status) {
        if (status == DeployRunStatus.RUNNING) {
            throw new IllegalArgumentException("closeWith 不接受 RUNNING（这是瞬态）");
        }
        if (closed) return;
        try {
            // 先写 footer（此刻 closed 仍为 false，writeLineRaw 会落盘）
            writeLineRaw("INFO", "=== 部署结束: " + status.name() + " ===");
            if (writer != null) {
                writer.flush();
                writer.close();
                writer = null;
            }
        } catch (IOException ignored) {
            // 最佳努力 close
        }
        closed = true; // 落盘完成后再标记，防止 footer 被 closed 检查拦掉
        // rename
        String newName = runningFile.getFileName().toString()
                .replace("_" + DeployRunStatus.RUNNING.name() + ".log", "_" + status.name() + ".log");
        Path target = runningFile.resolveSibling(newName);
        try {
            Files.move(runningFile, target);
        } catch (IOException ex) {
            // rename 失败：文件停留在 RUNNING 名下，与"进程异常退出"语义合并。不抛出。
        }
    }

}
