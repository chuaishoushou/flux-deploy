package com.flux.deploy.plugin.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Vue 前端工程解析器（纯文件系统逻辑，无 IDE 依赖）
 *
 * <p>识别 sce-vcom 体系的「模块式 Web 工程」（如 sce-vtms-m01-web）：</p>
 * <ul>
 *   <li>识别依据：工程根同时存在 {@code serve.yaml} 与 {@code package.json}，
 *       且 package.json 声明了 {@code zip:module} / {@code pack:module} 脚本
 *       （共享库 / 登录壳工程走 zip:server，不在本解析器范围）</li>
 *   <li>上下文名（content）：从 serve.yaml 的 {@code content:} 行读取（如 tm01webVue），
 *       它同时是线上 URL 第一段与模块 zip 文件名前缀</li>
 *   <li>业务模块：构建产物位于 {@code dist/umd/{模块号}/}（含 manifest.json 才算已构建），
 *       源码位于 {@code src/modules/{模块号}/}</li>
 * </ul>
 *
 * @author xumanyi
 * @date 2026-08-13
 */
public final class VueProjectResolver {

    /** serve.yaml 中 content 行的解析正则（与 sce-vcom-cli 的 getServeContent 口径一致） */
    private static final Pattern CONTENT_PATTERN = Pattern.compile("(?m)^content:\\s*(\\S+)\\s*$");

    /** package.json 中模块打包脚本的识别正则（存在即视为模块式 Web 工程） */
    private static final Pattern MODULE_SCRIPT_PATTERN = Pattern.compile("\"(?:zip|pack):module\"");

    /** dist 下 UMD 产物根目录（相对工程根） */
    public static final String DIST_UMD_DIR = "dist/umd";

    /** 模块源码根目录（相对工程根） */
    public static final String SRC_MODULES_DIR = "src/modules";

    private VueProjectResolver() {}

    /**
     * Vue 业务模块信息
     *
     * <p>{@code built=false} 表示 {@code dist/umd/} 下没有该模块的 manifest.json，
     * 即尚未构建（或构建产物被清理），部署前需用户先在前端工程里出包。</p>
     *
     * @author xumanyi
     * @date 2026-08-13
     */
    public static final class VueModule {
        /** 模块号（如 t0107） */
        public final String id;
        /** dist/umd/{id}/manifest.json 是否存在（产物是否就绪） */
        public final boolean built;
        /** dist/umd/{id}/ 子树内文件的最新修改时间（epoch 毫秒；未构建为 0） */
        public final long distMtime;
        /** src/modules/{id}/ 子树内文件的最新修改时间（epoch 毫秒；无源码目录为 0） */
        public final long srcMtime;

        /**
         * 构造模块信息
         *
         * @param id        模块号
         * @param built     产物是否就绪
         * @param distMtime 产物最新修改时间（epoch 毫秒）
         * @param srcMtime  源码最新修改时间（epoch 毫秒）
         * @author xumanyi
         * @date 2026-08-13
         */
        public VueModule(String id, boolean built, long distMtime, long srcMtime) {
            this.id = id;
            this.built = built;
            this.distMtime = distMtime;
            this.srcMtime = srcMtime;
        }

        /**
         * 产物是否已过期（源码晚于或等于产物，需要重新构建）
         *
         * <p>未构建、或缺任一侧时间信息时返回 false——「未构建」有独立状态展示，
         * 过期只对"曾经构建过"的模块有意义。</p>
         *
         * @return true 表示产物过期
         * @author xumanyi
         * @date 2026-08-13
         */
        public boolean isStale() {
            return built && distMtime > 0 && srcMtime > 0 && srcMtime >= distMtime;
        }
    }

    /**
     * 判断目录是否为可部署的 Vue 模块式 Web 工程
     *
     * @param projectRoot 工程根目录
     * @return 同时具备 serve.yaml、package.json 且声明模块打包脚本时返回 true
     * @author xumanyi
     * @date 2026-08-13
     */
    public static boolean isVueModuleProject(Path projectRoot) {
        if (projectRoot == null) return false;
        Path serveYaml = projectRoot.resolve("serve.yaml");
        Path packageJson = projectRoot.resolve("package.json");
        if (!Files.isRegularFile(serveYaml) || !Files.isRegularFile(packageJson)) {
            return false;
        }
        // 必须具备模块目录形态：src/modules（源码）或 dist/umd（产物）至少其一。
        // 共享库（如 sce-vcom-dialogs）也声明 zip:module 脚本，但产物平铺在根 umd/、
        // 没有"业务模块号目录"概念，不属于本工程类型
        if (!Files.isDirectory(projectRoot.resolve(SRC_MODULES_DIR))
                && !Files.isDirectory(projectRoot.resolve(DIST_UMD_DIR))) {
            return false;
        }
        String pkg = readQuietly(packageJson);
        return pkg != null && MODULE_SCRIPT_PATTERN.matcher(pkg).find();
    }

