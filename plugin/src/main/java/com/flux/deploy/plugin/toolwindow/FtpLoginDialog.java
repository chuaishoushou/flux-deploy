package com.flux.deploy.plugin.toolwindow;

import com.flux.deploy.plugin.util.FluxDialogs;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.ui.components.JBPasswordField;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.FormBuilder;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;

/**
 * FTP 登录对话框
 *
 * <p>替代原先 JOptionPane 内嵌表单的登录框，遵循 {@link FluxDialogs} 统一弹窗规范：
 * 平台组件（DialogWrapper + FormBuilder）、统一边距与宽度、主操作动词文案「连接」。
 * 端口号在 {@link #doValidate()} 中校验为数字，替代原实现直接
 * {@code Integer.parseInt} 可能抛未捕获异常的问题。</p>
 *
 * @author xumanyi
 * @date 2026-07-18
 */
public class FtpLoginDialog extends DialogWrapper {

    private final JBTextField hostField = new JBTextField();
    private final JBTextField portField = new JBTextField("18080");
    private final JBTextField userField = new JBTextField();
    private final JBPasswordField passField = new JBPasswordField();

    /**
     * 构造 FTP 登录对话框
     *
     * @param project 当前 IDEA 项目（对话框定位用，可为 null）
     * @author xumanyi
     * @date 2026-07-18
     */
    public FtpLoginDialog(@Nullable Project project) {
        super(project, false);
        setTitle("FTP 登录");
        setOKButtonText("连接");
        setCancelButtonText("取消");
        init();
    }

    /**
     * 构建表单面板：主机 / 端口 / 用户名 / 密码
     *
     * @return 表单面板
     * @author xumanyi
     * @date 2026-07-18
     */
    @Nullable
    @Override
    protected JComponent createCenterPanel() {
        JPanel form = FormBuilder.createFormBuilder()
                .addLabeledComponent("主机：", hostField)
                .addLabeledComponent("端口：", portField)
                .addLabeledComponent("用户名：", userField)
                .addLabeledComponent("密码：", passField)
                .getPanel();
        form.setBorder(FluxDialogs.contentBorder());
        form.setPreferredSize(new Dimension(FluxDialogs.WIDTH_M - 120, form.getPreferredSize().height));
        return form;
    }

    /**
     * 默认聚焦主机输入框
     *
     * @return 主机输入框
     * @author xumanyi
     * @date 2026-07-18
     */
    @Nullable
    @Override
    public JComponent getPreferredFocusedComponent() {
        return hostField;
    }

    /**
     * 校验表单：主机 / 用户名非空，端口为 1-65535 的数字
     *
     * @return 校验错误信息；通过时返回 null
     * @author xumanyi
     * @date 2026-07-18
     */
    @Nullable
    @Override
    protected ValidationInfo doValidate() {
        if (getHost().isEmpty()) {
            return new ValidationInfo("请输入主机地址", hostField);
        }
        String portText = portField.getText() == null ? "" : portField.getText().trim();
        try {
            int p = Integer.parseInt(portText);
            if (p < 1 || p > 65535) {
                return new ValidationInfo("端口需在 1-65535 之间", portField);
            }
        } catch (NumberFormatException e) {
            return new ValidationInfo("端口必须是数字", portField);
        }
        if (getUsername().isEmpty()) {
            return new ValidationInfo("请输入用户名", userField);
        }
        return null;
    }

    /**
     * 获取输入的主机地址（已 trim）
     *
     * @return 主机地址
     * @author xumanyi
     * @date 2026-07-18
     */
    public String getHost() {
        return hostField.getText() == null ? "" : hostField.getText().trim();
    }

    /**
     * 获取输入的端口号（调用前提：doValidate 已通过）
     *
     * @return 端口号
     * @author xumanyi
     * @date 2026-07-18
     */
    public int getPort() {
        return Integer.parseInt(portField.getText().trim());
    }

    /**
     * 获取输入的用户名（已 trim）
     *
     * @return 用户名
     * @author xumanyi
     * @date 2026-07-18
     */
    public String getUsername() {
        return userField.getText() == null ? "" : userField.getText().trim();
    }

    /**
     * 获取输入的密码
     *
     * @return 密码明文
     * @author xumanyi
     * @date 2026-07-18
     */
    public String getPassword() {
        return new String(passField.getPassword());
    }
}
