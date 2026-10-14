package com.mzgs.ffmpegx

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.FileOutputStream

object FFmpegUtils {
    
    fun getFileExtension(path: String): String {
        return File(path).extension
    }

    fun changeFileExtension(path: String, newExtension: String): String {
        val file = File(path)
        return File(file.parentFile, "${file.nameWithoutExtension}.${newExtension.removePrefix(".")}").path
    }

    fun generateOutputPath(inputPath: String, suffix: String, newExtension: String? = null): String {
        val file = File(inputPath)
        val nameWithoutExtension = file.nameWithoutExtension
        val extension = newExtension ?: file.extension
        val parent = file.parent ?: ""
        return File(parent, "${nameWithoutExtension}_$suffix.$extension").absolutePath
    }
    
    fun createTempFile(context: Context, prefix: String, extension: String): File {
        val tempDir = File(context.cacheDir, "ffmpeg_temp")
        if (!tempDir.exists()) {
            tempDir.mkdirs()
        }
        return File.createTempFile(prefix, ".$extension", tempDir)
    }
    
    fun copyUriToFile(context: Context, uri: Uri, outputFile: File): Boolean {
        var temporary: File? = null
        return try {
            val inputStream = context.contentResolver.openInputStream(uri) ?: return false
            inputStream.use { input ->
                val file = File.createTempFile("ffmpeg_uri", ".tmp", outputFile.absoluteFile.parentFile)
                temporary = file
                FileOutputStream(file).use { output -> input.copyTo(output) }
            }
            temporary?.renameTo(outputFile) == true
        } catch (_: Exception) {
            false
        } finally { temporary?.delete() }
    }

    fun deleteFile(path: String): Boolean {
        return try {
            File(path).delete()
        } catch (e: Exception) {
            false
        }
    }
    
    fun fileExists(path: String): Boolean {
        return File(path).exists()
    }
    
    fun getFileSize(path: String): Long {
        return File(path).length()
    }
    
    fun getFileSizeInMB(path: String): Double {
        return getFileSize(path) / (1024.0 * 1024.0)
    }
    
    fun formatFileSize(sizeInBytes: Long): String {
        val kb = sizeInBytes / 1024.0
        val mb = kb / 1024.0
        val gb = mb / 1024.0
        
        return when {
            gb >= 1.0 -> String.format("%.2f GB", gb)
            mb >= 1.0 -> String.format("%.2f MB", mb)
            kb >= 1.0 -> String.format("%.2f KB", kb)
            else -> "$sizeInBytes B"
        }
    }
    
    fun formatDuration(milliseconds: Long): String {
        val seconds = milliseconds / 1000
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        val secs = seconds % 60
        
        return if (hours > 0) {
            String.format("%02d:%02d:%02d", hours, minutes, secs)
        } else {
            String.format("%02d:%02d", minutes, secs)
        }
    }
    
    fun isValidVideoFile(path: String): Boolean {
        val validExtensions = listOf("mp4", "avi", "mkv", "mov", "wmv", "flv", "webm", "m4v", "mpg", "mpeg", "3gp")
        val extension = getFileExtension(path).lowercase()
        return validExtensions.contains(extension) && fileExists(path)
    }
    
    fun isValidAudioFile(path: String): Boolean {
        val validExtensions = listOf("mp3", "aac", "wav", "flac", "ogg", "m4a", "wma", "opus", "amr")
        val extension = getFileExtension(path).lowercase()
        return validExtensions.contains(extension) && fileExists(path)
    }
    
    fun isValidImageFile(path: String): Boolean {
        val validExtensions = listOf("jpg", "jpeg", "png", "gif", "bmp", "webp", "tiff")
        val extension = getFileExtension(path).lowercase()
        return validExtensions.contains(extension) && fileExists(path)
    }
    
    fun cleanupTempFiles(context: Context) {
        val tempDir = File(context.cacheDir, "ffmpeg_temp")
        if (tempDir.exists()) {
            tempDir.listFiles()?.forEach { it.delete() }
        }
    }
}