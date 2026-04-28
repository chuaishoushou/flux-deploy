package com.flux.deploy.core.test;

import com.flux.deploy.plugin.model.DeployMode;
import com.flux.deploy.plugin.service.LocalPackagePatchService;
import com.flux.deploy.plugin.service.LocalPackagePatchService.LocalPatchResult;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

/**
 * 本地模式端到端自动化测试（完全自主，零配置）
 *
 * <h3>覆盖范围</h3>
 * <p>3 种场景 × 3 种 DeployMode：</p>
 * <pre>
 *   Scenario 1: jar → jar             (源 scev6-utils-tms，目标 jar)
 *   Scenario 2: jar → war/lib          (源 scev6-utils-tms，目标含多版本 lib 的 war)
 *   Scenario 3: war → war              (源 tm01srv，目标 war)
 *   每场景内：FULL / INCREMENTAL / AUTO_DETECT 三种模式
 * </pre>
 * <p>每种组合验证：新增 / 修改 / 删除 / 内部类残留清理 / 未变动类保留</p>
 *
 * <h3>自主行为</h3>
 * <ul>
 *   <li>测试源文件：在专用包 {@code flux_local_mode_test} 下创建，不动既有生产代码</li>
 *   <li>Scenario 2 的测试 war：内存中手工拼装，不需要真实 war 工程</li>
 *   <li>Scenario 3：在 tm01srv 源工程下创建测试类 → mvn 打包 → 得到 tm01srv.war</li>
 *   <li>清理：无论成败，finally 块删除所有临时源文件 + 工作目录</li>
 * </ul>
 *
 * <h3>运行</h3>
 * <pre>
 *   cd flux-deploy-plugin
 *   ./gradlew runLocalModeTest
 * </pre>
 *
 * <p>与 {@link FtpModeTestDriver} 互不依赖，可独立运行。</p>
 *
 * @author xumanyi
 * @date 2026-04-19
 */
public class LocalModeTestDriver {

    // ───── 源工程 / 产物 ─────
    private static final String SCEV6_PATH =
            "/Users/chuaishoushou/FLUX/V9/scev6/v6_000_utils/scev6-utils-tms";
    private static final String SCEV6_ARTIFACT = "scev6-utils-tms-9.0.0-SNAPSHOT.jar";
    private static final String SCEV6_TEST_PKG = "com/flux/scev6/flux_local_mode_test";

    private static final String TM01_PATH = "/Users/chuaishoushou/FLUX/V9/tmsv6/tm01/tm01srv";
    private static final String TM01_TEST_PKG = "com/flux/tms/tm01srv/flux_local_mode_test";

    private static final String MVN = "/Users/chuaishoushou/FLUX/apache-maven-3.8.1/bin/mvn";

    // ───── 测试文件名常量 ─────
    private static final String KEEP1 = "Keep1.java";
    private static final String KEEP2 = "Keep2.java";
    private static final String TO_DEL = "ToBeDeleted.java";
    private static final String TO_ADD = "ToBeAdded.java";

    // ───── 状态 ─────
    private final List<String> failures = new ArrayList<>();
    private final List<Runnable> cleanupActions = new ArrayList<>();
    private Path workRoot;

    public static void main(String[] args) throws Exception {
        LocalModeTestDriver d = new LocalModeTestDriver();
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
        System.out.println("\n══════════════ 测试汇总 ══════════════");
        if (d.failures.isEmpty()) {
            System.out.println("✅ 全部场景、全部模式、全部断言 通过");
        } else {
            System.out.println("❌ " + d.failures.size() + " 个断言失败：");
            for (String f : d.failures) System.out.println("  - " + f);
        }
        System.exit(code);
    }

    private void run() throws Exception {
        workRoot = Files.createTempDirectory("flux-local-test-");
        log("工作目录: " + workRoot);

        runScenarioJarToJar();
        runScenarioJarToWarLib();
        runScenarioWarToWar();
    }

