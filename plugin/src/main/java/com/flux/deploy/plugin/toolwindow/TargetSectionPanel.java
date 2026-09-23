package com.flux.deploy.plugin.toolwindow;

import com.flux.deploy.plugin.model.FtpTargetSelection;
import com.flux.deploy.plugin.service.FtpBrowseService;
import com.flux.deploy.plugin.service.FtpBrowseService.PackageInfo;
import com.flux.deploy.plugin.service.FtpBrowseService.ScanResult;
import com.flux.deploy.plugin.util.CredentialBridge;
import com.flux.deploy.util.CredentialCache;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.util.ui.JBFont;
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
import javax.swing.event.TreeExpansionEvent;
import javax.swing.event.TreeExpansionListener;
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
 * <p>目标包树不做任何过滤隐藏：FTP 上扫描到的 JAR/WAR 与目录全部展示，由用户手动勾选。
 * 与源产物精确同名的包默认勾选（仅为省去手动查找，可自由取消）。</p>
 * <p>目录按层懒加载：选系统只 LIST 根层，目录默认折叠、首次展开时才拉取该层内容
 * （见 {@link #startLoadLevel}），不再全量递归扫描整个系统子树。</p>
 * <p>JAR 源产物：勾选的 JAR 直接覆盖（主目标），勾选的 WAR 作嵌入更新</p>
 * <p>WAR 源产物：勾选的 WAR 直接覆盖（主目标），不要求与源产物同名</p>
 *
 * <p>支持 FTP 自动连接（复用 CLI 凭据缓存）、项目/系统级联选择、
 * 目标包树形多选、关键字搜索过滤等功能。</p>
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
    private String sourceArtifactType;  // JAR / WAR / ZIP（ZIP = Vue 模块更新包）

    /** Vue 源工程上下文名（serve.yaml 的 content 值）；非 Vue 源为 null */
    private String vueContent;
    /** Vue 源选中的模块号列表（决定模块目录条目的合成与默认勾选）；非 Vue 源为空 */
    private List<String> vueSelectedModuleIds = List.of();
    /** Vue 源工程本地根目录（合并视图取本地产物用）；非 Vue 源为 null */
    private String vueProjectRootPath;
    /** Vue 模块文件合并视图缓存：模块目录 relativePath → 行列表（未加载则无键） */
    private final Map<String, List<VueFileRow>> vueFileRowsByModule = new HashMap<>();
    /** 正在加载文件视图的模块目录（防重复 FTP 请求） */
    private final Set<String> vueFileLoading = new HashSet<>();
    /** 被用户取消勾选的产物文件（模块目录 relativePath → rel 集合），排除式选择 */
    private final Map<String, Set<String>> vueFileUnchecked = new HashMap<>();
    /** 用户手动折叠过的模块文件视图（自动展开逻辑尊重用户的折叠操作，不再反复弹开） */
    private final Set<String> collapsedVueModules = new HashSet<>();
    /** 服务包目录自动探测是否已发起（每次换系统/源重置） */
    private boolean vueContentSearchStarted;
    /** 服务包目录自动探测的结局（Vue 更新只认服务包目录：未找到 / 探测失败都不生成任何目标） */
    private VueSearchOutcome vueContentSearchOutcome = VueSearchOutcome.NONE;
    /** 探测失败原因（超时 / 断连等，仅 FAILED 时有值），随探测结局一起呈现给用户 */
    private String vueContentSearchFailReason;
    /** 探测已扫描的目录数（进度展示与结局说明用） */
    private int vueContentSearchScanned;
    /** 探测是否因触及目录数上限而未穷尽（NOT_FOUND 时的补充说明） */
    private boolean vueContentSearchTruncated;
    /** 自动探测的访问目录数上限（防止在超大目录树上失控） */
    private static final int VUE_SEARCH_MAX_DIRS = 300;
    /** 自动探测的相对路径深度上限（segments 数） */
    private static final int VUE_SEARCH_MAX_DEPTH = 3;

    /**
     * 服务包目录自动探测的结局。
     *
     * <p>Vue 更新的目标只有一种形态——服务包解包目录。探测只有两种结果：定位到目录
     * （合成模块目录条目），或明确失败（未找到 / 网络中断）；失败不做任何兜底，
     * 由用户刷新重试或手动展开目标树勾选模块目录。</p>
     *
     * @author xumanyi
     * @date 2026-09-22
     */
    enum VueSearchOutcome {
        /** 尚未探测或探测进行中 */
        NONE,
        /** 已定位到服务包目录 */
        FOUND,
        /** 探测完整跑完（或触及上限）仍未找到 */
        NOT_FOUND,
        /** 探测中途因 FTP 异常（超时 / 断连）中止 */
        FAILED
    }

    /**
     * 一次服务包目录探测的结果（工作线程产出，EDT 消费）。
     *
     * @author xumanyi
     * @date 2026-09-22
     */
    private static final class VueSearchResult {
        /** 命中的目录相对路径；未找到为 null */
        final String found;
        /** 已扫描（LIST）的目录数 */
        final int visited;
        /** 是否因触及目录数上限而停止（此时未找到不等于确认不存在） */
        final boolean truncated;

        VueSearchResult(String found, int visited, boolean truncated) {
            this.found = found;
            this.visited = visited;
            this.truncated = truncated;
        }
    }

    /**
     * Vue 模块文件视图的行数据：FTP 上该模块目录的<b>当前现状</b>文件。
     *
     * <p>只展示远端真实存在的文件（更新前的状态，不做"更新后预测"）；
     * 勾选语义 = 本次更新是否允许覆盖该文件（取消 = 排除）。本地新增的产物文件
     * 不在此列（执行更新时自动上传，运行日志可见）。</p>
     *
     * @author xumanyi
     * @date 2026-08-13
     */
    static final class VueFileRow {
        /** 所属模块目录的 relativePath（排除清单的键） */
        final String moduleKey;
        /** 文件相对模块目录的路径 */
        final String rel;
        /** 远端字节数 */
        final long remoteSize;
        /** 远端修改时间（epoch 毫秒；未知为 0） */
        final long remoteMtime;

        VueFileRow(String moduleKey, String rel, long remoteSize, long remoteMtime) {
            this.moduleKey = moduleKey;
            this.rel = rel;
            this.remoteSize = remoteSize;
            this.remoteMtime = remoteMtime;
        }

        @Override
        public String toString() {
            return rel;
        }
    }

    /**
     * 目录浏览中的非部署普通文件行（js/css/json 等，只读展示、不可勾选）。
     *
     * <p>Vue 解包目录里模块内容就是这类文件；不展示的话"只有文件的模块目录"
     * 会显示成空目录，用户会误以为 FTP 上没有内容。</p>
     *
     * @author xumanyi
     * @date 2026-08-14
     */
    static final class PlainFileRow {
        /** 文件信息（type=FILE） */
        final PackageInfo info;

        PlainFileRow(PackageInfo info) {
            this.info = info;
        }

        @Override
        public String toString() {
            return info.getPackageName();
        }
    }

    // 包信息缓存：已加载各层包的累积（懒加载按层追加，切换系统/项目/刷新时整体重置）
    private final List<PackageInfo> currentPackages = new ArrayList<>();

    /**
     * 已加载各层目录的累积（相对系统根，与 {@link PackageInfo#getSubDirectory()} 同一坐标系）。
     *
     * <p>目录树的骨架源自这里而非 currentPackages：本身没有包（或包被搜索关键字过滤）的目录
     * 若只能从包反推就会消失，用户会误以为 FTP 上没有这个目录。</p>
     */
    private final List<String> currentDirectories = new ArrayList<>();

    /** 各已加载层的非部署普通文件（目录路径 → 文件列表），Vue 源模式下只读展示 */
    private final Map<String, List<PackageInfo>> plainFilesByDir = new HashMap<>();

    /** 用户最近一次鼠标点击的树节点（区分亲手勾选与级联勾选，见手动指定 Vue 目标目录） */
    private CheckedTreeNode lastUserToggledNode;

    /** Vue 手动指定目标目录的结果提示（成功/不匹配原因），展示在底部摘要行 */
    private String vueTargetNotice;

    /**
     * Vue 源模式下用户自由勾选、但目录名与勾选模块不匹配的目录（全路径）。
     *
     * <p>勾选不做限制（跨重建保持勾选态），部署语义在执行更新时校验：
     * 该集合非空则弹窗拦截，列出不匹配目录让用户处理。</p>
     */
    private final Set<String> vueFreeCheckedDirs = new LinkedHashSet<>();

    /**
     * 用户手动指定的模块目标目录（模块号小写 → 目录全路径）。
     *
     * <p>手动条目只存在于 {@code packageDataByKey}，任何数据重种（左侧勾选变化 / 模式切换 /
     * 部署后刷新）都会清掉它——不持久化的话目标会静默回退到自动探测目录。
     * 此映射独立于重种生命周期，重种时由 {@link #seedVueTargets} 回放；仅整体重置时清空。</p>
     */
    private final Map<String, String> vueManualTargets = new LinkedHashMap<>();

    /** 用户显式取消勾选的模块条目（模块号小写）：重种时不再自动勾回 */
    private final Set<String> vueUserUncheckedModules = new HashSet<>();

    // ========== 目录树按层懒加载状态 ==========
    /** 已完成加载（LIST 过）的层：""=系统根，其余为相对扫描根的目录路径 */
    private final Set<String> loadedDirs = new HashSet<>();
    /** 正在加载中的层（防止同一目录重复发起 FTP 请求） */
    private final Set<String> loadingDirs = new HashSet<>();
    /** 用户当前展开的目录路径（树视图重建后按此恢复展开状态） */
    private final Set<String> expandedDirs = new HashSet<>();

    /** 懒加载占位节点的 userObject：目录尚未加载时挂在其下，撑出展开箭头并渲染为灰色提示 */
    private static final Object LOADING_PLACEHOLDER = new Object() {
        @Override public String toString() { return "加载中..."; }
    };

    /** Vue 模块文件视图空目录占位：远端还没有文件时的提示行（不可勾选） */
    private static final Object VUE_EMPTY_PLACEHOLDER = new Object() {
        @Override public String toString() { return "（远端暂无文件）"; }
    };

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
        this.refreshButton = new JButton(PluginIcons.REFRESH);
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
        // 强制重量级弹层：轻量级 popup 画在面板层内，工具窗口首次打开时若面板尚未
        // 完成布局，下拉列表会被面板边界裁掉一截（复现即截图"下拉内容被遮挡"）；
        // 重量级弹层是独立窗口，不受面板边界裁切
        systemCombo.setLightWeightPopupEnabled(false);

        this.packageTreeRoot = new CheckedTreeNode("目标包");
        // CheckPolicy: 仅启用父→子传播（点文件夹联动所有子节点）。
        // 不启用子→父反向同步，避免初始化时单个子节点被自动勾选导致父节点显示为"部分选中"。
        com.intellij.ui.CheckboxTreeBase.CheckPolicy propagatePolicy =
                new com.intellij.ui.CheckboxTreeBase.CheckPolicy(true, true, false, false);
        this.packageTree = new CheckboxTree(new PackageTreeRenderer(), packageTreeRoot, propagatePolicy) {
            @Override
            protected void onNodeStateChanged(CheckedTreeNode node) {
                super.onNodeStateChanged(node);
                // Vue 模块文件行：取消勾选记入排除清单（该文件本次更新不覆盖）
                if (node.getUserObject() instanceof VueFileRow row && !rebuildingPackageTree) {
                    Set<String> unchecked = vueFileUnchecked
                            .computeIfAbsent(row.moduleKey, k -> new HashSet<>());
                    if (node.isChecked()) {
                        unchecked.remove(row.rel);
                        // 勾中模块目录里的任意一个文件 = 要更新这个模块目录：
                        // 把所属模块条目一并勾上（否则 getMainTargets 取不到目标包，
                        // 执行更新会被"未选择目标包"误拦）
                        ensureVueModuleChecked(node);
                    } else {
                        unchecked.add(row.rel);
                        // 取消最后一个文件 = 不更新这个模块目录：条目一并取消勾选，
                        // 避免条目仍勾着、文件全空而被画成"部分勾选"的减号
                        rebuildingPackageTree = true;
                        try {
                            ensureVueModuleUncheckedIfEmpty(node);
                        } finally {
                            rebuildingPackageTree = false;
                        }
                    }
                }
                if (!rebuildingPackageTree) {
                    // Vue 源：勾选目录名与勾选模块号一致的目录 → 手动指定为该模块的更新目标
                    // （不限制必须在自动探测出的服务包目录下，勾选即校验，匹配不上会复位并提示）。
                    // 只认用户亲手点击的节点；点击的是模块条目/文件行时清掉旧提示，回到常规摘要
                    if (isVueSource() && node == lastUserToggledNode) {
                        if (node.getUserObject() instanceof String) {
                            handleVueManualDirCheck(node);
                        } else {
                            // 用户亲手勾/取消模块条目：记录取舍，重种时不推翻
                            if (node.getUserObject() instanceof PackageNodeData pd
                                    && "VUEDIR".equals(pd.info.getType())) {
                                String mid = pd.info.getPackageName()
                                        .toLowerCase(java.util.Locale.ROOT);
                                if (node.isChecked()) {
                                    vueUserUncheckedModules.remove(mid);
                                } else {
                                    vueUserUncheckedModules.add(mid);
                                    // 自由勾选登记的条目被取消：连同待校验记录一并撤销，
                                    // 否则执行更新时会拦一个用户已经取消掉的目录
                                    String key = pd.info.getRelativePath();
                                    if (vueFreeCheckedDirs.remove(key)) {
                                        packageDataByKey.remove(key);
                                        userCheckedKeys.remove(key);
                                    }
                                }
                            }
                            vueTargetNotice = null;
                        }
                    }
                    // 子节点勾选状态变化后，自底向上同步父级目录的勾选状态：
                    // 仅当所有叶子都被勾选时目录才勾选，否则取消勾选（避免父节点遗留"部分选中"灰条）。
                    // 用 rebuildingPackageTree 防 propagateDirChecked 内的 setChecked 触发递归回调。
                    rebuildingPackageTree = true;
                    try {
                        propagateDirChecked(packageTreeRoot);
                        if (isVueSource()) {
                            // Vue 源：级联残留的无语义目录勾选复位；用户自由勾选的
                            // 不匹配目录（vueFreeCheckedDirs）保持勾选，执行更新时校验拦截
                            clearVueMeaninglessDirChecks(packageTreeRoot, "");
                        }
                    } finally {
                        rebuildingPackageTree = false;
                    }
                    packageTree.repaint();
                    syncVisibleCheckStateToKeys();
                    if (!isVueSource()) {
                        // 勾选了尚未加载的目录 → 自动加载该层，加载回来的包继承勾选，
                        // 补全"勾目录=选中其下全部包"的语义（懒加载下内容不在本地）。
                        // Vue 源不走此语义：模块条目由左侧勾选生成，批量拉取子目录只会打爆 FTP
                        triggerLoadForCheckedUnloadedDirs(packageTreeRoot, "");
                    }
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
                // 空格勾选也是"用户亲手操作"：与 mousePressed 同口径记录目标节点，
                // 否则键盘勾选无法触发手动指定/自由勾选登记与清理
                if (e.getID() == java.awt.event.KeyEvent.KEY_PRESSED
                        && e.getKeyCode() == java.awt.event.KeyEvent.VK_SPACE) {
                    javax.swing.tree.TreePath sel = getSelectionPath();
                    lastUserToggledNode = (sel != null
                            && sel.getLastPathComponent() instanceof CheckedTreeNode n) ? n : null;
                }
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
            public void mousePressed(java.awt.event.MouseEvent e) {
                // 记录用户实际点击的节点：onNodeStateChanged 会因勾选级联对每个后代节点各触发一次，
                // 「手动指定 Vue 目标目录」只对用户亲手点击的那个目录生效，级联出来的不算
                javax.swing.tree.TreePath p = packageTree.getPathForLocation(e.getX(), e.getY());
                lastUserToggledNode = (p != null && p.getLastPathComponent() instanceof CheckedTreeNode n)
                        ? n : null;
            }

            @Override
            public void mouseExited(java.awt.event.MouseEvent e) {
                if (packageTreeHoverRow != -1) {
                    packageTreeHoverRow = -1;
                    packageTree.repaint();
                }
            }

            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                // 双击行切换目录展开/收起：CheckboxTree 的点击处理吞掉了 JTree 默认的
                // toggleClickCount 行为，这里显式补回（叶子行无子节点，展开无效果不处理）
                if (e.getClickCount() != 2 || !SwingUtilities.isLeftMouseButton(e)) return;
                javax.swing.tree.TreePath path = packageTree.getPathForLocation(e.getX(), e.getY());
                if (path == null) return;
                if (path.getLastPathComponent() instanceof CheckedTreeNode n && n.getChildCount() > 0) {
                    if (packageTree.isExpanded(path)) {
                        packageTree.collapsePath(path);
                    } else {
                        packageTree.expandPath(path);
                    }
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
        // 摘要放 CENTER 右对齐而非 EAST：EAST 按首选宽度刚性占位，长句（服务包目录探测失败 /
        // 未找到的原因与指引）在窄面板下会从左边被裁掉开头；CENTER 会被压缩，
        // JLabel 空间不足时自动画省略号，完整文案通过 tooltip 查看（告警态已挂）
        selectionSummary.setHorizontalAlignment(SwingConstants.RIGHT);
        summaryRow.add(selectionSummary, BorderLayout.CENTER);
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
     * 只更新加载条文案（进度刷新用），不改变并发计数；非 EDT 调用自动路由到 EDT。
     *
     * @param text 新的加载描述
     * @author xumanyi
     * @date 2026-09-22
     */
    private void updateLoadingText(String text) {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(() -> updateLoadingText(text));
            return;
        }
        if (loadingBar.isVisible()) {
            loadingLabel.setText(text);
        }
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

    /** 初始化事件监听：连接、注销、项目/系统级联选择、刷新
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
            // 先清空目标包列表（含懒加载缓存），显示刷新中
            resetLazyState();
            final int refreshGeneration = lazyGeneration;
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

                    // 刷新目标包列表（懒加载：都只列一层，子目录等用户展开时再按需加载）：
                    // - 之前选了具体系统 → 重列该系统根层
                    // - 之前处于项目根上下文（未选系统，根目录直接部署）→ 重列项目根层
                    ScanResult scan = null;
                    if (prevProj != null && projects.contains(prevProj)) {
                        if (prevSys != null && systems != null && systems.contains(prevSys)) {
                            scan = browseService.listLevel(
                                    "/开发/" + prevProj + "/" + prevSys + "/", "");
                        } else {
                            // 项目根上下文不展示目录（根下子目录就是"系统"，由系统下拉承载），只取根层直接包
                            ScanResult rootScan = browseService.listLevel(
                                    "/开发/" + prevProj + "/", "");
                            scan = new ScanResult(rootScan.getPackages(), List.of());
                        }
                    }

                    // 所有 FTP 操作完成后，一次性更新 UI
                    final List<String> finalSystems = systems;
                    final ScanResult finalScan = scan;
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

                        // 代际校验：刷新期间用户注销/清空过目标区时，本次结果作废
                        if (finalScan != null && refreshGeneration == lazyGeneration) {
                            currentPackages.clear();
                            currentPackages.addAll(finalScan.getPackages());
                            currentDirectories.clear();
                            currentDirectories.addAll(finalScan.getDirectories());
                            loadedDirs.add("");
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

        // 目录树按层懒加载：首次展开未加载目录时按需 LIST 该层；
        // 展开/折叠状态记入 expandedDirs，供树视图重建后恢复。
        // rebuildingPackageTree=true 期间的程序化展开（视图重建恢复、搜索全展开）不触发加载。
        packageTree.addTreeExpansionListener(new TreeExpansionListener() {
            @Override
            public void treeExpanded(TreeExpansionEvent event) {
                if (rebuildingPackageTree) return;
                // Vue 模块目录条目展开 → 懒加载其文件合并视图（本地产物 ∪ 远端现状）。
                // 同时按 relativePath 记入 expandedDirs：左侧模块取消勾选后条目退化成
                // 普通目录（路径相同），展开状态才能跨形态延续，不被折叠回去
                if (event.getPath().getLastPathComponent() instanceof CheckedTreeNode n
                        && n.getUserObject() instanceof PackageNodeData pd
                        && "VUEDIR".equals(pd.info.getType())) {
                    collapsedVueModules.remove(pd.info.getRelativePath());
                    expandedDirs.add(pd.info.getRelativePath());
                    startLoadVueModuleFiles(pd);
                    return;
                }
                // 展开状态记录用宽口径 key（模块条目内部目录也记，供退化成普通目录后恢复）
                String expandKey = expandKeyOfTreePath(event.getPath());
                if (expandKey != null) expandedDirs.add(expandKey);
                // 懒加载仍用严格口径：模块条目内部的目录不是 FTP 目录层，不触发 LIST
                String dirPath = dirPathOfTreePath(event.getPath());
                if (dirPath == null) return;
                if (!loadedDirs.contains(dirPath) && !loadingDirs.contains(dirPath)) {
                    startLoadLevel(dirPath);
                }
            }

            @Override
            public void treeCollapsed(TreeExpansionEvent event) {
                if (rebuildingPackageTree) return;
                // Vue 模块条目被手动折叠 → 记录，自动展开逻辑不再反复弹开
                if (event.getPath().getLastPathComponent() instanceof CheckedTreeNode n
                        && n.getUserObject() instanceof PackageNodeData pd
                        && "VUEDIR".equals(pd.info.getType())) {
                    collapsedVueModules.add(pd.info.getRelativePath());
                    expandedDirs.remove(pd.info.getRelativePath());
                    return;
                }
                String expandKey = expandKeyOfTreePath(event.getPath());
                if (expandKey != null) expandedDirs.remove(expandKey);
            }
        });
    }

    /**
     * 设置源产物信息（影响目标包的默认勾选与主目标/嵌入判定）
     *
     * <p>目标包树始终全量展示，不按源产物过滤隐藏；源产物类型（JAR/WAR）只决定
     * 与其精确同名的包默认勾选，以及勾选包按类型判定为主目标还是 WAR 嵌入。</p>
     *
     * @param artifactFileName 源产物文件名（如 scev6-utils-tms-10.0.0-SNAPSHOT.jar）
     * @author xumanyi
     * @date 2026-03-27
     */
    public void setSourceArtifact(String artifactFileName) {
        clearVueSource();
        this.sourceArtifactName = artifactFileName;
        if (artifactFileName != null) {
            this.sourceArtifactType = artifactFileName.toLowerCase().endsWith(".war") ? "WAR" : "JAR";
        } else {
            this.sourceArtifactType = null;
        }
        // 重新构建树（应用新的默认勾选规则）
        rebuildPackageTree();
    }

    /**
     * 设置 Vue 源工程信息（影响模块目录条目的合成、默认勾选与主目标判定）
     *
     * <p>源产物类型置为 ZIP 仅作"当前是 Vue 源"的标记。Vue 更新的目标只有服务包解包目录
     * 一种形态：由 {@link #seedVueTargets} 在 content 目录下为每个选中模块合成「模块目录」
     * 条目；目标树里的 .zip 文件只是原样展示，不默认勾选、也不作为更新目标。</p>
     *
     * @param content   工程上下文名（serve.yaml 的 content 值，如 tm01webVue）
     * @param moduleIds 选中的模块号列表（如 [t0103, t0107]）
     * @author xumanyi
     * @date 2026-08-13
     */
    public void setSourceVue(String content, List<String> moduleIds) {
        setSourceVue(content, moduleIds, this.vueProjectRootPath);
    }

    /**
     * 设置 Vue 源工程信息（带工程本地根目录，供模块文件合并视图取本地产物）
     *
     * @param content         工程上下文名
     * @param moduleIds       选中的模块号列表
     * @param projectRootPath Vue 工程本地根目录绝对路径
     * @author xumanyi
     * @date 2026-08-13
     */
    public void setSourceVue(String content, List<String> moduleIds, String projectRootPath) {
        List<String> previousModuleIds = this.vueSelectedModuleIds;
        this.vueContent = content;
        this.vueProjectRootPath = projectRootPath;
        this.vueSelectedModuleIds = moduleIds == null ? List.of() : List.copyOf(moduleIds);
        resetVueMemoryForNewlySelected(previousModuleIds, this.vueSelectedModuleIds);
        // Vue 源没有"同名产物"概念（目标是模块目录条目，不按文件名匹配），不设 sourceArtifactName
        this.sourceArtifactName = null;
        this.sourceArtifactType = "ZIP";
        rebuildPackageTree();
    }

    /**
     * 左侧新勾选（此前未选中）的模块：清空对它的历史取舍记忆——"显式取消模块条目"记录
     * 与文件排除清单。
     *
     * <p>左侧取消再勾选是一次全新的更新意图。历史记忆若留着，右侧会出现两种"漏勾选"：
     * 模块条目不自动勾回（{@link #vueUserUncheckedModules}），或条目勾上了但文件视图里
     * 大半文件停在上一轮的排除态（{@link #vueFileUnchecked}）——用户看到的就是"重新勾选后
     * 目标包这次少勾了几项"。仍在勾选中的模块不受影响，其文件级取舍保留。</p>
     *
     * @param previous 变更前的模块号列表
     * @param current  变更后的模块号列表
     * @author xumanyi
     * @date 2026-08-14
     */
    private void resetVueMemoryForNewlySelected(List<String> previous, List<String> current) {
        for (String id : current) {
            boolean wasSelected = previous.stream().anyMatch(p -> p.equalsIgnoreCase(id));
            if (wasSelected) continue;
            vueUserUncheckedModules.remove(id.toLowerCase(Locale.ROOT));
            // 排除清单以模块目录路径为键（如 content/a0546）：按末段模块号匹配清理
            vueFileUnchecked.keySet().removeIf(key ->
                    key.substring(key.lastIndexOf('/') + 1).equalsIgnoreCase(id));
        }
    }

    /**
     * 目标树数据重载后重新套用当前源（Vue / Maven 通吃）
     *
     * <p>切换项目 / 系统 / 刷新会重置包数据，需要按当前源重新种子化默认勾选。
     * 历史实现直接调 {@code setSourceArtifact(sourceArtifactName)}——会清空 Vue 状态，
     * 导致模块目录条目 / 主目标判定全体失效。统一收口到本方法：
     * Vue 源用现存 content + 模块勾选原样重放（setSourceVue 幂等可重放）。</p>
     *
     * @author xumanyi
     * @date 2026-08-13
     */
    private void reapplySource() {
        if (isVueSource()) {
            setSourceVue(vueContent, vueSelectedModuleIds);
        } else if (sourceArtifactName != null) {
            setSourceArtifact(sourceArtifactName);
        } else {
            rebuildPackageTree();
        }
    }

    /**
     * 清除 Vue 源状态（切回 Maven 源或清空源时调用）
     *
     * @author xumanyi
     * @date 2026-08-13
     */
    private void clearVueSource() {
        this.vueContent = null;
        this.vueProjectRootPath = null;
        this.vueSelectedModuleIds = List.of();
        this.vueFileRowsByModule.clear();
        this.vueFileLoading.clear();
        this.vueFileUnchecked.clear();
        this.collapsedVueModules.clear();
        resetVueContentSearch();
    }

    /**
     * 复位服务包目录探测状态（换源 / 换系统 / 刷新时调用，下次重种会重新发起探测）
     *
     * @author xumanyi
     * @date 2026-09-22
     */
    private void resetVueContentSearch() {
        this.vueContentSearchStarted = false;
        this.vueContentSearchOutcome = VueSearchOutcome.NONE;
        this.vueContentSearchFailReason = null;
        this.vueContentSearchScanned = 0;
        this.vueContentSearchTruncated = false;
    }

    /**
     * 当前是否为 Vue 源（ZIP 产物）
     *
     * @return true 表示 Vue 源
     * @author xumanyi
     * @date 2026-08-13
     */
    private boolean isVueSource() {
        return "ZIP".equals(sourceArtifactType) && vueContent != null;
    }

    /**
     * 切换为共享库源（sce-vcom-components 等）：清空 Vue / Maven 源状态，
     * 目标树保持纯浏览形态（共享库更新的目标由执行阶段自动探测，不走勾选）。
     *
     * @author xumanyi
     * @date 2026-08-14
     */
    public void setSourceSharedLib() {
        clearVueSource();
        this.sourceArtifactName = null;
        this.sourceArtifactType = null;
        rebuildPackageTree();
    }

    /**
     * 当前系统扫描根的 FTP 绝对路径（含子目录收窄层，以 / 结尾）。
     *
     * <p>供共享库更新在当前浏览范围内自动探测 login 目录用；
     * 未选择项目/系统时返回 null。</p>
     *
     * @return 扫描根绝对路径；不可用时 null
     * @author xumanyi
     * @date 2026-08-14
     */
    public String getScanRootAbs() {
        String proj = selectedProject;
        String sys = (String) systemCombo.getSelectedItem();
        if (proj == null || sys == null || sys.isEmpty()) return null;
        return buildScanPath(proj, sys);
    }


    /**
     * 包数据集变化（系统/项目/源产物切换、清空）后调用：重新生成 packageDataByKey
     * 与 userCheckedKeys 默认勾选（与源产物精确同名且非备份的包），清空搜索关键字，并重建视图。
     *
     * <p>"包数据真正变了"是 reseed 的唯一触发条件；纯过滤刷新走 {@link #buildPackageTreeView()}。</p>
     */
    private void rebuildPackageTree() {
        seedDataAndKeysFromCurrentPackages();
        // 数据重种后的首次视图构建才自动展开匹配包所在目录；
        // 之后的增量重建（懒加载完成等）不再反复弹开用户已折叠的目录
        pendingAutoExpandMatched = true;
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
     * <p>不做任何过滤隐藏：扫描到的包（无论类型、是否与源产物匹配）全部入树，由用户手动勾选。
     * 源产物仅影响默认勾选：与源产物精确同名的同类型包默认勾选（JAR 源额外标记 locked，
     * 用于加粗渲染与搜索豁免，不再表示"必选"）。备份目录的包一律不默认勾选。
     * 默认勾选的包进入 userCheckedKeys，对应"切换目标系统时主目标自动勾选"。</p>
     */
    private void seedDataAndKeysFromCurrentPackages() {
        packageDataByKey.clear();
        userCheckedKeys.clear();
        for (PackageInfo pkg : currentPackages) {
            seedPackage(pkg, false);
        }
        seedVueTargets();
    }

    /**
     * Vue 源的目标种子入口：只认服务包解包目录，定位不到即明确失败、不做任何兜底。
     *
     * <p>客服 FTP 上 Vue 服务包的唯一运维形态是<b>解包目录</b>（{content}/{模块号}/...）。
     * 树中发现名字等于 content 的目录时，为每个选中模块合成「模块目录」条目
     * （远端已有该模块目录 = 覆盖更新；没有 = 新建投放）。树中没有 content 目录时发起一次
     * 有界自动探测；探测未找到或中途失败都<b>不生成任何目标</b>——状态与原因展示在摘要行、
     * 运行日志和执行更新的前置校验里，由用户刷新重试或手动展开目标树勾选模块目录。</p>
     *
     * <p>2026-09-22 起移除 zip 兜底：此前探测中途的 FTP 异常被当作"未找到"、退成
     * "首次投放 zip"虚拟目标，曾把模块 zip 投到了系统根目录。</p>
     *
     * @author xumanyi
     * @date 2026-08-13
     */
    private void seedVueTargets() {
        if (!isVueSource()) return;
        List<String> contentDirs = findVueContentDirs();
        if (contentDirs.isEmpty()) {
            // 服务包目录可能藏在尚未展开的深层子目录（如 sce-vcom-test/sec/A06SysBizWebVue）：
            // 做一次有界自动探测。探测未找到 / 失败时这里什么都不生成（无目标 = 无法执行更新）
            if (!vueContentSearchStarted && selectedProject != null
                    && browseService != null && loadedDirs.contains("")) {
                vueContentSearchStarted = true;
                searchVueContentDirAsync();
            }
            return;
        }
        // 用户手动展开定位到了 content 目录（自动探测失败 / 未找到之后的人工定位）：结局随之转正，
        // 摘要行不再停留在失败提示
        if (vueContentSearchOutcome != VueSearchOutcome.FOUND) {
            vueContentSearchOutcome = VueSearchOutcome.FOUND;
            vueContentSearchFailReason = null;
        }
        // 先回放用户手动指定的模块目标（独立于重种生命周期）：重种清空 packageDataByKey 后，
        // 手动条目必须在自动种子之前恢复，否则目标会静默回退到自动探测目录
        for (Map.Entry<String, String> e : vueManualTargets.entrySet()) {
            String selectedId = null;
            for (String sel : vueSelectedModuleIds) {
                if (sel.toLowerCase(Locale.ROOT).equals(e.getKey())) {
                    selectedId = sel;
                    break;
                }
            }
            if (selectedId == null) continue; // 模块当前未勾选：映射休眠，随勾选恢复
            String dir = e.getValue();
            if (!packageDataByKey.containsKey(dir)) {
                int slash = dir.lastIndexOf('/');
                String parentDir = slash > 0 ? dir.substring(0, slash) : ".";
                packageDataByKey.put(dir, new PackageNodeData(
                        new PackageInfo(parentDir, selectedId, "VUEDIR", dir, 0, false, 0L),
                        true, false));
            }
            if (!vueUserUncheckedModules.contains(e.getKey())) {
                userCheckedKeys.add(dir);
            }
        }
        for (String contentDir : contentDirs) {
            if (!loadedDirs.contains(contentDir)) {
                // content 目录尚未 LIST：自动按需加载，加载完成的 applyLevelScan 会重新进入本方法
                if (!loadingDirs.contains(contentDir)) {
                    startLoadLevel(contentDir);
                }
                continue;
            }
            for (String moduleId : vueSelectedModuleIds) {
                String moduleDirPath = contentDir + "/" + moduleId;
                String key = moduleDirPath;
                if (packageDataByKey.containsKey(key)) continue;
                // 模块有手动指定目标（无论勾选与否）：不再自动种子，避免同模块双条目
                if (vueManualTargets.containsKey(moduleId.toLowerCase(Locale.ROOT))) continue;
                // 该模块已有勾选中的目标条目（用户手动指定在别处）：不再自动种子，
                // 否则同一模块出现两个勾选目标，一次更新会写到两处
                if (hasCheckedVueEntryForModule(moduleId)) continue;
                boolean dirExists = containsDirIgnoreCase(moduleDirPath);
                PackageInfo info = new PackageInfo(contentDir, moduleId, "VUEDIR",
                        moduleDirPath, 0, false, 0L);
                packageDataByKey.put(key, new PackageNodeData(info, true, !dirExists));
                // 用户显式取消过的模块不自动勾回（重种不推翻用户的取舍）
                if (!vueUserUncheckedModules.contains(moduleId.toLowerCase(Locale.ROOT))) {
                    userCheckedKeys.add(key);
                }
            }
        }
        // 自由勾选目录：一律回放成勾选中的目标条目（重种会清空 packageDataByKey），
        // 勾选态完全按用户操作呈现；目录名是否与模块号一致由执行更新前的校验把关。
        // 目录名现在命中勾选模块、且该模块没有别的勾选目标 → 记录转正（不再需要校验拦截）
        if (!vueFreeCheckedDirs.isEmpty()) {
            List<String> resolved = new ArrayList<>();
            for (String p : vueFreeCheckedDirs) {
                String base = p.substring(p.lastIndexOf('/') + 1);
                if (!packageDataByKey.containsKey(p)) {
                    int slash = p.lastIndexOf('/');
                    String parentDir = slash > 0 ? p.substring(0, slash) : ".";
                    PackageInfo info = new PackageInfo(parentDir, base, "VUEDIR", p,
                            0, false, 0L);
                    packageDataByKey.put(p, new PackageNodeData(info, true, false));
                }
                userCheckedKeys.add(p);
                for (String id : vueSelectedModuleIds) {
                    if (id.equalsIgnoreCase(base) && !hasCheckedVueEntryForModule(id, p)) {
                        resolved.add(p);
                        break;
                    }
                }
            }
            vueFreeCheckedDirs.removeAll(resolved);
        }
        // 勾选中的模块条目自动预载文件合并视图（不必等用户手动展开），
        // 加载完成后 buildPackageTreeView 会把文件行直接展示出来。
        // 模块数多时（整包更新 40+ 个）不批量预载——逐模块递归 LIST 会打爆 FTP，
        // 此时按需展开加载即可
        if (vueSelectedModuleIds.size() <= 5) {
            for (Map.Entry<String, PackageNodeData> e : packageDataByKey.entrySet()) {
                PackageNodeData d = e.getValue();
                if ("VUEDIR".equals(d.info.getType()) && userCheckedKeys.contains(e.getKey())
                        && !vueFileRowsByModule.containsKey(e.getKey())
                        && !vueFileLoading.contains(e.getKey())) {
                    startLoadVueModuleFiles(d);
                }
            }
        }
    }

    /**
     * 异步有界探测服务包目录：从系统扫描根 BFS 逐层 LIST 子目录，
     * 寻找最后一段等于 content 的目录（跳过备份子树，深度 ≤3、访问目录数 ≤300）。
     *
     * <p>找到 → 把该目录加入骨架并触发按层加载（加载完成的 applyLevelScan 会重进
     * {@link #seedVueTargets} 合成模块目录条目）。没找到 → 结局 NOT_FOUND；
     * 中途 FTP 异常（超时 / 断连）→ 单个目录先换连接重试一次，仍失败则结局 FAILED 并记录原因。
     * 两种失败都不生成任何目标，只把状态明示给用户（摘要行 + 运行日志 + 执行更新前置校验），
     * 点「刷新」即重新探测。探测期间加载条实时显示已扫描目录数，慢网络下不会看起来像卡死。</p>
     *
     * @author xumanyi
     * @date 2026-08-13
     */
    private void searchVueContentDirAsync() {
        final int generation = lazyGeneration;
        final String proj = selectedProject;
        final String sys = (String) systemCombo.getSelectedItem();
        final String content = vueContent;
        if (proj == null || sys == null || content == null) {
            vueContentSearchOutcome = VueSearchOutcome.NOT_FOUND;
            return;
        }
        final String scanRoot = buildScanPath(proj, sys);
        final String relPathPrefix = buildSubdirPrefix();
        final String loadingText = "定位 " + content + " 服务包目录...";
        showLoading(loadingText);
        emitLog("INFO  [目标] 开始定位 " + content + " 服务包目录：" + scanRoot);
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            VueSearchResult result = null;
            String failReason = null;
            java.util.Map<String, FtpBrowseService.ScanResult> visitedListings =
                    new java.util.LinkedHashMap<>();
            try {
                synchronized (ftpLock) {
                    if (browseService == null || !browseService.isConnected()) {
                        reconnectFtp();
                    }
                    if (browseService == null) {
                        failReason = "FTP 未连接";
                    } else {
                        result = searchContentDirBfs(scanRoot, content, visitedListings,
                                scanned -> updateLoadingText(loadingText
                                        + "（已扫描 " + scanned + " 个目录）"));
                    }
                }
            } catch (Exception ex) {
                // 中途失败绝不降级成"未找到"：原样带出原因，由 EDT 侧记为 FAILED
                failReason = ex.getMessage() == null || ex.getMessage().isBlank()
                        ? ex.getClass().getSimpleName() : ex.getMessage();
            }
            final VueSearchResult searchResult = result;
            final String reason = failReason;
            SwingUtilities.invokeLater(() -> {
                hideLoading();
                if (generation != lazyGeneration) return;
                if (searchResult != null) {
                    vueContentSearchScanned = searchResult.visited;
                    vueContentSearchTruncated = searchResult.truncated;
                } else {
                    vueContentSearchScanned = visitedListings.size();
                    vueContentSearchTruncated = false;
                }
                final String foundDir = searchResult == null ? null : searchResult.found;
                if (foundDir != null) {
                    vueContentSearchOutcome = VueSearchOutcome.FOUND;
                    vueContentSearchFailReason = null;
                    emitLog("INFO  [目标] 已定位 " + content + " 服务包目录：" + foundDir);
                    if (!currentDirectories.contains(foundDir)) {
                        currentDirectories.add(foundDir);
                    }
                    // 探测时逐层 LIST 过 content 目录的祖先链——把这些真实扫描结果按常规
                    // 懒加载路径落地（登记 loadedDirs + 目录/包入库），否则祖先目录会以
                    // "未加载"状态挂懒加载占位，且程序化展开不触发加载，"加载中..."永久残留
                    String acc = null;
                    for (String seg : foundDir.split("/")) {
                        String chainDir = acc == null ? seg : acc + "/" + seg;
                        acc = chainDir;
                        if (chainDir.equals(foundDir)) break;
                        FtpBrowseService.ScanResult scan = visitedListings.get(chainDir);
                        if (scan != null && !loadedDirs.contains(chainDir)) {
                            applyLevelScan(generation, chainDir, false, scan, relPathPrefix);
                        }
                    }
                } else if (reason != null) {
                    vueContentSearchOutcome = VueSearchOutcome.FAILED;
                    vueContentSearchFailReason = reason;
                    emitLog("WARN  [目标] " + describeVueTargetStatus());
                } else {
                    vueContentSearchOutcome = VueSearchOutcome.NOT_FOUND;
                    vueContentSearchFailReason = null;
                    emitLog("WARN  [目标] " + describeVueTargetStatus());
                }
                // 重建让 content 目录进骨架；其按层加载由 seedVueTargets 内的
                // startLoadLevel 分支自动触发。未找到 / 失败时重建只刷新摘要行的状态提示
                rebuildPackageTree();
            });
        });
    }

    /**
     * BFS 逐层查找 content 目录（工作线程内、持 ftpLock 调用）。
     *
     * <p>单个目录 LIST 失败先换连接重试一次（见 {@link #listLevelWithReconnect}），
     * 仍失败则原样抛出，由调用方按"探测失败"处理——绝不把中途失败当作"未找到"。
     * 已扫过的目录结果留在 {@code visitedListings}，命中后用于把祖先链落地。</p>
     *
     * @param scanRoot        系统扫描根（绝对路径）
     * @param content         工程上下文名
     * @param visitedListings 收集途经目录的完整扫描结果（相对路径 → ScanResult）
     * @param progress        每扫完一个目录回调一次（参数为已扫描目录数），供加载条展示进度
     * @return 探测结果：命中目录 / 未找到（含是否触及目录数上限）
     * @throws java.io.IOException 某个目录 LIST 重试后仍失败
     * @author xumanyi
     * @date 2026-08-13
     */
    private VueSearchResult searchContentDirBfs(String scanRoot, String content,
            java.util.Map<String, FtpBrowseService.ScanResult> visitedListings,
            java.util.function.IntConsumer progress)
            throws java.io.IOException {
        java.util.ArrayDeque<String> queue = new java.util.ArrayDeque<>();
        queue.add("");
        int visited = 0;
        while (!queue.isEmpty()) {
            if (visited >= VUE_SEARCH_MAX_DIRS) {
                // 触及上限仍有目录没扫：未找到不等于确认不存在，结局说明里如实带出
                return new VueSearchResult(null, visited, true);
            }
            String dir = queue.poll();
            FtpBrowseService.ScanResult scan = listLevelWithReconnect(scanRoot, dir);
            visited++;
            visitedListings.put(dir, scan);
            progress.accept(visited);
            for (String sub : scan.getDirectories()) {
                String[] segs = sub.split("/");
                String last = segs[segs.length - 1];
                if (com.flux.deploy.ftp.FtpOperations.BACKUP_DIR_NAMES.contains(last)) {
                    continue;
                }
                if (last.equalsIgnoreCase(content)) {
                    return new VueSearchResult(sub, visited, false);
                }
                // 深度限制：相对路径最多 VUE_SEARCH_MAX_DEPTH 层再往下不钻
                if (segs.length < VUE_SEARCH_MAX_DEPTH) {
                    queue.add(sub);
                }
            }
        }
        return new VueSearchResult(null, visited, false);
    }

    /**
     * 探测用的单层 LIST：失败先换连接重试一次，仍失败原样抛出。
     *
     * <p>网络不稳定时常见的是被动数据连接超时（Connect timed out）或控制连接被服务端掐断，
     * 换一条连接重发同一个 LIST 通常即恢复；已扫完的目录不重扫。
     * 必须在 synchronized(ftpLock) 内调用。</p>
     *
     * @param scanRoot 系统扫描根（绝对路径）
     * @param dir      待 LIST 的目录（相对扫描根）
     * @return 该层扫描结果
     * @throws java.io.IOException 重试后仍失败
     * @author xumanyi
     * @date 2026-09-22
     */
    private FtpBrowseService.ScanResult listLevelWithReconnect(String scanRoot, String dir)
            throws java.io.IOException {
        try {
            return browseService.listLevel(scanRoot, dir);
        } catch (Exception first) {
            reconnectFtp();
            if (browseService == null) {
                throw first instanceof java.io.IOException io ? io : new java.io.IOException(first);
            }
            return browseService.listLevel(scanRoot, dir);
        }
    }

    /**
     * 服务包目录探测状态的用户可读说明（摘要行 / 运行日志 / 执行更新前置校验共用）。
     *
     * @return 一句话状态；已定位到服务包目录时返回 null
     * @author xumanyi
     * @date 2026-09-22
     */
    private String describeVueTargetStatus() {
        String content = vueContent;
        switch (vueContentSearchOutcome) {
            case FOUND:
                return null;
            case FAILED:
                return "定位 " + content + " 服务包目录失败：" + vueContentSearchFailReason
                        + "（已扫描 " + vueContentSearchScanned + " 个目录，网络可能不稳定）"
                        + "——请点「刷新」重试，或手动展开目标树勾选与模块同名的目录";
            case NOT_FOUND:
                return "未找到 " + content + " 服务包目录（已扫描 " + vueContentSearchScanned
                        + " 个目录" + (vueContentSearchTruncated ? "，超出自动探测范围" : "") + "）"
                        + "——请手动展开目标树勾选与模块同名的目录，或点「刷新」重试";
            default:
                return vueContentSearchStarted
                        ? "正在定位 " + content + " 服务包目录..."
                        : "尚未定位 " + content + " 服务包目录（请先连接 FTP 并选择项目 / 系统）";
        }
    }

    /**
     * Vue 目标状态提示：Vue 更新只认服务包目录，右侧没有可用的模块目录目标时说明原因。
     *
     * <p>供执行更新的前置校验弹窗使用：探测失败（网络）/ 未找到 / 进行中各给出对应指引；
     * 勾选了 zip 时额外说明 zip 不是有效目标。</p>
     *
     * @return 提示文案；非 Vue 源或已定位到服务包目录时返回 null
     * @author xumanyi
     * @date 2026-09-22
     */
    public String getVueTargetStatusHint() {
        if (!isVueSource()) return null;
        String status = describeVueTargetStatus();
        int checkedZips = 0;
        for (PackageNodeData d : getSelectedPackages()) {
            if ("ZIP".equals(d.info.getType())) checkedZips++;
        }
        if (checkedZips > 0) {
            String zipNote = "已勾选的 " + checkedZips + " 个 zip 不是 Vue 更新目标（只支持服务包目录逐文件更新）";
            return status == null ? zipNote : zipNote + "；" + status;
        }
        return status;
    }

    /**
     * 在已加载目录中查找 Vue 服务包根目录（最后一段等于 content，忽略大小写，排除备份子树）
     *
     * @return content 目录相对路径列表（相对扫描根）
     * @author xumanyi
     * @date 2026-08-13
     */
    private List<String> findVueContentDirs() {
        List<String> result = new ArrayList<>();
        if (vueContent == null) return result;
        for (String dir : currentDirectories) {
            String[] segs = dir.split("/");
            if (segs.length == 0) continue;
            if (!segs[segs.length - 1].equalsIgnoreCase(vueContent)) continue;
            boolean inBackup = false;
            for (String seg : segs) {
                if (com.flux.deploy.ftp.FtpOperations.BACKUP_DIR_NAMES.contains(seg)) {
                    inBackup = true;
                    break;
                }
            }
            if (!inBackup) result.add(dir);
        }
        return result;
    }

    /**
     * 判断目录列表中是否含指定路径（忽略大小写）
     *
     * @param dirPath 目录相对路径
     * @return true 表示存在
     * @author xumanyi
     * @date 2026-08-13
     */
    private boolean containsDirIgnoreCase(String dirPath) {
        for (String d : currentDirectories) {
            if (d.equalsIgnoreCase(dirPath)) return true;
        }
        return false;
    }

    /**
     * 判断目录是否属于某个 Vue 模块目录条目（其自身或其子目录）
     *
     * @param dir 目录相对路径
     * @return true 表示该目录已由 VUEDIR 条目承载，不再单独建目录节点
     * @author xumanyi
     * @date 2026-08-13
     */
    private boolean isUnderVueDirTarget(String dir) {
        if (!isVueSource()) return false;
        String lower = dir.toLowerCase(Locale.ROOT);
        for (PackageNodeData d : packageDataByKey.values()) {
            if (!"VUEDIR".equals(d.info.getType())) continue;
            String rel = d.info.getRelativePath().toLowerCase(Locale.ROOT);
            if (lower.equals(rel) || lower.startsWith(rel + "/")) return true;
        }
        return false;
    }

    /**
     * 为 Vue 模块目录条目挂载文件行子节点（合并视图已加载）或懒加载占位（未加载）。
     *
     * <p>行勾选状态从 {@link #vueFileUnchecked} 回放：NEW/OVERWRITE 默认勾选、
     * 用户取消过的保持取消；KEEP（远端有本地无）恒不勾且禁用。</p>
     *
     * @param pkgNode 模块目录条目节点
     * @param d       条目数据
     * @author xumanyi
     * @date 2026-08-13
     */
    private void attachVueFileChildren(CheckedTreeNode pkgNode, PackageNodeData d) {
        if (!"VUEDIR".equals(d.info.getType())) return;
        String moduleKey = d.info.getRelativePath();
        List<VueFileRow> rows = vueFileRowsByModule.get(moduleKey);
        if (rows == null) {
            // 未加载：挂占位撑出展开箭头，展开时触发加载。
            // 占位勾选态跟随条目——条目已勾而占位未勾时，平台会把条目画成"部分勾选"的减号
            // （展开加载完文件又变回全选，看着像勾选态自己在跳）
            CheckedTreeNode placeholder = new CheckedTreeNode(LOADING_PLACEHOLDER);
            placeholder.setChecked(pkgNode.isChecked());
            placeholder.setEnabled(false);
            pkgNode.add(placeholder);
            return;
        }
        Set<String> unchecked = vueFileUnchecked.getOrDefault(moduleKey, Set.of());
        if (rows.isEmpty()) {
            // 远端还没有文件（新建投放 / 空目录）：给一个不可勾选的提示行撑出展开箭头
            CheckedTreeNode empty = new CheckedTreeNode(VUE_EMPTY_PLACEHOLDER);
            empty.setChecked(pkgNode.isChecked());
            empty.setEnabled(false);
            pkgNode.add(empty);
            return;
        }
        // 按子目录嵌套挂载（dialogs/、submodules/details/ 等还原真实目录层级）
        Map<String, CheckedTreeNode> fileDirNodes = new LinkedHashMap<>();
        for (VueFileRow row : rows) {
            CheckedTreeNode parentNode = pkgNode;
            int lastSlash = row.rel.lastIndexOf('/');
            if (lastSlash > 0) {
                String[] segs = row.rel.substring(0, lastSlash).split("/");
                StringBuilder acc = new StringBuilder();
                for (String seg : segs) {
                    if (seg.isEmpty()) continue;
                    if (acc.length() > 0) acc.append('/');
                    acc.append(seg);
                    String dirKey = acc.toString();
                    CheckedTreeNode dirNode = fileDirNodes.get(dirKey);
                    if (dirNode == null) {
                        dirNode = new CheckedTreeNode(seg);
                        dirNode.setChecked(false);
                        parentNode.add(dirNode);
                        fileDirNodes.put(dirKey, dirNode);
                    }
                    parentNode = dirNode;
                }
            }
            CheckedTreeNode fileNode = new CheckedTreeNode(row);
            fileNode.setChecked(pkgNode.isChecked() && !unchecked.contains(row.rel));
            parentNode.add(fileNode);
        }
        // 内部目录节点按"可勾选后代是否全勾"聚合勾选态（KEEP 行不计入，
        // 否则含保留文件的目录永远显示半选）
        aggregateVueFileDirs(pkgNode);
        // 目录置顶 + 自然序：文件行按 rel 排序挂载会让目录节点按"首个文件出现位置"插队
        // （a0535.css 在 dialogs/ 之前 → dialogs 目录排到文件后面），与普通目录视图的
        // "目录在前"顺序不一致，勾选模块前后列表会跳动
        sortVueFileChildren(pkgNode);
    }

    /**
     * 对 Vue 模块文件视图子树排序：目录在前、文件在后，各自按名称自然序（递归）。
     *
     * <p>与目录懒加载视图（目录骨架在前、文件在后）保持同一顺序，模块条目 ↔ 普通目录
     * 形态切换时列表不跳动。</p>
     *
     * @param parent 模块条目节点或其内部目录节点
     * @author xumanyi
     * @date 2026-08-14
     */
    private static void sortVueFileChildren(CheckedTreeNode parent) {
        List<CheckedTreeNode> kids = new ArrayList<>();
        for (int i = 0; i < parent.getChildCount(); i++) {
            if (!(parent.getChildAt(i) instanceof CheckedTreeNode child)) return;
            kids.add(child);
        }
        if (kids.isEmpty()) return;
        kids.sort(Comparator
                .comparingInt((CheckedTreeNode n) ->
                        n.getUserObject() instanceof String ? 0 : 1)
                .thenComparing(n -> {
                    Object uo = n.getUserObject();
                    if (uo instanceof String s) return s;
                    if (uo instanceof VueFileRow r) {
                        int slash = r.rel.lastIndexOf('/');
                        return slash >= 0 ? r.rel.substring(slash + 1) : r.rel;
                    }
                    return "";
                }, TargetSectionPanel::naturalCompare));
        parent.removeAllChildren();
        for (CheckedTreeNode child : kids) {
            parent.add(child);
            if (child.getUserObject() instanceof String) {
                sortVueFileChildren(child);
            }
        }
    }

    /**
     * 聚合 Vue 模块文件视图内部目录节点的勾选状态：可勾选（非 KEEP）后代全勾 → 目录勾选。
     *
     * <p>只动模块条目内部的目录节点，不动模块条目自身（它的勾选 = 是否作为主目标，
     * 与文件级微调无关）。</p>
     *
     * @param parent 模块条目节点或其内部目录节点
     * @return {@code [可勾选后代数, 已勾选数]}
     * @author xumanyi
     * @date 2026-08-13
     */
    private static int[] aggregateVueFileDirs(CheckedTreeNode parent) {
        int total = 0;
        int checked = 0;
        for (int i = 0; i < parent.getChildCount(); i++) {
            if (!(parent.getChildAt(i) instanceof CheckedTreeNode child)) continue;
            Object uo = child.getUserObject();
            if (uo instanceof VueFileRow) {
                total++;
                if (child.isChecked()) checked++;
            } else if (uo instanceof String) {
                int[] r = aggregateVueFileDirs(child);
                child.setChecked(r[0] > 0 && r[0] == r[1]);
                total += r[0];
                checked += r[1];
            }
        }
        return new int[]{total, checked};
    }

    /**
     * 异步加载 Vue 模块目录的文件合并视图（本地 dist 产物 ∪ 远端现状）。
     *
     * <p>本地半边即时可得；远端半边走 FTP 递归列举（新建模块远端无目录，直接空集）。
     * 加载完成后重建树视图，行勾选默认全选（排除式做减法）。</p>
     *
     * @param d 模块目录条目数据
     * @author xumanyi
     * @date 2026-08-13
     */
    private void startLoadVueModuleFiles(PackageNodeData d) {
        String moduleKey = d.info.getRelativePath();
        if (vueFileRowsByModule.containsKey(moduleKey) || vueFileLoading.contains(moduleKey)) {
            return;
        }
        String proj = selectedProject;
        String sys = (String) systemCombo.getSelectedItem();
        if (proj == null) return;
        vueFileLoading.add(moduleKey);
        final int generation = lazyGeneration;
        final String moduleId = d.info.getPackageName();
        final boolean createNew = d.createNew;
        // moduleKey 是树坐标（相对 scan 根）：拼 FTP 绝对路径必须补回子目录收窄前缀，
        // 否则收窄状态下会 LIST 到不存在的路径，空结果被误当成"远端暂无文件"缓存
        final String remoteAbs = new com.flux.deploy.plugin.model.FtpTargetSelection(
                proj, sys, moduleId, moduleKey).getRemoteDir()
                + buildSubdirPrefix() + moduleKey + "/";
        showLoading("加载模块文件 " + moduleId + " ...");
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            List<FtpBrowseService.RemoteFileInfo> remote = List.of();
            // 新建模块无远端目录，空集即成功；覆盖型模块必须列举成功才可信
            boolean remoteOk = createNew;
            if (!createNew) {
                synchronized (ftpLock) {
                    try {
                        if (browseService == null || !browseService.isConnected()) {
                            reconnectFtp();
                        }
                        if (browseService != null) {
                            remote = browseService.listAllFilesRecursive(remoteAbs, 6);
                            remoteOk = true;
                        }
                    } catch (Exception ex) {
                        // 连接可能空闲超时被服务端断开：重连一次再试（与目录懒加载同款兜底）
                        try {
                            reconnectFtp();
                            if (browseService != null) {
                                remote = browseService.listAllFilesRecursive(remoteAbs, 6);
                                remoteOk = true;
                            }
                        } catch (Exception retryEx) {
                            // 两次都失败：走下方"不缓存"路径，保留占位允许再次展开重试
                        }
                    }
                }
            }
            final List<FtpBrowseService.RemoteFileInfo> remoteFinal = remote;
            final boolean remoteOkFinal = remoteOk;
            SwingUtilities.invokeLater(() -> {
                hideLoading();
                if (generation != lazyGeneration) {
                    vueFileLoading.remove(moduleKey);
                    return;
                }
                vueFileLoading.remove(moduleKey);
                if (!remoteOkFinal) {
                    // 远端列举失败：绝不缓存空视图（会永久显示"没有文件"），
                    // 保留"加载中"占位，用户再次展开或重新勾选会自动重试
                    buildPackageTreeView();
                    return;
                }
                // 纯现状视图：只列 FTP 上真实存在的文件（更新前状态，不做"更新后预测"）
                List<VueFileRow> rows = new ArrayList<>();
                for (FtpBrowseService.RemoteFileInfo r : remoteFinal) {
                    rows.add(new VueFileRow(moduleKey, r.relativePath, r.size, r.modifiedTime));
                }
                rows.sort(Comparator.comparing(row -> row.rel, String::compareToIgnoreCase));
                vueFileRowsByModule.put(moduleKey, rows);
                buildPackageTreeView();
            });
        });
    }

    /**
     * 部署成功后刷新 Vue 模块文件视图：清空文件清单缓存并重新加载远端最新现状。
     *
     * <p>同时把 content 目录标记为未加载，重新 LIST 一遍——首次投放的模块目录
     * 部署后已在远端存在，条目要从「新建」修正为常规覆盖形态。
     * 用户的文件排除偏好（{@link #vueFileUnchecked}）保留。</p>
     *
     * @author xumanyi
     * @date 2026-08-13
     */
    public void refreshVueFileViews() {
        if (!isVueSource()) return;
        vueFileRowsByModule.clear();
        vueFileLoading.clear();
        for (String contentDir : findVueContentDirs()) {
            loadedDirs.remove(contentDir);
            loadingDirs.remove(contentDir);
        }
        // 重建触发 seedVueTargets：content 目录未加载 → 自动重新 LIST →
        // applyLevelScan 重新合成模块条目并预载勾选模块的文件视图
        rebuildPackageTree();
    }

    /**
     * 导出 Vue 目录直更的文件排除清单（模块目录 relativePath → 用户取消勾选的产物相对路径）
     *
     * <p>排除式语义：未展开过文件视图 / 未取消任何文件的模块不出现在清单中（= 整包）。</p>
     *
     * @return 排除清单（仅含有排除项的模块）
     * @author xumanyi
     * @date 2026-08-13
     */
    public Map<String, List<String>> getVueExcludedFiles() {
        Map<String, List<String>> out = new LinkedHashMap<>();
        // key 换算到与 getMainTargets 相同的坐标系（补子目录收窄前缀）：
        // 部署侧按 t.getRelativePath() 查排除清单，坐标不一致会让排除项静默失效
        String vuePrefix = buildSubdirPrefix();
        for (Map.Entry<String, Set<String>> e : vueFileUnchecked.entrySet()) {
            if (!e.getValue().isEmpty()) {
                List<String> rels = new ArrayList<>(e.getValue());
                rels.sort(String::compareToIgnoreCase);
                out.put(vuePrefix + e.getKey(), rels);
            }
        }
        return out;
    }

    /**
     * 按默认勾选规则把一个包登记进 packageDataByKey / userCheckedKeys。
     *
     * <p>与源产物精确同名的同类型包默认勾选（JAR 源额外标记 locked，用于加粗与搜索豁免）；
     * 备份目录的包一律不默认勾选。{@code forceChecked} 供懒加载场景使用——用户先勾选了
     * 某个目录再展开时，该目录下新加载的包继承勾选（与全量模式"勾目录=级联全部子包"的
     * 语义一致），此时备份规则也不拦（这是用户对目录的显式勾选，不是自动匹配）。</p>
     *
     * @param pkg          待登记的包
     * @param forceChecked true 时无条件勾选（继承所在目录的勾选状态）
     * @author xumanyi
     * @date 2026-08-04
     */
    private void seedPackage(PackageInfo pkg, boolean forceChecked) {
        boolean isMatch = sourceArtifactName != null
                && isExactArtifactMatch(pkg.getPackageName(), sourceArtifactName);
        boolean locked = false;
        boolean checkedByDefault = false;
        if ("JAR".equals(sourceArtifactType)) {
            if ("JAR".equals(pkg.getType()) && isMatch) {
                locked = true;
                checkedByDefault = true;
            }
        } else if ("WAR".equals(sourceArtifactType)) {
            // WAR 源：产物名与 FTP 目标包名可能不一致，全名匹配到的 war 默认勾选，
            // 但不加锁（locked 保持 false，不加粗、可自由取消）。
            if ("WAR".equals(pkg.getType()) && isMatch) {
                checkedByDefault = true;
            }
        }
        // Vue 源：目标是 seedVueTargets 合成的模块目录条目，扫描到的 .zip 只原样展示、
        // 一律不默认勾选（zip 不是 Vue 更新目标）
        // 备份目录里的包保留展示（避免"我的包去哪了"），但永远不默认勾选，
        // 防止一键部署时把备份遗留的旧包当成目标
        if (pkg.isFromBackup()) checkedByDefault = false;
        if (forceChecked) checkedByDefault = true;
        String key = packageKey(pkg);
        packageDataByKey.put(key, new PackageNodeData(pkg, locked));
        if (checkedByDefault) userCheckedKeys.add(key);
    }

    /**
     * 仅从当前状态（packageDataByKey + userCheckedKeys + 搜索关键字）重建树视图。
     * 不重新种子化，保留用户在搜索期间的勾选改动；过滤期间被隐藏的包仍计入 userCheckedKeys。
     */
    private void buildPackageTreeView() {
        // 重建前把当前展开的目录记进 expandedDirs：自动展开（定位匹配包 / Vue 模块视图）
        // 不经过 treeExpanded 回调，不记下来的话左侧改一下勾选、右侧整棵树就折回顶层
        captureExpandedDirs();
        packageTreeRoot = new CheckedTreeNode("目标包");

        // 有目录就有内容可展示：系统下没有任何包、但 FTP 上确实有目录时，
        // 仍要把目录结构摆出来，让用户看清"这些目录里就是没有包"
        if (packageDataByKey.isEmpty() && currentDirectories.isEmpty()) {
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

        // 搜索关键字过滤后仍需展示的包（先定下来，供目录骨架判断根层包是否存在）
        List<PackageNodeData> visible = new ArrayList<>();
        for (PackageNodeData d : sorted) {
            // 精确匹配包 (locked) 始终保留——它是默认勾选的部署对象，被搜索过滤掉视觉上太怪
            if (terms != null && !d.locked && !packageMatches(d, terms)) continue;
            visible.add(d);
        }

        // 按 "/" 分段建嵌套目录树：同一前缀只建一个节点，包挂在最深层目录下。
        // 这样 backup/20260502_xumanyi/shared-tms 不再是一行扁平串，而是
        // backup → 20260502_xumanyi → shared-tms 三层嵌套，backup 顶层节点全局只出现一次，
        // FTP 上有几十次备份也只是顶层 backup 节点下的子树，视觉上极简
        Map<String, CheckedTreeNode> dirByPath = new LinkedHashMap<>();

        // ==== 目录骨架：先于包建立，且源自 FTP 真实目录而非可见包 ====
        // 目录若从包反推，本身没有包（或包全被搜索关键字过滤掉）的目录会整个消失，
        // 用户会误以为 FTP 上根本没有这个目录。这里先把已加载的目录全建出来，
        // 搜索过滤只影响包，目录始终与 FTP 实际结构一致。
        // 根层包（subDirectory="."）直接挂 root，与目录节点混排（目录在前、包在后）。
        List<String> dirs = new ArrayList<>(currentDirectories);
        dirs.sort(TargetSectionPanel::naturalCompare);
        for (String dir : dirs) {
            // 搜索激活时只建名字命中的目录，避免一堆无关空目录把搜索结果淹掉；
            // 未命中但含命中包的目录仍会被下面的挂包环节按需带出（resolveDirNode 自动补祖先）
            if (terms != null && !dirMatches(dir, terms)) continue;
            // Vue 目录直更：模块目录已作为原子部署条目展示（VUEDIR 叶子），
            // 其自身及子目录不再画成普通目录节点，避免同名重复
            if (isUnderVueDirTarget(dir)) continue;
            resolveDirNode(dir, dirByPath);
        }

        // ==== 懒加载占位 ====
        // 尚未 LIST 过的目录挂一个占位子节点：撑出展开箭头，用户点开时触发按需加载
        for (Map.Entry<String, CheckedTreeNode> e : dirByPath.entrySet()) {
            if (!loadedDirs.contains(e.getKey())) {
                CheckedTreeNode placeholder = new CheckedTreeNode(LOADING_PLACEHOLDER);
                placeholder.setChecked(false);
                placeholder.setEnabled(false);
                e.getValue().add(placeholder);
            }
        }

        // ==== 挂包 ====
        // 记录"全名匹配且默认勾选"的包所在目录节点，稍后展开这些目录，实现"匹配到就
        // 自动定位/展开它所在文件夹"。JAR 源与 WAR 源同样适用：默认勾选的主目标包所在
        // 目录（如嵌套在系统节点下的 shared-tms）不再折叠隐藏，用户切系统即可看到命中的包
        List<CheckedTreeNode> matchedDirs = new ArrayList<>();
        List<CheckedTreeNode> vueDirNodesToExpand = new ArrayList<>();
        List<PackageNodeData> vueEntriesToLoad = new ArrayList<>();
        for (PackageNodeData d : visible) {
            CheckedTreeNode parent = resolveDirNode(d.info.getSubDirectory(), dirByPath);
            CheckedTreeNode pkgNode = new CheckedTreeNode(d);
            boolean checked = userCheckedKeys.contains(packageKey(d.info));
            pkgNode.setChecked(checked);
            parent.add(pkgNode);
            attachVueFileChildren(pkgNode, d);
            // 模块条目自动展开（尊重用户手动折叠）：勾选中的照旧；此前展开过的
            // （expandedDirs 记录，含左侧重新勾选后由普通目录转回条目的）也保持展开
            if ("VUEDIR".equals(d.info.getType())
                    && !collapsedVueModules.contains(d.info.getRelativePath())
                    && (checked || expandedDirs.contains(d.info.getRelativePath()))) {
                if (vueFileRowsByModule.containsKey(d.info.getRelativePath())) {
                    vueDirNodesToExpand.add(pkgNode);
                } else if (expandedDirs.contains(d.info.getRelativePath())) {
                    // 展开过但文件视图未加载：先展开占位再补触发加载（重建期间监听器不响应）
                    vueDirNodesToExpand.add(pkgNode);
                    vueEntriesToLoad.add(d);
                }
            }
            // Vue 源的目标是模块目录条目（VUEDIR），其自动展开由上方 vueDirNodesToExpand 处理；
            // 同名产物定位只对 jar/war 源有意义
            boolean sourceMatched = !isVueSource()
                    && isExactArtifactMatch(d.info.getPackageName(), sourceArtifactName);
            if (checked && sourceMatched) {
                matchedDirs.add(parent);
            }
        }

        // ==== 挂非部署普通文件（Vue 源模式）====
        // Vue 解包目录里模块内容是 js/css/json 等普通文件，展示 FTP 现状。
        // 勾选不做限制（自由勾选，状态跨重建保持）；这些文件不属于勾选模块的部署内容，
        // 执行更新时由统一校验按所在目录拦截。搜索激活时不挂，避免淹没结果
        if (isVueSource() && terms == null) {
            for (Map.Entry<String, List<PackageInfo>> e : plainFilesByDir.entrySet()) {
                CheckedTreeNode dirNode = dirByPath.get(e.getKey());
                if (dirNode == null) continue;
                for (PackageInfo f : e.getValue()) {
                    // 版本记录 txt 是插件自己维护的元数据，不属于包内容，不展示
                    String lower = f.getPackageName().toLowerCase(Locale.ROOT);
                    if (lower.endsWith("_update_notes.txt") || "updatenote.txt".equals(lower)) {
                        continue;
                    }
                    // 可自由勾选，但勾选状态只存在于当前树（不跨重建回放）：
                    // 这类文件不属于任何部署内容，持久化回放会把陈旧勾选带到无关包上
                    CheckedTreeNode fn = new CheckedTreeNode(new PlainFileRow(f));
                    fn.setChecked(false);
                    dirNode.add(fn);
                }
            }
        }

        // Vue 源：模块目录条目与普通目录节点混排按名称排序。
        // 条目是"包"，默认挂在目录之后，手动勾选的目录一转成条目就整条跳到列表末尾，
        // 与 FTP 上的真实排列对不上，用户会以为目录被移动了
        if (isVueSource()) {
            sortVueSiblings(packageTreeRoot);
        }

        // 自底向上聚合：仅当目录下所有叶子（包）都勾选时父级才勾选，
        // 避免渲染器画"部分选中"的灰横条
        propagateDirChecked(packageTreeRoot);

        // Vue 源：恢复用户自由勾选的目录（聚合会把无叶子目录复位，这里补回；
        // 部署语义由执行更新时的校验拦截）
        if (isVueSource()) {
            for (String d : vueFreeCheckedDirs) {
                CheckedTreeNode n = dirByPath.get(d);
                if (n != null) n.setChecked(true);
            }
        }

        DefaultTreeModel model = new DefaultTreeModel(packageTreeRoot);
        rebuildingPackageTree = true;
        try {
            packageTree.setModel(model);
            packageTree.setRootVisible(false);
            // 默认展开规则（按层懒加载）：
            // - 搜索激活（terms != null）：已加载子树全展开，让命中项不被折叠藏住
            //   （未加载目录不展开——里面只有占位节点，没内容可看）
            // - 常态：目录默认全部折叠，只恢复用户此前展开过的目录（expandedDirs），
            //   再补展开"默认勾选主目标包"所在目录（expandPath 连带其祖先），
            //   让精确匹配到的包在切换系统后直接可见
            boolean hasSearch = terms != null;
            if (hasSearch) {
                for (int i = 0; i < packageTreeRoot.getChildCount(); i++) {
                    if (packageTreeRoot.getChildAt(i) instanceof CheckedTreeNode child) {
                        expandAllUnder(child);
                    }
                }
            } else {
                for (Map.Entry<String, CheckedTreeNode> e : dirByPath.entrySet()) {
                    if (expandedDirs.contains(e.getKey())) {
                        packageTree.expandPath(new TreePath(e.getValue().getPath()));
                    }
                }
                // 仅数据重种后的首次构建定位匹配包：展开状态转记 expandedDirs，
                // 此后随用户展开/折叠操作走常规恢复，不再强制弹开
                if (pendingAutoExpandMatched) {
                    pendingAutoExpandMatched = false;
                    for (CheckedTreeNode dir : matchedDirs) {
                        TreePath tp = new TreePath(dir.getPath());
                        packageTree.expandPath(tp);
                        String dp = dirPathOfTreePath(tp);
                        if (dp != null) expandedDirs.add(dp);
                    }
                }
                // Vue 模块文件视图自动展开（含其内部子目录，连带祖先目录展开到可见）
                for (CheckedTreeNode n : vueDirNodesToExpand) {
                    expandAllUnder(n);
                }
            }
        } finally {
            rebuildingPackageTree = false;
        }

        // 重建期间的程序化展开不触发监听器：恢复成展开态、但内容尚未加载的
        // 模块条目 / 普通目录，在这里补触发加载，避免"加载中"占位永远停着
        for (PackageNodeData d : vueEntriesToLoad) {
            startLoadVueModuleFiles(d);
        }
        if (terms == null) {
            for (Map.Entry<String, CheckedTreeNode> e : dirByPath.entrySet()) {
                if (expandedDirs.contains(e.getKey())
                        && !loadedDirs.contains(e.getKey())
                        && !loadingDirs.contains(e.getKey())
                        && packageTree.isExpanded(new TreePath(e.getValue().getPath()))) {
                    startLoadLevel(e.getKey());
                }
            }
        }

        packagePanel.setVisible(true);
        updateSelectionSummary();
        revalidate();
        repaint();
    }

    /**
     * 把树中当前展开的目录路径记进 {@link #expandedDirs}，供重建后恢复展开状态。
     *
     * @author xumanyi
     * @date 2026-08-14
     */
    private void captureExpandedDirs() {
        if (packageTree == null || packageTreeRoot == null) return;
        if (packageTree.getModel() == null
                || packageTree.getModel().getRoot() != packageTreeRoot) {
            return;
        }
        java.util.Enumeration<TreePath> expanded =
                packageTree.getExpandedDescendants(new TreePath(packageTreeRoot));
        if (expanded == null) return;
        while (expanded.hasMoreElements()) {
            // 宽口径：Vue 模块条目及其内部目录的展开状态一并记录，
            // 条目在重种后退化成普通目录时展开状态不丢
            String dirPath = expandKeyOfTreePath(expanded.nextElement());
            if (dirPath != null) expandedDirs.add(dirPath);
        }
    }

    /**
     * Vue 源：把同一层的「普通目录节点」与「模块目录条目」按名称混排（自然序），
     * 其余包节点、只读文件行仍排在后面。
     *
     * <p>只重排含模块条目的层级；模块条目内部的文件视图保持原有顺序，不进入递归。</p>
     *
     * @param node 子树根
     * @author xumanyi
     * @date 2026-08-14
     */
    private void sortVueSiblings(CheckedTreeNode node) {
        List<CheckedTreeNode> kids = new ArrayList<>();
        boolean hasVueDir = false;
        for (int i = 0; i < node.getChildCount(); i++) {
            if (!(node.getChildAt(i) instanceof CheckedTreeNode child)) return;
            kids.add(child);
            if (child.getUserObject() instanceof PackageNodeData pd
                    && "VUEDIR".equals(pd.info.getType())) {
                hasVueDir = true;
            }
        }
        for (CheckedTreeNode child : kids) {
            if (child.getUserObject() instanceof String) {
                sortVueSiblings(child);
            }
        }
        if (!hasVueDir || kids.size() < 2) return;
        kids.sort(Comparator.comparingInt(TargetSectionPanel::vueSiblingRank)
                .thenComparing(TargetSectionPanel::vueSiblingName,
                        TargetSectionPanel::naturalCompare));
        node.removeAllChildren();
        for (CheckedTreeNode child : kids) {
            node.add(child);
        }
    }

    /**
     * Vue 同层排序的分组序：目录 / 模块条目在前，其他包次之，只读文件行与占位在最后
     *
     * @param node 子节点
     * @return 分组序号，越小越靠前
     * @author xumanyi
     * @date 2026-08-14
     */
    private static int vueSiblingRank(CheckedTreeNode node) {
        Object uo = node.getUserObject();
        if (uo instanceof String) return 0;
        if (uo instanceof PackageNodeData pd) return "VUEDIR".equals(pd.info.getType()) ? 0 : 1;
        return 2;
    }

    /**
     * Vue 同层排序取用的显示名
     *
     * @param node 子节点
     * @return 用于比较的名称
     * @author xumanyi
     * @date 2026-08-14
     */
    private static String vueSiblingName(CheckedTreeNode node) {
        Object uo = node.getUserObject();
        if (uo instanceof String s) return s;
        if (uo instanceof PackageNodeData pd) return pd.info.getPackageName();
        if (uo instanceof PlainFileRow f) return f.info.getPackageName();
        return "";
    }

    /**
     * 在 packageTreeRoot 下按 "/" 分段建（或复用）嵌套目录节点，返回最深层目录节点（包应挂在它下面）。
     *
     * <p>{@code "."} 表示根层（扫描根自身的直接包），直接返回 root——根层包与目录节点
     * 混排在树顶（目录骨架先建、包后挂，天然目录在前包在后）；其他多段 subDir
     * （如 {@code backup/20260502_xumanyi/shared-tms}）按斜杠切开后逐层
     * computeIfAbsent，保证 {@code backup} 这种顶层段全局只建一个节点。</p>
     *
     * @param subDir    包所在的 subDirectory 字符串（来自 PackageInfo.getSubDirectory()）
     * @param dirByPath 路径前缀 → 节点缓存，键是累积的 "a/b/c" 形式串
     * @return 包应直接挂载到的目录节点
     */
    private CheckedTreeNode resolveDirNode(String subDir, Map<String, CheckedTreeNode> dirByPath) {
        if (".".equals(subDir)) {
            return packageTreeRoot;
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
                // CheckedTreeNode 默认 checked=true；已加载目录由 propagateDirChecked 聚合覆盖，
                // 未加载目录（仅占位）走"虚拟叶子"分支不再被聚合复位，必须建时显式置 false
                created.setChecked(false);
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
        // 懒加载占位不是内容：不计入叶子统计。勾选态保持挂载时设定的"跟随父节点"，
        // 不在这里复位——占位一旦被复位成未勾，已勾选的父节点会被平台画成"部分勾选"
        if (node.getUserObject() == LOADING_PLACEHOLDER) {
            node.setEnabled(false);
            return new int[]{0, 0};
        }
        // 普通文件行：可自由勾选，但不参与目录聚合（它不是部署内容，不应影响目录勾选态），
        // 也不在这里复位——勾选状态由用户操作与级联决定，树本身是唯一真源
        if (node.getUserObject() instanceof PlainFileRow) {
            return new int[]{0, 0};
        }
        // 叶子判定必须看"是不是包"，不能看 childCount==0：目录骨架里的空目录同样没有子节点，
        // 若被当成叶子，它会以"未勾选的叶子"身份计入父级 total，害得父目录永远无法显示全选。
        if (node.getUserObject() instanceof PackageNodeData pd) {
            // Vue 模块条目：内部文件视图的目录勾选态单独聚合（文件勾选变化时实时刷新），
            // 模块条目自身对外仍是一个叶子（勾选 = 是否作为主目标）
            if ("VUEDIR".equals(pd.info.getType()) && node.getChildCount() > 0) {
                aggregateVueFileDirs(node);
            }
            return new int[]{1, node.isChecked() ? 1 : 0};
        }
        // 未加载目录（仅含懒加载占位）：内容未知，勾选状态尊重用户操作、不按"子树无包"强制复位；
        // 以自身勾选状态作为一个虚拟叶子参与父级聚合。勾选它会自动触发该层加载
        // （见 triggerLoadForCheckedUnloadedDirs），加载回来的包继承勾选。
        if (node.getChildCount() == 1
                && node.getChildAt(0) instanceof CheckedTreeNode only
                && only.getUserObject() == LOADING_PLACEHOLDER) {
            // 占位跟随目录本身的勾选态，勾了未加载目录不会显示成"部分勾选"
            only.setChecked(node.isChecked());
            only.setEnabled(false);
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
        node.setChecked(total > 0 && checked == total);
        return new int[]{total, checked};
    }

    /**
     * Vue 源模式下复位"子树中没有任何可部署条目"的目录勾选。
     *
     * <p>Vue 的可部署目标（模块条目 {@link PackageNodeData}）由左侧模块勾选生成；
     * 勾选一个子树里没有条目的目录（如左侧未勾任何模块时的工程包目录）不产生部署语义，
     * 且之后任何懒加载重建都会把它复位成未勾——与其让勾选"过一会儿弹回去"，
     * 不如即时复位，配合底部摘要行的「请先在左侧勾选要更新的 Vue 模块」提示。</p>
     *
     * <p>需在 {@code rebuildingPackageTree} 防抖标记内调用（setChecked 会触发状态回调）。</p>
     *
     * @param node 子树根
     * @return 该子树下是否存在可部署条目（模块条目 / 包节点）
     * @author xumanyi
     * @date 2026-08-14
     */
    private boolean clearVueMeaninglessDirChecks(CheckedTreeNode node, String path) {
        Object uo = node.getUserObject();
        if (uo instanceof PackageNodeData) {
            // 模块条目自身即可部署条目，其内部文件行不再下钻
            return true;
        }
        if (uo == LOADING_PLACEHOLDER) {
            return false;
        }
        if (uo instanceof PlainFileRow) {
            // 普通文件自身状态不动（用户勾了就保持勾着，执行更新时校验拦截）；
            // 但它不算"可部署条目"，不为所在目录链保勾
            return false;
        }
        boolean hasEntry = false;
        for (int i = 0; i < node.getChildCount(); i++) {
            if (node.getChildAt(i) instanceof CheckedTreeNode child) {
                String seg = child.getUserObject() instanceof String s ? s : null;
                String childPath = seg == null ? path
                        : (path.isEmpty() ? seg : path + "/" + seg);
                // 注意不能短路：每个子树都要遍历到，保证复位覆盖所有分支
                hasEntry |= clearVueMeaninglessDirChecks(child, childPath);
            }
        }
        if (node != packageTreeRoot) {
            if (vueFreeCheckedDirs.contains(path)) {
                // 用户自由勾选的不匹配目录：保持勾选（部署语义由执行更新时的校验拦截），
                // 抵消 propagateDirChecked 的"无叶子即复位"聚合
                node.setChecked(true);
            } else if (!hasEntry) {
                node.setChecked(false);
            }
        }
        return hasEntry;
    }

    /**
     * Vue 源模式下的"手动指定目标目录"：用户亲手勾选一个目录时，若目录名与某个勾选模块号
     * 一致（忽略大小写），把该目录登记为该模块的更新目标（VUEDIR 条目）。
     *
     * <p>设计取向：勾选阶段不做任何限制——用户勾哪个目录就是哪个目录，一律登记成勾选中的
     * 目标条目（含目录名与模块号对不上、同模块已有目标这两种情况），底部摘要行只做告知。
     * 是否放行由执行更新前的统一校验负责（{@link #getVueUnmatchedCheckedDirs} → 弹窗中止），
     * 部署预检阶段还会做远端存在性核对，双重保险。</p>
     *
     * <p>仅处理用户鼠标亲手点击的节点（{@code lastUserToggledNode}）：勾选级联会让
     * onNodeStateChanged 对每个后代目录各触发一次，级联出来的目录不应各自成为目标或刷提示。</p>
     *
     * @param node 状态变化的目录节点（userObject 为 String）
     * @author xumanyi
     * @date 2026-08-14
     */
    private void handleVueManualDirCheck(CheckedTreeNode node) {
        if (node != lastUserToggledNode) return;
        // 拼出目录全路径（根节点是"目标包"占位，跳过）；途经模块条目说明这是
        // 模块文件视图内部的目录（排除式文件勾选的一部分），不参与目标指定
        javax.swing.tree.TreeNode[] pathNodes = node.getPath();
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i < pathNodes.length; i++) {
            if (!(pathNodes[i] instanceof CheckedTreeNode cn)) return;
            Object uo = cn.getUserObject();
            if (uo instanceof PackageNodeData) return;
            if (!(uo instanceof String seg)) return;
            if (sb.length() > 0) sb.append('/');
            sb.append(seg);
        }
        String dirPath = sb.toString();
        if (dirPath.isEmpty()) return;
        if (!node.isChecked()) {
            // 取消勾选：连同其下的自由勾选记录与登记条目一并撤销
            // （级联取消不应留下看不见却仍待校验、仍算作目标的残留项）
            List<String> freeGone = new ArrayList<>();
            for (String p : vueFreeCheckedDirs) {
                if (p.equals(dirPath) || p.startsWith(dirPath + "/")) freeGone.add(p);
            }
            for (String p : freeGone) {
                vueFreeCheckedDirs.remove(p);
                packageDataByKey.remove(p);
                userCheckedKeys.remove(p);
            }
            // 快速二连击竞态：首击已把该目录登记为手动目标（重建被推迟），
            // 第二击取消作用在旧目录节点上——同步撤销登记，避免"看着未勾选、实际是目标"
            PackageNodeData existing = packageDataByKey.get(dirPath);
            if (existing != null && "VUEDIR".equals(existing.info.getType())) {
                userCheckedKeys.remove(dirPath);
            }
            vueManualTargets.values().removeIf(dirPath::equals);
            // 目录级联取消也同步"显式取消"记录：其下模块条目被一并取消，重种时不应勾回
            syncUncheckedModulesUnder(node, false);
            vueTargetNotice = null;
            return;
        }
        String basename = dirPath.substring(dirPath.lastIndexOf('/') + 1);
        String matched = null;
        for (String id : vueSelectedModuleIds) {
            if (id.equalsIgnoreCase(basename)) {
                matched = id;
                break;
            }
        }
        if (matched == null) {
            // 子树里有模块条目 → 正常的目录级联全选：把其下模块的"显式取消"记录同步清掉
            // （级联勾选也是用户意图，重种时不应再翻回未勾）
            if (hasVueEntryUnder(node)) {
                syncUncheckedModulesUnder(node, node.isChecked());
                return;
            }
            // 目录名与勾选模块号对不上：勾选照常生效（不限制用户），登记为一个待校验的
            // 目标条目，是否放行由执行更新前的统一校验弹窗把关
            vueFreeCheckedDirs.add(dirPath);
            registerVueDirTarget(dirPath, basename);
            vueTargetNotice = vueSelectedModuleIds.isEmpty()
                    ? "已勾选目录 " + basename + "；请在左侧勾选对应模块，执行更新时会校验"
                    : "已勾选目录 " + basename + "（与勾选的模块号不一致，执行更新时会校验）";
            SwingUtilities.invokeLater(this::buildPackageTreeView);
            return;
        }
        if (packageDataByKey.containsKey(dirPath)) {
            vueFreeCheckedDirs.remove(dirPath);
            return;
        }
        // 同一模块出现第二个目标目录：同样允许勾上，登记为待校验条目，
        // 执行更新前的校验会列出来让用户二选一（勾选阶段不替用户做决定）
        if (hasCheckedVueEntryForModule(matched, dirPath)) {
            vueFreeCheckedDirs.add(dirPath);
            registerVueDirTarget(dirPath, matched);
            vueTargetNotice = "已勾选目录 " + dirPath + "；模块 " + matched
                    + " 已有另一个目标，执行更新时需二选一";
            SwingUtilities.invokeLater(this::buildPackageTreeView);
            return;
        }
        vueFreeCheckedDirs.remove(dirPath);
        registerVueDirTarget(dirPath, matched);
        // 手动映射独立于重种生命周期持久化；用户重新指定目标视为重新启用该模块
        vueManualTargets.put(matched.toLowerCase(Locale.ROOT), dirPath);
        vueUserUncheckedModules.remove(matched.toLowerCase(Locale.ROOT));
        vueTargetNotice = "已将 " + dirPath + " 设为模块 " + matched + " 的更新目标";
        // 当前处于状态回调内，重建推到下一帧（目录节点会转为模块条目并加载文件视图）
        SwingUtilities.invokeLater(this::buildPackageTreeView);
    }

    /**
     * 把一个目录登记成勾选中的 Vue 目标条目（VUEDIR），已存在则只补勾选。
     *
     * @param dirPath  目录全路径
     * @param moduleId 条目对外的模块号（目录名与模块号不一致时用目录名，校验阶段据此判定）
     * @author xumanyi
     * @date 2026-08-14
     */
    private void registerVueDirTarget(String dirPath, String moduleId) {
        if (!packageDataByKey.containsKey(dirPath)) {
            int slash = dirPath.lastIndexOf('/');
            String parentDir = slash > 0 ? dirPath.substring(0, slash) : ".";
            PackageInfo info = new PackageInfo(parentDir, moduleId, "VUEDIR", dirPath,
                    0, false, 0L);
            packageDataByKey.put(dirPath, new PackageNodeData(info, true, false));
        }
        userCheckedKeys.add(dirPath);
    }

    /**
     * 文件行被勾选时，确保其所属的 Vue 模块条目处于勾选态（= 该模块目录是本次更新的目标包）。
     *
     * <p>模块条目的勾选与文件级取舍原本互相独立：从"全选后逐个取消"进来的模块条目一直勾着，
     * 而手动勾单个文件时条目仍未勾，执行更新会被「未选择目标包」拦下——同一诉求两种结果。
     * 这里统一为"勾了文件即选中该目标包"，不再要求用户先勾模块条目。</p>
     *
     * <p>同步撤销该模块的"显式取消"记录，避免下一次重种又把条目取消勾选。</p>
     *
     * @param fileNode 被勾选的文件行节点
     * @author xumanyi
     * @date 2026-08-14
     */
    private void ensureVueModuleChecked(CheckedTreeNode fileNode) {
        javax.swing.tree.TreeNode p = fileNode.getParent();
        while (p != null) {
            if (p instanceof CheckedTreeNode cn
                    && cn.getUserObject() instanceof PackageNodeData pd
                    && "VUEDIR".equals(pd.info.getType())) {
                if (!cn.isChecked()) {
                    cn.setChecked(true);
                    userCheckedKeys.add(packageKey(pd.info));
                    // 条目此前未勾 → 其下文件行也全是未勾状态（构建时按条目状态置的，
                    // 没进过排除清单）。条目转勾后重建会把它们默认勾回，与"只勾这一个文件"
                    // 的意图相反——这里把当前未勾的文件行显式记入排除清单，固化用户所见
                    recordUncheckedVueFiles(cn);
                }
                vueUserUncheckedModules.remove(
                        pd.info.getPackageName().toLowerCase(Locale.ROOT));
                return;
            }
            p = p.getParent();
        }
    }

    /**
     * 文件行被取消勾选后，若所属模块已没有任何勾选中的文件，同步取消模块条目的勾选。
     *
     * <p>与 {@link #ensureVueModuleChecked} 对称：勾第一个文件即选中该目标包，
     * 取消最后一个文件即撤销该目标包。否则条目自身仍勾着、其下文件全未勾，
     * 平台渲染器会把条目画成"部分勾选"的减号，看着像取消不掉。</p>
     *
     * @param fileNode 被取消勾选的文件行节点
     * @author xumanyi
     * @date 2026-08-14
     */
    private void ensureVueModuleUncheckedIfEmpty(CheckedTreeNode fileNode) {
        javax.swing.tree.TreeNode p = fileNode.getParent();
        while (p != null) {
            if (p instanceof CheckedTreeNode cn
                    && cn.getUserObject() instanceof PackageNodeData pd
                    && "VUEDIR".equals(pd.info.getType())) {
                if (cn.isChecked() && !hasCheckedVueFileUnder(cn)) {
                    cn.setChecked(false);
                    String key = packageKey(pd.info);
                    userCheckedKeys.remove(key);
                    vueUserUncheckedModules.add(
                            pd.info.getPackageName().toLowerCase(Locale.ROOT));
                    // 自由勾选登记的条目：一并撤销待校验记录与条目本身
                    if (vueFreeCheckedDirs.remove(key)) {
                        packageDataByKey.remove(key);
                    }
                }
                return;
            }
            p = p.getParent();
        }
    }

    /**
     * 判断子树中是否还有勾选中的 Vue 文件行
     *
     * @param node 模块条目节点或其内部目录节点
     * @return true 表示还有文件被勾选
     * @author xumanyi
     * @date 2026-08-14
     */
    private static boolean hasCheckedVueFileUnder(CheckedTreeNode node) {
        for (int i = 0; i < node.getChildCount(); i++) {
            if (!(node.getChildAt(i) instanceof CheckedTreeNode child)) continue;
            if (child.getUserObject() instanceof VueFileRow) {
                if (child.isChecked()) return true;
            } else if (hasCheckedVueFileUnder(child)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 把子树中当前未勾选的 Vue 文件行记入排除清单（该文件本次更新不覆盖）。
     *
     * @param node 模块条目节点或其内部目录节点
     * @author xumanyi
     * @date 2026-08-14
     */
    private void recordUncheckedVueFiles(CheckedTreeNode node) {
        for (int i = 0; i < node.getChildCount(); i++) {
            if (!(node.getChildAt(i) instanceof CheckedTreeNode child)) continue;
            if (child.getUserObject() instanceof VueFileRow row) {
                if (!child.isChecked()) {
                    vueFileUnchecked.computeIfAbsent(row.moduleKey, k -> new HashSet<>())
                            .add(row.rel);
                }
            } else if (child.getUserObject() instanceof String) {
                recordUncheckedVueFiles(child);
            }
        }
    }

    /**
     * 判断某模块是否已存在勾选中的 VUEDIR 目标条目（任意路径）
     *
     * @param moduleId 模块号
     * @return true 表示该模块已有勾选目标
     * @author xumanyi
     * @date 2026-08-14
     */
    private boolean hasCheckedVueEntryForModule(String moduleId) {
        return hasCheckedVueEntryForModule(moduleId, null);
    }

    /**
     * 判断某模块是否已存在勾选中的 VUEDIR 目标条目，可排除指定路径（判断"除我之外还有没有"）
     *
     * @param moduleId   模块号
     * @param excludeKey 需排除的条目 key（全路径）；null 表示不排除
     * @return true 表示该模块已有勾选目标
     * @author xumanyi
     * @date 2026-08-14
     */
    private boolean hasCheckedVueEntryForModule(String moduleId, String excludeKey) {
        for (Map.Entry<String, PackageNodeData> e : packageDataByKey.entrySet()) {
            if (excludeKey != null && excludeKey.equals(e.getKey())) continue;
            PackageNodeData d = e.getValue();
            if ("VUEDIR".equals(d.info.getType())
                    && d.info.getPackageName().equalsIgnoreCase(moduleId)
                    && userCheckedKeys.contains(e.getKey())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 获取 Vue 源模式下勾选中的 zip 文件（执行更新前校验用：Vue 更新不支持 zip 目标，
     * 勾了就拦下说明，而不是静默忽略）
     *
     * @return 勾选中的 zip 相对路径列表；无则为空列表
     * @author xumanyi
     * @date 2026-09-22
     */
    public List<String> getVueCheckedZips() {
        List<String> result = new ArrayList<>();
        if (!isVueSource()) return result;
        for (PackageNodeData d : getSelectedPackages()) {
            if ("ZIP".equals(d.info.getType())) {
                result.add(d.info.getRelativePath());
            }
        }
        return result;
    }

    /**
     * 获取 Vue 源模式下"自由勾选但与勾选模块不匹配"的目录列表（执行更新前校验用）
     *
     * @return 不匹配目录全路径列表；无则为空列表
     * @author xumanyi
     * @date 2026-08-14
     */
    public List<String> getVueUnmatchedCheckedDirs() {
        Set<String> result = new LinkedHashSet<>();
        // 以树上的实际勾选状态为准扫一遍：登记记录（vueFreeCheckedDirs）只是辅助，
        // 一旦某次勾选没走到登记（时序 / 级联 / 重建），只信记录就会把界面上明明勾着的
        // 错误目录漏过去——用户看到的是"勾了却既不更新也不拦截"
        if (isVueSource() && packageTreeRoot != null) {
            collectVueUnmatchedChecked(packageTreeRoot, "", result);
        }
        // 记录兜底：目录名对不上模块的（可能被搜索过滤、不在当前树里），
        // 以及目录名对得上、但该模块同时勾了多个目标目录的（树扫描按模块名判定不出重复）
        for (String p : vueFreeCheckedDirs) {
            String base = p.substring(p.lastIndexOf('/') + 1);
            if (!isSelectedVueModule(base) || countCheckedVueTargets(base) > 1) {
                result.add(p);
            }
        }
        return new ArrayList<>(result);
    }

    /**
     * 递归收集"右侧勾着、但与左侧勾选模块对不上"的目录 / 模块条目。
     *
     * <p>目录自身被勾、其子树里又没有任何模块条目时，才把它当成用户指定的目标目录来校验；
     * 子树里有条目的目录（级联全选造成的勾选）继续下钻，不重复报。</p>
     *
     * @param node 子树根
     * @param path 该节点的目录全路径（根为空串）
     * @param out  收集结果
     * @return 子树内是否存在勾选中的模块条目
     * @author xumanyi
     * @date 2026-08-14
     */
    private boolean collectVueUnmatchedChecked(CheckedTreeNode node, String path, Set<String> out) {
        Object uo = node.getUserObject();
        if (uo instanceof PackageNodeData pd) {
            if (!"VUEDIR".equals(pd.info.getType()) || !node.isChecked()) return false;
            if (!isSelectedVueModule(pd.info.getPackageName())) {
                out.add(pd.info.getRelativePath());
            }
            return true;
        }
        if (node != packageTreeRoot && !(uo instanceof String)) return false;
        boolean hasEntry = false;
        for (int i = 0; i < node.getChildCount(); i++) {
            if (!(node.getChildAt(i) instanceof CheckedTreeNode child)) continue;
            Object cuo = child.getUserObject();
            String childPath = path;
            if (cuo instanceof String seg) {
                childPath = path.isEmpty() ? seg : path + "/" + seg;
            } else if (cuo instanceof PlainFileRow && child.isChecked() && !path.isEmpty()) {
                // 勾选中的普通文件不属于任何勾选模块的部署内容：按所在目录报给校验拦截
                out.add(path);
                continue;
            } else if (!(cuo instanceof PackageNodeData)) {
                continue;
            }
            // 不能短路：每个分支都要走到，漏一个分支就漏一处错误勾选
            hasEntry |= collectVueUnmatchedChecked(child, childPath, out);
        }
        if (!hasEntry && node != packageTreeRoot && node.isChecked() && !path.isEmpty()) {
            String base = path.substring(path.lastIndexOf('/') + 1);
            if (!isSelectedVueModule(base)) out.add(path);
        }
        return hasEntry;
    }

    /**
     * 判断名称是否为左侧当前勾选的 Vue 模块号（忽略大小写）
     *
     * @param name 目录名 / 条目模块号
     * @return true 表示命中已勾选模块
     * @author xumanyi
     * @date 2026-08-14
     */
    /**
     * 统计某模块当前有几个勾选中的 VUEDIR 目标条目（判定"同模块多目标"用）
     *
     * @param moduleId 模块号
     * @return 勾选中的目标条目数量
     * @author xumanyi
     * @date 2026-08-14
     */
    private int countCheckedVueTargets(String moduleId) {
        int n = 0;
        for (Map.Entry<String, PackageNodeData> e : packageDataByKey.entrySet()) {
            PackageNodeData d = e.getValue();
            if ("VUEDIR".equals(d.info.getType())
                    && d.info.getPackageName().equalsIgnoreCase(moduleId)
                    && userCheckedKeys.contains(e.getKey())) {
                n++;
            }
        }
        return n;
    }

    /**
     * 判断名称是否为左侧当前勾选的 Vue 模块号（忽略大小写）
     *
     * @param name 目录名 / 条目模块号
     * @return true 表示命中已勾选模块
     * @author xumanyi
     * @date 2026-08-14
     */
    private boolean isSelectedVueModule(String name) {
        for (String id : vueSelectedModuleIds) {
            if (id.equalsIgnoreCase(name)) return true;
        }
        return false;
    }

    /**
     * 同步子树内模块条目的"显式取消"记录：目录级联勾选 → 清除记录（重种时保持勾选）；
     * 目录级联取消 → 记录为显式取消（重种时不勾回）。
     *
     * @param node    子树根
     * @param checked true 表示级联勾选（清记录），false 表示级联取消（加记录）
     * @author xumanyi
     * @date 2026-08-14
     */
    private void syncUncheckedModulesUnder(CheckedTreeNode node, boolean checked) {
        if (node.getUserObject() instanceof PackageNodeData pd) {
            if ("VUEDIR".equals(pd.info.getType())) {
                String mid = pd.info.getPackageName().toLowerCase(Locale.ROOT);
                if (checked) {
                    vueUserUncheckedModules.remove(mid);
                } else {
                    vueUserUncheckedModules.add(mid);
                }
            }
            return;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            if (node.getChildAt(i) instanceof CheckedTreeNode c) {
                syncUncheckedModulesUnder(c, checked);
            }
        }
    }

    /**
     * 判断子树内是否存在 Vue 可部署条目（模块条目 / 包节点）
     *
     * @param node 子树根
     * @return true 表示子树内有条目，目录勾选属于正常级联
     * @author xumanyi
     * @date 2026-08-14
     */
    private boolean hasVueEntryUnder(CheckedTreeNode node) {
        if (node.getUserObject() instanceof PackageNodeData) return true;
        for (int i = 0; i < node.getChildCount(); i++) {
            if (node.getChildAt(i) instanceof CheckedTreeNode c && hasVueEntryUnder(c)) {
                return true;
            }
        }
        return false;
    }

    /** 把指定子树整棵展开（搜索激活时用，避免命中项被折叠藏住）。 */
    private void expandAllUnder(CheckedTreeNode root) {
        // 未加载目录下只有懒加载占位，没内容可展示——不展开，
        // 避免"加载中..."占位在搜索结果里驻留误导（用户手动点开时才真正触发加载）
        if (root.getChildCount() == 1
                && root.getChildAt(0) instanceof CheckedTreeNode only
                && only.getUserObject() == LOADING_PLACEHOLDER) {
            return;
        }
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
        return matchesAllTerms(haystack, pinyinCandidate, terms);
    }

    /** 目录过滤匹配：目录路径命中（子串或拼音），多关键字 AND */
    private boolean dirMatches(String dirPath, String[] terms) {
        return matchesAllTerms(dirPath.toLowerCase(Locale.ROOT), dirPath, terms);
    }

    /**
     * 判断候选文本是否命中全部关键字（每个关键字：小写子串命中或拼音命中）。
     *
     * @param lowerHaystack  已转小写的候选文本（做子串匹配）
     * @param pinyinCandidate 原始候选文本（做拼音匹配，需保留中文原样）
     * @param terms          关键字数组（已转小写），多关键字取 AND
     * @return 全部关键字均命中时返回 true
     * @author xumanyi
     * @date 2026-07-15
     */
    private static boolean matchesAllTerms(String lowerHaystack, String pinyinCandidate,
                                            String[] terms) {
        for (String term : terms) {
            if (!lowerHaystack.contains(term)
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

    // ==================== 目录树按层懒加载 ====================

    /** 懒加载代际：切换系统/项目、刷新、清空时 +1，令在途异步加载结果作废，防止旧数据串台 */
    private int lazyGeneration;

    /**
     * 数据重种（{@link #rebuildPackageTree}）后的首次视图构建时，自动展开"默认勾选匹配包"
     * 所在目录；消费后立即清零——懒加载完成等增量重建不再重复定位，避免反复弹开用户已折叠的目录。
     */
    private boolean pendingAutoExpandMatched;

    /**
     * 整体重置懒加载状态（切换系统/项目、刷新、清空目标区时调用）。
     *
     * @author xumanyi
     * @date 2026-08-04
     */
    private void resetLazyState() {
        lazyGeneration++;
        loadedDirs.clear();
        loadingDirs.clear();
        expandedDirs.clear();
        currentPackages.clear();
        currentDirectories.clear();
        plainFilesByDir.clear();
        vueFreeCheckedDirs.clear();
        vueManualTargets.clear();
        vueUserUncheckedModules.clear();
        // Vue 模块文件合并视图与远端绑定：换系统/项目/刷新后作废重载
        vueFileRowsByModule.clear();
        vueFileLoading.clear();
        vueFileUnchecked.clear();
        collapsedVueModules.clear();
        resetVueContentSearch();
    }

    /**
     * 从树路径拼出目录的相对路径（相对扫描根，"a/b/c" 形式）。
     *
     * <p>路径组件的 userObject 均为 String（目录名）时才是目录路径；命中包节点或
     * 占位节点返回 {@code null}（它们不参与懒加载）。root（index 0）不计入。</p>
     *
     * @param treePath 树展开/折叠事件的路径
     * @return 目录相对路径；非目录节点或根节点时为 null
     * @author xumanyi
     * @date 2026-08-04
     */
    /**
     * 从树路径拼出"展开状态记录"用的宽口径 key。
     *
     * <p>与 {@link #dirPathOfTreePath} 的差别：路径中允许出现 Vue 模块目录条目
     * （{@link PackageNodeData} type=VUEDIR），以其 relativePath 接续拼路径。
     * 这样模块条目本身与其内部目录的展开状态都落在与普通目录同一坐标系里——
     * 左侧模块勾选变化导致条目 ↔ 普通目录形态互换时，展开状态得以延续。</p>
     *
     * @param treePath 树展开/折叠事件的路径
     * @return 展开记录 key；路径含包节点等无坐标节点时为 null
     * @author xumanyi
     * @date 2026-08-14
     */
    private static String expandKeyOfTreePath(TreePath treePath) {
        Object[] comps = treePath.getPath();
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i < comps.length; i++) {
            if (!(comps[i] instanceof CheckedTreeNode n)) return null;
            Object uo = n.getUserObject();
            if (uo instanceof PackageNodeData pd && "VUEDIR".equals(pd.info.getType())) {
                sb.setLength(0);
                sb.append(pd.info.getRelativePath());
            } else if (uo instanceof String seg) {
                if (sb.length() > 0) sb.append('/');
                sb.append(seg);
            } else {
                return null;
            }
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    private static String dirPathOfTreePath(TreePath treePath) {
        Object[] comps = treePath.getPath();
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i < comps.length; i++) {
            if (!(comps[i] instanceof CheckedTreeNode n)
                    || !(n.getUserObject() instanceof String seg)) {
                return null;
            }
            if (sb.length() > 0) sb.append('/');
            sb.append(seg);
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    /**
     * 查询目录路径对应树节点当前的勾选状态。
     *
     * @param dirPath 目录相对路径（"a/b/c" 形式）
     * @return 找到节点且被勾选时 true；未找到（如被搜索过滤隐藏）时 false
     * @author xumanyi
     * @date 2026-08-04
     */
    private boolean isDirNodeChecked(String dirPath) {
        CheckedTreeNode node = packageTreeRoot;
        outer:
        for (String seg : dirPath.split("/")) {
            for (int i = 0; i < node.getChildCount(); i++) {
                if (node.getChildAt(i) instanceof CheckedTreeNode c
                        && c.getUserObject() instanceof String s && s.equals(seg)) {
                    node = c;
                    continue outer;
                }
            }
            return false;
        }
        return node != packageTreeRoot && node.isChecked();
    }

    /**
     * 递归找出树中"已勾选但尚未加载"的目录并触发按层加载。
     *
     * <p>勾选目录的语义是"选中其下全部包"；懒加载下该目录内容可能还没拉取，
     * 这里自动补一次加载（{@link #startLoadLevel} 的 dirChecked 快照会让加载回来的包
     * 继承勾选），避免"勾了文件夹、部署时里面的包却不算数"的静默陷阱。</p>
     *
     * @param node       当前递归节点（从 root 起）
     * @param pathPrefix 当前节点对应的目录路径前缀（root 为空串）
     * @author xumanyi
     * @date 2026-08-04
     */
    private void triggerLoadForCheckedUnloadedDirs(CheckedTreeNode node, String pathPrefix) {
        for (int i = 0; i < node.getChildCount(); i++) {
            if (!(node.getChildAt(i) instanceof CheckedTreeNode child)) continue;
            if (!(child.getUserObject() instanceof String seg)) continue;
            String path = pathPrefix.isEmpty() ? seg : pathPrefix + "/" + seg;
            if (child.isChecked() && !loadedDirs.contains(path) && !loadingDirs.contains(path)) {
                startLoadLevel(path);
            }
            triggerLoadForCheckedUnloadedDirs(child, path);
        }
    }

    /**
     * 按需加载一个目录层级（用户首次展开该目录时触发）。
     *
     * <p>单次 FTP LIST 该目录：子目录进目录骨架（继续挂占位等待下钻），其中的 jar/war
     * 按默认勾选规则登记进包数据；若该目录节点在触发加载时已被勾选（用户先勾了文件夹再展开），
     * 新加载的包继承勾选，与"勾目录=级联全部子包"的语义一致。加载期间顶部显示状态条；
     * 失败时折叠回该目录并提示。</p>
     *
     * @param dirPath 目录相对路径（相对扫描根）
     * @author xumanyi
     * @date 2026-08-04
     */
    private void startLoadLevel(String dirPath) {
        String proj = selectedProject;
        String sys = (String) systemCombo.getSelectedItem();
        // 项目根上下文（未选系统）只有根层直接包，树里不会出现可展开目录，这里仅防御
        if (proj == null || sys == null || browseService == null) return;
        loadingDirs.add(dirPath);
        final int generation = lazyGeneration;
        final String scanRoot = buildScanPath(proj, sys);
        final String relPathPrefix = buildSubdirPrefix();
        // 勾选状态在 EDT 先取快照，pooled 线程不碰 Swing 树
        final boolean dirChecked = isDirNodeChecked(dirPath);
        showLoading("加载目录 " + dirPath + " ...");
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                synchronized (ftpLock) {
                    try {
                        if (browseService == null || !browseService.isConnected()) {
                            reconnectFtp();
                        }
                        if (browseService == null) return;
                        applyLevelScan(generation, dirPath, dirChecked,
                                browseService.listLevel(scanRoot, dirPath), relPathPrefix);
                    } catch (Exception ex) {
                        // 连接可能超时，尝试重连一次
                        try {
                            reconnectFtp();
                            if (browseService != null) {
                                applyLevelScan(generation, dirPath, dirChecked,
                                        browseService.listLevel(scanRoot, dirPath), relPathPrefix);
                                return;
                            }
                        } catch (Exception retryEx) {
                            // 重连也失败
                        }
                        SwingUtilities.invokeLater(() -> {
                            if (generation != lazyGeneration) return;
                            loadingDirs.remove(dirPath);
                            expandedDirs.remove(dirPath);
                            buildPackageTreeView();
                            setLoginFailed("加载失败：" + ex.getMessage());
                        });
                    }
                }
            } finally {
                SwingUtilities.invokeLater(this::hideLoading);
            }
        });
    }

    /**
     * 在 EDT 上套用一层懒加载扫描结果：目录进骨架、包登记默认勾选（或继承目录勾选），随后重建树视图。
     *
     * <p>relativePath 前缀修正原因见 {@link #loadTargetPackages}；目录路径与
     * {@link PackageInfo#getSubDirectory()} 同属"相对 scan 根"坐标系，保持原值。</p>
     *
     * @param generation    发起加载时的懒加载代际（与当前不符说明期间切换了系统/项目，结果作废）
     * @param dirPath       本次加载的目录（相对扫描根）
     * @param dirChecked    该目录节点在触发加载时是否已勾选（true 时新包继承勾选）
     * @param scan          该层扫描结果
     * @param relPathPrefix 子目录前缀（如 {@code "shared-tms/"}），无收窄时为空串
     * @author xumanyi
     * @date 2026-08-04
     */
    private void applyLevelScan(int generation, String dirPath, boolean dirChecked,
                                 ScanResult scan, String relPathPrefix) {
        List<PackageInfo> packages =
                applySubdirPrefixToRelativePath(scan.getPackages(), relPathPrefix);
        List<String> directories = scan.getDirectories();
        SwingUtilities.invokeLater(() -> {
            if (generation != lazyGeneration || loadedDirs.contains(dirPath)) return;
            loadingDirs.remove(dirPath);
            loadedDirs.add(dirPath);
            // 非部署普通文件：Vue 源模式下作只读行展示（模块目录内容就是 js/css 等普通文件）
            if (!scan.getPlainFiles().isEmpty()) {
                plainFilesByDir.put(dirPath, scan.getPlainFiles());
            } else {
                plainFilesByDir.remove(dirPath);
            }
            currentPackages.addAll(packages);
            // 去重：同一目录被重复 LIST（探测落地 / 部署后刷新）时不产生重复骨架条目
            for (String dir : directories) {
                if (!currentDirectories.contains(dir)) {
                    currentDirectories.add(dir);
                }
            }
            for (PackageInfo pkg : packages) {
                seedPackage(pkg, dirChecked);
            }
            // 新加载的层里可能出现 content 目录或模块目录（合成/修正目录直更条目）
            seedVueTargets();
            buildPackageTreeView();
        });
    }

    /**
     * 更新选择摘要 + 包数量统计（共 X 个 / 已勾 Y 个）。
     *
     * <p>总数 = {@code packageDataByKey.size()}（已按层加载的包全量，不做过滤隐藏；
     * 懒加载下随目录展开增长），已勾 = {@code userCheckedKeys.size()}
     * （与树视图过滤无关，即被搜索隐藏但仍勾选的包也算）。</p>
     *
     * <p>主目标 / 嵌入按 {@link #isMainTarget} / {@link #isEmbedTarget} 真实判定统计；
     * 两者皆非的勾选（如 WAR 源下勾了 jar）不会参与部署，摘要如实提示"忽略"，
     * 避免用户误以为勾了就会更新。</p>
     */
    private void updateSelectionSummary() {
        // 手动指定 Vue 目标目录的即时反馈优先展示（成功确认 / 不匹配原因），
        // 直到下一次勾选动作或摘要状态变化才回到常规文案
        if (isVueSource() && vueTargetNotice != null) {
            setSummaryText(vueTargetNotice, false);
            updatePackageCountOnly();
            return;
        }
        List<PackageNodeData> selected = getSelectedPackages();
        int direct = 0;
        int embed = 0;
        int ignored = 0;
        int createNew = 0;
        for (PackageNodeData d : selected) {
            if (isMainTarget(d)) {
                direct++;
                if (d.createNew) createNew++;
            } else if (isEmbedTarget(d)) {
                embed++;
            } else {
                ignored++;
            }
        }
        if (isVueSource() && direct == 0) {
            // Vue 源没有可用的模块目录目标：把原因直接告诉用户——左侧没勾模块 /
            // 服务包目录探测进行中、失败（网络）、未找到 / 勾的是 zip（不是目标）
            if (vueSelectedModuleIds.isEmpty()) {
                setSummaryText("请先在左侧勾选要更新的 Vue 模块", false);
            } else {
                String hint = getVueTargetStatusHint();
                if (hint == null) {
                    setSummaryText("未选择目标包", false);
                } else {
                    boolean warn = vueContentSearchOutcome == VueSearchOutcome.FAILED
                            || vueContentSearchOutcome == VueSearchOutcome.NOT_FOUND
                            || ignored > 0;
                    setSummaryText(hint, warn);
                }
            }
        } else if (selected.isEmpty()) {
            setSummaryText("未选择目标包", false);
        } else if (sourceArtifactType == null) {
            // 未选源产物时主目标/嵌入尚无从判定，只报勾选数，避免"类型不符"的误导提示
            setSummaryText("已勾 " + selected.size() + " 个（未选择源产物）", false);
        } else if (isVueSource()) {
            // Vue 源：计数已由左下角「共 X 个模块 / 已勾 Y」承担，不再附加语义说明文案
            setSummaryText(" ", false);
        } else {
            StringBuilder sb = new StringBuilder("已选：").append(direct).append(" 个主目标");
            if (createNew > 0) {
                sb.append("（含 ").append(createNew).append(" 个新建）");
            }
            if (embed > 0) {
                sb.append(" + ").append(embed).append(" 个 WAR 嵌入");
            }
            if (ignored > 0) {
                sb.append("（").append(ignored).append(" 个与源产物类型不符，忽略）");
            }
            setSummaryText(sb.toString(), false);
        }

        packageCountLabel.setText(formatPackageCount(selected.size()));
    }

    /**
     * 设置底部摘要行文案：告警态红字并把全文放进悬停提示（窄面板下长句会被截断），
     * 常规态恢复灰字、清空提示。
     *
     * @param text 摘要文案
     * @param warn true 表示需要用户处理的告警（探测失败 / 未找到 / 勾了无效目标）
     * @author xumanyi
     * @date 2026-09-22
     */
    private void setSummaryText(String text, boolean warn) {
        selectionSummary.setText(text);
        selectionSummary.setForeground(warn ? Color.RED : Color.GRAY);
        selectionSummary.setToolTipText(warn ? text : null);
    }

    /**
     * 仅刷新左下角的包计数（共 X / 已勾 Y），不动右侧摘要文案
     *
     * <p>供 vueTargetNotice 提示占用摘要行时使用，保证计数仍然实时。</p>
     *
     * @author xumanyi
     * @date 2026-08-14
     */
    private void updatePackageCountOnly() {
        packageCountLabel.setText(formatPackageCount(getSelectedPackages().size()));
    }

    /**
     * 拼左下角计数文案。Vue 源计的是"模块目标条目"数，明确写出"模块"，
     * 避免与树里同时展示的目录 / 文件行混淆成"共 43 个文件"。
     *
     * @param selectedCount 已勾选条目数
     * @return 计数文案；无条目时为占位空串
     * @author xumanyi
     * @date 2026-08-14
     */
    private String formatPackageCount(int selectedCount) {
        int total = packageDataByKey.size();
        if (total == 0) {
            return " ";
        }
        String unit = isVueSource() ? " 个模块" : " 个";
        return "共 " + total + unit + " / 已勾 " + selectedCount + " 个";
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
     * 判断一个已勾选的目标包是否为"直接部署主目标"。
     *
     * <p>与源产物同类型的勾选包都是主目标（源产物直接覆盖到该包位置，不要求文件名一致）：
     * WAR 源——勾选的 war；JAR 源——勾选的 jar。目标包树不再过滤隐藏后，
     * 主目标判定只看类型，不再依赖 {@code locked}（精确匹配）标记，
     * 用户手动勾选的任意同类型包都会真实部署。</p>
     *
     * @param d 已勾选的包数据
     * @return true 表示作为主目标直接部署
     * @author xumanyi
     * @date 2026-06-17
     */
    private boolean isMainTarget(PackageNodeData d) {
        if (sourceArtifactType == null) return false;
        if (isVueSource()) {
            // Vue 源唯一的目标形态：模块目录（服务包解包目录逐文件更新）；勾选的 zip 不参与部署
            return "VUEDIR".equals(d.info.getType());
        }
        return sourceArtifactType.equals(d.info.getType());
    }

    /**
     * 判断一个已勾选的目标包是否为"WAR 嵌入目标"（把源 jar 替换进该 war 内部）。
     *
     * <p>仅 JAR 源场景存在嵌入：勾选的 war 即嵌入目标。WAR 源不存在嵌入（恒返回 false）。</p>
     *
     * @param d 已勾选的包数据
     * @return true 表示作为 WAR 嵌入目标
     * @author xumanyi
     * @date 2026-06-17
     */
    private boolean isEmbedTarget(PackageNodeData d) {
        // 仅 JAR 源存在嵌入语义；WAR 源、Vue（ZIP）源勾选的 war 都不做嵌入
        return "JAR".equals(sourceArtifactType) && "WAR".equals(d.info.getType());
    }

    /**
     * 获取主目标列表（直接部署目标）。
     *
     * <p>WAR 源：所有勾选的 war；JAR 源：所有勾选的 jar（同一系统下多个子目录可能各有一份，
     * 例如 shared-edp / shared-tms，逐个返回供部署流程分别处理）。判定见 {@link #isMainTarget}。</p>
     *
     * @return 主目标列表，可能为空
     * @author xumanyi
     * @date 2026-04-18
     */
    public List<FtpTargetSelection> getMainTargets() {
        String proj = selectedProject;
        if (proj == null) return List.of();
        // sys 可能为 null：项目根目录直接部署（未选系统），getRemoteDir() 会退化为项目根路径
        String sys = (String) systemCombo.getSelectedItem();

        List<FtpTargetSelection> result = new ArrayList<>();
        // 子目录收窄时的坐标换算：jar/war 包的 relativePath 在扫描落地时已补回子目录前缀，
        // 而 Vue 模块条目由 seedVueTargets 按树坐标（相对 scan 根）合成、未补前缀——
        // 下游一律按 remoteDir(/开发/项目/系统/) + relativePath 拼 FTP 绝对路径，
        // 不在这里补前缀会把部署/备份/文件视图全部指到错误路径（漏掉收窄的子目录层）
        String vuePrefix = buildSubdirPrefix();
        for (PackageNodeData d : getSelectedPackages()) {
            if (isMainTarget(d)) {
                boolean vueDir = "VUEDIR".equals(d.info.getType());
                String rel = vueDir
                        ? vuePrefix + d.info.getRelativePath()
                        : d.info.getRelativePath();
                result.add(new FtpTargetSelection(proj, sys,
                        d.info.getPackageName(), rel, d.createNew, vueDir));
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
     * 获取 WAR 嵌入目标列表（仅 JAR 源场景：勾选的非主目标 war）。判定见 {@link #isEmbedTarget}。
     *
     * @return WAR 嵌入目标列表，无嵌入目标时返回空列表
     * @author xumanyi
     * @date 2026-03-27
     */
    public List<FtpTargetSelection> getEmbedTargets() {
        String proj = selectedProject;
        if (proj == null) return List.of();
        // sys 可能为 null：项目根目录直接部署（未选系统）
        String sys = (String) systemCombo.getSelectedItem();

        List<FtpTargetSelection> embeds = new ArrayList<>();
        for (PackageNodeData d : getSelectedPackages()) {
            if (isEmbedTarget(d)) {
                embeds.add(new FtpTargetSelection(proj, sys,
                        d.info.getPackageName(), d.info.getRelativePath()));
            }
        }
        return embeds;
    }

    /**
     * 根据产物文件名自动选择目标包
     *
     * <p>委托给 {@link #setSourceArtifact(String)} 重建树并应用默认勾选规则。</p>
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
        titleLabel.setFont(JBFont.label().deriveFont(isCurrent ? Font.BOLD : Font.PLAIN));
        if (isCurrent) {
            titleLabel.setForeground(new Color(80, 170, 100));
        }
        titleLabel.setAlignmentX(Component.LEFT_ALIGNMENT);

        String sub = (acc.getVerifiedAt() == null || acc.getVerifiedAt().isBlank()
                ? "" : "最近验证：" + acc.getVerifiedAt());
        JBLabel subLabel = new JBLabel("   " + sub);
        subLabel.setFont(JBFont.label().lessOn(2f));
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
            // 删除是不可逆操作：confirmDanger 默认聚焦「取消」，避免误回车删账号
            boolean confirmed = com.flux.deploy.plugin.util.FluxDialogs.confirmDanger(parent,
                    "删除账号",
                    "确认删除账号 " + acc.getUsername() + "@" + acc.getHost() + "？\n"
                            + (isCurrent ? "当前已连接的账号将被断开。" : ""),
                    "删除账号");
            if (!confirmed) return;
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
     * 清空目标包列表（含懒加载缓存）
     */
    public void clearPackages() {
        resetLazyState();
        sourceArtifactName = null;
        sourceArtifactType = null;
        clearVueSource();
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

    /** 显示 FTP 登录对话框（统一 DialogWrapper 风格，含端口数字校验） */
    private void showLoginDialog() {
        FtpLoginDialog dialog = new FtpLoginDialog(project);
        if (dialog.showAndGet()) {
            setLoggingIn(null);
            doConnectAndSave(dialog.getHost(), dialog.getPort(),
                    dialog.getUsername(), dialog.getPassword());
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

    /** 加载指定项目下的系统列表 + 项目根目录直接包（连接失败时自动重连一次） */
    private void loadSystems(String projectName) {
        showLoading("加载系统 / 根目录包...");
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
            synchronized (ftpLock) {
                try {
                    // 连接可能超时，先检查并重连
                    if (browseService == null || !browseService.isConnected()) {
                        reconnectFtp();
                    }
                    if (browseService == null) return;

                    String projectRoot = "/开发/" + projectName + "/";
                    List<String> systems = browseService.listSubdirectories(projectRoot);
                    // 只扫项目根这一层的直接包（maxDepth=0，不下钻系统子目录），用于「根目录直接部署」的项目
                    List<PackageInfo> rootPackages = browseService.scanPackagesStructured(projectRoot, 0);
                    SwingUtilities.invokeLater(() -> applyProjectSelection(systems, rootPackages));
                } catch (Exception ex) {
                    // 重连一次
                    try {
                        reconnectFtp();
                        if (browseService != null) {
                            String projectRoot = "/开发/" + projectName + "/";
                            List<String> systems = browseService.listSubdirectories(projectRoot);
                            List<PackageInfo> rootPackages =
                                    browseService.scanPackagesStructured(projectRoot, 0);
                            SwingUtilities.invokeLater(() -> applyProjectSelection(systems, rootPackages));
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
     * 在 EDT 上套用「选定项目」后的目标区状态：刷新系统下拉，并把项目根目录这一层的直接包展示到目标包树。
     *
     * <p>有的项目把 jar/war 直接放在 {@code /开发/{项目}/} 根下、没有系统层，选完项目就应直接看到这些根包，
     * 无需先选系统——此时 systemCombo 保持未选，部署走「项目根上下文」（{@link FtpTargetSelection#getRemoteDir()}
     * 退化为项目根路径）。根层无包时 {@code rootPackages} 为空列表，等价于清空目标包树，回到「选系统后再递归加载」的原有路径；
     * 用户随后选择具体系统则照常触发系统级递归扫描，覆盖根包。</p>
     *
     * @param systems      项目下的系统子目录列表（填充系统下拉）
     * @param rootPackages 项目根目录这一层的直接包（只扫当前层的结果，可能为空）
     * @author xumanyi
     * @date 2026-06-02
     */
    private void applyProjectSelection(List<String> systems, List<PackageInfo> rootPackages) {
        refreshing = true;
        systemCombo.removeAllItems();
        // 项目切换会令旧子目录层级失效（refreshing=true 时 listener 不会代为清理）
        clearExtraLevels();
        for (String s : systems) systemCombo.addItem(s);
        systemCombo.setSelectedIndex(-1);
        refreshing = false;
        // 根层包直接展示（项目根上下文，system 留空）；无包则等价清空，等用户选系统走按层加载。
        // 重新套用源产物匹配，确保根包里的同名 JAR/WAR 被默认勾选。
        resetLazyState();
        currentPackages.addAll(rootPackages);
        // 项目根上下文不展示目录：根下的子目录就是"系统"，已由 systemCombo 承载，
        // 再画进目标包树等于把系统列表重复一遍。（扫描本身也是 maxDepth=0，不下钻。）
        loadedDirs.add("");
        reapplySource();
    }

    /**
     * 加载指定项目和系统下的目标包列表（懒加载：只列系统根层），连接超时时自动重连
     *
     * <p>只对系统根做一次 FTP LIST：根层包直接展示，子目录以折叠节点呈现、
     * 用户展开时再按需加载（见 {@link #startLoadLevel}），不再全量递归扫描整个子树。</p>
     *
     * <p>实际扫描路径由 {@link #buildScanPath(String, String)} 拼接，
     * 含已选中的子目录层级，用于把 (C) 类多层混深项目的列表收窄到指定子树。</p>
     *
     * <p><b>路径修正</b>：扫描收窄时，{@code listLevel} 返回的
     * {@link PackageInfo#getRelativePath()} 是相对于 <i>scan 根</i>（即 narrowed 路径）的；
     * 但下游 {@link com.flux.deploy.plugin.model.FtpTargetSelection#getRemoteDir()}
     * 总是按 {@code /开发/{proj}/{sys}/} 拼接，所以这里必须把"子目录前缀"
     * （如 {@code "shared-tms/"}）补回 relativePath，下游做 {@code remoteDir + relativePath}
     * 才能拿到完整 FTP 绝对路径。subDirectory 字段用于 UI 分组，保持原值不变。</p>
     */
    private void loadTargetPackages(String projectName, String systemName) {
        showLoading("加载目标包列表...");
        // 换系统即整体重置懒加载缓存；代际快照令旧的在途结果作废
        resetLazyState();
        final int generation = lazyGeneration;
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

                applyRootScan(generation, browseService.listLevel(scanPath, ""), relPathPrefix);
            } catch (Exception ex) {
                // 连接可能超时，尝试重连一次
                try {
                    reconnectFtp();
                    if (browseService != null) {
                        applyRootScan(generation,
                                browseService.listLevel(scanPath, ""), relPathPrefix);
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
     * 在 EDT 上套用系统根层扫描结果：包与子目录一并入库，随后重建目标包树。
     *
     * <p><b>只有包需要前缀修正</b>：{@link PackageInfo#getRelativePath()} 是下游拼 FTP 绝对路径用的，
     * 扫描收窄时必须补回子目录前缀（详见 {@link #loadTargetPackages}）；而目录列表与
     * {@link PackageInfo#getSubDirectory()} 同属"相对 scan 根"坐标系，只用于 UI 分组建树，
     * 保持原值即可——两者混用会让目录树凭空多出一层前缀。</p>
     *
     * @param generation    发起加载时的懒加载代际（与当前不符说明期间又切换了，结果作废）
     * @param scan          系统根层扫描结果
     * @param relPathPrefix 子目录前缀（如 {@code "shared-tms/"}），无收窄时为空串
     * @author xumanyi
     * @date 2026-07-15
     */
    private void applyRootScan(int generation, ScanResult scan, String relPathPrefix) {
        List<PackageInfo> packages =
                applySubdirPrefixToRelativePath(scan.getPackages(), relPathPrefix);
        List<String> directories = scan.getDirectories();
        SwingUtilities.invokeLater(() -> {
            if (generation != lazyGeneration) return;
            currentPackages.clear();
            currentPackages.addAll(packages);
            currentDirectories.clear();
            currentDirectories.addAll(directories);
            loadedDirs.add("");
            // 重新应用源产物匹配（确保默认勾选正确；Vue 源保留模块状态）
            reapplySource();
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

    /** 运行日志出口（目标面板自身的探测过程 / 结局要在运行日志留痕），由主面板注册 */
    private java.util.function.Consumer<String> logSink;

    /**
     * 注册运行日志出口。
     *
     * <p>Vue 服务包目录的自动探测在目标面板内异步进行，其开始 / 定位结果 / 失败原因
     * 通过此出口写入运行日志，用户事后能追溯"为什么右侧没有目标"。</p>
     *
     * @param sink 日志行消费者（自行保证线程安全）；null 表示不输出
     * @author xumanyi
     * @date 2026-09-22
     */
    public void setLogSink(java.util.function.Consumer<String> sink) {
        this.logSink = sink;
    }

    /**
     * 写一行运行日志（未注册出口时静默）
     *
     * @param line 日志行（含级别与标签前缀）
     * @author xumanyi
     * @date 2026-09-22
     */
    private void emitLog(String line) {
        java.util.function.Consumer<String> sink = logSink;
        if (sink != null) {
            sink.accept(line);
        }
    }

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
        // JAR 源下：是否为源产物精确同名匹配的包（默认勾选 + 渲染加粗 + 搜索豁免，用户仍可取消）。
        // WAR 源下恒为 false（所有 war 平等展示、不加粗）。主目标/嵌入由 isMainTarget/isEmbedTarget
        // 按源产物类型判定，与本字段无关。Vue 源下：合成的模块目录条目为 true（加粗）。
        final boolean locked;
        // Vue 源的「新建投放」模块目录条目：content 目录下尚无该模块目录，部署时首次投放（createNew 链路）。
        final boolean createNew;

        PackageNodeData(PackageInfo info, boolean locked) {
            this(info, locked, false);
        }

        PackageNodeData(PackageInfo info, boolean locked, boolean createNew) {
            this.info = info;
            this.locked = locked;
            this.createNew = createNew;
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
                if (userObj instanceof VueFileRow fileRow) {
                    // Vue 模块文件行：FTP 当前现状（文件名 + 远端大小/时间），
                    // 与 jar/war 包列表同款风格，不做"更新后预测"标注
                    int slash = fileRow.rel.lastIndexOf('/');
                    String fileName = slash >= 0 ? fileRow.rel.substring(slash + 1) : fileRow.rel;
                    getTextRenderer().append(fileName, SimpleTextAttributes.REGULAR_ATTRIBUTES);
                    String meta = formatPackageMeta(fileRow.remoteMtime, fileRow.remoteSize);
                    if (!meta.isEmpty()) {
                        getTextRenderer().append("   " + meta,
                                new SimpleTextAttributes(SimpleTextAttributes.STYLE_SMALLER,
                                        Color.GRAY));
                    }
                    return;
                }
                if (userObj instanceof PackageNodeData data) {
                    // 主目标加粗显示，便于识别；checkbox 不再置灰，允许用户取消
                    if (data.locked) {
                        getTextRenderer().append(data.info.getPackageName(),
                                SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES);
                    } else {
                        getTextRenderer().append(data.info.getPackageName(),
                                SimpleTextAttributes.REGULAR_ATTRIBUTES);
                    }
                    // 首次投放（createNew）不再加「新建」文字标注——列表里成串的标注是噪音，
                    // 且渲染位置随列宽漂移；新建语义保留在部署确认与日志里
                    // 包名后跟灰色小字「体积 修改时间」：用 STYLE_SMALLER 缩小字号弱化次要信息，
                    // 减轻长列表的视觉拥挤；不再显示 [WAR]/[JAR]（文件后缀已表明类型）
                    String meta = formatPackageMeta(data.info.getModifiedTime(), data.info.getSize());
                    if (!meta.isEmpty()) {
                        getTextRenderer().append("   " + meta,
                                new SimpleTextAttributes(SimpleTextAttributes.STYLE_SMALLER,
                                        Color.GRAY));
                    }
                } else if (userObj == LOADING_PLACEHOLDER) {
                    // 懒加载占位：目录尚未加载时的临时子节点，展开后即被真实内容替换
                    getTextRenderer().append("加载中...",
                            new SimpleTextAttributes(
                                    SimpleTextAttributes.STYLE_ITALIC | SimpleTextAttributes.STYLE_SMALLER,
                                    Color.GRAY));
                } else if (userObj == VUE_EMPTY_PLACEHOLDER) {
                    // Vue 模块文件视图：远端还没有文件时的提示行
                    getTextRenderer().append("（远端暂无文件）",
                            new SimpleTextAttributes(
                                    SimpleTextAttributes.STYLE_ITALIC | SimpleTextAttributes.STYLE_SMALLER,
                                    Color.GRAY));
                } else if (userObj instanceof PlainFileRow plainRow) {
                    // 目录浏览的普通文件：常规文字（与模块文件行同款），可自由勾选
                    getTextRenderer().append(plainRow.info.getPackageName(),
                            SimpleTextAttributes.REGULAR_ATTRIBUTES);
                    String meta = formatPackageMeta(plainRow.info.getModifiedTime(),
                            plainRow.info.getSize());
                    if (!meta.isEmpty()) {
                        getTextRenderer().append("   " + meta,
                                new SimpleTextAttributes(SimpleTextAttributes.STYLE_SMALLER,
                                        Color.GRAY));
                    }
                } else if (userObj instanceof String dirName) {
                    // 目录节点统一样式（不再按"子树内有无包"置灰——懒加载下未展开目录的内容未知）
                    getTextRenderer().append(dirName,
                            new SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, null));
                }
            }
        }

        /** 列表行「修改时间」格式，精确到分钟（FTP LIST 通常只给到分钟） */
        private static final DateTimeFormatter META_TIME_FMT =
                DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

        /**
         * 拼装包列表行右侧的灰色元信息：「体积 修改时间」（体积在前，两者间用空格分隔，不用「·」连接）。
         *
         * <p>体积按数量级自适应：&lt;1MB 显示 KB，否则显示 MB（各保留 1 位小数），&lt;=0 时省略，
         * 避免百 KB 级 jar 被截断成 "0 MB"；修改时间按本地时区格式化为 {@code yyyy-MM-dd HH:mm}，
         * 为 0（FTP 未给时间戳）时省略。两者都缺省时返回空串。</p>
         *
         * @param modifiedTime 文件最后修改时间（epoch 毫秒），0 表示未知
         * @param sizeBytes    文件大小（字节），&lt;=0 表示未知
         * @return 形如 "1.8 MB   2026-05-28 14:30" 的展示文本；无可展示信息时为空串
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
                return sizeStr + "   " + timeStr;
            }
            return sizeStr + timeStr;
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
                        com.flux.deploy.plugin.util.FluxDialogs.info(TargetSectionPanel.this,
                                "当前路径下无子目录，无需进一步缩小范围", "无子目录");
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
