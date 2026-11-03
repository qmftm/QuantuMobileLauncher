// The AFK bot (Minecraft protocol client, bot loop, server list) and the phone↔PC remote protocol, as plain
// Kotlin/JVM so the Android app and the PC host (`:afk-host`) share it.
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
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}

tasks.test {
    // Opt-in live test against a real server: QML_AFK_SERVER=host:port ./gradlew :afk-core:test
    environment("QML_AFK_SERVER", System.getenv("QML_AFK_SERVER") ?: "")
}
