package com.flux.deploy.plugin.util;

import com.flux.deploy.plugin.model.DeployMode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 部署前编译产物存在性校验器（纯函数，无 IDE 依赖）。
 *
 * <p>插件不再触发任何 mvn / IDE 编译；用户必须自行在 IDE Build (Cmd+F9) 或终端
 * {@code mvn package} 中产出 {@code target/classes/<x>.class} 与 {@code target/<artifact>}。
 * 本类提供"产物是否就绪"的判定，由 UI 层在点击部署前调用，缺失则弹窗提示用户先编译再重试。</p>
 *
 * <p>仅检查"存在性"，不做"是否新鲜"的判断——新鲜与否是用户语义决策，
 * 自动检测在多种合理工作流（IDE Build / 外部 mvn / CI 产物）下都会有盲区，
 * 与其猜错误导用户，不如把信任完全交给用户。</p>
 *
 * @author xumanyi
 * @date 2026-05-07
 */
public final class ArtifactPresenceValidator {

    private ArtifactPresenceValidator() {}

    /**
     * 校验结果：缺失产物清单 + 校验依据的根目录（用于在弹窗中给出绝对路径帮用户定位）。
     *
     * <p>{@link #missing} 为空表示一切就绪，可直接部署；非空表示需要弹窗终止部署。</p>
     *
     * @author xumanyi
     * @date 2026-05-07
     */
    public static final class Result {
        /** 缺失产物的相对路径列表（相对于 target/，已含 target/ 前缀），按字母序排序便于用户阅读 */
        public final List<String> missing;
        /** 模块根绝对路径，弹窗里展示给用户便于打开终端定位 */
        public final String modulePath;

        Result(List<String> missing, String modulePath) {
            this.missing = missing;
            this.modulePath = modulePath;
        }

        /**
         * @return true 表示无缺失，可继续部署
         * @author xumanyi
         * @date 2026-05-07
         */
        public boolean isOk() {
            return missing.isEmpty();
        }
    }

    /**
     * 按部署模式校验目标产物是否存在。
     *
     * <ul>
     *   <li>{@link DeployMode#FULL}：要求 {@code <modulePath>/target/<artifactFileName>} 存在</li>
     *   <li>{@link DeployMode#INCREMENTAL}：要求 {@code changedFiles} 中每个 {@code .java}
     *       在 {@code <modulePath>/target/classes/...} 下都有对应 {@code .class}；
     *       静态资源不做校验（{@link com.flux.deploy.plugin.service.StagingPackageBuilder}
     *       内部直读源文件，不依赖 target/classes）；
     *       当 {@code hasEmbedTargets=true} 且 {@code changedFiles} 为空时，叠加 FULL 同款
     *       {@code target/<artifactFileName>} 存在性校验，原因详见下方</li>
     * </ul>
     *
     * <p><b>为什么"INCREMENTAL+embed+空 changedFiles"才校验源 artifact？</b>
     * WAR 嵌入运行时（{@code DeployExecutionService.executeWarEmbed}）只在
     * {@code canPatch=false}（即 {@code changedFiles} 为空）时才会把
     * {@code target/<artifactFileName>} 当作整包兜底；只要 {@code changedFiles} 有任意条目
     * （全 .java / 全静态 / 混合），都会走 "extract WAR → patchExistingJar" 路径，
     * 源 artifact 不消费——其中静态资源直接从源文件读取，因此"只勾静态文件"在没做
     * {@code mvn package} 时也能正常嵌入。把校验收窄为"空 changedFiles"，避免误伤静态-only 部署。</p>
     *
     * <p>INCREMENTAL+主目标 不依赖本地源 artifact（{@code StagingPackageBuilder.build} 从 FTP
     * 下原包），故全程不叠加。</p>
     *
     * <p>调用方传入的 {@code changedFiles} 可携带 VCS 状态前缀（如 {@code "M src/..."}），
     * 本方法会自行剥离。</p>
     *
     * @param mode             部署模式
     * @param modulePath       模块根绝对路径；为 {@code null} 时直接返回 OK（外层会用别的提示拒绝部署）
     * @param artifactFileName 全量模式产物文件名（如 {@code scev6-utils-tms-10.0.0-SNAPSHOT.jar}）
     * @param changedFiles     增量模式勾选清单
     * @param hasEmbedTargets  本次部署是否包含 WAR 嵌入目标；与"空 changedFiles"叠加才触发
     *                         {@code target/<artifactFileName>} 存在性校验
     * @return 校验结果
     * @author xumanyi
     * @date 2026-05-07
     */
    public static Result validate(DeployMode mode,
                                   String modulePath,
                                   String artifactFileName,
                                   List<String> changedFiles,
                                   boolean hasEmbedTargets) {
        if (modulePath == null) {
            return new Result(List.of(), null);
        }
        Path moduleRoot = Path.of(modulePath);

        if (mode == DeployMode.FULL) {
            return validateFull(moduleRoot, modulePath, artifactFileName);
        }

        Result classResult = validateIncremental(moduleRoot, modulePath, changedFiles);
        if (!hasEmbedTargets) {
            return classResult;
        }

        // INCREMENTAL+embed：仅在 changedFiles 为空（=必走整包兜底）时叠加源 artifact 校验。
        // 非空（含纯静态）走 patchExistingJar 路径，源 artifact 不消费，不在此处强制要求。
        boolean changedFilesEmpty = changedFiles == null || changedFiles.isEmpty();
        if (!changedFilesEmpty) {
            return classResult;
        }
        if (artifactFileName == null || artifactFileName.isBlank()) {
            return classResult;
        }
        Path artifact = moduleRoot.resolve("target").resolve(artifactFileName);
        if (Files.isRegularFile(artifact)) {
            return classResult;
        }
        List<String> combined = new ArrayList<>(classResult.missing);
        combined.add("target/" + artifactFileName);
        combined.sort(String::compareTo);
        return new Result(combined, modulePath);
    }

