// flux-deploy cli 模块：
//   - 面向命令行 / AI Skill 的部署入口
//   - 依赖 :core 提供的纯业务代码；不得依赖 :plugin
//   - 构建产物：build/libs/flux-deploy-cli-<version>-all.jar （fat-jar，含 commons-net）
plugins {
    id("java")
    id("application")
    id("com.gradleup.shadow") version "8.3.5"
}

dependencies {
    implementation(project(":core"))
    // Gson：配置文件 JSON 解析；无外部反射框架依赖，shadowJar 体积增量可控（~250KB）
    implementation("com.google.code.gson:gson:2.11.0")
}

application {
    // CLI 入口类，shadowJar 会把 Main-Class 写入 fat-jar manifest
    mainClass.set("com.flux.deploy.cli.Main")
}

// fat-jar：保留 commons-net 等传递依赖，给终端 / Skill 直接 `java -jar` 使用
tasks.named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar") {
    archiveBaseName.set("flux-deploy-cli")
    archiveClassifier.set("all")
    mergeServiceFiles()
}
