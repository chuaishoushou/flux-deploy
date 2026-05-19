package com.flux.deploy.plugin.util;

import com.flux.deploy.plugin.model.DeployMode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * 部署前编译产物新鲜度校验器（纯函数，无 IDE 依赖）。
 *
 * <p>{@link ArtifactPresenceValidator} 只校验"存在性"；本类在存在性通过后再做一次
 * "源码 vs 编译产物 mtime 对比"，识别"用户改了 .java 但忘了重新编译/打包"的场景，
 * 防止上传到旧版本字节。</p>
 *
 * <p>校验范围只盯 {@code .java}：</p>
 * <ul>
 *   <li>{@link DeployMode#INCREMENTAL}：遍历 {@code changedFiles} 中的 {@code .java}，
 *       逐个比对 {@code target/classes/.../X.class} mtime。任一 {@code .java} 比对应
 *       {@code .class} 新 → 标记为 stale。</li>
 *   <li>{@link DeployMode#FULL}：扫描 {@code src/main/java/**\/*.java}，与
 *       {@code target/<artifactFileName>} 对比。任一 {@code .java} 比 jar 新 → 标记为 stale。</li>
 * </ul>
 *
 * <p>不校验 {@code resources/}、{@code webapp/}、{@code .properties} 等非 .java 资源：
 * INCREMENTAL 模式下 {@link com.flux.deploy.plugin.service.StagingPackageBuilder} 直读源文件，
 * 不存在"产物落后于源"的可能。</p>
 *
 * <p>对缺产物（{@code .class} / jar 不存在）一律返回 fresh，让上游
 * {@link ArtifactPresenceValidator} 用"产物缺失"的语义专门处理，避免两个校验器抢报错。</p>
 *
 * @author xumanyi
 * @date 2026-05-19
 */
public final class ArtifactFreshnessChecker {

    private ArtifactFreshnessChecker() {}

    /**
     * 校验结果：过期源文件清单。
     *
     * <p>{@link #staleSources} 为空表示产物比所有源都新（或为退化场景如缺产物），可直接部署；
     * 非空表示弹窗提示用户确认是否已重新编译。</p>
     *
     * @author xumanyi
     * @date 2026-05-19
     */
    public static final class Result {
        /** 比对应产物新的 .java 源相对路径列表（已剥 VCS 前缀，已按字母序排序） */
        public final List<String> staleSources;
        /** 模块根绝对路径，弹窗里展示给用户便于定位 */
        public final String modulePath;

        Result(List<String> staleSources, String modulePath) {
            this.staleSources = staleSources;
            this.modulePath = modulePath;
        }

        /**
         * @return true 表示产物足够新（或退化场景），可继续部署
         * @author xumanyi
         * @date 2026-05-19
         */
        public boolean isFresh() {
            return staleSources.isEmpty();
        }
    }

    /**
     * 按部署模式校验 .java 源是否比编译产物新。
     *
     * <p>本方法不抛 IO 异常：扫描或读 mtime 失败时按"无法判定 → 视为 fresh"退化，
     * 避免环境异常阻断部署。真正缺失的产物由 {@link ArtifactPresenceValidator} 负责。</p>
     *
     * @param mode             部署模式
     * @param modulePath       模块根绝对路径；为 {@code null} 时直接返回 fresh
     * @param artifactFileName 全量模式产物文件名（如 {@code app.jar}）
     * @param changedFiles     增量模式勾选清单（可含 VCS 状态前缀）
     * @return 校验结果
     * @author xumanyi
     * @date 2026-05-19
     */
    public static Result check(DeployMode mode,
                                String modulePath,
                                String artifactFileName,
                                List<String> changedFiles) {
        if (modulePath == null) {
            return new Result(List.of(), null);
        }
        Path moduleRoot = Path.of(modulePath);

        if (mode == DeployMode.FULL) {
            return checkFull(moduleRoot, modulePath, artifactFileName);
        }
        return checkIncremental(moduleRoot, modulePath, changedFiles);
    }

    /**
     * 全量模式：扫 {@code src/main/java} 下所有 {@code .java}，与 {@code target/<artifactFileName>}
     * 比 mtime。
     *
     * @param moduleRoot       模块根
     * @param modulePath       模块根字符串（用于结果回填）
     * @param artifactFileName 产物文件名；为空 / jar 不存在时返回 fresh
     * @return 校验结果
     * @author xumanyi
     * @date 2026-05-19
     */
    private static Result checkFull(Path moduleRoot, String modulePath, String artifactFileName) {
        if (artifactFileName == null || artifactFileName.isBlank()) {
            return new Result(List.of(), modulePath);
        }
        Path artifact = moduleRoot.resolve("target").resolve(artifactFileName);
        if (!Files.isRegularFile(artifact)) {
            return new Result(List.of(), modulePath);
        }
        Path sourcesRoot = moduleRoot.resolve("src/main/java");
        if (!Files.isDirectory(sourcesRoot)) {
            return new Result(List.of(), modulePath);
        }

        FileTime artifactMtime;
        try {
            artifactMtime = Files.getLastModifiedTime(artifact);
        } catch (IOException e) {
            return new Result(List.of(), modulePath);
        }

        List<String> stale = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(sourcesRoot)) {
            walk.filter(Files::isRegularFile)
                .filter(p -> isJavaFile(p.getFileName().toString()))
                .forEach(p -> {
                    try {
                        if (Files.getLastModifiedTime(p).compareTo(artifactMtime) > 0) {
                            stale.add(toForwardSlash(moduleRoot.relativize(p).toString()));
                        }
                    } catch (IOException ignored) {
                        // 单个文件 mtime 读不到不影响整体校验，跳过即可
                    }
                });
        } catch (IOException e) {
            return new Result(List.of(), modulePath);
        }
        stale.sort(String::compareTo);
        return new Result(stale, modulePath);
    }

    /**
     * 增量模式：对 {@code changedFiles} 中每个 {@code .java}，比对 {@code target/classes/.../X.class}
     * 的 mtime。
     *
     * <p>跳过场景：非 .java 条目、源文件在磁盘不存在（新增未落地）、对应 .class 不存在（让
     * PresenceValidator 兜底）。</p>
     *
     * @param moduleRoot   模块根
     * @param modulePath   模块根字符串
     * @param changedFiles 勾选清单
     * @return 校验结果
     * @author xumanyi
     * @date 2026-05-19
     */
    private static Result checkIncremental(Path moduleRoot, String modulePath,
                                            List<String> changedFiles) {
        if (changedFiles == null || changedFiles.isEmpty()) {
            return new Result(List.of(), modulePath);
        }
        Path classesDir = moduleRoot.resolve("target").resolve("classes");
        List<String> stale = new ArrayList<>();
        for (String raw : changedFiles) {
            String rel = stripVcsPrefix(raw).replace('\\', '/');
            if (!isJavaFile(rel)) continue;

            Path javaFile = moduleRoot.resolve(rel);
            if (!Files.isRegularFile(javaFile)) continue;

            String classRel = toClassRelative(rel);
            if (classRel == null) continue;
            Path classFile = classesDir.resolve(classRel);
            if (!Files.isRegularFile(classFile)) continue;

            try {
                FileTime javaMtime = Files.getLastModifiedTime(javaFile);
                FileTime classMtime = Files.getLastModifiedTime(classFile);
                if (javaMtime.compareTo(classMtime) > 0) {
                    stale.add(rel);
                }
            } catch (IOException ignored) {
                // 读 mtime 失败按 fresh 处理，避免环境异常阻断部署
            }
        }
        stale.sort(String::compareTo);
        return new Result(stale, modulePath);
    }

    /**
     * 把 git status 输出的"X path"前缀剥掉，与 {@link ArtifactPresenceValidator} 口径一致。
     *
     * @param raw 原始勾选项
     * @return 纯路径
     * @author xumanyi
     * @date 2026-05-19
     */
    private static String stripVcsPrefix(String raw) {
        String path = raw == null ? "" : raw;
        if (path.length() > 2 && path.charAt(1) == ' ') {
            path = path.substring(path.indexOf(' ')).trim();
        }
        return path;
    }

    /**
     * 路径是否以 {@code .java}（大小写不敏感）结尾。
     *
     * @param path 相对路径
     * @return true 表示是 Java 源文件
     * @author xumanyi
     * @date 2026-05-19
     */
    private static boolean isJavaFile(String path) {
        return path.length() >= 5 && path.regionMatches(true, path.length() - 5, ".java", 0, 5);
    }

    /**
     * 把 {@code src/main/java/com/foo/Bar.java} 转换为 {@code com/foo/Bar.class}，与
     * {@link ArtifactPresenceValidator} 口径一致。
     *
     * @param srcRelative 相对模块根的源码路径
     * @return classes 下的相对路径；不在 src/main/java 下时返回 null
     * @author xumanyi
     * @date 2026-05-19
     */
    private static String toClassRelative(String srcRelative) {
        int idx = srcRelative.indexOf("src/main/java/");
        if (idx < 0) return null;
        String afterRoot = srcRelative.substring(idx + "src/main/java/".length());
        if (!afterRoot.endsWith(".java") && !afterRoot.endsWith(".JAVA")) return null;
        return afterRoot.substring(0, afterRoot.length() - 5) + ".class";
    }

    /**
     * 把 Windows 反斜杠路径统一成 forward slash。
     *
     * @param p 相对路径
     * @return forward slash 形式
     * @author xumanyi
     * @date 2026-05-19
     */
    private static String toForwardSlash(String p) {
        return p.replace('\\', '/');
    }
}
