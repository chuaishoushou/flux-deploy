package com.flux.deploy.ftp;

import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.net.ftp.FTPFile;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * FTP 原子操作集合
 *
 * <p>所有方法均为原子语义：成功或抛出异常，不会处于中间状态。</p>
 *
 * @author xumanyi
 * @date 2026-03-26
 */
public class FtpOperations {

    private final FtpSession session;

    /**
     * 默认进度日志出口：写标准输出（插件端由 LogInterceptor 捕获进部署日志，CLI 直接上屏）。
     *
     * <p>所有不带显式日志参数的 {@link #download(String, Path)} / {@link #upload(Path, String)}
     * 都走这里，大文件传输期间自动输出节流进度，防止用户把长传输误判为卡死；
     * 小文件受 {@link TransferProgress} 的门槛与节流约束，天然零输出不刷屏。</p>
     */
    private Consumer<String> progressLog = msg -> System.out.println("  " + msg);

    /**
     * 创建 FTP 操作实例
     *
     * @param session 已连接的 FTP 会话
     * @author xumanyi
     * @date 2026-03-26
     */
    public FtpOperations(FtpSession session) {
        this.session = session;
    }

    /**
     * 覆盖默认进度日志出口（传 null 关闭默认进度输出）
     *
     * @param progressLog 进度日志回调，null 表示静默
     * @author xumanyi
     * @date 2026-08-26
     */
    public void setProgressLog(Consumer<String> progressLog) {
        this.progressLog = progressLog;
    }

    /**
     * 列出远程目录下的文件
     *
     * @param remoteDirPath 远程目录路径
     * @return 文件列表
     * @author xumanyi
     * @date 2026-03-26
     */
    public List<FTPFile> listFiles(String remoteDirPath) throws IOException {
        FTPClient client = session.getClient();
        FTPFile[] files = client.listFiles(remoteDirPath);
        if (files == null) {
            return List.of();
        }
        List<FTPFile> result = new ArrayList<>();
        for (FTPFile f : files) {
            if (f != null && f.getName() != null
                    && !".".equals(f.getName()) && !"..".equals(f.getName())) {
                result.add(f);
            }
        }
        return result;
    }

    /**
     * 下载远程文件到本地路径
     *
     * @param remoteFilePath 远程文件完整路径
     * @param localPath      本地保存路径
     * @return 下载的字节数
     * @author xumanyi
     * @date 2026-03-26
     */
    public long download(String remoteFilePath, Path localPath) throws IOException {
        // 默认路径不额外 SIZE 远端（避免小文件场景多一次往返），总量未知时
        // 进度行降级为"已传 X，速率 Y/s"——足以说明没卡死
        return download(remoteFilePath, localPath,
                "[下载] " + displayName(remoteFilePath), progressLog, -1);
    }

    /**
     * 下载远程文件到本地路径（带进度日志）
     *
     * <p>提供 {@code log} 时先 SIZE 远端文件拿总量，再用 {@link TransferProgress}
     * 包装输出流：大文件传输期间每隔一段时间输出 已传/总量/速率/预计剩余；
     * 小文件（不足一个间隔）不产生任何进度行。</p>
     *
     * @param remoteFilePath 远程文件完整路径
     * @param localPath      本地保存路径
     * @param label          进度日志前缀（如 {@code [备份] 下载 xxx.war}），log 为 null 时忽略
     * @param log            进度日志回调，为 null 时行为与无日志重载完全一致
     * @return 下载的字节数
     * @author xumanyi
     * @date 2026-08-26
     */
    public long download(String remoteFilePath, Path localPath,
                         String label, Consumer<String> log) throws IOException {
        long total = log != null ? getFileSize(remoteFilePath) : -1;
        return download(remoteFilePath, localPath, label, log, total);
    }

