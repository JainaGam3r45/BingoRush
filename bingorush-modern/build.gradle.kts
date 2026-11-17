plugins {
    java
    id("com.gradleup.shadow")
    id("xyz.jpenilla.run-paper")
}

description = "Bingo minigame for live events on Paper 26.x and newer"

repositories {
    maven("https://repo.papermc.io/repository/maven-public/")
    mavenCentral()
}

dependencies {
    implementation(project(":bingorush-core"))
    compileOnly("io.papermc.paper:paper-api:26.3.build.253-stable")
    testImplementation("io.papermc.paper:paper-api:26.3.build.253-stable")

    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.compileJava {
    // No complete JDK 25 on this machine, so build on the JDK 27 javac and
    // just emit Java 25 bytecode. Switch to a toolchain once a real JDK 25 is installed.
    options.release = 25
    options.encoding = "UTF-8"
    options.compilerArgs.add("-Xlint:deprecation")
}

tasks.compileTestJava {
    options.release = 25
    options.encoding = "UTF-8"
}

tasks.processResources {
    filteringCharset = "UTF-8"
    val expanded = mapOf(
        "name" to rootProject.name,
        "version" to project.version.toString(),
    )
    inputs.properties(expanded)
    filesMatching("plugin.yml") {
        expand(expanded)
    }
}

tasks.test {
    useJUnitPlatform()
}

tasks.jar {
    archiveClassifier = "slim"
}

tasks.shadowJar {
    // BingoRush's own license travels with every jar.
    from(rootProject.file("LICENSE"))
    archiveBaseName = "BingoRush-Modern"
    archiveClassifier = ""
    // Paper already provides Gson; only the core classes and files go in.
    dependencies {
        include(project(":bingorush-core"))
    }
}

tasks.assemble {
    dependsOn(tasks.shadowJar)
}

tasks.runServer {
    minecraftVersion("26.3")
    pluginJars(tasks.shadowJar.flatMap { it.archiveFile })
    runDirectory(rootProject.file("run"))
}