    /**
     * 全量模式：校验 {@code target/<artifactFileName>} 是否就绪。
     *
     * @param moduleRoot       模块根（已转 Path）
     * @param modulePath       模块根字符串（用于结果回填）
     * @param artifactFileName 产物文件名；为空时直接 OK（让上游用更具体的错误信息）
     * @return 校验结果
     * @author xumanyi
     * @date 2026-05-07
     */
    private static Result validateFull(Path moduleRoot, String modulePath, String artifactFileName) {
        if (artifactFileName == null || artifactFileName.isBlank()) {
            return new Result(List.of(), modulePath);
        }
        Path artifact = moduleRoot.resolve("target").resolve(artifactFileName);
        if (Files.isRegularFile(artifact)) {
            return new Result(List.of(), modulePath);
        }
        return new Result(List.of("target/" + artifactFileName), modulePath);
    }

    /**
     * 增量模式：校验每个勾选 .java 是否都有对应 .class。
     *
     * <p>仅校验 .java，不校验静态资源（静态资源由 StagingPackageBuilder 直读源文件，无需编译）。
     * 内部类 / 嵌套类的 .class 不单独校验：只要顶层 .class 存在就视为该 .java 已编译过。</p>
     *
     * @param moduleRoot   模块根
     * @param modulePath   模块根字符串
     * @param changedFiles 勾选清单（含 VCS 状态前缀）
     * @return 校验结果，按字母序排序的缺失列表
     * @author xumanyi
     * @date 2026-05-07
     */
    private static Result validateIncremental(Path moduleRoot, String modulePath,
                                                List<String> changedFiles) {
        if (changedFiles == null || changedFiles.isEmpty()) {
            return new Result(List.of(), modulePath);
        }
        Path classesDir = moduleRoot.resolve("target").resolve("classes");
        List<String> missing = new ArrayList<>();
        for (String raw : changedFiles) {
            String rel = stripVcsPrefix(raw).replace('\\', '/');
            if (!isJavaFile(rel)) continue;

            // 新增的 .java 但磁盘上还没创建文件 (git status 标 A)，跳过校验：
            // 用户可能刚 git mv，还没真正写代码就来部署；这种少见场景由后续部署逻辑自然报错
            if (!Files.exists(moduleRoot.resolve(rel))) continue;

            String classRel = toClassRelative(rel);
            if (classRel == null) continue;
            Path classFile = classesDir.resolve(classRel);
            if (!Files.isRegularFile(classFile)) {
                missing.add("target/classes/" + classRel);
            }
        }
        missing.sort(String::compareTo);
        return new Result(missing, modulePath);
    }

    /**
     * 把 git status 输出的"X path"前缀剥掉，与 SourceSectionPanel.stripStatusPrefix 口径一致。
     *
     * @param raw 原始勾选项
     * @return 纯路径
     * @author xumanyi
     * @date 2026-05-07
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
     * @date 2026-05-07
     */
    private static boolean isJavaFile(String path) {
        return path.length() >= 5 && path.regionMatches(true, path.length() - 5, ".java", 0, 5);
    }

    /**
     * 把 {@code src/main/java/com/foo/Bar.java} 这种相对路径转换为
     * 在 {@code target/classes/} 下的对应相对路径 {@code com/foo/Bar.class}。
     *
     * @param srcRelative 相对模块根的源码路径
     * @return classes 下的相对路径；不在 src/main/java 下时返回 null
     * @author xumanyi
     * @date 2026-05-07
     */
    private static String toClassRelative(String srcRelative) {
        int idx = srcRelative.indexOf("src/main/java/");
        if (idx < 0) return null;
        String afterRoot = srcRelative.substring(idx + "src/main/java/".length());
        if (!afterRoot.endsWith(".java") && !afterRoot.endsWith(".JAVA")) return null;
        return afterRoot.substring(0, afterRoot.length() - 5) + ".class";
    }

}
