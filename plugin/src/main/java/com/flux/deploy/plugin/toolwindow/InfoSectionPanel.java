package com.flux.deploy.plugin.toolwindow;

import com.flux.deploy.plugin.service.PluginSettingsService;
import com.intellij.openapi.project.Project;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBPanel;
import com.intellij.ui.components.JBTextField;

import javax.swing.*;
import java.awt.*;

/**
 * 信息 Section 面板：版本记录开关 + 任务号 + 客服号 + 操作人
 *
 * <p>操作人从 {@link PluginSettingsService} 缓存中恢复（上次填写值），
 * 无缓存时为空，不提供硬编码默认值。</p>
 *
 * <p>勾选"更新版本记录"后显示任务号和客服号输入框，
 * 取消勾选时隐藏并清空（操作人保留缓存值）。</p>
 *
 * @author xumanyi
 * @date 2026-03-27
 */
public class InfoSectionPanel extends JBPanel<InfoSectionPanel> {

    /**
     * FTP 上下文供给接口，由 {@link DeployToolWindowPanel} 桥接
     * {@code TargetSectionPanel.getCurrentContextDir()} 等方法供给
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
    /** "备份至" 路径标签 */
    private JBLabel backupLocationLabel;
    /** "更改" 按钮 */
    private JButton backupLocationChangeButton;

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
        this.updateNoteCheckBox.setToolTipText(
                "<html>上传成功后在远程目录追加 <b>*_update_note.txt</b> 更新记录，"
                + "<br>写入 取包/传包 两行，包含时间、开发、任务、客服、包名。"
                + "<br>不勾选则跳过版本记录。</html>");
        backupCheckBox.setToolTipText(
                "<html>上传前将远程原包备份到 <b>备份/yyyyMMdd_开发/</b> 目录，"
                + "<br>更新失败可从备份自动回滚。"
                + "<br>不勾选则无备份，失败后无法自动回滚。</html>");
        this.taskIdField = new JBTextField();
        this.taskIdField.setToolTipText(
                "<html>任务描述或任务号，支持中文与长文本。"
                + "<br>写入版本记录时会加「任务：」前缀。"
                + "<br>与客服至少填一项。</html>");
        this.customerIdField = new JBTextField();
        this.customerIdField.setToolTipText(
                "<html>客服单号或客服描述。"
                + "<br>写入版本记录时会加「客服：」前缀。"
                + "<br>与任务至少填一项。</html>");
        this.operatorField = new JBTextField();
        this.operatorField.setToolTipText(
                "<html>当前开发人员名字。"
                + "<br>用于备份目录命名、锁文件归属、版本记录。"
                + "<br>勾选版本记录或执行备份时必填。</html>");

        operatorField.setColumns(20);
        taskIdField.setColumns(20);
        customerIdField.setColumns(20);

        restoreFromCache();

        // 统一单网格：所有 label + field 共享同一列宽，确保视觉对齐
        //   行 0：☑版本记录  ☑执行备份
        //   行 1：开发： [________________]
        //   行 2：任务： [________________]   （勾选版本记录后显示）
        //   行 3：客服： [________________]   （同上）
        JPanel container = new JPanel(new GridBagLayout());
        GridBagConstraints g = new GridBagConstraints();
        g.anchor = GridBagConstraints.WEST;
        g.insets = new Insets(2, 2, 2, 2);

        // 行 0：勾选项，跨两列；备份选项后附小号灰色斜体使用提示
        g.gridy = 0;
        g.gridx = 0; g.gridwidth = 2; g.fill = GridBagConstraints.HORIZONTAL; g.weightx = 1.0;
        JPanel checkRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        checkRow.add(updateNoteCheckBox);
        checkRow.add(backupCheckBox);
        JBLabel backupHint = new JBLabel("· 重复备份会覆盖原包，多次更新请取消");
        backupHint.setFont(backupHint.getFont().deriveFont(Font.ITALIC, 11f));
        backupHint.setForeground(UIManager.getColor("Label.disabledForeground"));
        backupHint.setToolTipText(
                "<html>若同一开发在同一天多次更新同一个包，"
                + "<br>后一次备份会覆盖前一次，原始远程包会永久丢失。"
                + "<br>多次更新时建议取消此项，用第一次的备份回滚即可。</html>");
        checkRow.add(backupHint);
        container.add(checkRow, g);
        g.weightx = 0; g.fill = GridBagConstraints.NONE;

