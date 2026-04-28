# 批量 WAR 部署 健壮性与回滚语义重设计

- **状态**：草案，待 review
- **日期**：2026-04-28
- **作者**：xumanyi
- **影响模块**：`core/`（DeployPipeline / Rollback / Gates / FtpLock）、`cli/`（新增子命令）、`plugin/`（残留锁对话框、取消支持）
- **不影响**：FTP 协议层、凭证模块、编译/Maven 模块

---

## 1. 背景

当前 `core/src/main/java/com/flux/deploy/deploy/DeployPipeline.java` 的批量更新模型是 **"逐门禁分批 + 全量回滚"**：

```
PreCheck(T1..Tn) → Backup(T1..Tn) → Lock(T1..Tn) → Upload(T1..Tn)
                 → Verify(T1..Tn) → Note(T1..Tn) → Unlock(T1..Tn)

任一目标在任一门禁失败 → rollbackAll(全部目标)
```

该模型在以下三个场景暴露出问题：

### 问题 P1：FTP 连接异常时 rollback 被跳过

`DeployPipeline.execute()` 的 `try-with-resources` 在 `IOException` 抛到 catch 块（`DeployPipeline.java:111`）后直接 `addError + return`，没有调用 `Rollback.rollbackAll`。结果：如果连接断在 LockGate 之后，远端会留下一批锁文件，需要人工 `unlock` 清理。

### 问题 P2：已成功目标会被无辜回滚

`Rollback.rollbackTarget`（`Rollback.java:79-108`）的 `case COMPLETED` 分支调用 `restoreFromBackup`。也就是说当 30 个 war 中第 30 个失败、前 29 个已经走完 Unlock 进入 COMPLETED 时，前 29 个已经发布的新版本会被从 `backup/` 重新上传覆盖回去——把已经成功的工作作废。

虽然在"逐门禁分批"模型下正常路径不会出现"包 A COMPLETED 而包 B 还在 Backup"的状态分布，但只要 Unlock 自身可能失败（FTP 抖动、note 校验失败等），这种状态就会出现。

### 问题 P3：进程崩溃后无自动残留检测/恢复

JVM 被 kill / IDE 关闭 / OOM 时，所有运行时状态在内存丢光。下次启动只能靠 `LockGate.execute` 中的 `findResidualLocks` 抛异常来发现残留，UX 是"报错后让用户手动跑 `unlock`"，没有"是你自己的残留 → 一键清理"的便捷路径。

---

## 2. 设计目标

1. **健壮性 (B1)**：连接断、进程被 kill、用户取消都不留无主锁残留；可恢复或可清晰诊断
2. **回滚语义合理化**：批次失败时不波及已成功的目标，回滚边界缩到"当前失败的那一个"
3. **保持串行**：不引入并行（FTP 服务端速度受限，并行不会提升吞吐）
4. **行为统一**：CLI 与 IDE 插件共用同一套核心逻辑，差异仅在 UX 层

非目标（明确排除）：

- 并发/并行执行多个目标
- journal-based 自动恢复（写本地状态文件）
- "失败后继续做剩余目标" 开关（YAGNI，用户可手动重跑剩余目标）

---

## 3. 核心设计

### 3.1 执行模型：两段式

完整执行序列分三层：**Stage 0（残留锁解析）→ Stage 1（准备）→ Stage 2（流水）**。

```
┌────────── Stage 0（残留锁诊断与解析，远端只读 + 用户/flag 控制）──────────┐
│  ResidualLockResolver.diagnose(remoteDir) 列出所有残留锁                    │
│  IDE：弹清单对话框，用户勾选自己的，确认后清理                              │
│  CLI：默认报错退出；--auto-resolve-own 时自动清理自己的                     │
│  结束条件：远端无残留锁，或全部已被清理动作处理                             │
└─────────────────────────────────────────────────────────────────────────────┘
              │
              ▼
┌──────────────────── Stage 1（准备，零原包副作用）─────────────────────────┐
│  for each target:  PreCheck（本地包存在 + 远端原包存在）                    │
│  for each target:  Backup（下载远端原包 → 上传到 backup/）                   │
│  任一失败 → 整批中止，已上传的 backup 文件保留（无污染）                    │
└─────────────────────────────────────────────────────────────────────────────┘
              │ 全员通过才进入 Stage 2
              ▼
┌──────────────────── Stage 2（每个目标各自跑完整流水）─────────────────────┐
│  T1: Lock → Upload → Verify → Note → Unlock      ✅ COMPLETED              │
│  T2: Lock → Upload → Verify → Note → Unlock      ✅ COMPLETED              │
│  T3: Lock → Upload → Verify ❌                                              │
│      → 回滚 T3 自己（restoreLock 或 restoreFromBackup）                     │
│      → fail-fast：T4..Tn 全部跳过                                           │
│  T4..Tn: 状态 = SKIPPED（reason: 前序失败）                                  │
└─────────────────────────────────────────────────────────────────────────────┘
```

