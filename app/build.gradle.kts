plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.cosmerearchives.shortscreator"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.cosmerearchives.shortscreator"
        minSdk = 26
        targetSdk = 35
        ndk { abiFilters += listOf("arm64-v8a") }
        externalNativeBuild { cmake { cppFlags += "-std=c++17" } }
        versionCode = 87
        versionName = "0.86.0-alpha"
    }

    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt") } }

    val releaseStore = System.getenv("SHORTS_KEYSTORE_PATH")
    signingConfigs {
        if (!releaseStore.isNullOrEmpty()) {
            create("stable") {
                storeFile = file(releaseStore)
                storePassword = System.getenv("SHORTS_STORE_PASSWORD")
                keyAlias = System.getenv("SHORTS_KEY_ALIAS")
                keyPassword = System.getenv("SHORTS_KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        getByName("debug") {
            if (!releaseStore.isNullOrEmpty()) signingConfig = signingConfigs.getByName("stable")
        }
        getByName("release") {
            isMinifyEnabled = false
            if (!releaseStore.isNullOrEmpty()) signingConfig = signingConfigs.getByName("stable")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true; buildConfig = true }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.12.01"))
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.media3:media3-exoplayer:1.11.1")
    implementation("androidx.media3:media3-ui:1.11.1")
    implementation("androidx.media3:media3-transformer:1.11.1")
    implementation("androidx.media3:media3-effect:1.11.1")
    testImplementation("junit:junit:4.13.2")
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}
