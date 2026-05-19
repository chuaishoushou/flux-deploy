package com.flux.deploy.plugin.email;

import com.flux.deploy.email.DeployHistoryCache;
import com.flux.deploy.email.EmailDraftManager;
import com.flux.deploy.email.EmailTemplateStore;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.SystemInfo;
import com.intellij.ui.components.JBLabel;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.Action;
import javax.swing.Box;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.ListCellRenderer;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.UIManager;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;
import javax.swing.plaf.basic.BasicComboBoxUI;
import javax.swing.plaf.basic.BasicComboPopup;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.AWTEvent;
import java.awt.MouseInfo;
import java.awt.Point;
import java.awt.event.AWTEventListener;
import java.awt.event.ActionEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 通知邮件弹窗（单一编辑器版）：模板下拉 + WYSIWYG 编辑器 + 导入/保存/复制
 *
 * <p>UI 结构（从上到下）：</p>
 * <pre>
 *   ┌─ 通知邮件 ─────────────────────────────────────────┐
 *   │ 模板：[ComboBox ▾]  [新建] [恢复默认] [删除]         │
 *   │ ─────────────────────────────────────────────     │
 *   │ ┌── WYSIWYG 编辑器（JCEF contenteditable）──────┐   │
 *   │ │   渲染后的样子：颜色 / 字体 / 字号都生效        │   │
 *   │ │   ${字段名} 渲染为不可编辑的 chip（带值或斜体） │   │
 *   │ │   用户可在 chip 周围任意编辑                  │   │
 *   │ └────────────────────────────────────────────────┘   │
 *   │   [导入]   [保存模板]   [复制到剪贴板]   [关闭]      │
 *   └────────────────────────────────────────────────────┘
 * </pre>
 *
 * <p><b>核心思路</b>：弹窗 = 模板编辑器 + 实时预览（合二为一）。不再有独立的"编辑模板"子弹窗。</p>
 *
 * <p>用户工作流：</p>
 * <ol>
 *   <li>打开弹窗 → 编辑器加载当前模板，{@code ${字段名}} 已被快照值填充（空值显示占位斜体）</li>
 *   <li>调整文字 / 格式（可直接从企业微信邮箱粘贴富文本）</li>
 *   <li>点「导入」从部署历史缓存把更新包 / 备份目录写到对应 chip</li>
 *   <li>点「复制到剪贴板」→ chip 脱壳为内部值 → 写剪贴板 → 粘到企微邮箱</li>
 *   <li>需要让模板长期生效，就点「保存模板」 → chip 还原成 {@code ${字段名}} 落盘</li>
 * </ol>
 *
 * @author xumanyi
 * @date 2026-05-18
 */
public class EmailDialog extends DialogWrapper {

    /**
     * Default 模板在 UI 上的友好显示名（文件名仍是 "default"，仅 ComboBox 标签变）
     */
    private static final String DEFAULT_DISPLAY_LABEL = "默认模板";

    /**
     * 把模板文件名（如 "default" / "帝迈"）转成 UI 显示名（如 "默认模板" / "帝迈"）
     */
    private static String displayLabelOf(String name) {
        return EmailTemplateStore.DEFAULT_TEMPLATE_NAME.equals(name)
                ? DEFAULT_DISPLAY_LABEL : name;
    }

    /**
     * 把 UI 显示名（如 "默认模板"）转回文件名（如 "default"）
     */
    private static String nameOf(String displayLabel) {
        return DEFAULT_DISPLAY_LABEL.equals(displayLabel)
                ? EmailTemplateStore.DEFAULT_TEMPLATE_NAME : displayLabel;
    }

    /**
     * 更新包 / 备份包 多条之间的分隔符：纯 HTML 换行
     *
     * <p>续行与第一条的对齐由 chip 的 CSS（{@code display: inline-block}）自动处理，
     * 不依赖固定字符数。详见 {@code email-editor/index.html} 里 {@code .ph} 样式。</p>
     */
    private static final String PATH_SEPARATOR = "<br>";

    private final Project project;
    private final EmailDraftManager manager;
    private final EmailRuntimeData runtimeData;
    private final DeployHistoryCache historyCache;

    /** 字段值快照（弹窗打开时一次性 snapshot；导入按钮也会更新这个 map） */
    private final Map<String, String> fieldValuesSnapshot = new LinkedHashMap<>();

    // 视觉强制色：让弹窗顶部 / 编辑区在亮 / 暗主题下都保持"白纸感"
    private static final Color BG_WHITE = Color.WHITE;
    private static final Color TEXT_DARK = new Color(0x333333);
    private static final Color TEXT_MUTED = new Color(0x888888);
    private static final Color BORDER_LIGHT = new Color(0xD0D7DE);
    private static final Color SEL_BG = new Color(0xE3F2FD);
    private static final Color BTN_HOVER_BG = new Color(0xF3F4F6);
    private static final Color BTN_PRESSED_BG = new Color(0xE5E7EB);
    /** 「导入」高亮主操作色：跟主页面"打包并上传"主操作风格对齐（写死，不跟随主题）。
     *  主操作按钮用法是"边框 + 文字"——不做实心填充，整体仍维持白纸感。 */
    private static final Color PRIMARY_BG = new Color(0x2E7BE0);
    /** 主操作 outline 按钮 hover 时的极浅蓝填充（轻微反馈，不抢主操作蓝的视觉权重） */
    private static final Color PRIMARY_TINT_HOVER = new Color(0xEEF5FE);
    /** 主操作 outline 按钮 pressed 时略加深的浅蓝填充 */
    private static final Color PRIMARY_TINT_PRESSED = new Color(0xD6E7FB);

    /** 顶部行 + 底部行的控件统一外观参数 */
    private static final int BTN_PAD_V = 8;
    private static final int BTN_PAD_H = 18;
    private static final int BTN_RADIUS = 14;
    /** ComboBox 跟按钮等高（动态以按钮 preferred height 为准；该常量是兜底估值） */
    private static final int ROW_HEIGHT = 30;
    /** Action property key：标记 DialogWrapperAction 应当渲染成高亮主操作样式 */
    private static final String STYLE_PROP_KEY = "flux-button-style";
    private static final String STYLE_PRIMARY = "primary";

    private ComboBox<String> templateCombo;
    private JcefEmailEditor editor;
    private JButton restoreBtn;

    public EmailDialog(@Nullable Project project, @NotNull EmailDraftManager manager,
                       @NotNull EmailRuntimeData runtimeData,
                       @NotNull DeployHistoryCache historyCache) {
        super(project, false);
        this.project = project;
        this.manager = manager;
        this.runtimeData = runtimeData;
        this.historyCache = historyCache;
        setTitle("邮件模版");
        setOKButtonText("关闭");
        init();
    }

    /**
     * 重写 init —— super.init() 构建好整棵 dialog 组件树后，递归把
     * rootPane / contentPane / center / south / 所有 panel / button / label 全部强制涂白
     *
     * <p>弹窗"邮件预览"语义要求白底，不能跟随 IDE 主题在 Darcula 下变黑。
     * 不靠 createCenterPanel / createSouthPanel 各画一张圆角白卡——那样两张卡之间
     * 会露出 DialogWrapper 自带的 contentPane 暗色背景（截图最底下那条灰缝就是这个）。
     * 改成直接遍历整棵 Swing 树，把 contentPane 也涂白，没有任何主题色能漏出来。</p>
     *
     * @author xumanyi
     * @date 2026-05-19
     */
    @Override
    protected void init() {
        super.init();
        paintEverythingWhite();
        // 二次涂白：DialogWrapper 在 EDT 后续 invokeLater 里可能再加入 hint label /
        // status bar 之类 lazy 组件，第一次 forceAllWhite 跑完它们还没出现，延迟兜一次。
        SwingUtilities.invokeLater(this::paintEverythingWhite);
    }

