plugins {
    base
    id("com.gradleup.shadow") version "9.6.1" apply false
    id("xyz.jpenilla.run-paper") version "3.1.0" apply false
}

allprojects {
    group = "com.jifercraft"
    version = "0.2.0-pre.3"
}

// Every server jar lands in the root build/libs, next to each other.
val copyJars = tasks.register<Copy>("copyJars") {
    from(project(":bingorush-modern").tasks.named("shadowJar"))
    from(project(":bingorush-legacy").tasks.named("shadowJar"))
    into(layout.buildDirectory.dir("libs"))
}

tasks.named("assemble") {
    dependsOn(project(":bingorush-modern").tasks.named("shadowJar"))
    dependsOn(project(":bingorush-legacy").tasks.named("shadowJar"))
    finalizedBy(copyJars)
}
