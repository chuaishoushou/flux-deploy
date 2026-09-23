package com.flux.deploy.plugin.util;

import com.intellij.ui.jcef.JBCefApp;

/**
 * JCEF（IDE 内置 Chromium 内核）可用性安全探测工具。
 *
 * <p><b>为什么需要它</b>：直接调用 {@link JBCefApp#isSupported()} 会触发
 * {@code com.intellij.ui.jcef.JBCefApp} 类加载。在未捆绑 / 未启用 JCEF 的 IDE 运行时
 * （部分 Community 版、自定义启动 JRE、远程开发后端等）里，该类可能整体不在插件
 * classpath 中，此时调用抛出的是 {@link NoClassDefFoundError} / {@link LinkageError}
 * 而非返回 {@code false}——依赖 JCEF 的入口（文档面板、邮件编辑器）会在「守卫」处
 * 直接崩溃，本该生效的降级逻辑反而走不到。</p>
 *
 * <p>本类用 {@link Throwable} 兜住一切链接期错误与运行时异常，把「类缺失」与
 * 「明确不支持」统一收敛为 {@code false}，让调用方能安全降级（回退 Swing 渲染 /
 * 弹提示中止），而不是让异常冒泡到 EDT 变成未处理崩溃。</p>
 *
 * @author xumanyi
 * @date 2026-07-24
 */
public final class JcefSupport {

    private JcefSupport() {
    }

    /**
     * 安全探测当前 IDE 运行时是否支持 JCEF。
     *
     * <p>保证不抛任何异常：无论 {@code JBCefApp} 类缺失（{@link NoClassDefFoundError}）、
     * 静态初始化失败，还是 {@link JBCefApp#isSupported()} 内部异常，一律判定为不可用。</p>
     *
     * @return JCEF 可用返回 {@code true}；类缺失 / 初始化异常 / 明确不支持均返回 {@code false}
     * @author xumanyi
     * @date 2026-07-24
     */
    public static boolean isAvailable() {
        try {
            return JBCefApp.isSupported();
        } catch (Throwable t) {
            return false;
        }
    }
}
