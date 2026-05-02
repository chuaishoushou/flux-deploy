package com.flux.deploy.plugin.toolwindow;

import com.flux.deploy.plugin.model.DeployMode;
import com.flux.deploy.plugin.model.DeployTargetMode;
import com.flux.deploy.plugin.model.FtpTargetSelection;
import com.flux.deploy.plugin.model.LocalTargetSelection;
import com.flux.deploy.plugin.model.PluginDeployConfig;
import com.flux.deploy.plugin.service.DeployExecutionService;
import com.flux.deploy.plugin.service.GitChangeDetector;
import com.flux.deploy.plugin.service.LocalPackagePatchService;
import com.flux.deploy.plugin.service.MavenArtifactResolver;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.JBColor;
import com.intellij.ui.JBSplitter;
import com.intellij.ui.OnePixelSplitter;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBPanel;
import com.intellij.util.ui.JBUI;

import javax.swing.*;
import javax.swing.text.JTextComponent;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.Desktop;
import java.awt.event.AWTEventListener;
import java.awt.event.MouseEvent;
import java.io.File;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * FLUX 客服更新主面板
 *
 * <p>上下分割：表单区（源+目标+信息+按钮） | 日志区。
 * 目标区通过 Tab 页承载 FTP 模式与本地模式，执行按钮组与流程链路随模式切换。</p>
 *
 * @author xumanyi
 * @date 2026-03-27
 */
public class DeployToolWindowPanel extends JBPanel<DeployToolWindowPanel> {

    /** FTP 模式按钮卡片键 */
    private static final String CARD_FTP = "ftp";
    /** 本地模式按钮卡片键 */
    private static final String CARD_LOCAL = "local";
    /** 主面板卡片键：部署视图 */
    private static final String ROOT_DEPLOY = "deploy";
    /** 主面板卡片键：文档视图 */
    private static final String ROOT_DOCS = "docs";
    /** 右列初始分割比例：目标区占比 */
    private static final float RIGHT_SPLIT_DEFAULT_PROPORTION = 0.66f;
    /** 日志关闭时目标区占比（留极窄条给折叠标题） */
    private static final float RIGHT_SPLIT_LOG_CLOSED_PROPORTION = 0.97f;

    /** 根卡片布局：在部署面板与文档面板之间切换 */
    private CardLayout rootCardLayout;
    private JPanel rootCards;
    private DocsPanel docsPanel;
    /** 当前展示的文档 key；null 表示正在显示部署面板。用于图标按钮二次点击切回 */
    private String currentDocKey;

    private final Project project;
    private final SourceSectionPanel sourceSection;
    private final TargetContainerPanel targetContainer;
    private final TargetSectionPanel targetSection;
    private final InfoSectionPanel infoSection;
    private final LogSectionPanel logSection;

    // FTP 模式按钮
    private final JButton preCheckButton;
    private final JButton deployButton;
    private final JButton localOnlyButton;
    private final JButton rollbackButton;
    private final JButton resetButton;

    // 本地模式按钮
    private final JButton localBuildButton;
    private final JButton localResetButton;

    // 备份选项
    private final JCheckBox backupCheckBox;

    /** 右侧目标区 / 日志区分割器 */
    private JBSplitter rightSplit;
    /** 日志整体容器（上方横条 + 下方日志卡片），关闭后可重新挂回右侧分割器 */
    private JPanel logCard;
    /** 日志关闭后的恢复入口 */
    private JPanel logClosedBar;
    /** 日志是否处于关闭状态 */
    private boolean logClosed = false;
    /** 日志关闭前的右列比例，用于恢复时还原高度 */
    private float rightSplitRestoreProportion = RIGHT_SPLIT_DEFAULT_PROPORTION;

    /** 部署视图根容器，用于日志全屏切换时替换内容 */
    private JPanel deployCard;
    /** 部署主分割器（左列｜右列），全屏退出时还原回 deployCard */
    private OnePixelSplitter mainSplit;
    /** 日志是否处于插件级全屏状态（占满整个 deployCard） */
    private boolean logFullscreen = false;
    /** 全屏 / 取消全屏切换按钮 */
    private JButton fullscreenToggleButton;

    /** 模式感知的按钮卡片容器 */
    private JPanel buttonsCard;
    private CardLayout buttonsCardLayout;

    private String currentModulePath;
    private String currentArtifactFileName;
    /**
     * 上一次发射出去的 {@code [提示] ...} 内容，用于在 {@link #onModeChanged} 被相邻多次触发
     * 但状态完全相同（如 IDEA VFS 双 refresh）时跳过重复输出，避免日志面板出现一字不差的两行。
     * 切换模块或文件状态发生实际变化时，提示文本会变，dedup 不会误屏蔽。
     */
    private String lastModeHint = "";
    /**
     * 上一次 onModeChanged 实际加载文件列表时所对应的模块路径。
     * 用于判断切模块场景：模块变了就不应保留旧模块的勾选项。
     */
    private String lastFilesLoadedForModule = null;

    /** 是否有已完成的部署 */
    private boolean hasDeployed = false;
    /** 是否已执行过预检 */
    private boolean hasPreChecked = false;

    /** 「打包并上传」按钮的运行态：IDLE 触发部署 / RUNNING 触发停止弹窗 / STOPPING 禁用等待 */
    private enum DeployButtonState { IDLE, RUNNING, STOPPING }
    private DeployButtonState deployButtonState = DeployButtonState.IDLE;
    /** 缓存「打包并上传」按钮的原色（accentBlue），STOPPING / IDLE 切换时复用 */
    private Color deployButtonIdleColor;
    /** 用户在冲突对话框里选择的策略，仅在本轮部署生效（单次消费）；无冲突时保持默认 OVERWRITE */
    private com.flux.deploy.plugin.model.BackupConflictStrategy pendingBackupStrategy
            = com.flux.deploy.plugin.model.BackupConflictStrategy.OVERWRITE;

    public DeployToolWindowPanel(Project project) {
        super(new BorderLayout());
        this.project = project;

        this.backupCheckBox = new JCheckBox("执行备份", true);
        this.sourceSection = new SourceSectionPanel(project);
        this.targetContainer = new TargetContainerPanel(project);
        this.targetSection = targetContainer.getFtpPanel();
        this.infoSection = new InfoSectionPanel(project, backupCheckBox);
        this.logSection = new LogSectionPanel();

        this.preCheckButton = new JButton("预检");
        this.deployButton = new JButton("打包并上传");
        this.localOnlyButton = new JButton("打包不上传");
        this.rollbackButton = new JButton("回滚");
        this.resetButton = new JButton("重置");

        this.localBuildButton = new JButton("本地打包");
        this.localResetButton = new JButton("重置");

        rollbackButton.setEnabled(false);

        sourceSection.setModuleSelectedCallback(this::onModuleSelected);
        sourceSection.setModeChangeCallback(this::onModeChanged);
        targetContainer.setModeChangeCallback(this::onTargetModeChanged);
        // 本地面板读取源面板已勾选文件数
        targetContainer.getLocalPanel().setFileCountSupplier(
                () -> sourceSection.getSelectedFiles().size());

        // 桥接 InfoSectionPanel 与 TargetSectionPanel 之间的 FTP 上下文（用于"备份至"行）
        this.infoSection.setFtpContextSupplier(new InfoSectionPanel.FtpContextSupplier() {
            @Override public boolean isFtpConnected() { return targetSection.isFtpConnected(); }
            @Override public String getHost() { return targetSection.getConnectedHost(); }
            @Override public int getPort() { return targetSection.getConnectedPort(); }
            @Override public String getUsername() { return targetSection.getConnectedUsername(); }
            @Override public String getPassword() { return targetSection.getConnectedPassword(); }
            @Override public String getContextDir() { return targetSection.getCurrentContextDir(); }
            @Override public String getFirstTargetRemoteDir() {
                java.util.List<com.flux.deploy.plugin.model.FtpTargetSelection> mts = targetSection.getMainTargets();
                if (mts != null && !mts.isEmpty()) {
                    return mts.get(0).getRemoteDir();
                }
                return null;
            }
        });
        // 项目/系统/连接变化时刷新"备份至"行
        this.targetSection.setContextChangeCallback(() -> SwingUtilities.invokeLater(
                this.infoSection::refreshBackupLocationLabel));

        initUI();
        initListeners();

        // 初始按其默认模式（FTP）刷新一次按钮启用态与信息区
        onTargetModeChanged(targetContainer.getCurrentMode());

        // 点击输入框外任何非文本区域时自动取消输入框焦点
        installClickOutsideFocusDrop();

        // 预热文档面板：后台创建 DocsPanel + 初始化 JCEF 浏览器，承担首次 ~1s 内核启动开销，
        // 让用户首次点击"使用手册 / 版本记录"时秒开，不用再等一秒。
        // 用 invokeLater 延后到 EDT 空闲时执行，不阻塞本 ToolWindow 的首次显示。
        SwingUtilities.invokeLater(() -> {
            if (docsPanel == null) {
                docsPanel = new DocsPanel(this::showMain);
                rootCards.add(docsPanel, ROOT_DOCS);
            }
            docsPanel.prewarm();
        });
    }

