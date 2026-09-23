package com.flux.deploy.util;

import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.*;

/**
 * WAR 嵌入工具：替换 WAR 包中 WEB-INF/lib 下的指定 JAR
 *
 * <p>流程：流式遍历原 WAR → 目标 JAR 写入新字节、其余条目原样搬运 → 内容守卫校验。</p>
 * <p>关键：不解压到磁盘、不重新编译。逐条复制时用 {@link ZipEntry} 拷贝构造保留未改动条目的
 * 原始时间戳与压缩方式，确保"只有被替换的 JAR 时间变化"，其余内容与元数据零改动。</p>
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
     * <p><b>时间戳语义</b>：采用流式逐条复制，未改动条目用 {@link ZipEntry} 拷贝构造保留其原始
     * 时间戳与压缩方式；仅被替换的目标 JAR 用新条目（当前时间）。这样输出 WAR 内"只有目标 JAR
     * 的修改时间变化"，与用户对增量替换的直觉一致，也避免无谓刷新全部条目时间戳。</p>
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

        String targetEntryName = "WEB-INF/lib/" + targetJarName;

        try (java.util.zip.ZipFile srcWar = new java.util.zip.ZipFile(warFile.toFile())) {
            // 1. 精确定位目标 JAR（完整文件名 equals，不做前缀模糊匹配）
            ZipEntry targetEntry = srcWar.getEntry(targetEntryName);
            if (targetEntry == null || targetEntry.isDirectory()) {
                throw new IOException("目标 WAR 的 WEB-INF/lib 下不存在 " + targetJarName
                        + "，必须按完整文件名精确匹配，禁止 prefix 兜底");
            }
            System.out.println("INFO  [嵌入] 匹配到 " + targetEntryName);

            // 2. 流式重写 WAR：目标 JAR 写入新字节（新条目=当前时间），其余条目拷贝构造原样搬运，
            //    连同原始时间戳、压缩方式一并保留，不再解压到磁盘再重打。
            //    未改动条目逐字节复制，内容天然不可能变，无需再做替换前后的全量 SHA 比对。
            int libCountBefore = 0;
            try (ZipOutputStream zos = new ZipOutputStream(
                    new BufferedOutputStream(Files.newOutputStream(outputWarFile)))) {
                Enumeration<? extends ZipEntry> entries = srcWar.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    String name = entry.getName();
                    boolean isLibFile = name.startsWith("WEB-INF/lib/") && !entry.isDirectory();
                    if (isLibFile) {
                        libCountBefore++;
                    }

                    if (name.equals(targetEntryName)) {
                        // 目标 JAR：替换为新字节，用新条目（不回写旧时间）→ 表达"此 JAR 被改动"
                        zos.putNextEntry(new ZipEntry(name));
                        Files.copy(newJarFile, zos);
                        zos.closeEntry();
                    } else {
                        // 其余条目：拷贝构造保留 time/method 等元数据，字节原样复制。
                        // setCompressedSize(-1)：压缩大小交由输出流重算，与项目内 patchJar/alignWarLibs 一致。
                        ZipEntry copied = new ZipEntry(entry);
                        copied.setCompressedSize(-1);
                        zos.putNextEntry(copied);
                        try (InputStream is = srcWar.getInputStream(entry)) {
                            is.transferTo(zos);
                        }
                        zos.closeEntry();
                    }
                }
            }

            // 3. 内容守卫（end-to-end）：从 outputWarFile 内回读 WEB-INF/lib/<targetJarName> 字节，
            //    SHA256 必须等于 newJarFile 的 SHA256。任何差异说明"我以为我嵌入了 X，实际打包后里面是 Y"，
            //    这种错位（历史 bug 类型）应当在交付前就被立即拦下，而非依赖事后核查。
            String expectedSha = sha256(newJarFile);
            String packedSha = sha256OfWarEntry(outputWarFile, targetEntryName);
            if (!expectedSha.equals(packedSha)) {
                throw new IOException("内嵌 JAR 内容守卫失败：outputWar 内 WEB-INF/lib/"
                        + targetJarName + " 的 SHA256 [" + packedSha
                        + "] 与传入 patch 包 [" + expectedSha + "] 不一致，可能存在抽/写错位或打包腐化");
            }

            // 同名替换，lib 数量不变；除目标外其余 lib 字节均原样复制，故 unchanged = 总数 - 1
            int unchanged = libCountBefore - 1;
            String message = "校验通过，仅目标 JAR 变更，其余 " + unchanged + " 个文件不变";
            System.out.println("INFO  [嵌入] " + message);
            System.out.println("INFO  [嵌入] 输出 " + outputWarFile.getFileName()
                    + "，大小 " + Files.size(outputWarFile) / 1024 + " KB");

            return new EmbedResult(outputWarFile, targetJarName,
                    libCountBefore, libCountBefore, unchanged, 1, true, message);
        }
    }

    // ==================== 辅助方法 ====================

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
}
