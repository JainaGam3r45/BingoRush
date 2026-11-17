plugins {
    `java-library`
}

description = "Game rules and language files shared by every BingoRush jar"

repositories {
    mavenCentral()
}

dependencies {
    // The Gson that Spigot 1.8.8 ships. Compiling against it keeps the shared
    // code to what the oldest supported server has; newer servers bring a
    // newer, compatible Gson at runtime.
    compileOnly("com.google.code.gson:gson:2.2.4")
    testImplementation("com.google.code.gson:gson:2.2.4")

    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.compileJava {
    // The legacy jar runs on Java 8, so this code has to as well.
    options.release = 8
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:deprecation", "-Xlint:-options"))
}

tasks.compileTestJava {
    // Tests only run here, on the build JDK.
    options.release = 25
    options.encoding = "UTF-8"
}

tasks.test {
    useJUnitPlatform()
}
