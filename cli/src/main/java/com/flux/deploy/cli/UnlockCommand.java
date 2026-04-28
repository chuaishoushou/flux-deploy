package com.flux.deploy.cli;

import com.flux.deploy.ftp.FtpLock;
import com.flux.deploy.ftp.FtpOperations;
import com.flux.deploy.ftp.FtpSession;
import com.flux.deploy.util.CredentialCache;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * unlock 子命令：扫描 {@code remoteDir} 下所有残留锁文件（{@code *__LOCK__*}）并在安全前提下恢复原名。
 *
 * <p>恢复规则：</p>
 * <ul>
 *   <li>锁文件同目录下**不存在**同名的活跃包 → rename 回原名（恢复）</li>
 *   <li>锁文件同目录下**已存在**活跃包 → 列出冲突，跳过（需用户决定手工删锁或手工替换）</li>
 * </ul>
 *
 * @author xumanyi
 * @date 2026-03-21
 */
final class UnlockCommand {

    private static final int EX_USAGE = 64;
    private static final int EX_SOFTWARE = 70;

    private UnlockCommand() {
    }

    /**
     * 执行 unlock 子命令
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
            System.err.println("[unlock] " + e.getMessage());
            printUsage(System.err);
            return EX_USAGE;
        }
        if (parsed.host == null || parsed.username == null || parsed.remoteDir == null) {
            System.err.println("[unlock] --host / --user / --remote-dir 必填");
            printUsage(System.err);
            return EX_USAGE;
        }

        // 查凭据缓存
        String password;
        try {
            CredentialCache.CachedCredential cached =
                    CredentialCache.lookup(parsed.host, parsed.port, parsed.username);
            if (cached == null || cached.getPassword() == null || cached.getPassword().isBlank()) {
                System.err.println("[unlock] 未找到匹配凭据，请先运行 `credential set`");
                return EX_USAGE;
            }
            password = cached.getPassword();
        } catch (RuntimeException e) {
            System.err.println("[unlock] 凭据解密失败: " + e.getMessage());
            return EX_USAGE;
        }

        // 连接 FTP 并扫描
        try (FtpSession session = new FtpSession(parsed.host, parsed.port)) {
            session.connect(parsed.username, password);
            session.changeWorkingDirectory(parsed.remoteDir);

            FtpOperations ops = new FtpOperations(session);
            FtpLock lock = new FtpLock(ops);

            List<String> lockPaths = ops.scanLockFiles(parsed.remoteDir);
            if (lockPaths.isEmpty()) {
                System.out.println("(无残留锁)");
                return 0;
            }

            List<String> restored = new ArrayList<>();
            List<String> conflicts = new ArrayList<>();
            List<String> errors = new ArrayList<>();

            for (String lockPath : lockPaths) {
                int lastSlash = lockPath.lastIndexOf('/');
                String dir = lockPath.substring(0, lastSlash + 1);
                String lockName = lockPath.substring(lastSlash + 1);
                String originalName = FtpLock.extractOriginalName(lockName);
                if (originalName == null) {
                    errors.add(lockPath + " -> 无法解析原始名");
                    continue;
                }
                String originalPath = dir + originalName;

                try {
                    if (ops.exists(originalPath)) {
                        if (parsed.deleteOrphans) {
                            lock.releaseLock(dir, lockName);
                            restored.add(lockPath + " (已删除孤立锁，活跃包保留)");
                        } else {
                            conflicts.add(lockPath + " (同目录已有活跃包 " + originalName + ")");
                        }
                    } else {
                        lock.restoreLock(dir, lockName);
                        restored.add(lockPath + " -> " + originalPath);
                    }
                } catch (IOException e) {
                    errors.add(lockPath + " -> " + e.getMessage());
                }
            }

            System.out.println("=== 恢复完成 ===");
            System.out.println("已恢复: " + restored.size());
            for (String s : restored) {
                System.out.println("  [OK] " + s);
            }
            if (!conflicts.isEmpty()) {
                System.out.println("\n冲突跳过: " + conflicts.size() + "（请手动决定）");
                for (String s : conflicts) {
                    System.out.println("  [SKIP] " + s);
                }
            }
            if (!errors.isEmpty()) {
                System.out.println("\n错误: " + errors.size());
                for (String s : errors) {
                    System.out.println("  [ERR] " + s);
                }
                return EX_SOFTWARE;
            }
            return 0;

        } catch (IOException e) {
            System.err.println("[unlock] FTP 错误: " + e.getMessage());
            return EX_SOFTWARE;
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
        out.println("unlock - 扫描并恢复残留锁文件");
        out.println();
        out.println("Usage:");
        out.println("  java -jar flux-deploy-cli.jar unlock [options]");
        out.println();
        out.println("Options:");
        out.println("  --host <host>          (必填) FTP 主机");
        out.println("  --port <port>          (可选) FTP 端口，默认 18080");
        out.println("  --user <username>      (必填) 登录用户名");
        out.println("  --remote-dir <path>    (必填) 扫描起始目录，例 /开发/.../TMS V9.0-P7/");
        out.println("  --delete-orphans       (可选) 同目录已有活跃包的孤立锁直接删除");
    }

    private static final class Args {
        String host;
        int port = 18080;
        String username;
        String remoteDir;
        boolean deleteOrphans;

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
                    case "--delete-orphans":
                        a.deleteOrphans = true;
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
