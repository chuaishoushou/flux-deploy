package com.flux.deploy.plugin.email;

import com.flux.deploy.email.EmailDraftManager;
import com.flux.deploy.email.EmailTemplateRenderer;
import com.flux.deploy.email.EmailTemplateStore;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.Messages;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.text.AttributeSet;
import javax.swing.text.BadLocationException;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;
import javax.swing.text.StyledEditorKit;
import javax.swing.text.html.HTMLEditorKit;
import java.awt.*;
import java.awt.Toolkit;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.Transferable;
import java.awt.event.ActionEvent;
import java.awt.font.TextAttribute;
import java.io.StringWriter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 通知邮件弹窗：模板选择 + 富文本编辑 + 复制 / 保存到模板
 *
 * <p>UI 结构（从上到下）：</p>
 * <pre>
 *   ┌─ 通知邮件 ──────────────────────────────────────┐
 *   │ 模板：[下拉 ▾]  [+ 新建]  [× 删除]              │
 *   │ ─────────────────────────────────────────────  │
 *   │  富文本编辑区（JEditorPane + HTMLEditorKit）     │
 *   │  ...                                            │
 *   ├──────────────────────────────────────────────  │
 *   │  [复制到剪贴板]   [保存到模板]   [关闭]          │
 *   └─────────────────────────────────────────────  ┘
 * </pre>
 *
 * <p>关闭弹窗（点关闭 / ESC / 窗口 X）都会把当前编辑区内容回写
 * {@code EmailDraftManager}。</p>
 *
 * @author claude
 * @date 2026-05-17
 */
public class EmailDialog extends DialogWrapper {

    /** body 提取：从 JEditorPane 的完整 HTML 文档里取 {@code <body>} 包裹的内容 */
    private static final Pattern BODY_PATTERN =
            Pattern.compile("(?is)<body[^>]*>(.*?)</body>");

    private final Project project;
    private final EmailDraftManager manager;
    private final EmailRuntimeData runtimeData;

    private ComboBox<String> templateCombo;
    private JEditorPane editorPane;

    /**
     * 构造邮件弹窗
     *
     * @param project     当前项目（可空：脱离 ToolWindow 调用场景）
     * @param manager     邮件 draft 管理器（由 {@code DeployToolWindowPanel} 提供）
     * @param runtimeData 主面板运行时数据供给器
     * @author claude
     * @date 2026-05-17
     */
    public EmailDialog(@Nullable Project project, @NotNull EmailDraftManager manager,
                       @NotNull EmailRuntimeData runtimeData) {
        super(project, false);
        this.project = project;
        this.manager = manager;
        this.runtimeData = runtimeData;
        setTitle("通知邮件");
        // OK 按钮重命名为「关闭」；不显示 Cancel 按钮（关闭即唯一退出方式）
        setOKButtonText("关闭");
        init();
    }

    @Override
    protected JComponent createCenterPanel() {
        JPanel root = new JPanel(new BorderLayout(0, 6));
        root.setBorder(JBUI.Borders.empty(8, 10));
        root.setPreferredSize(new Dimension(820, 580));

        root.add(buildTemplateRow(), BorderLayout.NORTH);

        // 编辑区 + 顶部富文本工具栏 合在一个 BorderLayout 子容器
        JPanel editorArea = new JPanel(new BorderLayout(0, 4));
        JScrollPane editorScroll = buildEditorScroll();
        editorArea.add(buildRichTextToolbar(), BorderLayout.NORTH);
        editorArea.add(editorScroll, BorderLayout.CENTER);
        root.add(editorArea, BorderLayout.CENTER);
        return root;
    }

