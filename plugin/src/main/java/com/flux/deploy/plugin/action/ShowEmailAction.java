package com.flux.deploy.plugin.action;

import com.flux.deploy.plugin.toolwindow.DeployToolWindowPanel;
import com.flux.deploy.plugin.toolwindow.FluxDeployToolWindowFactory;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.IconLoader;
import org.jetbrains.annotations.NotNull;

import javax.swing.Icon;

/**
 * 工具窗口标题栏 Action：打开「通知邮件」弹窗
 *
 * <p>放在标题栏图标顺序的最前（{@code [✉ 邮件] [📘 使用手册] [📜 版本记录]}）。</p>
 *
 * @author xumanyi
 * @date 2026-05-17
 */
public class ShowEmailAction extends AnAction {

    /**
     * 邮件信封图标（SVG）。
     *
     * <p>IntelliJ Platform 自带的 AllIcons 里没有专门的邮件图标，
     * 自带 SVG 资源 {@code /icons/email.svg}（暗色主题用 {@code email_dark.svg}）。
     * 16x16 矢量，纯线条信封造型，与邻位"使用手册""版本记录"线条图标风格统一。</p>
     */
    private static final Icon EMAIL_ICON =
            IconLoader.getIcon("/icons/email.svg", ShowEmailAction.class);

    public ShowEmailAction() {
        super("通知邮件", "生成更新通知邮件，复制到剪贴板后粘到邮件客户端",
                EMAIL_ICON);
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) {
            return;
        }
        DeployToolWindowPanel panel = FluxDeployToolWindowFactory.getPanel(project);
        if (panel != null) {
            panel.openEmailDialog();
        }
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }
}
