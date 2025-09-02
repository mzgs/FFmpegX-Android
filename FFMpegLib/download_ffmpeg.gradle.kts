import java.net.URI
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream
import java.net.HttpURLConnection

// Task to download FFmpeg binaries
tasks.register("downloadFFmpegBinaries") {
    val assetsDir = File(projectDir, "src/main/assets/ffmpeg")
    val markerFile = File(assetsDir, ".downloaded")
    
    // Skip if already downloaded
    onlyIf {
        !markerFile.exists()
    }
    
    doLast {
        println("============================================================")
        println("Downloading FFmpeg binaries...")
        println("============================================================")
        
        var downloadSuccess = true
        
        // Create directories
        val architectures = listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
        architectures.forEach { arch ->
            val archDir = File(assetsDir, arch)
            archDir.mkdirs()
        }
        
        // Try mobile-ffmpeg source
        downloadSuccess = downloadFromMobileFFmpeg(assetsDir)
        
        if (!downloadSuccess) {
            // Try GitHub alternative
            println("Trying alternative source...")
            downloadSuccess = downloadFromGitHub(assetsDir)
        }
        
        if (downloadSuccess) {
            // Create marker file to indicate successful download
            markerFile.writeText("Downloaded on: ${java.util.Date()}")
            println("\n============================================================")
            println("✓ FFmpeg binaries ready!")
            println("============================================================")
        } else {
            println("\n============================================================")
            println("⚠️  Automatic download failed.")
            println("   Creating placeholder binaries...")
            println("============================================================")
            
            // Create placeholder binaries so build doesn't fail
            createPlaceholderBinaries(assetsDir)
            
            println("\n📦 MANUAL INSTALLATION REQUIRED:")
            println("------------------------------------------------------------")
            println("Please download FFmpeg binaries manually:")
            println("")
            println("Option 1: Use mobile-ffmpeg (Recommended)")
            println("  1. Visit: https://github.com/tanersener/mobile-ffmpeg/releases")
            println("  2. Download: mobile-ffmpeg-full-4.4.LTS.zip")
            println("  3. Extract and copy binaries to:")
            println("     src/main/assets/ffmpeg/[architecture]/")
            println("")
            println("Option 2: Build from source")
            println("  Run: ./build_ffmpeg_android.sh")
            println("")
            println("Option 3: Use Docker")
            println("  Run: docker run -v \$(pwd):/build writingminds/ffmpeg-android")
            println("============================================================")
        }
    }
}

fun downloadFromMobileFFmpeg(assetsDir: File): Boolean {
    return try {
        println("Trying Mobile-FFmpeg LTS source...")
        
        // Using mobile-ffmpeg 4.4.LTS which is stable and has binaries
        val version = "4.4.LTS"
        val baseUrl = "https://github.com/tanersener/mobile-ffmpeg/releases/download/v$version/"
        
        val packages = mapOf(
            "arm64-v8a" to "mobile-ffmpeg-full-$version-android-arm64-v8a.zip",
            "armeabi-v7a" to "mobile-ffmpeg-full-$version-android-arm-v7a.zip",
            "x86" to "mobile-ffmpeg-full-$version-android-x86.zip",
            "x86_64" to "mobile-ffmpeg-full-$version-android-x86_64.zip"
        )
        
        var allSuccess = true
        
        packages.forEach { (arch, fileName) ->
            val archDir = File(assetsDir, arch)
            val ffmpegFile = File(archDir, "ffmpeg")
            val ffprobeFile = File(archDir, "ffprobe")
            
            if (!ffmpegFile.exists() || !ffprobeFile.exists()) {
                println("Downloading mobile-ffmpeg for $arch...")
                
                try {
                    val uri = URI("$baseUrl$fileName")
                    val tempZip = File(archDir, "temp.zip")
                    
                    // Download with progress
                    downloadFile(uri, tempZip)
                    
                    // Extract binaries from zip
                    if (extractMobileFFmpeg(tempZip, archDir)) {
                        println("✓ Extracted $arch")
                    } else {
                        // If extraction fails, create placeholders
                        createPlaceholder(ffmpegFile, arch)
                        createPlaceholder(ffprobeFile, arch)
                        allSuccess = false
                    }
                    
                    tempZip.delete()
                } catch (e: Exception) {
                    println("✗ Failed $arch: ${e.message}")
                    createPlaceholder(ffmpegFile, arch)
                    createPlaceholder(ffprobeFile, arch)
                    allSuccess = false
                }
            }
        }
        
        allSuccess
    } catch (e: Exception) {
        false
    }
}

fun extractMobileFFmpeg(zipFile: File, targetDir: File): Boolean {
    return try {
        ZipInputStream(zipFile.inputStream()).use { zis ->
            var entry = zis.nextEntry
            var foundFFmpeg = false
            var foundFFprobe = false
            
            while (entry != null) {
                when {
                    entry.name.endsWith("/ffmpeg") -> {
                        val targetFile = File(targetDir, "ffmpeg")
                        FileOutputStream(targetFile).use { output ->
                            zis.copyTo(output)
                        }
                        targetFile.setExecutable(true)
                        foundFFmpeg = true
                    }
                    entry.name.endsWith("/ffprobe") -> {
                        val targetFile = File(targetDir, "ffprobe")
                        FileOutputStream(targetFile).use { output ->
                            zis.copyTo(output)
                        }
                        targetFile.setExecutable(true)
                        foundFFprobe = true
                    }
                }
                
                zis.closeEntry()
                entry = zis.nextEntry
            }
            
            foundFFmpeg && foundFFprobe
        }
    } catch (e: Exception) {
        false
    }
}

