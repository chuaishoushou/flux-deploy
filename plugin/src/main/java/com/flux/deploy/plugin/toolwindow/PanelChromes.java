package com.flux.deploy.plugin.toolwindow;

import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBLabel;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.NamedColorUtil;

import javax.swing.*;
import javax.swing.border.AbstractBorder;
import java.awt.*;

/**
 * 卡片框组件公共装饰：圆角外框、主色重音条、标题栏图标按钮样式与分隔线色。
 *
 * <p>各 Section 面板（源工程 / 部署目标 / 执行操作 / 运行日志）共用此处的工具，
 * 保证四块面板的边框半径、配色、标题栏图标按钮触感完全一致。</p>
 *
 * @author xumanyi
 * @date 2026-05-15
 */
final class PanelChromes {

    /** 卡片外框圆角半径（与设计基线一致：稍柔但不夸张） */
    static final int CARD_RADIUS = 10;

    /** 标题栏左侧主色短条尺寸（与 14pt 粗体标题字号成比例） */
    private static final int ACCENT_STRIP_WIDTH = 4;
    private static final int ACCENT_STRIP_HEIGHT = 16;

    /** 标题栏图标按钮统一边长（紧凑型，IDEA 工具窗常见 18-20px 区间） */
    private static final int HEADER_ICON_BUTTON_SIZE = 20;

    private PanelChromes() {}

    /**
     * 构造卡片外框：1px {@code Component.borderColor} 描边，圆角 {@value #CARD_RADIUS}px
     *
     * @return 边框实例
     * @author xumanyi
     * @date 2026-05-15
     */
    static AbstractBorder cardBorder() {
        return new RoundedBorder(CARD_RADIUS);
    }

    /**
     * 构造标题栏左侧的主色重音短条
     *
     * <p>固定 {@value #ACCENT_STRIP_WIDTH}×{@value #ACCENT_STRIP_HEIGHT}px，圆角填充，颜色取 Link.activeForeground 主题色。</p>
     *
     * @return 重音条组件
     * @author xumanyi
     * @date 2026-05-15
     */
    static JComponent accentStrip() {
        return new AccentStrip();
    }

    /**
     * 套用标题栏图标按钮样式：透明底、无边框、紧凑尺寸、不抢焦点
     *
     * <p>与运行日志卡片头部三个图标按钮（清空 / 全屏 / 最小化）样式一致。</p>
     *
     * @param button 待设置样式的按钮
     * @author xumanyi
     * @date 2026-05-15
     */
    static void styleHeaderIconButton(JButton button) {
        button.setBorderPainted(false);
        button.setContentAreaFilled(false);
        button.setFocusPainted(false);
        button.setFocusable(false);
        button.setMargin(JBUI.emptyInsets());
        Dimension size = new Dimension(HEADER_ICON_BUTTON_SIZE, HEADER_ICON_BUTTON_SIZE);
        // 三档尺寸都锁死：BoxLayout / GridBag 等容器才不会把按钮拉宽拉高
        button.setPreferredSize(size);
        button.setMinimumSize(size);
        button.setMaximumSize(size);
    }

    /**
     * 表单标签公共构造：右对齐 + 不带冒号。
     *
     * <p>四块面板（源工程 / 部署目标 / 执行操作 / ...）的标签都用同一规格：
     * 文字右对齐到固定的标签列宽，去掉冒号视觉更干净；输入框起点严格对齐。</p>
     *
     * @param text 标签文本（不含冒号）
     * @return 右对齐 JBLabel
     * @author xumanyi
     * @date 2026-05-16
     */
    static JBLabel rightLabel(String text) {
        JBLabel lbl = new JBLabel(text);
        lbl.setHorizontalAlignment(SwingConstants.RIGHT);
        return lbl;
    }

    /**
     * 主题感知的主色：用于重音条
     *
     * @return 主色，永远非 {@code null}
     * @author xumanyi
     * @date 2026-05-15
     */
    static Color accentColor() {
        Color c = UIManager.getColor("Link.activeForeground");
        return c != null ? c : new JBColor(new Color(0x2E7BE0), new Color(0x589DF6));
    }

