package com.flux.deploy.plugin.email;

import com.flux.deploy.email.EmailTemplateStore;
import com.intellij.openapi.diagnostic.Logger;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 邮件模板「浏览器内编辑」用的本地 HTTP server。
 *
 * <p>替代当前 JCEF 嵌入式编辑器的方案：插件起一个绑定到 {@code 127.0.0.1} 的随机端口 server，
 * 浏览器打开 <code>http://127.0.0.1:&lt;port&gt;/#token=&lt;random&gt;</code>，SPA 通过 REST API
 * 拉模板 / 保存模板 / 拿运行时数据。所有 API 都强制校验 token，防同机其他进程蹭。</p>
 *
 * <p><b>生命周期</b>：由 {@link EmailWebServerService} 在主面板「邮件模版」按钮第一次点击时
 * lazy 启动；服务跟随项目级 Disposable 释放（IDE 或项目关闭时统一 shutdown）。
 * 同一会话内反复打开浏览器编辑器都复用同一个 server（同端口同 token）。</p>
 *
 * <p><b>安全</b>：</p>
 * <ul>
 *   <li>绑 {@code 127.0.0.1}，不对外网暴露</li>
 *   <li>启动时 {@link SecureRandom} 生成 16 字节 token，URL fragment 携带；前端 JS 从
 *       {@code location.hash} 提取后放入每个 HTTP 请求的 {@code X-Flux-Token} header</li>
 *   <li>所有 {@code /api/**} handler 在最前面校验 token，不匹配直接 401</li>
 *   <li>静态资源（HTML / JS / CSS）不校验 token，方便首次加载</li>
 * </ul>
 *
 * @author xumanyi
 * @date 2026-05-27
 */
public final class EmailWebServer {

    private static final Logger LOG = Logger.getInstance(EmailWebServer.class);

    /** classpath 下 SPA 资源根目录 */
    private static final String STATIC_ROOT = "/email-web-editor";
    /** dev 覆盖根目录：用户在这里放同名文件 → 服务器优先读这里。
     *  方便不重新打包 plugin 就能改 HTML/CSS/JS、刷新浏览器立即看到效果。 */
    private static final java.nio.file.Path DEV_OVERRIDE_ROOT = java.nio.file.Path.of(
            System.getProperty("user.home"), ".flux-deploy", "email-web-editor-dev")
            .toAbsolutePath().normalize();
    /** 鉴权 header 名 */
    private static final String AUTH_HEADER = "X-Flux-Token";

    private final HttpServer server;
    private final int port;
    private final String token;
    private final EmailTemplateStore store;
    private final Supplier<Map<String, String>> runtimeDataSupplier;
    private final java.util.concurrent.ExecutorService executor;

    /**
     * 启动 server，立即返回。失败抛 IOException 由调用方决定降级。
     *
     * @param store               模板存储（用于 templates CRUD）
     * @param runtimeDataSupplier 主面板 + 部署历史快照供给者（用于 /api/runtime-data）
     * @throws IOException server 起不来（端口耗尽 / 权限等）
     */
    public EmailWebServer(@NotNull EmailTemplateStore store,
                          @NotNull Supplier<Map<String, String>> runtimeDataSupplier)
            throws IOException {
        this.store = store;
        this.runtimeDataSupplier = runtimeDataSupplier;
        this.token = generateToken();
        // port=0 → OS 分配自由端口，避免冲突
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        this.port = server.getAddress().getPort();
        registerHandlers();
        // 显式线程池：默认 DefaultExecutor 单线程同步处理，请求互相阻塞、异常恢复也差；
        // cachedThreadPool 让每个请求独立线程，daemon=true 不阻塞 IDE 退出
        this.executor = java.util.concurrent.Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "flux-email-server-" + port);
            t.setDaemon(true);
            return t;
        });
        server.setExecutor(executor);
        server.start();
        LOG.info("EmailWebServer started at http://127.0.0.1:" + port);
    }

    /** 浏览器要打开的完整 URL：含一次性 token */
    public @NotNull URI getEditorUrl() {
        return URI.create("http://127.0.0.1:" + port + "/#token=" + token);
    }

    /** 关闭 server。多次调用安全。 */
    public void shutdown() {
        try {
            server.stop(0);
            LOG.info("EmailWebServer stopped at port " + port);
        } catch (Exception ignored) { }
        try {
            if (executor != null) executor.shutdownNow();
        } catch (Exception ignored) { }
    }

    // ──────────────────────────────────────────────────────────────────
    //   handlers
    // ──────────────────────────────────────────────────────────────────

    private void registerHandlers() {
        server.createContext("/", new StaticHandler());
        server.createContext("/api/templates", new TemplatesHandler());
        server.createContext("/api/runtime-data", new RuntimeDataHandler());
    }

    /**
     * 静态资源：根路径 → index.html。
     *
     * <p>查找顺序：</p>
     * <ol>
     *   <li>dev 覆盖目录 {@code ~/.flux-deploy/email-web-editor-dev/<path>}（用户改这里立即生效，无需重打包）</li>
     *   <li>classpath {@code /email-web-editor/<path>}（plugin jar 内置）</li>
     *   <li>classpath {@code /email-editor/<path>}（兼容老资源：quill.min.js / quill.snow.css）</li>
     * </ol>
     */
    private class StaticHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange ex) throws IOException {
            String path = ex.getRequestURI().getPath();
            if (path == null || path.isEmpty() || "/".equals(path)) {
                path = "/index.html";
            }
            String resource = STATIC_ROOT + path;
            // 跨子路径滥用防御
            if (!resource.startsWith(STATIC_ROOT + "/") || resource.contains("..")) {
                respond(ex, 404, "text/plain", "Not Found");
                return;
            }

            // 1) dev 覆盖：先看 ~/.flux-deploy/email-web-editor-dev/<file>
            //    路径归一化后必须仍在 dev root 下，防 ../../etc/passwd 之类穿越
            try {
                String relPath = path.startsWith("/") ? path.substring(1) : path;
                java.nio.file.Path devPath = DEV_OVERRIDE_ROOT.resolve(relPath).normalize();
                if (devPath.startsWith(DEV_OVERRIDE_ROOT)
                        && java.nio.file.Files.isRegularFile(devPath)) {
                    byte[] bytes = java.nio.file.Files.readAllBytes(devPath);
                    respond(ex, 200, contentTypeFor(path), bytes);
                    return;
                }
            } catch (Exception devErr) {
                // dev 路径异常不阻断主流程，继续走 classpath
                LOG.debug("dev override lookup failed for " + path + ": " + devErr.getMessage());
            }

            // 2) classpath（plugin jar 内置）
            try (InputStream in = EmailWebServer.class.getResourceAsStream(resource)) {
                if (in == null) {
                    // 3) fallback：复用主 email-editor 目录的 quill 资源
                    String fallback = "/email-editor" + path;
                    try (InputStream fb = EmailWebServer.class.getResourceAsStream(fallback)) {
                        if (fb == null) {
                            respond(ex, 404, "text/plain", "Not Found: " + path);
                            return;
                        }
                        byte[] bytes = readAll(fb);
                        respond(ex, 200, contentTypeFor(path), bytes);
                        return;
                    }
                }
                byte[] bytes = readAll(in);
                respond(ex, 200, contentTypeFor(path), bytes);
            }
        }
    }

    /** REST：模板列表 / 加载 / 保存 / 删除 / 恢复默认 */
    private class TemplatesHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange ex) throws IOException {
            if (!checkAuth(ex)) return;
            String method = ex.getRequestMethod();
            String path = ex.getRequestURI().getPath();
            // /api/templates           → GET list
            // /api/templates/{name}    → GET load / PUT save / DELETE
            // /api/templates/{name}/restore  → POST 恢复默认
            try {
                if ("/api/templates".equals(path) && "GET".equalsIgnoreCase(method)) {
                    handleList(ex);
                } else if (path.startsWith("/api/templates/")) {
                    String rest = path.substring("/api/templates/".length());
                    if (rest.endsWith("/restore")) {
                        String name = urlDecode(rest.substring(0, rest.length() - "/restore".length()));
                        if ("POST".equalsIgnoreCase(method)) {
                            handleRestore(ex, name);
                        } else {
                            respond(ex, 405, "text/plain", "Method Not Allowed");
                        }
                    } else {
                        String name = urlDecode(rest);
                        switch (method.toUpperCase()) {
                            case "GET":    handleLoad(ex, name); break;
                            case "PUT":    handleSave(ex, name); break;
                            case "DELETE": handleDelete(ex, name); break;
                            default: respond(ex, 405, "text/plain", "Method Not Allowed");
                        }
                    }
                } else {
                    respond(ex, 404, "text/plain", "Not Found");
                }
            } catch (Exception err) {
                LOG.warn("templates handler error", err);
                respond(ex, 500, "application/json",
                        "{\"error\":" + jsonString(String.valueOf(err.getMessage())) + "}");
            }
        }

        private void handleList(HttpExchange ex) throws IOException {
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
            respond(ex, 200, "application/json", sb.toString());
        }

        private void handleLoad(HttpExchange ex, String name) throws IOException {
            store.ensureInitialized();
            String content = store.loadOrDefault(name);
            String body = "{\"name\":" + jsonString(name)
                    + ",\"content\":" + jsonString(content) + "}";
            respond(ex, 200, "application/json", body);
        }

        private void handleSave(HttpExchange ex, String name) throws IOException {
            String body = readRequestBody(ex);
            // 请求体 JSON：{"content": "<html...>"}
            String content = extractJsonField(body, "content");
            if (content == null) {
                respond(ex, 400, "application/json", "{\"error\":\"missing content\"}");
                return;
            }
            store.ensureInitialized();
            store.save(name, content);
            respond(ex, 200, "application/json", "{\"ok\":true}");
        }

        private void handleDelete(HttpExchange ex, String name) throws IOException {
            store.ensureInitialized();
            store.delete(name);
            respond(ex, 200, "application/json", "{\"ok\":true}");
        }

        private void handleRestore(HttpExchange ex, String name) throws IOException {
            store.ensureInitialized();
            store.restoreToBuiltinDefault(name);
            // 顺手返回恢复后的内容，前端拿到直接渲染
            String content = store.loadOrDefault(name);
            String resp = "{\"name\":" + jsonString(name)
                    + ",\"content\":" + jsonString(content) + "}";
            respond(ex, 200, "application/json", resp);
        }
    }

    /** REST：拿运行时数据快照（主面板 + 部署历史的 7 个变量值） */
    private class RuntimeDataHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange ex) throws IOException {
            if (!checkAuth(ex)) return;
            if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
                respond(ex, 405, "text/plain", "Method Not Allowed");
                return;
            }
            try {
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
                respond(ex, 200, "application/json", sb.toString());
            } catch (Exception err) {
                LOG.warn("runtime-data handler error", err);
                respond(ex, 500, "application/json",
                        "{\"error\":" + jsonString(String.valueOf(err.getMessage())) + "}");
            }
        }
    }

    // ──────────────────────────────────────────────────────────────────
    //   helpers
    // ──────────────────────────────────────────────────────────────────

    private boolean checkAuth(HttpExchange ex) throws IOException {
        String got = ex.getRequestHeaders().getFirst(AUTH_HEADER);
        if (token.equals(got)) return true;
        respond(ex, 401, "application/json", "{\"error\":\"unauthorized\"}");
        return false;
    }

    private static void respond(HttpExchange ex, int status,
                                String contentType, String body) throws IOException {
        respond(ex, status, contentType, body.getBytes(StandardCharsets.UTF_8));
    }

    private static void respond(HttpExchange ex, int status,
                                String contentType, byte[] body) throws IOException {
        ex.getResponseHeaders().add("Content-Type", contentType + "; charset=UTF-8");
        ex.getResponseHeaders().add("Cache-Control", "no-store");
        ex.sendResponseHeaders(status, body.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(body);
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return out.toByteArray();
    }

    private static String readRequestBody(HttpExchange ex) throws IOException {
        try (InputStream in = ex.getRequestBody()) {
            return new String(readAll(in), StandardCharsets.UTF_8);
        }
    }

    private static String contentTypeFor(String path) {
        String lower = path.toLowerCase();
        if (lower.endsWith(".html")) return "text/html";
        if (lower.endsWith(".js"))   return "application/javascript";
        if (lower.endsWith(".css"))  return "text/css";
        if (lower.endsWith(".svg"))  return "image/svg+xml";
        if (lower.endsWith(".png"))  return "image/png";
        if (lower.endsWith(".json")) return "application/json";
        return "application/octet-stream";
    }

    private static String urlDecode(String raw) {
        return URLDecoder.decode(raw, StandardCharsets.UTF_8);
    }

    /**
     * 极简 JSON 字段抽取：{@code body} 是 JSON object，返回 {@code "field": ...} 后的字符串值。
     * 不做嵌套对象支持；调用方 SPA 端只塞简单 {content: string} payload，足够。
     */
    private static @Nullable String extractJsonField(String body, String field) {
        if (body == null) return null;
        String marker = "\"" + field + "\"";
        int i = body.indexOf(marker);
        if (i < 0) return null;
        i = body.indexOf(':', i + marker.length());
        if (i < 0) return null;
        i++;
        while (i < body.length() && Character.isWhitespace(body.charAt(i))) i++;
        if (i >= body.length() || body.charAt(i) != '"') return null;
        // 从开引号下一个字符开始逐字解析（支持 \", \\ 等转义）
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
        return null;  // 没遇到闭引号
    }

    /** 把 Java 字符串包成合法 JSON string 字面量（含开闭引号） */
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

    /** error 信息嵌入 JSON 用的简易 escape（不加外层引号） */
    private static String escapeForJson(@Nullable String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String generateToken() {
        byte[] bytes = new byte[16];
        new SecureRandom().nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }
}
