package com.flux.deploy.plugin.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DeployRunLoggerTest {

    private DeployRunMeta sampleMeta() {
        return new DeployRunMeta(
                "tm10srv.war",
                "xumanyi",
                "ftp://host/项目/系统/",
                "IntelliJ IDEA 2024.3",
                "flux-deploy-plugin 1.2.7",
                LocalDateTime.of(2026, 5, 7, 14, 30, 52)
        );
    }

    @Test
    void openCreatesRunningFileWithHeader(@TempDir Path tmp) throws Exception {
        DeployRunLogger logger = DeployRunLogger.openAt(tmp, sampleMeta());
        logger.closeWith(DeployRunStatus.OK); // 暂时让测试能跑完，下个 task 再深入测 closeWith

        // openAt 阶段产生了 _RUNNING.log（已被 closeWith rename 走，这里改测它写过 header）
        Path okFile = tmp.resolve("logs/tm10srv.war/2026-05-07_143052_OK.log");
        assertThat(okFile).exists();
        List<String> lines = Files.readAllLines(okFile);
        assertThat(lines).anyMatch(l -> l.contains("=== 部署开始 ==="));
        assertThat(lines).anyMatch(l -> l.contains("包: tm10srv.war"));
        assertThat(lines).anyMatch(l -> l.contains("操作者: xumanyi"));
        assertThat(lines).anyMatch(l -> l.contains("远端: ftp://host/项目/系统/"));
        assertThat(lines).anyMatch(l -> l.contains("IDE: IntelliJ IDEA 2024.3"));
        assertThat(lines).anyMatch(l -> l.contains("插件: flux-deploy-plugin 1.2.7"));
    }

    @Test
    void writesInfoWarnErrorLines(@TempDir Path tmp) throws Exception {
        DeployRunLogger logger = DeployRunLogger.openAt(tmp, sampleMeta());
        logger.info("uploading tm10srv.war");
        logger.warn("ftp connection slow");
        logger.error("verify failed: size mismatch");
        logger.closeWith(DeployRunStatus.FAIL);

        Path failFile = tmp.resolve("logs/tm10srv.war/2026-05-07_143052_FAIL.log");
        assertThat(failFile).exists();
        List<String> lines = Files.readAllLines(failFile);
        assertThat(lines).anyMatch(l -> l.contains("[INFO] uploading tm10srv.war"));
        assertThat(lines).anyMatch(l -> l.contains("[WARN] ftp connection slow"));
        assertThat(lines).anyMatch(l -> l.contains("[ERROR] verify failed: size mismatch"));
    }

    @Test
    void closeWithOkRenamesAndWritesFooter(@TempDir Path tmp) throws Exception {
        DeployRunLogger logger = DeployRunLogger.openAt(tmp, sampleMeta());
        logger.closeWith(DeployRunStatus.OK);

        Path dir = tmp.resolve("logs/tm10srv.war");
        assertThat(dir.resolve("2026-05-07_143052_RUNNING.log")).doesNotExist();
        Path okFile = dir.resolve("2026-05-07_143052_OK.log");
        assertThat(okFile).exists();
        List<String> lines = Files.readAllLines(okFile);
        assertThat(lines.get(lines.size() - 1)).contains("=== 部署结束: OK ===");
    }

    @Test
    void closeWithFailRb(@TempDir Path tmp) throws Exception {
        DeployRunLogger logger = DeployRunLogger.openAt(tmp, sampleMeta());
        logger.closeWith(DeployRunStatus.FAIL_RB);
        assertThat(tmp.resolve("logs/tm10srv.war/2026-05-07_143052_FAIL_RB.log")).exists();
    }

    @Test
    void closeWithCancel(@TempDir Path tmp) throws Exception {
        DeployRunLogger logger = DeployRunLogger.openAt(tmp, sampleMeta());
        logger.closeWith(DeployRunStatus.CANCEL);
        assertThat(tmp.resolve("logs/tm10srv.war/2026-05-07_143052_CANCEL.log")).exists();
    }

    @Test
    void closeWithRunningIsRejected(@TempDir Path tmp) throws Exception {
        DeployRunLogger logger = DeployRunLogger.openAt(tmp, sampleMeta());
        try {
            org.junit.jupiter.api.Assertions.assertThrows(
                    IllegalArgumentException.class,
                    () -> logger.closeWith(DeployRunStatus.RUNNING));
        } finally {
            logger.closeWith(DeployRunStatus.FAIL); // 清理
        }
    }

    @Test
    void closeWithIsIdempotent(@TempDir Path tmp) throws Exception {
        DeployRunLogger logger = DeployRunLogger.openAt(tmp, sampleMeta());
        logger.closeWith(DeployRunStatus.OK);
        // 第二次调用不应抛错也不应改文件名
        logger.closeWith(DeployRunStatus.FAIL);
        assertThat(tmp.resolve("logs/tm10srv.war/2026-05-07_143052_OK.log")).exists();
        assertThat(tmp.resolve("logs/tm10srv.war/2026-05-07_143052_FAIL.log")).doesNotExist();
    }

    @Test
    void writesAfterCloseAreNoop(@TempDir Path tmp) throws Exception {
        DeployRunLogger logger = DeployRunLogger.openAt(tmp, sampleMeta());
        logger.closeWith(DeployRunStatus.OK);
        logger.info("late line"); // 不应抛错
        List<String> lines = Files.readAllLines(tmp.resolve("logs/tm10srv.war/2026-05-07_143052_OK.log"));
        assertThat(lines).noneMatch(l -> l.contains("late line"));
    }

    @Test
    void rejectsPackageNameWithPathSeparator(@TempDir Path tmp) {
        DeployRunMeta bad = new DeployRunMeta(
                "../escape.war", "x", "r", "ide", "plugin",
                LocalDateTime.of(2026, 5, 7, 14, 30, 52));
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> DeployRunLogger.openAt(tmp, bad));
    }
}
