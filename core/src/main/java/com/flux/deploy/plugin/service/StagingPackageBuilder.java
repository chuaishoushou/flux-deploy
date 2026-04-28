package com.flux.deploy.plugin.service;

import com.flux.deploy.ftp.FtpOperations;
import com.flux.deploy.ftp.FtpSession;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 暂存包构建器
 *
 * <p>增量更新和自动检索模式的核心逻辑：</p>
 * <ol>
 *   <li>从 FTP 下载远程原包</li>
 *   <li>编译项目获取新的 class 文件</li>
 *   <li>将选中文件的 class 替换到原包中</li>
 *   <li>生成暂存包供上传</li>
 * </ol>
 *
 * <p>支持 Java 源文件（含内部类）和资源文件的增量替换。</p>
 *
 * @author xumanyi
 * @date 2026-03-27
 */
public class StagingPackageBuilder {

    private final String modulePath;
    private final String artifactFileName;
    private final List<String> changedSourceFiles;
    private final Consumer<String> logCallback;

    /**
     * 构造暂存包构建器
     *
     * @param modulePath         模块根目录路径
     * @param artifactFileName   产物文件名
     * @param changedSourceFiles 变更的源文件列表（可含 git status 前缀）
     * @param logCallback        日志回调
     * @author xumanyi
     * @date 2026-03-27
     */
    public StagingPackageBuilder(String modulePath, String artifactFileName,
                                  List<String> changedSourceFiles, Consumer<String> logCallback) {
        this.modulePath = modulePath;
        this.artifactFileName = artifactFileName;
        this.changedSourceFiles = changedSourceFiles;
        this.logCallback = logCallback;
    }

    /**
     * 构建暂存包
     *
     * <p>下载远程包 → 定位变更 class → 替换到包中 → 返回暂存包路径</p>
     *
     * @param ftpHost     FTP 主机
     * @param ftpPort     FTP 端口
     * @param ftpUsername  FTP 用户名
     * @param ftpPassword  FTP 密码
     * @param remotePath  远程包完整路径（如 /开发/项目/系统/shared-tms/xxx.jar）
     * @return 暂存包路径
     * @author xumanyi
     * @date 2026-03-27
     */
    public Path build(String ftpHost, int ftpPort, String ftpUsername, String ftpPassword,
                      String remotePath) throws IOException {

        Path moduleRoot = Path.of(modulePath);
        Path targetDir = moduleRoot.resolve("target");
        Path classesDir = targetDir.resolve("classes");

        // 1. 查找变更文件对应的 class 文件
        logCallback.accept("[暂存包] 分析变更文件...");
        Map<String, Path> classEntries = resolveChangedClasses(moduleRoot, classesDir);

        if (classEntries.isEmpty()) {
            logCallback.accept("[错误] 未找到变更文件对应的 class 文件，请确认已编译");
            return null;
        }

        logCallback.accept("[暂存包] 找到 " + classEntries.size() + " 个 class 文件需要替换：");
        for (String entry : classEntries.keySet()) {
            logCallback.accept("  → " + entry);
        }

        // 2. 下载远程原包
        logCallback.accept("[暂存包] 下载远程包: " + remotePath);
        Path downloadedPackage = targetDir.resolve("__remote_" + artifactFileName);
        downloadRemotePackage(ftpHost, ftpPort, ftpUsername, ftpPassword, remotePath, downloadedPackage);
        logCallback.accept("[暂存包] 下载完成 (" + Files.size(downloadedPackage) / 1024 + " KB)");

        // 3. 替换 class 文件，生成暂存包
        Path stagingPackage = targetDir.resolve("__staging_" + artifactFileName);
        logCallback.accept("[暂存包] 替换 class 文件...");
        int replaced = patchJar(downloadedPackage, stagingPackage, classEntries);
        logCallback.accept("[暂存包] 已替换 " + replaced + " 个条目，暂存包已生成 ("
                + Files.size(stagingPackage) / 1024 + " KB)");

        // 4. 清理下载的原包
        Files.deleteIfExists(downloadedPackage);

        return stagingPackage;
    }

