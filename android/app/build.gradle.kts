import java.util.Properties

plugins {
    id("com.android.application")
    id("kotlin-android")
    // The Flutter Gradle Plugin must be applied after the Android and Kotlin Gradle plugins.
    id("dev.flutter.flutter-gradle-plugin")
}

// Local releases use private android/key.properties; CI prepares it from four
// explicitly configured repository secrets. Debug builds need no release key.
val keystorePropertiesFile = rootProject.file("key.properties")
val keystoreProperties = Properties()
if (keystorePropertiesFile.exists()) {
    keystorePropertiesFile.inputStream().use { keystoreProperties.load(it) }
}
val requiredSigningProperties = listOf("storeFile", "storePassword", "keyAlias", "keyPassword")
val missingSigningProperties = requiredSigningProperties.filter {
    keystoreProperties.getProperty(it).isNullOrBlank()
}
val releaseKeystore = keystoreProperties.getProperty("storeFile")
    ?.takeIf { it.isNotBlank() }?.let { file(it) }
val verifyReleaseSigning = tasks.register("verifyReleaseSigning") {
    doLast {
        check(missingSigningProperties.isEmpty()) {
            "Release signing requires android/key.properties with: " +
                missingSigningProperties.joinToString(", ") +
                ". Configure a private release key; see SIGNING.md. Use --debug for development."
        }
        check(releaseKeystore?.isFile == true) {
            "Release signing keystore does not exist. Check storeFile in android/key.properties."
        }
    }
}
// Cover both normal release builds and direct signing validation tasks.
tasks.configureEach {
    if (name == "preReleaseBuild" || name == "validateSigningRelease") {
        dependsOn(verifyReleaseSigning)
    }
}

android {
    // TODO: Change namespace to match your package name (e.g., "com.yourcompany.yourapp")
    namespace = "com.cmwen.tablet_hid_gamepad"
    compileSdk = flutter.compileSdkVersion
    ndkVersion = flutter.ndkVersion

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = JavaVersion.VERSION_17.toString()
    }

    defaultConfig {
        // TODO: Change applicationId to match your package name (e.g., "com.yourcompany.yourapp")
        // IMPORTANT: This MUST match the package in MainActivity.kt and namespace above
        applicationId = "com.cmwen.tablet_hid_gamepad"
        // You can update the following values to match your application needs.
        // For more information, see: https://flutter.dev/to/review-gradle-config.
        minSdk = 28
        targetSdk = flutter.targetSdkVersion
        versionCode = flutter.versionCode
        versionName = flutter.versionName
    }

    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }

    // Build optimization settings
    buildFeatures {
        buildConfig = false
        aidl = false
        renderScript = false
        shaders = false
    }

    signingConfigs {
        if (missingSigningProperties.isEmpty()) {
            create("release") {
                keyAlias = keystoreProperties["keyAlias"] as String
                keyPassword = keystoreProperties["keyPassword"] as String
                storeFile = file(keystoreProperties["storeFile"] as String)
                storePassword = keystoreProperties["storePassword"] as String
            }
        }
    }

    buildTypes {
        debug {
            // Faster debug builds
            isMinifyEnabled = false
            isShrinkResources = false
        }
        release {
            // Enable R8 code shrinking and resource shrinking for smaller APK
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt")
            )
            signingConfig = signingConfigs.findByName("release")
        }
    }

    packaging {
        jniLibs { useLegacyPackaging = true }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

flutter {
    source = "../.."
}

// Force JVM 17 for all dependency projects
afterEvaluate {
    tasks.withType(org.jetbrains.kotlin.gradle.tasks.KotlinCompile::class).all {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
}