**为什么把残留锁检测从 LockGate 独立出来成 Stage 0：**

当前 [LockGate.java:55-71](core/src/main/java/com/flux/deploy/deploy/gates/LockGate.java:55) 内部做 `findResidualLocks`，发现残留就抛 `GateException`。这导致：

- 用户跑了一次部署，T1 备份完才在 Lock 阶段发现残留锁，错误体验滞后
- 残留锁的 UX 没法做"清单展示 + 用户勾选"，因为 LockGate 的接口只能抛异常
- 残留锁清理本身是只读+rename 操作，和"加锁/上传"的事务性关注点不同

独立成 Stage 0 后，LockGate 内部的 `findResidualLocks` 检测可以删除（或保留作为防御性 assert，发现就抛 IllegalStateException——表示 Stage 0 漏掉了）。

**关键不变量**：

- Stage 1 期间远端**只新增 `backup/` 下的文件**，不改原包路径
- Stage 2 期间一个目标"开始"和"结束"之间是连续的，不会被其他目标穿插
- 已经达到 COMPLETED 的目标，永远不会被后续失败回滚

### 3.2 失败策略：fail-fast，无开关

任一步骤失败立即停止剩余工作。明确**不**提供 `continueOnError` 开关。

理由：批量更新的失败 80% 是环境性问题（FTP 抖动、磁盘满、权限掉、jar 包构建错），后续目标继续做只会扩大事故面。需要"完成剩余"的用户可以手动重跑剩余目标列表，等价效果且零额外配置。

### 3.3 回滚边界缩到单目标

`Rollback.rollbackTarget` 简化为只处理"当前正在 Stage 2 流水中、未达到 COMPLETED"的目标：

| 目标当前状态 | 回滚动作 |
|---|---|
| `LOCKED` / `UPLOADED` | `restoreLock`（rename 锁包回原名） |
| `VERIFIED` / `NOTE_UPDATED` | 从 `backup/` 下载 → 上传到原路径 + 删锁包 |
| `COMPLETED` | **不回滚**（删除该 case） |
| `PENDING` / `BACKED_UP` / `FAILED` / `ROLLED_BACK` | 不回滚（与现状一致） |

`Rollback.rollbackAll(targets)` 不再需要——Stage 2 失败只对当前那一个目标调 `rollbackTarget`。

### 3.4 残留锁清理 UX（方案 γ）

由 Stage 0 的 `ResidualLockResolver` 承担，**返回结构化诊断**，不再像现在的 LockGate 那样直接抛 `GateException`。

#### 3.4.1 诊断信息结构

```java
public class ResidualLockDiagnosis {
    String lockFileName;           // 完整锁文件名
    String originalPackageName;    // 解析得到
    String operator;               // 锁持有者
    LocalDateTime lockedAt;        // 锁时间
    boolean isOwnedByCurrentUser;  // owner == 当前 operator
    boolean originalPackageExists; // 远端原包是否存在
    SuggestedAction suggestion;    // RESTORE_LOCK / DELETE_LOCK / NEEDS_HUMAN
    String reason;                 // 文字解释
}

enum SuggestedAction {
    RESTORE_LOCK,   // 锁包在、原包不在 → rename 锁包回原名
    DELETE_LOCK,    // 锁包在、原包也在 → 删锁包，保留新版本
    NEEDS_HUMAN     // 状态异常，建议人工介入
}
```

#### 3.4.2 IDE 插件 UX

发现残留锁时，弹 `ResidualLockResolveDialog`：

```
检测到残留锁，请确认处理方式：

[√] tm01srv.war__LOCK__xumanyi_20260427_153012
    持有者: xumanyi（你自己）
    时间: 2026-04-27 15:30:12
    诊断: 锁包存在，原包不存在
    建议: 恢复为 tm01srv.war (restoreLock)        [详情]

[√] tm02srv.war__LOCK__xumanyi_20260427_153018
    持有者: xumanyi（你自己）
    时间: 2026-04-27 15:30:18
    诊断: 锁包与新原包同时存在
    建议: 删除锁包，保留新版本                     [详情]

[ ] tm03srv.war__LOCK__zhangsan_20260425_091200
    持有者: zhangsan（不是你）
    时间: 2026-04-25 09:12:00
    （不可勾选，需要先与 zhangsan 确认）

[确定执行]  [取消]
```