    /**
     * Override DialogWrapper 默认 contentPane padding——清成 empty
     *
     * <p>默认实现返回 {@code JBUI.Borders.empty(8, 12)}，contentPane 底部多出 8px inset
     * 不受 createSouthPanel 控制，会让 south 按钮上方 4px / 下方 4+8=12px 形成上下不对称，
     * 按钮看起来"顶在上面"。改成 empty 后，所有上下间距都由 root / south_wrap 自己定，
     * 真正对称。lr 16px 的边距由 root / south_wrap 自己补齐。</p>
     *
     * @author xumanyi
     * @date 2026-05-19
     */
    @Override
    protected javax.swing.border.Border createContentPaneBorder() {
        return JBUI.Borders.empty();
    }

    /**
     * 把 rootPane / contentPane / 整个 Swing 树 + Window 全部强制涂白
     *
     * <p>可重复调用：第一次在 super.init() 立即跑，第二次在 EDT invokeLater 跑，
     * 兜住 lazy 组件。</p>
     *
     * @author xumanyi
     * @date 2026-05-19
     */
    private void paintEverythingWhite() {
        javax.swing.JRootPane rp = getRootPane();
        if (rp == null) return;
        forceAllWhite(rp);
        // 显式再把 contentPane 涂一次：rootPane.setBackground 不会自动 cascade
        java.awt.Container cp = rp.getContentPane();
        if (cp instanceof JComponent) {
            ((JComponent) cp).setOpaque(true);
            cp.setBackground(BG_WHITE);
        }
        java.awt.Window w = getWindow();
        if (w != null) {
            w.setBackground(BG_WHITE);
        }
    }

    /**
     * 递归把组件树全部涂白：
     * <ul>
     *   <li>JButton → {@link #applyFlatStyle}（白底浅灰圆角，hover/pressed 自绘色），不向下递归</li>
     *   <li>JPanel / JRootPane / JLayeredPane / JComponent 容器 → setOpaque(true) + 白底</li>
     *   <li>JLabel → setForeground(TEXT_DARK)，opaque 不变（false）</li>
     * </ul>
     *
     * <p>JComboBox 跳过——已经在 {@link #buildTemplateRow} 设过 {@link WhiteComboBoxUI}，
     * 重复设会丢掉 popup renderer 监听。</p>
     *
     * @author xumanyi
     * @date 2026-05-19
     */
    private static void forceAllWhite(Component c) {
        if (c == null) return;
        if (c instanceof JButton) {
            JButton b = (JButton) c;
            Action a = b.getAction();
            // 标了 primary 的 action button（"导入"） → 高亮主操作样式；其它全部走 flat
            if (a != null && STYLE_PRIMARY.equals(a.getValue(STYLE_PROP_KEY))) {
                applyPrimaryStyle(b);
            } else {
                applyFlatStyle(b);
            }
            return; // 不向下递归按钮内部
        }
        if (c instanceof javax.swing.JComboBox) {
            return; // 已自行处理
        }
        if (c instanceof JComponent) {
            JComponent jc = (JComponent) c;
            if (jc instanceof JLabel) {
                ((JLabel) jc).setForeground(TEXT_DARK);
            } else {
                jc.setOpaque(true);
                jc.setBackground(BG_WHITE);
            }
        }
        if (c instanceof java.awt.Container) {
            for (Component child : ((java.awt.Container) c).getComponents()) {
                forceAllWhite(child);
            }
        }
    }