    /** package.json 中共享库服务器交换脚本的识别正则（存在即视为共享库工程） */
    private static final Pattern SHARED_LIB_SCRIPT_PATTERN = Pattern.compile("\"zip:server\"");

    /** package.json 中包名的解析正则 */
    private static final Pattern PACKAGE_NAME_PATTERN =
            Pattern.compile("\"name\"\\s*:\\s*\"([^\"]+)\"");

    /**
     * 判断目录是否为 sce-vcom 共享库工程（组件库 / stores / utils / functionconfig 等）。
     *
     * <p>特征：package.json 声明 {@code zip:server} 服务器交换脚本，且不是模块式 Web 工程
     * （无业务模块目录概念）。这类工程的产物是单个 UMD 文件
     * {@code dist/{包名}.umd.js}，部署形态是替换 login 壳应用 {@code lib/} 下的同名文件。</p>
     *
     * @param projectRoot 工程根目录
     * @return true 表示共享库工程
     * @author xumanyi
     * @date 2026-08-14
     */
    public static boolean isSharedLibProject(Path projectRoot) {
        if (projectRoot == null || isVueModuleProject(projectRoot)) return false;
        String pkg = readQuietly(projectRoot.resolve("package.json"));
        if (pkg == null || !SHARED_LIB_SCRIPT_PATTERN.matcher(pkg).find()) return false;
        String name = sharedLibName(projectRoot);
        if (name == null) return false;
        // 库工程的 main/module 指向 dist/{name}.umd.js|.es.js；
        // login 这类 Web 应用工程也带 zip:server 但没有库入口声明，据此排除
        return pkg.contains("dist/" + name + ".umd.js")
                || pkg.contains("dist/" + name + ".es.js");
    }

    /**
     * 读取共享库工程的包名（package.json 的 name 值，即 UMD 产物 / 远端 lib 文件的基名）
     *
     * @param projectRoot 工程根目录
     * @return 包名（如 sce-vcom-components）；解析失败返回 null
     * @author xumanyi
     * @date 2026-08-14
     */
    public static String sharedLibName(Path projectRoot) {
        String pkg = readQuietly(projectRoot.resolve("package.json"));
        if (pkg == null) return null;
        Matcher m = PACKAGE_NAME_PATTERN.matcher(pkg);
        return m.find() ? m.group(1) : null;
    }

    /**
     * 共享库工程的 UMD 产物路径（相对工程根）
     *
     * @param libName 包名
     * @return 产物相对路径（如 dist/sce-vcom-components.umd.js）
     * @author xumanyi
     * @date 2026-08-14
     */
    public static String sharedLibUmdRelPath(String libName) {
        return "dist/" + libName + ".umd.js";
    }

    /**
     * 读取工程的上下文名（serve.yaml 的 content 值）
     *
     * @param projectRoot 工程根目录
     * @return content 值（如 tm01webVue）；文件缺失或无 content 行返回 null
     * @author xumanyi
     * @date 2026-08-13
     */
    public static String readContent(Path projectRoot) {
        String yaml = readQuietly(projectRoot.resolve("serve.yaml"));
        if (yaml == null) return null;
        Matcher m = CONTENT_PATTERN.matcher(yaml);
        return m.find() ? m.group(1) : null;
    }

    /**
     * 列出工程的业务模块（dist 产物 ∪ src 源码，按模块号排序）
     *
     * <p>以 {@code dist/umd/} 下含 manifest.json 的子目录为「已构建」模块；
     * {@code src/modules/} 下存在但 dist 缺位的模块也列出（built=false），
     * 让用户能看见"该模块还没出包"而不是凭空消失。</p>
     *
     * @param projectRoot 工程根目录
     * @return 模块列表（按模块号忽略大小写排序）；目录缺失时返回空列表
     * @author xumanyi
     * @date 2026-08-13
     */
    public static List<VueModule> listModules(Path projectRoot) {
        List<VueModule> result = new ArrayList<>();
        List<String> seen = new ArrayList<>();

        Path umdRoot = projectRoot.resolve(DIST_UMD_DIR);
        Path srcRoot = projectRoot.resolve(SRC_MODULES_DIR);
        for (String id : listSubDirNames(umdRoot)) {
            if (!Files.isRegularFile(umdRoot.resolve(id).resolve("manifest.json"))) {
                continue; // 无 manifest 的目录不是模块产物（可能是残留）
            }
            result.add(new VueModule(id, true,
                    latestMtime(umdRoot.resolve(id)), latestMtime(srcRoot.resolve(id))));
            seen.add(id.toLowerCase(Locale.ROOT));
        }

        for (String id : listSubDirNames(srcRoot)) {
            if (seen.contains(id.toLowerCase(Locale.ROOT))) continue;
            result.add(new VueModule(id, false, 0L, latestMtime(srcRoot.resolve(id))));
        }

        result.sort(Comparator.comparing(m -> m.id.toLowerCase(Locale.ROOT)));
        return result;
    }

