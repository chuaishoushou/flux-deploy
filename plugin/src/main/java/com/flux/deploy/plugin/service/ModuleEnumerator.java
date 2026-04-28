package com.flux.deploy.plugin.service;

import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;

import java.io.File;
import java.io.FileInputStream;
import java.util.*;

/**
 * Maven 模块枚举器：扫描项目下所有含 pom.xml 的目录，构建树形数据
 *
 * <p>递归扫描 IDEA 项目根目录下的子目录，识别 Maven 模块并组织为
 * groupDir → subDir → module 的层级结构。叶子节点为实际模块，
 * 存储绝对路径和 artifactId。</p>
 *
 * <p>自动过滤工具工程（flux-deploy-cli、flux-deploy-plugin）和
 * 非部署相关目录（.git、.idea、node_modules 等）。</p>
 *
 * @author xumanyi
 * @date 2026年03月28日
 */
public class ModuleEnumerator {

    /** 扫描时跳过的目录名集合 */
    private static final Set<String> SKIP_DIRS = Set.of(
            ".git", ".idea", ".claude", "node_modules", "target", "build",
            "flux-deploy-cli", "flux-deploy-plugin"
    );

    /** 最大递归深度（防止过深扫描） */
    private static final int MAX_DEPTH = 5;

    /** 缓存的模块树根节点列表 */
    private static List<ModuleTreeNode> cachedTree;

    /** 缓存对应的项目路径（路径变化时失效） */
    private static String cachedBasePath;

    private ModuleEnumerator() {}

    /**
     * 模块树节点数据
     *
     * <p>叶子节点（modulePath 非 null）表示可选的 Maven 模块；
     * 非叶子节点（modulePath 为 null）表示目录分组。</p>
     */
    public static class ModuleTreeNode {

        /** 显示名称：叶子为 artifactId，非叶子为目录名 */
        private final String displayName;

        /** 模块绝对路径（仅叶子节点有值） */
        private final String modulePath;

        /** 子节点列表 */
        private final List<ModuleTreeNode> children;

        /**
         * 构造模块树节点
         *
         * @param displayName 显示名称
         * @param modulePath  模块绝对路径，目录节点传 {@code null}
         * @author xumanyi
         * @date 2026年03月28日
         */
        public ModuleTreeNode(String displayName, @Nullable String modulePath) {
            this.displayName = displayName;
            this.modulePath = modulePath;
            this.children = new ArrayList<>();
        }

        /**
         * @return 显示名称
         * @author xumanyi
         * @date 2026年03月28日
         */
        public String getDisplayName() { return displayName; }

        /**
         * @return 模块绝对路径，目录节点返回 {@code null}
         * @author xumanyi
         * @date 2026年03月28日
         */
        @Nullable
        public String getModulePath() { return modulePath; }

        /**
         * @return 子节点列表
         * @author xumanyi
         * @date 2026年03月28日
         */
        public List<ModuleTreeNode> getChildren() { return children; }

        /**
         * @return 是否为叶子节点（可选的 Maven 模块）
         * @author xumanyi
         * @date 2026年03月28日
         */
        public boolean isLeaf() { return modulePath != null; }

        @Override
        public String toString() { return displayName; }
    }

    /**
     * 获取项目的模块树（带缓存）
     *
     * <p>首次调用时扫描文件系统，后续调用返回缓存结果。
     * 项目路径变化或调用 {@link #invalidateCache()} 后重新扫描。</p>
     *
     * @param project 当前 IDEA 项目
     * @return 模块树根节点列表（每个根节点为一个顶层目录分组）
     * @author xumanyi
     * @date 2026年03月28日
     */
    @NotNull
    public static synchronized List<ModuleTreeNode> getModuleTree(@NotNull Project project) {
        String basePath = project.getBasePath();
        if (basePath == null) return List.of();

        if (cachedTree != null && basePath.equals(cachedBasePath)) {
            return cachedTree;
        }

        cachedTree = scanModuleTree(new File(basePath), 0);
        cachedBasePath = basePath;
        return cachedTree;
    }

    /**
     * 清除缓存，下次调用 {@link #getModuleTree} 时重新扫描
     *
     * @author xumanyi
     * @date 2026年03月28日
     */
    public static synchronized void invalidateCache() {
        cachedTree = null;
        cachedBasePath = null;
    }

    /**
     * 递归扫描目录，构建模块树节点列表
     *
     * @param dir   当前扫描目录
     * @param depth 当前递归深度
     * @return 当前层级的模块树节点列表
     * @author xumanyi
     * @date 2026年03月28日
     */
    private static List<ModuleTreeNode> scanModuleTree(File dir, int depth) {
        if (depth > MAX_DEPTH) return List.of();

        File[] children = dir.listFiles();
        if (children == null) return List.of();

        List<ModuleTreeNode> result = new ArrayList<>();

        // 按名称排序
        Arrays.sort(children, Comparator.comparing(File::getName));

        for (File child : children) {
            if (!child.isDirectory()) continue;
            String name = child.getName();
            if (SKIP_DIRS.contains(name) || name.startsWith(".")) continue;

            File pomFile = new File(child, "pom.xml");
            if (pomFile.exists()) {
                // 叶子节点：Maven 模块
                String artifactId = parseArtifactId(pomFile);
                String displayName = artifactId != null ? artifactId : name;
                result.add(new ModuleTreeNode(displayName, child.getAbsolutePath()));
            } else {
                // 非模块目录：递归扫描子目录
                List<ModuleTreeNode> subNodes = scanModuleTree(child, depth + 1);
                if (!subNodes.isEmpty()) {
                    ModuleTreeNode groupNode = new ModuleTreeNode(name, null);
                    groupNode.getChildren().addAll(subNodes);
                    result.add(groupNode);
                }
            }
        }

        return result;
    }

    /**
     * 从 pom.xml 解析 artifactId
     *
     * @param pomFile pom.xml 文件
     * @return artifactId，解析失败返回 {@code null}
     * @author xumanyi
     * @date 2026年03月28日
     */
    @Nullable
    private static String parseArtifactId(File pomFile) {
        try (FileInputStream fis = new FileInputStream(pomFile)) {
            Document doc = DocumentBuilderFactory.newInstance()
                    .newDocumentBuilder().parse(fis);
            doc.getDocumentElement().normalize();

            // 只读直接子元素的 artifactId（排除 parent 中的同名标签）
            NodeList nodes = doc.getDocumentElement().getChildNodes();
            for (int i = 0; i < nodes.getLength(); i++) {
                org.w3c.dom.Node node = nodes.item(i);
                if (node.getNodeType() == org.w3c.dom.Node.ELEMENT_NODE
                        && "artifactId".equals(node.getNodeName())) {
                    return node.getTextContent().trim();
                }
            }
        } catch (Exception ignored) {
            // pom.xml 解析失败，返回 null 使用目录名兜底
        }
        return null;
    }
}
