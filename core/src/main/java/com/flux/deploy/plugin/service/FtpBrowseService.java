package com.flux.deploy.plugin.service;

import com.flux.deploy.ftp.FtpOperations;
import com.flux.deploy.ftp.FtpSession;
import org.apache.commons.net.ftp.FTPFile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * FTP 目录浏览服务
 *
 * <p>封装 FTP 连接和目录浏览操作，提供列出子目录、扫描可部署包等功能。
 * 用于目标面板中的项目/系统/包列表加载。</p>
 *
 * @author xumanyi
 * @date 2026-03-27
 */
public class FtpBrowseService {

    private final FtpSession session;
    private final FtpOperations ops;

    /**
     * 创建 FTP 浏览服务并建立连接
     *
     * @param host     FTP 主机地址
     * @param port     FTP 端口
     * @param username FTP 用户名
     * @param password FTP 密码
     * @throws IOException 连接失败时抛出
     * @author xumanyi
     * @date 2026-03-27
     */
    public FtpBrowseService(String host, int port, String username, String password) throws IOException {
        this.session = new FtpSession(host, port);
        session.connect(username, password);
        this.ops = new FtpOperations(session);
    }

    /**
     * 列出指定目录下的子目录名
     *
     * @param remotePath 远程目录路径
     * @return 子目录名列表（按名称忽略大小写排序）
     * @throws IOException FTP 操作失败时抛出
     * @author xumanyi
     * @date 2026-03-27
     */
    public List<String> listSubdirectories(String remotePath) throws IOException {
        List<FTPFile> files = ops.listFiles(remotePath);
        List<String> dirs = new ArrayList<>();
        for (FTPFile f : files) {
            if (f.isDirectory()) {
                dirs.add(f.getName());
            }
        }
        dirs.sort(String::compareToIgnoreCase);
        return dirs;
    }

    /** 最大递归深度（从系统根算起）：避免误扫无关的大型子树 */
    private static final int MAX_SCAN_DEPTH = 5;
    /** 最大条目数：兜底防止 FTP 结构意外庞大导致卡死 */
    private static final int MAX_SCAN_ENTRIES = 10000;

    /**
     * 扫描系统目录下的可部署包（结构化数据，递归）
     *
     * <p>递归遍历系统目录及其子目录，收集所有 JAR/WAR 文件。备份目录（名称命中
     * {@link FtpOperations#BACKUP_DIR_NAMES}）也会被递归并展示，但其中的包会被标记
     * {@link PackageInfo#isFromBackup()}，由调用方决定是否默认勾选；只有 {@code .flux-lock}
     * 这类锁控制目录会被直接跳过——它不含部署包，递归只会浪费 FTP 调用。
     * 最大递归深度 {@value #MAX_SCAN_DEPTH} 层，最多扫描 {@value #MAX_SCAN_ENTRIES} 个条目。</p>
     *
     * @param systemPath 系统目录路径（如 /开发/项目名/系统名/）
     * @return 按相对路径排序的包信息列表
     * @throws IOException FTP 操作失败时抛出
     * @author xumanyi
     * @date 2026-04-19
     */
    public List<PackageInfo> scanPackagesStructured(String systemPath) throws IOException {
        List<PackageInfo> packages = new ArrayList<>();
        int[] counter = {0};
        // 当 UI 已经把扫描根下钻到备份子树（例如用户在"子目录"筛选里手动选了 backup/），
        // 递归本身从 insideBackup=false 起步会漏判。这里先看 scanPath 自身路径段，
        // 命中 BACKUP_DIR_NAMES 即把递归初始状态置为 true，让所有结果都按"来自备份目录"标记。
        boolean rootInsideBackup = pathContainsBackupSegment(systemPath);
        scanRecursive(systemPath, "", packages, counter, 0, rootInsideBackup);
        packages.sort((a, b) -> a.relativePath.compareToIgnoreCase(b.relativePath));
        return packages;
    }

