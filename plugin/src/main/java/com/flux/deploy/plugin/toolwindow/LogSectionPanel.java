package com.flux.deploy.plugin.toolwindow;

import com.intellij.ui.components.JBPanel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextArea;

import javax.swing.*;
import java.awt.*;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * 日志 Section 面板：实时执行日志 + 进度条
 *
 * <p>提供带时间戳的日志追加（线程安全）、日志清空、
 * 进度条显示/隐藏等功能，用于展示部署过程的实时输出。</p>
 *
 * @author xumanyi
 * @date 2026-03-27
 */
public class LogSectionPanel extends JBPanel<LogSectionPanel> {

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    /**
     * 行级标记：以该字符开头的日志行将不带时间戳前缀输出。
     *
     * <p>用于多行同组数据展示（例如"已更新 N 个包"下逐行列出完整路径），
     * 让从属行视觉上归属于上一条带时间戳的主消息，避免重复时间戳干扰。
     * 调用方在拼接行内容时只需在每行最前加上本字符即可。</p>
     */
    public static final char RAW_LINE_MARK = (char) 1;

    private final JBTextArea logArea;
    private final JProgressBar progressBar;

    /**
     * 构造日志面板，初始化日志文本区域和进度条
     */
    public LogSectionPanel() {
        super(new BorderLayout());

        this.logArea = new JBTextArea();
        logArea.setEditable(false);
        logArea.setRows(4);
        logArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));

        this.progressBar = new JProgressBar();
        progressBar.setVisible(false);

        JBScrollPane scrollPane = new JBScrollPane(logArea);
        add(scrollPane, BorderLayout.CENTER);
        add(progressBar, BorderLayout.SOUTH);
    }

    /**
     * 追加日志（线程安全）
     *
     * <p>自动添加时间戳前缀，并滚动到底部。</p>
     *
     * <p>消息允许内嵌 {@code \n} 实现多行：</p>
     * <ul>
     *   <li>空段打印为无前缀空行（便于视觉分组）；</li>
     *   <li>以 {@link #RAW_LINE_MARK} 开头的段，剥离标记后按原文输出，不加时间戳前缀
     *       （用于在主消息下分行展示从属内容，例如逐行列出包路径）；</li>
     *   <li>其余段独立带时间戳前缀。</li>
     * </ul>
     *
     * @param message 日志消息
     * @author xumanyi
     * @date 2026-03-27
     */
    public void appendLog(String message) {
        SwingUtilities.invokeLater(() -> {
            String timestamp = LocalTime.now().format(TIME_FMT);
            String[] lines = message == null ? new String[]{""} : message.split("\n", -1);
            StringBuilder sb = new StringBuilder();
            for (String line : lines) {
                if (line.isEmpty()) {
                    sb.append('\n');
                } else if (line.charAt(0) == RAW_LINE_MARK) {
                    // 标记为"无时间戳"的从属行：剥离标记后按原文输出
                    sb.append(line, 1, line.length()).append('\n');
                } else {
                    sb.append(timestamp).append("  ").append(line).append('\n');
                }
            }
            logArea.append(sb.toString());
            logArea.setCaretPosition(logArea.getDocument().getLength());
        });
    }

    /**
     * 清空日志
     */
    public void clear() {
        SwingUtilities.invokeLater(() -> logArea.setText(""));
    }

    /**
     * 显示/隐藏进度条
     *
     * @param visible {@code true} 显示并设为不确定模式，{@code false} 隐藏
     * @author xumanyi
     * @date 2026-03-27
     */
    public void setProgressVisible(boolean visible) {
        SwingUtilities.invokeLater(() -> {
            progressBar.setVisible(visible);
            progressBar.setIndeterminate(visible);
        });
    }

    /**
     * 获取日志文本内容
     */
    public String getLogText() {
        return logArea.getText();
    }

    /**
     * 将日志内容复制到剪贴板
     */
    public void copyLog() {
        String text = logArea.getText();
        if (text != null && !text.isEmpty()) {
            java.awt.datatransfer.StringSelection sel =
                    new java.awt.datatransfer.StringSelection(text);
            java.awt.Toolkit.getDefaultToolkit().getSystemClipboard().setContents(sel, null);
        }
    }
}
