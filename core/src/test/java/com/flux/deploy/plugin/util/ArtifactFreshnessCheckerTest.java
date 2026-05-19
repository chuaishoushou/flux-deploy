package com.flux.deploy.plugin.util;

import com.flux.deploy.plugin.model.DeployMode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ArtifactFreshnessChecker 单元测试：覆盖 INCREMENTAL/FULL 模式下的"源码 vs 编译产物"时间对比，
 * 以及对缺产物、空清单、非 java 条目、modulePath 缺失等情形的退化行为。
 *
 * @author xumanyi
 * @date 2026-05-19
 */
class ArtifactFreshnessCheckerTest {

    @TempDir
    Path moduleRoot;

    private Path sourcesDir;
    private Path classesDir;

    @BeforeEach
    void setUp() throws IOException {
        sourcesDir = Files.createDirectories(moduleRoot.resolve("src/main/java"));
        classesDir = Files.createDirectories(moduleRoot.resolve("target/classes"));
    }

    @Test
    void incremental_javaNewerThanClass_isStale() throws IOException {
        Path java = writeFile(sourcesDir.resolve("com/foo/Bar.java"), "class Bar {}");
        Path clazz = writeFile(classesDir.resolve("com/foo/Bar.class"), "binary");
        setMtime(clazz, Instant.parse("2026-05-18T09:00:00Z"));
        setMtime(java, Instant.parse("2026-05-19T10:00:00Z"));

        ArtifactFreshnessChecker.Result r = ArtifactFreshnessChecker.check(
                DeployMode.INCREMENTAL, moduleRoot.toString(), null,
                List.of("src/main/java/com/foo/Bar.java"));

        assertThat(r.isFresh()).isFalse();
        assertThat(r.staleSources).containsExactly("src/main/java/com/foo/Bar.java");
    }

    @Test
    void incremental_classNewerThanJava_isFresh() throws IOException {
        Path java = writeFile(sourcesDir.resolve("com/foo/Bar.java"), "class Bar {}");
        Path clazz = writeFile(classesDir.resolve("com/foo/Bar.class"), "binary");
        setMtime(java, Instant.parse("2026-05-18T09:00:00Z"));
        setMtime(clazz, Instant.parse("2026-05-19T10:00:00Z"));

        ArtifactFreshnessChecker.Result r = ArtifactFreshnessChecker.check(
                DeployMode.INCREMENTAL, moduleRoot.toString(), null,
                List.of("src/main/java/com/foo/Bar.java"));

        assertThat(r.isFresh()).isTrue();
    }

    @Test
    void incremental_missingClass_isSkipped() throws IOException {
        writeFile(sourcesDir.resolve("com/foo/Bar.java"), "class Bar {}");
        // 不写 class 文件，由 PresenceValidator 兜底

        ArtifactFreshnessChecker.Result r = ArtifactFreshnessChecker.check(
                DeployMode.INCREMENTAL, moduleRoot.toString(), null,
                List.of("src/main/java/com/foo/Bar.java"));

        assertThat(r.isFresh()).isTrue();
    }

    @Test
    void incremental_nonJavaEntries_areIgnored() throws IOException {
        Path java = writeFile(sourcesDir.resolve("com/foo/Bar.java"), "class Bar {}");
        Path clazz = writeFile(classesDir.resolve("com/foo/Bar.class"), "binary");
        setMtime(clazz, Instant.parse("2026-05-19T10:00:00Z"));
        setMtime(java, Instant.parse("2026-05-18T09:00:00Z"));
        writeFile(moduleRoot.resolve("src/main/resources/i18n.properties"), "k=v");

        ArtifactFreshnessChecker.Result r = ArtifactFreshnessChecker.check(
                DeployMode.INCREMENTAL, moduleRoot.toString(), null,
                List.of("src/main/java/com/foo/Bar.java",
                        "src/main/resources/i18n.properties"));

        assertThat(r.isFresh()).isTrue();
    }

    @Test
    void incremental_emptyChangedFiles_isFresh() {
        ArtifactFreshnessChecker.Result r = ArtifactFreshnessChecker.check(
                DeployMode.INCREMENTAL, moduleRoot.toString(), null, List.of());

        assertThat(r.isFresh()).isTrue();
    }

    @Test
    void incremental_vcsPrefix_isStripped() throws IOException {
        Path java = writeFile(sourcesDir.resolve("com/foo/Bar.java"), "class Bar {}");
        Path clazz = writeFile(classesDir.resolve("com/foo/Bar.class"), "binary");
        setMtime(clazz, Instant.parse("2026-05-18T09:00:00Z"));
        setMtime(java, Instant.parse("2026-05-19T10:00:00Z"));

        ArtifactFreshnessChecker.Result r = ArtifactFreshnessChecker.check(
                DeployMode.INCREMENTAL, moduleRoot.toString(), null,
                List.of("M src/main/java/com/foo/Bar.java"));

        assertThat(r.isFresh()).isFalse();
        assertThat(r.staleSources).containsExactly("src/main/java/com/foo/Bar.java");
    }