    /**
     * 顶部模板选择行：标签 + 下拉 + 新建 + 删除
     *
     * @return 顶部容器
     * @author claude
     * @date 2026-05-17
     */
    private JPanel buildTemplateRow() {
        templateCombo = new ComboBox<>();
        reloadTemplateNames();
        // 选中"上次选中的模板"
        templateCombo.setSelectedItem(manager.getSelectedTemplateName());
        templateCombo.addActionListener(e -> {
            Object sel = templateCombo.getSelectedItem();
            if (sel instanceof String) {
                manager.setSelectedTemplateName((String) sel);
            }
        });

        JButton newBtn = new JButton("+ 新建");
        newBtn.addActionListener(this::onCreateNewTemplate);
        JButton delBtn = new JButton("× 删除");
        delBtn.addActionListener(this::onDeleteTemplate);

        JPanel right = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        right.add(newBtn);
        right.add(delBtn);

        JPanel row = new JPanel(new BorderLayout(8, 0));
        row.add(new JBLabel("模板："), BorderLayout.WEST);
        row.add(templateCombo, BorderLayout.CENTER);
        row.add(right, BorderLayout.EAST);

        JPanel container = new JPanel(new BorderLayout());
        container.add(row, BorderLayout.CENTER);

        JBLabel hint = new JBLabel("<html><body style='color:#888;font-size:11px;'>"
                + "切换模板不影响当前编辑区；「保存到模板」会覆盖下拉选中的模板。"
                + "</body></html>");
        hint.setBorder(JBUI.Borders.emptyTop(4));
        container.add(hint, BorderLayout.SOUTH);
        return container;
    }

    /**
     * 中部富文本编辑区
     *
     * @return 滚动容器
     * @author claude
     * @date 2026-05-17
     */
    private JScrollPane buildEditorScroll() {
        editorPane = new JEditorPane();
        editorPane.setEditorKit(new HTMLEditorKit());
        editorPane.setContentType("text/html");
        editorPane.setEditable(true);
        // 覆盖默认 TransferHandler：让 Ctrl+C / Cmd+C 选中复制也产出
        //   1) 富文本 HTML（完整 charset 文档，保住加粗 / 颜色 / 换行）
        //   2) plain text fallback（真换行，从 Document.getText 提取）
        // 否则 JEditorPane 自带 copy 会输出 HTMLEditorKit 默认序列化的 HTML，
        // <br> 处理不一致，粘到一些应用会丢换行。
        editorPane.setTransferHandler(new HtmlAwareTransferHandler());

        Map<String, String> values = runtimeData.collectFieldValues();
        String currentProjectDir = runtimeData.getCurrentProjectDir();
        String bodyHtml = manager.getDraftForDisplay(values, currentProjectDir);
        editorPane.setText(wrapAsHtmlDocument(bodyHtml));
        // 把光标放回起始位置，避免初始 caret 跳到末尾导致用户看不到正文头部
        editorPane.setCaretPosition(0);

        JBScrollPane scroll = new JBScrollPane(editorPane);
        scroll.setBorder(BorderFactory.createLineBorder(
                UIManager.getColor("Component.borderColor"), 1));
        return scroll;
    }

