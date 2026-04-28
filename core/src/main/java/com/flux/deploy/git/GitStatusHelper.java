package com.flux.deploy.git;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * {@code git status --porcelain} 解析器。
 *
 * <p>输出的每条 {@link ChangedFile} 含：</p>
 * <ul>
 *   <li>{@code status} — A（新增）/ M（修改）/ D（删除）/ R（重命名）/ ? （未跟踪）</li>
 *   <li>{@code path}   — 相对 {@code workingDir} 的路径，已处理 rename 的 "from -&gt; to"</li>
 * </ul>
 *
 * <p>patch-local 子命令的本地补丁场景使用本类（mode=auto）。deploy 子命令的 incremental 模式不再使用——文件列表由调用方（插件 UI / Skill）显式提供。</p>
 *
 * @author xumanyi
 * @date 2026-04-25
 */
public final class GitStatusHelper {

    /** 单条变更记录。 */
    public static final class ChangedFile {
        public final char status;
        public final String path;

        public ChangedFile(char status, String path) {
            this.status = status;
            this.path = path;
        }

        @Override
        public String toString() {
            return status + " " + path;
        }
    }

    private GitStatusHelper() {
    }

    /**
     * 执行 {@code git status --porcelain} 并返回变更列表。
     *
     * <p>只返回 M / A / D / R / ? 的条目；R 取 rename 后的新路径。</p>
     *
     * @param workingDir 工作目录（Git 仓库内的任意子目录即可）
     * @return 变更文件列表，仓库无变更时返回空列表
     * @throws IOException git 调用失败或非 0 退出
     */
    public static List<ChangedFile> status(Path workingDir) throws IOException {
        Objects.requireNonNull(workingDir, "workingDir");
        ProcessBuilder pb = new ProcessBuilder("git", "status", "--porcelain");
        pb.directory(workingDir.toFile());
        pb.redirectErrorStream(true);

        List<ChangedFile> result = new ArrayList<>();
        Process proc = null;
        try {
            proc = pb.start();
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    ChangedFile cf = parseLine(line);
                    if (cf != null) {
                        result.add(cf);
                    }
                }
            }
            if (!proc.waitFor(30, TimeUnit.SECONDS)) {
                proc.destroyForcibly();
                throw new IOException("git status 超时");
            }
            int exit = proc.exitValue();
            if (exit != 0) {
                throw new IOException("git status 退出码非 0: " + exit);
            }
            return result;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (proc != null) proc.destroyForcibly();
            throw new IOException("git status 被中断", e);
        }
    }

    /**
     * 返回只相对某个模块根的变更路径（过滤后的 path 会相对 {@code moduleDir} 再次计算）。
     * 若变更文件不在 {@code moduleDir} 内则被忽略。
     */
    public static List<ChangedFile> statusRelativeTo(Path gitRoot, Path moduleDir) throws IOException {
        Path absModule = moduleDir.toAbsolutePath().normalize();
        Path absRoot = gitRoot.toAbsolutePath().normalize();

        List<ChangedFile> raw = status(gitRoot);
        List<ChangedFile> filtered = new ArrayList<>();
        for (ChangedFile cf : raw) {
            Path absFile = absRoot.resolve(cf.path).normalize();
            if (absFile.startsWith(absModule)) {
                String rel = absModule.relativize(absFile).toString().replace('\\', '/');
                filtered.add(new ChangedFile(cf.status, rel));
            }
        }
        return filtered;
    }

    /**
     * 解析 porcelain 一行，示例 {@code " M path/to/file"} 或 {@code "R  old -> new"}
     *
     * @param line git status --porcelain 输出的单行
     * @return 解析后的 {@link ChangedFile}；行为空或格式不合法时返回 null
     * @author xumanyi
     * @date 2026-04-25
     */
    static ChangedFile parseLine(String line) {
        if (line == null || line.length() < 3) return null;
        // porcelain 前两字符是 XY，第 3 字符是空格
        char x = line.charAt(0);
        char y = line.charAt(1);
        // 选择优先级：X（已 staged）非空则用 X；否则用 Y（工作区变更）
        char status = x != ' ' && x != '?' ? x : y;
        if (status == ' ') status = x; // ?? 情况保持 ?
        if (status == '?') status = '?';

        String rest = line.substring(3).trim();
        // 重命名：从 "old -> new" 取 new
        int arrow = rest.indexOf(" -> ");
        String path = arrow >= 0 ? rest.substring(arrow + 4) : rest;
        // 可能被双引号包裹（含特殊字符时）
        if (path.startsWith("\"") && path.endsWith("\"")) {
            path = path.substring(1, path.length() - 1);
        }
        if (path.isEmpty()) return null;
        return new ChangedFile(status, path);
    }
}
