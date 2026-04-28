package com.flux.deploy.deploy.gates;

import com.flux.deploy.deploy.Gate;
import com.flux.deploy.ftp.FtpOperations;
import com.flux.deploy.model.DeployConfig;
import com.flux.deploy.model.TargetPackage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 说明文件门禁
 *
 * <p>在包上传校验成功后，向标准命名的更新记录文件追加 取包/传包 两行记录。
 * 标准命名格式：{@code <包名>.jar_update_note.txt}（包名含扩展名 + 固定后缀）。</p>
 *
 * <p><b>历史命名兼容</b>：FTP 上早期版本使用过"去扩展名"的命名：
 * {@code <包名去扩展名>_update_note.txt}，这部分历史文件会被自动迁移为标准命名：</p>
 * <ul>
 *   <li>只存在历史命名 → FTP rename 原子改名为标准命名，再追加</li>
 *   <li>两者都存在 → 读取历史文件全文，拼接到标准文件头部（附迁移标记块），删除历史文件，再追加</li>
 *   <li>只存在标准命名 → 直接追加</li>
 *   <li>都不存在 → 新建标准文件</li>
 * </ul>
 *
 * <p><b>幂等保护</b>：拼接前先判断标准文件是否已含 {@link #MIGRATION_MARKER_PREFIX}，
 * 有则说明上次已合并过（只是历史文件删除失败残留），本次只需删历史文件不再重复拼接。</p>
 *
 * <p><b>执行顺序</b>：所有涉及历史文件的场景，一律"先把标准文件写完整，再删除历史文件"，
 * 即使中途失败也不会出现历史丢失（标准文件已经留底）。</p>
 *
 * @author xumanyi
 * @date 2026-03-26
 */
public class NoteGate implements Gate {

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 迁移标记块起始行前缀；字符串匹配即可识别，无需结构化解析。 */
    private static final String MIGRATION_MARKER_PREFIX = "==== 以下来自历史文件 ";
    private static final String MIGRATION_MARKER_SUFFIX = "==== 历史文件结束 ====";

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
     * 执行说明文件更新：下载现有 note 文件，追加取包/传包记录，上传覆盖
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
        String canonicalName = target.getPackageName() + "_update_note.txt";
        String legacyStem = stripLastExt(target.getPackageName());
        String legacyName = legacyStem + "_update_note.txt";
        boolean legacyDistinct = !legacyName.equals(canonicalName); // 无扩展名包退化为等同标准名

        String canonicalRemotePath = remoteDir + canonicalName;
        String legacyRemotePath = remoteDir + legacyName;

        Path tempNote = Files.createTempFile("note-", ".txt");
        try {
            // ===== 1. 决定工作文件路径，合并历史文件（如有） =====
            String existingContent = prepareCanonicalContent(
                    canonicalRemotePath, legacyRemotePath, legacyName, legacyDistinct, tempNote);

            // ===== 2. 构造本次两条记录 =====
            //    取包|yyyy-MM-dd HH:mm:ss|{开发}|任务：{任务描述}|客服：{客服号}|{包名}
            //    传包|...（同上）
            String now = LocalDateTime.now().format(TIME_FMT);
            String operator = nullToEmpty(config.getOperator());
            String taskSeg = "任务：" + nullToEmpty(config.getTaskId());
            String customerSeg = "客服：" + nullToEmpty(config.getCustomerId());
            String fetchRecord = String.join("|",
                    "取包", now, operator, taskSeg, customerSeg, target.getPackageName());
            String uploadRecord = String.join("|",
                    "传包", now, operator, taskSeg, customerSeg, target.getPackageName());

            // ===== 3. 追加到内容末尾（已有内容时先加一个空行分隔） =====
            StringBuilder sb = new StringBuilder(existingContent);
            if (!existingContent.isEmpty()) {
                if (!existingContent.endsWith("\n")) {
                    sb.append("\n");
                }
                sb.append("\n"); // 空行分隔
            }
            sb.append(fetchRecord).append("\n");
            sb.append(uploadRecord).append("\n");

            String newContent = sb.toString();

            // ===== 4. 先写标准文件 =====
            Files.writeString(tempNote, newContent, StandardCharsets.UTF_8);
            ops.upload(tempNote, canonicalRemotePath);

            // ===== 5. 标准文件写成功，再清理历史文件（先写后删，失败也不丢数据） =====
            if (legacyDistinct && ops.exists(legacyRemotePath)) {
                try {
                    ops.delete(legacyRemotePath);
                    System.out.println("  [说明] 已清理历史命名文件: " + legacyName);
                } catch (IOException delFail) {
                    // 删除失败不影响主流程：标准文件已包含历史内容（或有迁移标记），
                    // 下次部署幂等保护会跳过重复拼接，只再次尝试删除。
                    System.out.println("  [说明] 历史文件删除失败（下次部署会重试）: "
                            + legacyName + " - " + delFail.getMessage());
                }
            }

            target.setStatus(TargetPackage.Status.NOTE_UPDATED);
            System.out.println("  [说明] " + canonicalName + " 已追加 2 条记录");

        } finally {
            Files.deleteIfExists(tempNote);
        }
    }

    /**
     * 按四种场景准备出标准文件将要写入的基础内容（不含本次两条新记录）。
     *
     * <pre>
     * 标准文件存在 | 历史文件存在 | 动作
     * ------------ + ------------ + --------------------------------------------------------------
     *      ✓       |      ✗       | 读标准文件原文返回
     *      ✓       |      ✓       | 读标准文件：
     *              |              |   - 已含迁移标记 → 原文返回（下一步会删除残留的历史文件）
     *              |              |   - 未含迁移标记 → 读历史文件，拼接 [迁移块 + 历史原文 + 标准原文]
     *      ✗       |      ✓       | rename(历史 → 标准)：
     *              |              |   - 成功 → 读标准文件原文返回（等价于场景 ✓ ✗）
     *              |              |   - 失败 → 读历史原文返回，后续写入标准路径（双份过渡，下次重试）
     *      ✗       |      ✗       | 返回空串（后续将新建标准文件）
     * </pre>
     *
     * @param canonicalRemotePath 标准命名远程路径
     * @param legacyRemotePath    历史命名远程路径
     * @param legacyName          历史文件名（用于日志输出）
     * @param legacyDistinct      历史命名是否与标准命名不同
     * @param tempNote            本地临时文件，用于 FTP 下载中转
     * @return 标准文件的基础内容字符串；都不存在时返回空串
     * @throws IOException FTP 操作或本地文件 IO 失败
     * @author xumanyi
     * @date 2026-03-26
     */
    private String prepareCanonicalContent(String canonicalRemotePath,
                                           String legacyRemotePath,
                                           String legacyName,
                                           boolean legacyDistinct,
                                           Path tempNote) throws IOException {
        boolean canonicalExists = ops.exists(canonicalRemotePath);
        boolean legacyExists = legacyDistinct && ops.exists(legacyRemotePath);

        // 场景 4：都不存在
        if (!canonicalExists && !legacyExists) {
            return "";
        }

        // 场景 3：只有历史文件 —— 优先用 rename 原子改名
        if (!canonicalExists && legacyExists) {
            try {
                ops.rename(legacyRemotePath, canonicalRemotePath);
                System.out.println("  [说明] 历史命名 " + legacyName + " 已重命名为标准格式");
                // 重命名后读标准文件
                ops.download(canonicalRemotePath, tempNote);
                return Files.readString(tempNote, StandardCharsets.UTF_8);
            } catch (IOException renameFail) {
                // rename 失败：降级为直接读历史文件，写入标准路径；历史文件保留等下次再试
                System.out.println("  [说明] rename 失败，降级读历史文件写入标准路径: "
                        + renameFail.getMessage());
                ops.download(legacyRemotePath, tempNote);
                return Files.readString(tempNote, StandardCharsets.UTF_8);
            }
        }

        // 场景 1：只有标准文件 —— 直读
        if (canonicalExists && !legacyExists) {
            ops.download(canonicalRemotePath, tempNote);
            return Files.readString(tempNote, StandardCharsets.UTF_8);
        }

        // 场景 2：两者都在 —— 判幂等
        ops.download(canonicalRemotePath, tempNote);
        String canonicalContent = Files.readString(tempNote, StandardCharsets.UTF_8);
        if (canonicalContent.contains(MIGRATION_MARKER_PREFIX)) {
            // 标准文件里已经有历史迁移块，说明上次已合并，只是历史文件删除失败残留
            System.out.println("  [说明] 检测到历史迁移痕迹，跳过重复合并，仅删除残留历史文件");
            return canonicalContent;
        }

        // 真正的首次合并：读历史文件原文，拼接到标准文件头部
        ops.download(legacyRemotePath, tempNote);
        String legacyContent = Files.readString(tempNote, StandardCharsets.UTF_8);

        String migratedAt = LocalDateTime.now().format(TIME_FMT);
        StringBuilder merged = new StringBuilder();
        merged.append(MIGRATION_MARKER_PREFIX).append(legacyName)
                .append("（迁移于 ").append(migratedAt).append("） ====\n");
        merged.append(legacyContent);
        if (!legacyContent.endsWith("\n")) {
            merged.append("\n");
        }
        merged.append(MIGRATION_MARKER_SUFFIX).append("\n\n");
        merged.append(canonicalContent);

        System.out.println("  [说明] 检测到历史文件 " + legacyName + "，已合并到标准文件头部");
        return merged.toString();
    }

    /**
     * 取文件名最后一个扩展名之前的部分。
     * 例如 {@code scev6-utils-tms-10.0.0-SNAPSHOT.jar} → {@code scev6-utils-tms-10.0.0-SNAPSHOT}。
     * 无扩展名或点在开头时原样返回。
     *
     * @param name 文件名
     * @return 去掉最后一个扩展名后的文件名；无扩展名时原样返回
     * @author xumanyi
     * @date 2026-03-26
     */
    private static String stripLastExt(String name) {
        int dot = name.lastIndexOf('.');
        return (dot > 0) ? name.substring(0, dot) : name;
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
}
