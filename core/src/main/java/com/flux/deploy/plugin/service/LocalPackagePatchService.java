package com.flux.deploy.plugin.service;

import com.flux.deploy.plugin.model.DeployMode;
import com.flux.deploy.util.WarEmbedUtil;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.Consumer;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

/**
 * 本地模式包补丁服务
 *
 * <p>用户手动提供本地 jar/war 包，本服务根据源工程的变更清单在本地生成打补丁后的新包，
 * 整个流程与 FTP 完全解耦——不登录 FTP、不备份、不写版本记录、不可回滚。</p>
 *
 * <p>支持三种场景分流（由源产物类型 × 用户提供包类型 组合决定）：</p>
 * <ol>
 *   <li><b>jar → jar</b>：校验包名前缀 + 版本号严格一致 → 直接替换 jar 内条目</li>
 *   <li><b>war → war</b>：校验包名前缀 + 版本号严格一致 → 替换 war 内 WEB-INF/classes 下条目</li>
 *   <li><b>jar → war/lib</b>：在 war 的 WEB-INF/lib 下按前缀定位 jar → 版本严格一致 →
 *       抽出该 jar → 打补丁 → 通过 {@link WarEmbedUtil#embedJar} 回写 war</li>
 *   <li><b>war → jar</b>：非法组合，直接中断</li>
 * </ol>
 *
 * <p>输出新包文件名与输入同名，放在 {@code <包所在目录>/patched_{yyyyMMdd_HHmmss}/} 子目录。</p>
 *
 * @author xumanyi
 * @date 2026-04-17
 */
public class LocalPackagePatchService {

    /** 输出目录时间戳格式 */
    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    private LocalPackagePatchService() {}

    /**
     * 本地模式补丁执行结果
     */
    public static class LocalPatchResult {
        /** 执行是否成功 */
        private final boolean success;
        /** 输出包绝对路径（成功时存在） */
        private final Path outputPackage;
        /** 变更条目数（替换/新增/删除总和） */
        private final int changedCount;
        /** 失败原因（成功时为 null） */
        private final String errorMessage;

        public LocalPatchResult(boolean success, Path outputPackage, int changedCount, String errorMessage) {
            this.success = success;
            this.outputPackage = outputPackage;
            this.changedCount = changedCount;
            this.errorMessage = errorMessage;
        }

        public boolean isSuccess() { return success; }
        public Path getOutputPackage() { return outputPackage; }
        public int getChangedCount() { return changedCount; }
        public String getErrorMessage() { return errorMessage; }
    }

    /**
     * 推算本地模式默认输出目录：{@code <包所在目录>/patched_{yyyyMMdd_HHmmss}/}
     *
     * @param localPackagePath 用户选择的本地包路径
     * @return 默认输出目录绝对路径
     */
    public static Path suggestOutputDir(String localPackagePath) {
        Path pkg = Path.of(localPackagePath);
        Path parent = pkg.getParent();
        if (parent == null) parent = Path.of(".");
        String ts = LocalDateTime.now().format(TS_FMT);
        return parent.resolve("patched_" + ts);
    }

