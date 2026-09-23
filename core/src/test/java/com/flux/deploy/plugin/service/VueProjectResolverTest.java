package com.flux.deploy.plugin.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class VueProjectResolverTest {

    @TempDir
    Path tempDir;

    /**
     * 搭一个最小 Vue 模块式 Web 工程骨架
     *
     * @param withModuleScript package.json 是否声明 zip:module 脚本
     * @author xumanyi
     * @date 2026-08-13
     */
    private void scaffoldProject(boolean withModuleScript) throws IOException {
        Files.writeString(tempDir.resolve("serve.yaml"),
                "name: vtms-m01-web\ncontent: tm01webVue\nport: 3101\n");
        Files.createDirectories(tempDir.resolve("src/modules"));
        String scripts = withModuleScript
                ? "{\"name\":\"sce-vtms-m01-web\",\"scripts\":{\"zip:module\":\"sce-vcom zip:module\"}}"
                : "{\"name\":\"sce-vcom-login\",\"scripts\":{\"zip:server\":\"sce-vcom zip:server\"}}";
        Files.writeString(tempDir.resolve("package.json"), scripts);
    }

    @Test
    void isVueModuleProject_requiresServeYamlAndModuleScript() throws IOException {
        scaffoldProject(true);
        assertThat(VueProjectResolver.isVueModuleProject(tempDir)).isTrue();
    }

    @Test
    void isVueModuleProject_rejectsServerOnlyProject() throws IOException {
        scaffoldProject(false);
        assertThat(VueProjectResolver.isVueModuleProject(tempDir)).isFalse();
    }

    @Test
    void isVueModuleProject_rejectsPlainDir() {
        assertThat(VueProjectResolver.isVueModuleProject(tempDir)).isFalse();
    }

    /**
     * 共享库（如 sce-vcom-dialogs）也声明 zip:module 脚本，但没有 src/modules 或 dist/umd
     * 的模块目录形态，不应被识别为模块式 Web 工程。
     */
    @Test
    void isVueModuleProject_rejectsSharedLibWithModuleScriptButNoModulesDir() throws IOException {
        Files.writeString(tempDir.resolve("serve.yaml"),
                "name: sce-vcom-dialogs\ncontent: sce-vcom-dialogs\nport: 3016\n");
        Files.writeString(tempDir.resolve("package.json"),
                "{\"name\":\"sce-vcom-dialogs\",\"scripts\":{\"zip:module\":\"sce-vcom zip:module\"}}");
        assertThat(VueProjectResolver.isVueModuleProject(tempDir)).isFalse();
    }

    @Test
    void readContent_parsesServeYaml() throws IOException {
        scaffoldProject(true);
        assertThat(VueProjectResolver.readContent(tempDir)).isEqualTo("tm01webVue");
    }

    @Test
    void listModules_mergesDistAndSrc() throws IOException {
        scaffoldProject(true);
        // dist 下已构建模块 t0107（有 manifest）+ 残留目录 junk（无 manifest）
        Path t0107 = tempDir.resolve("dist/umd/t0107");
        Files.createDirectories(t0107);
        Files.writeString(t0107.resolve("manifest.json"), "{}");
        Files.writeString(t0107.resolve("t0107.js"), "console.log(1)");
        Files.createDirectories(tempDir.resolve("dist/umd/junk"));
        // src 下有源码但未构建的模块 t0103
        Files.createDirectories(tempDir.resolve("src/modules/t0103"));
        // src 与 dist 同时存在的模块只出现一次
        Files.createDirectories(tempDir.resolve("src/modules/t0107"));

        List<VueProjectResolver.VueModule> modules = VueProjectResolver.listModules(tempDir);

        assertThat(modules).extracting(m -> m.id).containsExactly("t0103", "t0107");
        assertThat(modules.get(0).built).isFalse();
        assertThat(modules.get(1).built).isTrue();
        assertThat(modules.get(1).distMtime).isGreaterThan(0L);
    }

    @Test
    void zipFileName_matchesCliNaming() {
        assertThat(VueProjectResolver.zipFileName("tm01webVue", "t0107"))
                .isEqualTo("tm01webVue_t0107.zip");
    }

    @Test
    void moduleIdFromZipName_parsesBothGenerations() {
        // 新版：无时间戳
        assertThat(VueProjectResolver.moduleIdFromZipName("tm01webVue", "tm01webVue_t0107.zip"))
                .isEqualTo("t0107");
        // 旧版：带分钟级时间戳
        assertThat(VueProjectResolver.moduleIdFromZipName(
                "tm01webVue", "tm01webVue_t0107_202605261415.zip"))
                .isEqualTo("t0107");
        // 大小写不敏感
        assertThat(VueProjectResolver.moduleIdFromZipName("tm01webVue", "TM01WEBVUE_t0107.zip"))
                .isEqualTo("t0107");
        // 其他 content 的 zip 不命中
        assertThat(VueProjectResolver.moduleIdFromZipName("tm01webVue", "tm02webVue_t0201.zip"))
                .isNull();
        // 非 zip 不命中
        assertThat(VueProjectResolver.moduleIdFromZipName("tm01webVue", "tm01webVue_t0107.jar"))
                .isNull();
        // 新版 CLI 多模块合包（模块号间下划线连接）无法归属单一模块，返回 null
        assertThat(VueProjectResolver.moduleIdFromZipName("tm01webVue", "tm01webVue_t0103_t0107.zip"))
                .isNull();
    }

    /**
     * dist 模块目录里的历史打包残留 zip 与 .DS_Store 不计入最新 mtime，
     * 避免残留文件时间掩盖"产物已过期"。
     */
    @Test
    void latestMtime_ignoresResidualZipAndDsStore() throws IOException {
        Path dir = Files.createDirectories(tempDir.resolve("dist/umd/t0107"));
        Path js = dir.resolve("t0107.js");
        Files.writeString(js, "console.log(1)");
        Files.setLastModifiedTime(js, java.nio.file.attribute.FileTime.fromMillis(1_000_000L));
        Path residualZip = dir.resolve("tm01webVue_t0107_202605261415.zip");
        Files.writeString(residualZip, "old");
        Files.setLastModifiedTime(residualZip, java.nio.file.attribute.FileTime.fromMillis(9_000_000L));

        assertThat(VueProjectResolver.latestMtime(dir)).isEqualTo(1_000_000L);
    }
}
