// Slowpage for Android. Run scripts/build-rust.sh first; it fills rust-out/ (the hushhour-ipad
// .so, its Kotlin bindings, the bundled music).
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val rustOut = rootProject.file("rust-out")

// The release key (phase 7), from the environment or a Gradle property, never from the repo:
// SLOWPAGE_KEYSTORE (path) and SLOWPAGE_KEYSTORE_PASSWORD, or slowpage.keystore and
// slowpage.keystorePassword in ~/.gradle/gradle.properties. scripts/release-apk.sh takes the
// password from the macOS Keychain. Without them a release build is signed with the debug key,
// so the project builds for anyone.
fun setting(env: String, property: String): String? =
    System.getenv(env)?.takeIf { it.isNotEmpty() } ?: (findProperty(property) as String?)?.takeIf { it.isNotEmpty() }
val releaseKeystore = setting("SLOWPAGE_KEYSTORE", "slowpage.keystore")?.let { file(it) }?.takeIf { it.isFile }
val releasePassword = setting("SLOWPAGE_KEYSTORE_PASSWORD", "slowpage.keystorePassword")

android {
    namespace = "com.fiftyhoursaweek.slowpage"
    compileSdk = 36
    ndkVersion = "29.0.14206865"

    defaultConfig {
        applicationId = "be.grunt.slowpage"
        minSdk = 26
        targetSdk = 36
        versionCode = 5
        versionName = "1.0.1"
        ndk { abiFilters += "arm64-v8a" }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        externalNativeBuild {
            cmake {
                arguments += "-DANDROID_STL=c++_shared"
                arguments += "-DHUSHHOUR_SO_DIR=${rustOut.resolve("jniLibs").absolutePath}"
            }
        }
    }

    signingConfigs {
        if (releaseKeystore != null && releasePassword != null) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = releasePassword
                keyAlias = "slowpage"
                keyPassword = releasePassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    sourceSets["main"].apply {
        jniLibs.directories += rustOut.resolve("jniLibs").path
        kotlin.directories += rustOut.resolve("kotlin").path
        assets.directories += rustOut.resolve("assets").path
        res.directories += rustOut.resolve("res").path
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
            // Robolectric's android.system.SystemCleaner (used by the UniFFI bindings) needs it.
            it.jvmArgs("--add-exports", "java.base/jdk.internal.ref=ALL-UNNAMED")
        }
    }

    buildFeatures {
        prefab = true
        compose = true
        // About shows the version and build (O5).
        buildConfig = true
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
    // The preview: one text per block, laid out only when it shows (V3).
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    // UniFFI's Kotlin bindings load the Rust library through JNA.
    implementation("net.java.dev.jna:jna:5.19.1@aar")
    // The bindings' Android cleaner (API 34+) is marked @RequiresApi.
    implementation("androidx.annotation:annotation:1.10.0")

    testImplementation("junit:junit:4.13.2")
    // On the emulator: the background shader against the core's reference (E16), as the iPad tests its Metal port.
    androidTestImplementation("androidx.test:runner:1.5.0")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    // Compose UI tests on the JVM (Robolectric): focus and taps in the editor (F1).
    testImplementation(platform("androidx.compose:compose-bom:2026.06.01"))
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("org.robolectric:robolectric:4.16.1")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    // JVM unit tests load the host build of the core (rust-out/host) through the same bindings.
    testImplementation("net.java.dev.jna:jna:5.19.1")
}