    /**
     * 拼装模块更新包 zip 文件名
     *
     * <p>命名与 sce-vcom-cli 新版 {@code zip:module} 一致：{@code {content}_{模块号}.zip}，
     * 不带时间戳——稳定文件名才能与 FTP 上的既有包做同名覆盖式更新。</p>
     *
     * @param content  工程上下文名（如 tm01webVue）
     * @param moduleId 模块号（如 t0107）
     * @return zip 文件名（如 tm01webVue_t0107.zip）
     * @author xumanyi
     * @date 2026-08-13
     */
    public static String zipFileName(String content, String moduleId) {
        return content + "_" + moduleId + ".zip";
    }

    /**
     * 从 zip 文件名反解模块号（目标 → 模块路由用）
     *
     * <p>兼容两代命名：{@code {content}_{模块号}.zip}（新版）与
     * {@code {content}_{模块号}_{yyyyMMddHHmm}.zip}（旧版带时间戳）。</p>
     *
     * @param content 工程上下文名
     * @param zipName zip 文件名
     * @return 模块号；命名不匹配该 content 时返回 null
     * @author xumanyi
     * @date 2026-08-13
     */
    public static String moduleIdFromZipName(String content, String zipName) {
        if (content == null || zipName == null) return null;
        String lower = zipName.toLowerCase(Locale.ROOT);
        String prefix = (content + "_").toLowerCase(Locale.ROOT);
        if (!lower.startsWith(prefix) || !lower.endsWith(".zip")) return null;
        String core = zipName.substring(content.length() + 1, zipName.length() - 4);
        // 旧版命名尾部可能带 _yyyyMMddHHmm 时间戳，剥掉
        Matcher m = Pattern.compile("^(.+?)_\\d{12}$").matcher(core);
        if (m.matches()) {
            core = m.group(1);
        }
        // 新版 CLI 支持多模块合包（{content}_{m1}_{m2}.zip）：模块号本身不含下划线，
        // 剥完时间戳仍带下划线的是合包，无法归属到单一模块，返回 null 交由用户手动处理
        if (core.isEmpty() || core.contains("_")) return null;
        return core;
    }

    /**
     * 计算目录子树内文件的最新修改时间
     *
     * @param root 目录
     * @return 最新文件 mtime（epoch 毫秒）；目录缺失或为空返回 0
     * @author xumanyi
     * @date 2026-08-13
     */
    public static long latestMtime(Path root) {
        if (!Files.isDirectory(root)) return 0L;
        final long[] latest = {0L};
        try {
            Files.walk(root)
                    .filter(Files::isRegularFile)
                    .filter(p -> {
                        // 排除历史打包残留 zip 与 .DS_Store：它们不属于产物/源码本体，
                        // 计入会让"产物是否新于源码"的判断被残留文件时间掩盖
                        String name = p.getFileName().toString().toLowerCase(Locale.ROOT);
                        return !name.endsWith(".zip") && !".ds_store".equals(name);
                    })
                    .forEach(p -> {
                        try {
                            long t = Files.getLastModifiedTime(p).toMillis();
                            if (t > latest[0]) latest[0] = t;
                        } catch (IOException ignored) {
                            // 单文件时间读取失败不影响整体判断
                        }
                    });
        } catch (IOException e) {
            return 0L;
        }
        return latest[0];
    }

    /**
     * 列出目录的一级子目录名
     *
     * @param dir 目录
     * @return 子目录名列表；目录缺失返回空列表
     * @author xumanyi
     * @date 2026-08-13
     */
    private static List<String> listSubDirNames(Path dir) {
        List<String> names = new ArrayList<>();
        if (!Files.isDirectory(dir)) return names;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path p : stream) {
                String name = p.getFileName().toString();
                if (Files.isDirectory(p) && !name.startsWith(".")) {
                    names.add(name);
                }
            }
        } catch (IOException ignored) {
            // 列目录失败按空处理，调用方自然得到"无模块"
        }
        return names;
    }

    /**
     * 静默读取文本文件（UTF-8）
     *
     * @param file 文件路径
     * @return 文件内容；读取失败返回 null
     * @author xumanyi
     * @date 2026-08-13
     */
    private static String readQuietly(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }
}
