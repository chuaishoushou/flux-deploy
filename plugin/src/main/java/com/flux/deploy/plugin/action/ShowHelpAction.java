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
 * 工具窗口标题栏 Action：在面板内切换到使用手册视图
 *
 * @author xumanyi
 * @date 2026-04-17
 */
public class ShowHelpAction extends AnAction {

    public ShowHelpAction() {
        super("使用手册", "查看 FLUX 客服更新使用手册", AllIcons.Toolwindows.Documentation);
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) return;
        DeployToolWindowPanel panel = FluxDeployToolWindowFactory.getPanel(project);
        if (panel != null) {
            panel.toggleDocs("使用手册", "/docs/help.html");
        }
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }
}