    /**
     * 执行本地模式补丁
     *
     * @param mode             更新模式（整包更新 / 增量更新；CLI 内部还有 AUTO_DETECT 仅 patch-local 用）
     * @param modulePath       源工程根目录
     * @param artifactFileName 源工程产物文件名（如 scev6-utils-tms-10.0.0-SNAPSHOT.jar）
     * @param changedFiles     变更文件列表（FULL 模式下可为 null / 空）
     * @param localPackagePath 用户提供的本地包路径
     * @param outputDir        输出目录
     * @param logCallback      日志回调
     * @return 执行结果
     */
    public static LocalPatchResult execute(DeployMode mode,
                                            String modulePath, String artifactFileName,
                                            List<String> changedFiles,
                                            String localPackagePath, String outputDir,
                                            Consumer<String> logCallback) {
        try {
            Path localPackage = Path.of(localPackagePath);
            if (!Files.isRegularFile(localPackage)) {
                return fail(logCallback, "本地包不存在: " + localPackagePath);
            }
            if (artifactFileName == null || artifactFileName.isEmpty()) {
                return fail(logCallback, "未解析到源工程产物文件名");
            }
            boolean fullMode = mode == DeployMode.FULL;
            if (!fullMode && (changedFiles == null || changedFiles.isEmpty())) {
                return fail(logCallback, "未勾选任何待更新文件");
            }

            String pkgName = localPackage.getFileName().toString();
            Scenario scenario = dispatch(artifactFileName, pkgName);
            if (scenario == null) {
                return fail(logCallback, "源工程为 WAR 但提供了 JAR 包，无法处理");
            }

            logCallback.accept("[本地] 场景: " + scenario.description
                    + "（" + (fullMode ? "整包更新" : "打补丁") + "）");

            // 准备输出目录与输出包路径（同名）
            Path outDir = Path.of(outputDir);
            Files.createDirectories(outDir);
            Path outputPackage = outDir.resolve(pkgName);

            int changed;
            if (fullMode) {
                changed = executeFull(modulePath, artifactFileName, scenario,
                        localPackage, outputPackage, logCallback);
            } else {
                switch (scenario) {
                    case JAR_TO_JAR:
                    case WAR_TO_WAR:
                        changed = patchDirect(modulePath, artifactFileName, changedFiles,
                                localPackage, outputPackage, logCallback);
                        break;
                    case JAR_TO_WAR_LIB:
                        changed = patchWarLib(modulePath, artifactFileName, changedFiles,
                                localPackage, outputPackage, logCallback);
                        break;
                    default:
                        return fail(logCallback, "未知场景");
                }
            }

            if (changed <= 0) {
                return fail(logCallback, "未产生任何变更");
            }

            logCallback.accept("[本地] ✓ 打包完成: " + outputPackage);
            return new LocalPatchResult(true, outputPackage, changed, null);
        } catch (Exception e) {
            return fail(logCallback, "本地模式异常: " + e.getMessage());
        }
    }

    /**
     * 整包更新模式执行分流
     *
     * <p>对三种场景分别：</p>
     * <ul>
     *   <li><b>jar→jar</b>：把 {@code target/X.jar} 整体拷贝到 outputPackage</li>
     *   <li><b>war→war</b>：把 {@code target/X.war} 整体拷贝到 outputPackage</li>
     *   <li><b>jar→war/lib</b>：用 {@code target/X.jar} 替换 war 内 lib jar，输出新 war</li>
     * </ul>
     *
     * @return 变更条目数（整包更新视为 1）；失败抛异常
     */
    private static int executeFull(String modulePath, String artifactFileName,
                                    Scenario scenario, Path localPackage, Path outputPackage,
                                    Consumer<String> logCallback) throws IOException {
        Path freshArtifact = Path.of(modulePath, "target", artifactFileName);
        if (!Files.isRegularFile(freshArtifact)) {
            throw new IOException("未找到编译产物：" + freshArtifact
                    + "（请确认编译成功且 pom 中的产物名与解析值一致）");
        }
        logCallback.accept("[整包] 新产物: " + freshArtifact.getFileName()
                + " (" + formatSize(Files.size(freshArtifact)) + ")");

        switch (scenario) {
            case JAR_TO_JAR:
            case WAR_TO_WAR: {
                // 直接覆盖：新产物 → 输出
                Files.copy(freshArtifact, outputPackage, StandardCopyOption.REPLACE_EXISTING);
                logCallback.accept("[整包] 已用新产物整体覆盖本地包");
                return 1;
            }
            case JAR_TO_WAR_LIB: {
                // 用新 jar 替换 war 内 WEB-INF/lib 中匹配的 jar；
                // war 中可能同时存在同前缀不同版本，优先挑与源产物完全一致的那个
                String prefix = extractArtifactPrefix(artifactFileName);
                List<String> candidates = collectInnerLibJars(localPackage, prefix);
                if (candidates.isEmpty()) {
                    throw new IOException("WAR 的 WEB-INF/lib 下未找到匹配 [" + prefix + "] 的 JAR");
                }
                String targetLib = pickVersionMatching(candidates, artifactFileName);
                if (targetLib == null) {
                    // FULL 场景若没版本完全一致的，也退一步用前缀匹配到的第一个
                    // （用户意图是"整包更新"，让 war 使用新编译产物即可）
                    targetLib = candidates.get(0);
                    logCallback.accept("[整包] 未找到版本一致的候选（" + String.join(", ", candidates)
                            + "），将替换第一个同前缀 JAR: " + targetLib);
                } else if (candidates.size() > 1) {
                    logCallback.accept("[整包] 候选 " + candidates.size()
                            + " 个，精确替换版本一致的: " + targetLib);
                }
                String exactBaseName = stripExt(targetLib);
                WarEmbedUtil.EmbedResult embed = WarEmbedUtil.embedJar(
                        localPackage, freshArtifact, exactBaseName, outputPackage);
                if (!embed.isVerified()) {
                    throw new IOException("WAR 嵌入校验失败: " + embed.getMessage());
                }
                logCallback.accept("[整包] 已替换 WEB-INF/lib/" + embed.getTargetJarName());
                return 1;
            }
            default:
                throw new IOException("未知场景：" + scenario);
        }
    }

