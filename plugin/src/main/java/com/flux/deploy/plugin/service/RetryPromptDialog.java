package com.flux.deploy.plugin.service;

import com.flux.deploy.ftp.FtpErrorClassifier;
import com.flux.deploy.ftp.FtpErrorKind;
import com.flux.deploy.ftp.RetryUserPrompter;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;

import java.util.concurrent.locks.ReentrantLock;

/**
 * IDEA 弹窗版重试提示器。
 *
 * <p>核心约束：</p>
 * <ul>
 *   <li><strong>EDT 上弹</strong>：{@link Messages#showDialog} 必须在 EDT，
 *       通过 {@link ApplicationManager#invokeAndWait} 同步等待用户选择</li>
 *   <li><strong>全局串行化</strong>：并发部署可能多个目标同时触发提示，
 *       同时弹多个 dialog 会让用户分不清在回答哪个目标的问题。
 *       静态 {@link ReentrantLock} 保证一次只弹一个，后到的目标排队等待</li>
 *   <li><strong>两按钮设计</strong>：[重试] / [结束]，无第三选项；用户可随时通过点"结束"
 *       中止当前目标，避免 dialog 卡死无法退出</li>
 * </ul>
 *
 * <p>UI 模态级使用 {@link ModalityState#any}：插件部署经常在后台进度条里跑，
 * 此时 IDE 处于 NON_MODAL 之外的状态，用 any 才能确保弹窗能浮出来。</p>
 *
 * @author xumanyi
 * @date 2026-05-03
 */
public final class RetryPromptDialog implements RetryUserPrompter {

    /**
     * 进程级串行锁。所有 RetryPromptDialog 实例共享同一锁，
     * 保证整个 IDE 进程同一时刻最多一个重试 dialog。
     */
    private static final ReentrantLock GLOBAL_LOCK = new ReentrantLock();

    private final Project project;

    /**
     * @param project 当前 IDEA Project，用于把 dialog 锚到对应窗口；可为 null 时弹无主 dialog
     * @author xumanyi
     * @date 2026-05-03
     */
    public RetryPromptDialog(Project project) {
        this.project = project;
    }

    /** {@inheritDoc} */
    @Override
    public Decision askRetryOrAbort(String targetKey, int attemptedTimes,
                                     String lastError, FtpErrorKind errorKind) {
        GLOBAL_LOCK.lock();
        try {
            int[] choice = new int[]{1};
            ApplicationManager.getApplication().invokeAndWait(() -> {
                String suggestion = FtpErrorClassifier.suggestionFor(errorKind);
                StringBuilder msg = new StringBuilder();
                msg.append("目标: ").append(targetKey).append('\n');
                msg.append("已自动重试 ").append(attemptedTimes).append(" 次仍失败：\n");
                msg.append(lastError == null ? "(无详细信息)" : lastError);
                if (suggestion != null) {
                    msg.append("\n\n").append(suggestion);
                }
                msg.append("\n\n点 [重试] 重新进入一轮自动重试，或点 [结束] 中止当前目标。");
                choice[0] = Messages.showDialog(
                        project,
                        msg.toString(),
                        "部署中断 — " + describeKind(errorKind),
                        new String[]{"重试", "结束"},
                        0,
                        Messages.getQuestionIcon());
            }, ModalityState.any());
            return choice[0] == 0 ? Decision.RETRY : Decision.ABORT;
        } finally {
            GLOBAL_LOCK.unlock();
        }
    }

    /**
     * 给 dialog 标题选个简短描述。
     *
     * @param kind 错误分类，可为 null
     * @return 标题后缀
     * @author xumanyi
     * @date 2026-05-03
     */
    private static String describeKind(FtpErrorKind kind) {
        if (kind == null) return "未知错误";
        return switch (kind) {
            case NETWORK -> "网络异常";
            case AUTH -> "认证失败";
            case SERVER_LIMIT -> "服务器并发限制";
            case PROTOCOL -> "协议错误";
        };
    }
}
