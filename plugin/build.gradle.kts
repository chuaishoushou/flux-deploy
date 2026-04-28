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

intellijPlatform {
    pluginConfiguration {
        id = "com.flux.deploy.plugin"
        name = "FLUX Customer Service Deploy"
        version = project.version.toString()
        ideaVersion {
            sinceBuild = "241"
            untilBuild = provider { null }
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