    /**
     * 本地模式：对给定本地 jar/war 包做增量补丁，输出到指定路径
     *
     * <p>与 {@link #build} 的差异：不下载远程包，直接使用用户提供的本地包。
     * 用于"本地模式"流程，不涉及 FTP、备份、版本记录、回滚等任何远端操作。</p>
     *
     * <p>源产物类型由构造时传入的 {@code artifactFileName} 决定：</p>
     * <ul>
     *   <li>源 jar → 本地 jar：替换 jar 内 class / 资源（包内路径无 WEB-INF/classes 前缀）</li>
     *   <li>源 war → 本地 war：替换 war 内 class / 资源（包内路径含 WEB-INF/classes 前缀）</li>
     * </ul>
     *
     * <p>"源 jar → 本地 war/lib" 场景不由本方法直接处理，
     * 由 {@link LocalPackagePatchService} 组合 {@link com.flux.deploy.util.WarEmbedUtil} 完成。</p>
     *
     * @param localPackage 本地原始包（jar / war）
     * @param outputPath   输出新包绝对路径（同名，位于独立的 patched 目录）
     * @return 变更的条目数（替换 + 新增 + 删除）
     * @throws IOException 解析或打包失败
     * @author xumanyi
     * @date 2026-04-17
     */
    public int buildFromLocal(Path localPackage, Path outputPath) throws IOException {
        Path moduleRoot = Path.of(modulePath);
        Path classesDir = moduleRoot.resolve("target/classes");

        logCallback.accept("[本地补丁] 分析变更文件...");
        Map<String, Path> classEntries = resolveChangedClasses(moduleRoot, classesDir);

        if (classEntries.isEmpty() && deleteEntries.isEmpty()) {
            logCallback.accept("[错误] 未找到变更文件对应的 class 文件，请确认已编译");
            return 0;
        }

        logCallback.accept("[本地补丁] 待替换/新增 " + classEntries.size()
                + " 条，待删除 " + deleteEntries.size() + " 条");

        Files.createDirectories(outputPath.getParent());
        int count = patchJar(localPackage, outputPath, classEntries);
        logCallback.accept("[本地补丁] 已变更 " + count + " 个条目，输出包: "
                + outputPath.getFileName() + " (" + Files.size(outputPath) / 1024 + " KB)");
        return count;
    }

    /**
     * 预检：解析变更文件对应的包内条目，返回预览清单（不写包）
     *
     * @return 包内条目 → 本地 class/资源路径；同时可通过 {@link #getDeletePreviewEntries()} 获取删除清单
     */
    public Map<String, Path> previewChangedEntries() throws IOException {
        Path moduleRoot = Path.of(modulePath);
        Path classesDir = moduleRoot.resolve("target/classes");
        return resolveChangedClasses(moduleRoot, classesDir);
    }

    /**
     * 预检：获取当前累计的删除条目集合（仅在 {@link #previewChangedEntries()} 调用后有效）
     */
    public Set<String> getDeletePreviewEntries() {
        return java.util.Collections.unmodifiableSet(deleteEntries);
    }

    /**
     * 对已有 JAR 做增量补丁：只替换修改的 class 文件
     *
     * <p>用于 WAR 嵌入场景：从远程 WAR 中提取嵌入 JAR 后，
     * 只替换修改的 class 而不是整个替换 JAR。</p>
     *
     * @param existingJar 远程提取的嵌入 JAR
     * @param outputDir   临时输出目录
     * @return 打好补丁的 JAR 路径，失败返回 null
     */
    public Path patchExistingJar(Path existingJar, Path outputDir) throws IOException {
        Path moduleRoot = Path.of(modulePath);
        Path classesDir = moduleRoot.resolve("target/classes");

        // 解析变更文件对应的 class
        Map<String, Path> classEntries = resolveChangedClasses(moduleRoot, classesDir);
        if (classEntries.isEmpty() && deleteEntries.isEmpty()) {
            logCallback.accept("[补丁] 未找到需要替换的 class 文件");
            return null;
        }

        logCallback.accept("[补丁] 找到 " + classEntries.size() + " 个 class 需要替换"
                + (deleteEntries.isEmpty() ? "" : ", " + deleteEntries.size() + " 个需要删除"));

        // 对提取的 JAR 做补丁
        Path patchedJar = outputDir.resolve("patched-" + existingJar.getFileName());
        int count = patchJar(existingJar, patchedJar, classEntries);
        logCallback.accept("[补丁] 已替换/删除 " + count + " 个条目");

        return patchedJar;
    }

