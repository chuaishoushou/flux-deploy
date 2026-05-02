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
 * <p>在包上传校验成功后，向更新记录文件追加 取包/传包 两行记录。</p>
 *
 * <p><b>命名兼容</b>：FTP 上历史遗留两种命名格式：</p>
 * <ul>
 *   <li>标准命名：{@code <包全名>_update_note.txt}（如 {@code tm10srv.war_update_note.txt}）</li>
 *   <li>历史命名：{@code <包名去扩展名>_update_note.txt}（如 {@code tm10srv_update_note.txt}）</li>
 * </ul>
 *
 * <p><b>解析规则</b>：</p>
 * <ul>
 *   <li>仅标准命名 → 直接追加</li>
 *   <li>仅历史命名 → 沿用历史命名追加，不重命名</li>
 *   <li>都不存在 → 按标准命名新建</li>
 *   <li><b>两者都在</b> → 安全合并到标准命名，删除历史文件（详见下文）</li>
 * </ul>
 *
 * <p><b>双文件合并安全策略</b>：上一版本的迁移逻辑在删除 legacy 失败时会留下双文件，需要清理。</p>
 * <ol>
 *   <li>下载两份文件到本地，比较字节大小，<b>大的作为 base，小的追加到末尾</b>（含合并标记行）</li>
 *   <li>本地拼接出最终内容（无任何 FTP 写操作）</li>
 *   <li>upload 到标准命名路径</li>
 *   <li>re-download 校验远端字节数与本地一致</li>
 *   <li>校验通过才 delete 历史文件；任一步失败 legacy 保持不动，下次重试</li>
 * </ol>
 *
 * <p><b>幂等保护</b>：合并标记 {@link #MERGE_MARKER_PREFIX} 写入产物。下次再发现双文件时，
 * 若 canonical 已含针对该 legacy 名的标记 → 说明上次已合并、只是 delete 残留，直接删 legacy 不再重复合并。</p>
 *
 * @author xumanyi
 * @date 2026-03-26
 */
public class NoteGate implements Gate {

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 合并标记行前缀；canonical 内若含 "{@code MERGE_MARKER_PREFIX} + legacyName" 即视为已合并过该 legacy。 */
    private static final String MERGE_MARKER_PREFIX = "==== 合并历史文件 ";

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

        boolean canonExists = ops.exists(canonicalRemotePath);
        boolean legacyExists = legacyDistinct && ops.exists(legacyRemotePath);

        Path tempNote = Files.createTempFile("note-", ".txt");
        Path tempLegacy = Files.createTempFile("note-legacy-", ".txt");
        try {
            // ===== 1. 决定写入路径 + 准备 base 内容（处理双文件合并） =====
            String writePath;
            String writeName;
            String baseContent;
            boolean shouldDeleteLegacyAfter = false;

            if (canonExists && legacyExists) {
                // 双文件 — 安全合并（大的为基底，小的追加到尾部）
                ops.download(canonicalRemotePath, tempNote);
                ops.download(legacyRemotePath, tempLegacy);
                String canonContent = Files.readString(tempNote, StandardCharsets.UTF_8);
                String legacyContent = Files.readString(tempLegacy, StandardCharsets.UTF_8);
                long canonBytes = canonContent.getBytes(StandardCharsets.UTF_8).length;
                long legacyBytes = legacyContent.getBytes(StandardCharsets.UTF_8).length;

                System.out.println("  [说明] 检测到双文件: " + canonicalName + "(" + canonBytes
                        + "B) + " + legacyName + "(" + legacyBytes + "B)");

                if (canonContent.contains(MERGE_MARKER_PREFIX + legacyName)) {
                    // 上次已合并，legacy 是 delete 失败留下的残留，直接清理
                    System.out.println("  [说明] canonical 已含合并标记，跳过合并，仅清理 legacy 残留");
                    baseContent = canonContent;
                } else if (canonBytes > legacyBytes) {
                    System.out.println("  [说明] canonical 较大，作为 base，将 legacy 追加到末尾");
                    baseContent = mergeContents(canonContent, legacyContent, legacyName, legacyBytes);
                } else if (legacyBytes > canonBytes) {
                    System.out.println("  [说明] legacy 较大，作为 base，将 canonical 追加到末尾");
                    baseContent = mergeContents(legacyContent, canonContent, canonicalName, canonBytes);
                } else {
                    // 大小相等 — 默认 canonical（标准命名）作为 base
                    System.out.println("  [说明] 两文件大小相等，canonical 作为 base，将 legacy 追加到末尾");
                    baseContent = mergeContents(canonContent, legacyContent, legacyName, legacyBytes);
                }

                writePath = canonicalRemotePath;
                writeName = canonicalName;
                shouldDeleteLegacyAfter = true;
            } else if (canonExists) {
                ops.download(canonicalRemotePath, tempNote);
                baseContent = Files.readString(tempNote, StandardCharsets.UTF_8);
                writePath = canonicalRemotePath;
                writeName = canonicalName;
            } else if (legacyExists) {
                ops.download(legacyRemotePath, tempLegacy);
                baseContent = Files.readString(tempLegacy, StandardCharsets.UTF_8);
                writePath = legacyRemotePath;
                writeName = legacyName;
                System.out.println("  [说明] 沿用历史命名文件: " + legacyName);
            } else {
                baseContent = "";
                writePath = canonicalRemotePath;
                writeName = canonicalName;
            }

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

            // ===== 3. 拼接最终内容（已有内容时空行分隔） =====
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

            // ===== 4. 上传 =====
            Files.writeString(tempNote, finalContent, StandardCharsets.UTF_8);
            long expectedBytes = Files.size(tempNote);
            ops.upload(tempNote, writePath);
            System.out.println("  [说明] 已上传 " + writeName + " (" + expectedBytes + "B)");

            // ===== 5. 涉及删除 legacy 时，先 re-download 校验远端字节数 =====
            if (shouldDeleteLegacyAfter) {
                Path verify = Files.createTempFile("note-verify-", ".txt");
                try {
                    ops.download(writePath, verify);
                    long actualBytes = Files.size(verify);
                    if (actualBytes != expectedBytes) {
                        System.out.println("  [说明] 校验失败：远端 size=" + actualBytes
                                + " 期望=" + expectedBytes + "，保留 legacy 不删，下次重试");
                        shouldDeleteLegacyAfter = false;
                    } else {
                        System.out.println("  [说明] 校验通过：远端 size=" + actualBytes);
                    }
                } finally {
                    Files.deleteIfExists(verify);
                }
            }

            // ===== 6. 校验通过后才删 legacy =====
            if (shouldDeleteLegacyAfter) {
                try {
                    ops.delete(legacyRemotePath);
                    System.out.println("  [说明] 已删除历史命名残留: " + legacyName);
                } catch (IOException delFail) {
                    // 删除失败不影响主流程；下次部署靠合并标记识别后再次尝试删除
                    System.out.println("  [说明] 历史文件删除失败（下次部署会重试）: "
                            + legacyName + " - " + delFail.getMessage());
                }
            }

            target.setStatus(TargetPackage.Status.NOTE_UPDATED);
            System.out.println("  [说明] " + writeName + " 已追加 2 条记录");

        } finally {
            Files.deleteIfExists(tempNote);
            Files.deleteIfExists(tempLegacy);
        }
    }

    /**
     * 把 {@code appended} 内容追加到 {@code base} 末尾，并写入合并标记行。
     * 标记行格式：{@code ==== 合并历史文件 <appendedName>（<bytes>B，于 yyyy-MM-dd HH:mm:ss） ====}
     *
     * @param base         作为基底的内容（较大）
     * @param appended     被追加的内容（较小）
     * @param appendedName 被追加文件的远端名（写入标记行供下次幂等识别）
     * @param appendedBytes 被追加内容的字节数
     * @return 合并后的完整字符串
     * @author xumanyi
     * @date 2026-04-30
     */
    private static String mergeContents(String base, String appended,
                                        String appendedName, long appendedBytes) {
        String ts = LocalDateTime.now().format(TIME_FMT);
        StringBuilder sb = new StringBuilder(base);
        if (!base.isEmpty() && !base.endsWith("\n")) {
            sb.append("\n");
        }
        sb.append("\n");
        sb.append(MERGE_MARKER_PREFIX).append(appendedName)
                .append("（").append(appendedBytes).append("B，于 ").append(ts).append("） ====\n");
        sb.append(appended);
        if (!appended.isEmpty() && !appended.endsWith("\n")) {
            sb.append("\n");
        }
        return sb.toString();
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
