# flux-deploy

FLUX 部署工具集，包含 IDEA 插件和 CLI 两个交付形式，共用 `core` 模块的业务逻辑。

## 模块结构

```
flux-deploy/
├── core/       # 公共业务逻辑（FTP、部署流水线、模型、工具类）
├── plugin/     # IntelliJ IDEA 插件
├── cli/        # 命令行工具 (flux-deploy-cli)
├── docs/       # 文档与流程图生成脚本
└── dist-archive/  # 预构建的插件 zip（归档）
```

## core — 核心层

`core/src/main/java/com/flux/deploy/`

| 包 | 职责 |
|----|------|
| `deploy/` | 部署流水线 (`DeployPipeline`) 与各阶段 Gate（备份、上传、校验、锁等） |
| `ftp/` | FTP 连接、操作、锁管理 |
| `model/` | 通用模型（`DeployConfig`、`DeployResult`、`TargetPackage`） |
| `plugin/model/` | 插件专用配置模型（部署模式、目标选择等） |
| `plugin/service/` | FTP 浏览、本地补丁、暂存包构建服务 |
| `plugin/util/` | 日志写入、拦截器 |
| `util/` | 凭证缓存、加密、哈希、WAR 内嵌工具 |

## plugin — IDEA 插件

`plugin/src/main/java/com/flux/deploy/plugin/`

| 包 | 职责 |
|----|------|
| `action/` | 右键菜单 / 工具栏 Action（全量部署、选文件部署、Git 自动检测等） |
| `service/` | 插件内服务（执行调度、Git 变更检测、Maven 产物解析、模块枚举） |
| `toolwindow/` | **插件页面 UI**：Tool Window 面板及各子面板、确认弹窗 |
| `util/` | 凭证桥接 |

插件页面代码集中在 `toolwindow/`：
- `FluxDeployToolWindowFactory` — Tool Window 注册入口
- `DeployToolWindowPanel` — 主面板（组合各子面板）
- `SourceSectionPanel` / `TargetSectionPanel` / `LogSectionPanel` 等 — 各区块

插件描述符：`plugin/src/main/resources/META-INF/plugin.xml`

## cli — 命令行工具

`cli/src/main/java/com/flux/deploy/cli/`

| 文件 | 职责 |
|------|------|
| `Main.java` | 入口，注册所有子命令 |
| `CliConfig.java` / `ConfigLoader.java` | 配置加载 |
| `CredentialCommand.java` | 凭证管理 |
| `BrowseCommand.java` | FTP 目录浏览 |
| `RollbackCommand.java` | 回滚 |
| `UnlockCommand.java` | 解锁 FTP |
| `SchemaCommand.java` | 输出配置 schema |
| `PatchLocalCommand.java` | 本地补丁模式 |

## 构建

```bash
# 构建插件 zip
./gradlew :plugin:buildPlugin

# 构建 CLI fat-jar
./gradlew :cli:shadowJar
```

产物位置：
- 插件：`plugin/build/distributions/flux-deploy-plugin-{version}.zip`
- CLI：`cli/build/libs/`
