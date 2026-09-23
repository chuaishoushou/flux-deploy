# 邮件编辑器 —— 从「外部浏览器」回退到「插件内嵌(JCEF)」开发交接

> 用途：在**新对话**里完成这项工作。本文件自包含，新会话无需读旧对话即可执行。
> 写于 v1.3.3 之后（HEAD = `a1dc185`）。

---

## 1. 决策（用户拍板）

把邮件模板编辑器**从「点按钮跳转外部系统浏览器」改回「IDE 内嵌富文本」**。
理由：**跳转到 web 的用户体验很差**（跳出 IDE、要协调浏览器进程、还起了本地 HTTP server）。

目标 = 编辑器回到 IDE 内部，**但不能丢掉最近几天打磨的所有修复和默认模板**。

---

## 2. 最重要的一条：不要做「字面回退」

**绝对不要 `git revert a1dc185` 或直接拿 v1.3.2 的旧编辑器回来。**

原因：v1.3.2 和 v1.3.3 用的是**同一个引擎**（Quill 2 + FluxField 变量 chip）。但最近几天的引擎级修复**全部只存在于 web 版的 `email-web-editor/app.js` 和 `core` 的 `EmailTemplateStore` 里**，旧 JCEF 的 `email-editor/index.html` 是**修复之前的老版本**。

字面回退 = 把这些修复**全部丢掉重做**。正确做法见第 4 节（保留修复，只换外壳）。

---

## 3. 现状代码地图（v1.3.3）

### 当前 web 方案（要拆掉 server 部分）
| 文件 | 处置 |
|---|---|
| `plugin/.../email/EmailWebServer.java` | **删除**（不再需要 HTTP server） |
| `plugin/.../email/EmailWebServerService.java` | **删除**（项目级 server 单例） |
| `plugin/.../email/EmailRuntimeValuesBuilder.java` | **保留**。`build()` 返回 `Map<String,String>` 变量快照；只是改为通过 JS 桥交付，而非 REST。注意它已新增「更新模式」字段。 |
| `plugin/.../email/EmailRuntimeData.java` | **保留**（接口，主面板实现） |
| `core/.../email/EmailTemplateStore.java` | **保留**。`BUILTIN_DEFAULT_TEMPLATE` 已是用户最新保存的模板（正文15段 + 2空行 + 签名档），别动。 |
| `core/.../email/EmailDraftManager.java` | **保留**（只记当前选中模板名 + 暴露 store） |

### web 前端资源（**这是要移植进 JCEF 的"好版本"**）
- `plugin/src/main/resources/email-web-editor/`
  - `index.html` —— 结构：`<link app.css>` + `<script quill.min.js>` + `<script app.js>`
  - `app.js` —— **所有引擎修复都在这里**（见第 5 节）
  - `app.css` —— line-height/picker/字号中文标签等样式修复
  - `quill.min.js` / `quill.snow.css` —— Quill 本地库（离线可用）

### 旧 JCEF 资源（**已过时，不要用**，可在新方案落地后归档/删除）
- `plugin/src/main/resources/email-editor/{index.html,quill.min.js,quill.snow.css}`
  - `index.html` 是修复**之前**的版本。

### 入口
- `DeployToolWindowPanel.openEmailDialog()` —— 当前实现是「启动 server + `BrowserUtil.browse()`」。
  - ⚠️ 我在改 web 版时**删掉了原来的 `JBCefApp.isSupported()` 守卫**。回退到 JCEF 后**必须重新加回 JCEF 可用性检查**（不可用时弹窗提示去开 `ide.browser.jcef.enabled`）。
- 「邮件模版」按钮：`DeployToolWindowPanel` 约 523 行，`addActionListener(e -> openEmailDialog())`
- 工具窗标题栏 ✉ 图标：`ShowEmailAction.actionPerformed()` → `panel.openEmailDialog()`

### git 参考（取旧实现做骨架参考，不是照搬）
- 被删文件都在 `a1dc185^`：
  - `JcefEmailEditor.java`（397 行）—— **JCEF 宿主的骨架参考**：用 `JBCefBrowser` + `browser.loadHTML(buildEditorHtml())`，`{{QUILL_CSS}}`/`{{QUILL_JS}}` 占位内联，`JBCefJSQuery` 做 JS↔Java 桥，`executeJavaScript` 推数据。
  - `HtmlClipboardTransferable.java`（181 行）—— **剪贴板实现，直接复活**：把 HTML+plain 写系统剪贴板。
  - `EmailDialog.java`（1658 行）—— 旧弹窗。**不建议整体复活**（含大量 JCEF-Swing 拖拽桥 hack：`startDragPoller`/`finishDragging`）。只在需要时参考它的 DialogWrapper 结构、按钮布局、`buildRuntimeValuesMap`（该逻辑已抽到 `EmailRuntimeValuesBuilder`）。
  - 取文件：`git show a1dc185^:plugin/src/main/java/com/flux/deploy/plugin/email/JcefEmailEditor.java`