    /**
     * 下载远程文件到本地路径（带进度日志，总量由调用方给定）
     *
     * @param remoteFilePath 远程文件完整路径
     * @param localPath      本地保存路径
     * @param label          进度日志前缀
     * @param log            进度日志回调，为 null 时不输出进度
     * @param total          远端文件总大小（字节），未知传 ≤0（进度行不含百分比）
     * @return 下载的字节数
     * @author xumanyi
     * @date 2026-08-26
     */
    private long download(String remoteFilePath, Path localPath,
                          String label, Consumer<String> log, long total) throws IOException {
        FTPClient client = session.getClient();
        Files.createDirectories(localPath.getParent());
        try (OutputStream out = TransferProgress.wrapDownload(
                new BufferedOutputStream(Files.newOutputStream(localPath)), total, label, log)) {
            boolean ok = client.retrieveFile(remoteFilePath, out);
            if (!ok) {
                Files.deleteIfExists(localPath);
                throw new IOException("下载失败: " + remoteFilePath
                        + " (响应: " + client.getReplyString().trim() + ")");
            }
        }
        return Files.size(localPath);
    }

    /**
     * 上传本地文件到远程路径
     *
     * @param localPath      本地文件路径
     * @param remoteFilePath 远程目标路径
     * @author xumanyi
     * @date 2026-03-26
     */
    public void upload(Path localPath, String remoteFilePath) throws IOException {
        // 上传总量取本地文件大小，零额外远端往返，默认即带完整百分比进度
        upload(localPath, remoteFilePath,
                "[上传] " + displayName(remoteFilePath), progressLog);
    }

    /**
     * 上传本地文件到远程路径（带进度日志）
     *
     * <p>提供 {@code log} 时用 {@link TransferProgress} 包装输入流：
     * 大文件传输期间每隔一段时间输出 已传/总量/速率/预计剩余。</p>
     *
     * @param localPath      本地文件路径
     * @param remoteFilePath 远程目标路径
     * @param label          进度日志前缀（如 {@code [备份] 上传 xxx.war}），log 为 null 时忽略
     * @param log            进度日志回调，为 null 时行为与无日志重载完全一致
     * @author xumanyi
     * @date 2026-08-26
     */
    public void upload(Path localPath, String remoteFilePath,
                       String label, Consumer<String> log) throws IOException {
        validateRemotePath(remoteFilePath);
        FTPClient client = session.getClient();
        try (InputStream in = TransferProgress.wrapUpload(
                new BufferedInputStream(Files.newInputStream(localPath)),
                0, Files.size(localPath), label, log)) {
            boolean ok = client.storeFile(remoteFilePath, in);
            if (!ok) {
                throw new IOException("上传失败: " + remoteFilePath
                        + " (响应: " + client.getReplyString().trim() + ")");
            }
        }
    }

    /** 上传中的临时文件后缀：传输完成并校验通过才 rename 为最终名 */
    public static final String UPLOADING_SUFFIX = ".__UPLOADING__";

    /** 影子目录后缀：新版本内容先整体落在这里，校验齐备后 rename 切换为正式目录 */
    public static final String SHADOW_NEW_SUFFIX = ".__NEW__";

    /** 旧版本目录后缀：切换时正式目录改名到这里，作为快速回滚源，部署成功后清理 */
    public static final String SHADOW_OLD_SUFFIX = ".__OLD__";

    /**
     * 判断一个名字是否为插件自用的中转产物（影子目录 / 上传临时文件）。
     *
     * <p>这些名字只在一次更新的中途存在，属于实现细节：目标树不展示、
     * 更新流程也不把它们当成业务内容。</p>
     *
     * @param name 文件或目录名
     * @return true 表示是中转产物
     * @author xumanyi
     * @date 2026-09-23
     */
    public static boolean isTransientArtifactName(String name) {
        return name != null && (name.endsWith(UPLOADING_SUFFIX)
                || name.endsWith(SHADOW_NEW_SUFFIX)
                || name.endsWith(SHADOW_OLD_SUFFIX));
    }

