package com.flux.deploy.cli;

import com.flux.deploy.plugin.service.FtpBrowseService;
import com.flux.deploy.util.CredentialCache;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * browse 子命令：列出 FTP 目录下的子目录或可部署包。
 *
 * <p>两个模式：</p>
 * <ul>
 *   <li>{@code --list-dirs <path>} — 列当前目录下的子目录名（对应插件的项目/系统下拉）</li>
 *   <li>{@code --scan-packages <path>} — 递归扫描 JAR/WAR 可部署包（对应插件的目标包面板）</li>
 * </ul>
 *
 * <p>输出 JSON 到 stdout，方便 AI/脚本消费。</p>
 *
 * @author xumanyi
 * @date 2026-03-21
 */
final class BrowseCommand {

    private static final int EX_USAGE = 64;
    private static final int EX_SOFTWARE = 70;

    private BrowseCommand() {
    }

    /**
     * 执行 browse 子命令
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
            System.err.println("[browse] " + e.getMessage());
            printUsage(System.err);
            return EX_USAGE;
        }
        if (parsed.host == null || parsed.username == null) {
            System.err.println("[browse] --host / --user 必填");
            printUsage(System.err);
            return EX_USAGE;
        }
        if (parsed.listDirs == null && parsed.scanPackages == null) {
            System.err.println("[browse] 必须提供 --list-dirs 或 --scan-packages");
            printUsage(System.err);
            return EX_USAGE;
        }

        String password = lookupPassword(parsed.host, parsed.port, parsed.username);
        if (password == null) return EX_USAGE;

        FtpBrowseService svc = null;
        try {
            svc = new FtpBrowseService(parsed.host, parsed.port, parsed.username, password);
            Map<String, Object> out = new LinkedHashMap<>();
            if (parsed.listDirs != null) {
                List<String> dirs = svc.listSubdirectories(parsed.listDirs);
                out.put("path", parsed.listDirs);
                out.put("directories", dirs);
                out.put("count", dirs.size());
            } else {
                List<FtpBrowseService.PackageInfo> pkgs = svc.scanPackagesStructured(parsed.scanPackages);
                out.put("path", parsed.scanPackages);
                List<Map<String, Object>> items = new java.util.ArrayList<>();
                for (FtpBrowseService.PackageInfo p : pkgs) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("subDirectory", p.getSubDirectory());
                    m.put("packageName", p.getPackageName());
                    m.put("type", p.getType());
                    m.put("relativePath", p.getRelativePath());
                    m.put("size", p.getSize());
                    items.add(m);
                }
                out.put("packages", items);
                out.put("count", items.size());
            }
            Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
            System.out.println(gson.toJson(out));
            return 0;
        } catch (IOException e) {
            System.err.println("[browse] FTP 操作失败: " + e.getMessage());
            return EX_SOFTWARE;
        } finally {
            if (svc != null) svc.disconnect();
        }
    }

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
            System.err.println("[browse] 凭据解密失败: " + e.getMessage());
            return null;
        }
        System.err.println("[browse] 未找到匹配凭据，请先 `credential set`");
        return null;
    }

    /**
     * 打印 usage 到指定流
     *
     * @param out 输出流
     * @author xumanyi
     * @date 2026-03-21
     */
    private static void printUsage(java.io.PrintStream out) {
        out.println("browse - 列出 FTP 目录结构");
        out.println();
        out.println("Usage:");
        out.println("  java -jar flux-deploy-cli.jar browse [options]");
        out.println();
        out.println("Required:");
        out.println("  --host <host>                 FTP 主机");
        out.println("  --port <port>                 端口，默认 18080");
        out.println("  --user <username>             登录用户名");
        out.println();
        out.println("Action (二选一):");
        out.println("  --list-dirs <path>            列出该目录下的子目录");
        out.println("  --scan-packages <path>        递归扫描并列出目录下所有 JAR/WAR 可部署包");
    }

    private static final class Args {
        String host;
        int port = 18080;
        String username;
        String listDirs;
        String scanPackages;

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
                    case "--host":          a.host = require(args, ++i, flag); break;
                    case "--port":          a.port = Integer.parseInt(require(args, ++i, flag)); break;
                    case "--user":
                    case "--username":      a.username = require(args, ++i, flag); break;
                    case "--list-dirs":     a.listDirs = require(args, ++i, flag); break;
                    case "--scan-packages": a.scanPackages = require(args, ++i, flag); break;
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