- 自己的锁默认勾选；用户可逐条取消勾选
- 别人的锁强制 `disabled`，提示去找对方
- 点确定后批量执行勾选项的"建议动作"
- 处理完成才进入正式部署流程

#### 3.4.3 CLI UX

默认非交互行为：发现残留锁就报错退出，stderr 输出每条诊断（同上信息），exit code 非零。

新增 flag：`flux-deploy-cli deploy --auto-resolve-own`
- 自动执行所有 owner == 当前 operator 的锁的"建议动作"
- 别人的锁仍报错退出
- stdout 记录每条做了什么

新增独立命令：`flux-deploy-cli unlock-resolve`
- 仅做残留锁诊断与清理，不做部署
- 默认列出诊断；加 `--apply` 才执行
- 加 `--include-others` 强行处理别人的锁（仅在确实是脏数据时使用）

### 3.5 崩溃后恢复路径（无 journal）

不写本地 journal 文件。所有恢复信息从远端 FTP 状态推导，可用线索：

- **锁文件名** `<package>__LOCK__<operator>_<yyyyMMdd_HHmmss>` 携带 owner 和时间
- **备份目录名** `backup/YYYYMMDD_<operator>/` 携带 owner 和日期
- **原包是否存在** 可推断崩溃位置

进程被 kill 后下次部署的恢复流程：

```
启动部署 → Stage 0
  ↓
ResidualLockResolver.diagnose(每个目标的 remoteDir)
  ↓
有残留锁？
  ├─ 否 → 进入 Stage 1 (PreCheck → Backup)
  └─ 是 → 走 3.4 的 UX，由用户/flag 决定清理后再进 Stage 1
```

不需要"自动重新上传"或"基于 journal 自动重做"——这些操作风险大于收益。备份目录始终在，用户事后任何时候都能用 `flux-deploy-cli rollback` 手动选择恢复点。

### 3.6 备份与临时产物清理

#### 备份文件 (`backup/YYYYMMDD_operator/...`)

- **失败路径不自动清**：备份是只读复制，没污染原包；保留供事后人工排查或 manual rollback
- **Stage 1 中途失败**：已上传的部分备份保留（可能给"幂等 backup"用，下次重跑可复用）
- **正常成功**：备份保留（与现状一致，给"几天后才发现问题"兜底）
- **新增 `flux-deploy-cli backup-prune`**：独立命令做保留期清理
  - `--keep-days N` 默认 30
  - `--remote-dir <dir>` 指定子系统根
  - 默认 dry-run，加 `--apply` 才真删

#### 临时产物（必须清）

- 暂存包 staging file（StagingPackageBuilder 输出到 `target/`）
- FTP 上传中转文件 `*.__UPLOADING__`
- 回滚时下载备份用的本地 temp 文件 `Files.createTempFile("rollback-", ...)`
- 备份时下载远端原包用的 temp 文件 `Files.createTempFile("backup-", ...)`

所有临时产物走 `try-finally` + `Files.deleteIfExists`。当前代码 BackupGate / Rollback 已有这个模式，UploadGate 的 `*.__UPLOADING__` 失败路径也已有清理（`UploadGate.java:75-80`），保持。

### 3.7 用户主动取消

#### 取消的语义

用户主动取消（IDE 点取消按钮 / CLI Ctrl-C）等同于"在当前位置失败"，不是"无害中止"。

- **Stage 1 中取消**：当前正在执行的 FTP 操作做完后停止；已上传的备份保留；零远端副作用，无需回滚
- **Stage 2 中取消**：当前正在处理的目标按"该 Gate 失败"对待 → 触发 `rollbackTarget` 回滚自己 → 剩余目标 SKIPPED
- 报告 `cancelled = true`，区分"取消"和"错误失败"，但回滚动作相同

#### 实现

在每个 Gate 执行**之前**和 staging build 循环每个目标之前设检查点：

- IDE：调 `ProgressManager.getInstance().getProgressIndicator().checkCanceled()`，抛 `ProcessCanceledException`
- CLI：注册 `Runtime.addShutdownHook` 捕 SIGINT，置 `volatile boolean cancelled` 标志位；DeployPipeline 在每个 Gate 入口检查标志位，命中抛自定义 `CancellationException`

不需要细到 FTP 操作内部能被 cancel——粒度做到"Gate 之间"够用，每个 Gate 通常 1-30 秒，体验可以接受。

### 3.8 FTP 连接异常的运行时处理

