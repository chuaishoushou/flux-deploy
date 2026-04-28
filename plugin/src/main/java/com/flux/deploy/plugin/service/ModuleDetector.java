package com.flux.deploy.plugin.service;

import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.Nullable;

/**
 * 从右键上下文检测模块根目录
 *
 * <p>向上遍历找到包含 pom.xml 的目录作为模块根。
 * 用于确定当前操作所属的 Maven 模块。</p>
 *
 * @author xumanyi
 * @date 2026-03-27
 */
public class ModuleDetector {

    private ModuleDetector() {}

    /**
     * 检测模块根目录（包含 pom.xml 的最近父目录）
     *
     * @param e 动作事件，从中获取当前选中的文件
     * @return 模块根目录，未找到时返回 {@code null}
     * @author xumanyi
     * @date 2026-03-27
     */
    @Nullable
    public static VirtualFile detectModuleRoot(AnActionEvent e) {
        VirtualFile file = e.getData(CommonDataKeys.VIRTUAL_FILE);
        if (file == null) return null;

        VirtualFile dir = file.isDirectory() ? file : file.getParent();
        while (dir != null) {
            if (dir.findChild("pom.xml") != null) {
                return dir;
            }
            dir = dir.getParent();
        }
        return null;
    }
}
