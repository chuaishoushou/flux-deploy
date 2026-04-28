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
     */
    public static class State {
        /** 上次使用的操作人 */
        public String lastOperator;
        /** 上次选择的 FTP 项目 */
        public String lastProject;
        /** 上次选择的 FTP 系统 */
        public String lastSystem;
        /** 上次填写的任务号 */
        public String lastTaskId;
        /** 上次填写的客服号 */
        public String lastCustomerId;
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
