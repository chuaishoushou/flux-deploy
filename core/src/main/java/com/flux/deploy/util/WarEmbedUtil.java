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
     * @param warFile        本地 WAR 文件
     * @param newJarFile     要嵌入的新 JAR 文件
     * @param jarArtifactId  JAR 的 artifactId 前缀（用于匹配 lib 中的旧 jar）
     * @param outputWarFile  输出的新 WAR 文件路径
     * @return 嵌入结果（含校验信息）
     * @throws IOException 操作失败
     * @author xumanyi
     * @date 2026-03-26
     */
    public static EmbedResult embedJar(Path warFile, Path newJarFile,
                                        String jarArtifactId, Path outputWarFile) throws IOException {
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

            // 4. 找到匹配的旧 JAR
            String matchedJarName = null;
            try (var stream = Files.list(libDir)) {
                for (Path p : (Iterable<Path>) stream::iterator) {
                    String name = p.getFileName().toString();
                    if (name.startsWith(jarArtifactId) && name.endsWith(".jar")) {
                        matchedJarName = name;
                        break;
                    }
                }
            }

            if (matchedJarName == null) {
                throw new IOException("目标 WAR 内不存在 " + jarArtifactId + " 的 JAR 文件");
            }

            System.out.println("  [嵌入] 匹配到: WEB-INF/lib/" + matchedJarName);

            // 5. 记录替换前所有 lib 文件的 SHA256
            Map<String, String> beforeHashes = hashAllFiles(libDir);
            int libCountBefore = beforeHashes.size();

            // 6. 替换 JAR
            Path targetJar = libDir.resolve(matchedJarName);
            Files.delete(targetJar);

            // 如果新 jar 文件名与旧的不同（版本号变化），用旧名字保持一致
            // 但通常我们用新 jar 的文件名
            String newJarName = newJarFile.getFileName().toString();
            Path destJar;
            if (newJarName.startsWith(jarArtifactId)) {
                // 版本可能不同，保留新文件名
                destJar = libDir.resolve(newJarName);
                // 如果旧名和新名不同，记录
                if (!matchedJarName.equals(newJarName)) {
                    System.out.println("  [嵌入] JAR 名称变更: " + matchedJarName + " → " + newJarName);
                }
            } else {
                destJar = libDir.resolve(matchedJarName);
            }
            Files.copy(newJarFile, destJar);
            // 不打"已替换 X.jar"：上面"匹配到 WEB-INF/lib/X.jar"已表达"哪个 jar 被替换"；
            // 名称变化场景由前面的"JAR 名称变更"行单独提示，无需此处再来一条同义记录。

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
                message = "校验通过：仅目标 JAR 变更，其他 " + unchanged + " 个文件未受影响";
            }

            System.out.println("  [嵌入] " + message);

            if (!verified) {
                throw new IOException("WAR 嵌入校验失败: " + message);
            }

            // 9. 重新打包 WAR（不打"重新打包 WAR..."进度行，紧跟其后的"输出"行已自带语义）
            zip(tempDir, outputWarFile);
            System.out.println("  [嵌入] 输出: " + outputWarFile.getFileName()
                    + " (" + Files.size(outputWarFile) / 1024 + " KB)");

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
