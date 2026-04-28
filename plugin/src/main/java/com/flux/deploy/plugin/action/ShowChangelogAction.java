package com.flux.deploy.plugin.action;

import com.flux.deploy.plugin.toolwindow.DeployToolWindowPanel;
import com.flux.deploy.plugin.toolwindow.FluxDeployToolWindowFactory;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

/**
 * 工具窗口标题栏 Action：在面板内切换到版本记录视图
 *
 * @author xumanyi
 * @date 2026-04-17
 */
public class ShowChangelogAction extends AnAction {

    public ShowChangelogAction() {
        super("版本记录", "查看插件版本更新记录", AllIcons.Vcs.History);
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) return;
        DeployToolWindowPanel panel = FluxDeployToolWindowFactory.getPanel(project);
        if (panel != null) {
            panel.toggleDocs("版本记录", "/docs/changelog.html");
        }
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }
}
