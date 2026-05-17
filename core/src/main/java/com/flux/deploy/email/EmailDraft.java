package com.flux.deploy.email;

/**
 * 通知邮件正文的工作副本（in-memory only）
 *
 * <p>邮件正文不是模板渲染后丢掉的临时串，而是有状态的工作副本：
 * 用户在弹窗里手改的内容会回写本对象、部署成功后累积新增的"更新包 / FTP 路径"
 * 也会就地写回。它跨多次弹窗打开持续存在，直到出现下列任一事件被清空：</p>
 *
 * <ul>
 *   <li>项目目录变化（系统切换不算）</li>
 *   <li>部署完成时检测到任务号与 {@link #getOwnerTaskId()} 不一致</li>
 *   <li>主面板「重置」按钮按下</li>
 *   <li>IDE 重启（本类不持久化，自然丢失）</li>
 * </ul>
 *
 * <p><b>身份语义</b>：{@link #getOwnerTaskId()} 是 draft 创建时的任务号，
 * 与后续主面板上输入框里的任务号无关。即便用户在弹窗里把"任务号"字段
 * 改成别的字符串，身份也不会变 —— 身份由部署侧推动重置。</p>
 *
 * @author claude
 * @date 2026-05-17
 */
public final class EmailDraft {

    /** 富文本正文（HTML 字符串，含 {@code <span data-flux-field="...">} 锚点用于累积） */
    private String contentHtml;

    /** 邮件身份：draft 创建时的任务号；部署时任务号变化触发整体重建 */
    private final String ownerTaskId;

    /** 邮件归属项目目录（如 {@code /开发/快尚时装/}），项目切换时触发清空 */
    private final String ownerProjectDir;

    /**
     * 构造工作副本
     *
     * @param contentHtml     初始富文本（通常由 {@link EmailTemplateRenderer#renderInitial} 产出）
     * @param ownerTaskId     身份任务号（允许为空字符串或 null：首次打开尚未填任务号的情况）
     * @param ownerProjectDir 归属项目目录（允许为 null：尚未连接 FTP 时打开邮件）
     * @author claude
     * @date 2026-05-17
     */
    public EmailDraft(String contentHtml, String ownerTaskId, String ownerProjectDir) {
        this.contentHtml = contentHtml;
        this.ownerTaskId = ownerTaskId;
        this.ownerProjectDir = ownerProjectDir;
    }

    /**
     * 当前富文本内容
     *
     * @return HTML 串
     * @author claude
     * @date 2026-05-17
     */
    public String getContentHtml() {
        return contentHtml;
    }

    /**
     * 回写富文本内容
     *
     * <p>用户在弹窗里手改 / {@link EmailTemplateRenderer#appendAccumulate} 累积后调用。</p>
     *
     * @param contentHtml 新的 HTML 串
     * @author claude
     * @date 2026-05-17
     */
    public void setContentHtml(String contentHtml) {
        this.contentHtml = contentHtml;
    }

    /**
     * draft 身份任务号
     *
     * @return 创建时的任务号；可能为 null 或空字符串
     * @author claude
     * @date 2026-05-17
     */
    public String getOwnerTaskId() {
        return ownerTaskId;
    }

    /**
     * draft 归属项目目录
     *
     * @return 创建时的项目目录；可能为 null
     * @author claude
     * @date 2026-05-17
     */
    public String getOwnerProjectDir() {
        return ownerProjectDir;
    }
}
