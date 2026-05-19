package com.flux.deploy.parallel;

import com.flux.deploy.ftp.FtpErrorKind;

import java.util.Objects;

/**
 * 单个目标的并行执行结果
 *
 * <p>不可变。由 {@link ParallelExecutor} 在每个任务结束时构造，
 * 由调用方按 status 聚合生成终态日志。</p>
 *
 * @author xumanyi
 * @date 2026-05-02
 */
public final class TargetOutcome {

    private final String targetKey;
    private final TargetStatus status;
    private final Throwable error;
    private final FtpErrorKind errorKind;
    private final String logSegment;

    /**
     * 全字段构造（私有，外部用静态工厂方法）
     *
     * @param targetKey  目标标识（通常是文件名或相对路径）
     * @param status     状态
     * @param error      异常根因（status=SUCCESS/SKIPPED/CANCELLED 时为 null）
     * @param errorKind  错误分类（仅 FAILED/ROLLBACK_FAILED 时有意义，否则 null）
     * @param logSegment 该目标的完整日志段（null 视作空字符串）
     * @author xumanyi
     * @date 2026-05-02
     */
    private TargetOutcome(String targetKey, TargetStatus status,
                          Throwable error, FtpErrorKind errorKind, String logSegment) {
        this.targetKey = Objects.requireNonNull(targetKey, "targetKey");
        this.status = Objects.requireNonNull(status, "status");
        this.error = error;
        this.errorKind = errorKind;
        this.logSegment = logSegment == null ? "" : logSegment;
    }

    /**
     * 构造成功结果
     *
     * @param targetKey  目标标识
     * @param logSegment 日志段
     * @return Outcome 实例
     * @author xumanyi
     * @date 2026-05-02
     */
    public static TargetOutcome success(String targetKey, String logSegment) {
        return new TargetOutcome(targetKey, TargetStatus.SUCCESS, null, null, logSegment);
    }

    /**
     * 构造失败结果（ISOLATED 模式下表示已成功完成单包回滚）
     *
     * @param targetKey  目标标识
     * @param error      异常根因
     * @param errorKind  错误分类
     * @param logSegment 日志段
     * @return Outcome 实例
     * @author xumanyi
     * @date 2026-05-02
     */
    public static TargetOutcome failed(String targetKey, Throwable error,
                                        FtpErrorKind errorKind, String logSegment) {
        return new TargetOutcome(targetKey, TargetStatus.FAILED, error, errorKind, logSegment);
    }

    /**
     * 构造单包回滚失败结果（CRITICAL）
     *
     * @param targetKey  目标标识
     * @param error      回滚阶段抛出的异常
     * @param logSegment 日志段
     * @return Outcome 实例
     * @author xumanyi
     * @date 2026-05-02
     */
    public static TargetOutcome rollbackFailed(String targetKey, Throwable error, String logSegment) {
        return new TargetOutcome(targetKey, TargetStatus.ROLLBACK_FAILED, error, null, logSegment);
    }

    /**
     * 构造已取消结果
     *
     * @param targetKey  目标标识
     * @param logSegment 取消时已积累的日志段
     * @return Outcome 实例
     * @author xumanyi
     * @date 2026-05-02
     */
    public static TargetOutcome cancelled(String targetKey, String logSegment) {
        return new TargetOutcome(targetKey, TargetStatus.CANCELLED, null, null, logSegment);
    }

    /**
     * 构造跳过结果（前置阶段失败导致剔除）
     *
     * @param targetKey  目标标识
     * @param logSegment 跳过原因日志
     * @return Outcome 实例
     * @author xumanyi
     * @date 2026-05-02
     */
    public static TargetOutcome skipped(String targetKey, String logSegment) {
        return new TargetOutcome(targetKey, TargetStatus.SKIPPED, null, null, logSegment);
    }

    /**
     * 目标标识
     *
     * @return 不为 null
     * @author xumanyi
     * @date 2026-05-02
     */
    public String getTargetKey() {
        return targetKey;
    }

    /**
     * 状态
     *
     * @return 不为 null
     * @author xumanyi
     * @date 2026-05-02
     */
    public TargetStatus getStatus() {
        return status;
    }

    /**
     * 异常根因
     *
     * @return SUCCESS/SKIPPED/CANCELLED 时为 null
     * @author xumanyi
     * @date 2026-05-02
     */
    public Throwable getError() {
        return error;
    }

    /**
     * 错误分类
     *
     * @return 仅 FAILED 时有意义；ROLLBACK_FAILED / 非失败状态返回 null
     * @author xumanyi
     * @date 2026-05-02
     */
    public FtpErrorKind getErrorKind() {
        return errorKind;
    }

    /**
     * 单包日志段（按目标 buffer，完成时一次性 flush 用）
     *
     * @return 不为 null（构造时 null 会归一为空字符串）
     * @author xumanyi
     * @date 2026-05-02
     */
    public String getLogSegment() {
        return logSegment;
    }

    /**
     * 是否为需要手动介入的严重状态
     *
     * @return 当前仅 ROLLBACK_FAILED 视为 CRITICAL
     * @author xumanyi
     * @date 2026-05-02
     */
    public boolean isCritical() {
        return status == TargetStatus.ROLLBACK_FAILED;
    }
}
