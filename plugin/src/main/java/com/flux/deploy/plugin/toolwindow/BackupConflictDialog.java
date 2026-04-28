package com.flux.deploy.plugin.toolwindow;

import com.flux.deploy.plugin.model.BackupConflictStrategy;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.util.List;

/**
 * 备份冲突选择对话框
 *
 * <p>当检测到今日已有备份时，让用户在三种处理策略间选择。</p>
 *
 * @author xumanyi
 * @date 2026-04-18
 */
public class BackupConflictDialog extends DialogWrapper {

    private final List<String> conflicts;
    private final ButtonGroup group;
    private final JRadioButton overwriteRadio;
    private final JRadioButton useExistingRadio;
    private final JRadioButton newDirRadio;

    public BackupConflictDialog(Project project, List<String> conflicts) {
        super(project, false);
        this.conflicts = conflicts;
        this.group = new ButtonGroup();
        this.overwriteRadio = new JRadioButton("覆盖备份");
        this.useExistingRadio = new JRadioButton("使用原始备份（推荐）");
        this.newDirRadio = new JRadioButton("新增备份目录");
        group.add(overwriteRadio);
        group.add(useExistingRadio);
        group.add(newDirRadio);
        // 默认推荐「使用原始备份」—— 最安全，不会丢失上一版远端包
        useExistingRadio.setSelected(true);

        setTitle("备份冲突");
        setOKButtonText("确认");
        setCancelButtonText("取消");
        init();
    }

    @Nullable
    @Override
    protected JComponent createCenterPanel() {
        JPanel root = new JPanel(new BorderLayout(0, 10));
        root.setBorder(JBUI.Borders.empty(10, 14));
        root.setPreferredSize(new Dimension(520, 380));

        // 顶部说明 + 冲突清单
        JPanel top = new JPanel(new BorderLayout(0, 6));
        JBLabel title = new JBLabel(
                "<html><b>⚠ 检测到以下包今天已有备份，请选择处理方式：</b></html>");
        top.add(title, BorderLayout.NORTH);

        DefaultListModel<String> listModel = new DefaultListModel<>();
        for (String c : conflicts) listModel.addElement("  · " + c);
        JList<String> list = new JList<>(listModel);
        list.setFont(list.getFont().deriveFont(12f));
        list.setVisibleRowCount(Math.min(6, conflicts.size()));
        JBScrollPane listScroll = new JBScrollPane(list);
        listScroll.setPreferredSize(new Dimension(500, 110));
        top.add(listScroll, BorderLayout.CENTER);
        root.add(top, BorderLayout.NORTH);

        // 选项区
        JPanel options = new JPanel();
        options.setLayout(new BoxLayout(options, BoxLayout.Y_AXIS));
        options.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createTitledBorder("处理方式"),
                JBUI.Borders.empty(4, 8)));

        options.add(makeOption(overwriteRadio,
                "用当前远端包覆盖老备份；<font color='#d08770'>上一版远端包将永久丢失</font>。"));
        options.add(Box.createVerticalStrut(8));
        options.add(makeOption(useExistingRadio,
                "<font color='#85c88a'>本次不再备份</font>，保留更早的备份作为回滚源。"
                        + "适合「同一天多次更新同一个包」的场景。"));
        options.add(Box.createVerticalStrut(8));
        options.add(makeOption(newDirRadio,
                "另建 <code>yyyyMMdd_{开发}_v2 / _v3 / ...</code> 备份目录，两份备份都保留。"));
        root.add(options, BorderLayout.CENTER);

        return root;
    }

    private JPanel makeOption(JRadioButton radio, String htmlHint) {
        JPanel row = new JPanel(new BorderLayout(6, 0));
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        JBLabel hint = new JBLabel("<html><body style='color:#8a8e93;'>" + htmlHint + "</body></html>");
        hint.setBorder(JBUI.Borders.emptyLeft(24));
        row.add(radio, BorderLayout.NORTH);
        row.add(hint, BorderLayout.CENTER);
        return row;
    }

    /**
     * 获取用户选择的策略，用户未点确认时返回 null
     */
    @Nullable
    public BackupConflictStrategy getSelectedStrategy() {
        if (overwriteRadio.isSelected()) return BackupConflictStrategy.OVERWRITE;
        if (useExistingRadio.isSelected()) return BackupConflictStrategy.USE_EXISTING;
        if (newDirRadio.isSelected()) return BackupConflictStrategy.NEW_DIR;
        return null;
    }
}