Stage 2 执行中 FTP 抛 `IOException`（连接断、读超时、协议错误），处理路径：

1. 当前的 `FtpSession` 视为已死，关闭丢弃
2. **如果当前目标已进入 LOCKED 或更高状态**：必须执行回滚。新建一个 `FtpSession` 重连：
   - 重连成功 → 调 `Rollback.rollbackTarget(currentTarget)` → 标记 T 为 ROLLED_BACK
   - 重连失败（FTP 服务持续不可用）→ 标记 T 为 `FAILED_NEEDS_MANUAL`，写明确日志："T 未能自动回滚，远端可能有锁残留 `<lockName>`，请用 `flux-deploy-cli unlock-resolve` 处理"
3. **如果当前目标还在 PENDING / BACKED_UP**：远端无副作用，无需回滚
4. 后续目标全部 SKIPPED

不做"自动重试 N 次"——重试通常在 `FtpOperations` 这一层做更合适，本设计不引入。批量层面只做"出错就停 + 尽力回滚"。

---

## 4. API / 代码影响

### 4.1 修改的类

| 文件 | 改动 |
|---|---|
| `core/.../deploy/DeployPipeline.java` | `execute()` 重构为 Stage 1 / Stage 2；`executeGates` 拆为 `executeStage1` 和 `executeStage2`；新增 cancellation 检查点；IOException catch 改为调 rollback |
| `core/.../deploy/Rollback.java` | `rollbackTarget` 删 `COMPLETED` 分支；`rollbackAll` 删除（不再需要） |
| `core/.../deploy/gates/PreCheckGate.java` | 不变。仍负责"本地包存在 + 远端原包存在"两项检查，作为 Stage 1 第一步 |
| `core/.../deploy/gates/LockGate.java` | 删除内部 `findResidualLocks` 检测块（移到 Stage 0 的 `ResidualLockResolver`）；保留时可改为防御性 assert |
| `core/.../deploy/Gate.java` | 不变。Gate 接口保持只关心 Stage 1/2 的具体步骤 |
| `core/.../model/DeployResult.java` | 新增 `cancelled` 字段；`TargetResult` 新增 `SKIPPED` 状态及原因 |
| `core/.../model/TargetPackage.java` | 新增 `Status.SKIPPED` 和 `Status.FAILED_NEEDS_MANUAL` 枚举值 |
| `core/.../ftp/FtpLock.java` | 不变（已有 `findResidualLocks` / `restoreLock`） |
| `plugin/.../service/DeployExecutionService.java` | 接 `ProgressIndicator.checkCanceled`；接收并展示 `ResidualLockDiagnosis` 列表，调用对话框 |
| `plugin/.../toolwindow/ResidualLockResolveDialog.java` | **新增**：3.4.2 描述的对话框 |
| `cli/.../Main.java` | 注册 `unlock-resolve` 和 `backup-prune` 子命令；为 `deploy` 子命令加 `--auto-resolve-own` flag |
| `cli/.../UnlockCommand.java` | 不动，向后兼容；`unlock-resolve` 是新增命令，不替换 `unlock` |

### 4.2 新增的类

| 文件 | 职责 |
|---|---|
| `core/.../deploy/ResidualLockDiagnosis.java` | 数据类，承载残留锁诊断结果 |
| `core/.../deploy/ResidualLockResolver.java` | 核心逻辑：`diagnose(remoteDirs)` 扫描所有目标 remoteDir 列出诊断；`apply(diagnosis, action)` 执行清理；DeployPipeline 在 Stage 0 调用 |
| `cli/.../UnlockResolveCommand.java` | CLI 命令 `unlock-resolve` 入口 |
| `cli/.../BackupPruneCommand.java` | CLI 命令 `backup-prune` 入口 |
| `plugin/.../toolwindow/ResidualLockResolveDialog.java` | IDE 对话框 |

---

## 5. 报告格式

### 5.1 成功批次（30/30）

```
[OK] 部署成功 30/30
  T1  tm01srv.war  ✅ COMPLETED
  T2  tm02srv.war  ✅ COMPLETED
  ...
  T30 tm30srv.war  ✅ COMPLETED
备份: /开发/.../backup/20260428_xumanyi/
```

### 5.2 Stage 1 失败（备份阶段）

```
[FAIL] 部署中止 - Stage 1 (Backup)
  失败位置: T15 tm15srv.war 备份失败 (FTP 连接超时)
  已完成备份: T1..T14 (保留在 backup/ 下，下次重跑可见)
  未开始: T15..T30 (零远端副作用)

下一步: 修复 FTP 后重新执行；已备份的可在 backup/20260428_xumanyi/ 下查看
```

