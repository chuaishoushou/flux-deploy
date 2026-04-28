package com.flux.deploy.plugin.toolwindow;

import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBPanel;
import com.intellij.ui.jcef.JBCefApp;
import com.intellij.ui.jcef.JBCefBrowser;
import com.intellij.util.ui.JBUI;

import javax.swing.*;
import javax.swing.event.HyperlinkEvent;
import javax.swing.text.html.HTMLEditorKit;
import java.awt.*;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * 文档展示面板：顶部返回按钮 + 主体 HTML 渲染区
 *
 * <p>优先使用 IntelliJ 内置 Chrome 内核（JBCefBrowser）渲染，字体和样式接近浏览器。
 * JCEF 不可用时回退到 Swing 的 JEditorPane。</p>
 *
 * @author xumanyi
 * @date 2026-04-17
 */
public class DocsPanel extends JBPanel<DocsPanel> {

    private final JBLabel titleLabel;
    private final JPanel contentHost;
    private final Runnable onBack;

    /** JCEF 浏览器（可能为 null，回退到 Swing） */
    private JBCefBrowser browser;
    /** Swing 回退渲染器 */
    private JEditorPane fallbackEditor;

    /**
     * 预热 JCEF 内核，提前承担首次初始化的 ~1s 开销，让用户首次点击使用手册/版本记录时秒开
     *
     * <p>调用方应在工具窗口打开后通过 {@link SwingUtilities#invokeLater(Runnable)}
     * 调用此方法，让浏览器在 EDT 空闲时初始化，不阻塞面板首次显示。</p>
     */
    public void prewarm() {
        if (JBCefApp.isSupported() && browser == null) {
            browser = new JBCefBrowser();
        }
    }

    public DocsPanel(Runnable onBack) {
        super(new BorderLayout());
        this.onBack = onBack;

        // 顶部导航栏：返回 + 标题
        JPanel header = new JPanel(new BorderLayout());
        header.setBorder(JBUI.Borders.empty(6, 10));

        JButton backBtn = new JButton("← 返回");
        backBtn.setMargin(new Insets(2, 10, 2, 10));
        backBtn.setFocusPainted(false);
        backBtn.setToolTipText("返回部署面板");
        backBtn.addActionListener(e -> { if (this.onBack != null) this.onBack.run(); });
        header.add(backBtn, BorderLayout.WEST);

        titleLabel = new JBLabel("");
        titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD, 13f));
        titleLabel.setHorizontalAlignment(SwingConstants.CENTER);
        header.add(titleLabel, BorderLayout.CENTER);

        add(header, BorderLayout.NORTH);

        // 内容区宿主（根据 JCEF 可用性动态放置浏览器或 JEditorPane）
        contentHost = new JPanel(new BorderLayout());
        add(contentHost, BorderLayout.CENTER);
    }

    /**
     * 加载指定文档资源
     *
     * @param title        顶部显示标题
     * @param resourcePath 插件资源路径，如 {@code /docs/help.html}
     */
    public void load(String title, String resourcePath) {
        titleLabel.setText(title);
        String html = loadHtml(resourcePath);

        if (JBCefApp.isSupported()) {
            loadWithJcef(html);
        } else {
            loadWithSwing(html);
        }
    }

    private void loadWithJcef(String html) {
        contentHost.removeAll();
        if (browser == null) {
            browser = new JBCefBrowser();
        }
        // data: URL 有长度/编码限制；使用 loadHTML 更稳
        browser.loadHTML(html);
        contentHost.add(browser.getComponent(), BorderLayout.CENTER);
        contentHost.revalidate();
        contentHost.repaint();
    }

    private void loadWithSwing(String html) {
        contentHost.removeAll();
        if (fallbackEditor == null) {
            fallbackEditor = new JEditorPane();
            fallbackEditor.setEditable(false);
            fallbackEditor.setContentType("text/html");
            fallbackEditor.setEditorKit(new HTMLEditorKit());
            fallbackEditor.setBackground(UIManager.getColor("Panel.background"));
            fallbackEditor.addHyperlinkListener(e -> {
                if (e.getEventType() == HyperlinkEvent.EventType.ACTIVATED
                        && e.getDescription() != null
                        && e.getDescription().startsWith("#")) {
                    fallbackEditor.scrollToReference(e.getDescription().substring(1));
                }
            });
        }
        fallbackEditor.setText(html);
        fallbackEditor.setCaretPosition(0);
        contentHost.add(new com.intellij.ui.components.JBScrollPane(fallbackEditor), BorderLayout.CENTER);
        contentHost.revalidate();
        contentHost.repaint();
    }

    private static String loadHtml(String resourcePath) {
        try (InputStream in = DocsPanel.class.getResourceAsStream(resourcePath)) {
            if (in == null) {
                return "<html><body style='padding:20px;font-family:sans-serif;'>未找到文档资源："
                        + resourcePath + "</body></html>";
            }
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(in, StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) sb.append(line).append('\n');
                return sb.toString();
            }
        } catch (Exception e) {
            return "<html><body style='padding:20px;font-family:sans-serif;'>加载失败："
                    + e.getMessage() + "</body></html>";
        }
    }
}