    /**
     * 原子发布式上传：先传到 {@code <最终名>.__UPLOADING__}，校验字节数后 rename 为最终名。
     *
     * <p>相对 {@link #upload(Path, String)} 的价值：传输中断时最终路径上的旧文件<b>原样不动</b>，
     * 不会出现"被截断的新文件"覆盖掉可用旧文件的情况——备份文件、版本记录、回滚写回
     * 这些"写坏了就没有第二份"的场景必须走这里。</p>
     *
     * <p>rename 覆盖已存在文件的行为各家服务端不一致：直接 rename 失败且目标确实存在时，
     * 删除旧文件后重试一次；目标不存在的失败属真失败，清掉临时文件后原样抛出。</p>
     *
     * @param localPath       本地文件
     * @param remoteFinalPath 远端最终路径
     * @param label           进度日志前缀，log 为 null 时忽略
     * @param log             进度日志回调，可为 null
     * @throws IOException 上传 / 校验 / 发布失败
     * @author xumanyi
     * @date 2026-09-23
     */
    public void uploadAtomic(Path localPath, String remoteFinalPath,
                             String label, Consumer<String> log) throws IOException {
        validateRemotePath(remoteFinalPath);
        String tempPath = remoteFinalPath + UPLOADING_SUFFIX;
        long localSize = Files.size(localPath);
        // 上次中断遗留的同名临时文件属于别的版本，留着会混淆校验，先删
        if (exists(tempPath)) {
            delete(tempPath);
        }
        if (log != null) {
            upload(localPath, tempPath, label, log);
        } else {
            upload(localPath, tempPath, null, null);
        }
        long uploaded = getFileSizeQuick(tempPath);
        if (uploaded != localSize) {
            try { delete(tempPath); } catch (IOException ignored) {
                // 临时文件删不掉不影响结论：本次上传已判定失败
            }
            throw new IOException("上传字节数不一致: " + remoteFinalPath
                    + "，本地 " + localSize + " B，远端 " + uploaded + " B");
        }
        try {
            rename(tempPath, remoteFinalPath);
            return;
        } catch (IOException renameErr) {
            if (!exists(remoteFinalPath)) {
                try { delete(tempPath); } catch (IOException ignored) {
                    // 同上：结论已定，临时文件由下次同名上传清理
                }
                throw renameErr;
            }
        }
        delete(remoteFinalPath);
        try {
            rename(tempPath, remoteFinalPath);
        } catch (IOException e2) {
            throw new IOException("发布失败（旧文件已删除，需从备份恢复）: " + remoteFinalPath
                    + " - " + e2.getMessage(), e2);
        }
    }

    /**
     * 原子发布式上传（无进度日志）
     *
     * @param localPath       本地文件
     * @param remoteFinalPath 远端最终路径
     * @throws IOException 上传 / 校验 / 发布失败
     * @author xumanyi
     * @date 2026-09-23
     */
    public void uploadAtomic(Path localPath, String remoteFinalPath) throws IOException {
        uploadAtomic(localPath, remoteFinalPath, null, null);
    }

