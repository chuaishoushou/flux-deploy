package com.flux.deploy.cli;

import com.flux.deploy.util.CredentialCache;

import java.io.BufferedReader;
import java.io.Console;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * credential 子命令：用户在终端管理本机凭据缓存（{@code ~/.flux-deploy/credentials.toml}）。
 *
 * <p>操作：</p>
 * <ul>
 *   <li>{@code credential set}    - 交互式输入密码并保存（密码隐藏，不进 shell history）</li>
 *   <li>{@code credential list}   - 列出已缓存凭据（不显示密码）</li>
 *   <li>{@code credential delete} - 按 host/port/user 删除条目</li>
 * </ul>
 *
 * <p>密码全程只在用户终端与 CLI 之间流动，不经过任何上游调用方（如 AI Skill）。</p>
 *
 * @author xumanyi
 * @date 2026-03-21
 */
final class CredentialCommand {

    private static final int EX_USAGE = 64;
    private static final int EX_SOFTWARE = 70;

    private CredentialCommand() {
    }

    /**
     * 执行 credential 子命令，分发到 set / list / delete 子操作
     *
     * @param args 去掉子命令后的参数
     * @return 退出码
     * @author xumanyi
     * @date 2026-03-21
     */
    static int run(String[] args) {
        if (args.length == 0) {
            printUsage(System.err);
            return EX_USAGE;
        }
        String sub = args[0];
        String[] rest = new String[args.length - 1];
        System.arraycopy(args, 1, rest, 0, rest.length);

        switch (sub) {
            case "set":
                return runSet(rest);
            case "list":
                return runList();
            case "delete":
                return runDelete(rest);
            case "-h":
            case "--help":
                printUsage(System.out);
                return 0;
            default:
                System.err.println("[credential] 未知子操作: " + sub);
                printUsage(System.err);
                return EX_USAGE;
        }
    }

    // -------------------------------------------------------------------------
    // set
    // -------------------------------------------------------------------------

    /**
     * 执行 credential set：交互式读取密码并保存到凭据缓存
     *
     * @param args set 子命令参数
     * @return 退出码
     * @author xumanyi
     * @date 2026-03-21
     */
    private static int runSet(String[] args) {
        Args parsed;
        try {
            parsed = Args.parse(args);
        } catch (IllegalArgumentException e) {
            System.err.println("[credential set] " + e.getMessage());
            printUsage(System.err);
            return EX_USAGE;
        }
        if (parsed.host == null || parsed.username == null) {
            System.err.println("[credential set] --host 和 --user 必填");
            printUsage(System.err);
            return EX_USAGE;
        }

        String password = readPassword();
        if (password == null || password.isEmpty()) {
            System.err.println("[credential set] 密码为空，已取消");
            return EX_USAGE;
        }

        try {
            CredentialCache.saveOrUpdate(parsed.host, parsed.port, parsed.username,
                    password, parsed.workingDir, parsed.protocol);
        } catch (RuntimeException e) {
            System.err.println("[credential set] 保存失败: " + e.getMessage());
            return EX_SOFTWARE;
        }

        System.out.println("[credential set] 已保存：" + parsed.protocol.toLowerCase()
                + "://" + parsed.username + "@" + parsed.host + ":" + parsed.port + "/");
        return 0;
    }

    /**
     * 从终端读取密码（不回显）。无 Console 时退化为从 stdin 读取一行
     *
     * @return 读取到的密码；用户取消或读取失败时返回 null
     * @author xumanyi
     * @date 2026-03-21
     */
    private static String readPassword() {
        Console console = System.console();
        if (console != null) {
            char[] chars = console.readPassword("Enter password: ");
            if (chars == null) {
                return null;
            }
            return new String(chars);
        }
        // 无 TTY（例如在 IDE/脚本中）：退化为明文 stdin，提示用户。
        System.err.println("[credential set] 未检测到 TTY，将从 stdin 读取密码（明文，不推荐）");
        try (BufferedReader br = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            return br.readLine();
        } catch (IOException e) {
            return null;
        }
    }

    // -------------------------------------------------------------------------
    // list
    // -------------------------------------------------------------------------