    /**
     * 富文本工具栏：字体 / 字号 / B I U S / 文字色 / 背景色 / 对齐
     *
     * <p>所有 Action 都构造 {@link StyledEditorKit} 提供的标准类并把 source 设为
     * {@code editorPane}（StyledTextAction 必须从 source 拿目标 JEditorPane）。
     * 删除线和背景色 StyledEditorKit 没现成 Action，自己用 {@link StyleConstants}
     * 写 {@link StyledDocument#setCharacterAttributes} 实现。</p>
     *
     * <p>风格作用对象：当前选中范围；没选中时改变 caret 处样式（继续输入生效）。</p>
     *
     * @return 工具栏组件
     * @author claude
     * @date 2026-05-17
     */
    private JToolBar buildRichTextToolbar() {
        JToolBar tb = new JToolBar();
        tb.setFloatable(false);
        tb.setBorder(JBUI.Borders.empty(2));

        // ---- 字体 family 下拉（跨平台常见 + 中文常用，足以覆盖邮件场景）----
        String[] fonts = {"默认", "SansSerif", "Serif", "Monospaced",
                "Arial", "Helvetica", "Times New Roman", "Courier New",
                "Microsoft YaHei", "SimSun", "SimHei", "PingFang SC"};
        ComboBox<String> fontCombo = new ComboBox<>(fonts);
        fontCombo.setSelectedIndex(0);
        fontCombo.setMaximumRowCount(12);
        fontCombo.setPreferredSize(new Dimension(140,
                fontCombo.getPreferredSize().height));
        fontCombo.addActionListener(e -> {
            Object sel = fontCombo.getSelectedItem();
            if (!(sel instanceof String)) return;
            String family = "默认".equals(sel) ? "SansSerif" : (String) sel;
            new StyledEditorKit.FontFamilyAction("font-family", family)
                    .actionPerformed(new ActionEvent(editorPane,
                            ActionEvent.ACTION_PERFORMED, ""));
            editorPane.requestFocusInWindow();
        });
        tb.add(new JBLabel("字体 "));
        tb.add(fontCombo);

        tb.addSeparator(new Dimension(8, 0));

        // ---- 字号下拉 ----
        Integer[] sizes = {10, 11, 12, 13, 14, 16, 18, 20, 24, 28};
        ComboBox<Integer> sizeCombo = new ComboBox<>(sizes);
        sizeCombo.setSelectedItem(12);
        sizeCombo.setMaximumRowCount(10);
        sizeCombo.setPreferredSize(new Dimension(60,
                sizeCombo.getPreferredSize().height));
        sizeCombo.addActionListener(e -> {
            Object sel = sizeCombo.getSelectedItem();
            if (!(sel instanceof Integer)) return;
            new StyledEditorKit.FontSizeAction("font-size", (Integer) sel)
                    .actionPerformed(new ActionEvent(editorPane,
                            ActionEvent.ACTION_PERFORMED, ""));
            editorPane.requestFocusInWindow();
        });
        tb.add(new JBLabel("字号 "));
        tb.add(sizeCombo);

        tb.addSeparator(new Dimension(8, 0));

        // ---- B I U S ----
        // 加粗
        JButton boldBtn = new JButton("B");
        boldBtn.setFont(boldBtn.getFont().deriveFont(Font.BOLD, 13f));
        boldBtn.setToolTipText("加粗");
        boldBtn.setMargin(JBUI.insets(2, 8));
        boldBtn.addActionListener(e -> runStyleAction(new StyledEditorKit.BoldAction()));
        tb.add(boldBtn);
        // 斜体
        JButton italicBtn = new JButton("I");
        italicBtn.setFont(italicBtn.getFont().deriveFont(Font.ITALIC, 13f));
        italicBtn.setToolTipText("斜体");
        italicBtn.setMargin(JBUI.insets(2, 8));
        italicBtn.addActionListener(e -> runStyleAction(new StyledEditorKit.ItalicAction()));
        tb.add(italicBtn);
        // 下划线
        JButton underlineBtn = new JButton("U");
        Map<TextAttribute, Object> ulAttrs = new HashMap<>();
        ulAttrs.put(TextAttribute.UNDERLINE, TextAttribute.UNDERLINE_ON);
        underlineBtn.setFont(underlineBtn.getFont().deriveFont(ulAttrs).deriveFont(13f));
        underlineBtn.setToolTipText("下划线");
        underlineBtn.setMargin(JBUI.insets(2, 8));
        underlineBtn.addActionListener(e -> runStyleAction(new StyledEditorKit.UnderlineAction()));
        tb.add(underlineBtn);
        // 删除线
        JButton strikeBtn = new JButton("S");
        Map<TextAttribute, Object> stAttrs = new HashMap<>();
        stAttrs.put(TextAttribute.STRIKETHROUGH, TextAttribute.STRIKETHROUGH_ON);
        strikeBtn.setFont(strikeBtn.getFont().deriveFont(stAttrs).deriveFont(13f));
        strikeBtn.setToolTipText("删除线");
        strikeBtn.setMargin(JBUI.insets(2, 8));
        strikeBtn.addActionListener(e -> toggleStrikeThrough());
        tb.add(strikeBtn);

        tb.addSeparator(new Dimension(8, 0));

        // ---- 颜色 ----
        // 文字颜色
        JButton fgBtn = new JButton("文字色");
        fgBtn.setToolTipText("文字前景色");
        fgBtn.setMargin(JBUI.insets(2, 8));
        fgBtn.addActionListener(e -> {
            Color c = JColorChooser.showDialog(getContentPanel(),
                    "选择文字颜色", editorPane.getForeground());
            if (c != null) {
                new StyledEditorKit.ForegroundAction("font-foreground", c)
                        .actionPerformed(new ActionEvent(editorPane,
                                ActionEvent.ACTION_PERFORMED, ""));
                editorPane.requestFocusInWindow();
            }
        });
        tb.add(fgBtn);
        // 背景色
        JButton bgBtn = new JButton("背景色");
        bgBtn.setToolTipText("文字背景色（高亮）");
        bgBtn.setMargin(JBUI.insets(2, 8));
        bgBtn.addActionListener(e -> {
            Color c = JColorChooser.showDialog(getContentPanel(),
                    "选择背景色", Color.YELLOW);
            if (c != null) {
                applySelectionBackground(c);
                editorPane.requestFocusInWindow();
            }
        });
        tb.add(bgBtn);

        tb.addSeparator(new Dimension(8, 0));

        // ---- 对齐 ----
        tb.add(buildAlignButton("左", "左对齐", StyleConstants.ALIGN_LEFT));
        tb.add(buildAlignButton("中", "居中对齐", StyleConstants.ALIGN_CENTER));
        tb.add(buildAlignButton("右", "右对齐", StyleConstants.ALIGN_RIGHT));

        return tb;
    }

