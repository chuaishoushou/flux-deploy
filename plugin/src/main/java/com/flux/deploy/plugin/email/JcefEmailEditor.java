package com.flux.deploy.plugin.email;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.util.Disposer;
import com.intellij.ui.jcef.JBCefBrowser;
import com.intellij.ui.jcef.JBCefBrowserBase;
import com.intellij.ui.jcef.JBCefJSQuery;
import org.cef.browser.CefBrowser;
import org.cef.browser.CefFrame;
import org.cef.handler.CefLoadHandlerAdapter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.JComponent;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * JCEF 嵌入的"可编辑"邮件编辑器 —— 直接在弹窗里 WYSIWYG 改模板
 *
 * <p>核心特性：</p>
 * <ul>
 *   <li>contenteditable HTML 页：所见即所得，渲染颜色 / 字体 / 字号</li>
 *   <li>模板里的 {@code ${字段名}} 自动渲染为不可编辑的 chip（带 .ph 样式），
 *       chip 显示对应字段值（空值时显示 {@code ${字段名}} 字面 + 暖灰斜体）</li>
 *   <li>保存模板：chip 还原成 {@code ${字段名}} 字面，其余 DOM 原样回写</li>
 *   <li>复制到剪贴板：chip 脱壳为内部值，输出最终 HTML</li>
 *   <li>导入：In-place 改对应 chip 的可见值，不重新加载（保留用户已经做的编辑）</li>
 * </ul>
 *
 * <p>不再依赖 wangEditor / Slate 等任何富文本库 —— 纯 contenteditable + 一段 JS。
 * 复制到企业微信邮箱时跟模板源串字节一致（除占位符已替换为字段值）。</p>
 *
 * @author xumanyi
 * @date 2026-05-18
 */
public final class JcefEmailEditor implements Disposable {

    private static final Logger LOG = Logger.getInstance(JcefEmailEditor.class);

    /** 取 HTML 的默认超时——避免 JS 异常时调用方挂起 */
    private static final long DEFAULT_REQUEST_TIMEOUT_MS = 2000L;

    private final JBCefBrowser browser;
    private final JBCefJSQuery bridge;
    /** 等待 JS 端 ready 握手 */
    private final CompletableFuture<Void> readyFuture = new CompletableFuture<>();
    /** 待处理的 request 调用，requestId → future */
    private final ConcurrentHashMap<Long, CompletableFuture<String>> pendingRequests = new ConcurrentHashMap<>();
    /** 请求 id 生成器 */
    private final AtomicLong requestIdSeq = new AtomicLong(0);
    /** 缓存上一次 template snapshot —— ready 前调 setTemplate 缓存在这里，ready 后下发 */
    @Nullable
    private volatile PendingTemplate pendingTemplate = null;
    /** 最近一次拿到的"模板形态" HTML —— 超时 / 异常时兜底 */
    private volatile String lastKnownTemplate = "";

    private static final class PendingTemplate {
        final String templateHtml;
        final Map<String, String> values;
        PendingTemplate(String templateHtml, Map<String, String> values) {
            this.templateHtml = templateHtml;
            this.values = values;
        }
    }

    /**
     * 构造编辑器
     *
     * @param parent 父级 Disposable
     * @author xumanyi
     * @date 2026-05-18
     */
    public JcefEmailEditor(@NotNull Disposable parent) {
        this.browser = new JBCefBrowser();
        Disposer.register(parent, this);

        this.bridge = JBCefJSQuery.create((JBCefBrowserBase) browser);
        this.bridge.addHandler(this::handleJsMessage);
        Disposer.register(this, this.bridge);

        // 页面加载完成后注入 fluxBridge
        String injectionJs =
                "window.fluxBridge = function(request, onSuccess, onFailure) {"
                        + bridge.inject(
                                "request",
                                "function(response) { if (typeof onSuccess === 'function') onSuccess(response); }",
                                "function(error_code, error_message) { if (typeof onFailure === 'function') onFailure(error_code, error_message); }")
                        + "};";

        browser.getJBCefClient().addLoadHandler(new CefLoadHandlerAdapter() {
            @Override
            public void onLoadEnd(CefBrowser cb, CefFrame frame, int httpStatusCode) {
                if (frame != null && frame.getParent() == null) {
                    cb.executeJavaScript(injectionJs, frame.getURL(), 0);
                }
            }
        }, browser.getCefBrowser());

        try {
            browser.loadHTML(buildEditorHtml());
        } catch (IOException e) {
            throw new IllegalStateException("加载邮件编辑器资源失败: " + e.getMessage(), e);
        }
    }

