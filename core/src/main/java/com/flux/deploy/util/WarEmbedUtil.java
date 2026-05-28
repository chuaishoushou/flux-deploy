package com.flux.deploy.util;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.*;

/**
 * WAR 嵌入工具：替换 WAR 包中 WEB-INF/lib 下的指定 JAR
 *
 * <p>流程：解压 WAR → 替换 lib 中的 jar → 校验 → 重新打包。</p>
 * <p>关键：不重新编译 WAR，只做 zip 级别文件替换，确保不影响其他内容。</p>
 *
 * @author xumanyi
 * @date 2026-03-26
 */
public final class WarEmbedUtil {

    /** 私有构造函数，防止实例化 */
    private WarEmbedUtil() {}

    /**
     * JAR 嵌入操作的结果，包含校验信息
     */
    public static class EmbedResult {
        private final Path outputWar;
        private final String targetJarName;
        private final int libFileCountBefore;
        private final int libFileCountAfter;
        private final int unchangedFiles;
        private final int changedFiles;
        private final boolean verified;
        private final String message;

        /**
         * 创建嵌入结果
         *
         * @param outputWar         输出 WAR 文件路径
         * @param targetJarName     被替换的目标 JAR 名称
         * @param libFileCountBefore 替换前 lib 目录文件数
         * @param libFileCountAfter  替换后 lib 目录文件数
         * @param unchangedFiles    未变化的文件数
         * @param changedFiles      变化的文件数
         * @param verified          校验是否通过
         * @param message           校验结果描述
     * @author xumanyi
     * @date 2026-03-26
     */
        public EmbedResult(Path outputWar, String targetJarName,
                           int libFileCountBefore, int libFileCountAfter,
                           int unchangedFiles, int changedFiles,
                           boolean verified, String message) {
            this.outputWar = outputWar;
            this.targetJarName = targetJarName;
            this.libFileCountBefore = libFileCountBefore;
            this.libFileCountAfter = libFileCountAfter;
            this.unchangedFiles = unchangedFiles;
            this.changedFiles = changedFiles;
            this.verified = verified;
            this.message = message;
        }

        public Path getOutputWar() { return outputWar; }
        public String getTargetJarName() { return targetJarName; }
        public int getLibFileCountBefore() { return libFileCountBefore; }
        public int getLibFileCountAfter() { return libFileCountAfter; }
        public int getUnchangedFiles() { return unchangedFiles; }
        public int getChangedFiles() { return changedFiles; }
        public boolean isVerified() { return verified; }
        public String getMessage() { return message; }
    }

