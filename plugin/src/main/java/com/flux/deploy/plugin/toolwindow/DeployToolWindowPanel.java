package com.flux.deploy.plugin.toolwindow;

import com.flux.deploy.email.EmailDraftManager;
import com.flux.deploy.email.EmailTemplateStore;
import com.flux.deploy.model.DeployResult;
import com.flux.deploy.plugin.email.EmailDialog;
import com.flux.deploy.plugin.email.EmailRuntimeData;
import com.flux.deploy.plugin.model.DeployMode;
import com.flux.deploy.plugin.model.DeployTargetMode;
import com.flux.deploy.plugin.model.FtpTargetSelection;
import com.flux.deploy.plugin.model.LocalTargetSelection;
import com.flux.deploy.plugin.model.PluginDeployConfig;
import com.flux.deploy.plugin.service.DeployExecutionService;
import com.flux.deploy.plugin.service.GitChangeDetector;
import com.flux.deploy.plugin.service.LocalPackagePatchService;
import com.flux.deploy.plugin.service.MavenArtifactResolver;
import com.flux.deploy.plugin.util.ArtifactFreshnessValidator;
import com.flux.deploy.plugin.util.ArtifactPresenceValidator;
import com.flux.deploy.plugin.util.BackupTargetGuard;
import com.flux.deploy.plugin.util.DeployRunLogger;
import com.flux.deploy.plugin.util.DeployRunMeta;
import com.flux.deploy.plugin.util.DeployRunStatus;
import com.intellij.icons.AllIcons;
import com.intellij.ide.plugins.PluginManager;
import com.intellij.openapi.application.ApplicationInfo;
import com.intellij.openapi.extensions.PluginId;
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
import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * FLUX 客服更新主面板
 *
 * <p>上下分割：表单区（源+目标+信息+按钮） | 日志区。
 * 目标区通过 Tab 页承载 FTP 模式与本地模式，执行按钮组与流程链路随模式切换。</p>
 *
 * @author xumanyi
 * @date 2026-03-27
 */