    /**
     * Swing 组件
     *
     * @return JBCefBrowser 的 component
     * @author xumanyi
     * @date 2026-05-18
     */
    @NotNull
    public JComponent getComponent() {
        return browser.getComponent();
    }

    /**
     * 注入模板 + 字段值 —— ready 之前调用会缓存，握手后自动下发
     *
     * @param templateHtml 模板源串（含 {@code ${字段名}} 占位符）
     * @param values       字段值字典
     * @author xumanyi
     * @date 2026-05-18
     */
    public void setTemplate(@Nullable String templateHtml, @Nullable Map<String, String> values) {
        String tpl = templateHtml == null ? "" : templateHtml;
        this.lastKnownTemplate = tpl;
        if (!readyFuture.isDone()) {
            this.pendingTemplate = new PendingTemplate(tpl, values);
            return;
        }
        executeSetTemplate(tpl, values);
    }

    /**
     * 更新若干字段值（in-place 改 chip 内容，不重新加载整个编辑器内容）
     *
     * @param values 字段值
     * @author xumanyi
     * @date 2026-05-18
     */
    public void updateChips(@NotNull Map<String, String> values) {
        if (!readyFuture.isDone()) {
            // 还没 ready：把更新合并到 pendingTemplate 的 values（覆盖同 key）
            PendingTemplate pt = this.pendingTemplate;
            if (pt != null && pt.values != null) {
                pt.values.putAll(values);
            }
            return;
        }
        String js = "if (window.__fluxEmail && window.__fluxEmail.updateChips) {"
                + "window.__fluxEmail.updateChips(" + toJsObject(values) + ");"
                + "}";
        browser.getCefBrowser().executeJavaScript(js, "", 0);
    }

    /**
     * 异步取当前编辑器内容的"模板形态"（chip→{@code ${字段名}}），用于保存模板
     *
     * @return future（超时回落到 {@link #lastKnownTemplate}）
     * @author xumanyi
     * @date 2026-05-18
     */
    @NotNull
    public CompletableFuture<String> requestTemplateSource() {
        return request("requestTemplateSource", DEFAULT_REQUEST_TIMEOUT_MS, lastKnownTemplate);
    }

    /**
     * 异步取当前编辑器内容的"渲染形态"（chip→内部值），用于复制到剪贴板
     *
     * @return future（超时回落到 {@link #lastKnownTemplate}）
     * @author xumanyi
     * @date 2026-05-18
     */
    @NotNull
    public CompletableFuture<String> requestRenderedHtml() {
        return request("requestRenderedHtml", DEFAULT_REQUEST_TIMEOUT_MS, lastKnownTemplate);
    }

