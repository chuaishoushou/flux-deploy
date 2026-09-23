package com.flux.deploy.plugin.toolwindow;

import com.flux.deploy.plugin.util.FluxDialogs;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.JBFont;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

/**
 * 版本记录文件手动选择对话框
 *
 * <p>预检阶段发现某个目标包按模糊匹配规则找不到对应的版本记录 TXT 文件时弹出，
 * 避免执行阶段静默新建标准命名文件、与目录里已有的"命名差异很大"的记录文件形成两份。</p>
 *
 * <p><b>版式：</b>上下两段，不用分组框。上段是「发生了什么」——警告图标 + 一行加粗结论
 * （未找到哪个包的记录文件）+ 一行灰色目录，超长的包名 / 路径中段省略，完整值挂 tooltip，
 * 保证窗口宽度恒定；一条分隔线之下是「怎么处理」——一行灰色说明 + 一列扁平单选项：
 * 目录内已有的 TXT 文件逐个列出（超过 6 个滚动），最后一项「新建文件」带缩进的文件名输入框，
 * 输入框仅在选中新建时可编辑。</p>
 *
 * <p>默认选中「新建」——与未弹窗时代的旧行为（未匹配即按标准命名新建）保持一致，
 * 用户不做任何操作直接确定就是原有效果。点「取消更新」则中止本次更新流程。</p>
 *
 * @author xumanyi
 * @date 2026-07-18
 */
public class NoteFileSelectDialog extends DialogWrapper {

    /** 头部包名单行显示的最大字符数，超出中段省略 */
    private static final int MAX_NAME_CHARS = 42;
    /** 头部目录单行显示的最大字符数，超出中段省略 */
    private static final int MAX_PATH_CHARS = 58;
    /** 已有文件列表最多直接展开的行数，超出部分滚动 */
    private static final int MAX_VISIBLE_ROWS = 6;
    /** 新建文件名输入框相对其单选项的缩进，与单选框图标宽度对齐 */
    private static final int FIELD_INDENT = 22;

    private final String packageName;
    private final String packageDir;
    private final List<String> txtFiles;
    private final String canonicalName;

    private final ButtonGroup group = new ButtonGroup();
    private final List<JRadioButton> fileRadios = new ArrayList<>();
    private final JRadioButton createNewRadio;
    private final JBTextField newNameField;

    /**
     * 构造版本记录文件选择对话框
     *
     * @param project       IDEA 项目（用于对话框定位，可为 null）
     * @param packageName   目标包文件名（含扩展名，用于标题与说明展示）
     * @param packageDir    包所在的远端目录（展示用）
     * @param txtFiles      该目录下全部 .txt 文件名（可为空列表，表示目录里没有任何 TXT）
     * @param canonicalName 标准命名的新建文件名，预填进输入框
     * @author xumanyi
     * @date 2026-07-18
     */
    public NoteFileSelectDialog(@Nullable Project project, String packageName, String packageDir,
                                List<String> txtFiles, String canonicalName) {
        super(project, false);
        this.packageName = packageName;
        this.packageDir = packageDir;
        this.txtFiles = txtFiles;
        this.canonicalName = canonicalName;

        this.createNewRadio = new JRadioButton("新建文件");
        this.newNameField = new JBTextField(canonicalName);

        setTitle("选择版本记录文件");
        setOKButtonText("确定");
        setCancelButtonText("取消更新");
        init();
    }

    /**
     * 构建对话框主面板：头部结论区 + 分隔线 + 单选项区
     *
     * @return 主面板组件
     * @author xumanyi
     * @date 2026-07-18
     */
    @Nullable
    @Override
    protected JComponent createCenterPanel() {
        JPanel root = new JPanel(new BorderLayout(0, 14));
        root.setBorder(FluxDialogs.contentBorder());

        JPanel top = new JPanel(new BorderLayout(0, 12));
        top.add(buildHeader(), BorderLayout.NORTH);
        top.add(new JSeparator(), BorderLayout.SOUTH);
        root.add(top, BorderLayout.NORTH);

        root.add(buildChoicePanel(), BorderLayout.CENTER);

        // 默认选中「新建」：与旧逻辑（未匹配即按标准命名新建）行为一致
        createNewRadio.setSelected(true);
        syncNewNameFieldState();

        // 宽度锁定为标准弹窗宽度，高度按内容自适应（长包名 / 长路径已省略，不会撑宽）
        root.setPreferredSize(new Dimension(FluxDialogs.WIDTH_M, root.getPreferredSize().height));
        return root;
    }

    /**
     * 构建头部结论区：警告图标 + 加粗结论行 + 灰色目录行
     *
     * @return 头部面板
     * @author xumanyi
     * @date 2026-07-18
     */
    private JComponent buildHeader() {
        JPanel header = new JPanel(new BorderLayout(10, 0));

        JBLabel icon = new JBLabel(AllIcons.General.Warning);
        icon.setVerticalAlignment(SwingConstants.TOP);
        header.add(icon, BorderLayout.WEST);

        JPanel texts = new JPanel();
        texts.setLayout(new BoxLayout(texts, BoxLayout.Y_AXIS));

        JBLabel headline = new JBLabel("未找到 "
                + StringUtil.shortenTextWithEllipsis(packageName, MAX_NAME_CHARS, MAX_NAME_CHARS / 3)
                + " 的版本记录文件");
        headline.setFont(JBFont.label().asBold());
        headline.setToolTipText(packageName);
        headline.setAlignmentX(Component.LEFT_ALIGNMENT);
        texts.add(headline);

        texts.add(Box.createVerticalStrut(4));

        JBLabel dirLine = new JBLabel(StringUtil.shortenPathWithEllipsis(packageDir, MAX_PATH_CHARS));
        dirLine.setFont(JBFont.small());
        dirLine.setForeground(UIUtil.getContextHelpForeground());
        dirLine.setToolTipText(packageDir);
        dirLine.setAlignmentX(Component.LEFT_ALIGNMENT);
        texts.add(dirLine);

        header.add(texts, BorderLayout.CENTER);
        return header;
    }

