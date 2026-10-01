plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "dev.lightsaber.app"
    // compileSdk 37 only buys access to newer APIs. It opts the app into NOTHING at runtime — that is
    // targetSdk, which stays 36 so the Android 17 background-audio rules (Wear OS 7) do not apply
    // until Milestone 4 has actually been designed for them. See docs/platform-decisions.md §5.
    compileSdk = 37

    // AGP 9 prefers NDK 28.2, but the only NDK installed on this machine is 26.1.10909125 and the
    // SDK directory sits outside this workspace's write sandbox. Pin what is actually present.
    ndkVersion = "26.1.10909125"

    defaultConfig {
        applicationId = "dev.lightsaber.app"
        // Wear OS 4 is the baseline. See docs/platform-decisions.md §1.
        minSdk = 34
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        // The SM-R870 test watch reports a 32-bit userland (armeabi-v7a, zygote32), so an
        // arm64-only APK fails to install with INSTALL_FAILED_NO_MATCHING_ABIS. This is also why
        // ControlFrame carries alignas(8): on 32-bit ABIs int64_t only aligns to 4.
        ndk {
            abiFilters += listOf("armeabi-v7a")
        }

        externalNativeBuild {
            cmake {
                // Oboe's prefab is built against the shared C++ runtime; AGP defaults an app to the
                // static one. Passing this on the command line is what makes the toolchain pick
                // c++_shared before our CMakeLists body runs — a plain set() inside it is too late.
                arguments += "-DANDROID_STL=c++_shared"
            }
        }
    }

    signingConfigs {
        // AGP already defines a "debug" config; configure it rather than adding a second one.
        getByName("debug") {
            // AGP would otherwise generate ~/.android/debug.keystore, which is outside this
            // workspace's write sandbox. The key is a throwaway debug key, generated locally.
            storeFile = rootProject.file("keystore/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        // Oboe ships its CMake config inside the AAR; prefab is how Gradle hands it to CMake.
        prefab = true
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
    }
}

dependencies {
    implementation(project(":motion"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.wear.compose.material)
    implementation(libs.wear.compose.foundation)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.oboe)
}

/**
 * Packaged sound assets are generated, not authored.
 *
 * `engine/assets` is the single source of truth; `tools/synth_assets.py` writes it and a
 * `.provenance.txt` beside every WAV. The APK still needs the files under `assets/audio/`, so they
 * are copied here at build time rather than committed twice — an edited copy in the app would
 * silently diverge from the engine, and the offline renderer reads the originals.
 *
 * The copy runs before every merge of the app's assets, so regenerating the assets and rebuilding
 * is all that is ever needed; nothing has to be synced by hand.
 */
val generatedAudioDir = layout.projectDirectory.dir("src/main/assets/audio")
val sourceAudioDir = rootProject.layout.projectDirectory.dir("engine/assets")

val syncGeneratedAudio = tasks.register<Sync>("syncGeneratedAudio") {
    description = "Copies engine/assets into the app's packaged assets (engine/assets is canonical)."
    group = "build"
    from(sourceAudioDir) {
        include("*.wav", "*.provenance.txt")
    }
    into(generatedAudioDir)
}

// packageDebugAssets consumes src/main/assets, so the copy must happen before it.
tasks.matching { it.name.contains("Assets", ignoreCase = true) && it.name.startsWith("merge") }
    .configureEach { dependsOn(syncGeneratedAudio) }

// Belt and braces: if the assets dir is missing entirely, fail with the command that fixes it
// rather than shipping a silently silent app.
tasks.matching { it.name == "preBuild" }.configureEach {
    doFirst {
        if (!generatedAudioDir.asFile.resolve("hum.wav").exists()) {
            logger.lifecycle(
                "engine/assets has not been generated yet. Run: python3 tools/synth_assets.py engine/assets",
            )
        }
    }
}