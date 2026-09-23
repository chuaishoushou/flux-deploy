package com.flux.deploy.ftp;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.Mockito;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link FtpOperations#uploadAtomic} 的契约测试。
 *
 * <p>锁定"最终路径上的旧文件在新文件完整传完并校验通过之前绝不被触碰"这一约定：
 * 备份文件、版本记录、回滚写回都依赖它，破坏它就等于把"写坏了没有第二份"的文件置于风险中。</p>
 *
 * @author xumanyi
 * @date 2026-09-23
 */
class FtpOperationsAtomicUploadTest {

    /** 合法远端路径（validateRemotePath 要求在 /开发/ 下） */
    private static final String FINAL_PATH = "/开发/proj/sys/pkg/main.js";
    private static final String TEMP_PATH = FINAL_PATH + FtpOperations.UPLOADING_SUFFIX;

    /**
     * 正常路径：传临时名 → 查字节数 → rename 发布，全程不碰最终路径
     *
     * @author xumanyi
     * @date 2026-09-23
     */
    @Test
    void uploadAtomic_publishesViaTempThenRename() throws Exception {
        Path local = writeLocal(3);
        FtpOperations ops = spyOps();
        doReturn(false).when(ops).exists(TEMP_PATH);
        doNothing().when(ops).upload(any(Path.class), eq(TEMP_PATH), any(), any());
        doReturn(3L).when(ops).getFileSizeQuick(TEMP_PATH);
        doNothing().when(ops).rename(TEMP_PATH, FINAL_PATH);

        ops.uploadAtomic(local, FINAL_PATH);

        InOrder order = inOrder(ops);
        order.verify(ops).upload(any(Path.class), eq(TEMP_PATH), any(), any());
        order.verify(ops).getFileSizeQuick(TEMP_PATH);
        order.verify(ops).rename(TEMP_PATH, FINAL_PATH);
        // 最终路径既没有被直接写入，也没有被删除
        verify(ops, never()).upload(any(Path.class), eq(FINAL_PATH), any(), any());
        verify(ops, never()).delete(FINAL_PATH);
    }

    /**
     * 字节数对不上（半传）：删临时文件并抛错，最终路径上的旧文件保持原样
     *
     * @author xumanyi
     * @date 2026-09-23
     */
    @Test
    void uploadAtomic_sizeMismatch_deletesTempAndKeepsFinalUntouched() throws Exception {
        Path local = writeLocal(10);
        FtpOperations ops = spyOps();
        doReturn(false).when(ops).exists(TEMP_PATH);
        doNothing().when(ops).upload(any(Path.class), eq(TEMP_PATH), any(), any());
        doReturn(4L).when(ops).getFileSizeQuick(TEMP_PATH);
        doNothing().when(ops).delete(TEMP_PATH);

        assertThatThrownBy(() -> ops.uploadAtomic(local, FINAL_PATH))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("上传字节数不一致");

        verify(ops).delete(TEMP_PATH);
        verify(ops, never()).rename(TEMP_PATH, FINAL_PATH);
        verify(ops, never()).delete(FINAL_PATH);
    }

    /**
     * 陈旧临时文件（上次中断遗留）先删除再传，避免与本次内容混淆
     *
     * @author xumanyi
     * @date 2026-09-23
     */
    @Test
    void uploadAtomic_removesStaleTempBeforeUpload() throws Exception {
        Path local = writeLocal(5);
        FtpOperations ops = spyOps();
        doReturn(true).when(ops).exists(TEMP_PATH);
        doNothing().when(ops).delete(TEMP_PATH);
        doNothing().when(ops).upload(any(Path.class), eq(TEMP_PATH), any(), any());
        doReturn(5L).when(ops).getFileSizeQuick(TEMP_PATH);
        doNothing().when(ops).rename(TEMP_PATH, FINAL_PATH);

        ops.uploadAtomic(local, FINAL_PATH);

        InOrder order = inOrder(ops);
        order.verify(ops).delete(TEMP_PATH);
        order.verify(ops).upload(any(Path.class), eq(TEMP_PATH), any(), any());
        order.verify(ops).rename(TEMP_PATH, FINAL_PATH);
    }