### ⚠️ 工作树不干净
`git status` 显示有**与本任务无关**的未提交改动（`plugin/build.gradle.kts`、`FluxDeployToolWindowFactory.java`、`TargetSectionPanel.java`、未跟踪的 `PluginVersionProvider.java`）。这些是其它在途工作，**别误删/误提交**。

---

## 4. 目标架构（保留修复，只换外壳）

把**当前已打磨好的 Quill SPA**（`email-web-editor/`）原封不动跑进 JCEF，数据通道从 REST 换成 JS 桥：

```
旧(web):  app.js fetch('/api/...')  ──HTTP──>  EmailWebServer  ──>  Store / Builder
新(JCEF): app.js bridge({op,...})   ──JBCefJSQuery──>  Java handler  ──>  Store / Builder
```

- 新建一个 JCEF 宿主类（如 `EmailJcefDialog` 或复用「弹窗 + JcefEmailEditor」二层结构）。
- `loadHTML` 一次性内联：`quill.snow.css` + `app.css` + `quill.min.js` + `app.js`（app.js 改造见下）。Quill 本地库已存在，离线可用。
- **app.js 唯一要改的是 `api()` 这一层**：把 `fetch + X-Flux-Token` 换成「调用注入的 JS 桥函数，返回 Promise」。上层 `apiListTemplates / apiLoadTemplate / apiSaveTemplate / apiDeleteTemplate / apiRestoreTemplate / apiRuntimeData` 全部不动。
- 剪贴板：JCEF 里 `navigator.clipboard.write()` 可能被禁。复制走桥：`serializeRendered()` 出 HTML → `bridge({op:'copyToClipboard', html, plain})` → Java `HtmlClipboardTransferable` → `Toolkit` 系统剪贴板。
- 删掉 `EmailWebServer` / `EmailWebServerService`。
- `openEmailDialog()` 改为弹 JCEF 对话框（带 JCEF 可用性守卫），不再 `browse()`。

---

## 5. 必须移植/保留的修复清单（验收点）

这些都已在 `email-web-editor/app.js` + `app.css` + `EmailTemplateStore` 里，移植后**逐条自测**：

1. **空行不被吞**：P/DIV matcher **不再**调用 `isEmptyParagraph` 丢弃空段落（Quill 默认保留）。用户敲的空行切换模板后还在。
2. **粘贴兼容（企微邮件 → 编辑器往返）**：
   - `nearestLineHeight`：`%`→无单位、就近匹配 `[1,1.15,1.5,2,3]`。
   - `nearestSize`：`px×0.75→pt`、`em/%→pt`，就近匹配 `[9,10.5,12,14,16,18,22,26]pt`。
   - `matchFontFamily`：字体栈逐项遍历，取第一个命中白名单的（企微是 `system-ui,"PingFang SC",...`）。
   - `applyInlineFormat`：`Object.assign({}, format, op.attributes)` —— **内层 span 样式优先**于外层。
   - `attachBlockFormat`：把 lineheight 挂到块尾 `\n`，没有就 append 一个带 format 的 `\n`。
3. **默认行距 = 1**：`.ql-editor { line-height: 1 }`（对齐企微视觉）。
4. **行距 picker 样式**：宽 92px、`white-space:nowrap`、label 显示中文「单倍/1.15倍/1.5倍/2倍/3倍」（不是「行距 1.15」截断）。
5. **字号 picker**：中文字号制（小五/五号/小四…），`data-value` 是 pt。
6. **变量 chip**：手动插入永远是空占位 `${变量名}`（橙色斜体）；值只在点「导入数据」时填。
7. **缩进**：段首前导空白→全角空格 U+3000（真实字符，可选中、企微不吞）。
8. **默认模板**：`EmailTemplateStore.BUILTIN_DEFAULT_TEMPLATE` = 用户最新保存内容（正文 + 签名档），已固化，勿改。

---

