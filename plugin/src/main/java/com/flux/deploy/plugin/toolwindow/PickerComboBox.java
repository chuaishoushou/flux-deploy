package com.flux.deploy.plugin.toolwindow;

import javax.swing.DefaultComboBoxModel;
import javax.swing.JComboBox;
import javax.swing.SwingUtilities;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;

/**
 * 视觉上完全等同 JComboBox（自带 L&amp;F 的边框、暗色背景、下拉箭头、高度），
 * 但点击下拉时不展开原生列表，而是执行外部传入的 popupHandler 弹出自定义弹窗。
 *
 * <p>用于源工程"工程"按钮、目标"项目"按钮等需要"看起来是下拉框、
 * 点开是自定义搜索/树弹窗"的场景，让它们与同面板的"产物""更新模式""系统"等
 * 真正下拉框/输入框的边框、高度、暗色背景视觉完全一致。</p>
 *
 * <p><b>拦截原生下拉的实现要点：</b><br>
 * IntelliJ L&amp;F 的 {@code BasicComboBoxUI.MouseHandler} 与下拉箭头按钮的
 * ActionListener 都直接调用 UI 自己的 {@code setPopupVisible(c, true)} →
 * {@code popup.show()} 流程，<b>完全绕过</b>了 JComboBox 的公开方法
 * （{@link #setPopupVisible(boolean)} / {@link #showPopup()}）。所以仅覆写
 * 这两个方法是拦不住原生面板的。<br>
 * 唯一稳定的拦截点是 {@link PopupMenuListener#popupMenuWillBecomeVisible}：
 * 原生面板一冒头就立即调 {@link #hidePopup()} 把它收回，紧跟着触发外部 popupHandler
 * 弹出自定义面板。两者间会有一瞬间的视觉闪烁，是当前架构下不可避免的代价。</p>
 *
 * @author xumanyi
 * @date 2026-04-30
 */
public class PickerComboBox extends JComboBox<String> {

    /** 点击下拉时触发的自定义弹窗回调（构造时注入，可为 {@code null}） */
    private final Runnable popupHandler;

    /**
     * 构造下拉触发型 ComboBox。
     *
     * @param initialText  初始展示文本（如 "点击选择工程"），{@code null} 视为空串
     * @param popupHandler 用户点击下拉时执行的回调，通常弹出自定义搜索/树弹窗；
     *                     传 {@code null} 时点击无反应（仅原生面板被抑制）
     */
    public PickerComboBox(String initialText, Runnable popupHandler) {
        super(new String[]{initialText == null ? "" : initialText});
        this.popupHandler = popupHandler;
        setEditable(false);
        // 必须 false：JComboBox 聚焦时键盘事件会尝试展开原生下拉，
        // 与本控件"原生下拉永不展示"的设计冲突。
        setFocusable(false);

        addPopupMenuListener(new PopupMenuListener() {
            @Override
            public void popupMenuWillBecomeVisible(PopupMenuEvent e) {
                // 必须 invokeLater：synchronously 调 hidePopup() 会和正在执行的
                // popup.show() 重入，可能抛 NPE 或留下"半开"状态；
                // 放到下一帧让原生 popup.show() 流程跑完再清理是最安全的做法。
                SwingUtilities.invokeLater(() -> {
                    hidePopup();
                    if (popupHandler != null) popupHandler.run();
                });
            }

            @Override public void popupMenuWillBecomeInvisible(PopupMenuEvent e) { }

            @Override public void popupMenuCanceled(PopupMenuEvent e) { }
        });
    }

    /**
     * 设置当前显示文本。沿用 setText 命名以最小侵入替换原 JButton 调用点。
     *
     * @param text 新文本，{@code null} 视为空串
     */
    public void setText(String text) {
        DefaultComboBoxModel<String> model = (DefaultComboBoxModel<String>) getModel();
        model.removeAllElements();
        model.addElement(text == null ? "" : text);
        setSelectedIndex(0);
    }

    /**
     * 获取当前显示文本。
     *
     * @return 当前选中项文本，未设置时返回空串
     */
    public String getText() {
        Object sel = getSelectedItem();
        return sel == null ? "" : sel.toString();
    }
}