    // ═══════════════════════════════════════════════════════════════
    //  Scenario 1: jar → jar
    // ═══════════════════════════════════════════════════════════════
    private void runScenarioJarToJar() throws Exception {
        log("\n╔══════════════════════════════════════╗");
        log("║  Scenario 1: jar → jar               ║");
        log("╚══════════════════════════════════════╝");

        // 1. 基准类
        Path scev6Src = Path.of(SCEV6_PATH, "src/main/java");
        writeTestFile(scev6Src, SCEV6_TEST_PKG, KEEP1, keep1Source("v1"));
        writeTestFile(scev6Src, SCEV6_TEST_PKG, KEEP2, keep2Source("v1", true));
        writeTestFile(scev6Src, SCEV6_TEST_PKG, TO_DEL, toBeDeletedSource());

        runMvn(SCEV6_PATH);
        Path baselineJarDir = Files.createDirectories(workRoot.resolve("scev6-baseline"));
        Path baselineJar = baselineJarDir.resolve(SCEV6_ARTIFACT);
        Files.copy(Path.of(SCEV6_PATH, "target", SCEV6_ARTIFACT), baselineJar,
                StandardCopyOption.REPLACE_EXISTING);
        log("✓ 基准 jar: " + baselineJar + " (" + Files.size(baselineJar) / 1024 + " KB)");

        Set<String> base = listEntries(baselineJar);
        assertTrue(base.contains(SCEV6_TEST_PKG + "/Keep1.class"),        "基准应含 Keep1.class");
        assertTrue(base.contains(SCEV6_TEST_PKG + "/Keep2.class"),        "基准应含 Keep2.class");
        assertTrue(base.contains(SCEV6_TEST_PKG + "/Keep2$Inner1.class"), "基准应含 Keep2$Inner1.class");
        assertTrue(base.contains(SCEV6_TEST_PKG + "/Keep2$Inner2.class"), "基准应含 Keep2$Inner2.class");
        assertTrue(base.contains(SCEV6_TEST_PKG + "/ToBeDeleted.class"),  "基准应含 ToBeDeleted.class");

        byte[] baselineKeep1 = readEntry(baselineJar, SCEV6_TEST_PKG + "/Keep1.class");
        byte[] baselineKeep2 = readEntry(baselineJar, SCEV6_TEST_PKG + "/Keep2.class");

        // Case 1: FULL
        log("\n── [jar→jar] FULL ──");
        writeTestFile(scev6Src, SCEV6_TEST_PKG, KEEP1, keep1Source("v2-full"));  // 改一点让 FULL 有信号
        runMvn(SCEV6_PATH);
        Path outFull = runLocal(SCEV6_PATH, SCEV6_ARTIFACT, DeployMode.FULL, null, baselineJar);
        byte[] targetKeep1 = readEntry(Path.of(SCEV6_PATH, "target", SCEV6_ARTIFACT),
                SCEV6_TEST_PKG + "/Keep1.class");
        byte[] fullKeep1 = readEntry(outFull, SCEV6_TEST_PKG + "/Keep1.class");
        assertTrue(Arrays.equals(fullKeep1, targetKeep1),   "[jar→jar FULL] 输出 Keep1 == 新 target 产物 Keep1");
        assertTrue(!Arrays.equals(fullKeep1, baselineKeep1),"[jar→jar FULL] 输出 Keep1 != 基准 Keep1");

        // Case 2 + 3: 共享的变更（在 INC 之前写好）
        writeTestFile(scev6Src, SCEV6_TEST_PKG, KEEP1, keep1Source("v1"));
        writeTestFile(scev6Src, SCEV6_TEST_PKG, KEEP2, keep2Source("v2", false));  // 改版本 + 去掉 Inner2
        writeTestFile(scev6Src, SCEV6_TEST_PKG, TO_ADD, toBeAddedSource());
        deleteTestFile(scev6Src, SCEV6_TEST_PKG, TO_DEL);
        runMvn(SCEV6_PATH);

        // Case 2: INCREMENTAL
        log("\n── [jar→jar] INCREMENTAL ──");
        List<String> incFiles = List.of(
                "src/main/java/" + SCEV6_TEST_PKG + "/" + TO_ADD,
                "src/main/java/" + SCEV6_TEST_PKG + "/" + KEEP2,
                "D src/main/java/" + SCEV6_TEST_PKG + "/" + TO_DEL
        );
        Path outInc = runLocal(SCEV6_PATH, SCEV6_ARTIFACT, DeployMode.INCREMENTAL, incFiles, baselineJar);
        assertJarJarIncrementalExpectations("jar→jar INC", outInc, baselineJar, baselineKeep2);

        // Case 3: AUTO_DETECT
        log("\n── [jar→jar] AUTO_DETECT ──");
        List<String> autoFiles = List.of(
                "A src/main/java/" + SCEV6_TEST_PKG + "/" + TO_ADD,
                "M src/main/java/" + SCEV6_TEST_PKG + "/" + KEEP2,
                "D src/main/java/" + SCEV6_TEST_PKG + "/" + TO_DEL
        );
        Path outAuto = runLocal(SCEV6_PATH, SCEV6_ARTIFACT, DeployMode.AUTO_DETECT, autoFiles, baselineJar);
        assertJarJarIncrementalExpectations("jar→jar AUTO", outAuto, baselineJar, baselineKeep2);
    }

