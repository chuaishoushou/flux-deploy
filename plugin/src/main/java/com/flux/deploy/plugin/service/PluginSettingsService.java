package com.flux.deploy.plugin.service;

import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * 插件设置持久化服务
 *
 * <p>按项目维度保存上次使用的配置，IDE 重启后恢复。
 * 存储在项目的 .idea/fluxDeploySettings.xml 中。</p>
 *
 * @author xumanyi
 * @date 2026-03-27
 */
@Service(Service.Level.PROJECT)
@State(name = "FluxDeploySettings", storages = @Storage("fluxDeploySettings.xml"))
public final class PluginSettingsService implements PersistentStateComponent<PluginSettingsService.State> {

    private State state = new State();

    /**
     * 持久化状态数据类
     *
     * <p>设计原则：仅持久化"对所有任务都通用、不会过期、不会误用"的输入。</p>
     *
     * <p>反例（曾经在这里、已删除）：lastTaskId / lastCustomerId 是按任务粒度变化的输入，
     * 自动恢复反而会误提交错误任务号；lastProject / lastSystem 一旦自动恢复就要求
     * "打开插件即触发 FTP 连接"，与"懒加载、用户主动连接"的体验冲突；
     * customBackupRoots 让自定义备份地址悄悄持久化，
     * 用户某次随手选错后所有后续部署都会沿用错的路径，
     * 故改为 InfoSectionPanel 内 session 级覆盖（切系统/项目自动重置）。</p>
     */
    public static class State {
        /** 上次使用的操作人（FLUX 内同一开发常年同名，缓存有意义） */
        public String lastOperator;

        /**
         * 上次发邮件时填的收件人（顾问名字相对固定，跨项目跨任务记忆有意义）。
         *
         * <p>仅作为「通知邮件」弹窗的 {@code ${收件人}} 占位符初始值；
         * 用户在弹窗里改了之后会回写到这里供下次使用。
         * 不参与版本记录 / 部署流程，无需校验。</p>
         */
        public String lastEmailRecipient;
    }

    /**
     * {@inheritDoc}
     *
     * @return 当前持久化状态
     * @author xumanyi
     * @date 2026-03-27
     */
    @Override
    public @Nullable State getState() {
        return state;
    }

    /**
     * {@inheritDoc}
     *
     * @param state 从 XML 反序列化的状态
     * @author xumanyi
     * @date 2026-03-27
     */
    @Override
    public void loadState(@NotNull State state) {
        this.state = state;
    }
}
