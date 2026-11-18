plugins {
    java
    id("com.gradleup.shadow")
}

description = "Bingo minigame for live events on Spigot and Paper 1.8.8 to 1.21.x"

repositories {
    mavenCentral()
    maven("https://hub.spigotmc.org/nexus/content/groups/public/") {
        content {
            includeGroup("org.spigotmc")
            includeGroup("org.bukkit")
            includeGroup("net.md-5")
        }
    }
    maven("https://repo.extendedclip.com/releases/")
}

// Code that needs the 1.12 API (advancements). It is compiled on its own and
// only loaded after the plugin checked the server is 1.12 or newer, so the
// main code keeps compiling against 1.8.8 and cannot use newer API by mistake.
val v1_12: SourceSet = sourceSets.create("v1_12")

configurations.named(v1_12.compileOnlyConfigurationName) {
    // Core, Adventure and XSeries, but not the 1.8.8 API from compileOnly.
    extendsFrom(configurations.implementation.get())
}

configurations.runtimeClasspath {
    // The server ships Gson, and Adventure's platform layer knows how to live
    // with the old one in 1.8.8.
    exclude(group = "com.google.code.gson")
}

dependencies {
    implementation(project(":bingorush-core"))
    compileOnly("org.spigotmc:spigot-api:1.8.8-R0.1-SNAPSHOT")
    compileOnly("me.clip:placeholderapi:2.11.6")
    "v1_12CompileOnly"("org.spigotmc:spigot-api:1.12.2-R0.1-SNAPSHOT")
    "v1_12CompileOnly"(sourceSets.main.get().output)

    implementation("net.kyori:adventure-api:4.26.1")
    implementation("net.kyori:adventure-text-minimessage:4.26.1")
    implementation("net.kyori:adventure-text-serializer-legacy:4.26.1")
    implementation("net.kyori:adventure-text-serializer-plain:4.26.1")
    implementation("net.kyori:adventure-platform-bukkit:4.4.1")
    implementation("com.github.cryptomorin:XSeries:14.0.0") {
        isTransitive = false
    }

    testImplementation("org.spigotmc:spigot-api:1.8.8-R0.1-SNAPSHOT")
    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}

tasks.compileJava {
    options.release = 8
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:deprecation", "-Xlint:-options"))
}

tasks.named<JavaCompile>("compileV1_12Java") {
    options.release = 8
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:deprecation", "-Xlint:-options"))
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

tasks.jar {
    archiveClassifier = "slim"
}

tasks.shadowJar {
    // BingoRush's own license travels with every jar.
    from(rootProject.file("LICENSE"))
    archiveBaseName = "BingoRush-Legacy"
    archiveClassifier = ""
    from(v1_12.output)
    mergeServiceFiles()
    // Other plugins may ship their own Adventure or XSeries; ours stays private.
    relocate("net.kyori", "com.jifercraft.bingo.lib.kyori")
    relocate("com.cryptomorin.xseries", "com.jifercraft.bingo.lib.xseries")
    exclude("META-INF/maven/**", "META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
}

tasks.assemble {
    dependsOn(tasks.shadowJar)
}