    /**
     * 构建单选项区：一行灰色说明 + 已有 TXT 文件单选列表 + 新建项与文件名输入框
     *
     * @return 单选项面板
     * @author xumanyi
     * @date 2026-07-18
     */
    private JComponent buildChoicePanel() {
        JPanel panel = new JPanel(new BorderLayout(0, 8));

        JBLabel hint = new JBLabel(txtFiles.isEmpty()
                ? "该目录下没有 TXT 文件，只能新建"
                : "本次更新记录追加写入所选文件");
        hint.setFont(JBFont.small());
        hint.setForeground(UIUtil.getContextHelpForeground());
        panel.add(hint, BorderLayout.NORTH);

        if (!txtFiles.isEmpty()) {
            JPanel column = new JPanel();
            column.setLayout(new BoxLayout(column, BoxLayout.Y_AXIS));
            for (String fname : txtFiles) {
                JRadioButton radio = new JRadioButton(fname);
                radio.setActionCommand(fname);
                radio.setToolTipText(fname);
                radio.setAlignmentX(Component.LEFT_ALIGNMENT);
                radio.addItemListener(e -> syncNewNameFieldState());
                group.add(radio);
                fileRadios.add(radio);
                column.add(radio);
            }
            JBScrollPane scroll = new JBScrollPane(column,
                    ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                    ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED);
            scroll.setBorder(JBUI.Borders.empty());
            scroll.setOpaque(false);
            scroll.getViewport().setOpaque(false);
            // 高度按文件数自适应，超过 MAX_VISIBLE_ROWS 行后出滚动条
            int rowHeight = createNewRadio.getPreferredSize().height;
            scroll.setPreferredSize(new Dimension(FluxDialogs.WIDTH_M - 40,
                    Math.min(txtFiles.size(), MAX_VISIBLE_ROWS) * rowHeight));
            panel.add(scroll, BorderLayout.CENTER);
        }

        JPanel createBlock = new JPanel(new BorderLayout(0, 4));
        createNewRadio.addItemListener(e -> syncNewNameFieldState());
        group.add(createNewRadio);
        createBlock.add(createNewRadio, BorderLayout.NORTH);

        JPanel fieldRow = new JPanel(new BorderLayout());
        fieldRow.setBorder(JBUI.Borders.emptyLeft(FIELD_INDENT));
        fieldRow.add(newNameField, BorderLayout.CENTER);
        createBlock.add(fieldRow, BorderLayout.CENTER);
        panel.add(createBlock, BorderLayout.SOUTH);

        return panel;
    }

    /**
     * 指定初始聚焦组件：默认选中「新建」，直接把光标交给文件名输入框
     *
     * @return 文件名输入框
     * @author xumanyi
     * @date 2026-07-18
     */
    @Nullable
    @Override
    public JComponent getPreferredFocusedComponent() {
        return newNameField;
    }

    /**
     * 联动输入框可编辑状态：仅在「新建」被选中时允许编辑文件名，选中已有文件时置灰
     *
     * @author xumanyi
     * @date 2026-07-18
     */
    private void syncNewNameFieldState() {
        boolean createNew = createNewRadio.isSelected();
        newNameField.setEnabled(createNew);
        if (createNew) {
            newNameField.requestFocusInWindow();
        }
    }

    /**
     * 校验用户输入：仅在选中「新建」时检查文件名合法性（非空、.txt 结尾、不含路径分隔符）
     *
     * @return 校验错误信息；通过时返回 null
     * @author xumanyi
     * @date 2026-07-18
     */
    @Nullable
    @Override
    protected ValidationInfo doValidate() {
        if (!createNewRadio.isSelected()) {
            return null;
        }
        String name = newNameField.getText() == null ? "" : newNameField.getText().trim();
        if (name.isEmpty()) {
            return new ValidationInfo("请输入新建文件名", newNameField);
        }
        if (name.contains("/") || name.contains("\\")) {
            return new ValidationInfo("文件名不能包含路径分隔符", newNameField);
        }
        if (!name.toLowerCase().endsWith(".txt")) {
            return new ValidationInfo("文件名需以 .txt 结尾", newNameField);
        }
        return null;
    }

    /**
     * 判断用户是否选择了「新建版本记录文件」
     *
     * @return true 表示新建；false 表示选中了目录里已有的文件
     * @author xumanyi
     * @date 2026-07-18
     */
    public boolean isCreateNew() {
        return createNewRadio.isSelected();
    }

    /**
     * 获取用户最终确定的版本记录文件名：选已有文件时为该文件名，选新建时为输入框内容（已 trim）
     *
     * @return 版本记录文件名（不含路径）
     * @author xumanyi
     * @date 2026-07-18
     */
    public String getSelectedFileName() {
        if (createNewRadio.isSelected()) {
            return newNameField.getText() == null ? canonicalName : newNameField.getText().trim();
        }
        for (JRadioButton radio : fileRadios) {
            if (radio.isSelected()) {
                return radio.getActionCommand();
            }
        }
        // 理论不可达：ButtonGroup 保证必有一项被选中；兜底返回标准命名
        return canonicalName;
    }
}
