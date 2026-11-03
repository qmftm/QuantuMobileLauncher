// PC program that runs AFK bots for the app ("AFK host"). Built as one runnable jar:
//   ./gradlew :afk-host:jar   →   java -jar afk-host/build/libs/afk-host.jar
plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":afk-core"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}

/** Same version as the app, so a release's host jar and APK match. */
val appVersion: String = Regex("versionName = \"([^\"]+)\"")
    .find(rootProject.file("app/build.gradle.kts").readText())?.groupValues?.get(1) ?: "dev"

tasks.jar {
    archiveFileName.set("afk-host.jar")
    manifest {
        attributes("Main-Class" to "com.quantumobile.afkhost.HostMainKt", "Implementation-Version" to appVersion)
    }
    // One self-contained jar: include the runtime libraries (Kotlin, coroutines, serialization, afk-core).
    dependsOn(configurations.runtimeClasspath)
    from({ configurations.runtimeClasspath.get().filter { it.name.endsWith(".jar") }.map { zipTree(it) } })
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/versions/9/module-info.class", "module-info.class")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}
