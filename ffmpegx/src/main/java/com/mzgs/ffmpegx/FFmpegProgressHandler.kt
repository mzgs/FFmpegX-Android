package com.mzgs.ffmpegx

import kotlin.math.roundToInt

class FFmpegProgressHandler(
    private val totalDurationMs: Long,
    private val onProgressUpdate: (FFmpegProgress) -> Unit
) {
    
    private val progressParser = FFmpegProgressParser()
    
    fun handleOutput(output: String) {
        val progressInfo = progressParser.parseProgress(output)
        progressInfo?.let {
            val progress = FFmpegProgress(
                timeMs = it.timeMs,
                totalDurationMs = it.totalDurationMs,
                percentage = it.percentage,
                bitrate = it.bitrate,
                fps = it.fps,
                videoFrameNumber = it.frame,
                videoQuality = 0f, // Not available from standard output
                size = it.size,
                speed = it.speed
            )
            onProgressUpdate(progress)
        }
    }
    
    fun reset() {
        progressParser.reset()
    }
}

data class FFmpegProgress(
    val timeMs: Long,
    val totalDurationMs: Long,
    val percentage: Float,
    val bitrate: Double,
    val fps: Float,
    val videoFrameNumber: Int,
    val videoQuality: Float,
    val size: Long,
    val speed: Float = 1.0f
) {
    val remainingTimeMs: Long
        get() = (totalDurationMs - timeMs).coerceAtLeast(0)
    
    val percentageInt: Int
        get() = percentage.roundToInt()
    
    val formattedTime: String
        get() = formatTime(timeMs)
    
    val formattedTotalTime: String
        get() = formatTime(totalDurationMs)
    
    val formattedRemainingTime: String
        get() = formatTime(remainingTimeMs)
    
    val estimatedRemainingTimeMs: Long
        get() = if (speed > 0) {
            (remainingTimeMs / speed).toLong()
        } else {
            remainingTimeMs
        }
    
    val formattedEstimatedRemainingTime: String
        get() = formatTime(estimatedRemainingTimeMs)
    
    private fun formatTime(milliseconds: Long): String {
        val seconds = milliseconds / 1000
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        val secs = seconds % 60
        return String.format("%02d:%02d:%02d", hours, minutes, secs)
    }
}