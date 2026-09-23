package com.flux.deploy.plugin.util;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.ui.JBColor;
import com.intellij.util.ui.JBUI;

import javax.swing.*;
import javax.swing.border.Border;
import java.awt.*;

/**
 * 插件弹窗统一规范：样式常量 + 消息/确认框统一入口。
 *
 * <p>全插件弹窗一律收敛到 IntelliJ 平台组件（{@link Messages} / DialogWrapper），
 * 不再使用 {@link javax.swing.JOptionPane}——后者样式与 IDE 主题脱节，且按钮顺序按
 * 传入数组硬排，各处顺序互相矛盾。平台组件的按钮排列交给 IDE 统一处理
 * （macOS 上取消在左、主按钮在右），天然保证全插件一致。</p>
 *
 * <p><b>统一规范约定：</b></p>
 * <ul>
 *   <li><b>按钮文案</b>：主操作用动词短语（确认执行 / 开始打包 / 删除账号），
 *       通用确定用「确定」，取消一律「取消」（有必要说明后果时可用「取消更新」类短语）；
 *       不使用系统 Yes/No 文案。</li>
 *   <li><b>默认（聚焦）按钮</b>：常规推进类确认默认主操作（{@link #confirm}）；
 *       危险 / 不可逆操作（回滚、删除、不备份更新等）默认聚焦取消（{@link #confirmDanger}），
 *       防止回车误触。</li>
 *   <li><b>标题</b>：短名词短语（确认回滚 / 删除账号 / 无法打包），不带问号、不写整句。</li>
 *   <li><b>DialogWrapper 尺寸</b>：标准交互弹窗宽 {@link #WIDTH_M}，清单 / 树类宽 {@link #WIDTH_L}；
 *       内容面板边距统一 {@link #contentBorder()}。</li>
 *   <li><b>说明区</b>：HTML 风格统一——首行加粗结论（危险场景加 ⚠ 前缀），
 *       次要说明用 {@link #HINT_COLOR} 灰色；正文不再使用 ✓ / ✗ 等 emoji 前缀（图标已表意）。</li>
 * </ul>
 *
 * @author xumanyi
 * @date 2026-07-18
 */
public final class FluxDialogs {

    /** 标准交互弹窗内容宽度（radio 选择、表单、确认类） */
    public static final int WIDTH_M = 560;
    /** 清单 / 树 / 多行列表类弹窗内容宽度 */
    public static final int WIDTH_L = 640;
    /** 说明区次要文字颜色（HTML color 值） */
    public static final String HINT_COLOR = "#8a8e93";
    /**
     * 弹窗定位锚点标记：挂在插件根面板上的 client property。
     *
     * <p>{@link Messages} 系列按传入组件所在位置居中，工具窗口里随手传一个子组件会让弹窗
     * 偏在面板一角；这里统一向上找带本标记的插件根面板，做到"相对插件面板居中"。</p>
     */
    public static final String DIALOG_ANCHOR_KEY = "flux.deploy.dialogAnchor";
    /** Messages 系列消息体的最小排版宽度（px）：避免短文案弹出一个过小的窗口 */
    private static final int MESSAGE_WIDTH = 460;

    private FluxDialogs() {}

    /**
     * 把弹窗父组件规范化为插件根面板（带 {@link #DIALOG_ANCHOR_KEY} 标记的祖先），
     * 让弹窗相对插件面板居中；找不到标记时原样返回。
     *
     * @param parent 调用方传入的组件；可为 null
     * @return 用于定位的父组件
     * @author xumanyi
     * @date 2026-08-14
     */
    private static Component anchor(Component parent) {
        Component c = parent;
        while (c != null) {
            if (c instanceof JComponent jc
                    && Boolean.TRUE.equals(jc.getClientProperty(DIALOG_ANCHOR_KEY))) {
                return jc;
            }
            c = c.getParent();
        }
        return parent;
    }

    /**
     * 给纯文本消息套上固定宽度的 HTML 排版：弹窗不再随文案长短忽大忽小，短提示也有正常体量。
     *
     * <p>已经是 HTML（以 {@code <html} 开头）的消息原样返回，由调用方自行控制排版。</p>
     *
     * @param message 原始消息
     * @return 可直接交给 {@link Messages} 的消息文本
     * @author xumanyi
     * @date 2026-08-14
     */
    private static String sized(String message) {
        if (message == null) return null;
        String trimmed = message.stripLeading();
        if (trimmed.regionMatches(true, 0, "<html", 0, 5)) return message;
        String html = message
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\n", "<br>");
        return "<html><body style='width:" + MESSAGE_WIDTH + "px'>" + html + "</body></html>";
    }

    /**
     * DialogWrapper 内容面板统一边距
     *
     * @return 统一的内容边距 Border
     * @author xumanyi
     * @date 2026-07-18
     */
    public static Border contentBorder() {
        return JBUI.Borders.empty(10, 14);
    }

    /**
     * 弹窗内警示文字前景色（亮 / 暗主题自适配的警示橙）
     *
     * @return 警示前景色
     * @author xumanyi
     * @date 2026-07-18
     */
    public static Color warnForeground() {
        return new JBColor(new Color(204, 120, 0), new Color(224, 158, 89));
    }

