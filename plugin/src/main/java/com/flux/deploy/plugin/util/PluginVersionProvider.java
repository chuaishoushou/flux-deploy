package com.flux.deploy.plugin.util;

import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * 读取本插件自身版本号的统一入口。
 *
 * <p>版本号在构建期由 {@code plugin/build.gradle.kts} 的 processResources 任务把 Gradle
 * 工程版本写入 classpath 资源 {@code /META-INF/flux-deploy-version.txt}，运行期从该资源读取。</p>
 *
 * <p>历史实现走 IntelliJ Platform 的 {@code PluginManager.findEnabledPlugin(PluginId)} 动态查询
 * 插件 descriptor，但该方法连同 {@code PluginManager} 上整个按 id / class 取 descriptor 的方法族
 * （getPluginByClass / getPlugins / getPlugin / getLoadedPlugins）自 IDEA 2026.2 起被标记
 * {@code @ApiStatus.Internal}，Marketplace Plugin Verifier 据此驳回。改读构建期注入的资源后彻底
 * 脱离平台 API，不受其后续 internal / deprecated 变更影响。</p>
 *
 * @author xumanyi
 * @date 2026-05-29
 */
public final class PluginVersionProvider {

    /** 构建期注入版本号的 classpath 资源路径。 */
    private static final String VERSION_RESOURCE = "/META-INF/flux-deploy-version.txt";

    private PluginVersionProvider() {
    }

    /**
     * 读取本插件版本号。
     *
     * <p>读不到资源（极少数沙盒 / 调试态）时返回 {@code null}，由调用方决定各自的回退展示，
     * 而不是抛异常中断面板初始化或日志写入。</p>
     *
     * @return 形如 {@code "1.3.3"} 的版本串；资源缺失或为空时返回 {@code null}
     * @author xumanyi
     * @date 2026-05-29
     */
    @Nullable
    public static String currentVersionOrNull() {
        try (InputStream in = PluginVersionProvider.class.getResourceAsStream(VERSION_RESOURCE)) {
            if (in != null) {
                String text = new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
                if (!text.isEmpty()) {
                    return text;
                }
            }
        } catch (IOException ignored) {
            // 读取失败按"取不到版本"处理，返回 null 交由调用方回退
        }
        return null;
    }
}
