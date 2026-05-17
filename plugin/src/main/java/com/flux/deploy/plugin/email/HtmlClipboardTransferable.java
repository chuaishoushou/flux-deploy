package com.flux.deploy.plugin.email;

import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * 同时提供 {@code text/html} 与 {@code text/plain} 两种 flavor 的剪贴板载体
 *
 * <p>用于「复制到剪贴板」：粘到 Outlook / Foxmail / Web 邮箱 的富文本输入区
 * 会优先取 {@code text/html} 保留加粗、换行、颜色；粘到纯文本区域（IDE 编辑器、
 * 微信、终端）则取 {@code text/plain}。</p>
 *
 * <p>HTML flavor 的 MIME 类型用 {@code "text/html;class=java.lang.String"}，
 * Java 剪贴板对 String 类型的 HTML 在 macOS / Windows / Linux 三端表现一致。</p>
 *
 * @author claude
 * @date 2026-05-17
 */
public final class HtmlClipboardTransferable implements Transferable {

    /** HTML flavor (String) —— Java app / 多数富文本控件能取 */
    private static final DataFlavor HTML_STRING_FLAVOR;
    /** HTML flavor (InputStream + UTF-8) —— macOS 原生 / Outlook 等更挑剔的接收端用 */
    private static final DataFlavor HTML_STREAM_FLAVOR;

    static {
        try {
            HTML_STRING_FLAVOR = new DataFlavor("text/html;class=java.lang.String");
            HTML_STREAM_FLAVOR = new DataFlavor(
                    "text/html;class=java.io.InputStream;charset=UTF-8");
        } catch (ClassNotFoundException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    /**
     * 支持的 flavor 列表（HTML 在前；接收端按列表顺序匹配第一个能用的）。
     *
     * <p>顺序：InputStream-HTML → String-HTML → 纯文本。macOS 上原生应用通常优先
     * 取 InputStream 形式（对应 {@code public.html} UTI），Java 应用则用 String 形式。</p>
     */
    private static final DataFlavor[] SUPPORTED = new DataFlavor[]{
            HTML_STREAM_FLAVOR,
            HTML_STRING_FLAVOR,
            DataFlavor.stringFlavor,
    };

    private final String html;
    private final String plain;

    /**
     * 构造剪贴板载体
     *
     * @param html  富文本 HTML 串（永不为 null，调用方传 null 会被规范化为空串）
     * @param plain 纯文本 fallback（通常由 {@link #stripHtml(String)} 从 HTML 提取）
     * @author claude
     * @date 2026-05-17
     */
    public HtmlClipboardTransferable(String html, String plain) {
        this.html = html == null ? "" : html;
        this.plain = plain == null ? "" : plain;
    }

    /**
     * 便捷构造：仅传 HTML，自动派生 plain text fallback
     *
     * @param html 富文本 HTML 串
     * @author claude
     * @date 2026-05-17
     */
    public HtmlClipboardTransferable(String html) {
        this(html, stripHtml(html));
    }

    @Override
    public DataFlavor[] getTransferDataFlavors() {
        return SUPPORTED.clone();
    }

    @Override
    public boolean isDataFlavorSupported(DataFlavor flavor) {
        if (flavor == null) {
            return false;
        }
        for (DataFlavor f : SUPPORTED) {
            if (f.equals(flavor)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public Object getTransferData(DataFlavor flavor) throws UnsupportedFlavorException {
        if (HTML_STRING_FLAVOR.equals(flavor)) {
            return html;
        }
        if (HTML_STREAM_FLAVOR.equals(flavor)) {
            return htmlInputStream();
        }
        if (DataFlavor.stringFlavor.equals(flavor)) {
            return plain;
        }
        throw new UnsupportedFlavorException(flavor);
    }

    /**
     * 把 HTML 串包成 UTF-8 字节流（供 InputStream flavor 使用）。
     *
     * <p>每次 {@code getTransferData} 调用都返回新流——接收方可能多次取，
     * 不能共享同一个 stream 实例（读完一次就 EOF 了）。</p>
     *
     * @return 字节输入流
     * @author claude
     * @date 2026-05-17
     */
    private InputStream htmlInputStream() {
        return new ByteArrayInputStream(html.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 把 HTML 串简单转成纯文本：去标签、把 {@code <br>} / {@code </p>} 转换行，
     * 还原常见 entity ({@code &nbsp; &amp; &lt; &gt; &quot;})
     *
     * <p>不调用真正的 HTML 解析器（避免引入 Jsoup 依赖），对本插件生成的简单 HTML
     * 够用。用户自己加了复杂 HTML 标签（{@code <table>} 等）时纯文本展示可能不完美，
     * 但富文本 flavor 仍然完整可用。</p>
     *
     * @param html HTML 串（{@code null} 视作空串）
     * @return 纯文本
     * @author claude
     * @date 2026-05-17
     */
    static String stripHtml(String html) {
        if (html == null) {
            return "";
        }
        String s = html;
        // 块级 / 换行标签 → 真换行符
        s = s.replaceAll("(?i)<br\\s*/?>", "\n");
        s = s.replaceAll("(?i)</p\\s*>", "\n");
        s = s.replaceAll("(?i)</div\\s*>", "\n");
        s = s.replaceAll("(?i)</li\\s*>", "\n");
        // 去掉所有剩余标签
        s = s.replaceAll("<[^>]+>", "");
        // 还原常见 entity
        s = s.replace("&nbsp;", " ")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&amp;", "&");
        return s;
    }
}
