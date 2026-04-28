package com.flux.deploy.plugin.action;

import com.flux.deploy.plugin.service.ModuleDetector;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;

/**
 * FLUX 客服更新右键菜单组
 *
 * <p>仅在包含 pom.xml 的模块上下文中显示。
 * 作为右键菜单的顶层分组，包含全量更新、自动检索、选中文件更新等子操作。</p>
 *
 * @author xumanyi
 * @date 2026-03-27
 */
public class FluxDeployActionGroup extends DefaultActionGroup {

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
     * 根据当前上下文更新菜单组的可见性
     *
     * <p>仅当右键点击的文件所在目录向上能找到 pom.xml 时，才显示此菜单组。</p>
     *
     * @param e 动作事件
     * @author xumanyi
     * @date 2026-03-27
     */
    @Override
    public void update(@NotNull AnActionEvent e) {
        VirtualFile moduleRoot = ModuleDetector.detectModuleRoot(e);
        e.getPresentation().setVisible(moduleRoot != null);
    }
}
