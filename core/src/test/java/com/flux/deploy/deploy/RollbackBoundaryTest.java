package com.flux.deploy.deploy;

import com.flux.deploy.ftp.FtpLock;
import com.flux.deploy.ftp.FtpOperations;
import com.flux.deploy.model.TargetPackage;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

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
        verify(ops, never()).upload(Mockito.any(), Mockito.anyString());
        verify(lock, never()).restoreLock(Mockito.anyString(), Mockito.anyString());
    }
}
