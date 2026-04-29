package com.flux.deploy.cli;

import com.flux.deploy.deploy.ResidualLockDiagnosis;
import com.flux.deploy.deploy.ResidualLockResolver;
import com.flux.deploy.ftp.FtpLock;
import com.flux.deploy.ftp.FtpOperations;
import com.flux.deploy.ftp.FtpSession;
import com.flux.deploy.util.CredentialCache;
import org.apache.commons.net.ftp.FTPFile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * unlock-resolve 子命令：以诊断+清理两阶段方式处理残留锁。
 *
 * <p>对比 unlock 命令：unlock-resolve 提供结构化诊断输出，并尊重 owner 与 --apply 控制。</p>
 *
 * @author xumanyi
 * @date 2026-04-28
 */
final class UnlockResolveCommand {

    private static final int EX_USAGE = 64;
    private static final int EX_SOFTWARE = 70;

    private UnlockResolveCommand() {}

    static int run(String[] args) {
        String host = null, username = null, remoteDir = null;
        int port = 18080;
        boolean apply = false, includeOthers = false;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--host": host = args[++i]; break;
                case "--port": port = Integer.parseInt(args[++i]); break;
                case "--user":
                case "--username": username = args[++i]; break;
                case "--remote-dir": remoteDir = args[++i]; break;
                case "--apply": apply = true; break;
                case "--include-others": includeOthers = true; break;
                case "-h":
                case "--help": printUsage(System.out); return 0;
                default:
                    System.err.println("[unlock-resolve] 未知参数: " + args[i]);
                    return EX_USAGE;
            }
        }
        if (host == null || username == null || remoteDir == null) {
            printUsage(System.err);
            return EX_USAGE;
        }

        CredentialCache.CachedCredential cred = CredentialCache.lookup(host, port, username);
        if (cred == null || cred.getPassword() == null) {
            System.err.println("[unlock-resolve] 未找到凭据，请先 credential set");
            return EX_USAGE;
        }

        try (FtpSession session = new FtpSession(host, port)) {
            session.connect(username, cred.getPassword());
            session.changeWorkingDirectory(remoteDir);
            FtpOperations ops = new FtpOperations(session);
            FtpLock lock = new FtpLock(ops);

            // 收集 remoteDir 下所有 *.war / *.jar 包名
            List<FTPFile> files = ops.listFiles(remoteDir);
            List<ResidualLockDiagnosis> all = new ArrayList<>();
            ResidualLockResolver resolver = new ResidualLockResolver(
                    ResidualLockResolver.wrap(ops, lock), username);
            for (FTPFile f : files) {
                String name = FtpSession.decodeRemotePath(f.getName());
                if (FtpLock.isLockFile(name)) {
                    String original = FtpLock.extractOriginalName(name);
                    if (original != null) {
                        all.addAll(resolver.diagnose(remoteDir, original));
                    }
                }
            }

            if (all.isEmpty()) {
                System.out.println("(无残留锁)");
                return 0;
            }

            System.out.println("发现 " + all.size() + " 个残留锁：");
            for (ResidualLockDiagnosis d : all) {
                System.out.println("  - " + d.getLockFileName());
                System.out.println("    owner: " + d.getOperator()
                        + (d.isOwnedByCurrentUser() ? "（你自己）" : "")
                        + "  时间: " + d.getLockedAt());
                System.out.println("    建议: " + d.getSuggestion() + " - " + d.getReason());
            }

            if (!apply) {
                System.out.println("\n(--apply 未指定，未做任何修改)");
                return 0;
            }

            int handled = 0, skipped = 0, failed = 0;
            for (ResidualLockDiagnosis d : all) {
                if (!d.isOwnedByCurrentUser() && !includeOthers) {
                    System.out.println("[SKIP] 他人的锁: " + d.getLockFileName() + "（用 --include-others 强行处理）");
                    skipped++;
                    continue;
                }
                if (d.getSuggestion() == ResidualLockDiagnosis.SuggestedAction.NEEDS_HUMAN) {
                    System.out.println("[SKIP] 需人工: " + d.getLockFileName());
                    skipped++;
                    continue;
                }
                try {
                    resolver.apply(d);
                    System.out.println("[OK] " + d.getSuggestion() + " " + d.getLockFileName());
                    handled++;
                } catch (IOException e) {
                    System.out.println("[ERR] " + d.getLockFileName() + " - " + e.getMessage());
                    failed++;
                }
            }
            System.out.println("\n=== 完成: 处理 " + handled + "  跳过 " + skipped + "  失败 " + failed + " ===");
            return failed == 0 ? 0 : EX_SOFTWARE;

        } catch (IOException e) {
            System.err.println("[unlock-resolve] FTP 错误: " + e.getMessage());
            return EX_SOFTWARE;
        }
    }

    private static void printUsage(java.io.PrintStream out) {
        out.println("unlock-resolve - 结构化残留锁诊断与清理（两阶段：列出 → --apply 执行）");
        out.println();
        out.println("Options:");
        out.println("  --host <host>       FTP 主机");
        out.println("  --port <port>       FTP 端口（默认 18080）");
        out.println("  --user <username>   登录用户");
        out.println("  --remote-dir <path> 扫描根目录");
        out.println("  --apply             执行清理（默认仅诊断）");
        out.println("  --include-others    也处理 owner != 当前用户的锁");
    }
}