    /**
     * 执行 credential list：列出所有已缓存凭据（不显示密码）
     *
     * @return 退出码
     * @author xumanyi
     * @date 2026-03-21
     */
    private static int runList() {
        List<CredentialCache.CachedCredential> all;
        try {
            all = CredentialCache.loadAllDecrypted();
        } catch (RuntimeException e) {
            // 整体解密失败（例如旧密钥）；仍输出错误但不中断
            System.err.println("[credential list] 警告: 密码解密失败（可能是旧密钥加密）: " + e.getMessage());
            return EX_SOFTWARE;
        }
        if (all.isEmpty()) {
            System.out.println("(无缓存凭据)");
            return 0;
        }
        for (CredentialCache.CachedCredential c : all) {
            System.out.println(c.getFtpEndpoint()
                    + "  verified_at=" + c.getVerifiedAt()
                    + "  last_working_dir=" + (c.getLastWorkingDir() == null ? "" : c.getLastWorkingDir()));
        }
        return 0;
    }

    // -------------------------------------------------------------------------
    // delete
    // -------------------------------------------------------------------------

    /**
     * 执行 credential delete：按 host/port/user 删除指定凭据条目
     *
     * @param args delete 子命令参数
     * @return 退出码
     * @author xumanyi
     * @date 2026-03-21
     */
    private static int runDelete(String[] args) {
        Args parsed;
        try {
            parsed = Args.parse(args);
        } catch (IllegalArgumentException e) {
            System.err.println("[credential delete] " + e.getMessage());
            printUsage(System.err);
            return EX_USAGE;
        }
        if (parsed.host == null || parsed.username == null) {
            System.err.println("[credential delete] --host 和 --user 必填");
            printUsage(System.err);
            return EX_USAGE;
        }
        boolean removed = CredentialCache.delete(parsed.host, parsed.port, parsed.username);
        if (removed) {
            System.out.println("[credential delete] 已删除: " + parsed.username + "@" + parsed.host + ":" + parsed.port);
            return 0;
        }
        System.err.println("[credential delete] 未找到匹配条目");
        return EX_USAGE;
    }

    // -------------------------------------------------------------------------
    // usage
    // -------------------------------------------------------------------------

    /**
     * 打印 usage 到指定流
     *
     * @param out 输出流
     * @author xumanyi
     * @date 2026-03-21
     */
    private static void printUsage(java.io.PrintStream out) {
        out.println("credential - 管理本机凭据缓存 (~/.flux-deploy/credentials.toml)");
        out.println();
        out.println("Usage:");
        out.println("  java -jar flux-deploy-cli.jar credential <set|list|delete> [options]");
        out.println();
        out.println("Options (set / delete):");
        out.println("  --host <host>              (必填) FTP 主机");
        out.println("  --port <port>              (可选) FTP 端口，默认 18080");
        out.println("  --user <username>          (必填) 登录用户名");
        out.println("  --protocol <FTP|SFTP>      (可选) 协议，默认 FTP");
        out.println("  --working-dir <path>       (可选, set) 上次成功的远程工作目录，仅元信息");
        out.println();
        out.println("Password input (set):");
        out.println("  密码通过终端隐式读取（不回显、不进 shell history、不经过任何上游进程）");
    }

    // -------------------------------------------------------------------------
    // Args
    // -------------------------------------------------------------------------

    private static final class Args {
        String host;
        int port = 18080;
        String username;
        String protocol = "FTP";
        String workingDir;

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
                        a.host = requireValue(args, ++i, flag);
                        break;
                    case "--port":
                        a.port = Integer.parseInt(requireValue(args, ++i, flag));
                        break;
                    case "--user":
                    case "--username":
                        a.username = requireValue(args, ++i, flag);
                        break;
                    case "--protocol":
                        a.protocol = requireValue(args, ++i, flag).toUpperCase();
                        break;
                    case "--working-dir":
                        a.workingDir = requireValue(args, ++i, flag);
                        break;
                    case "-h":
                    case "--help":
                        // 交给外层处理
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
        private static String requireValue(String[] args, int idx, String flag) {
            if (idx >= args.length) {
                throw new IllegalArgumentException("flag " + flag + " 需要值");
            }
            return args[idx];
        }
    }
}
