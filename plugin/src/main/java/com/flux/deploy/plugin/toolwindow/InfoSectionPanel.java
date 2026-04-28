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

    private final Project project;
    private final JCheckBox updateNoteCheckBox;
    private final JCheckBox backupCheckBox;
    private final JPanel fieldsPanel;
    private final JBTextField taskIdField;
    private final JBTextField customerIdField;
    private final JBTextField operatorField;

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

        // 行 1：开发（仅在需要时显示：勾选了版本记录或执行备份）
        JBLabel operatorLabel = new JBLabel("开发：");
        g.gridy = 1; g.gridwidth = 1;
        g.gridx = 0; g.fill = GridBagConstraints.NONE; g.weightx = 0;
        container.add(operatorLabel, g);
        g.gridx = 1; g.fill = GridBagConstraints.HORIZONTAL; g.weightx = 1.0;
        container.add(operatorField, g);

        // 行 2 + 3：任务 / 客服，单独记录引用以便整体显隐
        JBLabel taskLabel = new JBLabel("任务：");
        JBLabel customerLabel = new JBLabel("客服：");

        g.gridy = 2;
        g.gridx = 0; g.fill = GridBagConstraints.NONE; g.weightx = 0;
        container.add(taskLabel, g);
        g.gridx = 1; g.fill = GridBagConstraints.HORIZONTAL; g.weightx = 1.0;
        container.add(taskIdField, g);

        g.gridy = 3;
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
}
