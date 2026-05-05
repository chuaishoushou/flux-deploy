package com.flux.deploy.ftp;

/**
 * FTP 操作错误的语义分类
 *
 * <p>与"传染性"绑定：</p>
 * <ul>
 *   <li>SERVER_LIMIT / AUTH / NETWORK = 全局型错误，影响所有并发任务，
 *       发生时应触发取消令牌让其他任务尽快边界退出</li>
 *   <li>PROTOCOL = 单包型错误（如 550 File not found），仅当前任务受影响</li>
 * </ul>
 *
 * <p>判定方式：{@link FtpErrorClassifier#classify} 把任意 Throwable 映射到本枚举；
 * {@link FtpErrorClassifier#isGlobal} 给出"是否全局"判定；
 * {@link FtpErrorClassifier#suggestionFor} 给出可执行建议（不带具体数字，避免误导）。</p>
 *
 * @author claude
 * @date 2026-05-02
 */
public enum FtpErrorKind {
    /** FTP 服务器并发连接受限（421 too many / max clients） */
    SERVER_LIMIT,
    /** 认证失败（530 / 用户名密码错误） */
    AUTH,
    /** 网络层错误（连接拒绝 / 超时 / 磁盘满） */
    NETWORK,
    /** 其他 FTP 协议错误（默认分类，单包错误） */
    PROTOCOL
}
