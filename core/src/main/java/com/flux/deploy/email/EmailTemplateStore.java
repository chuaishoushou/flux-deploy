package com.flux.deploy.email;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 通知邮件模板的文件存储 CRUD（不依赖 IDE Platform）
 *
 * <p>默认目录：{@code ~/.flux-deploy/email_templates/}，每个模板对应一个
 * {@code <name>.html} 文件，文件名（不含 {@code .html} 后缀）即模板名。</p>
 *
 * <p><b>「default」模板的定位</b>：它只是「用户开箱时的第一个模板」，并非一份固定不变的
 * 出厂模板，跟用户新建的模板走同一套增删改逻辑——</p>
 * <ul>
 *   <li>首次访问目录时若不存在 → 静默创建目录并以内置内容（{@link #BUILTIN_DEFAULT_TEMPLATE}）种子写入</li>
 *   <li>允许用户通过 {@link #save} 自由编辑覆盖其内容（之后插件不再回头改它）</li>
 *   <li>唯一的特殊点：禁止通过 {@link #delete} 删除 —— 保证列表里始终至少有它一个</li>
 * </ul>
 *
 * <p><b>内置模板（{@link #BUILTIN_DEFAULT_TEMPLATE}）只作为隐藏的"内容源"</b>，不作为列表项出现，
 * 永远等于当前插件版本里的最新内容。它仅在两个时机被取用：新建模板时作初始内容、
 * 「恢复默认」时把当前选中模板重置回这份。因此插件升级后，用户任何一次新建 / 恢复默认
 * 都会自动拿到最新模板，而已存模板不会被插件擅自覆盖。</p>
 *
 * <p>所有方法把 I/O 失败包装为 {@link IOException}，由上层决定如何提示用户。</p>
 *
 * @author xumanyi
 * @date 2026-05-17
 */
public final class EmailTemplateStore {

    /** 默认模板名（不可删除，可覆盖） */
    public static final String DEFAULT_TEMPLATE_NAME = "default";

    /** 文件扩展名 */
    private static final String FILE_EXT = ".html";

    /**
     * 内置默认模板源串（HTML 片段）
     *
     * <p>绑定变量 {@code ${项目}} / {@code ${任务}} / {@code ${客服}} / {@code ${更新模式}} /
     * {@code ${FTP版本来源}} / {@code ${更新jar包}} / {@code ${更新war包}} / {@code ${备份包地址}}
     * 由主面板 + 部署历史缓存自动填（点「导入」触发）；
     * 空值时显示斜体占位符 {@code ${字段名}}。</p>
     *
     * <p>首行 {@code 顾问，} 是收件人占位，每次发邮件由用户自己改。后续行每行前面用 1 个
     * 半角空格作为视觉错落（不做"段首缩进 2 字"那种正式缩进——用户实测企微复制粘贴
     * 不要正式缩进，更紧凑、对齐效果更稳定）。占位符语法 {@code ${字段名}} 由
     * {@link EmailTemplateRenderer#renderInitialPlain} 解释。</p>
     *
     * <p>固定行（无变量、只有标签）由用户每次手填：{@code 更新内容 / 资源来源 /
     * 更新方式 / 是否重启 / 浏览器缓存刷新 / 影响范围 / SQL}。</p>
     */
    /** 内置默认模板的完整 HTML（含正文 + 签名档）。
     *  内容来源：用户在邮件编辑器里编排好、点「保存」落盘的 default.html，原样固化进来。
     *  正文变量 ${项目}/${任务}/${客服}/${更新模式}/${FTP版本来源}/${更新jar包}/${更新war包}/${备份包地址} 等由
     *  「导入数据」按钮填充；签名档为公司固定信息。这样新装用户 / 点「恢复默认」都拿到这份。 */

    public static final String BUILTIN_DEFAULT_TEMPLATE =
            "<p style=\"line-height: 1;\"><span style=\"font-size: 10.5pt; font-family: &quot;PingFang SC&quot;;\">顾问，</span></p>"
                    + "<p style=\"line-height: 1;\"><span style=\"font-size: 10.5pt; font-family: &quot;PingFang SC&quot;;\"> 你好！</span></p>"
                    + "<p style=\"line-height: 1;\"><span style=\"font-size: 10.5pt; font-family: &quot;PingFang SC&quot;;\"> 【${项目}】部署包已上传，请帮忙更新到测试环境，谢谢！</span></p>"
                    + "<p style=\"line-height: 1;\"><span style=\"font-size: 10.5pt; font-family: &quot;PingFang SC&quot;;\"> 更新内容:</span></p>"
                    + "<p style=\"line-height: 1;\"><span style=\"font-size: 10.5pt; font-family: &quot;PingFang SC&quot;;\"> 客服：${客服}</span></p>"
                    + "<p style=\"line-height: 1;\"><span style=\"font-size: 10.5pt; font-family: &quot;PingFang SC&quot;;\"> 任务：${任务}</span></p>"
                    + "<p style=\"line-height: 1;\"><span style=\"font-size: 10.5pt; font-family: &quot;PingFang SC&quot;;\"> 资源来源：</span></p>"
                    + "<p style=\"line-height: 1;\"><span style=\"font-size: 10.5pt; font-family: &quot;PingFang SC&quot;;\"> FTP版本来源：${FTP版本来源}</span></p>"
                    + "<p style=\"line-height: 1;\"><span style=\"font-size: 10.5pt; font-family: &quot;PingFang SC&quot;;\"> 备份位置：${备份包地址}</span></p>"
                    + "<p style=\"line-height: 1;\"><span style=\"font-size: 10.5pt; font-family: &quot;PingFang SC&quot;;\"> 更新方式：${更新模式}</span></p>"
                    + "<p style=\"line-height: 1;\"><span style=\"font-size: 10.5pt; font-family: &quot;PingFang SC&quot;;\"> 是否重启：需要</span></p>"
                    + "<p style=\"line-height: 1;\"><span style=\"font-size: 10.5pt; font-family: &quot;PingFang SC&quot;;\"> 浏览器缓存刷新：否</span></p>"
                    + "<p style=\"line-height: 1;\"><span style=\"font-size: 10.5pt; font-family: &quot;PingFang SC&quot;;\"> 影响范围：</span></p>"
                    + "<p style=\"line-height: 1;\"><span style=\"font-family: &quot;PingFang SC&quot;; font-size: 10.5pt;\"> 更新jar包：${更新jar包}</span></p>"
                    + "<p style=\"line-height: 1;\"><span style=\"font-family: &quot;PingFang SC&quot;; font-size: 10.5pt;\"> 更新war包：${更新war包}</span></p>"
                    + "<p style=\"line-height: 1;\"><span style=\"font-size: 10.5pt; font-family: &quot;PingFang SC&quot;;\"> SQL:</span></p>"
                    + "<p style=\"line-height: 1;\"><span style=\"font-family: &quot;PingFang SC&quot;; font-size: 10.5pt;\"> 其他：</span></p>"
                    + "<p style=\"line-height: 1;\"><br></p>"
                    + "<p style=\"line-height: 1;\"><br></p>"
                    + "<p style=\"text-align: justify;\"><strong style=\"color: rgb(192, 192, 192); font-family: &quot;PingFang SC&quot;; background-color: rgb(255, 255, 255);\">KaiFa 开发人员</strong></p>"
                    + "<p style=\"text-align: justify;\"><strong style=\"color: rgb(192, 192, 192); font-family: Verdana; font-size: 9pt; background-color: rgb(255, 255, 255);\">Technical Consultant</strong></p>"
                    + "<p><strong style=\"color: rgb(255, 0, 0); font-family: Verdana; font-size: 10.5pt; background-color: rgb(255, 255, 255);\"><em>F</em></strong><strong style=\"color: rgb(0, 128, 192); font-family: Verdana; font-size: 10.5pt; background-color: rgb(255, 255, 255);\"><em>ull-value </em></strong><strong style=\"color: rgb(255, 0, 0); font-family: Verdana; font-size: 10.5pt; background-color: rgb(255, 255, 255);\"><em>L</em></strong><strong style=\"color: rgb(0, 128, 192); font-family: Verdana; font-size: 10.5pt; background-color: rgb(255, 255, 255);\"><em>ogistics, </em></strong><strong style=\"color: rgb(255, 0, 0); font-family: Verdana; font-size: 10.5pt; background-color: rgb(255, 255, 255);\"><em>U</em></strong><strong style=\"color: rgb(0, 128, 192); font-family: Verdana; font-size: 10.5pt; background-color: rgb(255, 255, 255);\"><em>nited e</em></strong><strong style=\"color: rgb(255, 0, 0); font-family: Verdana; font-size: 10.5pt; background-color: rgb(255, 255, 255);\"><em>X</em></strong><strong style=\"color: rgb(0, 128, 192); font-family: Verdana; font-size: 10.5pt; background-color: rgb(255, 255, 255);\"><em>pertise</em></strong></p>"
                    + "<p><span style=\"font-size: 10.5pt; font-family: Arial; color: rgb(31, 73, 125); background-color: rgb(255, 255, 255);\">专注.专业.专心</span></p>"
                    + "<p><strong style=\"color: rgb(51, 102, 255); font-family: Arial; font-size: 10.5pt; background-color: rgb(255, 255, 255);\"><em>---------------------------------------------</em></strong></p>"
                    + "<p><span style=\"font-size: 10.5pt; font-family: Arial; color: rgb(0, 0, 128); background-color: rgb(255, 255, 255);\">上海富勒信息科技有限公司（FLUX）</span></p>"
                    + "<p><span style=\"font-size: 10.5pt; font-family: Arial; color: rgb(0, 0, 128); background-color: rgb(255, 255, 255);\">客服</span><strong style=\"font-size: 10.5pt; font-family: Arial; color: rgb(0, 0, 128); background-color: rgb(255, 255, 255);\">:</strong><span style=\"font-size: 10.5pt; font-family: Arial; color: rgb(0, 0, 128); background-color: rgb(255, 255, 255);\"> </span><span style=\"font-size: 10.5pt; font-family: Arial; color: rgb(0, 0, 64); background-color: rgb(255, 255, 255);\">400 878 9606 </span><strong style=\"font-size: 10.5pt; font-family: Arial; color: rgb(0, 0, 64); background-color: rgb(255, 255, 255);\"> </strong><span style=\"font-size: 10.5pt; font-family: Arial; color: rgb(0, 0, 128); background-color: rgb(255, 255, 255);\">手机</span><strong style=\"font-size: 10.5pt; font-family: Arial; color: rgb(0, 0, 64); background-color: rgb(255, 255, 255);\">: </strong></p>"
                    + "<p><span style=\"font-size: 10.5pt; font-family: Arial; color: rgb(0, 0, 128); background-color: rgb(255, 255, 255);\">微信</span><strong style=\"font-size: 10.5pt; font-family: Arial; color: rgb(0, 0, 128); background-color: rgb(255, 255, 255);\">:</strong><span style=\"font-size: 10.5pt; font-family: Arial; color: rgb(0, 0, 128); background-color: rgb(255, 255, 255);\"> </span><span style=\"font-size: 10.5pt; font-family: Arial; color: rgb(0, 0, 64); background-color: rgb(255, 255, 255);\">FLUX-2013</span></p>"
                    + "<p style=\"line-height: 1;\"><strong style=\"color: rgb(0, 0, 128); font-family: Arial; font-size: 10.5pt; background-color: rgb(255, 255, 255);\">网址:</strong><strong style=\"color: rgb(31, 73, 125); font-family: Arial; font-size: 10.5pt; background-color: rgb(255, 255, 255);\"> </strong><strong style=\"color: rgb(0, 0, 255); font-family: Arial; font-size: 10.5pt; background-color: rgb(255, 255, 255);\">http://www.flux.com.cn</strong></p>";

    private final Path rootDir;

    /**
     * 用默认路径 {@code ~/.flux-deploy/email_templates/} 构造
     *
     * @author xumanyi
     * @date 2026-05-17
     */
    public EmailTemplateStore() {
        this(Path.of(System.getProperty("user.home"), ".flux-deploy", "email_templates"));
    }

    /**
     * 用指定根目录构造（供测试注入）
     *
     * @param rootDir 模板存储根目录
     * @author xumanyi
     * @date 2026-05-17
     */
    public EmailTemplateStore(Path rootDir) {
        this.rootDir = rootDir;
    }

    /**
     * 首次设置：根目录不存在则创建，{@code default} 模板不存在则以内置内容种子写入
     *
     * <p>幂等：{@code default} 已存在则<b>原样保留、绝不覆盖</b>（它现在是用户可自由编辑的
     * 普通模板，插件不该回头改它）。每次访问 {@link #listNames()} / {@link #loadOrDefault}
     * 时内部都会先调一次本方法，无须外部显式触发。</p>
     *
     * @throws IOException 创建目录或写文件失败
     * @author xumanyi
     * @date 2026-05-17
     */
    public void ensureInitialized() throws IOException {
        if (!Files.exists(rootDir)) {
            Files.createDirectories(rootDir);
        }
        // 一次性迁移：扫所有 .html 模板，把已重命名的占位符（${任务号} → ${任务}、
        // ${客服编号} → ${客服}）就地替换。保留其他内容不动，老用户自定义模板也能直接用新字段。
        migrateRenamedPlaceholders();

        // default 只在"还不存在"时用内置内容种子创建（用户开箱时的第一个模板）；
        // 已存在就不动——用户对它的任何编辑都视作普通模板内容，插件不再升级 / 覆盖。
        Path defaultPath = pathOf(DEFAULT_TEMPLATE_NAME);
        if (!Files.exists(defaultPath)) {
            Files.writeString(defaultPath, BUILTIN_DEFAULT_TEMPLATE, StandardCharsets.UTF_8);
        }
    }

    /**
     * 已重命名的占位符映射：旧名 → 新名（运行时一次性迁移用）
     */
    private static final Map<String, String> RENAMED_PLACEHOLDERS = Map.of(
            "${任务号}", "${任务}",
            "${客服编号}", "${客服}"
    );

    /**
     * 扫描所有模板文件，把已重命名的占位符（{@link #RENAMED_PLACEHOLDERS}）就地替换
     *
     * <p>纯字面替换，不影响模板里其他 HTML / 文本。失败时静默跳过（启动不应被任何 I/O 错误阻挡）。</p>
     *
     * @author xumanyi
     * @date 2026-05-18
     */
    private void migrateRenamedPlaceholders() {
        if (!Files.exists(rootDir)) return;
        try (Stream<Path> s = Files.list(rootDir)) {
            s.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(FILE_EXT))
                    .forEach(this::migrateOneFile);
        } catch (IOException ignored) {
            // 列目录失败：跳过迁移，继续启动
        }
    }

    private void migrateOneFile(Path path) {
        try {
            String content = Files.readString(path, StandardCharsets.UTF_8);
            String migrated = content;
            for (Map.Entry<String, String> e : RENAMED_PLACEHOLDERS.entrySet()) {
                migrated = migrated.replace(e.getKey(), e.getValue());
            }
            if (!migrated.equals(content)) {
                Files.writeString(path, migrated, StandardCharsets.UTF_8);
            }
        } catch (IOException ignored) {
            // 单文件迁移失败：跳过，保留原文件
        }
    }

    /**
     * 列出所有模板名（不含 {@code .html} 后缀）
     *
     * <p>始终先 {@link #ensureInitialized}，保证至少能列出 {@code default}。
     * 列表按字典序排序，{@code default} 永远排第一。</p>
     *
     * @return 模板名列表（永不为空，至少含 {@code default}）
     * @throws IOException I/O 失败
     * @author xumanyi
     * @date 2026-05-17
     */
    public List<String> listNames() throws IOException {
        ensureInitialized();
        // 按文件 creationTime 升序：新创建的模板排到列表末尾，跟用户"看到新建的在后面"
        // 直觉一致。ext4 等没有 birth time 的 FS 上 JDK 回落到 lastModifiedTime，
        // 也大体上能反映"较新"的顺序。
        Map<String, FileTime> createdAt = new HashMap<>();
        try (Stream<Path> s = Files.list(rootDir)) {
            s.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(FILE_EXT))
                    .forEach(p -> {
                        String name = p.getFileName().toString();
                        name = name.substring(0, name.length() - FILE_EXT.length());
                        FileTime ft;
                        try {
                            ft = Files.readAttributes(p, BasicFileAttributes.class)
                                    .creationTime();
                        } catch (IOException ignore) {
                            ft = FileTime.fromMillis(0);
                        }
                        createdAt.put(name, ft);
                    });
        }
        List<String> names = new ArrayList<>(createdAt.keySet());
        names.sort(Comparator.comparing(createdAt::get));
        // default 永远排第一
        if (names.remove(DEFAULT_TEMPLATE_NAME)) {
            names.add(0, DEFAULT_TEMPLATE_NAME);
        }
        return names;
    }

    /**
     * 读取指定模板内容
     *
     * <p>文件不存在或读失败时不抛异常，回落到 {@link #BUILTIN_DEFAULT_TEMPLATE}
     * （避免弹窗因模板坏掉打不开）。</p>
     *
     * @param name 模板名（不含 {@code .html} 后缀）
     * @return 模板源串
     * @author xumanyi
     * @date 2026-05-17
     */
    public String loadOrDefault(String name) {
        try {
            ensureInitialized();
            Path p = pathOf(name);
            if (Files.isReadable(p)) {
                return Files.readString(p, StandardCharsets.UTF_8);
            }
        } catch (Exception ignored) {
            // 静默回落
        }
        return BUILTIN_DEFAULT_TEMPLATE;
    }

    /**
     * 「恢复默认」按钮的核心逻辑 —— 把指定模板内容重置为插件内置的默认内容
     *
     * <p>新定位下不再区分 default 与其他模板：任意模板（含 default）都统一恢复为当前
     * 插件版本的 {@link #BUILTIN_DEFAULT_TEMPLATE}。这样升级插件后点一次「恢复默认」，
     * 任何模板都能拿到最新的出厂内容。</p>
     *
     * @param name 待恢复的模板名
     * @throws IOException I/O 失败
     * @throws IllegalArgumentException 名字非法
     * @author xumanyi
     * @date 2026-05-18
     */
    public void restoreToBuiltinDefault(String name) throws IOException {
        validateName(name);
        ensureInitialized();
        Files.writeString(pathOf(name), BUILTIN_DEFAULT_TEMPLATE, StandardCharsets.UTF_8);
    }

    /**
     * 保存模板内容（已存在则覆盖）
     *
     * <p>给「保存到模板」按钮用：用户当前在弹窗里选中的模板被覆盖。
     * 不做"已存在拒绝"检查，因为覆盖是常态操作。新建用 {@link #createNew}。</p>
     *
     * @param name    模板名
     * @param content 模板源串
     * @throws IOException             I/O 失败
     * @throws IllegalArgumentException 名字非法（含路径分隔符 / 空白 / 空字符串）
     * @author xumanyi
     * @date 2026-05-17
     */
    public void save(String name, String content) throws IOException {
        validateName(name);
        ensureInitialized();
        Files.writeString(pathOf(name), content == null ? "" : content,
                StandardCharsets.UTF_8);
    }

    /**
     * 新建模板（重名拒绝）
     *
     * <p>给「+ 新建」按钮用。已存在 → 抛 {@link IllegalStateException}
     * 让上层提示"已存在请改名"。</p>
     *
     * @param name    新模板名
     * @param content 初始内容（可为空字符串）
     * @throws IOException              I/O 失败
     * @throws IllegalArgumentException 名字非法
     * @throws IllegalStateException    已存在
     * @author xumanyi
     * @date 2026-05-17
     */
    public void createNew(String name, String content) throws IOException {
        validateName(name);
        ensureInitialized();
        Path p = pathOf(name);
        if (Files.exists(p)) {
            throw new IllegalStateException("模板已存在: " + name);
        }
        Files.writeString(p, content == null ? "" : content, StandardCharsets.UTF_8);
    }

    /**
     * 删除指定模板
     *
     * <p>{@code default} 不允许删除（兜底约束）。文件不存在视作幂等成功，
     * 不抛异常。</p>
     *
     * @param name 模板名
     * @throws IOException              I/O 失败
     * @throws IllegalArgumentException 名字非法或试图删 default
     * @author xumanyi
     * @date 2026-05-17
     */
    public void delete(String name) throws IOException {
        validateName(name);
        if (DEFAULT_TEMPLATE_NAME.equals(name)) {
            throw new IllegalArgumentException("default 模板不可删除");
        }
        ensureInitialized();
        Files.deleteIfExists(pathOf(name));
    }

    /**
     * 名字 → 文件路径
     *
     * @param name 模板名
     * @return 该模板的绝对路径
     * @author xumanyi
     * @date 2026-05-17
     */
    private Path pathOf(String name) {
        return rootDir.resolve(name + FILE_EXT);
    }

    /**
     * 名字合法性校验
     *
     * <p>禁止：空、含路径分隔符、含空白、含点（避免 {@code ../} 这种逃逸）。</p>
     *
     * @param name 模板名
     * @throws IllegalArgumentException 不合法
     * @author xumanyi
     * @date 2026-05-17
     */
    private static void validateName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("模板名不能为空");
        }
        if (name.contains("/") || name.contains("\\") || name.contains(".")
                || !name.equals(name.trim()) || name.matches(".*\\s.*")) {
            throw new IllegalArgumentException(
                    "模板名只允许字母 / 数字 / 中文 / 下划线 / 连字符，不能含路径分隔符、点或空白: " + name);
        }
    }
}
