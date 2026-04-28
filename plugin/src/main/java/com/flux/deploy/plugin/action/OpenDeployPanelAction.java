package com.flux.deploy.plugin.action;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import org.jetbrains.annotations.NotNull;

/**
 * 打开更新面板
 *
 * <p>激活 FLUX 客服更新工具窗口，不指定特定模式或模块，
 * 供用户手动在面板中选择。</p>
 *
 * @author xumanyi
 * @date 2026-03-27
 */
public class OpenDeployPanelAction extends AnAction {

    /**
     * 激活 FLUX 客服更新工具窗口
     *
     * @param e 动作事件
     * @author xumanyi
     * @date 2026-03-27
     */
    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        if (e.getProject() == null) return;

        ToolWindow tw = ToolWindowManager.getInstance(e.getProject())
                .getToolWindow("FLUX Deploy");
        if (tw != null) {
            tw.activate(null);
        }
    }
}
