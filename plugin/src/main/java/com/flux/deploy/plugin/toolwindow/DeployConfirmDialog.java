package com.flux.deploy.plugin.toolwindow;

import com.flux.deploy.plugin.model.DeployMode;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.ui.CheckboxTree;
import com.intellij.ui.CheckedTreeNode;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.tree.DefaultTreeModel;
import java.awt.*;
import java.util.*;
import java.util.List;

/**
 * 部署确认对话框（树形文件列表）
 *
 * <p>在执行部署前弹出，展示目标包、远程路径、更新模式等信息，
 * 增量/自动检索模式下还展示按包路径分组的文件树供用户勾选确认。</p>
 *
 * @author xumanyi
 * @date 2026-03-27
 */
public class DeployConfirmDialog extends DialogWrapper {

    private final String targetPackage;
    private final String remotePath;
    private final DeployMode mode;
    private final List<String> files;
    private CheckboxTree fileTree;
    private CheckedTreeNode treeRoot;

    /**
     * 构造部署确认对话框
     *
     * @param project       当前 IDEA 项目
     * @param targetPackage 目标包名称
     * @param remotePath    远程路径
     * @param mode          更新模式
     * @param files         待更新的文件列表（增量模式下使用）
     * @author xumanyi
     * @date 2026-03-27
     */
    public DeployConfirmDialog(Project project, String targetPackage, String remotePath,
                                DeployMode mode, List<String> files) {
        super(project, false);
        this.targetPackage = targetPackage;
        this.remotePath = remotePath;
        this.mode = mode;
        this.files = files != null ? files : List.of();

        setTitle("确认更新");
        setOKButtonText("确认执行");
        setCancelButtonText("取消");
        init();
    }

    /**
     * 创建对话框中心面板
     *
     * @return 包含部署信息和文件树的面板
     * @author xumanyi
     * @date 2026-03-27
     */
    @Override
    protected @Nullable JComponent createCenterPanel() {
        JPanel panel = new JPanel(new BorderLayout(0, 8));
        panel.setPreferredSize(new Dimension(550, mode == DeployMode.FULL ? 140 : 400));

        // 顶部信息
        JPanel infoPanel = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(2, 4, 2, 4);
        gbc.anchor = GridBagConstraints.WEST;

        gbc.gridx = 0; gbc.gridy = 0;
        infoPanel.add(new JBLabel("目标包："), gbc);
        gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0;
        JBLabel pkgLabel = new JBLabel(targetPackage);
        pkgLabel.setFont(pkgLabel.getFont().deriveFont(Font.BOLD));
        infoPanel.add(pkgLabel, gbc);

        gbc.gridx = 0; gbc.gridy = 1; gbc.fill = GridBagConstraints.NONE; gbc.weightx = 0;
        infoPanel.add(new JBLabel("远程路径："), gbc);
        gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0;
        infoPanel.add(new JBLabel(remotePath), gbc);

        gbc.gridx = 0; gbc.gridy = 2; gbc.fill = GridBagConstraints.NONE; gbc.weightx = 0;
        infoPanel.add(new JBLabel("更新模式："), gbc);
        gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0;
        infoPanel.add(new JBLabel(mode.toString()), gbc);

        panel.add(infoPanel, BorderLayout.NORTH);

        // 文件树（增量/自动检索模式）
        if (mode != DeployMode.FULL && !files.isEmpty()) {
            JPanel filePanel = new JPanel(new BorderLayout(0, 4));

            // 头部：标题 + 全选
            JPanel headerPanel = new JPanel(new BorderLayout());
            JBLabel fileHeader = new JBLabel("更新文件 (" + files.size() + " 个)：");
            fileHeader.setFont(fileHeader.getFont().deriveFont(Font.BOLD));
            headerPanel.add(fileHeader, BorderLayout.WEST);

            JCheckBox selectAll = new JCheckBox("全选", true);
            selectAll.addActionListener(ev -> {
                boolean sel = selectAll.isSelected();
                setAllNodesChecked(treeRoot, sel);
                ((DefaultTreeModel) fileTree.getModel()).nodeChanged(treeRoot);
                fileTree.repaint();
            });
            headerPanel.add(selectAll, BorderLayout.EAST);
            filePanel.add(headerPanel, BorderLayout.NORTH);

            // 构建树
            treeRoot = new CheckedTreeNode("root");
            buildTree(files);

            // 鼠标悬浮行高亮：final 数组作为 hover 行指针，paintComponent 先铺背景条再让 super 画树
            final int[] hoverRow = { -1 };
            fileTree = new CheckboxTree(new SourceSectionPanel.FileTreeCellRenderer(), treeRoot) {
                @Override
                protected void paintComponent(Graphics g) {
                    if (hoverRow[0] >= 0 && hoverRow[0] < getRowCount()
                            && !isRowSelected(hoverRow[0])) {
                        Rectangle b = getRowBounds(hoverRow[0]);
                        if (b != null) {
                            g.setColor(SourceSectionPanel.hoverBackgroundColor());
                            g.fillRect(0, b.y, getWidth(), b.height);
                        }
                    }
                    super.paintComponent(g);
                }
            };
            fileTree.setRootVisible(false);
            fileTree.setShowsRootHandles(true);
            fileTree.addMouseMotionListener(new java.awt.event.MouseMotionAdapter() {
                @Override
                public void mouseMoved(java.awt.event.MouseEvent e) {
                    int row = fileTree.getRowForLocation(e.getX(), e.getY());
                    if (row != hoverRow[0]) {
                        hoverRow[0] = row;
                        fileTree.repaint();
                    }
                }
            });
            fileTree.addMouseListener(new java.awt.event.MouseAdapter() {
                @Override
                public void mouseExited(java.awt.event.MouseEvent e) {
                    if (hoverRow[0] != -1) {
                        hoverRow[0] = -1;
                        fileTree.repaint();
                    }
                }
            });

            // 展开所有
            for (int i = 0; i < fileTree.getRowCount(); i++) {
                fileTree.expandRow(i);
            }

            filePanel.add(new JBScrollPane(fileTree), BorderLayout.CENTER);
            panel.add(filePanel, BorderLayout.CENTER);

        } else if (mode == DeployMode.FULL) {
            panel.add(new JBLabel("将替换整个远程包"), BorderLayout.CENTER);
        }

        // 底部警告
        JBLabel warning = new JBLabel("⚠ 此操作将修改远程 FTP 包，请确认无误后执行");
        warning.setForeground(new Color(204, 120, 0));
        panel.add(warning, BorderLayout.SOUTH);

        return panel;
    }

