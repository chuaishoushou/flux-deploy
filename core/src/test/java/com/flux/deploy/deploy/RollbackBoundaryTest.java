package com.flux.deploy.deploy;

import com.flux.deploy.ftp.FtpLock;
import com.flux.deploy.ftp.FtpOperations;
import com.flux.deploy.model.TargetPackage;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RollbackBoundaryTest {

    @Test
    void completedTarget_doesNothing() throws Exception {
        FtpOperations ops = Mockito.mock(FtpOperations.class);
        FtpLock lock = Mockito.mock(FtpLock.class);
        Rollback rb = new Rollback(ops, lock);

        TargetPackage t = new TargetPackage();
        t.setPackageName("a.war");
        t.setRemoteDir("/d/");
        t.setRemotePath("/d/a.war");
        t.setBackupRemotePath("/d/backup/.../a.war");
        t.setStatus(TargetPackage.Status.COMPLETED);

        boolean acted = rb.rollbackTarget(t);

        assertThat(acted).isFalse();
        verify(ops, never()).download(Mockito.anyString(), Mockito.any());
        verify(ops, never()).download(Mockito.anyString(), Mockito.any(), Mockito.any(), Mockito.any());
        verify(ops, never()).upload(Mockito.any(), Mockito.anyString());
        verify(ops, never()).upload(Mockito.any(), Mockito.anyString(), Mockito.any(), Mockito.any());
        verify(ops, never()).uploadAtomic(Mockito.any(), Mockito.anyString());
        verify(ops, never()).uploadAtomic(Mockito.any(), Mockito.anyString(), Mockito.any(), Mockito.any());
        verify(lock, never()).restoreLock(Mockito.anyString(), Mockito.anyString());
    }

    /**
     * 漏洞 H1：UPLOADED 状态有备份时必须走 restoreFromBackup，
     * 不能用 restoreLock rename（多数 FTP 服务端拒绝 rename 到已存在路径返回 550）。
     */
    @Test
    void uploadedWithBackup_restoresFromBackup_notRestoreLock() throws Exception {
        FtpOperations ops = Mockito.mock(FtpOperations.class);
        FtpLock lock = Mockito.mock(FtpLock.class);
        Rollback rb = new Rollback(ops, lock);

        TargetPackage t = new TargetPackage();
        t.setPackageName("a.war");
        t.setRemoteDir("/d/");
        t.setRemotePath("/d/a.war");
        t.setBackupRemotePath("/d/backup/x/a.war");
        t.setLockName("a.war__LOCK__op_20260701_000000_TTL10");
        t.setStatus(TargetPackage.Status.UPLOADED);

        boolean acted = rb.rollbackTarget(t);

        assertThat(acted).isTrue();
        assertThat(t.getStatus()).isEqualTo(TargetPackage.Status.ROLLED_BACK);
        verify(ops).download(Mockito.eq("/d/backup/x/a.war"), Mockito.any(), Mockito.anyString(), Mockito.any());
        verify(ops).uploadAtomic(Mockito.any(), Mockito.eq("/d/a.war"), Mockito.anyString(), Mockito.any());
        verify(lock, never()).restoreLock(Mockito.anyString(), Mockito.anyString());
    }

    /**
     * 漏洞 H1 兜底：UPLOADED 状态无备份但有锁包时，先 delete 原路径再 restoreLock 锁包，
     * 避免 rename 撞已存在路径。
     */
    @Test
    void uploadedWithoutBackup_butWithLock_deletesRemoteThenRestoresLock() throws Exception {
        FtpOperations ops = Mockito.mock(FtpOperations.class);
        FtpLock lock = Mockito.mock(FtpLock.class);
        when(ops.exists("/d/a.war")).thenReturn(true);
        Rollback rb = new Rollback(ops, lock);

        TargetPackage t = new TargetPackage();
        t.setPackageName("a.war");
        t.setRemoteDir("/d/");
        t.setRemotePath("/d/a.war");
        t.setLockName("a.war__LOCK__op_20260701_000000_TTL10");
        t.setStatus(TargetPackage.Status.UPLOADED);

        boolean acted = rb.rollbackTarget(t);

        assertThat(acted).isTrue();
        assertThat(t.getStatus()).isEqualTo(TargetPackage.Status.ROLLED_BACK);
        verify(ops).delete("/d/a.war");
        verify(lock).restoreLock("/d/", t.getLockName());
    }

    /**
     * 漏洞 H6：UPLOADED + skipLock 模式 + 无备份 = 没有任何可恢复字节，
     * 必须 return false 让 attemptRollback 报 attempted=false，杜绝"假成功"。
     */
    @Test
    void uploadedWithoutBackupAndWithoutLock_returnsFalse() throws Exception {
        FtpOperations ops = Mockito.mock(FtpOperations.class);
        FtpLock lock = Mockito.mock(FtpLock.class);
        Rollback rb = new Rollback(ops, lock);

        TargetPackage t = new TargetPackage();
        t.setPackageName("a.war");
        t.setRemoteDir("/d/");
        t.setRemotePath("/d/a.war");
        // 无 backupRemotePath，无 lockName（典型 skipLock + skipBackup 场景）
        t.setStatus(TargetPackage.Status.UPLOADED);

        boolean acted = rb.rollbackTarget(t);

        assertThat(acted).isFalse();
        verify(ops, never()).download(Mockito.anyString(), Mockito.any());
        verify(ops, never()).download(Mockito.anyString(), Mockito.any(), Mockito.any(), Mockito.any());
        verify(ops, never()).upload(Mockito.any(), Mockito.anyString());
        verify(ops, never()).upload(Mockito.any(), Mockito.anyString(), Mockito.any(), Mockito.any());
        verify(ops, never()).uploadAtomic(Mockito.any(), Mockito.anyString());
        verify(ops, never()).uploadAtomic(Mockito.any(), Mockito.anyString(), Mockito.any(), Mockito.any());
        verify(lock, never()).restoreLock(Mockito.anyString(), Mockito.anyString());
    }

    /**
     * 漏洞 H3：NOTE_UPDATED 状态回滚时，业务包恢复后还要把 note 文件按 snapshot 字节写回。
     */
    @Test
    void noteUpdated_restoresBusinessPackageAndNoteSnapshot() throws Exception {
        FtpOperations ops = Mockito.mock(FtpOperations.class);
        FtpLock lock = Mockito.mock(FtpLock.class);
        when(ops.exists(Mockito.anyString())).thenReturn(false); // 锁包已被 NoteGate 后续逻辑视作可清
        Rollback rb = new Rollback(ops, lock);

        TargetPackage t = new TargetPackage();
        t.setPackageName("a.war");
        t.setRemoteDir("/d/");
        t.setRemotePath("/d/a.war");
        t.setBackupRemotePath("/d/backup/x/a.war");
        t.setNoteRemotePath("/d/a.war_update_notes.txt");
        t.setNoteSnapshotBytes(new byte[]{0x61, 0x62, 0x63}); // "abc"
        t.setStatus(TargetPackage.Status.NOTE_UPDATED);

        boolean acted = rb.rollbackTarget(t);

        assertThat(acted).isTrue();
        assertThat(t.getStatus()).isEqualTo(TargetPackage.Status.ROLLED_BACK);
        // 业务包恢复
        verify(ops).download(Mockito.eq("/d/backup/x/a.war"), Mockito.any(), Mockito.anyString(), Mockito.any());
        verify(ops).uploadAtomic(Mockito.any(), Mockito.eq("/d/a.war"), Mockito.anyString(), Mockito.any());
        // note 文件按 snapshot 字节写回
        verify(ops).uploadAtomic(Mockito.any(), Mockito.eq("/d/a.war_update_notes.txt"));
    }

    /**
     * 新建目标（Vue 模块 zip 首次投放）：UPLOADED 状态回滚 = 删除远端新文件，
     * 不做备份恢复、不做锁恢复（两者对新建目标都不存在）。
     */
    @Test
    void createNewTarget_uploaded_deletesRemoteFileOnly() throws Exception {
        FtpOperations ops = Mockito.mock(FtpOperations.class);
        FtpLock lock = Mockito.mock(FtpLock.class);
        when(ops.exists("/d/tm01webVue_t0107.zip")).thenReturn(true);
        Rollback rb = new Rollback(ops, lock);

        TargetPackage t = new TargetPackage();
        t.setPackageName("tm01webVue_t0107.zip");
        t.setRemoteDir("/d/");
        t.setRemotePath("/d/tm01webVue_t0107.zip");
        t.setCreateNew(true);
        t.setStatus(TargetPackage.Status.UPLOADED);

        boolean acted = rb.rollbackTarget(t);

        assertThat(acted).isTrue();
        assertThat(t.getStatus()).isEqualTo(TargetPackage.Status.ROLLED_BACK);
        verify(ops).delete("/d/tm01webVue_t0107.zip");
        verify(ops, never()).download(Mockito.anyString(), Mockito.any());
        verify(ops, never()).download(Mockito.anyString(), Mockito.any(), Mockito.any(), Mockito.any());
        verify(ops, never()).upload(Mockito.any(), Mockito.anyString());
        verify(ops, never()).upload(Mockito.any(), Mockito.anyString(), Mockito.any(), Mockito.any());
        verify(ops, never()).uploadAtomic(Mockito.any(), Mockito.anyString());
        verify(ops, never()).uploadAtomic(Mockito.any(), Mockito.anyString(), Mockito.any(), Mockito.any());
        verify(lock, never()).restoreLock(Mockito.anyString(), Mockito.anyString());
    }

    /**
     * 新建目标：尚未上传（PENDING）时无需回滚，返回 false 且不做任何远端操作。
     */
    @Test
    void createNewTarget_pending_returnsFalse() throws Exception {
        FtpOperations ops = Mockito.mock(FtpOperations.class);
        FtpLock lock = Mockito.mock(FtpLock.class);
        Rollback rb = new Rollback(ops, lock);

        TargetPackage t = new TargetPackage();
        t.setPackageName("tm01webVue_t0107.zip");
        t.setRemoteDir("/d/");
        t.setRemotePath("/d/tm01webVue_t0107.zip");
        t.setCreateNew(true);
        t.setStatus(TargetPackage.Status.PENDING);

        boolean acted = rb.rollbackTarget(t);

        assertThat(acted).isFalse();
        verify(ops, never()).delete(Mockito.anyString());
    }

    /**
     * 新建目标 + NOTE_UPDATED：回滚删除新文件之外，还要撤销新建的 note 文件（snapshot=null → delete）。
     */
    @Test
    void createNewTarget_noteUpdated_deletesFileAndCreatedNote() throws Exception {
        FtpOperations ops = Mockito.mock(FtpOperations.class);
        FtpLock lock = Mockito.mock(FtpLock.class);
        when(ops.exists(Mockito.anyString())).thenReturn(true);
        Rollback rb = new Rollback(ops, lock);

        TargetPackage t = new TargetPackage();
        t.setPackageName("tm01webVue_t0107.zip");
        t.setRemoteDir("/d/");
        t.setRemotePath("/d/tm01webVue_t0107.zip");
        t.setCreateNew(true);
        t.setNoteRemotePath("/d/tm01webVue_t0107.zip_update_notes.txt");
        t.setNoteSnapshotBytes(null);
        t.setStatus(TargetPackage.Status.NOTE_UPDATED);

        boolean acted = rb.rollbackTarget(t);

        assertThat(acted).isTrue();
        verify(ops).delete("/d/tm01webVue_t0107.zip");
        verify(ops).delete("/d/tm01webVue_t0107.zip_update_notes.txt");
    }

    /**
     * 漏洞 H3：NOTE_UPDATED 状态下 note 为新建（snapshot=null）时，
     * 回滚必须 delete 远端 note 文件（撤销新建动作）。
     */
    @Test
    void noteUpdated_withNullSnapshot_deletesCreatedNote() throws Exception {
        FtpOperations ops = Mockito.mock(FtpOperations.class);
        FtpLock lock = Mockito.mock(FtpLock.class);
        when(ops.exists("/d/a.war_update_notes.txt")).thenReturn(true);
        Rollback rb = new Rollback(ops, lock);

        TargetPackage t = new TargetPackage();
        t.setPackageName("a.war");
        t.setRemoteDir("/d/");
        t.setRemotePath("/d/a.war");
        t.setBackupRemotePath("/d/backup/x/a.war");
        t.setNoteRemotePath("/d/a.war_update_notes.txt");
        t.setNoteSnapshotBytes(null); // 新建场景
        t.setStatus(TargetPackage.Status.NOTE_UPDATED);

        boolean acted = rb.rollbackTarget(t);

        assertThat(acted).isTrue();
        verify(ops).delete("/d/a.war_update_notes.txt");
    }
}
