package com.flux.deploy.model;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class DeployResultFormatTest {

    @Test
    void formatSuccess() {
        DeployResult r = new DeployResult();
        DeployResult.TargetResult t1 = new DeployResult.TargetResult();
        t1.setPackageName("a.war"); t1.setVerified(true);
        r.addTarget(t1);
        r.markSuccess();
        String s = r.formatReport();
        assertThat(s).contains("[OK]").contains("a.war").contains("1/1");
    }

    @Test
    void formatStage2Failure() {
        DeployResult r = new DeployResult();
        DeployResult.TargetResult ok = new DeployResult.TargetResult();
        ok.setPackageName("a.war"); ok.setVerified(true);
        DeployResult.TargetResult fail = new DeployResult.TargetResult();
        fail.setPackageName("b.war");
        DeployResult.TargetResult skip = new DeployResult.TargetResult();
        skip.setPackageName("c.war");
        r.addTarget(ok); r.addTarget(fail); r.addTarget(skip);
        r.addError("verify", "b.war", "SHA256 不匹配");
        String s = r.formatReport();
        assertThat(s).contains("[FAIL]").contains("b.war").contains("a.war").contains("c.war");
    }

    @Test
    void formatCancelled() {
        DeployResult r = new DeployResult();
        r.setCancelled(true);
        String s = r.formatReport();
        assertThat(s).contains("[CANCELLED]");
    }
}
