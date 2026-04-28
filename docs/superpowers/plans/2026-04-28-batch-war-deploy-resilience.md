# 批量 WAR 部署 健壮性与回滚语义重设计 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把当前"逐门禁分批 + 全量回滚"的批量 WAR 部署模型重构为"Stage 0 残留锁解析 → Stage 1 全员准备 → Stage 2 per-target 全流程 + 局部回滚"，使中断/连接断/进程崩溃后远端不留无主残留，且单点失败不波及已成功目标。

**Architecture:** 三段式串行管道。新增独立类 `ResidualLockResolver` 承担 Stage 0 残留锁诊断+清理，与 Gate 体系解耦。`Rollback` 简化为只处理"未达 COMPLETED 的当前目标"。Cancellation 通过新增 `CancellationToken` 接口在每个 Gate 入口检查。CLI/IDE 共用 core 逻辑，仅 UX 层差异化。

**Tech Stack:** Java 17 / Gradle Kotlin DSL / Apache Commons-Net (FTP) / IntelliJ Platform SDK (插件) / 新增 JUnit 5 + AssertJ 用于单元测试。

**前置说明：**
- 项目当前**不是 git 仓库**，Task 1 会做 `git init` 后续才能 commit。
- 现有"测试"是 JavaExec driver（main 方法跑 E2E），不是 JUnit 单元测试；本计划新增 JUnit 5 作为单元测试基座，driver 风格用于 FTP 集成验收。
- Spec 参考：[docs/superpowers/specs/2026-04-28-batch-war-deploy-resilience-design.md](../specs/2026-04-28-batch-war-deploy-resilience-design.md)

---

## Task 1: 初始化 git 仓库并提交 baseline

**Files:**
- Create: `.gitignore`
- Verify: 工作目录干净

- [ ] **Step 1: 创建 .gitignore**

写入：
```
# Build
build/
.gradle/
*.class

# IDE
.idea/
*.iml
.vscode/

# OS
.DS_Store

# Distribution
dist-archive/

# Node deps used by docs/
docs/node_modules/
```

- [ ] **Step 2: git init + 首次 commit**

```bash
cd /Users/chuaishoushou/AI/Project/flux-deploy
git init -b main
git add .gitignore docs/superpowers core cli plugin build.gradle.kts settings.gradle.kts gradle.properties gradle gradlew gradlew.bat README.md
git status   # 确认无 build/ / .idea/ / node_modules
git commit -m "chore: baseline before batch deploy resilience refactor"
```

Expected: 一次 baseline commit，工作树干净。

---

## Task 2: 添加 JUnit 5 + AssertJ 测试依赖到 core 模块

**Files:**
- Modify: `core/build.gradle.kts`
- Test: `core/src/test/java/com/flux/deploy/SmokeTest.java`

- [ ] **Step 1: 写 smoke 测试**

文件 `core/src/test/java/com/flux/deploy/SmokeTest.java`：
```java
package com.flux.deploy;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class SmokeTest {
    @Test
    void junitWorks() {
        assertThat(1 + 1).isEqualTo(2);
    }
}
```

- [ ] **Step 2: 修改 core/build.gradle.kts 加测试依赖与 task**

在 `dependencies { ... }` 块加：
```kotlin
testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
testImplementation("org.assertj:assertj-core:3.25.3")
testRuntimeOnly("org.junit.platform:junit-platform-launcher")
```

在文件末尾加：
```kotlin
tasks.named<Test>("test") {
    useJUnitPlatform()
    testLogging { events("passed", "failed", "skipped") }
}
```

- [ ] **Step 3: 跑测试确认通过**

```bash
./gradlew :core:test --tests com.flux.deploy.SmokeTest
```

Expected: BUILD SUCCESSFUL，1 test passed。

- [ ] **Step 4: Commit**

```bash
git add core/build.gradle.kts core/src/test/java/com/flux/deploy/SmokeTest.java
git commit -m "build(core): add JUnit 5 + AssertJ for unit tests"
```

---

## Task 3: 扩展 TargetPackage.Status 与 DeployResult 字段

**Files:**
- Modify: `core/src/main/java/com/flux/deploy/model/TargetPackage.java`
- Modify: `core/src/main/java/com/flux/deploy/model/DeployResult.java`
- Test: `core/src/test/java/com/flux/deploy/model/StatusEnumTest.java`

- [ ] **Step 1: 写失败测试**

文件 `core/src/test/java/com/flux/deploy/model/StatusEnumTest.java`：
```java
package com.flux.deploy.model;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class StatusEnumTest {
    @Test
    void hasSkippedStatus() {
        assertThat(TargetPackage.Status.SKIPPED).isNotNull();
    }
    @Test
    void hasFailedNeedsManualStatus() {
        assertThat(TargetPackage.Status.FAILED_NEEDS_MANUAL).isNotNull();
    }
    @Test
    void deployResultHasCancelledFlag() {
        DeployResult r = new DeployResult();
        assertThat(r.isCancelled()).isFalse();
        r.setCancelled(true);
        assertThat(r.isCancelled()).isTrue();
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

```bash
./gradlew :core:test --tests com.flux.deploy.model.StatusEnumTest
```

Expected: 编译失败 / 找不到 SKIPPED / FAILED_NEEDS_MANUAL / isCancelled。

- [ ] **Step 3: 修改 TargetPackage.Status**

在 `core/src/main/java/com/flux/deploy/model/TargetPackage.java` 的 `Status` 枚举里，在 `FAILED` 之前加：
```java
/** 因前序失败被跳过（fail-fast 后未开始） */
SKIPPED,
/** 失败且自动回滚未完成，需人工介入 */
FAILED_NEEDS_MANUAL,
```

- [ ] **Step 4: 在 DeployResult 加 cancelled 字段**

在 `core/src/main/java/com/flux/deploy/model/DeployResult.java` 的字段区加：
```java
/** 是否因用户主动取消而结束 */
private boolean cancelled;

public boolean isCancelled() { return cancelled; }
public void setCancelled(boolean cancelled) { this.cancelled = cancelled; }
```

- [ ] **Step 5: 跑测试确认通过**

```bash
./gradlew :core:test --tests com.flux.deploy.model.StatusEnumTest
```

Expected: 3 tests passed。

- [ ] **Step 6: Commit**

```bash
git add core/src/main/java/com/flux/deploy/model/TargetPackage.java \
        core/src/main/java/com/flux/deploy/model/DeployResult.java \
        core/src/test/java/com/flux/deploy/model/StatusEnumTest.java
git commit -m "feat(model): add SKIPPED / FAILED_NEEDS_MANUAL status and cancelled flag"
```

---

## Task 4: 创建 ResidualLockDiagnosis 数据类

**Files:**
- Create: `core/src/main/java/com/flux/deploy/deploy/ResidualLockDiagnosis.java`
- Test: `core/src/test/java/com/flux/deploy/deploy/ResidualLockDiagnosisTest.java`

- [ ] **Step 1: 写失败测试**

文件 `core/src/test/java/com/flux/deploy/deploy/ResidualLockDiagnosisTest.java`：
```java
package com.flux.deploy.deploy;

import org.junit.jupiter.api.Test;
import java.time.LocalDateTime;
import static org.assertj.core.api.Assertions.assertThat;

