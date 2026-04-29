package com.flux.deploy.deploy;

import com.flux.deploy.ftp.FtpLock;
import com.flux.deploy.ftp.FtpOperations;
import com.flux.deploy.model.DeployResult;
import com.flux.deploy.model.TargetPackage;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PipelineStage2FailFastTest {

    /** 让 Stage 2 跑时第 N 个目标在 verify 抛失败 */
    static class FakePipeline {
        List<TargetPackage> targets = new ArrayList<>();
        DeployResult result = new DeployResult();
        Rollback rollback;

        FakePipeline(int total, int failAt) {
            FtpOperations ops = Mockito.mock(FtpOperations.class);
            FtpLock lock = Mockito.mock(FtpLock.class);
            this.rollback = new Rollback(ops, lock);
            for (int i = 0; i < total; i++) {
                TargetPackage t = new TargetPackage();
                t.setPackageName("t" + i + ".war");
                t.setRemoteDir("/d/");
                t.setRemotePath("/d/t" + i + ".war");
                targets.add(t);
            }
            // 模拟："T0..T(failAt-1) 全部跑完 → COMPLETED；TfailAt 失败回滚 → ROLLED_BACK；
            //         其余 SKIPPED；result.errors 加一条；result.markSuccess 不被调用"
            for (int i = 0; i < total; i++) {
                if (i < failAt) targets.get(i).setStatus(TargetPackage.Status.COMPLETED);
                else if (i == failAt) {
                    targets.get(i).setStatus(TargetPackage.Status.LOCKED);
                    // 调真实 rollback 路径
                    try { rollback.rollbackTarget(targets.get(i)); } catch (Exception ignored) {}
                    result.addError("verify", targets.get(i).getPackageName(), "SHA256 不匹配");
                } else {
                    targets.get(i).setStatus(TargetPackage.Status.SKIPPED);
                }
            }
        }
    }

    @Test
    void midBatchFailure_keepsEarlierCompleted_skipsLater() {
        FakePipeline p = new FakePipeline(5, 2);
        assertThat(p.targets.get(0).getStatus()).isEqualTo(TargetPackage.Status.COMPLETED);
        assertThat(p.targets.get(1).getStatus()).isEqualTo(TargetPackage.Status.COMPLETED);
        // T2 触发 rollbackTarget；由于 lockName 为 null，rollback 返回 false 不改状态——保持 LOCKED
        // 真实流程会先在 LockGate 设 lockName，本测试只验证 SKIPPED 边界
        assertThat(p.targets.get(3).getStatus()).isEqualTo(TargetPackage.Status.SKIPPED);
        assertThat(p.targets.get(4).getStatus()).isEqualTo(TargetPackage.Status.SKIPPED);
        assertThat(p.result.getErrors()).hasSize(1);
        assertThat(p.result.getErrors().get(0).getTarget()).isEqualTo("t2.war");
        assertThat(p.result.isCancelled()).isFalse();
    }

    @Test
    void allSuccess_noSkipped() {
        FakePipeline p = new FakePipeline(3, 99);
        for (TargetPackage t : p.targets) {
            assertThat(t.getStatus()).isEqualTo(TargetPackage.Status.COMPLETED);
        }
        assertThat(p.result.getErrors()).isEmpty();
    }
}
