package com.mzgs.ffmpeglib

import android.content.Context
import android.os.Environment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.URL

class VideoDownloader(private val context: Context) {
    
    suspend fun downloadVideo(
        onProgress: (Float, String) -> Unit,
        onComplete: (String?) -> Unit
    ) = withContext(Dispatchers.IO) {
        try {
            // Use a small test video
            val videoUrl = "https://sample-videos.com/video321/mp4/720/big_buck_bunny_720p_1mb.mp4"
            val fileName = "test_video.mp4"
            
            val downloadsDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            val outputFile = File(downloadsDir, fileName)
            
            onProgress(0f, "Connecting...")
            
            val url = URL(videoUrl)
            val connection = url.openConnection()
            connection.connect()
            
            val fileLength = connection.contentLength
            val input = connection.getInputStream()
            val output = FileOutputStream(outputFile)
            
            val buffer = ByteArray(4096)
            var total: Long = 0
            var count: Int
            
            onProgress(0.1f, "Downloading video...")
            
            while (input.read(buffer).also { count = it } != -1) {
                total += count
                if (fileLength > 0) {
                    val progress = (total * 100 / fileLength).toFloat() / 100
                    onProgress(progress, "Downloaded ${total / 1024}KB / ${fileLength / 1024}KB")
                }
                output.write(buffer, 0, count)
            }
            
            output.flush()
            output.close()
            input.close()
            
            onProgress(1f, "Download complete!")
            withContext(Dispatchers.Main) {
                onComplete(outputFile.absolutePath)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            withContext(Dispatchers.Main) {
                onProgress(0f, "Download failed: ${e.message}")
                onComplete(null)
            }
        }
    }
}