    /**
     * 构造统一规格的标题栏：左侧主色短条 + 「标题」+ 可选 meta + 右侧图标动作组，
     * 底部 1px 分隔线把标题与内容区切开。
     *
     * <p>四个 Section 面板（源工程 / 部署目标 / 执行操作 / 运行日志）共用此处的标题栏，
     * 保证字号、间距、对齐、分隔线完全一致。</p>
     *
     * @param title   标题文本
     * @param meta    可空。非空时跟在标题右边显示，灰色 11pt（产物名 / 当前选中 / 计数等）。
     *                调用方可以保留引用做后续 setText 动态刷新。
     * @param actions 0 个或多个标题栏右侧的图标按钮（已经过 {@link #styleHeaderIconButton} 处理）
     * @return 标题栏面板
     * @author xumanyi
     * @date 2026-05-16
     */
    static JPanel buildTitleBar(String title, JBLabel meta, JComponent... actions) {
        JPanel titleBar = new JPanel(new BorderLayout(8, 0));
        titleBar.setOpaque(false);
        titleBar.setBorder(BorderFactory.createCompoundBorder(
                JBUI.Borders.customLine(separatorColor(), 0, 0, 1, 0),
                JBUI.Borders.empty(8, 10, 7, 6)));

        // 左：主色短条 + 标题 + 可选 meta，BoxLayout 横向排列，末尾 glue 把整组推到左侧
        JPanel left = new JPanel();
        left.setLayout(new BoxLayout(left, BoxLayout.X_AXIS));
        left.setOpaque(false);

        // 锁死行高 = 图标按钮高度，无论本卡片是否带 action 图标，
        // 标题栏底分隔线 Y 都与"带图标卡片"完全一致（避免左右两列横线错位）。
        // 必须用 RigidArea：X_AXIS BoxLayout 下 verticalStrut 的 max width 是 32767，
        // 会被横向拉伸，把后面的标题连同 glue 一起挤到居中。
        left.add(Box.createRigidArea(new Dimension(0, HEADER_ICON_BUTTON_SIZE)));

        JComponent accent = accentStrip();
        accent.setAlignmentY(Component.CENTER_ALIGNMENT);
        left.add(accent);
        left.add(Box.createHorizontalStrut(8));

        JBLabel titleLabel = new JBLabel(title);
        titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD, 14f));
        titleLabel.setAlignmentY(Component.CENTER_ALIGNMENT);
        left.add(titleLabel);

        if (meta != null) {
            left.add(Box.createHorizontalStrut(10));
            if (meta.getForeground() == null) {
                meta.setForeground(NamedColorUtil.getInactiveTextColor());
            }
            meta.setAlignmentY(Component.CENTER_ALIGNMENT);
            left.add(meta);
        }
        left.add(Box.createHorizontalGlue());

        titleBar.add(left, BorderLayout.CENTER);

        if (actions != null && actions.length > 0) {
            JPanel actionsPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 2, 0));
            actionsPanel.setOpaque(false);
            for (JComponent a : actions) {
                if (a != null) actionsPanel.add(a);
            }
            titleBar.add(actionsPanel, BorderLayout.EAST);
        }
        return titleBar;
    }

    /**
     * 主题感知的分隔线色：用于标题栏底线与状态行顶线
     *
     * @return 分隔线色，永远非 {@code null}
     * @author xumanyi
     * @date 2026-05-15
     */
    static Color separatorColor() {
        Color c = UIManager.getColor("Component.borderColor");
        if (c == null) c = UIManager.getColor("Separator.foreground");
        if (c == null) c = new JBColor(new Color(0xD1D1D6), new Color(0x424245));
        return c;
    }

    /**
     * 主题感知的分割器线条色：用于 mainSplit / leftSplit / rightSplit 的 divider 背景。
     *
     * <p>比 {@link #separatorColor()} 略强一档——IDEA New UI 工具窗之间的 1px 分隔线
     * 用的就是这套色（亮 #C4C4C4 / 暗 #515459）。在没有面板外框的"borderless"布局里，
     * 这条线是用户唯一能识别区域边界的视觉锚点，不能太弱。</p>
     *
     * @return 分割器线条色
     * @author xumanyi
     * @date 2026-05-16
     */
    static Color splitterColor() {
        return JBColor.namedColor("OnePixelDivider.background",
                new JBColor(new Color(0xC4C4C4), new Color(0x515459)));
    }

    /** 圆角描边：1px Component.borderColor 抗锯齿，圆角半径可配 */
    private static final class RoundedBorder extends AbstractBorder {
        private final int radius;

        RoundedBorder(int radius) {
            this.radius = radius;
        }

        @Override
        public void paintBorder(Component c, Graphics g, int x, int y, int w, int h) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(separatorColor());
                g2.drawRoundRect(x, y, w - 1, h - 1, radius, radius);
            } finally {
                g2.dispose();
            }
        }

        @Override
        public Insets getBorderInsets(Component c) {
            return JBUI.insets(1);
        }
    }

    /** 主色短条：填充圆角矩形，水平/垂直布局都能正常显示 */
    private static final class AccentStrip extends JComponent {
        AccentStrip() {
            setOpaque(false);
            Dimension size = new Dimension(ACCENT_STRIP_WIDTH, ACCENT_STRIP_HEIGHT);
            setPreferredSize(size);
            setMinimumSize(size);
            setMaximumSize(size);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(accentColor());
                int w = getWidth();
                int h = getHeight();
                g2.fillRoundRect(0, 0, w, h, ACCENT_STRIP_WIDTH, ACCENT_STRIP_WIDTH);
            } finally {
                g2.dispose();
            }
        }
    }
}
