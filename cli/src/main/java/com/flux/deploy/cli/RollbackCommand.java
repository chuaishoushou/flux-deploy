package com.flux.deploy.cli;

import com.flux.deploy.deploy.DeployPipeline;
import com.flux.deploy.ftp.FtpOperations;
import com.flux.deploy.ftp.FtpSession;
import com.flux.deploy.model.DeployConfig;
import com.flux.deploy.model.DeployResult;
import com.flux.deploy.util.CredentialCache;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSerializer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * rollback 子命令：从指定备份目录恢复 FTP 上的包。
 *
 * <p>流程：</p>
 * <ol>
 *   <li>解析系统根（remoteDir 的前 3 级）、拼出备份目录 {@code <systemRoot>/backup/<backupDirName>/}</li>
 *   <li>连接 FTP，递归扫描备份目录下所有文件</li>
 *   <li>dry-run 时仅打印 "备份路径 → 恢复目标路径" 映射，不做任何远程变更</li>
 *   <li>实际执行时：下载所有备份文件到本地临时目录，构造 DeployConfig 复用 DeployPipeline 完成恢复</li>
 *   <li>DeployPipeline 的门禁保持启用：预检→（可选）对当前状态做安全备份→加锁→上传备份内容→校验→解锁</li>
 * </ol>
 *
 * <p>默认会对当前状态再做一次备份，目录名 {@code <YYYYMMDD>_rollback_<HHmmss>}，
 * 方便用户在回滚后再次"前滚"到回滚前状态。可用 {@code --no-safety-backup} 跳过。</p>
 *
 * @author xumanyi
 * @date 2026-03-21
 */
final class RollbackCommand {

    private static final int EX_USAGE = 64;
    private static final int EX_SOFTWARE = 70;
    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("HHmmss");
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    private RollbackCommand() {
    }