    /**
     * 分析变更的 .java 源文件，找到对应的 .class 文件
     *
     * <p>处理内部类：Foo.java → Foo.class, Foo$1.class, Foo$Inner.class</p>
     *
     * @param moduleRoot 模块根目录
     * @param classesDir 编译输出目录 (target/classes)
     * @return Map<jar内条目路径, 本地class文件路径>
     * @author xumanyi
     * @date 2026-03-27
     */
    /** 标记需要从包中删除的条目（value 为 null 表示删除） */
    private final Set<String> deleteEntries = new HashSet<>();

    /**
     * 内部类残留清理：当 Foo.java 被修改时，记录 "com/flux/Foo$" 前缀，
     * 打包时会清理该前缀下在 local class 集合之外的所有旧内部类条目。
     * 用于修复 "用户重构时删掉了 Foo$7 内部类，但 Foo$7.class 残留在远端包里" 的问题。
     */
    private final Set<String> innerClassPrunePrefixes = new LinkedHashSet<>();
    /** 本地扫描到的主类 + 内部类条目集合（是保留的，不能被残留清理误删） */
    private final Set<String> localClassEntries = new HashSet<>();

    private Map<String, Path> resolveChangedClasses(Path moduleRoot, Path classesDir) throws IOException {
        Map<String, Path> result = new LinkedHashMap<>();
        Path srcMainJava = moduleRoot.resolve("src/main/java");
        boolean isWar = artifactFileName != null && artifactFileName.toLowerCase().endsWith(".war");

        for (String rawSourceFile : changedSourceFiles) {
            // 提取 git status 前缀
            String status = "";
            String sourceFile = rawSourceFile;
            if (sourceFile.length() > 2 && sourceFile.charAt(1) == ' ') {
                status = String.valueOf(sourceFile.charAt(0));
                sourceFile = sourceFile.substring(sourceFile.indexOf(' ')).trim();
            }

            // 删除的文件：计算包内路径，标记为待删除
            if ("D".equals(status)) {
                handleDeletedFile(moduleRoot, sourceFile, isWar);
                continue;
            }

            Path sourcePath = Path.of(sourceFile);

            // 非 .java 文件：资源文件处理
            if (!sourceFile.endsWith(".java")) {
                handleResourceFile(moduleRoot, sourceFile, result, isWar);
                continue;
            }

            // 计算包路径：src/main/java/com/flux/Foo.java → com/flux/Foo
            String relativePath;
            if (sourcePath.isAbsolute()) {
                // 绝对路径
                if (sourcePath.startsWith(srcMainJava)) {
                    relativePath = srcMainJava.relativize(sourcePath).toString();
                } else {
                    // 尝试从路径中提取 src/main/java 后面的部分
                    String pathStr = sourcePath.toString();
                    int idx = pathStr.indexOf("src/main/java/");
                    if (idx >= 0) {
                        relativePath = pathStr.substring(idx + "src/main/java/".length());
                    } else {
                        logCallback.accept("[警告] 无法解析源文件路径: " + sourceFile);
                        continue;
                    }
                }
            } else {
                // 相对路径
                if (sourceFile.startsWith("src/main/java/")) {
                    relativePath = sourceFile.substring("src/main/java/".length());
                } else {
                    relativePath = sourceFile;
                }
            }

            // com/flux/Foo.java → com/flux/Foo
            String classBaseName = relativePath.replace(".java", "");
            // com/flux/Foo → 在 classesDir 下查找 com/flux/Foo.class 和 com/flux/Foo$*.class
            String className = classBaseName.substring(classBaseName.lastIndexOf('/') + 1);
            String packageDir = classBaseName.contains("/")
                    ? classBaseName.substring(0, classBaseName.lastIndexOf('/'))
                    : "";

            Path classPackageDir = classesDir.resolve(packageDir);
            if (!Files.isDirectory(classPackageDir)) {
                logCallback.accept("[警告] class 目录不存在: " + classPackageDir);
                continue;
            }

            // 查找主 class 和内部类
            boolean foundMainClass = false;
            try (Stream<Path> files = Files.list(classPackageDir)) {
                List<Path> matchedClasses = files
                        .filter(f -> {
                            String name = f.getFileName().toString();
                            return name.equals(className + ".class")
                                    || name.startsWith(className + "$");
                        })
                        .collect(Collectors.toList());

                for (Path classFile : matchedClasses) {
                    // class 在包内的路径
                    String entryPath = classesDir.relativize(classFile).toString();
                    entryPath = entryPath.replace(File.separatorChar, '/');
                    // WAR 包：class 在 WEB-INF/classes/ 下
                    if (isWar) {
                        entryPath = "WEB-INF/classes/" + entryPath;
                    }
                    result.put(entryPath, classFile);
                    localClassEntries.add(entryPath);
                    if (classFile.getFileName().toString().equals(className + ".class")) {
                        foundMainClass = true;
                    }
                }
            }

            // 记录残留清理前缀（针对主类名），例如 "com/flux/Foo$"（war 模式再加 WEB-INF/classes/ 前缀）
            // 安全守卫：只有本地找到主 class 时才激活。主 class 缺失说明编译未完成或产物异常，
            // 此时启用残留清理会误杀包里全部 Foo$*.class。
            if (foundMainClass) {
                String prunePrefix = (packageDir.isEmpty() ? "" : packageDir + "/") + className + "$";
                if (isWar) prunePrefix = "WEB-INF/classes/" + prunePrefix;
                innerClassPrunePrefixes.add(prunePrefix);
            } else {
                logCallback.accept("[警告] 未找到本地主 class " + className
                        + ".class，跳过内部类残留清理（请确认编译已完成）");
            }
        }

        return result;
    }