    /**
     * 构造一个段落对齐按钮
     *
     * @param label   按钮文字
     * @param tooltip 悬停提示
     * @param align   {@link StyleConstants#ALIGN_LEFT} / {@code ALIGN_CENTER} / {@code ALIGN_RIGHT}
     * @return 按钮
     * @author claude
     * @date 2026-05-17
     */
    private JButton buildAlignButton(String label, String tooltip, int align) {
        JButton btn = new JButton(label);
        btn.setToolTipText(tooltip);
        btn.setMargin(JBUI.insets(2, 8));
        btn.addActionListener(e -> {
            new StyledEditorKit.AlignmentAction("alignment", align)
                    .actionPerformed(new ActionEvent(editorPane,
                            ActionEvent.ACTION_PERFORMED, ""));
            editorPane.requestFocusInWindow();
        });
        return btn;
    }

    /**
     * 对当前选中范围切换删除线（StyledEditorKit 没有现成 Action，自行实现）
     *
     * @author claude
     * @date 2026-05-17
     */
    private void toggleStrikeThrough() {
        int start = editorPane.getSelectionStart();
        int end = editorPane.getSelectionEnd();
        if (start == end) return;
        StyledDocument doc = (StyledDocument) editorPane.getDocument();
        AttributeSet attr = doc.getCharacterElement(start).getAttributes();
        boolean current = StyleConstants.isStrikeThrough(attr);
        SimpleAttributeSet sas = new SimpleAttributeSet();
        StyleConstants.setStrikeThrough(sas, !current);
        doc.setCharacterAttributes(start, end - start, sas, false);
        editorPane.requestFocusInWindow();
    }

    /**
     * 对当前选中范围设置背景色（高亮）
     *
     * @param c 背景色
     * @author claude
     * @date 2026-05-17
     */
    private void applySelectionBackground(Color c) {
        int start = editorPane.getSelectionStart();
        int end = editorPane.getSelectionEnd();
        if (start == end) return;
        StyledDocument doc = (StyledDocument) editorPane.getDocument();
        SimpleAttributeSet sas = new SimpleAttributeSet();
        StyleConstants.setBackground(sas, c);
        doc.setCharacterAttributes(start, end - start, sas, false);
    }

