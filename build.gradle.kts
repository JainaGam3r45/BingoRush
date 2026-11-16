plugins {
    java
    id("xyz.jpenilla.run-paper") version "3.1.0"
}

group = "com.jifercraft"
version = "0.1.0"
description = "Bingo minigame for live events on Paper"

repositories {
    maven("https://repo.papermc.io/repository/maven-public/")
    mavenCentral()
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.3.build.253-stable")

    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
}

tasks.compileJava {
    // No complete JDK 25 on this machine, so build on the JDK 27 javac and
    // just emit Java 25 bytecode. Switch to a toolchain once a real JDK 25 is installed.
    options.release = 25
    options.encoding = "UTF-8"
    options.compilerArgs.add("-Xlint:deprecation")
}

tasks.processResources {
    filteringCharset = "UTF-8"
    val expanded = mapOf(
        "name" to rootProject.name,
        "version" to project.version.toString(),
    )
    filesMatching("plugin.yml") {
        expand(expanded)
    }
}

tasks.test {
    useJUnitPlatform()
}

tasks.jar {
    archiveBaseName = rootProject.name
}

tasks.runServer {
    minecraftVersion("26.3")
}