    private void assertJarJarIncrementalExpectations(String label, Path outJar,
                                                      Path baselineJar, byte[] baselineKeep2) throws IOException {
        Set<String> out = listEntries(outJar);
        byte[] outKeep2 = readEntry(outJar, SCEV6_TEST_PKG + "/Keep2.class");
        assertTrue(out.contains(SCEV6_TEST_PKG + "/ToBeAdded.class"),
                "[" + label + "] ToBeAdded.class 应出现");
        assertTrue(out.contains(SCEV6_TEST_PKG + "/Keep2.class"),
                "[" + label + "] Keep2.class 应存在");
        assertTrue(!Arrays.equals(outKeep2, baselineKeep2),
                "[" + label + "] Keep2.class 字节应与基准不同");
        assertTrue(out.contains(SCEV6_TEST_PKG + "/Keep2$Inner1.class"),
                "[" + label + "] Keep2$Inner1.class 应存在");
        assertTrue(!out.contains(SCEV6_TEST_PKG + "/Keep2$Inner2.class"),
                "[" + label + "] Keep2$Inner2.class 应被残留清理");
        assertTrue(!out.contains(SCEV6_TEST_PKG + "/ToBeDeleted.class"),
                "[" + label + "] ToBeDeleted.class 应被删除");
        assertTrue(out.contains(SCEV6_TEST_PKG + "/Keep1.class"),
                "[" + label + "] Keep1.class 应保留");
    }