    /** 构建按包路径分组的文件树 */
    private void buildTree(List<String> files) {
        Map<String, List<SourceSectionPanel.FileEntry>> grouped = new LinkedHashMap<>();
        for (String raw : files) {
            String status = "";
            String path = raw;
            if (raw.length() > 2 && raw.charAt(1) == ' ') {
                status = String.valueOf(raw.charAt(0));
                path = raw.substring(raw.indexOf(' ')).trim();
            }
            // 规范化路径分隔符（防御性处理）：Windows 下上游若漏改会带 '\'，
            // 导致 indexOf("src/main/java/") 永远找不到，包名/文件名拆不出来
            path = path.replace('\\', '/');
            int javaIdx = path.indexOf("src/main/java/");
            if (javaIdx >= 0) {
                path = path.substring(javaIdx + "src/main/java/".length());
            } else {
                int srcIdx = path.indexOf("src/");
                if (srcIdx >= 0) path = path.substring(srcIdx);
            }
            int lastSlash = path.lastIndexOf('/');
            String pkg = lastSlash > 0 ? path.substring(0, lastSlash).replace('/', '.') : "(default)";
            String fileName = lastSlash > 0 ? path.substring(lastSlash + 1) : path;
            grouped.computeIfAbsent(pkg, k -> new ArrayList<>())
                    .add(new SourceSectionPanel.FileEntry(raw, status, fileName));
        }

        for (Map.Entry<String, List<SourceSectionPanel.FileEntry>> entry : grouped.entrySet()) {
            CheckedTreeNode pkgNode = new CheckedTreeNode(
                    entry.getKey() + "  (" + entry.getValue().size() + " 个文件)");
            pkgNode.setChecked(true);
            for (SourceSectionPanel.FileEntry fe : entry.getValue()) {
                CheckedTreeNode fileNode = new CheckedTreeNode(fe);
                fileNode.setChecked(true);
                pkgNode.add(fileNode);
            }
            treeRoot.add(pkgNode);
        }
    }

    /**
     * 获取用户确认后选中的文件列表
     *
     * @return 勾选的文件原始路径列表，全量模式下返回原始文件列表
     * @author xumanyi
     * @date 2026-03-27
     */
    public List<String> getSelectedFiles() {
        if (fileTree == null || files.isEmpty()) {
            return files;
        }
        List<String> selected = new ArrayList<>();
        collectCheckedFiles(treeRoot, selected);
        return selected;
    }

    /** 递归收集选中的文件节点路径 */
    private void collectCheckedFiles(CheckedTreeNode node, List<String> result) {
        if (node.getUserObject() instanceof SourceSectionPanel.FileEntry fe) {
            if (node.isChecked()) result.add(fe.rawPath);
            return;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            if (node.getChildAt(i) instanceof CheckedTreeNode child) {
                collectCheckedFiles(child, result);
            }
        }
    }

    /** 递归设置所有节点的选中状态 */
    private void setAllNodesChecked(CheckedTreeNode node, boolean checked) {
        node.setChecked(checked);
        for (int i = 0; i < node.getChildCount(); i++) {
            if (node.getChildAt(i) instanceof CheckedTreeNode child) {
                setAllNodesChecked(child, checked);
            }
        }
    }
}
