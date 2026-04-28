package com.flux.deploy.plugin.util;

import java.io.OutputStream;
import java.io.PrintStream;
import java.util.function.Consumer;

/**
 * 线程局部 System.out 拦截器
 *
 * <p>仅捕获目标线程（创建时的线程）的输出，转发到日志回调。
 * 其他线程的输出直接透传到原始 PrintStream。</p>
 *
 * <p>用于捕获 DeployPipeline 等 CLI 组件的 System.out 输出，
 * 转发到插件的日志面板。</p>
 *
 * @author xumanyi
 * @date 2026-03-27
 */
public class LogInterceptor extends PrintStream {

    private final PrintStream original;
    private final Consumer<String> callback;
    private final Thread targetThread;

    /**
     * 构造日志拦截器
     *
     * @param original 原始 PrintStream（用于透传和保底输出）
     * @param callback 日志回调（仅目标线程的 println 触发）
     * @author xumanyi
     * @date 2026-03-27
     */
    public LogInterceptor(PrintStream original, Consumer<String> callback) {
        super(original, true);
        this.original = original;
        this.callback = callback;
        this.targetThread = Thread.currentThread();
    }

    /**
     * 拦截 println 输出，目标线程的输出转发到回调
     *
     * @param x 输出内容
     * @author xumanyi
     * @date 2026-03-27
     */
    @Override
    public void println(String x) {
        if (Thread.currentThread() == targetThread) {
            callback.accept(x);
        }
        original.println(x);
    }

    /**
     * 拦截 print 输出，不单独触发回调（等待 println）
     *
     * @param s 输出内容
     * @author xumanyi
     * @date 2026-03-27
     */
    @Override
    public void print(String s) {
        if (Thread.currentThread() == targetThread) {
            // 不单独回调 print，等 println
        }
        original.print(s);
    }

    /**
     * 获取原始 PrintStream
     *
     * @return 原始 PrintStream
     * @author xumanyi
     * @date 2026-03-27
     */
    public PrintStream getOriginal() {
        return original;
    }
}
