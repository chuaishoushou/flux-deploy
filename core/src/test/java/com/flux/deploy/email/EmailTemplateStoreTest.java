package com.flux.deploy.email;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EmailTemplateStoreTest {

    @Test
    void ensureInitialized_createsDirAndDefaultTemplate(@TempDir Path tmp) throws IOException {
        Path root = tmp.resolve("templates");
        EmailTemplateStore store = new EmailTemplateStore(root);
        store.ensureInitialized();

        assertThat(Files.isDirectory(root)).isTrue();
        Path defaultFile = root.resolve("default.html");
        assertThat(Files.isReadable(defaultFile)).isTrue();
        assertThat(Files.readString(defaultFile))
                .isEqualTo(EmailTemplateStore.BUILTIN_DEFAULT_TEMPLATE);
    }

    @Test
    void ensureInitialized_isIdempotent(@TempDir Path tmp) throws IOException {
        Path root = tmp.resolve("templates");
        EmailTemplateStore store = new EmailTemplateStore(root);
        store.ensureInitialized();
        // 改一下 default 内容
        Files.writeString(root.resolve("default.html"), "CUSTOMIZED");
        // 第二次调用不应覆盖用户改过的内容
        store.ensureInitialized();
        assertThat(Files.readString(root.resolve("default.html"))).isEqualTo("CUSTOMIZED");
    }

    @Test
    void ensureInitialized_doesNotTouchExistingDefault(@TempDir Path tmp) throws IOException {
        // 新定位：default 是用户可编辑的普通模板，已存在就原样保留，插件绝不自动升级 / 覆盖。
        // （含 v1 老占位符也照样保留——不再有 legacy 自动升级。）
        Path root = tmp.resolve("templates");
        Files.createDirectories(root);
        String userEdited = "${收件人}：<br>"
                + "&nbsp;&nbsp;你好！<br>"
                + "&nbsp;&nbsp;【${项目名}】${标题}<br>";
        Files.writeString(root.resolve("default.html"), userEdited);

        new EmailTemplateStore(root).ensureInitialized();
        // 内容原样保留，不被升级成 BUILTIN。仅 ${任务号}/${客服编号} 这类已重命名占位符会被
        // migrateRenamedPlaceholders 无损迁移（此例无这两个占位符，故完全不变）。
        assertThat(Files.readString(root.resolve("default.html"))).isEqualTo(userEdited);
        // 不再产生 legacy 备份文件
        assertThat(Files.exists(root.resolve("default.legacy.bak"))).isFalse();
    }

    @Test
    void migrateRenamedPlaceholders_stillRunsOnExistingTemplates(@TempDir Path tmp) throws IOException {
        Path root = tmp.resolve("templates");
        Files.createDirectories(root);
        // 含已重命名占位符的用户模板：ensureInitialized 会无损迁移 ${任务号}→${任务}、${客服编号}→${客服}
        String userEdited = "亲爱的：<br>"
                + "&nbsp;&nbsp;任务: ${任务号}<br>"
                + "&nbsp;&nbsp;客服: ${客服编号}<br>";
        Files.writeString(root.resolve("default.html"), userEdited);

        new EmailTemplateStore(root).ensureInitialized();
        String expected = userEdited.replace("${任务号}", "${任务}").replace("${客服编号}", "${客服}");
        assertThat(Files.readString(root.resolve("default.html"))).isEqualTo(expected);
    }

    @Test
    void restoreToBuiltinDefault_resetsAnyTemplateToBuiltin(@TempDir Path tmp) throws IOException {
        // 新定位：default 与非 default 一视同仁，恢复默认统一重置为插件内置内容。
        EmailTemplateStore store = new EmailTemplateStore(tmp.resolve("templates"));
        store.createNew("custom", "用户随便写的内容");
        // 把 default 也改成别的内容，验证恢复后同样回到 BUILTIN
        store.save("default", "被改过的默认内容");

        store.restoreToBuiltinDefault("custom");
        store.restoreToBuiltinDefault("default");

        assertThat(store.loadOrDefault("custom")).isEqualTo(EmailTemplateStore.BUILTIN_DEFAULT_TEMPLATE);
        assertThat(store.loadOrDefault("default")).isEqualTo(EmailTemplateStore.BUILTIN_DEFAULT_TEMPLATE);
    }

    @Test
    void listNames_defaultAlwaysFirst(@TempDir Path tmp) throws IOException {
        Path root = tmp.resolve("templates");
        EmailTemplateStore store = new EmailTemplateStore(root);
        store.ensureInitialized();
        store.createNew("zebra", "z");
        store.createNew("alpha", "a");
        store.createNew("mango", "m");

        // listNames 现按文件 creationTime 升序（新建的排末尾），default 永远第一。
        // 其余顺序依赖文件系统的 creationTime 精度（ext4 等回落 lastModifiedTime），
        // 这里只严格断言「default 第一 + 全部列出」，不锁定其余次序以免精度差异导致 flaky。
        List<String> names = store.listNames();
        assertThat(names).hasSize(4);
        assertThat(names.get(0)).isEqualTo("default");
        assertThat(names).containsExactlyInAnyOrder("default", "alpha", "mango", "zebra");
    }

    @Test
    void loadOrDefault_fallsBackWhenFileMissing(@TempDir Path tmp) {
        Path root = tmp.resolve("missing-dir");
        EmailTemplateStore store = new EmailTemplateStore(root);
        // 文件不存在，ensureInitialized 又因为路径只读失败的情况下，仍能返回内置默认
        String content = store.loadOrDefault("nonexistent");
        assertThat(content).isEqualTo(EmailTemplateStore.BUILTIN_DEFAULT_TEMPLATE);
    }

    @Test
    void save_overwritesExisting(@TempDir Path tmp) throws IOException {
        EmailTemplateStore store = new EmailTemplateStore(tmp);
        store.save("foo", "first");
        store.save("foo", "second");
        assertThat(store.loadOrDefault("foo")).isEqualTo("second");
    }

    @Test
    void createNew_rejectsDuplicate(@TempDir Path tmp) throws IOException {
        EmailTemplateStore store = new EmailTemplateStore(tmp);
        store.createNew("foo", "a");
        assertThatThrownBy(() -> store.createNew("foo", "b"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("已存在");
    }

    @Test
    void delete_works(@TempDir Path tmp) throws IOException {
        EmailTemplateStore store = new EmailTemplateStore(tmp);
        store.createNew("foo", "x");
        assertThat(store.listNames()).contains("foo");
        store.delete("foo");
        assertThat(store.listNames()).doesNotContain("foo");
    }

    @Test
    void delete_defaultRejected(@TempDir Path tmp) {
        EmailTemplateStore store = new EmailTemplateStore(tmp);
        assertThatThrownBy(() -> store.delete("default"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("default");
    }

    @Test
    void delete_nonexistentIsIdempotent(@TempDir Path tmp) throws IOException {
        EmailTemplateStore store = new EmailTemplateStore(tmp);
        // 不抛
        store.delete("doesnotexist");
    }

    @Test
    void invalidNames_rejected(@TempDir Path tmp) {
        EmailTemplateStore store = new EmailTemplateStore(tmp);
        assertThatThrownBy(() -> store.createNew("", "x"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.createNew("with/slash", "x"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.createNew("with space", "x"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.createNew("../escape", "x"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.createNew("hidden.foo", "x"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
