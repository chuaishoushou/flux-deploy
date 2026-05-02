package com.flux.deploy.plugin.toolwindow;

import com.flux.deploy.plugin.model.DeployMode;
import com.intellij.icons.AllIcons;
import com.intellij.util.ui.JBUI;
import com.flux.deploy.plugin.service.ModuleEnumerator;
import com.flux.deploy.plugin.service.ModuleEnumerator.ModuleTreeNode;
import com.intellij.openapi.project.Project;
import com.intellij.ui.CheckboxTree;
import com.intellij.ui.CheckedTreeNode;
import com.intellij.ui.SearchTextField;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBPanel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextField;
import com.intellij.ui.treeStructure.Tree;

import javax.swing.*;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import javax.swing.tree.TreeSelectionModel;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.util.*;
import java.util.List;
import java.util.function.Consumer;

/**
 * 源 Section 面板：工程信息 + 更新模式 + 变更文件列表（树形）
 *
 * <p>展示当前模块名称、Maven 产物名称、更新模式下拉，
 * 以及按包路径分组的变更文件树形列表（支持勾选）。</p>
 *
 * @author xumanyi
 * @date 2026-03-27
 */
public class SourceSectionPanel extends JBPanel<SourceSectionPanel> {

    private final Project project;
    private final PickerComboBox moduleCombo;
    private final JBTextField artifactField;
    private final JComboBox<DeployMode> modeComboBox;
    private final JPanel dynamicContent;

    private final JBLabel fullModeLabel;
    /**
     * 文件状态条：合并展示"已选 X / Y 个文件"与编译策略片段（含 .java 编译 / 静态资源跳过 / 未勾选）。
     * 与右侧 TargetSectionPanel.selectionSummary 风格一致，单行展示节省纵向空间。
     */
    private final JBLabel fileCountLabel;
    /**
     * 文件搜索框，使用 IDEA 平台原生 SearchTextField：
     * 自带左侧 🔍 图标、右侧 × 清空按钮、历史下拉，常驻显示无需 toggle。
     */
    private final SearchTextField fileSearchField;

    // CheckboxTree 替代 CheckBoxList
    private CheckboxTree fileTree;
    private CheckedTreeNode treeRoot;
    private JBScrollPane fileScrollPane;

    /** 变更文件树当前鼠标悬浮行（-1 为无） */
    private int fileTreeHoverRow = -1;

    private List<String> rawFiles = List.of();
    private List<FileEntry> allFileEntries = List.of();
    private final Set<String> checkedFilePaths = new LinkedHashSet<>();
    private int filteredFileCount = 0;
    private boolean rebuildingFileTree;
    private boolean suppressFileSearchEvents;
    private boolean expandChangedFoldersOnly;

    /** 当前选中的模块绝对路径 */
    private String currentModulePath;

    /** 模块选中回调，通知外部（DeployToolWindowPanel）执行联动 */
    private Consumer<String> moduleSelectedCallback;

    /** 模式切换回调，通知外部根据当前模块和模式刷新文件列表 */
    private Consumer<DeployMode> modeChangeCallback;

    /** 是否抑制模式切换回调（外部 setMode 时防止重复执行） */
    private boolean suppressModeCallback;

    /** 模块树弹窗是否已打开（防重入） */
    private boolean modulePopupOpen;

