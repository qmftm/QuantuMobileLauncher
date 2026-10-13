plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.quantumobile.launcher"
    compileSdk = 35
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "com.quantumobile.launcher"
        minSdk = 26
        targetSdk = 35
        versionCode = 10
        versionName = "0.5.3"

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
        externalNativeBuild {
            cmake {
                cppFlags += ""
            }
        }
    }

    // One fixed key for every build, so a new version installs over the old one and keeps the user's data (a key
    // generated per CI run would make every release a "different app" to Android and updates would be refused).
    // The key is committed on purpose: this is a sideloaded hobby app, not a store build. That also means anyone can
    // sign an APK that updates this app; replace the key (and these passwords) if that ever matters.
    // QML_KEYSTORE, QML_KEYSTORE_PASSWORD, QML_KEY_ALIAS and QML_KEY_PASSWORD override it (e.g. from CI secrets).
    signingConfigs {
        create("project") {
            val envStore = System.getenv("QML_KEYSTORE")
            storeFile = if (!envStore.isNullOrBlank()) file(envStore) else file("signing/quantumobile-release.jks")
            storePassword = System.getenv("QML_KEYSTORE_PASSWORD") ?: "1EZ91V86NuZnUQwo0dqoDv"
            keyAlias = System.getenv("QML_KEY_ALIAS") ?: "quantumobile"
            keyPassword = System.getenv("QML_KEY_PASSWORD") ?: "1EZ91V86NuZnUQwo0dqoDv"
        }
    }
    val projectSigning = signingConfigs.getByName("project")

    buildTypes {
        debug {
            signingConfig = projectSigning
        }
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = projectSigning
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    packaging {
        // The JVM dlopen()s LWJGL/renderer libraries by absolute path, so they must exist on disk.
        jniLibs.useLegacyPackaging = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.fragment:fragment-ktx:1.8.5")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.preference:preference-ktx:1.2.1")
    implementation("com.google.android.material:material:1.12.0")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.apache.commons:commons-compress:1.27.1")
    implementation("org.tukaani:xz:1.10")

    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}
