package com.flux.deploy.plugin.service;

import com.flux.deploy.ftp.FtpOperations;
import com.flux.deploy.ftp.FtpSession;
import org.apache.commons.net.ftp.FTPFile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Calendar;
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
    /**
     * 最大扫描目录数（= FTP LIST 调用次数）：兜底防止 FTP 结构意外庞大导致卡死。
     *
     * <p>只统计目录、不统计普通文件：文件不产生 FTP 调用，数量再多也不该占用配额——
     * 曾按"条目数"计数时，静态资源部署目录（如 Vue 前端解包后的数千个小文件）会把配额
     * 耗尽，导致同级中字母序靠后的目录整个不被扫描、在目标包树上凭空消失。</p>
     */
    private static final int MAX_SCAN_DIRS = 10000;

    /**
     * 扫描系统目录下的可部署包（结构化数据，递归）
     *
     * <p>递归遍历系统目录及其子目录，收集所有 JAR/WAR 文件。备份目录（名称命中
     * {@link FtpOperations#BACKUP_DIR_NAMES}）也会被递归并展示，但其中的包会被标记
     * {@link PackageInfo#isFromBackup()}，由调用方决定是否默认勾选；只有 {@code .flux-lock}
     * 这类锁控制目录会被直接跳过——它不含部署包，递归只会浪费 FTP 调用。
     * 最大递归深度 {@value #MAX_SCAN_DEPTH} 层，最多扫描 {@value #MAX_SCAN_DIRS} 个目录。</p>
     *
     * @param systemPath 系统目录路径（如 /开发/项目名/系统名/）
     * @return 按相对路径排序的包信息列表
     * @throws IOException FTP 操作失败时抛出
     * @author xumanyi
     * @date 2026-04-19
     */
    public List<PackageInfo> scanPackagesStructured(String systemPath) throws IOException {
        return scanPackagesStructured(systemPath, MAX_SCAN_DEPTH);
    }

    /**
     * 扫描指定目录下的可部署包（结构化数据，递归深度可控）
     *
     * <p>与 {@link #scanPackagesStructured(String)} 行为一致，但允许调用方收窄递归深度：
     * 传 {@code maxDepth=0} 时只扫描 {@code path} 这一层的直接文件、不下钻任何子目录。
     * 用于「项目根目录下直接放包」的场景——只取根层包，而不会把各系统子目录里的包一并递归出来。</p>
     *
     * @param systemPath 目录路径（如 /开发/项目名/ 或 /开发/项目名/系统名/）
     * @param maxDepth   最大递归深度（0 = 只扫当前层；上限 {@value #MAX_SCAN_DEPTH}）
     * @return 按相对路径排序的包信息列表
     * @throws IOException FTP 操作失败时抛出
     * @author xumanyi
     * @date 2026-06-02
     */
    public List<PackageInfo> scanPackagesStructured(String systemPath, int maxDepth) throws IOException {
        return scanStructured(systemPath, maxDepth).getPackages();
    }

    /**
     * 扫描系统目录下的可部署包与目录结构（结构化数据，递归）
     *
     * <p>与 {@link #scanPackagesStructured(String)} 同一次遍历，但额外返回途经的目录，
     * 供调用方在按类型/名称过滤掉包之后仍能如实还原 FTP 上的目录结构。</p>
     *
     * @param systemPath 系统目录路径（如 /开发/项目名/系统名/）
     * @return 扫描结果（包 + 目录）
     * @throws IOException FTP 操作失败时抛出
     * @author xumanyi
     * @date 2026-07-15
     */
    public ScanResult scanStructured(String systemPath) throws IOException {
        return scanStructured(systemPath, MAX_SCAN_DEPTH);
    }

    /**
     * 扫描指定目录下的可部署包与目录结构（结构化数据，递归深度可控）
     *
     * <p>包的收集规则与 {@link #scanPackagesStructured(String, int)} 完全一致；目录则记录
     * 递归<i>实际进入</i>的每一层，因此与包共享同一套边界——被 {@code maxDepth} 挡在外面、
     * 被 {@code .flux-lock} 跳过、或超出 {@value #MAX_SCAN_DIRS} 目录数上限的子树，
     * 其目录同样不会出现。{@code maxDepth=0} 时不下钻任何子目录，目录列表恒为空。</p>
     *
     * @param systemPath 目录路径（如 /开发/项目名/ 或 /开发/项目名/系统名/）
     * @param maxDepth   最大递归深度（0 = 只扫当前层；上限 {@value #MAX_SCAN_DEPTH}）
     * @return 扫描结果（包按相对路径排序，目录按路径排序，均忽略大小写）
     * @throws IOException FTP 操作失败时抛出
     * @author xumanyi
     * @date 2026-07-15
     */
    public ScanResult scanStructured(String systemPath, int maxDepth) throws IOException {
        List<PackageInfo> packages = new ArrayList<>();
        List<String> directories = new ArrayList<>();
        int[] counter = {0};
        // 当 UI 已经把扫描根下钻到备份子树（例如用户在"子目录"筛选里手动选了 backup/），
        // 递归本身从 insideBackup=false 起步会漏判。这里先看 scanPath 自身路径段，
        // 命中 BACKUP_DIR_NAMES 即把递归初始状态置为 true，让所有结果都按"来自备份目录"标记。
        boolean rootInsideBackup = pathContainsBackupSegment(systemPath);
        scanRecursive(systemPath, "", packages, directories, counter, 0, rootInsideBackup, maxDepth);
        packages.sort((a, b) -> a.relativePath.compareToIgnoreCase(b.relativePath));
        directories.sort(String::compareToIgnoreCase);
        return new ScanResult(packages, directories);
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
     * 列出指定层级目录的直接内容（单次 FTP LIST）：该层可部署包 + 该层子目录
     *
     * <p>为目标包树的按层懒加载服务：选择系统时只列根层，用户展开某个目录时再列该目录，
     * 每次仅一趟 FTP 往返、不递归下钻，代替全量递归扫描。坐标约定与 {@link #scanStructured}
     * 一致——包的 {@link PackageInfo#getSubDirectory()} 与返回的目录路径均相对 {@code scanRoot}。
     * 备份判定同样兼容：{@code scanRoot} 或 {@code relativeDir} 任一路径段命中备份目录名，
     * 该层所有包都标记 {@link PackageInfo#isFromBackup()}。</p>
     *
     * @param scanRoot    扫描根路径（以 / 结尾，如 /开发/项目名/系统名/）
     * @param relativeDir 要列出的层级（相对扫描根，多层用 / 连接；空串 = 扫描根自身）
     * @return 该层扫描结果：包按相对路径排序；目录为 {@code relativeDir/名} 形式（根层即目录名），
     *         已排除 {@code .flux-lock} 锁控制目录
     * @throws IOException FTP 操作失败时抛出
     * @author xumanyi
     * @date 2026-08-04
     */
    public ScanResult listLevel(String scanRoot, String relativeDir) throws IOException {
        String abs = relativeDir.isEmpty() ? scanRoot : scanRoot + relativeDir + "/";
        boolean insideBackup = pathContainsBackupSegment(scanRoot)
                || pathContainsBackupSegment(relativeDir);
        List<PackageInfo> packages = new ArrayList<>();
        List<String> directories = new ArrayList<>();
        List<PackageInfo> plainFiles = new ArrayList<>();
        for (FTPFile entry : ops.listFiles(abs)) {
            String name = entry.getName();
            if (".".equals(name) || "..".equals(name)) continue;
            // 插件自用的中转产物（上传临时文件 / 影子目录）不进目标树：
            // 它们只在一次更新的中途存在，展示出来只会让人以为远端多了奇怪的东西
            if (com.flux.deploy.ftp.FtpOperations.isTransientArtifactName(name)) continue;
            if (entry.isFile()) {
                String subForDisplay = relativeDir.isEmpty() ? "." : relativeDir;
                String relPath = relativeDir.isEmpty() ? name : relativeDir + "/" + name;
                // FTP LIST 解析出的时间戳可能为 null（服务器未在列表里返回时间）；null 记 0 表示未知
                Calendar ts = entry.getTimestamp();
                long modifiedTime = ts != null ? ts.getTimeInMillis() : 0L;
                if (isDeployable(name)) {
                    packages.add(new PackageInfo(subForDisplay, name, typeOf(name), relPath,
                            entry.getSize(), insideBackup, modifiedTime));
                } else {
                    // 非部署普通文件（js/css/json 等）也带回：Vue 解包目录里模块内容就是
                    // 这类文件，不带回的话"只有文件的模块目录"会显示成空目录
                    plainFiles.add(new PackageInfo(subForDisplay, name, "FILE", relPath,
                            entry.getSize(), insideBackup, modifiedTime));
                }
            } else if (entry.isDirectory() && !LOCK_CONTROL_DIR.equals(name)) {
                directories.add(relativeDir.isEmpty() ? name : relativeDir + "/" + name);
            }
        }
        packages.sort((a, b) -> a.relativePath.compareToIgnoreCase(b.relativePath));
        directories.sort(String::compareToIgnoreCase);
        plainFiles.sort((a, b) -> a.relativePath.compareToIgnoreCase(b.relativePath));
        return new ScanResult(packages, directories, plainFiles);
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
     * @param directories    累积途经目录（相对系统根，扫描根自身不计入）
     * @param counter        已扫描目录计数器（单元素数组作引用；只计目录，普通文件不计）
     * @param depth          当前递归深度（0 = 系统根）
     * @param insideBackup   当前路径是否已经位于某个备份目录之下
     * @param maxDepth       最大递归深度（0 = 只扫当前层，不下钻子目录）
     */
    private void scanRecursive(String absolutePath, String relativeSubDir,
                                List<PackageInfo> packages, List<String> directories,
                                int[] counter, int depth,
                                boolean insideBackup, int maxDepth) throws IOException {
        if (depth > maxDepth) return;
        if (counter[0] >= MAX_SCAN_DIRS) return;
        // 本目录即将 LIST，消耗一份目录配额；普通文件不计数（不产生 FTP 调用）
        counter[0]++;

        // 在深度/目录数上限之后登记：目录与包共享同一套扫描边界，不会出现"目录列出来了但里面根本没扫"。
        // relativeSubDir 为空即扫描根自身，它不是"子目录"，不登记。
        if (!relativeSubDir.isEmpty()) directories.add(relativeSubDir);

        List<FTPFile> entries = ops.listFiles(absolutePath);
        for (FTPFile entry : entries) {
            String name = entry.getName();
            if (".".equals(name) || "..".equals(name)) continue;

            if (entry.isFile() && isDeployable(name)) {
                String type = typeOf(name);
                String subForDisplay = relativeSubDir.isEmpty() ? "." : relativeSubDir;
                String relPath = relativeSubDir.isEmpty()
                        ? name : relativeSubDir + "/" + name;
                // FTP LIST 解析出的时间戳可能为 null（服务器未在列表里返回时间）；null 记 0 表示未知
                Calendar ts = entry.getTimestamp();
                long modifiedTime = ts != null ? ts.getTimeInMillis() : 0L;
                packages.add(new PackageInfo(subForDisplay, name, type, relPath,
                        entry.getSize(), insideBackup, modifiedTime));
            } else if (entry.isDirectory() && !LOCK_CONTROL_DIR.equals(name)) {
                String nextRel = relativeSubDir.isEmpty() ? name : relativeSubDir + "/" + name;
                String nextAbs = absolutePath + name + "/";
                boolean nextInsideBackup = insideBackup
                        || FtpOperations.BACKUP_DIR_NAMES.contains(name);
                scanRecursive(nextAbs, nextRel, packages, directories, counter, depth + 1,
                        nextInsideBackup, maxDepth);
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
     * 远端文件信息（Vue 模块目录合并视图用：任意类型文件，非"可部署包"）
     *
     * @author xumanyi
     * @date 2026-08-13
     */
    public static class RemoteFileInfo {
        /** 相对起始目录的路径（/ 分隔） */
        public final String relativePath;
        /** 文件字节数 */
        public final long size;
        /** 最后修改时间（epoch 毫秒；FTP 未给出时为 0） */
        public final long modifiedTime;

        /**
         * 构造远端文件信息
         *
         * @param relativePath 相对路径
         * @param size         字节数
         * @param modifiedTime 修改时间（epoch 毫秒）
         * @author xumanyi
         * @date 2026-08-13
         */
        public RemoteFileInfo(String relativePath, long size, long modifiedTime) {
            this.relativePath = relativePath;
            this.size = size;
            this.modifiedTime = modifiedTime;
        }
    }

    /**
     * 递归列出目录下全部文件（不做 jar/war/zip 过滤，Vue 模块目录合并视图用）
     *
     * @param absDir   远端目录绝对路径（以 / 结尾）
     * @param maxDepth 最大递归深度（相对 absDir，0 = 只列当前层）
     * @return 文件列表（相对路径 / 大小 / 修改时间），按相对路径排序
     * @throws IOException FTP 操作失败
     * @author xumanyi
     * @date 2026-08-13
     */
    public List<RemoteFileInfo> listAllFilesRecursive(String absDir, int maxDepth) throws IOException {
        List<RemoteFileInfo> result = new ArrayList<>();
        listAllFilesRecursive(absDir, "", result, 0, maxDepth);
        result.sort((a, b) -> a.relativePath.compareToIgnoreCase(b.relativePath));
        return result;
    }

    /**
     * {@link #listAllFilesRecursive(String, int)} 的递归实现
     *
     * @param absDir    当前目录绝对路径（以 / 结尾）
     * @param relPrefix 相对起始目录的前缀
     * @param result    结果收集（超过 5000 个文件停止，防失控目录）
     * @param depth     当前深度
     * @param maxDepth  最大深度
     * @throws IOException FTP 操作失败
     * @author xumanyi
     * @date 2026-08-13
     */
    private void listAllFilesRecursive(String absDir, String relPrefix,
            List<RemoteFileInfo> result, int depth, int maxDepth) throws IOException {
        if (depth > maxDepth || result.size() > 5000) return;
        // 部分 FTP 服务端对带尾斜杠的 LIST 返回空，统一剥掉（与 note 审计的口径一致）
        String listPath = absDir.length() > 1 && absDir.endsWith("/")
                ? absDir.substring(0, absDir.length() - 1) : absDir;
        for (FTPFile entry : ops.listFiles(listPath)) {
            if (entry == null) continue;
            String name = entry.getName();
            if (".".equals(name) || "..".equals(name)) continue;
            if (entry.isFile()) {
                Calendar ts = entry.getTimestamp();
                result.add(new RemoteFileInfo(relPrefix + name, entry.getSize(),
                        ts != null ? ts.getTimeInMillis() : 0L));
            } else if (entry.isDirectory()) {
                listAllFilesRecursive(absDir + name + "/", relPrefix + name + "/",
                        result, depth + 1, maxDepth);
            }
        }
    }

    /**
     * 判断文件是否为可部署的包文件（.jar / .war / .zip）
     *
     * <p>.zip 自 Vue 前端支持起纳入：Vue 模块更新包（如 {@code tm01webVue_t0107.zip}）
     * 以 zip 形态放在客服交换目录，与 jar/war 走同一条单文件部署链路。</p>
     *
     * @param fileName 文件名
     * @return 文件名以 .jar / .war / .zip 结尾时返回 true
     * @author xumanyi
     * @date 2026-03-27
     */
    private boolean isDeployable(String fileName) {
        String lower = fileName.toLowerCase();
        return lower.endsWith(".jar") || lower.endsWith(".war") || lower.endsWith(".zip");
    }

    /**
     * 按文件扩展名推导包类型标识
     *
     * @param fileName 文件名
     * @return "WAR" / "ZIP" / "JAR"（兜底）
     * @author xumanyi
     * @date 2026-08-13
     */
    private static String typeOf(String fileName) {
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".war")) return "WAR";
        if (lower.endsWith(".zip")) return "ZIP";
        return "JAR";
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
     * 扫描结果数据类：可部署包 + 目录结构
     *
     * <p>把"包"和"目录"分成两份返回，是因为调用方（目标包树）会按源产物类型和文件名过滤包，
     * 若目录只能从包反推，被过滤空的目录就会整个消失，用户会误以为 FTP 上没有这个目录。
     * 目录独立成一等数据后，过滤只影响包，目录始终如实反映 FTP 真实结构。</p>
     *
     * @author xumanyi
     * @date 2026-07-15
     */
    public static class ScanResult {
        private final List<PackageInfo> packages;
        private final List<String> directories;
        private final List<PackageInfo> plainFiles;

        /**
         * 构造扫描结果
         *
         * @param packages    可部署包列表
         * @param directories 目录列表（相对扫描根，如 shared-tms、backup/20260502_xumanyi）
         * @author xumanyi
         * @date 2026-07-15
         */
        public ScanResult(List<PackageInfo> packages, List<String> directories) {
            this(packages, directories, List.of());
        }

        /**
         * 构造扫描结果（带非部署普通文件清单）
         *
         * @param packages    可部署包列表
         * @param directories 目录列表（相对扫描根）
         * @param plainFiles  该层的非部署普通文件（js/css/json 等，type=FILE），供 UI 只读展示
         * @author xumanyi
         * @date 2026-08-14
         */
        public ScanResult(List<PackageInfo> packages, List<String> directories,
                          List<PackageInfo> plainFiles) {
            this.packages = packages;
            this.directories = directories;
            this.plainFiles = plainFiles;
        }

        /**
         * 获取该层的非部署普通文件清单
         *
         * <p>仅 {@link #listLevel} 填充（按层懒加载浏览用）；递归扫描等其他入口为空列表。
         * 坐标系与包一致（{@link PackageInfo#getRelativePath()} 相对扫描根）。</p>
         *
         * @return 普通文件列表（type=FILE，按相对路径排序）；无则为空列表
         * @author xumanyi
         * @date 2026-08-14
         */
        public List<PackageInfo> getPlainFiles() { return plainFiles; }

        /**
         * 获取可部署包列表
         *
         * @return 可部署包列表（按相对路径排序）
         * @author xumanyi
         * @date 2026-07-15
         */
        public List<PackageInfo> getPackages() { return packages; }

        /**
         * 获取目录列表
         *
         * <p>每项是相对扫描根的目录路径（多层用 / 连接），与 {@link PackageInfo#getSubDirectory()}
         * 同一坐标系，调用方可直接拿来建树。不含扫描根自身。</p>
         *
         * @return 目录列表（按路径排序，忽略大小写）
         * @author xumanyi
         * @date 2026-07-15
         */
        public List<String> getDirectories() { return directories; }
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
        private final String type; // JAR / WAR / ZIP
        private final String relativePath;
        private final long size;
        // 该包是否位于备份目录（backup/backups/bak/.backup）子树下；UI 据此默认不勾选
        private final boolean fromBackup;
        // 文件最后修改时间（epoch 毫秒）；0 表示未知（FTP 列表未给出时间戳）
        private final long modifiedTime;

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
            this(subDirectory, packageName, type, relativePath, size, false, 0L);
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
         * @param modifiedTime 文件最后修改时间（epoch 毫秒）；0 表示未知
         * @author xumanyi
         * @date 2026-05-02
         */
        public PackageInfo(String subDirectory, String packageName, String type,
                            String relativePath, long size, boolean fromBackup, long modifiedTime) {
            this.subDirectory = subDirectory;
            this.packageName = packageName;
            this.type = type;
            this.relativePath = relativePath;
            this.size = size;
            this.fromBackup = fromBackup;
            this.modifiedTime = modifiedTime;
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

        /**
         * 获取文件最后修改时间
         *
         * @return 最后修改时间（epoch 毫秒）；0 表示未知（FTP 列表未给出时间戳）
         * @author xumanyi
         * @date 2026-05-28
         */
        public long getModifiedTime() { return modifiedTime; }
    }
}
