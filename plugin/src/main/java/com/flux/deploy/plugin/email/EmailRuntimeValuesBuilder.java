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
 * （部署历史里的更新包 / 备份目录）合并出一份 key → 值的 map，供 JCEF 邮件编辑器
 * 点「导入数据」按钮时经 JS 桥（{@code op=runtimeData}）拉取。</p>
 *
 * <p>历史背景：此逻辑原先在 {@code EmailDialog.buildRuntimeValuesMap()}，抽到这里后由
 * {@code DeployToolWindowPanel} 构造，交给 {@link EmailJcefDialog} 的桥后端调用。</p>
 *
 * <p>返回 map 永远非 null；主面板和部署历史都没值时返回空 map，前端据此弹"暂无可导入内容"。</p>
 *
 * @author xumanyi
 * @date 2026-05-27
 */
public final class EmailRuntimeValuesBuilder {

    /** 更新包地址（多条 FTP 全路径）/ 备份包地址（多条备份目录）之间的分隔符：纯 HTML 换行 */
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
        List<String> backupDirs = List.of();
        String projectName = "";
        boolean hasDeployData = false;
        if (projectDir != null && !projectDir.isBlank()) {
            pkgs = historyCache.collectPackagePathsFor(projectDir);
            backupFilePaths = historyCache.collectBackupFilePathsFor(projectDir);
            backupDirs = historyCache.collectBackupDirsFor(projectDir);
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
            updates.put("FTP版本来源", commonDirOf(pkgs));
            updates.put("更新包", pkgNamesJoined);
            updates.put("更新jar包", joinNamesByExt(pkgs, ".jar"));
            updates.put("更新war包", joinNamesByExt(pkgs, ".war"));
            // Vue 前端模块更新包（{content}_{模块号}.zip）；模板未引用该变量时不展示
            updates.put("更新vue包", joinNamesByExt(pkgs, ".zip"));
            updates.put("更新包地址", String.join(PATH_SEPARATOR, pkgs));
            updates.put("备份包", backupNamesJoined);
            updates.put("备份包地址", String.join(PATH_SEPARATOR, backupDirs));
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
     * 从更新包完整路径列表中筛选指定扩展名的包名，顿号拼接
     *
     * <p>先复用 {@link #extractFileNames} 取去重文件名，再按扩展名（忽略大小写）筛选。
     * 用于邮件模板按类型细分的 {@code ${更新jar包}} / {@code ${更新war包}} 变量；
     * 非该类型的包不计入（仍体现在含全部包名的 {@code ${更新包}} 里）。</p>
     *
     * @param paths 包的完整 FTP 路径列表（含文件名）
     * @param ext   目标扩展名（小写、含点，如 {@code .jar}）
     * @return 命中该扩展名的包名（顿号拼接、去重）；无命中时返回空串
     * @author xumanyi
     * @date 2026-06-02
     */
    private static String joinNamesByExt(List<String> paths, String ext) {
        List<String> matched = new ArrayList<>();
        for (String name : extractFileNames(paths)) {
            if (name.toLowerCase().endsWith(ext)) {
                matched.add(name);
            }
        }
        return String.join(NAME_SEPARATOR, matched);
    }

    /**
     * 计算多个更新包路径的最长公共目录层级（不含包名）
     *
     * <p>各包的完整 FTP 路径先去掉文件名得到所在目录，再按 {@code /} 分段求最长公共前缀：
     * 单包时即该包所在目录；多包同目录时就是那个目录；多包分散在不同目录时退到最近的公共
     * 上级目录。用于邮件模板的 {@code ${FTP版本来源}} 变量——给顾问一个统一的"从哪个
     * 目录取版本"，避免逐包列长路径。</p>
     *
     * @param paths 包的完整 FTP 路径列表（含文件名）
     * @return 公共目录（含尾部 {@code /}）；无有效路径时返回空串
     * @author xumanyi
     * @date 2026-06-02
     */
    private static String commonDirOf(List<String> paths) {
        if (paths == null || paths.isEmpty()) return "";
        List<String[]> dirSegments = new ArrayList<>();
        for (String p : paths) {
            if (p == null || p.isBlank()) continue;
            int slash = p.lastIndexOf('/');
            String dir = slash >= 0 ? p.substring(0, slash) : "";
            dirSegments.add(dir.split("/", -1));
        }
        if (dirSegments.isEmpty()) return "";
        String[] first = dirSegments.get(0);
        int commonLen = first.length;
        for (String[] segs : dirSegments) {
            commonLen = Math.min(commonLen, segs.length);
            for (int i = 0; i < commonLen; i++) {
                if (!first[i].equals(segs[i])) {
                    commonLen = i;
                    break;
                }
            }
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < commonLen; i++) {
            if (i > 0) sb.append('/');
            sb.append(first[i]);
        }
        String common = sb.toString();
        if (common.isEmpty()) return "/";
        return common + "/";
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