    /**
     * 处理非 Java 资源文件（.xml, .properties, .js, .css, .html, .jsp 等）
     *
     * <p>根据文件所在目录和目标包类型决定映射方式：</p>
     * <ul>
     *   <li>src/main/webapp/ → WAR 根目录（直接使用源文件，不经过编译）</li>
     *   <li>src/main/resources/ → JAR 根目录 或 WAR 的 WEB-INF/classes/</li>
     * </ul>
     *
     * @param isWar 目标包是否为 WAR（影响 resources 文件的包内路径）
     */
    private void handleResourceFile(Path moduleRoot, String sourceFile, Map<String, Path> result,
                                     boolean isWar) throws IOException {
        Path sourcePath = Path.of(sourceFile);
        if (!sourcePath.isAbsolute()) {
            sourcePath = moduleRoot.resolve(sourceFile);
        }

        String pathStr = sourcePath.toString().replace(File.separatorChar, '/');

        // 1. src/main/webapp/ → WAR 根目录（直接用源文件）
        int webappIdx = pathStr.indexOf("src/main/webapp/");
        if (webappIdx >= 0) {
            String webRelative = pathStr.substring(webappIdx + "src/main/webapp/".length());
            if (Files.exists(sourcePath)) {
                result.put(webRelative, sourcePath);
                return;
            }
        }

        // 2. src/main/resources/ → JAR: 根目录 / WAR: WEB-INF/classes/
        int resIdx = pathStr.indexOf("src/main/resources/");
        if (resIdx >= 0) {
            String resRelative = pathStr.substring(resIdx + "src/main/resources/".length());
            Path classesFile = moduleRoot.resolve("target/classes").resolve(resRelative);
            if (Files.exists(classesFile)) {
                // WAR 包：resources 打包到 WEB-INF/classes/ 下
                String entryPath = isWar ? "WEB-INF/classes/" + resRelative : resRelative;
                result.put(entryPath, classesFile);
                return;
            }
        }

        // 3. src/main/java/ 下的非 .java 文件（如 .xml mapper 文件）
        int javaIdx = pathStr.indexOf("src/main/java/");
        if (javaIdx >= 0) {
            String javaRelative = pathStr.substring(javaIdx + "src/main/java/".length());
            Path classesFile = moduleRoot.resolve("target/classes").resolve(javaRelative);
            if (Files.exists(classesFile)) {
                String entryPath = isWar ? "WEB-INF/classes/" + javaRelative : javaRelative;
                result.put(entryPath, classesFile);
                return;
            }
        }

        // 4. 无法识别的路径
        logCallback.accept("[警告] 无法确定资源文件的包内路径: " + sourceFile);
    }

