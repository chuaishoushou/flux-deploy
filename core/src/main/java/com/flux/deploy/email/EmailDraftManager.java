package com.flux.deploy.email;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 通知邮件 draft 的状态机：协调"创建 / 累积 / 重建 / 清空"
 *
 * <p>本类被 {@code DeployToolWindowPanel} 持有一个实例（per-panel = per-project），
 * 通过外部钩子接收事件：</p>
 *
 * <ul>
 *   <li>{@link #onProjectMaybeChanged} —— FTP 项目目录变化时调用；项目真变了才清 draft</li>
 *   <li>{@link #onReset} —— 主面板"重置"按钮按下时调用</li>
 *   <li>{@link #onDeploySucceeded} —— "打包并上传"成功后调用，按任务号身份决定累积 or 重建</li>
 *   <li>{@link #getDraftForDisplay} —— 弹窗打开时调用，无 draft 自动创建</li>
 *   <li>{@link #setDraftContent} —— 弹窗关闭时回写用户手改</li>
 *   <li>{@link #saveCurrentDraftAsTemplate} —— 「保存到模板」按钮按下时调用</li>
 * </ul>
 *
 * <p>不依赖 IDE Platform，可作为 core 模块的纯类被测试驱动。</p>
 *
 * @author claude
 * @date 2026-05-17
 */
public final class EmailDraftManager {

    /** 部署成功后追加更新包用的分隔符（顿号，跟邮件例子格式一致） */
    public static final String UPDATE_PKG_SEPARATOR = "、";

    /** 部署成功后追加 FTP 路径用的分隔符（HTML 换行；粘到 Outlook 后保持每行一条） */
    public static final String FTP_PATH_SEPARATOR = "<br>";

    /**
     * 含 HTML 片段的字段集合：渲染时不做 escape，让 {@code <br>} / 顿号正常生效
     *
     * <p>主面板预填多条目时已经在 caller 端用 {@link #UPDATE_PKG_SEPARATOR}
     * 拼好，必须按 raw HTML 注入到锚点中。</p>
     *
     * <p>FTP版本来源 不在绑定变量集合内（模板里是字面值，用户手填），所以也不在
     * 这里出现。</p>
     */
    private static final Set<String> RAW_HTML_KEYS = Set.of("更新包");

    /** 内置模板/已保存模板的存储 */
    private final EmailTemplateStore store;

    /** 当前 draft；首次打开 / 项目切换 / 重置 / 任务号变更后为 null */
    private EmailDraft draft;

    /** 上次记录的项目目录，用于 {@link #onProjectMaybeChanged} 比对差异 */
    private String lastSeenProjectDir;

    /** 用户当前在弹窗里选中的模板名（默认 {@link EmailTemplateStore#DEFAULT_TEMPLATE_NAME}） */
    private String selectedTemplateName = EmailTemplateStore.DEFAULT_TEMPLATE_NAME;

    /**
     * 构造管理器
     *
     * @param store 模板存储；不允许 null
     * @author claude
     * @date 2026-05-17
     */
    public EmailDraftManager(EmailTemplateStore store) {
        this.store = Objects.requireNonNull(store, "store");
    }

    /**
     * 检测项目目录是否变化；变化则清 draft
     *
     * <p>首次调用（{@code lastSeenProjectDir == null}）只记录不清空，避免插件刚启动
     * 就误把第一个上下文当成"切换"。后续每次调用都做对比。</p>
     *
     * @param currentProjectDir 当前 FTP 项目目录（如 {@code /开发/快尚时装/}），可为 null
     * @author claude
     * @date 2026-05-17
     */
    public void onProjectMaybeChanged(String currentProjectDir) {
        if (lastSeenProjectDir == null) {
            lastSeenProjectDir = currentProjectDir;
            return;
        }
        if (!Objects.equals(lastSeenProjectDir, currentProjectDir)) {
            draft = null;
            lastSeenProjectDir = currentProjectDir;
        }
    }

    /**
     * 主面板"重置"按钮回调：清空 draft
     *
     * <p>{@code lastSeenProjectDir} 不清——重置只是丢弃这次的工作副本，
     * 不改变"当前在哪个项目"的认知。</p>
     *
     * @author claude
     * @date 2026-05-17
     */
    public void onReset() {
        draft = null;
    }

    /**
     * 部署成功后累积或重建 draft
     *
     * <p>判定规则：</p>
     * <ul>
     *   <li>draft 为空，或 draft 的身份任务号与本次 {@code taskId} 不同 →
     *       推倒重建：用当前选中模板 + 当前字段值渲染出新 draft</li>
     *   <li>身份任务号一致 → 累积：在 {@code 更新包} 和 {@code FTP版本来源}
     *       两个锚点上追加本次的新条目；其他字段（包括用户在弹窗里手改的）保持不变</li>
     * </ul>
     *
     * @param taskId            本次部署的任务号（draft 身份依据）
     * @param projectDir        本次部署的项目目录
     * @param newPackageNames   本次部署的更新包文件名列表（按顺序追加）
     * @param newFtpPaths       本次部署的 FTP 完整路径列表（按顺序追加）
     * @param currentFieldValues 渲染初始 draft 时用的字段值字典（仅在重建路径用到）
     * @author claude
     * @date 2026-05-17
     */
    public void onDeploySucceeded(String taskId, String projectDir,
                                  List<String> newPackageNames, List<String> newFtpPaths,
                                  Map<String, String> currentFieldValues) {
        boolean needRebuild = (draft == null) || !Objects.equals(draft.getOwnerTaskId(), taskId);
        if (needRebuild) {
            String template = store.loadOrDefault(selectedTemplateName);
            String content = EmailTemplateRenderer.renderInitial(
                    template, currentFieldValues, RAW_HTML_KEYS);
            draft = new EmailDraft(content, taskId, projectDir);
            lastSeenProjectDir = projectDir;
            return;
        }
        // 累积分支：本次部署成功的更新包名追加到 ${更新包} 锚点，与已存在条目去重——
        // 避免"打开邮件时主面板预填了包 a / b"和"部署成功后又追加 a / b"造成重复。
        // FTP 路径不再累积：模板里的 FTP 版本来源是字面值，由用户手填，无锚点可定位。
        String content = draft.getContentHtml();
        if (newPackageNames != null) {
            for (String pkg : newPackageNames) {
                content = EmailTemplateRenderer.appendAccumulate(
                        content, "更新包", pkg, UPDATE_PKG_SEPARATOR, true, true);
            }
        }
        draft.setContentHtml(content);
    }

    /**
     * 弹窗打开时调用：返回当前 draft 内容；无 draft 则用当前字段值创建一个
     *
     * <p>"无 draft" 场景：插件刚启动 / 项目刚切 / 上次部署后被重置。此时
     * 用当前主面板的字段值渲染一个新 draft，身份任务号取自 values["任务号"]。</p>
     *
     * @param currentFieldValues 当前主面板字段值
     * @param currentProjectDir  当前 FTP 项目目录（赋予新建 draft 的 ownerProjectDir）
     * @return 富文本 HTML 串（永远非 null）
     * @author claude
     * @date 2026-05-17
     */
    public String getDraftForDisplay(Map<String, String> currentFieldValues,
                                     String currentProjectDir) {
        // 内存 draft 仍含 v1 时代的老占位符（${项目名} / ${资源来源} / ${标题} 等）
        // → 已经过期，丢掉重渲。配合 EmailTemplateStore.ensureInitialized 的 fuzzy
        // 升级，确保升级到新版后用户开邮件就看到新模板。
        if (draft != null
                && EmailTemplateStore.containsAnyLegacyPlaceholder(draft.getContentHtml())) {
            draft = null;
        }
        if (draft == null) {
            String template = store.loadOrDefault(selectedTemplateName);
            String content = EmailTemplateRenderer.renderInitial(
                    template, currentFieldValues, RAW_HTML_KEYS);
            String taskId = currentFieldValues == null
                    ? ""
                    : currentFieldValues.getOrDefault("任务号", "");
            draft = new EmailDraft(content, taskId, currentProjectDir);
            if (lastSeenProjectDir == null) {
                lastSeenProjectDir = currentProjectDir;
            }
        }
        return draft.getContentHtml();
    }

    /**
     * 弹窗关闭时调用：把用户在编辑区的最终文本回写 draft
     *
     * <p>draft 为空（弹窗在没创建 draft 的情况下被关闭，理论上不会发生）→ 静默忽略。</p>
     *
     * @param contentHtml 编辑区当前富文本
     * @author claude
     * @date 2026-05-17
     */
    public void setDraftContent(String contentHtml) {
        if (draft != null) {
            draft.setContentHtml(contentHtml);
        }
    }

    /**
     * 「保存到模板」：把当前编辑区内容反向占位符化后写入指定模板文件
     *
     * <p>调用流程：</p>
     * <ol>
     *   <li>调用方先调 {@link #setDraftContent} 回写最新编辑区内容（或直接传入此处）</li>
     *   <li>{@link EmailTemplateRenderer#toTemplateSource} 把所有
     *       {@code <span class="flux-field-X">...</span>} 替换为 {@code ${X}}</li>
     *   <li>写入 {@code <templateName>.html}（已存在则覆盖）</li>
     * </ol>
     *
     * @param contentHtml  待保存的富文本（通常是编辑区当前内容）
     * @param templateName 保存到的模板名（同时切换 {@link #selectedTemplateName}）
     * @throws java.io.IOException I/O 失败
     * @author claude
     * @date 2026-05-17
     */
    public void saveCurrentDraftAsTemplate(String contentHtml, String templateName)
            throws java.io.IOException {
        String source = EmailTemplateRenderer.toTemplateSource(contentHtml);
        store.save(templateName, source);
        selectedTemplateName = templateName;
    }

    /**
     * 当前选中模板名
     *
     * @return 选中模板名（默认 {@code default}）
     * @author claude
     * @date 2026-05-17
     */
    public String getSelectedTemplateName() {
        return selectedTemplateName;
    }

    /**
     * 切换当前选中模板（不重渲染已存在的 draft）
     *
     * <p>已有 draft 的情况下切换模板只影响"下次重建时用哪个模板"，
     * 当前编辑区内容保持。若用户希望立即刷新到新模板，应该先重置再切。</p>
     *
     * @param name 新模板名
     * @author claude
     * @date 2026-05-17
     */
    public void setSelectedTemplateName(String name) {
        if (name != null && !name.isBlank()) {
            this.selectedTemplateName = name;
        }
    }

    /**
     * 暴露 store 给上层 UI（用于列模板名 / 新建 / 删除）
     *
     * @return 关联的模板存储
     * @author claude
     * @date 2026-05-17
     */
    public EmailTemplateStore getStore() {
        return store;
    }

    /**
     * 测试 / 调试用：当前 draft 是否存在
     *
     * @return draft 非空返回 true
     * @author claude
     * @date 2026-05-17
     */
    public boolean hasDraft() {
        return draft != null;
    }
}
