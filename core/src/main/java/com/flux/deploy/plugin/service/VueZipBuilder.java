package com.flux.deploy.plugin.service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Vue 模块更新包构建器
 *
 * <p>把 {@code dist/umd/{模块号}/} 目录打成模块更新 zip，结构与 sce-vcom-cli 的
 * {@code zip:module} 产物一致：zip 内条目带一层模块号目录前缀
 * （{@code t0107/manifest.json}、{@code t0107/t0107.js} ...），客户侧在
 * {@code {静态根}/{content}/} 下解压即得 {@code {content}/{模块号}/...}。</p>
 *
 * <p>排除项：嵌套 {@code *.zip}（历史打包残留）与 {@code .DS_Store}。
 * 条目按相对路径排序写入，同一份产物两次打包字节序一致，便于对账。</p>
 *
 * @author xumanyi
 * @date 2026-08-13
 */
public final class VueZipBuilder {

    private VueZipBuilder() {}

    /**
     * 构建单个模块的更新包 zip
     *
     * @param projectRoot 工程根目录（含 dist/umd）
     * @param content     工程上下文名（如 tm01webVue），决定 zip 文件名
     * @param moduleId    模块号（如 t0107）
     * @param logCallback 进度日志回调（可为 null）
     * @return 生成的 zip 文件路径（位于临时目录，文件名 {content}_{moduleId}.zip）
     * @throws IOException 模块产物缺失（无 manifest.json）或写 zip 失败
     * @author xumanyi
     * @date 2026-08-13
     */
    public static Path buildModuleZip(Path projectRoot, String content, String moduleId,
                                      Consumer<String> logCallback) throws IOException {
        Path moduleDist = projectRoot.resolve(VueProjectResolver.DIST_UMD_DIR).resolve(moduleId);
        if (!Files.isRegularFile(moduleDist.resolve("manifest.json"))) {
            throw new IOException("Vue 模块产物缺失（无 manifest.json）: " + moduleDist
                    + "，请先在前端工程中构建该模块");
        }

        List<Path> files = collectFiles(moduleDist);
        if (files.isEmpty()) {
            throw new IOException("Vue 模块产物目录为空: " + moduleDist);
        }

        Path outDir = Files.createTempDirectory("flux-vuezip-");
        Path zipPath = outDir.resolve(VueProjectResolver.zipFileName(content, moduleId));
        long totalBytes = 0;
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zipPath))) {
            for (Path file : files) {
                String rel = moduleDist.relativize(file).toString().replace('\\', '/');
                ZipEntry entry = new ZipEntry(moduleId + "/" + rel);
                entry.setTime(Files.getLastModifiedTime(file).toMillis());
                zos.putNextEntry(entry);
                try (InputStream in = Files.newInputStream(file)) {
                    in.transferTo(zos);
                }
                zos.closeEntry();
                totalBytes += Files.size(file);
            }
        } catch (IOException e) {
            Files.deleteIfExists(zipPath);
            Files.deleteIfExists(outDir);
            throw e;
        }

        if (logCallback != null) {
            logCallback.accept(String.format("[Vue 打包] %s：%d 个文件（%.1f KB）→ %s",
                    moduleId, files.size(), totalBytes / 1024.0, zipPath.getFileName()));
        }
        return zipPath;
    }

    /**
     * 收集模块产物目录内的待打包文件（排序、排除 zip 与 .DS_Store）
     *
     * @param moduleDist 模块产物目录（dist/umd/{模块号}）
     * @return 按相对路径排序的文件列表
     * @throws IOException 遍历失败
     * @author xumanyi
     * @date 2026-08-13
     */
    private static List<Path> collectFiles(Path moduleDist) throws IOException {
        List<Path> files = new ArrayList<>();
        try (var stream = Files.walk(moduleDist)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> {
                        String name = p.getFileName().toString();
                        String lower = name.toLowerCase(Locale.ROOT);
                        return !lower.endsWith(".zip") && !".ds_store".equals(lower);
                    })
                    .forEach(files::add);
        }
        files.sort(Comparator.comparing(p -> moduleDist.relativize(p).toString()));
        return files;
    }
}