    /**
     * 同步取（限时阻塞）—— 给"关闭 / 保存 / 复制"等同步路径用
     *
     * @param kind      {@code "template"} 或 {@code "rendered"}
     * @param timeoutMs 超时
     * @return body HTML
     * @author xumanyi
     * @date 2026-05-18
     */
    @NotNull
    public String requestBlocking(@NotNull String kind, long timeoutMs) {
        CompletableFuture<String> f = "rendered".equals(kind)
                ? requestRenderedHtml() : requestTemplateSource();
        try {
            return f.get(timeoutMs + 100, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            LOG.warn("request HTML 阻塞获取超时 / 异常，回落到缓存", e);
            return lastKnownTemplate;
        }
    }

    @Override
    public void dispose() {
        if (browser != null && !browser.isDisposed()) {
            browser.dispose();
        }
    }

    // ==================== 内部 ====================

    private CompletableFuture<String> request(String jsFnName, long timeoutMs, String fallback) {
        long id = requestIdSeq.incrementAndGet();
        CompletableFuture<String> future = new CompletableFuture<>();
        pendingRequests.put(id, future);
        String js = "if (window.__fluxEmail && window.__fluxEmail." + jsFnName + ") {"
                + "window.__fluxEmail." + jsFnName + "(" + id + ");"
                + "}";
        browser.getCefBrowser().executeJavaScript(js, "", 0);
        return future.orTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .exceptionally(ex -> {
                    pendingRequests.remove(id);
                    if (ex instanceof TimeoutException || ex.getCause() instanceof TimeoutException) {
                        LOG.warn(jsFnName + " 超时（" + timeoutMs + " ms），回落缓存");
                    } else {
                        LOG.warn(jsFnName + " 异常，回落缓存", ex);
                    }
                    return fallback;
                });
    }

    private JBCefJSQuery.Response handleJsMessage(String payload) {
        try {
            String kind = extractStringField(payload, "kind");
            if ("ready".equals(kind)) {
                onJsReady();
            } else if ("template".equals(kind) || "rendered".equals(kind)) {
                long id = extractLongField(payload, "id");
                String body = extractStringField(payload, "body");
                if ("template".equals(kind) && body != null) {
                    lastKnownTemplate = body;
                }
                CompletableFuture<String> f = pendingRequests.remove(id);
                if (f != null && !f.isDone()) {
                    f.complete(body == null ? "" : body);
                }
            }
        } catch (Exception e) {
            LOG.warn("解析 JS 消息失败: " + payload, e);
        }
        return new JBCefJSQuery.Response(null);
    }

    private void onJsReady() {
        readyFuture.complete(null);
        PendingTemplate pt = this.pendingTemplate;
        if (pt != null) {
            this.pendingTemplate = null;
            executeSetTemplate(pt.templateHtml, pt.values);
        }
    }

    private void executeSetTemplate(String templateHtml, Map<String, String> values) {
        String tplEscaped = escapeJsString(templateHtml);
        String valuesJs = toJsObject(values);
        String js = "if (window.__fluxEmail && window.__fluxEmail.setTemplate) {"
                + "window.__fluxEmail.setTemplate('" + tplEscaped + "', " + valuesJs + ");"
                + "}";
        browser.getCefBrowser().executeJavaScript(js, "", 0);
    }

    // ==================== 资源 / JSON / 转义 工具 ====================

    /**
     * 把 Quill 静态资源 inline 进 index.html 的 {{QUILL_CSS}} / {{QUILL_JS}} 占位符
     *
     * <p>整页一次性 loadHTML 加载，避免 file:// 跨域 / 资源解析问题。</p>
     *
     * @return 完整 HTML 字符串
     * @throws IOException 资源缺失
     * @author xumanyi
     * @date 2026-05-18
     */
    private static String buildEditorHtml() throws IOException {
        String tpl = readResource("/email-editor/index.html");
        String css = readResource("/email-editor/quill.snow.css");
        String js = readResource("/email-editor/quill.min.js");
        return tpl
                .replace("{{QUILL_CSS}}", css)
                .replace("{{QUILL_JS}}", js);
    }

    private static String readResource(String path) throws IOException {
        try (InputStream in = JcefEmailEditor.class.getResourceAsStream(path)) {
            if (in == null) throw new IOException("资源未找到: " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** 把 Map 转成 JS 字面对象 {key: 'val', ...}（key/val 都做 JS 字符串 escape） */
    private static String toJsObject(@Nullable Map<String, String> values) {
        if (values == null || values.isEmpty()) return "{}";
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, String> e : values.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            sb.append('\'').append(escapeJsString(e.getKey())).append('\'')
                    .append(":'").append(escapeJsString(e.getValue() == null ? "" : e.getValue())).append('\'');
        }
        sb.append('}');
        return sb.toString();
    }

    private static String escapeJsString(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\': sb.append("\\\\"); break;
                case '\'': sb.append("\\'"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                default: sb.append(c);
            }
        }
        return sb.toString();
    }

    private static final Pattern STR_FIELD = Pattern.compile(
            "\"([^\"]+)\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");
    private static final Pattern NUM_FIELD = Pattern.compile(
            "\"([^\"]+)\"\\s*:\\s*(-?\\d+)");

    @Nullable
    private static String extractStringField(String json, String key) {
        Matcher m = STR_FIELD.matcher(json);
        while (m.find()) {
            if (key.equals(m.group(1))) return unescapeJson(m.group(2));
        }
        return null;
    }

    private static long extractLongField(String json, String key) {
        Matcher m = NUM_FIELD.matcher(json);
        while (m.find()) {
            if (key.equals(m.group(1))) {
                try { return Long.parseLong(m.group(2)); } catch (NumberFormatException ignored) { return -1; }
            }
        }
        return -1;
    }

    private static String unescapeJson(String s) {
        if (s == null) return null;
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char next = s.charAt(i + 1);
                switch (next) {
                    case '"': sb.append('"'); i++; break;
                    case '\\': sb.append('\\'); i++; break;
                    case '/': sb.append('/'); i++; break;
                    case 'n': sb.append('\n'); i++; break;
                    case 'r': sb.append('\r'); i++; break;
                    case 't': sb.append('\t'); i++; break;
                    case 'u':
                        if (i + 5 < s.length()) {
                            try {
                                int code = Integer.parseInt(s.substring(i + 2, i + 6), 16);
                                sb.append((char) code);
                                i += 5;
                            } catch (NumberFormatException ex) { sb.append(c); }
                        } else { sb.append(c); }
                        break;
                    default: sb.append(c);
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