    /**
     * 执行 rollback 子命令
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
            System.err.println("[rollback] " + e.getMessage());
            printUsage(System.err);
            return EX_USAGE;
        }
        if (parsed.host == null || parsed.username == null
                || parsed.remoteDir == null || parsed.backupDirName == null) {
            System.err.println("[rollback] --host / --user / --remote-dir / --backup-dir 必填");
            printUsage(System.err);
            return EX_USAGE;
        }

        String password = lookupPassword(parsed.host, parsed.port, parsed.username);
        if (password == null) {
            return EX_USAGE;
        }

        String systemRoot = resolveSystemRoot(parsed.remoteDir);
        String backupDir = systemRoot + "backup/" + parsed.backupDirName + "/";

        List<String> backupFiles;
        List<Path> tempFiles = new ArrayList<>();
        try (FtpSession session = new FtpSession(parsed.host, parsed.port)) {
            session.connect(parsed.username, password);
            session.changeWorkingDirectory(parsed.remoteDir);

            FtpOperations ops = new FtpOperations(session);

            List<String> allBackupFiles = ops.scanAllFiles(backupDir);
            if (allBackupFiles.isEmpty()) {
                System.err.println("[rollback] 备份目录为空或不存在: " + backupDir);
                return EX_USAGE;
            }

            // 校验：只保留生产上存在同路径目标的备份；目标不存在时跳过并警告
            backupFiles = new ArrayList<>();
            List<String> skipped = new ArrayList<>();
            for (String backupPath : allBackupFiles) {
                String rel = backupPath.substring(backupDir.length());
                String targetPath = systemRoot + rel;
                if (ops.exists(targetPath)) {
                    backupFiles.add(backupPath);
                } else {
                    skipped.add(backupPath + " (目标 " + targetPath + " 不存在)");
                }
            }

            System.out.println("=== 扫描结果 ===");
            System.out.println("  备份目录下文件总数: " + allBackupFiles.size());
            System.out.println("  可恢复: " + backupFiles.size());
            for (String p : backupFiles) {
                String rel = p.substring(backupDir.length());
                System.out.println("    [OK] " + p + " → " + systemRoot + rel);
            }
            if (!skipped.isEmpty()) {
                System.out.println("  跳过: " + skipped.size());
                for (String s : skipped) {
                    System.out.println("    [SKIP] " + s);
                }
            }
            if (backupFiles.isEmpty()) {
                System.err.println("\n[rollback] 无可恢复目标");
                return EX_USAGE;
            }

            if (parsed.dryRun) {
                System.out.println("\n[DRY-RUN] 以上映射仅预览，未做任何修改");
                return 0;
            }

            System.out.println("\n=== 下载备份文件到本地 temp ===");
            for (String backupPath : backupFiles) {
                String rel = backupPath.substring(backupDir.length());
                String fileName = rel.substring(rel.lastIndexOf('/') + 1);
                Path temp = Files.createTempFile("rollback-", "-" + fileName);
                ops.download(backupPath, temp);
                tempFiles.add(temp);
            }
            System.out.println("  已下载 " + tempFiles.size() + " 个文件");
        } catch (IOException e) {
            cleanupTemp(tempFiles);
            System.err.println("[rollback] FTP 操作失败: " + e.getMessage());
            return EX_SOFTWARE;
        }

        try {
            DeployConfig cfg = buildRollbackConfig(parsed, password, systemRoot, backupDir, backupFiles, tempFiles);
            DeployResult result = new DeployPipeline(cfg).execute();
            printResult(result);
            return isSuccess(result) ? 0 : EX_SOFTWARE;
        } catch (RuntimeException e) {
            System.err.println("[rollback] 流水线异常: " + e.getMessage());
            return EX_SOFTWARE;
        } finally {
            cleanupTemp(tempFiles);
        }
    }

    // -------------------------------------------------------------------------
    // 辅助
    // -------------------------------------------------------------------------

    /**
     * 从本机凭据缓存中查找密码
     *
     * @param host     FTP 主机
     * @param port     FTP 端口
     * @param username 用户名
     * @return 密码明文；未找到时打印错误并返回 null
     * @author xumanyi
     * @date 2026-03-21
     */
    private static String lookupPassword(String host, int port, String username) {
        try {
            CredentialCache.CachedCredential cached = CredentialCache.lookup(host, port, username);
            if (cached != null && cached.getPassword() != null && !cached.getPassword().isBlank()) {
                return cached.getPassword();
            }
        } catch (RuntimeException e) {
            System.err.println("[rollback] 凭据解密失败: " + e.getMessage());
            return null;
        }
        System.err.println("[rollback] 未找到匹配凭据，请先运行 `credential set`");
        return null;
    }

    /**
     * 从 remoteDir 解析系统根目录（前 3 级路径）
     *
     * @param remoteDir FTP 工作目录路径
     * @return 系统根目录路径（以 / 结尾）
     * @author xumanyi
     * @date 2026-03-21
     */
    private static String resolveSystemRoot(String remoteDir) {
        String trimmed = remoteDir.replaceAll("^/+", "").replaceAll("/+$", "");
        String[] parts = trimmed.split("/");
        if (parts.length >= 3) {
            return "/" + parts[0] + "/" + parts[1] + "/" + parts[2] + "/";
        }
        return "/" + trimmed + "/";
    }

