package com.flux.deploy.plugin.email;

import java.util.List;
import java.util.Map;

/**
 * 通知邮件弹窗 / draft 管理所需的"主面板运行时数据"供给接口
 *
 * <p>由 {@code DeployToolWindowPanel} 实现并在初始化时注入。
 * 把"如何从 InfoSectionPanel / TargetSectionPanel 拼凑字段值"的细节留在
 * 主面板里，让 {@link EmailDialog} 与 {@code EmailDraftManager} 不直接耦合
 * 这些 UI 类。</p>
 *
 * @author xumanyi
 * @date 2026-05-17
 */
public interface EmailRuntimeData {

    /**
     * 收集当前主面板里属于"插件可填字段"的最新值
     *
     * <p>返回 map 至少应该包含：{@code 任务} / {@code 客服}（来自主面板"任务号" /
     * "客服号"输入框）。备份包 / 更新包 / 项目 不在此处提供，由邮件弹窗的「导入」
     * 按钮自己从部署历史缓存拉取，跟本方法的返回值合并后一并写到 chip。</p>
     *
     * <p><b>调用时机</b>：仅在邮件弹窗的「导入」按钮点击回调里调用一次。打开弹窗时
     * <b>不</b>自动调用——任务 / 客服 chip 默认显示占位符，等用户点导入才跟其他
     * 字段一起填入。</p>
     *
     * @return 字段名 → 当前值；非 null
     * @author xumanyi
     * @date 2026-05-17
     */
    Map<String, String> collectFieldValues();

    /**
     * 当前 FTP 项目目录（如 {@code /开发/快尚时装/}）；项目未选时返回 null
     *
     * @return 项目目录或 null
     * @author xumanyi
     * @date 2026-05-17
     */
    String getCurrentProjectDir();

    /**
     * 主面板当前勾选的目标包文件名列表（按勾选顺序）
     *
     * <p>用于邮件弹窗的「导入选中包」按钮：把当前勾选追加到 draft 的
     * {@code ${更新包}} 锚点，去重不重复。未勾任何包时返回空列表。</p>
     *
     * @return 包文件名列表（永不为 null）
     * @author xumanyi
     * @date 2026-05-17
     */
    List<String> getCurrentSelectedPackageNames();
}
