package com.flux.deploy.plugin.toolwindow;

import com.flux.deploy.plugin.model.DeployTargetMode;
import com.intellij.openapi.project.Project;
import com.intellij.ui.components.JBPanel;

import javax.swing.*;
import javax.swing.event.ChangeListener;
import java.awt.*;
import java.util.function.Consumer;

/**
 * 目标区容器：JTabbedPane 承载 FTP / 本地两套子面板，
 * FTP 状态条以 overlay 形式覆盖到 tab strip 右侧空白区，与 tab 同行可见。
 *
 * <p>外层用 null 布局的 wrapper：tabs 占满全部空间，statusBar 通过手算位置贴到
 * tab strip 的右上角（z-order 在 tabs 之上）。tab 自身仍是 IDEA 原生 LaF，不引入
 * 自绘 toggle 样式偏差。</p>
 *
 * <p>本地模式下 statusBar 整条 setVisible(false)，避免误读连接信息。</p>
 *
 * @author xumanyi
 * @date 2026-04-17
 */
public class TargetContainerPanel extends JBPanel<TargetContainerPanel> {

    /** statusBar 距 tab strip 右边界的内缩留白 */
    private static final int STATUS_BAR_RIGHT_INSET = 6;

    private final TargetSectionPanel ftpPanel;
    private final LocalTargetPanel localPanel;
    private final JTabbedPane tabs;
    private final JPanel statusBar;
    private final JPanel overlayWrapper;

    private Consumer<DeployTargetMode> modeChangeCallback;

    public TargetContainerPanel(Project project) {
        super(new BorderLayout());
        this.ftpPanel = new TargetSectionPanel(project);
        this.localPanel = new LocalTargetPanel(project);
        this.tabs = new JTabbedPane();
        tabs.setTabPlacement(JTabbedPane.TOP);
        tabs.addTab(DeployTargetMode.FTP.getDisplayName(), ftpPanel);
        tabs.addTab(DeployTargetMode.LOCAL.getDisplayName(), localPanel);
        tabs.setToolTipTextAt(0, "连接 FTP，从远端选择目标包并上传更新");
        tabs.setToolTipTextAt(1, "不连 FTP，对本地 jar/war 直接打补丁生成新包");
        refreshUnselectedTabBackground();

        this.statusBar = ftpPanel.getFtpStatusBar();
        this.overlayWrapper = buildOverlayWrapper();
        add(overlayWrapper, BorderLayout.CENTER);

        ChangeListener l = e -> {
            refreshUnselectedTabBackground();
            applyVisibilityForMode(getCurrentMode());
            overlayWrapper.revalidate();
            overlayWrapper.repaint();
            if (modeChangeCallback != null) {
                modeChangeCallback.accept(getCurrentMode());
            }
        };
        tabs.addChangeListener(l);
        applyVisibilityForMode(getCurrentMode());
    }

    /**
     * 构造 overlay 容器：null 布局，tabs 撑满，statusBar 通过 doLayout 算到 tab strip 右上角。
     * z-order 把 statusBar 放在 tabs 上方，让 statusBar 的按钮在 tab strip 空白区也能响应点击。
     */
    private JPanel buildOverlayWrapper() {
        JPanel wrap = new JPanel((LayoutManager) null) {
            @Override
            public void doLayout() {
                tabs.setBounds(0, 0, getWidth(), getHeight());
                if (statusBar.isVisible()) {
                    Rectangle stripBounds = computeTabStripBounds();
                    Dimension pref = statusBar.getPreferredSize();
                    int x = Math.max(0, getWidth() - pref.width - STATUS_BAR_RIGHT_INSET);
                    int y = stripBounds.y + Math.max(0, (stripBounds.height - pref.height) / 2);
                    statusBar.setBounds(x, y, pref.width, pref.height);
                }
            }

            @Override
            public Dimension getPreferredSize() {
                return tabs.getPreferredSize();
            }
        };
        wrap.add(statusBar);
        wrap.add(tabs);
        // statusBar 渲染在 tabs 上方，按钮才能在 tab strip 空白区接收点击
        wrap.setComponentZOrder(statusBar, 0);
        wrap.setComponentZOrder(tabs, 1);
        return wrap;
    }

    /**
     * 计算 tab strip 的几何位置：取第 0 个 tab 的 bounds，宽不重要，
     * 只关心 y 和 height——statusBar 沿 strip 垂直居中。
     *
     * <p>UI 还未完成初始化时回退到 28px 兜底高度，避免 NPE 影响布局。</p>
     */
    private Rectangle computeTabStripBounds() {
        try {
            Rectangle first = tabs.getUI().getTabBounds(tabs, 0);
            if (first != null) return first;
        } catch (Exception ignored) {
            // UI 未就绪 / 索引越界 → 走兜底
        }
        return new Rectangle(0, 0, 0, 28);
    }

    /**
     * 让未选中 Tab 常驻 hover 色，提示用户该 Tab 可点击；
     * 选中 Tab 保持 L&amp;F 默认背景，走原生选中样式。
     */
    private void refreshUnselectedTabBackground() {
        int selected = tabs.getSelectedIndex();
        Color hoverBg = UIManager.getColor("TabbedPane.hoverColor");
        if (hoverBg == null) hoverBg = UIManager.getColor("List.hoverBackground");
        if (hoverBg == null) hoverBg = new Color(70, 73, 75);
        Color defaultBg = UIManager.getColor("TabbedPane.background");
        for (int i = 0; i < tabs.getTabCount(); i++) {
            tabs.setBackgroundAt(i, i == selected ? defaultBg : hoverBg);
        }
    }

    /**
     * 按当前模式更新 FTP 状态条可见性：本地模式下整条隐藏，避免误读连接信息。
     */
    private void applyVisibilityForMode(DeployTargetMode mode) {
        statusBar.setVisible(mode == DeployTargetMode.FTP);
    }

    /** @return 当前选中的模式 */
    public DeployTargetMode getCurrentMode() {
        return tabs.getSelectedIndex() == 0 ? DeployTargetMode.FTP : DeployTargetMode.LOCAL;
    }

    /** 设置模式切换回调 */
    public void setModeChangeCallback(Consumer<DeployTargetMode> callback) {
        this.modeChangeCallback = callback;
    }

    /** @return FTP 子面板 */
    public TargetSectionPanel getFtpPanel() { return ftpPanel; }

    /** @return 本地子面板 */
    public LocalTargetPanel getLocalPanel() { return localPanel; }
}
