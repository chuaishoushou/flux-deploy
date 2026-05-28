package com.flux.deploy.plugin.toolwindow;

import com.flux.deploy.plugin.model.FtpTargetSelection;
import com.flux.deploy.plugin.service.FtpBrowseService;
import com.flux.deploy.plugin.service.FtpBrowseService.PackageInfo;
import com.flux.deploy.plugin.util.CredentialBridge;
import com.flux.deploy.util.CredentialCache;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.util.ui.JBUI;
import com.intellij.openapi.project.Project;
import com.intellij.ui.CheckboxTree;
import com.intellij.ui.CheckedTreeNode;
import com.intellij.ui.SearchTextField;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBPanel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.util.ui.AsyncProcessIcon;

import javax.swing.*;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import java.awt.*;
import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.List;

/**
 * 目标 Section 面板：FTP 连接 + 项目/系统选择 + 目标包树形多选
 *
 * <p>JAR 源产物：同名 JAR 锁定必选 + WAR 包可选勾选（嵌入更新）</p>
 * <p>WAR 源产物：同名 WAR 锁定必选</p>
 *
 * <p>支持 FTP 自动连接（复用 CLI 凭据缓存）、项目/系统级联选择、
 * 目标包树形多选（锁定包不可取消）、全选 WAR 等功能。</p>
 *
 * @author xumanyi
 * @date 2026-03-27
 */
public class TargetSectionPanel extends JBPanel<TargetSectionPanel> {

    private final Project project;
    private final JBLabel connectionStatus;
    private PickerComboBox projectCombo;
    private String selectedProject;
    private final JComboBox<String> systemCombo;
    private final JButton connectButton;
    private final JButton switchAccountButton;
    private final JButton logoutButton;
    private final JButton refreshButton;

    /**
     * FTP 状态条：[连接状态标签][刷新][⋯ 更多][连接(仅未连接时)]。
     *
     * <p>从 topPanel 第 0 行抽出，由 TargetContainerPanel 拿走挂到自绘 toggle bar 的
     * 右侧空位，与「FTP 模式 / 本地模式」按钮同行渲染；切到本地模式时整条 setVisible(false)。</p>
     */
    private JPanel ftpStatusBar;

    /**
     * 顶部表单区（FTP/项目/系统/子目录 行）的独立 GridBag 容器。
     *
     * <p>把这部分跟下面的"目标包树"分开放在两个 panel 里，是为了让它们的布局不再共享列宽：
     * 树里出现长文件名时，preferredWidth 只影响下半部分，不会推挤上面的下拉框列宽。
     * 所有 +/- 等动态行的 add/remove 都走 {@code topPanel}，外层 TargetSectionPanel
     * 仅负责 {@code NORTH=topPanel, CENTER=packagePanel} 的上下拼装。</p>
     */
    private JPanel topPanel;

    // ========== 子目录层级筛选（针对系统下含多层目录的项目，按需收窄扫描范围）==========
    /** "+" 按钮：在系统下拉右边，点击新增一层子目录筛选 */
    private JButton addLevelButton;
    /** "🔍" 按钮：在项目下拉右边 col 2，点击 toggle 显示/隐藏 packageSearchField（默认隐藏） */
    private JButton searchToggleButton;
    /** 当前已添加的子目录层级（按从浅到深的顺序） */
    private final List<SubdirLevel> extraLevels = new ArrayList<>();

    // 目标包树形勾选
    private CheckboxTree packageTree;
    private CheckedTreeNode packageTreeRoot;
    private JBLabel selectionSummary;
    /** 包数量统计标签：显示当前系统下"共 X 个 / 已勾 Y 个"，与 selectionSummary 同列南侧 */
    private JBLabel packageCountLabel;
    /** 目标包区域容器（树 + 全选按钮），无数据时隐藏 */
    private JPanel packagePanel;
    private JBScrollPane treeScroll;

    /** 目标包树当前鼠标悬浮行（-1 为无） */
    private int packageTreeHoverRow = -1;

    /**
     * 目标包过滤搜索框（IDEA 原生 SearchTextField，自带 🔍 / × 清空）。
     * 仅过滤视图，不影响选中态——已勾选但被过滤隐藏的包仍会进入 getSelectedPackages()。
     */
    private SearchTextField packageSearchField;

    /**
     * 已勾选包的真理之源（独立于树视图）。
     * key = subDirectory + "/" + packageName。
     * 视图重建（包括过滤刷新）后从这里恢复勾选；toggle 反向同步进来。
     */
    private final Set<String> userCheckedKeys = new LinkedHashSet<>();

    /**
     * 全量包数据按 key 索引（含 locked 标记）。
     * 与 userCheckedKeys 共同支撑"选中态与视图分离"，方便过滤期间仍能取到完整数据。
     */
    private final Map<String, PackageNodeData> packageDataByKey = new LinkedHashMap<>();

    /** 重建视图期间抑制 onNodeStateChanged 反向同步（避免清空再写回的抖动） */
    private boolean rebuildingPackageTree;
    /** 重建视图期间抑制搜索框 document 事件（程序化 setText("") 时不触发刷新） */
    private boolean suppressPackageSearchEvents;

    private volatile FtpBrowseService browseService;

    /** FTP 操作锁，防止并发操作同一连接 */
    private final Object ftpLock = new Object();

    // ========== 加载中状态指示（顶部状态条） ==========
    /** 加载中状态条（默认隐藏，有 FTP 异步任务进行中时显示） */
    private JPanel loadingBar;
    /** IDEA 自带异步旋转图标 */
    private AsyncProcessIcon loadingIcon;
    /** 加载描述文案（如"加载系统列表..."） */
    private JBLabel loadingLabel;
    /**
     * 并发加载计数器：允许多个 FTP 任务同时进行，所有任务都结束才隐藏状态条。
     * 避免"先回来的任务把仍在进行的任务的加载条关掉"。仅在 EDT 访问。
     */
    private int loadingCounter = 0;

    // FTP 凭据
    private String connectedHost;
    private int connectedPort;
    private String connectedUsername;
    private String connectedPassword;

    // 当前源产物信息（由外部设置，影响勾选行为）
    private String sourceArtifactName;  // 如 scev6-utils-tms-10.0.0-SNAPSHOT.jar
    private String sourceArtifactType;  // JAR or WAR

    // 包信息缓存
    private List<PackageInfo> currentPackages = List.of();

    /** 项目完整列表（搜索过滤用） */
    private List<String> allProjects = List.of();

    // projectSearchField 已移到弹窗内部（showProjectSearchPopup）

    /** 刷新中标志：抑制 combo ActionListener 避免并发 FTP 操作 */
    private boolean refreshing;

