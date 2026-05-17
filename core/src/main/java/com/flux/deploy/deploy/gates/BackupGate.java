package com.flux.deploy.deploy.gates;

import com.flux.deploy.deploy.Gate;
import com.flux.deploy.ftp.FtpOperations;
import com.flux.deploy.model.DeployConfig;
import com.flux.deploy.model.TargetPackage;
import org.apache.commons.net.ftp.FTPFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 备份门禁
 *
 * <p>在加锁前将远程原包备份到子系统根目录下的 backup/ 目录。</p>
 *
 * <p>备份路径示例：{@code /开发/AISKILL测试项目/TMS V9.0-P7/backup/20260326_xumanyi/}</p>
 *
 * <p>备份目录命名规则：</p>
 * <ul>
 *   <li>基础名：{@code YYYYMMDD_operator}</li>
 *   <li>同名已存在时自动加序号：{@code YYYYMMDD_operator_2}、{@code _3}...</li>
 * </ul>
 *
 * @author xumanyi
 * @date 2026-03-26
 */
public class BackupGate implements Gate {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final FtpOperations ops;
    private final DeployConfig config;

    /**
     * 创建备份门禁实例
     *
     * @param ops    FTP 操作对象
     * @param config 部署配置
     * @author xumanyi
     * @date 2026-03-26
     */
    public BackupGate(FtpOperations ops, DeployConfig config) {
        this.ops = ops;
        this.config = config;
    }

    /** {@inheritDoc} */
    @Override
    public String name() { return "backup"; }

    /**
     * 执行备份：下载远程原包到本地临时文件，上传到 backup/ 目录并验证大小一致
     *
     * @param target 目标包
     * @throws IOException    FTP 操作失败
     * @throws GateException  备份大小不一致
     * @author xumanyi
     * @date 2026-03-26
     */
    @Override
    public void execute(TargetPackage target) throws IOException, GateException {
        // 1. 计算子系统根目录（-d 参数对应的目录，即至少 3 级的那个目录）
        String systemRoot = resolveSystemRoot(config.getRemoteDir());
        String backupParent = systemRoot + "backup/";

        // 2. 确保 backup/ 目录存在
        ops.mkdirIfAbsent(backupParent);

        // 3. 确定备份子目录名（优先使用配置显式指定；否则按 <date>_<operator> 自动生成）
        String baseName;
        if (config.getBackupDirName() != null && !config.getBackupDirName().isBlank()) {
            baseName = config.getBackupDirName();
        } else {
            baseName = LocalDate.now().format(DATE_FMT) + "_" + config.getOperator();
        }
        String backupDirName = resolveBackupDirName(backupParent, baseName);
        String backupDir = backupParent + backupDirName + "/";
        ops.mkdirIfAbsent(backupDir);

        // 4. 按目标在 systemRoot 下的相对路径镜像到备份目录，避免同名包互相覆盖
        String relativeFromRoot = target.getRemotePath().startsWith(systemRoot)
                ? target.getRemotePath().substring(systemRoot.length())
                : target.getPackageName();
        String backupFilePath = backupDir + relativeFromRoot;

        // 5. 创建备份目录下的中间层级
        int lastSlash = backupFilePath.lastIndexOf('/');
        if (lastSlash > 0) {
            ops.mkdirs(backupFilePath.substring(0, lastSlash));
        }

        // 6. "1 下 + 2 上"优化：
        //    优先复用前序门禁（如 StagingPackageBuilder）已下载的本地原包字节，跳过远端下载；
        //    缺失时回退到下载到本地 temp，并保留供后续门禁复用。
        //    本地 temp 的最终清理由流水线收尾统一处理（DeployPipeline finally 阶段）。
        Path reusable = target.getLocalOriginalCopy();
        boolean reusedExistingCopy = reusable != null
                && Files.isRegularFile(reusable) && Files.size(reusable) > 0;

        Path tempBackup;
        long downloadedSize;
        boolean handedOff = false;
        if (reusedExistingCopy) {
            tempBackup = reusable;
            downloadedSize = Files.size(tempBackup);
        } else {
            tempBackup = Files.createTempFile("backup-", "-" + target.getPackageName());
            try {
                downloadedSize = ops.download(target.getRemotePath(), tempBackup);
            } catch (IOException | RuntimeException e) {
                Files.deleteIfExists(tempBackup);
                throw e;
            }
        }
        try {
            ops.upload(tempBackup, backupFilePath);

            // 5. 验证备份大小
            long backupSize = ops.getFileSize(backupFilePath);
            if (backupSize != downloadedSize) {
                throw new GateException(name(),
                        "备份大小不一致: 下载 " + downloadedSize + " 字节, 备份 " + backupSize + " 字节");
            }

            target.setBackupRemotePath(backupFilePath);
            target.setLocalOriginalCopy(tempBackup);
            target.setStatus(TargetPackage.Status.BACKED_UP);
            handedOff = true;

            System.out.println("  [备份] " + target.getPackageName()
                    + " → " + backupFilePath + " (" + formatSize(backupSize) + ")"
                    + (reusedExistingCopy ? "（复用 staging 阶段本地副本）" : ""));

        } finally {
            // 异常路径：本次自己下载的 temp 未交付 → 删除避免泄漏；
            // 复用前序门禁的副本 → 由流水线收尾统一删，本方法不删。
            if (!handedOff && !reusedExistingCopy) {
                Files.deleteIfExists(tempBackup);
            }
        }
    }

    /**
     * 从 remoteDir 解析子系统根目录
     *
     * <p>remoteDir 可能是子系统根本身（如 /开发/客户/系统/），
     * 也可能是更深的子目录（如 /开发/客户/系统/shared-tms/）。
     * 子系统根固定为第 3 级目录。</p>
     *
     * @param remoteDir 远程目录路径
     * @return 子系统根目录路径（以 / 结尾）
     * @author xumanyi
     * @date 2026-03-26
     */
    private static String resolveSystemRoot(String remoteDir) {
        String trimmed = remoteDir.replaceAll("^/+", "").replaceAll("/+$", "");
        String[] parts = trimmed.split("/");
        // 取前 3 级作为子系统根
        if (parts.length >= 3) {
            return "/" + parts[0] + "/" + parts[1] + "/" + parts[2] + "/";
        }
        // 不足 3 级时直接用（不应该走到这里，前面有校验）
        return "/" + trimmed + "/";
    }

    /**
     * 确定备份目录名（复用当天同操作人的目录，已存在的备份包会被覆盖）
     *
     * @param backupParent backup/ 父目录路径
     * @param baseName     基础目录名（YYYYMMDD_operator）
     * @return 备份目录名
     * @author xumanyi
     * @date 2026-03-31
     */
    private String resolveBackupDirName(String backupParent, String baseName) throws IOException {
        return baseName;
    }

    /**
     * 检查目录列表中是否存在指定名称的目录
     *
     * @param files   文件列表
     * @param dirName 目录名
     * @return 存在返回 true
     * @author xumanyi
     * @date 2026-03-26
     */
    private static boolean dirExists(List<FTPFile> files, String dirName) {
        for (FTPFile f : files) {
            if (f.isDirectory() && dirName.equals(f.getName())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 将字节数格式化为可读的大小字符串
     *
     * @param bytes 字节数
     * @return 可读的大小字符串，如 "1.5 MB"
     * @author xumanyi
     * @date 2026-03-26
     */
    private static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
    }
}
