package com.flux.deploy.plugin.toolwindow;

import com.intellij.openapi.util.IconLoader;

import javax.swing.Icon;

/**
 * 工具窗口标题栏自定义图标常量。
 *
 * <p>图标为单色 SVG，放在 {@code resources/icons/} 下：一部分取自项目自带的
 * iconfont 库（font_295756，已清洗掉 path 上写死的 fill），一部分按 16px 网格
 * 手绘（1.2px 圆角描边风格）；颜色统一为中性灰。
 * 每个图标都配一个 {@code _dark} 变体（亮色 {@code #6E6E6E} / 暗色 {@code #AFB1B3}），
 * {@link IconLoader} 会在暗色主题下自动加载 {@code xxx_dark.svg}，调用方无需关心主题。</p>
 *
 * <p>用于替换标题栏几个按钮原先的 {@code AllIcons.*} 内置图标（刷新 / 关闭 / 全屏 /
 * 退出全屏 / 清空日志）；其余按钮仍沿用 {@code AllIcons}。</p>
 *
 * @author xumanyi
 * @date 2026-05-29
 */
final class PluginIcons {

    private PluginIcons() {
    }

    /** 刷新：源工程列表刷新、部署目标重连刷新共用（iconfont e6fa） */
    static final Icon REFRESH = IconLoader.getIcon("/icons/refresh.svg", PluginIcons.class);

    /** 关闭 / 清除：本地模式「清除已选源包」（iconfont e64a） */
    static final Icon CLOSE = IconLoader.getIcon("/icons/close.svg", PluginIcons.class);

    /** 全屏：运行日志区放大铺满（四角外框角标，手绘） */
    static final Icon FULLSCREEN = IconLoader.getIcon("/icons/fullscreen.svg", PluginIcons.class);

    /** 退出全屏：运行日志区从全屏还原（四角内收角标，手绘） */
    static final Icon EXIT_FULLSCREEN = IconLoader.getIcon("/icons/exit_fullscreen.svg", PluginIcons.class);

    /** 删除 / 清空运行日志（iconfont e74b） */
    static final Icon DELETE = IconLoader.getIcon("/icons/delete.svg", PluginIcons.class);

    /** 还原窗口：下排折叠态恢复「执行操作 + 运行日志」面板（经典双叠矩形还原符号，手绘） */
    static final Icon RESTORE = IconLoader.getIcon("/icons/restore.svg", PluginIcons.class);
}
