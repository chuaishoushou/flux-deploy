package com.flux.deploy.email;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 部署历史缓存（in-memory only）—— 给「通知邮件」弹窗的"导入"按钮提供数据来源
 *
 * <p>每次"打包并上传成功"后，由主面板把本次部署的目标列表（完整 FTP 路径）+ 备份目录追加到这里。
 * 邮件弹窗的"导入"按钮按当前 FTP 项目目录过滤本缓存，把命中的所有包路径合并去重后写入模板的
 * {@code ${更新包}}，备份目录写入 {@code ${备份包}}。</p>
 *
 * <p><b>生命周期</b>：</p>
 * <ul>
 *   <li>累积：每次部署成功 → {@link #recordDeploy}</li>
 *   <li>清空：主面板"重置"按钮 → {@link #clear}</li>
 *   <li>IDE 重启 → 本类不持久化，自然丢失</li>
 * </ul>
 *
 * <p><b>粒度</b>：一次部署 = 多个包 + 一个备份目录（同次部署所有包共享该目录）。</p>
 *
 * @author xumanyi
 * @date 2026-05-18
 */
public final class DeployHistoryCache {

    /** 单条部署记录（一次"打包并上传成功"产出一条） */
    public static final class Record {
        private final String projectDir;
        private final List<String> packageRemotePaths;
        private final String backupDir;

        public Record(String projectDir, List<String> packageRemotePaths, String backupDir) {
            this.projectDir = projectDir;
            this.packageRemotePaths = packageRemotePaths == null
                    ? List.of() : List.copyOf(packageRemotePaths);
            this.backupDir = backupDir;
        }

        public String getProjectDir() { return projectDir; }
        public List<String> getPackageRemotePaths() { return packageRemotePaths; }
        public String getBackupDir() { return backupDir; }
    }

    private final List<Record> records = new ArrayList<>();

    /**
     * 追加一条部署记录
     *
     * @param projectDir         项目目录（{@code TargetSectionPanel.getCurrentProjectDir}）
     * @param packageRemotePaths 本次部署所有目标包的完整 FTP 路径
     * @param backupDir          本次部署的备份目录（备份未启用时可为 null）
     * @author xumanyi
     * @date 2026-05-18
     */
    public void recordDeploy(String projectDir, List<String> packageRemotePaths, String backupDir) {
        if (projectDir == null || projectDir.isBlank()) return;
        if (packageRemotePaths == null || packageRemotePaths.isEmpty()) return;
        records.add(new Record(projectDir, packageRemotePaths, backupDir));
    }

    /**
     * 清空缓存（主面板"重置"按钮回调）
     */
    public void clear() {
        records.clear();
    }

    /**
     * 取指定项目目录下的所有包路径（合并多次部署，按部署顺序去重）
     *
     * @param projectDir 项目目录
     * @return 去重后的包完整 FTP 路径列表（永不为 null）
     * @author xumanyi
     * @date 2026-05-18
     */
    public List<String> collectPackagePathsFor(String projectDir) {
        if (projectDir == null) return List.of();
        Set<String> seen = new LinkedHashSet<>();
        for (Record r : records) {
            if (!Objects.equals(r.getProjectDir(), projectDir)) continue;
            for (String p : r.getPackageRemotePaths()) {
                if (p != null && !p.isBlank()) seen.add(p);
            }
        }
        return new ArrayList<>(seen);
    }

    /**
     * 取指定项目目录下的所有备份目录（去重，按部署顺序）
     *
     * <p>多次部署一般落在同一备份目录（同一天 + 同一 operator），去重后通常只有一条。</p>
     *
     * @param projectDir 项目目录
     * @return 去重后的备份目录列表（永不为 null；null/空的 backupDir 自动过滤）
     * @author xumanyi
     * @date 2026-05-18
     */
    public List<String> collectBackupDirsFor(String projectDir) {
        if (projectDir == null) return List.of();
        Set<String> seen = new LinkedHashSet<>();
        for (Record r : records) {
            if (!Objects.equals(r.getProjectDir(), projectDir)) continue;
            String dir = r.getBackupDir();
            if (dir != null && !dir.isBlank()) seen.add(dir);
        }
        return new ArrayList<>(seen);
    }

    /**
     * 调试用：当前缓存内的记录条数
     */
    public int size() {
        return records.size();
    }
}
