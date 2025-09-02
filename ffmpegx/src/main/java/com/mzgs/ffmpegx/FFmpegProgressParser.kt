package com.mzgs.ffmpegx

import kotlin.math.roundToInt

class FFmpegProgressParser {
    
    private var totalDurationMs: Long = 0
    private var lastTimeMs: Long = 0
    
    fun parseProgress(line: String): ProgressInfo? {
        // Parse total duration if not yet set
        if (totalDurationMs == 0L) {
            parseDuration(line)?.let { totalDurationMs = it }
        }
        
        // Parse progress information
        return parseProgressLine(line)
    }
    
    private fun parseDuration(line: String): Long? {
        // Pattern: Duration: 00:01:30.50
        val durationRegex = Regex("Duration:\\s*(\\d{2}):(\\d{2}):(\\d{2})\\.(\\d{2})")
        val match = durationRegex.find(line)
        
        return match?.let {
            val hours = it.groupValues[1].toLong()
            val minutes = it.groupValues[2].toLong()
            val seconds = it.groupValues[3].toLong()
            val centiseconds = it.groupValues[4].toLong()
            
            (hours * 3600 + minutes * 60 + seconds) * 1000 + centiseconds * 10
        }
    }
    
    private fun parseProgressLine(line: String): ProgressInfo? {
        // FFmpeg progress line pattern:
        // frame= 1234 fps=25.0 q=28.0 size= 1234kB time=00:01:30.50 bitrate= 123.4kbits/s speed=1.23x
        
        if (!line.contains("time=")) return null
        
        val timeRegex = Regex("time=(\\d{2}):(\\d{2}):(\\d{2})\\.(\\d{2})")
        val fpsRegex = Regex("fps=\\s*([\\d.]+)")
        val speedRegex = Regex("speed=\\s*([\\d.]+)x")
        val bitrateRegex = Regex("bitrate=\\s*([\\d.]+)kbits/s")
        val sizeRegex = Regex("size=\\s*(\\d+)kB")
        val frameRegex = Regex("frame=\\s*(\\d+)")
        
        val timeMatch = timeRegex.find(line)
        
        return timeMatch?.let {
            val hours = it.groupValues[1].toLong()
            val minutes = it.groupValues[2].toLong()
            val seconds = it.groupValues[3].toLong()
            val centiseconds = it.groupValues[4].toLong()
            
            val currentTimeMs = (hours * 3600 + minutes * 60 + seconds) * 1000 + centiseconds * 10
            
            // Only return progress if time has advanced
            if (currentTimeMs > lastTimeMs) {
                lastTimeMs = currentTimeMs
                
                val fps = fpsRegex.find(line)?.groupValues?.get(1)?.toFloatOrNull() ?: 0f
                val speed = speedRegex.find(line)?.groupValues?.get(1)?.toFloatOrNull() ?: 0f
                val bitrate = bitrateRegex.find(line)?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
                val size = sizeRegex.find(line)?.groupValues?.get(1)?.toLongOrNull()?.times(1024) ?: 0L
                val frame = frameRegex.find(line)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                
                val percentage = if (totalDurationMs > 0) {
                    ((currentTimeMs.toFloat() / totalDurationMs.toFloat()) * 100).coerceIn(0f, 100f)
                } else {
                    0f
                }
                
                ProgressInfo(
                    timeMs = currentTimeMs,
                    totalDurationMs = totalDurationMs,
                    percentage = percentage,
                    fps = fps,
                    speed = speed,
                    bitrate = bitrate,
                    size = size,
                    frame = frame
                )
            } else {
                null
            }
        }
    }
    
    fun reset() {
        totalDurationMs = 0
        lastTimeMs = 0
    }
}

data class ProgressInfo(
    val timeMs: Long,
    val totalDurationMs: Long,
    val percentage: Float,
    val fps: Float,
    val speed: Float,
    val bitrate: Double,
    val size: Long,
    val frame: Int
) {
    val remainingTimeMs: Long
        get() = (totalDurationMs - timeMs).coerceAtLeast(0)
    
    val percentageInt: Int
        get() = percentage.roundToInt()
    
    val estimatedTotalTimeMs: Long
        get() = if (speed > 0 && percentage > 0) {
            (timeMs / (percentage / 100) / speed).toLong()
        } else {
            totalDurationMs
        }
    
    val estimatedRemainingTimeMs: Long
        get() = if (speed > 0) {
            (remainingTimeMs / speed).toLong()
        } else {
            remainingTimeMs
        }
    
    val formattedTime: String
        get() = formatTime(timeMs)
    
    val formattedTotalTime: String
        get() = formatTime(totalDurationMs)
    
    val formattedRemainingTime: String
        get() = formatTime(remainingTimeMs)
    
    val formattedEstimatedRemainingTime: String
        get() = formatTime(estimatedRemainingTimeMs)
    
    private fun formatTime(milliseconds: Long): String {
        val seconds = milliseconds / 1000
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        val secs = seconds % 60
        return String.format("%02d:%02d:%02d", hours, minutes, secs)
    }
    
    val formattedSize: String
        get() = FFmpegUtils.formatFileSize(size)
    
    val formattedBitrate: String
        get() = "${bitrate.toInt()} kbps"
    
    val formattedSpeed: String
        get() = "${speed}x"
    
    val formattedFps: String
        get() = "$fps fps"
}