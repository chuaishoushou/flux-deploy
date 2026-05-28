package com.flux.deploy.plugin.email;

import com.flux.deploy.email.DeployHistoryCache;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 邮件模板"运行时变量值"快照构造器
 *
 * <p>从 {@link EmailRuntimeData}（主面板任务 / 客服）和 {@link DeployHistoryCache}
 * （部署历史里的更新包 / 备份目录）合并出一份 key → 值的 map，供浏览器编辑器
 * {@code GET /api/runtime-data} 拉取（即用户点「导入数据」按钮触发）。</p>
 *
 * <p>历史背景：此逻辑原先在 {@code EmailDialog.buildRuntimeValuesMap()}。删除 JCEF 弹窗后
 * 抽到这里，让 {@code DeployToolWindowPanel} 直接构造给 {@link EmailWebServerService} 用。</p>
 *
 * <p>返回 map 永远非 null；主面板和部署历史都没值时返回空 map，前端据此弹"暂无可导入内容"。</p>
 *
 * @author xumanyi
 * @date 2026-05-27
 */
public final class EmailRuntimeValuesBuilder {

    /** 更新包路径 / 备份包路径 多条 FTP 全路径之间的分隔符：纯 HTML 换行 */
    private static final String PATH_SEPARATOR = "<br>";

    /** 更新包文件名（不含路径）之间的分隔符：中文顿号 */
    private static final String NAME_SEPARATOR = "、";

    private final EmailRuntimeData runtimeData;
    private final DeployHistoryCache historyCache;

    public EmailRuntimeValuesBuilder(@NotNull EmailRuntimeData runtimeData,
                                     @NotNull DeployHistoryCache historyCache) {
        this.runtimeData = runtimeData;
        this.historyCache = historyCache;
    }

    /**
     * 拼装当前可用的变量值快照
     *
     * @return 变量名 → 值；当前没数据时返回空 map（永不 null）
     */
    public @NotNull Map<String, String> build() {
        Map<String, String> mainFields = runtimeData.collectFieldValues();
        String taskValue = mainFields == null ? "" : mainFields.getOrDefault("任务", "");
        String customerValue = mainFields == null ? "" : mainFields.getOrDefault("客服", "");
        String modeValue = mainFields == null ? "" : mainFields.getOrDefault("更新模式", "");
        // 更新模式（整包 / 增量）下拉框永远有值，不能算进 hasMainData，否则"暂无可导入内容"
        // 守卫将永远失效。它只在确有可导入数据时随 任务 / 客服 一并写出。
        boolean hasMainData = !taskValue.isBlank() || !customerValue.isBlank();

        String projectDir = runtimeData.getCurrentProjectDir();
        List<String> pkgs = List.of();
        List<String> backupFilePaths = List.of();
        String projectName = "";
        boolean hasDeployData = false;
        if (projectDir != null && !projectDir.isBlank()) {
            pkgs = historyCache.collectPackagePathsFor(projectDir);
            backupFilePaths = historyCache.collectBackupFilePathsFor(projectDir);
            if (!pkgs.isEmpty() || !backupFilePaths.isEmpty()) {
                projectName = extractProjectName(projectDir);
                hasDeployData = true;
            }
        }

        if (!hasMainData && !hasDeployData) {
            return new LinkedHashMap<>();
        }

        Map<String, String> updates = new LinkedHashMap<>();
        updates.put("任务", taskValue);
        updates.put("客服", customerValue);
        updates.put("更新模式", modeValue);
        if (hasDeployData) {
            String pkgNamesJoined = String.join(NAME_SEPARATOR, extractFileNames(pkgs));
            String backupNamesJoined = String.join(NAME_SEPARATOR, extractFileNames(backupFilePaths));
            updates.put("更新包", pkgNamesJoined);
            updates.put("备份包", backupNamesJoined);
            updates.put("更新包路径", String.join(PATH_SEPARATOR, pkgs));
            updates.put("备份包路径", String.join(PATH_SEPARATOR, backupFilePaths));
            updates.put("项目", projectName);
        }
        return updates;
    }

    /**
     * FTP 全路径 → 去重的文件名列表（保留首次出现顺序）
     */
    private static List<String> extractFileNames(List<String> paths) {
        if (paths == null || paths.isEmpty()) return List.of();
        List<String> names = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String p : paths) {
            if (p == null || p.isBlank()) continue;
            int slash = p.lastIndexOf('/');
            String name = slash >= 0 ? p.substring(slash + 1) : p;
            if (!name.isEmpty() && seen.add(name)) names.add(name);
        }
        return names;
    }

    /**
     * 从 FTP 项目目录提取项目名（如 {@code /开发/快尚时装/} → {@code 快尚时装}）
     */
    private static String extractProjectName(String projectDir) {
        if (projectDir == null || projectDir.isBlank()) return "";
        String trimmed = projectDir.replaceAll("/+$", "");
        int lastSlash = trimmed.lastIndexOf('/');
        return lastSlash >= 0 ? trimmed.substring(lastSlash + 1) : trimmed;
    }
}