    /**
     * 把任意 JButton 改造成统一的扁平白底圆角样式
     *
     * <p>顶部按钮（新建 / 恢复默认 / 删除）和 DialogWrapper 底部 action 按钮
     * （导入 / 保存模板 / 复制到剪贴板 / 关闭）都走这条同一路径，
     * 保证两组按钮视觉 100% 一致。</p>
     *
     * <p>用 setUI 替换 BasicButtonUI 而不是 paintComponent override，
     * 是为了能套到 DialogWrapper 自己 new 出来的 JButton 上。</p>
     *
     * @author xumanyi
     * @date 2026-05-19
     */
    private static void applyFlatStyle(JButton btn) {
        btn.setForeground(TEXT_DARK);
        btn.setContentAreaFilled(false);
        btn.setBorderPainted(false);
        btn.setFocusPainted(false);
        btn.setOpaque(false);
        btn.setBorder(JBUI.Borders.empty(BTN_PAD_V, BTN_PAD_H));
        btn.setMargin(JBUI.emptyInsets());
        btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        btn.setUI(new javax.swing.plaf.basic.BasicButtonUI() {
            @Override
            public void paint(Graphics g, javax.swing.JComponent c) {
                JButton b = (JButton) c;
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                        RenderingHints.VALUE_ANTIALIAS_ON);
                Color bg;
                if (b.getModel().isPressed()) bg = BTN_PRESSED_BG;
                else if (b.getModel().isRollover()) bg = BTN_HOVER_BG;
                else bg = BG_WHITE;
                g2.setColor(bg);
                g2.fillRoundRect(0, 0, b.getWidth(), b.getHeight(), BTN_RADIUS, BTN_RADIUS);
                g2.setColor(BORDER_LIGHT);
                g2.drawRoundRect(0, 0, b.getWidth() - 1, b.getHeight() - 1, BTN_RADIUS, BTN_RADIUS);
                g2.dispose();
                super.paint(g, c);
            }
        });
    }

    /**
     * 把 JButton 改造成高亮主操作样式：outline 风格——白底 + 蓝边 + 蓝字
     *
     * <p>视觉层级：跟 flat 同样的圆角尺寸 / 内边距，仅把 1px 浅灰描边换成 1.5px 蓝色描边、
     * 文字色由深灰换成主操作蓝。整体仍保持白纸感，跟 flat 形成"同款外形 / 不同强调"对照。
     * hover / pressed 用极浅蓝填充（{@link #PRIMARY_TINT_HOVER} / {@link #PRIMARY_TINT_PRESSED}）
     * 做反馈，色相跟主操作蓝同族但饱和度很低，不抢主蓝的视觉权重。</p>
     *
     * <p><b>不画实心填充</b>：高亮靠"边框 + 字色"实现，避免实心蓝在白底弹窗里过于扎眼。</p>
     *
     * @author xumanyi
     * @date 2026-05-19
     */
    private static void applyPrimaryStyle(JButton btn) {
        btn.setForeground(PRIMARY_BG);
        btn.setContentAreaFilled(false);
        btn.setBorderPainted(false);
        btn.setFocusPainted(false);
        btn.setOpaque(false);
        btn.setBorder(JBUI.Borders.empty(BTN_PAD_V, BTN_PAD_H));
        btn.setMargin(JBUI.emptyInsets());
        btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        btn.setUI(new javax.swing.plaf.basic.BasicButtonUI() {
            @Override
            public void paint(Graphics g, javax.swing.JComponent c) {
                JButton b = (JButton) c;
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                        RenderingHints.VALUE_ANTIALIAS_ON);
                Color bg;
                if (b.getModel().isPressed()) bg = PRIMARY_TINT_PRESSED;
                else if (b.getModel().isRollover()) bg = PRIMARY_TINT_HOVER;
                else bg = BG_WHITE;
                g2.setColor(bg);
                g2.fillRoundRect(0, 0, b.getWidth(), b.getHeight(), BTN_RADIUS, BTN_RADIUS);
                g2.setColor(PRIMARY_BG);
                g2.setStroke(new java.awt.BasicStroke(1.5f));
                g2.drawRoundRect(0, 0, b.getWidth() - 1, b.getHeight() - 1, BTN_RADIUS, BTN_RADIUS);
                g2.dispose();
                super.paint(g, c);
            }
        });
    }

    /**
     * 给 DialogWrapper 默认底部 button bar 包一层白底 wrap 调 padding 跟按钮间距
     *
     * <p>四处协同把底部按钮"上下严格居中、横向有间距"做到位：</p>
     * <ol>
     *   <li>{@link #createContentPaneBorder} 已清成 empty，contentPane 不再贡献任何上下 inset，
     *       南区上下间距完全由本 wrap 自己决定（默认 8px contentPane bottom 是按钮"顶在上面"的主因）</li>
     *   <li>{@link #clearChildInsets} 递归清掉 super south 子树里所有 JComponent 的 border
     *       （IntelliJ 嵌套层会偷偷加 8~16px inset）</li>
     *   <li>{@link #compactRow} 递归把 FlowLayout / GridLayout 的 vgap 设为 0、hgap 设为 24
     *       （FlowLayout 默认 vgap=5 会偷加 10px 上下内边距）</li>
     *   <li>wrap padding 对称 (top=4, bottom=4)：上下完全等距</li>
     * </ol>
     *
     * <p>最终视觉：按钮自身 ~32px 高 + wrap 上下各 4px = south 区 40px。按钮上方 4px、下方 4px，
     * 严格对称居中；横向 hgap 24 留出充足呼吸距离。</p>
     *
     * @author xumanyi
     * @date 2026-05-19
     */
    @Override
    protected JComponent createSouthPanel() {
        JComponent south = super.createSouthPanel();
        if (south == null) return null;
        south.setBorder(JBUI.Borders.empty());
        clearChildInsets(south);
        compactRow(south, 24);
        JPanel wrap = new JPanel(new BorderLayout());
        wrap.setOpaque(true);
        wrap.setBackground(BG_WHITE);
        wrap.setBorder(JBUI.Borders.empty(4, 16));
        wrap.add(south, BorderLayout.CENTER);
        return wrap;
    }

    /**
     * 递归清掉容器树里所有 JComponent 的 border（仅 super.createSouthPanel 返回的子树用）
     *
     * <p>DialogWrapper 默认 button bar 在内部嵌套了多层 panel，每层都可能 setBorder
     * 加上 8~16px 的 inset。只清掉顶层 south 的 border 不够，得递归到底。</p>
     *
     * <p>跳过 JButton：按钮自己的 border 是 {@link #applyFlatStyle} / {@link #applyPrimaryStyle}
     * 套上去的，不能被覆盖。</p>
     *
     * @author xumanyi
     * @date 2026-05-19
     */
    private static void clearChildInsets(Component c) {
        if (c instanceof JButton) return;
        if (c instanceof JComponent) {
            ((JComponent) c).setBorder(JBUI.Borders.empty());
        }
        if (c instanceof java.awt.Container) {
            for (Component child : ((java.awt.Container) c).getComponents()) {
                clearChildInsets(child);
            }
        }
    }

    /**
     * 递归把组件树里 FlowLayout / GridLayout 的 hgap 设为指定值、vgap 清 0
     *
     * <p>用于 createSouthPanel：DialogWrapper 内部 button container 的具体类型在 IDE 版本
     * 之间可能漂移（有时 FlowLayout、有时 GridLayout），递归遍历兼容两者。</p>
     *
     * <p><b>vgap 必须清 0</b>：FlowLayout 默认 vgap=5，会偷加 5+5=10px 上下内边距——叠加上
     * 我们 wrap 自己的 (4, 4) padding 后，按钮"上方 5+4=9 / 下方 5+4=9" 看似对称，但顶部
     * editor 紧贴 wrap 顶时仍会留出 ~9px 让按钮"顶在上面"。强制 vgap=0 让 wrap padding 成为
     * 唯一的纵向呼吸源，按钮严格上下居中。</p>
     *
     * @author xumanyi
     * @date 2026-05-19
     */
    private static void compactRow(Component c, int hgap) {
        if (c instanceof java.awt.Container) {
            java.awt.Container container = (java.awt.Container) c;
            java.awt.LayoutManager lm = container.getLayout();
            if (lm instanceof FlowLayout) {
                ((FlowLayout) lm).setHgap(hgap);
                ((FlowLayout) lm).setVgap(0);
            } else if (lm instanceof java.awt.GridLayout) {
                ((java.awt.GridLayout) lm).setHgap(hgap);
                ((java.awt.GridLayout) lm).setVgap(0);
            }
            for (Component child : container.getComponents()) {
                compactRow(child, hgap);
            }
        }
    }

    @Override
    protected JComponent createCenterPanel() {
        // 整片白底矩形：不再画圆角白卡（圆角缺口会让 contentPane 的主题色漏出来）。
        // 整个 dialog 的白色感由 init() 里 forceAllWhite(rootPane) 统一兜底。
        JPanel root = new JPanel(new BorderLayout(0, 10));
        root.setOpaque(true);
        root.setBackground(BG_WHITE);
        // 4/8 padding：editor 区域贴近 dialog 边缘，减少"用户拖动选择时鼠标移出 editor"
        // 的概率。注：JCEF 嵌入 Swing 时鼠标 grab 不完全生效，鼠标拖到 dialog 外仍可能
        // 中断选择——这是 IntelliJ JCEF 的已知限制，padding 缩小只能 mitigate
        // 自己接管 contentPane 留下的 top / lr margin（createContentPaneBorder 被清成 empty）：
        // top 8 给标题栏到模板行一段呼吸距离，lr 16 给整体内容跟 dialog 左右框留出对称留白，
        // bottom 0 把"编辑器 → 按钮"那段间距完全交给 south_wrap 控制。
        root.setBorder(JBUI.Borders.empty(8, 16, 0, 16));
        root.setPreferredSize(new Dimension(960, 720));

        root.add(buildHeader(), BorderLayout.NORTH);
        root.add(buildEditorPanel(), BorderLayout.CENTER);

        // JCEF 嵌入 Swing 时鼠标拖出 dialog 边缘后 native CEF 收不到 mouseDragged，
        // 拖动选择中断。装一个 AWTEventListener + MouseInfo poll，把全局鼠标位置
        // forward 给 browser component 让 CEF 持续收到 drag 事件。
        installMouseDragForwarder();

        // EDT 异步：弹窗 dispose 链建立后再 snapshot + 注入模板
        SwingUtilities.invokeLater(this::reloadEditorWithFreshSnapshot);
        return root;
    }

    /** JCEF 鼠标拖动 forward 用的"正在 dragging"状态 */
    private boolean isDraggingFromEditor;
    /** 拖动期间用 Timer 持续 poll 鼠标位置并 forward，~60fps */
    private javax.swing.Timer dragPoller;
    /** mousedown 时记录的 button mask，用于 forward MouseEvent 时填正确 modifiers */
    private int dragButtonModifier;

    /**
     * 装上"鼠标拖出 JCEF 边界后仍持续 forward"的全局鼠标转发器
     *
     * <p>JCEF 嵌入 Swing 的 native bridge 在鼠标 native 离开 browser component 后停止
     * 向 CEF 转发 mouseMoved，表现为"拖动选择文本，鼠标移到 dialog 外文本选择中断"。</p>
     *
     * <p><b>方案</b>：</p>
     * <ol>
     *   <li>用 {@code Toolkit.addAWTEventListener} 全局监听 MOUSE_PRESSED / MOUSE_RELEASED</li>
     *   <li>press 落在 browser component（或其子树）时进入 {@code isDraggingFromEditor} 态，
     *       启动 60fps Timer poll</li>
     *   <li>Timer 用 {@link MouseInfo#getPointerInfo()}（OS 级 API，无视 Swing window 边界）
     *       拿到屏幕鼠标位置，转换成 component 局部坐标，{@code dispatchEvent} 给 browser
     *       component → JCEF wrapper 把 MouseEvent forward 给 native CEF → 选择延续</li>
     *   <li>全局 MOUSE_RELEASED 时停止 Timer + forward 最后一次 mouseup 给 browser</li>
     * </ol>
     *
     * <p><b>注意</b>：在 macOS 上 JDialog 鼠标 grab 会让 mouseReleased 在 dialog 外也能被
     * AWTEventListener 收到（前提是用户从 dialog 内开始 press）。如果 grab 失败导致
     * release 没收到，Timer 会一直跑——通过 {@link Disposer} 在 dialog dispose 时强制 stop。</p>
     *
     * @author xumanyi
     * @date 2026-05-19
     */
    private void installMouseDragForwarder() {
        final Component browserComponent = editor.getComponent();
        if (browserComponent == null) return;

        AWTEventListener listener = event -> {
            if (!(event instanceof MouseEvent)) return;
            MouseEvent me = (MouseEvent) event;
            int id = me.getID();
            if (id == MouseEvent.MOUSE_PRESSED) {
                Object src = me.getSource();
                if (src instanceof Component
                        && (src == browserComponent
                            || SwingUtilities.isDescendingFrom((Component) src, browserComponent))) {
                    isDraggingFromEditor = true;
                    dragButtonModifier = MouseEvent.BUTTON1_DOWN_MASK;
                    startDragPoller(browserComponent);
                }
            } else if (id == MouseEvent.MOUSE_RELEASED) {
                if (isDraggingFromEditor) {
                    finishDragging(browserComponent);
                }
            }
        };
        Toolkit.getDefaultToolkit().addAWTEventListener(listener,
                AWTEvent.MOUSE_EVENT_MASK);

        Disposer.register(getDisposable(), () -> {
            Toolkit.getDefaultToolkit().removeAWTEventListener(listener);
            stopDragPoller();
        });
    }

    private void startDragPoller(Component browserComponent) {
        stopDragPoller();
        dragPoller = new javax.swing.Timer(16, e -> {
            if (!isDraggingFromEditor) return;
            try {
                Point screenP = MouseInfo.getPointerInfo().getLocation();
                Point compP = new Point(screenP);
                SwingUtilities.convertPointFromScreen(compP, browserComponent);
                MouseEvent forward = new MouseEvent(browserComponent,
                        MouseEvent.MOUSE_DRAGGED,
                        System.currentTimeMillis(),
                        dragButtonModifier,
                        compP.x, compP.y, 0, false, MouseEvent.BUTTON1);
                browserComponent.dispatchEvent(forward);
            } catch (Throwable ex) {
                // MouseInfo / convertPointFromScreen 在 headless / display 关闭时可能 throw
            }
        });
        dragPoller.setRepeats(true);
        dragPoller.start();
    }

    private void stopDragPoller() {
        if (dragPoller != null) {
            dragPoller.stop();
            dragPoller = null;
        }
    }

    private void finishDragging(Component browserComponent) {
        isDraggingFromEditor = false;
        stopDragPoller();
        try {
            Point screenP = MouseInfo.getPointerInfo().getLocation();
            Point compP = new Point(screenP);
            SwingUtilities.convertPointFromScreen(compP, browserComponent);
            MouseEvent forward = new MouseEvent(browserComponent,
                    MouseEvent.MOUSE_RELEASED,
                    System.currentTimeMillis(),
                    0,
                    compP.x, compP.y, 1, false, MouseEvent.BUTTON1);
            browserComponent.dispatchEvent(forward);
        } catch (Throwable ex) {
            // ignore
        }
    }

    /**
     * 顶部 header：macOS 下 = "邮件模版"白底标题条 + 模板行；其它平台 = 仅模板行
     *
     * <p>macOS 下系统 NSWindow 标题栏文字色由 OS 渲染，且 IntelliJ DialogWrapper
     * 没有暴露干预口子（apple.awt.windowAppearance / transparentTitleBar / fullWindowContent
     * 都被 dialog peer 吞掉），dark mode 下"邮件模版"标题文字跟黑底对比度不足看不清。
     * 在 dialog 内容紧贴系统标题栏下方加一行白底深色加粗标题做一次冗余，无论 dark/light
     * 都能清晰看到弹窗用途。非 macOS 平台标题栏跟随 IDE 主题正常显示，不需要这层冗余。</p>
     *
     * @return mac 上为"标题条 + 模板行"组合面板，其它平台直接返回模板行
     * @author xumanyi
     * @date 2026-05-19
     */
    private JComponent buildHeader() {
        JPanel templateRow = buildTemplateRow();
        if (!SystemInfo.isMac) {
            return templateRow;
        }
        JLabel title = new JLabel("邮件模版");
        title.setForeground(TEXT_DARK);
        title.setFont(title.getFont().deriveFont(Font.BOLD, 14f));
        JPanel titleRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        titleRow.setOpaque(false);
        titleRow.add(title);
        JPanel header = new JPanel(new BorderLayout(0, 6));
        header.setOpaque(false);
        header.add(titleRow, BorderLayout.NORTH);
        header.add(templateRow, BorderLayout.CENTER);
        return header;
    }

    private JPanel buildTemplateRow() {
        templateCombo = new ComboBox<>();
        reloadTemplateNames();
        templateCombo.setSelectedItem(displayLabelOf(manager.getSelectedTemplateName()));
        templateCombo.addActionListener(e -> {
            Object sel = templateCombo.getSelectedItem();
            if (sel instanceof String) {
                String name = nameOf((String) sel);
                if (!name.equals(manager.getSelectedTemplateName())) {
                    manager.setSelectedTemplateName(name);
                    reloadEditorWithFreshSnapshot();
                }
            }
        });
        // 下拉项渲染器：非默认模板右侧画一个 × 删除按钮区域
        templateCombo.setRenderer(new TemplateListCellRenderer());
        // 给 popup JList 装点击监听：在 × 区域内点击 → 快捷删除
        installQuickDeleteListener();
        // 强制白底深字 + 自定义 UI（暗主题下 Darcula ComboBox 的右侧 arrow 按钮区
        // 是深色，setBackground 也覆盖不了；切到 BasicComboBoxUI + 自绘 arrow，全白）
        // setUI 必须放在 setBackground 之前——setUI 触发 propertyChange 会重置 background
        templateCombo.setUI(new WhiteComboBoxUI());
        templateCombo.setBackground(BG_WHITE);
        templateCombo.setForeground(TEXT_DARK);
        // 复合 border：外层 RoundedBorder + 内层 4px 一刀切把 ComboBox 内容（renderer +
        // arrow button）整体推到圆弧内侧，避免它们的 rectangular bounds + 自绘白底"啃掉"
        // 四个拐角的曲线像素。
        //
        // 几何要求：arrow 顶点 (w-I_right-1, 1+I_top) 要满足
        //   (13-I_right)² + (I_top-13)² ≤ R² = 196
        // 当 I_top = I_right = 4 时距离 √162 ≈ 12.7 < 14 ✓，四角全部覆盖。
        // 内部可用区 32-2-8=22px 高、240-2-8=230px 宽，文字 + arrow 仍宽松。
        templateCombo.setBorder(javax.swing.BorderFactory.createCompoundBorder(
                new RoundedBorder(BTN_RADIUS, BORDER_LIGHT),
                JBUI.Borders.empty(4)));

        JButton newBtn = flatButton("新建");
        newBtn.setToolTipText("新建模板");
        newBtn.addActionListener(this::onCreateNewTemplate);
        restoreBtn = flatButton("恢复默认");
        restoreBtn.setToolTipText("重置为默认模板内容");
        restoreBtn.addActionListener(this::onRestoreDefault);
        JButton delBtn = flatButton("删除");
        delBtn.addActionListener(this::onDeleteTemplate);

        // ComboBox 高度对齐按钮高度（动态以实际按钮 preferred height 为准；
        // 退一档兜底用 ROW_HEIGHT 常量）
        int rowH = Math.max(ROW_HEIGHT, newBtn.getPreferredSize().height);
        templateCombo.setPreferredSize(new Dimension(240, rowH));
        templateCombo.setMaximumSize(new Dimension(240, rowH));
        templateCombo.setMinimumSize(new Dimension(240, rowH));

        JBLabel modelLabel = new JBLabel("模板");
        modelLabel.setForeground(TEXT_DARK);

        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        // 行容器透明，让根面板的白底透出
        row.setOpaque(false);
        row.add(modelLabel);
        row.add(templateCombo);
        row.add(Box.createHorizontalStrut(8));
        row.add(newBtn);
        row.add(restoreBtn);
        row.add(delBtn);
        return row;
    }

    /**
     * 「恢复默认」回调：把当前选中模板的内容覆盖为内置默认模板
     *
     * <p>任何模板都能恢复——比如用户在"帝迈"模板上改坏了，选中"帝迈"再点这里就重置回出厂内容。
     * 不做内容备份，直接覆盖；弹窗里的编辑器同时刷新到默认内容。</p>
     *
     * @param e action 事件
     * @author xumanyi
     * @date 2026-05-18
     */
    private void onRestoreDefault(ActionEvent e) {
        Object sel = templateCombo.getSelectedItem();
        if (!(sel instanceof String)) return;
        String name = nameOf((String) sel);
        boolean isDefault = EmailTemplateStore.DEFAULT_TEMPLATE_NAME.equals(name);
        String displayName = displayLabelOf(name);
        String confirmMsg = isDefault
                ? "确认把「默认模板」恢复为插件出厂内置内容？\n当前内容会被覆盖，无法撤销。"
                : "确认把模板「" + displayName + "」恢复为<当前默认模板>的内容？\n"
                        + "（即用默认模板的当前内容覆盖本模板，不是插件出厂内容。）\n"
                        + "当前内容会被覆盖，无法撤销。";
        int confirm = Messages.showYesNoDialog(project,
                confirmMsg, "恢复默认", Messages.getWarningIcon());
        if (confirm != Messages.YES) return;
        try {
            manager.getStore().restoreToBuiltinDefault(name);
            reloadEditorWithFreshSnapshot();
            flashButtonFeedback(restoreBtn, "恢复默认", "✓ 已恢复");
        } catch (java.io.IOException ex) {
            Messages.showErrorDialog(project,
                    "写入模板文件失败: " + ex.getMessage(), "恢复失败");
        } catch (IllegalArgumentException ex) {
            Messages.showErrorDialog(project, ex.getMessage(), "恢复失败");
        }
    }

    private JPanel buildEditorPanel() {
        editor = new JcefEmailEditor(getDisposable());
        JPanel wrap = new JPanel(new BorderLayout());
        wrap.setBackground(BG_WHITE);
        wrap.add(editor.getComponent(), BorderLayout.CENTER);
        // 复合 border：外层圆角描边（跟 ComboBox / 按钮视觉统一）+ 内层 5px 白色 padding。
        //
        // 必须加内层 padding 的原因：JCEF 是 native 渲染（绕过 Swing 的 clip 管线），
        // 它的 rectangular bounds 会把 RoundedBorder 画的曲线像素"啃掉"，表现为四个
        // 拐角处圆弧缺失。给 JCEF 5px 白色内边距把它收在曲线内侧——R=14 时几何上要求
        // I ≥ R×(1 - 1/√2) ≈ 4.1，取 5 留 1px 安全余量——曲线就完整可见。
        wrap.setBorder(javax.swing.BorderFactory.createCompoundBorder(
                new RoundedBorder(BTN_RADIUS, BORDER_LIGHT),
                JBUI.Borders.empty(5)));
        return wrap;
    }

    /**
     * 用当前选中模板 + 空字段快照重新加载编辑器内容
     *
     * <p><b>所有字段都不预填充</b>：任务 / 客服 / 更新包 / 备份包 / 项目 在编辑器里
     * 一律以空 chip（{@code ${字段名}} 斜体占位）形态显示，等用户点「导入」按钮时
     * 才一次性收集主面板 + 部署历史的最新值，统一刷到对应 chip。</p>
     *
     * <p>这样做的好处：</p>
     * <ul>
     *   <li>用户没点导入就看到一个干净起点，不会被上次模板留下的"借尸还魂"值污染</li>
     *   <li>"导入"动作语义统一——任务 / 客服 跟更新包 / 备份包 / 项目 走同一条路径</li>
     * </ul>
     *
     * <p>用 import 按钮自己负责往 snapshot 写新值，那条路径不走本方法。</p>
     *
     * @author xumanyi
     * @date 2026-05-19
     */
    private void reloadEditorWithFreshSnapshot() {
        fieldValuesSnapshot.clear();
        String template = manager.getStore().loadOrDefault(manager.getSelectedTemplateName());
        editor.setTemplate(template, fieldValuesSnapshot);
    }

    // ==================== 模板增删按钮 ====================

    private void onCreateNewTemplate(ActionEvent e) {
        String name = Messages.showInputDialog(project,
                "新模板名称（中文 / 字母 / 数字 / 下划线 / 连字符，不含点和空白）：",
                "新建邮件模板", null);
        if (name == null || name.isBlank()) return;
        name = name.trim();
        if (DEFAULT_DISPLAY_LABEL.equals(name)) {
            Messages.showErrorDialog(project,
                    "名称「" + DEFAULT_DISPLAY_LABEL + "」是默认模板的保留显示名，请换一个。",
                    "新建失败");
            return;
        }
        try {
            // 以「默认模板」内容为初值——而不是当前选中模板。
            // 当前选中的可能是用户改坏了的派生模板，新建若沿用它会把脏数据传染下去；
            // 用默认模板内容做起点，新建出来的总是干净的。
            String initial = manager.getStore().loadOrDefault(
                    EmailTemplateStore.DEFAULT_TEMPLATE_NAME);
            manager.getStore().createNew(name, initial);
            reloadTemplateNames();
            templateCombo.setSelectedItem(displayLabelOf(name));
            manager.setSelectedTemplateName(name);
            reloadEditorWithFreshSnapshot();
        } catch (IllegalArgumentException | IllegalStateException ex) {
            Messages.showErrorDialog(project, ex.getMessage(), "新建失败");
        } catch (java.io.IOException ex) {
            Messages.showErrorDialog(project,
                    "写入模板文件失败: " + ex.getMessage(), "新建失败");
        }
    }

    private void onDeleteTemplate(ActionEvent e) {
        Object sel = templateCombo.getSelectedItem();
        if (!(sel instanceof String)) return;
        String name = nameOf((String) sel);
        if (EmailTemplateStore.DEFAULT_TEMPLATE_NAME.equals(name)) {
            Messages.showWarningDialog(project, "默认模板不可删除。", "无法删除");
            return;
        }
        deleteTemplate(name);
    }

    /**
     * 删除指定模板的统一入口（顶栏「删除」按钮和下拉行内 × 都走这里）
     *
     * @param name 模板名（已经从 display label 转换过）
     */
    private void deleteTemplate(String name) {
        String displayName = displayLabelOf(name);
        int confirm = Messages.showYesNoDialog(project,
                "确认删除模板「" + displayName + "」？删除后无法恢复。",
                "确认删除", Messages.getWarningIcon());
        if (confirm != Messages.YES) return;
        try {
            manager.getStore().delete(name);
            reloadTemplateNames();
            templateCombo.setSelectedItem(displayLabelOf(EmailTemplateStore.DEFAULT_TEMPLATE_NAME));
            manager.setSelectedTemplateName(EmailTemplateStore.DEFAULT_TEMPLATE_NAME);
            reloadEditorWithFreshSnapshot();
        } catch (Exception ex) {
            Messages.showErrorDialog(project,
                    "删除模板失败: " + ex.getMessage(), "删除失败");
        }
    }

    // ==================== 下拉行内快捷删除 ====================

    /** 右侧 × 删除按钮区域宽度（像素） */
    private static final int DELETE_ZONE_WIDTH = 28;

    /**
     * 下拉项渲染器：左侧模板显示名 + 右侧 × 图标（仅非默认模板显示）
     */
    private class TemplateListCellRenderer extends JPanel implements ListCellRenderer<String> {
        private final JBLabel nameLabel = new JBLabel();
        private final JBLabel deleteLabel = new JBLabel("×");

        TemplateListCellRenderer() {
            super(new BorderLayout());
            nameLabel.setBorder(JBUI.Borders.empty(4, 8));
            deleteLabel.setBorder(JBUI.Borders.empty(4, 8));
            deleteLabel.setFont(deleteLabel.getFont().deriveFont(Font.BOLD, 14f));
            deleteLabel.setHorizontalAlignment(JBLabel.CENTER);
            deleteLabel.setPreferredSize(new Dimension(DELETE_ZONE_WIDTH,
                    deleteLabel.getPreferredSize().height));
            add(nameLabel, BorderLayout.CENTER);
            add(deleteLabel, BorderLayout.EAST);
            setOpaque(true);
        }

        @Override
        public Component getListCellRendererComponent(JList<? extends String> list,
                                                      String value, int index,
                                                      boolean isSelected, boolean cellHasFocus) {
            String displayName = value == null ? "" : value;
            nameLabel.setText(displayName);
            boolean isDefault = EmailTemplateStore.DEFAULT_TEMPLATE_NAME.equals(nameOf(displayName));
            // × 仅在 popup 下拉列表项里显示（index >= 0）；当 ComboBox 用 renderer 显示
            // "当前值"时 index = -1，那是已选中状态，× 不该出现——否则用户会看到 ComboBox
            // 框里跟着一个"假"删除按钮，跟"打开下拉再点 ×"的删除入口语义混淆。
            boolean inPopupList = index >= 0;
            deleteLabel.setVisible(inPopupList && !isDefault);

            // 强制白底深字（不跟随 IDE 主题）—— 跟弹窗整体白底统一。
            // 关键：只对 popup list 里真正的选中行 (index >= 0) 涂蓝；
            // 当 ComboBox 用 renderer 显示"当前值"时 index = -1，isSelected 也 = true
            // （BasicComboBoxUI focused 状态会这样调），不能涂蓝，否则 combo 显示框一片蓝。
            boolean realPopupSelection = isSelected && index >= 0;
            if (realPopupSelection) {
                setBackground(SEL_BG);
                nameLabel.setForeground(TEXT_DARK);
                deleteLabel.setForeground(TEXT_DARK);
            } else {
                setBackground(BG_WHITE);
                nameLabel.setForeground(TEXT_DARK);
                deleteLabel.setForeground(TEXT_MUTED);
            }
            return this;
        }
    }

    /**
     * 给 ComboBox 的 popup JList 装快捷删除监听
     *
     * <p>popup 是延迟构造的（首次展开下拉才创建），所以只能在 popupMenuWillBecomeVisible
     * 时拿到 JList 并加 listener。用 client property 标记避免重复加。</p>
     */
    private void installQuickDeleteListener() {
        templateCombo.addPopupMenuListener(new PopupMenuListener() {
            @Override
            public void popupMenuWillBecomeVisible(PopupMenuEvent e) {
                Object accessibleChild = templateCombo.getUI()
                        .getAccessibleChild(templateCombo, 0);
                if (!(accessibleChild instanceof BasicComboPopup)) return;
                JList<?> list = ((BasicComboPopup) accessibleChild).getList();
                if (list.getClientProperty("flux-quick-delete-attached") != null) return;
                list.putClientProperty("flux-quick-delete-attached", Boolean.TRUE);

                // 鼠标在右侧 × 区域时改成手型，提示可点击
                list.addMouseMotionListener(new MouseMotionAdapter() {
                    @Override
                    public void mouseMoved(MouseEvent ev) {
                        int idx = list.locationToIndex(ev.getPoint());
                        boolean inDeleteZone = idx >= 0 && isInDeleteZone(list, idx, ev) && !isDefaultAtIndex(list, idx);
                        list.setCursor(Cursor.getPredefinedCursor(
                                inDeleteZone ? Cursor.HAND_CURSOR : Cursor.DEFAULT_CURSOR));
                    }
                });

                // 点击 × 区域 → 不切换选中，触发快捷删除
                list.addMouseListener(new MouseAdapter() {
                    @Override
                    public void mousePressed(MouseEvent ev) {
                        int idx = list.locationToIndex(ev.getPoint());
                        if (idx < 0) return;
                        if (isDefaultAtIndex(list, idx)) return;
                        if (!isInDeleteZone(list, idx, ev)) return;
                        ev.consume();
                        String displayName = String.valueOf(list.getModel().getElementAt(idx));
                        String name = nameOf(displayName);
                        templateCombo.hidePopup();
                        // EDT 异步弹确认框，避免在 popup 隐藏过程中弹对话框造成焦点错乱
                        SwingUtilities.invokeLater(() -> deleteTemplate(name));
                    }
                });
            }

            @Override public void popupMenuWillBecomeInvisible(PopupMenuEvent e) {}
            @Override public void popupMenuCanceled(PopupMenuEvent e) {}
        });
    }

    /**
     * 判定点击点是否落在某行的"右侧 × 删除区"
     */
    private static boolean isInDeleteZone(JList<?> list, int index, MouseEvent ev) {
        Rectangle bounds = list.getCellBounds(index, index);
        if (bounds == null) return false;
        return ev.getX() >= bounds.x + bounds.width - DELETE_ZONE_WIDTH;
    }

    /**
     * 判定 list 第 index 行是不是"默认模板"（不显示 × 按钮）
     */
    private static boolean isDefaultAtIndex(JList<?> list, int index) {
        Object item = list.getModel().getElementAt(index);
        return item != null && EmailTemplateStore.DEFAULT_TEMPLATE_NAME.equals(
                nameOf(String.valueOf(item)));
    }

    private void reloadTemplateNames() {
        try {
            List<String> names = manager.getStore().listNames();
            templateCombo.removeAllItems();
            for (String n : names) templateCombo.addItem(displayLabelOf(n));
        } catch (Exception ex) {
            templateCombo.removeAllItems();
            templateCombo.addItem(displayLabelOf(EmailTemplateStore.DEFAULT_TEMPLATE_NAME));
        }
    }

    // ==================== 底部按钮 ====================

    @NotNull
    @Override
    protected Action[] createActions() {
        // 注意：所有 action 都不能标 DEFAULT_ACTION = TRUE。
        // 标了之后该按钮会变成 dialog 的"回车默认按钮"——
        // Quill 编辑器里按回车换行时事件会冒泡到 Swing dialog 触发默认按钮，
        // 表现为"回车没换行反而点了导入"。
        Action okAction = getOKAction();
        okAction.putValue(DEFAULT_ACTION, null);
        // 按钮顺序：[导入(primary)] [复制到剪贴板(primary)] [保存模板] [关闭]
        // 主路径"导入数据 → 复制到剪贴板"两步连排在左侧并高亮；保存模板是分支操作放右；关闭最末
        return new Action[]{
                new ImportFromHistoryAction(),
                new CopyToClipboardAction(),
                new SaveTemplateAction(),
                okAction,
        };
    }

    /**
     * 「导入」action —— 把当前可用的字段值一次性写入字段快照 + 实时刷新对应 chip。
     *
     * <p>导入内容（按"能拿到的就拿"逻辑，缺哪部分就跳过哪部分）：</p>
     * <ul>
     *   <li>{@code 任务} / {@code 客服}：从主面板输入框
     *       （{@link EmailRuntimeData#collectFieldValues()}）取，永远参与导入</li>
     *   <li>{@code 更新包} / {@code 备份包} / {@code 项目}：从
     *       {@link DeployHistoryCache} 拉当前项目目录的部署历史；只有部署过才会有，
     *       没部署过这三个 chip 保持占位符不动</li>
     * </ul>
     *
     * <p>主面板任务 / 客服全为空 且 没有部署历史，才弹"暂无可导入内容"提示。
     * 成功不弹窗，按钮文字闪 "✓ 已导入" 1.5 秒。</p>
     */
    private class ImportFromHistoryAction extends DialogWrapperAction {
        private static final String LABEL_IDLE = "导入";
        private static final String LABEL_DONE = "✓ 已导入";

        protected ImportFromHistoryAction() {
            super(LABEL_IDLE);
            putValue(SHORT_DESCRIPTION,
                    "把主面板的任务 / 客服 + 「重置 / IDE 重启」之后部署记录里的"
                            + "更新包 / 备份目录 / 项目一次性导入到邮件模板");
            // 标记为高亮主操作：forceAllWhite 会识别此 key 套用 applyPrimaryStyle
            putValue(STYLE_PROP_KEY, STYLE_PRIMARY);
        }

        @Override
        protected void doAction(ActionEvent e) {
            // 主面板字段：任务 / 客服（永远可拿，空就是空字符串）
            Map<String, String> mainFields = runtimeData.collectFieldValues();
            String taskValue = mainFields == null ? "" : mainFields.getOrDefault("任务", "");
            String customerValue = mainFields == null ? "" : mainFields.getOrDefault("客服", "");
            boolean hasMainData = !taskValue.isBlank() || !customerValue.isBlank();

            // 部署历史字段：项目 / 更新包 / 备份包（只有部署过才会有）
            String projectDir = runtimeData.getCurrentProjectDir();
            List<String> pkgs = List.of();
            List<String> backupDirs = List.of();
            String projectName = "";
            boolean hasDeployData = false;
            if (projectDir != null && !projectDir.isBlank()) {
                pkgs = historyCache.collectPackagePathsFor(projectDir);
                backupDirs = historyCache.collectBackupDirsFor(projectDir);
                if (!pkgs.isEmpty() || !backupDirs.isEmpty()) {
                    projectName = extractProjectName(projectDir);
                    hasDeployData = true;
                }
            }

            if (!hasMainData && !hasDeployData) {
                Messages.showInfoMessage(project,
                        "暂无可导入内容：请在主面板填写任务 / 客服，或先打包上传以生成更新包。",
                        "导入");
                return;
            }

            // 任务 / 客服 永远参与导入；项目 / 更新包 / 备份包 只在有部署数据时才覆盖——
            // 否则会把"上次导入留下的值"或"用户在编辑器里手填的值"清掉
            Map<String, String> updates = new LinkedHashMap<>();
            updates.put("任务", taskValue);
            updates.put("客服", customerValue);
            if (hasDeployData) {
                updates.put("更新包", String.join(PATH_SEPARATOR, pkgs));
                updates.put("备份包", String.join(PATH_SEPARATOR, backupDirs));
                updates.put("项目", projectName);
            }
            fieldValuesSnapshot.putAll(updates);
            editor.updateChips(updates);
            flashActionFeedback(this, LABEL_IDLE, LABEL_DONE);
        }
    }

    /**
     * 圆角描边 Border：跟按钮的 8px 圆角对齐，给 ComboBox / editor wrap 使用
     *
     * <p>用抗锯齿 g2.drawRoundRect 画 1px 浅灰描边，比 BorderFactory.createLineBorder
     * 的 rounded 模式更平滑（后者像素化明显）。</p>
     *
     * @author xumanyi
     * @date 2026-05-19
     */
    private static final class RoundedBorder extends javax.swing.border.AbstractBorder {
        private final int radius;
        private final Color color;

        RoundedBorder(int radius, Color color) {
            this.radius = radius;
            this.color = color;
        }

        @Override
        public void paintBorder(Component c, Graphics g, int x, int y, int w, int h) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                        RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(color);
                g2.drawRoundRect(x, y, w - 1, h - 1, radius, radius);
            } finally {
                g2.dispose();
            }
        }

        @Override
        public java.awt.Insets getBorderInsets(Component c) {
            return new java.awt.Insets(1, 1, 1, 1);
        }
    }

    /**
     * 白底 ComboBox UI —— BasicComboBoxUI + 自绘 arrow，强制全白外观
     */
    private static final class WhiteComboBoxUI extends BasicComboBoxUI {
        /**
         * 把 BasicComboBoxUI 内部 listBox 的 selection 色也强制成白底
         *
         * <p><b>必要性</b>：BasicComboBoxUI.paintCurrentValue 在 ComboBox 获得焦点
         * （且 popup 未展开）时，会用 {@code listBox.getSelectionBackground()} 覆盖
         * renderer component 的 background。Darcula 主题下这是深蓝色，会让
         * ComboBox 显示框整片变蓝（即"默认模板"那块字段被蓝底吞掉）。</p>
         *
         * <p>把 listBox 的 selection 色也调白，paintCurrentValue 拿到的就是白色。
         * popup 下拉时"真正选中行"的高亮（SEL_BG 浅蓝）由
         * {@link TemplateListCellRenderer} 自己控制，不受影响。</p>
         *
         * @author xumanyi
         * @date 2026-05-19
         */
        @Override
        public void installUI(JComponent c) {
            super.installUI(c);
            if (listBox != null) {
                listBox.setSelectionBackground(BG_WHITE);
                listBox.setSelectionForeground(TEXT_DARK);
            }
        }

        @Override
        public void paintCurrentValueBackground(Graphics g, Rectangle bounds, boolean hasFocus) {
            g.setColor(BG_WHITE);
            g.fillRect(bounds.x, bounds.y, bounds.width, bounds.height);
        }

        @Override
        protected JButton createArrowButton() {
            JButton btn = new JButton() {
                @Override
                protected void paintComponent(Graphics g) {
                    Graphics2D g2 = (Graphics2D) g.create();
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                            RenderingHints.VALUE_ANTIALIAS_ON);
                    // 白底
                    g2.setColor(BG_WHITE);
                    g2.fillRect(0, 0, getWidth(), getHeight());
                    // 向下三角形 arrow
                    g2.setColor(TEXT_DARK);
                    int cx = getWidth() / 2;
                    int cy = getHeight() / 2;
                    int[] xs = {cx - 4, cx + 4, cx};
                    int[] ys = {cy - 2, cy - 2, cy + 3};
                    g2.fillPolygon(xs, ys, 3);
                    g2.dispose();
                }
            };
            btn.setBorder(javax.swing.BorderFactory.createEmptyBorder());
            btn.setOpaque(false);
            btn.setContentAreaFilled(false);
            btn.setFocusable(false);
            return btn;
        }
    }

    /**
     * 工厂方法：扁平浅色 JButton —— 白底 + 浅灰圆角边框 + hover/pressed 自绘色
     *
     * <p>仅为 buildTemplateRow 创建顶部三按钮（新建 / 恢复默认 / 删除）使用。
     * DialogWrapper 底部 action 按钮由 {@link #init} 阶段 {@link #forceAllWhite}
     * 找到后调 {@link #applyFlatStyle} 改造，两组按钮走完全同一条样式路径，保证视觉一致。</p>
     *
     * @param text 按钮文字
     * @return 扁平按钮
     * @author xumanyi
     * @date 2026-05-19
     */
    private static JButton flatButton(String text) {
        JButton btn = new JButton(text);
        applyFlatStyle(btn);
        return btn;
    }

    /**
     * 让 DialogWrapperAction 的按钮文字短暂闪一段反馈文字 1.5 秒
     *
     * @param action     待闪的 action
     * @param idleLabel  闪完恢复的常规文字
     * @param doneLabel  闪期间显示的文字（如 "✓ 已导入"）
     * @author xumanyi
     * @date 2026-05-19
     */
    private static void flashActionFeedback(Action action, String idleLabel, String doneLabel) {
        action.putValue(Action.NAME, doneLabel);
        Timer t = new Timer(1500, evt -> action.putValue(Action.NAME, idleLabel));
        t.setRepeats(false);
        t.start();
    }

    /**
     * 让普通 JButton 短暂闪反馈文字 1.5 秒
     *
     * @param btn        待闪的 button
     * @param idleLabel  闪完恢复的常规文字
     * @param doneLabel  闪期间显示的文字（如 "✓ 已恢复"）
     * @author xumanyi
     * @date 2026-05-19
     */
    private static void flashButtonFeedback(JButton btn, String idleLabel, String doneLabel) {
        btn.setText(doneLabel);
        Timer t = new Timer(1500, evt -> btn.setText(idleLabel));
        t.setRepeats(false);
        t.start();
    }

    /**
     * 从 FTP 项目目录路径里抽出项目名（最后一段）
     *
     * <p>典型 FTP 项目目录如 {@code /开发/1测试项目/}，返回 {@code 1测试项目}。
     * 路径为空 / null 时返回空字符串。</p>
     *
     * @param projectDir FTP 项目目录
     * @return 项目名
     * @author xumanyi
     * @date 2026-05-19
     */
    private static String extractProjectName(String projectDir) {
        if (projectDir == null || projectDir.isBlank()) return "";
        String trimmed = projectDir.replaceAll("/+$", "");
        int lastSlash = trimmed.lastIndexOf('/');
        return lastSlash >= 0 ? trimmed.substring(lastSlash + 1) : trimmed;
    }

    /**
     * 「保存模板」action —— 把当前编辑器内容（chip 还原成 {@code ${字段名}}）写入当前选中模板文件
     *
     * <p><b>必须走异步</b>：JCEF JS 桥的 handler 在某些 IDE 版本会被调度到 EDT 队列，
     * 如果这里同步 {@code future.get()} 阻塞 EDT，handler 永远拿不到 dispatch → 必然超时 →
     * fallback 拿到的是"打开弹窗时的初始模板内容"，把用户的编辑全部丢掉，看起来就是
     * "保存了但没生效"。改成 {@code whenComplete + invokeLater}：EDT 不阻塞，handler 能正常 fire。</p>
     */
    private class SaveTemplateAction extends DialogWrapperAction {
        private static final String LABEL_IDLE = "保存";
        private static final String LABEL_DONE = "✓ 已保存";

        protected SaveTemplateAction() {
            super(LABEL_IDLE);
            putValue(SHORT_DESCRIPTION,
                    "把当前编辑器内容（占位符还原成 ${字段名}）写入当前选中的模板");
        }

        @Override
        protected void doAction(ActionEvent e) {
            String name = manager.getSelectedTemplateName();
            if (name == null || name.isBlank()) {
                Messages.showWarningDialog(project,
                        "未选中模板，无法保存。", "保存");
                return;
            }
            editor.requestTemplateSource().whenComplete((templateSource, ex) ->
                    SwingUtilities.invokeLater(() -> writeTemplate(name, templateSource, ex)));
        }

        private void writeTemplate(String name, String templateSource, Throwable ex) {
            if (ex != null) {
                Messages.showErrorDialog(project,
                        "取编辑器内容失败: " + ex.getMessage(), "保存失败");
                return;
            }
            try {
                manager.getStore().save(name, templateSource == null ? "" : templateSource);
                flashActionFeedback(this, LABEL_IDLE, LABEL_DONE);
            } catch (java.io.IOException ioEx) {
                Messages.showErrorDialog(project,
                        "写入模板文件失败: " + ioEx.getMessage(), "保存失败");
            } catch (IllegalArgumentException argEx) {
                Messages.showErrorDialog(project, argEx.getMessage(), "保存失败");
            }
        }
    }

    /**
     * 「复制到剪贴板」action —— 把当前编辑器的"渲染形态"（chip 脱壳为内部值）写剪贴板
     *
     * <p>同样走异步，理由同 {@link SaveTemplateAction}。</p>
     */
    private class CopyToClipboardAction extends DialogWrapperAction {
        private static final String LABEL_IDLE = "复制到剪贴板";
        private static final String LABEL_DONE = "✓ 已复制";

        protected CopyToClipboardAction() {
            super(LABEL_IDLE);
            // 跟"导入"一起标主操作：蓝底白字加粗，强调"先导入数据 → 再复制到剪贴板"的工作流主路径
            putValue(STYLE_PROP_KEY, STYLE_PRIMARY);
        }

        @Override
        protected void doAction(ActionEvent e) {
            editor.requestRenderedHtml().whenComplete((body, ex) ->
                    SwingUtilities.invokeLater(() -> writeClipboard(body, ex)));
        }

        private void writeClipboard(String body, Throwable ex) {
            if (ex != null) {
                Messages.showErrorDialog(project,
                        "取编辑器内容失败: " + ex.getMessage(), "复制失败");
                return;
            }
            String safeBody = body == null ? "" : body;
            String fullHtml = "<!DOCTYPE html><html><head><meta charset=\"UTF-8\"></head>"
                    + "<body>" + safeBody + "</body></html>";
            String plain = HtmlClipboardTransferable.stripHtml(safeBody);
            try {
                Toolkit.getDefaultToolkit().getSystemClipboard().setContents(
                        new HtmlClipboardTransferable(fullHtml, plain), null);
                flashActionFeedback(this, LABEL_IDLE, LABEL_DONE);
            } catch (IllegalStateException stateEx) {
                Messages.showErrorDialog(project,
                        "复制到剪贴板失败: " + stateEx.getMessage(), "复制失败");
            }
        }
    }
}
