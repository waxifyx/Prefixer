plugins {
    id("java-library")
    id("com.gradleup.shadow") version "9.0.0-beta13"
    id("xyz.jpenilla.run-paper") version "3.0.2"
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.codemc.io/repository/maven-public/")
    maven("https://repo.helpch.at/releases/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    compileOnly("net.luckperms:api:5.5")
    compileOnly("me.clip:placeholderapi:2.11.6")
    compileOnly("io.github.miniplaceholders:miniplaceholders-api:3.0.1")

    implementation("dev.jorel:commandapi-paper-shade:11.2.0")
    implementation("com.google.code.gson:gson:2.13.1")
    implementation("org.xerial:sqlite-jdbc:3.50.3.0")
    implementation("net.dv8tion:JDA:5.6.1") {
        exclude(module = "opus-java")
        exclude(module = "tink")
    }
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(21)
}

tasks {
    shadowJar {
        archiveClassifier.set("")
        relocate("dev.jorel.commandapi", "xyz.waxify.prefixer.lib.commandapi")
    }

    build {
        dependsOn(shadowJar)
    }

    jar {
        archiveClassifier.set("plain")
    }

    runServer {
        minecraftVersion("1.21.11")
        jvmArgs("-Xms2G", "-Xmx2G", "-Dcom.mojang.eula.agree=true")
    }

    processResources {
        val props = mapOf("version" to version)
        filesMatching(listOf("paper-plugin.yml", "messages.yml")) {
            expand(props)
        }
    }
}
