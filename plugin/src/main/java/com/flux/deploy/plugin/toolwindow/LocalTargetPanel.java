package com.flux.deploy.plugin.toolwindow;

import com.flux.deploy.plugin.model.LocalTargetSelection;
import com.flux.deploy.plugin.service.LocalPackagePatchService;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.fileChooser.FileChooser;
import com.intellij.openapi.fileChooser.FileChooserDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBPanel;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.IconUtil;
import com.intellij.util.ui.JBUI;

import javax.swing.*;
import javax.swing.border.AbstractBorder;
import javax.swing.border.Border;
import java.awt.*;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.dnd.DnDConstants;
import java.awt.dnd.DropTarget;
import java.awt.dnd.DropTargetDragEvent;
import java.awt.dnd.DropTargetDropEvent;
import java.awt.dnd.DropTargetEvent;
import java.awt.dnd.DropTargetListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Supplier;

/**
 * 目标区-本地模式子面板：主角级"拖放/点选"区 + 输出位置行
 *
 * <p>顶部是一个主角级的源包区，内部用 {@link CardLayout} 切两态：</p>
 * <ul>
 *   <li><b>空态</b>：虚线边框的拖放区，居中展示图标与提示（"拖入 jar / war 或点击选择"）；
 *       整个区域可点击或接收文件拖入。</li>
 *   <li><b>已选态</b>：紧凑卡片展示文件图标 / 文件名 / 父目录，右侧一个 × 清除按钮；
 *       仍可点击卡片或拖入新文件来更换。</li>
 * </ul>
 *
 * <p>下方是"输出到"一行——标签 + 输入框 + 选择按钮。源包被选中后，若用户未手动改过，
 * 输出目录会自动推算为 {@code <包所在目录>/patched_{时间戳}/}。</p>
 *
 * @author xumanyi
 * @date 2026-04-17
 */
public class LocalTargetPanel extends JBPanel<LocalTargetPanel> {

    /** CardLayout 卡片键：空态拖放区 */
    private static final String CARD_EMPTY = "empty";
    /** CardLayout 卡片键：已选态卡片 */
    private static final String CARD_SELECTED = "selected";

    /** 空态拖放区的最小高度（像素），保证视觉主角感 */
    private static final int DROP_ZONE_HEIGHT = 140;

    private final Project project;

    /** 输出目录输入框（支持 empty text 占位符） */
    private final JBTextField outputDirField;
    private final JButton pickOutputButton;

    /** CardLayout 切换空态 / 已选态 */
    private final CardLayout sourceCardLayout;
    private final JPanel sourceCards;
    private final JPanel emptyDropZone;
    private final JPanel selectedCard;
    private final JBLabel selectedFileNameLabel;
    private final JBLabel selectedParentPathLabel;
    private final JButton clearSelectionButton;

    /** 实际存储的包完整路径 */
    private String packageFullPath = "";
    /** 用户是否手动改过输出目录（改过就不再随包路径自动推算） */
    private boolean outputDirDirty;

    /** 待更新文件数获取器（保留 API 兼容，当前版本展示层已不再使用） */
    @SuppressWarnings("unused")
    private Supplier<Integer> fileCountSupplier = () -> 0;

