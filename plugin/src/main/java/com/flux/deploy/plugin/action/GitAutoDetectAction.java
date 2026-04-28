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
 * 自动检索（git变更）
 *
 * <p>右键菜单操作：自动检测 Git 工作区中的变更文件，
 * 激活部署面板并设置为 INCREMENTAL 模式（合并 UI 后该模式默认拉取 Git 变更并勾选）。面板会展示全量可部署文件，
 * 并默认勾选检测到的 Git 变更。</p>
 *
 * @author xumanyi
 * @date 2026-03-27
 */
public class GitAutoDetectAction extends AnAction {

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
     * 执行自动检索操作
     *
     * <p>检测模块根目录，激活部署工具窗口，并设置为增量更新模式（默认按 Git 变更勾选）。</p>
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
                    // 合并 UI 模式后，"自动检测变更"右键入口等价于切到 INCREMENTAL：
                    // INCREMENTAL 默认拉取 Git 变更并预先勾选——行为与原 AUTO_DETECT 一致。
                    panel.setModuleAndMode(moduleRoot.getPath(), DeployMode.INCREMENTAL);
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