    /**
     * 构造回滚用的 DeployConfig
     *
     * @param parsed      解析后的命令行参数
     * @param password    FTP 密码
     * @param systemRoot  系统根目录路径
     * @param backupDir   备份目录路径
     * @param backupFiles 备份目录下所有可恢复文件的远程路径
     * @param tempFiles   已下载到本地的临时文件列表（与 backupFiles 一一对应）
     * @return 构造完成的 {@link DeployConfig}
     * @author xumanyi
     * @date 2026-03-21
     */
    private static DeployConfig buildRollbackConfig(Args parsed, String password,
                                                    String systemRoot, String backupDir,
                                                    List<String> backupFiles, List<Path> tempFiles) {
        DeployConfig cfg = new DeployConfig();
        cfg.setHost(parsed.host);
        cfg.setPort(parsed.port);
        cfg.setUsername(parsed.username);
        cfg.setPassword(password);
        cfg.setRemoteDir(systemRoot);

        List<Path> localFiles = new ArrayList<>(tempFiles);
        List<String> names = new ArrayList<>();
        List<String> relatives = new ArrayList<>();
        for (String backupPath : backupFiles) {
            String rel = backupPath.substring(backupDir.length());
            String fileName = rel.substring(rel.lastIndexOf('/') + 1);
            names.add(fileName);
            relatives.add(rel);
        }
        cfg.setLocalFiles(localFiles);
        cfg.setTargetNames(names);
        cfg.setTargetRelativePaths(relatives);

        cfg.setSkipLock(false);
        cfg.setSkipBackup(parsed.noSafetyBackup);
        cfg.setSkipNote(true); // rollback 不写版本记录

        String operator = parsed.operator != null ? parsed.operator : "rollback";
        cfg.setOperator(operator);

        // 安全备份目录名：避免与被回滚的备份同名
        if (!parsed.noSafetyBackup) {
            String safetyDirName = LocalDate.now().format(DATE_FMT) + "_rollback_"
                    + LocalDateTime.now().format(TS_FMT);
            cfg.setBackupDirName(safetyDirName);
        }

        cfg.setOutputFormat("json");
        return cfg;
    }

    /**
     * 判断部署结果是否成功
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
     * 将部署结果以 JSON 格式输出到 stdout
     *
     * @param result 部署结果
     * @author xumanyi
     * @date 2026-03-21
     */
    private static void printResult(DeployResult result) {
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

    /**
     * 清理本地临时文件
     *
     * @param files 需要删除的临时文件列表
     * @author xumanyi
     * @date 2026-03-21
     */
    private static void cleanupTemp(List<Path> files) {
        for (Path p : files) {
            try {
                Files.deleteIfExists(p);
            } catch (IOException ignored) {
                // 清理失败不影响主流程
            }
        }
    }

    /**
     * 打印 usage 到指定流
     *
     * @param out 输出流
     * @author xumanyi
     * @date 2026-03-21
     */
    private static void printUsage(java.io.PrintStream out) {
        out.println("rollback - 从指定备份目录恢复 FTP 上的包");
        out.println();
        out.println("Usage:");
        out.println("  java -jar flux-deploy-cli.jar rollback [options]");
        out.println();
        out.println("Options:");
        out.println("  --host <host>              (必填) FTP 主机");
        out.println("  --port <port>              (可选) FTP 端口，默认 18080");
        out.println("  --user <username>          (必填) 登录用户名");
        out.println("  --remote-dir <path>        (必填) FTP 工作目录（前 3 级将作为系统根，用于定位 backup/）");
        out.println("  --backup-dir <name>        (必填) 备份子目录名，例 20260424_xumanyi");
        out.println("  --operator <id>            (可选) 执行回滚的操作人 id，默认 rollback");
        out.println("  --no-safety-backup         (可选) 跳过对当前状态的安全备份（不推荐）");
        out.println("  --dry-run                  (可选) 仅打印备份 → 目标的映射，不做任何修改");
        out.println();
        out.println("行为:");
        out.println("  默认会先把当前状态备份到 backup/<YYYYMMDD>_rollback_<HHmmss>/，再用指定备份覆盖。");
        out.println("  全部门禁保持启用（加锁/上传/校验/解锁），任一失败触发自动回滚到回滚前。");
    }

    private static final class Args {
        String host;
        int port = 18080;
        String username;
        String remoteDir;
        String backupDirName;
        String operator;
        boolean noSafetyBackup;
        boolean dryRun;

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
                    case "--host":
                        a.host = require(args, ++i, flag);
                        break;
                    case "--port":
                        a.port = Integer.parseInt(require(args, ++i, flag));
                        break;
                    case "--user":
                    case "--username":
                        a.username = require(args, ++i, flag);
                        break;
                    case "--remote-dir":
                        a.remoteDir = require(args, ++i, flag);
                        break;
                    case "--backup-dir":
                        a.backupDirName = require(args, ++i, flag);
                        break;
                    case "--operator":
                        a.operator = require(args, ++i, flag);
                        break;
                    case "--no-safety-backup":
                        a.noSafetyBackup = true;
                        break;
                    case "--dry-run":
                        a.dryRun = true;
                        break;
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
