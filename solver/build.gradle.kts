plugins {
    kotlin("jvm") version "1.9.23"
    application
}

repositories { mavenCentral() }

dependencies {
    // Sat4j core: a SAT solver in PURE JAVA (no JNI, no native libs, no AWT), so it drops into
    // an Android app unchanged. This is the solver engine — see SatEngine.kt for why search lost.
    implementation("org.ow2.sat4j:org.ow2.sat4j.core:2.3.6")
    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(17)          // Android-compatible
}

application {
    mainClass.set("flow.MainKt")
}

tasks.test {
    useJUnitPlatform()
    testLogging { events("passed", "failed"); showStandardStreams = true }
}

// fat jar so `java -jar` works without gradle
tasks.register<Jar>("cli") {
    archiveFileName.set("flowsolve.jar")
    manifest { attributes["Main-Class"] = "flow.MainKt" }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(sourceSets.main.get().output)
    from({ configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) } })
}

tasks.register<JavaExec>("bench") {
    group = "verification"
    description = "Benchmark the solver against the puzzles in src/test/resources"
    mainClass.set("flow.Bench")
    classpath = sourceSets.main.get().runtimeClasspath
}

tasks.register<JavaExec>("detect") {
    group = "application"
    description = "Read a Flow Free screenshot and solve it"
    mainClass.set("flow.desktop.DetectMainKt")
    classpath = sourceSets.main.get().runtimeClasspath
}

// write a synthetic Flow Free screenshot to PNG, so the whole pipeline can be exercised on a file
tasks.register<JavaExec>("shot") {
    group = "verification"
    mainClass.set("flow.desktop.ShotKt")
    classpath = sourceSets.main.get().runtimeClasspath
}
