package com.flux.deploy.plugin.toolwindow;

import com.flux.deploy.plugin.service.LocalPackagePatchService.PreCheckResult;
import com.flux.deploy.plugin.service.LocalPackagePatchService.PreviewEntry;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;

/**
 * 本地模式预检确认对话框
 *
 * <p>展示场景说明 + 拟应用的变更清单（修改 / 新增 / 删除），用户确认后方可执行打包。</p>
 *
 * @author xumanyi
 * @date 2026-04-17
 */
public class LocalPreCheckDialog extends DialogWrapper {

    private final PreCheckResult result;
    private final String pkgName;
    private final String outputDir;

    public LocalPreCheckDialog(Project project, PreCheckResult result,
                                String pkgName, String outputDir) {
        super(project, false);
        this.result = result;
        this.pkgName = pkgName;
        this.outputDir = outputDir;
        setTitle("本地模式预检");
        setOKButtonText("确认打包");
        setCancelButtonText("取消");
        init();
    }

    @Nullable
    @Override
    protected JComponent createCenterPanel() {
        JPanel panel = new JPanel(new BorderLayout(0, 6));
        panel.setBorder(JBUI.Borders.empty(6, 8));
        panel.setPreferredSize(new Dimension(640, 420));

        // 顶部信息
        JPanel info = new JPanel(new GridLayout(0, 1, 0, 2));
        info.add(new JBLabel("模式：本地模式（不登录 FTP，无备份、无回滚）"));
        info.add(new JBLabel("包：" + pkgName));
        info.add(new JBLabel("输出目录：" + outputDir));
        if (result.getScopeDescription() != null) {
            info.add(new JBLabel("场景：" + result.getScopeDescription()));
        }
        panel.add(info, BorderLayout.NORTH);

        // 清单
        int modify = 0, add = 0, del = 0;
        for (PreviewEntry e : result.getPreviews()) {
            switch (e.getChangeType()) {
                case MODIFY: modify++; break;
                case ADD:    add++;    break;
                case DELETE: del++;    break;
            }
        }

        DefaultListModel<String> listModel = new DefaultListModel<>();
        listModel.addElement(String.format("待应用变更：%d 个（修改 %d / 新增 %d / 删除 %d）",
                result.getPreviews().size(), modify, add, del));
        listModel.addElement("");
        for (PreviewEntry e : result.getPreviews()) {
            String tag;
            switch (e.getChangeType()) {
                case MODIFY: tag = "[修改]"; break;
                case ADD:    tag = "[新增]"; break;
                case DELETE: tag = "[删除]"; break;
                default:     tag = "[变更]"; break;
            }
            listModel.addElement("  " + tag + " " + e.getEntryPath());
        }

        JList<String> list = new JList<>(listModel);
        list.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        panel.add(new JBScrollPane(list), BorderLayout.CENTER);

        return panel;
    }
}
