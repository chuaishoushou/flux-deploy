package com.flux.deploy.maven;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * 可移植的 Maven 调用封装，core 模块内无 IDE 依赖。
 *
 * <p>mvn 可执行文件查找优先级：</p>
 * <ol>
 *   <li>构造时传入的 {@code mavenHome}（最高优先级，一般由 IDE 插件传入其配置）</li>
 *   <li>项目根向上 6 级内的 {@code mvnw} / {@code mvnw.cmd}</li>
 *   <li>环境变量 {@code MAVEN_HOME} / {@code M2_HOME}</li>
 *   <li>交互式 shell 里的 {@code which mvn}</li>
 *   <li>裸 {@code mvn} / {@code mvn.cmd}（依赖 PATH）</li>
 * </ol>
 *
 * <p>JAVA_HOME 来源：构造时传入（IDE 里是 Project SDK，CLI 里用调用者 env）。</p>
 *
 * @author xumanyi
 * @date 2026-04-25
 */
public final class MavenRunner {

    private static final boolean IS_WINDOWS =
            System.getProperty("os.name", "").toLowerCase().contains("win");

    private final Path projectDir;
    private final String javaHome;
    private final String mavenHome;
    private final Consumer<String> logCallback;

    /**
     * 构造 Maven 调用器。
     *
     * @param projectDir   包含 pom.xml 的项目或模块目录
     * @param javaHome     JAVA_HOME 绝对路径，null 时沿用子进程继承环境
     * @param mavenHome    Maven 根目录，null 时自动查找
     * @param logCallback  日志回调，null 时丢弃日志
     */
    public MavenRunner(Path projectDir, String javaHome, String mavenHome,
                       Consumer<String> logCallback) {
        this.projectDir = projectDir;
        this.javaHome = javaHome;
        this.mavenHome = mavenHome;
        this.logCallback = logCallback == null ? s -> {} : logCallback;
    }

    /**
     * 以传入 goals 执行 mvn，阻塞到结束。
     *
     * @param goals mvn 目标（如 {@code "clean"}, {@code "package"}, {@code "-DskipTests"}）
     * @return mvn 退出码（0 表示成功）
     */
    public int run(String... goals) throws IOException, InterruptedException {
        String mvnCmd = resolveMvnCommand();
        logCallback.accept("[编译] 使用 Maven: " + mvnCmd);
        if (javaHome != null) {
            logCallback.accept("[编译] 使用 JDK: " + javaHome);
        }

        String[] cmd = new String[goals.length + 1];
        cmd[0] = mvnCmd;
        System.arraycopy(goals, 0, cmd, 1, goals.length);

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(projectDir.toFile());
        pb.redirectErrorStream(true);

        if (javaHome != null && !javaHome.isBlank()) {
            Map<String, String> env = pb.environment();
            env.put("JAVA_HOME", javaHome);
            String binDir = javaHome + File.separator + "bin";
            String existingPath = env.getOrDefault("PATH", env.getOrDefault("Path", ""));
            String newPath = binDir + File.pathSeparator + existingPath;
            env.put("PATH", newPath);
            env.put("Path", newPath);
        }

        logCallback.accept("[编译] " + String.join(" ", Arrays.asList(cmd)));
        Process process = pb.start();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.contains("BUILD SUCCESS")
                        || line.contains("BUILD FAILURE")
                        || line.contains("[ERROR]")
                        || line.contains("FAILURE")) {
                    logCallback.accept("[编译] " + line);
                }
            }
        }
        int exitCode = process.waitFor();
        logCallback.accept("[编译] mvn 退出码: " + exitCode);
        return exitCode;
    }

    /**
     * 便捷方法：{@code mvn clean package -DskipTests}。
     */
    public int cleanPackage() throws IOException, InterruptedException {
        return run("clean", "package", "-DskipTests");
    }

    /**
     * 便捷方法：{@code mvn compile}，用于只需要 class 产物的增量场景。
     */
    public int compile() throws IOException, InterruptedException {
        return run("compile");
    }

    // -------------------------------------------------------------------------
    // mvn 命令查找
    // -------------------------------------------------------------------------

    private String resolveMvnCommand() {
        String mvnExe = IS_WINDOWS ? "mvn.cmd" : "mvn";
        String mvnwExe = IS_WINDOWS ? "mvnw.cmd" : "mvnw";

        // 1. 构造时传入的 mavenHome
        if (mavenHome != null && !mavenHome.isBlank()) {
            Path p = Path.of(mavenHome, "bin", mvnExe);
            if (Files.exists(p)) return p.toString();
        }

        // 2. 项目内 mvnw（向上搜索 6 层）
        Path dir = projectDir;
        for (int i = 0; i < 6 && dir != null; i++) {
            Path mvnw = dir.resolve(mvnwExe);
            if (Files.exists(mvnw)) return mvnw.toString();
            dir = dir.getParent();
        }

        // 3. MAVEN_HOME / M2_HOME
        for (String envVar : new String[]{"MAVEN_HOME", "M2_HOME"}) {
            String home = System.getenv(envVar);
            if (home != null) {
                Path mvn = Path.of(home, "bin", mvnExe);
                if (Files.exists(mvn)) return mvn.toString();
            }
        }

        // 4. 交互式 shell 查找
        String viaShell = findViaShell(mvnExe);
        if (viaShell != null) return viaShell;

        // 5. 兜底
        return mvnExe;
    }

    private static String findViaShell(String mvnExe) {
        try {
            String[] cmd;
            if (IS_WINDOWS) {
                cmd = new String[]{"cmd", "/c", "where", mvnExe};
            } else {
                String shell = System.getenv("SHELL");
                if (shell == null) shell = "/bin/zsh";
                cmd = new String[]{shell, "-ilc", "which mvn"};
            }
            Process proc = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    line = line.trim();
                    if (!line.isEmpty()
                            && !line.startsWith("Last login")
                            && Files.exists(Path.of(line))) {
                        proc.destroyForcibly();
                        return line;
                    }
                }
            }
            proc.waitFor(5, TimeUnit.SECONDS);
            proc.destroyForcibly();
        } catch (Exception ignored) {}
        return null;
    }
}