    /**
     * 触发一个 {@link StyledEditorKit} 提供的样式 Action，把 source 设为
     * 当前 editorPane，使 Action 内部 {@code getEditor(e)} 能定位到目标。
     *
     * <p>样式 Action 处理完后把焦点交回编辑区，方便用户立即继续输入。</p>
     *
     * @param action 样式 Action（粗体 / 斜体 / 下划线等）
     * @author claude
     * @date 2026-05-17
     */
    private void runStyleAction(Action action) {
        action.actionPerformed(new ActionEvent(editorPane,
                ActionEvent.ACTION_PERFORMED, ""));
        editorPane.requestFocusInWindow();
    }

    /**
     * 重新加载模板下拉里的选项（新建 / 删除后调用）
     *
     * @author claude
     * @date 2026-05-17
     */
    private void reloadTemplateNames() {
        try {
            List<String> names = manager.getStore().listNames();
            templateCombo.removeAllItems();
            for (String n : names) {
                templateCombo.addItem(n);
            }
        } catch (Exception ex) {
            // 列模板失败：至少能继续工作，强制塞入 default
            templateCombo.removeAllItems();
            templateCombo.addItem(EmailTemplateStore.DEFAULT_TEMPLATE_NAME);
        }
    }

    /**
     * 「+ 新建」按钮回调：弹输入框拿新名字，复制当前 default 模板作为初始内容
     *
     * @param e action 事件
     * @author claude
     * @date 2026-05-17
     */
    private void onCreateNewTemplate(ActionEvent e) {
        String name = Messages.showInputDialog(project,
                "新模板名称（中文 / 字母 / 数字 / 下划线 / 连字符，不含点和空白）：",
                "新建邮件模板", null);
        if (name == null || name.isBlank()) {
            return;
        }
        name = name.trim();
        try {
            // 新模板初始内容 = 当前 default 模板内容（让用户改差异，而非从零开始）
            String initial = manager.getStore().loadOrDefault(
                    EmailTemplateStore.DEFAULT_TEMPLATE_NAME);
            manager.getStore().createNew(name, initial);
            reloadTemplateNames();
            templateCombo.setSelectedItem(name);
            manager.setSelectedTemplateName(name);
            Messages.showInfoMessage(project,
                    "已创建模板「" + name + "」。在编辑区里改完后点「保存到模板」把当前内容写入此模板。",
                    "新建成功");
        } catch (IllegalArgumentException | IllegalStateException ex) {
            Messages.showErrorDialog(project, ex.getMessage(), "新建失败");
        } catch (java.io.IOException ex) {
            Messages.showErrorDialog(project,
                    "写入模板文件失败: " + ex.getMessage(), "新建失败");
        }
    }

    /**
     * 「× 删除」按钮回调：删除下拉选中的模板（default 拒绝）
     *
     * @param e action 事件
     * @author claude
     * @date 2026-05-17
     */
    private void onDeleteTemplate(ActionEvent e) {
        Object sel = templateCombo.getSelectedItem();
        if (!(sel instanceof String)) {
            return;
        }
        String name = (String) sel;
        if (EmailTemplateStore.DEFAULT_TEMPLATE_NAME.equals(name)) {
            Messages.showWarningDialog(project,
                    "default 模板是兜底模板，不允许删除。可以「保存到模板」覆盖它的内容。",
                    "无法删除");
            return;
        }
        int confirm = Messages.showYesNoDialog(project,
                "确认删除模板「" + name + "」？删除后无法恢复。",
                "确认删除", Messages.getWarningIcon());
        if (confirm != Messages.YES) {
            return;
        }
        try {
            manager.getStore().delete(name);
            reloadTemplateNames();
            // 删后切回 default
            templateCombo.setSelectedItem(EmailTemplateStore.DEFAULT_TEMPLATE_NAME);
            manager.setSelectedTemplateName(EmailTemplateStore.DEFAULT_TEMPLATE_NAME);
        } catch (Exception ex) {
            Messages.showErrorDialog(project,
                    "删除模板失败: " + ex.getMessage(), "删除失败");
        }
    }

    /**
     * 自定义底部按钮：保存到模板 / 复制到剪贴板 / 关闭
     *
     * @return action 数组（最右侧的 getOKAction = 关闭）
     * @author claude
     * @date 2026-05-17
     */
    @NotNull
    @Override
    protected Action[] createActions() {
        return new Action[]{
                new SaveTemplateAction(),
                new CopyToClipboardAction(),
                getOKAction(),
        };
    }

