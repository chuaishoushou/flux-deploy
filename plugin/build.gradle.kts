// flux-deploy plugin 模块：
//   - IntelliJ Platform 插件打包入口
//   - 依赖 :core 提供的纯业务代码
//   - 构建产物：build/distributions/flux-deploy-plugin-<version>.zip
plugins {
    id("java")
    id("org.jetbrains.intellij.platform") version "2.2.1"
}

repositories {
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    // 纯业务代码来自 core 模块；commons-net 由 core 通过 api 暴露
    implementation(project(":core"))

    // TinyPinyin：纯查表式的中文转拼音库（~100KB、零依赖），
    // 用于项目/系统/文件搜索框的拼音首字母与全拼模糊匹配。
    // 原坐标 com.github.promeg:tinypinyin 在 jcenter 关闭后失效，
    // io.github.biezhi 是 Maven Central 上的重发版本，包名保持 com.github.promeg.*。
    implementation("io.github.biezhi:TinyPinyin:2.0.3.RELEASE")

    intellijPlatform {
        intellijIdeaCommunity("2024.1")
        bundledPlugin("Git4Idea")
        // 引入 IDEA 内置 Maven 插件：用于读取 effective pom 的 source/target/release，
        // 驱动编译时 JDK 版本匹配（详见 DeployExecutionService.resolveJdkHomeForModule）
        bundledPlugin("org.jetbrains.idea.maven")
        pluginVerifier()
    }
}

// IntelliJ Platform 2.x 的字节码插桩对当前 gate/action 代码不必要，沿用旧工程设置关闭
tasks.named("instrumentCode") {
    enabled = false
}

// 编译期开启 deprecation / unchecked lint，便于在控制台直接看到 Marketplace
// Plugin Verifier 报的 deprecated API 使用，配合 :plugin:verifyPlugin 互相参照。
tasks.named<JavaCompile>("compileJava") {
    options.isDeprecation = true
    options.compilerArgs.addAll(listOf("-Xlint:deprecation", "-Xlint:unchecked"))
}

intellijPlatform {
    pluginConfiguration {
        id = "com.flux.deploy.plugin"
        name = "FLUX Customer Service Deploy"
        version = project.version.toString()
        ideaVersion {
            sinceBuild = "241"
            untilBuild = provider { null }
        }
        // change-notes 来自根目录 CHANGELOG.md，构建期渲染成 HTML，
        // 让 Marketplace 展示与仓库 CHANGELOG / 插件内"版本记录"页面同源
        changeNotes = providers.provider {
            renderChangeNotesHtml(
                parseChangelog(rootProject.file("CHANGELOG.md").readText()),
                limit = 5
            )
        }
    }

    // ./gradlew :plugin:verifyPlugin —— 跑 Marketplace Plugin Verifier 本地复现。
    // 同时跑 sinceBuild 对应的 2024.1 与 2024.3，覆盖大部分 deprecated/internal API 差异。
    pluginVerification {
        ides {
            ide(org.jetbrains.intellij.platform.gradle.IntelliJPlatformType.IntellijIdeaCommunity, "2024.1")
            ide(org.jetbrains.intellij.platform.gradle.IntelliJPlatformType.IntellijIdeaCommunity, "2024.3")
        }
    }
}

// 保持与历史产物同名：flux-deploy-plugin-<version>.zip，避免下发客户包改名
tasks.withType<Jar>().configureEach {
    archiveBaseName.set("flux-deploy-plugin")
}
tasks.named<org.gradle.api.tasks.bundling.Zip>("buildPlugin") {
    archiveBaseName.set("flux-deploy-plugin")
}

// ============================================================================
// CHANGELOG.md → docs/changelog.html / plugin.xml change-notes 构建期渲染
// ----------------------------------------------------------------------------
// 历史上版本文案要在 3 个地方同步维护：CHANGELOG.md / plugin.xml 的 change-notes /
// resources/docs/changelog.html。现在统一改为：
//   - 唯一编辑源：根目录 CHANGELOG.md（Markdown，新版本只编辑这一处）
//   - 构建期生成：resources/docs/changelog.html 中的 <!-- VERSIONS --> 占位符被替换
//   - 构建期注入：plugin.xml 的 <change-notes> 由 pluginConfiguration.changeNotes 提供
// v1.2.6 及以前的历史块仍以手写 HTML 保留在 changelog.html 模板里。
// ============================================================================

/** CHANGELOG.md 解析出的一个版本块。 */
data class ChangelogVersion(
    val version: String,
    val date: String,
    /** 已渲染好的 <li> 内部 HTML（不含 <li> 标签本身）。 */
    val itemsHtml: List<String>
)

/**
 * 解析 CHANGELOG.md 的最小 Markdown 子集。
 *
 * 支持：
 *   - 二级标题版本头：`## 1.2.3 — 2026-04-27`（破折号兼容 em-dash 与 ASCII '-'）
 *   - 三级子标题：`### 新功能` / `### 修复`（渲染为加粗的伪列表项，置入当前版本）
 *   - 顶层编号项：`1. xxx`（多行续行会拼到上一项）
 *   - 无序条目：`- xxx` 或缩进 `   - xxx`（与编号项同级扁平为 <li>）
 *   - 行内：`**bold**` / `` `code` ``
 *
 * 不支持：链接、图片、表格、围栏代码块、嵌套编号——CHANGELOG.md 维护规范上避免这些。
 */