    /**
     * 将 JAR 嵌入 WAR 的 WEB-INF/lib 中
     *
     * <p><b>精确匹配契约</b>：{@code targetJarName} 必须是 lib 下的<b>完整文件名</b>（含扩展名），
     * 例如 {@code scev6-utils-6.2.1-SNAPSHOT.jar}。本方法用 {@code equals} 比对 lib 下文件名，
     * 不再做任何前缀近似匹配。<br>
     * 历史遗留：旧版形参 {@code jarArtifactId} 接受"artifactId 前缀"并用 {@code startsWith} 模糊匹配，
     * 在 maven 多模块项目里会把同前缀 sibling jar（{@code scev6-utils-objs-*} /
     * {@code scev6-utils-wms-*}）误命中，配合 extract 阶段同样的前缀匹配，导致"抽到 X、写回 Y"，
     * 主包被错误的 patch 包覆盖、且 lib 文件数校验依然显示"通过"。已废止。</p>
     *
     * <p><b>调用方义务</b>：传入前必须先用
     * {@link com.flux.deploy.plugin.service.LocalPackagePatchService#collectInnerLibJars}
     * + {@link com.flux.deploy.plugin.service.LocalPackagePatchService#pickVersionMatching}
     * 在该 war 内确认完整 jar 文件名；并且 {@code newJarFile} 必须是基于<b>同一个</b>
     * jar 抽出后做的补丁产物，不能跨 war 复用。</p>
     *
     * @param warFile        本地 WAR 文件
     * @param newJarFile     要嵌入的新 JAR 文件（基于同一 war 内的 targetJar 抽出 + 打补丁后的产物）
     * @param targetJarName  WEB-INF/lib 下被替换的 JAR 完整文件名（含 .jar 扩展名）
     * @param outputWarFile  输出的新 WAR 文件路径
     * @return 嵌入结果（含校验信息）
     * @throws IOException 操作失败
     * @author xumanyi
     * @date 2026-03-26
     */
    public static EmbedResult embedJar(Path warFile, Path newJarFile,
                                        String targetJarName, Path outputWarFile) throws IOException {
        if (targetJarName == null || targetJarName.isEmpty() || !targetJarName.endsWith(".jar")) {
            throw new IOException("targetJarName 必须是完整 jar 文件名（含 .jar 扩展名），实际: "
                    + targetJarName);
        }
        // 1. 创建临时解压目录
        Path tempDir = Files.createTempDirectory("war-embed-");

        try {
            // 2. 解压 WAR（不打日志，属于实现细节）
            unzip(warFile, tempDir);

            // 3. 定位 WEB-INF/lib
            Path libDir = tempDir.resolve("WEB-INF").resolve("lib");
            if (!Files.isDirectory(libDir)) {
                throw new IOException("WAR 中未找到 WEB-INF/lib 目录");
            }

            // 4. 精确定位目标 JAR（完整文件名 equals，不再做前缀模糊匹配）
            Path targetJar = libDir.resolve(targetJarName);
            if (!Files.isRegularFile(targetJar)) {
                throw new IOException("目标 WAR 的 WEB-INF/lib 下不存在 " + targetJarName
                        + "（必须按完整文件名精确匹配，禁止 prefix 兜底）");
            }
            String matchedJarName = targetJarName;

            System.out.println("INFO  [嵌入] 匹配到 WEB-INF/lib/" + matchedJarName);

            // 5. 记录替换前所有 lib 文件的 SHA256
            Map<String, String> beforeHashes = hashAllFiles(libDir);
            int libCountBefore = beforeHashes.size();

            // 6. 替换 JAR：固定用 targetJarName，确保"被替换条目"与"写回条目"的文件名严格一致
            Files.delete(targetJar);
            Path destJar = libDir.resolve(targetJarName);
            Files.copy(newJarFile, destJar);
            // 不打"已替换 X.jar"：上面"匹配到 WEB-INF/lib/X.jar"已表达"哪个 jar 被替换"。

            // 7. 记录替换后所有 lib 文件的 SHA256
            Map<String, String> afterHashes = hashAllFiles(libDir);
            int libCountAfter = afterHashes.size();

            // 8. 校验：只有目标 JAR 变化，其他不变
            int unchanged = 0;
            int changed = 0;
            Set<String> allKeys = new HashSet<>(beforeHashes.keySet());
            allKeys.addAll(afterHashes.keySet());

            for (String key : allKeys) {
                String beforeHash = beforeHashes.get(key);
                String afterHash = afterHashes.get(key);
                if (beforeHash != null && afterHash != null && beforeHash.equals(afterHash)) {
                    unchanged++;
                } else {
                    changed++;
                }
            }

            boolean verified;
            String message;

            // 文件数变化检查（允许因版本号变化导致名称变化）
            if (Math.abs(libCountBefore - libCountAfter) > 1) {
                verified = false;
                message = "文件数异常：替换前 " + libCountBefore + " 个，替换后 " + libCountAfter + " 个";
            } else if (changed > 1) {
                verified = false;
                message = "异常：" + changed + " 个文件发生变化（预期仅 1 个）";
            } else {
                verified = true;
                message = "校验通过（仅目标 JAR 变更，其余 " + unchanged + " 个文件不变）";
            }

            System.out.println("INFO  [嵌入] " + message);

            if (!verified) {
                throw new IOException("WAR 嵌入校验失败: " + message);
            }

            // 9. 重新打包 WAR（不打"重新打包 WAR..."进度行，紧跟其后的"输出"行已自带语义）
            zip(tempDir, outputWarFile);

            // 10. 内容守卫（end-to-end）：从 outputWarFile 内回读 WEB-INF/lib/<targetJarName> 字节，
            //     SHA256 必须等于 newJarFile 的 SHA256。任何差异说明"我以为我嵌入了 X，实际打包后里面是 Y"，
            //     这种错位（历史 bug 类型）应当在交付前就被立即拦下，而非依赖事后核查
            String expectedSha = sha256(newJarFile);
            String packedSha = sha256OfWarEntry(outputWarFile, "WEB-INF/lib/" + targetJarName);
            if (!expectedSha.equals(packedSha)) {
                throw new IOException("内嵌 JAR 内容守卫失败：outputWar 内 WEB-INF/lib/"
                        + targetJarName + " 的 SHA256 [" + packedSha
                        + "] 与传入 patch 包 [" + expectedSha + "] 不一致，可能存在抽/写错位或打包腐化");
            }

            System.out.println("INFO  [嵌入] 输出 " + outputWarFile.getFileName()
                    + "，大小 " + Files.size(outputWarFile) / 1024 + " KB");

            return new EmbedResult(outputWarFile, matchedJarName,
                    libCountBefore, libCountAfter,
                    unchanged, changed, verified, message);

        } finally {
            // 清理临时目录
            deleteRecursively(tempDir);
        }
    }

    // ==================== ZIP 操作 ====================

