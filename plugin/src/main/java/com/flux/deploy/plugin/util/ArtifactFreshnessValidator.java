package com.flux.deploy.plugin.util;

import com.flux.deploy.plugin.model.DeployMode;

import javax.swing.JOptionPane;
import java.awt.Component;
import java.util.List;

/**
 * 编译产物新鲜度 UI 校验：在 {@link ArtifactPresenceValidator} 通过后调用，
 * 把 {@link ArtifactFreshnessChecker} 的结果转成"取消 / 继续"二选一弹窗。
 *
 * <p>触发条件：检测到任一 .java 源比对应编译产物（.class 或 jar）新。弹窗默认按钮为"取消"，
 * 防止用户回车直接放行旧产物。</p>
 *
 * @author xumanyi
 * @date 2026-05-19
 */
public final class ArtifactFreshnessValidator {

    private ArtifactFreshnessValidator() {}

    /**
     * 校验结果，供调用方区分"用户取消"和"产物本身就是新的"以决定是否写日志。
     *
     * @author xumanyi
     * @date 2026-05-19
     */
    public enum Decision {
        /** 校验通过，产物足够新（或退化场景），无需弹窗 */
        FRESH,
        /** 检测到过期源，用户确认继续部署 */
        USER_CONFIRMED_STALE,
        /** 检测到过期源，用户取消部署 */
        CANCELED
    }

    /**
     * 校验结果包：决策 + 过期源列表（即便用户继续也带回来便于日志记录）。
     *
     * @author xumanyi
     * @date 2026-05-19
     */
    public static final class Outcome {
        public final Decision decision;
        public final List<String> staleSources;

        Outcome(Decision decision, List<String> staleSources) {
            this.decision = decision;
            this.staleSources = staleSources;
        }
    }

    /**
     * 执行新鲜度校验，stale 时弹"取消 / 继续部署"二选一对话框。
     *
     * @param parent           对话框父组件（一般传 {@code DeployToolWindowPanel.this}）
     * @param mode             部署模式
     * @param modulePath       模块根绝对路径
     * @param artifactFileName 全量模式产物文件名
     * @param changedFiles     增量模式勾选清单
     * @return 校验决策与过期源列表
     * @author xumanyi
     * @date 2026-05-19
     */
    public static Outcome verifyOrPrompt(Component parent,
                                          DeployMode mode,
                                          String modulePath,
                                          String artifactFileName,
                                          List<String> changedFiles) {
        ArtifactFreshnessChecker.Result r = ArtifactFreshnessChecker.check(
                mode, modulePath, artifactFileName, changedFiles);
        if (r.isFresh()) {
            return new Outcome(Decision.FRESH, List.of());
        }

        Object[] options = {"取消", "继续"};
        int choice = JOptionPane.showOptionDialog(
                parent,
                "检测到编译文件时间早于更新文件，请确认是否已执行 maven 编译？",
                "请确认编译状态",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.QUESTION_MESSAGE,
                null,
                options,
                options[0]);

        Decision d = (choice == 1) ? Decision.USER_CONFIRMED_STALE : Decision.CANCELED;
        return new Outcome(d, r.staleSources);
    }
}