fun parseChangelog(md: String): List<ChangelogVersion> {
    val versionHeader = Regex("""^##\s+(\S+)\s+[—\-]\s+(\S+)\s*$""")
    val subHeader     = Regex("""^###\s+(.+?)\s*$""")
    val numberedItem  = Regex("""^\d+\.\s+(.*)$""")
    val bulletItem    = Regex("""^\s*-\s+(.*)$""")

    val versions = mutableListOf<ChangelogVersion>()
    var curVersion: String? = null
    var curDate: String? = null
    val curItems = mutableListOf<StringBuilder>()

    fun flush() {
        val v = curVersion ?: return
        val d = curDate ?: return
        versions += ChangelogVersion(v, d, curItems.map { renderInline(it.toString().trim()) })
        curVersion = null
        curDate = null
        curItems.clear()
    }

    for (line in md.lines()) {
        versionHeader.matchEntire(line)?.let {
            flush()
            curVersion = it.groupValues[1]
            curDate = it.groupValues[2]
            return@let
        } ?: run {
            if (curVersion == null) return@run  // 跳过文档开头 # 更新日志 之类
            subHeader.matchEntire(line)?.let {
                curItems += StringBuilder("<strong>${escapeHtml(it.groupValues[1])}</strong>")
            } ?: numberedItem.matchEntire(line)?.let {
                curItems += StringBuilder(it.groupValues[1])
            } ?: bulletItem.matchEntire(line)?.let {
                // 缩进的 `- xxx`（前导空白）视作上一项的子条目，拼成 "<br>- xxx"；
                // 顶级 `- xxx`（无缩进，如 ### 修复 下的列表）独立为一个 <li>。
                if (line.startsWith(" ") && curItems.isNotEmpty()) {
                    curItems.last().append("<br>- ").append(it.groupValues[1])
                } else {
                    curItems += StringBuilder(it.groupValues[1])
                }
            } ?: run {
                // 续行：非空行 + 已经有 item，拼到上一项末尾
                if (line.isNotBlank() && curItems.isNotEmpty()) {
                    curItems.last().append(' ').append(line.trim())
                }
            }
        }
    }
    flush()
    return versions
}

/**
 * 行内 Markdown 标记转 HTML。
 *
 * 假设 CHANGELOG.md 内容受控（自家仓库维护），不做严格 HTML 转义——
 * 现有 changelog.html 里 v1.2.6+ 也直接写裸 HTML 标签（`<br>` `<code>` 等），
 * 强制转义会破坏既有写法。
 */
private fun renderInline(s: String): String = s
    .replace(Regex("""\*\*([^*]+)\*\*"""), "<strong>$1</strong>")
    .replace(Regex("""`([^`]+)`"""), "<code>$1</code>")

/** 仅给子标题文本用：` ` `<` `>` 转 HTML 实体。 */
private fun escapeHtml(s: String): String = s
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")

/**
 * 把 CHANGELOG.md 解析结果渲染成 changelog.html 用的 `<div class="version-block">` 序列。
 *
 * 输出格式与 v1.2.6 及以前手写块保持一致，让模板里两部分视觉无缝衔接。
 */
fun renderVersionsHtml(versions: List<ChangelogVersion>): String {
    val sb = StringBuilder()
    for ((i, v) in versions.withIndex()) {
        if (i > 0) sb.append("\n\n")
        sb.append("<div class=\"version-block\">\n")
        sb.append("  <div class=\"version-title\">v").append(v.version)
            .append(" <span class=\"date\">").append(v.date).append("</span></div>\n")
        sb.append("  <ol>\n")
        for (item in v.itemsHtml) {
            sb.append("    <li>").append(item).append("</li>\n")
        }
        sb.append("  </ol>\n")
        sb.append("</div>")
    }
    return sb.toString()
}

/**
 * 渲染最近 N 个版本作为 plugin.xml `<change-notes>` 用的 HTML 片段。
 *
 * Marketplace 接受 HTML 子集（`<h3>` `<ul>` `<li>` `<code>` `<strong>`），
 * 这里只产出这些标签。同时限 N 个版本，避免 change-notes 过长被截。
 */
fun renderChangeNotesHtml(versions: List<ChangelogVersion>, limit: Int): String {
    val sb = StringBuilder()
    for (v in versions.take(limit)) {
        sb.append("<h3>").append(v.version).append("</h3>\n")
        sb.append("<ul>\n")
        for (item in v.itemsHtml) {
            sb.append("    <li>").append(item).append("</li>\n")
        }
        sb.append("</ul>\n")
    }
    return sb.toString().trimEnd()
}

// processResources 后置：把生成产物里 docs/changelog.html 的占位符替换成渲染好的版本块
tasks.processResources {
    val mdFile = rootProject.file("CHANGELOG.md")
    inputs.file(mdFile)
    doLast {
        val target = destinationDir.resolve("docs/changelog.html")
        if (!target.exists()) return@doLast
        val raw = target.readText(Charsets.UTF_8)
        val rendered = renderVersionsHtml(parseChangelog(mdFile.readText(Charsets.UTF_8)))
        target.writeText(raw.replace("<!-- VERSIONS -->", rendered), Charsets.UTF_8)
    }
}