    /**
     * 带重试 + 断点续传的上传。
     *
     * <p>核心策略：</p>
     * <ol>
     *   <li>开始前 {@code SIZE} 远端目标文件，已传 N 字节就 {@code REST N} 续传，
     *       本地输入流 skip(N) 后调 {@code STOR}</li>
     *   <li>失败按 {@link FtpErrorClassifier} 分类，仅 NETWORK 进入退避重试循环</li>
     *   <li>每次重试前调用 {@link FtpSession#reconnect()} 重建控制连接</li>
     *   <li>预算耗尽时调 {@code prompter.askRetryOrAbort}：用户选 RETRY 重置预算再来一轮，
     *       选 ABORT 抛最后一次异常给上层</li>
     * </ol>
     *
     * <p>幂等保证：远端 size &gt; 本地 size 视为脏数据（上次部署留下的不同版本残片），
     * 强制从 0 重传，避免传出半新半旧的混合体。</p>
     *
     * <p>线程：本方法阻塞调用线程，调用方自行决定是否在线程池里跑。</p>
     *
     * @param localPath  本地文件
     * @param remotePath 远端目标路径
     * @param policy     重试策略
     * @param prompter   预算耗尽时的用户提示器
     * @param log        日志回调（每次重试 / 续传决策都会写一行）
     * @throws IOException 用户选择 ABORT 后抛最后一次错误；或非网络类错误首次失败即抛
     * @author xumanyi
     * @date 2026-05-03
     */
    public void uploadResumable(Path localPath, String remotePath,
                                  RetryPolicy policy, RetryUserPrompter prompter,
                                  Consumer<String> log) throws IOException {
        validateRemotePath(remotePath);
        long localSize = Files.size(localPath);
        Consumer<String> logger = log != null ? log : msg -> {};

        // 外层 while：用户在弹窗选 RETRY 时重新进入新一轮重试
        while (true) {
            IOException lastErr = null;
            FtpErrorKind lastKind = FtpErrorKind.PROTOCOL;

            for (int attempt = 1; attempt <= policy.maxAttempts(); attempt++) {
                try {
                    long remoteOffset = computeResumeOffset(remotePath, localSize, logger);
                    tryUploadFromOffset(localPath, remotePath, remoteOffset, logger);
                    return; // 成功
                } catch (IOException e) {
                    lastErr = e;
                    lastKind = FtpErrorClassifier.classify(e);
                    boolean canRetry = policy.shouldRetry(lastKind) && attempt < policy.maxAttempts();
                    if (!canRetry) {
                        // 非网络类错误首次失败即跳出，不进入退避；网络类预算耗尽也跳出
                        if (!policy.shouldRetry(lastKind)) {
                            // 非网络错误：不询问用户，直接抛（AUTH/SERVER_LIMIT/PROTOCOL 重试无意义）
                            throw e;
                        }
                        break;
                    }
                    Duration backoff = policy.backoffFor(attempt);
                    logger.accept("[上传] 第 " + attempt + " 次失败，"
                            + backoff.toSeconds() + "s 后重试 (剩 "
                            + (policy.maxAttempts() - attempt) + " 次): " + e.getMessage());
                    sleep(backoff);
                    try {
                        session.reconnect();
                        logger.accept("[上传] 控制连接已重建");
                    } catch (IOException reconnectErr) {
                        logger.accept("[上传] 重连失败: " + reconnectErr.getMessage()
                                + "（继续退避后下一轮重试会再次尝试）");
                    }
                }
            }

            // 预算耗尽，询问用户
            String key = remotePath;
            RetryUserPrompter.Decision d = prompter.askRetryOrAbort(
                    key, policy.maxAttempts(),
                    lastErr != null ? lastErr.getMessage() : "未知错误",
                    lastKind);
            if (d == RetryUserPrompter.Decision.ABORT) {
                throw lastErr != null ? lastErr : new IOException("上传失败: " + remotePath);
            }
            logger.accept("[上传] 用户选择重试，重置预算");
            // 用户选 RETRY：尝试重连一次（可能现在网通了），失败也无所谓，循环里还会重试
            try { session.reconnect(); } catch (IOException ignored) {}
        }
    }

    /**
     * 计算续传起点：远端已存在则用其大小作为 offset，&gt; 本地大小则视为脏数据从 0 重传。
     *
     * @param remotePath 远端文件路径
     * @param localSize  本地文件大小
     * @param log        日志回调
     * @return 续传起点（≥ 0，≤ localSize）
     * @throws IOException 列目录失败
     * @author xumanyi
     * @date 2026-05-03
     */
    private long computeResumeOffset(String remotePath, long localSize, Consumer<String> log) throws IOException {
        long remoteSize = exists(remotePath) ? getFileSize(remotePath) : 0;
        if (remoteSize < 0) remoteSize = 0;
        if (remoteSize > localSize) {
            log.accept("[上传] 远端已有 " + remoteSize + " 字节 > 本地 " + localSize
                    + " 字节，疑似残留脏数据，从 0 重传");
            return 0;
        }
        if (remoteSize > 0 && remoteSize < localSize) {
            log.accept("[上传] 检测到远端已传 " + remoteSize + "/" + localSize + " 字节，启用断点续传");
        }
        return remoteSize;
    }

