package com.flux.deploy.plugin.service;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.vcs.changes.Change;
import com.intellij.openapi.vcs.changes.ChangeListManager;
import com.intellij.openapi.vfs.VirtualFile;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Git 变更文件检测
 *
 * <p>使用 IDEA Git4Idea API 获取工作区中已修改但未提交的文件。
 * 检测范围限定在指定模块根目录下，并过滤出可部署的文件类型
 * （.java, .xml, .properties, .yml 等）。</p>
 *
 * @author xumanyi
 * @date 2026-03-27
 */
public class GitChangeDetector {

    private GitChangeDetector() {}

    /**
     * 检测指定模块根目录下的 git 变更文件
     *
     * @param project    IDEA 项目
     * @param moduleRoot 模块根目录
     * @return 变更文件的相对路径列表
     * @author xumanyi
     * @date 2026-03-27
     */
    public static List<String> detectChangedFiles(Project project, VirtualFile moduleRoot) {
        List<String> changedFiles = new ArrayList<>();

        ChangeListManager clm = ChangeListManager.getInstance(project);
        Collection<Change> changes = clm.getAllChanges();

        String modulePrefix = moduleRoot.getPath();

        for (Change change : changes) {
            // 获取文件路径（优先 afterRevision，删除文件用 beforeRevision）
            // 注意：删除的文件 getVirtualFile() 返回 null（文件已不存在），
            // 必须用 getFile().getPath() 获取路径
            String filePath = null;
            String fileName = null;

            if (change.getAfterRevision() != null) {
                // 新增/修改/移动：afterRevision 有值
                VirtualFile vf = change.getAfterRevision().getFile().getVirtualFile();
                if (vf != null) {
                    filePath = vf.getPath();
                    fileName = vf.getName();
                } else {
                    // VirtualFile 可能为 null（文件刚创建但未刷新），用路径兜底
                    filePath = change.getAfterRevision().getFile().getPath();
                    fileName = change.getAfterRevision().getFile().getName();
                }
            }

            if (filePath == null && change.getBeforeRevision() != null) {
                // 删除的文件：只有 beforeRevision
                // getVirtualFile() 返回 null（文件已删除），直接用 getFile().getPath()
                filePath = change.getBeforeRevision().getFile().getPath();
                fileName = change.getBeforeRevision().getFile().getName();
            }

            if (filePath != null && filePath.startsWith(modulePrefix)) {
                String relativePath = filePath.substring(modulePrefix.length());
                if (relativePath.startsWith("/")) {
                    relativePath = relativePath.substring(1);
                }

                // 不按扩展名过滤：所有 Git 变更文件都纳入候选，由用户在 UI 中勾选
                if (fileName != null && !fileName.isEmpty()) {
                    String status = getChangeStatus(change);
                    changedFiles.add(status + "  " + relativePath);
                }
            }
        }

        changedFiles.sort(String::compareToIgnoreCase);
        return changedFiles;
    }

    /**
     * 获取变更类型的状态标识
     *
     * @param change 变更对象
     * @return 状态标识（A=新增, D=删除, M=修改, R=移动）
     * @author xumanyi
     * @date 2026-03-27
     */
    private static String getChangeStatus(Change change) {
        return switch (change.getType()) {
            case NEW -> "A";
            case DELETED -> "D";
            case MODIFICATION -> "M";
            case MOVED -> "R";
        };
    }
}
