package com.flux.deploy.plugin.service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Vue 模块自动构建执行器
 *
 * <p>调用前端工程自带的 sce-vcom CLI 做单/多模块构建：
 * {@code npx sce-vcom build:one --force --skip-typecheck}，模块号从 stdin 喂入
 * （该命令为交互式输入，无命令行参数；已实测管道输入可用）。</p>
 *
 * <p>只允许 {@code build:one}——它无任何 git 副作用；全量构建命令（sce-vcom build）
 * 内置 {@code git checkout develop + reset --hard} 会覆盖本地未提交改动，绝不能被自动执行。</p>
 *
 * <p>环境：mac/linux 经登录 shell（zsh/bash -lc）执行以带上用户 PATH（nvm 等），
 * 解决 IDE 进程 PATH 精简找不到 node 的经典问题；Windows 走 cmd /c。</p>
 *
 * @author xumanyi
 * @date 2026-08-13
 */
public final class VueModuleBuildRunner {

    /** 构建超时（分钟）：单模块秒级~分钟级，留足裕量 */
    private static final int TIMEOUT_MINUTES = 15;

    private VueModuleBuildRunner() {}

    /**
     * 构建指定模块（阻塞直至完成；输出逐行回调）
     *
     * @param projectRoot 前端工程根目录
     * @param moduleIds   要构建的模块号列表（逗号分隔喂给 CLI）
     * @param logCallback 构建输出逐行回调
     * @param cancelled   取消探测；返回 true 时强制终止构建进程并抛异常
     * @throws IOException 构建进程启动失败 / 退出码非 0 / 超时 / 被取消
     * @author xumanyi
     * @date 2026-08-13
     */
    public static void buildModules(Path projectRoot, List<String> moduleIds,
                                    Consumer<String> logCallback, BooleanSupplier cancelled)
            throws IOException {
        runBuild(projectRoot, wrapShell("npx sce-vcom build:one --force --skip-typecheck"),
                String.join(",", moduleIds), moduleIds.size(), logCallback, cancelled);
    }

    /**
     * 执行工程 package.json 中的 npm 脚本（非交互；用于共享库工程的 build-only 构建）
     *
     * @param projectRoot 工程根目录
     * @param npmScript   脚本名（如 build-only）
     * @param logCallback 构建输出逐行回调
     * @param cancelled   取消探测；返回 true 时强制终止构建进程并抛异常
     * @throws IOException 构建进程启动失败 / 退出码非 0 / 超时 / 被取消
     * @author xumanyi
     * @date 2026-08-14
     */
    public static void runNpmScript(Path projectRoot, String npmScript,
                                    Consumer<String> logCallback, BooleanSupplier cancelled)
            throws IOException {
        runBuild(projectRoot, wrapShell("npm run " + npmScript), null, 1, logCallback, cancelled);
    }

    /**
     * 构建进程执行主体：启动进程、可选喂入 stdin、逐行清洗输出、超时与取消控制
     *
     * @param projectRoot 工程根目录
     * @param command     完整命令行（已按操作系统 shell 包装）
     * @param stdinLine   写入 stdin 的一行（null 表示不喂入，直接关闭）
     * @param unitCount   任务单元数（超时按单元数 ×5 分钟缩放，下限 15 分钟）
     * @param logCallback 输出逐行回调
     * @param cancelled   取消探测
     * @throws IOException 启动失败 / 退出码非 0 / 超时 / 被取消
     * @author xumanyi
     * @date 2026-08-14
     */
    private static void runBuild(Path projectRoot, List<String> command, String stdinLine,
                                 int unitCount, Consumer<String> logCallback,
                                 BooleanSupplier cancelled) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(projectRoot.toFile());
        pb.redirectErrorStream(true);

