package com.flux.deploy.plugin.service;

import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.Nullable;

import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;

import java.io.InputStream;

/**
 * Maven 产物解析器
 *
 * <p>从模块的 pom.xml 中读取 artifactId、version、packaging，
 * 构造产物文件名。版本号优先从直接子元素读取，其次从 parent 继承，
 * 最后兜底扫描 target/ 目录。</p>
 *
 * @author xumanyi
 * @date 2026-03-27
 */
public class MavenArtifactResolver {

    private MavenArtifactResolver() {}

    /**
     * 解析模块产物文件名
     *
     * @param moduleRoot 模块根目录（包含 pom.xml）
     * @return 产物文件名（如 scev6-utils-tms-9.0.0-SNAPSHOT.jar），解析失败返回 null
     * @author xumanyi
     * @date 2026-03-27
     */
    @Nullable
    public static String resolveArtifactFileName(VirtualFile moduleRoot) {
        VirtualFile pomFile = moduleRoot.findChild("pom.xml");
        if (pomFile == null) return null;

        try (InputStream is = pomFile.getInputStream()) {
            Document doc = DocumentBuilderFactory.newInstance()
                    .newDocumentBuilder().parse(is);
            doc.getDocumentElement().normalize();

            String artifactId = getDirectChildText(doc.getDocumentElement(), "artifactId");
            String version = getDirectChildText(doc.getDocumentElement(), "version");
            String packaging = getDirectChildText(doc.getDocumentElement(), "packaging");

            // 版本未直接定义时，从 parent 继承
            if (version == null) {
                version = getParentVersion(doc);
            }

            if (artifactId == null) return null;
            if (packaging == null) packaging = "jar";

            // 优先扫描 target/ 目录下的实际编译产物
            // 传入 pom 版本号，确保 target/ 产物版本与 pom 一致（防止残留旧版本产物）
            String pomVersion = version != null ? version : getParentVersion(doc);
            String fromTarget = resolveFromTarget(moduleRoot, artifactId, packaging, pomVersion);
            if (fromTarget != null) {
                return fromTarget;
            }

            // target/ 无产物时（未编译），尝试读取 finalName 或按规则拼接
            // 1. 优先读取 <build><finalName>
            String finalName = getBuildFinalName(doc);
            if (finalName != null) {
                return finalName + "." + packaging;
            }

            // 2. WAR 包：Maven war 插件默认 finalName = artifactId（不含版本号）
            if ("war".equals(packaging)) {
                return artifactId + "." + packaging;
            }

            // 3. JAR 包：默认 artifactId-version.jar
            if (version == null) {
                version = getParentVersion(doc);
            }
            if (version != null) {
                return artifactId + "-" + version + "." + packaging;
            }

            // 版本也无法确定时返回 null
            return null;

        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 从 parent 标签读取版本号
     *
     * @param doc XML 文档
     * @return parent 版本号，不存在时返回 {@code null}
     * @author xumanyi
     * @date 2026-03-27
     */
    @Nullable
    private static String getParentVersion(Document doc) {
        NodeList parentNodes = doc.getDocumentElement().getElementsByTagName("parent");
        if (parentNodes.getLength() > 0) {
            org.w3c.dom.Node parentNode = parentNodes.item(0);
            if (parentNode instanceof org.w3c.dom.Element parentElem) {
                NodeList versionNodes = parentElem.getElementsByTagName("version");
                if (versionNodes.getLength() > 0) {
                    return versionNodes.item(0).getTextContent().trim();
                }
            }
        }
        return null;
    }

    /**
     * 从 target/ 目录下扫描实际编译产物文件名（兜底方案）
     *
     * @param moduleRoot 模块根目录
     * @param artifactId Maven artifactId
     * @param packaging  打包类型（jar/war），为 {@code null} 时默认 jar
     * @return 匹配的产物文件名，未找到返回 {@code null}
     * @author xumanyi
     * @date 2026-03-27
     */
    @Nullable
    private static String resolveFromTarget(VirtualFile moduleRoot, String artifactId, String packaging,
                                             String pomVersion) {
        VirtualFile targetDir = moduleRoot.findChild("target");
        if (targetDir == null || !targetDir.isDirectory()) return null;

        String ext = (packaging != null) ? packaging : "jar";
        String exactMatch = artifactId + "." + ext;
        String prefixMatch = artifactId + "-";
        // 如果有 pom 版本号，构建精确版本匹配名（如 scev6-xxx-9.0.0-SNAPSHOT.jar）
        String versionMatch = (pomVersion != null) ? artifactId + "-" + pomVersion + "." + ext : null;
        String bestMatch = null;

        for (VirtualFile child : targetDir.getChildren()) {
            String name = child.getName();
            if (!name.endsWith("." + ext)) continue;
            if (name.contains("-sources") || name.contains("-javadoc")) continue;
            if (name.startsWith("__")) continue;

            // 精确匹配（如 tm10srv.war）优先
            if (name.equals(exactMatch)) {
                return name;
            }
            // 版本精确匹配优先（如 scev6-xxx-9.0.0-SNAPSHOT.jar）
            if (versionMatch != null && name.equals(versionMatch)) {
                return name;
            }
            // 前缀匹配（兜底，但需要验证版本一致）
            if (name.startsWith(prefixMatch) && bestMatch == null) {
                // 如果有 pom 版本号，检查 target 产物版本是否一致
                if (pomVersion != null && !name.contains(pomVersion)) {
                    continue; // 版本不一致，跳过（可能是残留旧产物）
                }
                bestMatch = name;
            }
        }
        return bestMatch;
    }

    /**
     * 从 pom.xml 的 build/finalName 读取自定义产物名
     */
    @Nullable
    private static String getBuildFinalName(Document doc) {
        NodeList buildNodes = doc.getDocumentElement().getChildNodes();
        for (int i = 0; i < buildNodes.getLength(); i++) {
            org.w3c.dom.Node node = buildNodes.item(i);
            if (node.getNodeType() == org.w3c.dom.Node.ELEMENT_NODE
                    && "build".equals(node.getNodeName())) {
                NodeList children = node.getChildNodes();
                for (int j = 0; j < children.getLength(); j++) {
                    org.w3c.dom.Node child = children.item(j);
                    if (child.getNodeType() == org.w3c.dom.Node.ELEMENT_NODE
                            && "finalName".equals(child.getNodeName())) {
                        String val = child.getTextContent().trim();
                        // finalName 可能包含 ${...} 变量，无法解析时返回 null
                        if (!val.contains("${")) {
                            return val;
                        }
                    }
                }
            }
        }
        return null;
    }

    /**
     * 获取直接子元素文本（排除 parent 等嵌套元素中的同名标签）
     *
     * @param parent  父元素
     * @param tagName 目标标签名
     * @return 标签文本内容，不存在时返回 {@code null}
     * @author xumanyi
     * @date 2026-03-27
     */
    @Nullable
    private static String getDirectChildText(org.w3c.dom.Element parent, String tagName) {
        NodeList nodes = parent.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            org.w3c.dom.Node node = nodes.item(i);
            if (node.getNodeType() == org.w3c.dom.Node.ELEMENT_NODE
                    && node.getNodeName().equals(tagName)) {
                return node.getTextContent().trim();
            }
        }
        return null;
    }
}