    private void initUI() {
        setMinimumSize(new Dimension(520, 400));

        // ═══ 执行卡片（按内容自适应高度，固定在右列底部） ═══
        JPanel execCard = new JPanel(new BorderLayout(0, 4));
        execCard.setBorder(BorderFactory.createCompoundBorder(
                new RoundedBorder(8), JBUI.Borders.empty(4, 8)));

        JBLabel execTitle = new JBLabel("执行操作");
        styleCardTitle(execTitle);
        execCard.add(execTitle, BorderLayout.NORTH);

        JPanel execContent = new JPanel();
        execContent.setLayout(new BoxLayout(execContent, BoxLayout.Y_AXIS));

        infoSection.setAlignmentX(Component.LEFT_ALIGNMENT);
        execContent.add(infoSection);

        JSeparator sep = new JSeparator();
        sep.setAlignmentX(Component.LEFT_ALIGNMENT);
        sep.setMaximumSize(new Dimension(Integer.MAX_VALUE, sep.getPreferredSize().height));
        execContent.add(Box.createVerticalStrut(4));
        execContent.add(sep);
        execContent.add(Box.createVerticalStrut(4));

        buttonsCardLayout = new CardLayout();
        // 覆写 maxSize 避免按钮区被拉伸
        buttonsCard = new JPanel(buttonsCardLayout) {
            @Override public Dimension getMaximumSize() {
                Dimension pref = getPreferredSize();
                return new Dimension(Integer.MAX_VALUE, pref.height);
            }
            @Override public Dimension getPreferredSize() {
                // CardLayout 默认返回所有子卡片中最大的首选尺寸；改为仅计当前可见卡片
                Component visible = null;
                for (Component c : getComponents()) {
                    if (c.isVisible()) { visible = c; break; }
                }
                return visible != null ? visible.getPreferredSize() : super.getPreferredSize();
            }
        };
        buttonsCard.setAlignmentX(Component.LEFT_ALIGNMENT);
        buttonsCard.add(buildFtpButtons(), CARD_FTP);
        buttonsCard.add(buildLocalButtons(), CARD_LOCAL);
        execContent.add(buttonsCard);

        execCard.add(execContent, BorderLayout.CENTER);

        // ═══ 目标卡片 ═══
        JPanel targetCard = createCardPanel("部署目标", targetContainer);

        // ═══ 日志卡片 ═══
        logCard = new JPanel(new BorderLayout(0, 4));
        logCard.setBorder(BorderFactory.createCompoundBorder(
                new RoundedBorder(8), JBUI.Borders.empty(4, 5)));
        JPanel logHeader = new JPanel(new BorderLayout());
        JBLabel logTitle = new JBLabel("运行日志");
        styleCardTitle(logTitle);
        logHeader.add(logTitle, BorderLayout.WEST);

        // 日志头部右侧操作区：清空日志 + 全屏切换 + 最小化
        JButton clearLogButton = new JButton(AllIcons.Actions.GC);
        styleIconToggle(clearLogButton);
        clearLogButton.setToolTipText("清空运行日志");
        clearLogButton.addActionListener(e -> logSection.clear());

        fullscreenToggleButton = new JButton(AllIcons.General.ExpandComponent);
        fullscreenToggleButton.setRolloverIcon(AllIcons.General.ExpandComponentHover);
        styleIconToggle(fullscreenToggleButton);
        fullscreenToggleButton.setToolTipText("全屏运行日志窗口（占满插件区域）");
        fullscreenToggleButton.addActionListener(e -> toggleLogFullscreen());

        // 最小化按钮：图标为一条横线（"—"），视觉上贴近窗口最小化按钮
        JButton minimizeButton = new JButton();
        minimizeButton.setIcon(buildMinimizeDashIcon(false));
        minimizeButton.setRolloverIcon(buildMinimizeDashIcon(true));
        styleIconToggle(minimizeButton);
        minimizeButton.setToolTipText("最小化运行日志窗口（日志仍会继续记录）");
        minimizeButton.addActionListener(e -> closeLogWindow());

        JPanel logHeaderActions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 2, 0));
        logHeaderActions.setOpaque(false);
        logHeaderActions.add(clearLogButton);
        logHeaderActions.add(fullscreenToggleButton);
        logHeaderActions.add(minimizeButton);
        logHeader.add(logHeaderActions, BorderLayout.EAST);

        logCard.add(logHeader, BorderLayout.NORTH);
        logCard.add(logSection, BorderLayout.CENTER);
        logClosedBar = buildLogClosedBar();

        // ═══ 右列：目标 ↕ 日志 可拖动（带抓取手柄） ═══
        // 初始比例占位 0.66，真正数值在首次 componentResized 时按 execCard 的首选高度动态计算
        rightSplit = new JBSplitter(true, RIGHT_SPLIT_DEFAULT_PROPORTION);
        rightSplit.setShowDividerControls(true);
        rightSplit.setShowDividerIcon(true);
        rightSplit.setDividerWidth(2);
        rightSplit.setFirstComponent(targetCard);
        rightSplit.setSecondComponent(logCard);
        rightSplit.setProportion(RIGHT_SPLIT_DEFAULT_PROPORTION);

        // ═══ 左列：源 ↕ 执行 可上下拖动（带抓取手柄） ═══
        JBSplitter leftSplit = new JBSplitter(true, 0.66f);
        leftSplit.setShowDividerControls(true);
        leftSplit.setShowDividerIcon(true);
        leftSplit.setDividerWidth(2);
        leftSplit.setFirstComponent(createCardPanel("源工程", sourceSection));
        leftSplit.setSecondComponent(execCard);
        JPanel leftColumn = new JPanel(new BorderLayout());
        // 用 JLayer 包一层 → 在 divider 上下各 4px 范围加"幽灵命中区"，
        // 视觉宽度仍为 dividerWidth，但鼠标可在更宽的隐形带上抓取拖动（详见 ExtendedSplitterHitZoneUI）
        leftColumn.add(new JLayer<>(leftSplit, new ExtendedSplitterHitZoneUI()),
                BorderLayout.CENTER);

        // 首次布局后按 execCard 的首选高度收紧左右两列下半区：
        //  - 左列：执行区维持其自身所需的最低高度，上半部源工程拿到剩余高度；
        //  - 右列：默认关闭日志时只保留恢复条；用户打开日志后再按执行区高度还原。
        // 只在首次 componentResized 时生效（done 标志兜底），之后分隔条由用户自由拖动。
        leftSplit.addComponentListener(new java.awt.event.ComponentAdapter() {
            boolean done = false;
            @Override
            public void componentResized(java.awt.event.ComponentEvent e) {
                if (done) return;
                int totalH = leftSplit.getHeight();
                if (totalH <= 0) return;
                int execH = Math.max(1, execCard.getPreferredSize().height);
                // 夹到合理区间，避免极端窗口高度下执行区被挤到 0 或撑满全屏
                float prop = Math.max(0.3f, Math.min(0.95f,
                        (totalH - execH) / (float) totalH));
                leftSplit.setProportion(prop);
                rightSplitRestoreProportion = prop;
                rightSplit.setProportion(logClosed ? RIGHT_SPLIT_LOG_CLOSED_PROPORTION : prop);
                done = true;
            }
        });

        // ═══ 主分割：左列 | 右列（可左右拖动） ═══
        mainSplit = new OnePixelSplitter(false, 0.5f);
        mainSplit.setFirstComponent(leftColumn);
        // 同 leftSplit：JLayer 包装扩展 rightSplit 的拖动命中区
        mainSplit.setSecondComponent(new JLayer<>(rightSplit, new ExtendedSplitterHitZoneUI()));

        deployCard = new JPanel(new BorderLayout());
        deployCard.setBorder(JBUI.Borders.empty(4));
        deployCard.add(mainSplit, BorderLayout.CENTER);

        // 根卡片：部署视图 / 文档视图
        rootCardLayout = new CardLayout();
        rootCards = new JPanel(rootCardLayout);
        rootCards.add(deployCard, ROOT_DEPLOY);
        add(rootCards, BorderLayout.CENTER);
    }

    /**
     * 切换到文档视图并加载指定资源
     *
     * @param title        顶部标题
     * @param resourcePath 插件资源路径（如 /docs/help.html）
     * @author xumanyi
     * @date 2026-04-17
     */
    public void showDocs(String title, String resourcePath) {
        if (docsPanel == null) {
            docsPanel = new DocsPanel(this::showMain);
            rootCards.add(docsPanel, ROOT_DOCS);
        }
        docsPanel.load(title, resourcePath);
        rootCardLayout.show(rootCards, ROOT_DOCS);
        currentDocKey = resourcePath;
    }

    /**
     * 切换回部署视图
     *
     * @author xumanyi
     * @date 2026-04-17
     */
    public void showMain() {
        rootCardLayout.show(rootCards, ROOT_DEPLOY);
        currentDocKey = null;
    }

    /**
     * 图标按钮切换：当前正显示该文档则切回部署面板；否则切到该文档
     *
     * @param title        顶部标题
     * @param resourcePath 插件资源路径
     * @author xumanyi
     * @date 2026-04-17
     */
    public void toggleDocs(String title, String resourcePath) {
        if (resourcePath.equals(currentDocKey)) {
            showMain();
        } else {
            showDocs(title, resourcePath);
        }
    }

    /** FTP 模式的按钮行：全部按钮单行排列 */
    private JPanel buildFtpButtons() {
        JPanel p = new JPanel(new GridBagLayout());
        GridBagConstraints gc = new GridBagConstraints();
        gc.gridy = 0; gc.insets = JBUI.insets(4, 0, 4, 6);
        gc.anchor = GridBagConstraints.WEST;

        preCheckButton.setMargin(JBUI.insets(3, 10));
        preCheckButton.setToolTipText("检查 FTP 连接和目标包状态，不执行实际操作");
        gc.gridx = 0; p.add(preCheckButton, gc);

        deployButton.putClientProperty("JButton.buttonType", "default");
        deployButton.setFont(deployButton.getFont().deriveFont(Font.BOLD));
        deployButtonIdleColor = accentBlue();
        deployButton.setForeground(deployButtonIdleColor);
        deployButton.setMargin(JBUI.insets(3, 12));
        deployButton.setToolTipText("合并本地 Maven 编译产物，生成新包上传到 FTP 服务器");
        gc.gridx = 1; p.add(deployButton, gc);

        localOnlyButton.setMargin(JBUI.insets(3, 10));
        localOnlyButton.setToolTipText("合并本地 Maven 编译产物，生成新包保存到本地");
        gc.gridx = 2; p.add(localOnlyButton, gc);

        rollbackButton.setMargin(JBUI.insets(3, 10));
        rollbackButton.setToolTipText("回滚上次部署，恢复备份版本");
        gc.gridx = 3; p.add(rollbackButton, gc);

        resetButton.setMargin(JBUI.insets(3, 10));
        resetButton.setToolTipText("清空所有选择，恢复初始状态");
        gc.gridx = 4; p.add(resetButton, gc);

        gc.gridx = 5; gc.weightx = 1.0; gc.fill = GridBagConstraints.HORIZONTAL;
        p.add(Box.createHorizontalGlue(), gc);
        return p;
    }

    /** 本地模式的按钮行 */
    private JPanel buildLocalButtons() {
        JPanel p = new JPanel(new GridBagLayout());
        GridBagConstraints gc = new GridBagConstraints();
        gc.gridy = 0; gc.insets = JBUI.insets(4, 0, 4, 6);
        gc.anchor = GridBagConstraints.WEST;

        localBuildButton.putClientProperty("JButton.buttonType", "default");
        localBuildButton.setFont(localBuildButton.getFont().deriveFont(Font.BOLD));
        localBuildButton.setForeground(accentBlue());
        localBuildButton.setMargin(JBUI.insets(3, 12));
        localBuildButton.setToolTipText("对本地 jar/war 打补丁并输出新包（打包前会弹出变更清单供确认）");
        gc.gridx = 0; p.add(localBuildButton, gc);

        localResetButton.setMargin(JBUI.insets(3, 10));
        localResetButton.setToolTipText("清空本地模式选择");
        gc.gridx = 1; p.add(localResetButton, gc);

        gc.gridx = 3; gc.weightx = 1.0; gc.fill = GridBagConstraints.HORIZONTAL;
        p.add(Box.createHorizontalGlue(), gc);
        return p;
    }

    private void initListeners() {
        // ── FTP 模式按钮 ──
        preCheckButton.addActionListener(e -> {
            logSection.appendLog("[操作] 点击「预检」");
            // 预检：轻量校验，仅输出日志，不弹窗阻断
            executeDeploy(true);
            hasPreChecked = true;
        });
        deployButton.addActionListener(e -> {
            // 三态分发：RUNNING 时按钮变身"停止"，弹收尾选择对话框；STOPPING 已请求停止，忽略
            if (deployButtonState == DeployButtonState.RUNNING) {
                logSection.appendLog("[操作] 点击「停止」");
                showStopDialogAndDispatch();
                return;
            }
            if (deployButtonState == DeployButtonState.STOPPING) {
                return;
            }
            logSection.appendLog("[操作] 点击「打包并上传」");
            // 打包并上传：硬性前置校验，任何缺失都弹窗阻断
            List<String> missing = validateFtpPrerequisites();
            if (!missing.isEmpty()) {
                logSection.appendLog("[错误] 前置条件未满足：" + String.join("；", missing));
                showPrerequisiteDialog(missing, "打包并上传");
                return;
            }
            // 已勾选备份时：异步查 FTP 是否已存在当天同开发的备份，存在则弹冲突对话框
            if (backupCheckBox.isSelected()) {
                checkBackupConflictAndProceed();
            } else {
                proceedToDeployOrPreCheck();
            }
        });
        localOnlyButton.addActionListener(e -> {
            logSection.appendLog("[操作] 点击「打包不上传」");
            // 打包不上传：同样强制校验（需要 FTP 下载远端原包）
            List<String> missing = validateFtpPrerequisites();
            if (!missing.isEmpty()) {
                logSection.appendLog("[错误] 前置条件未满足：" + String.join("；", missing));
                showPrerequisiteDialog(missing, "打包不上传");
                return;
            }
            if (!confirmFtpLocalBuild()) {
                logSection.appendLog("[操作] 用户取消打包不上传");
                return;
            }
            logSection.appendLog("[操作] 确认打包不上传");
            executeDeploy(false, null, true);
        });
        resetButton.addActionListener(e -> resetFtpMode());
        rollbackButton.addActionListener(e -> {
            logSection.appendLog("[操作] 点击「回滚」");
            doRollback();
        });

        // ── 本地模式按钮 ──
        localBuildButton.addActionListener(e -> {
            logSection.appendLog("[操作] 点击「本地打包」（本地模式）");
            doLocalBuild();
        });
        localResetButton.addActionListener(e -> resetLocalMode());
    }

    // ═══════════════════════════════════════════════════════════════
    //  目标模式切换
    // ═══════════════════════════════════════════════════════════════

    private void onTargetModeChanged(DeployTargetMode mode) {
        boolean ftp = mode == DeployTargetMode.FTP;
        buttonsCardLayout.show(buttonsCard, ftp ? CARD_FTP : CARD_LOCAL);
        // 本地模式不需要操作人/版本记录/备份，隐藏信息区
        infoSection.setVisible(ftp);
        // 按钮卡片切换后首选高度变化：从 buttonsCard 向上逐级使 BoxLayout / BorderLayout 重新布局，
        // 使底部"执行"区按新内容自适应高度
        buttonsCard.invalidate();
        SwingUtilities.invokeLater(() -> {
            Container c = buttonsCard;
            while (c != null) {
                c.invalidate();
                c = c.getParent();
            }
            revalidate();
            repaint();
        });
        logSection.appendLog("[模式] 切换至" + mode.getDisplayName());
    }

    // ═══════════════════════════════════════════════════════════════
    //  本地模式：本地打包 / 重置
    // ═══════════════════════════════════════════════════════════════

    /**
     * 本地模式执行打包：先预检 → 弹确认清单 → 确认后打包
     */
    private void doLocalBuild() {
        if (!validateLocalInputs()) return;
        LocalTargetSelection lt = targetContainer.getLocalPanel().getSelection();
        List<String> files = sourceSection.getSelectedFiles();

        // 打包前预检以获取清单供用户确认
        LocalPackagePatchService.PreCheckResult pre = LocalPackagePatchService.preCheck(
                sourceSection.getMode(),
                currentModulePath, currentArtifactFileName, files,
                lt.getPackagePath(), logSection::appendLog);
        if (!pre.isOk()) {
            logSection.appendLog("[本地][预检失败] " + pre.getErrorMessage());
            JOptionPane.showMessageDialog(this,
                    "预检失败：\n" + pre.getErrorMessage(),
                    "无法打包", JOptionPane.WARNING_MESSAGE);
            return;
        }
        if (pre.getPreviews().isEmpty()) {
            JOptionPane.showMessageDialog(this,
                    "未产生任何变更（选中文件与包内现状一致或未编译）。",
                    "无可打包内容", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        String pkgName = new File(lt.getPackagePath()).getName();
        LocalPreCheckDialog dialog = new LocalPreCheckDialog(
                project, pre, pkgName, lt.getOutputDir());
        if (!dialog.showAndGet()) {
            logSection.appendLog("[操作] 用户取消本地打包");
            return;
        }
        logSection.appendLog("[操作] 确认打包");

        runLocalBuild(lt, files);
    }

    /** 实际执行本地打包的异步任务 */
    private void runLocalBuild(LocalTargetSelection lt, List<String> files) {

        PluginDeployConfig config = new PluginDeployConfig();
        config.setTargetMode(DeployTargetMode.LOCAL);
        config.setModulePath(currentModulePath);
        config.setArtifactFileName(currentArtifactFileName);
        config.setMode(sourceSection.getMode());
        config.setChangedFiles(files);
        config.setLocalTarget(lt);
        config.setSkipCompile(!sourceSection.needsCompile());

        logSection.appendLog("[本地] 开始打包...");
        setLocalButtonsEnabled(false);
        logSection.setProgressVisible(true);

        DeployExecutionService.executeLocalMode(project, config, logSection::appendLog,
                result -> SwingUtilities.invokeLater(() -> {
                    logSection.setProgressVisible(false);
                    setLocalButtonsEnabled(true);
                    if (result != null && result.isSuccess()) {
                        showLocalSuccess(result);
                    }
                }));
    }

    /** 本地模式打包成功后的反馈 */
    private void showLocalSuccess(LocalPackagePatchService.LocalPatchResult result) {
        Path out = result.getOutputPackage();
        String msg = "✓ 打包成功\n\n输出包：" + out + "\n变更：" + result.getChangedCount() + " 个条目";
        Object[] options = {"打开所在目录", "复制路径", "关闭"};
        int choice = JOptionPane.showOptionDialog(this, msg, "本地打包完成",
                JOptionPane.DEFAULT_OPTION, JOptionPane.INFORMATION_MESSAGE, null,
                options, options[0]);
        if (choice == 0) {
            openContainingDir(out);
        } else if (choice == 1) {
            Toolkit.getDefaultToolkit().getSystemClipboard()
                    .setContents(new StringSelection(out.toString()), null);
            logSection.appendLog("[本地] 已复制路径到剪贴板");
        }
    }

    /** 在系统文件管理器中打开新包所在目录 */
    private void openContainingDir(Path file) {
        try {
            File parent = file.toFile().getParentFile();
            if (parent != null && Desktop.isDesktopSupported()) {
                Desktop.getDesktop().open(parent);
            }
        } catch (Exception ex) {
            logSection.appendLog("[本地] 打开目录失败: " + ex.getMessage());
        }
    }

    /**
     * 校验本地模式执行前置条件（工程选择、包选择、输出目录、勾选文件）
     */
    private boolean validateLocalInputs() {
        if (currentModulePath == null || currentArtifactFileName == null) {
            JOptionPane.showMessageDialog(this, "请先选择源工程", "提示", JOptionPane.WARNING_MESSAGE);
            return false;
        }
        LocalTargetSelection lt = targetContainer.getLocalPanel().getSelection();
        if (lt == null) {
            JOptionPane.showMessageDialog(this, "请先选择本地包和输出目录", "提示",
                    JOptionPane.WARNING_MESSAGE);
            return false;
        }
        // 整包更新模式不需要勾选文件（整体覆盖）
        if (sourceSection.getMode() != DeployMode.FULL) {
            List<String> files = sourceSection.getSelectedFiles();
            if (files == null || files.isEmpty()) {
                JOptionPane.showMessageDialog(this, "请先勾选待更新文件", "提示",
                        JOptionPane.WARNING_MESSAGE);
                return false;
            }
        }
        return true;
    }

    private void setLocalButtonsEnabled(boolean enabled) {
        localBuildButton.setEnabled(enabled);
        localResetButton.setEnabled(enabled);
    }

    /**
     * 本地模式重置：清空源工程 + 本地子面板输入 + 日志（不触及 FTP 连接与 FTP 侧目标状态）
     */
    private void resetLocalMode() {
        // 源工程（与 FTP 模式的 resetFtpMode 对齐）
        currentModulePath = null;
        currentArtifactFileName = null;
        lastFilesLoadedForModule = null;
        sourceSection.setModule(null);
        sourceSection.setArtifact(null);
        sourceSection.setMode(DeployMode.FULL);
        sourceSection.setChangedFiles(null);
        // 本地目标面板
        targetContainer.getLocalPanel().resetAll();
        // 日志与状态
        logSection.clear();
        hasPreChecked = false;
    }

    // ═══════════════════════════════════════════════════════════════
    //  FTP 模式：原有逻辑
    // ═══════════════════════════════════════════════════════════════

    private void resetFtpMode() {
        currentModulePath = null;
        currentArtifactFileName = null;
        lastFilesLoadedForModule = null;
        sourceSection.setModule(null);
        sourceSection.setArtifact(null);
        sourceSection.setMode(DeployMode.FULL);
        sourceSection.setChangedFiles(null);
        targetSection.resetAll();
        infoSection.reset();
        logSection.clear();
        hasDeployed = false;
        hasPreChecked = false;
        rollbackButton.setEnabled(false);
    }

    private void doRollback() {
        if (!DeployExecutionService.hasRollbackData()) {
            logSection.appendLog("[回滚] 没有可回滚的部署记录");
            rollbackButton.setEnabled(false);
            return;
        }
        int confirm = JOptionPane.showConfirmDialog(this,
                "确认回滚上次部署？\n\n"
                + "此操作将：\n"
                + "  1. 恢复所有已更新的包为备份版本\n"
                + "  2. 撤销版本记录中新增的记录\n"
                + "  3. 删除备份目录\n\n"
                + "⚠ 回滚不可撤销。\n"
                + "⚠ 请确认当前没有其他人正在更新同一系统的包。",
                "确认回滚", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (confirm != JOptionPane.YES_OPTION) {
            logSection.appendLog("[操作] 用户取消回滚");
            return;
        }
        logSection.appendLog("[操作] 确认回滚");

        rollbackButton.setEnabled(false);
        setButtonsEnabled(false);
        logSection.setProgressVisible(true);
        DeployExecutionService.manualRollback(project,
                targetSection.getConnectedHost(), targetSection.getConnectedPort(),
                targetSection.getConnectedUsername(), targetSection.getConnectedPassword(),
                logSection::appendLog,
                () -> {
                    preCheckButton.setEnabled(true);
                    deployButton.setEnabled(true);
                    resetButton.setEnabled(true);
                    logSection.setProgressVisible(false);
                    hasDeployed = false;
                    hasPreChecked = false;
                    rollbackButton.setEnabled(false);
                });
    }

    /**
     * 异步查询 FTP 是否已有今天同开发的备份；有冲突时弹确认框，无冲突时直接进入预检/部署流程
     *
     * @author xumanyi
     * @date 2026-04-17
     */
    private void checkBackupConflictAndProceed() {
        // 收集所有将被备份的目标（主 + 嵌入），主目标可能有多个同名 JAR 分布在不同子目录
        java.util.List<FtpTargetSelection> allTargets = new java.util.ArrayList<>();
        allTargets.addAll(targetSection.getMainTargets());
        java.util.List<FtpTargetSelection> embeds = targetSection.getEmbedTargets();
        if (embeds != null) allTargets.addAll(embeds);

        String operator = infoSection.getOperator();
        String host = targetSection.getConnectedHost();
        int port = targetSection.getConnectedPort();
        String user = targetSection.getConnectedUsername();
        String pass = targetSection.getConnectedPassword();

        // 禁用按钮 + 日志提示
        setButtonsEnabled(false);
        logSection.appendLog("[备份] 正在检查当天是否已有备份...");

        com.intellij.openapi.application.ApplicationManager.getApplication()
                .executeOnPooledThread(() -> {
            java.util.List<String> conflicts;
            try {
                String detectContextDir = targetSection.getCurrentContextDir();
                String detectCustomRoot = null;
                if (detectContextDir != null) {
                    String detectKey = host + ":" + port + "|" + detectContextDir;
                    com.flux.deploy.plugin.service.PluginSettingsService detectSettings =
                            project.getService(com.flux.deploy.plugin.service.PluginSettingsService.class);
                    if (detectSettings != null && detectSettings.getState() != null) {
                        detectCustomRoot = detectSettings.getState().customBackupRoots.get(detectKey);
                    }
                }
                conflicts = DeployExecutionService.detectExistingBackups(
                        host, port, user, pass, operator, allTargets, detectCustomRoot,
                        msg -> SwingUtilities.invokeLater(() -> logSection.appendLog(msg)));
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> {
                    logSection.appendLog("[备份] 检查失败（忽略此步骤继续）: " + ex.getMessage());
                    setButtonsEnabled(true);
                    proceedToDeployOrPreCheck();
                });
                return;
            }
            SwingUtilities.invokeLater(() -> {
                setButtonsEnabled(true);
                if (conflicts.isEmpty()) {
                    logSection.appendLog("[备份] 检查完成：无冲突");
                    proceedToDeployOrPreCheck();
                } else {
                    BackupConflictDialog dialog = new BackupConflictDialog(project, conflicts);
                    if (dialog.showAndGet()) {
                        com.flux.deploy.plugin.model.BackupConflictStrategy strategy =
                                dialog.getSelectedStrategy();
                        if (strategy == null) {
                            logSection.appendLog("[备份] 未选择处理方式，已取消");
                            return;
                        }
                        pendingBackupStrategy = strategy;
                        logSection.appendLog("[备份] 冲突处理：" + strategy.getDisplayName()
                                + "（" + conflicts.size() + " 个冲突）");
                        proceedToDeployOrPreCheck();
                    } else {
                        logSection.appendLog("[备份] 用户取消，未执行更新");
                    }
                }
            });
        });
    }

    /**
     * 弹出备份冲突确认对话框
     *
     * @return true=用户确认继续覆盖；false=取消
     */
    /** 备份冲突检查后的通用入口：按预检状态决定是否先提示预检 */
    private void proceedToDeployOrPreCheck() {
        if (!hasPreChecked) {
            int choice = JOptionPane.showConfirmDialog(this,
                    "尚未进行预检，建议先预检确认 FTP 状态。\n是否先执行预检？",
                    "提示", JOptionPane.YES_NO_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE);
            if (choice == JOptionPane.YES_OPTION) {
                logSection.appendLog("[操作] 先执行预检");
                executeDeploy(true);
                hasPreChecked = true;
            } else if (choice == JOptionPane.NO_OPTION) {
                logSection.appendLog("[操作] 跳过预检，直接进入部署确认");
                showConfirmAndDeploy();
            } else {
                logSection.appendLog("[操作] 用户取消，未执行更新");
            }
        } else {
            showConfirmAndDeploy();
        }
    }

    /**
     * FTP 模式前置条件校验：返回所有未满足项的人类可读说明列表
     *
     * <p>调用者需要在必填项缺失时阻断用户继续操作；预检为轻量校验，不走本方法。</p>
     *
     * @return 空列表表示全部满足；否则为缺失项说明
     * @author xumanyi
     * @date 2026-04-17
     */
    private List<String> validateFtpPrerequisites() {
        List<String> missing = new java.util.ArrayList<>();

        // 工程 & 产物
        if (currentModulePath == null || currentModulePath.isEmpty()) {
            missing.add("未选择工程");
        }

        // FTP 连接
        if (!targetSection.isFtpConnected()) {
            missing.add("FTP 未连接");
        }

        // 目标包（主 or 嵌入）
        boolean hasMainTarget = !targetSection.getMainTargets().isEmpty();
        List<FtpTargetSelection> embeds = targetSection.getEmbedTargets();
        boolean hasEmbedTarget = embeds != null && !embeds.isEmpty();
        if (!hasMainTarget && !hasEmbedTarget) {
            missing.add("未选择目标包");
        }

        // 待更新文件（非整包模式要求至少一个）
        DeployMode mode = sourceSection.getMode();
        if (mode != DeployMode.FULL) {
            List<String> files = sourceSection.getSelectedFiles();
            if (files == null || files.isEmpty()) {
                missing.add("未勾选任何待更新文件");
            }
        }

        // 版本记录 / 备份要求的字段
        boolean updateNote = infoSection.isUpdateNote();
        boolean backup = backupCheckBox.isSelected();
        if (updateNote) {
            String task = infoSection.getTaskId();
            String cust = infoSection.getCustomerId();
            if ((task == null || task.isEmpty()) && (cust == null || cust.isEmpty())) {
                missing.add("勾选了更新版本记录，请至少填写任务或客服");
            }
        }
        if ((updateNote || backup)
                && (infoSection.getOperator() == null || infoSection.getOperator().isEmpty())) {
            missing.add("勾选了更新版本记录或执行备份，请填写开发");
        }

        return missing;
    }

    /**
     * FTP 模式下「打包不上传」的确认对话框
     *
     * <p>列出将要做的事与目标清单。不改远端，但会跑 mvn + 下载 FTP 原包。</p>
     *
     * @return true=用户确认继续
     * @author xumanyi
     * @date 2026-04-19
     */
    private boolean confirmFtpLocalBuild() {
        List<FtpTargetSelection> mainTargets = targetSection.getMainTargets();
        List<FtpTargetSelection> embeds = targetSection.getEmbedTargets();
        DeployMode mode = sourceSection.getMode();

        StringBuilder html = new StringBuilder();
        html.append("<html><body style='width:460px;'>");
        html.append("<b>即将执行「打包不上传」</b><br><br>");

        html.append("模式：<code>").append(mode == null ? "未选" : mode.name()).append("</code><br>");

        html.append("<b>目标包：</b><ul style='margin-top:2px;'>");
        for (FtpTargetSelection t : mainTargets) {
            html.append("<li>").append(t.getRelativePath()).append("</li>");
        }
        if (embeds != null) {
            for (FtpTargetSelection t : embeds) {
                html.append("<li>").append(t.getRelativePath()).append("  <i>(WAR 嵌入)</i></li>");
            }
        }
        html.append("</ul>");

        html.append("<b>将执行：</b><ol style='margin-top:2px;'>");
        html.append("<li>编译项目（mvn clean package）</li>");
        html.append("<li>从 FTP 下载每个目标的远端原包</li>");
        html.append("<li>替换本地选中的 class / 资源，或按模式整体替换</li>");
        html.append("<li>合成包输出到 <code>target/flux-deploy-output/</code></li>");
        html.append("</ol>");

        html.append("<span style='color:#85c88a;'>✓ 不上传、不备份、不加锁、不改动远端任何文件</span><br>");
        html.append("<span style='color:#8a8e93;'>输出包生成后你可以手动上传 / 发给客户 / 本地验证</span>");
        html.append("</body></html>");

        Object[] options = {"开始打包", "取消"};
        int choice = JOptionPane.showOptionDialog(this, html.toString(),
                "打包不上传确认",
                JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE,
                null, options, options[0]);
        return choice == 0;
    }

    /**
     * 弹出前置条件不满足的提示对话框
     *
     * @param missing 缺失项列表
     * @param action  用户正尝试的操作名（用于对话框标题）
     */
    private void showPrerequisiteDialog(List<String> missing, String action) {
        StringBuilder html = new StringBuilder();
        html.append("<html><body style='width:360px;'>");
        html.append("<b>⚠ 以下条件未满足，无法执行「").append(action).append("」：</b><br><br>");
        html.append("<ul style='margin-top:0;'>");
        for (String m : missing) {
            html.append("<li>").append(m).append("</li>");
        }
        html.append("</ul>");
        html.append("请补齐后重试。");
        html.append("</body></html>");
        JOptionPane.showMessageDialog(this, html.toString(),
                "条件不满足", JOptionPane.WARNING_MESSAGE);
    }

    private void showConfirmAndDeploy() {
        List<FtpTargetSelection> mainTargets = targetSection.getMainTargets();
        DeployMode mode = sourceSection.getMode();
        List<String> files = sourceSection.getSelectedFiles();

        // 主目标包展示：单个时显示包名；多个时列出所有 relativePath 便于用户核对
        String targetPkg;
        String remotePath;
        if (mainTargets.isEmpty()) {
            targetPkg = "未选择";
            remotePath = "未选择";
        } else if (mainTargets.size() == 1) {
            FtpTargetSelection t = mainTargets.get(0);
            targetPkg = t.getTargetName();
            remotePath = t.getRemoteDir() + t.getRelativePath();
        } else {
            targetPkg = mainTargets.get(0).getTargetName() + "（共 " + mainTargets.size() + " 个同名目标）";
            StringBuilder sb = new StringBuilder();
            for (FtpTargetSelection t : mainTargets) {
                if (sb.length() > 0) sb.append('\n');
                sb.append(t.getRemoteDir()).append(t.getRelativePath());
            }
            remotePath = sb.toString();
        }

        DeployConfirmDialog dialog = new DeployConfirmDialog(
                project, targetPkg, remotePath, mode, files);

        if (dialog.showAndGet()) {
            logSection.appendLog("[操作] 部署确认对话框：确认");
            if (!backupCheckBox.isSelected()) {
                int warn = JOptionPane.showConfirmDialog(this,
                        "⚠ 未勾选「执行备份」，更新失败后将无法自动回滚！\n\n"
                        + "确定不备份直接更新到 FTP？",
                        "安全警告", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
                if (warn != JOptionPane.YES_OPTION) {
                    logSection.appendLog("[操作] 用户取消（未勾选备份的安全警告）");
                    return;
                }
                logSection.appendLog("[操作] 用户确认：不备份直接更新");
            }
            List<String> selectedFiles = dialog.getSelectedFiles();
            executeDeploy(false, selectedFiles);
        } else {
            logSection.appendLog("[操作] 用户取消部署确认");
        }
    }

    private void executeDeploy(boolean dryRun) {
        executeDeploy(dryRun, null, false);
    }

    private void executeDeploy(boolean dryRun, List<String> selectedFiles) {
        executeDeploy(dryRun, selectedFiles, false);
    }

    private void executeDeploy(boolean dryRun, List<String> selectedFiles, boolean localOnly) {
        // 不再清空日志：保留前置步骤（前置校验、备份冲突检查）的输出，
        // 方便用户在失败时往上滚动看到完整上下文。想清空可点日志区「清空」按钮。
        logSection.appendLog("────── 开始新一轮" + (dryRun ? "预检" : localOnly ? "打包不上传" : "打包并上传") + " ──────");

        PluginDeployConfig pluginConfig = new PluginDeployConfig();
        pluginConfig.setTargetMode(DeployTargetMode.FTP);
        pluginConfig.setModulePath(currentModulePath);
        pluginConfig.setArtifactFileName(currentArtifactFileName);
        pluginConfig.setMode(sourceSection.getMode());
        pluginConfig.setMainTargets(targetSection.getMainTargets());
        pluginConfig.setEmbedTargets(targetSection.getEmbedTargets());
        pluginConfig.setBackupConflictStrategy(pendingBackupStrategy);
        pluginConfig.setDryRun(dryRun);
        pluginConfig.setLocalOnly(localOnly);
        pluginConfig.setSkipBackup(!backupCheckBox.isSelected());
        pluginConfig.setSkipCompile(!sourceSection.needsCompile());

        // 注入用户自定义备份根（若已配置）
        String contextDir = targetSection.getCurrentContextDir();
        if (contextDir != null) {
            String customKey = targetSection.getConnectedHost() + ":"
                    + targetSection.getConnectedPort() + "|" + contextDir;
            com.flux.deploy.plugin.service.PluginSettingsService settings =
                    project.getService(com.flux.deploy.plugin.service.PluginSettingsService.class);
            if (settings != null && settings.getState() != null) {
                String custom = settings.getState().customBackupRoots.get(customKey);
                if (custom != null && !custom.isBlank()) {
                    pluginConfig.setCustomBackupRoot(custom);
                }
            }
        }

        if (selectedFiles != null) {
            pluginConfig.setChangedFiles(selectedFiles);
        } else {
            pluginConfig.setChangedFiles(sourceSection.getSelectedFiles());
        }

        boolean updateNote = infoSection.isUpdateNote();
        pluginConfig.setUpdateNote(updateNote);
        if (updateNote) {
            pluginConfig.setTaskId(infoSection.getTaskId());
            pluginConfig.setCustomerId(infoSection.getCustomerId());
        }
        pluginConfig.setOperator(infoSection.getOperator());

        if (pluginConfig.getModulePath() == null || pluginConfig.getModulePath().isEmpty()) {
            logSection.appendLog("[错误] 请先选择工程");
            return;
        }
        if (!targetSection.isFtpConnected()) {
            logSection.appendLog("[错误] FTP 未连接，请先点击连接按钮");
            return;
        }
        if (pluginConfig.getMainTargets().isEmpty()
                && (pluginConfig.getEmbedTargets() == null || pluginConfig.getEmbedTargets().isEmpty())) {
            logSection.appendLog("[错误] 请选择目标（项目 / 系统 / 目标包）");
            return;
        }
        if (updateNote) {
            boolean taskEmpty = pluginConfig.getTaskId() == null || pluginConfig.getTaskId().isEmpty();
            boolean customerEmpty = pluginConfig.getCustomerId() == null || pluginConfig.getCustomerId().isEmpty();
            if (taskEmpty && customerEmpty) {
                logSection.appendLog("[错误] 勾选了更新版本记录，请至少填写任务或客服之一");
                return;
            }
        }
        boolean needOperator = updateNote || backupCheckBox.isSelected();
        if (needOperator
                && (pluginConfig.getOperator() == null || pluginConfig.getOperator().isEmpty())) {
            logSection.appendLog("[错误] 勾选了版本记录或执行备份，请填写开发");
            return;
        }

        infoSection.saveToCache();

        enterRunningState();
        logSection.setProgressVisible(true);

        DeployExecutionService.execute(project, pluginConfig,
                targetSection.getConnectedHost(), targetSection.getConnectedPort(),
                targetSection.getConnectedUsername(), targetSection.getConnectedPassword(),
                logSection::appendLog,
                result -> SwingUtilities.invokeLater(() -> {
                    exitToIdleState();
                    logSection.setProgressVisible(false);
                    // KEEP_SUCCEEDED 路径下 service 已登记 lastUpdatedPackages，启用回滚按钮
                    boolean keptForManualRollback = DeployExecutionService.hasRollbackData();
                    if (result != null && result.isSuccess() && !dryRun && !localOnly
                            && backupCheckBox.isSelected()) {
                        hasDeployed = true;
                        rollbackButton.setEnabled(true);
                    } else if (keptForManualRollback) {
                        hasDeployed = true;
                        rollbackButton.setEnabled(true);
                    }
                    // 一次部署结束后重置备份冲突策略为默认，避免污染下次操作
                    pendingBackupStrategy = com.flux.deploy.plugin.model.BackupConflictStrategy.OVERWRITE;
                }));
    }

    private void setButtonsEnabled(boolean enabled) {
        preCheckButton.setEnabled(enabled);
        deployButton.setEnabled(enabled);
        localOnlyButton.setEnabled(enabled);
        resetButton.setEnabled(enabled);
        if (enabled && hasDeployed) {
            rollbackButton.setEnabled(true);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  「打包并上传」按钮状态机：IDLE / RUNNING / STOPPING
    // ═══════════════════════════════════════════════════════════════

    /**
     * 进入 RUNNING 态：禁用其他按钮，把"打包并上传"变身为红色"停止"。
     * 由 executeDeploy 在异步任务启动时调用。
     */
    private void enterRunningState() {
        deployButtonState = DeployButtonState.RUNNING;
        preCheckButton.setEnabled(false);
        localOnlyButton.setEnabled(false);
        rollbackButton.setEnabled(false);
        resetButton.setEnabled(false);
        deployButton.setEnabled(true);
        deployButton.setText("停止");
        deployButton.setForeground(JBColor.RED);
        deployButton.setToolTipText("点击停止当前部署：将弹出对话框选择如何处理已成功的包");
    }

    /**
     * 进入 STOPPING 态：用户已确认停止，在后台 catch 块跑完前禁用按钮防抖。
     */
    private void enterStoppingState() {
        deployButtonState = DeployButtonState.STOPPING;
        deployButton.setEnabled(false);
        deployButton.setText("正在停止…");
    }

    /**
     * 回到 IDLE 态：恢复"打包并上传"原文案 / 原色 / 启用所有按钮。
     * 由 executeDeploy 的 onComplete 回调调用。
     */
    private void exitToIdleState() {
        deployButtonState = DeployButtonState.IDLE;
        deployButton.setText("打包并上传");
        deployButton.setForeground(deployButtonIdleColor);
        deployButton.setToolTipText("合并本地 Maven 编译产物，生成新包上传到 FTP 服务器");
        setButtonsEnabled(true);
    }

    /**
     * 弹出"如何停止"对话框：根据已成功的包数量给出 2 / 3 选项，用户选择后翻取消标志。
     *
     * <p>与 IDEA 进度条 cancel 按钮的区别：进度条 cancel 直接走 ROLLBACK_ALL 兜底，
     * 不弹窗；本方法仅由我们自己的"停止"按钮触发。</p>
     */
    private void showStopDialogAndDispatch() {
        if (DeployExecutionService.isStopRequested()) {
            // 防止用户手快二连点
            return;
        }
        boolean dryRun = DeployExecutionService.isCurrentDryRun();
        int total = DeployExecutionService.getLiveTotalTargets();
        java.util.List<String> succeededNames = DeployExecutionService.getLiveSucceededNames();
        int succeeded = succeededNames.size();

        StringBuilder msg = new StringBuilder();
        msg.append(dryRun ? "预检进行中。\n\n" : "部署进行中。\n\n");
        if (total > 0 && !dryRun) {
            msg.append("已成功：").append(succeeded).append(" / ").append(total).append("\n");
            if (!succeededNames.isEmpty()) {
                for (String n : succeededNames) {
                    msg.append("  ✓ ").append(n).append("\n");
                }
            }
        }
        msg.append("\n请选择如何处理：");

        String[] options;
        if (succeeded > 0 && !dryRun) {
            options = new String[]{"继续部署", "停止并回滚已成功的包", "停止但保留已成功的包"};
        } else {
            options = new String[]{"继续部署", "停止"};
        }
        int choice = JOptionPane.showOptionDialog(this, msg.toString(),
                dryRun ? "停止预检？" : "停止部署？",
                JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE,
                null, options, options[0]);
        // 0 = 继续 / -1 = ESC 关闭：都视为继续
        if (choice <= 0) {
            logSection.appendLog("[操作] 用户取消停止，继续部署");
            return;
        }
        DeployExecutionService.CancelMode mode;
        if (succeeded > 0 && !dryRun) {
            mode = (choice == 1)
                    ? DeployExecutionService.CancelMode.ROLLBACK_ALL
                    : DeployExecutionService.CancelMode.KEEP_SUCCEEDED;
        } else {
            mode = DeployExecutionService.CancelMode.ROLLBACK_ALL;
        }
        logSection.appendLog("[操作] 请求停止：" + (
                mode == DeployExecutionService.CancelMode.KEEP_SUCCEEDED
                        ? "保留已成功的包" : "回滚已成功的包"));
        DeployExecutionService.requestStop(mode);
        enterStoppingState();
    }

    /**
     * 标题栏图标切换按钮样式：透明底、无边框、紧凑尺寸，贴近 IntelliJ ToolWindow 头部图标。
     *
     * @param button 待设置样式的按钮
     */
    private void styleIconToggle(JButton button) {
        button.setBorderPainted(false);
        button.setContentAreaFilled(false);
        button.setFocusPainted(false);
        button.setFocusable(false);
        button.setMargin(JBUI.emptyInsets());
        int size = 22;
        button.setPreferredSize(new Dimension(size, size));
    }

    /**
     * 构造一条 16×16 的横线图标，作为最小化按钮的视觉，模拟系统窗口的"—"最小化键。
     *
     * @param hover 是否为悬停态（悬停时颜色更亮）
     * @return 图标实例
     */
    private Icon buildMinimizeDashIcon(boolean hover) {
        return new Icon() {
            @Override public int getIconWidth() { return 16; }
            @Override public int getIconHeight() { return 16; }
            @Override
            public void paintIcon(Component c, Graphics g, int x, int y) {
                Graphics2D g2 = (Graphics2D) g.create();
                try {
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    Color base = JBColor.foreground();
                    int alpha = hover ? 255 : 180;
                    g2.setColor(new Color(base.getRed(), base.getGreen(), base.getBlue(), alpha));
                    g2.setStroke(new BasicStroke(1.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    int midY = y + getIconHeight() / 2;
                    g2.drawLine(x + 3, midY, x + getIconWidth() - 3, midY);
                } finally {
                    g2.dispose();
                }
            }
        };
    }

    /**
     * 构造日志关闭后的折叠条。
     *
     * <p>只保留「运行日志」标题 + 展开图标，视觉上与 logCard 头部完全一致，
     * 关闭/展开像是同一条标题栏被折叠/展开，不引入额外提示文字。</p>
     *
     * @return 日志折叠条面板
     */
    private JPanel buildLogClosedBar() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createCompoundBorder(
                new RoundedBorder(8), JBUI.Borders.empty(2, 5)));

        JBLabel title = new JBLabel("运行日志");
        styleCardTitle(title);
        panel.add(title, BorderLayout.WEST);

        JButton expandButton = new JButton(AllIcons.General.ExpandComponent);
        expandButton.setRolloverIcon(AllIcons.General.ExpandComponentHover);
        styleIconToggle(expandButton);
        expandButton.setToolTipText("展开运行日志窗口");
        expandButton.addActionListener(e -> restoreLogWindow());
        panel.add(expandButton, BorderLayout.EAST);

        // 限制最大高度等于首选高度，JBSplitter 分配空间时折叠条不会被拉高
        panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, panel.getPreferredSize().height));
        return panel;
    }

    /**
     * 折叠日志窗口，只保留顶部折叠条
     */
    private void closeLogWindow() {
        // 全屏状态下先退出全屏，再执行最小化
        if (logFullscreen) {
            toggleLogFullscreen();
        }
        if (rightSplit == null || logClosed) {
            return;
        }
        rightSplitRestoreProportion = rightSplit.getProportion();
        logClosed = true;
        rightSplit.setSecondComponent(logClosedBar);
        rightSplit.setProportion(RIGHT_SPLIT_LOG_CLOSED_PROPORTION);
        rightSplit.revalidate();
        rightSplit.repaint();
    }

    /**
     * 切换日志全屏：全屏时日志卡片占满整个 deployCard（左列与目标区临时隐藏）；
     * 取消全屏时还原回主分割器布局。
     */
    private void toggleLogFullscreen() {
        if (deployCard == null || logCard == null) {
            return;
        }
        // 最小化状态下进入全屏前先恢复
        if (!logFullscreen && logClosed) {
            restoreLogWindow();
        }
        if (!logFullscreen) {
            // 进入全屏：把 logCard 从 rightSplit 摘出，放进 deployCard
            rightSplit.setSecondComponent(new JPanel());
            deployCard.remove(mainSplit);
            deployCard.add(logCard, BorderLayout.CENTER);
            logFullscreen = true;
            fullscreenToggleButton.setIcon(AllIcons.General.CollapseComponent);
            fullscreenToggleButton.setRolloverIcon(AllIcons.General.CollapseComponentHover);
            fullscreenToggleButton.setToolTipText("退出全屏");
        } else {
            // 退出全屏：把 logCard 还回 rightSplit，恢复 mainSplit
            deployCard.remove(logCard);
            deployCard.add(mainSplit, BorderLayout.CENTER);
            rightSplit.setSecondComponent(logCard);
            logFullscreen = false;
            fullscreenToggleButton.setIcon(AllIcons.General.ExpandComponent);
            fullscreenToggleButton.setRolloverIcon(AllIcons.General.ExpandComponentHover);
            fullscreenToggleButton.setToolTipText("全屏运行日志窗口（占满插件区域）");
        }
        deployCard.revalidate();
        deployCard.repaint();
    }

    /**
     * 展开日志窗口，恢复到折叠前的高度
     */
    private void restoreLogWindow() {
        if (rightSplit == null || !logClosed) {
            return;
        }
        logClosed = false;
        rightSplit.setSecondComponent(logCard);
        rightSplit.setProportion(rightSplitRestoreProportion);
        rightSplit.revalidate();
        rightSplit.repaint();
    }

    // ═══════════════════════════════════════════════════════════════
    //  源工程设置（共享）
    // ═══════════════════════════════════════════════════════════════

    private void setupModule(String modulePath) {
        this.currentModulePath = modulePath;
        sourceSection.setModule(modulePath);

        if (modulePath != null) {
            VirtualFile moduleRoot = LocalFileSystem.getInstance().findFileByPath(modulePath);
            if (moduleRoot != null) {
                currentArtifactFileName = MavenArtifactResolver.resolveArtifactFileName(moduleRoot);
                sourceSection.setArtifact(currentArtifactFileName);
                targetSection.autoSelectTarget(currentArtifactFileName);
            }
        }
    }

    private void onModuleSelected(String modulePath) {
        setupModule(modulePath);
        onModeChanged(sourceSection.getMode());
    }

    private void onModeChanged(DeployMode mode) {
        String modulePath = sourceSection.getCurrentModulePath();
        if (modulePath == null) return;

        if (mode == DeployMode.FULL) {
            // 整包更新模式无文件列表需要刷新；仅输出一次提示
            appendModeHintOnce("[提示] 整包更新模式：将重新编译并替换整个远程包");
            return;
        }
        if (mode == DeployMode.INCREMENTAL) {
            // 增量更新：列出全部可部署文件 + Git 变更默认勾选 + 保留用户已勾选项。
            // 这是原 AUTO_DETECT + INCREMENTAL 两个 UI 模式合并后的统一行为：
            //   - 用户什么都不动 → 等同原 AUTO_DETECT（按 git 变更走）
            //   - 用户取消默认勾选并自己挑 → 等同原 INCREMENTAL（手动选择）
            //   - 用户在默认基础上增删 → 混合模式（合并前做不到）
            VirtualFile moduleRoot = LocalFileSystem.getInstance().findFileByPath(modulePath);
            // 仅在仍是同一个模块时保留用户上一次的手动勾选；切模块时上次的勾选属于旧模块，
            // 文件路径不通用，必须清空，否则会出现"切到新工程默认还勾着旧工程文件"的错觉
            boolean sameModule = modulePath.equals(lastFilesLoadedForModule);
            Set<String> prevSelected = sameModule
                    ? normalizeSelectedPaths(sourceSection.getSelectedFiles())
                    : java.util.Collections.emptySet();
            List<String> changedFiles = moduleRoot != null
                    ? GitChangeDetector.detectChangedFiles(project, moduleRoot)
                    : java.util.Collections.emptyList();
            List<String> allFiles = SourceSectionPanel.scanAllDeployableFiles(modulePath);
            List<String> displayFiles = mergeAutoDetectFiles(allFiles, changedFiles);
            Set<String> selectedPaths = normalizeSelectedPaths(changedFiles);
            selectedPaths.addAll(prevSelected);
            sourceSection.setChangedFiles(displayFiles, false, selectedPaths, true);
            lastFilesLoadedForModule = modulePath;

            if (displayFiles.isEmpty()) {
                appendModeHintOnce("[提示] 模块下未找到可部署文件");
            } else if (changedFiles.isEmpty()) {
                appendModeHintOnce("[提示] 未检测到 Git 变更，已显示 "
                        + displayFiles.size() + " 个可部署文件，可手动勾选");
            } else {
                appendModeHintOnce("[提示] 检测到 " + changedFiles.size()
                        + " 个 Git 变更，已显示 " + displayFiles.size()
                        + " 个可部署文件并默认勾选变更项");
            }
        }
    }

    /**
     * 输出"模式 / 文件状态"类提示，但仅在与上一次完全不同时才追加到日志面板。
     *
     * <p>问题来源：IDEA 在某些场景会让 {@link #onModeChanged} 在毫秒级内被触发两次（比如
     * VFS 刷新连带 module-rootschanged，或 Git tracker 异步回填），两次回调拿到的文件列表
     * 完全一致，原本会让日志里出现一字不差的两行 {@code [提示]} ——纯噪音。</p>
     *
     * <p>仅做"与上一次相同则跳过"的就近 dedup。模块切换或文件状态发生实际变化时提示文本
     * 必然不同，不会被误屏蔽。</p>
     *
     * @param message 即将追加的提示文本
     * @author xumanyi
     * @date 2026-04-30
     */
    private void appendModeHintOnce(String message) {
        if (message.equals(lastModeHint)) return;
        lastModeHint = message;
        logSection.appendLog(message);
    }

    /**
     * 合并自动检测模式下的全量文件和 Git 变更文件。
     *
     * <p>同一路径优先展示 Git 变更状态；删除文件本地已不存在，因此只从 Git 变更结果保留。</p>
     */
    private List<String> mergeAutoDetectFiles(List<String> allFiles, List<String> changedFiles) {
        java.util.Map<String, String> fileByPath = new java.util.LinkedHashMap<>();
        for (String raw : allFiles) {
            fileByPath.put(normalizeDeployFilePath(raw), raw);
        }
        for (String raw : changedFiles) {
            fileByPath.put(normalizeDeployFilePath(raw), raw);
        }

        List<String> result = new java.util.ArrayList<>(fileByPath.values());
        result.sort((a, b) -> normalizeDeployFilePath(a)
                .compareToIgnoreCase(normalizeDeployFilePath(b)));
        return result;
    }

    /**
     * 提取文件列表条目的相对路径，去掉 Git 状态前缀并统一路径分隔符。
     */
    private Set<String> normalizeSelectedPaths(List<String> files) {
        Set<String> paths = new HashSet<>();
        if (files == null) {
            return paths;
        }
        for (String raw : files) {
            paths.add(normalizeDeployFilePath(raw));
        }
        return paths;
    }

    /**
     * 提取单个文件条目的相对路径。
     */
    private String normalizeDeployFilePath(String raw) {
        if (raw == null) {
            return "";
        }
        String path = raw;
        if (path.length() > 2 && path.charAt(1) == ' ') {
            path = path.substring(path.indexOf(' ')).trim();
        }
        return path.replace('\\', '/');
    }

    public void setModuleAndMode(String modulePath, DeployMode mode) {
        setupModule(modulePath);
        sourceSection.setMode(mode);
        onModeChanged(mode);
    }

    public void setFilesAndMode(String modulePath, List<String> filePaths) {
        setupModule(modulePath);
        sourceSection.setMode(DeployMode.INCREMENTAL);

        List<String> allFiles = SourceSectionPanel.scanAllDeployableFiles(modulePath);

        Set<String> selectedPaths = new HashSet<>();
        if (filePaths != null && modulePath != null) {
            String prefix = modulePath.endsWith("/") ? modulePath : modulePath + "/";
            for (String path : filePaths) {
                if (path.startsWith(prefix)) {
                    selectedPaths.add(path.substring(prefix.length()));
                } else {
                    selectedPaths.add(path);
                }
            }
        }

        sourceSection.setChangedFiles(allFiles, false, selectedPaths);
    }

    private JPanel createCardPanel(String title, JComponent content) {
        JPanel panel = new JPanel(new BorderLayout(0, 4));
        panel.setBorder(BorderFactory.createCompoundBorder(
                new RoundedBorder(8), JBUI.Borders.empty(4, 8)));
        JBLabel titleLabel = new JBLabel(title);
        styleCardTitle(titleLabel);
        panel.add(titleLabel, BorderLayout.NORTH);
        panel.add(content, BorderLayout.CENTER);
        return panel;
    }

    /** 卡片标题统一样式：仅加粗，颜色使用主题默认 */
    private static void styleCardTitle(JBLabel label) {
        label.setFont(label.getFont().deriveFont(Font.BOLD, 13f));
    }

    /** 主题感知的主色蓝（亮/暗色自适应） */
    private static Color accentBlue() {
        Color c = UIManager.getColor("Link.activeForeground");
        return c != null ? c : new JBColor(new Color(0x2E7BE0), new Color(0x589DF6));
    }

    /**
     * 装全局鼠标按下监听器，实现"点击输入框外的区域自动取消输入框焦点"。
     *
     * <p>Swing 的 {@link JTextField} 默认只能通过焦点转移释放焦点；点击纯 {@link JPanel}
     * 等不可聚焦区域不会触发焦点转移，用户会看到输入框持续处于高亮 + 光标闪烁的激活状态，
     * 与浏览器 / macOS 原生行为不一致。此方法通过 AWT 全局监听器捕捉 MOUSE_PRESSED，
     * 当点击目标不在可编辑文本组件上时，延后到事件分发完成后清空键盘焦点。</p>
     *
     * <p>通过 {@link SwingUtilities#isDescendingFrom} 将作用域限制在本 ToolWindow
     * 组件子树内，IDE 其他窗口 / 弹窗的鼠标事件一律忽略。</p>
     */
    private void installClickOutsideFocusDrop() {
        // 让根面板可接收焦点——实现"抢焦点"的落点；不能依赖 clearFocusOwner()，
        // IntelliJ 的 IdeFocusManager 会吃掉 clear 调用，真正有效的是"请求新 owner"
        setFocusable(true);

        AWTEventListener listener = event -> {
            if (event.getID() != MouseEvent.MOUSE_PRESSED) {
                return;
            }
            Object src = event.getSource();
            if (!(src instanceof Component)) {
                return;
            }
            Component target = (Component) src;
            // 只处理发生在本 ToolWindow 组件子树内的点击
            if (!SwingUtilities.isDescendingFrom(target, this)) {
                return;
            }
            // 点击对象本身就是可编辑文本组件：让它照常获得焦点
            if (isEditableTextComponent(target)) {
                return;
            }
            // 延后抢焦点，避免打断目标组件自身的 MOUSE_PRESSED 处理（按钮按下动画等）
            SwingUtilities.invokeLater(() -> {
                Component focused = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
                // 只在焦点还停在输入框里时才抢——否则焦点已经被按钮等正常接管，不必打扰
                if (isEditableTextComponent(focused) && isShowing()) {
                    requestFocusInWindow();
                }
            });
        };
        Toolkit.getDefaultToolkit().addAWTEventListener(listener, AWTEvent.MOUSE_EVENT_MASK);
    }

    /** 是否是可编辑的文本输入组件（用于决定"点击目标时是否保留焦点"） */
    private static boolean isEditableTextComponent(Component c) {
        return c instanceof JTextComponent && ((JTextComponent) c).isEditable();
    }

    private static class RoundedBorder extends javax.swing.border.AbstractBorder {
        private final int radius;
        RoundedBorder(int radius) { this.radius = radius; }
        @Override
        public void paintBorder(Component c, Graphics g, int x, int y, int w, int h) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(UIManager.getColor("Component.borderColor"));
            g2.drawRoundRect(x, y, w - 1, h - 1, radius, radius);
            g2.dispose();
        }
        @Override
        public Insets getBorderInsets(Component c) { return JBUI.insets(1); }
    }

    /**
     * 扩展 JBSplitter 拖动命中区的 LayerUI
     *
     * <p>JBSplitter 的 {@code dividerWidth} 同时决定视觉宽度与鼠标命中范围。
     * 默认值 2px 视觉细但极难抓取。本 UI 通过 {@link JLayer} 包一层透明事件拦截：
     * 在 divider 真实位置上下各 {@value #HIT_PADDING} px 的隐形带内同样响应拖动，
     * 视觉宽度保持不变（仅 dividerWidth），命中区扩大到 dividerWidth + 2*HIT_PADDING。</p>
     *
     * <p><b>事件分流</b>：</p>
     * <ul>
     *   <li>鼠标在原 divider 内（onDivider=true）→ 不消费任何事件，让 divider 自身的
     *       hover 高亮、拖动手势、setProportion 逻辑保持原状；</li>
     *   <li>鼠标在扩展带但 divider 外（ghost band）→ MOVED 设 N_RESIZE 光标；
     *       PRESSED 起始拖动并消费；DRAGGED 直接计算并 {@code setProportion}；
     *       RELEASED 结束拖动。消费这些事件可阻止下方面板（树/列表/滚动条）误响应。</li>
     * </ul>
     *
     * <p><b>限制</b>：上下面板内容贴近 divider 边缘的 4px 区域不再触发原本的点击交互
     * （比如树最末行的下边沿）。这部分通常不是交互热点，权衡可接受。</p>
     *
     * @author xumanyi
     * @date 2026-04-30
     */
    private static class ExtendedSplitterHitZoneUI extends javax.swing.plaf.LayerUI<JBSplitter> {
        /** 上下各扩展的命中像素数；总命中带宽 = dividerWidth + 2*HIT_PADDING */
        private static final int HIT_PADDING = 4;
        /** 拖动时夹紧 proportion 的下限 / 上限（保持每侧最少有可见区域） */
        private static final float MIN_PROPORTION = 0.05f;
        private static final float MAX_PROPORTION = 0.95f;

        /** 扩展带触发的拖动会话标志（divider 自身拖动不进入此状态） */
        private boolean dragging;
        /** 上一次被覆盖 cursor 的组件，离开命中区时还原 */
        private Component cursorOverrideOn;

        @Override
        public void installUI(JComponent c) {
            super.installUI(c);
            ((JLayer<?>) c).setLayerEventMask(
                    AWTEvent.MOUSE_EVENT_MASK | AWTEvent.MOUSE_MOTION_EVENT_MASK);
        }

        @Override
        public void uninstallUI(JComponent c) {
            ((JLayer<?>) c).setLayerEventMask(0);
            clearCursorOverride();
            super.uninstallUI(c);
        }

        @Override
        protected void processMouseEvent(MouseEvent e, JLayer<? extends JBSplitter> l) {
            handle(e, l);
        }

        @Override
        protected void processMouseMotionEvent(MouseEvent e, JLayer<? extends JBSplitter> l) {
            handle(e, l);
        }

        /** 统一处理鼠标事件：判断 zone → 设光标 / 启停拖动 / 更新 proportion */
        private void handle(MouseEvent e, JLayer<? extends JBSplitter> l) {
            JBSplitter splitter = l.getView();
            if (splitter == null) return;
            // divider Y/H 用 firstComponent 实际 bounds 推算，避免依赖 Splitter 内部 API
            Component first = splitter.getFirstComponent();
            Component second = splitter.getSecondComponent();
            if (first == null || second == null) return;

            int dividerY;
            int dividerH;
            if (first.isVisible() && second.isVisible()) {
                dividerY = first.getY() + first.getHeight();
                dividerH = second.getY() - dividerY;
                if (dividerH <= 0) dividerH = splitter.getDividerWidth();
            } else {
                // 任一侧折叠时拖动语义不再成立，退出拖动并放弃命中
                if (dragging) dragging = false;
                clearCursorOverride();
                return;
            }

            Component src = (Component) e.getSource();
            Point onSplitter = SwingUtilities.convertPoint(src, e.getPoint(), splitter);
            boolean onDivider = onSplitter.y >= dividerY && onSplitter.y < dividerY + dividerH;
            boolean inExt = onSplitter.y >= dividerY - HIT_PADDING
                    && onSplitter.y < dividerY + dividerH + HIT_PADDING;
            boolean inGhostBand = inExt && !onDivider;

            switch (e.getID()) {
                case MouseEvent.MOUSE_MOVED:
                    if (dragging) return;
                    if (inGhostBand) {
                        setCursorOverride(src,
                                Cursor.getPredefinedCursor(Cursor.N_RESIZE_CURSOR));
                    } else if (!onDivider) {
                        // onDivider 时让 divider 自身处理光标，不在此处还原免抢覆盖
                        clearCursorOverride();
                    }
                    break;
                case MouseEvent.MOUSE_EXITED:
                    if (!dragging) clearCursorOverride();
                    break;
                case MouseEvent.MOUSE_PRESSED:
                    if (inGhostBand) {
                        dragging = true;
                        e.consume();
                    }
                    break;
                case MouseEvent.MOUSE_DRAGGED:
                    if (dragging) {
                        int splitterH = splitter.getHeight();
                        if (splitterH > 0) {
                            float prop = onSplitter.y / (float) splitterH;
                            if (prop < MIN_PROPORTION) prop = MIN_PROPORTION;
                            if (prop > MAX_PROPORTION) prop = MAX_PROPORTION;
                            splitter.setProportion(prop);
                        }
                        e.consume();
                    }
                    break;
                case MouseEvent.MOUSE_RELEASED:
                    if (dragging) {
                        dragging = false;
                        e.consume();
                        // 释放后重新评估当前位置的光标状态
                        if (inGhostBand) {
                            setCursorOverride(src,
                                    Cursor.getPredefinedCursor(Cursor.N_RESIZE_CURSOR));
                        } else if (!onDivider) {
                            clearCursorOverride();
                        }
                    }
                    break;
                default:
                    // 其他事件（CLICKED / ENTERED 等）不处理
                    break;
            }
        }

        /** 在指定组件上覆盖 cursor，并记录以便后续还原（切组件时先还原旧的） */
        private void setCursorOverride(Component c, Cursor cursor) {
            if (cursorOverrideOn != null && cursorOverrideOn != c) {
                try { cursorOverrideOn.setCursor(null); } catch (Exception ignored) {}
            }
            cursorOverrideOn = c;
            try {
                if (!cursor.equals(c.getCursor())) c.setCursor(cursor);
            } catch (Exception ignored) {}
        }

        /** 还原最近一次被覆盖的组件 cursor（setCursor(null) 让其继承父节点默认值） */
        private void clearCursorOverride() {
            if (cursorOverrideOn != null) {
                try { cursorOverrideOn.setCursor(null); } catch (Exception ignored) {}
                cursorOverrideOn = null;
            }
        }
    }
}