        // 行 1：备份至（仅勾"执行备份"时显示）
        backupLocationLabel = new JBLabel(" ");
        backupLocationLabel.setToolTipText("当前生效的备份根目录");
        backupLocationChangeButton = new JButton("更改");
        backupLocationChangeButton.setMargin(new Insets(2, 8, 2, 8));
        backupLocationChangeButton.addActionListener(e -> openBackupLocationDialog());

        backupLocationRow = new JPanel(new BorderLayout(8, 0));
        JPanel pathPanel = new JPanel(new BorderLayout());
        JBLabel prefix = new JBLabel("备份至：");
        pathPanel.add(prefix, BorderLayout.WEST);
        pathPanel.add(backupLocationLabel, BorderLayout.CENTER);
        backupLocationRow.add(pathPanel, BorderLayout.CENTER);
        backupLocationRow.add(backupLocationChangeButton, BorderLayout.EAST);

        g.gridy = 1; g.gridx = 0; g.gridwidth = 2;
        g.fill = GridBagConstraints.HORIZONTAL; g.weightx = 1.0;
        container.add(backupLocationRow, g);
        g.gridwidth = 1; g.weightx = 0; g.fill = GridBagConstraints.NONE;

        // 行 2：开发（仅在需要时显示：勾选了版本记录或执行备份）
        JBLabel operatorLabel = new JBLabel("开发：");
        g.gridy = 2; g.gridwidth = 1;
        g.gridx = 0; g.fill = GridBagConstraints.NONE; g.weightx = 0;
        container.add(operatorLabel, g);
        g.gridx = 1; g.fill = GridBagConstraints.HORIZONTAL; g.weightx = 1.0;
        container.add(operatorField, g);

        // 行 3 + 4：任务 / 客服，单独记录引用以便整体显隐
        JBLabel taskLabel = new JBLabel("任务：");
        JBLabel customerLabel = new JBLabel("客服：");

        g.gridy = 3;
        g.gridx = 0; g.fill = GridBagConstraints.NONE; g.weightx = 0;
        container.add(taskLabel, g);
        g.gridx = 1; g.fill = GridBagConstraints.HORIZONTAL; g.weightx = 1.0;
        container.add(taskIdField, g);

        g.gridy = 4;
        g.gridx = 0; g.fill = GridBagConstraints.NONE; g.weightx = 0;
        container.add(customerLabel, g);
        g.gridx = 1; g.fill = GridBagConstraints.HORIZONTAL; g.weightx = 1.0;
        container.add(customerIdField, g);