    /**
     * 构造源信息面板
     *
     * @param project 当前 IDEA 项目
     * @author xumanyi
     * @date 2026-03-27
     */
    public SourceSectionPanel(Project project) {
        super(new GridBagLayout());
        this.project = project;

        // 用 PickerComboBox（视觉=JComboBox / 点击=自定义弹窗）替换原 JButton，
        // 让控件边框、暗色背景、高度、下拉箭头与同面板的"产物""更新模式"
        // 以及右侧"项目""系统"完全一致；点击下拉时仍弹出模块树搜索面板。
        this.moduleCombo = new PickerComboBox("点击选择工程", this::showModuleTreePopup);
        moduleCombo.setToolTipText("选择要更新的 Maven 模块（源工程）");
        this.artifactField = new JBTextField();
        artifactField.setEditable(false);
        artifactField.setFocusable(false);
        artifactField.setEnabled(false);
        artifactField.getEmptyText().setText("选择工程后自动填充");
        artifactField.setToolTipText(
                "<html>当前源工程的产物文件名（从 pom 自动解析）。"
                + "<br>用于匹配目标 FTP 包或本地包的版本。</html>");
        // UI 只暴露两档：FULL / INCREMENTAL；AUTO_DETECT 是 CLI 内部值，不进下拉
        this.modeComboBox = new JComboBox<>(new DeployMode[]{DeployMode.FULL, DeployMode.INCREMENTAL});
        modeComboBox.setToolTipText(
                "<html><b>整包更新</b>：重新编译并上传整个 jar/war<br>"
                + "<b>增量更新</b>：列出可部署文件，Git 变更默认勾选；<br>"
                + "用户可在此基础上手动增删勾选</html>");
        this.dynamicContent = new JPanel(new CardLayout());

        this.fullModeLabel = new JBLabel("将替换整个远程包");
        this.fileCountLabel = new JBLabel("");
        // 关闭历史下拉：本场景每次进入文件列表都是新的模块/分支，复用历史关键字价值有限，
        // 且会让 × 旁边多一个三角按钮，挤压输入区
        this.fileSearchField = new SearchTextField(false);
        fileSearchField.getTextEditor().getEmptyText().setText("搜索文件名或路径");
        fileSearchField.getTextEditor().setToolTipText(
                "按文件名、目录路径或变更状态过滤文件树，支持空格分隔多个关键字与拼音");

        // 初始化 CheckboxTree
        this.treeRoot = new CheckedTreeNode("变更文件");
        this.fileTree = new CheckboxTree(new FileTreeCellRenderer(), treeRoot) {
            @Override
            protected void onNodeStateChanged(CheckedTreeNode node) {
                super.onNodeStateChanged(node);
                if (!rebuildingFileTree) {
                    syncVisibleCheckedState();
                }
                updateFileCountLabel();
            }

            @Override
            protected void paintComponent(Graphics g) {
                // 先铺 hover 行背景条，再让 super 画树内容。
                // FileTreeCellRenderer 非选中行不自绘背景，颜色能正常透出；
                // 选中行 super 会覆盖我们的 hover 条，自然让位给选中色。
                if (fileTreeHoverRow >= 0 && fileTreeHoverRow < getRowCount()
                        && !isRowSelected(fileTreeHoverRow)) {
                    Rectangle b = getRowBounds(fileTreeHoverRow);
                    if (b != null) {
                        g.setColor(hoverBackgroundColor());
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
            public void mouseMoved(MouseEvent e) {
                int row = fileTree.getRowForLocation(e.getX(), e.getY());
                if (row != fileTreeHoverRow) {
                    fileTreeHoverRow = row;
                    fileTree.repaint();
                }
            }
        });
        fileTree.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() != 2) return;
                TreePath path = fileTree.getPathForLocation(e.getX(), e.getY());
                if (path == null) return;
                Object node = path.getLastPathComponent();
                if (node instanceof CheckedTreeNode ctn
                        && ctn.getUserObject() instanceof String) {
                    if (fileTree.isExpanded(path)) {
                        fileTree.collapsePath(path);
                    } else {
                        fileTree.expandPath(path);
                    }
                }
            }

            @Override
            public void mouseExited(MouseEvent e) {
                if (fileTreeHoverRow != -1) {
                    fileTreeHoverRow = -1;
                    fileTree.repaint();
                }
            }
        });
        this.fileScrollPane = new JBScrollPane(fileTree);

        initUI();
        initListeners();

        // 默认选中"增量更新"（合并自原 INCREMENTAL+AUTO_DETECT；Git 变更预选 + 用户可调，是日常高频场景）。
        // 必须放在 initListeners() 之后：此处 listener 已就绪，setSelectedItem 会触发
        // CardLayout 从默认的 "FULL" 卡同步切到 "FILE_LIST"，保证下拉值与右侧显示区一致；
        // 若放在 listener 绑定前，下拉显示 INCREMENTAL 但右侧仍停在 FULL 卡，
        // 走右键"增量更新"入口时 setSelectedItem 同值不触发 listener，问题不可自愈。
        // 此时 currentModulePath 为 null 且 modeChangeCallback 尚未注入，不会触发外部回调。
        this.modeComboBox.setSelectedItem(DeployMode.INCREMENTAL);
    }

    /** 初始化 UI 布局：工程标签、产物标签、模式下拉和动态内容区 */
    private void initUI() {
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(3, 2, 3, 2);
        gbc.anchor = GridBagConstraints.WEST;

        gbc.gridx = 0; gbc.gridy = 0;
        add(new JBLabel("工程："), gbc);
        gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0;
        add(moduleCombo, gbc);

        gbc.gridx = 0; gbc.gridy = 1; gbc.fill = GridBagConstraints.NONE; gbc.weightx = 0;
        add(new JBLabel("产物："), gbc);
        gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0;
        add(artifactField, gbc);

        gbc.gridx = 0; gbc.gridy = 2; gbc.fill = GridBagConstraints.NONE; gbc.weightx = 0;
        add(new JBLabel("更新模式："), gbc);
        JPanel modeRow = new JPanel(new BorderLayout(4, 0));
        modeRow.add(modeComboBox, BorderLayout.CENTER);

        JButton refreshModeButton = new JButton(AllIcons.Actions.Refresh);
        int btnSize = modeComboBox.getPreferredSize().height;
        Dimension iconBtnSize = new Dimension(btnSize, btnSize);
        refreshModeButton.setMargin(JBUI.emptyInsets());
        refreshModeButton.setPreferredSize(iconBtnSize);
        refreshModeButton.setFocusPainted(false);
        refreshModeButton.setFocusable(false);
        refreshModeButton.setToolTipText(
                "<html>刷新当前模式的文件列表"
                + "<br>整包更新：无变化"
                + "<br>增量更新：重新扫描模块文件并重新执行 Git 变更检测</html>");
        refreshModeButton.addActionListener(e -> refreshCurrentMode());

        modeRow.add(refreshModeButton, BorderLayout.EAST);
        gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0;
        add(modeRow, gbc);

        // 全量提示
        dynamicContent.add(fullModeLabel, "FULL");

        // 文件树面板：南区用单个 fileCountLabel 同时承载"已选 X / Y"与编译策略片段，
        // 与右侧 TargetSectionPanel.selectionSummary 风格统一，单行展示节省纵向空间
        JPanel fileListPanel = new JPanel(new BorderLayout(0, 4));
        fileListPanel.add(fileSearchField, BorderLayout.NORTH);
        fileListPanel.add(fileScrollPane, BorderLayout.CENTER);
        fileCountLabel.setForeground(UIManager.getColor("Label.disabledForeground"));
        fileCountLabel.setBorder(JBUI.Borders.emptyTop(2));
        fileCountLabel.setToolTipText(
                "<html>编译策略由勾选清单自动决定，无需手动选择："
                + "<br>• 含任意 <b>.java</b> 文件 → 执行 <code>mvn clean package</code>"
                + "<br>• 全是静态资源（js/css/csv/模板等）→ 跳过编译，直接从源文件读取"
                + "<br>• 整包更新模式 → 始终编译"
                + "<br>取消勾选 .java 即可让本次部署跳过编译。</html>");
        fileListPanel.add(fileCountLabel, BorderLayout.SOUTH);
        dynamicContent.add(fileListPanel, "FILE_LIST");

        gbc.gridx = 0; gbc.gridy = 3; gbc.gridwidth = 2;
        gbc.fill = GridBagConstraints.BOTH; gbc.weighty = 1.0;
        add(dynamicContent, gbc);

        // 底部 spacer：dynamicContent 隐藏时吸收空间，保持内容顶部对齐
        gbc.gridy = 4; gbc.weighty = 0.01;
        add(Box.createGlue(), gbc);

        // 初始无内容时隐藏动态区
        dynamicContent.setVisible(false);
    }

    /** 初始化事件监听：模式切换、搜索过滤、Ctrl+F / Esc 快捷键 */
    private void initListeners() {
        modeComboBox.addActionListener(e -> {
            DeployMode mode = (DeployMode) modeComboBox.getSelectedItem();
            if (mode != null) {
                CardLayout cl = (CardLayout) dynamicContent.getLayout();
                cl.show(dynamicContent, mode == DeployMode.FULL ? "FULL" : "FILE_LIST");
                if (mode == DeployMode.FULL) {
                    dynamicContent.setVisible(currentModulePath != null);
                }
                if (!suppressModeCallback && modeChangeCallback != null
                        && currentModulePath != null) {
                    modeChangeCallback.accept(mode);
                }
                updateFileCountLabel();
            }
        });

        // SearchTextField 自带 addDocumentListener；其 × 清空按钮会调 setText("")，
        // 同样走这里的 listener，无需额外处理。
        fileSearchField.addDocumentListener(new javax.swing.event.DocumentListener() {
            @Override public void insertUpdate(javax.swing.event.DocumentEvent e) { refresh(); }
            @Override public void removeUpdate(javax.swing.event.DocumentEvent e) { refresh(); }
            @Override public void changedUpdate(javax.swing.event.DocumentEvent e) { refresh(); }
            private void refresh() {
                if (suppressFileSearchEvents) return;
                // 在 document 事件回调里直接动 Swing 树模型在某些 LAF 下会被吞掉 repaint。
                // 改为下一帧执行，保证每次输入都能看到树刷新。
                SwingUtilities.invokeLater(() -> {
                    if (suppressFileSearchEvents) return;
                    rebuildFileTreeFromFilter(true);
                });
            }
        });

        // 不绑定 Ctrl+F：IDEA IdeKeyEventDispatcher 在 Swing 之前就把 Ctrl+F 派给
        // 全局 Find / SpeedSearch，本地 AnAction 注册也无法稳定压过；搜索框已常驻
        // 显示，鼠标点击即可聚焦，无需快捷键。

        // Esc：先清空（让 × 的功能也覆盖到键盘用户），已空时把焦点交回文件树
        fileSearchField.getTextEditor().registerKeyboardAction(
                e -> {
                    if (!fileSearchField.getText().isEmpty()) {
                        fileSearchField.setText("");
                    } else {
                        fileTree.requestFocusInWindow();
                    }
                },
                KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_ESCAPE, 0),
                JComponent.WHEN_FOCUSED);
    }

    /**
     * 把焦点放到搜索框输入区并全选已有文本。
     *
     * <p>FULL 模式下搜索框位于 CardLayout 隐藏卡内，requestFocusInWindow
     * 会自然失败，无需额外校验。</p>
     *
     * @author xumanyi
     * @date 2026-04-30
     */
    private void focusSearchField() {
        JBTextField editor = fileSearchField.getTextEditor();
        editor.requestFocusInWindow();
        editor.selectAll();
    }

    /**
     * 设置模块路径，更新按钮显示文字
     *
     * @param modulePath 模块根目录路径，为 {@code null} 时显示默认提示
     * @author xumanyi
     * @date 2026年03月28日
     */
    public void setModule(String modulePath) {
        this.currentModulePath = modulePath;
        if (modulePath != null) {
            // 同时兼容 Unix '/' 与 Windows '\\'：取最后一段目录名
            // （Windows 下 modulePath 形如 D:\...\scev6-a05-srv，旧实现只认 '/' 所以整条路径都展示）
            int lastSep = Math.max(modulePath.lastIndexOf('/'), modulePath.lastIndexOf('\\'));
            String name = lastSep >= 0 ? modulePath.substring(lastSep + 1) : modulePath;
            // 不再追加 " ▾"：PickerComboBox 由 L&F 自动绘制下拉箭头
            moduleCombo.setText(name);
            // FULL 模式下显示提示
            DeployMode mode = (DeployMode) modeComboBox.getSelectedItem();
            if (mode == DeployMode.FULL) {
                dynamicContent.setVisible(true);
            }
        } else {
            moduleCombo.setText("点击选择工程");
            dynamicContent.setVisible(false);
        }
        updateFileCountLabel();
    }

    /**
     * 外部设置模块路径并同步按钮显示（右键菜单入口调用）
     *
     * <p>仅更新 UI 显示，不触发回调（避免循环调用）。</p>
     *
     * @param modulePath 模块根目录路径
     * @author xumanyi
     * @date 2026年03月28日
     */
    public void setModulePath(String modulePath) {
        setModule(modulePath);
    }

    /**
     * 设置模块选中回调
     *
     * @param callback 回调函数，参数为模块绝对路径
     * @author xumanyi
     * @date 2026年03月28日
     */
    public void setModuleSelectedCallback(Consumer<String> callback) {
        this.moduleSelectedCallback = callback;
    }

    /**
     * 设置模式切换回调
     *
     * @param callback 回调函数，参数为新选中的模式
     */
    public void setModeChangeCallback(Consumer<DeployMode> callback) {
        this.modeChangeCallback = callback;
    }

    /**
     * 重新触发当前模式的文件列表刷新
     *
     * <p>未选工程时无操作；整包更新模式下外部回调会直接返回，不报错。</p>
     *
     * @author xumanyi
     * @date 2026-04-17
     */
    public void refreshCurrentMode() {
        if (currentModulePath == null) return;
        if (modeChangeCallback == null) return;
        DeployMode mode = (DeployMode) modeComboBox.getSelectedItem();
        if (mode != null) modeChangeCallback.accept(mode);
    }

    /**
     * @return 当前选中的模块绝对路径，未选择时返回 {@code null}
     */
    public String getCurrentModulePath() {
        return currentModulePath;
    }

    /**
     * 设置产物文件名显示
     *
     * @param artifactFileName 产物文件名（如 scev6-utils-tms-10.0.0-SNAPSHOT.jar），为 {@code null} 时显示"-"
     * @author xumanyi
     * @date 2026-03-27
     */
    public void setArtifact(String artifactFileName) {
        artifactField.setText(artifactFileName != null ? artifactFileName : "");
        updateFileCountLabel();
    }

    /**
     * 根据当前模式与勾选清单判定是否需要 mvn package
     *
     * <p>规则：</p>
     * <ul>
     *   <li>FULL 模式：必须编译（要打整 jar）</li>
     *   <li>INCREMENTAL 模式：勾选清单内含任意 <b>.java</b> → 编译；
     *       全是静态资源（含 src/main/webapp、src/main/resources、src/main/java 下非 .java）→ 跳过</li>
     *   <li>未选工程或未勾选任何文件：保守按"需要编译"返回，由上层兜底校验</li>
     * </ul>
     *
     * @return true 表示需要执行 mvn package；false 表示可跳过
     * @author xumanyi
     * @date 2026-04-29
     */
    public boolean needsCompile() {
        DeployMode mode = (DeployMode) modeComboBox.getSelectedItem();
        if (mode == DeployMode.FULL) return true;
        List<String> selected = getSelectedFiles();
        if (selected.isEmpty()) return true;
        for (String raw : selected) {
            String path = raw == null ? "" : raw;
            if (path.length() > 2 && path.charAt(1) == ' ') {
                path = path.substring(path.indexOf(' ')).trim();
            }
            if (path.toLowerCase(Locale.ROOT).endsWith(".java")) return true;
        }
        return false;
    }

    /**
     * 计算编译策略片段（带颜色 span，无 &lt;html&gt; 包裹），由 updateFileCountLabel 拼接到状态条尾部。
     *
     * <p>未选工程返回空串；FULL 分支保留以防未来在 FULL 卡内复用，
     * 当前 CardLayout 下 fileCountLabel 仅出现在 FILE_LIST 卡。</p>
     *
     * @return 编译策略片段，未选模块返回空串
     */
    private String computeCompileStatusFragment() {
        if (currentModulePath == null) return "";
        DeployMode mode = (DeployMode) modeComboBox.getSelectedItem();
        if (mode == DeployMode.FULL) {
            return "<span style='color:#6897BB;'>⚙ 整包更新：将执行 mvn clean package</span>";
        }
        List<String> selected = getSelectedFiles();
        if (selected.isEmpty()) {
            return "<span style='color:gray;'>未勾选</span>";
        }
        int javaCount = 0;
        for (String raw : selected) {
            String p = raw == null ? "" : raw;
            if (p.length() > 2 && p.charAt(1) == ' ') p = p.substring(p.indexOf(' ')).trim();
            if (p.toLowerCase(Locale.ROOT).endsWith(".java")) javaCount++;
        }
        if (javaCount > 0) {
            return "<span style='color:#6897BB;'>含 " + javaCount + " 个 .java，将编译</span>";
        }
        return "<span style='color:#6A8759;'>静态资源，跳过编译</span>";
    }

    /**
     * 设置更新模式
     *
     * @param mode 更新模式枚举
     * @author xumanyi
     * @date 2026-03-27
     */
    public void setMode(DeployMode mode) {
        suppressModeCallback = true;
        try {
            modeComboBox.setSelectedItem(mode);
        } finally {
            suppressModeCallback = false;
        }
    }

    /**
     * 获取当前选中的更新模式
     *
     * @return 更新模式枚举
     * @author xumanyi
     * @date 2026-03-27
     */
    public DeployMode getMode() {
        return (DeployMode) modeComboBox.getSelectedItem();
    }

    /**
     * 设置变更文件列表，构建多级目录树结构（默认全选）
     *
     * <p>按目录路径构建真正的树形层级：</p>
     * <pre>
     * ▼ src/main/java
     *   ▼ com/flux/scev6/utils/tms
     *       [A] AAAAAAAAVal.java
     *   ▼ com/flux/scev6/utils/tms/business
     *       [M] TaskM2D02CVal.java
     * </pre>
     */
    public void setChangedFiles(List<String> files) {
        setChangedFiles(files, true);
    }

    /**
     * 设置变更文件列表，构建多级目录树结构
     *
     * @param files          文件路径列表
     * @param defaultChecked 是否默认勾选所有文件
     */
    public void setChangedFiles(List<String> files, boolean defaultChecked) {
        setChangedFiles(files, defaultChecked, null);
    }

    /**
     * 设置变更文件列表，构建多级目录树结构（支持预选文件集合）
     *
     * @param files           文件路径列表（全量）
     * @param defaultChecked  无预选集合时的默认勾选状态
     * @param preSelectedPaths 预选文件的相对路径集合，匹配的文件勾选，其余不勾选；
     *                         为 {@code null} 时使用 defaultChecked
     */
    public void setChangedFiles(List<String> files, boolean defaultChecked,
                                 Set<String> preSelectedPaths) {
        setChangedFiles(files, defaultChecked, preSelectedPaths, false);
    }

    /**
     * 设置变更文件列表，构建多级目录树结构（支持预选文件集合和自动检测展开策略）
     *
     * @param files                    文件路径列表（全量）
     * @param defaultChecked           无预选集合时的默认勾选状态
     * @param preSelectedPaths         预选文件的相对路径集合，匹配的文件勾选，其余不勾选；
     *                                 为 {@code null} 时使用 defaultChecked
     * @param expandChangedFoldersOnly true 时只展开包含 Git 变更文件的目录分支
     */
    public void setChangedFiles(List<String> files, boolean defaultChecked,
                                 Set<String> preSelectedPaths,
                                 boolean expandChangedFoldersOnly) {
        this.rawFiles = files != null ? new ArrayList<>(files) : List.of();
        this.allFileEntries = parseFileEntries(rawFiles);
        this.expandChangedFoldersOnly = expandChangedFoldersOnly;
        checkedFilePaths.clear();

        if (!allFileEntries.isEmpty()) {
            if (preSelectedPaths != null) {
                Set<String> normalizedPreSelected = new LinkedHashSet<>();
                for (String p : preSelectedPaths) {
                    normalizedPreSelected.add(p == null ? "" : p.replace('\\', '/'));
                }
                for (FileEntry entry : allFileEntries) {
                    if (normalizedPreSelected.contains(entry.fullPath)) {
                        checkedFilePaths.add(entry.fullPath);
                    }
                }
            } else if (defaultChecked) {
                for (FileEntry entry : allFileEntries) {
                    checkedFilePaths.add(entry.fullPath);
                }
            }
        }

        suppressFileSearchEvents = true;
        try {
            if (!fileSearchField.getText().isEmpty()) {
                fileSearchField.setText("");
            }
        } finally {
            suppressFileSearchEvents = false;
        }
        rebuildFileTreeFromFilter(false);

        // 动态显隐
        dynamicContent.setVisible(!allFileEntries.isEmpty());
        updateFileCountLabel();
        revalidate();
        repaint();
    }

    /**
     * 解析文件路径列表，统一为可过滤、可回写勾选状态的条目。
     */
    private List<FileEntry> parseFileEntries(List<String> files) {
        if (files == null || files.isEmpty()) {
            return List.of();
        }
        List<FileEntry> entries = new ArrayList<>();
        for (String raw : files) {
            String status = "";
            String path = raw == null ? "" : raw;
            if (path.length() > 2 && path.charAt(1) == ' ') {
                status = String.valueOf(path.charAt(0));
                path = path.substring(path.indexOf(' ')).trim();
            }
            // 规范化路径分隔符：Windows 下 File.getPath() 返回 '\'，而 VCS / 手动勾选 Action
            // 返回的可能是 '/'。这里统一成 '/'，下游 buildDirectoryTree 的 split / lastIndexOf
            // 才能正确拆成多级节点；否则 Windows 上会整条 path 当作一个叶子节点显示。
            path = path.replace('\\', '/');
            entries.add(new FileEntry(raw, status, path));
        }
        return entries;
    }

    /**
     * 根据搜索框内容刷新文件树，过滤时保留隐藏文件的勾选状态。
     */
    private void rebuildFileTreeFromFilter(boolean syncBeforeRebuild) {
        if (syncBeforeRebuild) {
            syncVisibleCheckedState();
        }

        String keyword = fileSearchField.getText().trim();
        List<FileEntry> visibleEntries = filterFileEntries(keyword);
        filteredFileCount = visibleEntries.size();
        treeRoot = new CheckedTreeNode("变更文件");
        if (!visibleEntries.isEmpty()) {
            buildDirectoryTree(treeRoot, visibleEntries, checkedFilePaths);
        }

        rebuildingFileTree = true;
        try {
            DefaultTreeModel model = new DefaultTreeModel(treeRoot);
            fileTree.setModel(model);
            fileTree.setRootVisible(false);
            fileTreeHoverRow = -1;
            expandFileTreeAfterRebuild(!keyword.isEmpty());
        } finally {
            rebuildingFileTree = false;
        }
        updateFileCountLabel();
    }

    /**
     * 重建树后按当前模式展开节点。
     */
    private void expandFileTreeAfterRebuild(boolean searching) {
        if (!expandChangedFoldersOnly || searching) {
            expandAllFileTreeRows();
            return;
        }
        expandChangedFileBranches(treeRoot, new TreePath(treeRoot));
    }

    /** 展开当前文件树的所有行。 */
    private void expandAllFileTreeRows() {
        for (int i = 0; i < fileTree.getRowCount(); i++) {
            fileTree.expandRow(i);
        }
    }

    /**
     * 仅展开包含 Git 变更文件的目录分支。
     *
     * @return 当前节点下是否包含 Git 变更文件
     */
    private boolean expandChangedFileBranches(CheckedTreeNode node, TreePath path) {
        Object userObj = node.getUserObject();
        if (userObj instanceof FileEntry fe) {
            return fe.isGitChanged();
        }

        boolean hasChanged = false;
        for (int i = 0; i < node.getChildCount(); i++) {
            if (node.getChildAt(i) instanceof CheckedTreeNode child) {
                TreePath childPath = path.pathByAddingChild(child);
                if (expandChangedFileBranches(child, childPath)) {
                    hasChanged = true;
                }
            }
        }
        if (hasChanged) {
            fileTree.expandPath(path);
        }
        return hasChanged;
    }

    /**
     * 按关键字过滤文件条目，空关键字返回全量条目。
     *
     * <p>支持空格分隔的多关键字"与"匹配，每个关键字命中以下任一即视为通过：</p>
     * <ol>
     *   <li>原始文本子串（状态 + 文件名 + 完整路径，大小写不敏感）</li>
     *   <li>拼音全拼或拼音首字母匹配（中文路径专用）</li>
     * </ol>
     */
    private List<FileEntry> filterFileEntries(String keyword) {
        if (keyword == null || keyword.isEmpty()) {
            return allFileEntries;
        }
        String[] terms = keyword.toLowerCase(Locale.ROOT).split("\\s+");
        List<FileEntry> result = new ArrayList<>();
        for (FileEntry entry : allFileEntries) {
            String searchable = (entry.status + " " + entry.fileName + " " + entry.fullPath)
                    .toLowerCase(Locale.ROOT);
            String pinyinCandidate = entry.status + " " + entry.fileName + " " + entry.fullPath;
            boolean matched = true;
            for (String term : terms) {
                if (!searchable.contains(term)
                        && !com.flux.deploy.plugin.util.PinyinMatcher.matches(pinyinCandidate, term)) {
                    matched = false;
                    break;
                }
            }
            if (matched) {
                result.add(entry);
            }
        }
        return result;
    }

    /**
     * 将当前可见树的勾选状态同步回全量选择集合。
     */
    private void syncVisibleCheckedState() {
        Set<String> visiblePaths = new LinkedHashSet<>();
        Set<String> visibleCheckedPaths = new LinkedHashSet<>();
        collectVisibleFileState(treeRoot, visiblePaths, visibleCheckedPaths);
        checkedFilePaths.removeAll(visiblePaths);
        checkedFilePaths.addAll(visibleCheckedPaths);
    }

    /** 递归收集当前可见文件节点的路径及其勾选状态。 */
    private void collectVisibleFileState(CheckedTreeNode node, Set<String> visiblePaths,
                                         Set<String> visibleCheckedPaths) {
        Object userObj = node.getUserObject();
        if (userObj instanceof FileEntry fe) {
            visiblePaths.add(fe.fullPath);
            if (node.isChecked()) {
                visibleCheckedPaths.add(fe.fullPath);
            }
            return;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            if (node.getChildAt(i) instanceof CheckedTreeNode child) {
                collectVisibleFileState(child, visiblePaths, visibleCheckedPaths);
            }
        }
    }

    /**
     * 将文件列表构建为多级目录树（合并单子节点目录）
     */
    private void buildDirectoryTree(CheckedTreeNode root, List<FileEntry> entries,
                                     Set<String> checkedPaths) {
        // 按目录分组：key = 目录路径（如 "src/main/java/com/flux"），value = 文件条目列表
        Map<String, List<FileEntry>> dirMap = new LinkedHashMap<>();
        for (FileEntry fe : entries) {
            int lastSlash = fe.fullPath.lastIndexOf('/');
            String dir = lastSlash > 0 ? fe.fullPath.substring(0, lastSlash) : "(root)";
            dirMap.computeIfAbsent(dir, k -> new ArrayList<>()).add(fe);
        }

        // 构建 trie 树结构
        DirNode trieRoot = new DirNode("");
        for (Map.Entry<String, List<FileEntry>> entry : dirMap.entrySet()) {
            String[] parts = entry.getKey().split("/");
            DirNode current = trieRoot;
            for (String part : parts) {
                current = current.children.computeIfAbsent(part, k -> new DirNode(k));
            }
            current.files.addAll(entry.getValue());
        }

        // 从 trie 构建 CheckedTreeNode，合并只有单个子目录且无文件的中间节点
        for (DirNode child : trieRoot.children.values()) {
            buildCompressedNode(root, child, child.name, checkedPaths);
        }
    }

    /**
     * 递归构建压缩路径的树节点
     *
     * <p>当一个目录只有一个子目录且自身没有文件时，合并路径显示
     * （如 src/main/java 合并为一个节点，而不是三级嵌套）。</p>
     */
    private void buildCompressedNode(CheckedTreeNode parent, DirNode dirNode,
                                      String displayPath, Set<String> checkedPaths) {
        // 合并单链路径：只有一个子目录且自身无文件时，继续拼接
        if (dirNode.files.isEmpty() && dirNode.children.size() == 1) {
            Map.Entry<String, DirNode> only = dirNode.children.entrySet().iterator().next();
            buildCompressedNode(parent, only.getValue(),
                    displayPath + "/" + only.getKey(), checkedPaths);
            return;
        }

        // 创建目录节点
        int fileCount = countFiles(dirNode);
        CheckedTreeNode dirTreeNode = new CheckedTreeNode(
                displayPath + "  (" + fileCount + " 个文件)");
        dirTreeNode.setChecked(fileCount > 0
                && countCheckedFiles(dirNode, checkedPaths) == fileCount);

        // 先递归子目录（目录排在文件之前，符合常见文件浏览器交互习惯）
        for (Map.Entry<String, DirNode> child : dirNode.children.entrySet()) {
            buildCompressedNode(dirTreeNode, child.getValue(),
                    child.getKey(), checkedPaths);
        }

        // 再添加本目录下的文件
        for (FileEntry fe : dirNode.files) {
            String fileName = fe.fullPath.substring(fe.fullPath.lastIndexOf('/') + 1);
            FileEntry display = new FileEntry(fe.rawPath, fe.status, fileName, fe.fullPath);
            CheckedTreeNode fileNode = new CheckedTreeNode(display);
            fileNode.setChecked(checkedPaths.contains(fe.fullPath));
            dirTreeNode.add(fileNode);
        }

        parent.add(dirTreeNode);
    }

    /** 递归统计目录下的文件总数 */
    private int countFiles(DirNode node) {
        int count = node.files.size();
        for (DirNode child : node.children.values()) {
            count += countFiles(child);
        }
        return count;
    }

    /** 递归统计目录下已勾选的文件数 */
    private int countCheckedFiles(DirNode node, Set<String> checkedPaths) {
        int count = 0;
        for (FileEntry entry : node.files) {
            if (checkedPaths.contains(entry.fullPath)) {
                count++;
            }
        }
        for (DirNode child : node.children.values()) {
            count += countCheckedFiles(child, checkedPaths);
        }
        return count;
    }

    /** 目录 trie 节点 */
    private static class DirNode {
        final String name;
        final Map<String, DirNode> children = new LinkedHashMap<>();
        final List<FileEntry> files = new ArrayList<>();

        DirNode(String name) { this.name = name; }
    }

    /**
     * 获取用户勾选的文件列表（原始路径）
     */
    public List<String> getSelectedFiles() {
        List<String> selected = new ArrayList<>();
        for (FileEntry entry : allFileEntries) {
            if (checkedFilePaths.contains(entry.fullPath)) {
                selected.add(entry.rawPath);
            }
        }
        return selected;
    }

    /**
     * 更新底部状态条：合并展示"已选 X / Y 个文件"与编译策略片段。
     *
     * <p>计数部分沿用 label 默认 disabledForeground；编译策略片段用 html span 单独着色。
     * 无文件时清空文本（dynamicContent 在外层负责整体显隐）。</p>
     */
    private void updateFileCountLabel() {
        int total = rawFiles.size();
        if (total == 0) {
            fileCountLabel.setText("");
            return;
        }
        int checked = getSelectedFiles().size();
        String keyword = fileSearchField.getText().trim();
        String count;
        if (keyword.isEmpty()) {
            count = "已选 " + checked + " / " + total + " 个文件";
        } else if (filteredFileCount == 0) {
            count = "未找到匹配文件，已选 " + checked + " / " + total + " 个文件";
        } else {
            count = "已选 " + checked + " / " + total
                    + " 个文件，筛选 " + filteredFileCount + " 个";
        }
        String compile = computeCompileStatusFragment();
        if (compile.isEmpty()) {
            fileCountLabel.setText(count);
        } else {
            fileCountLabel.setText("<html>" + count + " · " + compile + "</html>");
        }
    }

    // ==================== 模块树弹窗 ====================

    /**
     * 弹出模块树选择弹窗（无标题栏轻量对话框）
     *
     * <p>使用 JDialog（非 JPopupMenu）解决滚轮和点击事件被吞的问题。
     * 对话框定位在按钮下方，点击外部或选中叶子后自动关闭。</p>
     */
    /** 当前打开的弹窗引用 */
    private JDialog moduleDialog;
    /** AWTEventListener 引用，确保能正确移除 */
    private java.awt.event.AWTEventListener outsideClickListener;

    /** 强制关闭当前弹窗（无论状态） */
    private void forceClosePopup() {
        if (outsideClickListener != null) {
            Toolkit.getDefaultToolkit().removeAWTEventListener(outsideClickListener);
            outsideClickListener = null;
        }
        if (moduleDialog != null) {
            moduleDialog.dispose();
            moduleDialog = null;
        }
        modulePopupOpen = false;
    }

    /**
     * 取当前 L&amp;F 下的行悬浮背景色，主题切换时自动适配
     *
     * <p>优先级：List.hoverBackground → Tree.hoverBackground → List.selectionBackground 兜底。
     * 同包下 DeployConfirmDialog 的文件树也复用此色。</p>
     *
     * @return 悬浮背景色，永远非 {@code null}
     */
    static Color hoverBackgroundColor() {
        Color c = UIManager.getColor("List.hoverBackground");
        if (c == null) c = UIManager.getColor("Tree.hoverBackground");
        if (c == null) c = UIManager.getColor("List.selectionBackground");
        if (c == null) c = new Color(62, 90, 130);
        return c;
    }

    private void showModuleTreePopup() {
        // 无论什么状态，先强制关闭旧弹窗
        forceClosePopup();

        List<ModuleTreeNode> moduleTree = ModuleEnumerator.getModuleTree(project);
        if (moduleTree.isEmpty()) {
            JOptionPane.showMessageDialog(this, "未找到 Maven 模块",
                    "提示", JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        modulePopupOpen = true;

        // 1. 构建扁平叶子列表（附父路径展示），供搜索用
        List<ModuleLeafEntry> allLeaves = new ArrayList<>();
        collectLeaves(moduleTree, new ArrayList<>(), allLeaves);

        // 2. 树组件
        DefaultMutableTreeNode root = new DefaultMutableTreeNode("工程列表");
        buildTreeNodes(root, moduleTree);
        Tree tree = new Tree(root);
        tree.setRootVisible(false);
        tree.setShowsRootHandles(true);
        tree.getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);
        for (int i = 0; i < tree.getRowCount(); i++) tree.expandRow(i);

        // 鼠标悬浮行高亮：数组装指针，渲染器按需着色，mouseMoved 更新后整树重绘
        final int[] treeHoverRow = { -1 };
        tree.setCellRenderer(new javax.swing.tree.DefaultTreeCellRenderer() {
            @Override
            public Component getTreeCellRendererComponent(JTree tr, Object value, boolean sel,
                    boolean expanded, boolean leaf, int row, boolean hasFocus) {
                super.getTreeCellRendererComponent(tr, value, sel, expanded, leaf, row, hasFocus);
                // 非 hover 行必须显式重置回 tree 本身背景色：
                // 不能传 null——DefaultTreeCellRenderer.paintComponent 看到 null 时会退化到
                // getBackground()，而 super 为了画选中行可能把 JLabel.background 设为选中色，
                // 从而让后续未选中行拿到脏的选中色（整列发蓝即此原因）。
                if (!sel && row == treeHoverRow[0] && row >= 0) {
                    setBackgroundNonSelectionColor(hoverBackgroundColor());
                } else {
                    setBackgroundNonSelectionColor(tr.getBackground());
                }
                return this;
            }
        });
        tree.addMouseMotionListener(new java.awt.event.MouseMotionAdapter() {
            @Override
            public void mouseMoved(MouseEvent e) {
                int row = tree.getRowForLocation(e.getX(), e.getY());
                if (row != treeHoverRow[0]) {
                    treeHoverRow[0] = row;
                    tree.repaint();
                }
            }
        });
        tree.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseExited(MouseEvent e) {
                if (treeHoverRow[0] != -1) {
                    treeHoverRow[0] = -1;
                    tree.repaint();
                }
            }
        });

        // 3. 搜索结果列表（扁平 JList，自定义渲染显示父路径）
        DefaultListModel<ModuleLeafEntry> listModel = new DefaultListModel<>();
        JList<ModuleLeafEntry> filteredList = new JList<>(listModel);
        filteredList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);

        // 鼠标悬浮行高亮：数组装指针，包装原渲染器，对非选中的 hover 行覆盖背景色
        final int[] listHoverIndex = { -1 };
        ModuleLeafRenderer baseLeafRenderer = new ModuleLeafRenderer();
        filteredList.setCellRenderer((ListCellRenderer<ModuleLeafEntry>)
                (list, value, index, isSelected, cellHasFocus) -> {
            Component c = baseLeafRenderer.getListCellRendererComponent(
                    list, value, index, isSelected, cellHasFocus);
            if (!isSelected && index == listHoverIndex[0] && c instanceof JComponent jc) {
                jc.setBackground(hoverBackgroundColor());
                jc.setOpaque(true);
            }
            return c;
        });
        filteredList.addMouseMotionListener(new java.awt.event.MouseMotionAdapter() {
            @Override
            public void mouseMoved(MouseEvent e) {
                int idx = filteredList.locationToIndex(e.getPoint());
                // locationToIndex 会返回最近行索引即使鼠标在列表外；显式校验是否在 cell 内
                if (idx >= 0) {
                    Rectangle cell = filteredList.getCellBounds(idx, idx);
                    if (cell == null || !cell.contains(e.getPoint())) idx = -1;
                }
                if (idx != listHoverIndex[0]) {
                    listHoverIndex[0] = idx;
                    filteredList.repaint();
                }
            }
        });
        filteredList.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseExited(MouseEvent e) {
                if (listHoverIndex[0] != -1) {
                    listHoverIndex[0] = -1;
                    filteredList.repaint();
                }
            }
        });

        // 4. CardLayout：tree 与 filteredList 切换
        CardLayout cards = new CardLayout();
        JPanel contentPanel = new JPanel(cards);
        JBScrollPane treeScroll = new JBScrollPane(tree);
        JBScrollPane listScroll = new JBScrollPane(filteredList);
        contentPanel.add(treeScroll, "tree");
        contentPanel.add(listScroll, "list");
        contentPanel.setPreferredSize(new Dimension(400, 360));

        // 5. 搜索框
        javax.swing.JTextField searchField = new javax.swing.JTextField();
        searchField.putClientProperty("JTextField.placeholderText", "搜索模块（artifactId 或父目录名）...");

        Window owner = SwingUtilities.getWindowAncestor(moduleCombo);
        JDialog dialog = new JDialog(owner);
        dialog.setUndecorated(true);
        // 不要 setAlwaysOnTop(true)：会让弹窗浮在其他应用之上，切到其他应用时弹窗仍可见。
        // 依赖 owner 的父子关系保证在 IDE 前台时弹窗位于 IDE 内容之上即可。
        dialog.setLayout(new BorderLayout(0, 2));
        dialog.getRootPane().setBorder(BorderFactory.createLineBorder(Color.GRAY));
        dialog.add(searchField, BorderLayout.NORTH);
        dialog.add(contentPanel, BorderLayout.CENTER);
        this.moduleDialog = dialog;

        dialog.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosed(java.awt.event.WindowEvent e) {
                if (moduleDialog == dialog) {
                    modulePopupOpen = false;
                    moduleDialog = null;
                }
                if (outsideClickListener != null) {
                    Toolkit.getDefaultToolkit().removeAWTEventListener(outsideClickListener);
                    outsideClickListener = null;
                }
            }
        });

        // 6. 选中处理：tree / list 都走统一回调
        Runnable pickFromTree = () -> {
            TreePath path = tree.getSelectionPath();
            if (path == null) return;
            DefaultMutableTreeNode node = (DefaultMutableTreeNode) path.getLastPathComponent();
            if (node.getUserObject() instanceof ModuleTreeNode mn && mn.isLeaf()) {
                pickLeaf(mn.getModulePath());
            }
        };
        Runnable pickFromList = () -> {
            ModuleLeafEntry sel = filteredList.getSelectedValue();
            if (sel != null) pickLeaf(sel.modulePath);
        };

        tree.addTreeSelectionListener(tse -> SwingUtilities.invokeLater(pickFromTree));
        filteredList.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseClicked(java.awt.event.MouseEvent e) {
                if (filteredList.getSelectedValue() != null) pickFromList.run();
            }
        });

        // 7. 搜索框输入：空 → tree；非空 → 过滤 list
        searchField.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            @Override public void insertUpdate(javax.swing.event.DocumentEvent e) { refresh(); }
            @Override public void removeUpdate(javax.swing.event.DocumentEvent e) { refresh(); }
            @Override public void changedUpdate(javax.swing.event.DocumentEvent e) { refresh(); }
            private void refresh() {
                String kw = searchField.getText().trim();
                if (kw.isEmpty()) {
                    cards.show(contentPanel, "tree");
                } else {
                    listModel.clear();
                    for (ModuleLeafEntry entry : allLeaves) {
                        if (com.flux.deploy.plugin.util.PinyinMatcher.matches(entry.displayName, kw)
                                || com.flux.deploy.plugin.util.PinyinMatcher.matches(entry.parentPath, kw)) {
                            listModel.addElement(entry);
                        }
                    }
                    if (!listModel.isEmpty()) filteredList.setSelectedIndex(0);
                    cards.show(contentPanel, "list");
                }
            }
        });

        // 8. 快捷键：
        //   Enter 在搜索框或列表上 → 选中当前高亮
        //   Down/Up 从搜索框 → 跳到列表
        searchField.addActionListener(e -> {
            if (!listModel.isEmpty()) pickFromList.run();
        });
        searchField.addKeyListener(new java.awt.event.KeyAdapter() {
            @Override public void keyPressed(java.awt.event.KeyEvent e) {
                if (e.getKeyCode() == java.awt.event.KeyEvent.VK_DOWN && !listModel.isEmpty()) {
                    filteredList.requestFocusInWindow();
                    if (filteredList.getSelectedIndex() < 0) filteredList.setSelectedIndex(0);
                }
            }
        });
        filteredList.addKeyListener(new java.awt.event.KeyAdapter() {
            @Override public void keyPressed(java.awt.event.KeyEvent e) {
                if (e.getKeyCode() == java.awt.event.KeyEvent.VK_ENTER) pickFromList.run();
            }
        });

        dialog.getRootPane().registerKeyboardAction(
                ev -> forceClosePopup(),
                KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_ESCAPE, 0),
                JComponent.WHEN_IN_FOCUSED_WINDOW);

        dialog.pack();
        Point loc = moduleCombo.getLocationOnScreen();
        dialog.setLocation(loc.x, loc.y + moduleCombo.getHeight());
        dialog.setVisible(true);
        searchField.requestFocusInWindow();

        // 点击外部关闭（延迟注册）
        SwingUtilities.invokeLater(() -> {
            outsideClickListener = event -> {
                if (event instanceof MouseEvent me && me.getID() == MouseEvent.MOUSE_PRESSED) {
                    if (moduleDialog != null && moduleDialog.isShowing()) {
                        try {
                            Point click = me.getLocationOnScreen();
                            Rectangle bounds = moduleDialog.getBounds();
                            // 也排除按钮区域（防止关闭后立即重新打开）
                            Rectangle btnBounds = moduleCombo.getBounds();
                            Point btnLoc = moduleCombo.getLocationOnScreen();
                            Rectangle btnScreen = new Rectangle(btnLoc.x, btnLoc.y,
                                    btnBounds.width, btnBounds.height);
                            if (!bounds.contains(click) && !btnScreen.contains(click)) {
                                SwingUtilities.invokeLater(() -> forceClosePopup());
                            }
                        } catch (Exception ignored) {
                            // 组件可能已不在屏幕上
                        }
                    }
                }
            };
            Toolkit.getDefaultToolkit().addAWTEventListener(
                    outsideClickListener, java.awt.AWTEvent.MOUSE_EVENT_MASK);
        });
    }

    /** 递归构建 JTree 节点 */
    private void buildTreeNodes(DefaultMutableTreeNode parent,
                                List<ModuleTreeNode> treeNodes) {
        for (ModuleTreeNode node : treeNodes) {
            DefaultMutableTreeNode treeNode = new DefaultMutableTreeNode(node);
            if (!node.isLeaf()) {
                buildTreeNodes(treeNode, node.getChildren());
            }
            parent.add(treeNode);
        }
    }

    /** 递归收集所有叶子模块，记录其从根到父的路径，供搜索展示 */
    private static void collectLeaves(List<ModuleTreeNode> nodes,
                                       List<String> breadcrumb,
                                       List<ModuleLeafEntry> out) {
        for (ModuleTreeNode node : nodes) {
            if (node.isLeaf()) {
                out.add(new ModuleLeafEntry(
                        node.getDisplayName(),
                        String.join(" / ", breadcrumb),
                        node.getModulePath()));
            } else {
                breadcrumb.add(node.getDisplayName());
                collectLeaves(node.getChildren(), breadcrumb, out);
                breadcrumb.remove(breadcrumb.size() - 1);
            }
        }
    }

    /** 统一的叶子选中动作：设置 module + 关闭弹窗 + 通知外部回调 */
    private void pickLeaf(String modulePath) {
        if (modulePath == null) return;
        SwingUtilities.invokeLater(() -> {
            setModule(modulePath);
            forceClosePopup();
            if (moduleSelectedCallback != null) {
                moduleSelectedCallback.accept(modulePath);
            }
        });
    }

    /** 搜索结果中的叶子条目（带父路径） */
    private static class ModuleLeafEntry {
        final String displayName;
        final String parentPath;  // e.g. "scev6 / v6_000_utils"
        final String modulePath;
        ModuleLeafEntry(String displayName, String parentPath, String modulePath) {
            this.displayName = displayName;
            this.parentPath = parentPath;
            this.modulePath = modulePath;
        }
    }

    /** 叶子条目渲染器：主标题 + 次级父路径 */
    private static class ModuleLeafRenderer extends javax.swing.DefaultListCellRenderer {
        @Override
        public java.awt.Component getListCellRendererComponent(javax.swing.JList<?> list,
                Object value, int index, boolean isSelected, boolean cellHasFocus) {
            super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
            if (value instanceof ModuleLeafEntry e) {
                String parent = e.parentPath == null || e.parentPath.isEmpty() ? "" : "    [" + e.parentPath + "]";
                String html = "<html><b>" + escape(e.displayName) + "</b>"
                        + "<span style='color:gray;'>" + escape(parent) + "</span></html>";
                setText(html);
            }
            return this;
        }
        private static String escape(String s) {
            return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        }
    }

    /**
     * 扫描模块下所有可部署文件（用于 INCREMENTAL 增量更新模式）
     *
     * <p>递归扫描 src/main/ 目录下所有可部署文件类型，
     * 返回与 Git 变更检测相同格式的路径列表（无状态前缀）。</p>
     *
     * @param modulePath 模块根目录绝对路径
     * @return 文件相对路径列表
     */
    public static List<String> scanAllDeployableFiles(String modulePath) {
        List<String> result = new ArrayList<>();
        File srcMain = new File(modulePath, "src/main");
        if (!srcMain.exists()) return result;

        scanFilesRecursive(srcMain, modulePath, result);
        result.sort(String::compareToIgnoreCase);
        return result;
    }

    /** 递归扫描所有文件（不按扩展名过滤） */
    private static void scanFilesRecursive(File dir, String moduleRoot, List<String> result) {
        File[] children = dir.listFiles();
        if (children == null) return;
        for (File child : children) {
            if (child.isDirectory()) {
                scanFilesRecursive(child, moduleRoot, result);
            } else if (child.isFile()) {
                String rel = child.getAbsolutePath().substring(moduleRoot.length());
                // 源头规范化：Windows 上 getAbsolutePath 返回 '\'，统一成 '/'，
                // 与 GitChangeDetector 输出格式一致，下游文件树才能按层级拆解
                rel = rel.replace('\\', '/');
                if (rel.startsWith("/")) rel = rel.substring(1);
                // 使用与 GitChangeDetector 相同的格式：状态 + 两个空格 + 路径
                result.add("F  " + rel);
            }
        }
    }

    // ==================== 内部类和辅助方法 ====================

    /**
     * 文件条目，保存原始路径、状态、文件名
     */
    static class FileEntry {
        final String rawPath;
        final String status;
        final String fileName;
        /** 解析后的完整相对路径（不含状态前缀） */
        final String fullPath;

        FileEntry(String rawPath, String status, String fileName) {
            this.rawPath = rawPath;
            this.status = status;
            this.fileName = fileName;
            this.fullPath = fileName; // 兼容旧构造
        }

        FileEntry(String rawPath, String status, String fileName, String fullPath) {
            this.rawPath = rawPath;
            this.status = status;
            this.fileName = fileName;
            this.fullPath = fullPath;
        }

        @Override
        public String toString() {
            return (status.isEmpty() || "F".equals(status) ? "" : "[" + status + "] ") + fileName;
        }

        boolean isGitChanged() {
            return !status.isEmpty() && !"F".equals(status);
        }
    }

    /**
     * 树节点渲染器
     */
    static class FileTreeCellRenderer extends CheckboxTree.CheckboxTreeCellRenderer {
        @Override
        public void customizeRenderer(JTree tree, Object value, boolean selected,
                                       boolean expanded, boolean leaf, int row, boolean hasFocus) {
            if (value instanceof CheckedTreeNode node) {
                Object userObj = node.getUserObject();
                if (userObj instanceof FileEntry fe) {
                    // 文件节点：[M] FileName.java（F 状态为全量文件，不显示标签）
                    if (!fe.status.isEmpty() && !"F".equals(fe.status)) {
                        String color = switch (fe.status) {
                            case "A" -> "#6A8759";  // 绿色 - 新增
                            case "M" -> "#6897BB";  // 蓝色 - 修改
                            case "D" -> "#CC7832";  // 橙色 - 删除
                            case "F" -> "#A9B7C6";  // 灰色 - 全量文件（无变更状态）
                            default -> "#A9B7C6";
                        };
                        getTextRenderer().append("[" + fe.status + "] ",
                                new SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD,
                                        Color.decode(color)));
                    }
                    getTextRenderer().append(fe.fileName, SimpleTextAttributes.REGULAR_ATTRIBUTES);
                } else if (userObj instanceof String s) {
                    // 包节点
                    getTextRenderer().append(s,
                            new SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD,
                                    null));
                }
            }
        }
    }

}
