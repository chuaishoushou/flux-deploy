package com.flux.deploy.plugin.action;

import com.flux.deploy.plugin.model.DeployMode;
import com.flux.deploy.plugin.service.ModuleDetector;
import com.flux.deploy.plugin.toolwindow.DeployToolWindowPanel;
import com.flux.deploy.plugin.toolwindow.FluxDeployToolWindowFactory;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import org.jetbrains.annotations.NotNull;

/**
 * 全量更新此模块
 *
 * <p>右键菜单操作：整包更新模式。激活部署面板并设置为 FULL 模式，
 * 将整个编译产物替换远程 FTP 上的同名包。</p>
 *
 * @author xumanyi
 * @date 2026-03-27
 */
public class FullDeployAction extends AnAction {

    /**
     * {@inheritDoc}
     *
     * @return 后台线程更新策略
     * @author xumanyi
     * @date 2026-03-27
     */
    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    /**
     * 执行全量更新操作
     *
     * <p>检测模块根目录，激活部署工具窗口，并设置为全量更新模式。</p>
     *
     * @param e 动作事件
     * @author xumanyi
     * @date 2026-03-27
     */
    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        if (e.getProject() == null) return;

        VirtualFile moduleRoot = ModuleDetector.detectModuleRoot(e);
        if (moduleRoot == null) return;

        ToolWindow tw = ToolWindowManager.getInstance(e.getProject())
                .getToolWindow("FLUX Deploy");
        if (tw != null) {
            tw.activate(() -> {
                DeployToolWindowPanel panel = FluxDeployToolWindowFactory.getPanel(e.getProject());
                if (panel != null) {
                    panel.setModuleAndMode(moduleRoot.getPath(), DeployMode.FULL);
                }
            });
        }
    }

    /**
     * 根据当前上下文更新操作的可用性和可见性
     *
     * @param e 动作事件
     * @author xumanyi
     * @date 2026-03-27
     */
    @Override
    public void update(@NotNull AnActionEvent e) {
        VirtualFile moduleRoot = ModuleDetector.detectModuleRoot(e);
        e.getPresentation().setEnabledAndVisible(moduleRoot != null);
    }
}
