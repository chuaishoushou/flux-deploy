package com.flux.deploy.plugin.toolwindow;

import com.flux.deploy.deploy.ResidualLockDiagnosis;
import com.flux.deploy.plugin.util.FluxDialogs;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

/**
 * 残留锁清理确认对话框
 *
 * <p>展示所有残留锁的诊断信息，自己的默认勾选；他人的禁用勾选并提示。
 * 样式遵循 {@link FluxDialogs} 统一弹窗规范：顶部 HTML 说明区（⚠ 结论 + 灰色操作提示）、
 * 统一内容边距与宽度档位、主题自适配的卡片边框。</p>
 *
 * @author xumanyi
 * @date 2026-04-29
 */
public class ResidualLockResolveDialog extends DialogWrapper {

    private final List<ResidualLockDiagnosis> diagnoses;
    private final List<JCheckBox> checkBoxes = new ArrayList<>();

    /**
     * 构造残留锁清理确认对话框
     *
     * @param project   当前 IDEA 项目（对话框定位用，可为 null）
     * @param diagnoses 所有残留锁的诊断结果
     * @author xumanyi
     * @date 2026-04-29
     */
    public ResidualLockResolveDialog(@Nullable Project project, List<ResidualLockDiagnosis> diagnoses) {
        super(project);
        this.diagnoses = diagnoses;
        setTitle("残留锁处理");
        setOKButtonText("确定");
        setCancelButtonText("取消");
        init();
    }

    /**
     * 构建对话框主面板：顶部说明区 + 逐锁诊断卡片列表（滚动）
     *
     * @return 主面板组件
     * @author xumanyi
     * @date 2026-04-29
     */
    @Override
    protected JComponent createCenterPanel() {
        JPanel root = new JPanel(new BorderLayout(0, 10));
        root.setBorder(FluxDialogs.contentBorder());
        root.setPreferredSize(new Dimension(FluxDialogs.WIDTH_L, 420));

        // 顶部说明：为什么弹窗 + 勾选规则
        JBLabel title = new JBLabel("<html><b>⚠ 检测到 " + diagnoses.size()
                + " 个残留锁，请确认处理方式</b><br>"
                + "<font color='" + FluxDialogs.HINT_COLOR
                + "'>勾选要清理的锁后点「确定」继续；他人持有的锁不可勾选，需先与对方确认后手动处理。"
                + "</font></html>");
        root.add(title, BorderLayout.NORTH);

        JPanel listPanel = new JPanel();
        listPanel.setLayout(new BoxLayout(listPanel, BoxLayout.Y_AXIS));
        for (ResidualLockDiagnosis d : diagnoses) {
            listPanel.add(buildRow(d));
            listPanel.add(Box.createVerticalStrut(8));
        }
        JBScrollPane scroll = new JBScrollPane(listPanel);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        root.add(scroll, BorderLayout.CENTER);
        return root;
    }

    /**
     * 构建单个残留锁的诊断卡片（勾选框 + 持有者 / 诊断 / 建议信息行）
     *
     * @param d 残留锁诊断
     * @return 卡片组件
     * @author xumanyi
     * @date 2026-04-29
     */
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
                BorderFactory.createLineBorder(JBColor.border()),
                JBUI.Borders.empty(6, 8)));
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.add(cb);
        String ownerLabel = d.getOperator()
                + (d.isOwnedByCurrentUser() ? "（你自己）" : "（不是你，需先与对方确认）");
        row.add(new JBLabel("持有者: " + ownerLabel + "    时间: " + d.getLockedAt()));
        row.add(new JBLabel("诊断: " + d.getReason()));
        row.add(new JBLabel("建议: " + d.getSuggestion()));
        return row;
    }

    /**
     * 获取用户勾选要处理的诊断（按对话框中的顺序）
     *
     * @return 勾选的诊断列表
     * @author xumanyi
     * @date 2026-04-29
     */
    public List<ResidualLockDiagnosis> getSelected() {
        List<ResidualLockDiagnosis> out = new ArrayList<>();
        for (int i = 0; i < diagnoses.size(); i++) {
            if (checkBoxes.get(i).isSelected()) out.add(diagnoses.get(i));
        }
        return out;
    }
}
