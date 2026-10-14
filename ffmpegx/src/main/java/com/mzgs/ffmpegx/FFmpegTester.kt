package com.mzgs.ffmpegx

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object FFmpegTester {
    suspend fun testFFmpeg(context: Context): TestResult = withContext(Dispatchers.IO) {
        val installer = FFmpegInstaller(context)
        val success = installer.verifyInstallation()
        TestResult(success, if (success) "FFmpeg and ffprobe are working" else "Native programs are missing or unusable",
            installer.getInstalledVersion())
    }
    data class TestResult(val success: Boolean, val message: String, val version: String?)
}
