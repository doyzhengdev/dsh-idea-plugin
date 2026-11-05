import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.gradle.language.jvm.tasks.ProcessResources

plugins {
    id("idea")
    kotlin("jvm") version "2.1.0"
    id("org.jetbrains.intellij.platform") version "2.2.1"
}

group = "dev.doyzheng.dsh"
version = "0.2.1"

// IDE 索引排除（与 zcode-ide 同样的理由：生成产物不进索引）
idea {
    module {
        excludeDirs.addAll(
            listOf(
                file(".kotlin"),
                file(".intellijPlatform"),
                file("build"),
            ),
        )
    }
}

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    // 平台自带 Kotlin 运行时：compileOnly 避免把 kotlin-stdlib 打进插件包（否则 zip 从 ~80KB 涨到 ~1.6MB）。
    compileOnly(kotlin("stdlib"))
    testImplementation(libs.junit)

    intellijPlatform {
        create(providers.gradleProperty("platformType"), providers.gradleProperty("platformVersion"))
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "243"
            untilBuild = "262.*"
        }
    }
    buildSearchableOptions = false
}

// DSH 侧插件源码在 src/main/resources/dsh-plugin-jetbrains/，Gradle 默认会把它打进 jar 的
// /dsh-plugin-jetbrains 资源；供 DshBundleBootstrap 在 IDE 启动时注入到 profile（保证 profile 一定装得上）。
// 只排除开发用文件（README / selftest 脚本）；运行期只需 package.json / index.js / client.js /
// cordis.patch.yml / icon.svg / locale。
tasks.named<ProcessResources>("processResources") {
    exclude("dsh-plugin-jetbrains/README.md")
}

tasks.withType<Test> {
    useJUnit()
}
