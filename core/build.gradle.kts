// flux-deploy core 模块：
//   - 纯业务代码，不得依赖 IntelliJ Platform
//   - 依赖 commons-net 作为 FTP 底层库
//   - 提供若干端到端本地/FTP 测试驱动（JavaExec 形式）
plugins {
    `java-library`
}

dependencies {
    // commons-net 是 FtpSession/FtpOperations 的底层传输依赖，api 暴露给上层模块
    api("commons-net:commons-net:3.11.1")
}

// 本地模式端到端测试驱动：跑 3 种模式 × 增/删/改 + 孤儿清理
// 使用：./gradlew :core:runLocalModeTest
tasks.register<JavaExec>("runLocalModeTest") {
    group = "verification"
    description = "Run local mode E2E test driver (3 modes x add/modify/delete)"
    dependsOn("testClasses")
    classpath = sourceSets["test"].runtimeClasspath + sourceSets["main"].runtimeClasspath
    mainClass.set("com.flux.deploy.core.test.LocalModeTestDriver")
}

// FTP 模式端到端测试驱动：在 /开发/1测试项目/TMS V9.0-P7/auto_test_* 子目录下跑，自动清理
// 使用：./gradlew :core:runFtpModeTest
tasks.register<JavaExec>("runFtpModeTest") {
    group = "verification"
    description = "Run FTP mode E2E test driver (3 modes x add/modify/delete)"
    dependsOn("testClasses")
    classpath = sourceSets["test"].runtimeClasspath + sourceSets["main"].runtimeClasspath
    mainClass.set("com.flux.deploy.core.test.FtpModeTestDriver")
    // 保证解密缓存凭据时与 IDE 端使用相同的 user.name 派生密钥
    systemProperty("user.name", "xumanyi")
}

// 一键跑全部（本地 + FTP）
tasks.register("runAllE2ETests") {
    group = "verification"
    description = "Run both local and FTP E2E tests"
    dependsOn("runLocalModeTest", "runFtpModeTest")
}

// 日志格式快速校验（不依赖 IDE / mvn，纯格式转换）
tasks.register<JavaExec>("runLogFormatTest") {
    group = "verification"
    description = "Quick sanity check for log format (splits embedded \\n)"
    dependsOn("testClasses")
    classpath = sourceSets["test"].runtimeClasspath + sourceSets["main"].runtimeClasspath
    mainClass.set("com.flux.deploy.core.test.LogFormatTest")
}