    public LocalTargetPanel(Project project) {
        super(new GridBagLayout());
        this.project = project;

        // ── 输出位置 ──
        this.outputDirField = new JBTextField();
        this.outputDirField.getEmptyText().setText("先选源包后自动生成 patched_{时间戳}/ 子目录");
        this.outputDirField.setToolTipText("新包的输出目录");
        this.pickOutputButton = new JButton(AllIcons.Nodes.Folder);
        this.pickOutputButton.setMargin(JBUI.emptyInsets());
        this.pickOutputButton.setFocusable(false);
        this.pickOutputButton.setToolTipText("选择输出目录");
        int pickBtnSize = this.outputDirField.getPreferredSize().height;
        this.pickOutputButton.setPreferredSize(new Dimension(pickBtnSize, pickBtnSize));

        // ── 源包区组件 ──
        this.selectedFileNameLabel = new JBLabel();
        this.selectedParentPathLabel = new JBLabel();
        this.clearSelectionButton = new JButton(AllIcons.Actions.Close);
        this.clearSelectionButton.setBorderPainted(false);
        this.clearSelectionButton.setContentAreaFilled(false);
        this.clearSelectionButton.setFocusable(false);
        this.clearSelectionButton.setMargin(JBUI.emptyInsets());
        this.clearSelectionButton.setToolTipText("清除已选源包");
        int closeSize = 22;
        this.clearSelectionButton.setPreferredSize(new Dimension(closeSize, closeSize));
        this.clearSelectionButton.setRolloverIcon(AllIcons.Actions.CloseHovered);

        this.emptyDropZone = buildEmptyDropZone();
        this.selectedCard = buildSelectedCard();

        this.sourceCardLayout = new CardLayout();
        this.sourceCards = new JPanel(sourceCardLayout);
        sourceCards.add(emptyDropZone, CARD_EMPTY);
        sourceCards.add(selectedCard, CARD_SELECTED);

        initUI();
        initListeners();
        installDropTargets();
        showEmptyState();
    }

    /** 注入待更新文件数获取器（保留 API 兼容） */
    public void setFileCountSupplier(Supplier<Integer> supplier) {
        this.fileCountSupplier = supplier == null ? () -> 0 : supplier;
    }

    // ═══════════════════════════════════════════════════════════════
    //   布局
    // ═══════════════════════════════════════════════════════════════

    private void initUI() {
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridx = 0;
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.anchor = GridBagConstraints.NORTHWEST;

        // row 0: 源包区（固定高度，不吃剩余空间）
        gbc.gridy = 0; gbc.weighty = 0.0;
        gbc.insets = new Insets(0, 0, 14, 0);
        add(sourceCards, gbc);

        // row 1: 输出位置标签
        gbc.gridy = 1;
        gbc.insets = new Insets(0, 0, 4, 0);
        add(sectionLabel("输出到"), gbc);

        // row 2: 输出位置输入行
        JPanel outRow = new JPanel(new BorderLayout(4, 0));
        outRow.add(outputDirField, BorderLayout.CENTER);
        outRow.add(pickOutputButton, BorderLayout.EAST);
        gbc.gridy = 2;
        gbc.insets = new Insets(0, 0, 0, 0);
        add(outRow, gbc);

        // row 3: glue，吃掉下方所有剩余空间
        gbc.gridy = 3; gbc.weighty = 1.0;
        gbc.fill = GridBagConstraints.BOTH;
        add(new JPanel() {{ setOpaque(false); }}, gbc);
    }

