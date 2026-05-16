plugins {
    alias(mihonx.plugins.android.library)
}

android {
    namespace = "mihon.core.superresolution"

    defaultConfig {
        consumerProguardFiles("consumer-rules.pro")

        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86_64")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    ndkVersion = "27.0.12077973"

    sourceSets {
        getByName("main") {
            assets.srcDirs("src/main/assets")
        }
    }
}

dependencies {
    implementation(libs.kotlinx.coroutines.android)
    implementation(projects.i18n)
    implementation(libs.okhttp.core)
    api(libs.logcat)
}
