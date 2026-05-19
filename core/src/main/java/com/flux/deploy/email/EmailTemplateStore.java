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
import java.util.Set;
import java.util.stream.Stream;

/**
 * 通知邮件模板的文件存储 CRUD（不依赖 IDE Platform）
 *
 * <p>默认目录：{@code ~/.flux-deploy/email_templates/}，每个模板对应一个
 * {@code <name>.html} 文件，文件名（不含 {@code .html} 后缀）即模板名。</p>
 *
 * <p><b>「default」模板的特殊语义</b>：</p>
 * <ul>
 *   <li>首次访问目录时若不存在 → 静默创建目录并写入内置默认模板（{@link #BUILTIN_DEFAULT_TEMPLATE}）</li>
 *   <li>允许用户通过 {@link #save} 覆盖其内容</li>
 *   <li>禁止通过 {@link #delete} 删除 —— 始终保留至少一个兜底模板</li>
 * </ul>
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
     * <p>4 个绑定变量（{@code ${任务}}、{@code ${客服}}、{@code ${更新包}}、{@code ${备份包}}）
     * 由主面板 / 部署历史缓存自动填；其他字段是占位标签，用户在邮件弹窗里手填。</p>
     *
     * <p>首行 {@code XX，} 是收件人占位，每次发邮件由用户自己改。后续行用 4 个
     * {@code &nbsp;} 缩进（约 1 个中文字宽），跟首行 "XX，" 视觉错开。占位符语法
     * {@code ${字段名}} 由 {@link EmailTemplateRenderer#renderInitialPlain} 解释。</p>
     */
    public static final String BUILTIN_DEFAULT_TEMPLATE =
            "XX，<br>"
                    + "&nbsp;&nbsp;&nbsp;&nbsp;你好！<br>"
                    + "&nbsp;&nbsp;&nbsp;&nbsp;【${项目}】项目已更新到FTP，麻烦更新下<br>"
                    + "&nbsp;&nbsp;&nbsp;&nbsp;更新内容：<br>"
                    + "&nbsp;&nbsp;&nbsp;&nbsp;任务：${任务}<br>"
                    + "&nbsp;&nbsp;&nbsp;&nbsp;客服：${客服}<br>"
                    + "&nbsp;&nbsp;&nbsp;&nbsp;更新包：${更新包}<br>"
                    + "&nbsp;&nbsp;&nbsp;&nbsp;备份包：${备份包}<br>"
                    + "&nbsp;&nbsp;&nbsp;&nbsp;更新方式：<br>"
                    + "&nbsp;&nbsp;&nbsp;&nbsp;是否重启：<br>"
                    + "&nbsp;&nbsp;&nbsp;&nbsp;浏览器缓存刷新：<br>"
                    + "&nbsp;&nbsp;&nbsp;&nbsp;影响范围：<br>"
                    + "&nbsp;&nbsp;&nbsp;&nbsp;SQL:<br>";

    /**
     * v1 时代的"老占位符"集合 —— 检测到 {@code default.html} 内容包含这里任一项
     * 即视为老版本模板，自动升级为 {@link #BUILTIN_DEFAULT_TEMPLATE}。
     *
     * <p>用 fuzzy match（任意包含）而非精确字面值比对，能覆盖以下情况：</p>
     * <ul>
     *   <li>用户在历史版本里点过「保存到模板」，导致老模板文件被反向占位符化等
     *       操作微调过，跟我们记的原始 BUILTIN 字面值不再完全一致</li>
     *   <li>不同历史 BUILTIN 版本之间细微差异（HTML 标签顺序 / 换行符等）</li>
     * </ul>
     *
     * <p>升级前会把老内容备份到 {@code default.legacy.bak}（不进入下拉），
     * 万一误升级用户可以手动恢复。</p>
     */
    public static final Set<String> LEGACY_PLACEHOLDER_TOKENS = Set.of(
            "${收件人}",
            "${项目名}",
            "${标题}",
            "${资源来源}",
            "${FTP版本来源}",
            "${更新方式}",
            "${是否重启}",
            "${浏览器缓存刷新}",
            "${影响范围}",
            "${SQL}",
            "${开发}"
    );

    /** 备份老模板文件的文件名（不带 .html 后缀，不会被 {@link #listNames()} 列出） */
    private static final String LEGACY_BACKUP_FILENAME = "default.legacy.bak";

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
     * 首次设置：根目录不存在则创建，default 模板不存在则写入内置默认内容
     *
     * <p>幂等：已存在则不动。每次访问 {@link #listNames()} / {@link #load(String)} 时
     * 内部都会先调一次本方法，无须外部显式触发。</p>
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

        Path defaultPath = pathOf(DEFAULT_TEMPLATE_NAME);
        if (!Files.exists(defaultPath)) {
            Files.writeString(defaultPath, BUILTIN_DEFAULT_TEMPLATE, StandardCharsets.UTF_8);
            return;
        }
        // 自动升级 / 修复：
        //   1. 文件为空或全空白 → 历史误操作清空过，直接恢复内置默认
        //   2. 含 v1 时代老占位符 → 模板已过期，备份到 default.legacy.bak 后覆盖
        try {
            String existing = Files.readString(defaultPath, StandardCharsets.UTF_8);
            if (existing.trim().isEmpty()) {
                Files.writeString(defaultPath, BUILTIN_DEFAULT_TEMPLATE,
                        StandardCharsets.UTF_8);
                return;
            }
            if (existing.equals(BUILTIN_DEFAULT_TEMPLATE)) {
                return;
            }
            if (containsAnyLegacyPlaceholder(existing)) {
                Files.writeString(rootDir.resolve(LEGACY_BACKUP_FILENAME),
                        existing, StandardCharsets.UTF_8);
                Files.writeString(defaultPath, BUILTIN_DEFAULT_TEMPLATE,
                        StandardCharsets.UTF_8);
            }
        } catch (IOException ignored) {
            // 读失败不影响后续 load——load 会回落到 BUILTIN_DEFAULT_TEMPLATE
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
     * 检测内容是否包含 v1 时代的任一老占位符
     *
     * <p>用于 {@link #ensureInitialized()} 的 fuzzy 自动升级；也由
     * {@code EmailDraftManager} 在打开邮件时检测内存 draft 是否陈旧。</p>
     *
     * @param content 模板源串或 draft HTML
     * @return 含 {@link #LEGACY_PLACEHOLDER_TOKENS} 中任一占位符返回 true
     * @author xumanyi
     * @date 2026-05-17
     */
    public static boolean containsAnyLegacyPlaceholder(String content) {
        if (content == null || content.isEmpty()) {
            return false;
        }
        for (String token : LEGACY_PLACEHOLDER_TOKENS) {
            if (content.contains(token)) {
                return true;
            }
        }
        return false;
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
     * 「恢复默认」按钮的核心逻辑 —— default 与其他模板语义不同
     *
     * <p>设计意图：把 "default" 模板当作"用户自己的默认模板"（可定制），
     * 其他自定义模板视为"从默认模板派生"。所以：</p>
     * <ul>
     *   <li>name = "default" → 用插件内置的 {@link #BUILTIN_DEFAULT_TEMPLATE} 覆盖
     *       （回到出厂状态）</li>
     *   <li>name = 其他 → 用<b>当前 default 模板的内容</b>覆盖（即用户定制过的"默认"，
     *       而不是出厂状态）。让自定义模板"重置"成跟默认一致的起点。</li>
     * </ul>
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
        String content;
        if (DEFAULT_TEMPLATE_NAME.equals(name)) {
            content = BUILTIN_DEFAULT_TEMPLATE;
        } else {
            // 其他模板恢复成当前 default 的内容（默认模板用户可能已经定制过）
            content = loadOrDefault(DEFAULT_TEMPLATE_NAME);
        }
        Files.writeString(pathOf(name), content, StandardCharsets.UTF_8);
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
