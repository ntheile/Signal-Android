/**
 * LNI (Lightning Node Interface) Android Module
 * 
 * This module integrates the LNI Rust library with Android via UniFFI bindings.
 * 
 * Prerequisites:
 * 1. Rust installed with Android targets:
 *    rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android i686-linux-android
 * 2. cargo-ndk installed:
 *    cargo install cargo-ndk
 * 3. Android NDK installed (set via ANDROID_NDK_HOME)
 * 
 * To build the native libraries:
 *    ./gradlew :lni:buildRust
 * 
 * The build task will:
 * 1. Clone/update the LNI repository from GitHub
 * 2. Build the Rust library for Android targets using cargo-ndk
 * 3. Generate Kotlin bindings using uniffi-bindgen
 * 4. Copy the .so files and generated Kotlin code to the appropriate locations
 */

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.jetbrains.kotlin.android)
}

val signalBuildToolsVersion: String by rootProject.extra
val signalCompileSdkVersion: String by rootProject.extra
val signalMinSdkVersion: Int by rootProject.extra
val signalNdkVersion: String by rootProject.extra
val signalJavaVersion: JavaVersion by rootProject.extra
val signalKotlinJvmTarget: String by rootProject.extra

android {
    namespace = "org.lni"
    compileSdkVersion = signalCompileSdkVersion
    buildToolsVersion = signalBuildToolsVersion
    ndkVersion = signalNdkVersion

    defaultConfig {
        minSdk = signalMinSdkVersion
        
        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
        }
    }

    compileOptions {
        sourceCompatibility = signalJavaVersion
        targetCompatibility = signalJavaVersion
    }

    kotlinOptions {
        jvmTarget = signalKotlinJvmTarget
    }

    sourceSets {
        getByName("main") {
            // Generated Kotlin bindings from uniffi
            kotlin.srcDir("src/main/kotlin")
            // Native libraries built by cargo-ndk
            jniLibs.srcDir("src/main/jniLibs")
        }
    }
}

dependencies {
    // JNA is required for UniFFI bindings
    implementation("net.java.dev.jna:jna:5.14.0@aar")
    
    // Kotlin coroutines for async operations
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
}

// Task to clone or update the LNI repository
tasks.register<Exec>("cloneLni") {
    val lniDir = file("${project.projectDir}/lni-src")
    
    doFirst {
        if (!lniDir.exists()) {
            commandLine("git", "clone", "--depth", "1", "https://github.com/lightning-node-interface/lni.git", lniDir.absolutePath)
        } else {
            workingDir = lniDir
            commandLine("git", "pull")
        }
    }
}

// Task to build Rust library for Android
tasks.register("buildRust") {
    group = "build"
    description = "Build LNI Rust library for Android targets"
    
    dependsOn("cloneLni")
    
    doLast {
        val lniDir = file("${project.projectDir}/lni-src/crates/lni")
        val jniLibsDir = file("${project.projectDir}/src/main/jniLibs")
        
        // Mapping from Rust target to Android ABI
        val targets = mapOf(
            "aarch64-linux-android" to "arm64-v8a",
            "armv7-linux-androideabi" to "armeabi-v7a",
            "x86_64-linux-android" to "x86_64",
            "i686-linux-android" to "x86"
        )
        
        targets.forEach { (rustTarget, androidAbi) ->
            val abiDir = file("${jniLibsDir}/${androidAbi}")
            abiDir.mkdirs()
            
            // Build with cargo-ndk
            exec {
                workingDir = lniDir
                environment("CARGO_FEATURE_UNIFFI", "1")
                commandLine(
                    "cargo", "ndk",
                    "--target", rustTarget,
                    "--platform", "21",
                    "build", "--release", "--features", "uniffi"
                )
            }
            
            // Copy the built .so file
            val soFile = file("${project.projectDir}/lni-src/target/${rustTarget}/release/liblni.so")
            if (soFile.exists()) {
                copy {
                    from(soFile)
                    into(abiDir)
                    rename { "liblni.so" }
                }
            }
        }
        
        // Generate Kotlin bindings using uniffi-bindgen
        val bindingsDir = file("${project.projectDir}/src/main/kotlin")
        bindingsDir.mkdirs()
        
        exec {
            workingDir = lniDir
            commandLine(
                "cargo", "run", "--features", "uniffi/cli", "--bin", "uniffi-bindgen",
                "generate", "--library", 
                "${project.projectDir}/lni-src/target/aarch64-linux-android/release/liblni.so",
                "--language", "kotlin",
                "--out-dir", bindingsDir.absolutePath
            )
        }
    }
}

// Make preBuild depend on Rust build for production builds
// Comment out for development if you have pre-built binaries
// tasks.named("preBuild") {
//     dependsOn("buildRust")
// }