    /**
     * 构造目标信息面板
     *
     * @param project 当前 IDEA 项目
     * @author xumanyi
     * @date 2026-03-27
     */
    public TargetSectionPanel(Project project) {
        // 外层用 BorderLayout：NORTH=表单区（topPanel，独立 GridBag）、CENTER=目标包树。
        // 上下两块物理上分开，下半部分树的 preferredWidth 不会再影响上半部分的列宽计算。
        super(new BorderLayout());
        this.project = project;

        this.connectionStatus = new JBLabel("未登录");
        this.connectionStatus.setForeground(Color.RED);
        this.connectButton = new JButton("连接");
        connectButton.setToolTipText("连接到 FTP 服务器");
        this.switchAccountButton = new JButton("账号 ▾");
        switchAccountButton.setToolTipText("管理已保存账号");
        switchAccountButton.setMargin(new Insets(2, 8, 2, 8));
        this.logoutButton = new JButton("注销");
        logoutButton.setToolTipText("断开当前连接");
        logoutButton.setVisible(false);
        // 纯图标按钮：无边框、无填充背景、无边距、不可聚焦
        this.refreshButton = new JButton(com.intellij.icons.AllIcons.Actions.Refresh);
        refreshButton.setToolTipText("重连 FTP 并刷新列表");
        refreshButton.setMargin(JBUI.emptyInsets());
        refreshButton.setBorderPainted(false);
        refreshButton.setContentAreaFilled(false);
        refreshButton.setFocusable(false);
        refreshButton.setFocusPainted(false);
        refreshButton.setVisible(false);
        this.systemCombo = new JComboBox<>();
        // 长系统名（如 "深圳-中外运 成都宝洁WMS V6.21 P6共享jar-升级"）不应撑大
        // combo 自身的 preferred / minimum 宽度——否则右列 preferredSize 会传到
        // OnePixelSplitter 影响主分割条比例，并把项目/系统下拉框挤窄。
        // 用极短原型参与尺寸计算，实际渲染宽度由 GridBag (weightx=1.0 + fill=HORIZONTAL) 决定。
        systemCombo.setPrototypeDisplayValue("XXXXXXXXXX");
        systemCombo.setToolTipText("选择目标系统");

        this.packageTreeRoot = new CheckedTreeNode("目标包");
        // CheckPolicy: 仅启用父→子传播（点文件夹联动所有子节点）。
        // 不启用子→父反向同步，避免初始化时单个子节点被自动勾选导致父节点显示为"部分选中"。
        com.intellij.ui.CheckboxTreeBase.CheckPolicy propagatePolicy =
                new com.intellij.ui.CheckboxTreeBase.CheckPolicy(true, true, false, false);
        this.packageTree = new CheckboxTree(new PackageTreeRenderer(), packageTreeRoot, propagatePolicy) {
            @Override
            protected void onNodeStateChanged(CheckedTreeNode node) {
                super.onNodeStateChanged(node);
                if (!rebuildingPackageTree) {
                    // 子节点勾选状态变化后，自底向上同步父级目录的勾选状态：
                    // 仅当所有叶子都被勾选时目录才勾选，否则取消勾选（避免父节点遗留"部分选中"灰条）。
                    // 用 rebuildingPackageTree 防 propagateDirChecked 内的 setChecked 触发递归回调。
                    rebuildingPackageTree = true;
                    try {
                        propagateDirChecked(packageTreeRoot);
                    } finally {
                        rebuildingPackageTree = false;
                    }
                    packageTree.repaint();
                    syncVisibleCheckStateToKeys();
                }
                updateSelectionSummary();
            }

            @Override
            protected void paintComponent(Graphics g) {
                // 先铺 hover 行背景条，再让 super 画树内容。
                // PackageTreeRenderer 非选中行不自绘背景，颜色能正常透出；
                // 选中行 super 会覆盖我们的 hover 条，自然让位给选中色。
                if (packageTreeHoverRow >= 0 && packageTreeHoverRow < getRowCount()
                        && !isRowSelected(packageTreeHoverRow)) {
                    Rectangle b = getRowBounds(packageTreeHoverRow);
                    if (b != null) {
                        g.setColor(SourceSectionPanel.hoverBackgroundColor());
                        g.fillRect(0, b.y, getWidth(), b.height);
                    }
                }
                super.paintComponent(g);
            }

            /**
             * 把可打印字符键事件转发到面板的搜索框，并屏蔽 CheckboxTree / JTree 自带的
             * "按键定位下一行"与 TreeSpeedSearch，让搜索入口唯一。
             *
             * <p>策略与左侧源工程文件树一致：</p>
             * <ul>
             *   <li>带 Ctrl / Alt / Cmd 修饰键 → 透传 super（允许导航与平台快捷键）</li>
             *   <li>控制字符（方向键 / 回车 / Tab / Esc / Backspace 等）→ 透传 super</li>
             *   <li>其余可打印字符 → 自动展开搜索框、追加该字符、聚焦输入；不调用 super，避免触发
             *       JTree 默认的 "type-to-next-match" 与平台 SpeedSearch</li>
             * </ul>
             */
            @Override
            protected void processKeyEvent(java.awt.event.KeyEvent e) {
                if (e.getID() == java.awt.event.KeyEvent.KEY_TYPED
                        && !e.isControlDown() && !e.isAltDown() && !e.isMetaDown()
                        && !Character.isISOControl(e.getKeyChar())) {
                    char c = e.getKeyChar();
                    if (!packageSearchField.isVisible()) {
                        packageSearchField.setVisible(true);
                        TargetSectionPanel.this.revalidate();
                        TargetSectionPanel.this.repaint();
                    }
                    packageSearchField.setText(packageSearchField.getText() + c);
                    TargetSectionPanel.this.focusSearchField();
                    e.consume();
                    return;
                }
                super.processKeyEvent(e);
            }
        };
        packageTree.setRootVisible(false);
        packageTree.setShowsRootHandles(true);
        packageTree.addMouseMotionListener(new java.awt.event.MouseMotionAdapter() {
            @Override
            public void mouseMoved(java.awt.event.MouseEvent e) {
                int row = packageTree.getRowForLocation(e.getX(), e.getY());
                if (row != packageTreeHoverRow) {
                    packageTreeHoverRow = row;
                    packageTree.repaint();
                }
            }
        });
        packageTree.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseExited(java.awt.event.MouseEvent e) {
                if (packageTreeHoverRow != -1) {
                    packageTreeHoverRow = -1;
                    packageTree.repaint();
                }
            }
        });

        this.selectionSummary = new JBLabel("请选择项目和系统");
        this.packageCountLabel = new JBLabel(" ");

        // SearchTextField 与左侧源工程文件搜索保持一致：默认隐藏，点 🔍 toggle 展开，失焦清空时自动收起
        this.packageSearchField = new SearchTextField(false);
        packageSearchField.getTextEditor().getEmptyText().setText("搜索目标包名或子目录");
        packageSearchField.getTextEditor().setToolTipText("过滤目标包，支持空格分隔关键字或拼音");
        packageSearchField.setVisible(false);
        // removeUpdate 中识别"一次性多字符删除→空文本"为 ×（或 Cmd+A+Delete）一键清空，
        // 这种"明确结束搜索"的动作直接收起搜索框；单字符 backspace 不触发收起，留给用户继续输入。
        packageSearchField.addDocumentListener(new javax.swing.event.DocumentListener() {
            @Override public void insertUpdate(javax.swing.event.DocumentEvent e) { refresh(); }
            @Override public void removeUpdate(javax.swing.event.DocumentEvent e) {
                if (!suppressPackageSearchEvents && e.getLength() > 1
                        && packageSearchField.getText().isEmpty()) {
                    SwingUtilities.invokeLater(TargetSectionPanel.this::hideSearchField);
                }
                refresh();
            }
            @Override public void changedUpdate(javax.swing.event.DocumentEvent e) { refresh(); }
            private void refresh() {
                if (suppressPackageSearchEvents) return;
                // 与源工程搜索一致：下一帧重建，避免 LAF 吞 repaint
                SwingUtilities.invokeLater(() -> {
                    if (suppressPackageSearchEvents) return;
                    buildPackageTreeView();
                });
            }
        });
        // 失去焦点 + 文本为空 → 自动收起搜索框（与点 🔍 toggle 的语义对称）。
        // 延后到事件分发完成后再判断，避免和 IDE 焦点管理器的中间态打架。
        packageSearchField.getTextEditor().addFocusListener(new java.awt.event.FocusAdapter() {
            @Override
            public void focusLost(java.awt.event.FocusEvent e) {
                SwingUtilities.invokeLater(() -> {
                    if (packageSearchField.isVisible() && packageSearchField.getText().isEmpty()) {
                        hideSearchField();
                    }
                });
            }
        });
        // Esc：有内容→清空；已空→收起搜索框并把焦点交回包树
        packageSearchField.getTextEditor().registerKeyboardAction(
                e -> {
                    if (!packageSearchField.getText().isEmpty()) {
                        packageSearchField.setText("");
                    } else {
                        hideSearchField();
                        packageTree.requestFocusInWindow();
                    }
                },
                KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_ESCAPE, 0),
                JComponent.WHEN_FOCUSED);

        this.ftpStatusBar = buildFtpStatusBar();

        initUI();
        initListeners();
        tryAutoConnect();
    }

    /**
     * 构造 FTP 状态条：连接状态文字 + 刷新 + 更多操作 + 连接按钮。
     *
     * <p>外部由 TargetContainerPanel 挂到自绘 toggle bar 的右侧（BorderLayout.CENTER），
     * 与「FTP 模式 / 本地模式」toggle 按钮同行渲染；切到本地模式时容器整条 setVisible(false)。</p>
     *
     * @return FTP 状态条面板
     * @author xumanyi
     * @date 2026-05-16
     */
    private JPanel buildFtpStatusBar() {
        // 整条按 BorderLayout 排：状态文字在 CENTER（按需占满剩余宽度），
        // 右侧固定挂 [刷新][⋯ 更多][连接(仅未连接时)] 三枚等高的紧凑按钮。
        JPanel bar = new JPanel(new BorderLayout(6, 0));
        bar.setOpaque(false);
        // 与 tab 文字基本对齐：左 8px 留白；右 0px 让 [刷新][⋯ 更多] 贴 targetContent
        // 右内边缘，加上外层 targetContent 的 8px right padding，trailing icon 中心
        // 距 panel 右边缘 ≈ 16px——刚好跟 IDE 右侧 stripe icon 中心线（stripe 宽 ~28,
        // icon 16x16 居中）对齐。竖向 1px 间距让 trailing 不顶满 tab strip。
        bar.setBorder(JBUI.Borders.empty(1, 8, 1, 0));

        connectionStatus.putClientProperty("html.disable", Boolean.TRUE);
        bar.add(connectionStatus, BorderLayout.CENTER);

        // 更多操作按钮（切换账号 / 注销 收到这里；刷新已独立为行内图标按钮）
        // 纯图标按钮：无边框、无填充背景、无边距、不可聚焦
        JButton moreButton = new JButton(com.intellij.icons.AllIcons.Actions.More);
        moreButton.setToolTipText("更多操作");
        moreButton.setMargin(JBUI.emptyInsets());
        moreButton.setBorderPainted(false);
        moreButton.setContentAreaFilled(false);
        moreButton.setFocusable(false);
        moreButton.setFocusPainted(false);
        int moreBtnSize = connectionStatus.getPreferredSize().height + 4;
        moreButton.setPreferredSize(new Dimension(moreBtnSize, moreBtnSize));
        moreButton.addActionListener(e -> showFtpMoreMenu(moreButton));

        // 刷新按钮：尺寸与 moreButton 对齐，放在 ⋯ 左侧
        refreshButton.setPreferredSize(new Dimension(moreBtnSize, moreBtnSize));

        JPanel rightBox = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        rightBox.setOpaque(false);
        rightBox.add(refreshButton);
        rightBox.add(moreButton);
        // connectButton 仅在未连接时可见，位于 ⋯ 右侧以便快速建立连接
        rightBox.add(connectButton);
        bar.add(rightBox, BorderLayout.EAST);

        return bar;
    }

    /**
     * 暴露 FTP 状态条给 TargetContainerPanel 挂到自绘 toggle bar 上。
     *
     * @return FTP 状态条面板，永远非 null
     * @author xumanyi
     * @date 2026-05-16
     */
    public JPanel getFtpStatusBar() {
        return ftpStatusBar;
    }

    /** 初始化 UI 布局：项目/系统下拉、目标包树和选择摘要 */
    private void initUI() {
        // 上半部表单区，独立 GridBag。三列：col 0=label / col 1=combo / col 2=+- 按钮。
        // 关键：上下两个 panel 物理隔离，下半部分树的 preferredWidth 不再影响这里的列宽。
        topPanel = new JPanel(new GridBagLayout());

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(3, 0, 3, 8);
        gbc.anchor = GridBagConstraints.EAST;

        // FTP 状态条已抽出到 buildFtpStatusBar()，由 TargetContainerPanel 挂到
        // JTabbedPane 的 trailingComponent 槽位，与「FTP 模式 / 本地模式」tab 同行。
        // 这里不再在 topPanel 第 0 行渲染连接状态；项目/系统行 gridy 保持 2/3 不变。

        // 项目选择：用 PickerComboBox（视觉=JComboBox / 点击=自定义弹窗）替换原 JButton，
        // 让控件边框、暗色背景、高度、下拉箭头与同面板的"系统"以及左侧"工程"完全一致；
        // 点击下拉时仍弹出项目搜索面板。
        gbc.gridx = 0; gbc.gridy = 2;
        gbc.anchor = GridBagConstraints.EAST;
        gbc.fill = GridBagConstraints.NONE; gbc.weightx = 0;
        gbc.insets = new Insets(3, 0, 3, 8);
        topPanel.add(PanelChromes.rightLabel("项目"), gbc);
        gbc.gridx = 1;
        gbc.anchor = GridBagConstraints.WEST;
        gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0;
        gbc.insets = new Insets(3, 0, 3, 0);
        projectCombo = new PickerComboBox("请选择项目", this::showProjectSearchPopup);
        // 同 systemCombo：长项目名不应撑大首选/最小宽度（防御）。
        // PickerComboBox 模型只有 1 个元素（当前显示文本），prototype 优先于模型项参与尺寸计算。
        projectCombo.setPrototypeDisplayValue("XXXXXXXXXX");
        projectCombo.setToolTipText("选择客户项目");
        topPanel.add(projectCombo, gbc);

        // 项目行 col 2：🔍 toggle 显示/隐藏目标包搜索框（默认隐藏，节省纵向空间）。
        // 单按钮与下方动态子目录行的 ➖ 等宽对齐。
        searchToggleButton = createCompactActionButton(
                com.intellij.icons.AllIcons.Actions.Find,
                "展开或收起搜索框",
                e -> toggleSearchField());
        gbc.gridx = 2; gbc.fill = GridBagConstraints.NONE; gbc.weightx = 0;
        topPanel.add(searchToggleButton, gbc);

        // 系统下拉
        gbc.gridx = 0; gbc.gridy = 3;
        gbc.anchor = GridBagConstraints.EAST;
        gbc.fill = GridBagConstraints.NONE; gbc.weightx = 0;
        gbc.insets = new Insets(3, 0, 3, 8);
        topPanel.add(PanelChromes.rightLabel("系统"), gbc);
        gbc.gridx = 1;
        gbc.anchor = GridBagConstraints.WEST;
        gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0;
        gbc.insets = new Insets(3, 0, 3, 0);
        topPanel.add(systemCombo, gbc);

        // 系统行 col 2：➕ 新增子目录筛选层级。与下方动态子目录行的 ➖ 同列同宽。
        addLevelButton = createCompactActionButton(
                com.intellij.icons.AllIcons.General.Add, "新增子目录筛选",
                e -> addSubdirLevel());
        gbc.gridx = 2; gbc.fill = GridBagConstraints.NONE; gbc.weightx = 0;
        topPanel.add(addLevelButton, gbc);

        // 加载中状态条（一行：旋转图标 + 描述文案），默认隐藏，FTP 请求期间显示
        loadingBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        loadingIcon = new AsyncProcessIcon("TargetSectionPanelLoading");
        loadingLabel = new JBLabel("加载中...");
        loadingLabel.setForeground(Color.GRAY);
        loadingBar.add(loadingIcon);
        loadingBar.add(loadingLabel);
        loadingBar.setVisible(false);
        // gridy 从 100 起；gridy 4..99 留给动态新增的子目录筛选行（addSubdirLevel）
        gbc.gridx = 0; gbc.gridy = 100; gbc.gridwidth = 3;
        gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0; gbc.weighty = 0;
        topPanel.add(loadingBar, gbc);
        gbc.gridwidth = 1;

        // 目标包区域（搜索框 + 树 + 摘要，打包为一个可隐藏的容器）
        // 物理上独立于 topPanel，所以树里再长的文件名也不会反推 topPanel 的列宽。
        packagePanel = new JPanel(new BorderLayout(0, 4));

        treeScroll = new JBScrollPane(packageTree);
        // 与左侧源工程树保持一致：去掉 JBScrollPane 默认细边与 viewport 边，与外层背景平齐
        treeScroll.setBorder(JBUI.Borders.empty());
        treeScroll.setViewportBorder(JBUI.Borders.empty());
        packagePanel.add(packageSearchField, BorderLayout.NORTH);
        packagePanel.add(treeScroll, BorderLayout.CENTER);

        // 南侧统计行：左边 packageCountLabel（共/已勾），右边 selectionSummary（已选明细）
        selectionSummary.setForeground(Color.GRAY);
        selectionSummary.setBorder(JBUI.Borders.emptyTop(2));
        packageCountLabel.setForeground(Color.GRAY);
        packageCountLabel.setBorder(JBUI.Borders.emptyTop(2));
        JPanel summaryRow = new JPanel(new BorderLayout(8, 0));
        summaryRow.setOpaque(false);
        summaryRow.add(packageCountLabel, BorderLayout.WEST);
        summaryRow.add(selectionSummary, BorderLayout.EAST);
        packagePanel.add(summaryRow, BorderLayout.SOUTH);

        // 初始隐藏
        packagePanel.setVisible(false);

        // 外层 BorderLayout：上面表单区、下面树区
        // 不需要额外 spacer——BorderLayout 的 NORTH 自然只取 preferredHeight，
        // CENTER 在 packagePanel 隐藏时为空白区域，等价于"自动顶部对齐"。
        add(topPanel, BorderLayout.NORTH);
        add(packagePanel, BorderLayout.CENTER);
    }

    /**
     * 创建一个紧凑、扁平、与下拉等高的图标按钮（用于 +/- 等次要动作）
     *
     * <p>宽度固定 {@value #COMPACT_ACTION_BTN_WIDTH}px，高度跟随 systemCombo 自适应当前 L&amp;F；
     * 无边框、无填充、无聚焦边、不可聚焦，外观尽可能与现有 ⟳/⋯ 按钮一致。</p>
     *
     * @param icon     按钮图标
     * @param tooltip  悬浮提示（保持极简，单行）
     * @param listener 点击回调
     * @return 已配置好的按钮
     * @author xumanyi
     * @date 2026-04-30
     */
    private JButton createCompactActionButton(javax.swing.Icon icon, String tooltip,
                                               java.awt.event.ActionListener listener) {
        JButton btn = new JButton(icon);
        btn.setMargin(JBUI.emptyInsets());
        btn.setBorderPainted(false);
        btn.setContentAreaFilled(false);
        btn.setFocusable(false);
        btn.setFocusPainted(false);
        int height = systemCombo.getPreferredSize().height;
        btn.setPreferredSize(new Dimension(COMPACT_ACTION_BTN_WIDTH, height));
        btn.setToolTipText(tooltip);
        btn.addActionListener(listener);
        return btn;
    }

    /** 子目录层级 +/- 按钮的紧凑宽度（icon 16px + 两侧各 2px 内边距） */
    private static final int COMPACT_ACTION_BTN_WIDTH = 20;

    /**
     * 显示顶部加载中状态条。可并发调用：所有调用方都调完 hideLoading 后才真正隐藏。
     * 线程安全：非 EDT 调用自动路由到 EDT。
     *
     * @param text 加载描述，null / 空串时默认 "加载中..."
     * @author xumanyi
     * @date 2026-04-21
     */
    private void showLoading(String text) {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(() -> showLoading(text));
            return;
        }
        loadingCounter++;
        loadingLabel.setText(text == null || text.isEmpty() ? "加载中..." : text);
        loadingBar.setVisible(true);
        loadingIcon.resume();
        loadingBar.revalidate();
    }

    /**
     * 隐藏加载中状态条。仅当 {@link #loadingCounter} 归零时真正隐藏，
     * 以避免并发 FTP 任务中"先回来的把仍在进行的任务的加载条关掉"。
     *
     * @author xumanyi
     * @date 2026-04-21
     */
    private void hideLoading() {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(this::hideLoading);
            return;
        }
        loadingCounter = Math.max(0, loadingCounter - 1);
        if (loadingCounter == 0) {
            loadingIcon.suspend();
            loadingBar.setVisible(false);
            loadingBar.revalidate();
        }
    }

    /**
     * 切换目标包搜索框显示状态。
     *
     * <p>展开时：自动聚焦输入并全选已有文本；收起时：清空筛选并把焦点交回包树。
     * 与左侧源工程文件搜索的 toggle 行为完全一致。</p>
     *
     * @author xumanyi
     * @date 2026-05-16
     */
    private void toggleSearchField() {
        if (packageSearchField.isVisible()) {
            hideSearchField();
            packageTree.requestFocusInWindow();
        } else {
            packageSearchField.setVisible(true);
            revalidate();
            repaint();
            focusSearchField();
        }
    }

    /**
     * 收起搜索框并清空筛选；调用方负责把焦点交回合适位置。
     *
     * @author xumanyi
     * @date 2026-05-16
     */
    private void hideSearchField() {
        if (!packageSearchField.getText().isEmpty()) {
            packageSearchField.setText("");
        }
        packageSearchField.setVisible(false);
        revalidate();
        repaint();
    }

    /**
     * 把焦点放到搜索框输入区并全选已有文本。
     *
     * <p>由 toggle 按钮与树键盘转发共用：保证两条路径都得到一致的"展开后立即可输入"体验。</p>
     *
     * @author xumanyi
     * @date 2026-05-16
     */
    private void focusSearchField() {
        JTextField editor = packageSearchField.getTextEditor();
        editor.requestFocusInWindow();
        editor.selectAll();
    }

    /** 初始化事件监听：连接、注销、项目/系统级联选择、刷新、全选 WAR
     *  （projectCombo 的点击由 PickerComboBox 自身回调到 showProjectSearchPopup） */
    private void initListeners() {
        connectButton.addActionListener(e -> connect());

        // 注销：仅断开当前连接，不删除已保存凭据（删除由"账号 ▾"列表内的 × 按钮负责）
        logoutButton.addActionListener(e -> doLogout(false));

        // 账号切换按钮：弹出已保存账号列表（含切换/删除/添加）
        // 注：switchAccountButton 当前已不再加入界面，此 listener 为兼容保留，实际点击入口走 "⋯ 更多" 菜单
        switchAccountButton.addActionListener(e -> showAccountSwitcherPopup(switchAccountButton));

        systemCombo.addActionListener(e -> {
            if (refreshing) return;
            String proj = selectedProject;
            String sys = (String) systemCombo.getSelectedItem();
            // 系统切换时，旧的子目录层级失效（路径前缀已变），全部清空后再加载
            clearExtraLevels();
            if (proj != null && sys != null && browseService != null) {
                loadTargetPackages(proj, sys);
            }
            fireContextChange();
        });

        // 刷新按钮：重连 FTP + 刷新项目/系统/目标包列表
        refreshButton.addActionListener(e -> {
            if (connectedHost == null) return;

            String prevProj = selectedProject;
            String prevSys = (String) systemCombo.getSelectedItem();

            refreshButton.setEnabled(false);
            refreshing = true;

            // 刷新整体重置：旧的子目录层级随系统列表一同丢弃，避免引用过期路径
            clearExtraLevels();
            // 先清空目标包列表，显示刷新中
            currentPackages = List.of();
            rebuildPackageTree();
            selectionSummary.setText("正在刷新...");
            packageCountLabel.setText(" ");

            showLoading("刷新项目 / 系统 / 目标包...");
            ApplicationManager.getApplication().executeOnPooledThread(() -> {
                try {
                    synchronized (ftpLock) {
                    // 重新建立 FTP 连接（旧连接可能已超时）
                    reconnectFtp();

                    // 刷新项目列表
                    List<String> projects = browseService.listSubdirectories("/开发/");

                    // 如果之前选了项目，刷新系统列表
                    List<String> systems = null;
                    if (prevProj != null && projects.contains(prevProj)) {
                        systems = browseService.listSubdirectories(
                                "/开发/" + prevProj + "/");
                    }

                    // 如果之前选了系统，刷新目标包列表
                    List<PackageInfo> packages = null;
                    if (systems != null && prevSys != null && systems.contains(prevSys)) {
                        packages = browseService.scanPackagesStructured(
                                "/开发/" + prevProj + "/" + prevSys + "/");
                    }

                    // 所有 FTP 操作完成后，一次性更新 UI
                    final List<String> finalSystems = systems;
                    final List<PackageInfo> finalPackages = packages;
                    SwingUtilities.invokeLater(() -> {
                        allProjects = new ArrayList<>(projects);
                        if (prevProj != null && projects.contains(prevProj)) {
                            selectedProject = prevProj;
                            projectCombo.setText(prevProj);
                        } else {
                            selectedProject = null;
                            projectCombo.setText("请选择项目");
                        }

                        if (finalSystems != null) {
                            systemCombo.removeAllItems();
                            for (String s : finalSystems) systemCombo.addItem(s);
                            if (prevSys != null && finalSystems.contains(prevSys)) {
                                systemCombo.setSelectedItem(prevSys);
                            } else {
                                systemCombo.setSelectedIndex(-1);
                            }
                        }

                        if (finalPackages != null) {
                            currentPackages = finalPackages;
                            rebuildPackageTree();
                        }

                        refreshing = false;
                        refreshButton.setEnabled(true);
                        fireContextChange();
                    });
                    } // end synchronized(ftpLock)
                } catch (Exception ex) {
                    SwingUtilities.invokeLater(() -> {
                        refreshing = false;
                        setLoginFailed("刷新失败：" + ex.getMessage());
                        refreshButton.setEnabled(true);
                    });
                } finally {
                    SwingUtilities.invokeLater(this::hideLoading);
                }
            });
        });

    }

    /**
     * 设置源产物信息（影响目标包的锁定和过滤逻辑）
     *
     * <p>根据源产物类型（JAR/WAR），决定目标包树中哪些包锁定必选、哪些可选。</p>
     *
     * @param artifactFileName 源产物文件名（如 scev6-utils-tms-10.0.0-SNAPSHOT.jar）
     * @author xumanyi
     * @date 2026-03-27
     */
    public void setSourceArtifact(String artifactFileName) {
        this.sourceArtifactName = artifactFileName;
        if (artifactFileName != null) {
            this.sourceArtifactType = artifactFileName.toLowerCase().endsWith(".war") ? "WAR" : "JAR";
        } else {
            this.sourceArtifactType = null;
        }
        // 重新构建树（应用新的过滤和锁定规则）
        rebuildPackageTree();
    }

    /**
     * 包数据集变化（系统/项目/源产物切换、清空）后调用：重新生成 packageDataByKey
     * 与 userCheckedKeys 默认勾选（locked=true 且非备份的包），清空搜索关键字，并重建视图。
     *
     * <p>"包数据真正变了"是 reseed 的唯一触发条件；纯过滤刷新走 {@link #buildPackageTreeView()}。</p>
     */
    private void rebuildPackageTree() {
        seedDataAndKeysFromCurrentPackages();
        // 数据集换了就清空旧搜索词，避免新数据被旧条件意外过滤
        suppressPackageSearchEvents = true;
        try {
            if (!packageSearchField.getText().isEmpty()) {
                packageSearchField.setText("");
            }
        } finally {
            suppressPackageSearchEvents = false;
        }
        buildPackageTreeView();
    }

    /**
     * 从 currentPackages 重新生成 packageDataByKey 与 userCheckedKeys 默认勾选。
     *
     * <p>locked / visible / 备份默认不勾 的判定逻辑与原 rebuildPackageTree 完全一致；
     * locked=true 且非备份的包默认进入 userCheckedKeys，对应"刚切换目标系统时主目标自动勾选"的旧行为。</p>
     */
    private void seedDataAndKeysFromCurrentPackages() {
        packageDataByKey.clear();
        userCheckedKeys.clear();
        for (PackageInfo pkg : currentPackages) {
            boolean isMatch = sourceArtifactName != null
                    && isExactArtifactMatch(pkg.getPackageName(), sourceArtifactName);
            boolean locked = false;
            boolean checkedByDefault = false;
            boolean visible = true;
            if ("JAR".equals(sourceArtifactType)) {
                if ("JAR".equals(pkg.getType()) && isMatch) {
                    locked = true;
                    checkedByDefault = true;
                } else if (!"WAR".equals(pkg.getType())) {
                    visible = false;
                }
            } else if ("WAR".equals(sourceArtifactType)) {
                if ("WAR".equals(pkg.getType()) && isMatch) {
                    locked = true;
                    checkedByDefault = true;
                } else {
                    visible = false;
                }
            }
            // 备份目录里的包保留展示（避免"我的包去哪了"），但永远不默认勾选，
            // 防止一键部署时把备份遗留的旧包当成目标
            if (pkg.isFromBackup()) checkedByDefault = false;
            if (!visible) continue;
            String key = packageKey(pkg);
            packageDataByKey.put(key, new PackageNodeData(pkg, locked));
            if (checkedByDefault) userCheckedKeys.add(key);
        }
    }

    /**
     * 仅从当前状态（packageDataByKey + userCheckedKeys + 搜索关键字）重建树视图。
     * 不重新种子化，保留用户在搜索期间的勾选改动；过滤期间被隐藏的包仍计入 userCheckedKeys。
     */
    private void buildPackageTreeView() {
        packageTreeRoot = new CheckedTreeNode("目标包");

        if (packageDataByKey.isEmpty()) {
            DefaultTreeModel model = new DefaultTreeModel(packageTreeRoot);
            rebuildingPackageTree = true;
            try {
                packageTree.setModel(model);
                packageTree.setRootVisible(false);
            } finally {
                rebuildingPackageTree = false;
            }
            packagePanel.setVisible(false);
            updateSelectionSummary();
            revalidate();
            repaint();
            return;
        }

        // 自然排序：先 subDir，再 packageName（字母+数字混合按数字大小比较，
        // 避免 tm01srv1 / tm01srv10 / tm01srv2 这类纯字典序问题）
        List<PackageNodeData> sorted = new ArrayList<>(packageDataByKey.values());
        sorted.sort(
                Comparator.comparing((PackageNodeData d) -> d.info.getSubDirectory(),
                                TargetSectionPanel::naturalCompare)
                        .thenComparing(d -> d.info.getPackageName(),
                                TargetSectionPanel::naturalCompare));

        String keyword = packageSearchField.getText().trim();
        String[] terms = keyword.isEmpty() ? null : keyword.toLowerCase(Locale.ROOT).split("\\s+");

        // 按 "/" 分段建嵌套目录树：同一前缀只建一个节点，包挂在最深层目录下。
        // 这样 backup/20260502_xumanyi/shared-tms 不再是一行扁平串，而是
        // backup → 20260502_xumanyi → shared-tms 三层嵌套，backup 顶层节点全局只出现一次，
        // FTP 上有几十次备份也只是顶层 backup 节点下的子树，视觉上极简
        Map<String, CheckedTreeNode> dirByPath = new LinkedHashMap<>();
        for (PackageNodeData d : sorted) {
            // 主目标 (locked) 始终保留——它是部署的核心对象，被过滤掉视觉上太怪
            if (terms != null && !d.locked && !packageMatches(d, terms)) continue;
            CheckedTreeNode parent = resolveDirNode(d.info.getSubDirectory(), dirByPath);
            CheckedTreeNode pkgNode = new CheckedTreeNode(d);
            pkgNode.setChecked(userCheckedKeys.contains(packageKey(d.info)));
            parent.add(pkgNode);
        }

        // 自底向上聚合：仅当目录下所有叶子（包）都勾选时父级才勾选，
        // 避免渲染器画"部分选中"的灰横条
        propagateDirChecked(packageTreeRoot);

        DefaultTreeModel model = new DefaultTreeModel(packageTreeRoot);
        rebuildingPackageTree = true;
        try {
            packageTree.setModel(model);
            packageTree.setRootVisible(false);
            // 默认展开规则：
            // - 搜索激活（terms != null）：整棵子树全展开，让命中项不被折叠藏住
            // - 否则：非备份顶层目录展开 1 层（看到包名方便挑），
            //   备份顶层（backup/backups/bak/.backup）连同内部嵌套都保持折叠
            boolean hasSearch = terms != null;
            for (int i = 0; i < packageTreeRoot.getChildCount(); i++) {
                if (!(packageTreeRoot.getChildAt(i) instanceof CheckedTreeNode child)) continue;
                if (hasSearch) {
                    expandAllUnder(child);
                } else if (!isBackupTopName(child.getUserObject())) {
                    packageTree.expandPath(new TreePath(child.getPath()));
                }
            }
        } finally {
            rebuildingPackageTree = false;
        }

        packagePanel.setVisible(true);
        updateSelectionSummary();
        revalidate();
        repaint();
    }

    /**
     * 在 packageTreeRoot 下按 "/" 分段建（或复用）嵌套目录节点，返回最深层目录节点（包应挂在它下面）。
     *
     * <p>{@code "."} 视为"当前目录"特殊单段，建成单一顶层节点 {@code "(当前目录)"}；
     * 其他多段 subDir（如 {@code backup/20260502_xumanyi/shared-tms}）按斜杠切开后逐层
     * computeIfAbsent，保证 {@code backup} 这种顶层段全局只建一个节点。</p>
     *
     * @param subDir    包所在的 subDirectory 字符串（来自 PackageInfo.getSubDirectory()）
     * @param dirByPath 路径前缀 → 节点缓存，键是累积的 "a/b/c" 形式串
     * @return 包应直接挂载到的目录节点
     */
    private CheckedTreeNode resolveDirNode(String subDir, Map<String, CheckedTreeNode> dirByPath) {
        if (".".equals(subDir)) {
            return dirByPath.computeIfAbsent(".", k -> {
                CheckedTreeNode node = new CheckedTreeNode("(当前目录)");
                packageTreeRoot.add(node);
                return node;
            });
        }
        String[] segs = subDir.split("/");
        CheckedTreeNode parent = packageTreeRoot;
        StringBuilder accumulated = new StringBuilder();
        for (String seg : segs) {
            if (seg.isEmpty()) continue;
            if (accumulated.length() > 0) accumulated.append("/");
            accumulated.append(seg);
            String key = accumulated.toString();
            CheckedTreeNode existing = dirByPath.get(key);
            if (existing == null) {
                CheckedTreeNode created = new CheckedTreeNode(seg);
                parent.add(created);
                dirByPath.put(key, created);
                parent = created;
            } else {
                parent = existing;
            }
        }
        return parent;
    }

    /**
     * 自底向上递归设置目录节点的勾选状态：仅当所有后代叶子（包节点）都被勾选时父级才勾选。
     *
     * <p>CheckedTreeNode 默认 {@code isChecked()=true}，如果不在初始化时统一回填，新建的目录
     * 节点会以"勾选"状态出现（视觉上像默认全选），所以这里对所有非包节点都强制 setChecked。</p>
     *
     * @return 该子树下 {@code [叶子总数, 已勾叶子数]}，供上层聚合
     */
    private static int[] propagateDirChecked(CheckedTreeNode node) {
        if (node.getChildCount() == 0) {
            return new int[]{1, node.isChecked() ? 1 : 0};
        }
        int total = 0, checked = 0;
        for (int i = 0; i < node.getChildCount(); i++) {
            if (node.getChildAt(i) instanceof CheckedTreeNode child) {
                int[] r = propagateDirChecked(child);
                total += r[0];
                checked += r[1];
            }
        }
        if (!(node.getUserObject() instanceof PackageNodeData)) {
            node.setChecked(total > 0 && checked == total);
        }
        return new int[]{total, checked};
    }

    /** 顶层目录节点的显示名是否命中备份目录约定（backup/backups/bak/.backup）。 */
    private static boolean isBackupTopName(Object userObj) {
        return userObj instanceof String s
                && com.flux.deploy.ftp.FtpOperations.BACKUP_DIR_NAMES.contains(s);
    }

    /** 把指定子树整棵展开（搜索激活时用，避免命中项被折叠藏住）。 */
    private void expandAllUnder(CheckedTreeNode root) {
        packageTree.expandPath(new TreePath(root.getPath()));
        for (int i = 0; i < root.getChildCount(); i++) {
            if (root.getChildAt(i) instanceof CheckedTreeNode child) {
                expandAllUnder(child);
            }
        }
    }

    /** 包过滤匹配：包名 / 子目录 任意命中（子串或拼音），多关键字 AND */
    private boolean packageMatches(PackageNodeData d, String[] terms) {
        String haystack = (d.info.getSubDirectory() + " " + d.info.getPackageName())
                .toLowerCase(Locale.ROOT);
        String pinyinCandidate = d.info.getSubDirectory() + " " + d.info.getPackageName();
        for (String term : terms) {
            if (!haystack.contains(term)
                    && !com.flux.deploy.plugin.util.PinyinMatcher.matches(pinyinCandidate, term)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 唯一标识一个目标包（同名包在不同 subDir 下不会冲突）
     *
     * <p>用 {@link PackageInfo#getRelativePath()} 而非 {@code subDirectory + "/" + packageName}：
     * 收窄子目录扫描时 {@code subDirectory} 会从 {@code "shared-tms"} 变成 {@code "."}（相对 scan 根），
     * 但 relativePath 在 {@link #applySubdirPrefixToRelativePath} 修正后<b>始终相对系统根</b>，
     * 跨筛选切换稳定，不会让 userCheckedKeys / packageDataByKey 在切层后失配。</p>
     */
    private static String packageKey(PackageInfo pkg) {
        return pkg.getRelativePath();
    }

    /**
     * 把树中当前可见的 PackageNodeData 节点的勾选状态同步进 userCheckedKeys。
     * 被过滤隐藏的包不在树里 → 它们的 key 在 userCheckedKeys 中保持不动。
     */
    private void syncVisibleCheckStateToKeys() {
        if (packageTreeRoot == null) return;
        collectVisibleCheckState(packageTreeRoot);
    }

    private void collectVisibleCheckState(CheckedTreeNode node) {
        if (node.getUserObject() instanceof PackageNodeData data) {
            String key = packageKey(data.info);
            if (node.isChecked()) {
                userCheckedKeys.add(key);
            } else {
                userCheckedKeys.remove(key);
            }
            return;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            if (node.getChildAt(i) instanceof CheckedTreeNode child) {
                collectVisibleCheckState(child);
            }
        }
    }

    /**
     * 更新选择摘要 + 包数量统计（共 X 个 / 已勾 Y 个）。
     *
     * <p>总数 = {@code packageDataByKey.size()}（当前系统下、按源产物类型过滤后剩下的全量），
     * 已勾 = {@code userCheckedKeys.size()}（与树视图过滤无关，即被搜索隐藏但仍勾选的包也算）。</p>
     */
    private void updateSelectionSummary() {
        List<PackageNodeData> selected = getSelectedPackages();
        int direct = 0;
        int embed = 0;
        for (PackageNodeData d : selected) {
            if (d.locked) direct++;
            else embed++;
        }
        if (selected.isEmpty()) {
            selectionSummary.setText("未选择目标包");
        } else if (embed > 0) {
            selectionSummary.setText("已选：" + direct + " 个主目标 + " + embed + " 个 WAR 嵌入");
        } else {
            selectionSummary.setText("已选：" + direct + " 个主目标");
        }

        int total = packageDataByKey.size();
        if (total == 0) {
            packageCountLabel.setText(" ");
        } else {
            packageCountLabel.setText("共 " + total + " 个 / 已勾 " + selected.size() + " 个");
        }
    }

    /**
     * 获取选中的所有目标包数据
     */
    private List<PackageNodeData> getSelectedPackages() {
        // 读 userCheckedKeys 而非走树：过滤期间被搜索关键字隐藏的包仍属于"用户的选择"，
        // 应当参与部署，避免"搜了一下结果嵌入 WAR 没了"
        List<PackageNodeData> result = new ArrayList<>();
        for (String key : userCheckedKeys) {
            PackageNodeData d = packageDataByKey.get(key);
            if (d != null) result.add(d);
        }
        return result;
    }

    /**
     * 获取主目标列表（所有锁定的同名包）
     *
     * <p>当同一系统下的多个子目录存在同名 JAR（例如 shared-edp / shared-tms 各一份），
     * 返回所有被锁定的实例，供部署流程分别处理。</p>
     *
     * @return 主目标列表，可能为空
     * @author xumanyi
     * @date 2026-04-18
     */
    public List<FtpTargetSelection> getMainTargets() {
        String proj = selectedProject;
        String sys = (String) systemCombo.getSelectedItem();
        if (proj == null || sys == null) return List.of();

        List<FtpTargetSelection> result = new ArrayList<>();
        for (PackageNodeData d : getSelectedPackages()) {
            if (d.locked) {
                result.add(new FtpTargetSelection(proj, sys,
                        d.info.getPackageName(), d.info.getRelativePath()));
            }
        }
        return result;
    }

    /**
     * 获取第一个主目标（兼容旧代码）。调用方若需要支持多主目标请改用 {@link #getMainTargets()}。
     *
     * @return 第一个主目标，未选择时返回 {@code null}
     */
    public FtpTargetSelection getSelection() {
        List<FtpTargetSelection> targets = getMainTargets();
        return targets.isEmpty() ? null : targets.get(0);
    }

    /**
     * 获取 WAR 嵌入目标列表（非锁定的已选中 WAR）
     *
     * @return WAR 嵌入目标列表，无嵌入目标时返回空列表
     * @author xumanyi
     * @date 2026-03-27
     */
    public List<FtpTargetSelection> getEmbedTargets() {
        String proj = selectedProject;
        String sys = (String) systemCombo.getSelectedItem();
        if (proj == null || sys == null) return List.of();

        List<FtpTargetSelection> embeds = new ArrayList<>();
        for (PackageNodeData d : getSelectedPackages()) {
            if (!d.locked && "WAR".equals(d.info.getType())) {
                embeds.add(new FtpTargetSelection(proj, sys,
                        d.info.getPackageName(), d.info.getRelativePath()));
            }
        }
        return embeds;
    }

    /**
     * 根据产物文件名自动选择目标包
     *
     * <p>委托给 {@link #setSourceArtifact(String)} 重建树并应用锁定规则。</p>
     *
     * @param artifactFileName 产物文件名
     * @author xumanyi
     * @date 2026-03-27
     */
    public void autoSelectTarget(String artifactFileName) {
        setSourceArtifact(artifactFileName);
    }

    /**
     * 安全重建 FTP 连接（关闭旧连接 + 创建新连接）
     *
     * <p>必须在 synchronized(ftpLock) 块内调用，或在确保无并发的上下文中调用。</p>
     *
     * @throws IOException 新连接创建失败时抛出
     */
    private void reconnectFtp() throws IOException {
        if (browseService != null) {
            try { browseService.disconnect(); } catch (Exception ignored) {}
        }
        if (connectedHost != null) {
            browseService = new FtpBrowseService(
                    connectedHost, connectedPort, connectedUsername, connectedPassword);
        }
    }

    /**
     * 弹出项目搜索选择框：搜索输入框 + 实时过滤列表
     */
    /** 当前项目搜索弹窗 */
    private JDialog projectSearchDialog;
    /** 项目搜索弹窗外部点击监听器 */
    private java.awt.event.AWTEventListener projectOutsideListener;

    /** 强制关闭项目搜索弹窗 */
    private void forceCloseProjectPopup() {
        if (projectOutsideListener != null) {
            Toolkit.getDefaultToolkit().removeAWTEventListener(projectOutsideListener);
            projectOutsideListener = null;
        }
        if (projectSearchDialog != null) {
            projectSearchDialog.dispose();
            projectSearchDialog = null;
        }
    }

    private void showProjectSearchPopup() {
        // 先关闭旧弹窗
        forceCloseProjectPopup();
        if (allProjects.isEmpty()) return;

        Window owner = SwingUtilities.getWindowAncestor(projectCombo);
        JDialog dialog = new JDialog(owner);
        dialog.setUndecorated(true);
        // 不要 setAlwaysOnTop(true)：会让弹窗浮在其他应用之上，切到其他应用时弹窗仍可见。
        // 依赖 owner 的父子关系保证在 IDE 前台时弹窗位于 IDE 内容之上即可。
        dialog.setLayout(new BorderLayout(0, 2));
        dialog.getRootPane().setBorder(BorderFactory.createLineBorder(Color.GRAY));
        projectSearchDialog = dialog;

        // dispose 时自动清理
        dialog.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosed(java.awt.event.WindowEvent e) {
                if (projectSearchDialog == dialog) projectSearchDialog = null;
                if (projectOutsideListener != null) {
                    Toolkit.getDefaultToolkit().removeAWTEventListener(projectOutsideListener);
                    projectOutsideListener = null;
                }
            }
        });

        // 搜索框
        javax.swing.JTextField searchField = new javax.swing.JTextField();
        searchField.putClientProperty("JTextField.placeholderText", "搜索项目...");
        dialog.add(searchField, BorderLayout.NORTH);

        // 列表
        DefaultListModel<String> listModel = new DefaultListModel<>();
        for (String p : allProjects) listModel.addElement(p);
        JList<String> list = new JList<>(listModel);
        list.setSelectionMode(javax.swing.ListSelectionModel.SINGLE_SELECTION);

        // 鼠标悬浮行高亮：与其他下拉弹窗一致的视觉
        final int[] hoverIndex = { -1 };
        javax.swing.DefaultListCellRenderer baseRenderer = new javax.swing.DefaultListCellRenderer();
        list.setCellRenderer((ListCellRenderer<String>)
                (l, value, index, isSelected, cellHasFocus) -> {
            Component c = baseRenderer.getListCellRendererComponent(
                    l, value, index, isSelected, cellHasFocus);
            if (!isSelected && index == hoverIndex[0] && c instanceof JComponent jc) {
                jc.setBackground(SourceSectionPanel.hoverBackgroundColor());
                jc.setOpaque(true);
            }
            return c;
        });
        list.addMouseMotionListener(new java.awt.event.MouseMotionAdapter() {
            @Override
            public void mouseMoved(java.awt.event.MouseEvent e) {
                int idx = list.locationToIndex(e.getPoint());
                if (idx >= 0) {
                    Rectangle cell = list.getCellBounds(idx, idx);
                    if (cell == null || !cell.contains(e.getPoint())) idx = -1;
                }
                if (idx != hoverIndex[0]) {
                    hoverIndex[0] = idx;
                    list.repaint();
                }
            }
        });
        list.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseExited(java.awt.event.MouseEvent e) {
                if (hoverIndex[0] != -1) {
                    hoverIndex[0] = -1;
                    list.repaint();
                }
            }
        });

        JBScrollPane scrollPane = new JBScrollPane(list);
        scrollPane.setPreferredSize(new Dimension(
                Math.max(projectCombo.getWidth(), 200),
                Math.min(300, allProjects.size() * 24 + 10)));
        dialog.add(scrollPane, BorderLayout.CENTER);

        // 实时过滤
        searchField.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            @Override public void insertUpdate(javax.swing.event.DocumentEvent e) { filter(); }
            @Override public void removeUpdate(javax.swing.event.DocumentEvent e) { filter(); }
            @Override public void changedUpdate(javax.swing.event.DocumentEvent e) { filter(); }
            private void filter() {
                String kw = searchField.getText().trim();
                listModel.clear();
                for (String p : allProjects) {
                    if (com.flux.deploy.plugin.util.PinyinMatcher.matches(p, kw)) {
                        listModel.addElement(p);
                    }
                }
            }
        });

        // 选中并关闭
        Runnable selectAndClose = () -> {
            String sel = list.getSelectedValue();
            if (sel != null) {
                selectedProject = sel;
                projectCombo.setText(sel);
                forceCloseProjectPopup();
                if (browseService != null) loadSystems(sel);
                fireContextChange();
            }
        };

        list.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                // 首次弹窗时 JList 尚未获得焦点，mousePressed 被消耗在焦点切换上，
                // 此时 list.getSelectedValue() 仍为 null；改为根据点击坐标显式定位索引
                int idx = list.locationToIndex(e.getPoint());
                if (idx < 0) return;
                Rectangle cell = list.getCellBounds(idx, idx);
                if (cell == null || !cell.contains(e.getPoint())) return;
                list.setSelectedIndex(idx);
                selectAndClose.run();
            }
        });
        searchField.addActionListener(e -> {
            if (listModel.size() == 1) list.setSelectedIndex(0);
            selectAndClose.run();
        });

        // Escape 关闭
        dialog.getRootPane().registerKeyboardAction(
                e -> forceCloseProjectPopup(),
                KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_ESCAPE, 0),
                JComponent.WHEN_IN_FOCUSED_WINDOW);

        dialog.pack();
        Point loc = projectCombo.getLocationOnScreen();
        dialog.setLocation(loc.x, loc.y + projectCombo.getHeight());
        dialog.setVisible(true);
        searchField.requestFocusInWindow();

        // 点击外部关闭（延迟注册）
        SwingUtilities.invokeLater(() -> {
            projectOutsideListener = event -> {
                if (event instanceof java.awt.event.MouseEvent me
                        && me.getID() == java.awt.event.MouseEvent.MOUSE_PRESSED) {
                    if (projectSearchDialog != null && projectSearchDialog.isShowing()) {
                        try {
                            Point click = me.getLocationOnScreen();
                            Rectangle bounds = projectSearchDialog.getBounds();
                            Rectangle btnBounds = new Rectangle(
                                    projectCombo.getLocationOnScreen(),
                                    projectCombo.getSize());
                            if (!bounds.contains(click) && !btnBounds.contains(click)) {
                                SwingUtilities.invokeLater(this::forceCloseProjectPopup);
                            }
                        } catch (Exception ignored) {}
                    }
                }
            };
            Toolkit.getDefaultToolkit().addAWTEventListener(
                    projectOutsideListener, java.awt.AWTEvent.MOUSE_EVENT_MASK);
        });
    }

    /**
     * 注销：断开 FTP 连接并重置目标栏状态；不删除任何已保存凭据
     *
     * @param silent true 时不修改连接状态标签（用于切换账号的中间过程）
     * @author xumanyi
     * @date 2026-04-17
     */
    /** FTP "⋯ 更多" 菜单：切换账号 / 注销（刷新已提取为行内图标按钮） */
    private void showFtpMoreMenu(JComponent anchor) {
        boolean connected = browseService != null && browseService.isConnected();
        JPopupMenu menu = new JPopupMenu();

        JMenuItem switchItem = new JMenuItem("切换账号");
        // 用传入的 anchor（即"⋯ 更多"按钮）作为弹窗定位锚点；它是实际显示在界面上的组件
        switchItem.addActionListener(e -> showAccountSwitcherPopup(anchor));
        menu.add(switchItem);

        JMenuItem logoutItem = new JMenuItem("注销");
        logoutItem.setEnabled(connected);
        logoutItem.addActionListener(e -> doLogout(false));
        menu.add(logoutItem);

        menu.show(anchor, 0, anchor.getHeight());
    }

    private void doLogout(boolean silent) {
        if (browseService != null) {
            try { browseService.disconnect(); } catch (Exception ignored) {}
            browseService = null;
        }
        connectedHost = null;
        connectedPort = 0;
        connectedUsername = null;
        connectedPassword = null;

        if (!silent) {
            setLoggedOut();
            connectButton.setVisible(true);
            logoutButton.setVisible(false);
            refreshButton.setVisible(false);
        }
        allProjects = List.of();
        selectedProject = null;
        projectCombo.setText("请选择项目");
        refreshing = true;
        systemCombo.removeAllItems();
        clearExtraLevels();
        refreshing = false;
        clearPackages();
        fireContextChange();
    }

    /** 账号行行高 */
    private static final int ACCOUNT_ROW_HEIGHT = 48;
    /** 账号弹窗首选宽度 */
    private static final int ACCOUNT_POPUP_WIDTH = 420;

    /**
     * 弹出账号切换列表：高亮当前账号，每行可点击切换 / × 删除，底部"+ 添加新账号"
     *
     * <p>弹窗右对齐 {@code anchor} 按钮右边界，避免被窗口右侧遮挡；超出屏幕时自动回退到左对齐。</p>
     *
     * @param anchor 实际显示在界面上的锚点按钮（如 "⋯ 更多" 按钮）；必须处于 showing 状态，
     *               否则 {@link Component#getLocationOnScreen()} 会抛 {@link java.awt.IllegalComponentStateException}
     */
    private void showAccountSwitcherPopup(JComponent anchor) {
        List<com.flux.deploy.util.CredentialCache.CachedCredential> accounts =
                com.flux.deploy.plugin.util.CredentialBridge.listAll();

        Window owner = SwingUtilities.getWindowAncestor(anchor);
        JDialog dialog = new JDialog(owner);
        dialog.setUndecorated(true);
        // 不要 setAlwaysOnTop(true)：会让弹窗浮在其他应用之上，切到其他应用时弹窗仍可见。
        // 依赖 owner 的父子关系保证在 IDE 前台时弹窗位于 IDE 内容之上即可。
        dialog.setLayout(new BorderLayout());
        dialog.getRootPane().setBorder(BorderFactory.createLineBorder(
                UIManager.getColor("Component.borderColor") == null
                        ? new Color(100, 100, 100)
                        : UIManager.getColor("Component.borderColor"), 1, true));

        // 标题区
        JBLabel header = new JBLabel(accounts.isEmpty()
                ? "  已保存账号"
                : "  已保存账号  (" + accounts.size() + ")");
        header.setFont(header.getFont().deriveFont(Font.BOLD));
        header.setForeground(UIManager.getColor("Label.disabledForeground"));
        header.setBorder(BorderFactory.createEmptyBorder(6, 10, 6, 10));

        JPanel list = new JPanel();
        list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS));
        list.setOpaque(true);
        list.setBackground(UIManager.getColor("Panel.background"));

        if (accounts.isEmpty()) {
            JBLabel empty = new JBLabel("暂无已保存账号", SwingConstants.CENTER);
            empty.setForeground(UIManager.getColor("Label.disabledForeground"));
            empty.setBorder(BorderFactory.createEmptyBorder(16, 10, 16, 10));
            empty.setAlignmentX(Component.LEFT_ALIGNMENT);
            empty.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));
            list.add(empty);
        } else {
            for (com.flux.deploy.util.CredentialCache.CachedCredential acc : accounts) {
                JComponent row = buildAccountRow(acc, dialog);
                row.setAlignmentX(Component.LEFT_ALIGNMENT);
                row.setMaximumSize(new Dimension(Integer.MAX_VALUE, ACCOUNT_ROW_HEIGHT));
                list.add(row);
            }
        }

        // 底部添加账号行
        JPanel addRow = new JPanel(new BorderLayout());
        addRow.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));
        addRow.setOpaque(false);
        JButton addButton = new JButton("+ 添加新账号...");
        addButton.setHorizontalAlignment(SwingConstants.LEFT);
        addButton.setBorderPainted(false);
        addButton.setContentAreaFilled(false);
        addButton.setFocusPainted(false);
        addButton.setForeground(UIManager.getColor("Link.activeForeground") == null
                ? new Color(88, 157, 246)
                : UIManager.getColor("Link.activeForeground"));
        addButton.setBorder(BorderFactory.createEmptyBorder(6, 10, 6, 10));
        addButton.addActionListener(e -> {
            dialog.dispose();
            showLoginDialog();
        });
        addHoverBackground(addButton);
        addRow.add(addButton, BorderLayout.CENTER);
        addRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, ACCOUNT_ROW_HEIGHT));
        addRow.setAlignmentX(Component.LEFT_ALIGNMENT);

        JPanel center = new JPanel();
        center.setLayout(new BoxLayout(center, BoxLayout.Y_AXIS));
        center.add(header);
        center.add(new JSeparator());
        center.add(list);
        center.add(new JSeparator());
        center.add(addRow);

        JBScrollPane scroll = new JBScrollPane(center);
        scroll.setBorder(BorderFactory.createEmptyBorder());

        int popupHeight = Math.min(360,
                40 /*header*/ + Math.max(1, accounts.size()) * ACCOUNT_ROW_HEIGHT
                        + ACCOUNT_ROW_HEIGHT /*add row*/ + 8);
        scroll.setPreferredSize(new Dimension(ACCOUNT_POPUP_WIDTH, popupHeight));
        dialog.add(scroll, BorderLayout.CENTER);

        dialog.getRootPane().registerKeyboardAction(
                e -> dialog.dispose(),
                KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_ESCAPE, 0),
                JComponent.WHEN_IN_FOCUSED_WINDOW);

        dialog.pack();

        // 定位：右对齐锚点按钮右边界；若因此溢出左侧则回退到左对齐；再做屏幕边界兜底
        Point btnLoc = anchor.getLocationOnScreen();
        int btnRight = btnLoc.x + anchor.getWidth();
        int popupW = dialog.getWidth();
        int x = btnRight - popupW;
        Rectangle screen = anchor.getGraphicsConfiguration().getBounds();
        if (x < screen.x + 8) x = btnLoc.x; // 左对齐兜底
        if (x + popupW > screen.x + screen.width - 8) x = screen.x + screen.width - popupW - 8;
        int y = btnLoc.y + anchor.getHeight() + 2;
        if (y + dialog.getHeight() > screen.y + screen.height - 8) {
            y = btnLoc.y - dialog.getHeight() - 2;
        }
        dialog.setLocation(x, y);
        dialog.setVisible(true);

        // 点击外部关闭
        java.awt.event.AWTEventListener outside = event -> {
            if (event instanceof java.awt.event.MouseEvent me
                    && me.getID() == java.awt.event.MouseEvent.MOUSE_PRESSED) {
                if (dialog.isShowing()) {
                    try {
                        Point click = me.getLocationOnScreen();
                        if (!dialog.getBounds().contains(click)
                                && !new Rectangle(anchor.getLocationOnScreen(),
                                        anchor.getSize()).contains(click)) {
                            SwingUtilities.invokeLater(dialog::dispose);
                        }
                    } catch (Exception ignored) {}
                }
            }
        };
        Toolkit.getDefaultToolkit().addAWTEventListener(outside,
                java.awt.AWTEvent.MOUSE_EVENT_MASK);
        dialog.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override public void windowClosed(java.awt.event.WindowEvent e) {
                Toolkit.getDefaultToolkit().removeAWTEventListener(outside);
            }
        });
    }

    /** 构建一条账号行：账号信息 + 切换按钮 + 删除按钮 */
    private JComponent buildAccountRow(
            com.flux.deploy.util.CredentialCache.CachedCredential acc, JDialog parent) {
        boolean isCurrent = acc.getHost() != null
                && acc.getHost().equals(connectedHost)
                && acc.getPort() == connectedPort
                && acc.getUsername() != null
                && acc.getUsername().equals(connectedUsername);

        JPanel row = new JPanel(new BorderLayout(6, 0));
        row.setBorder(BorderFactory.createEmptyBorder(4, 10, 4, 8));
        row.setOpaque(true);
        Color baseBg = UIManager.getColor("Panel.background");
        Color hoverBg = UIManager.getColor("List.selectionBackground");
        if (hoverBg == null) hoverBg = new Color(62, 90, 130);
        row.setBackground(isCurrent ? blend(baseBg, hoverBg, 0.25f) : baseBg);

        // 左侧：账号标题 + 次行日期
        JPanel info = new JPanel();
        info.setLayout(new BoxLayout(info, BoxLayout.Y_AXIS));
        info.setOpaque(false);

        String title = acc.getUsername() + "@" + acc.getHost() + ":" + acc.getPort();
        JBLabel titleLabel = new JBLabel((isCurrent ? "● " : "   ") + title);
        titleLabel.setFont(titleLabel.getFont().deriveFont(
                isCurrent ? Font.BOLD : Font.PLAIN, 13f));
        if (isCurrent) {
            titleLabel.setForeground(new Color(80, 170, 100));
        }
        titleLabel.setAlignmentX(Component.LEFT_ALIGNMENT);

        String sub = (acc.getVerifiedAt() == null || acc.getVerifiedAt().isBlank()
                ? "" : "最近验证：" + acc.getVerifiedAt());
        JBLabel subLabel = new JBLabel("   " + sub);
        subLabel.setFont(subLabel.getFont().deriveFont(11f));
        subLabel.setForeground(UIManager.getColor("Label.disabledForeground"));
        subLabel.setAlignmentX(Component.LEFT_ALIGNMENT);

        info.add(titleLabel);
        if (!sub.isEmpty()) info.add(subLabel);

        row.add(info, BorderLayout.CENTER);

        // 右侧：切换 + 删除
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        actions.setOpaque(false);

        JButton switchBtn = new JButton(isCurrent ? "当前" : "切换");
        switchBtn.setMargin(new Insets(2, 10, 2, 10));
        switchBtn.setFocusPainted(false);
        if (isCurrent) {
            switchBtn.setEnabled(false);
            switchBtn.setToolTipText("当前已连接的账号");
        } else {
            switchBtn.setToolTipText("切换到此账号");
            switchBtn.addActionListener(e -> {
                parent.dispose();
                switchToAccount(acc);
            });
        }
        actions.add(switchBtn);

        JButton deleteBtn = new JButton("删除");
        deleteBtn.setMargin(new Insets(2, 10, 2, 10));
        deleteBtn.setFocusPainted(false);
        deleteBtn.setToolTipText(isCurrent ? "删除此账号并断开连接" : "删除此账号");
        deleteBtn.addActionListener(e -> {
            // 删除是不可逆操作：默认按钮落在「否」，避免误回车删账号
            Object[] delOptions = {
                    UIManager.getString("OptionPane.yesButtonText"),
                    UIManager.getString("OptionPane.noButtonText")
            };
            int confirm = JOptionPane.showOptionDialog(parent,
                    "确认删除账号 " + acc.getUsername() + "@" + acc.getHost() + "？\n"
                            + (isCurrent ? "当前已连接的账号将被断开。" : ""),
                    "删除账号", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE,
                    null, delOptions, delOptions[1]);
            if (confirm != JOptionPane.YES_OPTION) return;
            com.flux.deploy.plugin.util.CredentialBridge.deleteCredential(
                    acc.getHost(), acc.getPort(), acc.getUsername());
            if (isCurrent) doLogout(false);
            parent.dispose();
        });
        actions.add(deleteBtn);

        row.add(actions, BorderLayout.EAST);

        // 行悬浮高亮
        final Color finalBase = row.getBackground();
        final Color finalHover = hoverBg;
        row.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseEntered(java.awt.event.MouseEvent e) {
                row.setBackground(blend(finalBase, finalHover, 0.25f));
            }
            @Override public void mouseExited(java.awt.event.MouseEvent e) {
                row.setBackground(finalBase);
            }
        });
        return row;
    }

    /** 组件悬浮时背景高亮 */
    private static void addHoverBackground(JComponent c) {
        c.setOpaque(false);
        Color hover = UIManager.getColor("List.hoverBackground");
        if (hover == null) hover = UIManager.getColor("List.selectionBackground");
        if (hover == null) hover = new Color(62, 90, 130);
        Color finalHover = hover;
        c.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseEntered(java.awt.event.MouseEvent e) {
                c.setOpaque(true);
                c.setBackground(finalHover);
                c.repaint();
            }
            @Override public void mouseExited(java.awt.event.MouseEvent e) {
                c.setOpaque(false);
                c.repaint();
            }
        });
    }

    /** 两色按比例混合（ratio=0 → a，ratio=1 → b） */
    private static Color blend(Color a, Color b, float ratio) {
        if (a == null) return b;
        if (b == null) return a;
        float r = Math.max(0, Math.min(1, ratio));
        int red = (int) (a.getRed() * (1 - r) + b.getRed() * r);
        int green = (int) (a.getGreen() * (1 - r) + b.getGreen() * r);
        int blue = (int) (a.getBlue() * (1 - r) + b.getBlue() * r);
        return new Color(red, green, blue);
    }

    /**
     * 切换到指定账号：断开当前连接 → 重置目标栏 → 用该账号重新连接
     *
     * @author xumanyi
     * @date 2026-04-17
     */
    private void switchToAccount(com.flux.deploy.util.CredentialCache.CachedCredential acc) {
        doLogout(true);
        setLoggingIn("切换至 " + acc.getUsername());
        doConnect(acc.getHost(), acc.getPort(), acc.getUsername(), acc.getPassword());
    }

    /**
     * 完全重置目标区（项目/系统/目标包全部清空，保留 FTP 连接）
     */
    public void resetAll() {
        selectedProject = null;
        projectCombo.setText("请选择项目");
        refreshing = true;
        systemCombo.removeAllItems();
        clearExtraLevels();
        refreshing = false;
        clearPackages();
    }

    /**
     * 清空目标包列表
     */
    public void clearPackages() {
        currentPackages = List.of();
        sourceArtifactName = null;
        sourceArtifactType = null;
        packagePanel.setVisible(false);
        rebuildPackageTree();
    }

    // ==================== FTP 连接 ====================

    /** 设置"已登录"状态：文字精简成两个字，账号全文挪到 tooltip */
    private void setLoggedIn(String username, String host, int port) {
        connectionStatus.setText("已登录");
        connectionStatus.setForeground(new Color(0, 128, 0));
        connectionStatus.setToolTipText(username + "@" + host + ":" + port);
    }

    /** 设置"未登录"状态：清掉 tooltip，避免显示旧账号信息 */
    private void setLoggedOut() {
        connectionStatus.setText("未登录");
        connectionStatus.setForeground(Color.RED);
        connectionStatus.setToolTipText(null);
    }

    /** 设置"登录中"状态：橙色提示，tooltip 可附带目标账号（无则 null） */
    private void setLoggingIn(String detail) {
        connectionStatus.setText("登录中...");
        connectionStatus.setForeground(Color.ORANGE);
        connectionStatus.setToolTipText(detail);
    }

    /** 设置"登录失败"状态：文字仍为两个字，错误详情挪到 tooltip 避免溢出 */
    private void setLoginFailed(String reason) {
        connectionStatus.setText("登录失败");
        connectionStatus.setForeground(Color.RED);
        connectionStatus.setToolTipText(reason);
    }

    /** 尝试使用缓存凭据自动连接 FTP */
    private void tryAutoConnect() {
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            CredentialCache.CachedCredential cached = CredentialBridge.loadCachedCredential();
            if (cached != null) {
                SwingUtilities.invokeLater(() -> setLoggingIn(null));
                doConnect(cached.getHost(), cached.getPort(),
                        cached.getUsername(), cached.getPassword());
            }
        });
    }

    /** 执行 FTP 连接：优先使用缓存凭据，无缓存时弹出登录对话框 */
    private void connect() {
        CredentialCache.CachedCredential cached = CredentialBridge.loadCachedCredential();
        if (cached != null) {
            setLoggingIn(null);
            doConnect(cached.getHost(), cached.getPort(), cached.getUsername(), cached.getPassword());
            return;
        }
        showLoginDialog();
    }

    /** 显示 FTP 登录对话框 */
    private void showLoginDialog() {
        JPanel panel = new JPanel(new GridLayout(4, 2, 4, 4));
        JTextField hostField = new JTextField();
        JTextField portField = new JTextField("18080");
        JTextField userField = new JTextField();
        JPasswordField passField = new JPasswordField();

        panel.add(new JBLabel("主机："));   panel.add(hostField);
        panel.add(new JBLabel("端口："));   panel.add(portField);
        panel.add(new JBLabel("用户名：")); panel.add(userField);
        panel.add(new JBLabel("密码："));   panel.add(passField);

        int result = JOptionPane.showConfirmDialog(this, panel,
                "FTP 登录", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);

        if (result == JOptionPane.OK_OPTION) {
            String host = hostField.getText().trim();
            int port = Integer.parseInt(portField.getText().trim());
            String user = userField.getText().trim();
            String pass = new String(passField.getPassword());
            setLoggingIn(null);
            doConnectAndSave(host, port, user, pass);
        }
    }

    /** 连接 FTP 并保存凭据到缓存 */
    private void doConnectAndSave(String host, int port, String username, String password) {
        showLoading("连接 FTP 并加载项目列表...");
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                synchronized (ftpLock) {
                try {
                    if (browseService != null) {
                        try { browseService.disconnect(); } catch (Exception ignored) {}
                    }
                    browseService = new FtpBrowseService(host, port, username, password);
                    connectedHost = host;
                    connectedPort = port;
                    connectedUsername = username;
                    connectedPassword = password;
                    CredentialBridge.saveCredential(host, port, username, password);
                    List<String> projects = browseService.listSubdirectories("/开发/");
                    SwingUtilities.invokeLater(() -> {
                        setLoggedIn(username, host, port);
                        connectButton.setVisible(false);
                        logoutButton.setVisible(true);
                        refreshButton.setVisible(true);
                        allProjects = new ArrayList<>(projects);
                        selectedProject = null;
                        projectCombo.setText("请选择项目");
                        fireContextChange();
                    });
                } catch (Exception ex) {
                    SwingUtilities.invokeLater(() -> setLoginFailed(ex.getMessage()));
                }
                } // end synchronized(ftpLock)
            } finally {
                SwingUtilities.invokeLater(this::hideLoading);
            }
        });
    }

    /** 使用指定凭据连接 FTP 并加载项目列表 */
    private void doConnect(String host, int port, String username, String password) {
        showLoading("连接 FTP 并加载项目列表...");
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                synchronized (ftpLock) {
                try {
                    if (browseService != null) {
                        try { browseService.disconnect(); } catch (Exception ignored) {}
                    }
                    browseService = new FtpBrowseService(host, port, username, password);
                    connectedHost = host;
                    connectedPort = port;
                    connectedUsername = username;
                    connectedPassword = password;
                    List<String> projects = browseService.listSubdirectories("/开发/");
                    SwingUtilities.invokeLater(() -> {
                        setLoggedIn(username, host, port);
                        connectButton.setVisible(false);
                        logoutButton.setVisible(true);
                        refreshButton.setVisible(true);
                        allProjects = new ArrayList<>(projects);
                        selectedProject = null;
                        projectCombo.setText("请选择项目");
                        fireContextChange();
                    });
                } catch (Exception ex) {
                    SwingUtilities.invokeLater(() -> setLoginFailed(ex.getMessage()));
                }
                } // end synchronized(ftpLock)
            } finally {
                SwingUtilities.invokeLater(this::hideLoading);
            }
        });
    }

    /** 加载指定项目下的系统列表（连接失败时自动重连一次） */
    private void loadSystems(String projectName) {
        showLoading("加载系统列表...");
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
            synchronized (ftpLock) {
                try {
                    // 连接可能超时，先检查并重连
                    if (browseService == null || !browseService.isConnected()) {
                        reconnectFtp();
                    }
                    if (browseService == null) return;

                    List<String> systems = browseService.listSubdirectories("/开发/" + projectName + "/");
                    SwingUtilities.invokeLater(() -> {
                        refreshing = true;
                        systemCombo.removeAllItems();
                        // 项目切换会令旧子目录层级失效（refreshing=true 时 listener 不会代为清理）
                        clearExtraLevels();
                        currentPackages = List.of();
                        rebuildPackageTree();
                        for (String s : systems) systemCombo.addItem(s);
                        systemCombo.setSelectedIndex(-1);
                        refreshing = false;
                    });
                } catch (Exception ex) {
                    // 重连一次
                    try {
                        reconnectFtp();
                        if (browseService != null) {
                            List<String> systems = browseService.listSubdirectories(
                                    "/开发/" + projectName + "/");
                            SwingUtilities.invokeLater(() -> {
                                refreshing = true;
                                systemCombo.removeAllItems();
                                clearExtraLevels();
                                for (String s : systems) systemCombo.addItem(s);
                                systemCombo.setSelectedIndex(-1);
                                refreshing = false;
                            });
                            return;
                        }
                    } catch (Exception ignored) {}
                    SwingUtilities.invokeLater(() ->
                            setLoginFailed("加载失败：" + ex.getMessage()));
                }
            }
            } finally {
                SwingUtilities.invokeLater(this::hideLoading);
            }
        });
    }

    /**
     * 加载指定项目和系统下的目标包列表，连接超时时自动重连
     *
     * <p>实际扫描路径由 {@link #buildScanPath(String, String)} 拼接，
     * 含已选中的子目录层级，用于把 (C) 类多层混深项目的列表收窄到指定子树。</p>
     *
     * <p><b>路径修正</b>：扫描收窄时，{@code scanPackagesStructured} 返回的
     * {@link PackageInfo#getRelativePath()} 是相对于 <i>scan 根</i>（即 narrowed 路径）的；
     * 但下游 {@link com.flux.deploy.plugin.model.FtpTargetSelection#getRemoteDir()}
     * 总是按 {@code /开发/{proj}/{sys}/} 拼接，所以这里必须把"子目录前缀"
     * （如 {@code "shared-tms/"}）补回 relativePath，下游做 {@code remoteDir + relativePath}
     * 才能拿到完整 FTP 绝对路径。subDirectory 字段用于 UI 分组，保持原值不变。</p>
     */
    private void loadTargetPackages(String projectName, String systemName) {
        showLoading("加载目标包列表...");
        // EDT 上读取 extraLevels 状态，避免后续 IO 线程读到不一致快照
        final String scanPath = buildScanPath(projectName, systemName);
        final String relPathPrefix = buildSubdirPrefix();
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
            synchronized (ftpLock) {
            try {
                // 检查连接是否有效，无效则重连
                if (browseService == null || !browseService.isConnected()) {
                    reconnectFtp();
                }
                if (browseService == null) return;

                List<PackageInfo> packages = applySubdirPrefixToRelativePath(
                        browseService.scanPackagesStructured(scanPath), relPathPrefix);
                SwingUtilities.invokeLater(() -> {
                    currentPackages = packages;
                    // 重新应用源产物匹配（确保 locked 状态正确）
                    if (sourceArtifactName != null) {
                        setSourceArtifact(sourceArtifactName);
                    } else {
                        rebuildPackageTree();
                    }
                });
            } catch (Exception ex) {
                // 连接可能超时，尝试重连一次
                try {
                    reconnectFtp();
                    if (browseService != null) {
                        List<PackageInfo> packages = applySubdirPrefixToRelativePath(
                                browseService.scanPackagesStructured(scanPath), relPathPrefix);
                        SwingUtilities.invokeLater(() -> {
                            currentPackages = packages;
                            if (sourceArtifactName != null) {
                                setSourceArtifact(sourceArtifactName);
                            } else {
                                rebuildPackageTree();
                            }
                        });
                        return;
                    }
                } catch (Exception retryEx) {
                    // 重连也失败
                }
                SwingUtilities.invokeLater(() ->
                        setLoginFailed("加载失败：" + ex.getMessage()));
            }
            } // end synchronized(ftpLock)
            } finally {
                SwingUtilities.invokeLater(this::hideLoading);
            }
        });
    }

    /**
     * 拼接当前已选子目录层级的相对路径前缀（不含项目/系统部分）
     *
     * <p>例：选了 [shared-tms, v2] → 返回 {@code "shared-tms/v2/"}；
     * 没选任何子目录层级 → 返回 {@code ""}。</p>
     *
     * @return 永远以 "/" 结尾或空串
     * @author xumanyi
     * @date 2026-05-02
     */
    private String buildSubdirPrefix() {
        StringBuilder sb = new StringBuilder();
        for (SubdirLevel lvl : extraLevels) {
            if (lvl.selectedSubdir == null || lvl.selectedSubdir.isEmpty()) {
                break;
            }
            sb.append(lvl.selectedSubdir).append("/");
        }
        return sb.toString();
    }

    /**
     * 把子目录前缀补到每个 {@link PackageInfo#getRelativePath()} 头部，
     * 让下游 {@code remoteDir + relativePath} 能拼出正确的 FTP 绝对路径。
     *
     * <p>若 prefix 为空或 packages 为空，原样返回（零拷贝）。
     * subDirectory / packageName / type / size / fromBackup / modifiedTime 字段全部保留。</p>
     *
     * @param packages 扫描结果（relativePath 当前是相对 scan 根的）
     * @param prefix   要补的前缀（{@link #buildSubdirPrefix()} 的产物）
     * @return 修正后的 PackageInfo 列表
     * @author xumanyi
     * @date 2026-05-02
     */
    private List<PackageInfo> applySubdirPrefixToRelativePath(List<PackageInfo> packages,
                                                                String prefix) {
        if (prefix == null || prefix.isEmpty() || packages == null || packages.isEmpty()) {
            return packages;
        }
        List<PackageInfo> adjusted = new ArrayList<>(packages.size());
        for (PackageInfo pkg : packages) {
            adjusted.add(new PackageInfo(
                    pkg.getSubDirectory(),
                    pkg.getPackageName(),
                    pkg.getType(),
                    prefix + pkg.getRelativePath(),
                    pkg.getSize(),
                    pkg.isFromBackup(),
                    pkg.getModifiedTime()));
        }
        return adjusted;
    }

    // ==================== 工具方法 ====================

    /**
     * 精确匹配：去掉扩展名后完整文件名相同
     *
     * <p>例：scev6-utils-commonUtils-10.0.0-SNAPSHOT.jar 只匹配
     * scev6-utils-commonUtils-10.0.0-SNAPSHOT.jar，不匹配 8.0.0 版本。</p>
     */
    private static boolean isExactArtifactMatch(String packageName, String sourceArtifactName) {
        if (packageName == null || sourceArtifactName == null) return false;
        // 去扩展名后比较（忽略大小写）
        String pkgBase = removeExtension(packageName);
        String srcBase = removeExtension(sourceArtifactName);
        return pkgBase.equalsIgnoreCase(srcBase);
    }

    /** 去掉文件扩展名 */
    private static String removeExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    /**
     * 判断包名是否匹配指定的 artifactId 前缀
     *
     * <p>包名去掉扩展名后，前缀完全等于 artifactPrefix，
     * 且前缀后紧跟 "-" + 数字（版本号开始）。
     * 防止 scev6-utils 误匹配 scev6-utils-tms。</p>
     */
    private static boolean isArtifactMatch(String packageName, String artifactPrefix) {
        // 去掉扩展名
        String name = packageName;
        int dotIdx = name.lastIndexOf('.');
        if (dotIdx > 0) name = name.substring(0, dotIdx);

        if (!name.startsWith(artifactPrefix)) return false;
        // 精确匹配（无版本号，如 tm10srv.war 匹配 tm10srv）
        if (name.length() == artifactPrefix.length()) return true;
        // 前缀后必须是 "-数字"（版本号开始）
        if (name.length() > artifactPrefix.length() + 1) {
            char sep = name.charAt(artifactPrefix.length());
            char next = name.charAt(artifactPrefix.length() + 1);
            return sep == '-' && Character.isDigit(next);
        }
        return false;
    }

    /**
     * 从文件名提取 artifactId 前缀（去掉版本号部分）
     * 例：scev6-utils-tms-10.0.0-SNAPSHOT.jar → scev6-utils-tms
     */
    private static String extractArtifactPrefix(String fileName) {
        if (fileName == null) return null;
        // 去掉扩展名
        String name = fileName;
        int dotIdx = name.lastIndexOf('.');
        if (dotIdx > 0) name = name.substring(0, dotIdx);
        // 找到第一个 -数字 的位置（版本号开始）
        for (int i = 1; i < name.length(); i++) {
            if (name.charAt(i - 1) == '-' && Character.isDigit(name.charAt(i))) {
                return name.substring(0, i - 1);
            }
        }
        return name;
    }

    /**
     * 字母 + 数字混合自然排序比较器
     *
     * <p>把字符串切成 "数字段" 和 "非数字段" 交替比较：数字段按数值大小比较，
     * 非数字段按大小写不敏感的字典序比较。例如：
     * tm01srv1 &lt; tm01srv2 &lt; tm01srv10（而非 tm01srv1, tm01srv10, tm01srv2）。</p>
     *
     * <p>纯数字段实现细节：先跳过前导 0，长度长的数值更大；长度相同则逐位比较。
     * 这样可以正确处理 "01" 和 "1" 视作等值（最终用原始长度做 tie-break）。</p>
     *
     * @param a 左操作数，允许为 null（视为空串）
     * @param b 右操作数，允许为 null（视为空串）
     * @return 标准 Comparator 语义的负/零/正整数
     * @author xumanyi
     * @date 2026-04-29
     */
    private static int naturalCompare(String a, String b) {
        if (a == null) a = "";
        if (b == null) b = "";
        int ai = 0;
        int bi = 0;
        int aLen = a.length();
        int bLen = b.length();
        while (ai < aLen && bi < bLen) {
            char ac = a.charAt(ai);
            char bc = b.charAt(bi);
            boolean aDigit = Character.isDigit(ac);
            boolean bDigit = Character.isDigit(bc);
            if (aDigit && bDigit) {
                int aEnd = ai;
                while (aEnd < aLen && Character.isDigit(a.charAt(aEnd))) aEnd++;
                int bEnd = bi;
                while (bEnd < bLen && Character.isDigit(b.charAt(bEnd))) bEnd++;
                int aStart = ai;
                while (aStart < aEnd - 1 && a.charAt(aStart) == '0') aStart++;
                int bStart = bi;
                while (bStart < bEnd - 1 && b.charAt(bStart) == '0') bStart++;
                int aDigits = aEnd - aStart;
                int bDigits = bEnd - bStart;
                if (aDigits != bDigits) return Integer.compare(aDigits, bDigits);
                for (int k = 0; k < aDigits; k++) {
                    int d = a.charAt(aStart + k) - b.charAt(bStart + k);
                    if (d != 0) return d;
                }
                // 数值相等：长度（含前导 0）短的视为更小，保证排序稳定可预期
                int lenDiff = (aEnd - ai) - (bEnd - bi);
                if (lenDiff != 0) return lenDiff;
                ai = aEnd;
                bi = bEnd;
            } else if (aDigit) {
                // 数字段排在非数字段前
                return -1;
            } else if (bDigit) {
                return 1;
            } else {
                int d = Character.compare(Character.toLowerCase(ac), Character.toLowerCase(bc));
                if (d != 0) return d;
                ai++;
                bi++;
            }
        }
        return Integer.compare(aLen - ai, bLen - bi);
    }

    // ==================== 公共 getter ====================

    /** @return 已连接的 FTP 主机地址
     * @author xumanyi
     * @date 2026-03-27
     */
    public String getConnectedHost() { return connectedHost; }
    /** @return 已连接的 FTP 端口
     * @author xumanyi
     * @date 2026-03-27
     */
    public int getConnectedPort() { return connectedPort; }
    /** @return 已连接的 FTP 用户名
     * @author xumanyi
     * @date 2026-03-27
     */
    public String getConnectedUsername() { return connectedUsername; }
    /** @return 已连接的 FTP 密码
     * @author xumanyi
     * @date 2026-03-27
     */
    public String getConnectedPassword() { return connectedPassword; }
    /** @return FTP 是否已连接
     * @author xumanyi
     * @date 2026-03-27
     */
    public boolean isFtpConnected() { return browseService != null && browseService.isConnected(); }

    /**
     * 获取当前 FTP 上下文路径
     *
     * <p>供 {@code BackupLocationDialog} 与 {@code InfoSectionPanel} 计算默认派生
     * 备份路径用。不复用 {@link com.flux.deploy.plugin.model.FtpTargetSelection#getRemoteDir()}，
     * 后者强假设 system 非空。</p>
     *
     * <p>返回值规则（按优先级）：</p>
     * <ul>
     *   <li>项目 + 系统都已选：{@code /开发/{project}/{system}/}</li>
     *   <li>仅项目已选：{@code /开发/{project}/}</li>
     *   <li>项目未选：返回 null</li>
     * </ul>
     *
     * @return 当前 FTP 上下文目录（含尾部 /），项目未选时返回 null
     * @author xumanyi
     * @date 2026-05-02
     */
    public String getCurrentContextDir() {
        if (selectedProject == null || selectedProject.isBlank()) {
            return null;
        }
        Object sys = systemCombo.getSelectedItem();
        if (sys != null && !((String) sys).isBlank()) {
            return "/开发/" + selectedProject + "/" + sys + "/";
        }
        return "/开发/" + selectedProject + "/";
    }

    /**
     * 获取当前项目根目录（不含系统），用于 BackupLocationDialog 限制目录树根范围。
     *
     * @return 项目根（含尾部 /）；项目未选时返回 null
     * @author xumanyi
     * @date 2026-05-02
     */
    public String getCurrentProjectDir() {
        if (selectedProject == null || selectedProject.isBlank()) {
            return null;
        }
        return "/开发/" + selectedProject + "/";
    }

    /**
     * 注册当前 FTP 上下文（项目 / 系统 / 连接态）变化的回调。
     *
     * <p>{@code InfoSectionPanel} 用此回调刷新"备份至"行。
     * 多次调用会替换原回调（仅一个监听者）。</p>
     *
     * @param callback 回调函数；可为 null 表示清除监听
     * @author xumanyi
     * @date 2026-05-02
     */
    public void setContextChangeCallback(Runnable callback) {
        this.contextChangeCallback = callback;
    }

    /** 上下文（项目 / 系统 / 连接）变化的回调，由 {@link InfoSectionPanel} 注册 */
    private Runnable contextChangeCallback;

    /**
     * 触发上下文变化回调（内部调用）
     *
     * @author xumanyi
     * @date 2026-05-02
     */
    private void fireContextChange() {
        if (contextChangeCallback != null) {
            contextChangeCallback.run();
        }
    }

    // ==================== 内部类 ====================

    /**
     * 包节点数据
     */
    static class PackageNodeData {
        final PackageInfo info;
        // 是否为源产物精确匹配的主目标（默认勾选 + 渲染加粗，但用户仍可取消）
        final boolean locked;

        PackageNodeData(PackageInfo info, boolean locked) {
            this.info = info;
            this.locked = locked;
        }

        @Override
        public String toString() {
            return info.getPackageName();
        }
    }

    /**
     * 树节点渲染器
     */
    private static class PackageTreeRenderer extends CheckboxTree.CheckboxTreeCellRenderer {
        @Override
        public void customizeRenderer(JTree tree, Object value, boolean selected,
                                       boolean expanded, boolean leaf, int row, boolean hasFocus) {
            if (value instanceof CheckedTreeNode node) {
                Object userObj = node.getUserObject();
                if (userObj instanceof PackageNodeData data) {
                    // 主目标加粗显示，便于识别；checkbox 不再置灰，允许用户取消
                    if (data.locked) {
                        getTextRenderer().append(data.info.getPackageName(),
                                SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES);
                    } else {
                        getTextRenderer().append(data.info.getPackageName(),
                                SimpleTextAttributes.REGULAR_ATTRIBUTES);
                    }
                    // 包名后跟灰色「修改时间 · 体积」；不再显示 [WAR]/[JAR]（文件后缀已表明类型）
                    String meta = formatPackageMeta(data.info.getModifiedTime(), data.info.getSize());
                    if (!meta.isEmpty()) {
                        getTextRenderer().append("   " + meta,
                                new SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN,
                                        Color.GRAY));
                    }
                } else if (userObj instanceof String dirName) {
                    // 目录节点
                    getTextRenderer().append(dirName,
                            new SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, null));
                }
            }
        }

        /** 列表行「修改时间」格式，精确到分钟（FTP LIST 通常只给到分钟） */
        private static final DateTimeFormatter META_TIME_FMT =
                DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

        /**
         * 拼装包列表行右侧的灰色元信息：「修改时间 · 体积」。
         *
         * <p>修改时间按本地时区格式化为 {@code yyyy-MM-dd HH:mm}，为 0（FTP 未给时间戳）时省略；
         * 体积按数量级自适应：&lt;1MB 显示 KB，否则显示 MB（各保留 1 位小数），&lt;=0 时省略，
         * 避免百 KB 级 jar 被截断成 "0 MB"。两者都缺省时返回空串。</p>
         *
         * @param modifiedTime 文件最后修改时间（epoch 毫秒），0 表示未知
         * @param sizeBytes    文件大小（字节），&lt;=0 表示未知
         * @return 形如 "2026-05-28 14:30 · 1.8 MB" 的展示文本；无可展示信息时为空串
         * @author xumanyi
         * @date 2026-05-28
         */
        private static String formatPackageMeta(long modifiedTime, long sizeBytes) {
            String timeStr = "";
            if (modifiedTime > 0) {
                timeStr = META_TIME_FMT.format(
                        Instant.ofEpochMilli(modifiedTime).atZone(ZoneId.systemDefault()));
            }
            String sizeStr = "";
            if (sizeBytes > 0) {
                sizeStr = sizeBytes < 1024 * 1024
                        ? String.format("%.1f KB", sizeBytes / 1024.0)
                        : String.format("%.1f MB", sizeBytes / (1024.0 * 1024.0));
            }
            if (!timeStr.isEmpty() && !sizeStr.isEmpty()) {
                return timeStr + " · " + sizeStr;
            }
            return timeStr + sizeStr;
        }
    }

    // ==================== 子目录层级筛选（动态多层下拉） ====================

    /**
     * 拼接当前 narrowed 的 FTP 扫描路径
     *
     * <p>基础路径为 {@code /开发/{项目}/{系统}/}，遇到任何未选中的层级即停止追加，
     * 保证返回的总是一个合法的 FTP 目录路径。</p>
     *
     * @param projectName 项目名（不含分隔符）
     * @param systemName  系统名（不含分隔符）
     * @return 以 "/" 结尾的完整 FTP 目录路径
     * @author xumanyi
     * @date 2026-04-30
     */
    private String buildScanPath(String projectName, String systemName) {
        StringBuilder sb = new StringBuilder("/开发/")
                .append(projectName).append("/")
                .append(systemName).append("/");
        for (SubdirLevel lvl : extraLevels) {
            if (lvl.selectedSubdir == null || lvl.selectedSubdir.isEmpty()) {
                break;
            }
            sb.append(lvl.selectedSubdir).append("/");
        }
        return sb.toString();
    }

    /**
     * 清空所有已添加的子目录层级，并从 UI 移除对应组件。
     *
     * <p>用于：项目切换、系统切换、注销、刷新——这些场景下旧的层级路径已失效。</p>
     *
     * @author xumanyi
     * @date 2026-04-30
     */
    private void clearExtraLevels() {
        for (SubdirLevel lvl : extraLevels) {
            topPanel.remove(lvl.labelComp);
            topPanel.remove(lvl.combo);
            topPanel.remove(lvl.removeBtnComp);
        }
        extraLevels.clear();
        topPanel.revalidate();
        topPanel.repaint();
    }

    /**
     * 修剪 fromIndex 及之后的所有子目录层级（含 fromIndex 本身）
     *
     * <p>用于：</p>
     * <ul>
     *   <li>用户在某层切换 combo → 该层下方的所有层级失效（fromIndex = 该层 + 1）</li>
     *   <li>用户点 "−" 删除某层 → 该层及下方的所有层级删除（fromIndex = 该层）</li>
     * </ul>
     *
     * @param fromIndex 起始索引（含），范围 [0, extraLevels.size()]
     * @author xumanyi
     * @date 2026-04-30
     */
    private void trimExtraLevelsFrom(int fromIndex) {
        if (fromIndex < 0) fromIndex = 0;
        while (extraLevels.size() > fromIndex) {
            SubdirLevel removed = extraLevels.remove(extraLevels.size() - 1);
            topPanel.remove(removed.labelComp);
            topPanel.remove(removed.combo);
            topPanel.remove(removed.removeBtnComp);
        }
        topPanel.revalidate();
        topPanel.repaint();
    }

    /**
     * 处理 "+" 按钮点击：异步查询当前最深路径下的子目录列表，回到 EDT 追加一行筛选下拉
     *
     * <p>边界情况：</p>
     * <ul>
     *   <li>未选项目 / 系统：静默返回（按钮按理也不会被点到，但兜底）</li>
     *   <li>查询到子目录为空：弹提示"无可用子目录"，不追加行</li>
     *   <li>FTP 连接已断：尝试重连一次，失败则更新连接状态</li>
     * </ul>
     *
     * @author xumanyi
     * @date 2026-04-30
     */
    private void addSubdirLevel() {
        if (selectedProject == null) return;
        String sys = (String) systemCombo.getSelectedItem();
        if (sys == null) return;

        final String parentPath = buildScanPath(selectedProject, sys);
        showLoading("加载子目录列表...");
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                List<String> subdirs;
                synchronized (ftpLock) {
                    if (browseService == null || !browseService.isConnected()) {
                        reconnectFtp();
                    }
                    if (browseService == null) {
                        SwingUtilities.invokeLater(() ->
                                setLoginFailed("连接已断开，无法加载子目录"));
                        return;
                    }
                    try {
                        subdirs = browseService.listSubdirectories(parentPath);
                    } catch (IOException retry1) {
                        // 连接可能超时，重连一次
                        reconnectFtp();
                        if (browseService == null) {
                            SwingUtilities.invokeLater(() ->
                                    setLoginFailed("连接已断开，无法加载子目录"));
                            return;
                        }
                        subdirs = browseService.listSubdirectories(parentPath);
                    }
                }
                final List<String> finalSubdirs = subdirs;
                SwingUtilities.invokeLater(() -> {
                    if (finalSubdirs.isEmpty()) {
                        JOptionPane.showMessageDialog(TargetSectionPanel.this,
                                "当前路径下无子目录，无需进一步缩小范围",
                                "提示", JOptionPane.INFORMATION_MESSAGE);
                        return;
                    }
                    appendSubdirLevelRow(finalSubdirs);
                });
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() ->
                        setLoginFailed("加载子目录失败：" + ex.getMessage()));
            } finally {
                SwingUtilities.invokeLater(this::hideLoading);
            }
        });
    }

    /**
     * 在 EDT 上把一行子目录下拉追加到外层 GridBag 布局中
     *
     * <p>新行使用外层 GridBag 的 gridy = {@code 4 + extraLevels.size()}，
     * 与 "项目：" / "系统：" 共用同一组列宽，自动对齐。</p>
     *
     * <p>新行默认选中第一项 → 立即触发一次 {@link #triggerPackageReload()} 把包列表收窄。</p>
     *
     * @param subdirs 当前路径下的子目录候选（非空，已排序）
     * @author xumanyi
     * @date 2026-04-30
     */
    private void appendSubdirLevelRow(List<String> subdirs) {
        JComboBox<String> combo = new JComboBox<>();
        for (String s : subdirs) combo.addItem(s);

        // 该层级在 extraLevels 中的最终位置（提前捕获，供两个 listener 共用）。
        // 修剪只从尾部开始，所以本层一旦被裁掉自身的 listener 也不会再触发，索引保持稳定。
        final int levelIndex = extraLevels.size();

        JButton removeBtn = createCompactActionButton(
                com.intellij.icons.AllIcons.General.Remove, "移除子目录筛选",
                e -> {
                    trimExtraLevelsFrom(levelIndex);
                    triggerPackageReload();
                });

        JBLabel label = new JBLabel("子目录：");

        // 三列布局，topPanel 内部 GridBag 直接管理：label/combo/btn 与 项目/系统 行共用同列宽，
        // combo 右边界对齐到 col 1 右边、"−" 按钮独占 col 2
        // gridy 4..99 是预留的动态层级空间；从 4 开始按层级递增
        int rowGridy = 4 + levelIndex;
        GridBagConstraints lblGbc = new GridBagConstraints();
        lblGbc.insets = new Insets(3, 2, 3, 2);
        lblGbc.anchor = GridBagConstraints.WEST;
        lblGbc.gridx = 0; lblGbc.gridy = rowGridy;
        lblGbc.fill = GridBagConstraints.NONE; lblGbc.weightx = 0;
        topPanel.add(label, lblGbc);

        GridBagConstraints comboGbc = new GridBagConstraints();
        comboGbc.insets = new Insets(3, 2, 3, 2);
        comboGbc.anchor = GridBagConstraints.WEST;
        comboGbc.gridx = 1; comboGbc.gridy = rowGridy;
        comboGbc.fill = GridBagConstraints.HORIZONTAL; comboGbc.weightx = 1.0;
        topPanel.add(combo, comboGbc);

        GridBagConstraints btnGbc = new GridBagConstraints();
        btnGbc.insets = new Insets(3, 2, 3, 2);
        btnGbc.anchor = GridBagConstraints.WEST;
        btnGbc.gridx = 2; btnGbc.gridy = rowGridy;
        btnGbc.fill = GridBagConstraints.NONE; btnGbc.weightx = 0;
        topPanel.add(removeBtn, btnGbc);

        SubdirLevel level = new SubdirLevel(label, combo, removeBtn);
        // 默认选中第一项；先记录状态，再注册 listener，避免初始化触发的 ActionEvent 走刷新
        level.selectedSubdir = subdirs.get(0);
        combo.setSelectedIndex(0);

        extraLevels.add(level);

        combo.addActionListener(e -> {
            if (refreshing) return;
            String picked = (String) combo.getSelectedItem();
            if (picked == null) return;
            if (picked.equals(level.selectedSubdir)) return;
            level.selectedSubdir = picked;
            // 当前层切换 → 下方所有更深层失效
            trimExtraLevelsFrom(levelIndex + 1);
            triggerPackageReload();
        });

        topPanel.revalidate();
        topPanel.repaint();

        // 默认选中第一项相当于已收窄一层，立即刷新包列表
        triggerPackageReload();
    }

    /**
     * 用当前完整 narrowed 路径重新触发目标包加载。
     *
     * <p>调用前置条件由 {@link #loadTargetPackages(String, String)} 守护，
     * 此处仅做 null 检查后转发。</p>
     *
     * @author xumanyi
     * @date 2026-04-30
     */
    private void triggerPackageReload() {
        String sys = (String) systemCombo.getSelectedItem();
        if (selectedProject == null || sys == null || browseService == null) return;
        loadTargetPackages(selectedProject, sys);
    }

    /**
     * 子目录层级状态：组件引用 + 当前选中项
     *
     * <p>持有 label / combo / removeBtn 三个外层 GridBag 直接子组件的引用，便于精确移除。</p>
     */
    private static class SubdirLevel {
        final JComponent labelComp;
        final JComboBox<String> combo;
        final JComponent removeBtnComp;
        /** 当前 combo 选中的子目录名；null 表示未选 */
        String selectedSubdir;

        SubdirLevel(JComponent labelComp, JComboBox<String> combo, JComponent removeBtnComp) {
            this.labelComp = labelComp;
            this.combo = combo;
            this.removeBtnComp = removeBtnComp;
        }
    }
}