    /**
     * 处理删除的文件：计算包内路径，标记为待删除
     *
     * <p>删除的文件在磁盘上已不存在，无法从 target/classes/ 获取。
     * 需要根据源文件路径推算包内条目路径，在 patchJar 时跳过该条目。</p>
     */
    private void handleDeletedFile(Path moduleRoot, String sourceFile, boolean isWar) {
        String pathStr = sourceFile.replace(File.separatorChar, '/');

        // .java 文件 → 删除对应 .class（含内部类）
        if (sourceFile.endsWith(".java")) {
            String rel = pathStr;
            if (rel.startsWith("src/main/java/")) {
                rel = rel.substring("src/main/java/".length());
            }
            String classBase = rel.replace(".java", "");
            String prefix = isWar ? "WEB-INF/classes/" : "";
            // 主 class + 内部类用通配符标记（patchJar 里按前缀匹配）
            deleteEntries.add(prefix + classBase + ".class");
            deleteEntries.add(prefix + classBase + "$"); // 内部类前缀
            logCallback.accept("  [删除] " + prefix + classBase + ".class (+ 内部类)");
            return;
        }

        // src/main/webapp/ 文件
        int webappIdx = pathStr.indexOf("src/main/webapp/");
        if (webappIdx >= 0) {
            String entry = pathStr.substring(webappIdx + "src/main/webapp/".length());
            deleteEntries.add(entry);
            logCallback.accept("  [删除] " + entry);
            return;
        }

        // src/main/resources/ 文件
        int resIdx = pathStr.indexOf("src/main/resources/");
        if (resIdx >= 0) {
            String rel = pathStr.substring(resIdx + "src/main/resources/".length());
            String entry = isWar ? "WEB-INF/classes/" + rel : rel;
            deleteEntries.add(entry);
            logCallback.accept("  [删除] " + entry);
            return;
        }

        // src/main/java/ 下的非 .java 文件
        int javaIdx = pathStr.indexOf("src/main/java/");
        if (javaIdx >= 0) {
            String rel = pathStr.substring(javaIdx + "src/main/java/".length());
            String entry = isWar ? "WEB-INF/classes/" + rel : rel;
            deleteEntries.add(entry);
            logCallback.accept("  [删除] " + entry);
            return;
        }

        logCallback.accept("[警告] 无法确定删除文件的包内路径: " + sourceFile);
    }