    /**
     * 服务端拒绝 rename 覆盖已存在文件：删旧文件后重试一次，仍在同一路径完成发布
     *
     * @author xumanyi
     * @date 2026-09-23
     */
    @Test
    void uploadAtomic_renameRejectedOnExistingTarget_deletesThenRenamesAgain() throws Exception {
        Path local = writeLocal(7);
        FtpOperations ops = spyOps();
        doReturn(false).when(ops).exists(TEMP_PATH);
        doReturn(true).when(ops).exists(FINAL_PATH);
        doNothing().when(ops).upload(any(Path.class), eq(TEMP_PATH), any(), any());
        doReturn(7L).when(ops).getFileSizeQuick(TEMP_PATH);
        doNothing().when(ops).delete(FINAL_PATH);
        Mockito.doThrow(new IOException("550 rename 到已存在文件被拒"))
                .doNothing()
                .when(ops).rename(TEMP_PATH, FINAL_PATH);

        ops.uploadAtomic(local, FINAL_PATH);

        InOrder order = inOrder(ops);
        order.verify(ops).rename(TEMP_PATH, FINAL_PATH);
        order.verify(ops).delete(FINAL_PATH);
        order.verify(ops).rename(TEMP_PATH, FINAL_PATH);
    }

    /**
     * rename 失败且最终路径确实不存在：属真失败，清临时文件后原样抛出
     *
     * @author xumanyi
     * @date 2026-09-23
     */
    @Test
    void uploadAtomic_renameFailedWithoutExistingTarget_throwsOriginalError() throws Exception {
        Path local = writeLocal(2);
        FtpOperations ops = spyOps();
        doReturn(false).when(ops).exists(TEMP_PATH);
        doReturn(false).when(ops).exists(FINAL_PATH);
        doNothing().when(ops).upload(any(Path.class), eq(TEMP_PATH), any(), any());
        doReturn(2L).when(ops).getFileSizeQuick(TEMP_PATH);
        doNothing().when(ops).delete(TEMP_PATH);
        Mockito.doThrow(new IOException("连接已断开")).when(ops).rename(TEMP_PATH, FINAL_PATH);

        assertThatThrownBy(() -> ops.uploadAtomic(local, FINAL_PATH))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("连接已断开");

        verify(ops).delete(TEMP_PATH);
        verify(ops, never()).delete(FINAL_PATH);
    }

    /**
     * 中转产物识别：上传临时文件与影子目录都算，正常业务文件名不受影响
     *
     * @author xumanyi
     * @date 2026-09-23
     */
    @Test
    void identifiesTransientArtifacts() {
        assertThat(FtpOperations.isTransientArtifactName("main.js" + FtpOperations.UPLOADING_SUFFIX)).isTrue();
        assertThat(FtpOperations.isTransientArtifactName("t0402" + FtpOperations.SHADOW_NEW_SUFFIX)).isTrue();
        assertThat(FtpOperations.isTransientArtifactName("t0402" + FtpOperations.SHADOW_OLD_SUFFIX)).isTrue();
        assertThat(FtpOperations.isTransientArtifactName("main.js")).isFalse();
        assertThat(FtpOperations.isTransientArtifactName("t0402")).isFalse();
        assertThat(FtpOperations.isTransientArtifactName(null)).isFalse();
    }

    /**
     * 构造一个 spy 版 FtpOperations（会话被 mock，不产生真实 FTP 交互）
     *
     * @return spy 实例
     * @author xumanyi
     * @date 2026-09-23
     */
    private static FtpOperations spyOps() {
        FtpSession session = Mockito.mock(FtpSession.class);
        return Mockito.spy(new FtpOperations(session));
    }

    /**
     * 生成指定字节数的本地临时文件
     *
     * @param size 字节数
     * @return 本地文件路径
     * @throws IOException 写文件失败
     * @author xumanyi
     * @date 2026-09-23
     */
    private static Path writeLocal(int size) throws IOException {
        Path local = Files.createTempFile("atomic-upload-", ".bin");
        Files.write(local, new byte[size]);
        local.toFile().deleteOnExit();
        assertThat(Files.size(local)).isEqualTo(size);
        return local;
    }
}
