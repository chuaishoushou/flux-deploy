package com.flux.deploy.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 守卫 {@link WarEmbedUtil#embedJar} 的"完整文件名精确匹配"契约
 *
 * <p>历史 bug：旧实现 {@code startsWith(jarArtifactId)} 会把 {@code scev6-utils-objs-*}
 * 等同前缀 sibling jar 误命中，配合 {@code extractEmbeddedJar} 同样的前缀匹配，
 * 在多模块 maven 工程里抽到错误的 jar、再把"打了 4 个 class 补丁"的小弟 jar 写回到
 * 主包位置，主包内容被替换为 ~700KB 的错误产物。校验阶段只比较 lib 文件数量，无法察觉。</p>
 *
 * <p>这些用例锁住"精确匹配 + 严格存在性 + 扩展名校验"，一旦回退到 prefix 兜底立即红。</p>
 */
class WarEmbedUtilTest {

    @Test
    void embedJar_rejectsNonJarTargetName(@TempDir Path tmp) throws IOException {
        Path war = newWarWithLib(tmp.resolve("src.war"),
                new String[]{"scev6-utils-6.2.1-SNAPSHOT.jar"},
                new String[]{"original-utils-content"});
        Path patch = writeBytes(tmp.resolve("patch.jar"), "patched-content");
        Path out = tmp.resolve("out.war");

        assertThatThrownBy(() -> WarEmbedUtil.embedJar(war, patch, "scev6-utils", out))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("完整 jar 文件名");
    }

    @Test
    void embedJar_failsWhenLibMissesExactName(@TempDir Path tmp) throws IOException {
        // war 内只放 sibling 包，主包不存在 → 严格匹配应直接失败
        // （旧实现会用 startsWith 兜底命中 sibling，把它当主包替换掉）
        Path war = newWarWithLib(tmp.resolve("src.war"),
                new String[]{"scev6-utils-objs-6.2.1-SNAPSHOT.jar"},
                new String[]{"objs-payload"});
        Path patch = writeBytes(tmp.resolve("patch.jar"), "patched-content");
        Path out = tmp.resolve("out.war");

        assertThatThrownBy(() -> WarEmbedUtil.embedJar(war, patch,
                "scev6-utils-6.2.1-SNAPSHOT.jar", out))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("不存在 scev6-utils-6.2.1-SNAPSHOT.jar");
    }

    @Test
    void embedJar_replacesOnlyExactName_siblingsUntouched(@TempDir Path tmp) throws IOException {
        // war 同时包含主包和同前缀 sibling；只有完整文件名 equals 的那个被替换
        Path war = newWarWithLib(tmp.resolve("src.war"),
                new String[]{
                        "scev6-utils-6.2.1-SNAPSHOT.jar",
                        "scev6-utils-objs-6.2.1-SNAPSHOT.jar",
                        "scev6-utils-wms-6.2.1-SNAPSHOT.jar"
                },
                new String[]{
                        "MAIN-jar-original-bytes",
                        "OBJS-jar-original-bytes",
                        "WMS-jar-original-bytes"
                });
        Path patch = writeBytes(tmp.resolve("patch.jar"), "patched-content");
        Path out = tmp.resolve("out.war");

        WarEmbedUtil.EmbedResult res = WarEmbedUtil.embedJar(war, patch,
                "scev6-utils-6.2.1-SNAPSHOT.jar", out);

        assertThat(res.isVerified()).isTrue();
        assertThat(res.getTargetJarName()).isEqualTo("scev6-utils-6.2.1-SNAPSHOT.jar");
        // 输出 war 必须仍含两个 sibling 且字节未变
        byte[] objsAfter = readEntry(out, "WEB-INF/lib/scev6-utils-objs-6.2.1-SNAPSHOT.jar");
        byte[] wmsAfter = readEntry(out, "WEB-INF/lib/scev6-utils-wms-6.2.1-SNAPSHOT.jar");
        byte[] mainAfter = readEntry(out, "WEB-INF/lib/scev6-utils-6.2.1-SNAPSHOT.jar");
        assertThat(new String(objsAfter)).isEqualTo("OBJS-jar-original-bytes");
        assertThat(new String(wmsAfter)).isEqualTo("WMS-jar-original-bytes");
        assertThat(new String(mainAfter)).isEqualTo("patched-content");
    }

    // ===== helpers =====

    private static Path newWarWithLib(Path warPath, String[] libNames, String[] libBytes) throws IOException {
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(warPath))) {
            // war 内随便加点其他 entry，避免 lib 是唯一内容
            zos.putNextEntry(new ZipEntry("WEB-INF/web.xml"));
            zos.write("<web-app/>".getBytes());
            zos.closeEntry();
            for (int i = 0; i < libNames.length; i++) {
                zos.putNextEntry(new ZipEntry("WEB-INF/lib/" + libNames[i]));
                zos.write(libBytes[i].getBytes());
                zos.closeEntry();
            }
        }
        return warPath;
    }

    private static Path writeBytes(Path p, String content) throws IOException {
        try (OutputStream os = Files.newOutputStream(p)) {
            os.write(content.getBytes());
        }
        return p;
    }

    private static byte[] readEntry(Path war, String entryName) throws IOException {
        try (java.util.jar.JarFile jar = new java.util.jar.JarFile(war.toFile())) {
            java.util.jar.JarEntry e = jar.getJarEntry(entryName);
            assertThat(e).as("entry %s in %s", entryName, war).isNotNull();
            return jar.getInputStream(e).readAllBytes();
        }
    }
}
