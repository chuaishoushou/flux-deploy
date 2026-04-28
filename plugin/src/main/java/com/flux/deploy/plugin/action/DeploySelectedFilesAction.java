package com.flux.deploy.plugin.action;

import com.flux.deploy.plugin.model.DeployMode;
import com.flux.deploy.plugin.service.ModuleDetector;
import com.flux.deploy.plugin.toolwindow.DeployToolWindowPanel;
import com.flux.deploy.plugin.toolwindow.FluxDeployToolWindowFactory;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * 更新选中的文件（仅选中具体文件时可用）
 *
 * <p>右键菜单操作：将用户在项目树中选中的文件作为增量更新列表，
 * 激活部署面板并设置为 INCREMENTAL 模式。仅在选中了非目录文件时可用。</p>
 *
 * @author xumanyi
 * @date 2026-03-27
 */
public class DeploySelectedFilesAction extends AnAction {

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
     * 执行选中文件更新操作
     *
     * <p>收集选中的文件路径，激活部署工具窗口，并以增量模式设置文件列表。</p>
     *
     * @param e 动作事件
     * @author xumanyi
     * @date 2026-03-27
     */
    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        if (e.getProject() == null) return;

        VirtualFile[] files = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY);
        if (files == null || files.length == 0) return;

        VirtualFile moduleRoot = ModuleDetector.detectModuleRoot(e);
        List<String> filePaths = new ArrayList<>();
        for (VirtualFile f : files) {
            filePaths.add(f.getPath());
        }

        ToolWindow tw = ToolWindowManager.getInstance(e.getProject())
                .getToolWindow("FLUX Deploy");
        if (tw != null) {
            tw.activate(() -> {
                DeployToolWindowPanel panel = FluxDeployToolWindowFactory.getPanel(e.getProject());
                if (panel != null) {
                    String modulePath = moduleRoot != null ? moduleRoot.getPath() : null;
                    panel.setFilesAndMode(modulePath, filePaths);
                }
            });
        }
    }

    /**
     * 根据当前上下文更新操作的可用性和可见性
     *
     * <p>仅当选中了至少一个非目录文件时，此操作才可用。</p>
     *
     * @param e 动作事件
     * @author xumanyi
     * @date 2026-03-27
     */
    @Override
    public void update(@NotNull AnActionEvent e) {
        VirtualFile[] files = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY);
        boolean hasFiles = files != null && files.length > 0;
        // 仅选中具体文件时可用（非目录）
        if (hasFiles) {
            for (VirtualFile f : files) {
                if (f.isDirectory()) {
                    hasFiles = false;
                    break;
                }
            }
        }
        e.getPresentation().setEnabledAndVisible(hasFiles);
    }
}