    // ═══════════════════════════════════════════════════════════════
    //  Scenario 2: jar → war/lib
    // ═══════════════════════════════════════════════════════════════
    private void runScenarioJarToWarLib() throws Exception {
        log("\n╔══════════════════════════════════════╗");
        log("║  Scenario 2: jar → war/lib           ║");
        log("╚══════════════════════════════════════╝");

        Path scev6Src = Path.of(SCEV6_PATH, "src/main/java");

        // 恢复基准状态
        writeTestFile(scev6Src, SCEV6_TEST_PKG, KEEP1, keep1Source("v1"));
        writeTestFile(scev6Src, SCEV6_TEST_PKG, KEEP2, keep2Source("v1", true));
        writeTestFile(scev6Src, SCEV6_TEST_PKG, TO_DEL, toBeDeletedSource());
        deleteTestFile(scev6Src, SCEV6_TEST_PKG, TO_ADD);
        runMvn(SCEV6_PATH);
        Path scev6Baseline = Files.copy(Path.of(SCEV6_PATH, "target", SCEV6_ARTIFACT),
                workRoot.resolve("scev6-baseline-s2.jar"), StandardCopyOption.REPLACE_EXISTING);

        // 构造测试 war：含三个 lib jar + classes + web.xml
        Path baselineWar = workRoot.resolve("war-baseline/test-embed.war");
        Files.createDirectories(baselineWar.getParent());
        Path junkJar = makeTinyJar(workRoot.resolve("junk.jar"), "x/Junk.class",
                "hello".getBytes(StandardCharsets.UTF_8));
        // 干扰项：同前缀 10.0.0 版本，用截短的 baseline 内容即可，只需确保文件名 + 可辨识字节
        Path interferenceJar = workRoot.resolve("interference-10.jar");
        byte[] interferenceBytes = markerBytes("v10-interference");
        Files.write(interferenceJar, interferenceBytes);

        buildTestWar(baselineWar,
                "WEB-INF/lib/scev6-utils-tms-9.0.0-SNAPSHOT.jar", scev6Baseline.toString(),
                "WEB-INF/lib/scev6-utils-tms-10.0.0-SNAPSHOT.jar", interferenceJar.toString(),
                "WEB-INF/lib/some-other-1.0.jar", junkJar.toString());
        // 额外塞一个静态文件和 web.xml
        appendEntry(baselineWar, "WEB-INF/web.xml", "<web-app/>".getBytes(StandardCharsets.UTF_8));
        appendEntry(baselineWar, "WEB-INF/classes/marker.txt",
                "untouched".getBytes(StandardCharsets.UTF_8));

        Set<String> warEntries = listEntries(baselineWar);
        assertTrue(warEntries.contains("WEB-INF/lib/scev6-utils-tms-9.0.0-SNAPSHOT.jar"),
                "测试 war 应含 9.0.0 lib");
        assertTrue(warEntries.contains("WEB-INF/lib/scev6-utils-tms-10.0.0-SNAPSHOT.jar"),
                "测试 war 应含 10.0.0 lib（干扰项）");
        assertTrue(warEntries.contains("WEB-INF/lib/some-other-1.0.jar"),
                "测试 war 应含 some-other lib（无关干扰）");

        byte[] baseline9LibBytes = readEntry(baselineWar,
                "WEB-INF/lib/scev6-utils-tms-9.0.0-SNAPSHOT.jar");
        byte[] baseline10LibBytes = readEntry(baselineWar,
                "WEB-INF/lib/scev6-utils-tms-10.0.0-SNAPSHOT.jar");
        byte[] baselineOtherBytes = readEntry(baselineWar,
                "WEB-INF/lib/some-other-1.0.jar");
        byte[] baselineMarker = readEntry(baselineWar, "WEB-INF/classes/marker.txt");
        byte[] baselineWebXml = readEntry(baselineWar, "WEB-INF/web.xml");

        // Case 1: FULL
        log("\n── [jar→war/lib] FULL ──");
        writeTestFile(scev6Src, SCEV6_TEST_PKG, KEEP1, keep1Source("v2-full"));
        runMvn(SCEV6_PATH);
        Path outFull = runLocalWithWar(baselineWar, DeployMode.FULL, null);
        assertJarWarLibCommon("jar→war/lib FULL", outFull,
                baseline10LibBytes, baselineOtherBytes, baselineMarker, baselineWebXml);
        // FULL 下 9.0.0 lib 被整体替换为新编译产物
        byte[] fullInner9 = extractInnerLib(outFull, "WEB-INF/lib/scev6-utils-tms-9.0.0-SNAPSHOT.jar");
        byte[] fullKeep1InLib = readEntryFromBytes(fullInner9, SCEV6_TEST_PKG + "/Keep1.class");
        byte[] targetKeep1 = readEntry(Path.of(SCEV6_PATH, "target", SCEV6_ARTIFACT),
                SCEV6_TEST_PKG + "/Keep1.class");
        assertTrue(Arrays.equals(fullKeep1InLib, targetKeep1),
                "[jar→war/lib FULL] 9.0.0 lib 内 Keep1 == 新 target 产物");

        // 准备 INC/AUTO 的源改动
        writeTestFile(scev6Src, SCEV6_TEST_PKG, KEEP1, keep1Source("v1"));
        writeTestFile(scev6Src, SCEV6_TEST_PKG, KEEP2, keep2Source("v2", false));
        writeTestFile(scev6Src, SCEV6_TEST_PKG, TO_ADD, toBeAddedSource());
        deleteTestFile(scev6Src, SCEV6_TEST_PKG, TO_DEL);
        runMvn(SCEV6_PATH);

        byte[] inner9BaselineKeep2 = readEntryFromBytes(
                readEntry(baselineWar, "WEB-INF/lib/scev6-utils-tms-9.0.0-SNAPSHOT.jar"),
                SCEV6_TEST_PKG + "/Keep2.class");

        // Case 2: INCREMENTAL
        log("\n── [jar→war/lib] INCREMENTAL ──");
        List<String> incFiles = List.of(
                "src/main/java/" + SCEV6_TEST_PKG + "/" + TO_ADD,
                "src/main/java/" + SCEV6_TEST_PKG + "/" + KEEP2,
                "D src/main/java/" + SCEV6_TEST_PKG + "/" + TO_DEL
        );
        Path outInc = runLocalWithWar(baselineWar, DeployMode.INCREMENTAL, incFiles);
        assertJarWarLibCommon("jar→war/lib INC", outInc,
                baseline10LibBytes, baselineOtherBytes, baselineMarker, baselineWebXml);
        assertJarWarLibInnerLibPatched("jar→war/lib INC", outInc, inner9BaselineKeep2);

        // Case 3: AUTO_DETECT
        log("\n── [jar→war/lib] AUTO_DETECT ──");
        List<String> autoFiles = List.of(
                "A src/main/java/" + SCEV6_TEST_PKG + "/" + TO_ADD,
                "M src/main/java/" + SCEV6_TEST_PKG + "/" + KEEP2,
                "D src/main/java/" + SCEV6_TEST_PKG + "/" + TO_DEL
        );
        Path outAuto = runLocalWithWar(baselineWar, DeployMode.AUTO_DETECT, autoFiles);
        assertJarWarLibCommon("jar→war/lib AUTO", outAuto,
                baseline10LibBytes, baselineOtherBytes, baselineMarker, baselineWebXml);
        assertJarWarLibInnerLibPatched("jar→war/lib AUTO", outAuto, inner9BaselineKeep2);
    }

    /** 共通断言：10.0.0 lib、some-other、classes、web.xml 都应原封不动 */
    private void assertJarWarLibCommon(String label, Path outWar,
                                        byte[] base10, byte[] baseOther,
                                        byte[] baseMarker, byte[] baseWebXml) throws IOException {
        byte[] out10 = readEntry(outWar, "WEB-INF/lib/scev6-utils-tms-10.0.0-SNAPSHOT.jar");
        byte[] outOther = readEntry(outWar, "WEB-INF/lib/some-other-1.0.jar");
        byte[] outMarker = readEntry(outWar, "WEB-INF/classes/marker.txt");
        byte[] outWebXml = readEntry(outWar, "WEB-INF/web.xml");
        assertTrue(Arrays.equals(out10, base10),       "[" + label + "] 10.0.0 lib 字节未变");
        assertTrue(Arrays.equals(outOther, baseOther), "[" + label + "] some-other lib 未变");
        assertTrue(Arrays.equals(outMarker, baseMarker),"[" + label + "] classes/marker 未变");
        assertTrue(Arrays.equals(outWebXml, baseWebXml),"[" + label + "] web.xml 未变");
    }

