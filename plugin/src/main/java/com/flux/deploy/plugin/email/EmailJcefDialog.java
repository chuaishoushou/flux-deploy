package com.flux.deploy.plugin.email;

import com.flux.deploy.email.EmailTemplateStore;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.util.Disposer;
import com.intellij.ui.jcef.JBCefBrowser;
import com.intellij.ui.jcef.JBCefBrowserBase;
import com.intellij.ui.jcef.JBCefJSQuery;
import org.cef.browser.CefBrowser;
import org.cef.browser.CefFrame;
import org.cef.handler.CefLoadHandlerAdapter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.Action;
import javax.swing.JComponent;
import java.awt.Dimension;
import java.awt.Toolkit;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 邮件模板编辑器的 JCEF 内嵌宿主对话框。
 *
 * <p>把打磨好的 Quill SPA（{@code /email-web-editor/}）整页 {@code loadHTML} 进 JCEF，
 * 数据通道用 {@link JBCefJSQuery} 桥替代原来的本地 HTTP server + REST：前端 app.js 调
 * {@code window.fluxBridge({op,...})}，本类 {@link #handleBridge} 按 {@code op} 分发到
 * {@link EmailTemplateStore}（模板 CRUD）、运行时数据供给者（导入数据）和
 * {@link HtmlClipboardTransferable}（复制到系统剪贴板）。响应 JSON 的结构与原 REST 端点一致，
 * 因此 app.js 上层的 apiListTemplates / apiLoadTemplate / ... 无需改动。</p>
 *
 * <p><b>资源</b>：quill.snow.css / app.css / quill.min.js / app.js 全部内联进一次
 * {@code loadHTML}，JCEF 离线可用，不依赖任何相对路径或 base URL。</p>
 *
 * <p><b>启动时序</b>：app.js 加载后把启动入口挂到 {@code window.__fluxBootstrap} 而不自启动；
 * 页面 {@code onLoadEnd} 时本类先注入 {@code window.fluxBridge}，紧接着调用
 * {@code __fluxBootstrap()}，保证 bootstrap 拉数据时桥已就绪。</p>
 *
 * <p>JCEF 可用性由调用方（{@code DeployToolWindowPanel.openEmailDialog()}）用
 * {@code JBCefApp.isSupported()} 守卫，本类构造前提是 JCEF 可用。</p>
 *
 * @author xumanyi
 * @date 2026-05-29
 */
public final class EmailJcefDialog extends DialogWrapper {

    private static final Logger LOG = Logger.getInstance(EmailJcefDialog.class);

    /** SPA 资源根（classpath） */
    private static final String RESOURCE_ROOT = "/email-web-editor";

    private final EmailTemplateStore store;
    private final Supplier<Map<String, String>> runtimeDataSupplier;
    private final JBCefBrowser browser;
    private final JBCefJSQuery bridge;

    /**
     * 构造并初始化对话框（非模态，用户可同时操作 IDE 主面板）。
     *
     * @param project             当前项目；用于对话框归属，可为 null
     * @param store               模板存储（list / load / save / delete / restore）
     * @param runtimeDataSupplier 运行时变量值供给者（「导入数据」按钮拉取）
     * @author xumanyi
     * @date 2026-05-29
     */
    public EmailJcefDialog(@Nullable Project project,
                           @NotNull EmailTemplateStore store,
                           @NotNull Supplier<Map<String, String>> runtimeDataSupplier) {
        super(project);
        this.store = store;
        this.runtimeDataSupplier = runtimeDataSupplier;
        this.browser = new JBCefBrowser();
        Disposer.register(getDisposable(), browser);
        this.bridge = JBCefJSQuery.create((JBCefBrowserBase) browser);
        this.bridge.addHandler(this::handleBridge);
        Disposer.register(browser, bridge);

        installBridgeAndLoad();

        setTitle("通知邮件");
        setModal(false);
        init();
    }

    /**
     * 注册页面加载完成回调（注入桥 + 触发 bootstrap），并整页 loadHTML。
     *
     * @author xumanyi
     * @date 2026-05-29
     */
    private void installBridgeAndLoad() {
        // onLoadEnd 时执行：先定义 window.fluxBridge（JBCefJSQuery.inject 生成的 query 调用），
        // 再调用 app.js 暴露的 __fluxBootstrap()，保证 bootstrap 拉数据时桥已就绪。
        final String startupJs =
                "window.fluxBridge = function(request, onSuccess, onFailure) {"
                        + bridge.inject(
                                "request",
                                "function(response) { if (typeof onSuccess === 'function') onSuccess(response); }",
                                "function(error_code, error_message) { if (typeof onFailure === 'function') onFailure(error_code, error_message); }")
                        + "};\n"
                        + "if (typeof window.__fluxBootstrap === 'function') window.__fluxBootstrap();";

        browser.getJBCefClient().addLoadHandler(new CefLoadHandlerAdapter() {
            @Override
            public void onLoadEnd(CefBrowser cb, CefFrame frame, int httpStatusCode) {
                // 只在主 frame（无父 frame）注入一次
                if (frame != null && frame.getParent() == null) {
                    cb.executeJavaScript(startupJs, frame.getURL(), 0);
                }
            }
        }, browser.getCefBrowser());

        try {
            browser.loadHTML(buildEditorHtml());
        } catch (IOException e) {
            throw new IllegalStateException("加载邮件编辑器资源失败: " + e.getMessage(), e);
        }
    }

    @Override
    protected JComponent createCenterPanel() {
        JComponent comp = browser.getComponent();
        comp.setPreferredSize(new Dimension(980, 720));
        return comp;
    }

    @Override
    protected Action[] createActions() {
        // SPA 自带全部操作（新建 / 保存 / 复制 / 导入 / 删除 / 恢复），底部只留一个「关闭」。
        Action close = getCancelAction();
        close.putValue(Action.NAME, "关闭");
        return new Action[]{ close };
    }

    @Override
    protected String getDimensionServiceKey() {
        // 记住用户调整后的窗口大小 / 位置
        return "com.flux.deploy.plugin.email.EmailJcefDialog";
    }

    // ──────────────────────────────────────────────────────────────────
    //   JS 桥分发
    // ──────────────────────────────────────────────────────────────────

    /**
     * 处理来自 app.js 的桥消息，按 {@code op} 分发，返回 JSON 字符串（结构与原 REST 一致）。
     *
     * @param payload JSON 串，形如 {@code {"op":"load","name":"default"}}
     * @return 给前端 onSuccess 的响应；出错返回 {@code {"error":"..."}}
     * @author xumanyi
     * @date 2026-05-29
     */
    private JBCefJSQuery.Response handleBridge(@Nullable String payload) {
        try {
            String op = extractJsonField(payload, "op");
            if (op == null) {
                return new JBCefJSQuery.Response(errorJson("缺少 op"));
            }
            switch (op) {
                case "list":
                    return new JBCefJSQuery.Response(handleList());
                case "load":
                    return new JBCefJSQuery.Response(handleLoad(extractJsonField(payload, "name")));
                case "save":
                    return new JBCefJSQuery.Response(
                            handleSave(extractJsonField(payload, "name"),
                                    extractJsonField(payload, "content")));
                case "delete":
                    return new JBCefJSQuery.Response(handleDelete(extractJsonField(payload, "name")));
                case "restore":
                    return new JBCefJSQuery.Response(handleRestore(extractJsonField(payload, "name")));
                case "runtimeData":
                    return new JBCefJSQuery.Response(handleRuntimeData());
                case "copyToClipboard":
                    return new JBCefJSQuery.Response(
                            handleCopy(extractJsonField(payload, "html"),
                                    extractJsonField(payload, "plain")));
                default:
                    return new JBCefJSQuery.Response(errorJson("未知 op: " + op));
            }
        } catch (Exception e) {
            LOG.warn("邮件编辑器桥处理失败: " + payload, e);
            return new JBCefJSQuery.Response(errorJson(e.getMessage()));
        }
    }

    /**
     * {@code op=list}：列模板名 + 默认模板名。
     *
     * @return {@code {"templates":[...],"defaultName":"default"}}
     * @throws IOException 列目录失败
     * @author xumanyi
     * @date 2026-05-29
     */
    private String handleList() throws IOException {
        store.ensureInitialized();
        List<String> names = store.listNames();
        StringBuilder sb = new StringBuilder("{\"templates\":[");
        for (int i = 0; i < names.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(jsonString(names.get(i)));
        }
        sb.append("],\"defaultName\":")
                .append(jsonString(EmailTemplateStore.DEFAULT_TEMPLATE_NAME))
                .append('}');
        return sb.toString();
    }

    /**
     * {@code op=load}：读取指定模板内容（不存在回落内置默认）。
     *
     * @param name 模板名
     * @return {@code {"name":...,"content":...}}
     * @author xumanyi
     * @date 2026-05-29
     */
    private String handleLoad(@Nullable String name) {
        String content = store.loadOrDefault(name);
        return "{\"name\":" + jsonString(name)
                + ",\"content\":" + jsonString(content) + "}";
    }

    /**
     * {@code op=save}：保存（覆盖）模板内容。
     *
     * @param name    模板名
     * @param content 模板源串
     * @return {@code {"ok":true}}；缺 content 返回 error
     * @throws IOException 写文件失败
     * @author xumanyi
     * @date 2026-05-29
     */
    private String handleSave(@Nullable String name, @Nullable String content) throws IOException {
        if (content == null) {
            return errorJson("missing content");
        }
        store.ensureInitialized();
        store.save(name, content);
        return "{\"ok\":true}";
    }

    /**
     * {@code op=delete}：删除模板（default 不可删，由 store 拦截）。
     *
     * @param name 模板名
     * @return {@code {"ok":true}}
     * @throws IOException 删除失败
     * @author xumanyi
     * @date 2026-05-29
     */
    private String handleDelete(@Nullable String name) throws IOException {
        store.ensureInitialized();
        store.delete(name);
        return "{\"ok\":true}";
    }

    /**
     * {@code op=restore}：恢复默认（default→内置出厂，其他→当前 default 内容），返回恢复后的内容。
     *
     * @param name 模板名
     * @return {@code {"name":...,"content":...}}
     * @throws IOException 写文件失败
     * @author xumanyi
     * @date 2026-05-29
     */
    private String handleRestore(@Nullable String name) throws IOException {
        store.ensureInitialized();
        store.restoreToBuiltinDefault(name);
        String content = store.loadOrDefault(name);
        return "{\"name\":" + jsonString(name)
                + ",\"content\":" + jsonString(content) + "}";
    }

    /**
     * {@code op=runtimeData}：拿运行时变量值快照（主面板 + 部署历史）。
     *
     * @return {@code {"任务":...,"客服":...,...}}；无数据时空 object
     * @author xumanyi
     * @date 2026-05-29
     */
    private String handleRuntimeData() {
        Map<String, String> values = runtimeDataSupplier.get();
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        if (values != null) {
            for (Map.Entry<String, String> e : values.entrySet()) {
                if (!first) sb.append(',');
                sb.append(jsonString(e.getKey())).append(':')
                        .append(jsonString(e.getValue() == null ? "" : e.getValue()));
                first = false;
            }
        }
        sb.append('}');
        return sb.toString();
    }

    /**
     * {@code op=copyToClipboard}：把渲染好的 HTML + 纯文本写系统剪贴板，供粘贴到企业微信邮件等。
     *
     * @param html  富文本 HTML（app.js serializeRendered 出，已脱壳 chip + 段首缩进）
     * @param plain 纯文本 fallback
     * @return {@code {"ok":true}}
     * @author xumanyi
     * @date 2026-05-29
     */
    private String handleCopy(@Nullable String html, @Nullable String plain) {
        HtmlClipboardTransferable transferable =
                new HtmlClipboardTransferable(html == null ? "" : html,
                        plain == null ? "" : plain);
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(transferable, null);
        return "{\"ok\":true}";
    }

    // ──────────────────────────────────────────────────────────────────
    //   资源内联 + JSON 工具
    // ──────────────────────────────────────────────────────────────────

    /**
     * 把 SPA 的 css / js 内联进 index.html（JCEF loadHTML 无 base URL，相对资源取不到）。
     *
     * <p>替换依赖 index.html 中四个标签的精确写法（quill.snow.css / app.css /
     * quill.min.js / app.js）；改动 index.html 标签写法时需同步这里。</p>
     *
     * @return 完整可独立运行的 HTML
     * @throws IOException 资源缺失
     * @author xumanyi
     * @date 2026-05-29
     */
    private static String buildEditorHtml() throws IOException {
        String html = readResource(RESOURCE_ROOT + "/index.html");
        String quillCss = readResource(RESOURCE_ROOT + "/quill.snow.css");
        String appCss = readResource(RESOURCE_ROOT + "/app.css");
        String quillJs = readResource(RESOURCE_ROOT + "/quill.min.js");
        String appJs = readResource(RESOURCE_ROOT + "/app.js");
        return html
                .replace("<link rel=\"stylesheet\" href=\"quill.snow.css\">",
                        "<style>\n" + quillCss + "\n</style>")
                .replace("<link rel=\"stylesheet\" href=\"app.css\">",
                        "<style>\n" + appCss + "\n</style>")
                .replace("<script src=\"quill.min.js\"></script>",
                        "<script>\n" + quillJs + "\n</script>")
                .replace("<script src=\"app.js\"></script>",
                        "<script>\n" + appJs + "\n</script>");
    }

    private static String readResource(@NotNull String path) throws IOException {
        try (InputStream in = EmailJcefDialog.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IOException("资源未找到: " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** error 信息包成 JSON object {@code {"error":"..."}} */
    private static String errorJson(@Nullable String message) {
        return "{\"error\":" + jsonString(message == null ? "未知错误" : message) + "}";
    }

    /**
     * 把 Java 字符串包成合法 JSON string 字面量（含开闭引号）。
     *
     * @param s 原串；null 输出 {@code null}
     * @return JSON 字符串字面量
     * @author xumanyi
     * @date 2026-05-29
     */
    private static String jsonString(@Nullable String s) {
        if (s == null) return "null";
        StringBuilder sb = new StringBuilder(s.length() + 8);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n");  break;
                case '\r': sb.append("\\r");  break;
                case '\t': sb.append("\\t");  break;
                case '\b': sb.append("\\b");  break;
                case '\f': sb.append("\\f");  break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
        return sb.toString();
    }

    /**
     * 极简 JSON 字段抽取：从 object 串取 {@code "field": "value"} 的字符串值（支持常见转义）。
     *
     * <p>app.js 端塞的都是平铺的 {@code {op,name,content,html,plain}} 简单 object，不含嵌套，
     * 这个手写抽取够用，避免引入 JSON 库。</p>
     *
     * @param body  JSON object 串
     * @param field 字段名
     * @return 字段值；不存在或非字符串返回 null
     * @author xumanyi
     * @date 2026-05-29
     */
    private static @Nullable String extractJsonField(@Nullable String body, @NotNull String field) {
        if (body == null) return null;
        String marker = "\"" + field + "\"";
        int i = body.indexOf(marker);
        if (i < 0) return null;
        i = body.indexOf(':', i + marker.length());
        if (i < 0) return null;
        i++;
        while (i < body.length() && Character.isWhitespace(body.charAt(i))) i++;
        if (i >= body.length() || body.charAt(i) != '"') return null;
        StringBuilder out = new StringBuilder();
        i++;
        while (i < body.length()) {
            char c = body.charAt(i);
            if (c == '\\' && i + 1 < body.length()) {
                char n = body.charAt(i + 1);
                switch (n) {
                    case '"':  out.append('"');  break;
                    case '\\': out.append('\\'); break;
                    case '/':  out.append('/');  break;
                    case 'n':  out.append('\n'); break;
                    case 'r':  out.append('\r'); break;
                    case 't':  out.append('\t'); break;
                    case 'b':  out.append('\b'); break;
                    case 'f':  out.append('\f'); break;
                    case 'u':
                        if (i + 5 < body.length()) {
                            String hex = body.substring(i + 2, i + 6);
                            try {
                                out.append((char) Integer.parseInt(hex, 16));
                                i += 4;
                            } catch (NumberFormatException nfe) {
                                out.append(n);
                            }
                        }
                        break;
                    default: out.append(n);
                }
                i += 2;
            } else if (c == '"') {
                return out.toString();
            } else {
                out.append(c);
                i++;
            }
        }
        return null;
    }
}
