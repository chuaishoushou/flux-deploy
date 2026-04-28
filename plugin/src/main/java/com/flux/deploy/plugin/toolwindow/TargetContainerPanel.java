package com.flux.deploy.plugin.toolwindow;

import com.flux.deploy.plugin.model.DeployTargetMode;
import com.intellij.openapi.project.Project;
import com.intellij.ui.components.JBPanel;

import javax.swing.*;
import javax.swing.event.ChangeListener;
import java.awt.*;
import java.util.function.Consumer;

/**
 * 目标区容器：通过 Tab 页承载 FTP 模式与本地模式
 *
 * <p>Tab 切换不影响各子面板内的状态，仅切换展示与执行流程。
 * 切换时通过回调通知外部面板更新按钮组与执行链路。</p>
 *
 * @author xumanyi
 * @date 2026-04-17
 */
public class TargetContainerPanel extends JBPanel<TargetContainerPanel> {

    private final TargetSectionPanel ftpPanel;
    private final LocalTargetPanel localPanel;
    private final JTabbedPane tabs;

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
        add(tabs, BorderLayout.CENTER);

        ChangeListener l = e -> {
            refreshUnselectedTabBackground();
            if (modeChangeCallback != null) {
                modeChangeCallback.accept(getCurrentMode());
            }
        };
        tabs.addChangeListener(l);
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
