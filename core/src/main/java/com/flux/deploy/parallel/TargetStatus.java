package com.flux.deploy.parallel;

/**
 * 单个目标在并行批次中的执行状态
 *
 * @author xumanyi
 * @date 2026-05-02
 */
public enum TargetStatus {
    /** 成功完成 */
    SUCCESS,
    /** 失败（在 ISOLATED 模式下已尝试单包回滚成功） */
    FAILED,
    /** 因取消令牌触发，未启动或边界退出 */
    CANCELLED,
    /** 因前置阶段失败被剔除（如备份失败 → 嵌入阶段不参与） */
    SKIPPED,
    /** 单包回滚自身失败（CRITICAL，需手动介入） */
    ROLLBACK_FAILED
}