    /**
     * 解压 ZIP 文件到目标目录，包含 zip slip 攻击防护
     *
     * @param zipFile ZIP 文件路径
     * @param destDir 目标解压目录
     * @throws IOException 解压失败或检测到非法 zip 条目
     * @author xumanyi
     * @date 2026-03-26
     */
    private static void unzip(Path zipFile, Path destDir) throws IOException {
        try (ZipInputStream zis = new ZipInputStream(
                new BufferedInputStream(Files.newInputStream(zipFile)))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                Path entryPath = destDir.resolve(entry.getName()).normalize();
                // 防止 zip slip 攻击
                if (!entryPath.startsWith(destDir)) {
                    throw new IOException("非法 zip 条目: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(entryPath);
                } else {
                    Files.createDirectories(entryPath.getParent());
                    try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(entryPath))) {
                        zis.transferTo(out);
                    }
                }
                zis.closeEntry();
            }
        }
    }

    /**
     * 将目录打包为 ZIP 文件
     *
     * @param sourceDir 源目录
     * @param outputZip 输出 ZIP 文件路径
     * @throws IOException 打包失败
     * @author xumanyi
     * @date 2026-03-26
     */
    private static void zip(Path sourceDir, Path outputZip) throws IOException {
        try (ZipOutputStream zos = new ZipOutputStream(
                new BufferedOutputStream(Files.newOutputStream(outputZip)))) {
            Files.walkFileTree(sourceDir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    String entryName = sourceDir.relativize(file).toString();
                    // ZIP 条目用 / 分隔
                    entryName = entryName.replace('\\', '/');
                    zos.putNextEntry(new ZipEntry(entryName));
                    Files.copy(file, zos);
                    zos.closeEntry();
                    return FileVisitResult.CONTINUE;
                }

                /**
                 * 访问目录前回调，为非根目录创建 ZIP 条目
                 *
                 * @param dir   当前目录
                 * @param attrs 目录属性
                 * @return 继续遍历
                 * @throws IOException 创建 ZIP 条目失败
                 * @author xumanyi
                 * @date 2026-03-26
                 */
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                    if (!dir.equals(sourceDir)) {
                        String entryName = sourceDir.relativize(dir).toString() + "/";
                        entryName = entryName.replace('\\', '/');
                        zos.putNextEntry(new ZipEntry(entryName));
                        zos.closeEntry();
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        }
    }

    // ==================== 辅助方法 ====================

    /**
     * 计算目录下所有文件的 SHA256 哈希
     *
     * @param dir 目录路径
     * @return 文件名到 SHA256 哈希的映射
     * @throws IOException 文件读取失败
     * @author xumanyi
     * @date 2026-03-26
     */
    private static Map<String, String> hashAllFiles(Path dir) throws IOException {
        Map<String, String> hashes = new TreeMap<>();
        try (var stream = Files.list(dir)) {
            for (Path p : (Iterable<Path>) stream::iterator) {
                if (Files.isRegularFile(p)) {
                    hashes.put(p.getFileName().toString(), sha256(p));
                }
            }
        }
        return hashes;
    }

    /**
     * 计算单个文件的 SHA256 哈希
     *
     * @param file 文件路径
     * @return 小写十六进制哈希字符串
     * @throws IOException 文件读取或算法不可用
     * @author xumanyi
     * @date 2026-03-26
     */
    private static String sha256(Path file) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            try (InputStream is = new BufferedInputStream(Files.newInputStream(file))) {
                byte[] buf = new byte[8192];
                int len;
                while ((len = is.read(buf)) != -1) {
                    md.update(buf, 0, len);
                }
            }
            byte[] digest = md.digest();
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 not available", e);
        }
    }

    /**
     * 从 zip / war 中抽出指定条目的字节并计算 SHA256
     *
     * <p>用于 end-to-end 内容守卫：嵌入完成后回读 outputWar 内嵌 jar 的真实字节，
     * 与传入的 patch 包做哈希比对，确保"打包写回"过程没有把字节写错。</p>
     *
     * @param zipFile   zip / war 文件
     * @param entryName zip 内条目名（含路径，如 {@code WEB-INF/lib/foo.jar}）
     * @return 该条目字节的 SHA-256 小写十六进制
     * @throws IOException 条目不存在或读取失败
     * @author xumanyi
     * @date 2026-05-07
     */
    private static String sha256OfWarEntry(Path zipFile, String entryName) throws IOException {
        try (java.util.zip.ZipFile zf = new java.util.zip.ZipFile(zipFile.toFile())) {
            ZipEntry entry = zf.getEntry(entryName);
            if (entry == null || entry.isDirectory()) {
                throw new IOException("zip 内不存在条目: " + entryName + " @ " + zipFile);
            }
            try {
                MessageDigest md = MessageDigest.getInstance("SHA-256");
                try (InputStream is = new BufferedInputStream(zf.getInputStream(entry))) {
                    byte[] buf = new byte[8192];
                    int len;
                    while ((len = is.read(buf)) != -1) {
                        md.update(buf, 0, len);
                    }
                }
                byte[] digest = md.digest();
                StringBuilder sb = new StringBuilder();
                for (byte b : digest) {
                    sb.append(String.format("%02x", b));
                }
                return sb.toString();
            } catch (java.security.NoSuchAlgorithmException e) {
                throw new IOException("SHA-256 not available", e);
            }
        }
    }

    /**
     * 递归删除目录及其所有内容
     *
     * @param dir 要删除的目录
     * @author xumanyi
     * @date 2026-03-26
     */
    private static void deleteRecursively(Path dir) {
        try {
            Files.walkFileTree(dir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }
                @Override
                public FileVisitResult postVisitDirectory(Path d, IOException exc) throws IOException {
                    Files.delete(d);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException ignored) {}
    }
}