    /**
     * 左下角按钮：导入主面板当前勾选的目标包到 {@code ${更新包}} 锚点
     *
     * @return 左侧 action 数组
     * @author claude
     * @date 2026-05-17
     */
    @NotNull
    @Override
    protected Action[] createLeftSideActions() {
        return new Action[]{new ImportSelectedPackagesAction()};
    }

    /** 「关闭」点击 / ESC / 窗口 X 都走这里：回写 draft + 缓存收件人 */
    @Override
    protected void doOKAction() {
        persistDraftAndRecipient();
        super.doOKAction();
    }

    @Override
    public void doCancelAction() {
        persistDraftAndRecipient();
        super.doCancelAction();
    }

    /**
     * 把当前编辑区的 HTML 抽 body + 回写 draft
     *
     * <p>历史版本曾把 {@code ${收件人}} 锚点值缓存到 {@code PluginSettingsService}，
     * 简化为 4 绑定变量后收件人改为字面文字（由用户在编辑区里写并通过「保存到模板」
     * 持久化），不再需要专门缓存。</p>
     *
     * @author claude
     * @date 2026-05-17
     */
    private void persistDraftAndRecipient() {
        manager.setDraftContent(extractBodyHtml());
    }

    /**
     * 「复制到剪贴板」action
     *
     * <p>成功时静默（不弹对话框打扰用户），仅按钮文字短暂变为「✓ 已复制」
     * 给出视觉反馈，1.5 秒后还原。失败时（剪贴板被占用等罕见情况）仍弹错误
     * 对话框，否则用户不知道为什么没生效。</p>
     */
    private class CopyToClipboardAction extends DialogWrapperAction {
        private static final String LABEL_IDLE = "复制到剪贴板";
        private static final String LABEL_DONE = "✓ 已复制";

        protected CopyToClipboardAction() {
            super(LABEL_IDLE);
            putValue(DEFAULT_ACTION, Boolean.TRUE);
        }

        @Override
        protected void doAction(ActionEvent e) {
            // HTML flavor：包成完整文档 + <meta charset>，避免接收方按 ISO-8859-1 误读中文，
            // 也帮助一些"严格"富文本接收端（Outlook / 金山文档等）正确识别格式。
            String body = extractBodyHtml();
            String fullHtml = "<!DOCTYPE html><html><head><meta charset=\"UTF-8\"></head>"
                    + "<body>" + body + "</body></html>";

            // plain text flavor：用 JEditorPane Document 的真换行文本，而不是从 HTML
            // strip——因为 HTMLEditorKit 的 getText() 输出 HTML 不稳定，stripHtml 的
            // 正则不保证捕获所有换行点。Document.getText() 拿的就是用户视觉看到的文本，
            // 自带 \n 换行，最可靠。
            String plain;
            try {
                javax.swing.text.Document doc = editorPane.getDocument();
                plain = doc.getText(0, doc.getLength());
            } catch (javax.swing.text.BadLocationException badLoc) {
                plain = HtmlClipboardTransferable.stripHtml(body);
            }

            try {
                Toolkit.getDefaultToolkit().getSystemClipboard().setContents(
                        new HtmlClipboardTransferable(fullHtml, plain), null);
                flashCopiedFeedback();
            } catch (IllegalStateException ex) {
                Messages.showErrorDialog(project,
                        "复制到剪贴板失败: " + ex.getMessage(), "复制失败");
            }
        }

        /**
         * 把按钮文字临时改为「✓ 已复制」，1.5 秒后还原
         *
         * <p>DialogWrapperAction 的 name 改了之后 EDT 上对应 JButton 会自动重绘。
         * 用 Swing Timer 调度还原，避免阻塞 EDT。</p>
         */
        private void flashCopiedFeedback() {
            putValue(NAME, LABEL_DONE);
            Timer timer = new Timer(1500, evt -> putValue(NAME, LABEL_IDLE));
            timer.setRepeats(false);
            timer.start();
        }
    }