        // fieldsPanel 不再作为容器使用，但保留字段以兼容既有 reset() 逻辑；
        // 改为持有"行 2/3 需要显隐的组件集合"
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
            // 任务号和客服号不缓存（每次不同）
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
            // 任务号和客服号不缓存（每次不同）
        }
    }

    /**
     * 重置表单
     */
    public void reset() {
        updateNoteCheckBox.setSelected(false);
        taskIdField.setText("");
        customerIdField.setText("");
        // 触发监听器以同步任务/客服行显隐
        for (java.awt.event.ActionListener l : updateNoteCheckBox.getActionListeners()) {
            l.actionPerformed(new java.awt.event.ActionEvent(
                    updateNoteCheckBox, java.awt.event.ActionEvent.ACTION_PERFORMED, null));
        }
        // 操作人保留缓存值
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
     * 刷新"备份至"行的显示路径与"更改"按钮启用态
     *
     * <p>FTP 未连接 / 项目未选 → 按钮禁用 + tooltip 解释；
     * 已配置 customBackupRoot → 显示该路径；
     * 否则显示默认派生路径（灰色 + (默认) 标签）。</p>
     *
     * @author xumanyi
     * @date 2026-05-02
     */
    public void refreshBackupLocationLabel() {
        if (backupLocationRow == null) return; // 构造期间被调用时
        if (ftpContextSupplier == null || !ftpContextSupplier.isFtpConnected()) {
            backupLocationChangeButton.setEnabled(false);
            backupLocationChangeButton.setToolTipText("请先连接 FTP");
            backupLocationLabel.setText("（请先连接 FTP）");
            backupLocationLabel.setForeground(UIManager.getColor("Label.disabledForeground"));
            return;
        }
        String contextDir = ftpContextSupplier.getContextDir();
        if (contextDir == null || contextDir.isBlank()) {
            backupLocationChangeButton.setEnabled(false);
            backupLocationChangeButton.setToolTipText("请先选择项目");
            backupLocationLabel.setText("（请先选择项目）");
            backupLocationLabel.setForeground(UIManager.getColor("Label.disabledForeground"));
            return;
        }

        backupLocationChangeButton.setEnabled(true);
        backupLocationChangeButton.setToolTipText(null);

        String custom = readCustomBackupRoot(contextDir);
        if (custom != null && !custom.isBlank()) {
            backupLocationLabel.setText(ellipsizePath(custom));
            backupLocationLabel.setToolTipText(custom);
            backupLocationLabel.setForeground(UIManager.getColor("Label.foreground"));
        } else {
            String defaultPath = computeDefaultBackupRoot();
            String display = defaultPath + " (默认)";
            backupLocationLabel.setText(ellipsizePath(display));
            backupLocationLabel.setToolTipText(defaultPath);
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
     * 读取当前 (host, contextDir) 对应的 customBackupRoot
     *
     * @param contextDir 当前上下文路径
     * @return customBackupRoot；未配置时返回 null
     * @author xumanyi
     * @date 2026-05-02
     */
    private String readCustomBackupRoot(String contextDir) {
        PluginSettingsService settings = project.getService(PluginSettingsService.class);
        if (settings == null || settings.getState() == null) return null;
        String key = backupKey(contextDir);
        if (key == null) return null;
        return settings.getState().customBackupRoots.get(key);
    }

    /**
     * 构造 customBackupRoots 的 key
     *
     * @param contextDir 当前上下文路径
     * @return key 字符串；无 host 时返回 null
     * @author xumanyi
     * @date 2026-05-02
     */
    private String backupKey(String contextDir) {
        if (ftpContextSupplier == null) return null;
        String host = ftpContextSupplier.getHost();
        if (host == null || host.isBlank()) return null;
        return host + ":" + ftpContextSupplier.getPort() + "|" + contextDir;
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
     * @author xumanyi
     * @date 2026-05-02
     */
    private void openBackupLocationDialog() {
        if (ftpContextSupplier == null || !ftpContextSupplier.isFtpConnected()) return;
        String contextDir = ftpContextSupplier.getContextDir();
        if (contextDir == null) return;

        String currentCustom = readCustomBackupRoot(contextDir);
        BackupLocationDialog dialog = new BackupLocationDialog(
                project,
                ftpContextSupplier.getHost(), ftpContextSupplier.getPort(),
                ftpContextSupplier.getUsername(), ftpContextSupplier.getPassword(),
                contextDir, currentCustom);
        if (!dialog.showAndGet()) return; // 用户取消

        PluginSettingsService settings = project.getService(PluginSettingsService.class);
        if (settings == null || settings.getState() == null) return;
        String key = backupKey(contextDir);
        if (key == null) return;

        if (dialog.isRestoreDefault()) {
            settings.getState().customBackupRoots.remove(key);
        } else {
            String picked = dialog.getResultBackupRoot();
            if (picked != null && !picked.isBlank()) {
                settings.getState().customBackupRoots.put(key, picked);
            }
        }
        refreshBackupLocationLabel();
    }
}