        Process proc;
        try {
            proc = pb.start();
        } catch (IOException e) {
            throw new IOException("无法启动前端构建进程（请确认本机已安装 node/npm 环境）: "
                    + e.getMessage(), e);
        }
        try {
            try (OutputStreamWriter w = new OutputStreamWriter(
                    proc.getOutputStream(), StandardCharsets.UTF_8)) {
                if (stdinLine != null) {
                    w.write(stdinLine + "\n");
                    w.flush();
                }
            }
            // 超时按任务单元数缩放：整包更新一次构建 40+ 个模块，固定 15 分钟会误伤
            long timeoutMinutes = Math.max(TIMEOUT_MINUTES, unitCount * 5L);
            long deadline = System.currentTimeMillis() + timeoutMinutes * 60_000L;
            try (BufferedReader r = new BufferedReader(new InputStreamReader(
                    proc.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    if (cancelled != null && cancelled.getAsBoolean()) {
                        proc.destroyForcibly();
                        throw new IOException("用户请求停止，已终止前端构建");
                    }
                    if (System.currentTimeMillis() > deadline) {
                        proc.destroyForcibly();
                        throw new IOException("前端构建超时（超过 " + timeoutMinutes + " 分钟）");
                    }
                    String cleaned = sanitizeLine(line);
                    if (cleaned != null) {
                        logCallback.accept(cleaned);
                    }
                }
            }
            int code;
            try {
                code = proc.waitFor();
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                proc.destroyForcibly();
                throw new IOException("前端构建被中断");
            }
            if (code != 0) {
                throw new IOException("前端构建失败，退出码 " + code + "（详见上方构建输出）");
            }
        } finally {
            if (proc.isAlive()) {
                proc.destroyForcibly();
            }
        }
    }

    /**
     * ANSI/终端控制序列（CSI 序列如光标定位 {@code ESC[87G}、清行 {@code ESC[2K}，
     * 以及 {@code ESC[?25h} 这类模式开关）。CLI 的交互式提示与 vite 进度条大量输出这类
     * 序列，落进纯文本日志就是乱码。
     */
    private static final java.util.regex.Pattern ANSI_PATTERN =
            java.util.regex.Pattern.compile("\\u001B\\[[0-9;:?]*[ -/]*[@-~]|\\u001B[@-_]|\\r");

    /**
     * 清洗构建输出行：剥掉终端控制序列，过滤交互提示回显与逐帧进度噪音。
     *
     * <p>CLI 是交互式命令（inquirer 提示 + vite 进度动画），面向终端的输出直接进日志
     * 会出现两类脏东西：① 每敲一个字符就整行重绘的提示回显（含大量光标控制序列）；
     * ② transforming / rendering chunks 这类逐帧刷新的进度行。这里按<b>噪音清单</b>过滤
     * ——只丢确认无信息量的行，未知输出（含报错）一律放行，保证构建失败时线索不丢。</p>
     *
     * <p>保留的关键行：{@code 开始打包 X 模块}、{@code [模块] 待构建入口 N 个}、
     * {@code dist/umd/... 产物尺寸}、{@code 打包 X 成功/失败} 与所有未识别行。</p>
     *
     * @param raw 进程原始输出行
     * @return 清洗后的行；应整行丢弃时返回 {@code null}
     * @author xumanyi
     * @date 2026-08-14
     */
    private static String sanitizeLine(String raw) {
        String line = ANSI_PATTERN.matcher(raw).replaceAll("").trim();
        if (line.isEmpty()) return null;
        // 交互提示回显：每个按键重绘一次，同一句提示能刷十几行；模块号已由插件喂入并
        // 在自己的 INFO 行里说明过，回显（含 ✔ 确认行、跨行折断的"分隔）"尾巴）全部丢弃
        if (line.contains("请输入您要打包的模块") || line.startsWith("分隔）")
                || line.startsWith("要打包的模块为")) {
            return null;
        }
        // vite 逐帧进度与固定流程播报：无诊断价值，出错时 vite 会另打 error 行
        if (line.startsWith("vite v") || line.startsWith("transforming")
                || line.startsWith("rendering chunks") || line.startsWith("computing gzip")
                || line.contains("modules transformed") || line.startsWith("✓ built in")) {
            return null;
        }
        // CLI 每个入口固定打的 6 行流程话术（生成配置/执行打包/清理配置/两种版本），
        // 成功与否看「打包 X 成功/失败」一行即可
        if (line.startsWith("构建压缩版本") || line.startsWith("构建源码版本")
                || line.startsWith("正在生成 ") || line.startsWith("正在执行 ")
                || line.startsWith("正在清理 ") || line.contains("已指定忽略缓存")
                || (line.startsWith("生成 ") && line.endsWith("成功"))
                || (line.startsWith("清理 ") && line.endsWith("成功"))) {
            return null;
        }
        return line;
    }

    /**
     * 组装按操作系统适配的构建命令行
     *
     * @return 完整命令行（shell 包装）
     * @author xumanyi
     * @date 2026-08-13
     */
    private static List<String> wrapShell(String cliCommand) {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            return List.of("cmd", "/c", cliCommand);
        }
        String shell = Files.isRegularFile(Path.of("/bin/zsh")) ? "/bin/zsh" : "/bin/bash";
        return List.of(shell, "-lc", cliCommand);
    }
}
