package com.flux.deploy.email;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class EmailTemplateRendererTest {

    @Test
    void renderInitial_replacesKnownPlaceholdersAndWrapsInSpan() {
        Map<String, String> values = new HashMap<>();
        values.put("任务号", "T20260517");
        values.put("客服编号", "C001");

        String source = "任务号: ${任务号}\n客服编号: ${客服编号}";
        String html = EmailTemplateRenderer.renderInitial(source, values);

        assertThat(html).contains("<span class=\"flux-field-任务号\">T20260517</span>");
        assertThat(html).contains("<span class=\"flux-field-客服编号\">C001</span>");
    }

    @Test
    void renderInitial_preservesUnknownPlaceholders() {
        String source = "任务: ${任务号}, SQL: ${SQL}";
        String html = EmailTemplateRenderer.renderInitial(source, Map.of("任务号", "T1"));
        assertThat(html).contains("<span class=\"flux-field-任务号\">T1</span>");
        // SQL 不在 values 里 → 保留 ${SQL} 字面量
        assertThat(html).contains("${SQL}");
    }

    @Test
    void renderInitial_emptyValueStillProducesAnchorSpan() {
        // 即便值是空串也要保留 span，便于后续 appendAccumulate 定位
        Map<String, String> values = Map.of("更新包", "");
        String html = EmailTemplateRenderer.renderInitial("更新包: ${更新包}", values);
        assertThat(html).contains("<span class=\"flux-field-更新包\"></span>");
    }

    @Test
    void renderInitial_escapesHtmlInValues() {
        Map<String, String> values = Map.of("任务号", "<script>alert('x')</script>");
        String html = EmailTemplateRenderer.renderInitial("${任务号}", values);
        assertThat(html).contains("&lt;script&gt;");
        assertThat(html).doesNotContain("<script>");
    }

    @Test
    void renderInitial_nullTemplateReturnsEmpty() {
        assertThat(EmailTemplateRenderer.renderInitial(null, Map.of())).isEmpty();
    }

    @Test
    void appendAccumulate_emptyAnchorTakesItemAsIs() {
        String html = "<span class=\"flux-field-更新包\"></span>";
        String result = EmailTemplateRenderer.appendAccumulate(html, "更新包", "pkg1.jar", "、");
        assertThat(result).isEqualTo("<span class=\"flux-field-更新包\">pkg1.jar</span>");
    }

    @Test
    void appendAccumulate_appendsWithSeparator() {
        String html = "<span class=\"flux-field-更新包\">pkg1.jar</span>";
        String result = EmailTemplateRenderer.appendAccumulate(html, "更新包", "pkg2.jar", "、");
        assertThat(result).isEqualTo("<span class=\"flux-field-更新包\">pkg1.jar、pkg2.jar</span>");
    }

    @Test
    void appendAccumulate_ftpPathSeparatorAcceptsHtmlBreak() {
        String html = "<span class=\"flux-field-FTP版本来源\">/a/b.jar</span>";
        String result = EmailTemplateRenderer.appendAccumulate(html, "FTP版本来源",
                "/a/c.war", "<br>");
        assertThat(result).contains("/a/b.jar<br>/a/c.war");
    }

    @Test
    void appendAccumulate_missingAnchorReturnsUnchanged() {
        // 用户在富文本里把整行删了 / 模板压根没用该占位符 → 静默忽略
        String html = "<p>no anchor here</p>";
        String result = EmailTemplateRenderer.appendAccumulate(html, "更新包", "pkg.jar", "、");
        assertThat(result).isEqualTo(html);
    }

    @Test
    void appendAccumulate_escapesNewItem() {
        String html = "<span class=\"flux-field-x\">a</span>";
        String result = EmailTemplateRenderer.appendAccumulate(html, "x", "<b>", "、");
        assertThat(result).contains("a、&lt;b&gt;");
    }

    @Test
    void toTemplateSource_replacesSpanBackToPlaceholder() {
        String html = "任务: <span class=\"flux-field-任务号\">T1</span> 完成";
        String source = EmailTemplateRenderer.toTemplateSource(html);
        assertThat(source).isEqualTo("任务: ${任务号} 完成");
    }

    @Test
    void toTemplateSource_handlesMultipleSpans() {
        String html = "<span class=\"flux-field-a\">1</span> "
                + "<span class=\"flux-field-b\">2</span>";
        assertThat(EmailTemplateRenderer.toTemplateSource(html)).isEqualTo("${a} ${b}");
    }

    @Test
    void toTemplateSource_keepsContentOutsideSpans() {
        // 用户加在 span 外的自定义文字（签名 / 备注）应保留
        String html = "你好！<span class=\"flux-field-任务号\">T1</span>\n签名：小明";
        String source = EmailTemplateRenderer.toTemplateSource(html);
        assertThat(source).isEqualTo("你好！${任务号}\n签名：小明");
    }

    @Test
    void renderInitial_rawHtmlKeyKeepsBrTag() {
        // FTP 版本来源用 <br> 分隔多条路径，必须以 raw HTML 渲染，不能 escape
        Map<String, String> values = Map.of("FTP版本来源", "/a/x.jar<br>/a/y.jar");
        String html = EmailTemplateRenderer.renderInitial(
                "${FTP版本来源}", values, Set.of("FTP版本来源"));
        assertThat(html).contains("/a/x.jar<br>/a/y.jar");
        assertThat(html).doesNotContain("&lt;br&gt;");
    }

    @Test
    void renderInitial_nonRawKeyStillEscapesBr() {
        // 同样的内容，未在 rawHtmlKeys 中时仍走 escape
        Map<String, String> values = Map.of("x", "<br>");
        String html = EmailTemplateRenderer.renderInitial("${x}", values, Set.of());
        assertThat(html).contains("&lt;br&gt;");
    }

    @Test
    void appendAccumulate_dedupSkipsDuplicateItem() {
        String html = "<span class=\"flux-field-更新包\">pkg1.jar、pkg2.jar</span>";
        // 试图加入已存在的 pkg1.jar → 应被去重跳过
        String result = EmailTemplateRenderer.appendAccumulate(
                html, "更新包", "pkg1.jar", "、", true, true);
        assertThat(result).isEqualTo(html);
    }

    @Test
    void appendAccumulate_dedupAcceptsDistinctItem() {
        String html = "<span class=\"flux-field-更新包\">pkg1.jar</span>";
        String result = EmailTemplateRenderer.appendAccumulate(
                html, "更新包", "pkg2.jar", "、", true, true);
        assertThat(result).contains("pkg1.jar、pkg2.jar");
    }

    @Test
    void appendAccumulate_escapeNewFalseKeepsRawHtml() {
        String html = "<span class=\"flux-field-FTP版本来源\">/a/x.jar</span>";
        String result = EmailTemplateRenderer.appendAccumulate(
                html, "FTP版本来源", "<i>/a/y.jar</i>", "<br>", true, false);
        // escapeNew=false：<i> 保持原样
        assertThat(result).contains("<i>/a/y.jar</i>");
    }

    @Test
    void roundTrip_renderThenToTemplateSource_isIdempotent() {
        String original = "任务: ${任务号}\nSQL: ${SQL}";
        Map<String, String> values = Map.of("任务号", "T1");
        String rendered = EmailTemplateRenderer.renderInitial(original, values);
        String back = EmailTemplateRenderer.toTemplateSource(rendered);
        // 反向之后未知占位符 ${SQL} 仍保留为字面量
        assertThat(back).isEqualTo("任务: ${任务号}\nSQL: ${SQL}");
    }
}
