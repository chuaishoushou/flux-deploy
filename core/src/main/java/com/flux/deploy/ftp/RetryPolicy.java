package com.flux.deploy.ftp;

import java.time.Duration;
import java.util.List;

/**
 * FTP 操作重试策略。
 *
 * <p>三要素：</p>
 * <ul>
 *   <li>{@code maxAttempts} —— 单轮重试预算（含首次尝试）</li>
 *   <li>{@code backoffSchedule} —— 退避时长序列，按尝试次数索引（attempt=1 → idx=0）；
 *       attempt 超出序列长度时使用最后一项</li>
 *   <li>{@link #shouldRetry(FtpErrorKind)} —— 错误类型白名单，只有 NETWORK 才重试，
 *       AUTH / SERVER_LIMIT 重试无意义直接放弃，PROTOCOL 是单包错误也不重试</li>
 * </ul>
 *
 * <p>注意 {@code maxAttempts} 是<strong>单轮</strong>预算：用户在弹窗里点"重试"会重置预算，
 * 总尝试次数无上限，由用户主动选择"结束"作为终止条件。</p>
 *
 * @param maxAttempts      单轮最大尝试次数（含首次），≥ 1
 * @param backoffSchedule  退避序列；空序列或 maxAttempts=1 等价于不重试
 * @author claude
 * @date 2026-05-03
 */
public record RetryPolicy(int maxAttempts, List<Duration> backoffSchedule) {

    public RetryPolicy {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts 必须 ≥ 1，当前: " + maxAttempts);
        }
        if (backoffSchedule == null) {
            throw new IllegalArgumentException("backoffSchedule 不能为 null");
        }
        backoffSchedule = List.copyOf(backoffSchedule);
    }

    /**
     * 默认网络重试策略：3 次，退避 2s/5s/10s。
     *
     * <p>选 3 次的依据：覆盖典型瞬断（路由 RA 抖动 / NAT 重协商 / 短时丢包），
     * 大部分恢复在 5~15s 内。再多的次数继续等下去用户体验差，应升级为弹窗。</p>
     *
     * @return 默认策略
     * @author claude
     * @date 2026-05-03
     */
    public static RetryPolicy networkDefault() {
        return new RetryPolicy(3, List.of(
                Duration.ofSeconds(2),
                Duration.ofSeconds(5),
                Duration.ofSeconds(10)
        ));
    }

    /**
     * 取第 {@code attempt} 次失败后的退避时长（attempt 从 1 开始）。
     *
     * <p>attempt 超出序列长度时夹到最后一项，避免数组越界。</p>
     *
     * @param attempt 当前已失败的尝试序号，≥ 1
     * @return 应等待时长；序列为空时返回 {@link Duration#ZERO}
     * @author claude
     * @date 2026-05-03
     */
    public Duration backoffFor(int attempt) {
        if (backoffSchedule.isEmpty()) return Duration.ZERO;
        int idx = Math.min(attempt - 1, backoffSchedule.size() - 1);
        return backoffSchedule.get(Math.max(0, idx));
    }

    /**
     * 判定该错误类型是否值得重试。
     *
     * <p>当前实现：</p>
     * <ul>
     *   <li>{@link FtpErrorKind#NETWORK} —— 重试（瞬态网络/服务端断开）</li>
     *   <li>{@link FtpErrorKind#SERVER_LIMIT} —— 重试（421 too many：退避等并发槽位释放，
     *       这正是该错误码的标准应对）</li>
     *   <li>{@link FtpErrorKind#AUTH} —— 不重试（凭证错，重试同样错）</li>
     *   <li>{@link FtpErrorKind#PROTOCOL} —— 不重试（550/553 业务错，重试同样错）</li>
     * </ul>
     *
     * @param kind 错误分类（可为 null）
     * @return true 表示调用方应进入退避 + 重试循环
     * @author claude
     * @date 2026-05-03
     */
    public boolean shouldRetry(FtpErrorKind kind) {
        return kind == FtpErrorKind.NETWORK || kind == FtpErrorKind.SERVER_LIMIT;
    }
}
