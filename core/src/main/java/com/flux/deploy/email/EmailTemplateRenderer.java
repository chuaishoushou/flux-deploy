package com.flux.deploy.email;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 通知邮件模板渲染 / 累积 / 反向占位符化的纯函数集合
 *
 * <p>提供三个互逆操作：</p>
 *
 * <ol>
 *   <li>{@link #renderInitial}：模板源串（含 {@code ${字段名}}）→ 富文本 HTML
 *       （已知字段值包在 {@code <span class="flux-field-字段名">} 锚点中，
 *       未知占位符保留 {@code ${字段名}} 原样）</li>
 *   <li>{@link #appendAccumulate}：定位某个字段锚点，追加新条目（如部署成功后
 *       追加新的"更新包"项），不影响其他字段</li>
 *   <li>{@link #toTemplateSource}：富文本 HTML → 模板源串（{@code <span>} 锚点
 *       整体替换回 {@code ${字段名}}，保留 span 外部内容；保存到模板文件用）</li>
 * </ol>
 *
 * <p><b>为什么用 class 锚点而非 data-attribute</b>：Swing 的 {@code HTMLEditorKit}
 * 基于 HTML 3.2 解析器，对 HTML5 的 {@code data-*} 属性识别不稳定，
 * 富文本编辑过程中可能被剥离。{@code class} 是标准属性，编辑过程中保留可靠。</p>
 *
 * @author xumanyi
 * @date 2026-05-17
 */
public final class EmailTemplateRenderer {

    /** 模板源串里的占位符语法：{@code ${字段名}}，字段名不允许包含 } 或空白 */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([^}\\s]+)\\}");

    /** class 属性公共前缀：渲染时拼接为 {@code flux-field-<字段名>} */
    static final String CLASS_PREFIX = "flux-field-";

    private EmailTemplateRenderer() { /* 静态工具类 */ }

    /**
     * 把模板源串渲染为富文本 HTML
     *
     * <p>对模板里的每个 {@code ${字段名}}：</p>
     * <ul>
     *   <li>{@code values} 含此 key → 替换为 {@code <span class="flux-field-字段名">值</span>}
     *       （值做 HTML 转义；值为 null 视作空串，仍保留 span 锚点以便后续累积）</li>
     *   <li>{@code values} 不含此 key → 保留 {@code ${字段名}} 原样字符串，
     *       让用户在富文本里手填后再复制</li>
     * </ul>
     *
     * @param templateSource 模板源串（{@code null} 视作空串）
     * @param values         已知字段 key → 值的映射；{@code null} 视作空 map
     * @return 渲染后的富文本 HTML 串
     * @author xumanyi
     * @date 2026-05-17
     */
    public static String renderInitial(String templateSource, Map<String, String> values) {
        return renderInitial(templateSource, values, Collections.emptySet());
    }

    /**
     * 把模板源串渲染为富文本 HTML（可指定哪些字段的值是 HTML 片段、不做 escape）
     *
     * <p>用法：某些字段的"原始值"本身已经是 HTML 片段（如"FTP版本来源"用 {@code <br>}
     * 分隔多条路径，"更新包"用 {@code <br>} 或顿号分隔多个包名），必须以 raw HTML
     * 形式保留——否则 escape 会把 {@code <br>} 转成 {@code &lt;br&gt;}，邮件就丢失换行。</p>
     *
     * <p>对 {@code rawHtmlKeys} 之外的字段仍走最小 escape（{@code & < > "}），
     * 防止用户输入的任务号 / 客服号意外含 HTML 字符破坏渲染。</p>
     *
     * @param templateSource 模板源串
     * @param values         字段值字典
     * @param rawHtmlKeys    这些 key 的值是 HTML 片段、不 escape（可为 null）
     * @return 渲染后的富文本 HTML
     * @author xumanyi
     * @date 2026-05-17
     */
    /**
     * "方案 C" 模板填充工具：把模板源串里的 {@code ${字段名}} 直接替换为字段值，
     * <b>不加</b> {@code <span class="flux-field-X">} 锚点
     *
     * <p>跟 {@link #renderInitial} 的区别：本方法产出的 HTML 是 "模板源 + 字段填值"
     * 的纯净结果，没有插件内部用的 span 包裹。适用于"模板填充工具"工作流：</p>
     *
     * <ul>
     *   <li>不再需要在富文本编辑器里识别锚点 → 不需要 span class 记号</li>
     *   <li>渲染后的 HTML 直接写入预览 / 复制到剪贴板，到企业微信邮箱样式跟模板源串一致</li>
     *   <li>未在 {@code values} 中的占位符保留为字面 {@code ${字段名}}（上层 UI 用此提示）</li>
     * </ul>
     *
     * @param templateSource 模板源串
     * @param values         字段值字典
     * @param rawHtmlKeys    这些 key 的值是 HTML 片段、不 escape（可为 null）
     * @return 替换后的 HTML 字符串
     * @author xumanyi
     * @date 2026-05-18
     */
    public static String renderInitialPlain(String templateSource, Map<String, String> values,
                                            Set<String> rawHtmlKeys) {
        if (templateSource == null) {
            return "";
        }
        Map<String, String> safeValues = (values == null) ? Map.of() : values;
        Set<String> rawKeys = (rawHtmlKeys == null) ? Collections.emptySet() : rawHtmlKeys;
        Matcher m = PLACEHOLDER.matcher(templateSource);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String key = m.group(1);
            if (safeValues.containsKey(key)) {
                String raw = safeValues.get(key);
                String content = rawKeys.contains(key)
                        ? (raw == null ? "" : raw)
                        : escapeHtml(raw == null ? "" : raw);
                m.appendReplacement(sb, Matcher.quoteReplacement(content));
            } else {
                m.appendReplacement(sb, Matcher.quoteReplacement(m.group(0)));
            }
        }
        m.appendTail(sb);
        return sb.toString();
    }

    public static String renderInitial(String templateSource, Map<String, String> values,
                                       Set<String> rawHtmlKeys) {
        if (templateSource == null) {
            return "";
        }
        Map<String, String> safeValues = (values == null) ? Map.of() : values;
        Set<String> rawKeys = (rawHtmlKeys == null) ? Collections.emptySet() : rawHtmlKeys;
        Matcher m = PLACEHOLDER.matcher(templateSource);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String key = m.group(1);
            if (safeValues.containsKey(key)) {
                String raw = safeValues.get(key);
                String content = rawKeys.contains(key)
                        ? (raw == null ? "" : raw)
                        : escapeHtml(raw == null ? "" : raw);
                String replacement = "<span class=\"" + CLASS_PREFIX + key + "\">"
                        + content + "</span>";
                m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
            } else {
                m.appendReplacement(sb, Matcher.quoteReplacement(m.group(0)));
            }
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /**
     * 在指定字段锚点的现有内容后追加新条目
     *
     * <p>用于部署成功后把新的"更新包 / FTP 路径"叠加到同一封邮件 draft 中。
     * 锚点格式由 {@link #renderInitial} 产出：{@code <span class="flux-field-字段名">现内容</span>}。</p>
     *
     * <p><b>边界</b>：</p>
     * <ul>
     *   <li>锚点找不到（用户在富文本里把整行删了 / 模板里压根没用该占位符）→
     *       原样返回 {@code htmlContent}，不报错</li>
     *   <li>锚点内现有内容为空白 → 直接置为新条目，不带 separator</li>
     *   <li>锚点内现有内容非空 → 末尾拼 {@code separator + 新条目}</li>
     *   <li>{@code newItem} 做 HTML 转义；{@code separator} 当作裸 HTML 片段处理
     *       （调用方负责传入合法 HTML，如 {@code "、"} 或 {@code "<br>"}）</li>
     * </ul>
     *
     * @param htmlContent 当前富文本 HTML
     * @param fieldKey    字段名（如 "更新包"）
     * @param newItem     新追加的条目（纯文本，由本方法做 HTML 转义）
     * @param separator   分隔符（可含 HTML 片段，如 "、" 或 "&lt;br&gt;"）
     * @return 追加后的 HTML 串
     * @author xumanyi
     * @date 2026-05-17
     */
    public static String appendAccumulate(String htmlContent, String fieldKey,
                                          String newItem, String separator) {
        return appendAccumulate(htmlContent, fieldKey, newItem, separator, true, true);
    }

    /**
     * 在指定字段锚点追加新条目，可选去重 / 可选 escape
     *
     * <p>{@code dedup=true} 时，先按 {@code separator} 拆分锚点现有内容，{@code newItem}
     * 与现有任一条目相同则不追加，避免"主面板预填的包"和"部署成功的包"双写。</p>
     *
     * <p>{@code escapeNew=false} 时 {@code newItem} 当作 raw HTML 片段（如 包含 {@code <br>}），
     * 不做 HTML 转义。调用方需自行保证安全。</p>
     *
     * @param htmlContent 当前富文本
     * @param fieldKey    字段 key
     * @param newItem     新条目（按 {@code escapeNew} 决定是否 escape）
     * @param separator   分隔符（同时用于拆分现有内容做去重）
     * @param dedup       是否去重
     * @param escapeNew   是否对 {@code newItem} 做 HTML escape
     * @return 累积后 HTML
     * @author xumanyi
     * @date 2026-05-17
     */
    public static String appendAccumulate(String htmlContent, String fieldKey,
                                          String newItem, String separator,
                                          boolean dedup, boolean escapeNew) {
        if (htmlContent == null) {
            return null;
        }
        Pattern p = Pattern.compile(
                "(<span\\s+class=\"" + Pattern.quote(CLASS_PREFIX + fieldKey) + "\">)"
                        + "(.*?)"
                        + "(</span>)",
                Pattern.DOTALL);
        Matcher m = p.matcher(htmlContent);
        if (!m.find()) {
            return htmlContent;
        }
        String prefix = m.group(1);
        String inner = m.group(2);
        String suffix = m.group(3);
        String newPiece = escapeNew ? escapeHtml(newItem) : (newItem == null ? "" : newItem);
        String sep = (separator == null) ? "" : separator;

        String newInner;
        if (inner == null || inner.trim().isEmpty()) {
            newInner = newPiece;
        } else if (dedup && !sep.isEmpty() && containsItem(inner, newPiece, sep)) {
            // 已存在相同条目，不追加
            newInner = inner;
        } else {
            newInner = inner + sep + newPiece;
        }
        return htmlContent.substring(0, m.start())
                + prefix + newInner + suffix
                + htmlContent.substring(m.end());
    }

    /**
     * 按 separator 拆分锚点内容，判断是否已含 candidate
     *
     * @param inner     锚点内现有 HTML 片段
     * @param candidate 待查 condidate
     * @param separator 分隔符
     * @return 已存在返回 true
     * @author xumanyi
     * @date 2026-05-17
     */
    private static boolean containsItem(String inner, String candidate, String separator) {
        if (inner == null || candidate == null) {
            return false;
        }
        for (String part : inner.split(Pattern.quote(separator))) {
            if (part.equals(candidate)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 把富文本 HTML 反向折算回模板源串
     *
     * <p>把所有 {@code <span class="flux-field-字段名">任意内容</span>} 整段替换为
     * {@code ${字段名}}；span 外的内容（用户手填的固定文字、签名、自定义段落）保持不变。</p>
     *
     * <p>用于「保存到模板」时把当前编辑区的富文本（含已渲染的任务号 / 客服号等实例值）
     * 折回纯模板源串，避免下次打开时把"上次的任务号"作为字面量带入。</p>
     *
     * @param htmlContent 富文本 HTML（{@code null} 视作空串）
     * @return 模板源串
     * @author xumanyi
     * @date 2026-05-17
     */
    public static String toTemplateSource(String htmlContent) {
        if (htmlContent == null) {
            return "";
        }
        Pattern spanPat = Pattern.compile(
                "<span\\s+class=\"" + Pattern.quote(CLASS_PREFIX) + "([^\"]+)\">.*?</span>",
                Pattern.DOTALL);
        Matcher m = spanPat.matcher(htmlContent);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String key = m.group(1);
            m.appendReplacement(sb, Matcher.quoteReplacement("${" + key + "}"));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /**
     * HTML 文本转义（仅最小集：&amp; &lt; &gt; &quot;）
     *
     * <p>不转义换行 —— renderer 假设调用方已经把换行作为 HTML {@code <br>} 处理，
     * 或者就是想保留 \n 作为纯文本里的换行符。</p>
     *
     * @param s 原始文本（{@code null} 视作空串）
     * @return 转义后文本
     * @author xumanyi
     * @date 2026-05-17
     */
    static String escapeHtml(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