    /** INC/AUTO 下 9.0.0 lib 的内部 patch 正确性 */
    private void assertJarWarLibInnerLibPatched(String label, Path outWar, byte[] baselineKeep2) throws IOException {
        byte[] innerBytes = extractInnerLib(outWar, "WEB-INF/lib/scev6-utils-tms-9.0.0-SNAPSHOT.jar");
        Set<String> innerEntries = listEntriesFromBytes(innerBytes);
        byte[] outKeep2 = readEntryFromBytes(innerBytes, SCEV6_TEST_PKG + "/Keep2.class");
        assertTrue(innerEntries.contains(SCEV6_TEST_PKG + "/ToBeAdded.class"),
                "[" + label + "] inner jar ToBeAdded.class 应出现");
        assertTrue(!Arrays.equals(outKeep2, baselineKeep2),
                "[" + label + "] inner jar Keep2.class 字节应与基准不同");
        assertTrue(innerEntries.contains(SCEV6_TEST_PKG + "/Keep2$Inner1.class"),
                "[" + label + "] inner jar Keep2$Inner1.class 应存在");
        assertTrue(!innerEntries.contains(SCEV6_TEST_PKG + "/Keep2$Inner2.class"),
                "[" + label + "] inner jar Keep2$Inner2.class 应被残留清理");
        assertTrue(!innerEntries.contains(SCEV6_TEST_PKG + "/ToBeDeleted.class"),
                "[" + label + "] inner jar ToBeDeleted.class 应被删除");
    }

    private byte[] extractInnerLib(Path war, String entryName) throws IOException {
        return readEntry(war, entryName);
    }

