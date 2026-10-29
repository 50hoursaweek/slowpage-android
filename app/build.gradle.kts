// Slowpage for Android. Run scripts/build-rust.sh first; it fills rust-out/ (the hushhour-ipad
// .so, its Kotlin bindings, the bundled music).
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
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
        versionCode = 2
        versionName = "0.1.0-shell"
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

    testOptions {
        // The bindings' cleaner checks Build.VERSION; the android.jar stubs return defaults.
        unitTests.isReturnDefaultValues = true
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            it.systemProperty("jna.library.path", rustOut.resolve("host").absolutePath)
            it.systemProperty("slowpage.assets", rustOut.resolve("assets").absolutePath)
        }
    }

    buildFeatures {
        prefab = true
        compose = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.06.01"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.activity:activity-compose:1.12.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("com.google.oboe:oboe:1.10.0")
    // UniFFI's Kotlin bindings load the Rust library through JNA.
    implementation("net.java.dev.jna:jna:5.19.1@aar")
    // The bindings' Android cleaner (API 34+) is marked @RequiresApi.
    implementation("androidx.annotation:annotation:1.10.0")

    testImplementation("junit:junit:4.13.2")
    // Compose UI tests on the JVM (Robolectric): focus and taps in the editor (F1).
    testImplementation(platform("androidx.compose:compose-bom:2026.06.01"))
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("org.robolectric:robolectric:4.16.1")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    // JVM unit tests load the host build of the core (rust-out/host) through the same bindings.
    testImplementation("net.java.dev.jna:jna:5.19.1")
}
