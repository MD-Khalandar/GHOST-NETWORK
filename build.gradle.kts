buildscript {
    dependencies {
        // AGP provides built-in Kotlin; align it with LiteRT-LM's Kotlin 2.4 metadata.
        classpath(libs.kotlin.gradle.plugin)
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
}

