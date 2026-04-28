package com.flux.deploy.cli;

import com.flux.deploy.git.GitStatusHelper;
import com.flux.deploy.maven.MavenRunner;
import com.flux.deploy.plugin.model.DeployMode;
import com.flux.deploy.plugin.service.LocalPackagePatchService;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * patch-local 子命令：对用户提供的本地 jar/war 包做增量补丁，输出一个打好补丁的新包。
 * 全程无 FTP、无备份、无版本记录、不可回滚。对应插件"本地模式"。
 *
 * <p>三种分流（由源产物 × 用户包类型自动判定）：</p>
 * <ul>
 *   <li>jar → jar：替换 jar 内 class / 资源</li>
 *   <li>war → war：替换 war 内 WEB-INF/classes 下条目</li>
 *   <li>jar → war/lib：定位 war 内的嵌入 jar → 打补丁 → 回写 war</li>
 * </ul>
 *
 * @author xumanyi
 * @date 2026-03-21
 */
final class PatchLocalCommand {

    private static final int EX_USAGE = 64;
    private static final int EX_SOFTWARE = 70;

    private PatchLocalCommand() {
    }

    /**
     * 执行 patch-local 子命令
     *
     * @param args 去掉子命令后的参数
     * @return 退出码
     * @author xumanyi
     * @date 2026-03-21
     */
    static int run(String[] args) {
        Args parsed;
        try {
            parsed = Args.parse(args);
        } catch (IllegalArgumentException e) {
            System.err.println("[patch-local] " + e.getMessage());
            printUsage(System.err);
            return EX_USAGE;
        }

        if (parsed.projectDir == null || parsed.localPackage == null) {
            System.err.println("[patch-local] --project-dir / --local-package 必填");
            printUsage(System.err);
            return EX_USAGE;
        }

        Path projectDir = Paths.get(parsed.projectDir).toAbsolutePath().normalize();
        if (!Files.isDirectory(projectDir) || !Files.isRegularFile(projectDir.resolve("pom.xml"))) {
            System.err.println("[patch-local] projectDir 不存在或缺 pom.xml: " + projectDir);
            return EX_USAGE;
        }

        Path localPackage = Paths.get(parsed.localPackage).toAbsolutePath().normalize();
        if (!Files.isRegularFile(localPackage)) {
            System.err.println("[patch-local] 本地包不存在: " + localPackage);
            return EX_USAGE;
        }

        DeployMode mode = parseMode(parsed.mode);
        if (mode == null) {
            System.err.println("[patch-local] mode 非法: " + parsed.mode);
            return EX_USAGE;
        }

        String artifactName = parsed.artifactName;
        if (artifactName == null || artifactName.isBlank()) {
            artifactName = inferArtifactName(projectDir);
            if (artifactName == null) {
                System.err.println("[patch-local] 无法推断源工程产物名，请用 --artifact-name 指定");
                return EX_USAGE;
            }
        }

        // 检索变更
        List<String> changed;
        try {
            changed = resolveChanges(mode, parsed, projectDir);
        } catch (IllegalArgumentException e) {
            System.err.println("[patch-local] " + e.getMessage());
            return EX_USAGE;
        } catch (IOException e) {
            System.err.println("[patch-local] 检索变更失败: " + e.getMessage());
            return EX_SOFTWARE;
        }

        if (mode != DeployMode.FULL && changed.isEmpty()) {
            System.err.println("[patch-local] 未检测到变更文件");
            return EX_USAGE;
        }

        // 编译
        try {
            int exit = new MavenRunner(projectDir, parsed.javaHome, parsed.mavenHome, System.err::println)
                    .cleanPackage();
            if (exit != 0) {
                System.err.println("[patch-local] mvn 编译失败，退出码 " + exit);
                return EX_SOFTWARE;
            }
        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.println("[patch-local] mvn 编译异常: " + e.getMessage());
            return EX_SOFTWARE;
        }

        // 决定输出目录
        Path outputDir = parsed.outputDir != null
                ? Paths.get(parsed.outputDir).toAbsolutePath().normalize()
                : LocalPackagePatchService.suggestOutputDir(localPackage.toString());

        // 执行补丁
        LocalPackagePatchService.LocalPatchResult result = LocalPackagePatchService.execute(
                mode,
                projectDir.toString(),
                artifactName,
                changed,
                localPackage.toString(),
                outputDir.toString(),
                System.out::println);

        if (!result.isSuccess()) {
            System.err.println("[patch-local] 失败: " + result.getErrorMessage());
            return EX_SOFTWARE;
        }

        System.out.println("=== 补丁完成 ===");
        System.out.println("输出包: " + result.getOutputPackage());
        System.out.println("变更条目: " + result.getChangedCount());
        return 0;
    }