### 5.3 Stage 2 失败（含跳过）

```
[FAIL] 部署中止 - Stage 2 (Verify)
  ✅ 已完成: T1..T15 (15 个，保留在线上)
  ❌ 失败:   T16 tm16srv.war (Verify 失败 - SHA256 不匹配)
            └─ 已回滚 T16 (锁恢复为原文件名)
  ⏭ 跳过:   T17..T30 (14 个，未开始)

下一步: 排查 T16 的本地构建产物；其他 15 个已成功部署，无需重做
```

### 5.4 用户取消

```
[CANCELLED] 部署被取消 - Stage 2 (Lock)
  ✅ 已完成: T1..T7
  ⛔ 已取消: T8 (回滚锁恢复)
  ⏭ 跳过:   T9..T30
```

---

## 6. 验证策略

### 6.1 单元测试

每个 Gate 独立可测，已有结构。新增：

- `ResidualLockResolver` 的 `diagnose` 在四种 FTP 状态下（只有锁、锁+原包、只有原包、都没有）的诊断结果
- `Rollback.rollbackTarget` 删 COMPLETED 分支后，已 COMPLETED 目标的回滚调用必须 no-op

### 6.2 集成测试（需 FTP fixture）

| 场景 | 预期 |
|---|---|
| 30 个目标全部成功 | 全部 COMPLETED，备份目录有 30 份文件 |
| Stage 1 第 15 个 Backup 失败 | 报错；远端 backup/ 有 14 份；原包无变动 |
| Stage 2 第 16 个 Verify 失败 | 前 15 个 COMPLETED 在线上；T16 回滚回旧版本；T17-30 SKIPPED |
| Stage 2 第 16 个上传后连接断（重连成功） | 重新建立 FtpSession → rollback T16 → T17-30 SKIPPED |
| Stage 2 第 16 个上传后连接断（重连也失败） | T16 标记 FAILED_NEEDS_MANUAL，日志提示 unlock-resolve；T17-30 SKIPPED |
| Stage 2 第 16 个上传后进程被 kill | 重启后 Stage 0 的 ResidualLockResolver 检测到 T16 残留锁，UX 提示自己的残留 |
| 用户在 Stage 2 中点取消 | 当前目标回滚自己，剩余 SKIPPED |
| `--auto-resolve-own` 配 owner == 自己的残留 | 直接清理后继续部署 |
| `--auto-resolve-own` 配 owner != 自己的残留 | 报错退出，告知去找对方 |

### 6.3 手动验收清单

- 通过 IDE 插件部署 5 个 war，模拟在 Stage 2 第 3 个失败，确认前 2 个仍在线、第 3 个回退、其余跳过
- 在 Stage 2 第 3 个开始后强制 kill IDE，重新打开后触发部署，看到残留锁清理对话框
- 跑 `flux-deploy-cli backup-prune --keep-days 7` 确认保留期外的备份目录被列出（dry-run）

---

## 7. 不在范围内（明确排除）

| 排除项 | 理由 |
|---|---|
| 多目标并行执行 | FTP 服务端速度受限，并行不提升吞吐；明确不做 |
| 本地 journal 文件 + 启动自动恢复 | 远端状态本身已携带足够信息（锁名 + 备份目录名）；引入 journal 增加并发写、清理时机、跨工作区一致性等复杂度，收益小于成本 |
| `continueOnError` 开关 | YAGNI；用户可手动重跑剩余目标得到等价效果 |
| 取消时细到 FTP 操作内部中断 | 粒度做到"Gate 之间"已够用（每个 Gate 1-30 秒） |
| 自动判定别人的残留锁是否过期可清 | 永远不在用户没确认的情况下动别人的资源 |

---

## 8. 迁移与兼容性

- **现有 Gate 接口**：完全不动。残留锁逻辑通过新增独立类 `ResidualLockResolver` 承担，与 Gate 体系解耦
- **现有 CLI 命令**：`unlock`、`rollback` 保持兼容；`unlock-resolve` 是新增命令，不替换 `unlock`
- **现有数据**：`backup/YYYYMMDD_operator/` 目录结构不变；旧锁文件名格式不变；新逻辑可处理旧数据
- **现有跳过开关**：`config.skipLock` / `skipBackup` / `skipNote` 的语义保持——`skipLock=true` 时整个 Stage 0 + Lock/Unlock 都跳过

无数据迁移需求。

---

## 9. 开放问题

无。所有关键决策已收敛（参考会话记录 2026-04-28）。
