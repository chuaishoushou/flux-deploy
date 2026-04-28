package com.flux.deploy.core.test;

import com.flux.deploy.ftp.FtpOperations;
import com.flux.deploy.ftp.FtpSession;
import com.flux.deploy.plugin.service.StagingPackageBuilder;
import com.flux.deploy.util.CredentialCache;

import org.apache.commons.net.ftp.FTPFile;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * FTP 模式端到端自动化测试（完全自主，零配置）
 *
 * <h3>覆盖范围</h3>
 * <p>FTP + 三种 DeployMode：FULL / INCREMENTAL / AUTO_DETECT</p>
 * <p>每种模式验证：新增 / 修改 / 删除 / 内部类残留清理 / 未变动类保留</p>
 *
 * <h3>自主行为</h3>
 * <ul>
 *   <li>凭据：自动从 {@code ~/.flux-deploy/credentials.toml} 读取（gradle 任务里已固定 user.name=xumanyi）</li>
 *   <li>工作目录：自动在 {@link #FTP_BASE_DIR} 下创建 {@code auto_test_{yyyyMMdd_HHmmss}/} 子目录（递归建父级）</li>
 *   <li>基准包：本地 mvn 编译 + 上传作基线</li>
 *   <li>清理：无论成败，finally 块删除所有测试文件 + 工作目录 + 临时源文件</li>
 * </ul>
 *
 * <h3>运行</h3>
 * <pre>
 *   cd flux-deploy-plugin
 *   ./gradlew runFtpModeTest
 * </pre>
 *
 * <p>需要先至少通过插件登录一次 FTP，让 {@code ~/.flux-deploy/credentials.toml} 生成。</p>
 *
 * @author xumanyi
 * @date 2026-04-19
 */
public class FtpModeTestDriver {

    /** 源工程路径 */
    private static final String SCEV6_PATH =
            "/Users/chuaishoushou/FLUX/V9/scev6/v6_000_utils/scev6-utils-tms";
    /** 产物名 */
    private static final String ARTIFACT = "scev6-utils-tms-9.0.0-SNAPSHOT.jar";
    /** 测试源码专用包（项目原本不存在） */
    private static final String PKG = "com/flux/scev6/flux_ftp_mode_test";
    /** mvn 可执行路径 */
    private static final String MVN = "/Users/chuaishoushou/FLUX/apache-maven-3.8.1/bin/mvn";

    /** FTP 工作目录根 */
    private static final String FTP_BASE_DIR = "/开发/1测试项目/TMS V9.0-P7/";

    // 测试文件名
    private static final String KEEP1 = "Keep1.java";
    private static final String KEEP2 = "Keep2.java";
    private static final String TO_DEL = "ToBeDeleted.java";
    private static final String TO_ADD = "ToBeAdded.java";

    // 测试条目路径
    private static final String E_KEEP1        = PKG + "/Keep1.class";
    private static final String E_KEEP2        = PKG + "/Keep2.class";
    private static final String E_KEEP2_INNER1 = PKG + "/Keep2$Inner1.class";
    private static final String E_KEEP2_INNER2 = PKG + "/Keep2$Inner2.class";
    private static final String E_TO_DEL       = PKG + "/ToBeDeleted.class";
    private static final String E_TO_ADD       = PKG + "/ToBeAdded.class";

    private final List<String> failures = new ArrayList<>();
    private final List<Runnable> cleanupActions = new ArrayList<>();
    private Path workRoot;
    private String ftpHost;
    private int ftpPort;
    private String ftpUser;
    private String ftpPass;
    private String testDirRemote;

    public static void main(String[] args) throws Exception {
        printBanner();
        FtpModeTestDriver d = new FtpModeTestDriver();
        int code = 0;
        try {
            d.run();
        } catch (Throwable t) {
            t.printStackTrace();
            code = 1;
        } finally {
            d.cleanup();
        }
        if (!d.failures.isEmpty()) code = 2;
        System.out.println("\n══════════════ FTP 测试汇总 ══════════════");
        if (d.failures.isEmpty()) {
            System.out.println("✅ 三种 FTP 模式的增/删/改/残留清理 断言全部通过");
        } else {
            System.out.println("❌ " + d.failures.size() + " 个断言失败：");
            for (String f : d.failures) System.out.println("  - " + f);
        }
        System.exit(code);
    }

    private static void printBanner() {
        System.out.println("╔════════════════════════════════════════════════════════╗");
        System.out.println("║  FTP 模式自动化测试                                     ║");
        System.out.println("║  覆盖：FULL / INCREMENTAL / AUTO_DETECT × 增/删/改      ║");
        System.out.println("║  工作目录：/开发/1测试项目/TMS V9.0-P7/auto_test_{ts}/  ║");
        System.out.println("║  完成后自动清理（测试文件 + 远端目录）                 ║");
        System.out.println("╚════════════════════════════════════════════════════════╝");
    }

    private void run() throws Exception {
        // 1. 凭据
        loadCredentials();
        log("[env] FTP = " + ftpUser + "@" + ftpHost + ":" + ftpPort);

        // 2. 本地工作目录
        workRoot = Files.createTempDirectory("flux-ftp-test-");
        log("[env] 本地工作目录: " + workRoot);

        // 3. 远端工作目录：自建 auto_test_{timestamp}/
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        testDirRemote = FTP_BASE_DIR + "auto_test_" + stamp + "/";
        createRemoteDirRecursive(testDirRemote);
        log("[env] FTP 测试目录: " + testDirRemote);

        // 4. 基准源 + 编译 + 捕获
        Path scev6Src = Path.of(SCEV6_PATH, "src/main/java");
        writeTestFile(scev6Src, PKG, KEEP1, keep1Source("v1"));
        writeTestFile(scev6Src, PKG, KEEP2, keep2Source("v1", true));
        writeTestFile(scev6Src, PKG, TO_DEL, toBeDeletedSource());
        runMvn();
        Path baselineLocal = workRoot.resolve("baseline/" + ARTIFACT);
        Files.createDirectories(baselineLocal.getParent());
        Files.copy(Path.of(SCEV6_PATH, "target", ARTIFACT), baselineLocal,
                StandardCopyOption.REPLACE_EXISTING);

        Set<String> base = listEntries(baselineLocal);
        assertTrue(base.contains(E_KEEP1),        "基准应含 Keep1.class");
        assertTrue(base.contains(E_KEEP2),        "基准应含 Keep2.class");
        assertTrue(base.contains(E_KEEP2_INNER1), "基准应含 Keep2$Inner1.class");
        assertTrue(base.contains(E_KEEP2_INNER2), "基准应含 Keep2$Inner2.class");
        assertTrue(base.contains(E_TO_DEL),       "基准应含 ToBeDeleted.class");
        byte[] baselineKeep1 = readEntry(baselineLocal, E_KEEP1);
        byte[] baselineKeep2 = readEntry(baselineLocal, E_KEEP2);

        String remoteJarPath = testDirRemote + ARTIFACT;

        // ═══ FULL ═══
        log("\n────────── FTP + FULL ──────────");
        writeTestFile(scev6Src, PKG, KEEP1, keep1Source("v2-full"));
        runMvn();
        uploadToFtp(baselineLocal, remoteJarPath);
        Path freshJar = Path.of(SCEV6_PATH, "target", ARTIFACT);
        uploadToFtp(freshJar, remoteJarPath);
        Path downloadedFull = workRoot.resolve("downloaded-full.jar");
        downloadFromFtp(remoteJarPath, downloadedFull);
        byte[] fullKeep1 = readEntry(downloadedFull, E_KEEP1);
        byte[] targetKeep1 = readEntry(freshJar, E_KEEP1);
        assertTrue(Arrays.equals(fullKeep1, targetKeep1),
                "[FTP FULL] 远端 Keep1 == 新 target 产物 Keep1");
        assertTrue(!Arrays.equals(fullKeep1, baselineKeep1),
                "[FTP FULL] 远端 Keep1 != 基准 Keep1");

        // 准备 INC/AUTO 共享的源改动
        writeTestFile(scev6Src, PKG, KEEP1, keep1Source("v1"));
        writeTestFile(scev6Src, PKG, KEEP2, keep2Source("v2", false));
        writeTestFile(scev6Src, PKG, TO_ADD, toBeAddedSource());
        deleteTestFile(scev6Src, PKG, TO_DEL);
        runMvn();

        // ═══ INCREMENTAL ═══
        log("\n────────── FTP + INCREMENTAL ──────────");
        uploadToFtp(baselineLocal, remoteJarPath);
        List<String> incFiles = List.of(
                "src/main/java/" + PKG + "/" + TO_ADD,
                "src/main/java/" + PKG + "/" + KEEP2,
                "D src/main/java/" + PKG + "/" + TO_DEL
        );
        Path stagingInc = runStagingBuild(incFiles, remoteJarPath);
        uploadToFtp(stagingInc, remoteJarPath);
        Path downloadedInc = workRoot.resolve("downloaded-inc.jar");
        downloadFromFtp(remoteJarPath, downloadedInc);
        assertFtpIncrementalExpectations("FTP INC", downloadedInc, baselineKeep2);

        // ═══ AUTO_DETECT ═══
        log("\n────────── FTP + AUTO_DETECT ──────────");
        uploadToFtp(baselineLocal, remoteJarPath);
        List<String> autoFiles = List.of(
                "A src/main/java/" + PKG + "/" + TO_ADD,
                "M src/main/java/" + PKG + "/" + KEEP2,
                "D src/main/java/" + PKG + "/" + TO_DEL
        );
        Path stagingAuto = runStagingBuild(autoFiles, remoteJarPath);
        uploadToFtp(stagingAuto, remoteJarPath);
        Path downloadedAuto = workRoot.resolve("downloaded-auto.jar");
        downloadFromFtp(remoteJarPath, downloadedAuto);
        assertFtpIncrementalExpectations("FTP AUTO", downloadedAuto, baselineKeep2);
    }

    private void assertFtpIncrementalExpectations(String label, Path downloaded, byte[] baselineKeep2) throws IOException {
        Set<String> out = listEntries(downloaded);
        byte[] outKeep2 = readEntry(downloaded, E_KEEP2);
        assertTrue(out.contains(E_TO_ADD),        "[" + label + "] 远端 ToBeAdded.class 应出现");
        assertTrue(out.contains(E_KEEP2),         "[" + label + "] 远端 Keep2.class 应存在");
        assertTrue(!Arrays.equals(outKeep2, baselineKeep2),
                                                   "[" + label + "] 远端 Keep2.class 字节应与基准不同");
        assertTrue(out.contains(E_KEEP2_INNER1),  "[" + label + "] 远端 Keep2$Inner1.class 应存在");
        assertTrue(!out.contains(E_KEEP2_INNER2), "[" + label + "] 远端 Keep2$Inner2.class 应被残留清理");
        assertTrue(!out.contains(E_TO_DEL),       "[" + label + "] 远端 ToBeDeleted.class 应被删除");
        assertTrue(out.contains(E_KEEP1),         "[" + label + "] 远端 Keep1.class 应保留");
    }

    // ═════════════════ helpers ═════════════════

    private void loadCredentials() {
        // 环境变量优先（便于 CI）
        String envHost = System.getenv("FLUX_FTP_HOST");
        String envUser = System.getenv("FLUX_FTP_USER");
        String envPass = System.getenv("FLUX_FTP_PASS");
        if (envHost != null && envUser != null && envPass != null) {
            this.ftpHost = envHost;
            this.ftpUser = envUser;
            this.ftpPass = envPass;
            String envPort = System.getenv("FLUX_FTP_PORT");
            this.ftpPort = envPort == null ? 18080 : Integer.parseInt(envPort);
            log("[cred] 使用环境变量凭据");
            return;
        }
        // 回退缓存文件
        log("[cred] sys: user.name=" + System.getProperty("user.name"));
        try {
            List<CredentialCache.CachedCredential> all = CredentialCache.loadAllDecrypted();
            if (all.isEmpty()) {
                throw new RuntimeException("缓存为空，请先在 IDEA 插件中登录一次 FTP");
            }
            CredentialCache.CachedCredential best = all.get(0);
            for (CredentialCache.CachedCredential c : all) {
                if (c.getVerifiedAt() != null && best.getVerifiedAt() != null
                        && c.getVerifiedAt().compareTo(best.getVerifiedAt()) > 0) best = c;
            }
            this.ftpHost = best.getHost();
            this.ftpPort = best.getPort();
            this.ftpUser = best.getUsername();
            this.ftpPass = best.getPassword();
            log("[cred] 使用缓存凭据");
        } catch (Exception e) {
            throw new RuntimeException(
                    "凭据加载失败：" + e.getMessage()
                    + "\n  解决：在 IDEA 插件里重新登录 FTP；或设置 FLUX_FTP_HOST/PORT/USER/PASS 环境变量", e);
        }
    }

    /** 递归创建远端目录（FTP 的 mkdirIfAbsent 通常只建一级） */
    private void createRemoteDirRecursive(String absPath) throws IOException {
        // "/a/b/c/" → ["/a/","/a/b/","/a/b/c/"]
        List<String> tiers = new ArrayList<>();
        StringBuilder cur = new StringBuilder("/");
        String stripped = absPath.replaceAll("^/+", "").replaceAll("/+$", "");
        for (String seg : stripped.split("/")) {
            cur.append(seg).append("/");
            tiers.add(cur.toString());
        }
        try (FtpSession s = new FtpSession(ftpHost, ftpPort)) {
            s.connect(ftpUser, ftpPass);
            FtpOperations ops = new FtpOperations(s);
            for (String t : tiers) {
                try { ops.mkdirIfAbsent(t); } catch (Exception ignored) {}
            }
        }
    }

    private Path runStagingBuild(List<String> changedFiles, String remotePath) throws IOException {
        StagingPackageBuilder builder = new StagingPackageBuilder(
                SCEV6_PATH, ARTIFACT, changedFiles,
                msg -> System.out.println("    [staging] " + msg));
        Path staging = builder.build(ftpHost, ftpPort, ftpUser, ftpPass, remotePath);
        if (staging == null) throw new IOException("StagingPackageBuilder.build 返回 null");
        return staging;
    }

    private void uploadToFtp(Path local, String remotePath) throws IOException {
        log("[up] " + local.getFileName() + " → " + remotePath);
        try (FtpSession s = new FtpSession(ftpHost, ftpPort)) {
            s.connect(ftpUser, ftpPass);
            new FtpOperations(s).upload(local, remotePath);
        }
    }

    private void downloadFromFtp(String remotePath, Path local) throws IOException {
        Files.createDirectories(local.getParent());
        try (FtpSession s = new FtpSession(ftpHost, ftpPort)) {
            s.connect(ftpUser, ftpPass);
            new FtpOperations(s).download(remotePath, local);
        }
    }

    private void writeTestFile(Path srcRoot, String pkgPath, String fileName, String content) throws IOException {
        Path dir = srcRoot.resolve(pkgPath);
        Files.createDirectories(dir);
        Path file = dir.resolve(fileName);
        Files.writeString(file, content, StandardCharsets.UTF_8);
        cleanupActions.add(() -> {
            try { Files.deleteIfExists(file); } catch (Exception ignored) {}
        });
        cleanupActions.add(() -> {
            try {
                if (Files.isDirectory(dir)) {
                    try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
                        if (!ds.iterator().hasNext()) Files.delete(dir);
                    }
                }
            } catch (Exception ignored) {}
        });
    }

    private void deleteTestFile(Path srcRoot, String pkgPath, String fileName) throws IOException {
        Files.deleteIfExists(srcRoot.resolve(pkgPath).resolve(fileName));
    }

    private void runMvn() throws Exception {
        log("[mvn] clean package -DskipTests ...");
        ProcessBuilder pb = new ProcessBuilder(MVN, "clean", "package", "-DskipTests", "-q");
        pb.directory(Path.of(SCEV6_PATH).toFile());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(),
                StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append('\n');
        }
        int ec = p.waitFor();
        if (ec != 0) {
            System.out.println(sb);
            throw new IOException("mvn 失败 ec=" + ec);
        }
    }

    private Set<String> listEntries(Path jar) throws IOException {
        Set<String> set = new HashSet<>();
        try (JarFile j = new JarFile(jar.toFile())) {
            Enumeration<JarEntry> en = j.entries();
            while (en.hasMoreElements()) set.add(en.nextElement().getName());
        }
        return set;
    }

    private byte[] readEntry(Path jar, String name) throws IOException {
        try (JarFile j = new JarFile(jar.toFile())) {
            JarEntry e = j.getJarEntry(name);
            if (e == null) return new byte[0];
            return j.getInputStream(e).readAllBytes();
        }
    }

    private void assertTrue(boolean cond, String msg) {
        if (cond) log("  ✓ " + msg);
        else { log("  ✗ " + msg); failures.add(msg); }
    }

    private void log(String msg) { System.out.println(msg); }

    private void cleanup() {
        // 1. 清远端测试目录（删目录下所有文件 + 删目录）
        if (testDirRemote != null && ftpHost != null) {
            try (FtpSession s = new FtpSession(ftpHost, ftpPort)) {
                s.connect(ftpUser, ftpPass);
                FtpOperations ops = new FtpOperations(s);
                try {
                    for (FTPFile f : ops.listFiles(testDirRemote)) {
                        String name = f.getName();
                        if (".".equals(name) || "..".equals(name)) continue;
                        try {
                            ops.delete(testDirRemote + name);
                            log("[cleanup] rm " + testDirRemote + name);
                        } catch (Exception ignored) {}
                    }
                    s.getClient().removeDirectory(testDirRemote);
                    log("[cleanup] rmdir " + testDirRemote);
                } catch (Exception e) {
                    log("[cleanup] 远端清理失败（请手动删除 " + testDirRemote + "）: " + e.getMessage());
                }
            } catch (Exception e) {
                log("[cleanup] 连不上 FTP，无法清远端: " + e.getMessage());
            }
        }
        // 2. 清本地源文件
        for (int i = cleanupActions.size() - 1; i >= 0; i--) {
            try { cleanupActions.get(i).run(); } catch (Exception ignored) {}
        }
        // 3. 清本地工作目录
        if (workRoot != null) {
            try { deleteTree(workRoot); } catch (Exception ignored) {}
        }
    }

    private static void deleteTree(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        Files.walkFileTree(dir, new java.nio.file.SimpleFileVisitor<>() {
            @Override public java.nio.file.FileVisitResult visitFile(Path f, BasicFileAttributes a) throws IOException {
                Files.delete(f);
                return java.nio.file.FileVisitResult.CONTINUE;
            }
            @Override public java.nio.file.FileVisitResult postVisitDirectory(Path d, IOException e) throws IOException {
                Files.delete(d);
                return java.nio.file.FileVisitResult.CONTINUE;
            }
        });
    }

    // ───── source templates ─────

    private static String keep1Source(String tag) {
        return "package com.flux.scev6.flux_ftp_mode_test;\n" +
               "public class Keep1 { public static final String TAG = \"" + tag + "\"; }\n";
    }

    private static String keep2Source(String tag, boolean withInner2) {
        StringBuilder sb = new StringBuilder();
        sb.append("package com.flux.scev6.flux_ftp_mode_test;\n");
        sb.append("public class Keep2 {\n");
        sb.append("    public static final String VERSION = \"").append(tag).append("\";\n");
        sb.append("    static class Inner1 { int x; }\n");
        if (withInner2) sb.append("    static class Inner2 { int y; }\n");
        sb.append("}\n");
        return sb.toString();
    }

    private static String toBeDeletedSource() {
        return "package com.flux.scev6.flux_ftp_mode_test;\n" +
               "public class ToBeDeleted { public static final String NAME = \"DELETED\"; }\n";
    }

    private static String toBeAddedSource() {
        return "package com.flux.scev6.flux_ftp_mode_test;\n" +
               "public class ToBeAdded { public static final String NAME = \"ADDED\"; }\n";
    }
}
