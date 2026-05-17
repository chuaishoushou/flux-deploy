package com.flux.deploy.plugin.toolwindow;

import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBPanel;
import com.intellij.ui.components.JBScrollPane;

import javax.swing.*;
import javax.swing.text.AttributeSet;
import javax.swing.text.BadLocationException;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;
import java.awt.*;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 日志 Section 面板：实时执行日志 + 进度条
 *
 * <p>提供带时间戳的日志追加（线程安全）、日志清空、
 * 进度条显示/隐藏等功能，用于展示部署过程的实时输出。</p>
 *
 * <p>渲染规则（按行匹配，从上到下首个命中）：</p>
 * <ol>
 *   <li>空段：输出空行（用于视觉分组）。</li>
 *   <li>以 {@link #RAW_LINE_MARK} 开头：剥离标记后按原文输出，不带时间戳前缀，
 *       用于在主消息下分行展示从属内容（如逐行列出包路径）。</li>
 *   <li>以 {@code ╔ ╚ ║} 起始的框体行：按文本内容判定收尾色（成功绿 / 失败红 / 中止橙）。</li>
 *   <li>满足 {@code LEVEL [stage] message} 形式：丢弃 LEVEL 文本，时间戳与 {@code [stage]}
 *       均按默认色，仅按级别为消息正文着色（INFO 默认 / WARN 橙 / ERROR 红）。</li>
 *   <li>其余行：按 INFO 默认色渲染，保持向后兼容。</li>
 * </ol>
 * <p>统一格式：{@code HH:mm:ss  [stage] message}，时间戳后 2 空格分隔，不显示级别字样。</p>
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

    /** 形如 {@code INFO [stage] message} 的前缀解析（级别仅 INFO/WARN/ERROR）。 */
    private static final Pattern LEVEL_LINE = Pattern.compile(
            "^(INFO|WARN|ERROR)\\s+(\\[[^\\]]+\\])\\s*(.*)$");

    private final JTextPane logArea;
    private final StyledDocument doc;
    private final JProgressBar progressBar;

    private final AttributeSet styleInfo;
    private final AttributeSet styleWarn;
    private final AttributeSet styleError;
    private final AttributeSet styleBoxSuccess;
    private final AttributeSet styleBoxFail;
    private final AttributeSet styleBoxAbort;

    /**
     * 构造日志面板，初始化日志文本区域和进度条
     */
    public LogSectionPanel() {
        super(new BorderLayout());

        this.logArea = new JTextPane();
        logArea.setEditable(false);
        logArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        this.doc = logArea.getStyledDocument();

        this.styleInfo = buildStyle(null, false);
        this.styleWarn = buildStyle(new JBColor(new Color(0xB07D00), new Color(0xE0A030)), false);
        this.styleError = buildStyle(new JBColor(new Color(0xC7222D), new Color(0xFF6B6B)), false);
        this.styleBoxSuccess = buildStyle(new JBColor(new Color(0x2E8B2E), new Color(0x6FBF6F)), true);
        this.styleBoxFail = buildStyle(new JBColor(new Color(0xC7222D), new Color(0xFF6B6B)), true);
        this.styleBoxAbort = buildStyle(new JBColor(new Color(0xB07D00), new Color(0xE0A030)), true);

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
     *   <li>其余段独立带时间戳前缀，并按级别 / 框体规则着色。</li>
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
            for (String line : lines) {
                renderLine(timestamp, line);
            }
            logArea.setCaretPosition(doc.getLength());
        });
    }

    /**
     * 按规则渲染一行（必须在 EDT 上调用）
     *
     * @param timestamp 时间戳
     * @param line      原始行内容
     * @author xumanyi
     * @date 2026-05-07
     */
    private void renderLine(String timestamp, String line) {
        if (line.isEmpty()) {
            append("\n", styleInfo);
            return;
        }
        if (line.charAt(0) == RAW_LINE_MARK) {
            append(line.substring(1) + "\n", styleInfo);
            return;
        }
        char first = line.charAt(0);
        if (first == '╔' || first == '╚' || first == '║') {
            append(timestamp + "  ", styleInfo);
            append(line + "\n", pickBoxStyle(line));
            return;
        }
        Matcher m = LEVEL_LINE.matcher(line);
        if (m.matches()) {
            String level = m.group(1);
            String stage = m.group(2);
            String msg = m.group(3);
            append(timestamp + "  " + stage + " ", styleInfo);
            append(msg + "\n", pickLevelStyle(level));
            return;
        }
        // 无级别前缀的行：按 INFO 默认色渲染
        append(timestamp + "  " + line + "\n", styleInfo);
    }

    /**
     * 根据框体内文字判定收尾色：成功绿 / 失败红 / 中止橙
     *
     * @param line 框体行
     * @return 对应样式
     * @author xumanyi
     * @date 2026-05-07
     */
    private AttributeSet pickBoxStyle(String line) {
        if (line.contains("失败")) return styleBoxFail;
        if (line.contains("中止") || line.contains("停止") || line.contains("部分")) return styleBoxAbort;
        return styleBoxSuccess;
    }

    /**
     * 按日志级别选择正文颜色
     *
     * @param level 级别字符串
     * @return 对应样式
     * @author xumanyi
     * @date 2026-05-07
     */
    private AttributeSet pickLevelStyle(String level) {
        switch (level) {
            case "WARN":  return styleWarn;
            case "ERROR": return styleError;
            default:      return styleInfo;
        }
    }

    /**
     * 在文档末尾追加带样式片段（必须在 EDT 上调用）
     *
     * @param text  文本
     * @param style 样式
     * @author xumanyi
     * @date 2026-05-07
     */
    private void append(String text, AttributeSet style) {
        try {
            doc.insertString(doc.getLength(), text, style);
        } catch (BadLocationException ignored) {
            // 不应发生：始终往末尾插入
        }
    }

    /**
     * 构造一个带可选前景色与粗体的样式
     *
     * @param fg   前景色，{@code null} 表示沿用默认前景
     * @param bold 是否粗体
     * @return 不可变样式
     * @author xumanyi
     * @date 2026-05-07
     */
    private static AttributeSet buildStyle(Color fg, boolean bold) {
        SimpleAttributeSet s = new SimpleAttributeSet();
        if (fg != null) {
            StyleConstants.setForeground(s, fg);
        }
        if (bold) {
            StyleConstants.setBold(s, true);
        }
        return s;
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
     * 获取日志文本内容（纯文本，不含样式）
     */
    public String getLogText() {
        try {
            return doc.getText(0, doc.getLength());
        } catch (BadLocationException e) {
            return "";
        }
    }

    /**
     * 将日志内容复制到剪贴板（纯文本，颜色不进入剪贴板）
     */
    public void copyLog() {
        String text = getLogText();
        if (text != null && !text.isEmpty()) {
            java.awt.datatransfer.StringSelection sel =
                    new java.awt.datatransfer.StringSelection(text);
            java.awt.Toolkit.getDefaultToolkit().getSystemClipboard().setContents(sel, null);
        }
    }
}
