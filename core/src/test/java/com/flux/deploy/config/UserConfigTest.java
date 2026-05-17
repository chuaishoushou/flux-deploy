package com.flux.deploy.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UserConfigTest {

    @Test
    void load_returnsDefaultsWhenFileMissing(@TempDir Path tmp) {
        Path missing = tmp.resolve("config.toml");
        UserConfig cfg = UserConfig.loadFrom(missing);
        assertThat(cfg.getFailureStrategy()).isEqualTo(FailureStrategy.ISOLATED);
        assertThat(cfg.getEmbedMaxRetries()).isEqualTo(1);
        assertThat(cfg.getBackupMaxRetries()).isEqualTo(1);
    }

    @Test
    void load_legacyParallelismBlock_silentlyIgnored(@TempDir Path tmp) throws Exception {
        // [parallelism] 块自 2026-05-08 起完全失效：旧配置中包含此块（无论值多奇怪）也不应报错。
        Path file = tmp.resolve("config.toml");
        Files.writeString(file, ""
                + "[parallelism]\n"
                + "backup = 99\n"
                + "embed = 0\n"
                + "embed_download = -5\n"
                + "embed_upload = abc\n"
                + "\n"
                + "[failure_strategy]\n"
                + "mode = \"isolated\"\n");
        // 不抛异常即视为通过；其它字段仍正常解析
        UserConfig cfg = UserConfig.loadFrom(file);
        assertThat(cfg.getFailureStrategy()).isEqualTo(FailureStrategy.ISOLATED);
    }

    @Test
    void load_embedMaxRetries_defaultIsOne(@TempDir Path tmp) {
        Path missing = tmp.resolve("config.toml");
        UserConfig cfg = UserConfig.loadFrom(missing);
        assertThat(cfg.getEmbedMaxRetries()).isEqualTo(1);
    }

    @Test
    void load_embedMaxRetries_canBeOverridden(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("config.toml");
        Files.writeString(file, "[retry]\nembed_max_retries = 3\n");
        UserConfig cfg = UserConfig.loadFrom(file);
        assertThat(cfg.getEmbedMaxRetries()).isEqualTo(3);
    }

    @Test
    void load_embedMaxRetries_zeroDisablesRetry(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("config.toml");
        Files.writeString(file, "[retry]\nembed_max_retries = 0\n");
        UserConfig cfg = UserConfig.loadFrom(file);
        assertThat(cfg.getEmbedMaxRetries()).isZero();
    }

    @Test
    void load_throwsWhenEmbedMaxRetriesNegative(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("config.toml");
        Files.writeString(file, "[retry]\nembed_max_retries = -1\n");
        assertThatThrownBy(() -> UserConfig.loadFrom(file))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("retry.embed_max_retries");
    }

    @Test
    void load_throwsWhenEmbedMaxRetriesAboveMax(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("config.toml");
        Files.writeString(file, "[retry]\nembed_max_retries = 6\n");
        assertThatThrownBy(() -> UserConfig.loadFrom(file))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("retry.embed_max_retries");
    }

    @Test
    void load_backupMaxRetries_defaultIsOne(@TempDir Path tmp) {
        Path missing = tmp.resolve("config.toml");
        UserConfig cfg = UserConfig.loadFrom(missing);
        assertThat(cfg.getBackupMaxRetries()).isEqualTo(1);
    }

    @Test
    void load_backupMaxRetries_canBeOverridden(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("config.toml");
        Files.writeString(file, "[retry]\nbackup_max_retries = 2\n");
        UserConfig cfg = UserConfig.loadFrom(file);
        assertThat(cfg.getBackupMaxRetries()).isEqualTo(2);
    }

    @Test
    void load_throwsWhenBackupMaxRetriesNegative(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("config.toml");
        Files.writeString(file, "[retry]\nbackup_max_retries = -1\n");
        assertThatThrownBy(() -> UserConfig.loadFrom(file))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("retry.backup_max_retries");
    }

    @Test
    void defaultTemplate_canBeReParsedToProduceDefaultValues(@TempDir Path tmp) throws Exception {
        // 关键不变量：自动生成的模板内容必须与代码内默认值产生完全相同的 UserConfig
        // 否则用户首次使用看到的"默认值"与"模板字面量"不一致，会困惑
        Path templatePath = tmp.resolve("config.toml");

        java.lang.reflect.Field f = UserConfig.class.getDeclaredField("DEFAULT_TEMPLATE");
        f.setAccessible(true);
        String template = (String) f.get(null);
        Files.writeString(templatePath, template);

        UserConfig fromTemplate = UserConfig.loadFrom(templatePath);
        UserConfig fromMissing = UserConfig.loadFrom(tmp.resolve("missing.toml"));

        assertThat(fromTemplate.getFailureStrategy()).isEqualTo(fromMissing.getFailureStrategy());
        assertThat(fromTemplate.getEmbedMaxRetries()).isEqualTo(fromMissing.getEmbedMaxRetries());
        assertThat(fromTemplate.getBackupMaxRetries()).isEqualTo(fromMissing.getBackupMaxRetries());
    }

    @Test
    void defaultConfigPath_pointsToHomeFluxDeploy() {
        Path p = UserConfig.defaultConfigPath();
        assertThat(p.toString())
                .endsWith("/.flux-deploy/config.toml")
                .startsWith(System.getProperty("user.home"));
    }

    @Test
    void load_supportsTrailingLineComments(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("config.toml");
        Files.writeString(file, ""
                + "[failure_strategy]\n"
                + "mode = \"rollback_all\"   # 字符串后注释\n");
        UserConfig cfg = UserConfig.loadFrom(file);
        assertThat(cfg.getFailureStrategy()).isEqualTo(FailureStrategy.ROLLBACK_ALL);
    }

    @Test
    void load_throwsOnUnknownStrategyMode(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("config.toml");
        Files.writeString(file, "[failure_strategy]\nmode = \"continue\"\n");
        assertThatThrownBy(() -> UserConfig.loadFrom(file))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("failure_strategy.mode");
    }
}
