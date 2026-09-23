package com.flux.deploy.plugin.service;

import com.flux.deploy.csv.CsvMergePlan;
import com.flux.deploy.csv.CsvTableKeyRegistry;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vcs.changes.Change;
import com.intellij.openapi.vcs.changes.ChangeListManager;
import com.intellij.openapi.vcs.changes.ContentRevision;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.search.FilenameIndex;
import com.intellij.psi.search.GlobalSearchScope;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * CSV 增量合并输入收集器（插件层）
 *
 * <p>部署启动时（后台线程）收集 CSV 行级增量合并所需的全部输入，
 * 组装成 {@link CsvMergePlan} 注入 core 打包流程：</p>
 * <ul>
 *   <li>基线内容：对变更清单中 git 状态为 M 的 .csv，经 {@code ChangeListManager}
 *       取其 HEAD 版内容（改动前基线）。A（新文件）整份放入、D（删除）移除条目、
 *       R（移动）按删+加处理，均无需基线</li>
 *   <li>主键字典：经 {@code FilenameIndex} 定位工程内全部 {@code tablefieldlist*.csv}
 *       并解析为 {@link CsvTableKeyRegistry}</li>
 * </ul>
 *
 * <p>稳定性约定：本类任何一步失败都只降级不报错——返回 null 或不完整计划，
 * 对应 CSV 自动退回原有整份覆盖行为，部署流程绝不因收集失败而中断。</p>
 *
 * @author xumanyi
 * @date 2026-07-10
 */
public final class CsvMergePlanBuilder {

    private CsvMergePlanBuilder() {}

    /**
     * 收集 CSV 增量合并计划
     *
     * <p>须在后台线程调用（内部有 VCS 内容读取与索引查询）。</p>
     *
     * @param project      IDEA 项目
     * @param modulePath   模块根目录（changedFiles 相对路径的基准）
     * @param changedFiles 变更文件清单（可含 git status 前缀 "M  path" 等）
     * @param logCallback  日志回调
     * @return 合并计划；无 CSV 变更或收集失败时返回 null（CSV 保持整份覆盖）
     * @author xumanyi
     * @date 2026-07-10
     */
    public static CsvMergePlan build(Project project, String modulePath,
                                     List<String> changedFiles, Consumer<String> logCallback) {
        try {
            return doBuild(project, modulePath, changedFiles, logCallback);
        } catch (Exception e) {
            // 防御性兜底：收集失败只降级为整份覆盖，不中断部署
            logCallback.accept("WARN  [CSV增量] 合并输入收集失败，本次 CSV 将按整份覆盖处理: "
                    + e.getMessage());
            return null;
        }
    }

    /**
     * 收集主流程（异常由外层 {@link #build} 统一兜底）
     *
     * @param project      IDEA 项目
     * @param modulePath   模块根目录
     * @param changedFiles 变更文件清单
     * @param logCallback  日志回调
     * @return 合并计划；无可合并 CSV 时返回 null
     * @author xumanyi
     * @date 2026-07-10
     */
    private static CsvMergePlan doBuild(Project project, String modulePath,
                                        List<String> changedFiles, Consumer<String> logCallback) {
        List<String> csvPaths = pickModifiedCsvPaths(changedFiles);
        if (csvPaths.isEmpty()) {
            return null;
        }

        // 1. 逐文件取 git HEAD 版基线内容
        Map<String, String> baseContents = new HashMap<>();
        ChangeListManager clm = ChangeListManager.getInstance(project);
        LocalFileSystem lfs = LocalFileSystem.getInstance();
        for (String rel : csvPaths) {
            Path abs = Path.of(rel).isAbsolute() ? Path.of(rel) : Path.of(modulePath, rel);
            String fileName = abs.getFileName().toString();
            try {
                VirtualFile vf = lfs.findFileByNioFile(abs);
                Change change = vf != null ? clm.getChange(vf) : null;
                ContentRevision before = change != null ? change.getBeforeRevision() : null;
                String content = before != null ? before.getContent() : null;
                if (content == null || content.isBlank()) {
                    logCallback.accept("INFO  [CSV增量] " + fileName
                            + " 未取到改动前基线内容，将按整份覆盖处理");
                    continue;
                }
                baseContents.put(CsvMergePlan.normalizePathKey(abs), content);
            } catch (Exception e) {
                logCallback.accept("WARN  [CSV增量] " + fileName + " 读取基线内容失败，将按整份覆盖处理: "
                        + e.getMessage());
            }
        }
        if (baseContents.isEmpty()) {
            return null;
        }

        // 2. 加载业务主键字典（字典为空时引擎会逐文件按"主键未识别"兜底并留日志）
        CsvTableKeyRegistry registry = loadRegistry(project, logCallback);

        logCallback.accept("INFO  [CSV增量] 已启用 CSV 行级增量更新：" + baseContents.size()
                + " 个 CSV 带改动前基线，主键字典覆盖 " + registry.tableCount() + " 张表");
        return new CsvMergePlan(registry, baseContents);
    }

    /**
     * 从变更清单中挑出"git 状态为 M 的部署内 .csv"相对路径
     *
     * @param changedFiles 变更文件清单（可含 git status 前缀）
     * @return 需要增量合并的 .csv 路径列表
     * @author xumanyi
     * @date 2026-07-10
     */
    private static List<String> pickModifiedCsvPaths(List<String> changedFiles) {
        List<String> result = new ArrayList<>();
        if (changedFiles == null) {
            return result;
        }
        for (String raw : changedFiles) {
            String status = "";
            String path = raw;
            if (raw.length() > 2 && raw.charAt(1) == ' ') {
                status = String.valueOf(raw.charAt(0));
                path = raw.substring(raw.indexOf(' ')).trim();
            }
            if (!path.toLowerCase(Locale.ROOT).endsWith(".csv")) {
                continue;
            }
            // src/test/** 不进部署包；仅 M（修改）需要行级合并
            if (path.contains("src/test/") || !"M".equals(status)) {
                continue;
            }
            result.add(path);
        }
        return result;
    }

    /**
     * 加载工程内全部 tablefieldlist*.csv，解析为业务主键字典
     *
     * <p>索引查询在 ReadAction 中执行；索引未就绪（dumb mode）等异常由
     * 外层兜底，表现为字典为空 → 引擎按"主键未识别"逐文件整份覆盖。</p>
     *
     * @param project     IDEA 项目
     * @param logCallback 日志回调
     * @return 主键字典（可能为空字典）
     * @author xumanyi
     * @date 2026-07-10
     */
    private static CsvTableKeyRegistry loadRegistry(Project project, Consumer<String> logCallback) {
        List<String> contents = ReadAction.compute(() -> {
            List<String> list = new ArrayList<>();
            Collection<VirtualFile> csvFiles = FilenameIndex.getAllFilesByExt(
                    project, "csv", GlobalSearchScope.projectScope(project));
            for (VirtualFile vf : csvFiles) {
                if (!vf.getName().toLowerCase(Locale.ROOT).startsWith("tablefieldlist")) {
                    continue;
                }
                try {
                    list.add(new String(vf.contentsToByteArray(), StandardCharsets.UTF_8));
                } catch (Exception ignored) {
                    // 单份读取失败不影响其余清单
                }
            }
            return list;
        });
        CsvTableKeyRegistry registry = CsvTableKeyRegistry.load(contents);
        if (registry.isEmpty()) {
            logCallback.accept("WARN  [CSV增量] 工程内未找到可用的 tablefieldlist-*.csv，"
                    + "CSV 将按整份覆盖处理");
        }
        return registry;
    }
}
