package com.flux.deploy.email;

import java.util.Objects;

/**
 * 通知邮件状态管理器（精简版）—— 只持有"当前选中模板名"
 *
 * <p>方案 C 之后，邮件 draft 不再保存"工作副本 HTML / 字段值字典"等状态：</p>
 * <ul>
 *   <li>所有字段（任务 / 客服 / 更新包 / 备份包 / 项目）打开弹窗时都不预填充，
 *       chip 都是空占位符；只有用户点「导入」按钮时才一次性拉数据填入</li>
 *   <li>任务 / 客服 由 {@code collectFieldValues()} 从主面板取；
 *       更新包 / 备份目录 / 项目 由 {@link DeployHistoryCache} 缓存拉取——
 *       两者都在「导入」按钮的回调里收集，弹窗里不允许编辑值的来源</li>
 * </ul>
 *
 * <p>所以本类只剩最小职责：跨弹窗打开记住用户选中的模板名 + 暴露 {@link EmailTemplateStore}
 * 给 UI 列模板。{@code DeployToolWindowPanel} 持有一个实例。</p>
 *
 * @author xumanyi
 * @date 2026-05-18
 */
public final class EmailDraftManager {

    private final EmailTemplateStore store;
    private String selectedTemplateName = EmailTemplateStore.DEFAULT_TEMPLATE_NAME;

    /**
     * 构造管理器
     *
     * @param store 模板存储；不允许 null
     * @author xumanyi
     * @date 2026-05-18
     */
    public EmailDraftManager(EmailTemplateStore store) {
        this.store = Objects.requireNonNull(store, "store");
    }

    /**
     * 暴露 store 给上层 UI（用于列模板名 / 新建 / 删除 / 模板维护子弹窗）
     *
     * @return 关联的模板存储
     * @author xumanyi
     * @date 2026-05-18
     */
    public EmailTemplateStore getStore() {
        return store;
    }

    /**
     * 当前选中模板名
     *
     * @return 选中模板名（默认 {@code default}）
     * @author xumanyi
     * @date 2026-05-18
     */
    public String getSelectedTemplateName() {
        return selectedTemplateName;
    }

    /**
     * 切换当前选中模板（跨弹窗打开保留选择）
     *
     * @param name 新模板名；null / 空字符串忽略
     * @author xumanyi
     * @date 2026-05-18
     */
    public void setSelectedTemplateName(String name) {
        if (name != null && !name.isBlank()) {
            this.selectedTemplateName = name;
        }
    }
}