public class DeployToolWindowPanel extends JBPanel<DeployToolWindowPanel>
        implements EmailRuntimeData {

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
    /** 日志最小化时右侧分割条的比例：让目标区拿 97%、日志槽位只剩标题栏 */
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

    /**
     * 通知邮件 draft 管理器（in-memory，per-panel = per-project）。
     *
     * <p>持有当前邮件工作副本、监听项目变化 / 重置 / 部署完成事件。
     * 不依赖 IDE Platform，可在测试中独立验证逻辑。</p>
     */
    private final EmailDraftManager emailDraftManager =
            new EmailDraftManager(new EmailTemplateStore());

    /**
     * 部署历史缓存：每次"打包并上传成功"追加一条记录，"重置"按钮清空，IDE 重启自然丢失。
     *
     * <p>邮件弹窗的「导入」按钮按当前 FTP 项目目录过滤本缓存，把命中的所有包名 + 备份目录
     * 写入模板的 {@code ${更新包}} / {@code ${备份包}} 字段。</p>
     */
    private final com.flux.deploy.email.DeployHistoryCache deployHistoryCache =
            new com.flux.deploy.email.DeployHistoryCache();

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
    /** 部署主分割器（左列｜右列），固定 50/50 不可拖动；日志全屏退出时还原回 deployCard */
    private OnePixelSplitter mainSplit;
    /** 日志是否处于插件级全屏状态（占满整个 deployCard） */
    private boolean logFullscreen = false;

    // 日志卡片头部三按钮：清空 / 全屏 / 最小化。
    // 展开态（logCard）与折叠态（logClosedBar）各持有一组（同一 JButton 实例不能跨容器复用），
    // 通过 refreshHeaderIcons 统一刷新 6 个按钮的图标与 tooltip，确保两套头部状态一致。
    private JButton logHeaderClearButton;
    private JButton logHeaderFullscreenButton;
    private JButton logHeaderMinimizeButton;
    private JButton logClosedClearButton;
    private JButton logClosedFullscreenButton;
    private JButton logClosedMinimizeButton;

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
    /**
     * 预检完成后的后续动作（单次消费）。
     *
     * <p>使用场景：用户点击「打包并上传」但尚未预检时，系统会先弹"是否先预检"对话框；
     * 用户选 YES 后跑预检，预检通过后**自动继续**走原本的部署流程。
     * 该字段在 {@link #proceedToDeployOrPreCheck} 设置，在 {@link #executeDeploy} 的
     * onComplete 回调里消费（成功的预检后调用并清空）。</p>
     */
    private Runnable pendingPostPrecheckAction;

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

        // 工具窗口首次打开时静默触发一次配置加载：
        // ~/.flux-deploy/config.toml 不存在时会自动生成默认模板，便于新用户发现可调项。
        // 不需要返回值；任何异常都吞掉（实际部署时还会再次 load 并展示具体错误）。
        try {
            com.flux.deploy.config.UserConfig.load();
        } catch (Throwable ignored) {
            // 配置非法时 panel 仍能正常打开；用户在部署阶段会看到详细错误
        }

        this.backupCheckBox = new JCheckBox("执行备份", true);
        this.sourceSection = new SourceSectionPanel(project);
        this.targetContainer = new TargetContainerPanel(project);
        this.targetSection = targetContainer.getFtpPanel();
        this.infoSection = new InfoSectionPanel(project, backupCheckBox);
        this.logSection = new LogSectionPanel();
        // 横幅：打开 ToolWindow 时在日志顶端记录当前版本，便于排查问题第一眼对版本。
        // 实际打包动作（打包并上传 / 打包不上传 / 本地打包）的起头另带 (vX) 后缀；预检不打。
        logSection.appendLog("INFO  [版本] FLUX Deploy v" + currentPluginVersion());

        // preCheckButton 字段保留：UI 按钮不再添加到面板，但部署内部"先预检后部署"
        // 状态机仍引用其 setEnabled() 做联动（见 setButtonsEnabled / rollback cleanup）。
        // 这是 dead-no-op，保留是为了不破坏现有状态机；后续重构时一并移除。
        this.preCheckButton = new JButton("预检");
        this.deployButton = new JButton("执行更新");
        this.localOnlyButton = new JButton("本地打包");
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
            @Override public String getProjectDir() { return targetSection.getCurrentProjectDir(); }
            @Override public String getFirstTargetRemoteDir() {
                java.util.List<com.flux.deploy.plugin.model.FtpTargetSelection> mts = targetSection.getMainTargets();
                if (mts != null && !mts.isEmpty()) {
                    return mts.get(0).getRemoteDir();
                }
                return null;
            }
        });
        // 项目/系统/连接变化时：清空本次 session 的自定义备份根（避免跨系统误用），
        // 再刷新"备份至"行显示最新默认派生路径。
        // 邮件部署历史缓存不在此处理项目切换——缓存内部按项目目录过滤，跨项目记录互不污染；
        // 用户主动点"重置"按钮才清空整个缓存。
        this.targetSection.setContextChangeCallback(() -> SwingUtilities.invokeLater(() -> {
            this.infoSection.clearSessionBackupRoot();
            this.infoSection.refreshBackupLocationLabel();
        }));

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

        // 日志栏默认展开：首启就能看到「[版本] FLUX Deploy vX.X.X」横幅和后续运行日志，
        // 用户若想腾出纵向空间可手动点日志卡片头部的最小化图标折叠。
    }

    private void initUI() {
        setMinimumSize(new Dimension(520, 400));

        // ═══ 执行卡片：infoSection + 分隔线 + 按钮组（CardLayout 切 FTP / 本地） ═══
        JPanel execContent = new JPanel();
        execContent.setLayout(new BoxLayout(execContent, BoxLayout.Y_AXIS));
        execContent.setBorder(JBUI.Borders.empty(4, 8, 4, 8));

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

        JPanel execCard = createCardPanel("执行操作", execContent);
        // 下排面板自己画 1px 顶边线，跟 deployCard 顶部那条 customLine 同款
        execCard.setBorder(JBUI.Borders.customLine(PanelChromes.splitterColor(), 1, 0, 0, 0));

        // ═══ 目标卡片 ═══
        // targetContainer 自己（JTabbedPane）没有左右 padding，直接交给 createCardPanel
        // 会让内容贴着卡片边框。这里包一层 4/8/4/8 的留白，跟 execContent 同款。
        JPanel targetContent = new JPanel(new BorderLayout());
        targetContent.setBorder(JBUI.Borders.empty(4, 8, 4, 8));
        targetContent.add(targetContainer, BorderLayout.CENTER);
        JPanel targetCard = createCardPanel("部署目标", targetContent);

        // ═══ 日志卡片：标题栏右侧三个图标（清空 / 全屏 / 最小化），三态常驻 ═══
        // 三个按钮在「展开 / 最小化 / 全屏」三态下都常驻显示，每次点击一步到位：
        //   - 清空：始终清空日志正文，不切换状态；
        //   - 全屏：非全屏 → 全屏（含最小化态直达）；全屏 → 还原展开态；
        //   - 最小化：非最小化 → 最小化（含全屏态直达）；最小化 → 展开。
        // 展开态与折叠态各有一组按钮，通过 refreshHeaderIcons 同步图标/tooltip。
        logHeaderClearButton = createHeaderClearButton();
        logHeaderFullscreenButton = createHeaderFullscreenButton();
        logHeaderMinimizeButton = createHeaderMinimizeButton();
        logClosedClearButton = createHeaderClearButton();
        logClosedFullscreenButton = createHeaderFullscreenButton();
        logClosedMinimizeButton = createHeaderMinimizeButton();

        logCard = createCardPanel("运行日志", logSection,
                logHeaderClearButton, logHeaderFullscreenButton, logHeaderMinimizeButton);
        // 与 execCard 同款 1px 顶边线，下排两块面板顶部就是一条连贯的横线
        logCard.setBorder(JBUI.Borders.customLine(PanelChromes.splitterColor(), 1, 0, 0, 0));
        logClosedBar = buildLogClosedBar();

        // 初次按当前状态刷新一遍图标（默认 NORMAL：全屏=⤢、最小化=∨）
        refreshHeaderIcons();

        // ═══ 右列：目标 ↕ 日志 可拖动（带抓取手柄） ═══
        // 上下分割器：dividerWidth=1 + 关掉 controls / icon → 1px 极细线；
        // 视觉上的"上排↔下排"分隔由下排面板自己的 1px 顶边线提供（execCard / logCard 都有 customLine）。
        // 拖动通过 JLayer<ExtendedSplitterHitZoneUI> 扩展的 4px 隐形命中区抓取。
        rightSplit = new JBSplitter(true, RIGHT_SPLIT_DEFAULT_PROPORTION);
        rightSplit.setShowDividerControls(false);
        rightSplit.setShowDividerIcon(false);
        rightSplit.setDividerWidth(1);
        rightSplit.setFirstComponent(targetCard);
        rightSplit.setSecondComponent(logCard);
        rightSplit.setProportion(RIGHT_SPLIT_DEFAULT_PROPORTION);

        // ═══ 左列：源 ↕ 执行 可上下拖动（拖动通过 JLayer 隐形命中区实现） ═══
        JBSplitter leftSplit = new JBSplitter(true, 0.66f);
        leftSplit.setShowDividerControls(false);
        leftSplit.setShowDividerIcon(false);
        leftSplit.setDividerWidth(1);
        // 源工程面板自己渲染圆角外框 + 标题栏 + 状态行（PanelChromes / SourceSectionPanel#initUI），
        // 不再用 createCardPanel 再包一层标题与边框
        leftSplit.setFirstComponent(sourceSection);
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
                // 折叠态走 0.97 让日志槽位只剩标题栏；展开态与左列同款比例，
                // 保证上下分割条 Y 位置一致，视觉上是一条整齐的横线穿过两列。
                rightSplit.setProportion(logClosed ? RIGHT_SPLIT_LOG_CLOSED_PROPORTION : prop);
                done = true;
            }
        });

        // 右列与左列结构对称：JPanel(BorderLayout) + JLayer<JBSplitter>，
        // 这样 mainSplit 给两侧分配的内部高度完全一致，
        // 上下分割条的 Y 位置才能严格对齐，不会出现 1-2px 错位。
        JPanel rightColumn = new JPanel(new BorderLayout());
        rightColumn.add(new JLayer<>(rightSplit, new ExtendedSplitterHitZoneUI()),
                BorderLayout.CENTER);

        // ═══ 主分割：左列 | 右列，OnePixelSplitter 自带 1px 线 ═══
        // 不再硬指定 setResizeEnabled / divider 颜色——保留 IDEA 默认行为，避免不同
        // 平台版本下空内容渲染问题。左右仍是 50/50 起始；拖动用户可以微调。
        mainSplit = new OnePixelSplitter(false, 0.5f);
        mainSplit.setFirstComponent(leftColumn);
        mainSplit.setSecondComponent(rightColumn);

        deployCard = new JPanel(new BorderLayout());
        // 顶部加 1px 线把"IDEA 工具窗 header"和"我们的 4 格内容"分开；
        // 左右下三边交给 IDEA 工具窗自己的窗框做边界，不在这里重复画。
        deployCard.setBorder(BorderFactory.createCompoundBorder(
                JBUI.Borders.customLine(PanelChromes.splitterColor(), 1, 0, 0, 0),
                JBUI.Borders.empty(4)));
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

    /** FTP 模式的按钮行：执行更新 / 本地打包 / 回滚 / 重置 [...glue...] 邮件（贴右边框） */
    private JPanel buildFtpButtons() {
        JPanel p = new JPanel(new GridBagLayout());
        GridBagConstraints gc = new GridBagConstraints();
        // 按钮间距 4px，整体按钮 padding (3,8)；主操作 deployButton 单独 (3,10) 保留权重差
        gc.gridy = 0; gc.insets = JBUI.insets(4, 0, 4, 4);
        gc.anchor = GridBagConstraints.WEST;

        // 注：「预检」按钮已下线 UI（hasPreChecked / pendingPostPrecheckAction 状态机
        // 在部署流程内部仍保留，"先预检再部署"逻辑由 executeDeploy 自驱）

        deployButton.putClientProperty("JButton.buttonType", "default");
        deployButton.setFont(deployButton.getFont().deriveFont(Font.BOLD));
        deployButtonIdleColor = accentBlue();
        deployButton.setForeground(deployButtonIdleColor);
        deployButton.setMargin(JBUI.insets(3, 10));
        deployButton.setToolTipText("生成新包并上传到 FTP");
        gc.gridx = 0; p.add(deployButton, gc);

        localOnlyButton.setMargin(JBUI.insets(3, 8));
        localOnlyButton.setToolTipText("生成新包保存到本地");
        gc.gridx = 1; p.add(localOnlyButton, gc);

        rollbackButton.setMargin(JBUI.insets(3, 8));
        rollbackButton.setToolTipText("回滚上次部署");
        gc.gridx = 2; p.add(rollbackButton, gc);

        resetButton.setMargin(JBUI.insets(3, 8));
        resetButton.setToolTipText("重置所有选择");
        gc.gridx = 3; p.add(resetButton, gc);

        // glue 把"邮件"按钮推到最右
        gc.gridx = 4; gc.weightx = 1.0; gc.fill = GridBagConstraints.HORIZONTAL;
        p.add(Box.createHorizontalGlue(), gc);

        // 邮件按钮贴右：right inset = 0 紧贴 execContent 右内边框
        JButton emailTemplateButton = new JButton("邮件模版");
        emailTemplateButton.setMargin(JBUI.insets(3, 8));
        emailTemplateButton.setToolTipText("打开邮件模板");
        emailTemplateButton.addActionListener(e -> openEmailDialog());
        gc.gridx = 5;
        gc.weightx = 0;
        gc.fill = GridBagConstraints.NONE;
        gc.anchor = GridBagConstraints.EAST;
        gc.insets = JBUI.insets(4, 0, 4, 0);
        p.add(emailTemplateButton, gc);

        return p;
    }

    /** 本地模式的按钮行 */
    private JPanel buildLocalButtons() {
        JPanel p = new JPanel(new GridBagLayout());
        GridBagConstraints gc = new GridBagConstraints();
        // 按钮间距 / padding 跟 buildFtpButtons 对齐，FTP / 本地两套按钮风格统一
        gc.gridy = 0; gc.insets = JBUI.insets(4, 0, 4, 4);
        gc.anchor = GridBagConstraints.WEST;

        localBuildButton.putClientProperty("JButton.buttonType", "default");
        localBuildButton.setFont(localBuildButton.getFont().deriveFont(Font.BOLD));
        localBuildButton.setForeground(accentBlue());
        localBuildButton.setMargin(JBUI.insets(3, 10));
        localBuildButton.setToolTipText("对本地包打补丁生成新包");
        gc.gridx = 0; p.add(localBuildButton, gc);

        localResetButton.setMargin(JBUI.insets(3, 8));
        localResetButton.setToolTipText("清空本地模式选择");
        gc.gridx = 1; p.add(localResetButton, gc);

        gc.gridx = 3; gc.weightx = 1.0; gc.fill = GridBagConstraints.HORIZONTAL;
        p.add(Box.createHorizontalGlue(), gc);
        return p;
    }

    private void initListeners() {
        // ── FTP 模式按钮 ──
        // 注：preCheckButton 已下线 UI，listener 不再注册；"先预检后部署"由 executeDeploy
        // 内部 hasPreChecked / pendingPostPrecheckAction 状态机驱动
        deployButton.addActionListener(e -> {
            // 三态分发：RUNNING 时按钮变身"停止"，弹收尾选择对话框；STOPPING 已请求停止，忽略
            if (deployButtonState == DeployButtonState.RUNNING) {
                logSection.appendLog("INFO  [界面] 点击「停止」");
                showStopDialogAndDispatch();
                return;
            }
            if (deployButtonState == DeployButtonState.STOPPING) {
                return;
            }
            logSection.appendLog("INFO  [界面] 点击「执行更新」");
            // 执行更新：硬性前置校验，任何缺失都弹窗阻断
            List<String> missing = validateFtpPrerequisites();
            if (!missing.isEmpty()) {
                logSection.appendLog("ERROR [界面] 前置条件未满足：" + String.join("；", missing));
                showPrerequisiteDialog(missing, "执行更新");
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
            logSection.appendLog("INFO  [界面] 点击「本地打包」（FTP 模式产出本地包）");
            // 本地打包（FTP 模式）：同样强制校验（需要 FTP 下载远端原包）
            List<String> missing = validateFtpPrerequisites();
            if (!missing.isEmpty()) {
                logSection.appendLog("ERROR [界面] 前置条件未满足：" + String.join("；", missing));
                showPrerequisiteDialog(missing, "本地打包");
                return;
            }
            if (!confirmFtpLocalBuild()) {
                logSection.appendLog("INFO  [界面] 用户取消本地打包");
                return;
            }
            logSection.appendLog("INFO  [界面] 确认本地打包");
            executeDeploy(false, null, true);
        });
        resetButton.addActionListener(e -> resetFtpMode());
        rollbackButton.addActionListener(e -> {
            logSection.appendLog("INFO  [界面] 点击「回滚」");
            doRollback();
        });

        // ── 本地模式按钮 ──
        localBuildButton.addActionListener(e -> {
            logSection.appendLog("INFO  [界面] 点击「本地打包」（本地模式）");
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
        logSection.appendLog("INFO  [界面] 切换至" + mode.getDisplayName());
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

        // 编译产物存在性校验：插件不再触发任何 mvn / IDE 编译；
        // 缺少 .class 或 target/<artifact> 时弹窗提示用户先 Build/mvn package 后重试。
        // 本地模式不存在 WAR 嵌入目标，hasEmbedTargets 恒为 false
        if (!verifyArtifactsOrPrompt(sourceSection.getMode(), files, false)) {
            return;
        }

        // 打包前预检以获取清单供用户确认
        LocalPackagePatchService.PreCheckResult pre = LocalPackagePatchService.preCheck(
                sourceSection.getMode(),
                currentModulePath, currentArtifactFileName, files,
                lt.getPackagePath(), logSection::appendLog);
        if (!pre.isOk()) {
            logSection.appendLog("ERROR [本地] 预检失败：" + pre.getErrorMessage());
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
            logSection.appendLog("INFO  [界面] 用户取消本地打包");
            return;
        }
        logSection.appendLog("INFO  [界面] 确认打包");

        runLocalBuild(lt, files);
    }

    /**
     * 调用 {@link ArtifactPresenceValidator} 校验当前模块的编译产物是否就绪。
     *
     * <p>FULL 模式校验 {@code target/<artifactFileName>} 是否存在；
     * INCREMENTAL 校验勾选清单中每个 .java 是否在 {@code target/classes} 下有对应 .class；
     * INCREMENTAL+嵌入目标 + 空 {@code files} 时才叠加 {@code target/<artifactFileName>} 存在性
     * 校验，对齐 {@code DeployExecutionService.executeWarEmbed} 在 {@code canPatch=false} 时的
     * "整包兜底"约束（{@code files} 非空走 patchExistingJar，含纯静态文件场景，不消费源 artifact）。
     * 缺失时弹窗中止本次操作，仅显示前若干条；用户需手动 Build/mvn package 后重试。</p>
     *
     * @param mode             当前部署模式
     * @param files            勾选清单（含 VCS 状态前缀，由本面板的源 section 直接收集）
     * @param hasEmbedTargets  本次部署是否包含 WAR 嵌入目标；LOCAL 模式恒为 false
     * @return true 表示校验通过、可继续；false 表示已弹窗、调用方应 return
     * @author xumanyi
     * @date 2026-05-07
     */
    private boolean verifyArtifactsOrPrompt(DeployMode mode, List<String> files, boolean hasEmbedTargets) {
        ArtifactPresenceValidator.Result r = ArtifactPresenceValidator.validate(
                mode, currentModulePath, currentArtifactFileName, files, hasEmbedTargets);
        if (r.isOk()) {
            ArtifactFreshnessValidator.Outcome f = ArtifactFreshnessValidator.verifyOrPrompt(
                    this, mode, currentModulePath, currentArtifactFileName, files);
            switch (f.decision) {
                case USER_CONFIRMED_STALE:
                    logSection.appendLog("WARN  [界面] 编译产物比源代码旧 " + f.staleSources.size()
                            + " 个，用户确认继续：");
                    for (String rel : f.staleSources) {
                        logSection.appendLog(com.flux.deploy.plugin.toolwindow.LogSectionPanel.RAW_LINE_MARK
                                + "            " + rel);
                    }
                    return true;
                case CANCELED:
                    logSection.appendLog("INFO  [界面] 用户取消部署：编译产物比源代码旧 "
                            + f.staleSources.size() + " 个");
                    return false;
                case FRESH:
                default:
                    return true;
            }
        }

        logSection.appendLog("ERROR [界面] 编译产物缺失 " + r.missing.size() + " 个，已中止本次操作：");
        for (String rel : r.missing) {
            logSection.appendLog(com.flux.deploy.plugin.toolwindow.LogSectionPanel.RAW_LINE_MARK
                    + "            " + rel);
        }
        logSection.appendLog("INFO [界面] 请手动执行编译/打包后重试");
        JOptionPane.showMessageDialog(this, "编译产物缺失，请手动执行编译/打包后重试。",
                "请手动执行编译/打包", JOptionPane.WARNING_MESSAGE);
        return false;
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

        logSection.appendLog("INFO  [本地] 开始打包... (v" + currentPluginVersion() + ")");
        setLocalButtonsEnabled(false);
        logSection.setProgressVisible(true);

        final DeployRunLogger runLogger = openRunLoggerOrNull(config);
        Consumer<String> teeCallback = line -> {
            logSection.appendLog(line);
            if (runLogger != null) runLogger.info(line);
        };

        DeployExecutionService.executeLocalMode(project, config, teeCallback,
                result -> SwingUtilities.invokeLater(() -> {
                    if (runLogger != null) {
                        runLogger.closeWith(result != null && result.isSuccess()
                                ? DeployRunStatus.OK : DeployRunStatus.FAIL);
                    }
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
            logSection.appendLog("INFO  [本地] 已复制路径到剪贴板");
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
            logSection.appendLog("INFO  [本地] 打开目录失败: " + ex.getMessage());
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
        pendingPostPrecheckAction = null;
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
        pendingPostPrecheckAction = null;
        rollbackButton.setEnabled(false);
        // 部署历史缓存跟随主面板「重置」一起清空：邮件弹窗的「导入」按钮从此显示空
        deployHistoryCache.clear();
    }

    private void doRollback() {
        if (!DeployExecutionService.hasRollbackData()) {
            logSection.appendLog("INFO  [回滚] 没有可回滚的部署记录");
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
            logSection.appendLog("INFO  [界面] 用户取消回滚");
            return;
        }
        logSection.appendLog("INFO  [界面] 确认回滚");

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
        pendingPostPrecheckAction = null;
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
        logSection.appendLog("INFO  [备份] 正在检查当天是否已有备份...");

        com.intellij.openapi.application.ApplicationManager.getApplication()
                .executeOnPooledThread(() -> {
            java.util.List<String> conflicts;
            try {
                // 自定义备份根改为 session 级（不持久化），直接从 InfoSectionPanel 读
                String detectCustomRoot = infoSection.getSessionBackupRoot();
                conflicts = DeployExecutionService.detectExistingBackups(
                        host, port, user, pass, operator, allTargets, detectCustomRoot,
                        msg -> SwingUtilities.invokeLater(() -> logSection.appendLog(msg)));
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> {
                    logSection.appendLog("INFO  [备份] 检查失败（忽略此步骤继续）: " + ex.getMessage());
                    setButtonsEnabled(true);
                    proceedToDeployOrPreCheck();
                });
                return;
            }
            SwingUtilities.invokeLater(() -> {
                setButtonsEnabled(true);
                if (conflicts.isEmpty()) {
                    logSection.appendLog("INFO  [备份] 检查完成：无冲突");
                    proceedToDeployOrPreCheck();
                } else {
                    BackupConflictDialog dialog = new BackupConflictDialog(project, conflicts);
                    if (dialog.showAndGet()) {
                        com.flux.deploy.plugin.model.BackupConflictStrategy strategy =
                                dialog.getSelectedStrategy();
                        if (strategy == null) {
                            logSection.appendLog("INFO  [备份] 未选择处理方式，已取消");
                            return;
                        }
                        pendingBackupStrategy = strategy;
                        logSection.appendLog("INFO  [备份] 冲突处理：" + strategy.getDisplayName()
                                + "（" + conflicts.size() + " 个冲突）");
                        proceedToDeployOrPreCheck();
                    } else {
                        logSection.appendLog("INFO  [备份] 用户取消，未执行更新");
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
    /**
     * 备份冲突检查后的通用入口
     *
     * <p>设计：用户既然已经点了「打包并上传」，就不再弹"是否先预检"对话框打断流程。
     * 同一 session 内首次部署自动跑一次预检（确认 FTP 状态），通过后自动进入部署确认；
     * 预检已跑过则直接进部署确认。</p>
     *
     * @author xumanyi
     * @date 2026-05-02
     */
    private void proceedToDeployOrPreCheck() {
        if (!hasPreChecked) {
            logSection.appendLog("INFO  [界面] 自动执行预检（预检通过后将自动进入部署确认）");
            // 预检通过后自动继续部署，串接成无缝流程。
            // 预检失败时该 action 仍会被消费但不会重复触发部署（onComplete 内会判断 result.isSuccess）。
            pendingPostPrecheckAction = this::showConfirmAndDeploy;
            executeDeploy(true);
            hasPreChecked = true;
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
        html.append("<b>即将执行「本地打包」</b><br><br>");

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
                "本地打包确认",
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

        // 安全前置校验：勾选的目标包是否落在备份目录（backup/backups/bak）下。
        // 命中即先弹窗拦截，避免用户在主确认对话框里走完一长串流程后才被拦下。
        List<BackupTargetGuard.Hit> backupHits = BackupTargetGuard.scan(
                mainTargets, targetSection.getEmbedTargets());
        if (!backupHits.isEmpty()) {
            String msg;
            if (backupHits.size() == 1) {
                BackupTargetGuard.Hit h = backupHits.get(0);
                msg = "勾选的目标包路径包含「" + h.matchedSegment + "」，疑似备份目录。\n\n"
                        + "是否确认更新？";
            } else {
                msg = "勾选的 " + backupHits.size() + " 个目标包路径疑似备份目录。\n\n"
                        + "是否确认更新？";
            }
            int warn = JOptionPane.showConfirmDialog(this, msg,
                    "目标包疑似备份目录", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
            if (warn != JOptionPane.YES_OPTION) {
                logSection.appendLog("INFO  [界面] 用户取消（目标包位于备份目录的安全警告）");
                return;
            }
            logSection.appendLog("WARN  [界面] 用户确认：将更新到备份目录下的目标包");
        }

        DeployConfirmDialog dialog = new DeployConfirmDialog(
                project, targetPkg, remotePath, mode, files);

        if (dialog.showAndGet()) {
            logSection.appendLog("INFO  [界面] 部署确认对话框：确认");

            if (!backupCheckBox.isSelected()) {
                int warn = JOptionPane.showConfirmDialog(this,
                        "⚠ 未勾选「执行备份」，更新失败后将无法自动回滚！\n\n"
                        + "确定不备份直接更新到 FTP？",
                        "安全警告", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
                if (warn != JOptionPane.YES_OPTION) {
                    logSection.appendLog("INFO  [界面] 用户取消（未勾选备份的安全警告）");
                    return;
                }
                logSection.appendLog("INFO  [界面] 用户确认：不备份直接更新");
            }
            List<String> selectedFiles = dialog.getSelectedFiles();
            executeDeploy(false, selectedFiles);
        } else {
            logSection.appendLog("INFO  [界面] 用户取消部署确认");
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
        // 版本后缀只在"实际打包"动作里加（打包并上传 / 打包不上传），预检不打：预检不消费本地产物
        String roundLabel = dryRun ? "预检" : localOnly ? "本地打包" : "执行更新";
        String versionSuffix = dryRun ? "" : " (v" + currentPluginVersion() + ")";
        logSection.appendLog("INFO  [界面] 开始新一轮" + roundLabel + versionSuffix);

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

        // 注入用户在本次 session 内选定的自定义备份根（仅 session 级，不持久化）
        String sessionBackupRoot = infoSection.getSessionBackupRoot();
        if (sessionBackupRoot != null && !sessionBackupRoot.isBlank()) {
            pluginConfig.setCustomBackupRoot(sessionBackupRoot);
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
            logSection.appendLog("ERROR [界面] 请先选择工程");
            return;
        }
        if (!targetSection.isFtpConnected()) {
            logSection.appendLog("ERROR [界面] FTP 未连接，请先点击连接按钮");
            return;
        }
        if (pluginConfig.getMainTargets().isEmpty()
                && (pluginConfig.getEmbedTargets() == null || pluginConfig.getEmbedTargets().isEmpty())) {
            logSection.appendLog("ERROR [界面] 请选择目标（项目 / 系统 / 目标包）");
            return;
        }
        if (updateNote) {
            boolean taskEmpty = pluginConfig.getTaskId() == null || pluginConfig.getTaskId().isEmpty();
            boolean customerEmpty = pluginConfig.getCustomerId() == null || pluginConfig.getCustomerId().isEmpty();
            if (taskEmpty && customerEmpty) {
                logSection.appendLog("ERROR [界面] 勾选了更新版本记录，请至少填写任务或客服之一");
                return;
            }
        }
        boolean needOperator = updateNote || backupCheckBox.isSelected();
        if (needOperator
                && (pluginConfig.getOperator() == null || pluginConfig.getOperator().isEmpty())) {
            logSection.appendLog("ERROR [界面] 勾选了版本记录或执行备份，请填写开发");
            return;
        }

        // 编译产物存在性校验：dryRun 不需要本地产物，仅做 FTP 状态预检；其他流程必须要求
        // target/<artifact> 与 .class 提前就绪（插件不再触发任何编译）。
        // INCREMENTAL+嵌入目标 时 validator 会额外校验 target/<artifact>，对齐 executeWarEmbed
        // 的"整包兜底"约束，避免到嵌入阶段才报"本地 JAR 文件不存在"。
        boolean hasEmbedTargetsForValidate = pluginConfig.getEmbedTargets() != null
                && !pluginConfig.getEmbedTargets().isEmpty();
        if (!dryRun && !verifyArtifactsOrPrompt(pluginConfig.getMode(),
                pluginConfig.getChangedFiles(), hasEmbedTargetsForValidate)) {
            return;
        }

        infoSection.saveToCache();

        enterRunningState();
        logSection.setProgressVisible(true);

        final DeployRunLogger runLogger = openRunLoggerOrNull(pluginConfig);
        Consumer<String> teeCallback = line -> {
            logSection.appendLog(line);
            if (runLogger != null) runLogger.info(line);
        };

        DeployExecutionService.execute(project, pluginConfig,
                targetSection.getConnectedHost(), targetSection.getConnectedPort(),
                targetSection.getConnectedUsername(), targetSection.getConnectedPassword(),
                teeCallback,
                result -> SwingUtilities.invokeLater(() -> {
                    if (runLogger != null) {
                        runLogger.closeWith(resolveRunStatus(result));
                    }
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

                    // 通知邮件「导入」缓存累积：只有"打包并上传"且整体成功才记。
                    // 直接读 pluginConfig.getMainTargets() + getEmbedTargets()——这是用户实际选的所有目标，
                    // 而 result.getTargets() 在多主目标循环里会被反复覆盖（DeployExecutionService:1350），
                    // 又不含 embed 阶段处理的 war 目标，会漏记。
                    if (result != null && result.isSuccess() && !dryRun && !localOnly) {
                        List<String> packagePaths = new ArrayList<>();
                        java.util.List<com.flux.deploy.plugin.model.FtpTargetSelection> mts =
                                pluginConfig.getMainTargets();
                        if (mts != null) {
                            for (com.flux.deploy.plugin.model.FtpTargetSelection mt : mts) {
                                if (mt == null) continue;
                                String dir = mt.getRemoteDir();
                                String rel = mt.getRelativePath();
                                if (dir == null) dir = "";
                                if (rel == null) rel = "";
                                packagePaths.add(dir + rel);
                            }
                        }
                        java.util.List<com.flux.deploy.plugin.model.FtpTargetSelection> ets =
                                pluginConfig.getEmbedTargets();
                        if (ets != null) {
                            for (com.flux.deploy.plugin.model.FtpTargetSelection et : ets) {
                                if (et == null) continue;
                                String dir = et.getRemoteDir();
                                String rel = et.getRelativePath();
                                if (dir == null) dir = "";
                                if (rel == null) rel = "";
                                packagePaths.add(dir + rel);
                            }
                        }
                        // 备份目录：勾选了"备份"才记；否则留空表示没有备份
                        String backupDir = backupCheckBox.isSelected()
                                ? computeBackupRootForEmail() : null;
                        deployHistoryCache.recordDeploy(
                                targetSection.getCurrentProjectDir(), packagePaths, backupDir);
                    }

                    // 消费"预检后自动继续部署"的 pending action：
                    // 仅在预检 + 通过 + 非本地打包 时触发，避免在常规部署完成后误触
                    Runnable postAction = pendingPostPrecheckAction;
                    pendingPostPrecheckAction = null;
                    if (dryRun && !localOnly && result != null && result.isSuccess()
                            && postAction != null) {
                        logSection.appendLog("INFO  [界面] 预检通过，自动进入部署确认");
                        postAction.run();
                    }
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
        deployButton.setToolTipText("停止当前部署");
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
        deployButton.setText("执行更新");
        deployButton.setForeground(deployButtonIdleColor);
        deployButton.setToolTipText("生成新包并上传到 FTP");
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
            logSection.appendLog("INFO  [界面] 用户取消停止，继续部署");
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
        logSection.appendLog("INFO  [界面] 请求停止：" + (
                mode == DeployExecutionService.CancelMode.KEEP_SUCCEEDED
                        ? "保留已成功的包" : "回滚已成功的包"));
        DeployExecutionService.requestStop(mode);
        enterStoppingState();
    }

    /**
     * 创建「清空」按钮（展开态 / 折叠态各一份）。
     *
     * <p>点击始终清空日志正文，不切换面板状态。</p>
     *
     * @return 已挂监听的按钮实例
     */
    private JButton createHeaderClearButton() {
        JButton btn = new JButton(AllIcons.Actions.GC);
        PanelChromes.styleHeaderIconButton(btn);
        btn.setToolTipText("清空运行日志");
        btn.addActionListener(e -> logSection.clear());
        return btn;
    }

    /**
     * 创建「全屏 / 退出全屏」按钮（展开态 / 折叠态各一份）。
     *
     * <p>图标 / Tooltip 由 {@link #refreshHeaderIcons} 按当前状态统一刷新。</p>
     *
     * @return 已挂监听的按钮实例
     */
    private JButton createHeaderFullscreenButton() {
        JButton btn = new JButton();
        PanelChromes.styleHeaderIconButton(btn);
        btn.addActionListener(e -> toggleLogFullscreen());
        return btn;
    }

    /**
     * 创建「最小化 / 展开」按钮（展开态 / 折叠态各一份）。
     *
     * <p>图标 / Tooltip 由 {@link #refreshHeaderIcons} 按当前状态统一刷新。</p>
     *
     * @return 已挂监听的按钮实例
     */
    private JButton createHeaderMinimizeButton() {
        JButton btn = new JButton();
        PanelChromes.styleHeaderIconButton(btn);
        btn.addActionListener(e -> toggleLogMinimized());
        return btn;
    }

    /**
     * 按当前 {@code logFullscreen} / {@code logClosed} 状态，刷新展开态与折叠态
     * 两套按钮共 6 个的图标与 Tooltip，保证视觉与点击语义同步。
     *
     * <p>规则：</p>
     * <ul>
     *   <li>清空：图标恒为 GC，不随状态变化。</li>
     *   <li>全屏：全屏态显示 ⤡（CollapseComponent），其他状态显示 ⤢（ExpandComponent）。</li>
     *   <li>最小化：展开 / 全屏态显示 IDEA 标准 HideToolWindow（向下收起），最小化态
     *       显示 ArrowUp（向上展开）——和 IDEA 工具窗 hide/show 同款语义。</li>
     * </ul>
     */
    private void refreshHeaderIcons() {
        Icon fsIcon = logFullscreen ? AllIcons.General.CollapseComponent
                                    : AllIcons.General.ExpandComponent;
        Icon fsHover = logFullscreen ? AllIcons.General.CollapseComponentHover
                                     : AllIcons.General.ExpandComponentHover;
        String fsTip = logFullscreen ? "退出全屏" : "全屏显示日志";
        for (JButton b : new JButton[]{logHeaderFullscreenButton, logClosedFullscreenButton}) {
            if (b == null) continue;
            b.setIcon(fsIcon);
            b.setRolloverIcon(fsHover);
            b.setToolTipText(fsTip);
        }

        Icon minIcon = logClosed ? AllIcons.General.ArrowUp
                                 : AllIcons.General.HideToolWindow;
        String minTip = logClosed ? "展开日志窗口" : "最小化日志窗口";
        for (JButton b : new JButton[]{logHeaderMinimizeButton, logClosedMinimizeButton}) {
            if (b == null) continue;
            b.setIcon(minIcon);
            b.setRolloverIcon(minIcon);
            b.setToolTipText(minTip);
        }
    }

    /**
     * 构造日志最小化态的折叠条。
     *
     * <p>结构与 {@code logCard} 头部完全一致：1px 顶边线 + 标题栏（含同款三按钮），
     * 视觉上像是 logCard 的标题栏单独保留下来。三按钮与展开态分别持有各自的 JButton
     * 实例（同一 JButton 不能跨容器复用），状态通过 {@link #refreshHeaderIcons} 同步。</p>
     *
     * @return 折叠条面板
     */
    private JPanel buildLogClosedBar() {
        JPanel wrap = new JPanel(new BorderLayout());
        wrap.setBorder(JBUI.Borders.customLine(PanelChromes.splitterColor(), 1, 0, 0, 0));
        wrap.add(PanelChromes.buildTitleBar("运行日志", null,
                        logClosedClearButton, logClosedFullscreenButton, logClosedMinimizeButton),
                BorderLayout.NORTH);
        return wrap;
    }

    /**
     * 「最小化」按钮的统一动作：当前已最小化 → 展开；否则 → 最小化。
     *
     * <p>从全屏态直接点最小化按钮也走这里：{@link #closeLogWindow} 会先关闭全屏再折叠，
     * 用户视觉上是一次点击完成「全屏 → 折叠」。</p>
     */
    private void toggleLogMinimized() {
        if (logClosed) {
            restoreLogWindow();
        } else {
            closeLogWindow();
        }
    }

    /**
     * 折叠日志窗口，只保留顶部折叠条。
     *
     * <p>关键：必须把 rightSplit 比例改成 {@link #RIGHT_SPLIT_LOG_CLOSED_PROPORTION}（=0.97），
     * 否则 logClosedBar 会继承之前的 34% 槽位高度，看起来是「标题栏 + 大片空白」，
     * 而不是真正的最小化条。</p>
     *
     * <p>从全屏态进入折叠态时，先把 logCard 从 deployCard 摘回 mainSplit，再把
     * rightSplit 第二槽换成 logClosedBar——一次 repaint 完成「全屏 → 折叠」。</p>
     */
    private void closeLogWindow() {
        if (rightSplit == null || logClosed) {
            return;
        }
        boolean wasFullscreen = logFullscreen;
        if (logFullscreen) {
            deployCard.remove(logCard);
            deployCard.add(mainSplit, BorderLayout.CENTER);
            logFullscreen = false;
            deployCard.revalidate();
        }
        // 仅 NORMAL→MIN 时刷新展开比例：从全屏直达折叠时 rightSplit 在 FS 期间未显示，
        // 其 getProportion() 可能仍是上一轮 MIN 残留的 0.97（来源：MIN→FS 不重置比例）；
        // 若误把 0.97 当成"展开比例"保存，再次展开就只剩 3% 高度，看起来像日志栏无法打开。
        // 此时直接复用 toggleLogFullscreen 在进入 FS 前已记录的 rightSplitRestoreProportion。
        if (!wasFullscreen) {
            rightSplitRestoreProportion = rightSplit.getProportion();
        }
        logClosed = true;
        rightSplit.setSecondComponent(logClosedBar);
        rightSplit.setProportion(RIGHT_SPLIT_LOG_CLOSED_PROPORTION);
        rightSplit.revalidate();
        rightSplit.repaint();
        deployCard.repaint();
        refreshHeaderIcons();
    }

    /**
     * 切换日志全屏：全屏时 logCard 占满 deployCard；退出全屏一律回到 NORMAL 态。
     *
     * <p>从最小化态点全屏按钮也走这里：直接把 rightSplit 第二槽位换成空 panel，
     * logCard 搬进 deployCard，一次点击就到全屏，不经过中间「先展开 → 再点全屏」。</p>
     */
    private void toggleLogFullscreen() {
        if (deployCard == null || logCard == null) {
            return;
        }
        if (logFullscreen) {
            // 退出全屏：logCard 还回 rightSplit，恢复 NORMAL 态
            deployCard.remove(logCard);
            deployCard.add(mainSplit, BorderLayout.CENTER);
            rightSplit.setSecondComponent(logCard);
            rightSplit.setProportion(rightSplitRestoreProportion);
            logFullscreen = false;
            logClosed = false;
        } else {
            // 进入全屏前先把"当前展开比例"记到 rightSplitRestoreProportion，退出全屏 / 折叠时还原。
            // 仅 NORMAL 态记录：MINIMIZED 态此时 rightSplit 比例是临时占位值 0.97，
            // 不能当作展开比例保存——否则后续 MIN→FS→MIN→展开会只剩 3% 高度。
            if (!logClosed) {
                rightSplitRestoreProportion = rightSplit.getProportion();
            }
            // rightSplit 第二槽位先填空 panel 占位（无论当前是 NORMAL 还是
            // MINIMIZED 都直接覆盖），logCard 移入 deployCard
            rightSplit.setSecondComponent(new JPanel());
            deployCard.remove(mainSplit);
            deployCard.add(logCard, BorderLayout.CENTER);
            logFullscreen = true;
            logClosed = false;
        }
        refreshHeaderIcons();
        deployCard.revalidate();
        deployCard.repaint();
    }

    /**
     * 展开日志窗口，恢复到折叠前的高度。
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
        refreshHeaderIcons();
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
            // expandChangedFoldersOnly：有 Git 变更时只展开变更分支（保留原行为）；
            // 无 Git 变更时让面板全部展开，避免用户拿到一个全部折叠的列表还得手动一层层点开。
            sourceSection.setChangedFiles(displayFiles, false, selectedPaths, !changedFiles.isEmpty());
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

    /**
     * 复用 {@link PanelChromes#buildTitleBar} 给「部署目标 / 执行操作 / 运行日志」三张卡套上
     * 与「源工程」同款的 chrome（主色短条 + 14pt 粗体 + 底分隔线）。
     *
     * <p>外框边框统一交给后续 Step 4 决策——这里只负责标题栏 + 内容区组合。</p>
     */
    private static JPanel createCardPanel(String title, JComponent content,
                                          JComponent... headerActions) {
        // 不画独立圆角外框：四块面板紧贴主 / 上下分割器的 1px 线条做区域分隔，
        // 每张卡片只靠标题栏底分隔线圈出 header 区。
        JPanel panel = new JPanel(new BorderLayout());
        panel.add(PanelChromes.buildTitleBar(title, null, headerActions), BorderLayout.NORTH);
        panel.add(content, BorderLayout.CENTER);
        return panel;
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

    /**
     * 打开 DeployRunLogger，失败时返回 null（不阻塞部署）。
     *
     * <p>失败原因不写工具窗口，避免给用户造成"日志失败 = 部署失败"的误读；
     * IDE 的 idea.log 会有底层 IOException 留痕。</p>
     *
     * @param pluginConfig 插件配置（用于构造 meta）
     * @return 已打开的 logger，meta 不可识别或 IO 失败时为 null
     * @author xumanyi
     * @date 2026-05-07
     */
    private static DeployRunLogger openRunLoggerOrNull(PluginDeployConfig pluginConfig) {
        DeployRunMeta meta = buildRunMeta(pluginConfig);
        if (meta == null) return null;
        try {
            return DeployRunLogger.open(meta);
        } catch (IOException ioe) {
            return null;
        }
    }

    /**
     * 从 IDEA 插件管理器读取本插件版本号，用于日志开头横幅、每次操作起头后缀以及
     * RunLogger 元数据三处共享。
     *
     * <p>取不到 descriptor（极少数沙盒/调试态）时回退 {@code "unknown"}，让日志仍然可读，
     * 而不是抛 NPE 中断面板初始化。</p>
     *
     * @return 形如 {@code "1.2.8"} 的版本字符串
     * @author xumanyi
     * @date 2026-05-08
     */
    private static String currentPluginVersion() {
        var plugin = PluginManager.getInstance().findEnabledPlugin(PluginId.getId("com.flux.deploy.plugin"));
        return plugin != null ? plugin.getVersion() : "unknown";
    }

    /**
     * 从 PluginDeployConfig 提取一次部署的元数据，用于 DeployRunLogger.open。
     *
     * <p>包名拿法按目标模式分支：FTP 模式取 mainTargets[0].targetName，
     * 本地模式取 localTarget.packagePath 的文件名部分。</p>
     *
     * @param pluginConfig 插件配置
     * @return 元数据；packageFileName 为 null 表示无法识别
     * @author xumanyi
     * @date 2026-05-07
     */
    private static DeployRunMeta buildRunMeta(PluginDeployConfig pluginConfig) {
        String packageFileName = null;
        String remoteTarget = "";
        if (pluginConfig.getTargetMode() == DeployTargetMode.LOCAL
                && pluginConfig.getLocalTarget() != null) {
            String pkgPath = pluginConfig.getLocalTarget().getPackagePath();
            if (pkgPath != null && !pkgPath.isBlank()) {
                packageFileName = Path.of(pkgPath).getFileName().toString();
                remoteTarget = "local:" + pkgPath;
            }
        } else if (pluginConfig.getTarget() != null) {
            packageFileName = pluginConfig.getTarget().getTargetName();
            remoteTarget = pluginConfig.getTarget().getRemoteDir();
        }
        if (packageFileName == null || packageFileName.isBlank()) {
            return null;
        }

        String ideVersion = "IntelliJ IDEA " + ApplicationInfo.getInstance().getFullVersion();
        String pluginVersion = "flux-deploy-plugin (unknown)";
        var plugin = PluginManager.getInstance().findEnabledPlugin(PluginId.getId("com.flux.deploy.plugin"));
        if (plugin != null) {
            pluginVersion = "flux-deploy-plugin " + plugin.getVersion();
        }
        return new DeployRunMeta(
                packageFileName,
                pluginConfig.getOperator() == null ? "" : pluginConfig.getOperator(),
                remoteTarget,
                ideVersion,
                pluginVersion,
                LocalDateTime.now()
        );
    }

    /**
     * 把 DeployResult 映射成 DeployRunStatus，作为日志文件名后缀。
     *
     * @param result 部署结果（可能为 null：用户配置非法等提前返回路径）
     * @return 终态（OK / FAIL / FAIL_RB / CANCEL）
     * @author xumanyi
     * @date 2026-05-07
     */
    private static DeployRunStatus resolveRunStatus(DeployResult result) {
        if (result == null) return DeployRunStatus.FAIL;
        if (result.isCancelled()) return DeployRunStatus.CANCEL;
        if (result.isSuccess()) return DeployRunStatus.OK;
        DeployResult.RollbackResult rb = result.getRollback();
        if (rb != null && rb.isAttempted() && rb.isSuccess()) return DeployRunStatus.FAIL_RB;
        return DeployRunStatus.FAIL;
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

    // ============================================================
    //  通知邮件入口
    // ============================================================

    /**
     * 打开「通知邮件」弹窗（由 {@code ShowEmailAction} 标题栏图标触发）
     *
     * <p>弹窗会从 {@link #emailDraftManager} 取出当前 draft；若 draft 为空（首次打开 /
     * 项目刚切 / 重置后），用主面板当前字段值 + 选中模板渲染一个新 draft。</p>
     *
     * @author xumanyi
     * @date 2026-05-17
     */
    public void openEmailDialog() {
        if (!com.intellij.ui.jcef.JBCefApp.isSupported()) {
            com.intellij.openapi.ui.Messages.showWarningDialog(
                    project,
                    "通知邮件需要 IDE 内置浏览器（JCEF）支持。当前检测到 JCEF 不可用。\n\n"
                            + "请到 Help → Find Action → Registry，把 ide.browser.jcef.enabled 设为 true，"
                            + "重启 IDE 后再试。",
                    "JCEF 不可用");
            return;
        }
        new EmailDialog(project, emailDraftManager, this, deployHistoryCache).show();
    }


    /**
     * 实现 {@link EmailRuntimeData#collectFieldValues()}：从主面板字段拼一个 key → 值的 map
     *
     * <p>插件提供的字段：{@code 任务} / {@code 客服}（来自主面板的"任务号""客服号"输入框）。
     * 模板里其他占位符（如 {@code 更新包} / {@code 备份包} / {@code 项目} /
     * {@code 资源来源} 等）：</p>
     * <ul>
     *   <li>{@code 更新包} / {@code 备份包} / {@code 项目}：由邮件弹窗的「导入」按钮从
     *       {@link com.flux.deploy.email.DeployHistoryCache} 拉取，跟本方法返回的
     *       任务 / 客服 合并后一并刷到对应 chip</li>
     *   <li>其他：模板里的字面占位符，在编辑器里以空 chip 形态显示，由用户手填</li>
     * </ul>
     *
     * <p><b>调用时机</b>：仅在邮件弹窗的「导入」按钮回调里调用一次（弹窗打开时<b>不</b>调用），
     * 避免主面板的任务 / 客服 在用户没点导入前就提前出现在邮件 chip 里。</p>
     *
     * @return 字段值字典
     * @author xumanyi
     * @date 2026-05-18
     */
    @Override
    public Map<String, String> collectFieldValues() {
        // 只填"用户在主面板表单输入的字段"——任务、客服。
        // 项目 / 备份包 / 更新包 都是"部署后才有的数据"，跟 任务 / 客服 一起由邮件弹窗
        // 的「导入」按钮拉取 —— 用户没点导入前 chip 一律保持占位符。
        Map<String, String> v = new HashMap<>();
        v.put("任务", nullToEmpty(infoSection.getTaskId()));
        v.put("客服", nullToEmpty(infoSection.getCustomerId()));
        return v;
    }

    /**
     * 实现 {@link EmailRuntimeData#getCurrentSelectedPackageNames()}：
     * 返回主面板当前勾选的所有目标包文件名列表（按勾选顺序）。
     *
     * <p>由邮件弹窗的「导入选中包」按钮调用，把当前选择追加到 draft 的
     * {@code ${更新包}} 锚点。</p>
     *
     * @return 包文件名列表（永不为 null）
     * @author xumanyi
     * @date 2026-05-17
     */
    @Override
    public List<String> getCurrentSelectedPackageNames() {
        List<String> result = new ArrayList<>();
        List<FtpTargetSelection> mts = targetSection.getMainTargets();
        if (mts != null) {
            for (FtpTargetSelection mt : mts) {
                if (mt == null) continue;
                String name = mt.getTargetName();
                if (name != null && !name.isBlank()) {
                    result.add(name);
                }
            }
        }
        return result;
    }


    @Override
    public String getCurrentProjectDir() {
        return targetSection.getCurrentProjectDir();
    }

    /**
     * 计算邮件正文里"备份包"字段应填什么
     *
     * <p>优先用 {@code InfoSectionPanel.sessionBackupRoot}（用户在备份位置弹窗里
     * 自定义过的路径）；否则用 InfoSectionPanel 内部 {@code computeDefaultBackupRoot}
     * 的派生规则。这里直接调用主面板对外暴露的 {@code refreshBackupLocationLabel}
     * 不合适——那是 UI 刷新方法，没返回值。简化处理：复用 sessionBackupRoot 即可，
     * 默认派生路径由邮件正文的接收方（顾问）按惯例理解。</p>
     *
     * @return 备份包路径或空串
     * @author xumanyi
     * @date 2026-05-17
     */
    private String computeBackupRootForEmail() {
        String custom = infoSection.getSessionBackupRoot();
        if (custom != null && !custom.isBlank()) return custom;
        // 默认派生：跟 InfoSectionPanel.computeDefaultBackupRoot 同款规则
        List<FtpTargetSelection> mts = targetSection.getMainTargets();
        String basePath;
        if (mts != null && !mts.isEmpty() && mts.get(0).getRemoteDir() != null) {
            basePath = mts.get(0).getRemoteDir();
        } else {
            basePath = targetSection.getCurrentContextDir();
        }
        if (basePath == null || basePath.isBlank()) return "";
        String trimmed = basePath.replaceAll("^/+", "").replaceAll("/+$", "");
        String[] parts = trimmed.split("/");
        String systemRoot = parts.length >= 3
                ? "/" + parts[0] + "/" + parts[1] + "/" + parts[2] + "/"
                : "/" + trimmed + "/";
        // 备份目录格式与 DeployExecutionService 保持一致：yyyyMMdd_<operator>
        String today = java.time.LocalDate.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd"));
        String operator = nullToEmpty(infoSection.getOperator());
        return systemRoot + "backup/" + today + "_" + operator + "/";
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