    /**
     * 从指定 offset 上传：local skip(offset) → setRestartOffset → storeFile。
     *
     * <p>finally 中清空 RestartOffset 防止污染同一 FTPClient 后续操作。</p>
     *
     * @param local  本地文件
     * @param remote 远端路径
     * @param offset 续传起点（0 = 从头）
     * @param log    日志回调
     * @throws IOException IO 失败 / 服务端响应非正完成
     * @author xumanyi
     * @date 2026-05-03
     */
    private void tryUploadFromOffset(Path local, String remote, long offset,
                                       Consumer<String> log) throws IOException {
        FTPClient client = session.getClient();
        try (InputStream raw = new BufferedInputStream(Files.newInputStream(local))) {
            if (offset > 0) {
                long skipped = 0;
                while (skipped < offset) {
                    long n = raw.skip(offset - skipped);
                    if (n <= 0) {
                        throw new IOException("本地文件 skip 到 offset 失败: 已 skip "
                                + skipped + "/" + offset);
                    }
                    skipped += n;
                }
                client.setRestartOffset(offset);
            }
            // 进度日志包装在 skip 之后，续传跳过的部分不计入速率
            InputStream counted = TransferProgress.wrapUpload(raw, offset, Files.size(local),
                    "[上传] " + displayName(remote), log);
            boolean ok = client.storeFile(remote, counted);
            if (!ok) {
                throw new IOException("上传失败: " + remote
                        + " (响应: " + client.getReplyString().trim() + ")");
            }
        } finally {
            // 必须清理：否则同一 FTPClient 上的下一次 storeFile/retrieveFile 会继续使用旧 offset
            try { client.setRestartOffset(0); } catch (RuntimeException ignored) {}
        }
    }

    /**
     * 从远端路径提取用于进度日志的显示名（basename，去掉上传临时后缀）
     *
     * @param remotePath 远端文件路径
     * @return 显示名，如 {@code logincenter.war}
     * @author xumanyi
     * @date 2026-08-26
     */
    private static String displayName(String remotePath) {
        String name = remotePath.substring(remotePath.lastIndexOf('/') + 1);
        int suffix = name.indexOf(".__UPLOADING__");
        return suffix > 0 ? name.substring(0, suffix) : name;
    }

