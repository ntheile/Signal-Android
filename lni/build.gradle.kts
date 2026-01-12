/**
 * LNI (Lightning Node Interface) Android Module
 * 
 * This module integrates the LNI Rust library with Android via UniFFI bindings.
 * Version: v0.2.0 (includes Spark, Swift bindings, improved Kotlin bindings)
 * 
 * Supported Nodes:
 * - LndNode - LND (Lightning Network Daemon)
 * - ClnNode - Core Lightning (CLN)
 * - PhoenixdNode - Phoenixd daemon
 * - NwcNode - Nostr Wallet Connect
 * - StrikeNode - Strike Lightning service
 * - BlinkNode - Blink Lightning service
 * - SpeedNode - Speed Lightning service
 * - SparkNode - Breez Spark SDK (NEW in v0.2.0)
 * 
 * Setup for new users:
 * 1. Clone with submodules: git clone --recurse-submodules <repo>
 *    OR if already cloned: git submodule update --init --recursive
 * 2. That's it! Pre-built native libs are committed, Kotlin bindings come from submodule.
 * 
 * To rebuild native libraries (maintainers only):
 * Prerequisites:
 * 1. Rust installed with Android targets:
 *    rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android i686-linux-android
 * 2. cargo-ndk installed: cargo install cargo-ndk
 * 3. Android NDK installed (set via ANDROID_NDK_HOME)
 * 
 * Then run: ./gradlew :lni:buildRust
 */

plugins {
    id("signal-library")
}

android {
    namespace = "org.lni"

    defaultConfig {
        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
        }
    }

    sourceSets {
        getByName("main") {
            // Use pre-generated Kotlin bindings from upstream LNI
            kotlin.srcDir("lni-src/bindings/kotlin/src/main/kotlin")
            // Native libraries built by cargo-ndk
            jniLibs.srcDir("src/main/jniLibs")
        }
    }
}

dependencies {
    // JNA is required for UniFFI bindings (per LNI docs)
    implementation("net.java.dev.jna:jna:5.13.0@aar")
    
    // Kotlin coroutines for async operations
    implementation(libs.kotlinx.coroutines.core)
}

// Task to build Rust library for Android (only needed to update native binaries)
tasks.register("buildRust") {
    group = "build"
    description = "Build LNI Rust library for Android targets. Run 'git submodule update --remote lni/lni-src' first to get latest LNI."
    
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