fun downloadFromGitHub(assetsDir: File): Boolean {
    // Alternative: Direct binary downloads from various sources
    // These are collected from working Android FFmpeg builds
    val directUrls = mapOf(
        "armeabi-v7a" to mapOf(
            "ffmpeg" to "https://github.com/WritingMinds/ffmpeg-android/raw/master/android/armeabi-v7a/bin/ffmpeg",
            "ffprobe" to "https://github.com/WritingMinds/ffmpeg-android/raw/master/android/armeabi-v7a/bin/ffprobe"
        )
    )
    
    var success = false
    directUrls.forEach { (arch, binaries) ->
        val archDir = File(assetsDir, arch)
        
        binaries.forEach { (name, urlString) ->
            val targetFile = File(archDir, name)
            if (!targetFile.exists()) {
                try {
                    println("Downloading $name for $arch from GitHub...")
                    val uri = URI(urlString)
                    downloadFile(uri, targetFile)
                    targetFile.setExecutable(true)
                    success = true
                } catch (e: Exception) {
                    createPlaceholder(targetFile, arch)
                }
            }
        }
    }
    
    return success
}

fun downloadFile(uri: URI, outputFile: File) {
    val connection = uri.toURL().openConnection() as HttpURLConnection
    connection.instanceFollowRedirects = true
    connection.connectTimeout = 10000
    connection.readTimeout = 10000
    
    try {
        connection.connect()
        
        if (connection.responseCode != HttpURLConnection.HTTP_OK) {
            throw Exception("HTTP ${connection.responseCode}")
        }
        
        val totalSize = connection.contentLength
        var downloadedSize = 0
        
        connection.inputStream.use { input ->
            FileOutputStream(outputFile).use { output ->
                val buffer = ByteArray(8192)
                var bytesRead: Int
                
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    output.write(buffer, 0, bytesRead)
                    downloadedSize += bytesRead
                    
                    if (totalSize > 0 && downloadedSize % (1024 * 100) == 0) {
                        val progress = (downloadedSize * 100 / totalSize)
                        print("\rProgress: $progress%")
                    }
                }
                println("\r✓ Downloaded (${downloadedSize / 1024} KB)")
            }
        }
    } finally {
        connection.disconnect()
    }
}

fun createPlaceholder(file: File, arch: String) {
    file.parentFile?.mkdirs()
    file.writeText("#!/system/bin/sh\necho 'FFmpeg placeholder for $arch - Please install actual binary'\nexit 1")
    file.setExecutable(true)
}

fun createPlaceholderBinaries(assetsDir: File) {
    val architectures = listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
    val binaries = listOf("ffmpeg", "ffprobe")
    
    architectures.forEach { arch ->
        val archDir = File(assetsDir, arch)
        archDir.mkdirs()
        
        binaries.forEach { binary ->
            val file = File(archDir, binary)
            if (!file.exists()) {
                createPlaceholder(file, arch)
                println("Created placeholder: $arch/$binary")
            }
        }
    }
}

// Task to clean downloaded binaries
tasks.register("cleanFFmpegBinaries") {
    doLast {
        val assetsDir = File(projectDir, "src/main/assets/ffmpeg")
        if (assetsDir.exists()) {
            assetsDir.deleteRecursively()
            println("✓ Cleaned FFmpeg binaries")
        }
    }
}

// Task to verify FFmpeg binaries
tasks.register("verifyFFmpegBinaries") {
    doLast {
        val assetsDir = File(projectDir, "src/main/assets/ffmpeg")
        val architectures = listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
        val binaries = listOf("ffmpeg", "ffprobe")
        
        println("\n============================================================")
        println("FFmpeg Binary Verification")
        println("============================================================")
        
        var allPresent = true
        var hasActualBinaries = false
        var totalSize = 0L
        
        architectures.forEach { arch ->
            println("\n$arch:")
            binaries.forEach { binary ->
                val file = File(assetsDir, "$arch/$binary")
                
                if (file.exists()) {
                    val sizeKB = file.length() / 1024
                    totalSize += file.length()
                    
                    // Check if it's a placeholder
                    val content = file.readText()
                    if (content.contains("placeholder")) {
                        println("  ⚠️  $binary (placeholder)")
                    } else {
                        println("  ✓ $binary (${sizeKB} KB)")
                        hasActualBinaries = true
                    }
                } else {
                    println("  ✗ $binary (missing)")
                    allPresent = false
                }
            }
        }
        
        println("\n------------------------------------------------------------")
        if (hasActualBinaries) {
            println("✓ FFmpeg binaries installed")
            println("Total size: ${totalSize / 1024 / 1024} MB")
        } else if (allPresent) {
            println("⚠️  Only placeholder binaries present")
            println("Run './gradlew downloadFFmpegBinaries' to download actual binaries")
        } else {
            println("⚠️  Some binaries are missing")
            println("Run './gradlew downloadFFmpegBinaries' to set them up")
        }
        println("============================================================\n")
    }
}