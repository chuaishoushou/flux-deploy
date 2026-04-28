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
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBPanel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.util.ui.AsyncProcessIcon;

import javax.swing.*;
import javax.swing.tree.DefaultTreeModel;
import java.awt.*;
import java.io.IOException;
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
    private JButton projectSelectButton;
    private String selectedProject;
    private final JComboBox<String> systemCombo;
    private final JButton connectButton;
    private final JButton switchAccountButton;
    private final JButton logoutButton;
    private final JButton refreshButton;

    // 目标包树形勾选
    private CheckboxTree packageTree;
    private CheckedTreeNode packageTreeRoot;
    private JBLabel selectionSummary;
    /** 目标包区域容器（树 + 全选按钮），无数据时隐藏 */
    private JPanel packagePanel;
    private JBScrollPane treeScroll;

    /** 目标包树当前鼠标悬浮行（-1 为无） */
    private int packageTreeHoverRow = -1;

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
        super(new GridBagLayout());
        this.project = project;

        this.connectionStatus = new JBLabel("未连接");
        this.connectionStatus.setForeground(Color.RED);
        this.connectButton = new JButton("连接");
        connectButton.setToolTipText("连接到 FTP 服务器（使用已保存凭据或弹出登录框）");
        this.switchAccountButton = new JButton("账号 ▾");
        switchAccountButton.setToolTipText("切换 / 删除 / 添加已保存账号");
        switchAccountButton.setMargin(new Insets(2, 8, 2, 8));
        this.logoutButton = new JButton("注销");
        logoutButton.setToolTipText("断开当前连接（不删除账号）");
        logoutButton.setVisible(false);
        // 纯图标按钮：无边框、无填充背景、无边距、不可聚焦
        this.refreshButton = new JButton(com.intellij.icons.AllIcons.Actions.Refresh);
        refreshButton.setToolTipText("重连 FTP 并刷新项目 / 系统 / 目标包列表");
        refreshButton.setMargin(JBUI.emptyInsets());
        refreshButton.setBorderPainted(false);
        refreshButton.setContentAreaFilled(false);
        refreshButton.setFocusable(false);
        refreshButton.setFocusPainted(false);
        refreshButton.setVisible(false);
        this.systemCombo = new JComboBox<>();
        systemCombo.setToolTipText("选择系统，对应 /开发/{项目}/{系统}/ 下的目标包");

        this.packageTreeRoot = new CheckedTreeNode("目标包");
        this.packageTree = new CheckboxTree(new PackageTreeRenderer(), packageTreeRoot) {
            // 重写：点击行任意位置即可切换 checkbox，无需精确点击 checkbox 区域
            @Override
            protected void onNodeStateChanged(CheckedTreeNode node) {
                super.onNodeStateChanged(node);
                // 主目标默认勾选，但允许用户取消（部分客户备份目录命名不规范，无法靠关键字过滤）
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

        initUI();
        initListeners();
        tryAutoConnect();
    }

    /** 初始化 UI 布局：FTP 连接状态、项目/系统下拉、目标包树和选择摘要 */
    private void initUI() {
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(3, 2, 3, 2);
        gbc.anchor = GridBagConstraints.WEST;

        // FTP 状态：单行布局
        //   [FTP：]  状态标签（填满剩余宽度）  [⋯ 更多操作]  [连接](仅断开时)
        gbc.gridx = 0; gbc.gridy = 0;
        add(new JBLabel("FTP："), gbc);

        JPanel statusRow = new JPanel(new BorderLayout(6, 0));
        connectionStatus.putClientProperty("html.disable", Boolean.TRUE);
        statusRow.add(connectionStatus, BorderLayout.CENTER);

        // 更多操作按钮（切换账号 / 注销 收到这里；刷新已独立为行内图标按钮）
        // 纯图标按钮：无边框、无填充背景、无边距、不可聚焦
        JButton moreButton = new JButton(com.intellij.icons.AllIcons.Actions.More);
        moreButton.setToolTipText("更多操作：切换账号 / 注销");
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
        rightBox.add(refreshButton);
        rightBox.add(moreButton);
        // connectButton 仅在未连接时可见，位于 ⋯ 右侧以便快速建立连接
        rightBox.add(connectButton);
        statusRow.add(rightBox, BorderLayout.EAST);

        gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0;
        add(statusRow, gbc);

        // 项目选择按钮（点击弹出搜索+列表）
        gbc.gridx = 0; gbc.gridy = 2; gbc.fill = GridBagConstraints.NONE; gbc.weightx = 0;
        add(new JBLabel("项目："), gbc);
        gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0;
        projectSelectButton = new JButton("请选择项目 ▾");
        projectSelectButton.setHorizontalAlignment(SwingConstants.LEFT);
        projectSelectButton.setFocusable(false);
        projectSelectButton.setToolTipText("选择客户项目（对应 FTP 根目录下 /开发/{项目}/），支持搜索");
        add(projectSelectButton, gbc);

        // 系统下拉
        gbc.gridx = 0; gbc.gridy = 3; gbc.fill = GridBagConstraints.NONE; gbc.weightx = 0;
        add(new JBLabel("系统："), gbc);
        gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0;
        add(systemCombo, gbc);

        // 加载中状态条（一行：旋转图标 + 描述文案），默认隐藏，FTP 请求期间显示
        loadingBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        loadingIcon = new AsyncProcessIcon("TargetSectionPanelLoading");
        loadingLabel = new JBLabel("加载中...");
        loadingLabel.setForeground(Color.GRAY);
        loadingBar.add(loadingIcon);
        loadingBar.add(loadingLabel);
        loadingBar.setVisible(false);
        gbc.gridx = 0; gbc.gridy = 4; gbc.gridwidth = 2;
        gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0; gbc.weighty = 0;
        add(loadingBar, gbc);
        gbc.gridwidth = 1; // 恢复默认，下面保持原有布局语义

        // 目标包区域（树 + 摘要，打包为一个可隐藏的容器）
        packagePanel = new JPanel(new BorderLayout(0, 0));

        treeScroll = new JBScrollPane(packageTree);
        packagePanel.add(treeScroll, BorderLayout.CENTER);

        selectionSummary.setForeground(Color.GRAY);
        selectionSummary.setBorder(JBUI.Borders.emptyTop(2));
        packagePanel.add(selectionSummary, BorderLayout.SOUTH);

        // 初始隐藏
        packagePanel.setVisible(false);

        gbc.gridx = 0; gbc.gridy = 5; gbc.gridwidth = 2;
        gbc.fill = GridBagConstraints.BOTH; gbc.weightx = 1.0; gbc.weighty = 1.0;
        add(packagePanel, gbc);

        // 底部 spacer：packagePanel 隐藏时吸收空间，保持内容顶部对齐
        gbc.gridy = 6; gbc.weighty = 0.01;
        add(Box.createGlue(), gbc);
    }

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

    /** 初始化事件监听：连接、注销、项目搜索、项目/系统级联选择、刷新、全选 WAR */
    private void initListeners() {
        connectButton.addActionListener(e -> connect());

        // 项目选择按钮：弹出搜索+列表
        projectSelectButton.addActionListener(e -> showProjectSearchPopup());

        // 注销：仅断开当前连接，不删除已保存凭据（删除由"账号 ▾"列表内的 × 按钮负责）
        logoutButton.addActionListener(e -> doLogout(false));

        // 账号切换按钮：弹出已保存账号列表（含切换/删除/添加）
        // 注：switchAccountButton 当前已不再加入界面，此 listener 为兼容保留，实际点击入口走 "⋯ 更多" 菜单
        switchAccountButton.addActionListener(e -> showAccountSwitcherPopup(switchAccountButton));

        // projectCombo 已替换为 projectSelectButton，项目选择通过弹窗回调触发 loadSystems

        systemCombo.addActionListener(e -> {
            if (refreshing) return;
            String proj = selectedProject;
            String sys = (String) systemCombo.getSelectedItem();
            if (proj != null && sys != null && browseService != null) {
                loadTargetPackages(proj, sys);
            }
        });

        // 刷新按钮：重连 FTP + 刷新项目/系统/目标包列表
        refreshButton.addActionListener(e -> {
            if (connectedHost == null) return;

            String prevProj = selectedProject;
            String prevSys = (String) systemCombo.getSelectedItem();

            refreshButton.setEnabled(false);
            refreshing = true;

            // 先清空目标包列表，显示刷新中
            currentPackages = List.of();
            rebuildPackageTree();
            selectionSummary.setText("正在刷新...");

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
                            projectSelectButton.setText(prevProj + " ▾");
                        } else {
                            selectedProject = null;
                            projectSelectButton.setText("请选择项目 ▾");
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
                    });
                    } // end synchronized(ftpLock)
                } catch (Exception ex) {
                    SwingUtilities.invokeLater(() -> {
                        refreshing = false;
                        connectionStatus.setText("刷新失败: " + ex.getMessage());
                        connectionStatus.setForeground(Color.RED);
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
     * 构建目标包树
     */
    private void rebuildPackageTree() {
        packageTreeRoot = new CheckedTreeNode("目标包");

        if (currentPackages.isEmpty()) {
            DefaultTreeModel model = new DefaultTreeModel(packageTreeRoot);
            packageTree.setModel(model);
            packageTree.setRootVisible(false);
            packagePanel.setVisible(false);
            updateSelectionSummary();
            revalidate();
            repaint();
            return;
        }

        // 按子目录分组
        Map<String, List<PackageInfo>> grouped = new LinkedHashMap<>();
        for (PackageInfo pkg : currentPackages) {
            grouped.computeIfAbsent(pkg.getSubDirectory(), k -> new ArrayList<>()).add(pkg);
        }

        String artifactPrefix = extractArtifactPrefix(sourceArtifactName);

        for (Map.Entry<String, List<PackageInfo>> entry : grouped.entrySet()) {
            // "." 表示包直接在当前目录下（无子目录层级）
            String subDir = entry.getKey();
            String displayDir = ".".equals(subDir) ? "(当前目录)" : subDir;
            List<PackageInfo> packages = entry.getValue();

            CheckedTreeNode dirNode = new CheckedTreeNode(displayDir);
            boolean hasPkg = false;

            for (PackageInfo pkg : packages) {
                // 匹配规则：完整文件名匹配（含版本号），不同版本不自动锁定
                boolean isMatch = sourceArtifactName != null
                        && isExactArtifactMatch(pkg.getPackageName(), sourceArtifactName);
                boolean locked = false;
                boolean checked = false;
                boolean visible = true;

                if ("JAR".equals(sourceArtifactType)) {
                    // JAR 源：精确匹配的 JAR 锁定 + 所有 WAR 可选（嵌入更新）
                    if ("JAR".equals(pkg.getType()) && isMatch) {
                        locked = true;
                        checked = true;
                    } else if ("WAR".equals(pkg.getType())) {
                        checked = false;
                    } else {
                        visible = false;
                    }
                } else if ("WAR".equals(sourceArtifactType)) {
                    // WAR 源：只显示精确匹配的 WAR，其他全部隐藏
                    if ("WAR".equals(pkg.getType()) && isMatch) {
                        locked = true;
                        checked = true;
                    } else {
                        visible = false;
                    }
                } else {
                    checked = false;
                }

                if (visible) {
                    PackageNodeData data = new PackageNodeData(pkg, locked);
                    CheckedTreeNode pkgNode = new CheckedTreeNode(data);
                    pkgNode.setChecked(checked);
                    dirNode.add(pkgNode);
                    hasPkg = true;
                }
            }

            if (hasPkg) {
                // 目录始终可点，便于批量勾选/取消（含主目标的目录也允许用户调整）
                packageTreeRoot.add(dirNode);
            }
        }

        DefaultTreeModel model = new DefaultTreeModel(packageTreeRoot);
        packageTree.setModel(model);
        packageTree.setRootVisible(false);

        // 展开所有
        for (int i = 0; i < packageTree.getRowCount(); i++) {
            packageTree.expandRow(i);
        }

        packagePanel.setVisible(true);
        updateSelectionSummary();
        revalidate();
        repaint();
    }

    /**
     * 更新选择摘要
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
    }

    /**
     * 获取选中的所有目标包数据
     */
    private List<PackageNodeData> getSelectedPackages() {
        List<PackageNodeData> result = new ArrayList<>();
        collectChecked(packageTreeRoot, result);
        return result;
    }

    /** 递归收集选中的包节点 */
    private void collectChecked(CheckedTreeNode node, List<PackageNodeData> result) {
        if (node.getUserObject() instanceof PackageNodeData data) {
            if (node.isChecked()) {
                result.add(data);
            }
            return;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            if (node.getChildAt(i) instanceof CheckedTreeNode child) {
                collectChecked(child, result);
            }
        }
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

        Window owner = SwingUtilities.getWindowAncestor(projectSelectButton);
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
                Math.max(projectSelectButton.getWidth(), 200),
                Math.min(300, allProjects.size() * 24 + 10)));
        dialog.add(scrollPane, BorderLayout.CENTER);

        // 实时过滤
        searchField.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            @Override public void insertUpdate(javax.swing.event.DocumentEvent e) { filter(); }
            @Override public void removeUpdate(javax.swing.event.DocumentEvent e) { filter(); }
            @Override public void changedUpdate(javax.swing.event.DocumentEvent e) { filter(); }
            private void filter() {
                String kw = searchField.getText().trim().toLowerCase();
                listModel.clear();
                for (String p : allProjects) {
                    if (kw.isEmpty() || p.toLowerCase().contains(kw)) {
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
                projectSelectButton.setText(sel + " ▾");
                forceCloseProjectPopup();
                if (browseService != null) loadSystems(sel);
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
        Point loc = projectSelectButton.getLocationOnScreen();
        dialog.setLocation(loc.x, loc.y + projectSelectButton.getHeight());
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
                                    projectSelectButton.getLocationOnScreen(),
                                    projectSelectButton.getSize());
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
            connectionStatus.setText("未连接");
            connectionStatus.setForeground(Color.RED);
            connectButton.setVisible(true);
            logoutButton.setVisible(false);
            refreshButton.setVisible(false);
        }
        allProjects = List.of();
        selectedProject = null;
        projectSelectButton.setText("请选择项目 ▾");
        refreshing = true;
        systemCombo.removeAllItems();
        refreshing = false;
        clearPackages();
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
        deleteBtn.setToolTipText("删除此账号" + (isCurrent ? "（同时断开当前连接）" : ""));
        deleteBtn.addActionListener(e -> {
            int confirm = JOptionPane.showConfirmDialog(parent,
                    "确认删除账号 " + acc.getUsername() + "@" + acc.getHost() + "？\n"
                            + (isCurrent ? "当前已连接的账号将被断开。" : ""),
                    "删除账号", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
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
        connectionStatus.setText("正在切换至 " + acc.getUsername() + "...");
        connectionStatus.setForeground(Color.ORANGE);
        doConnect(acc.getHost(), acc.getPort(), acc.getUsername(), acc.getPassword());
    }

    /**
     * 完全重置目标区（项目/系统/目标包全部清空，保留 FTP 连接）
     */
    public void resetAll() {
        selectedProject = null;
        projectSelectButton.setText("请选择项目 ▾");
        refreshing = true;
        systemCombo.removeAllItems();
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

    /** 尝试使用缓存凭据自动连接 FTP */
    private void tryAutoConnect() {
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            CredentialCache.CachedCredential cached = CredentialBridge.loadCachedCredential();
            if (cached != null) {
                SwingUtilities.invokeLater(() -> {
                    connectionStatus.setText("正在连接...");
                    connectionStatus.setForeground(Color.ORANGE);
                });
                doConnect(cached.getHost(), cached.getPort(),
                        cached.getUsername(), cached.getPassword());
            }
        });
    }

    /** 执行 FTP 连接：优先使用缓存凭据，无缓存时弹出登录对话框 */
    private void connect() {
        CredentialCache.CachedCredential cached = CredentialBridge.loadCachedCredential();
        if (cached != null) {
            connectionStatus.setText("正在连接...");
            connectionStatus.setForeground(Color.ORANGE);
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
            connectionStatus.setText("正在连接...");
            connectionStatus.setForeground(Color.ORANGE);
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
                        connectionStatus.setText(username + "@" + host + ":" + port + " 已连接");
                        connectionStatus.setForeground(new Color(0, 128, 0));
                        connectButton.setVisible(false);
                        logoutButton.setVisible(true);
                        refreshButton.setVisible(true);
                        allProjects = new ArrayList<>(projects);
                        selectedProject = null;
                        projectSelectButton.setText("请选择项目 ▾");
                    });
                } catch (Exception ex) {
                    SwingUtilities.invokeLater(() -> {
                        connectionStatus.setText("连接失败: " + ex.getMessage());
                        connectionStatus.setForeground(Color.RED);
                    });
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
                        connectionStatus.setText(username + "@" + host + ":" + port + " 已连接");
                        connectionStatus.setForeground(new Color(0, 128, 0));
                        connectButton.setVisible(false);
                        logoutButton.setVisible(true);
                        refreshButton.setVisible(true);
                        allProjects = new ArrayList<>(projects);
                        selectedProject = null;
                        projectSelectButton.setText("请选择项目 ▾");
                    });
                } catch (Exception ex) {
                    SwingUtilities.invokeLater(() -> {
                        connectionStatus.setText("连接失败: " + ex.getMessage());
                        connectionStatus.setForeground(Color.RED);
                    });
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
                                for (String s : systems) systemCombo.addItem(s);
                                systemCombo.setSelectedIndex(-1);
                                refreshing = false;
                            });
                            return;
                        }
                    } catch (Exception ignored) {}
                    SwingUtilities.invokeLater(() ->
                            connectionStatus.setText("加载失败: " + ex.getMessage()));
                }
            }
            } finally {
                SwingUtilities.invokeLater(this::hideLoading);
            }
        });
    }

    /** 加载指定项目和系统下的目标包列表，连接超时时自动重连 */
    private void loadTargetPackages(String projectName, String systemName) {
        showLoading("加载目标包列表...");
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
            synchronized (ftpLock) {
            try {
                // 检查连接是否有效，无效则重连
                if (browseService == null || !browseService.isConnected()) {
                    reconnectFtp();
                }
                if (browseService == null) return;

                List<PackageInfo> packages = browseService.scanPackagesStructured(
                        "/开发/" + projectName + "/" + systemName + "/");
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
                        List<PackageInfo> packages = browseService.scanPackagesStructured(
                                "/开发/" + projectName + "/" + systemName + "/");
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
                        connectionStatus.setText("加载失败: " + ex.getMessage()));
            }
            } // end synchronized(ftpLock)
            } finally {
                SwingUtilities.invokeLater(this::hideLoading);
            }
        });
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
                    String typeTag = " [" + data.info.getType() + "]";
                    String sizeStr = data.info.getSize() > 0
                            ? " (" + (data.info.getSize() / 1024 / 1024) + " MB)" : "";

                    // 主目标加粗显示，便于识别；checkbox 不再置灰，允许用户取消
                    if (data.locked) {
                        getTextRenderer().append(data.info.getPackageName(),
                                SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES);
                    } else {
                        getTextRenderer().append(data.info.getPackageName(),
                                SimpleTextAttributes.REGULAR_ATTRIBUTES);
                    }
                    getTextRenderer().append(typeTag,
                            new SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN,
                                    Color.GRAY));
                    getTextRenderer().append(sizeStr,
                            new SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN,
                                    Color.GRAY));
                } else if (userObj instanceof String dirName) {
                    // 目录节点
                    getTextRenderer().append(dirName,
                            new SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, null));
                }
            }
        }
    }
}
