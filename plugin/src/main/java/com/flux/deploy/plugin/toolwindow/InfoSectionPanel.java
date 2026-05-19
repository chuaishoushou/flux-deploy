package com.flux.deploy.plugin.toolwindow;

import com.flux.deploy.plugin.service.PluginSettingsService;
import com.intellij.openapi.project.Project;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBPanel;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.NamedColorUtil;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.font.TextAttribute;
import java.util.HashMap;
import java.util.Map;

/**
 * 信息 Section 面板：版本记录开关 + 任务号 + 客服号 + 操作人 + 备份位置
 *
 * <p>操作人从 {@link PluginSettingsService} 缓存中恢复（上次填写值），
 * 无缓存时为空，不提供硬编码默认值。</p>
 *
 * <p>勾选"更新版本记录"后显示任务号和客服号输入框，
 * 取消勾选时隐藏并清空（操作人保留缓存值）。</p>
 *
 * <p>勾选"执行备份"后显示"备份至"行：单一可点击路径标签。
 * 点击路径打开 {@link BackupLocationDialog} 修改本次 session 的备份根；
 * 弹窗内"恢复默认"按钮可清掉本次自定义。
 * 切换系统/项目时自动清空 sessionBackupRoot 回到默认派生路径，
 * 不做任何跨重启持久化（避免"很久前手滑选过的路径还沿用至今"的隐式 bug）。</p>
 *
 * @author xumanyi
 * @date 2026-03-27
 */
public class InfoSectionPanel extends JBPanel<InfoSectionPanel> {

    /**
     * FTP 上下文供给接口，由 {@link DeployToolWindowPanel} 桥接
     * {@code TargetSectionPanel} 的当前选择状态供给
     *
     * @author xumanyi
     * @date 2026-05-02
     */
    public interface FtpContextSupplier {
        /** @return FTP 是否已连接 */
        boolean isFtpConnected();
        /** @return FTP 主机；未连接时返回 null */
        String getHost();
        /** @return FTP 端口；未连接时返回 0 */
        int getPort();
        /** @return FTP 用户名；未连接时返回 null */
        String getUsername();
        /** @return FTP 密码；未连接时返回 null */
        String getPassword();
        /** @return 当前上下文目录（项目+系统 或 仅项目）；项目未选时返回 null */
        String getContextDir();
        /** @return 当前项目根目录（仅项目，不含系统）；项目未选时返回 null */
        String getProjectDir();
        /** @return 第一个目标的 remoteDir，用于显示默认派生路径；无目标时返回 null */
        String getFirstTargetRemoteDir();
    }

    private final Project project;
    private final JCheckBox updateNoteCheckBox;
    private final JCheckBox backupCheckBox;
    private final JPanel fieldsPanel;
    private final JBTextField taskIdField;
    private final JBTextField customerIdField;
    private final JBTextField operatorField;

    /** FTP 上下文供给器（可空，未注入时按未连接处理） */
    private FtpContextSupplier ftpContextSupplier;
    /** "备份至" 行容器 */
    private JPanel backupLocationRow;
    /** "备份至" 路径标签：本身就是按钮（hover 下划线 + 手型，点击打开弹窗） */
    private JBLabel backupLocationLabel;

    /**
     * 当前 session 的自定义备份根（FTP 绝对路径，含尾部 /）。
     *
     * <p>{@code null} 表示走默认派生路径。仅本次 session 有效——切换系统/项目、
     * IDE 重启后都会回到默认。设计为不持久化，避免"用户随手选错→所有后续部署
     * 默默沿用错误路径"的隐式 bug。</p>
     */
    private String sessionBackupRoot;

