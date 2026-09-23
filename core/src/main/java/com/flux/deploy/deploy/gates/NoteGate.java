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
    /** 本次部署开始执行的时刻——所有包共用，作为版本记录的"取包时间"。 */
    private final LocalDateTime deployStart;

    /**
     * 创建说明文件门禁实例
     *
     * @param ops         FTP 操作对象
     * @param config      部署配置
     * @param deployStart 本次部署开始执行的时刻，用作所有包的取包时间
     * @author xumanyi
     * @date 2026-05-28
     */
    public NoteGate(FtpOperations ops, DeployConfig config, LocalDateTime deployStart) {
        this.ops = ops;
        this.config = config;
        this.deployStart = deployStart;
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
            String content = downloadString(fpath, fname);
            candidates.add(new RemoteNote(fname, fpath, content));
        }

        // 2) 选目标文件：优先 canonical（精确匹配），否则最大字节者；都没有就用 canonical 新建。
        RemoteNote primary = pickPrimary(name(), candidates, canonicalName);
        String writeName;
        String writePath;
        String baseContent;
        if (primary == null) {
            writeName = canonicalName;
            writePath = remoteDir + writeName;
            baseContent = "";
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
            // 3) 拼接新 2 条记录：取包时间=部署开始时刻（全局共用），传包时间=本包刚上传校验完成的当前时刻
            String fetchTime = deployStart.format(TIME_FMT);
            String uploadTime = LocalDateTime.now().format(TIME_FMT);
            String operator = nullToEmpty(config.getOperator());
            String taskId = nullToEmpty(config.getTaskId());
            String customerId = nullToEmpty(config.getCustomerId());
            String fetchRecord = "取包时间：" + fetchTime
                    + " 开发：" + operator
                    + " 任务：" + taskId
                    + " 客服：" + customerId
                    + " 包名称：" + packageName;
            String uploadRecord = "传包时间：" + uploadTime
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

            // 4) 漏洞 H3 修复：upload 之前先快照远端原始字节，登记 writePath 到 target，
            //    让 Rollback 在 NOTE_UPDATED 状态能精确撤销 NoteGate 的写入：
            //    - primary != null（覆盖已存在）：snapshot = 原始字节，回滚时 STOR 原字节
            //    - primary == null（新建 canonical）：snapshot = null，回滚时 delete writePath
            //    这一步独立于 baseContent 字符串，避免 GB18030↔UTF-8 转码丢失原编码字节。
            byte[] noteSnapshot = null;
            if (primary != null) {
                noteSnapshot = downloadBytes(primary.path);
            }
            target.setNoteSnapshotBytes(noteSnapshot);
            target.setNoteRemotePath(writePath);

            // 5) 发布到 writePath（原子发布：整份写回一旦半传就会截断历史记录，
            //    而版本记录没有第二份副本，必须传完校验通过才替换）
            Files.writeString(tempNote, finalContent, StandardCharsets.UTF_8);
            long expectedBytes = Files.size(tempNote);
            ops.uploadAtomic(tempNote, writePath);
            target.setStatus(TargetPackage.Status.NOTE_UPDATED);
            System.out.println("  [说明] " + writeName + " 已追加 2 条记录 (" + expectedBytes + " B)");

        } finally {
            Files.deleteIfExists(tempNote);
        }
    }

    /**
     * 下载远端 note 文件并宽容解码为字符串。文件非 UTF-8 时自动回退 GB18030，绝不因编码非法而中断。
     *
     * @param remotePath  远端文件绝对路径
     * @param displayName 用于日志的文件名
     * @return 解码后的文本内容（写回时统一转 UTF-8）
     * @throws IOException 下载或读取失败
     * @author xumanyi
     * @date 2026-06-02
     */
    private String downloadString(String remotePath, String displayName) throws IOException {
        Path tmp = Files.createTempFile("note-dl-", ".txt");
        try {
            ops.download(remotePath, tmp);
            return NoteCharsetReader.readLenient(tmp, displayName, msg -> System.out.println("  " + msg));
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    /**
     * 下载远端 note 文件的原始字节（不做任何解码 / 编码转换）。
     *
     * <p>专用于 {@link com.flux.deploy.deploy.Rollback} 的字节级回滚快照：覆盖 GB18030 等
     * 原编码的 note 文件后，必须按原始字节写回，不能经过 UTF-8 string 转换链路。</p>
     *
     * @param remotePath 远端绝对路径
     * @return 文件原始字节
     * @throws IOException 下载失败
     * @author xumanyi
     * @date 2026-07-01
     */
    private byte[] downloadBytes(String remotePath) throws IOException {
        Path tmp = Files.createTempFile("note-snap-", ".bin");
        try {
            ops.download(remotePath, tmp);
            return Files.readAllBytes(tmp);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    /**
     * 从匹配的候选文件里挑选目标：0 个返回 null（调用者按 canonical 新建）；1 个直接返回；
     * ≥2 个属于冲突状态——本应由 plugin 层预检阶段拦截并要求用户手动清理，落到此处属于异常路径，直接抛错避免误写。
     *
     * <p>抛 {@link Gate.GateException} 而不是 unchecked 异常，让 Stage2 的
     * {@code catch (GateException)} 分支能正确捕获并触发回滚（漏洞 H2 修复）：
     * 历史上抛 {@code IllegalStateException} 会绕过 DeployPipeline.executeStage2 的全部 catch，
     * 一路冒到 {@code execute()} 之外，业务包不被回滚，锁包/新包留在远端。</p>
     *
     * @param gateName  门禁名（用于异常信息）
     * @param candidates 命中谓词的所有候选
     * @param canonicalName canonical 文件名（仅用于异常信息）
     * @return 选中的目标，或 null
     * @throws Gate.GateException 候选 ≥2 时（应在预检拦截）
     * @author xumanyi
     * @date 2026-05-11
     */
    private static RemoteNote pickPrimary(String gateName, List<RemoteNote> candidates, String canonicalName)
            throws Gate.GateException {
        if (candidates.isEmpty()) return null;
        if (candidates.size() == 1) return candidates.get(0);
        StringBuilder names = new StringBuilder();
        for (RemoteNote rn : candidates) {
            if (names.length() > 0) names.append(", ");
            names.append(rn.name);
        }
        throw new Gate.GateException(gateName,
                "note 候选 ≥2（预检阶段应已拦截，请手动清理后重试），canonical=" + canonicalName + "，候选=[" + names + "]");
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
