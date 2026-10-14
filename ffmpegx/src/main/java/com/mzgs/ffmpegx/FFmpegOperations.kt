package com.mzgs.ffmpegx

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class FFmpegOperations(private val context: Context) {
    
    private val ffmpegHelper = FFmpegHelper(context)
    
    suspend fun compressVideo(
        inputPath: String,
        outputPath: String,
        quality: VideoQuality = VideoQuality.MEDIUM,
        callback: FFmpegHelper.FFmpegCallback? = null
    ): Boolean {
        val command = FFmpegCommandBuilder()
            .input(inputPath).overwriteOutput()
            .videoCodec("libx264").preset(quality.preset).crf(quality.crf)
            .customOption("-pix_fmt", "yuv420p")
            .audioCodec("aac").audioBitrate("128k")
            .output(outputPath).build()
        return ffmpegHelper.execute(command, callback)
    }

    suspend fun extractAudio(
        inputPath: String,
        outputPath: String,
        audioFormat: AudioFormat = AudioFormat.MP3,
        callback: FFmpegHelper.FFmpegCallback? = null
    ): Boolean {
        val command = FFmpegCommandBuilder().input(inputPath).overwriteOutput()
            .noVideo().map("0:a:0").audioCodec(audioFormat.codec)
            .audioBitrate(audioFormat.bitrate).output(outputPath).build()
        return ffmpegHelper.execute(command, callback)
    }

    suspend fun convertVideo(
        inputPath: String,
        outputPath: String,
        outputFormat: VideoFormat,
        callback: FFmpegHelper.FFmpegCallback? = null
    ): Boolean {
        val command = FFmpegCommandBuilder()
            .input(inputPath)
            .overwriteOutput()
            .videoCodec(outputFormat.videoCodec)
            .audioCodec(outputFormat.audioCodec)
            .customOption("-f", if (outputFormat == VideoFormat.MKV) "matroska" else outputFormat.extension)
            .output(outputPath)
            .build()
        
        return ffmpegHelper.execute(command, callback)
    }
    
    suspend fun trimVideo(
        inputPath: String,
        outputPath: String,
        startTimeSeconds: Double,
        durationSeconds: Double,
        callback: FFmpegHelper.FFmpegCallback? = null
    ): Boolean {
        require(startTimeSeconds.isFinite() && startTimeSeconds >= 0)
        require(durationSeconds.isFinite() && durationSeconds > 0)
        val command = FFmpegCommandBuilder()
            .input(inputPath)
            .overwriteOutput()
            .startTime(startTimeSeconds)
            .duration(durationSeconds)
            .copyAllCodecs()
            .output(outputPath)
            .build()
        
        return ffmpegHelper.execute(command, callback)
    }
    
    suspend fun mergeVideos(
        videoPaths: List<String>,
        outputPath: String,
        callback: FFmpegHelper.FFmpegCallback? = null
    ): Boolean = withContext(Dispatchers.IO) {
        require(videoPaths.isNotEmpty()) { "At least one input video is required" }
        require(videoPaths.none { '\n' in it || '\r' in it }) { "Newlines in concat paths are unsupported" }
        val listFile = File.createTempFile("ffmpeg_list", ".txt", context.cacheDir)
        try {
            listFile.writeText(videoPaths.joinToString("\n") { "file '${File(it).absolutePath.replace("'", "'\\''")}'" })
            
            val command = FFmpegCommandBuilder()
                .customOption("-f", "concat")
                .customOption("-safe", "0")
                .input(listFile.absolutePath)
                .overwriteOutput()
                .copyAllCodecs()
                .output(outputPath)
                .build()
            
            ffmpegHelper.execute(command, callback)
        } finally {
            listFile.delete()
        }
    }
    
    suspend fun resizeVideo(
        inputPath: String,
        outputPath: String,
        width: Int,
        height: Int,
        maintainAspectRatio: Boolean = true,
        callback: FFmpegHelper.FFmpegCallback? = null
    ): Boolean {
        require(width > 0 && height > 0 && width % 2 == 0 && height % 2 == 0) {
            "H.264 output dimensions must be positive and even"
        }
        val scaleFilter = if (maintainAspectRatio) {
            "scale=$width:$height:force_original_aspect_ratio=decrease,pad=$width:$height:(ow-iw)/2:(oh-ih)/2"
        } else {
            "scale=$width:$height"
        }
        
        val command = FFmpegCommandBuilder()
            .input(inputPath)
            .overwriteOutput()
            .videoFilter(scaleFilter)
            .videoCodec("libx264")
            .audioCodec("copy")
            .output(outputPath)
            .build()
        
        return ffmpegHelper.execute(command, callback)
    }
    
    suspend fun rotateVideo(
        inputPath: String,
        outputPath: String,
        degrees: Int,
        callback: FFmpegHelper.FFmpegCallback? = null
    ): Boolean {
        val transposeValue = when (degrees) {
            90 -> "1"
            180 -> "2,transpose=2"
            270 -> "2"
            else -> return false
        }
        
        val command = FFmpegCommandBuilder()
            .input(inputPath)
            .overwriteOutput()
            .videoFilter("transpose=$transposeValue").videoCodec("libx264")
            .audioCodec("copy")
            .output(outputPath)
            .build()
        
        return ffmpegHelper.execute(command, callback)
    }
    
    suspend fun addWatermark(
        videoPath: String,
        watermarkPath: String,
        outputPath: String,
        position: WatermarkPosition = WatermarkPosition.BOTTOM_RIGHT,
        callback: FFmpegHelper.FFmpegCallback? = null
    ): Boolean {
        val overlay = when (position) {
            WatermarkPosition.TOP_LEFT -> "10:10"
            WatermarkPosition.TOP_RIGHT -> "main_w-overlay_w-10:10"
            WatermarkPosition.BOTTOM_LEFT -> "10:main_h-overlay_h-10"
            WatermarkPosition.BOTTOM_RIGHT -> "main_w-overlay_w-10:main_h-overlay_h-10"
            WatermarkPosition.CENTER -> "(main_w-overlay_w)/2:(main_h-overlay_h)/2"
        }
        
        val command = FFmpegCommandBuilder()
            .input(videoPath)
            .input(watermarkPath)
            .overwriteOutput()
            .complexFilter("[0:v][1:v]overlay=$overlay").videoCodec("libx264")
            .audioCodec("copy")
            .output(outputPath)
            .build()
        
        return ffmpegHelper.execute(command, callback)
    }
    
    suspend fun extractFrames(
        videoPath: String,
        outputPattern: String,
        fps: Int = 1,
        callback: FFmpegHelper.FFmpegCallback? = null
    ): Boolean {
        require(fps > 0) { "Frame rate must be positive" }
        val command = FFmpegCommandBuilder()
            .input(videoPath).overwriteOutput()
            .videoFilter("fps=$fps")
            .output(outputPattern)
            .build()
        
        return ffmpegHelper.execute(command, callback)
    }
    
    suspend fun createGif(
        inputPath: String,
        outputPath: String,
        width: Int = 320,
        fps: Int = 10,
        startTime: Double? = null,
        duration: Double? = null,
        callback: FFmpegHelper.FFmpegCallback? = null
    ): Boolean {
        require(width > 0 && fps > 0) { "GIF dimensions and frame rate must be positive" }
        require(startTime == null || startTime.isFinite() && startTime >= 0)
        require(duration == null || duration.isFinite() && duration > 0)
        val builder = FFmpegCommandBuilder().overwriteOutput()

        startTime?.let { builder.startTime(it) }
        duration?.let { builder.duration(it) }
        
        builder.input(inputPath)
            .videoFilter("fps=$fps,scale=$width:-1:flags=lanczos")
            .output(outputPath)
        
        return ffmpegHelper.execute(builder.build(), callback)
    }
    
    suspend fun changeVideoSpeed(
        inputPath: String,
        outputPath: String,
        speed: Float,
        adjustAudio: Boolean = true,
        callback: FFmpegHelper.FFmpegCallback? = null
    ): Boolean {
        require(speed.isFinite() && speed > 0) { "Speed must be finite and positive" }
        val videoFilter = "setpts=${1.0 / speed}*PTS"
        val tempo = mutableListOf<String>()
        var remaining = speed.toDouble()
        while (remaining < 0.5) { tempo.add("atempo=0.5"); remaining /= 0.5 }
        while (remaining > 2.0) { tempo.add("atempo=2.0"); remaining /= 2.0 }
        tempo.add("atempo=$remaining")
        val audioFilter = if (adjustAudio) tempo.joinToString(",") else null
        
        val builder = FFmpegCommandBuilder()
            .input(inputPath)
            .overwriteOutput()
            .videoFilter(videoFilter).videoCodec("libx264")
        
        audioFilter?.let { builder.audioFilter(it) }
        
        builder.output(outputPath)
        
        return ffmpegHelper.execute(builder.build(), callback)
    }
    
    suspend fun addSubtitles(
        videoPath: String,
        subtitlePath: String,
        outputPath: String,
        callback: FFmpegHelper.FFmpegCallback? = null
    ): Boolean {
        val command = FFmpegCommandBuilder()
            .input(videoPath)
            .input(subtitlePath)
            .overwriteOutput()
            .videoCodec("copy")
            .audioCodec("copy")
            .map("0:v:0").map("0:a?").map("1:s:0")
            .customOption("-c:s", if (File(outputPath).extension.lowercase() in listOf("mp4", "mov", "m4v")) "mov_text" else "srt")
            .output(outputPath)
            .build()
        
        return ffmpegHelper.execute(command, callback)
    }
    
    suspend fun reverseVideo(
        inputPath: String,
        outputPath: String,
        reverseAudio: Boolean = true,
        callback: FFmpegHelper.FFmpegCallback? = null
    ): Boolean {
        val builder = FFmpegCommandBuilder()
            .input(inputPath)
            .overwriteOutput()
            .videoFilter("reverse").videoCodec("libx264")
        
        if (reverseAudio) {
            builder.audioFilter("areverse")
        }
        
        builder.output(outputPath)
        
        return ffmpegHelper.execute(builder.build(), callback)
    }
    
    fun isVideoFile(path: String): Boolean = ffmpegHelper.isVideoFile(path)
    fun isAudioFile(path: String): Boolean = ffmpegHelper.isAudioFile(path)
    fun getVideoDuration(path: String): Long = ffmpegHelper.getVideoDuration(path)
    fun getVideoResolution(path: String): Pair<Int, Int>? = ffmpegHelper.getVideoResolution(path)
    fun getVideoCodec(path: String): String? = ffmpegHelper.getVideoCodec(path)

    enum class VideoQuality(val preset: String, val crf: Int) {
        LOW("ultrafast", 28),
        MEDIUM("medium", 23),
        HIGH("slow", 18),
        VERY_HIGH("veryslow", 15)
    }
    
    enum class AudioFormat(val codec: String, val bitrate: String, val extension: String) {
        MP3("libmp3lame", "192k", "mp3"),
        AAC("aac", "192k", "aac"),
        WAV("pcm_s16le", "1411k", "wav"),
        FLAC("flac", "1411k", "flac"),
        OGG("libvorbis", "192k", "ogg")
    }
    
    enum class VideoFormat(val videoCodec: String, val audioCodec: String, val extension: String) {
        MP4("libx264", "aac", "mp4"),
        AVI("mpeg4", "libmp3lame", "avi"),
        MKV("libx264", "aac", "mkv"),
        MOV("libx264", "aac", "mov"),
        WEBM("libvpx", "libvorbis", "webm"),
        FLV("flv", "libmp3lame", "flv")
    }
    
    enum class WatermarkPosition {
        TOP_LEFT,
        TOP_RIGHT,
        BOTTOM_LEFT,
        BOTTOM_RIGHT,
        CENTER
    }
}