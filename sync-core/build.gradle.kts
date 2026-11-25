// Folder sync engine shared with pSync: plain Kotlin/JVM (stdlib + JDK only), wire-compatible with it.
plugins {
    id("org.jetbrains.kotlin.jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// No test libraries: tests run with a tiny built-in harness (src/test/.../testing/MiniTest.kt).
val runTests by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Runs sync engine tests with the built-in harness"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("x.vladgba.sync.core.testing.MiniTestKt")
}

tasks.named("check") { dependsOn(runTests) }
