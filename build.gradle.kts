plugins {
    id("java-library")
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
}

java {
    // JDK used to run the build
    toolchain.languageVersion.set(JavaLanguageVersion.of(17))
}

tasks {
    // Bytecode target: runs on Java 8 and up
    withType<JavaCompile> {
        options.release.set(8)
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