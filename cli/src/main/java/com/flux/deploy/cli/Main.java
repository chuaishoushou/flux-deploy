package com.flux.deploy.cli;

import com.flux.deploy.deploy.DeployPipeline;
import com.flux.deploy.model.DeployConfig;
import com.flux.deploy.model.DeployResult;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSerializer;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * FLUX Deploy 命令行入口。
 *
 * <p>子命令：</p>
 * <ul>
 *   <li>{@code deploy}  - 按 JSON 配置执行一次 FTP 部署流水线</li>
 *   <li>{@code schema}  - 输出 JSON Schema，供 AI Skill 加载以了解当前 CLI 能力</li>
 *   <li>{@code --help} / {@code -h} - 显示使用说明</li>
 * </ul>
 *
 * <p>退出码约定：</p>
 * <ul>
 *   <li>0  - 成功</li>
 *   <li>64 - 用法 / 配置错误 (EX_USAGE)</li>
 *   <li>70 - 执行期内部错误 (EX_SOFTWARE)</li>
 * </ul>
 *
 * @author xumanyi
 * @date 2026-03-21
 */
public final class Main {

    /** 用法错误退出码（BSD sysexits 约定）。 */
    private static final int EX_USAGE = 64;
    /** 内部错误退出码。 */
    private static final int EX_SOFTWARE = 70;

    private Main() {
    }

    /**
     * CLI 入口
     *
     * @param args 命令行参数
     * @author xumanyi
     * @date 2026-03-21
     */
    public static void main(String[] args) {
        if (args.length == 0) {
            printUsage(System.err);
            System.exit(EX_USAGE);
        }
        String cmd = args[0];
        if ("-h".equals(cmd) || "--help".equals(cmd) || "help".equals(cmd)) {
            printUsage(System.out);
            return;
        }
        if ("deploy".equals(cmd)) {
            System.exit(runDeploy(slice(args, 1)));
        }
        if ("schema".equals(cmd)) {
            System.exit(SchemaCommand.run());
        }
        if ("credential".equals(cmd)) {
            System.exit(CredentialCommand.run(slice(args, 1)));
        }
        if ("unlock".equals(cmd)) {
            System.exit(UnlockCommand.run(slice(args, 1)));
        }
        if ("rollback".equals(cmd)) {
            System.exit(RollbackCommand.run(slice(args, 1)));
        }
        if ("patch-local".equals(cmd)) {
            System.exit(PatchLocalCommand.run(slice(args, 1)));
        }
        if ("browse".equals(cmd)) {
            System.exit(BrowseCommand.run(slice(args, 1)));
        }

        System.err.println("[flux-deploy-cli] 未知子命令: " + cmd);
        printUsage(System.err);
        System.exit(EX_USAGE);
    }

    /**
     * 执行 deploy 子命令
     *
     * @param args 去掉子命令后的参数
     * @return 退出码
     * @author xumanyi
     * @date 2026-03-21
     */
    private static int runDeploy(String[] args) {
        Path configPath = null;
        Path passwordFile = null;
        boolean dryRun = false;
        boolean verbose = false;

        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            switch (a) {
                case "--config":
                    configPath = Paths.get(requireValue(args, ++i, "--config"));
                    break;
                case "--password-file":
                    passwordFile = Paths.get(requireValue(args, ++i, "--password-file"));
                    break;
                case "--dry-run":
                    dryRun = true;
                    break;
                case "--verbose":
                    verbose = true;
                    break;
                case "-h":
                case "--help":
                    printUsage(System.out);
                    return 0;
                default:
                    System.err.println("[flux-deploy-cli] 未知参数: " + a);
                    printUsage(System.err);
                    return EX_USAGE;
            }
        }

        if (configPath == null) {
            System.err.println("[flux-deploy-cli] 缺少 --config <path>");
            printUsage(System.err);
            return EX_USAGE;
        }

        DeployConfig cfg;
        try {
            cfg = ConfigLoader.load(configPath, passwordFile, dryRun);
        } catch (ConfigLoader.InvalidConfigException e) {
            System.err.println("[flux-deploy-cli] 配置错误: " + e.getMessage());
            return EX_USAGE;
        }

        if (verbose) {
            System.err.println("[flux-deploy-cli] host=" + cfg.getHost()
                    + " port=" + cfg.getPort()
                    + " user=" + cfg.getUsername()
                    + " remoteDir=" + cfg.getRemoteDir()
                    + " targets=" + cfg.getTargetNames()
                    + " dryRun=" + cfg.isDryRun());
        }

        DeployResult result;
        try {
            result = new DeployPipeline(cfg).execute();
        } catch (RuntimeException e) {
            System.err.println("[flux-deploy-cli] 流水线异常: " + e.getMessage());
            if (verbose) {
                e.printStackTrace(System.err);
            }
            return EX_SOFTWARE;
        }

