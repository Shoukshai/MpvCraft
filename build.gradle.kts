import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("net.fabricmc.fabric-loom")
    kotlin("jvm")
}

group = property("maven_group") as String
version = property("mod_version") as String

repositories {
    mavenCentral()
}

dependencies {
    minecraft("com.mojang:minecraft:${property("minecraft_version")}")
    implementation("net.fabricmc:fabric-loader:${property("loader_version")}")
    implementation("net.fabricmc:fabric-language-kotlin:${property("fabric_kotlin_version")}")
    implementation("net.fabricmc.fabric-api:fabric-api:${property("fabric_api_version")}")

    // JNA is what talks to libmpv. It must be shipped inside the jar, hence include().
    property("jna_version").let {
        implementation("net.java.dev.jna:jna:$it")
        include("net.java.dev.jna:jna:$it")
    }
}

loom {
    accessWidenerPath = rootProject.file("src/main/resources/mpvcraft.accesswidener")
    runConfigs.named("client") {
        isIdeConfigGenerated = true
        vmArgs.addAll(
            arrayOf(
                "-XX:+AllowEnhancedClassRedefinition",
                "-XX:+IgnoreUnrecognizedVMOptions",
                "-XX:ErrorFile=run/hs_err_%p.log",
                // Uncomment and point at your libmpv if autodetection fails:
                // "-Dmpvcraft.libmpv=/usr/lib/libmpv.so.2",
            )
        )
    }
    runConfigs.named("server") { isIdeConfigGenerated = false }
}

tasks {
    processResources {
        filesMatching("fabric.mod.json") {
            expand(getProperties())
        }
    }

    compileKotlin {
        compilerOptions {
            jvmTarget = JvmTarget.JVM_25
        }
    }

    compileJava {
        sourceCompatibility = "25"
        targetCompatibility = "25"
        options.encoding = "UTF-8"
    }
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}
