package com.flux.deploy.cli;

import com.flux.deploy.ftp.FtpOperations;
import com.flux.deploy.ftp.FtpSession;
import com.flux.deploy.util.CredentialCache;
import org.apache.commons.net.ftp.FTPFile;

import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * backup-prune 子命令：清理 backup/ 下保留期之外的备份目录。
 *
 * @author xumanyi
 * @date 2026-04-29
 */
final class BackupPruneCommand {

    private static final int EX_USAGE = 64;
    private static final int EX_SOFTWARE = 70;
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    private BackupPruneCommand() {}

    static int run(String[] args) {
        String host = null, username = null, remoteDir = null;
        int port = 18080, keepDays = 30;
        boolean apply = false;

        try {
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--host": host = requireValue(args, ++i, "--host"); break;
                    case "--port": port = Integer.parseInt(requireValue(args, ++i, "--port")); break;
                    case "--user":
                    case "--username": username = requireValue(args, ++i, args[i - 1]); break;
                    case "--remote-dir": remoteDir = requireValue(args, ++i, "--remote-dir"); break;
                    case "--keep-days": keepDays = Integer.parseInt(requireValue(args, ++i, "--keep-days")); break;
                    case "--apply": apply = true; break;
                    case "-h":
                    case "--help": printUsage(System.out); return 0;
                    default:
                        System.err.println("[backup-prune] 未知参数: " + args[i]);
                        return EX_USAGE;
                }
            }
        } catch (IllegalArgumentException e) {
            System.err.println("[backup-prune] 参数解析失败: " + e.getMessage());
            return EX_USAGE;
        }

        if (host == null || username == null || remoteDir == null) {
            printUsage(System.err);
            return EX_USAGE;
        }

        CredentialCache.CachedCredential cred = CredentialCache.lookup(host, port, username);
        if (cred == null || cred.getPassword() == null) {
            System.err.println("[backup-prune] 未找到凭据");
            return EX_USAGE;
        }

        try (FtpSession session = new FtpSession(host, port)) {
            session.connect(username, cred.getPassword());
            FtpOperations ops = new FtpOperations(session);

            String backupParent = remoteDir.endsWith("/") ? remoteDir + "backup/" : remoteDir + "/backup/";
            if (!ops.exists(backupParent)) {
                System.out.println("(无 backup/ 目录: " + backupParent + ")");
                return 0;
            }

            LocalDate threshold = LocalDate.now().minusDays(keepDays);
            List<String> toRemove = new ArrayList<>();
            for (FTPFile f : ops.listFiles(backupParent)) {
                if (!f.isDirectory()) continue;
                String name = FtpSession.decodeRemotePath(f.getName());
                if (".".equals(name) || "..".equals(name)) continue;
                String datePart = name.length() >= 8 ? name.substring(0, 8) : null;
                if (datePart == null) continue;
                LocalDate d;
                try { d = LocalDate.parse(datePart, DATE_FMT); } catch (Exception e) { continue; }
                if (d.isBefore(threshold)) {
                    toRemove.add(backupParent + name + "/");
                }
            }

            if (toRemove.isEmpty()) {
                System.out.println("(无超过 " + keepDays + " 天的备份目录)");
                return 0;
            }

            System.out.println("超过 " + keepDays + " 天的备份目录（共 " + toRemove.size() + " 个）：");
            for (String p : toRemove) System.out.println("  " + p);

            if (!apply) {
                System.out.println("\n(--apply 未指定，未做任何修改)");
                return 0;
            }

            int ok = 0, fail = 0;
            for (String p : toRemove) {
                try {
                    ops.removeDirRecursively(p);
                    System.out.println("[DEL] " + p);
                    ok++;
                } catch (IOException e) {
                    System.out.println("[ERR] " + p + " - " + e.getMessage());
                    fail++;
                }
            }
            System.out.println("\n=== 完成: 删除 " + ok + "  失败 " + fail + " ===");
            return fail == 0 ? 0 : EX_SOFTWARE;

        } catch (IOException e) {
            System.err.println("[backup-prune] FTP 错误: " + e.getMessage());
            return EX_SOFTWARE;
        }
    }

    private static String requireValue(String[] args, int idx, String flag) {
        if (idx >= args.length) {
            throw new IllegalArgumentException("flag " + flag + " 需要值");
        }
        return args[idx];
    }

    private static void printUsage(java.io.PrintStream out) {
        out.println("backup-prune - 清理 backup/ 下保留期外的备份");
        out.println();
        out.println("Options:");
        out.println("  --host / --port / --user / --remote-dir   FTP 与子系统根");
        out.println("  --keep-days N    保留 N 天内的备份（默认 30）");
        out.println("  --apply          执行删除（默认仅 dry-run 列出）");
    }
}
