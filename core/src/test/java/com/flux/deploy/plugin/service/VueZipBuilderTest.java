package com.flux.deploy.plugin.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VueZipBuilderTest {

    @TempDir
    Path tempDir;

    /**
     * 搭一个含嵌套子目录 / 排除项的模块产物目录
     *
     * @author xumanyi
     * @date 2026-08-13
     */
    private void scaffoldModuleDist() throws IOException {
        Path dist = tempDir.resolve("dist/umd/t0107");
        Files.createDirectories(dist.resolve("submodules/address"));
        Files.writeString(dist.resolve("manifest.json"), "{\"entry\":\"t0107.js\"}");
        Files.writeString(dist.resolve("t0107.js"), "console.log('t0107')");
        Files.writeString(dist.resolve("t0107.css"), ".a{}");
        Files.writeString(dist.resolve("submodules/address/address.js"), "console.log('addr')");
        // 排除项：历史打包残留 zip 与 .DS_Store
        Files.writeString(dist.resolve("tm01webVue_t0107_202605261415.zip"), "old-zip");
        Files.writeString(dist.resolve(".DS_Store"), "junk");
    }

    @Test
    void buildModuleZip_packsWithModulePrefixAndExcludesJunk() throws Exception {
        scaffoldModuleDist();

        Path zip = VueZipBuilder.buildModuleZip(tempDir, "tm01webVue", "t0107", null);

        assertThat(zip).exists();
        assertThat(zip.getFileName().toString()).isEqualTo("tm01webVue_t0107.zip");
        List<String> entries = new ArrayList<>();
        try (ZipFile zf = new ZipFile(zip.toFile())) {
            zf.stream().map(ZipEntry::getName).forEach(entries::add);
        }
        // 条目带一层模块号目录前缀，与 sce-vcom-cli 的 zip 结构一致
        assertThat(entries).containsExactly(
                "t0107/manifest.json",
                "t0107/submodules/address/address.js",
                "t0107/t0107.css",
                "t0107/t0107.js");
    }

    @Test
    void buildModuleZip_missingManifest_throws() throws IOException {
        Files.createDirectories(tempDir.resolve("dist/umd/t0104"));

        assertThatThrownBy(() ->
                VueZipBuilder.buildModuleZip(tempDir, "tm01webVue", "t0104", null))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("manifest.json");
    }

    @Test
    void buildModuleZip_twoCallsProduceIndependentFiles() throws Exception {
        scaffoldModuleDist();

        Path zip1 = VueZipBuilder.buildModuleZip(tempDir, "tm01webVue", "t0107", null);
        Path zip2 = VueZipBuilder.buildModuleZip(tempDir, "tm01webVue", "t0107", null);

        // 多主目标场景：每次调用产出独立文件，互不覆盖
        assertThat(zip1).isNotEqualTo(zip2);
        assertThat(zip1).exists();
        assertThat(zip2).exists();
        assertThat(Files.size(zip1)).isEqualTo(Files.size(zip2));
    }
}
