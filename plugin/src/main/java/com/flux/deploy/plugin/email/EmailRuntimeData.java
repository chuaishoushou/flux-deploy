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
 * @author claude
 * @date 2026-05-17
 */
public interface EmailRuntimeData {

    /**
     * 收集当前主面板的所有可作为占位符的字段值
     *
     * <p>返回 map 至少应该包含设计文档里定义的"插件自动填占位符"集合：
     * 任务号 / 客服编号 / 开发 / 项目名 / 备份包 / 更新包 / FTP版本来源 / 日期。
     * 还应该把 PluginSettingsService 缓存的 {@code lastEmailRecipient}
     * 写到 key {@code 收件人} 上，作为初值。</p>
     *
     * @return 字段名 → 当前值；非 null
     * @author claude
     * @date 2026-05-17
     */
    Map<String, String> collectFieldValues();

    /**
     * 当前 FTP 项目目录（如 {@code /开发/快尚时装/}）；项目未选时返回 null
     *
     * @return 项目目录或 null
     * @author claude
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
     * @author claude
     * @date 2026-05-17
     */
    List<String> getCurrentSelectedPackageNames();
}