    @Test
    void incremental_multipleStale_areAllListed() throws IOException {
        Path javaA = writeFile(sourcesDir.resolve("com/foo/A.java"), "");
        Path classA = writeFile(classesDir.resolve("com/foo/A.class"), "");
        Path javaB = writeFile(sourcesDir.resolve("com/foo/B.java"), "");
        Path classB = writeFile(classesDir.resolve("com/foo/B.class"), "");
        setMtime(classA, Instant.parse("2026-05-18T09:00:00Z"));
        setMtime(classB, Instant.parse("2026-05-18T09:00:00Z"));
        setMtime(javaA, Instant.parse("2026-05-19T10:00:00Z"));
        setMtime(javaB, Instant.parse("2026-05-19T11:00:00Z"));

        ArtifactFreshnessChecker.Result r = ArtifactFreshnessChecker.check(
                DeployMode.INCREMENTAL, moduleRoot.toString(), null,
                List.of("src/main/java/com/foo/A.java",
                        "src/main/java/com/foo/B.java"));

        assertThat(r.isFresh()).isFalse();
        assertThat(r.staleSources).containsExactlyInAnyOrder(
                "src/main/java/com/foo/A.java",
                "src/main/java/com/foo/B.java");
    }

    @Test
    void full_anyJavaNewerThanJar_isStale() throws IOException {
        Path jar = writeFile(moduleRoot.resolve("target/app.jar"), "binary");
        Path javaA = writeFile(sourcesDir.resolve("com/foo/A.java"), "");
        Path javaB = writeFile(sourcesDir.resolve("com/foo/B.java"), "");
        setMtime(jar, Instant.parse("2026-05-18T09:00:00Z"));
        setMtime(javaA, Instant.parse("2026-05-17T09:00:00Z"));
        setMtime(javaB, Instant.parse("2026-05-19T10:00:00Z"));

        ArtifactFreshnessChecker.Result r = ArtifactFreshnessChecker.check(
                DeployMode.FULL, moduleRoot.toString(), "app.jar", null);

        assertThat(r.isFresh()).isFalse();
        assertThat(r.staleSources).containsExactly("src/main/java/com/foo/B.java");
    }

    @Test
    void full_allJavaOlderThanJar_isFresh() throws IOException {
        Path jar = writeFile(moduleRoot.resolve("target/app.jar"), "binary");
        Path javaA = writeFile(sourcesDir.resolve("com/foo/A.java"), "");
        setMtime(javaA, Instant.parse("2026-05-17T09:00:00Z"));
        setMtime(jar, Instant.parse("2026-05-19T10:00:00Z"));

        ArtifactFreshnessChecker.Result r = ArtifactFreshnessChecker.check(
                DeployMode.FULL, moduleRoot.toString(), "app.jar", null);

        assertThat(r.isFresh()).isTrue();
    }

    @Test
    void full_jarMissing_isFresh() throws IOException {
        writeFile(sourcesDir.resolve("com/foo/A.java"), "");
        // 不写 jar，由 PresenceValidator 兜底

        ArtifactFreshnessChecker.Result r = ArtifactFreshnessChecker.check(
                DeployMode.FULL, moduleRoot.toString(), "app.jar", null);

        assertThat(r.isFresh()).isTrue();
    }

    @Test
    void full_artifactFileNameBlank_isFresh() throws IOException {
        writeFile(sourcesDir.resolve("com/foo/A.java"), "");

        ArtifactFreshnessChecker.Result r = ArtifactFreshnessChecker.check(
                DeployMode.FULL, moduleRoot.toString(), "", null);

        assertThat(r.isFresh()).isTrue();
    }

    @Test
    void full_modulePathNull_isFresh() {
        ArtifactFreshnessChecker.Result r = ArtifactFreshnessChecker.check(
                DeployMode.FULL, null, "app.jar", null);

        assertThat(r.isFresh()).isTrue();
    }

    @Test
    void full_noJavaSources_isFresh() throws IOException {
        Path jar = writeFile(moduleRoot.resolve("target/app.jar"), "binary");
        setMtime(jar, Instant.parse("2026-05-19T10:00:00Z"));

        ArtifactFreshnessChecker.Result r = ArtifactFreshnessChecker.check(
                DeployMode.FULL, moduleRoot.toString(), "app.jar", null);

        assertThat(r.isFresh()).isTrue();
    }

    private static Path writeFile(Path path, String content) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
        return path;
    }

    private static void setMtime(Path path, Instant when) throws IOException {
        Files.setLastModifiedTime(path, FileTime.from(when));
    }
}