    /**
     * 构造信息面板
     *
     * @param project 当前 IDEA 项目
     * @author xumanyi
     * @date 2026-03-27
     */
    public InfoSectionPanel(Project project, JCheckBox backupCheckBox) {
        super(new BorderLayout());
        this.project = project;
        this.backupCheckBox = backupCheckBox;

        this.updateNoteCheckBox = new JCheckBox("更新版本记录", true);
        this.updateNoteCheckBox.setToolTipText("上传后追加版本更新记录");
        backupCheckBox.setToolTipText("上传前备份远程原包");
        this.taskIdField = new JBTextField();
        this.taskIdField.setToolTipText("任务号或任务描述");
        this.customerIdField = new JBTextField();
        this.customerIdField.setToolTipText("客服单号或客服描述");
        this.operatorField = new JBTextField();
        this.operatorField.setToolTipText("当前开发人员姓名");

        operatorField.setColumns(20);
        taskIdField.setColumns(20);
        customerIdField.setColumns(20);

        restoreFromCache();

        // 统一单网格：所有 label + field 共享同一列宽，确保视觉对齐。
        // 标签右对齐 + 不带冒号，跟源工程框的 addFormRow 同款规格。
        JPanel container = new JPanel(new GridBagLayout());
        GridBagConstraints g = new GridBagConstraints();
        g.anchor = GridBagConstraints.WEST;
        g.insets = new Insets(2, 0, 2, 0);

        // 行 0：勾选项，跨两列；备份选项后附小号灰色斜体使用提示
        g.gridy = 0;
        g.gridx = 0; g.gridwidth = 2; g.fill = GridBagConstraints.HORIZONTAL; g.weightx = 1.0;
        JPanel checkRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        checkRow.add(updateNoteCheckBox);
        checkRow.add(backupCheckBox);
        JBLabel backupHint = new JBLabel("· 重复备份会覆盖原包，多次更新请取消");
        backupHint.setFont(backupHint.getFont().deriveFont(Font.ITALIC, 11f));
        backupHint.setForeground(NamedColorUtil.getInactiveTextColor());
        backupHint.setToolTipText("同一天重复备份会覆盖前一次");
        checkRow.add(backupHint);
        container.add(checkRow, g);
        g.weightx = 0; g.fill = GridBagConstraints.NONE;

        // 行 1：备份至（仅勾"执行备份"时显示）
        // 长路径文本不应撑大左侧执行卡片的 preferred / minimum 宽度——否则会把
        // 主水平分割条往右挤，导致右侧"部署目标"列变窄。这里强制把宽度钳到 0，
        // 实际渲染宽度由外层 BorderLayout.CENTER 分配；超宽时由 ellipsizePath
        // 在文本中段截断（"..."），完整路径仍可通过 tooltip 查看。
        backupLocationLabel = new JBLabel(" ") {
            @Override
            public Dimension getPreferredSize() {
                Dimension d = super.getPreferredSize();
                return new Dimension(0, d.height);
            }

            @Override
            public Dimension getMinimumSize() {
                Dimension d = super.getMinimumSize();
                return new Dimension(0, d.height);
            }
        };
        // 路径标签即按钮：hover 下划线 + 手型，单击打开 BackupLocationDialog
        // 用 derive 出的 underline 字体做 hover 反馈，避免 setText 嵌 HTML 导致跟
        // ellipsizePath 的纯文本逻辑相互影响。
        final Font baseFont = backupLocationLabel.getFont();
        Map<TextAttribute, Object> underlineAttrs = new HashMap<>(baseFont.getAttributes());
        underlineAttrs.put(TextAttribute.UNDERLINE, TextAttribute.UNDERLINE_ON);
        final Font hoverFont = baseFont.deriveFont(underlineAttrs);
        backupLocationLabel.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (backupLocationLabel.isEnabled()) openBackupLocationDialog();
            }

            @Override
            public void mouseEntered(MouseEvent e) {
                if (backupLocationLabel.isEnabled()) {
                    backupLocationLabel.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
                    backupLocationLabel.setFont(hoverFont);
                }
            }