    /**
     * 信息提示框（单「确定」按钮，信息图标）
     *
     * @param parent  父组件（用于定位弹窗）
     * @param message 正文
     * @param title   标题（短名词短语）
     * @author xumanyi
     * @date 2026-07-18
     */
    public static void info(Component parent, String message, String title) {
        Messages.showInfoMessage(anchor(parent), sized(message), title);
    }

    /**
     * 警告提示框（单「确定」按钮，警告图标）
     *
     * @param parent  父组件
     * @param message 正文
     * @param title   标题
     * @author xumanyi
     * @date 2026-07-18
     */
    public static void warn(Component parent, String message, String title) {
        Messages.showWarningDialog(anchor(parent), sized(message), title);
    }

    /**
     * 错误提示框（单「确定」按钮，错误图标）
     *
     * @param parent  父组件；为 null 时弹无主窗口
     * @param message 正文
     * @param title   标题
     * @author xumanyi
     * @date 2026-07-18
     */
    public static void error(Component parent, String message, String title) {
        if (parent == null) {
            Messages.showErrorDialog(sized(message), title);
        } else {
            Messages.showErrorDialog(anchor(parent), sized(message), title);
        }
    }

    /**
     * 错误提示框（Project 版本，供 service / 无组件上下文使用）
     *
     * @param project 项目；可为 null
     * @param message 正文
     * @param title   标题
     * @author xumanyi
     * @date 2026-07-18
     */
    public static void error(Project project, String message, String title) {
        if (project == null) {
            Messages.showErrorDialog(sized(message), title);
        } else {
            Messages.showErrorDialog(project, sized(message), title);
        }
    }

    /**
     * 常规确认框：主操作 + 取消，默认聚焦主操作（回车即确认），问号图标。
     * 用于无风险的推进类确认（开始打包、继续流程等）。
     *
     * @param parent  父组件
     * @param title   标题（短名词短语）
     * @param message 正文
     * @param okText  主操作按钮文案（动词短语）
     * @return 用户点主操作返回 true；取消 / ESC 返回 false
     * @author xumanyi
     * @date 2026-07-18
     */
    public static boolean confirm(Component parent, String title, String message, String okText) {
        int choice = Messages.showDialog(anchor(parent), sized(message), title,
                new String[]{okText, "取消"}, 0, Messages.getQuestionIcon());
        return choice == 0;
    }

    /**
     * 危险操作确认框：主操作 + 取消，<b>默认聚焦取消</b>（回车不会触发危险动作），警告图标。
     * 用于不可逆 / 高风险操作：回滚、删除、跳过备份直接更新等。
     *
     * @param parent  父组件
     * @param title   标题
     * @param message 正文（首行应说清后果）
     * @param okText  主操作按钮文案（动词短语，如「确认回滚」「删除账号」）
     * @return 用户明确点主操作返回 true；取消 / ESC 返回 false
     * @author xumanyi
     * @date 2026-07-18
     */
    public static boolean confirmDanger(Component parent, String title, String message, String okText) {
        int choice = Messages.showDialog(anchor(parent), sized(message), title,
                new String[]{okText, "取消"}, 1, Messages.getWarningIcon());
        return choice == 0;
    }

    /**
     * 危险操作确认框（Project 版本）：弹窗挂 IDE 主窗口居中。
     *
     * <p>Component 版本的弹窗依附父组件定位，工具窗口较窄时长文案会被裁切出滚动条；
     * 正文较长的确认（如回滚）应使用本重载。</p>
     *
     * @param project 项目；可为 null（回退到无父窗口定位）
     * @param title   标题
     * @param message 正文（首行应说清后果）
     * @param okText  主操作按钮文案（动词短语，如「确认回滚」）
     * @return 用户明确点主操作返回 true；取消 / ESC 返回 false
     * @author xumanyi
     * @date 2026-08-14
     */
    public static boolean confirmDanger(Project project, String title, String message, String okText) {
        int choice = Messages.showDialog(project, sized(message), title,
                new String[]{okText, "取消"}, 1, Messages.getWarningIcon());
        return choice == 0;
    }

    /**
     * 多选项选择框（3 个及以上按钮，或需要自定义两按钮文案组合的场景）
     *
     * @param parent       父组件
     * @param title        标题
     * @param message      正文
     * @param options      按钮文案数组（主操作在前，中止 / 关闭类在最后；渲染顺序交给平台）
     * @param defaultIndex 默认聚焦按钮下标（危险场景应指向安全项）
     * @param icon         图标（{@link Messages#getQuestionIcon()} 等）；null 则无图标
     * @return 选中按钮下标；ESC / 关闭返回 -1
     * @author xumanyi
     * @date 2026-07-18
     */
    public static int choose(Component parent, String title, String message,
                             String[] options, int defaultIndex, Icon icon) {
        return Messages.showDialog(anchor(parent), sized(message), title, options, defaultIndex, icon);
    }

    /**
     * 多选项选择框（Project 版本，供 service 层后台流程在 EDT 上调用）
     *
     * @param project      项目；可为 null
     * @param title        标题
     * @param message      正文
     * @param options      按钮文案数组
     * @param defaultIndex 默认聚焦按钮下标
     * @param icon         图标
     * @return 选中按钮下标；ESC / 关闭返回 -1
     * @author xumanyi
     * @date 2026-07-18
     */
    public static int choose(Project project, String title, String message,
                             String[] options, int defaultIndex, Icon icon) {
        return Messages.showDialog(project, sized(message), title, options, defaultIndex, icon);
    }
}