    // ═══════════════════════════════════════════════════════════════
    //  Scenario 3: war → war
    // ═══════════════════════════════════════════════════════════════
    private void runScenarioWarToWar() throws Exception {
        log("\n╔══════════════════════════════════════╗");
        log("║  Scenario 3: war → war               ║");
        log("╚══════════════════════════════════════╝");

        Path tm01Src = Path.of(TM01_PATH, "src/main/java");
        writeTestFile(tm01Src, TM01_TEST_PKG, KEEP1, warTestSource(TM01_TEST_PKG, "Keep1", "v1"));
        writeTestFile(tm01Src, TM01_TEST_PKG, KEEP2, warKeep2Source("v1", true));
        writeTestFile(tm01Src, TM01_TEST_PKG, TO_DEL, warTestSource(TM01_TEST_PKG, "ToBeDeleted", "DEL"));

        runMvn(TM01_PATH);
        Path warBuilt = findWarArtifact(TM01_PATH);
        String warFileName = warBuilt.getFileName().toString();
        log("检测到 war 产物名: " + warFileName);

        Path baselineWarDir = Files.createDirectories(workRoot.resolve("tm01-baseline"));
        Path baselineWar = baselineWarDir.resolve(warFileName);
        Files.copy(warBuilt, baselineWar, StandardCopyOption.REPLACE_EXISTING);

        Set<String> base = listEntries(baselineWar);
        String keep1Entry    = "WEB-INF/classes/" + TM01_TEST_PKG + "/Keep1.class";
        String keep2Entry    = "WEB-INF/classes/" + TM01_TEST_PKG + "/Keep2.class";
        String keep2Inner1   = "WEB-INF/classes/" + TM01_TEST_PKG + "/Keep2$Inner1.class";
        String keep2Inner2   = "WEB-INF/classes/" + TM01_TEST_PKG + "/Keep2$Inner2.class";
        String toDelEntry    = "WEB-INF/classes/" + TM01_TEST_PKG + "/ToBeDeleted.class";
        String toAddEntry    = "WEB-INF/classes/" + TM01_TEST_PKG + "/ToBeAdded.class";
        assertTrue(base.contains(keep1Entry),  "[war] 基准应含 Keep1.class");
        assertTrue(base.contains(keep2Entry),  "[war] 基准应含 Keep2.class");
        assertTrue(base.contains(keep2Inner1), "[war] 基准应含 Keep2$Inner1.class");
        assertTrue(base.contains(keep2Inner2), "[war] 基准应含 Keep2$Inner2.class");
        assertTrue(base.contains(toDelEntry),  "[war] 基准应含 ToBeDeleted.class");

        byte[] baselineKeep1 = readEntry(baselineWar, keep1Entry);
        byte[] baselineKeep2 = readEntry(baselineWar, keep2Entry);

        // Case 1: FULL
        log("\n── [war→war] FULL ──");
        writeTestFile(tm01Src, TM01_TEST_PKG, KEEP1, warTestSource(TM01_TEST_PKG, "Keep1", "v2-full"));
        runMvn(TM01_PATH);
        Path outFull = runLocal(TM01_PATH, warFileName, DeployMode.FULL, null, baselineWar);
        byte[] fullKeep1 = readEntry(outFull, keep1Entry);
        byte[] targetKeep1InWar = readEntry(findWarArtifact(TM01_PATH), keep1Entry);
        assertTrue(Arrays.equals(fullKeep1, targetKeep1InWar),
                "[war→war FULL] 输出 Keep1 == 新 target war 的 Keep1");
        assertTrue(!Arrays.equals(fullKeep1, baselineKeep1),
                "[war→war FULL] 输出 Keep1 != 基准 Keep1");

        // INC/AUTO 源改动
        writeTestFile(tm01Src, TM01_TEST_PKG, KEEP1, warTestSource(TM01_TEST_PKG, "Keep1", "v1"));
        writeTestFile(tm01Src, TM01_TEST_PKG, KEEP2, warKeep2Source("v2", false));
        writeTestFile(tm01Src, TM01_TEST_PKG, TO_ADD, warTestSource(TM01_TEST_PKG, "ToBeAdded", "ADD"));
        deleteTestFile(tm01Src, TM01_TEST_PKG, TO_DEL);
        runMvn(TM01_PATH);

        // Case 2: INCREMENTAL
        log("\n── [war→war] INCREMENTAL ──");
        List<String> incFiles = List.of(
                "src/main/java/" + TM01_TEST_PKG + "/" + TO_ADD,
                "src/main/java/" + TM01_TEST_PKG + "/" + KEEP2,
                "D src/main/java/" + TM01_TEST_PKG + "/" + TO_DEL
        );
        Path outInc = runLocal(TM01_PATH, warFileName, DeployMode.INCREMENTAL, incFiles, baselineWar);
        assertWarWarIncrementalExpectations("war→war INC", outInc, baselineWar, baselineKeep2,
                keep1Entry, keep2Entry, keep2Inner1, keep2Inner2, toDelEntry, toAddEntry);

        // Case 3: AUTO_DETECT
        log("\n── [war→war] AUTO_DETECT ──");
        List<String> autoFiles = List.of(
                "A src/main/java/" + TM01_TEST_PKG + "/" + TO_ADD,
                "M src/main/java/" + TM01_TEST_PKG + "/" + KEEP2,
                "D src/main/java/" + TM01_TEST_PKG + "/" + TO_DEL
        );
        Path outAuto = runLocal(TM01_PATH, warFileName, DeployMode.AUTO_DETECT, autoFiles, baselineWar);
        assertWarWarIncrementalExpectations("war→war AUTO", outAuto, baselineWar, baselineKeep2,
                keep1Entry, keep2Entry, keep2Inner1, keep2Inner2, toDelEntry, toAddEntry);
    }

