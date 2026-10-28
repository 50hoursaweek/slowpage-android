// Gate A spike: the Rust mixer from hushhour-ipad, driven by an Oboe callback. No UI yet.
// Run scripts/build-rust.sh first; it fills rust-out/ (the .so, the Kotlin bindings, the music).
plugins {
    id("com.android.application")
}

val rustOut = rootProject.file("rust-out")

android {
    namespace = "com.fiftyhoursaweek.slowpage"
    compileSdk = 36
    ndkVersion = "29.0.14206865"

    defaultConfig {
        applicationId = "be.grunt.slowpage"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.0.1-gate-a"
        ndk { abiFilters += "arm64-v8a" }
        externalNativeBuild {
            cmake {
                arguments += "-DANDROID_STL=c++_shared"
                arguments += "-DHUSHHOUR_SO_DIR=${rustOut.resolve("jniLibs").absolutePath}"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Sideloaded internal builds: signed with the debug key until Francesco decides on a key.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    sourceSets["main"].apply {
        jniLibs.directories += rustOut.resolve("jniLibs").path
        kotlin.directories += rustOut.resolve("kotlin").path
        assets.directories += rustOut.resolve("assets").path
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "4.1.2"
        }
    }

    buildFeatures { prefab = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("com.google.oboe:oboe:1.10.0")
    // UniFFI's Kotlin bindings load the Rust library through JNA.
    implementation("net.java.dev.jna:jna:5.19.1@aar")
    // The bindings' Android cleaner (API 34+) is marked @RequiresApi.
    implementation("androidx.annotation:annotation:1.10.0")
}
