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
    void ensureInitialized_autoUpgradesLegacyDefaultTemplate(@TempDir Path tmp) throws IOException {
        Path root = tmp.resolve("templates");
        Files.createDirectories(root);
        // 模拟用户机器上残留的 v1 老模板（含 ${收件人} ${项目名} 等 14 个占位符）
        String legacy = "${收件人}：<br>"
                + "&nbsp;&nbsp;你好！<br>"
                + "&nbsp;&nbsp;【${项目名}】${标题}<br>"
                + "&nbsp;&nbsp;更新内容: ${标题}<br>"
                + "&nbsp;&nbsp;客服编号：${客服编号}<br>"
                + "&nbsp;&nbsp;任务编号：${任务号}<br>"
                + "&nbsp;&nbsp;资源来源：${资源来源}<br>"
                + "&nbsp;&nbsp;FTP版本来源：${FTP版本来源}<br>"
                + "&nbsp;&nbsp;备份包：${备份包}<br>"
                + "&nbsp;&nbsp;更新方式：${更新方式}<br>"
                + "&nbsp;&nbsp;是否重启：${是否重启}<br>"
                + "&nbsp;&nbsp;浏览器缓存刷新：${浏览器缓存刷新}<br>"
                + "&nbsp;&nbsp;影响范围：${影响范围}<br>"
                + "&nbsp;&nbsp;SQL: ${SQL}<br>"
                + "&nbsp;&nbsp;更新包: ${更新包}<br>";
        Files.writeString(root.resolve("default.html"), legacy);

        EmailTemplateStore store = new EmailTemplateStore(root);
        store.ensureInitialized();
        // 应该升级为新内置模板
        assertThat(Files.readString(root.resolve("default.html")))
                .isEqualTo(EmailTemplateStore.BUILTIN_DEFAULT_TEMPLATE);
    }

    @Test
    void ensureInitialized_fuzzyMatchUpgradesPartialLegacy(@TempDir Path tmp) throws IOException {
        // 不完整的老模板（含部分老占位符 + 用户改过的部分），fuzzy match 仍判定升级
        Path root = tmp.resolve("templates");
        Files.createDirectories(root);
        String partialLegacy = "帝迈顾问：<br>"
                + "&nbsp;&nbsp;你好！<br>"
                + "&nbsp;&nbsp;【${项目名}】${标题}<br>"
                + "&nbsp;&nbsp;客服编号：${客服编号}<br>"
                + "&nbsp;&nbsp;任务编号：${任务号}<br>"
                + "&nbsp;&nbsp;资源来源：${资源来源}<br>"
                + "&nbsp;&nbsp;更新包: ${更新包}<br>";
        Files.writeString(root.resolve("default.html"), partialLegacy);

        EmailTemplateStore store = new EmailTemplateStore(root);
        store.ensureInitialized();

        // default.html 升级
        assertThat(Files.readString(root.resolve("default.html")))
                .isEqualTo(EmailTemplateStore.BUILTIN_DEFAULT_TEMPLATE);
        // 备份保留。注意 ensureInitialized 先跑 migrateRenamedPlaceholders（${客服编号}→${客服}、
        // ${任务号}→${任务}）再做 legacy 升级备份，所以备份的是占位符迁移后的内容。
        String expectedBak = partialLegacy
                .replace("${客服编号}", "${客服}")
                .replace("${任务号}", "${任务}");
        assertThat(Files.readString(root.resolve("default.legacy.bak")))
                .isEqualTo(expectedBak);
    }

    @Test
    void containsAnyLegacyPlaceholder_detectsKnownTokens() {
        assertThat(EmailTemplateStore.containsAnyLegacyPlaceholder("包含 ${项目名} 的内容"))
                .isTrue();
        assertThat(EmailTemplateStore.containsAnyLegacyPlaceholder("含 ${资源来源} 的"))
                .isTrue();
        assertThat(EmailTemplateStore.containsAnyLegacyPlaceholder("无老占位符 ${任务号} ${客服编号}"))
                .isFalse();
        assertThat(EmailTemplateStore.containsAnyLegacyPlaceholder(null)).isFalse();
        assertThat(EmailTemplateStore.containsAnyLegacyPlaceholder("")).isFalse();
    }

    @Test
    void ensureInitialized_doesNotUpgradeUserModifiedTemplate(@TempDir Path tmp) throws IOException {
        Path root = tmp.resolve("templates");
        Files.createDirectories(root);
        // 用户改过的内容（不含 v1 老占位符 ${项目名} 等，不触发整模板升级覆盖）
        String userEdited = "亲爱的：<br>"
                + "&nbsp;&nbsp;你好，更新到了<br>"
                + "&nbsp;&nbsp;任务: ${任务号}<br>";
        Files.writeString(root.resolve("default.html"), userEdited);

        new EmailTemplateStore(root).ensureInitialized();
        // 不会被升级覆盖成 BUILTIN；但 ${任务号}→${任务} 这类重命名占位符会被
        // migrateRenamedPlaceholders 无损迁移，属预期行为。
        String expectedAfterMigration = userEdited.replace("${任务号}", "${任务}");
        assertThat(Files.readString(root.resolve("default.html"))).isEqualTo(expectedAfterMigration);
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
