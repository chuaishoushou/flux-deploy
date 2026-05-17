package com.flux.deploy.plugin.util;

/**
 * 部署执行的状态枚举，对应日志文件名后缀。
 *
 * <p>RUNNING 是写入中的瞬态；其余为终态：
 * <ul>
 *   <li>OK：全流程成功</li>
 *   <li>FAIL_RB：失败但已成功回滚（现场干净）</li>
 *   <li>FAIL：失败且未回滚或回滚失败（现场可能脏，需人工介入）</li>
 *   <li>CANCEL：用户主动取消</li>
 * </ul>
 *
 * @author xumanyi
 * @date 2026-05-07
 */
public enum DeployRunStatus {
    RUNNING,
    OK,
    FAIL,
    FAIL_RB,
    CANCEL
}
