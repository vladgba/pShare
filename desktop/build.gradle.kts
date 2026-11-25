import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.jvm")
    application
}

application {
    mainClass.set("x.vladgba.share.desktop.MainKt")
    applicationName = "pShare"
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

val syncCore = tasks.register<Sync>("syncCore") {
    from("../app/src/main/java/x/vladgba/share/core")
    into(layout.buildDirectory.dir("generated/sources/core/x/vladgba/share/core"))
}

sourceSets {
    main {
        java.srcDirs(
            "src/main/kotlin",
            layout.buildDirectory.dir("generated/sources/core")
        )
    }
}

tasks.named("compileKotlin") {
    dependsOn(syncCore)
}

dependencies {
    implementation("com.google.zxing:core:3.5.3")
    implementation("com.google.zxing:javase:3.5.3")
    implementation("com.github.sarxos:webcam-capture:0.3.12")
    implementation("com.formdev:flatlaf:3.5.4")
    implementation("org.json:json:20240303")
    testImplementation("junit:junit:4.13.2")
}

val fatJar = tasks.register<Jar>("fatJar") {
    archiveBaseName.set("pShare-desktop")
    archiveClassifier.set("all")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest {
        attributes["Main-Class"] = "x.vladgba.share.desktop.MainKt"
    }
    from(sourceSets.main.get().output)
    dependsOn(configurations.runtimeClasspath)
    from({
        configurations.runtimeClasspath.get().filter { it.name.endsWith("jar") }.map { zipTree(it) }
    })
}

// 1. Portable Version: Standalone application directory with pShare.exe and bundled JRE
val packagePortable = tasks.register<Exec>("packagePortable") {
    dependsOn(fatJar)
    val outputDir = layout.buildDirectory.dir("dist/portable").get().asFile
    val jarFile = fatJar.get().archiveFile.get().asFile
    doFirst {
        outputDir.deleteRecursively()
        outputDir.mkdirs()
    }
    commandLine(
        "jpackage",
        "--type", "app-image",
        "--input", jarFile.parentFile.absolutePath,
        "--main-jar", jarFile.name,
        "--main-class", "x.vladgba.share.desktop.MainKt",
        "--name", "pShare",
        "--dest", outputDir.absolutePath
    )
}

// Zip the portable folder into pShare-windows-portable.zip
tasks.register<Zip>("zipPortable") {
    dependsOn(packagePortable)
    archiveFileName.set("pShare-windows-portable.zip")
    destinationDirectory.set(layout.buildDirectory.dir("dist"))
    from(layout.buildDirectory.dir("dist/portable"))
}

// 2. Installable Version: Windows MSI installer (pShare-1.0.msi)
tasks.register<Exec>("packageInstaller") {
    dependsOn(fatJar)
    val outputDir = layout.buildDirectory.dir("dist/installer").get().asFile
    val jarFile = fatJar.get().archiveFile.get().asFile
    doFirst {
        outputDir.deleteRecursively()
        outputDir.mkdirs()
    }
    val wixPath = "C:\\Users\\User\\.wix311"
    val existingPath = System.getenv("PATH") ?: ""
    environment("PATH", "$wixPath;$existingPath")
    commandLine(
        "jpackage",
        "--type", "msi",
        "--input", jarFile.parentFile.absolutePath,
        "--main-jar", jarFile.name,
        "--main-class", "x.vladgba.share.desktop.MainKt",
        "--name", "pShare",
        "--app-version", "1.0.0",
        "--dest", outputDir.absolutePath,
        "--win-shortcut",
        "--win-menu"
    )
}
