package com.mzgs.ffmpegx

import android.content.Context
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

class FFmpegInstaller(private val context: Context) {
    companion object {
        private var certificates: Map<String, String>? = null

        @Synchronized
        internal fun environment(context: Context): Map<String, String> {
            certificates?.let { return it }
            val directory = File("/apex/com.android.conscrypt/cacerts").takeIf { it.isDirectory }
                ?: File("/system/etc/security/cacerts")
            val files = directory.listFiles()?.filter { it.isFile && it.canRead() }.orEmpty()
            val values = if (files.isEmpty()) emptyMap() else {
                val bundle = File(context.cacheDir, "ffmpeg-system-ca.pem")
                // Android's hashed filenames differ from OpenSSL's; use the same roots as a PEM bundle.
                bundle.bufferedWriter().use { output ->
                    files.forEach { output.write(it.readText()); output.newLine() }
                }
                mapOf("SSL_CERT_FILE" to bundle.absolutePath)
            }
            certificates = values
            return values
        }

        fun getFFmpegPath(context: Context): String =
            File(context.applicationInfo.nativeLibraryDir, "libffmpeg_exec.so").absolutePath
        fun getFFprobePath(context: Context): String =
            File(context.applicationInfo.nativeLibraryDir, "libffprobe_exec.so").absolutePath
        fun getDeviceABI(): String = Build.SUPPORTED_ABIS.first()
        fun isFFmpegInstalled(context: Context): Boolean =
            listOf(getFFmpegPath(context), getFFprobePath(context)).all { File(it).canExecute() }
    }

    interface InstallProgressListener {
        fun onProgressUpdate(progress: Int, message: String)
        fun onInstallComplete(success: Boolean, message: String)
    }

    suspend fun installFFmpeg(listener: InstallProgressListener? = null): Boolean = withContext(Dispatchers.IO) {
        val ready = verifyInstallation()
        val message = if (ready) "FFmpeg is ready" else
            "FFmpeg binaries missing or unusable. Build native binaries and enable jniLibs.useLegacyPackaging in the app."
        listener?.onProgressUpdate(if (ready) 100 else 0, message)
        listener?.onInstallComplete(ready, message)
        ready
    }

    fun getInstalledVersion(): String? = version(getFFmpegPath(context))
    fun verifyInstallation(): Boolean = getInstalledVersion() != null && version(getFFprobePath(context)) != null
    fun getInstalledSize(): Long = File(getFFmpegPath(context)).length() + File(getFFprobePath(context)).length()

    // Binaries belong to the APK; only an app update or uninstall can remove them.
    fun uninstallFFmpeg() { FFmpegExecutor.cancelAll() }

    private fun version(path: String): String? {
        if (!File(path).canExecute()) return null
        val process = try { ProcessBuilder(path, "-version").redirectErrorStream(true).start() }
            catch (_: Exception) { return null }
        return try {
            if (!process.waitFor(10, TimeUnit.SECONDS) || process.exitValue() != 0) null
            else process.inputStream.bufferedReader().use { it.readLine() }
        } finally { process.destroyForcibly() }
    }
}
