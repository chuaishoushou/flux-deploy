package com.flux.deploy.config;

/**
 * 嵌入阶段单包失败的处置决策。
 *
 * <p>把"单包 embed 失败时是中止整批还是跳过该包继续"这个判定抽出成纯函数，
 * 让串行 / 并行两条路径走同一套规则，且可被单元测试直接覆盖。</p>
 *
 * <p>规则：</p>
 * <ul>
 *   <li>用户主动停止（cancel / 取消令牌） → 一律 {@link #ABORT_BATCH}，
 *       忽略策略：用户的意图比配置优先</li>
 *   <li>{@link FailureStrategy#ISOLATED} → {@link #CONTINUE_ISOLATED}，
 *       该包标 FAILED 但循环继续，由 preUnlockAll 在最后兜底回滚单包锁</li>
 *   <li>{@link FailureStrategy#ROLLBACK_ALL} / {@link FailureStrategy#KEEP_SUCCEEDED}
 *       → {@link #ABORT_BATCH}，进入 abortPartial / preUnlockAll 整批回滚收尾</li>
 * </ul>
 *
 * <p>本类不处理"如何回滚"，只负责"何时回滚"。具体回滚动作（preUnlockAll /
 * abortPartial / restoreLock）是 FTP IO 操作，由调用方按返回值决定走哪条分支。</p>
 *
 * @author xumanyi
 * @date 2026-05-04
 */
public enum EmbedFailureDecision {
    /** 单包失败：标记 + 继续处理后续目标 */
    CONTINUE_ISOLATED,
    /** 整批失败：abortPartial + preUnlockAll + 退出 */
    ABORT_BATCH;

    /**
     * 计算单包失败的处置决策。
     *
     * @param strategy 用户配置的失败策略；null 视为 {@link FailureStrategy#ISOLATED}
     *                 (与 {@link FailureStrategy#fromString(String)} 兜底一致)
     * @param userRequestedStop 是否由用户主动停止（取消令牌触发或显式 stop 按钮）；
     *                          true 时一律 ABORT_BATCH，无视策略
     * @return 处置决策
     * @author xumanyi
     * @date 2026-05-04
     */
    public static EmbedFailureDecision decide(FailureStrategy strategy, boolean userRequestedStop) {
        if (userRequestedStop) {
            return ABORT_BATCH;
        }
        FailureStrategy s = strategy != null ? strategy : FailureStrategy.ISOLATED;
        return s == FailureStrategy.ISOLATED ? CONTINUE_ISOLATED : ABORT_BATCH;
    }
}