    // -------------------------------------------------------------------------
    // 辅助
    // -------------------------------------------------------------------------

    /**
     * 将模式字符串解析为 {@link DeployMode} 枚举值
     *
     * @param mode 模式字符串（full / incremental / auto），大小写不敏感；null 或空视为 full
     * @return 对应枚举值；无法识别时返回 null
     * @author xumanyi
     * @date 2026-03-21
     */
    private static DeployMode parseMode(String mode) {
        if (mode == null || mode.isBlank() || "full".equalsIgnoreCase(mode)) return DeployMode.FULL;
        if ("incremental".equalsIgnoreCase(mode)) return DeployMode.INCREMENTAL;
        if ("auto".equalsIgnoreCase(mode)) return DeployMode.AUTO_DETECT;
        return null;
    }

    /**
     * 按模式解析变更文件列表：full 返回空列表，incremental 使用 --files，auto 通过 git status 获取
     *
     * @param mode       部署模式
     * @param parsed     解析后的命令行参数
     * @param projectDir 项目目录（用于 auto 模式向上查找 .git）
     * @return 变更文件路径列表（相对 projectDir）
     * @throws IOException               auto 模式读取 git status 失败
     * @throws IllegalArgumentException  incremental 模式未提供 --files，或 auto 模式未找到 .git
     * @author xumanyi
     * @date 2026-03-21
     */
    private static List<String> resolveChanges(DeployMode mode, Args parsed, Path projectDir)
            throws IOException {
        if (mode == DeployMode.FULL) {
            return new ArrayList<>();
        }
        if (parsed.files != null && !parsed.files.isEmpty()) {
            return parsed.files;
        }
        if (mode == DeployMode.INCREMENTAL) {
            throw new IllegalArgumentException("mode=incremental 需要 --files");
        }
        // auto: git status
        Path gitRoot = parsed.gitRoot != null
                ? Paths.get(parsed.gitRoot).toAbsolutePath().normalize()
                : findGitRoot(projectDir);
        if (gitRoot == null) {
            throw new IllegalArgumentException("auto 模式未找到 .git（projectDir 及其父级）");
        }
        List<GitStatusHelper.ChangedFile> changes = GitStatusHelper.statusRelativeTo(gitRoot, projectDir);
        List<String> out = new ArrayList<>();
        for (GitStatusHelper.ChangedFile cf : changes) {
            out.add(cf.status + " " + cf.path);
        }
        return out;
    }

    /**
     * 从指定目录向上查找 {@code .git} 目录，最多向上 8 层
     *
     * @param from 起始目录
     * @return 包含 {@code .git} 的目录；未找到时返回 null
     * @author xumanyi
     * @date 2026-03-21
     */
    private static Path findGitRoot(Path from) {
        Path cur = from;
        for (int i = 0; i < 8 && cur != null; i++) {
            if (Files.isDirectory(cur.resolve(".git"))) return cur;
            cur = cur.getParent();
        }
        return null;
    }

    /**
     * 从 pom.xml 推断产物文件名：优先 {@code <finalName>}，其次 {@code <artifactId>-<version>.<packaging>}。
     * 推断失败返回 null，由调用方报错提示用户显式 {@code --artifact-name}。
     *
     * @param projectDir 包含 pom.xml 的模块根目录
     * @return 推断出的产物文件名（含扩展名）；无法推断时返回 null
     * @author xumanyi
     * @date 2026-03-21
     */
    private static String inferArtifactName(Path projectDir) {
        try {
            String pom = Files.readString(projectDir.resolve("pom.xml"));
            String finalName = matchTag(pom, "finalName");
            String packaging = matchTag(pom, "packaging");
            if (packaging == null || packaging.isBlank()) packaging = "jar";
            if (finalName != null && !finalName.isBlank()) {
                return finalName + "." + packaging;
            }
            String artifactId = matchTag(pom, "artifactId");
            String version = matchTag(pom, "version");
            if (artifactId != null && version != null) {
                return artifactId + "-" + version + "." + packaging;
            }
        } catch (IOException ignored) {
        }
        return null;
    }