    private void assertWarWarIncrementalExpectations(String label, Path outWar, Path baselineWar,
                                                      byte[] baselineKeep2,
                                                      String keep1Entry, String keep2Entry,
                                                      String keep2Inner1, String keep2Inner2,
                                                      String toDelEntry, String toAddEntry) throws IOException {
        Set<String> out = listEntries(outWar);
        byte[] outKeep2 = readEntry(outWar, keep2Entry);
        assertTrue(out.contains(toAddEntry),       "[" + label + "] ToBeAdded.class 应出现");
        assertTrue(out.contains(keep2Entry),       "[" + label + "] Keep2.class 存在");
        assertTrue(!Arrays.equals(outKeep2, baselineKeep2),
                                                    "[" + label + "] Keep2.class 字节与基准不同");
        assertTrue(out.contains(keep2Inner1),      "[" + label + "] Keep2$Inner1.class 存在");
        assertTrue(!out.contains(keep2Inner2),     "[" + label + "] Keep2$Inner2.class 残留清理");
        assertTrue(!out.contains(toDelEntry),      "[" + label + "] ToBeDeleted.class 被删");
        assertTrue(out.contains(keep1Entry),       "[" + label + "] Keep1.class 保留（未变动）");

        // 关键：war→war 不应动 WEB-INF/lib
        Set<String> baseEntries = listEntries(baselineWar);
        int libChecked = 0;
        for (String e : baseEntries) {
            if (!e.startsWith("WEB-INF/lib/")) continue;
            byte[] baseBytes = readEntry(baselineWar, e);
            byte[] outBytes = readEntry(outWar, e);
            if (!Arrays.equals(baseBytes, outBytes)) {
                failures.add("[" + label + "] WEB-INF/lib/ 被误动: " + e);
                if (++libChecked > 3) break;
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  工具方法
    // ═══════════════════════════════════════════════════════════════

    /** 在源码下写测试文件，注册清理 */
    private void writeTestFile(Path srcRoot, String pkgPath, String fileName, String content) throws IOException {
        Path dir = srcRoot.resolve(pkgPath);
        Files.createDirectories(dir);
        Path file = dir.resolve(fileName);
        Files.writeString(file, content, StandardCharsets.UTF_8);
        cleanupActions.add(() -> {
            try { Files.deleteIfExists(file); } catch (Exception ignored) {}
        });
        // 同时注册包目录清理
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
        Path p = srcRoot.resolve(pkgPath).resolve(fileName);
        Files.deleteIfExists(p);
    }

    private Path runLocal(String modulePath, String artifactFileName,
                           DeployMode mode, List<String> changedFiles, Path input) throws IOException {
        Path caseOutDir = Files.createTempDirectory(workRoot, "out-");
        LocalPatchResult r = LocalPackagePatchService.execute(
                mode, modulePath, artifactFileName, changedFiles,
                input.toString(), caseOutDir.toString(),
                msg -> System.out.println("    [plugin] " + msg));
        if (!r.isSuccess()) {
            throw new RuntimeException("LocalPackagePatchService 失败: " + r.getErrorMessage());
        }
        return r.getOutputPackage();
    }

    /** 运行 war 作为输入（保证同名）；用 SCEV6 source + SCEV6 artifact 作为 patcher 的源信息 */
    private Path runLocalWithWar(Path inputWar, DeployMode mode, List<String> changedFiles) throws IOException {
        // 重命名为与产物一致的 .war（场景 2 要求模式分流，只看包名后缀）
        Path ensured = workRoot.resolve("in-" + System.nanoTime() + "-" + inputWar.getFileName());
        Files.copy(inputWar, ensured, StandardCopyOption.REPLACE_EXISTING);
        Path caseOutDir = Files.createTempDirectory(workRoot, "out-");
        LocalPatchResult r = LocalPackagePatchService.execute(
                mode, SCEV6_PATH, SCEV6_ARTIFACT, changedFiles,
                ensured.toString(), caseOutDir.toString(),
                msg -> System.out.println("    [plugin] " + msg));
        if (!r.isSuccess()) {
            throw new RuntimeException("LocalPackagePatchService 失败: " + r.getErrorMessage());
        }
        return r.getOutputPackage();
    }

    private void runMvn(String modulePath) throws Exception {
        log("[mvn] " + modulePath + "  clean package -DskipTests ...");
        ProcessBuilder pb = new ProcessBuilder(MVN, "clean", "package", "-DskipTests", "-q");
        pb.directory(Path.of(modulePath).toFile());
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
            System.out.println("--- mvn output ---\n" + sb + "\n---");
            throw new IOException("mvn 失败 ec=" + ec);
        }
        log("[mvn] OK");
    }

    private Path findWarArtifact(String modulePath) throws IOException {
        Path targetDir = Path.of(modulePath, "target");
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(targetDir, "*.war")) {
            for (Path p : ds) return p;
        }
        throw new IOException("未在 target/ 下找到 .war 产物: " + targetDir);
    }

    // --- jar/zip 操作 ---

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

    private Set<String> listEntriesFromBytes(byte[] jarBytes) throws IOException {
        Path tmp = Files.createTempFile("entries-", ".jar");
        try {
            Files.write(tmp, jarBytes);
            return listEntries(tmp);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private byte[] readEntryFromBytes(byte[] jarBytes, String name) throws IOException {
        Path tmp = Files.createTempFile("readentry-", ".jar");
        try {
            Files.write(tmp, jarBytes);
            return readEntry(tmp, name);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private Path makeTinyJar(Path output, String entryName, byte[] content) throws IOException {
        try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(output))) {
            jos.putNextEntry(new JarEntry(entryName));
            jos.write(content);
            jos.closeEntry();
        }
        return output;
    }

    /**
     * 构造一个 war（zip）：每对 (entryName, sourceFilePath) 写入一个条目
     */
    private void buildTestWar(Path output, String... nameAndPath) throws IOException {
        Files.createDirectories(output.getParent());
        try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(output))) {
            Set<String> created = new HashSet<>();
            for (int i = 0; i < nameAndPath.length; i += 2) {
                String name = nameAndPath[i];
                Path content = Path.of(nameAndPath[i + 1]);
                // 先建中间目录条目（war 工具有些要求）
                int slash = 0;
                while ((slash = name.indexOf('/', slash + 1)) > 0) {
                    String dir = name.substring(0, slash + 1);
                    if (created.add(dir)) {
                        jos.putNextEntry(new JarEntry(dir));
                        jos.closeEntry();
                    }
                }
                jos.putNextEntry(new JarEntry(name));
                Files.copy(content, jos);
                jos.closeEntry();
            }
        }
    }

    /**
     * 追加单个条目到已有 zip/war：读出旧的内容 + 新条目，写回去。
     */
    private void appendEntry(Path war, String entryName, byte[] content) throws IOException {
        Path tmp = Files.createTempFile("append-", ".war");
        try (JarFile old = new JarFile(war.toFile());
             JarOutputStream jos = new JarOutputStream(Files.newOutputStream(tmp))) {
            Enumeration<JarEntry> en = old.entries();
            while (en.hasMoreElements()) {
                JarEntry e = en.nextElement();
                JarEntry ne = new JarEntry(e.getName());
                jos.putNextEntry(ne);
                try (var is = old.getInputStream(e)) { is.transferTo(jos); }
                jos.closeEntry();
            }
            // 需要的父目录
            int slash = 0;
            Set<String> existing = listEntries(war);
            while ((slash = entryName.indexOf('/', slash + 1)) > 0) {
                String dir = entryName.substring(0, slash + 1);
                if (!existing.contains(dir)) {
                    jos.putNextEntry(new JarEntry(dir));
                    jos.closeEntry();
                    existing.add(dir);
                }
            }
            jos.putNextEntry(new JarEntry(entryName));
            jos.write(content);
            jos.closeEntry();
        }
        Files.move(tmp, war, StandardCopyOption.REPLACE_EXISTING);
    }

    private byte[] markerBytes(String tag) {
        // 构造一个最小合法 jar：至少一个条目
        Path t;
        try {
            t = Files.createTempFile("marker-", ".jar");
            try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(t))) {
                jos.putNextEntry(new JarEntry("MARKER/" + tag + ".txt"));
                jos.write(tag.getBytes(StandardCharsets.UTF_8));
                jos.closeEntry();
            }
            byte[] bytes = Files.readAllBytes(t);
            Files.deleteIfExists(t);
            return bytes;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void assertTrue(boolean cond, String msg) {
        if (cond) log("  ✓ " + msg);
        else { log("  ✗ " + msg); failures.add(msg); }
    }

    private void log(String msg) { System.out.println(msg); }

    private void cleanup() {
        // 反向执行清理动作
        for (int i = cleanupActions.size() - 1; i >= 0; i--) {
            try { cleanupActions.get(i).run(); } catch (Exception ignored) {}
        }
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

    // ═══════════════════════════════════════════════════════════════
    //  源码模板
    // ═══════════════════════════════════════════════════════════════

    private static String keep1Source(String tag) {
        return "package com.flux.scev6.flux_local_mode_test;\n" +
               "public class Keep1 { public static final String TAG = \"" + tag + "\"; }\n";
    }

    private static String keep2Source(String tag, boolean withInner2) {
        StringBuilder sb = new StringBuilder();
        sb.append("package com.flux.scev6.flux_local_mode_test;\n");
        sb.append("public class Keep2 {\n");
        sb.append("    public static final String VERSION = \"").append(tag).append("\";\n");
        sb.append("    static class Inner1 { int x; }\n");
        if (withInner2) sb.append("    static class Inner2 { int y; }\n");
        sb.append("}\n");
        return sb.toString();
    }

    private static String toBeDeletedSource() {
        return "package com.flux.scev6.flux_local_mode_test;\n" +
               "public class ToBeDeleted { public static final String NAME = \"DELETED\"; }\n";
    }

    private static String toBeAddedSource() {
        return "package com.flux.scev6.flux_local_mode_test;\n" +
               "public class ToBeAdded { public static final String NAME = \"ADDED\"; }\n";
    }

    private static String warTestSource(String pkgPath, String className, String tag) {
        String pkg = pkgPath.replace('/', '.');
        return "package " + pkg + ";\n" +
               "public class " + className + " { public static final String TAG = \"" + tag + "\"; }\n";
    }

    private static String warKeep2Source(String tag, boolean withInner2) {
        StringBuilder sb = new StringBuilder();
        sb.append("package ").append(TM01_TEST_PKG.replace('/', '.')).append(";\n");
        sb.append("public class Keep2 {\n");
        sb.append("    public static final String VERSION = \"").append(tag).append("\";\n");
        sb.append("    static class Inner1 { int x; }\n");
        if (withInner2) sb.append("    static class Inner2 { int y; }\n");
        sb.append("}\n");
        return sb.toString();
    }
}
