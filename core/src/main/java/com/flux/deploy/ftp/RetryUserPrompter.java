package com.flux.deploy.ftp;

/**
 * 重试用户提示器：自动重试预算耗尽后由该接口决定继续 / 结束。
 *
 * <p>core 层只定义协议，plugin 层负责弹 EDT 模态 dialog；CLI 等非交互场景可用
 * {@link #abortAll()} 把所有失败一律 ABORT。</p>
 *
 * <p>语义约定：</p>
 * <ul>
 *   <li>{@link Decision#RETRY} —— 重置重试预算，重新进入一轮自动重试循环</li>
 *   <li>{@link Decision#ABORT} —— 结束当前操作，把最后一次错误抛给上层处理</li>
 * </ul>
 *
 * <p>实现方应保证<strong>串行化</strong>：并发场景下多个目标同时触发提示，
 * 不能同时弹多个 dialog（IDEA 体验灾难），必须排队。</p>
 *
 * @author xumanyi
 * @date 2026-05-03
 */
public interface RetryUserPrompter {

    /**
     * 用户在重试弹窗上的两种选择。
     *
     * @author xumanyi
     * @date 2026-05-03
     */
    enum Decision {
        /** 继续重试：重置预算再来一轮 */
        RETRY,
        /** 结束当前操作 */
        ABORT
    }

    /**
     * 询问用户是否继续重试。
     *
     * @param targetKey      目标标识（host:dir/pkg），仅用于展示
     * @param attemptedTimes 已自动重试次数（即 RetryPolicy.maxAttempts）
     * @param lastError      最后一次错误的人类可读信息
     * @param errorKind      错误分类，可用来给文案补"网络异常 / 服务器拒绝"等附加提示
     * @return 用户决定
     * @author xumanyi
     * @date 2026-05-03
     */
    Decision askRetryOrAbort(String targetKey, int attemptedTimes, String lastError, FtpErrorKind errorKind);

    /**
     * 非交互默认实现：所有失败一律 ABORT。
     *
     * <p>用于 CLI、单元测试，以及 plugin 在 headless 模式下的回退。</p>
     *
     * @return 永远返回 ABORT 的 prompter
     * @author xumanyi
     * @date 2026-05-03
     */
    static RetryUserPrompter abortAll() {
        return (key, n, err, kind) -> Decision.ABORT;
    }

    /**
     * 测试用：永远 RETRY，配合最大重试次数防止无限循环。
     *
     * @param maxRetries 总共可调用 askRetryOrAbort 几次返回 RETRY，再调返回 ABORT
     * @return 测试 prompter
     * @author xumanyi
     * @date 2026-05-03
     */
    static RetryUserPrompter retryNTimes(int maxRetries) {
        int[] counter = new int[]{0};
        return (key, n, err, kind) -> {
            if (counter[0]++ < maxRetries) return Decision.RETRY;
            return Decision.ABORT;
        };
    }
}