    /**
     * 从 XML 字符串中提取指定标签的文本内容
     *
     * @param xml XML 字符串
     * @param tag 标签名（不含尖括号）
     * @return 标签内文本（已 trim）；标签不存在时返回 null
     * @author xumanyi
     * @date 2026-03-21
     */
    private static String matchTag(String xml, String tag) {
        int open = xml.indexOf("<" + tag + ">");
        if (open < 0) return null;
        int close = xml.indexOf("</" + tag + ">", open);
        if (close < 0) return null;
        return xml.substring(open + tag.length() + 2, close).trim();
    }

    /**
     * 打印 usage 到指定流
     *
     * @param out 输出流
     * @author xumanyi
     * @date 2026-03-21
     */
    private static void printUsage(java.io.PrintStream out) {
        out.println("patch-local - 对本地 jar/war 打补丁，输出新包；不涉及 FTP");
        out.println();
        out.println("Usage:");
        out.println("  java -jar flux-deploy-cli.jar patch-local [options]");
        out.println();
        out.println("Options:");
        out.println("  --project-dir <path>       (必填) 源工程模块根（含 pom.xml）");
        out.println("  --local-package <path>     (必填) 用户提供的本地 jar/war 路径");
        out.println("  --mode <mode>              full|incremental|auto，默认 full");
        out.println("  --artifact-name <name>     源工程产物文件名；省略则从 pom.xml 推断");
        out.println("  --files <f1,f2,...>        incremental 模式必填；相对 projectDir 的变更文件");
        out.println("  --git-root <path>          auto 模式可选；默认向上查找 .git");
        out.println("  --output-dir <path>        可选；默认 <包所在目录>/patched_<时间戳>/");
        out.println("  --maven-home <path>        可选；覆盖 MAVEN_HOME");
        out.println("  --java-home <path>         可选；覆盖 JAVA_HOME");
    }

    // -------------------------------------------------------------------------
    // Args
    // -------------------------------------------------------------------------

    private static final class Args {
        String projectDir;
        String localPackage;
        String mode;
        String artifactName;
        List<String> files;
        String gitRoot;
        String outputDir;
        String mavenHome;
        String javaHome;

        /**
         * 解析命令行参数
         *
         * @param args 命令行参数数组
         * @return 解析后的参数对象
         * @author xumanyi
         * @date 2026-03-21
         */
        static Args parse(String[] args) {
            Args a = new Args();
            for (int i = 0; i < args.length; i++) {
                String flag = args[i];
                switch (flag) {
                    case "--project-dir":    a.projectDir    = require(args, ++i, flag); break;
                    case "--local-package":  a.localPackage  = require(args, ++i, flag); break;
                    case "--mode":           a.mode          = require(args, ++i, flag); break;
                    case "--artifact-name":  a.artifactName  = require(args, ++i, flag); break;
                    case "--files":
                        String list = require(args, ++i, flag);
                        List<String> files = new ArrayList<>();
                        for (String s : list.split(",")) {
                            String t = s.trim();
                            if (!t.isEmpty()) files.add(t);
                        }
                        a.files = files;
                        break;
                    case "--git-root":       a.gitRoot    = require(args, ++i, flag); break;
                    case "--output-dir":     a.outputDir  = require(args, ++i, flag); break;
                    case "--maven-home":     a.mavenHome  = require(args, ++i, flag); break;
                    case "--java-home":      a.javaHome   = require(args, ++i, flag); break;
                    case "-h":
                    case "--help":
                        break;
                    default:
                        throw new IllegalArgumentException("未知参数: " + flag);
                }
            }
            return a;
        }

        /**
         * 读取 flag 的值；缺失时抛异常
         *
         * @param args 参数数组
         * @param idx  当前读取位置
         * @param flag flag 名称（用于异常信息）
         * @return flag 对应的值字符串
         * @author xumanyi
         * @date 2026-03-21
         */
        private static String require(String[] args, int idx, String flag) {
            if (idx >= args.length) {
                throw new IllegalArgumentException("flag " + flag + " 需要值");
            }
            return args[idx];
        }
    }
}