        // 结果输出：JSON 到 stdout（方便 Skill 解析）
        printResult(result, cfg.getOutputFormat());

        return isSuccess(result) ? 0 : EX_SOFTWARE;
    }

    /**
     * 判断部署是否成功（结果无错误即为成功）
     *
     * @param result 部署结果
     * @return 结果非空且错误列表为空时返回 true
     * @author xumanyi
     * @date 2026-03-21
     */
    private static boolean isSuccess(DeployResult result) {
        return result != null && result.getErrors() != null && result.getErrors().isEmpty();
    }

    /**
     * 输出部署结果；默认 JSON，便于 Skill / 脚本消费
     *
     * @param result 部署结果
     * @param format 输出格式：json 或 text
     * @author xumanyi
     * @date 2026-03-21
     */
    private static void printResult(DeployResult result, String format) {
        if ("text".equalsIgnoreCase(format)) {
            System.out.println("success=" + isSuccess(result));
            System.out.println("targets=" + (result.getTargets() == null ? 0 : result.getTargets().size()));
            if (result.getErrors() != null) {
                for (DeployResult.ErrorInfo err : result.getErrors()) {
                    System.out.println("error gate=" + err.getGate()
                            + " target=" + err.getTarget()
                            + " message=" + err.getMessage());
                }
            }
        } else {
            Gson gson = new GsonBuilder()
                    .setPrettyPrinting()
                    .disableHtmlEscaping()
                    .registerTypeAdapter(LocalDateTime.class,
                            (JsonSerializer<LocalDateTime>) (src, typeOfSrc, ctx) -> new JsonPrimitive(src.toString()))
                    .registerTypeAdapter(LocalDate.class,
                            (JsonSerializer<LocalDate>) (src, typeOfSrc, ctx) -> new JsonPrimitive(src.toString()))
                    .create();
            System.out.println(gson.toJson(result));
        }
    }

    /**
     * 从参数数组中读取 flag 的值；缺失时抛异常
     *
     * @param args 参数数组
     * @param idx  当前读取位置
     * @param flag flag 名称（用于异常信息）
     * @return flag 对应的值字符串
     * @author xumanyi
     * @date 2026-03-21
     */
    private static String requireValue(String[] args, int idx, String flag) {
        if (idx >= args.length) {
            throw new IllegalArgumentException("flag " + flag + " 需要值");
        }
        return args[idx];
    }

    /**
     * 数组切片：复制 from 之后的所有元素
     *
     * @param arr  源数组
     * @param from 起始下标（包含）
     * @return 从 from 起的子数组
     * @author xumanyi
     * @date 2026-03-21
     */
    private static String[] slice(String[] arr, int from) {
        String[] out = new String[arr.length - from];
        System.arraycopy(arr, from, out, 0, out.length);
        return out;
    }

    /**
     * 打印 usage 到指定流
     *
     * @param out 输出流
     * @author xumanyi
     * @date 2026-03-21
     */
    private static void printUsage(java.io.PrintStream out) {
        out.println("flux-deploy-cli - FLUX 客服 FTP 部署命令行工具");
        out.println();
        out.println("Usage:");
        out.println("  java -jar flux-deploy-cli-all.jar <command> [options]");
        out.println();
        out.println("Commands:");
        out.println("  deploy    按 JSON 配置执行一次 FTP 部署流水线");
        out.println("  schema     输出 JSON Schema（供 AI Skill 加载以了解当前 CLI 能力）");
        out.println("  credential 管理本机凭据缓存（set / list / delete；密码只在终端与 CLI 之间流动）");
        out.println("  unlock     扫描并恢复残留锁文件（中断的部署留下的 *__LOCK__* 文件）");
        out.println("  rollback   从指定备份目录恢复 FTP 上的包");
        out.println("  patch-local 对本地 jar/war 打补丁（本地模式，不涉及 FTP）");
        out.println("  browse     列出 FTP 目录结构（子目录 / 可部署包）");
        out.println();
        out.println("Deploy options:");
        out.println("  --config <path>         (必填) JSON 配置文件路径");
        out.println("  --password-file <path>  (可选) 密码文件路径，覆盖 ftp.password");
        out.println("  --dry-run               (可选) 强制仅预检，不改动远程");
        out.println("  --verbose               (可选) 打印调试信息到 stderr");
        out.println("  -h, --help              显示帮助");
        out.println();
        out.println("Exit codes:");
        out.println("  0   成功");
        out.println("  64  用法 / 配置错误");
        out.println("  70  执行期内部错误（含远程校验失败、FTP 连接失败）");
        out.println();
        out.println("JSON 配置字段：运行 `schema` 子命令获取完整 JSON Schema。");
    }
}
