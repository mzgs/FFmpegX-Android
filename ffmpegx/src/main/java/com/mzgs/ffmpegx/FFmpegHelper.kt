package com.mzgs.ffmpegx

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

class FFmpegHelper(private val context: Context) {
    companion object {
        fun cancelAllTasks() { FFmpegExecutor.cancelAll() }
        fun cancelTask(sessionId: Long): Boolean = FFmpegExecutor.cancel(sessionId)
    }

    interface FFmpegCallback {
        fun onStart()
        /** Progress is a fraction between 0 and 1; time is in milliseconds. */
        fun onProgress(progress: Float, time: Long)
        fun onOutput(line: String)
        fun onSuccess(output: String?)
        fun onFailure(error: String)
        fun onFinish()
    }

    suspend fun execute(command: String, callback: FFmpegCallback? = null): Boolean =
        execute(FFmpegArguments.parse(command), callback)

    suspend fun execute(arguments: List<String>, callback: FFmpegCallback? = null): Boolean =
        withContext(Dispatchers.IO) {
            suspendCancellableCoroutine { continuation ->
                val id = start(arguments, callback) { code ->
                    if (continuation.isActive) continuation.resume(code == 0)
                }
                continuation.invokeOnCancellation { FFmpegExecutor.cancel(id) }
            }
        }

    fun executeAsync(command: String, callback: FFmpegCallback? = null): Long =
        start(FFmpegArguments.parse(command), callback)

    private fun start(arguments: List<String>, callback: FFmpegCallback?, complete: (Int) -> Unit = {}): Long {
        callback?.onStart()
        val output = StringBuilder()
        val parser = FFmpegProgressParser()
        return FFmpegExecutor.execute(FFmpegInstaller.getFFmpegPath(context), listOf("-nostdin") + arguments,
            object : FFmpegExecutor.ExecutorCallback {
                override fun onOutput(line: String) {
                    output.append(line).append('\n')
                    // shortcut: retain the last 64 KiB of logs; use onOutput for complete logs.
                    if (output.length > 65536) output.delete(0, output.length - 65536)
                    parser.parseProgress(line)?.let { callback?.onProgress(it.percentage, it.timeMs) }
                    callback?.onOutput(line)
                }
                override fun onError(error: String) { output.append(error).append('\n') }
                override fun onComplete(exitCode: Int) {
                    try {
                        if (exitCode == 0) {
                            callback?.onProgress(1f, parser.lastTimeMs)
                            callback?.onSuccess(output.toString())
                        } else callback?.onFailure("Process exited with code $exitCode:\n$output")
                    } finally {
                        try { callback?.onFinish() } finally { complete(exitCode) }
                    }
                }
            }, FFmpegInstaller.environment(context))
    }

    fun getMediaInformation(path: String): MediaInformation? {
        val output = StringBuilder()
        val done = CountDownLatch(1)
        var code = -1
        val id = FFmpegExecutor.execute(FFmpegInstaller.getFFprobePath(context),
            listOf("-v", "error", "-show_format", "-show_streams", "-of", "json", "-i", path),
            object : FFmpegExecutor.ExecutorCallback {
                override fun onOutput(line: String) { output.append(line).append('\n') }
                override fun onError(error: String) { }
                override fun onComplete(exitCode: Int) { code = exitCode; done.countDown() }
            }, FFmpegInstaller.environment(context))
        try {
            if (!done.await(30, TimeUnit.SECONDS)) { FFmpegExecutor.cancel(id); return null }
        } catch (_: InterruptedException) {
            FFmpegExecutor.cancel(id)
            Thread.currentThread().interrupt()
            return null
        }
        if (code != 0) return null
        return try { parseMediaInformation(output.toString()) } catch (_: Exception) { null }
    }

    suspend fun getMediaInformationAsync(path: String): MediaInformation? = withContext(Dispatchers.IO) {
        // Keep blocking callers compatible, but let coroutine cancellation stop ffprobe.
        suspendCancellableCoroutine { continuation ->
            val output = StringBuilder()
            val id = FFmpegExecutor.execute(FFmpegInstaller.getFFprobePath(context),
                listOf("-v", "error", "-show_format", "-show_streams", "-of", "json", "-i", path),
                object : FFmpegExecutor.ExecutorCallback {
                    override fun onOutput(line: String) { output.append(line).append('\n') }
                    override fun onError(error: String) { }
                    override fun onComplete(exitCode: Int) {
                        val info = if (exitCode == 0) try { parseMediaInformation(output.toString()) }
                            catch (_: Exception) { null } else null
                        if (continuation.isActive) continuation.resume(info)
                    }
                }, FFmpegInstaller.environment(context))
            continuation.invokeOnCancellation { FFmpegExecutor.cancel(id) }
        }
    }

    internal fun parseMediaInformation(output: String): MediaInformation {
        val json = JSONObject(output)
        val format = json.optJSONObject("format")
        val info = MediaInformation(
            duration = ((format?.optString("duration")?.toDoubleOrNull() ?: 0.0) * 1000).toLong(),
            bitrate = format?.optString("bit_rate")?.toLongOrNull() ?: 0L)
        val streams = json.optJSONArray("streams") ?: return info
        for (index in 0 until streams.length()) {
            val stream = streams.getJSONObject(index)
            when (stream.optString("codec_type")) {
                "video" -> {
                    val rate = stream.optString("avg_frame_rate").split('/')
                    val fps = if (rate.size == 2) {
                        val denominator = rate[1].toDoubleOrNull() ?: 0.0
                        if (denominator != 0.0) (rate[0].toDoubleOrNull() ?: 0.0) / denominator else 0.0
                    } else rate.first().toDoubleOrNull() ?: 0.0
                    info.videoStreams.add(VideoStream(stream.optString("codec_name"),
                        stream.optInt("width"), stream.optInt("height"), fps))
                }
                "audio" -> info.audioStreams.add(AudioStream(stream.optString("codec_name"),
                    stream.optInt("sample_rate"), stream.optInt("channels")))
            }
        }
        return info
    }

    fun isVideoFile(path: String): Boolean = getMediaInformation(path)?.videoStreams?.isNotEmpty() == true
    fun isAudioFile(path: String): Boolean = getMediaInformation(path)?.let {
        it.audioStreams.isNotEmpty() && it.videoStreams.isEmpty()
    } == true
    fun getVideoDuration(path: String): Long = getMediaInformation(path)?.duration ?: 0L
    fun getVideoResolution(path: String): Pair<Int, Int>? =
        getMediaInformation(path)?.videoStreams?.firstOrNull()?.let { it.width to it.height }
    fun getVideoCodec(path: String): String? = getMediaInformation(path)?.videoStreams?.firstOrNull()?.codec
    fun getAudioCodec(path: String): String? = getMediaInformation(path)?.audioStreams?.firstOrNull()?.codec
    fun getBitrate(path: String): Long = getMediaInformation(path)?.bitrate ?: 0L
    fun getFrameRate(path: String): Double = getMediaInformation(path)?.videoStreams?.firstOrNull()?.frameRate ?: 0.0
}

data class MediaInformation(
    var duration: Long = 0,
    var bitrate: Long = 0,
    val videoStreams: MutableList<VideoStream> = mutableListOf(),
    val audioStreams: MutableList<AudioStream> = mutableListOf()
)
data class VideoStream(var codec: String = "", var width: Int = 0, var height: Int = 0, var frameRate: Double = 0.0)
data class AudioStream(var codec: String = "", var sampleRate: Int = 0, var channels: Int = 0)