    /**
     * 判断给定 FTP 路径中是否存在命中 {@link FtpOperations#BACKUP_DIR_NAMES} 的路径段。
     *
     * <p>用于识别"扫描根本身就在备份子树下"的场景——此时递归自身的 insideBackup 入参
     * 没法回溯，需要通过路径字符串补足判断。</p>
     *
     * @param path FTP 绝对路径（任意是否以 / 开头/结尾）
     * @return 任一路径段（按 / 切分后的非空段）命中备份目录名时为 true
     * @author xumanyi
     * @date 2026-05-02
     */
    private static boolean pathContainsBackupSegment(String path) {
        if (path == null || path.isEmpty()) return false;
        for (String segment : path.split("/")) {
            if (!segment.isEmpty() && FtpOperations.BACKUP_DIR_NAMES.contains(segment)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 递归扫描子目录内的可部署包
     *
     * <p>遇到名字命中 {@link FtpOperations#BACKUP_DIR_NAMES} 的目录会继续向下递归，但所有
     * 在备份子树中找到的包都会被标记为 {@code fromBackup=true}，让 UI 默认不勾选；
     * {@code .flux-lock} 仅含锁标记文件，不会产出 jar/war，直接跳过以节约 FTP 调用。</p>
     *
     * @param absolutePath   当前目录的完整 FTP 路径（以 / 结尾）
     * @param relativeSubDir 相对系统根的子目录（如 {@code ""} / {@code "shared-tms"} / {@code "shared-tms/v2"}）
     * @param packages       累积结果
     * @param counter        已扫描条目计数器（单元素数组作引用）
     * @param depth          当前递归深度（0 = 系统根）
     * @param insideBackup   当前路径是否已经位于某个备份目录之下
     */
    private void scanRecursive(String absolutePath, String relativeSubDir,
                                List<PackageInfo> packages, int[] counter, int depth,
                                boolean insideBackup) throws IOException {
        if (depth > MAX_SCAN_DEPTH) return;
        if (counter[0] >= MAX_SCAN_ENTRIES) return;

        List<FTPFile> entries = ops.listFiles(absolutePath);
        for (FTPFile entry : entries) {
            if (counter[0] >= MAX_SCAN_ENTRIES) break;
            counter[0]++;
            String name = entry.getName();
            if (".".equals(name) || "..".equals(name)) continue;

            if (entry.isFile() && isDeployable(name)) {
                String type = name.toLowerCase().endsWith(".war") ? "WAR" : "JAR";
                String subForDisplay = relativeSubDir.isEmpty() ? "." : relativeSubDir;
                String relPath = relativeSubDir.isEmpty()
                        ? name : relativeSubDir + "/" + name;
                packages.add(new PackageInfo(subForDisplay, name, type, relPath,
                        entry.getSize(), insideBackup));
            } else if (entry.isDirectory() && !LOCK_CONTROL_DIR.equals(name)) {
                String nextRel = relativeSubDir.isEmpty() ? name : relativeSubDir + "/" + name;
                String nextAbs = absolutePath + name + "/";
                boolean nextInsideBackup = insideBackup
                        || FtpOperations.BACKUP_DIR_NAMES.contains(name);
                scanRecursive(nextAbs, nextRel, packages, counter, depth + 1, nextInsideBackup);
            }
        }
    }

    /**
     * 锁控制目录名。该目录由 CLI 写入 {@code __LOCK__} 标记文件，不含可部署包，
     * UI 浏览时跳过递归即可，不在备份目录展示策略之内。
     */
    private static final String LOCK_CONTROL_DIR = ".flux-lock";

    /**
     * 扫描系统目录下的可部署包（兼容旧接口）
     *
     * @param systemPath 系统目录路径
     * @return 包的相对路径列表
     * @throws IOException FTP 操作失败时抛出
     * @author xumanyi
     * @date 2026-03-27
     */
    public List<String> scanPackages(String systemPath) throws IOException {
        List<PackageInfo> structured = scanPackagesStructured(systemPath);
        List<String> result = new ArrayList<>();
        for (PackageInfo p : structured) {
            result.add(p.relativePath);
        }
        return result;
    }

    /**
     * 判断文件是否为可部署的包文件（.jar 或 .war）
     *
     * @param fileName 文件名
     * @return 文件名以 .jar 或 .war 结尾时返回 true
     * @author xumanyi
     * @date 2026-03-27
     */
    private boolean isDeployable(String fileName) {
        String lower = fileName.toLowerCase();
        return lower.endsWith(".jar") || lower.endsWith(".war");
    }

    /**
     * 判断 FTP 连接是否有效
     *
     * @return FTP 连接有效时返回 true
     * @author xumanyi
     * @date 2026-03-27
     */
    public boolean isConnected() { return session.isConnected(); }

    /**
     * 断开 FTP 连接
     *
     * @author xumanyi
     * @date 2026-03-27
     */
    public void disconnect() {
        try { session.close(); } catch (IOException ignored) {}
    }

    /**
     * 包信息数据类
     *
     * <p>封装 FTP 上一个可部署包的元信息，包括所在子目录、包名、类型、相对路径和大小。</p>
     *
     * @author xumanyi
     * @date 2026-03-27
     */
    public static class PackageInfo {
        private final String subDirectory;
        private final String packageName;
        private final String type; // JAR or WAR
        private final String relativePath;
        private final long size;
        // 该包是否位于备份目录（backup/backups/bak/.backup）子树下；UI 据此默认不勾选
        private final boolean fromBackup;

        /**
         * 构造包信息（兼容旧签名，默认非备份目录）
         *
         * @param subDirectory 所在子目录名
         * @param packageName  包文件名
         * @param type         包类型（JAR 或 WAR）
         * @param relativePath 相对于系统目录的路径
         * @param size         文件大小（字节）
         * @author xumanyi
         * @date 2026-03-27
         */
        public PackageInfo(String subDirectory, String packageName, String type,
                            String relativePath, long size) {
            this(subDirectory, packageName, type, relativePath, size, false);
        }

        /**
         * 构造包信息
         *
         * @param subDirectory 所在子目录名
         * @param packageName  包文件名
         * @param type         包类型（JAR 或 WAR）
         * @param relativePath 相对于系统目录的路径
         * @param size         文件大小（字节）
         * @param fromBackup   是否来自备份目录子树（true 时 UI 默认不勾选该包）
         * @author xumanyi
         * @date 2026-05-02
         */
        public PackageInfo(String subDirectory, String packageName, String type,
                            String relativePath, long size, boolean fromBackup) {
            this.subDirectory = subDirectory;
            this.packageName = packageName;
            this.type = type;
            this.relativePath = relativePath;
            this.size = size;
            this.fromBackup = fromBackup;
        }

        /**
         * 获取所在子目录名
         *
         * @return 所在子目录名
         * @author xumanyi
         * @date 2026-03-27
         */
        public String getSubDirectory() { return subDirectory; }

        /**
         * 获取包文件名
         *
         * @return 包文件名
         * @author xumanyi
         * @date 2026-03-27
         */
        public String getPackageName() { return packageName; }

        /**
         * 获取包类型
         *
         * @return 包类型（JAR 或 WAR）
         * @author xumanyi
         * @date 2026-03-27
         */
        public String getType() { return type; }

        /**
         * 获取相对于系统目录的路径
         *
         * @return 相对于系统目录的路径
         * @author xumanyi
         * @date 2026-03-27
         */
        public String getRelativePath() { return relativePath; }

        /**
         * 获取文件大小
         *
         * @return 文件大小（字节）
         * @author xumanyi
         * @date 2026-03-27
         */
        public long getSize() { return size; }

        /**
         * 是否位于备份目录子树下
         *
         * <p>用于 UI 区分"备份遗留包"与"现役包"：UI 仍然展示备份目录中的包以保证透明度，
         * 但默认不勾选，避免误把备份目录里的旧包当作部署目标。</p>
         *
         * @return 任一祖先目录命中 {@link FtpOperations#BACKUP_DIR_NAMES} 时为 true
         * @author xumanyi
         * @date 2026-05-02
         */
        public boolean isFromBackup() { return fromBackup; }
    }
}