    /**
     * 「导入选中包」action：把主面板当前勾选的目标包名一个个 append 到 {@code ${更新包}} 锚点。
     *
     * <p>去重：若包名已经在锚点里，不重复添加。空选择时给出友好提示。
     * 锚点缺失（如用户在编辑区把整行删了）时也给提示，避免用户疑惑"按了没反应"。</p>
     */
    private class ImportSelectedPackagesAction extends DialogWrapperAction {
        protected ImportSelectedPackagesAction() {
            super("导入选中包");
        }

        @Override
        protected void doAction(ActionEvent e) {
            List<String> selected = runtimeData.getCurrentSelectedPackageNames();
            if (selected == null || selected.isEmpty()) {
                Messages.showInfoMessage(project,
                        "主面板没有勾选任何目标包，无可导入。",
                        "导入选中包");
                return;
            }
            String before = extractBodyHtml();
            String after = before;
            int added = 0;
            int skipped = 0;
            for (String pkg : selected) {
                String result = EmailTemplateRenderer.appendAccumulate(
                        after, "更新包", pkg, EmailDraftManager.UPDATE_PKG_SEPARATOR,
                        true, true);
                if (result.equals(after)) {
                    skipped++;
                } else {
                    added++;
                    after = result;
                }
            }
            if (added == 0 && skipped == selected.size() && !after.contains("flux-field-更新包")) {
                // 锚点丢失：appendAccumulate 找不到 span 会原样返回，看上去像"全部去重"
                Messages.showWarningDialog(project,
                        "找不到「更新包」锚点。可能编辑区里这一行被删了——"
                                + "可以切到 default 模板后重新打开邮件，或在编辑区里加回\"更新包: ${更新包}\"那一行。",
                        "导入失败");
                return;
            }
            // 把更新后的 body 重新写回编辑器
            editorPane.setText(wrapAsHtmlDocument(after));
            editorPane.setCaretPosition(0);
            Messages.showInfoMessage(project,
                    "已导入 " + added + " 个包" + (skipped > 0 ? "，跳过 " + skipped + " 个重复" : "") + "。",
                    "导入选中包");
        }
    }

    /** 「保存到模板」action */
    private class SaveTemplateAction extends DialogWrapperAction {
        protected SaveTemplateAction() {
            super("保存到模板");
        }

        @Override
        protected void doAction(ActionEvent e) {
            Object sel = templateCombo.getSelectedItem();
            if (!(sel instanceof String)) {
                return;
            }
            String name = (String) sel;
            String body = extractBodyHtml();
            try {
                manager.saveCurrentDraftAsTemplate(body, name);
                Messages.showInfoMessage(project,
                        "当前编辑区内容已保存到模板「" + name + "」。\n"
                                + "已自动把任务号 / 客服号等实例值反向折算回占位符。",
                        "保存成功");
            } catch (java.io.IOException ex) {
                Messages.showErrorDialog(project,
                        "写入模板文件失败: " + ex.getMessage(), "保存失败");
            } catch (IllegalArgumentException ex) {
                Messages.showErrorDialog(project, ex.getMessage(), "保存失败");
            }
        }
    }

    // ==================== HTML 辅助 ====================

    /**
     * 把 body HTML 片段包装为完整 HTML 文档供 {@link JEditorPane#setText} 使用
     *
     * <p>包一层 {@code <html><head><style>...</style></head><body>}，
     * 让 HTMLEditorKit 用统一的字体 / 行高渲染，避免不同系统默认字体的视觉漂移。</p>
     *
     * @param bodyHtml body 内片段
     * @return 完整 HTML 文档串
     * @author claude
     * @date 2026-05-17
     */
    private static String wrapAsHtmlDocument(String bodyHtml) {
        return "<html><head><style>"
                + "body { font-family: sans-serif; font-size: 12px; line-height: 1.6; "
                + "margin: 6px; }"
                + "</style></head><body>"
                + (bodyHtml == null ? "" : bodyHtml)
                + "</body></html>";
    }

