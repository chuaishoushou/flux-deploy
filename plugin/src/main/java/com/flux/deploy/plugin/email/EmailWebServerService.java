package com.flux.deploy.plugin.email;

import com.flux.deploy.email.EmailTemplateStore;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.net.URI;
import java.util.Collections;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 邮件浏览器编辑器 server 的 **项目级单例**。
 *
 * <p>第一次有用户点主面板「邮件模版」按钮时 lazy 启动一个 {@link EmailWebServer}，
 * 后续不论关 / 开浏览器多少次都复用同一个 server（同一个端口、同一个 token）。
 * 这样浏览器里的标签页**始终能跟插件通信**，不会出现"重新点按钮 → 旧浏览器页面立刻 500/连接失败"。</p>
 *
 * <p><b>生命周期</b>：随项目级 Disposable 释放，IDE 关闭 / 项目关闭时自动 shutdown。</p>
 *
 * <p><b>运行时数据来源</b>：每次进入"邮件模版"流程时由 {@code DeployToolWindowPanel.openEmailDialog()}
 * 调 {@link #updateDataSupplier} 把"当前最新的变量值快照来源"换上，供 {@code /api/runtime-data}
 * 实时拉取。这样浏览器里点「导入」拿到的永远是**当下主面板 + 部署历史的最新快照**。</p>
 *
 * @author xumanyi
 * @date 2026-05-27
 */
@Service(Service.Level.PROJECT)
public final class EmailWebServerService implements Disposable {

    private static final Logger LOG = Logger.getInstance(EmailWebServerService.class);

    /** 当前"运行时变量值"供给者（由 dialog 注入）；可能为 null（没 dialog 时）。 */
    private volatile Supplier<Map<String, String>> dataSupplier;
    /** 真正的 HTTP server；lazy 初始化。 */
    private EmailWebServer server;

    /**
     * 获取（或第一次启动）server。线程安全。
     *
     * @param store 模板存储（API 后端要读 / 写文件）
     * @return 已启动的 server 实例
     * @throws IOException 启动失败
     */
    public synchronized EmailWebServer getOrStart(@NotNull EmailTemplateStore store) throws IOException {
        if (server == null) {
            server = new EmailWebServer(store, () -> {
                Supplier<Map<String, String>> s = dataSupplier;
                return s == null ? Collections.emptyMap() : s.get();
            });
            LOG.info("EmailWebServerService: server started, browser URL persists across dialog open/close");
        }
        return server;
    }

    /**
     * 当前 server URL；尚未启动时返回 null。
     *
     * @return 浏览器要打开的 URL，含一次性 token
     */
    public @Nullable URI getEditorUrl() {
        return server == null ? null : server.getEditorUrl();
    }

    /**
     * 由 dialog 注入"运行时数据 supplier"。每次 dialog 打开时更新，确保浏览器拿到的是最新值。
     * 传 null 表示"目前没有 dialog 在线"，supplier 退化为空 map。
     */
    public void updateDataSupplier(@Nullable Supplier<Map<String, String>> supplier) {
        this.dataSupplier = supplier;
    }

    /** 项目 / IDE 关闭时被调用，关掉 server。 */
    @Override
    public void dispose() {
        if (server != null) {
            server.shutdown();
            server = null;
        }
        dataSupplier = null;
    }

    /** 便捷取实例。 */
    public static @NotNull EmailWebServerService getInstance(@NotNull Project project) {
        return project.getService(EmailWebServerService.class);
    }
}