            @Override
            public void mouseExited(MouseEvent e) {
                backupLocationLabel.setCursor(Cursor.getDefaultCursor());
                backupLocationLabel.setFont(baseFont);
            }
        });

        backupLocationRow = new JPanel(new BorderLayout(8, 0));
        backupLocationRow.add(PanelChromes.rightLabel("备份至"), BorderLayout.WEST);
        backupLocationRow.add(backupLocationLabel, BorderLayout.CENTER);

        g.gridy = 1; g.gridx = 0; g.gridwidth = 2;
        g.fill = GridBagConstraints.HORIZONTAL; g.weightx = 1.0;
        container.add(backupLocationRow, g);
        g.gridwidth = 1; g.weightx = 0; g.fill = GridBagConstraints.NONE;

        // 行 2：开发（仅在需要时显示：勾选了版本记录或执行备份）
        JBLabel operatorLabel = PanelChromes.rightLabel("开发");
        g.gridy = 2; g.gridwidth = 1;
        g.gridx = 0; g.anchor = GridBagConstraints.EAST;
        g.fill = GridBagConstraints.NONE; g.weightx = 0;
        g.insets = new Insets(2, 0, 2, 8);
        container.add(operatorLabel, g);
        g.gridx = 1; g.anchor = GridBagConstraints.WEST;
        g.fill = GridBagConstraints.HORIZONTAL; g.weightx = 1.0;
        g.insets = new Insets(2, 0, 2, 0);
        container.add(operatorField, g);

        // 行 3 + 4：任务 / 客服，单独记录引用以便整体显隐
        JBLabel taskLabel = PanelChromes.rightLabel("任务");
        JBLabel customerLabel = PanelChromes.rightLabel("客服");

        g.gridy = 3;
        g.gridx = 0; g.anchor = GridBagConstraints.EAST;
        g.fill = GridBagConstraints.NONE; g.weightx = 0;
        g.insets = new Insets(2, 0, 2, 8);
        container.add(taskLabel, g);
        g.gridx = 1; g.anchor = GridBagConstraints.WEST;
        g.fill = GridBagConstraints.HORIZONTAL; g.weightx = 1.0;
        g.insets = new Insets(2, 0, 2, 0);
        container.add(taskIdField, g);

        g.gridy = 4;
        g.gridx = 0; g.anchor = GridBagConstraints.EAST;
        g.fill = GridBagConstraints.NONE; g.weightx = 0;
        g.insets = new Insets(2, 0, 2, 8);
        container.add(customerLabel, g);
        g.gridx = 1; g.anchor = GridBagConstraints.WEST;
        g.fill = GridBagConstraints.HORIZONTAL; g.weightx = 1.0;
        g.insets = new Insets(2, 0, 2, 0);
        container.add(customerIdField, g);

        // fieldsPanel 不再作为容器使用，但保留字段以兼容既有 reset() 逻辑
        fieldsPanel = new JPanel();
        fieldsPanel.setVisible(true);

        add(container, BorderLayout.CENTER);

        Runnable refreshVisibility = () -> {
            boolean note = updateNoteCheckBox.isSelected();
            boolean backup = backupCheckBox.isSelected();
            // 任务/客服：仅版本记录勾选时显示
            taskLabel.setVisible(note);
            taskIdField.setVisible(note);
            customerLabel.setVisible(note);
            customerIdField.setVisible(note);
            fieldsPanel.setVisible(note);
            if (!note) {
                taskIdField.setText("");
                customerIdField.setText("");
            }
            // 开发：版本记录或备份任一勾选时显示；都没勾则隐藏，不必填
            boolean showOperator = note || backup;
            operatorLabel.setVisible(showOperator);
            operatorField.setVisible(showOperator);
            // 备份至：仅"执行备份"勾选时显示
            backupLocationRow.setVisible(backup);
            if (backup) {
                refreshBackupLocationLabel();
            }
            revalidate();
            repaint();
        };

        updateNoteCheckBox.addActionListener(e -> refreshVisibility.run());
        backupCheckBox.addActionListener(e -> refreshVisibility.run());
        // 初始同步
        refreshVisibility.run();
    }

    /**
     * 从缓存恢复上次输入值
     */
    private void restoreFromCache() {
        PluginSettingsService settings = project.getService(PluginSettingsService.class);
        if (settings != null && settings.getState() != null) {
            PluginSettingsService.State state = settings.getState();
            if (state.lastOperator != null && !state.lastOperator.isBlank()) {
                operatorField.setText(state.lastOperator);
            }
        }
    }

    /**
     * 保存当前值到缓存
     */
    public void saveToCache() {
        PluginSettingsService settings = project.getService(PluginSettingsService.class);
        if (settings != null && settings.getState() != null) {
            PluginSettingsService.State state = settings.getState();
            String op = operatorField.getText().trim();
            if (!op.isEmpty()) state.lastOperator = op;
        }
    }

    /**
     * 重置表单
     */
    public void reset() {
        updateNoteCheckBox.setSelected(false);
        taskIdField.setText("");
        customerIdField.setText("");
        for (java.awt.event.ActionListener l : updateNoteCheckBox.getActionListeners()) {
            l.actionPerformed(new java.awt.event.ActionEvent(
                    updateNoteCheckBox, java.awt.event.ActionEvent.ACTION_PERFORMED, null));
        }
        restoreFromCache();
        revalidate();
        repaint();
    }

    /** @return 是否勾选了更新版本记录
     * @author xumanyi
     * @date 2026-03-27
     */
    public boolean isUpdateNote() { return updateNoteCheckBox.isSelected(); }
    /** @return 任务号（去除首尾空白）
     * @author xumanyi
     * @date 2026-03-27
     */
    public String getTaskId() { return taskIdField.getText().trim(); }
    /** @return 客服号（去除首尾空白）
     * @author xumanyi
     * @date 2026-03-27
     */
    public String getCustomerId() { return customerIdField.getText().trim(); }
    /** @return 操作人（去除首尾空白）
     * @author xumanyi
     * @date 2026-03-27
     */
    public String getOperator() { return operatorField.getText().trim(); }

    // ==================== 备份位置自定义 ====================

    /**
     * 设置 FTP 上下文供给器（由 {@link DeployToolWindowPanel} 在初始化时调用）
     *
     * @param supplier 供给器
     * @author xumanyi
     * @date 2026-05-02
     */
    public void setFtpContextSupplier(FtpContextSupplier supplier) {
        this.ftpContextSupplier = supplier;
        refreshBackupLocationLabel();
    }

    /**
     * 刷新"备份至"行的显示路径与点击启用态
     *
     * <p>FTP 未连接 / 项目未选 → 标签 disabled + 灰色提示文案，点击不响应；
     * 否则启用点击：显示当前 sessionBackupRoot（已自定义，常规色） 或
     * 默认派生路径（灰色）。</p>
     *
     * @author xumanyi
     * @date 2026-05-02
     */
    public void refreshBackupLocationLabel() {
        if (backupLocationRow == null) return; // 构造期间被调用时
        if (ftpContextSupplier == null || !ftpContextSupplier.isFtpConnected()) {
            backupLocationLabel.setEnabled(false);
            backupLocationLabel.setText("（请先连接 FTP）");
            backupLocationLabel.setToolTipText(null);
            backupLocationLabel.setForeground(UIManager.getColor("Label.disabledForeground"));
            return;
        }
        String contextDir = ftpContextSupplier.getContextDir();
        if (contextDir == null || contextDir.isBlank()) {
            backupLocationLabel.setEnabled(false);
            backupLocationLabel.setText("（请先选择项目）");
            backupLocationLabel.setToolTipText(null);
            backupLocationLabel.setForeground(UIManager.getColor("Label.disabledForeground"));
            return;
        }

        backupLocationLabel.setEnabled(true);
        if (sessionBackupRoot != null && !sessionBackupRoot.isBlank()) {
            // 已自定义：常规色文字
            backupLocationLabel.setText(ellipsizePath(sessionBackupRoot));
            backupLocationLabel.setToolTipText("本次自定义：" + sessionBackupRoot);
            backupLocationLabel.setForeground(UIManager.getColor("Label.foreground"));
        } else {
            // 默认派生路径：灰色文字
            String defaultPath = computeDefaultBackupRoot();
            backupLocationLabel.setText(ellipsizePath(defaultPath));
            backupLocationLabel.setToolTipText("默认位置：" + defaultPath);
            backupLocationLabel.setForeground(UIManager.getColor("Label.disabledForeground"));
        }
    }

    /**
     * 计算默认派生备份路径
     *
     * <p>取第一个目标的 remoteDir 走 resolveSystemRoot 前 3 级 + backup/。
     * 无目标时回落到 contextDir + backup/。</p>
     *
     * @return 默认派生备份路径
     * @author xumanyi
     * @date 2026-05-02
     */
    private String computeDefaultBackupRoot() {
        String first = ftpContextSupplier.getFirstTargetRemoteDir();
        String basePath = (first != null && !first.isBlank())
                ? first
                : ftpContextSupplier.getContextDir();
        // 复用与 DeployExecutionService 一致的前 3 级规则
        String trimmed = basePath.replaceAll("^/+", "").replaceAll("/+$", "");
        String[] parts = trimmed.split("/");
        String systemRoot;
        if (parts.length >= 3) {
            systemRoot = "/" + parts[0] + "/" + parts[1] + "/" + parts[2] + "/";
        } else {
            systemRoot = "/" + trimmed + "/";
        }
        return systemRoot + "backup/";
    }

    /**
     * 获取本次 session 内用户选定的自定义备份根
     *
     * <p>由 {@link DeployToolWindowPanel} 在打包前调用，注入到 {@link
     * com.flux.deploy.plugin.model.PluginDeployConfig#setCustomBackupRoot}。
     * 已不再走持久化缓存——重启 IDE 或切系统/项目都会回到 {@code null}（默认派生）。</p>
     *
     * @return 自定义备份根；未自定义时返回 {@code null}
     * @author xumanyi
     * @date 2026-05-03
     */
    public String getSessionBackupRoot() {
        return sessionBackupRoot;
    }

    /**
     * 清除当前 session 的自定义备份根并刷新显示
     *
     * <p>{@link DeployToolWindowPanel} 在 FTP 上下文变化（项目/系统切换）时调用，
     * 避免"我换了客户结果备份还在前一个客户的目录"这种事故。</p>
     *
     * @author xumanyi
     * @date 2026-05-03
     */
    public void clearSessionBackupRoot() {
        if (sessionBackupRoot != null) {
            sessionBackupRoot = null;
            refreshBackupLocationLabel();
        }
    }

    /**
     * 路径过长时居中省略
     *
     * @param path 原始路径
     * @return 截断后的路径
     * @author xumanyi
     * @date 2026-05-02
     */
    private static String ellipsizePath(String path) {
        if (path == null) return "";
        if (path.length() <= 60) return path;
        return path.substring(0, 28) + " ... " + path.substring(path.length() - 28);
    }

    /**
     * 打开备份位置选择对话框
     *
     * <p>弹窗 OK → 把所选路径写入 {@code sessionBackupRoot}；
     * 弹窗"恢复默认"按钮 → 把 {@code sessionBackupRoot} 置 null；
     * 弹窗取消 → 什么都不做。</p>
     *
     * @author xumanyi
     * @date 2026-05-02
     */
    private void openBackupLocationDialog() {
        if (ftpContextSupplier == null || !ftpContextSupplier.isFtpConnected()) return;
        String contextDir = ftpContextSupplier.getContextDir();
        if (contextDir == null) return;
        String projectDir = ftpContextSupplier.getProjectDir();
        if (projectDir == null) return;

        BackupLocationDialog dialog = new BackupLocationDialog(
                project,
                ftpContextSupplier.getHost(), ftpContextSupplier.getPort(),
                ftpContextSupplier.getUsername(), ftpContextSupplier.getPassword(),
                projectDir, contextDir, sessionBackupRoot);
        if (!dialog.showAndGet()) return; // 用户取消

        if (dialog.isResetRequested()) {
            sessionBackupRoot = null;
        } else {
            String picked = dialog.getResultBackupRoot();
            if (picked != null && !picked.isBlank()) {
                sessionBackupRoot = picked;
            }
        }
        refreshBackupLocationLabel();
    }
}