## 6. 实施步骤（建议顺序）

1. 复活 `HtmlClipboardTransferable.java`（`git show a1dc185^:...`）。
2. 写 JCEF 宿主类：`JBCefBrowser` + `loadHTML`（内联 quill css/js + app.css + app.js）+ 一个 `JBCefJSQuery` 桥。参考 `a1dc185^:JcefEmailEditor.java`。
3. 改造 app.js 的 `api()`：fetch → 桥调用（Promise 化；JBCefJSQuery inject 的 onSuccess/onFailure 直接当 resolve/reject）。建议在 JCEF 专用副本里改，或用条件分支兼容两种运行环境。
4. Java 端 `handleJsMessage`：按 `op` 分发到 `EmailTemplateStore`（list/load/save/delete/restore）和 `EmailRuntimeValuesBuilder.build()`（runtime-data）、`HtmlClipboardTransferable`（copyToClipboard）。
5. 复制路径接桥；验证从企微**粘进** JCEF 编辑器的富文本能被 Quill matcher 接住。
6. `openEmailDialog()` → 弹 JCEF 对话框 + 重新加 `JBCefApp.isSupported()` 守卫。
7. 删 `EmailWebServer.java` / `EmailWebServerService.java`；清理对它们的引用。
8. 决定旧 `email-editor/` 资源去留；前端统一用 `email-web-editor/`。
9. 逐条过第 5 节验收点 → `./gradlew :plugin:buildPlugin`。

---

## 7. 关键技术点

- **JBCefJSQuery 桥 Promise 化**：`query.inject(jsPayloadExpr)` 生成的 JS 接受 `onSuccess`/`onFailure` 回调；包一层 `function bridge(payload){return new Promise((res,rej)=>{ <inject: handler(payload, res, rej)> })}`，每次调用独立 resolve，无需手动 reqId。
- **app.js 改动面最小化**：只换 `api()` 实现 + 去掉 token 逻辑；其余全保留。
- **Quill 本地加载**：`quill.min.js`/`quill.snow.css` 内联进 loadHTML，JCEF 离线可用。
- **dev 热更（可选）**：可保留「对话框打开时优先读 `~/.flux-deploy/email-web-editor-dev/` 的 app.js/css 内联」，留个不重打包就能改的口子。

---

## 8. 需要你（用户）拍板的开放问题

1. **版本号**：回退后是停在 1.3.3 内修订，还是发 1.3.4？**必须你给数字**（项目硬规则：严禁私自 bump 版本）。
2. 旧 `email-editor/` 资源：删除还是先归档保留？
3. dev 热更口子：JCEF 版要不要保留？
4. JCEF 不可用时：只弹提示，还是要留一个「降级到外部浏览器」的兜底入口？

---

## 9. 项目硬约束（来自全局/项目记忆，务必遵守）

- **严禁私自更新版本号**：`build.gradle.kts` / `CHANGELOG` 版本必须用户给数字。
- **不要自加防御约束**：业务规则字面实现，别为"防呆"加额外边界条件。
- **插件 UI 禁用快捷键提示**：UI 文本不能写 `Cmd+F9` / `mvn package` 等。
- **flux-deploy 插件不套完整 FLUX 编码规范**：只要求方法注释完整。
- **禁用"孤儿"一词**：用"残留/滞留/无主/遗留"。
- **取插件版本只用 `PluginVersionProvider`**（读构建注入的 version.txt），不要用 PluginManager descriptor 族。
- superpowers 产物本地保留不入库。

---

## 10. 构建与验证

- 打包：`./gradlew :plugin:buildPlugin` → `plugin/build/distributions/flux-deploy-plugin-<ver>.zip`
  - 开头那行 `cwm-plugin ... Invalid plugin descriptor` 是**既有无害告警**，忽略。
- JS 语法检查：`node --check <file>`
- 验证模板固化：解压 zip 找 `core-<ver>.jar`，`grep -a` 关键串确认 BUILTIN 进了 class。

---

## 11. 一句话给新会话

> 把 `email-web-editor/` 里这套已修好的 Quill 编辑器搬进 JCEF（`loadHTML` + `JBCefJSQuery` 桥，删掉 HTTP server），剪贴板复活 `HtmlClipboardTransferable`，入口 `openEmailDialog()` 改弹内嵌对话框并加回 JCEF 守卫。**保留第 5 节所有修复，别动 `BUILTIN_DEFAULT_TEMPLATE`，版本号等用户给。**
