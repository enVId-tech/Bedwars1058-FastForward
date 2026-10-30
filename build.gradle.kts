import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.3.21"
    id("com.gradleup.shadow") version "9.4.3"
    id("xyz.jpenilla.run-paper") version "2.3.1"
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://hub.spigotmc.org/nexus/content/repositories/snapshots/")
    maven("https://jitpack.io")
}

dependencies {
    // Old API on purpose: it's Java 8 and works on 1.16 through 1.21 servers.
    compileOnly("org.spigotmc:spigot-api:1.16.5-R0.1-SNAPSHOT")

    // BedWars1058 (provides the API classes we compile against)
    compileOnly(files("libs/bedwars-plugin-25.5-SNAPSHOT.jar"))

    // Bukkit doesn't ship the Kotlin runtime, so it is bundled (and relocated) by shadowJar below.
    implementation(kotlin("stdlib"))
}

java {
    // JDK used to run the build
    toolchain.languageVersion.set(JavaLanguageVersion.of(17))
}

kotlin {
    compilerOptions {
        // Bytecode target: runs on Java 8 and up
        jvmTarget.set(JvmTarget.JVM_1_8)
    }
}

tasks {
    withType<JavaCompile> {
        options.release.set(8)
    }

    shadowJar {
        archiveClassifier.set("")
        // Relocate so we can't clash with another plugin's copy of Kotlin.
        relocate("kotlin", "dev.etran.bedwars1058FastForward.libs.kotlin")
        relocate("org.jetbrains.annotations", "dev.etran.bedwars1058FastForward.libs.jetbrains.annotations")
        relocate("org.intellij.lang.annotations", "dev.etran.bedwars1058FastForward.libs.intellij.annotations")
        minimize()
    }

    // Make `gradle build` produce the ready-to-install, Kotlin-bundled jar.
    assemble {
        dependsOn(shadowJar)
    }

    runServer {
        // Test server only; this does not affect the compiled plugin
        minecraftVersion("1.20.2")
        jvmArgs("-Xms2G", "-Xmx2G")
    }

    processResources {
        val props = mapOf("version" to project.version)
        inputs.properties(props)
        filesMatching("plugin.yml") {
            expand(props)
        }
    }
}
