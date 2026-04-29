package com.flux.deploy.plugin.toolwindow;

import com.flux.deploy.deploy.ResidualLockDiagnosis;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

/**
 * 残留锁清理确认对话框
 *
 * <p>展示所有残留锁的诊断信息，自己的默认勾选；他人的禁用勾选并提示。</p>
 *
 * @author xumanyi
 * @date 2026-04-29
 */
public class ResidualLockResolveDialog extends DialogWrapper {

    private final List<ResidualLockDiagnosis> diagnoses;
    private final List<JCheckBox> checkBoxes = new ArrayList<>();

    public ResidualLockResolveDialog(@Nullable Project project, List<ResidualLockDiagnosis> diagnoses) {
        super(project);
        this.diagnoses = diagnoses;
        setTitle("检测到残留锁，请确认处理方式");
        init();
    }

    @Override
    protected JComponent createCenterPanel() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        for (ResidualLockDiagnosis d : diagnoses) {
            panel.add(buildRow(d));
            panel.add(Box.createVerticalStrut(8));
        }
        JScrollPane scroll = new JScrollPane(panel);
        scroll.setPreferredSize(new Dimension(680, 420));
        return scroll;
    }

    private JComponent buildRow(ResidualLockDiagnosis d) {
        boolean canSelect = d.isOwnedByCurrentUser()
                && d.getSuggestion() != ResidualLockDiagnosis.SuggestedAction.NEEDS_HUMAN;
        JCheckBox cb = new JCheckBox(d.getLockFileName());
        cb.setSelected(canSelect);
        cb.setEnabled(canSelect);
        checkBoxes.add(cb);

        JPanel row = new JPanel();
        row.setLayout(new BoxLayout(row, BoxLayout.Y_AXIS));
        row.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Color.GRAY),
                BorderFactory.createEmptyBorder(6, 8, 6, 8)));
        row.add(cb);
        String ownerLabel = d.getOperator()
                + (d.isOwnedByCurrentUser() ? "（你自己）" : "（不是你，需先与对方确认）");
        row.add(new JLabel("持有者: " + ownerLabel + "    时间: " + d.getLockedAt()));
        row.add(new JLabel("诊断: " + d.getReason()));
        row.add(new JLabel("建议: " + d.getSuggestion()));
        return row;
    }

    /** 用户勾选要处理的诊断（按对话框中的顺序） */
    public List<ResidualLockDiagnosis> getSelected() {
        List<ResidualLockDiagnosis> out = new ArrayList<>();
        for (int i = 0; i < diagnoses.size(); i++) {
            if (checkBoxes.get(i).isSelected()) out.add(diagnoses.get(i));
        }
        return out;
    }
}
