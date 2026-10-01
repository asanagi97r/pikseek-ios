buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.10")
    }
}

plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.compose) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}

// 构建产物不落在源码目录里：gw.ps1 把它指到项目的 _runtime/pikseek/b 下
val buildRoot = providers.gradleProperty("pikseek.buildRoot").orNull
if (buildRoot != null) {
    allprojects {
        layout.buildDirectory.set(file("$buildRoot/${if (path == ":") "_root" else name}"))
    }
}
