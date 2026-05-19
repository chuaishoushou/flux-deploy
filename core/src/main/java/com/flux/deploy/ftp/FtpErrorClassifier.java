package com.flux.deploy.ftp;

import java.net.ConnectException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.Locale;

/**
 * FTP 异常分类器
 *
 * <p>把 raw {@link Throwable} 识别为 {@link FtpErrorKind}，
 * 用于并发执行器决定"单包失败"还是"全局取消"。</p>
 *
 * <p>识别策略：</p>
 * <ul>
 *   <li>沿 cause 链查找 {@link ConnectException} / {@link SocketTimeoutException} → NETWORK</li>
 *   <li>消息含 421 + 关键字（too many / max clients / connections） → SERVER_LIMIT</li>
 *   <li>消息含 530 / "认证失败" / "login incorrect|failed" → AUTH</li>
 *   <li>消息含 "no space left" / "disk full" → NETWORK</li>
 *   <li>否则 → PROTOCOL</li>
 * </ul>
 *
 * @author xumanyi
 * @date 2026-05-02
 */
public final class FtpErrorClassifier {

    private FtpErrorClassifier() {
    }

    /**
     * 把任意 Throwable 分类为 {@link FtpErrorKind}
     *
     * <p>会沿 cause 链向下查找，处理被包了一层 wrapper 的情况。</p>
     *
     * @param t 原始异常（可为 null，返回 PROTOCOL 兜底）
     * @return 分类结果（永不返回 null）
     * @author xumanyi
     * @date 2026-05-02
     */
    public static FtpErrorKind classify(Throwable t) {
        if (t == null) {
            return FtpErrorKind.PROTOCOL;
        }

        // 沿 cause 链查找 socket 类异常（ConnectException / SocketTimeoutException / SocketException）
        // SocketException 覆盖 "Connection reset"、"Broken pipe"、"Connection closed by peer" 等
        // 中途 socket 失败的常见情况，这些都属于网络瞬态
        Throwable cur = t;
        while (cur != null) {
            if (cur instanceof ConnectException
                    || cur instanceof SocketTimeoutException
                    || cur instanceof SocketException) {
                return FtpErrorKind.NETWORK;
            }
            cur = cur.getCause();
        }

        String msg = collectMessages(t);
        String low = msg.toLowerCase(Locale.ROOT);

        // AUTH 优先：530 是确定的不可恢复错，重试无意义
        if (low.contains("530") || msg.contains("认证失败")
                || low.contains("login incorrect") || low.contains("login failed")) {
            return FtpErrorKind.AUTH;
        }

        // 421 服务端并发限制：退避后大概率恢复，归入 SERVER_LIMIT，由策略决定重试
        if (low.contains("421") && containsAny(low, "too many", "max clients", "connections")) {
            return FtpErrorKind.SERVER_LIMIT;
        }

        // 网络瞬态扩展：FTP 服务端主动断开 / 数据连接异常 / 控制连接被关，
        // 这些原本都被兜底成 PROTOCOL 不重试，是 production 里最常见的"假死"误判源头。
        // 全部上提到 NETWORK 让重试循环兜起来。
        boolean transientServer =
                // 421 不带 "too many"：通用服务端 idle/reaper/restart 主动断开
                low.contains("421")
                // 425：服务端拒开数据连接（被动端口分配失败、防火墙策略抖动）
                || low.contains("425")
                // 426：数据连接传输中被服务端关闭
                || low.contains("426")
                // 450：文件被临时占用 / 锁住，下次可能就好了
                || low.contains("450")
                // Commons Net 在控制连接突然死时抛出的固定文案
                || low.contains("connection closed without indication")
                // socket 层的常见瞬态文案（即便没被识别为 SocketException 子类）
                || low.contains("connection reset")
                || low.contains("broken pipe")
                || low.contains("connection refused")
                || low.contains("connection timed out")
                || low.contains("network is unreachable")
                || low.contains("software caused connection abort")
                || low.contains("connection closed by peer");
        if (transientServer) {
            return FtpErrorKind.NETWORK;
        }

        // 磁盘满：归 NETWORK（实际不属于网络，但表现上重试一次让用户感知会更清晰；
        // 如果仍然满则下一轮分类不变继续 NETWORK，预算耗尽弹窗给用户）
        if (low.contains("no space left") || low.contains("disk full")) {
            return FtpErrorKind.NETWORK;
        }
        return FtpErrorKind.PROTOCOL;
    }

    /**
     * 是否为"全局型"错误（影响所有并发任务）
     *
     * <p>全局错误：服务器并发限制、认证、网络/磁盘。
     * 单包错误：协议错误（如 550 File not found，仅当前文件相关）。</p>
     *
     * @param kind 已分类的错误类型
     * @return true 时调用方应触发取消令牌
     * @author xumanyi
     * @date 2026-05-02
     */
    public static boolean isGlobal(FtpErrorKind kind) {
        return kind != null && kind != FtpErrorKind.PROTOCOL;
    }

    /**
     * 给用户的可执行建议（不含具体数字，避免误导）
     *
     * @param kind 错误类型
     * @return 建议文案；PROTOCOL 返回 null（无针对性建议）
     * @author xumanyi
     * @date 2026-05-02
     */
    public static String suggestionFor(FtpErrorKind kind) {
        if (kind == null) return null;
        switch (kind) {
            case SERVER_LIMIT:
                return "FTP 服务器并发连接受限。建议降低 backup / embed 的并发数后重试。";
            case AUTH:
                return "FTP 认证失败。请检查凭证是否过期或被服务器侧禁用。";
            case NETWORK:
                return "网络异常。请检查 VPN / 网络稳定性后重试。";
            case PROTOCOL:
            default:
                return null;
        }
    }

    /**
     * 收集异常链上所有 message，便于关键字匹配
     *
     * @param t 异常（非 null）
     * @return 用 " / " 拼接的所有 message
     * @author xumanyi
     * @date 2026-05-02
     */
    private static String collectMessages(Throwable t) {
        StringBuilder sb = new StringBuilder();
        Throwable cur = t;
        while (cur != null) {
            if (cur.getMessage() != null) {
                if (sb.length() > 0) sb.append(" / ");
                sb.append(cur.getMessage());
            }
            cur = cur.getCause();
        }
        return sb.length() == 0 ? t.getClass().getSimpleName() : sb.toString();
    }

    /**
     * 判断字符串是否包含任意关键字
     *
     * @param src  目标字符串
     * @param keys 关键字数组
     * @return 命中任一关键字返回 true
     * @author xumanyi
     * @date 2026-05-02
     */
    private static boolean containsAny(String src, String... keys) {
        for (String k : keys) {
            if (src.contains(k)) return true;
        }
        return false;
    }
}
