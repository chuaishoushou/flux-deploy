// 根构建脚本：只承载多模块公共配置。
// 单模块具体依赖、产物打包请放到各自模块的 build.gradle.kts 中。

allprojects {
    group = "com.flux.deploy"
    version = "1.3.1"
}

// 给所有子模块的 Java 编译统一一套 toolchain 与编码配置。
subprojects {
    plugins.withType<JavaPlugin> {
        extensions.configure<JavaPluginExtension> {
            toolchain {
                languageVersion.set(JavaLanguageVersion.of(17))
            }
        }
    }

    repositories {
        mavenCentral()
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
    }

    tasks.withType<Test>().configureEach {
        systemProperty("file.encoding", "UTF-8")
    }
}
