package com.mzgs.ffmpegx

/** Compatibility helper for MP3 extraction. The bundled LAME encoder needs no fallback. */
class FFmpegAudioExtractor(private val ffmpegHelper: FFmpegHelper) {
    suspend fun extractAudioWithFallback(
        inputPath: String,
        outputPath: String,
        callback: FFmpegHelper.FFmpegCallback? = null
    ): Boolean = ffmpegHelper.execute(FFmpegCommandBuilder().input(inputPath).overwriteOutput()
        .noVideo().map("0:a:0").audioCodec("libmp3lame").audioBitrate("192k")
        .output(outputPath).buildAsList(), callback)
}
