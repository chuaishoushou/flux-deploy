package com.flux.deploy.deploy.gates;

import com.flux.deploy.deploy.Gate;
import com.flux.deploy.ftp.FtpOperations;
import com.flux.deploy.model.DeployConfig;
import com.flux.deploy.model.TargetPackage;
import org.apache.commons.net.ftp.FTPFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 说明文件门禁
 *
 * <p>在包上传校验成功后，向更新记录文件追加 取包/传包 两行记录。</p>
 *
 * <p><b>命名兼容</b>：扫描包所在目录、按 {@link NoteFileNames#isNoteCandidate(String, String)}
 * 谓词筛选——文件名以 stem(包名) 开头、{@code .txt} 结尾的全部命中。</p>
 *
 * <p><b>原地追加策略</b>（不做迁移/合并/删除）：</p>
 * <ul>
 *   <li>0 个匹配 → 新建 canonical {@code <包全名>_update_notes.txt} 并写入 2 条记录</li>
 *   <li>1 个匹配 → 原地在该文件末尾追加 2 条记录，文件名保持不变</li>
 *   <li>≥2 个匹配 → 选 canonical（若存在）或字节最多的那个原地追加，其他文件不动</li>
 * </ul>
 *
 * <p>核心保证：<b>不会去重命名、不会去删除任何已存在的版本记录文件</b>。客户 FTP 上已有的命名
 * （含 stem 形式、单数、写法不规范等）会被保留下来继续使用。</p>
 *
 * @author xumanyi
 * @date 2026-03-26
 */
public class NoteGate implements Gate {

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyyMMdd HH:mm");

    private final FtpOperations ops;
    private final DeployConfig config;

    /**
     * 创建说明文件门禁实例
     *
     * @param ops    FTP 操作对象
     * @param config 部署配置
     * @author xumanyi
     * @date 2026-03-26
     */
    public NoteGate(FtpOperations ops, DeployConfig config) {
        this.ops = ops;
        this.config = config;
    }

    /** {@inheritDoc} */
    @Override
    public String name() { return "note"; }

    /**
     * 执行说明文件更新：扫描目录里匹配当前包的所有 .txt，选 canonical 或字节最多者，原地追加取包/传包 2 行。
     * 都没匹配时按 canonical 命名新建文件。不会重命名/删除任何已存在文件。
     *
     * @param target 目标包
     * @throws IOException    FTP 操作或文件 IO 失败
     * @throws GateException  业务逻辑异常
     * @author xumanyi
     * @date 2026-03-26
     */
    @Override
    public void execute(TargetPackage target) throws IOException, GateException {
        String remoteDir = ensureTrailingSlash(target.getRemoteDir());
        String packageName = target.getPackageName();
        String canonicalName = NoteFileNames.canonicalName(packageName);

        // 1) 扫描远端目录，按 NoteFileNames.isNoteCandidate 筛选所有候选
        List<FTPFile> dirListing = ops.listFiles(stripTrailingSlash(remoteDir));
        List<RemoteNote> candidates = new ArrayList<>();
        for (FTPFile entry : dirListing) {
            if (entry == null || !entry.isFile()) continue;
            String fname = entry.getName();
            if (!NoteFileNames.isNoteCandidate(packageName, fname)) continue;
            String fpath = remoteDir + fname;
            String content = downloadString(fpath);
            candidates.add(new RemoteNote(fname, fpath, content));
        }

        // 2) 选目标文件：优先 canonical（精确匹配），否则最大字节者；
        //    都没有就按目录里别的包 .txt 命名风格推一个新文件名，推不出再用 canonical 默认。
        RemoteNote primary = pickPrimary(candidates, canonicalName);
        String writeName;
        String writePath;
        String baseContent;
        if (primary == null) {
            java.util.List<String> dirFileNames = new ArrayList<>();
            for (FTPFile e : dirListing) {
                if (e != null && e.isFile()) dirFileNames.add(e.getName());
            }
            String inferred = NoteFileNames.inferNoteFilenameForNewPackage(packageName, dirFileNames);
            writeName = (inferred != null) ? inferred : canonicalName;
            writePath = remoteDir + writeName;
            baseContent = "";
            if (inferred != null && !inferred.equals(canonicalName)) {
                System.out.println("  [说明] 目录里无当前包 note，参考已有 .txt 命名风格新建: " + writeName);
            }
        } else {
            writeName = primary.name;
            writePath = primary.path;
            baseContent = primary.content;
            if (candidates.size() > 1) {
                System.out.println("  [说明] 检测到 " + candidates.size()
                        + " 个匹配文件，原地追加到字节最多的: " + writeName);
            }
        }

        Path tempNote = Files.createTempFile("note-", ".txt");
        try {
            // 3) 拼接新 2 条记录
            String now = LocalDateTime.now().format(TIME_FMT);
            String operator = nullToEmpty(config.getOperator());
            String taskId = nullToEmpty(config.getTaskId());
            String customerId = nullToEmpty(config.getCustomerId());
            String fetchRecord = "取包时间：" + now
                    + " 开发：" + operator
                    + " 任务：" + taskId
                    + " 客服：" + customerId
                    + " 包名称：" + packageName;
            String uploadRecord = "传包时间：" + now
                    + " 开发：" + operator
                    + " 任务：" + taskId
                    + " 客服：" + customerId
                    + " 包名称：" + packageName;

            StringBuilder sb = new StringBuilder(baseContent);
            if (!baseContent.isEmpty()) {
                if (!baseContent.endsWith("\n")) {
                    sb.append("\n");
                }
                sb.append("\n");
            }
            sb.append(fetchRecord).append("\n");
            sb.append(uploadRecord).append("\n");
            String finalContent = sb.toString();

            // 4) 上传到 writePath（原文件就地覆盖，新建场景就是新 canonical 路径）
            Files.writeString(tempNote, finalContent, StandardCharsets.UTF_8);
            long expectedBytes = Files.size(tempNote);
            ops.upload(tempNote, writePath);
            System.out.println("  [说明] 已上传 " + writeName + " (" + expectedBytes + "B)");

            target.setStatus(TargetPackage.Status.NOTE_UPDATED);
            System.out.println("  [说明] " + writeName + " 已追加 2 条记录");

        } finally {
            Files.deleteIfExists(tempNote);
        }
    }

    private String downloadString(String remotePath) throws IOException {
        Path tmp = Files.createTempFile("note-dl-", ".txt");
        try {
            ops.download(remotePath, tmp);
            return Files.readString(tmp, StandardCharsets.UTF_8);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    /**
     * 从匹配的候选文件里挑选目标：优先精确匹配 canonical；否则取字节最多者；候选为空返回 null。
     *
     * @param candidates 命中谓词的所有候选
     * @param canonicalName canonical 文件名
     * @return 选中的目标，或 null
     * @author xumanyi
     * @date 2026-05-11
     */
    private static RemoteNote pickPrimary(List<RemoteNote> candidates, String canonicalName) {
        if (candidates.isEmpty()) return null;
        for (RemoteNote rn : candidates) {
            if (rn.name.equals(canonicalName)) return rn;
        }
        RemoteNote largest = candidates.get(0);
        for (int i = 1; i < candidates.size(); i++) {
            RemoteNote c = candidates.get(i);
            int cmp = Long.compare(
                    c.content.getBytes(StandardCharsets.UTF_8).length,
                    largest.content.getBytes(StandardCharsets.UTF_8).length);
            if (cmp > 0 || (cmp == 0 && c.name.compareTo(largest.name) < 0)) {
                largest = c;
            }
        }
        return largest;
    }

    /**
     * 确保路径以 / 结尾
     *
     * @param path 原始路径
     * @return 以 / 结尾的路径
     * @author xumanyi
     * @date 2026-03-26
     */
    private static String ensureTrailingSlash(String path) {
        return path.endsWith("/") ? path : path + "/";
    }

    /**
     * 去掉路径末尾的 /，根目录 {@code "/"} 保留不变（FTP listFiles 不接受空路径）。
     *
     * @param path 原始路径
     * @return 去掉末尾 / 的路径
     * @author xumanyi
     * @date 2026-05-11
     */
    private static String stripTrailingSlash(String path) {
        if (path.length() > 1 && path.endsWith("/")) {
            return path.substring(0, path.length() - 1);
        }
        return path;
    }

    /**
     * null 安全转空字符串，避免记录中出现 "null" 字面量
     *
     * @param s 原始字符串，可为 null
     * @return 原字符串；为 null 时返回空串
     * @author xumanyi
     * @date 2026-03-26
     */
    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    /** 远端一份现存的 note 文件（pickPrimary 候选）。 */
    private static final class RemoteNote {
        final String name;
        final String path;
        final String content;

        RemoteNote(String name, String path, String content) {
            this.name = name;
            this.path = path;
            this.content = content;
        }
    }
}