    /**
     * 构建空态拖放区：虚线边框 + 居中图标 + 两行提示；整个区域可点击。
     */
    private JPanel buildEmptyDropZone() {
        JPanel zone = new JPanel(new GridBagLayout());
        zone.setOpaque(false);
        zone.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        zone.setBorder(BorderFactory.createCompoundBorder(
                new DashedBorder(borderColorNormal(), 1.3f, 8f),
                JBUI.Borders.empty(14, 12)));
        // 固定最小/首选高度，让拖放区视觉上占主角
        zone.setPreferredSize(new Dimension(0, DROP_ZONE_HEIGHT));
        zone.setMinimumSize(new Dimension(0, DROP_ZONE_HEIGHT));

        Icon iconBase = AllIcons.FileTypes.Archive;
        Icon bigIcon = IconUtil.scale(iconBase, null, 2.0f);
        JBLabel iconLabel = new JBLabel(bigIcon);

        JBLabel line1 = new JBLabel("拖入 jar / war 文件");
        line1.setFont(line1.getFont().deriveFont(Font.BOLD, 13f));

        JBLabel line2 = new JBLabel("或点击选择");
        line2.setForeground(mutedForeground());

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridx = 0; gbc.weightx = 1.0;
        gbc.anchor = GridBagConstraints.CENTER;

        gbc.gridy = 0; gbc.weighty = 1.0; gbc.fill = GridBagConstraints.BOTH;
        zone.add(glue(), gbc);

        gbc.gridy = 1; gbc.weighty = 0; gbc.fill = GridBagConstraints.NONE;
        gbc.insets = new Insets(0, 0, 8, 0);
        zone.add(iconLabel, gbc);

        gbc.gridy = 2; gbc.insets = new Insets(0, 0, 2, 0);
        zone.add(line1, gbc);

        gbc.gridy = 3; gbc.insets = new Insets(0, 0, 0, 0);
        zone.add(line2, gbc);

        gbc.gridy = 4; gbc.weighty = 1.0; gbc.fill = GridBagConstraints.BOTH;
        zone.add(glue(), gbc);

        // 整个区域点击 → 选文件
        MouseAdapter clickToPick = new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) { choosePackage(); }
        };
        zone.addMouseListener(clickToPick);
        iconLabel.addMouseListener(clickToPick);
        line1.addMouseListener(clickToPick);
        line2.addMouseListener(clickToPick);

        return zone;
    }

    /**
     * 构建已选态卡片：实线边框 + 文件图标 + 文件名 + × 清除；下方一行父目录路径。
     */
    private JPanel buildSelectedCard() {
        JPanel card = new JPanel(new GridBagLayout());
        card.setOpaque(false);
        card.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(borderColorNormal(), 1, true),
                JBUI.Borders.empty(10, 12)));

        JBLabel fileIcon = new JBLabel(AllIcons.FileTypes.Archive);

        selectedFileNameLabel.setFont(selectedFileNameLabel.getFont().deriveFont(Font.BOLD, 13f));
        selectedParentPathLabel.setForeground(mutedForeground());
        selectedParentPathLabel.setFont(selectedParentPathLabel.getFont().deriveFont(11f));

        GridBagConstraints gbc = new GridBagConstraints();

        // row 0: icon | filename | × close
        gbc.gridx = 0; gbc.gridy = 0;
        gbc.anchor = GridBagConstraints.WEST;
        gbc.insets = new Insets(0, 0, 0, 8);
        card.add(fileIcon, gbc);

        gbc.gridx = 1;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;
        card.add(selectedFileNameLabel, gbc);

        gbc.gridx = 2;
        gbc.fill = GridBagConstraints.NONE;
        gbc.weightx = 0;
        gbc.insets = new Insets(0, 0, 0, 0);
        card.add(clearSelectionButton, gbc);

        // row 1: 父目录（跨三列，小号灰字）
        gbc.gridx = 0; gbc.gridy = 1;
        gbc.gridwidth = 3;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;
        gbc.insets = new Insets(4, 0, 0, 0);
        card.add(selectedParentPathLabel, gbc);

        // 点击卡片（非 × 按钮区域）也可以重新选文件
        MouseAdapter clickToReplace = new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (e.getSource() == clearSelectionButton) return;
                choosePackage();
            }
        };
        card.addMouseListener(clickToReplace);
        selectedFileNameLabel.addMouseListener(clickToReplace);
        selectedParentPathLabel.addMouseListener(clickToReplace);
        fileIcon.addMouseListener(clickToReplace);
        card.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

        return card;
    }

    /** 小节标题：加粗 */
    private static JBLabel sectionLabel(String text) {
        JBLabel l = new JBLabel(text);
        l.setFont(l.getFont().deriveFont(Font.BOLD));
        return l;
    }

    private static JComponent glue() {
        JPanel p = new JPanel();
        p.setOpaque(false);
        return p;
    }

    // ═══════════════════════════════════════════════════════════════
    //   状态切换
    // ═══════════════════════════════════════════════════════════════

    private void showEmptyState() {
        sourceCardLayout.show(sourceCards, CARD_EMPTY);
    }

    private void showSelectedState(File file) {
        selectedFileNameLabel.setText(file.getName());
        File parent = file.getParentFile();
        selectedParentPathLabel.setText(parent != null ? parent.getAbsolutePath() : "");
        selectedParentPathLabel.setToolTipText(file.getAbsolutePath());
        sourceCardLayout.show(sourceCards, CARD_SELECTED);
    }

    // ═══════════════════════════════════════════════════════════════
    //   选/拖 / 清除
    // ═══════════════════════════════════════════════════════════════

    /** 应用选中/拖入的包文件：存储完整路径、切换到已选态、刷新默认输出目录 */
    private void applyPickedFile(File file) {
        packageFullPath = file.getAbsolutePath();
        showSelectedState(file);
        if (!outputDirDirty) {
            Path suggest = LocalPackagePatchService.suggestOutputDir(packageFullPath);
            outputDirField.setText(suggest.toString());
        }
    }

    private void clearSelection() {
        packageFullPath = "";
        selectedFileNameLabel.setText("");
        selectedParentPathLabel.setText("");
        selectedParentPathLabel.setToolTipText(null);
        if (!outputDirDirty) {
            outputDirField.setText("");
        }
        showEmptyState();
    }

    private void choosePackage() {
        FileChooserDescriptor desc = new FileChooserDescriptor(
                true, false, true, true, false, false)
                .withFileFilter(f -> {
                    String n = f.getName().toLowerCase();
                    return n.endsWith(".jar") || n.endsWith(".war");
                })
                .withTitle("选择本地 jar / war 包");
        VirtualFile chosen = FileChooser.chooseFile(desc, project, null);
        if (chosen == null) return;
        applyPickedFile(new File(chosen.getPath()));
    }

    private void chooseOutputDir() {
        FileChooserDescriptor desc = new FileChooserDescriptor(
                false, true, false, false, false, false)
                .withTitle("选择输出目录");
        VirtualFile chosen = FileChooser.chooseFile(desc, project, null);
        if (chosen == null) return;
        outputDirField.setText(chosen.getPath());
        outputDirDirty = true;
    }

    // ═══════════════════════════════════════════════════════════════
    //   拖拽
    // ═══════════════════════════════════════════════════════════════

    private void installDropTargets() {
        // 整个面板都接受拖拽（防漏），同时给拖放区自己加一份监听以便做 hover 边框反馈
        attachDropTarget(this, false);
        attachDropTarget(emptyDropZone, true);
        attachDropTarget(selectedCard, true);
    }

    /**
     * 给组件挂上拖放监听
     *
     * @param target    监听目标组件
     * @param withHover 是否在 dragEnter 时给组件加一层高亮边框提示
     */
    private void attachDropTarget(JComponent target, boolean withHover) {
        Border normal = target.getBorder();
        DropTargetListener listener = new DropTargetListener() {
            @Override public void dragEnter(DropTargetDragEvent dtde) {
                if (dtde.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                    dtde.acceptDrag(DnDConstants.ACTION_COPY);
                    if (withHover) {
                        target.setBorder(hoverBorder());
                    }
                } else {
                    dtde.rejectDrag();
                }
            }
            @Override public void dragOver(DropTargetDragEvent dtde) {}
            @Override public void dropActionChanged(DropTargetDragEvent dtde) {}
            @Override public void dragExit(DropTargetEvent dte) {
                if (withHover) target.setBorder(normal);
            }
            @Override public void drop(DropTargetDropEvent dtde) {
                try {
                    if (!dtde.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                        dtde.rejectDrop();
                        if (withHover) target.setBorder(normal);
                        return;
                    }
                    dtde.acceptDrop(DnDConstants.ACTION_COPY);
                    Transferable t = dtde.getTransferable();
                    @SuppressWarnings("unchecked")
                    List<File> files = (List<File>) t.getTransferData(DataFlavor.javaFileListFlavor);
                    File pick = pickFirstJarOrWar(files);
                    if (pick != null) {
                        applyPickedFile(pick);
                        dtde.dropComplete(true);
                    } else {
                        dtde.dropComplete(false);
                        JOptionPane.showMessageDialog(LocalTargetPanel.this,
                                "请拖入 .jar 或 .war 文件", "提示", JOptionPane.WARNING_MESSAGE);
                    }
                } catch (Exception ex) {
                    dtde.dropComplete(false);
                } finally {
                    if (withHover) target.setBorder(normal);
                }
            }
        };
        new DropTarget(target, DnDConstants.ACTION_COPY, listener, true);
    }

    private static File pickFirstJarOrWar(List<File> files) {
        if (files == null) return null;
        for (File f : files) {
            String n = f.getName().toLowerCase();
            if (f.isFile() && (n.endsWith(".jar") || n.endsWith(".war"))) return f;
        }
        return null;
    }

    // ═══════════════════════════════════════════════════════════════
    //   监听器与对外 API
    // ═══════════════════════════════════════════════════════════════

    private void initListeners() {
        pickOutputButton.addActionListener(e -> chooseOutputDir());
        clearSelectionButton.addActionListener(e -> clearSelection());
        // 用户手改输出目录时记录 dirty
        outputDirField.addFocusListener(new java.awt.event.FocusAdapter() {
            @Override public void focusLost(java.awt.event.FocusEvent e) {
                if (!outputDirField.getText().trim().isEmpty()) outputDirDirty = true;
            }
        });
    }

    /** 获取用户选择的目标信息；任一字段为空返回 null */
    public LocalTargetSelection getSelection() {
        String pkg = packageFullPath;
        String out = outputDirField.getText().trim();
        if (pkg == null || pkg.isEmpty() || out.isEmpty()) return null;
        return new LocalTargetSelection(pkg, out);
    }

    /** 重置面板（清空用户输入） */
    public void resetAll() {
        packageFullPath = "";
        selectedFileNameLabel.setText("");
        selectedParentPathLabel.setText("");
        selectedParentPathLabel.setToolTipText(null);
        outputDirField.setText("");
        outputDirDirty = false;
        showEmptyState();
    }

    // ═══════════════════════════════════════════════════════════════
    //   颜色 / Border 工具
    // ═══════════════════════════════════════════════════════════════

    private static Color borderColorNormal() {
        Color c = UIManager.getColor("Component.borderColor");
        if (c == null) c = UIManager.getColor("Separator.foreground");
        return c != null ? c : JBColor.border();
    }

    private static Color borderColorHover() {
        Color c = UIManager.getColor("Link.activeForeground");
        return c != null ? c : new JBColor(new Color(0x2E7BE0), new Color(0x589DF6));
    }

    private static Color mutedForeground() {
        Color c = UIManager.getColor("Label.disabledForeground");
        return c != null ? c : JBColor.GRAY;
    }

    /** dragEnter 时给目标组件套一层蓝色高亮边框，提示"可以在此放下" */
    private static Border hoverBorder() {
        return BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(borderColorHover(), 2, true),
                JBUI.Borders.empty(9, 11));
    }

    /**
     * 虚线圆角边框——用于空态拖放区，暗示"这不是普通输入框，而是一个可拖入的区域"。
     */
    private static class DashedBorder extends AbstractBorder {
        private final Color color;
        private final float thickness;
        private final float dashLength;

        DashedBorder(Color color, float thickness, float dashLength) {
            this.color = color;
            this.thickness = thickness;
            this.dashLength = dashLength;
        }

        @Override
        public void paintBorder(Component c, Graphics g, int x, int y, int width, int height) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(color);
            g2.setStroke(new BasicStroke(
                    thickness,
                    BasicStroke.CAP_BUTT,
                    BasicStroke.JOIN_ROUND,
                    0f,
                    new float[]{dashLength, dashLength * 0.7f},
                    0f));
            int inset = Math.round(thickness / 2f) + 1;
            g2.drawRoundRect(x + inset, y + inset,
                    width - inset * 2 - 1, height - inset * 2 - 1,
                    10, 10);
            g2.dispose();
        }

        @Override
        public Insets getBorderInsets(Component c) {
            int pad = Math.round(thickness) + 2;
            return new Insets(pad, pad, pad, pad);
        }

        @Override
        public Insets getBorderInsets(Component c, Insets insets) {
            int pad = Math.round(thickness) + 2;
            insets.set(pad, pad, pad, pad);
            return insets;
        }
    }
}