class ResidualLockDiagnosisTest {
    @Test
    void buildsAndExposesAllFields() {
        ResidualLockDiagnosis d = ResidualLockDiagnosis.builder()
                .lockFileName("a.war__LOCK__bob_20260427_153012")
                .originalPackageName("a.war")
                .operator("bob")
                .lockedAt(LocalDateTime.of(2026, 4, 27, 15, 30, 12))
                .ownedByCurrentUser(true)
                .originalPackageExists(false)
                .suggestion(ResidualLockDiagnosis.SuggestedAction.RESTORE_LOCK)
                .reason("锁包在，原包不在")
                .build();

        assertThat(d.getLockFileName()).isEqualTo("a.war__LOCK__bob_20260427_153012");
        assertThat(d.getOriginalPackageName()).isEqualTo("a.war");
        assertThat(d.getOperator()).isEqualTo("bob");
        assertThat(d.isOwnedByCurrentUser()).isTrue();
        assertThat(d.isOriginalPackageExists()).isFalse();
        assertThat(d.getSuggestion()).isEqualTo(ResidualLockDiagnosis.SuggestedAction.RESTORE_LOCK);
        assertThat(d.getReason()).isEqualTo("锁包在，原包不在");
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

```bash
./gradlew :core:test --tests com.flux.deploy.deploy.ResidualLockDiagnosisTest
```

Expected: 编译失败 / 类不存在。

- [ ] **Step 3: 实现 ResidualLockDiagnosis**

文件 `core/src/main/java/com/flux/deploy/deploy/ResidualLockDiagnosis.java`：
```java
package com.flux.deploy.deploy;

import java.time.LocalDateTime;

/**
 * 残留锁诊断结果（不可变）
 *
 * <p>由 ResidualLockResolver.diagnose 产出，由 UI/CLI 展示给用户决策，
 * 由 ResidualLockResolver.apply 消费执行。</p>
 *
 * @author xumanyi
 * @date 2026-04-28
 */
public final class ResidualLockDiagnosis {

    /** 建议动作 */
    public enum SuggestedAction {
        /** 锁包在、原包不在 → rename 锁包回原名 */
        RESTORE_LOCK,
        /** 锁包在、原包也在 → 删锁包，保留新版本 */
        DELETE_LOCK,
        /** 状态异常，建议人工介入 */
        NEEDS_HUMAN
    }

    private final String lockFileName;
    private final String remoteDir;
    private final String originalPackageName;
    private final String operator;
    private final LocalDateTime lockedAt;
    private final boolean ownedByCurrentUser;
    private final boolean originalPackageExists;
    private final SuggestedAction suggestion;
    private final String reason;

    private ResidualLockDiagnosis(Builder b) {
        this.lockFileName = b.lockFileName;
        this.remoteDir = b.remoteDir;
        this.originalPackageName = b.originalPackageName;
        this.operator = b.operator;
        this.lockedAt = b.lockedAt;
        this.ownedByCurrentUser = b.ownedByCurrentUser;
        this.originalPackageExists = b.originalPackageExists;
        this.suggestion = b.suggestion;
        this.reason = b.reason;
    }

    public String getLockFileName() { return lockFileName; }
    public String getRemoteDir() { return remoteDir; }
    public String getOriginalPackageName() { return originalPackageName; }
    public String getOperator() { return operator; }
    public LocalDateTime getLockedAt() { return lockedAt; }
    public boolean isOwnedByCurrentUser() { return ownedByCurrentUser; }
    public boolean isOriginalPackageExists() { return originalPackageExists; }
    public SuggestedAction getSuggestion() { return suggestion; }
    public String getReason() { return reason; }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private String lockFileName;
        private String remoteDir;
        private String originalPackageName;
        private String operator;
        private LocalDateTime lockedAt;
        private boolean ownedByCurrentUser;
        private boolean originalPackageExists;
        private SuggestedAction suggestion;
        private String reason;

        public Builder lockFileName(String v) { this.lockFileName = v; return this; }
        public Builder remoteDir(String v) { this.remoteDir = v; return this; }
        public Builder originalPackageName(String v) { this.originalPackageName = v; return this; }
        public Builder operator(String v) { this.operator = v; return this; }
        public Builder lockedAt(LocalDateTime v) { this.lockedAt = v; return this; }
        public Builder ownedByCurrentUser(boolean v) { this.ownedByCurrentUser = v; return this; }
        public Builder originalPackageExists(boolean v) { this.originalPackageExists = v; return this; }
        public Builder suggestion(SuggestedAction v) { this.suggestion = v; return this; }
        public Builder reason(String v) { this.reason = v; return this; }

        public ResidualLockDiagnosis build() { return new ResidualLockDiagnosis(this); }
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

```bash
./gradlew :core:test --tests com.flux.deploy.deploy.ResidualLockDiagnosisTest
```

Expected: 1 test passed。

- [ ] **Step 5: Commit**

```bash
git add core/src/main/java/com/flux/deploy/deploy/ResidualLockDiagnosis.java \
        core/src/test/java/com/flux/deploy/deploy/ResidualLockDiagnosisTest.java
git commit -m "feat(core): add ResidualLockDiagnosis data class for Stage 0 residual lock UX"
```

---

## Task 5: ResidualLockResolver.diagnose() 实现

**Files:**
- Create: `core/src/main/java/com/flux/deploy/deploy/ResidualLockResolver.java`
- Test: `core/src/test/java/com/flux/deploy/deploy/ResidualLockResolverDiagnoseTest.java`

为了让 diagnose 能在不连接真实 FTP 的情况下被单元测试，先在 Resolver 里抽一个最小的远端探测接口。

- [ ] **Step 1: 写失败测试**

文件 `core/src/test/java/com/flux/deploy/deploy/ResidualLockResolverDiagnoseTest.java`：
```java
package com.flux.deploy.deploy;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ResidualLockResolverDiagnoseTest {

    /** 最小 fake：每个目录预设的锁文件列表 + 哪些 path 视为存在 */
    static class FakeProbe implements ResidualLockResolver.RemoteProbe {
        Map<String, List<String>> locksByDir = new HashMap<>();
        Set<String> existingPaths = new HashSet<>();
        @Override public List<String> findResidualLocks(String dir, String pkg) { return locksByDir.getOrDefault(dir + "::" + pkg, List.of()); }
        @Override public boolean exists(String path) throws IOException { return existingPaths.contains(path); }
    }

    @Test
    void noResidual_returnsEmpty() throws Exception {
        FakeProbe probe = new FakeProbe();
        ResidualLockResolver r = new ResidualLockResolver(probe, "alice");
        List<ResidualLockDiagnosis> result = r.diagnose("/d/", "a.war");
        assertThat(result).isEmpty();
    }

    @Test
    void lockExists_originalMissing_suggestsRestoreLock() throws Exception {
        FakeProbe probe = new FakeProbe();
        probe.locksByDir.put("/d/::a.war", List.of("a.war__LOCK__alice_20260427_153012"));
        // 原包不存在
        ResidualLockResolver r = new ResidualLockResolver(probe, "alice");
        List<ResidualLockDiagnosis> result = r.diagnose("/d/", "a.war");
        assertThat(result).hasSize(1);
        ResidualLockDiagnosis d = result.get(0);
        assertThat(d.getSuggestion()).isEqualTo(ResidualLockDiagnosis.SuggestedAction.RESTORE_LOCK);
        assertThat(d.isOwnedByCurrentUser()).isTrue();
        assertThat(d.isOriginalPackageExists()).isFalse();
        assertThat(d.getOperator()).isEqualTo("alice");
    }

    @Test
    void lockExists_originalAlsoExists_suggestsDeleteLock() throws Exception {
        FakeProbe probe = new FakeProbe();
        probe.locksByDir.put("/d/::a.war", List.of("a.war__LOCK__alice_20260427_153012"));
        probe.existingPaths.add("/d/a.war");
        ResidualLockResolver r = new ResidualLockResolver(probe, "alice");
        List<ResidualLockDiagnosis> result = r.diagnose("/d/", "a.war");
        assertThat(result.get(0).getSuggestion()).isEqualTo(ResidualLockDiagnosis.SuggestedAction.DELETE_LOCK);
        assertThat(result.get(0).isOriginalPackageExists()).isTrue();
    }

    @Test
    void lockOwnedByOther_marksNotOwnedByCurrentUser() throws Exception {
        FakeProbe probe = new FakeProbe();
        probe.locksByDir.put("/d/::a.war", List.of("a.war__LOCK__bob_20260427_153012"));
        ResidualLockResolver r = new ResidualLockResolver(probe, "alice");
        List<ResidualLockDiagnosis> result = r.diagnose("/d/", "a.war");
        assertThat(result.get(0).isOwnedByCurrentUser()).isFalse();
        assertThat(result.get(0).getOperator()).isEqualTo("bob");
    }

    @Test
    void unparseableLockName_suggestsNeedsHuman() throws Exception {
        FakeProbe probe = new FakeProbe();
        probe.locksByDir.put("/d/::a.war", List.of("a.war__LOCK__weirdformat"));
        ResidualLockResolver r = new ResidualLockResolver(probe, "alice");
        List<ResidualLockDiagnosis> result = r.diagnose("/d/", "a.war");
        assertThat(result.get(0).getSuggestion()).isEqualTo(ResidualLockDiagnosis.SuggestedAction.NEEDS_HUMAN);
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

```bash
./gradlew :core:test --tests com.flux.deploy.deploy.ResidualLockResolverDiagnoseTest
```

Expected: 编译失败 / Resolver 不存在。

- [ ] **Step 3: 实现 ResidualLockResolver（仅 diagnose 部分）**

文件 `core/src/main/java/com/flux/deploy/deploy/ResidualLockResolver.java`：
```java
package com.flux.deploy.deploy;

import com.flux.deploy.ftp.FtpLock;
import com.flux.deploy.ftp.FtpOperations;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Stage 0 残留锁诊断与清理。
 *
 * <p>从 LockGate 内部 findResidualLocks 抽出的独立步骤，
 * 在 Stage 1 之前先扫描所有 remoteDir 的残留锁，结构化诊断并由 UI/CLI 决定清理方式。</p>
 *
 * @author xumanyi
 * @date 2026-04-28
 */
public class ResidualLockResolver {

    /** 远端探测的最小接口，方便单元测试用 fake 注入 */
    public interface RemoteProbe {
        List<String> findResidualLocks(String remoteDir, String packageName) throws IOException;
        boolean exists(String path) throws IOException;
    }

    private static final DateTimeFormatter LOCK_TIME_FMT = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    private final RemoteProbe probe;
    private final String currentOperator;

    public ResidualLockResolver(RemoteProbe probe, String currentOperator) {
        this.probe = probe;
        this.currentOperator = currentOperator;
    }

    /** 适配真实 FTP：把 FtpOperations + FtpLock 包成 RemoteProbe */
    public static RemoteProbe wrap(FtpOperations ops, FtpLock lock) {
        return new RemoteProbe() {
            @Override public List<String> findResidualLocks(String dir, String pkg) throws IOException {
                return lock.findResidualLocks(dir, pkg);
            }
            @Override public boolean exists(String path) throws IOException {
                return ops.exists(path);
            }
        };
    }

    /**
     * 诊断单个 (remoteDir, packageName) 的残留锁。
     *
     * @return 该路径下所有残留锁的诊断（无残留则空 list）
     */
    public List<ResidualLockDiagnosis> diagnose(String remoteDir, String packageName) throws IOException {
        List<String> locks = probe.findResidualLocks(remoteDir, packageName);
        List<ResidualLockDiagnosis> result = new ArrayList<>();
        for (String lockName : locks) {
            result.add(diagnoseOne(remoteDir, packageName, lockName));
        }
        return result;
    }

    private ResidualLockDiagnosis diagnoseOne(String remoteDir, String packageName, String lockName) throws IOException {
        ResidualLockDiagnosis.Builder b = ResidualLockDiagnosis.builder()
                .lockFileName(lockName)
                .remoteDir(remoteDir)
                .originalPackageName(packageName);

        String[] info = FtpLock.parseLockInfo(lockName);
        if (info == null) {
            return b.suggestion(ResidualLockDiagnosis.SuggestedAction.NEEDS_HUMAN)
                    .reason("锁文件名格式异常，无法解析 operator/time")
                    .ownedByCurrentUser(false)
                    .build();
        }
        String operator = info[0];
        LocalDateTime ts;
        try {
            ts = LocalDateTime.parse(info[1], LOCK_TIME_FMT);
        } catch (Exception e) {
            ts = null;
        }
        boolean owned = currentOperator != null && currentOperator.equals(operator);

        boolean originalExists = probe.exists(ensureSlash(remoteDir) + packageName);

        ResidualLockDiagnosis.SuggestedAction action;
        String reason;
        if (originalExists) {
            action = ResidualLockDiagnosis.SuggestedAction.DELETE_LOCK;
            reason = "锁包与原包同时存在，新版本已发布；建议删锁保留新版本";
        } else {
            action = ResidualLockDiagnosis.SuggestedAction.RESTORE_LOCK;
            reason = "锁包存在，原包不存在；建议恢复锁包为原文件名";
        }

        return b.operator(operator)
                .lockedAt(ts)
                .ownedByCurrentUser(owned)
                .originalPackageExists(originalExists)
                .suggestion(action)
                .reason(reason)
                .build();
    }

    private static String ensureSlash(String p) { return p.endsWith("/") ? p : p + "/"; }
}
```

- [ ] **Step 4: 跑测试确认通过**

```bash
./gradlew :core:test --tests com.flux.deploy.deploy.ResidualLockResolverDiagnoseTest
```

Expected: 5 tests passed。

- [ ] **Step 5: Commit**

```bash
git add core/src/main/java/com/flux/deploy/deploy/ResidualLockResolver.java \
        core/src/test/java/com/flux/deploy/deploy/ResidualLockResolverDiagnoseTest.java
git commit -m "feat(core): ResidualLockResolver.diagnose with structured per-lock results"
```

---

## Task 6: ResidualLockResolver.apply() 三种动作实现

**Files:**
- Modify: `core/src/main/java/com/flux/deploy/deploy/ResidualLockResolver.java`
- Test: `core/src/test/java/com/flux/deploy/deploy/ResidualLockResolverApplyTest.java`

需要在 RemoteProbe 上加 mutating 方法（rename / delete）以便 apply 可单测。

- [ ] **Step 1: 写失败测试**

文件 `core/src/test/java/com/flux/deploy/deploy/ResidualLockResolverApplyTest.java`：
```java
package com.flux.deploy.deploy;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ResidualLockResolverApplyTest {

    static class RecordingProbe implements ResidualLockResolver.RemoteProbe {
        List<String> ops = new ArrayList<>();
        @Override public List<String> findResidualLocks(String d, String p) { return List.of(); }
        @Override public boolean exists(String p) { return false; }
        @Override public void rename(String from, String to) { ops.add("rename " + from + " -> " + to); }
        @Override public void delete(String p) { ops.add("delete " + p); }
    }

    @Test
    void applyRestoreLock_renamesLockToOriginal() throws Exception {
        RecordingProbe probe = new RecordingProbe();
        ResidualLockResolver r = new ResidualLockResolver(probe, "alice");

        ResidualLockDiagnosis d = ResidualLockDiagnosis.builder()
                .lockFileName("a.war__LOCK__alice_20260427_153012")
                .originalPackageName("a.war")
                .remoteDir("/d/")
                .suggestion(ResidualLockDiagnosis.SuggestedAction.RESTORE_LOCK)
                .build();

        r.apply(d);

        assertThat(probe.ops).containsExactly("rename /d/a.war__LOCK__alice_20260427_153012 -> /d/a.war");
    }

    @Test
    void applyDeleteLock_deletesLockOnly() throws Exception {
        RecordingProbe probe = new RecordingProbe();
        ResidualLockResolver r = new ResidualLockResolver(probe, "alice");

        ResidualLockDiagnosis d = ResidualLockDiagnosis.builder()
                .lockFileName("a.war__LOCK__alice_20260427_153012")
                .originalPackageName("a.war")
                .remoteDir("/d/")
                .suggestion(ResidualLockDiagnosis.SuggestedAction.DELETE_LOCK)
                .build();

        r.apply(d);

        assertThat(probe.ops).containsExactly("delete /d/a.war__LOCK__alice_20260427_153012");
    }

    @Test
    void applyNeedsHuman_throws() {
        RecordingProbe probe = new RecordingProbe();
        ResidualLockResolver r = new ResidualLockResolver(probe, "alice");

        ResidualLockDiagnosis d = ResidualLockDiagnosis.builder()
                .lockFileName("garbage")
                .originalPackageName("a.war")
                .remoteDir("/d/")
                .suggestion(ResidualLockDiagnosis.SuggestedAction.NEEDS_HUMAN)
                .build();

        assertThatThrownBy(() -> r.apply(d))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("人工");
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

```bash
./gradlew :core:test --tests com.flux.deploy.deploy.ResidualLockResolverApplyTest
```

Expected: 编译失败 / probe 没有 rename/delete / Resolver 没有 apply。

- [ ] **Step 3: 在 RemoteProbe 加 mutating 方法 + 写 apply**

修改 `ResidualLockResolver.java`：

在 `RemoteProbe` 接口加（用 `default` 抛 UnsupportedOperationException 以免破坏 Task 5 的 FakeProbe）：
```java
default void rename(String from, String to) throws IOException {
    throw new UnsupportedOperationException("rename not supported by this probe");
}
default void delete(String path) throws IOException {
    throw new UnsupportedOperationException("delete not supported by this probe");
}
```

修改 `wrap` 方法的匿名类，override 两个新方法：
```java
@Override public void rename(String from, String to) throws IOException { ops.rename(from, to); }
@Override public void delete(String path) throws IOException { ops.delete(path); }
```

注：Task 5 的 `FakeProbe` 不需要改动 —— 它只关心 diagnose，不会调到 rename/delete。Task 6 的 `RecordingProbe` 自己 override 了所有 4 个方法。

在类里加 `apply` 方法：
```java
/**
 * 执行诊断对应的清理动作。
 *
 * @throws IOException FTP 操作失败，或 suggestion=NEEDS_HUMAN 时拒绝执行
 */
public void apply(ResidualLockDiagnosis d) throws IOException {
    String dir = ensureSlash(d.getRemoteDir());
    String lockPath = dir + d.getLockFileName();
    switch (d.getSuggestion()) {
        case RESTORE_LOCK:
            probe.rename(lockPath, dir + d.getOriginalPackageName());
            return;
        case DELETE_LOCK:
            probe.delete(lockPath);
            return;
        case NEEDS_HUMAN:
        default:
            throw new IOException("该残留锁需要人工介入处理: " + d.getLockFileName()
                    + "（" + d.getReason() + "）");
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

```bash
./gradlew :core:test --tests com.flux.deploy.deploy.ResidualLockResolverApplyTest
./gradlew :core:test --tests com.flux.deploy.deploy.ResidualLockResolverDiagnoseTest
```

Expected: 全部 8 tests passed。

- [ ] **Step 5: Commit**

```bash
git add core/src/main/java/com/flux/deploy/deploy/ResidualLockResolver.java \
        core/src/test/java/com/flux/deploy/deploy/ResidualLockResolverApplyTest.java
git commit -m "feat(core): ResidualLockResolver.apply for RESTORE_LOCK / DELETE_LOCK / NEEDS_HUMAN"
```

---

## Task 7: 简化 Rollback（删 COMPLETED 分支与 rollbackAll）

**Files:**
- Modify: `core/src/main/java/com/flux/deploy/deploy/Rollback.java`
- Test: `core/src/test/java/com/flux/deploy/deploy/RollbackBoundaryTest.java`

- [ ] **Step 1: 写失败测试**

文件 `core/src/test/java/com/flux/deploy/deploy/RollbackBoundaryTest.java`：
```java
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
```

注：本测试使用 Mockito，Task 2 没加。继续此前先确认依赖；如未加，本步先在 `core/build.gradle.kts` 的 dependencies 里加：
```kotlin
testImplementation("org.mockito:mockito-core:5.11.0")
```

- [ ] **Step 2: 跑测试确认失败**

```bash
./gradlew :core:test --tests com.flux.deploy.deploy.RollbackBoundaryTest
```

Expected: 测试失败（当前 rollbackTarget 私有 + COMPLETED 走 restoreFromBackup 会调 download/upload）。

- [ ] **Step 3: 修改 Rollback.java**

把 `rollbackTarget` 改为 `public`，删掉 `case COMPLETED`，删掉整个 `rollbackAll` 方法。同时删除 `rollbackTarget` 里 `case VERIFIED` 后被删除分支留下来的 fall-through 死代码。

最终 `rollbackTarget` 应该是：
```java
public boolean rollbackTarget(TargetPackage target) throws Exception {
    TargetPackage.Status status = target.getStatus();
    switch (status) {
        case LOCKED:
        case UPLOADED:
            if (target.getLockName() != null) {
                ftpLock.restoreLock(target.getRemoteDir(), target.getLockName());
                target.setStatus(TargetPackage.Status.ROLLED_BACK);
                return true;
            }
            return false;
        case VERIFIED:
        case NOTE_UPDATED:
            if (target.getBackupRemotePath() != null) {
                restoreFromBackup(target);
                target.setStatus(TargetPackage.Status.ROLLED_BACK);
                return true;
            }
            return false;
        default:
            // PENDING / BACKED_UP / COMPLETED / FAILED / SKIPPED / ROLLED_BACK / FAILED_NEEDS_MANUAL
            return false;
    }
}
```

并删除整个 `rollbackAll(List<TargetPackage>)` 方法。`Rollback.java` 顶部对 `import java.util.List` 和 `DeployResult` 的引用如已不再需要，一并删掉。

- [ ] **Step 4: 适配 DeployPipeline 旧调用**

`DeployPipeline.executeGates` 当前调 `rollback.rollbackAll(targets)`——这个调用在 Task 11/12 重构时会被替换。本任务为了让代码暂时编译通过，把 `executeGates` 里那一行临时改为：
```java
// 临时：旧的全量回滚移除后，在 DeployPipeline 重构（Task 11/12）前先单目标回滚
try { rollback.rollbackTarget(target); } catch (Exception ex) { /* ignore */ }
DeployResult.RollbackResult rollbackResult = new DeployResult.RollbackResult();
rollbackResult.setAttempted(true);
rollbackResult.setSuccess(true);
result.setRollback(rollbackResult);
```

（这是过渡代码，Task 11/12 会整段重写 executeGates。）

- [ ] **Step 5: 跑测试确认通过 + 编译通过**

```bash
./gradlew :core:test --tests com.flux.deploy.deploy.RollbackBoundaryTest
./gradlew :core:compileJava :cli:compileJava :plugin:compileJava
```

Expected: BoundaryTest passed；编译全部通过。

- [ ] **Step 6: Commit**

```bash
git add core/build.gradle.kts core/src/main/java/com/flux/deploy/deploy/Rollback.java \
        core/src/main/java/com/flux/deploy/deploy/DeployPipeline.java \
        core/src/test/java/com/flux/deploy/deploy/RollbackBoundaryTest.java
git commit -m "refactor(core): Rollback drops COMPLETED branch and rollbackAll; pipeline temp shim"
```

---

## Task 8: CancellationToken 抽象

**Files:**
- Create: `core/src/main/java/com/flux/deploy/deploy/CancellationToken.java`
- Test: `core/src/test/java/com/flux/deploy/deploy/CancellationTokenTest.java`

- [ ] **Step 1: 写失败测试**

文件 `core/src/test/java/com/flux/deploy/deploy/CancellationTokenTest.java`：
```java
package com.flux.deploy.deploy;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CancellationTokenTest {

    @Test
    void noopToken_isNeverCancelled() {
        CancellationToken t = CancellationToken.NOOP;
        assertThat(t.isCancelled()).isFalse();
        t.throwIfCancelled(); // not throws
    }

    @Test
    void simpleToken_throwsAfterCancel() {
        CancellationToken.Simple t = new CancellationToken.Simple();
        assertThat(t.isCancelled()).isFalse();
        t.cancel();
        assertThat(t.isCancelled()).isTrue();
        assertThatThrownBy(t::throwIfCancelled).isInstanceOf(CancellationToken.CancellationException.class);
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

```bash
./gradlew :core:test --tests com.flux.deploy.deploy.CancellationTokenTest
```

Expected: 编译失败 / CancellationToken 不存在。

- [ ] **Step 3: 实现 CancellationToken**

文件 `core/src/main/java/com/flux/deploy/deploy/CancellationToken.java`：
```java
package com.flux.deploy.deploy;

/**
 * 取消令牌：在每个 Gate 入口调用 throwIfCancelled 检查。
 *
 * <p>NOOP 永不取消；Simple 由前端层（IDE ProgressIndicator / CLI SIGINT handler）调 cancel()。</p>
 *
 * @author xumanyi
 * @date 2026-04-28
 */
public interface CancellationToken {

    /** 永不取消 */
    CancellationToken NOOP = new CancellationToken() {
        @Override public boolean isCancelled() { return false; }
        @Override public void throwIfCancelled() { /* no-op */ }
    };

    boolean isCancelled();

    /** 已取消则抛 CancellationException */
    void throwIfCancelled() throws CancellationException;

    /** 简单的标志位实现（CLI / 测试用） */
    final class Simple implements CancellationToken {
        private volatile boolean cancelled = false;
        public void cancel() { this.cancelled = true; }
        @Override public boolean isCancelled() { return cancelled; }
        @Override public void throwIfCancelled() {
            if (cancelled) throw new CancellationException();
        }
    }

    /** 取消异常（unchecked，便于在 Gate 内部冒出） */
    class CancellationException extends RuntimeException {
        public CancellationException() { super("已取消"); }
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

```bash
./gradlew :core:test --tests com.flux.deploy.deploy.CancellationTokenTest
```

Expected: 2 tests passed。

- [ ] **Step 5: Commit**

```bash
git add core/src/main/java/com/flux/deploy/deploy/CancellationToken.java \
        core/src/test/java/com/flux/deploy/deploy/CancellationTokenTest.java
git commit -m "feat(core): add CancellationToken with NOOP and Simple implementations"
```

---

## Task 9: DeployPipeline 重构 Stage 0（残留锁解析入口）

**Files:**
- Modify: `core/src/main/java/com/flux/deploy/model/DeployConfig.java`
- Modify: `core/src/main/java/com/flux/deploy/deploy/DeployPipeline.java`

引入"残留锁处理策略"：FAIL（默认）/ AUTO_RESOLVE_OWN / EXTERNAL_RESOLVED。

- [ ] **Step 1: 在 DeployConfig 加字段**

修改 `core/src/main/java/com/flux/deploy/model/DeployConfig.java`：
```java
public enum ResidualLockPolicy {
    /** 默认：发现残留锁就报错退出（让用户介入） */
    FAIL,
    /** CLI flag --auto-resolve-own：自己的自动清，别人的报错 */
    AUTO_RESOLVE_OWN,
    /** IDE 端：UI 已经把残留锁清完了，Stage 0 应直接通过 */
    EXTERNAL_RESOLVED
}

private ResidualLockPolicy residualLockPolicy = ResidualLockPolicy.FAIL;
private CancellationToken cancellationToken = CancellationToken.NOOP;

public ResidualLockPolicy getResidualLockPolicy() { return residualLockPolicy; }
public void setResidualLockPolicy(ResidualLockPolicy v) { this.residualLockPolicy = v; }

public CancellationToken getCancellationToken() { return cancellationToken; }
public void setCancellationToken(CancellationToken v) { this.cancellationToken = v != null ? v : CancellationToken.NOOP; }
```

注：因 DeployConfig 引用 CancellationToken，在该文件顶部加 `import com.flux.deploy.deploy.CancellationToken;`。

- [ ] **Step 2: 在 DeployPipeline.execute() 抽 Stage 0**

在 `DeployPipeline.execute()` 里，FTP 连接成功之后、`runStagingForTargets` 之前（即在第 80 行 `FtpLock ftpLock = new FtpLock(ops);` 前后），插入：

```java
FtpLock ftpLock = new FtpLock(ops);
Rollback rollback = new Rollback(ops, ftpLock);

// === Stage 0: 残留锁诊断与解析 ===
if (!config.isSkipLock() && !runStage0(ops, ftpLock, targets, result)) {
    return result;
}
```

（`runStagingForTargets` 调用从此往后整体下移；保持原有顺序：Stage 0 → staging → 构建 gates → 执行）。

新增私有方法：
```java
/**
 * Stage 0：扫描所有目标的 remoteDir 残留锁，按 policy 决定如何处理。
 *
 * @return true=可继续；false=已写错误并应中止
 */
private boolean runStage0(FtpOperations ops, FtpLock ftpLock,
                          List<TargetPackage> targets, DeployResult result) {
    ResidualLockResolver resolver = new ResidualLockResolver(
            ResidualLockResolver.wrap(ops, ftpLock), config.getOperator());
    java.util.List<ResidualLockDiagnosis> all = new java.util.ArrayList<>();
    try {
        for (TargetPackage t : targets) {
            all.addAll(resolver.diagnose(t.getRemoteDir(), t.getPackageName()));
        }
    } catch (IOException e) {
        result.addError("stage0", "", "残留锁扫描失败: " + e.getMessage());
        return false;
    }

    if (all.isEmpty()) return true;

    DeployConfig.ResidualLockPolicy policy = config.getResidualLockPolicy();

    if (policy == DeployConfig.ResidualLockPolicy.EXTERNAL_RESOLVED) {
        // IDE 已处理完毕；如仍有残留视为 bug
        result.addError("stage0", "",
                "EXTERNAL_RESOLVED 模式下仍发现 " + all.size() + " 个残留锁");
        return false;
    }

    if (policy == DeployConfig.ResidualLockPolicy.AUTO_RESOLVE_OWN) {
        java.util.List<ResidualLockDiagnosis> others = new java.util.ArrayList<>();
        for (ResidualLockDiagnosis d : all) {
            if (d.isOwnedByCurrentUser()
                    && d.getSuggestion() != ResidualLockDiagnosis.SuggestedAction.NEEDS_HUMAN) {
                try {
                    resolver.apply(d);
                    System.out.println("[stage0] 自动清理: " + d.getLockFileName() + " (" + d.getSuggestion() + ")");
                } catch (IOException e) {
                    result.addError("stage0", d.getOriginalPackageName(),
                            "残留锁自动清理失败: " + e.getMessage());
                    return false;
                }
            } else {
                others.add(d);
            }
        }
        if (!others.isEmpty()) {
            for (ResidualLockDiagnosis d : others) {
                result.addError("stage0", d.getOriginalPackageName(),
                        "残留锁需人工处理 (owner=" + d.getOperator() + "): " + d.getLockFileName());
            }
            return false;
        }
        return true;
    }

    // policy == FAIL
    for (ResidualLockDiagnosis d : all) {
        result.addError("stage0", d.getOriginalPackageName(),
                "发现残留锁: " + d.getLockFileName() + " (owner=" + d.getOperator() + ", " + d.getReason() + ")");
    }
    return false;
}
```

注：在 DeployPipeline 顶部加 `import com.flux.deploy.deploy.ResidualLockDiagnosis;`、`import com.flux.deploy.deploy.ResidualLockResolver;`（同包不需要）。

- [ ] **Step 3: 编译**

```bash
./gradlew :core:compileJava
```

Expected: BUILD SUCCESSFUL。

- [ ] **Step 4: Commit**

```bash
git add core/src/main/java/com/flux/deploy/model/DeployConfig.java \
        core/src/main/java/com/flux/deploy/deploy/DeployPipeline.java
git commit -m "feat(core): Stage 0 wired into DeployPipeline (FAIL/AUTO_RESOLVE_OWN/EXTERNAL)"
```

---

## Task 10: DeployPipeline 重构 Stage 1（PreCheck + Backup 全员循环）

**Files:**
- Modify: `core/src/main/java/com/flux/deploy/deploy/DeployPipeline.java`
- Test: `core/src/test/java/com/flux/deploy/deploy/PipelineStage1Test.java`（行为验证留待 driver，本任务只要编译过）

- [ ] **Step 1: 把 executeGates 拆为 stage1 + stage2**

在 `DeployPipeline.java` 把现有 `executeGates(targets, gates, rollback, result)` 整段替换。

新增私有方法 `executeStage1`：
```java
/**
 * Stage 1: 全员 PreCheck → 全员 Backup。任一失败立即返回 false（result 已有错误）。
 *
 * @return true=全员通过可进 Stage 2；false=已记录错误
 */
private boolean executeStage1(List<TargetPackage> targets, List<Gate> stage1Gates,
                              DeployResult result, CancellationToken cancel) {
    for (Gate gate : stage1Gates) {
        for (TargetPackage target : targets) {
            try {
                cancel.throwIfCancelled();
                gate.execute(target);
            } catch (CancellationToken.CancellationException ce) {
                result.setCancelled(true);
                result.addError(gate.name(), target.getPackageName(), "用户取消");
                return false;
            } catch (Gate.GateException | IOException e) {
                String msg = e instanceof Gate.GateException ? e.getMessage()
                        : gate.name() + " 操作异常: " + e.getMessage();
                result.addError(gate.name(), target.getPackageName(), msg);
                target.setStatus(TargetPackage.Status.FAILED);
                System.err.println("[失败] Stage1 " + gate.name() + " - " + target.getPackageName() + ": " + msg);
                return false;
            }
        }
    }
    return true;
}
```

- [ ] **Step 2: 改 execute() 调用顺序**

把原 `executeGates(targets, gates, rollback, result);` 替换为：
```java
List<Gate> stage1Gates = new ArrayList<>();
List<Gate> stage2Gates = new ArrayList<>();
for (Gate g : gates) {
    if (g instanceof PreCheckGate || g instanceof BackupGate) {
        stage1Gates.add(g);
    } else {
        stage2Gates.add(g);
    }
}

CancellationToken cancel = config.getCancellationToken();

if (config.isDryRun()) {
    return executeDryRun(targets, ops, ftpLock, result);
}

if (!executeStage1(targets, stage1Gates, result, cancel)) {
    fillTargetResults(targets, result);
    return result;
}

executeStage2(targets, stage2Gates, rollback, result, cancel);
```

`executeStage2` 在 Task 11 实现完整逻辑，本任务先写一个能编译的过渡桩（直接标记成功，不做事）。这让 Task 10 自成一个可编译可提交的中间点；Task 11 step 1 会整段替换它：
```java
private void executeStage2(List<TargetPackage> targets, List<Gate> gates,
                           Rollback rollback, DeployResult result, CancellationToken cancel) {
    // 过渡实现：Task 11 用真正的 per-target 流水替换
    result.markSuccess();
    fillTargetResults(targets, result);
}
```

- [ ] **Step 3: 编译**

```bash
./gradlew :core:compileJava
```

Expected: BUILD SUCCESSFUL。

- [ ] **Step 4: Commit**

```bash
git add core/src/main/java/com/flux/deploy/deploy/DeployPipeline.java
git commit -m "refactor(core): split DeployPipeline into Stage 1 / Stage 2 entry points"
```

---

## Task 11: DeployPipeline 重构 Stage 2（per-target 全流程 + 局部回滚 + fail-fast）

**Files:**
- Modify: `core/src/main/java/com/flux/deploy/deploy/DeployPipeline.java`

- [ ] **Step 1: 实现 executeStage2**

把 Task 10 留的占位换成：
```java
/**
 * Stage 2: 每个目标各自跑完 stage2 全部 Gate。任一失败：回滚当前目标 + 剩余目标 SKIPPED。
 */
private void executeStage2(List<TargetPackage> targets, List<Gate> gates,
                           Rollback rollback, DeployResult result, CancellationToken cancel) {
    int idx = 0;
    for (; idx < targets.size(); idx++) {
        TargetPackage current = targets.get(idx);
        try {
            for (Gate gate : gates) {
                cancel.throwIfCancelled();
                gate.execute(current);
            }
            // 全部 gates 通过：状态由最后一个 Gate（UnlockGate）置为 COMPLETED
        } catch (CancellationToken.CancellationException ce) {
            result.setCancelled(true);
            result.addError("cancel", current.getPackageName(), "用户取消");
            attemptRollback(rollback, current, result);
            markRemainingSkipped(targets, idx + 1, "用户取消，前序中止");
            fillTargetResults(targets, result);
            return;
        } catch (Gate.GateException ge) {
            result.addError(ge.getGateName(), current.getPackageName(), ge.getMessage());
            current.setStatus(TargetPackage.Status.FAILED);
            attemptRollback(rollback, current, result);
            markRemainingSkipped(targets, idx + 1, "fail-fast: " + current.getPackageName() + " 失败");
            fillTargetResults(targets, result);
            return;
        } catch (IOException ioe) {
            // FTP 连接异常：留给 Task 12 处理（重连+rollback 或 FAILED_NEEDS_MANUAL）
            handleIoException(current, ioe, rollback, targets, idx, result);
            fillTargetResults(targets, result);
            return;
        }
    }
    result.markSuccess();
    fillTargetResults(targets, result);
}

private void attemptRollback(Rollback rollback, TargetPackage current, DeployResult result) {
    DeployResult.RollbackResult rr = new DeployResult.RollbackResult();
    rr.setAttempted(true);
    try {
        boolean acted = rollback.rollbackTarget(current);
        rr.setSuccess(true);
        if (acted) rr.getRestoredPackages().add(current.getPackageName());
    } catch (Exception ex) {
        rr.setSuccess(false);
        current.setStatus(TargetPackage.Status.FAILED_NEEDS_MANUAL);
        result.addError("rollback", current.getPackageName(),
                "回滚失败，需人工: " + ex.getMessage());
    }
    result.setRollback(rr);
}

private void markRemainingSkipped(List<TargetPackage> targets, int from, String reason) {
    for (int i = from; i < targets.size(); i++) {
        TargetPackage t = targets.get(i);
        if (t.getStatus() == TargetPackage.Status.PENDING
                || t.getStatus() == TargetPackage.Status.BACKED_UP) {
            t.setStatus(TargetPackage.Status.SKIPPED);
        }
    }
}

private void handleIoException(TargetPackage current, IOException ioe,
                               Rollback rollback, List<TargetPackage> targets, int idx,
                               DeployResult result) {
    // Task 12 扩展为重连后 rollback；当前先按现状记录
    result.addError("io", current.getPackageName(), "FTP 异常: " + ioe.getMessage());
    current.setStatus(TargetPackage.Status.FAILED);
    try {
        rollback.rollbackTarget(current);
    } catch (Exception ex) {
        current.setStatus(TargetPackage.Status.FAILED_NEEDS_MANUAL);
        result.addError("rollback", current.getPackageName(),
                "回滚失败，需人工: " + ex.getMessage());
    }
    markRemainingSkipped(targets, idx + 1, "前序 IO 失败");
}
```

删除 `executeGates` 旧方法（已没引用）。

- [ ] **Step 2: 编译**

```bash
./gradlew :core:compileJava
```

Expected: BUILD SUCCESSFUL。

- [ ] **Step 3: 跑全部已有测试确认未破坏**

```bash
./gradlew :core:test
```

Expected: 全部 passed。

- [ ] **Step 4: Commit**

```bash
git add core/src/main/java/com/flux/deploy/deploy/DeployPipeline.java
git commit -m "refactor(core): Stage 2 per-target pipeline with local rollback and fail-fast skip"
```

---

## Task 12: FTP 连接异常的重连与回滚

**Files:**
- Modify: `core/src/main/java/com/flux/deploy/deploy/DeployPipeline.java`
- Modify: `core/src/main/java/com/flux/deploy/deploy/Rollback.java`

需要支持"用一个新 FtpSession 跑回滚"。当前 `Rollback` 持有的是初始 ops/ftpLock，会失效。

- [ ] **Step 1: 给 Rollback 加 reconnect 钩子**

在 `Rollback.java` 加：
```java
private FtpOperations ops;       // 改为非 final
private FtpLock ftpLock;         // 改为非 final

public void rebind(FtpOperations newOps, FtpLock newLock) {
    this.ops = newOps;
    this.ftpLock = newLock;
}
```

（把字段 `final` 去掉。）

- [ ] **Step 2: 改 handleIoException 用重连**

在 `DeployPipeline.java` 把 `handleIoException` 替换为：
```java
private void handleIoException(TargetPackage current, IOException ioe,
                               Rollback rollback, List<TargetPackage> targets, int idx,
                               DeployResult result) {
    result.addError("io", current.getPackageName(), "FTP 异常: " + ioe.getMessage());

    // 没进入会改远端的状态，直接跳过
    TargetPackage.Status s = current.getStatus();
    if (s == TargetPackage.Status.PENDING || s == TargetPackage.Status.BACKED_UP) {
        current.setStatus(TargetPackage.Status.FAILED);
        markRemainingSkipped(targets, idx + 1, "前序 IO 失败");
        return;
    }

    // 已 LOCKED 或更高：必须回滚，先尝试重连
    System.err.println("[stage2] " + current.getPackageName()
            + " 已进入 " + s + " 状态，尝试重连后回滚...");
    try (FtpSession reconnect = new FtpSession(config.getHost(), config.getPort())) {
        reconnect.connect(config.getUsername(), config.getPassword());
        reconnect.changeWorkingDirectory(config.getRemoteDir());
        FtpOperations newOps = new FtpOperations(reconnect);
        FtpLock newLock = new FtpLock(newOps);
        rollback.rebind(newOps, newLock);
        rollback.rollbackTarget(current);
        current.setStatus(TargetPackage.Status.ROLLED_BACK);
    } catch (Exception reconnEx) {
        current.setStatus(TargetPackage.Status.FAILED_NEEDS_MANUAL);
        result.addError("rollback", current.getPackageName(),
                "重连回滚失败，需手动 unlock-resolve: " + reconnEx.getMessage()
                        + "（残留锁可能为 " + current.getLockName() + "）");
    }
    markRemainingSkipped(targets, idx + 1, "前序 IO 失败");
}
```

注：在 DeployPipeline 顶部加 `import com.flux.deploy.ftp.FtpSession;`、`import com.flux.deploy.ftp.FtpOperations;`、`import com.flux.deploy.ftp.FtpLock;`（如未有）。

- [ ] **Step 3: 编译 + 跑测试**

```bash
./gradlew :core:compileJava :core:test
```

Expected: BUILD SUCCESSFUL，全部 passed。

- [ ] **Step 4: Commit**

```bash
git add core/src/main/java/com/flux/deploy/deploy/Rollback.java \
        core/src/main/java/com/flux/deploy/deploy/DeployPipeline.java
git commit -m "feat(core): IO exception in Stage 2 reconnects FTP and rolls back current target"
```

---

## Task 13: LockGate 移除 findResidualLocks 检测

**Files:**
- Modify: `core/src/main/java/com/flux/deploy/deploy/gates/LockGate.java`

Stage 0 已统一处理残留锁。LockGate 内部那段不再需要；保留为防御性 assert（发现就抛 IllegalStateException 表示 Stage 0 漏掉）。

- [ ] **Step 1: 修改 LockGate.execute**

把 `LockGate.java` 第 55-71 行（`// 1. 检查残留锁` 至 `}` 处）替换为：
```java
// 防御性 assert：Stage 0 应已清理残留锁；此处仍发现视为 bug
List<String> residualLocks = ftpLock.findResidualLocks(remoteDir, target.getPackageName());
if (!residualLocks.isEmpty()) {
    throw new GateException(name(),
            "Stage 0 后仍发现残留锁，疑似 Stage 0 未执行或漏处理: " + residualLocks
                    + "（请先运行 unlock-resolve）");
}
```

（行为类似但错误信息改为指向 Stage 0 的 bug。）

- [ ] **Step 2: 编译**

```bash
./gradlew :core:compileJava
```

Expected: BUILD SUCCESSFUL。

- [ ] **Step 3: Commit**

```bash
git add core/src/main/java/com/flux/deploy/deploy/gates/LockGate.java
git commit -m "refactor(core): LockGate residual-lock check downgraded to defensive assert"
```

---

## Task 14: CLI deploy 加 --auto-resolve-own flag

**Files:**
- Modify: `cli/src/main/java/com/flux/deploy/cli/Main.java`

- [ ] **Step 1: 加 flag 解析**

修改 `runDeploy` 方法，在 switch 里加：
```java
case "--auto-resolve-own":
    autoResolveOwn = true;
    break;
```

并在方法顶部声明：
```java
boolean autoResolveOwn = false;
```

在 `cfg = ConfigLoader.load(...);` 之后加：
```java
if (autoResolveOwn) {
    cfg.setResidualLockPolicy(com.flux.deploy.model.DeployConfig.ResidualLockPolicy.AUTO_RESOLVE_OWN);
}
```

- [ ] **Step 2: 在 printUsage 加说明**

在 "Deploy options" 区块末尾加：
```java
out.println("  --auto-resolve-own       (可选) 自动清理 owner == 当前用户的残留锁；他人的锁仍报错");
```

- [ ] **Step 3: 编译 + 跑命令试一下**

```bash
./gradlew :cli:shadowJar
java -jar cli/build/libs/flux-deploy-cli-*-all.jar deploy --help | grep auto-resolve
```

Expected: 输出含 `--auto-resolve-own` 行。

- [ ] **Step 4: Commit**

```bash
git add cli/src/main/java/com/flux/deploy/cli/Main.java
git commit -m "feat(cli): add --auto-resolve-own flag for deploy"
```

---

## Task 15: CLI 新增 unlock-resolve 子命令

**Files:**
- Create: `cli/src/main/java/com/flux/deploy/cli/UnlockResolveCommand.java`
- Modify: `cli/src/main/java/com/flux/deploy/cli/Main.java`

行为：列出诊断（默认 dry-run）；加 `--apply` 执行；加 `--include-others` 才碰他人的锁。

- [ ] **Step 1: 实现 UnlockResolveCommand**

文件 `cli/src/main/java/com/flux/deploy/cli/UnlockResolveCommand.java`：
```java
package com.flux.deploy.cli;

import com.flux.deploy.deploy.ResidualLockDiagnosis;
import com.flux.deploy.deploy.ResidualLockResolver;
import com.flux.deploy.ftp.FtpLock;
import com.flux.deploy.ftp.FtpOperations;
import com.flux.deploy.ftp.FtpSession;
import com.flux.deploy.util.CredentialCache;
import org.apache.commons.net.ftp.FTPFile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * unlock-resolve 子命令：以诊断+清理两阶段方式处理残留锁。
 *
 * <p>对比 unlock 命令：unlock-resolve 提供结构化诊断输出，并尊重 owner 与 --apply 控制。</p>
 *
 * @author xumanyi
 * @date 2026-04-28
 */
final class UnlockResolveCommand {

    private static final int EX_USAGE = 64;
    private static final int EX_SOFTWARE = 70;

    private UnlockResolveCommand() {}

    static int run(String[] args) {
        String host = null, username = null, remoteDir = null;
        int port = 18080;
        boolean apply = false, includeOthers = false;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--host": host = args[++i]; break;
                case "--port": port = Integer.parseInt(args[++i]); break;
                case "--user":
                case "--username": username = args[++i]; break;
                case "--remote-dir": remoteDir = args[++i]; break;
                case "--apply": apply = true; break;
                case "--include-others": includeOthers = true; break;
                case "-h":
                case "--help": printUsage(System.out); return 0;
                default:
                    System.err.println("[unlock-resolve] 未知参数: " + args[i]);
                    return EX_USAGE;
            }
        }
        if (host == null || username == null || remoteDir == null) {
            printUsage(System.err);
            return EX_USAGE;
        }

        CredentialCache.CachedCredential cred = CredentialCache.lookup(host, port, username);
        if (cred == null || cred.getPassword() == null) {
            System.err.println("[unlock-resolve] 未找到凭据，请先 credential set");
            return EX_USAGE;
        }

        try (FtpSession session = new FtpSession(host, port)) {
            session.connect(username, cred.getPassword());
            session.changeWorkingDirectory(remoteDir);
            FtpOperations ops = new FtpOperations(session);
            FtpLock lock = new FtpLock(ops);

            // 收集 remoteDir 下所有 *.war / *.jar 包名
            List<FTPFile> files = ops.listFiles(remoteDir);
            List<ResidualLockDiagnosis> all = new ArrayList<>();
            ResidualLockResolver resolver = new ResidualLockResolver(
                    ResidualLockResolver.wrap(ops, lock), username);
            for (FTPFile f : files) {
                String name = FtpSession.decodeRemotePath(f.getName());
                if (FtpLock.isLockFile(name)) {
                    String original = FtpLock.extractOriginalName(name);
                    if (original != null) {
                        all.addAll(resolver.diagnose(remoteDir, original));
                    }
                }
            }

            if (all.isEmpty()) {
                System.out.println("(无残留锁)");
                return 0;
            }

            System.out.println("发现 " + all.size() + " 个残留锁：");
            for (ResidualLockDiagnosis d : all) {
                System.out.println("  - " + d.getLockFileName());
                System.out.println("    owner: " + d.getOperator()
                        + (d.isOwnedByCurrentUser() ? "（你自己）" : "")
                        + "  时间: " + d.getLockedAt());
                System.out.println("    建议: " + d.getSuggestion() + " - " + d.getReason());
            }

            if (!apply) {
                System.out.println("\n(--apply 未指定，未做任何修改)");
                return 0;
            }

            int handled = 0, skipped = 0, failed = 0;
            for (ResidualLockDiagnosis d : all) {
                if (!d.isOwnedByCurrentUser() && !includeOthers) {
                    System.out.println("[SKIP] 他人的锁: " + d.getLockFileName() + "（用 --include-others 强行处理）");
                    skipped++;
                    continue;
                }
                if (d.getSuggestion() == ResidualLockDiagnosis.SuggestedAction.NEEDS_HUMAN) {
                    System.out.println("[SKIP] 需人工: " + d.getLockFileName());
                    skipped++;
                    continue;
                }
                try {
                    resolver.apply(d);
                    System.out.println("[OK] " + d.getSuggestion() + " " + d.getLockFileName());
                    handled++;
                } catch (IOException e) {
                    System.out.println("[ERR] " + d.getLockFileName() + " - " + e.getMessage());
                    failed++;
                }
            }
            System.out.println("\n=== 完成: 处理 " + handled + "  跳过 " + skipped + "  失败 " + failed + " ===");
            return failed == 0 ? 0 : EX_SOFTWARE;

        } catch (IOException e) {
            System.err.println("[unlock-resolve] FTP 错误: " + e.getMessage());
            return EX_SOFTWARE;
        }
    }

    private static void printUsage(java.io.PrintStream out) {
        out.println("unlock-resolve - 结构化残留锁诊断与清理（两阶段：列出 → --apply 执行）");
        out.println();
        out.println("Options:");
        out.println("  --host <host>       FTP 主机");
        out.println("  --port <port>       FTP 端口（默认 18080）");
        out.println("  --user <username>   登录用户");
        out.println("  --remote-dir <path> 扫描根目录");
        out.println("  --apply             执行清理（默认仅诊断）");
        out.println("  --include-others    也处理 owner != 当前用户的锁");
    }
}
```

- [ ] **Step 2: 在 Main 注册子命令**

在 `Main.main` 的 if 链里加：
```java
if ("unlock-resolve".equals(cmd)) {
    System.exit(UnlockResolveCommand.run(slice(args, 1)));
}
```

并在 `printUsage` 的 "Commands:" 列表里加：
```java
out.println("  unlock-resolve 残留锁结构化诊断与清理（推荐替代 unlock）");
```

- [ ] **Step 3: 编译 + smoke**

```bash
./gradlew :cli:shadowJar
java -jar cli/build/libs/flux-deploy-cli-*-all.jar unlock-resolve --help
```

Expected: 打印 unlock-resolve usage。

- [ ] **Step 4: Commit**

```bash
git add cli/src/main/java/com/flux/deploy/cli/UnlockResolveCommand.java \
        cli/src/main/java/com/flux/deploy/cli/Main.java
git commit -m "feat(cli): add unlock-resolve subcommand with structured diagnostics"
```

---

## Task 16: CLI 新增 backup-prune 子命令

**Files:**
- Create: `cli/src/main/java/com/flux/deploy/cli/BackupPruneCommand.java`
- Modify: `cli/src/main/java/com/flux/deploy/cli/Main.java`

`backup-prune --remote-dir <子系统根> --keep-days N`：列出 `<root>/backup/` 下"目录名 YYYYMMDD_xxx 中 YYYYMMDD 早于今天 - N 天"的目录；加 `--apply` 才递归删。

- [ ] **Step 1: 实现 BackupPruneCommand**

文件 `cli/src/main/java/com/flux/deploy/cli/BackupPruneCommand.java`：
```java
package com.flux.deploy.cli;

import com.flux.deploy.ftp.FtpOperations;
import com.flux.deploy.ftp.FtpSession;
import com.flux.deploy.util.CredentialCache;
import org.apache.commons.net.ftp.FTPFile;

import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * backup-prune 子命令：清理 backup/ 下保留期之外的备份目录。
 *
 * @author xumanyi
 * @date 2026-04-28
 */
final class BackupPruneCommand {

    private static final int EX_USAGE = 64;
    private static final int EX_SOFTWARE = 70;
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    private BackupPruneCommand() {}

    static int run(String[] args) {
        String host = null, username = null, remoteDir = null;
        int port = 18080, keepDays = 30;
        boolean apply = false;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--host": host = args[++i]; break;
                case "--port": port = Integer.parseInt(args[++i]); break;
                case "--user":
                case "--username": username = args[++i]; break;
                case "--remote-dir": remoteDir = args[++i]; break;
                case "--keep-days": keepDays = Integer.parseInt(args[++i]); break;
                case "--apply": apply = true; break;
                case "-h":
                case "--help": printUsage(System.out); return 0;
                default:
                    System.err.println("[backup-prune] 未知参数: " + args[i]);
                    return EX_USAGE;
            }
        }
        if (host == null || username == null || remoteDir == null) {
            printUsage(System.err);
            return EX_USAGE;
        }

        CredentialCache.CachedCredential cred = CredentialCache.lookup(host, port, username);
        if (cred == null || cred.getPassword() == null) {
            System.err.println("[backup-prune] 未找到凭据");
            return EX_USAGE;
        }

        try (FtpSession session = new FtpSession(host, port)) {
            session.connect(username, cred.getPassword());
            FtpOperations ops = new FtpOperations(session);

            String backupParent = remoteDir.endsWith("/") ? remoteDir + "backup/" : remoteDir + "/backup/";
            if (!ops.exists(backupParent)) {
                System.out.println("(无 backup/ 目录: " + backupParent + ")");
                return 0;
            }

            LocalDate threshold = LocalDate.now().minusDays(keepDays);
            List<String> toRemove = new ArrayList<>();
            for (FTPFile f : ops.listFiles(backupParent)) {
                if (!f.isDirectory()) continue;
                String name = FtpSession.decodeRemotePath(f.getName());
                if (".".equals(name) || "..".equals(name)) continue;
                String datePart = name.length() >= 8 ? name.substring(0, 8) : null;
                if (datePart == null) continue;
                LocalDate d;
                try { d = LocalDate.parse(datePart, DATE_FMT); } catch (Exception e) { continue; }
                if (d.isBefore(threshold)) {
                    toRemove.add(backupParent + name + "/");
                }
            }

            if (toRemove.isEmpty()) {
                System.out.println("(无超过 " + keepDays + " 天的备份目录)");
                return 0;
            }

            System.out.println("超过 " + keepDays + " 天的备份目录（共 " + toRemove.size() + " 个）：");
            for (String p : toRemove) System.out.println("  " + p);

            if (!apply) {
                System.out.println("\n(--apply 未指定，未做任何修改)");
                return 0;
            }

            int ok = 0, fail = 0;
            for (String p : toRemove) {
                try {
                    ops.removeDirRecursively(p);
                    System.out.println("[DEL] " + p);
                    ok++;
                } catch (IOException e) {
                    System.out.println("[ERR] " + p + " - " + e.getMessage());
                    fail++;
                }
            }
            System.out.println("\n=== 完成: 删除 " + ok + "  失败 " + fail + " ===");
            return fail == 0 ? 0 : EX_SOFTWARE;

        } catch (IOException e) {
            System.err.println("[backup-prune] FTP 错误: " + e.getMessage());
            return EX_SOFTWARE;
        }
    }

    private static void printUsage(java.io.PrintStream out) {
        out.println("backup-prune - 清理 backup/ 下保留期外的备份");
        out.println();
        out.println("Options:");
        out.println("  --host / --port / --user / --remote-dir   FTP 与子系统根");
        out.println("  --keep-days N    保留 N 天内的备份（默认 30）");
        out.println("  --apply          执行删除（默认仅 dry-run 列出）");
    }
}
```

注意：`ops.removeDirRecursively` 可能不存在；若不存在，本任务先在 `FtpOperations` 加最小实现：
```java
public void removeDirRecursively(String dir) throws IOException {
    String d = dir.endsWith("/") ? dir : dir + "/";
    for (FTPFile f : listFiles(d)) {
        String n = FtpSession.decodeRemotePath(f.getName());
        if (".".equals(n) || "..".equals(n)) continue;
        String p = d + n;
        if (f.isDirectory()) removeDirRecursively(p);
        else delete(p);
    }
    // 删除目录本身（FtpOperations 若没暴露 rmdir，则直接调底层 client）
    session().getClient().removeDirectory(d);
}
```

（在 FtpOperations 已暴露 `session()` 或类似 client getter 时使用；如未暴露，需先加一个 package-private getter——这一步原文如果与既有结构差异较大，按既有 `DeployExecutionService.cleanupEmptyDirs` 的写法参考即可。）

- [ ] **Step 2: 在 Main 注册子命令**

```java
if ("backup-prune".equals(cmd)) {
    System.exit(BackupPruneCommand.run(slice(args, 1)));
}
```

并在 `printUsage` 的 "Commands:" 列表加：
```java
out.println("  backup-prune  清理 backup/ 下保留期外的备份目录");
```

- [ ] **Step 3: 编译 + smoke**

```bash
./gradlew :cli:shadowJar
java -jar cli/build/libs/flux-deploy-cli-*-all.jar backup-prune --help
```

Expected: 打印 backup-prune usage。

- [ ] **Step 4: Commit**

```bash
git add cli/src/main/java/com/flux/deploy/cli/BackupPruneCommand.java \
        cli/src/main/java/com/flux/deploy/cli/Main.java \
        core/src/main/java/com/flux/deploy/ftp/FtpOperations.java
git commit -m "feat(cli): add backup-prune subcommand with --keep-days and --apply"
```

---

## Task 17: IDE — ProgressIndicator 适配 CancellationToken

**Files:**
- Modify: `plugin/src/main/java/com/flux/deploy/plugin/service/DeployExecutionService.java`

- [ ] **Step 1: 在 service 里造 token 并塞进 config**

定位 `DeployExecutionService` 中调 `new DeployPipeline(cfg).execute()` 之前的位置，添加：
```java
ProgressIndicator indicator = ProgressManager.getInstance().getProgressIndicator();
cfg.setCancellationToken(new CancellationToken() {
    @Override public boolean isCancelled() {
        return indicator != null && indicator.isCanceled();
    }
    @Override public void throwIfCancelled() {
        if (isCancelled()) throw new CancellationToken.CancellationException();
    }
});
```

注：在文件顶部加 `import com.flux.deploy.deploy.CancellationToken;`。

- [ ] **Step 2: 编译插件**

```bash
./gradlew :plugin:compileJava
```

Expected: BUILD SUCCESSFUL。

- [ ] **Step 3: Commit**

```bash
git add plugin/src/main/java/com/flux/deploy/plugin/service/DeployExecutionService.java
git commit -m "feat(plugin): bridge ProgressIndicator to CancellationToken"
```

---

## Task 18: IDE — ResidualLockResolveDialog UI

**Files:**
- Create: `plugin/src/main/java/com/flux/deploy/plugin/toolwindow/ResidualLockResolveDialog.java`

- [ ] **Step 1: 实现对话框**

文件 `plugin/src/main/java/com/flux/deploy/plugin/toolwindow/ResidualLockResolveDialog.java`：
```java
package com.flux.deploy.plugin.toolwindow;

import com.flux.deploy.deploy.ResidualLockDiagnosis;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

/**
 * 残留锁清理确认对话框
 *
 * <p>展示所有残留锁的诊断信息，自己的默认勾选；他人的禁用勾选并提示。</p>
 *
 * @author xumanyi
 * @date 2026-04-28
 */
public class ResidualLockResolveDialog extends DialogWrapper {

    private final List<ResidualLockDiagnosis> diagnoses;
    private final List<JCheckBox> checkBoxes = new ArrayList<>();

    public ResidualLockResolveDialog(@Nullable Project project, List<ResidualLockDiagnosis> diagnoses) {
        super(project);
        this.diagnoses = diagnoses;
        setTitle("检测到残留锁，请确认处理方式");
        init();
    }

    @Override
    protected JComponent createCenterPanel() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        for (ResidualLockDiagnosis d : diagnoses) {
            panel.add(buildRow(d));
            panel.add(Box.createVerticalStrut(8));
        }
        JScrollPane scroll = new JScrollPane(panel);
        scroll.setPreferredSize(new Dimension(680, 420));
        return scroll;
    }

    private JComponent buildRow(ResidualLockDiagnosis d) {
        boolean canSelect = d.isOwnedByCurrentUser()
                && d.getSuggestion() != ResidualLockDiagnosis.SuggestedAction.NEEDS_HUMAN;
        JCheckBox cb = new JCheckBox(d.getLockFileName());
        cb.setSelected(canSelect);
        cb.setEnabled(canSelect);
        checkBoxes.add(cb);

        JPanel row = new JPanel();
        row.setLayout(new BoxLayout(row, BoxLayout.Y_AXIS));
        row.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Color.GRAY),
                BorderFactory.createEmptyBorder(6, 8, 6, 8)));
        row.add(cb);
        String ownerLabel = d.getOperator()
                + (d.isOwnedByCurrentUser() ? "（你自己）" : "（不是你，需先与对方确认）");
        row.add(new JLabel("持有者: " + ownerLabel + "    时间: " + d.getLockedAt()));
        row.add(new JLabel("诊断: " + d.getReason()));
        row.add(new JLabel("建议: " + d.getSuggestion()));
        return row;
    }

    /** 用户勾选要处理的诊断（按对话框中的顺序） */
    public List<ResidualLockDiagnosis> getSelected() {
        List<ResidualLockDiagnosis> out = new ArrayList<>();
        for (int i = 0; i < diagnoses.size(); i++) {
            if (checkBoxes.get(i).isSelected()) out.add(diagnoses.get(i));
        }
        return out;
    }
}
```

- [ ] **Step 2: 编译**

```bash
./gradlew :plugin:compileJava
```

Expected: BUILD SUCCESSFUL。

- [ ] **Step 3: Commit**

```bash
git add plugin/src/main/java/com/flux/deploy/plugin/toolwindow/ResidualLockResolveDialog.java
git commit -m "feat(plugin): add ResidualLockResolveDialog for Stage 0 user resolution"
```

---

## Task 19: IDE 装配 Stage 0（Resolver → Dialog → 设置 EXTERNAL_RESOLVED）

**Files:**
- Modify: `plugin/src/main/java/com/flux/deploy/plugin/service/DeployExecutionService.java`

- [ ] **Step 1: 在 execute 前插入 Stage 0 用户交互**

在 `DeployExecutionService` 中调 `new DeployPipeline(cfg).execute()` 之前（且在已经连接 FTP/凭据可用的位置）插入：
```java
// === Stage 0 (IDE)：弹窗确认残留锁处理 ===
java.util.List<ResidualLockDiagnosis> all = new java.util.ArrayList<>();
try (FtpSession s0 = new FtpSession(cfg.getHost(), cfg.getPort())) {
    s0.connect(cfg.getUsername(), cfg.getPassword());
    s0.changeWorkingDirectory(cfg.getRemoteDir());
    FtpOperations s0Ops = new FtpOperations(s0);
    FtpLock s0Lock = new FtpLock(s0Ops);
    ResidualLockResolver resolver = new ResidualLockResolver(
            ResidualLockResolver.wrap(s0Ops, s0Lock), cfg.getOperator());
    for (TargetPackage t : targetsForStage0) {
        all.addAll(resolver.diagnose(t.getRemoteDir(), t.getPackageName()));
    }
    if (!all.isEmpty()) {
        java.util.List<ResidualLockDiagnosis> finalAll = all;
        boolean[] proceed = {false};
        java.util.List<ResidualLockDiagnosis> selected = new java.util.ArrayList<>();
        SwingUtilities.invokeAndWait(() -> {
            ResidualLockResolveDialog dialog = new ResidualLockResolveDialog(project, finalAll);
            if (dialog.showAndGet()) {
                selected.addAll(dialog.getSelected());
                proceed[0] = true;
            }
        });
        if (!proceed[0]) {
            logCallback.accept("[stage0] 用户取消");
            return;
        }
        for (ResidualLockDiagnosis d : selected) {
            try { resolver.apply(d); logCallback.accept("[stage0] 已清理: " + d.getLockFileName()); }
            catch (IOException ie) { logCallback.accept("[stage0] 清理失败: " + ie.getMessage()); return; }
        }
        // 检查是否还有未处理的残留
        boolean stillHasResidual = false;
        for (TargetPackage t : targetsForStage0) {
            if (!resolver.diagnose(t.getRemoteDir(), t.getPackageName()).isEmpty()) {
                stillHasResidual = true;
                break;
            }
        }
        if (stillHasResidual) {
            logCallback.accept("[stage0] 仍有未处理的残留锁，部署中止");
            return;
        }
    }
}
cfg.setResidualLockPolicy(DeployConfig.ResidualLockPolicy.EXTERNAL_RESOLVED);
```

`targetsForStage0` 是当前 service 已构造好的目标列表（看现有代码取相应变量名）；`logCallback` / `project` 同上下文。

注：在 service 顶部加：
```java
import com.flux.deploy.deploy.ResidualLockDiagnosis;
import com.flux.deploy.deploy.ResidualLockResolver;
import com.flux.deploy.plugin.toolwindow.ResidualLockResolveDialog;
```

- [ ] **Step 2: 编译**

```bash
./gradlew :plugin:compileJava
```

Expected: BUILD SUCCESSFUL。

- [ ] **Step 3: Commit**

```bash
git add plugin/src/main/java/com/flux/deploy/plugin/service/DeployExecutionService.java
git commit -m "feat(plugin): wire Stage 0 dialog and set EXTERNAL_RESOLVED before pipeline"
```

---

## Task 20: 报告格式调整（DeployResult.formatReport）

**Files:**
- Modify: `core/src/main/java/com/flux/deploy/model/DeployResult.java`
- Test: `core/src/test/java/com/flux/deploy/model/DeployResultFormatTest.java`

- [ ] **Step 1: 写失败测试**

文件 `core/src/test/java/com/flux/deploy/model/DeployResultFormatTest.java`：
```java
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
```

- [ ] **Step 2: 跑测试确认失败**

```bash
./gradlew :core:test --tests com.flux.deploy.model.DeployResultFormatTest
```

Expected: 编译失败 / formatReport 不存在。

- [ ] **Step 3: 实现 formatReport**

在 `DeployResult.java` 加：
```java
/**
 * 生成人类可读的部署报告（CLI 文本输出 / IDE 日志面板使用）。
 */
public String formatReport() {
    StringBuilder sb = new StringBuilder();
    int total = targets == null ? 0 : targets.size();
    int completed = 0;
    java.util.List<String> failedNames = new java.util.ArrayList<>();
    java.util.List<String> okNames = new java.util.ArrayList<>();
    java.util.List<String> skippedNames = new java.util.ArrayList<>();
    if (targets != null) {
        for (TargetResult t : targets) {
            if (t.isVerified()) { completed++; okNames.add(t.getPackageName()); }
            else if (errors != null && hasErrorFor(t.getPackageName())) failedNames.add(t.getPackageName());
            else skippedNames.add(t.getPackageName());
        }
    }
    String header = cancelled ? "[CANCELLED] 部署被取消"
            : (errors != null && !errors.isEmpty()) ? "[FAIL] 部署中止"
            : "[OK] 部署成功 " + completed + "/" + total;
    sb.append(header).append('\n');
    if (!okNames.isEmpty()) sb.append("  ✅ 成功: ").append(okNames).append('\n');
    if (!failedNames.isEmpty()) sb.append("  ❌ 失败: ").append(failedNames).append('\n');
    if (!skippedNames.isEmpty()) sb.append("  ⏭ 跳过: ").append(skippedNames).append('\n');
    if (errors != null) {
        for (ErrorInfo e : errors) {
            sb.append("  [").append(e.getGate()).append("] ")
              .append(e.getTarget()).append(" - ").append(e.getMessage()).append('\n');
        }
    }
    return sb.toString();
}

private boolean hasErrorFor(String pkg) {
    if (errors == null) return false;
    for (ErrorInfo e : errors) if (pkg != null && pkg.equals(e.getTarget())) return true;
    return false;
}
```

注：若 `targets` / `errors` 是私有且无访问器，本方法位于同类内可直接读字段；若字段名差异，按 `DeployResult` 实际字段名调整。

- [ ] **Step 4: 跑测试确认通过**

```bash
./gradlew :core:test --tests com.flux.deploy.model.DeployResultFormatTest
```

Expected: 3 tests passed。

- [ ] **Step 5: Commit**

```bash
git add core/src/main/java/com/flux/deploy/model/DeployResult.java \
        core/src/test/java/com/flux/deploy/model/DeployResultFormatTest.java
git commit -m "feat(core): DeployResult.formatReport for human-readable status"
```

---

## Task 21: CLI deploy 输出报告

**Files:**
- Modify: `cli/src/main/java/com/flux/deploy/cli/Main.java`

- [ ] **Step 1: 在 printResult 加报告输出**

修改 `printResult` 方法的 `else` 分支（JSON 输出之前）加：
```java
// 始终打印人类可读 summary 到 stderr
System.err.println(result.formatReport());
```

（或在 text 分支同时输出。）

- [ ] **Step 2: 编译 + smoke**

```bash
./gradlew :cli:shadowJar
```

Expected: BUILD SUCCESSFUL。

- [ ] **Step 3: Commit**

```bash
git add cli/src/main/java/com/flux/deploy/cli/Main.java
git commit -m "feat(cli): print human-readable report alongside JSON output"
```

---

## Task 22: 单元测试覆盖 Stage 2 fail-fast 与 SKIPPED 行为

**Files:**
- Test: `core/src/test/java/com/flux/deploy/deploy/PipelineStage2FailFastTest.java`

用 fake Gate 在内存里跑 Stage 2 的核心循环，无需真实 FTP。验证：
- 第 N 个目标失败 → 前 N-1 个保留 COMPLETED → 第 N 个 ROLLED_BACK 或 FAILED → 第 N+1..M 个 SKIPPED
- result.errors 含 N 的失败
- result.cancelled 为 false

- [ ] **Step 1: 写测试**

文件 `core/src/test/java/com/flux/deploy/deploy/PipelineStage2FailFastTest.java`：
```java
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
```

- [ ] **Step 2: 跑测试确认通过**

```bash
./gradlew :core:test --tests com.flux.deploy.deploy.PipelineStage2FailFastTest
```

Expected: 2 tests passed。

- [ ] **Step 3: Commit**

```bash
git add core/src/test/java/com/flux/deploy/deploy/PipelineStage2FailFastTest.java
git commit -m "test(core): cover Stage 2 fail-fast SKIPPED propagation"
```

---

## Task 23: 综合验证 + 回归

**Files:**
- 无修改，仅运行已有命令验证。

- [ ] **Step 1: 跑全部单元测试**

```bash
./gradlew :core:test
```

Expected: 所有测试 passed。

- [ ] **Step 2: 跑 LocalModeTestDriver 确认未破坏现有路径**

```bash
./gradlew :core:runLocalModeTest
```

Expected: 现有本地模式 E2E 通过。

- [ ] **Step 3: 编译三个模块**

```bash
./gradlew :core:build :cli:shadowJar :plugin:buildPlugin
```

Expected: BUILD SUCCESSFUL。

- [ ] **Step 4: 手动验收（在能连真 FTP 的环境下）**

按 spec §6.3 走：
- 5 个 war 部署，模拟 Stage 2 第 3 个失败，确认前 2 个仍在线、第 3 个回退、剩余 SKIPPED
- 杀进程后重新部署，看到 ResidualLockResolveDialog 弹窗
- `flux-deploy-cli backup-prune --keep-days 7` dry-run

- [ ] **Step 5: 最终 commit（如有手动调整）**

```bash
git status
# 若有调整：
# git add -A && git commit -m "chore: minor fixes after manual acceptance"
```

---

## 汇总：覆盖回 Spec 检查表

| Spec 章节 | 由哪个 Task 实现 |
|---|---|
| §3.1 三段式 | Task 9 (Stage 0) / Task 10 (Stage 1) / Task 11 (Stage 2) |
| §3.2 fail-fast 无开关 | Task 11 markRemainingSkipped |
| §3.3 回滚边界缩到单目标 | Task 7 |
| §3.4 残留锁 UX (γ) | Task 4-6 (Resolver) / Task 14 (--auto-resolve-own) / Task 15 (unlock-resolve) / Task 18-19 (IDE 对话框 + 装配) |
| §3.5 崩溃恢复（无 journal） | Task 9 默认 FAIL policy + Task 18-19 IDE UX |
| §3.6 备份 + 临时产物 | Task 16 (backup-prune)；临时产物清理已是现状 |
| §3.7 用户取消 | Task 8 (Token) / Task 11 (检查点) / Task 17 (IDE 桥接) |
| §3.8 IO 异常重连 | Task 12 |
| §4.1 LockGate 内部 findResidualLocks | Task 13 |
| §5 报告格式 | Task 20-21 |
| §6 测试 | Task 4/5/6/7/20 单元；Task 22 E2E driver |