    /**
     * 可中断的退避 sleep。被中断时恢复中断标志并抛 IOException 让上层结束循环。
     *
     * @param duration 等待时长，≤ 0 立即返回
     * @throws IOException 被中断
     * @author xumanyi
     * @date 2026-05-03
     */
    private static void sleep(Duration duration) throws IOException {
        long ms = duration.toMillis();
        if (ms <= 0) return;
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("等待重试被中断", e);
        }
    }

    /**
     * 远程文件重命名（原子操作，用于加锁/解锁）
     *
     * @param fromPath 原路径
     * @param toPath   目标路径
     * @author xumanyi
     * @date 2026-03-26
     */
    public void rename(String fromPath, String toPath) throws IOException {
        validateRemotePath(fromPath);
        validateRemotePath(toPath);
        FTPClient client = session.getClient();
        boolean ok = client.rename(fromPath, toPath);
        if (!ok) {
            throw new IOException("重命名失败: " + fromPath + " → " + toPath
                    + " (响应: " + client.getReplyString().trim() + ")");
        }
    }

    /**
     * 删除远程文件（仅用于删除锁文件和备份目录内文件）
     *
     * <p>安全约束：路径必须在 /开发/ 目录下，且不得包含路径遍历字符。
     * 删除操作会记录日志。</p>
     *
     * @param remoteFilePath 远程文件完整路径
     * @throws IOException 路径校验失败或删除失败时抛出
     * @author xumanyi
     * @date 2026-03-28
     */
    public void delete(String remoteFilePath) throws IOException {
        validateRemotePath(remoteFilePath);
        FTPClient client = session.getClient();
        boolean ok = client.deleteFile(remoteFilePath);
        if (!ok) {
            throw new IOException("删除失败: " + remoteFilePath
                    + " (响应: " + client.getReplyString().trim() + ")");
        }
    }

    /**
     * 校验远程路径安全性
     *
     * <p>防止路径遍历攻击和误操作：</p>
     * <ul>
     *   <li>禁止包含 ".." 路径段</li>
     *   <li>必须以 / 开头（绝对路径）</li>
     *   <li>必须在 /开发/ 目录下</li>
     * </ul>
     *
     * @param path 待校验的远程路径
     * @throws IOException 路径不合法时抛出
     * @author xumanyi
     * @date 2026-03-28
     */
    public static void validateRemotePath(String path) throws IOException {
        if (path == null || path.isEmpty()) {
            throw new IOException("[安全] 远程路径不能为空");
        }
        if (path.contains("..")) {
            throw new IOException("[安全] 远程路径禁止包含 '..' : " + path);
        }
        if (!path.startsWith("/")) {
            throw new IOException("[安全] 远程路径必须为绝对路径: " + path);
        }
        if (!path.startsWith("/开发/")) {
            throw new IOException("[安全] 远程路径必须在 /开发/ 目录下: " + path);
        }
    }

    /**
     * 创建远程目录（如已存在则忽略）
     *
     * @param remoteDirPath 远程目录路径
     * @author xumanyi
     * @date 2026-03-26
     */
    public void mkdirIfAbsent(String remoteDirPath) throws IOException {
        FTPClient client = session.getClient();
        boolean ok = client.makeDirectory(remoteDirPath);
        if (!ok) {
            // 可能已存在，检查响应码
            int reply = client.getReplyCode();
            // 550 通常表示目录已存在
            if (reply != 550) {
                throw new IOException("创建目录失败: " + remoteDirPath
                        + " (响应: " + client.getReplyString().trim() + ")");
            }
        }
    }

    /**
     * 递归创建远程目录树（自顶向下逐级创建，已存在则跳过）。
     *
     * @param remoteDirPath 目标目录绝对路径，必须以 / 开头
     * @author xumanyi
     * @date 2026-04-24
     */
    public void mkdirs(String remoteDirPath) throws IOException {
        if (remoteDirPath == null || remoteDirPath.isEmpty() || "/".equals(remoteDirPath)) {
            return;
        }
        String trimmed = remoteDirPath.endsWith("/")
                ? remoteDirPath.substring(0, remoteDirPath.length() - 1)
                : remoteDirPath;
        String[] parts = trimmed.split("/");
        StringBuilder cur = new StringBuilder();
        for (String p : parts) {
            if (p.isEmpty()) {
                continue;
            }
            cur.append('/').append(p);
            mkdirIfAbsent(cur.toString());
        }
    }

    /**
     * 递归删除目录（含子目录与所有文件）。
     *
     * @param remoteDirPath 要删除的目录路径
     * @throws IOException 列表 / 删除失败
     * @author xumanyi
     * @date 2026-04-29
     */
    public void removeDirRecursively(String remoteDirPath) throws IOException {
        String d = remoteDirPath.endsWith("/") ? remoteDirPath : remoteDirPath + "/";
        for (FTPFile f : listFiles(d)) {
            String n = FtpSession.decodeRemotePath(f.getName());
            if (".".equals(n) || "..".equals(n)) continue;
            String p = d + n;
            if (f.isDirectory()) {
                removeDirRecursively(p);
            } else {
                delete(p);
            }
        }
        // Remove the directory itself via FTPClient.removeDirectory
        if (!session.getClient().removeDirectory(d)) {
            throw new IOException("无法删除目录: " + d
                    + "（可能仍有内容或权限不足）");
        }
    }

    /**
     * 检查远程文件是否存在并返回大小
     *
     * @param remoteFilePath 远程文件完整路径
     * @return 文件大小（字节），不存在返回 -1
     * @author xumanyi
     * @date 2026-03-26
     */
    public long getFileSize(String remoteFilePath) throws IOException {
        // 获取文件所在目录和文件名
        int lastSlash = remoteFilePath.lastIndexOf('/');
        String dir = lastSlash > 0 ? remoteFilePath.substring(0, lastSlash + 1) : "/";
        String fileName = lastSlash >= 0 ? remoteFilePath.substring(lastSlash + 1) : remoteFilePath;

        List<FTPFile> files = listFiles(dir);
        for (FTPFile f : files) {
            String name = f.getName();
            if (fileName.equals(name) && f.isFile()) {
                return f.getSize();
            }
        }
        return -1;
    }

    /**
     * 检查远程文件是否存在
     *
     * @param remoteFilePath 远程文件完整路径
     * @return 文件存在返回 true
     * @throws IOException FTP 操作失败
     * @author xumanyi
     * @date 2026-03-26
     */
    public boolean exists(String remoteFilePath) throws IOException {
        return getFileSize(remoteFilePath) >= 0;
    }

    /**
     * 取远端文件大小（优先用 SIZE 命令，单行应答）。
     *
     * <p>{@link #getFileSize} 是 LIST 整个父目录再按名字挑——目录里有几百个文件时，
     * 为了一个数字要把整份列表传回来。逐文件校验的场景（备份、原子发布）用这个更省：
     * 一条命令一行应答。服务端不支持 SIZE 时退回列目录的算法，结果完全一致。</p>
     *
     * @param remoteFilePath 远程文件完整路径
     * @return 文件大小（字节），不存在返回 -1
     * @throws IOException 两种取法都失败
     * @author xumanyi
     * @date 2026-09-23
     */
    public long getFileSizeQuick(String remoteFilePath) throws IOException {
        FTPClient client = session.getClient();
        try {
            if (client.sendCommand("SIZE", remoteFilePath) == 213) {
                String reply = client.getReplyString().trim();
                int sp = reply.indexOf(' ');
                if (sp > 0) {
                    return Long.parseLong(reply.substring(sp + 1).trim());
                }
            }
        } catch (NumberFormatException e) {
            // 应答不是数字：按不支持处理，走下面的列目录算法
        }
        return getFileSize(remoteFilePath);
    }

    /**
     * 读取远程文件的修改时间（MDTM，UTC 时间串 yyyyMMddHHmmss）
     *
     * <p>返回值原样透传服务器的 UTC 时间串，与 {@link #setModificationTime} 配对使用
     * （读什么写回什么，无需时区换算）。服务器不支持 MDTM 或文件不存在时返回 {@code null}。</p>
     *
     * @param remoteFilePath 远程文件完整路径
     * @return UTC 时间串（yyyyMMddHHmmss），失败返回 {@code null}
     * @author xumanyi
     * @date 2026-08-14
     */
    public String getModificationTime(String remoteFilePath) {
        try {
            return session.getClient().getModificationTime(remoteFilePath);
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * 设置远程文件的修改时间（MFMT，UTC 时间串 yyyyMMddHHmmss）
     *
     * <p>用于回滚恢复文件后把修改时间写回更新前的原值，避免"回滚后时间戳变成回滚时刻"。
     * 服务器不支持 MFMT 时返回 false，调用方按尽力而为处理（不影响回滚本身）。</p>
     *
     * @param remoteFilePath 远程文件完整路径
     * @param mdtmUtc        UTC 时间串（来自 {@link #getModificationTime}）
     * @return 设置成功返回 true
     * @author xumanyi
     * @date 2026-08-14
     */
    public boolean setModificationTime(String remoteFilePath, String mdtmUtc) {
        if (mdtmUtc == null || mdtmUtc.isEmpty()) return false;
        try {
            return session.getClient().setModificationTime(remoteFilePath, mdtmUtc);
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * 在指定目录下递归扫描，返回所有文件名等于 basename 的完整远程路径。
     *
     * @param remoteDir 扫描起始目录（绝对路径）
     * @param basename  目标文件名，精确全名匹配（区分大小写）
     * @return 匹配的远程文件完整路径列表，未找到时返回空列表
     * @author xumanyi
     * @date 2026-04-24
     */
    public List<String> scanByBasename(String remoteDir, String basename) throws IOException {
        List<String> matches = new ArrayList<>();
        scanRecursive(ensureTrailingSlash(remoteDir), basename, matches);
        return matches;
    }

    /**
     * 递归扫描锁文件（文件名包含 {@code __LOCK__}）。
     *
     * @param remoteDir 扫描起始目录（绝对路径）
     * @return 命中的锁文件完整路径列表
     * @author xumanyi
     * @date 2026-04-24
     */
    public List<String> scanLockFiles(String remoteDir) throws IOException {
        List<String> matches = new ArrayList<>();
        scanLockRecursive(ensureTrailingSlash(remoteDir), matches);
        return matches;
    }

    /**
     * 递归扫描指定目录下所有文件（不跳过任何子目录），供回滚场景读取备份目录内容。
     *
     * @param remoteDir 扫描起始目录（绝对路径）
     * @return 命中的文件完整路径列表
     * @author xumanyi
     * @date 2026-04-24
     */
    public List<String> scanAllFiles(String remoteDir) throws IOException {
        List<String> matches = new ArrayList<>();
        scanAllRecursive(ensureTrailingSlash(remoteDir), matches);
        return matches;
    }

    /**
     * 递归扫描指定目录下所有文件（不跳过任何子目录）
     *
     * @param dir     扫描目录路径
     * @param matches 命中文件完整路径收集列表
     * @throws IOException FTP 操作失败
     * @author xumanyi
     * @date 2026-03-26
     */
    private void scanAllRecursive(String dir, List<String> matches) throws IOException {
        String base = ensureTrailingSlash(dir);
        List<FTPFile> files = listFiles(base);
        for (FTPFile f : files) {
            String name = f.getName();
            if (f.isDirectory()) {
                scanAllRecursive(base + name, matches);
            } else if (f.isFile()) {
                matches.add(base + name);
            }
        }
    }

    /**
     * 递归扫描文件名包含 {@code __LOCK__} 的锁文件
     *
     * @param dir     扫描目录路径
     * @param matches 命中锁文件完整路径收集列表
     * @throws IOException FTP 操作失败
     * @author xumanyi
     * @date 2026-03-26
     */
    private void scanLockRecursive(String dir, List<String> matches) throws IOException {
        String base = ensureTrailingSlash(dir);
        List<FTPFile> files = listFiles(base);
        for (FTPFile f : files) {
            String name = f.getName();
            if (f.isDirectory()) {
                if (SCAN_SKIP_DIRS.contains(name)) {
                    continue;
                }
                scanLockRecursive(base + name, matches);
            } else if (f.isFile() && name.contains("__LOCK__")) {
                matches.add(base + name);
            }
        }
    }

    /**
     * 备份目录名集合。
     *
     * <p>常见外部约定的"原包备份"目录命名，含 CLI 自身写入的 {@code backup/} 以及历史习惯名。
     * CLI 通过 {@link #SCAN_SKIP_DIRS} 跳过这些目录；UI 端 {@code FtpBrowseService}
     * 不再过滤这些目录，但会标记落入备份目录的包，使其默认不勾选。</p>
     *
     * @author xumanyi
     * @date 2026-05-02
     */
    public static final java.util.Set<String> BACKUP_DIR_NAMES =
            java.util.Set.of("backup", "backups", "bak", ".backup");

    /**
     * CLI 扫描时跳过的目录名（备份目录 + 锁控制目录）。
     *
     * <p>这些目录是 CLI 自身或常见外部约定生成的备份/锁目录，不应作为 CLI 端的部署目标。
     * 仅 CLI 使用：UI 浏览不再共用此集合，避免备份目录在树里"消失"导致用户困惑。</p>
     */
    public static final java.util.Set<String> SCAN_SKIP_DIRS =
            java.util.Set.of("backup", "backups", "bak", ".backup", ".flux-lock");

    /**
     * 递归扫描文件名等于 basename 的文件
     *
     * @param dir      扫描目录路径
     * @param basename 目标文件名（精确全名匹配）
     * @param matches  命中文件完整路径收集列表
     * @throws IOException FTP 操作失败
     * @author xumanyi
     * @date 2026-03-26
     */
    private void scanRecursive(String dir, String basename, List<String> matches) throws IOException {
        String base = ensureTrailingSlash(dir);
        List<FTPFile> files = listFiles(base);
        for (FTPFile f : files) {
            String name = f.getName();
            if (f.isDirectory()) {
                if (SCAN_SKIP_DIRS.contains(name)) {
                    continue;
                }
                scanRecursive(base + name, basename, matches);
            } else if (f.isFile() && basename.equals(name)) {
                matches.add(base + name);
            }
        }
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
}