    /**
     * 从 {@link JEditorPane#getText()} 的完整 HTML 中抽出 body 内片段
     *
     * <p>找不到 {@code <body>} 时返回原文（防御性兜底；HTMLEditorKit 一定包含 body）。</p>
     *
     * @return body 内 HTML
     * @author claude
     * @date 2026-05-17
     */
    private String extractBodyHtml() {
        String full = editorPane.getText();
        if (full == null) {
            return "";
        }
        Matcher m = BODY_PATTERN.matcher(full);
        if (m.find()) {
            return m.group(1).trim();
        }
        return full.trim();
    }

    // ==================== TransferHandler 用于 Ctrl+C / Cmd+C ====================

    /**
     * 自定义剪贴板导出：选中复制时同时提供富文本 HTML 与真换行纯文本
     *
     * <p>JEditorPane 默认 TransferHandler 走 HTMLEditorKit 的 write，输出 HTML
     * 结构不稳定，{@code <br>} / {@code <p>} 混杂，粘到一些应用会丢换行；
     * 这里覆盖 {@link #createTransferable} 同时构造：</p>
     *
     * <ul>
     *   <li>HTML flavor：用 HTMLEditorKit 的 write 拿选中片段 HTML，再用
     *       {@code <body>} 抽出来包装成完整 charset 文档</li>
     *   <li>plain text flavor：直接从 {@link javax.swing.text.Document#getText}
     *       拿选中段——这是用户视觉看到的字符串，自带 {@code \n} 真换行</li>
     * </ul>
     *
     * <p>{@link #getSourceActions} 返回 {@code COPY} 而不是 {@code COPY_OR_MOVE}，
     * 因此 Cmd+X 剪切回退到默认行为，保持稳妥。</p>
     */
    private class HtmlAwareTransferHandler extends TransferHandler {

        @Override
        public int getSourceActions(JComponent c) {
            return COPY;
        }

        @Override
        protected Transferable createTransferable(JComponent c) {
            if (!(c instanceof JEditorPane)) {
                return null;
            }
            JEditorPane pane = (JEditorPane) c;
            int start = pane.getSelectionStart();
            int end = pane.getSelectionEnd();
            if (start == end) {
                return null;
            }

            // plain text 真换行（用户视觉文本）
            String plain;
            try {
                plain = pane.getDocument().getText(start, end - start);
            } catch (BadLocationException ex) {
                plain = "";
            }

            // HTML 选中片段：HTMLEditorKit.write 输出含完整文档结构，再用 body 抽出来
            String fragment;
            try {
                HTMLEditorKit kit = (HTMLEditorKit) pane.getEditorKit();
                StringWriter sw = new StringWriter();
                kit.write(sw, pane.getDocument(), start, end - start);
                String raw = sw.toString();
                Matcher m = BODY_PATTERN.matcher(raw);
                fragment = m.find() ? m.group(1).trim() : plainToHtml(plain);
            } catch (Exception ex) {
                fragment = plainToHtml(plain);
            }

            String fullHtml = "<!DOCTYPE html><html><head><meta charset=\"UTF-8\"></head>"
                    + "<body>" + fragment + "</body></html>";
            return new HtmlClipboardTransferable(fullHtml, plain);
        }

        @Override
        public void exportToClipboard(JComponent comp, Clipboard clip, int action)
                throws IllegalStateException {
            Transferable t = createTransferable(comp);
            if (t == null) {
                return;
            }
            clip.setContents(t, null);
        }
    }

    /**
     * 极简纯文本 → HTML：把 {@code \n} 替换为 {@code <br>}，做最小 HTML escape
     *
     * <p>仅在 HTMLEditorKit.write 失败时兜底，正常路径走 kit.write 输出更精确的 HTML。</p>
     *
     * @param plain 纯文本（可含 {@code \n}）
     * @return HTML 片段
     * @author claude
     * @date 2026-05-17
     */
    private static String plainToHtml(String plain) {
        if (plain == null) return "";
        return plain.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\n", "<br>");
    }

}
