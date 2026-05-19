package com.flux.deploy.plugin.toolwindow;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * FLUX 客服更新 Tool Window 工厂
 *
 * <p>负责创建和管理 FLUX 客服更新工具窗口的内容。
 * 通过 {@link #getPanel(Project)} 静态方法可获取已创建的面板实例，
 * 供 Action 类设置模块和模式。</p>
 *
 * @author xumanyi
 * @date 2026-03-27
 */
public class FluxDeployToolWindowFactory implements ToolWindowFactory {

    /**
     * 创建工具窗口内容
     *
     * <p>实例化 {@link DeployToolWindowPanel} 并将其添加到工具窗口中，
     * 同时通过 {@link com.intellij.openapi.util.Key} 关联面板引用以供后续获取。</p>
     *
     * @param project    当前项目
     * @param toolWindow 工具窗口实例
     * @author xumanyi
     * @date 2026-03-27
     */
    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        DeployToolWindowPanel panel = new DeployToolWindowPanel(project);

        // 设置默认宽度为屏幕宽度的 50%（首次打开时 IDE 参考此值）
        java.awt.Dimension screenSize = java.awt.Toolkit.getDefaultToolkit().getScreenSize();
        panel.setPreferredSize(new java.awt.Dimension(screenSize.width / 2, 0));

        Content content = ContentFactory.getInstance().createContent(panel, "", false);
        content.putUserData(PANEL_KEY, panel);
        toolWindow.getContentManager().addContent(content);
        String version = getPluginVersion();
        toolWindow.setStripeTitle("FLUX 客服更新  v" + version);

        // 注册标题栏图标：使用手册 / 更新日志
        // 通知邮件入口改成主面板「重置」按钮旁的「邮件模板」按钮，不再占顶栏图标位
        toolWindow.setTitleActions(java.util.List.of(
                new com.flux.deploy.plugin.action.ShowHelpAction(),
                new com.flux.deploy.plugin.action.ShowChangelogAction()
        ));
    }

    /**
     * 从 IDEA 插件管理器动态获取本插件版本号
     *
     * <p>使用公共 API {@link com.intellij.ide.plugins.PluginManager#findEnabledPlugin}
     * 替代 internal {@code PluginManagerCore.getPlugin}，
     * 通过 Marketplace Plugin Verifier。</p>
     */
    private static String getPluginVersion() {
        com.intellij.ide.plugins.IdeaPluginDescriptor plugin =
                com.intellij.ide.plugins.PluginManager.getInstance().findEnabledPlugin(
                        com.intellij.openapi.extensions.PluginId.getId("com.flux.deploy.plugin"));
        return plugin != null ? plugin.getVersion() : "1.0.0";
    }

    private static final com.intellij.openapi.util.Key<DeployToolWindowPanel> PANEL_KEY =
            com.intellij.openapi.util.Key.create("FluxDeployPanel");

    /**
     * 获取已创建的面板实例
     *
     * @param project 当前项目
     * @return 部署面板实例，如果工具窗口尚未创建则返回 {@code null}
     * @author xumanyi
     * @date 2026-03-27
     */
    @Nullable
    public static DeployToolWindowPanel getPanel(@NotNull Project project) {
        ToolWindow tw = ToolWindowManager.getInstance(project).getToolWindow("FLUX Deploy");
        if (tw == null || tw.getContentManager().getContentCount() == 0) return null;
        Content content = tw.getContentManager().getContent(0);
        return content != null ? content.getUserData(PANEL_KEY) : null;
    }
}
