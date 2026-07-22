import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

// Signing credentials stay out of git. Copy keystore.properties.example and fill it in;
// without it, release builds are simply unsigned rather than failing.
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use(::load)
}

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "org.peekit.opentimelapse"
    compileSdk = 35

    defaultConfig {
        applicationId = "org.peekit.opentimelapse"
        minSdk = 28
        targetSdk = 35
        // Bump versionCode for every build you install over an older one; Android refuses
        // a downgrade. versionName is what humans read.
        versionCode = 6
        versionName = "0.2.4"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // The Phase 0 probe binary is a real ELF executable, not a library: it must be
    // extracted to nativeLibraryDir at install time, which is the only directory an
    // app is permitted to exec from since Android 10.
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    buildFeatures {
        compose = true
    }

    // Per-ABI APKs so neither ships a 4MB ffmpeg binary it cannot run. A universal APK is
    // also emitted for convenient sideloading.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a")
            isUniversalApk = true
        }
    }

    signingConfigs {
        create("release") {
            val storeFilePath = keystoreProperties.getProperty("storeFile")
            if (storeFilePath != null) {
                storeFile = rootProject.file(storeFilePath)
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }

        release {
            // Signed only when keystore.properties is present, so a fresh clone still builds.
            if (keystoreProperties.getProperty("storeFile") != null) {
                signingConfig = signingConfigs.getByName("release")
            }
            // Left off deliberately: the accessibility service and the config classes are
            // reflected over by the system and by kotlinx.serialization, and a broken
            // release is worse than a larger APK for a sideloaded personal tool.
            isMinifyEnabled = false
        }
    }

    sourceSets["main"].java.srcDir("src/main/kotlin")
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core"))
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.datastore.preferences)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
}