    /** 格式化字节数为 KB/MB */
    private static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
    }

    /**
     * 场景分流 + 源/包匹配校验（含版本严格一致校验）
     *
     * @return 匹配成功返回场景；非法组合返回 null
     */
    private static Scenario dispatch(String srcArtifact, String pkgName) throws IOException {
        boolean srcIsWar = srcArtifact.toLowerCase().endsWith(".war");
        boolean pkgIsWar = pkgName.toLowerCase().endsWith(".war");
        boolean pkgIsJar = pkgName.toLowerCase().endsWith(".jar");
        if (!pkgIsWar && !pkgIsJar) {
            throw new IOException("本地包必须为 .jar 或 .war: " + pkgName);
        }
        if (srcIsWar && pkgIsJar) {
            return null; // 非法
        }
        if (!srcIsWar && pkgIsJar) {
            assertStrictMatch(srcArtifact, pkgName);
            return Scenario.JAR_TO_JAR;
        }
        if (srcIsWar && pkgIsWar) {
            assertStrictMatch(srcArtifact, pkgName);
            return Scenario.WAR_TO_WAR;
        }
        // 源 jar + 用户 war → war/lib 场景，校验在 patchWarLib 中针对内部 jar 执行
        return Scenario.JAR_TO_WAR_LIB;
    }

    /**
     * 严格匹配校验：去扩展名后完整文件名必须一致（含版本号）
     */
    private static void assertStrictMatch(String expected, String actual) throws IOException {
        String a = stripExt(expected);
        String b = stripExt(actual);
        if (!a.equalsIgnoreCase(b)) {
            throw new IOException("包名/版本不匹配，源产物=" + expected + "，本地包=" + actual);
        }
    }

    /**
     * 场景 1/2：直接对本地 jar/war 做补丁
     */
    private static int patchDirect(String modulePath, String artifactFileName,
                                    List<String> changedFiles,
                                    Path localPackage, Path outputPackage,
                                    Consumer<String> logCallback) throws IOException {
        StagingPackageBuilder builder = new StagingPackageBuilder(
                modulePath, artifactFileName, changedFiles, logCallback);
        return builder.buildFromLocal(localPackage, outputPackage);
    }

    /**
     * 场景 3：源 jar → 本地 war/lib
     *
     * <p>在 war 的 WEB-INF/lib 下按前缀定位唯一 jar，校验版本严格一致，
     * 抽出该 jar → 使用 StagingPackageBuilder 打补丁 → 通过 WarEmbedUtil 回写 war。</p>
     */
    private static int patchWarLib(String modulePath, String artifactFileName,
                                    List<String> changedFiles,
                                    Path localWar, Path outputWar,
                                    Consumer<String> logCallback) throws IOException {
        String prefix = extractArtifactPrefix(artifactFileName);
        logCallback.accept("[本地] 在 WAR 内 WEB-INF/lib 下定位前缀=" + prefix + " 的 JAR...");

        // 1. 收集所有前缀匹配的 lib jar（war 内可能存在同前缀不同版本的多份）
        List<String> candidates = collectInnerLibJars(localWar, prefix);
        if (candidates.isEmpty()) {
            throw new IOException("WAR 的 WEB-INF/lib 下未找到匹配 [" + prefix + "] 的 JAR");
        }
        if (candidates.size() > 1) {
            logCallback.accept("[本地] 发现 " + candidates.size() + " 个前缀匹配的 JAR: "
                    + String.join(", ", candidates) + "，按版本一致性挑选");
        }

        // 2. 从候选集中挑版本严格一致的那个
        String innerLibName = pickVersionMatching(candidates, artifactFileName);
        if (innerLibName == null) {
            throw new IOException("WAR 的 WEB-INF/lib 下存在前缀 [" + prefix
                    + "] 的 JAR（" + String.join(", ", candidates)
                    + "），但没有版本与源产物 " + artifactFileName + " 一致的。");
        }
        logCallback.accept("[本地] 定位到: WEB-INF/lib/" + innerLibName + "，版本校验通过");

        // 3. 抽出 lib jar 到临时文件
        Path tempDir = Files.createTempDirectory("flux-local-embed-");
        try {
            Path extractedJar = tempDir.resolve(innerLibName);
            extractEntry(localWar, "WEB-INF/lib/" + innerLibName, extractedJar);
            logCallback.accept("[本地] 已抽出 lib JAR (" + Files.size(extractedJar) / 1024 + " KB)");

            // 4. 对 lib jar 打补丁
            Path patchedJar = tempDir.resolve("patched-" + innerLibName);
            StagingPackageBuilder builder = new StagingPackageBuilder(
                    modulePath, artifactFileName, changedFiles, logCallback);
            int changed = builder.buildFromLocal(extractedJar, patchedJar);
            if (changed <= 0) return 0;

            // 5. 回写 war — 传 innerLibName 的 base name 作为精确前缀，
            //    避免 WarEmbedUtil.embedJar 在同前缀多版本场景下挑错
            String exactBaseName = stripExt(innerLibName);
            logCallback.accept("[本地] 回写 WAR（精确替换 " + innerLibName + "）...");
            WarEmbedUtil.EmbedResult embed = WarEmbedUtil.embedJar(
                    localWar, patchedJar, exactBaseName, outputWar);
            if (!embed.isVerified()) {
                throw new IOException("WAR 回写校验失败: " + embed.getMessage());
            }
            return changed;
        } finally {
            deleteTree(tempDir);
        }
    }

    /**
     * 从 jar/war 中抽出指定条目到目标路径
     */
    private static void extractEntry(Path archive, String entryName, Path dest) throws IOException {
        try (JarFile jar = new JarFile(archive.toFile())) {
            JarEntry e = jar.getJarEntry(entryName);
            if (e == null) throw new IOException("条目不存在: " + entryName);
            Files.createDirectories(dest.getParent());
            try (InputStream is = jar.getInputStream(e)) {
                Files.copy(is, dest, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    /** 递归删除目录 */
    private static void deleteTree(Path dir) {
        try {
            Files.walkFileTree(dir, new java.nio.file.SimpleFileVisitor<>() {
                @Override
                public java.nio.file.FileVisitResult visitFile(Path f,
                        java.nio.file.attribute.BasicFileAttributes a) throws IOException {
                    Files.delete(f);
                    return java.nio.file.FileVisitResult.CONTINUE;
                }
                @Override
                public java.nio.file.FileVisitResult postVisitDirectory(Path d,
                        IOException e) throws IOException {
                    Files.delete(d);
                    return java.nio.file.FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException ignored) {}
    }

    /**
     * 去除文件名扩展名
     *
     * @param fileName 文件名
     * @return 去除扩展名后的文件名；无扩展名时返回原字符串
     * @author xumanyi
     * @date 2026-04-17
     */
    private static String stripExt(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    /**
     * 提取 artifactId 前缀，例如 scev6-utils-tms-10.0.0-SNAPSHOT.jar → scev6-utils-tms
     *
     * @param fileName 产物文件名（含扩展名）
     * @return artifactId 前缀；无法识别版本号分隔符时返回去扩展名结果
     * @author xumanyi
     * @date 2026-04-17
     */
    private static String extractArtifactPrefix(String fileName) {
        String name = stripExt(fileName);
        for (int i = 1; i < name.length(); i++) {
            if (name.charAt(i - 1) == '-' && Character.isDigit(name.charAt(i))) {
                return name.substring(0, i - 1);
            }
        }
        return name;
    }

    /**
     * 构造失败结果并记录错误日志
     *
     * @param logCallback 日志回调
     * @param msg         错误信息
     * @return 标记失败的 {@link LocalPatchResult}
     * @author xumanyi
     * @date 2026-04-17
     */
    private static LocalPatchResult fail(Consumer<String> logCallback, String msg) {
        logCallback.accept("[本地][错误] " + msg);
        return new LocalPatchResult(false, null, 0, msg);
    }

    /**
     * 场景枚举
     */
    private enum Scenario {
        JAR_TO_JAR("源 JAR → 本地 JAR"),
        WAR_TO_WAR("源 WAR → 本地 WAR"),
        JAR_TO_WAR_LIB("源 JAR → 本地 WAR/lib");

        final String description;
        Scenario(String description) { this.description = description; }
    }

    // ==================== 预检支持 ====================

    /**
     * 预检条目描述：包内路径 + 变更类型（修改 / 新增 / 删除）
     */
    public static class PreviewEntry {
        public enum ChangeType { MODIFY, ADD, DELETE }
        private final String entryPath;
        private final ChangeType changeType;

        public PreviewEntry(String entryPath, ChangeType changeType) {
            this.entryPath = entryPath;
            this.changeType = changeType;
        }
        public String getEntryPath() { return entryPath; }
        public ChangeType getChangeType() { return changeType; }
    }

    /**
     * 预检：生成变更预览清单（不写新包）
     *
     * <p>FULL 模式下预览内容为整包更新的摘要（不做文件级对比）；
     * 其他模式下遍历源工程变更文件与用户提供包内已存在的条目做比对，标注修改 / 新增 / 删除。</p>
     *
     * @param mode             更新模式
     * @return 预览结果：包含场景说明、变更清单、是否通过校验
     */
    public static PreCheckResult preCheck(DeployMode mode,
                                            String modulePath, String artifactFileName,
                                            List<String> changedFiles,
                                            String localPackagePath,
                                            Consumer<String> logCallback) {
        try {
            Path localPackage = Path.of(localPackagePath);
            if (!Files.isRegularFile(localPackage)) {
                return PreCheckResult.fail("本地包不存在: " + localPackagePath);
            }
            if (artifactFileName == null || artifactFileName.isEmpty()) {
                return PreCheckResult.fail("未解析到源工程产物文件名");
            }
            boolean fullMode = mode == DeployMode.FULL;
            if (!fullMode && (changedFiles == null || changedFiles.isEmpty())) {
                return PreCheckResult.fail("未勾选任何待更新文件");
            }

            String pkgName = localPackage.getFileName().toString();
            Scenario scenario = dispatch(artifactFileName, pkgName);
            if (scenario == null) {
                return PreCheckResult.fail("源工程为 WAR 但提供了 JAR 包，无法处理");
            }

            // FULL 模式：不做文件级对比，返回场景摘要
            if (fullMode) {
                String scopeDescription = scenario.description + "（整包更新）";
                List<PreviewEntry> previews = new ArrayList<>();
                switch (scenario) {
                    case JAR_TO_JAR:
                    case WAR_TO_WAR:
                        previews.add(new PreviewEntry(
                                "整个包被新编译的 " + artifactFileName + " 覆盖",
                                PreviewEntry.ChangeType.MODIFY));
                        break;
                    case JAR_TO_WAR_LIB:
                        String prefix = extractArtifactPrefix(artifactFileName);
                        List<String> candidates = collectInnerLibJars(localPackage, prefix);
                        if (candidates.isEmpty()) {
                            return PreCheckResult.fail("WAR 的 WEB-INF/lib 下未找到匹配 ["
                                    + prefix + "] 的 JAR");
                        }
                        // FULL 模式：只要有前缀匹配即可，用新产物整体替换该 jar（名字用新产物名）
                        String pickedFull = pickVersionMatching(candidates, artifactFileName);
                        String targetLib = pickedFull != null ? pickedFull : candidates.get(0);
                        previews.add(new PreviewEntry(
                                "WEB-INF/lib/" + targetLib + " 被新编译的 "
                                        + artifactFileName + " 替换",
                                PreviewEntry.ChangeType.MODIFY));
                        break;
                }
                return PreCheckResult.ok(scopeDescription, previews);
            }

            // 构造补丁器，获取拟变更条目
            StagingPackageBuilder builder = new StagingPackageBuilder(
                    modulePath, artifactFileName, changedFiles, logCallback);
            Map<String, Path> changes = builder.previewChangedEntries();
            Set<String> deletes = builder.getDeletePreviewEntries();

            // 计算实际比对的原包：war/lib 场景需要抽出 lib jar
            Set<String> existingEntries;
            String scopeDescription;
            if (scenario == Scenario.JAR_TO_WAR_LIB) {
                String prefix = extractArtifactPrefix(artifactFileName);
                List<String> candidates = collectInnerLibJars(localPackage, prefix);
                if (candidates.isEmpty()) {
                    return PreCheckResult.fail("WAR 的 WEB-INF/lib 下未找到匹配 [" + prefix + "] 的 JAR");
                }
                // 从候选中挑版本严格一致的，没找到则报错列出候选
                String innerLibName = pickVersionMatching(candidates, artifactFileName);
                if (innerLibName == null) {
                    return PreCheckResult.fail("WAR 的 WEB-INF/lib 下存在前缀 [" + prefix
                            + "] 的 JAR（" + String.join(", ", candidates)
                            + "），但没有版本与源产物 " + artifactFileName + " 一致的。");
                }
                if (candidates.size() > 1) {
                    logCallback.accept("[本地] 候选 " + candidates.size() + " 个，挑选版本一致的: " + innerLibName);
                }
                scopeDescription = scenario.description + "（WEB-INF/lib/" + innerLibName + "）";
                Path tempDir = Files.createTempDirectory("flux-local-precheck-");
                try {
                    Path extracted = tempDir.resolve(innerLibName);
                    extractEntry(localPackage, "WEB-INF/lib/" + innerLibName, extracted);
                    existingEntries = listEntries(extracted);
                } finally {
                    deleteTree(tempDir);
                }
            } else {
                scopeDescription = scenario.description;
                existingEntries = listEntries(localPackage);
            }

            List<PreviewEntry> previews = new ArrayList<>();
            for (String e : changes.keySet()) {
                previews.add(new PreviewEntry(e,
                        existingEntries.contains(e) ? PreviewEntry.ChangeType.MODIFY : PreviewEntry.ChangeType.ADD));
            }
            for (String del : deletes) {
                if (del.endsWith("$")) continue; // 内部类前缀标记，跳过显示
                if (existingEntries.contains(del)) {
                    previews.add(new PreviewEntry(del, PreviewEntry.ChangeType.DELETE));
                }
            }
            // 残留内部类清理：主类被修改但某些 Foo$N.class 本地已不存在 → 也应删除
            Set<String> orphans = builder.previewOrphanDeletes(existingEntries);
            for (String orphan : orphans) {
                // 已在 changes 里（即将被覆盖）或已在 deletes 里（显式删除）的跳过
                if (changes.containsKey(orphan) || deletes.contains(orphan)) continue;
                previews.add(new PreviewEntry(orphan, PreviewEntry.ChangeType.DELETE));
            }

            return PreCheckResult.ok(scopeDescription, previews);
        } catch (Exception e) {
            return PreCheckResult.fail(e.getMessage());
        }
    }

    private static Set<String> listEntries(Path archive) throws IOException {
        Set<String> set = new HashSet<>();
        try (JarFile jar = new JarFile(archive.toFile())) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                set.add(entries.nextElement().getName());
            }
        }
        return set;
    }

    /**
     * 旧接口：返回 war 内第一个前缀匹配的 jar（不做版本校验）。
     * 当前改用 {@link #collectInnerLibJars} + {@link #pickVersionMatching} 组合以支持多版本场景。
     */
    private static String findInnerLibJar(Path war, String prefix) throws IOException {
        List<String> all = collectInnerLibJars(war, prefix);
        return all.isEmpty() ? null : all.get(0);
    }

    /**
     * 列出 war 内 WEB-INF/lib 下所有前缀匹配且形如 {@code prefix-{数字}...jar} 的 jar 名
     *
     * @author xumanyi
     * @date 2026-04-19
     */
    private static List<String> collectInnerLibJars(Path war, String prefix) throws IOException {
        List<String> result = new ArrayList<>();
        try (JarFile warJar = new JarFile(war.toFile())) {
            Enumeration<JarEntry> entries = warJar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String name = entry.getName();
                if (!name.startsWith("WEB-INF/lib/")) continue;
                if (entry.isDirectory()) continue;
                String libName = name.substring("WEB-INF/lib/".length());
                if (libName.startsWith(prefix) && libName.endsWith(".jar")
                        && libName.length() > prefix.length() + 1
                        && libName.charAt(prefix.length()) == '-'
                        && Character.isDigit(libName.charAt(prefix.length() + 1))) {
                    result.add(libName);
                }
            }
        }
        return result;
    }

    /**
     * 从候选 lib jar 里挑一个版本与源产物完全一致的（去扩展名 + 不区分大小写）
     *
     * @return 匹配项；没有匹配返回 null
     * @author xumanyi
     * @date 2026-04-19
     */
    private static String pickVersionMatching(List<String> candidates, String expected) {
        String expBase = stripExt(expected);
        for (String c : candidates) {
            if (stripExt(c).equalsIgnoreCase(expBase)) return c;
        }
        return null;
    }

    /**
     * 预检结果
     */
    public static class PreCheckResult {
        private final boolean ok;
        private final String scopeDescription;
        private final List<PreviewEntry> previews;
        private final String errorMessage;

        private PreCheckResult(boolean ok, String scopeDescription,
                               List<PreviewEntry> previews, String errorMessage) {
            this.ok = ok;
            this.scopeDescription = scopeDescription;
            this.previews = previews;
            this.errorMessage = errorMessage;
        }

        static PreCheckResult ok(String scope, List<PreviewEntry> previews) {
            return new PreCheckResult(true, scope, previews, null);
        }
        static PreCheckResult fail(String msg) {
            return new PreCheckResult(false, null, List.of(), msg);
        }

        public boolean isOk() { return ok; }
        public String getScopeDescription() { return scopeDescription; }
        public List<PreviewEntry> getPreviews() { return previews; }
        public String getErrorMessage() { return errorMessage; }
    }
}
