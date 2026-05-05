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
        assertThat(cfg.getBackupParallelism()).isEqualTo(3);
        assertThat(cfg.getEmbedParallelism()).isEqualTo(3);
        assertThat(cfg.getEmbedDownloadParallelism()).isEqualTo(3);
        assertThat(cfg.getEmbedUploadParallelism()).isEqualTo(3);
        assertThat(cfg.getFailureStrategy()).isEqualTo(FailureStrategy.ISOLATED);
    }

    @Test
    void load_embedDownloadAndUpload_fallbackToEmbedWhenMissing(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("config.toml");
        Files.writeString(file, "[parallelism]\nembed = 3\n");
        UserConfig cfg = UserConfig.loadFrom(file);
        assertThat(cfg.getEmbedParallelism()).isEqualTo(3);
        assertThat(cfg.getEmbedDownloadParallelism()).isEqualTo(3);  // 回退到 embed
        assertThat(cfg.getEmbedUploadParallelism()).isEqualTo(3);    // 回退到 embed
    }

    @Test
    void load_embedDownloadAndUpload_canBeIndependent(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("config.toml");
        Files.writeString(file, ""
                + "[parallelism]\n"
                + "embed = 2\n"
                + "embed_download = 4\n"
                + "embed_upload = 6\n");
        UserConfig cfg = UserConfig.loadFrom(file);
        assertThat(cfg.getEmbedParallelism()).isEqualTo(2);
        assertThat(cfg.getEmbedDownloadParallelism()).isEqualTo(4);
        assertThat(cfg.getEmbedUploadParallelism()).isEqualTo(6);
    }

    @Test
    void load_throwsWhenEmbedDownloadOutOfRange(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("config.toml");
        Files.writeString(file, "[parallelism]\nembed_download = 11\n");
        assertThatThrownBy(() -> UserConfig.loadFrom(file))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("parallelism.embed_download");
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

        // 模拟首次使用：通过反射或 package-private API 触发模板写入
        // 这里通过空文件路径 → load 走 default branch，但我们直接验证模板字符串能 round-trip
        // 更直接的测试方式：用反射访问 DEFAULT_TEMPLATE，写出来再 loadFrom
        java.lang.reflect.Field f = UserConfig.class.getDeclaredField("DEFAULT_TEMPLATE");
        f.setAccessible(true);
        String template = (String) f.get(null);
        Files.writeString(templatePath, template);

        UserConfig fromTemplate = UserConfig.loadFrom(templatePath);
        UserConfig fromMissing = UserConfig.loadFrom(tmp.resolve("missing.toml"));

        assertThat(fromTemplate.getBackupParallelism()).isEqualTo(fromMissing.getBackupParallelism());
        assertThat(fromTemplate.getEmbedParallelism()).isEqualTo(fromMissing.getEmbedParallelism());
        assertThat(fromTemplate.getEmbedDownloadParallelism())
                .isEqualTo(fromMissing.getEmbedDownloadParallelism());
        assertThat(fromTemplate.getEmbedUploadParallelism())
                .isEqualTo(fromMissing.getEmbedUploadParallelism());
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
                + "[parallelism]\n"
                + "backup = 4    # 这是行尾注释\n"
                + "embed = 5     # 另一段注释\n"
                + "\n"
                + "[failure_strategy]\n"
                + "mode = \"rollback_all\"   # 字符串后注释\n");
        UserConfig cfg = UserConfig.loadFrom(file);
        assertThat(cfg.getBackupParallelism()).isEqualTo(4);
        assertThat(cfg.getEmbedParallelism()).isEqualTo(5);
        assertThat(cfg.getFailureStrategy()).isEqualTo(FailureStrategy.ROLLBACK_ALL);
    }

    @Test
    void load_parsesAllFields(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("config.toml");
        Files.writeString(file, ""
                + "[parallelism]\n"
                + "backup = 3\n"
                + "embed = 4\n"
                + "\n"
                + "[failure_strategy]\n"
                + "mode = \"rollback_all\"\n");
        UserConfig cfg = UserConfig.loadFrom(file);
        assertThat(cfg.getBackupParallelism()).isEqualTo(3);
        assertThat(cfg.getEmbedParallelism()).isEqualTo(4);
        assertThat(cfg.getFailureStrategy()).isEqualTo(FailureStrategy.ROLLBACK_ALL);
    }

    @Test
    void load_partialFileUsesDefaultsForMissingFields(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("config.toml");
        Files.writeString(file, "[parallelism]\nbackup = 2\n");
        UserConfig cfg = UserConfig.loadFrom(file);
        assertThat(cfg.getBackupParallelism()).isEqualTo(2);
        assertThat(cfg.getEmbedParallelism()).isEqualTo(3);
        assertThat(cfg.getFailureStrategy()).isEqualTo(FailureStrategy.ISOLATED);
    }

    @Test
    void load_throwsWhenParallelismBelowOne(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("config.toml");
        Files.writeString(file, "[parallelism]\nbackup = 0\n");
        assertThatThrownBy(() -> UserConfig.loadFrom(file))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("parallelism.backup")
                .hasMessageContaining("[1, 10]");
    }

    @Test
    void load_throwsWhenParallelismAboveTen(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("config.toml");
        Files.writeString(file, "[parallelism]\nembed = 11\n");
        assertThatThrownBy(() -> UserConfig.loadFrom(file))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("parallelism.embed")
                .hasMessageContaining("[1, 10]");
    }

    @Test
    void load_throwsOnUnknownStrategyMode(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("config.toml");
        Files.writeString(file, "[failure_strategy]\nmode = \"continue\"\n");
        assertThatThrownBy(() -> UserConfig.loadFrom(file))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("failure_strategy.mode");
    }

    @Test
    void load_throwsWhenIntegerFieldNotInteger(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("config.toml");
        Files.writeString(file, "[parallelism]\nbackup = \"five\"\n");
        assertThatThrownBy(() -> UserConfig.loadFrom(file))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("parallelism.backup");
    }
}
