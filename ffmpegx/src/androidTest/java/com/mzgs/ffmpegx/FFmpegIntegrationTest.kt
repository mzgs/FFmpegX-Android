package com.mzgs.ffmpegx

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class FFmpegIntegrationTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val ffmpeg = FFmpeg.initialize(context)

    @Test fun realCommandsAndEveryConvenienceOperation() = runBlocking {
        withTimeout(180_000) {
            assertTrue(ffmpeg.install())
            assertTrue(ffmpeg.getVersion()!!.contains("8.1.3"))
            val dir = File(context.cacheDir, "ffmpeg 'integration' space").apply { mkdirs() }
            var lastError = ""
            val callback = object : FFmpegHelper.FFmpegCallback {
                override fun onStart() { }
                override fun onProgress(progress: Float, time: Long) { assertTrue(progress in 0f..1f) }
                override fun onOutput(line: String) { }
                override fun onSuccess(output: String?) { }
                override fun onFailure(error: String) { lastError = error }
                override fun onFinish() { }
            }
            fun file(name: String) = File(dir, name).absolutePath
            suspend fun check(name: String, result: Boolean, output: String = name) {
                assertTrue("$name: $lastError", result)
                assertTrue("Empty output: $name", File(file(output)).length() > 0)
                assertNotNull("Unprobeable output: $name", ffmpeg.getMediaInfo(file(output)))
            }
            try {
                assertTrue(ffmpeg.execute(listOf("-y", "-f", "lavfi", "-i", "testsrc2=size=128x96:rate=10",
                    "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=44100", "-t", "2",
                    "-c:v", "libx264", "-pix_fmt", "yuv420p", "-c:a", "aac", file("input.mp4")), callback))
                val input = file("input.mp4")
                val copied = File(file("copied.mp4"))
                copied.writeText("original")
                assertFalse(FFmpegUtils.copyUriToFile(context, android.net.Uri.fromFile(File(file("missing.mp4"))), copied))
                assertEquals("original", copied.readText())
                assertTrue(FFmpegUtils.copyUriToFile(context, android.net.Uri.fromFile(File(input)), copied))
                assertEquals(File(input).length(), copied.length())
                val ops = ffmpeg.operations()
                val info = ffmpeg.getMediaInfo(input)!!
                assertEquals("h264", info.videoStreams.first().codec)
                assertEquals(128, info.videoStreams.first().width)
                assertEquals(10.0, info.videoStreams.first().frameRate, 0.01)
                assertTrue(info.duration in 1900..2200)
                assertTrue(ops.isVideoFile(input))
                assertEquals(128 to 96, ops.getVideoResolution(input))
                assertTrue(ops.getVideoDuration(input) > 0)
                assertEquals("h264", ops.getVideoCodec(input))
                for (quality in FFmpegOperations.VideoQuality.values()) {
                    val name = "compressed-$quality.mp4"
                    check(name, ops.compressVideo(input, file(name), quality, callback))
                }
                for (format in FFmpegOperations.AudioFormat.values()) {
                    val name = "audio.${format.extension}"
                    check(name, ops.extractAudio(input, file(name), format, callback))
                    assertTrue(ops.isAudioFile(file(name)))
                    val codec = ffmpeg.getMediaInfo(file(name))!!.audioStreams.first().codec
                    assertEquals(when (format) {
                        FFmpegOperations.AudioFormat.MP3 -> "mp3"
                        FFmpegOperations.AudioFormat.OGG -> "vorbis"
                        else -> format.codec
                    }, codec)
                }
                for (format in FFmpegOperations.VideoFormat.values()) {
                    val name = "converted.${format.extension}"
                    check(name, ops.convertVideo(input, file(name), format, callback))
                    assertEquals(when (format) {
                        FFmpegOperations.VideoFormat.MP4, FFmpegOperations.VideoFormat.MKV, FFmpegOperations.VideoFormat.MOV -> "h264"
                        FFmpegOperations.VideoFormat.AVI -> "mpeg4"
                        FFmpegOperations.VideoFormat.WEBM -> "vp8"
                        FFmpegOperations.VideoFormat.FLV -> "flv1"
                    }, ffmpeg.getMediaInfo(file(name))!!.videoStreams.first().codec)
                }
                check("container.data", ops.convertVideo(input, file("container.data"), FFmpegOperations.VideoFormat.MP4, callback))
                check("trim.mp4", ops.trimVideo(input, file("trim.mp4"), 0.0, 1.0, callback))
                check("merge.mp4", ops.mergeVideos(listOf(input, input), file("merge.mp4"), callback))
                assertTrue(ffmpeg.getMediaInfo(file("merge.mp4"))!!.duration > 3500)
                check("resize.mp4", ops.resizeVideo(input, file("resize.mp4"), 64, 48, callback = callback))
                assertEquals(64 to 48, ops.getVideoResolution(file("resize.mp4")))
                for (degrees in listOf(90, 180, 270)) {
                    val name = "rotate-$degrees.mp4"
                    check(name, ops.rotateVideo(input, file(name), degrees, callback))
                    assertEquals(if (degrees == 180) 128 to 96 else 96 to 128, ops.getVideoResolution(file(name)))
                }
                assertTrue(ffmpeg.execute(listOf("-y", "-f", "lavfi", "-i", "color=red:size=16x16", "-frames:v", "1", file("logo.png")), callback))
                for (position in FFmpegOperations.WatermarkPosition.values()) {
                    val name = "watermark-$position.mp4"
                    check(name, ops.addWatermark(input, file("logo.png"), file(name), position, callback))
                }
                check("frames", ops.extractFrames(input, file("frame_%03d.jpg"), callback = callback), "frame_001.jpg")
                check("out.gif", ops.createGif(input, file("out.gif"), 64, 5, 0.0, 1.0, callback))
                for (speed in listOf(0.25f, 2f, 4f)) {
                    val name = "speed-$speed.mp4"
                    check(name, ops.changeVideoSpeed(input, file(name), speed, callback = callback))
                    assertEquals(2000.0 / speed, ffmpeg.getMediaInfo(file(name))!!.duration.toDouble(), 500.0)
                }
                check("reverse.mp4", ops.reverseVideo(input, file("reverse.mp4"), callback = callback))
                File(file("captions.srt")).writeText("1\n00:00:00,000 --> 00:00:01,000\nHello Android\n")
                check("subtitles.mp4", ops.addSubtitles(input, file("captions.srt"), file("subtitles.mp4"), callback))
                check("vp9.webm", ffmpeg.execute(listOf("-y", "-i", input, "-c:v", "libvpx-vp9", "-deadline", "realtime", "-cpu-used", "8", "-c:a", "libopus", file("vp9.webm")), callback))
                check("image.webp", ffmpeg.execute(listOf("-y", "-i", input, "-frames:v", "1", "-c:v", "libwebp", file("image.webp")), callback))
                val captions = File(context.cacheDir, "burn-captions.srt")
                try {
                    captions.writeText(File(file("captions.srt")).readText())
                    check("burn.mp4", ffmpeg.execute(listOf("-y", "-i", input, "-vf", "subtitles=${captions.path}:fontsdir=/system/fonts", "-c:v", "libx264", "-c:a", "copy", file("burn.mp4")), callback))
                    check("text.mp4", ffmpeg.execute(listOf("-y", "-i", input, "-vf", "drawtext=fontfile=/system/fonts/Roboto-Regular.ttf:text='Hello Android'", "-c:v", "libx264", "-c:a", "copy", file("text.mp4")), callback))
                } finally { captions.delete() }
                check("hevc.mp4", ffmpeg.execute(listOf("-y", "-i", input, "-c:v", "libx265", "-preset", "ultrafast", "-c:a", "copy", file("hevc.mp4")), callback))
                check("av1.mkv", ffmpeg.execute(listOf("-y", "-i", input, "-c:v", "libaom-av1", "-cpu-used", "8", "-c:a", "copy", file("av1.mkv")), callback))
                assertFalse(ffmpeg.execute(listOf("-y", "-i", file("missing.mp4"), file("bad.mp4")), callback))
                assertTrue(lastError.contains("No such file"))
                assertNull(ffmpeg.getMediaInfo(file("missing.mp4")))
            } finally { dir.deleteRecursively() }
        }
    }

    @Test fun metadataPreservesChannelsAndHandlesUnknownDurations() {
        val info = FFmpegHelper(context).parseMediaInformation("""{
            "format":{"duration":"N/A","bit_rate":"N/A"},
            "streams":[{"codec_type":"audio","codec_name":"aac","sample_rate":"48000","channels":6},
                       {"codec_type":"video","codec_name":"h264","width":128,"height":96,"avg_frame_rate":"0/0"}]
        }""")
        assertEquals(6, info.audioStreams.first().channels)
        assertEquals(0L, info.duration)
        assertEquals(0.0, info.videoStreams.first().frameRate, 0.0)
    }

    @Test fun asynchronousCancellationAndCoroutineCancellation() = runBlocking {
        val done = CountDownLatch(1)
        val callback = object : FFmpegHelper.FFmpegCallback {
            override fun onStart() { }
            override fun onProgress(progress: Float, time: Long) { }
            override fun onOutput(line: String) { }
            override fun onSuccess(output: String?) { }
            override fun onFailure(error: String) { assertTrue(error.contains("255")) }
            override fun onFinish() { done.countDown() }
        }
        val id = ffmpeg.executeAsync("-re -f lavfi -i testsrc2=size=32x32:rate=1 -f null -", callback)
        assertTrue(ffmpeg.cancel(id))
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertTrue(ffmpeg.sessions().getSessionInfo(id)!!.isCancelled)
        assertFalse(ffmpeg.sessions().isSessionRunning(id))
        try {
            withTimeout(250) { ffmpeg.execute("-re -f lavfi -i testsrc2=size=32x32:rate=1 -f null -") }
            fail("Expected coroutine cancellation")
        } catch (_: TimeoutCancellationException) { }
        withTimeout(5000) {
            while (ffmpeg.sessions().getRunningSessionsCount() != 0) delay(25)
        }
    }
}
