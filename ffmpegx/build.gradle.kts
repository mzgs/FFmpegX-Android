import java.net.URL
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    `maven-publish`
}

android {
    namespace = "com.mzgs.ffmpegx"
    compileSdk = 34

    defaultConfig {
        minSdk = 24


        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
        
        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
                arguments += listOf(
                    "-DANDROID_STL=c++_shared",
                    "-DCMAKE_SHARED_LINKER_FLAGS=-Wl,-z,max-page-size=16384"
                )
            }
        }
        
        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a")
        }
    }
    
    packaging {
        jniLibs {
            // Keep unaligned libraries for now (they're in assets, not JNI libs)
            keepDebugSymbols += "**/*.so"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
    
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    
    // Coroutines for async operations
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    
    // NOTE: We're using our own compiled FFmpeg binaries in assets/ffmpeg
    // The binaries include:
    // - Hardware-accelerated H.264/H.265 via MediaCodec
    // - VP8/VP9 decoders
    // - All major audio codecs (AAC, MP3, Opus, Vorbis)
    // - 300+ video/audio filters
    // - Network protocol support (HTTP/HTTPS/RTMP/HLS)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}

// Skip FFmpeg build for now on JitPack due to file size issues
// The libraries should be pre-built and checked in or use Git LFS

// Hook the build process
tasks.named("preBuild").configure {
    // Check if FFmpeg libraries exist
    val ffmpegLibsExist = File(projectDir, "src/main/cpp/ffmpeg-libs/arm64-v8a/lib/libavcodec.a").exists()
    
    if (!ffmpegLibsExist && System.getenv("JITPACK") != "true") {
        // For local development only - build FFmpeg if needed
        val buildScript = File(rootDir, "build-ffmpeg.sh")
        if (buildScript.exists()) {
            println("FFmpeg libraries not found. Please run: ./build-ffmpeg.sh")
        }
    }
}

// Maven publishing configuration for JitPack
afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                
                groupId = "com.github.mustafazgs"
                artifactId = "ffmpegx-android"
                version = "1.0.0"
            }
        }
    }
}