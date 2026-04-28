package com.flux.deploy.plugin.util;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * 部署日志文件写入器
 *
 * <p>将日志同步写入文件。一次操作（预检/部署）对应一个日志文件，
 * 手动回滚追加到同一文件。</p>
 *
 * <p>目录结构：{baseDir}/.flux-deploy-log/{date}/{time}_{type}.log</p>
 *
 * <p>线程安全：所有写入方法均通过 {@code synchronized} 保证。</p>
 *
 * @author xumanyi
 * @date 2026-03-27
 */
public class DeployLogWriter {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH-mm-ss");
    private static final DateTimeFormatter LOG_TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final Path logFile;
    private BufferedWriter writer;
    private boolean closed;

    /**
     * 私有构造，通过 {@link #create(String, String)} 工厂方法创建
     *
     * @param logFile 日志文件路径
     * @throws IOException 创建目录或文件失败时抛出
     * @author xumanyi
     * @date 2026-03-27
     */
    private DeployLogWriter(Path logFile) throws IOException {
        this.logFile = logFile;
        Files.createDirectories(logFile.getParent());
        this.writer = Files.newBufferedWriter(logFile, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        this.closed = false;
    }

    /**
     * 创建新的日志文件
     *
     * @param baseDir 日志根目录（如项目根目录）
     * @param type    操作类型（如 update、precheck、deploy）
     * @return 日志写入器实例
     * @throws IOException 创建文件失败时抛出
     * @author xumanyi
     * @date 2026-03-27
     */
    public static DeployLogWriter create(String baseDir, String type) throws IOException {
        String date = LocalDate.now().format(DATE_FMT);
        String time = LocalTime.now().format(TIME_FMT);
        String fileName = time + "_" + type + ".log";

        Path logFile = Path.of(baseDir, ".flux-deploy-log", date, fileName);
        return new DeployLogWriter(logFile);
    }

    /**
     * 写入一行日志
     *
     * @param message 日志消息
     * @author xumanyi
     * @date 2026-03-27
     */
    public synchronized void writeLine(String message) {
        if (closed || writer == null) return;
        try {
            writer.write(message);
            writer.newLine();
            writer.flush();
        } catch (IOException ignored) {}
    }

    /**
     * 追加分隔线和标题（手动回滚等场景使用）
     *
     * @param title 分隔区段标题
     * @author xumanyi
     * @date 2026-03-27
     */
    public synchronized void appendSection(String title) {
        if (closed || writer == null) return;
        try {
            writer.newLine();
            writer.write("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
            writer.newLine();
            writer.write("[" + LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")) + "] " + title);
            writer.newLine();
            writer.write("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
            writer.newLine();
            writer.flush();
        } catch (IOException ignored) {}
    }

    /**
     * 关闭日志文件
     */
    public synchronized void close() {
        if (closed) return;
        closed = true;
        try {
            if (writer != null) {
                writer.flush();
                writer.close();
                writer = null;
            }
        } catch (IOException ignored) {}
    }

    /**
     * 是否已关闭
     *
     * @return {@code true} 表示已关闭，不可再写入
     * @author xumanyi
     * @date 2026-03-27
     */
    public boolean isClosed() {
        return closed;
    }

    /**
     * 获取日志文件路径
     *
     * @return 日志文件的绝对路径
     * @author xumanyi
     * @date 2026-03-27
     */
    public Path getLogFile() {
        return logFile;
    }
}