    /**
     * 检查包内条目是否应被删除
     *
     * <p>支持三类删除来源：</p>
     * <ul>
     *   <li>精确匹配 {@link #deleteEntries}（源文件被 git 识别为 D）</li>
     *   <li>前缀 "Foo$" 匹配（源 .java 被删除时，波及其所有内部类）</li>
     *   <li>残留内部类清理：主类被修改时，原包内匹配 "Foo$" 前缀但本地不存在的条目</li>
     * </ul>
     */
    private boolean shouldDelete(String entryName) {
        if (deleteEntries.contains(entryName)) return true;
        for (String prefix : deleteEntries) {
            if (prefix.endsWith("$") && entryName.startsWith(prefix)) {
                return true;
            }
        }
        // 残留清理：条目在修改的主类的内部类前缀下，但本地没有对应文件 → 删除
        for (String prefix : innerClassPrunePrefixes) {
            if (entryName.startsWith(prefix) && entryName.endsWith(".class")
                    && !localClassEntries.contains(entryName)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 返回将被残留清理删除的条目集合（仅供预检展示使用）
     *
     * @param existingEntries 原包中全部条目名
     * @return 被识别为残留的条目（主类被修改但内部类在本地已消失的）
     */
    public Set<String> previewOrphanDeletes(Set<String> existingEntries) {
        Set<String> orphans = new LinkedHashSet<>();
        for (String entry : existingEntries) {
            if (!entry.endsWith(".class")) continue;
            for (String prefix : innerClassPrunePrefixes) {
                if (entry.startsWith(prefix) && !localClassEntries.contains(entry)) {
                    orphans.add(entry);
                    break;
                }
            }
        }
        return orphans;
    }

    /**
     * 从 FTP 下载远程包
     */
    private void downloadRemotePackage(String host, int port, String username, String password,
                                        String remotePath, Path localPath) throws IOException {
        try (FtpSession session = new FtpSession(host, port)) {
            session.connect(username, password);
            FtpOperations ops = new FtpOperations(session);
            ops.download(remotePath, localPath);
        }
    }

    /**
     * 全量 WAR 包 lib 对齐：以远程 war 的 WEB-INF/lib/ 为准
     *
     * <p>逻辑：以本地新 war 为基础，但 WEB-INF/lib/ 下的 jar 只保留远程包中存在的。
     * 远程有而本地没有的 lib → 从远程复制；本地有而远程没有的 lib → 丢弃。</p>
     *
     * @param localWar     本地编译的 war 文件
     * @param ftpHost      FTP 主机
     * @param ftpPort      FTP 端口
     * @param ftpUsername   FTP 用户名
     * @param ftpPassword  FTP 密码
     * @param remotePath   远程 war 完整路径
     * @param logCallback  日志回调
     * @return 对齐后的暂存 war 路径
     */
    public static Path alignWarLibs(Path localWar, String ftpHost, int ftpPort,
                                      String ftpUsername, String ftpPassword,
                                      String remotePath,
                                      Consumer<String> logCallback) throws IOException {

        Path targetDir = localWar.getParent();
        Path remoteWar = targetDir.resolve("__remote_" + localWar.getFileName());
        Path alignedWar = targetDir.resolve("__aligned_" + localWar.getFileName());

        // 1. 下载远程 war
        logCallback.accept("[lib对齐] 下载远程 WAR: " + remotePath);
        try (FtpSession session = new FtpSession(ftpHost, ftpPort)) {
            session.connect(ftpUsername, ftpPassword);
            new FtpOperations(session).download(remotePath, remoteWar);
        }
        logCallback.accept("[lib对齐] 下载完成 (" + Files.size(remoteWar) / 1024 / 1024 + " MB)");

        // 2. 收集远程 war 的 WEB-INF/lib/ 文件名集合
        Set<String> remoteLibNames = new HashSet<>();
        Map<String, JarEntry> remoteLibEntries = new LinkedHashMap<>();
        try (JarFile remoteJar = new JarFile(remoteWar.toFile())) {
            Enumeration<JarEntry> entries = remoteJar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (entry.getName().startsWith("WEB-INF/lib/") && !entry.isDirectory()) {
                    String libName = entry.getName().substring("WEB-INF/lib/".length());
                    remoteLibNames.add(libName);
                    remoteLibEntries.put(entry.getName(), entry);
                }
            }
        }
        logCallback.accept("[lib对齐] 远程 WAR 共 " + remoteLibNames.size() + " 个 lib");

        // 3. 构建对齐后的 war：本地非 lib 内容 + 远程 lib
        int kept = 0, removed = 0, fromRemote = 0;

        try (JarFile localJar = new JarFile(localWar.toFile());
             JarFile remoteJar = new JarFile(remoteWar.toFile());
             JarOutputStream jos = new JarOutputStream(
                     new BufferedOutputStream(Files.newOutputStream(alignedWar)))) {

            Set<String> processedLibs = new HashSet<>();

            // 遍历本地 war 的所有条目
            Enumeration<JarEntry> entries = localJar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String name = entry.getName();

                if (name.startsWith("WEB-INF/lib/") && !entry.isDirectory()) {
                    String libName = name.substring("WEB-INF/lib/".length());
                    if (remoteLibNames.contains(libName)) {
                        // 远程有这个 lib → 保留本地的（更新后的版本）
                        JarEntry newEntry = new JarEntry(name);
                        jos.putNextEntry(newEntry);
                        try (InputStream is = localJar.getInputStream(entry)) {
                            is.transferTo(jos);
                        }
                        jos.closeEntry();
                        processedLibs.add(libName);
                        kept++;
                    } else {
                        // 远程没有 → 丢弃
                        removed++;
                    }
                } else {
                    // 非 lib 内容：原样复制
                    JarEntry newEntry = new JarEntry(entry);
                    newEntry.setCompressedSize(-1);
                    jos.putNextEntry(newEntry);
                    try (InputStream is = localJar.getInputStream(entry)) {
                        is.transferTo(jos);
                    }
                    jos.closeEntry();
                }
            }

            // 补充远程有但本地没有的 lib
            for (Map.Entry<String, JarEntry> re : remoteLibEntries.entrySet()) {
                String libName = re.getKey().substring("WEB-INF/lib/".length());
                if (!processedLibs.contains(libName)) {
                    JarEntry remoteEntry = re.getValue();
                    JarEntry newEntry = new JarEntry(re.getKey());
                    jos.putNextEntry(newEntry);
                    try (InputStream is = remoteJar.getInputStream(remoteEntry)) {
                        is.transferTo(jos);
                    }
                    jos.closeEntry();
                    fromRemote++;
                }
            }
        }

        logCallback.accept("[lib对齐] 保留 " + kept + " 个, 移除 " + removed
                + " 个, 从远程补充 " + fromRemote + " 个");

        // 4. 清理下载的远程包
        Files.deleteIfExists(remoteWar);

        return alignedWar;
    }

    /**
     * 替换 jar/war 中的指定条目
     *
     * <p>遍历原包的所有条目，如果条目在 classEntries 中则替换为新内容，否则原样复制。
     * 对于原包中不存在的新条目，追加到末尾。</p>
     *
     * @param originalJar  原始 jar/war 文件
     * @param outputJar    输出暂存包路径
     * @param classEntries 要替换的条目（key=jar条目路径, value=新class文件路径）
     * @return 实际替换/新增的条目数
     * @author xumanyi
     * @date 2026-03-27
     */
    private int patchJar(Path originalJar, Path outputJar, Map<String, Path> classEntries) throws IOException {
        int count = 0;
        Set<String> processedEntries = new HashSet<>();

        try (JarFile jar = new JarFile(originalJar.toFile());
             JarOutputStream jos = new JarOutputStream(new BufferedOutputStream(Files.newOutputStream(outputJar)))) {

            // 遍历原包所有条目
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String entryName = entry.getName();

                // 检查是否需要删除此条目
                if (shouldDelete(entryName)) {
                    logCallback.accept("  - 删除: " + entryName);
                    count++;
                    continue; // 跳过，不写入输出包
                }

                if (classEntries.containsKey(entryName)) {
                    // 替换为新 class
                    JarEntry newEntry = new JarEntry(entryName);
                    jos.putNextEntry(newEntry);
                    Files.copy(classEntries.get(entryName), jos);
                    jos.closeEntry();
                    processedEntries.add(entryName);
                    count++;
                } else {
                    // 原样复制
                    JarEntry newEntry = new JarEntry(entry);
                    newEntry.setCompressedSize(-1);
                    jos.putNextEntry(newEntry);
                    try (InputStream is = jar.getInputStream(entry)) {
                        is.transferTo(jos);
                    }
                    jos.closeEntry();
                }
            }

            // 追加原包中没有的新条目（新增的 class 文件）
            for (Map.Entry<String, Path> e : classEntries.entrySet()) {
                if (!processedEntries.contains(e.getKey())) {
                    JarEntry newEntry = new JarEntry(e.getKey());
                    jos.putNextEntry(newEntry);
                    Files.copy(e.getValue(), jos);
                    jos.closeEntry();
                    count++;
                    logCallback.accept("  + 新增: " + e.getKey());
                }
            }
        }

        return count;
    }
}
