package com.mzgs.ffmpeglib

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Main entry point for FFmpeg library
 * Handles initialization and provides access to all FFmpeg functionality
 */
class FFmpeg private constructor(private val context: Context) {
    
    private val installer = FFmpegInstaller(context)
    private val helper = FFmpegHelper(context)
    private val operations = FFmpegOperations(context)
    private val sessionManager = FFmpegSessionManager()
    
    companion object {
        private const val TAG = "FFmpeg"
        @Volatile
        private var INSTANCE: FFmpeg? = null
        
        /**
         * Initialize FFmpeg library with context
         * This should be called once in Application.onCreate() or Activity.onCreate()
         */
        fun initialize(context: Context): FFmpeg {
            Log.d(TAG, "Initializing FFmpeg with context: ${context.packageName}")
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: FFmpeg(context.applicationContext).also { 
                    INSTANCE = it
                    Log.i(TAG, "FFmpeg instance created successfully")
                }
            }
        }
        
        /**
         * Get FFmpeg instance (must be initialized first)
         */
        fun getInstance(): FFmpeg {
            return INSTANCE ?: throw IllegalStateException(
                "FFmpeg not initialized. Call FFmpeg.initialize(context) first."
            )
        }
        
        /**
         * Check if FFmpeg is initialized
         */
        fun isInitialized(): Boolean = INSTANCE != null
    }
    
    /**
     * Install FFmpeg binaries from assets
     * This is automatically called on first use, but can be called manually
     */
    suspend fun install(listener: FFmpegInstaller.InstallProgressListener? = null): Boolean {
        Log.d(TAG, "Starting FFmpeg installation")
        val result = installer.installFFmpeg(listener)
        Log.i(TAG, "FFmpeg installation result: $result")
        return result
    }
    
    /**
     * Check if FFmpeg is installed and ready to use
     */
    fun isInstalled(): Boolean {
        val installed = FFmpegInstaller.isFFmpegInstalled(context)
        Log.d(TAG, "FFmpeg installation status: $installed")
        return installed
    }
    
    /**
     * Ensure FFmpeg is installed before executing commands
     */
    private suspend fun ensureInstalled() {
        if (!isInstalled()) {
            Log.w(TAG, "FFmpeg not installed, starting installation...")
            val success = install()
            if (!success) {
                Log.e(TAG, "Failed to install FFmpeg!")
                throw FFmpegException.InstallationException("FFmpeg installation failed")
            }
        } else {
            Log.d(TAG, "FFmpeg already installed")
        }
    }
    
    /**
     * Execute raw FFmpeg command
     * Uses our custom implementation with JNI for Android 10+ support
     */
    suspend fun execute(
        command: String,
        callback: FFmpegHelper.FFmpegCallback? = null
    ): Boolean = withContext(Dispatchers.IO) {
        Log.d(TAG, "Executing FFmpeg command: $command")
        try {
            ensureInstalled()
            val result = helper.execute(command, callback)
            Log.i(TAG, "FFmpeg command execution result: $result")
            return@withContext result
        } catch (e: Exception) {
            Log.e(TAG, "Error executing FFmpeg command", e)
            throw e
        }
    }
    
    /**
     * Execute FFmpeg command asynchronously (returns session ID)
     */
    suspend fun executeAsync(
        command: String,
        callback: FFmpegHelper.FFmpegCallback? = null
    ): Long = withContext(Dispatchers.IO) {
        Log.d(TAG, "Executing FFmpeg command async: $command")
        try {
            ensureInstalled()
            val sessionId = helper.executeAsync(command, callback)
            Log.i(TAG, "FFmpeg async command started with session ID: $sessionId")
            sessionManager.addSession(sessionId, "FFmpeg Command", command)
            sessionId
        } catch (e: Exception) {
            Log.e(TAG, "Error executing FFmpeg command async", e)
            throw e
        }
    }
    
    /**
     * Build FFmpeg command using fluent API
     */
    fun commandBuilder(): FFmpegCommandBuilder {
        return FFmpegCommandBuilder()
    }
    
    /**
     * Get media information for a file
     */
    suspend fun getMediaInfo(path: String): MediaInformation? = withContext(Dispatchers.IO) {
        ensureInstalled()
        helper.getMediaInformationAsync(path)
    }
    
    /**
     * Cancel specific FFmpeg session
     */
    fun cancel(sessionId: Long): Boolean {
        return sessionManager.cancelSession(sessionId)
    }
    
    /**
     * Cancel all running FFmpeg sessions
     */
    fun cancelAll() {
        sessionManager.cancelAllSessions()
    }
    
    /**
     * Get session manager for advanced session control
     */
    fun sessions(): FFmpegSessionManager = sessionManager
    
    /**
     * Get operations helper for common tasks
     */
    fun operations(): FFmpegOperations = operations
    
    /**
     * Get FFmpeg version
     */
    fun getVersion(): String? {
        return installer.getInstalledVersion()
    }
    
    /**
     * Verify FFmpeg installation
     */
    fun verifyInstallation(): Boolean {
        Log.d(TAG, "Verifying FFmpeg installation...")
        val verified = installer.verifyInstallation()
        Log.i(TAG, "FFmpeg installation verification result: $verified")
        return verified
    }
    
    /**
     * Get installed binary size
     */
    fun getInstalledSize(): Long {
        return installer.getInstalledSize()
    }
    
    /**
     * Uninstall FFmpeg binaries
     */
    fun uninstall() {
        installer.uninstallFFmpeg()
    }
    
    /**
     * Test if FFmpeg is working
     */
    suspend fun testFFmpeg(): Boolean = withContext(Dispatchers.IO) {
        Log.d(TAG, "Testing FFmpeg functionality...")
        val result = FFmpegTester.testFFmpeg(context)
        Log.i(TAG, "FFmpeg test result: ${result.success} - ${result.message}")
        if (result.version != null) {
            Log.i(TAG, "FFmpeg version: ${result.version}")
        }
        return@withContext result.success
    }
}

/**
 * Extension function for easy access
 */
suspend fun Context.ffmpeg(): FFmpeg {
    val ffmpeg = if (FFmpeg.isInitialized()) {
        FFmpeg.getInstance()
    } else {
        FFmpeg.initialize(this)
    }
    
    // Auto-install if needed
    if (!ffmpeg.isInstalled()) {
        ffmpeg.install()
    }
    
    return ffmpeg
}