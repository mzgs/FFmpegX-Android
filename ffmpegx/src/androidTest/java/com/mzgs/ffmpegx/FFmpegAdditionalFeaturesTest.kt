package com.mzgs.ffmpegx

import android.graphics.BitmapFactory
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class FFmpegAdditionalFeaturesTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val ffmpeg = FFmpeg.initialize(context)

    private fun directory(name: String) = File(context.cacheDir, "features-proof/$name").apply {
        deleteRecursively()
        assertTrue(mkdirs())
    }

    private suspend fun run(dir: File, vararg args: String): String {
        val log = StringBuilder()
        val callback = object : FFmpegHelper.FFmpegCallback {
            override fun onStart() { }
            override fun onProgress(progress: Float, time: Long) { }
            override fun onOutput(line: String) { log.appendLine(line) }
            override fun onSuccess(output: String?) { }
            override fun onFailure(error: String) { log.appendLine(error) }
            override fun onFinish() { }
        }
        val arguments = listOf("-y") + args
        File(dir, "commands.txt").appendText("ffmpeg ${FFmpegArguments.format(arguments)}\n\n")
        val success = ffmpeg.execute(arguments, callback)
        File(dir, "ffmpeg.log").appendText(log.toString())
        assertTrue("${arguments}\n$log", success)
        return log.toString()
    }

    private fun probe(dir: File, name: String): JSONObject {
        val process = ProcessBuilder(FFmpegInstaller.getFFprobePath(context), "-v", "error",
            "-show_streams", "-show_format", "-show_chapters", "-of", "json", File(dir, name).path)
            .redirectErrorStream(true).start()
        return try {
            val output = process.inputStream.bufferedReader().use { it.readText() }
            assertTrue(process.waitFor(10, TimeUnit.SECONDS))
            assertEquals(output, 0, process.exitValue())
            File(dir, "$name.json").writeText(output)
            JSONObject(output)
        } finally { process.destroyForcibly() }
    }

    private suspend fun video(dir: File, duration: Int) {
        run(dir, "-f", "lavfi", "-i", "testsrc2=size=160x96:rate=10:duration=$duration",
            "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000:duration=$duration",
            "-c:v", "libx264", "-preset", "fast", "-g", "10", "-sc_threshold", "0",
            "-c:a", "aac", File(dir, "source.mp4").path)
    }

    private suspend fun hashes(dir: File, name: String): List<String> {
        val output = File(dir, "$name.framemd5")
        run(dir, "-i", File(dir, name).path, "-map", "0:v:0", "-f", "framemd5", output.path)
        return output.readLines().filter { it.isNotBlank() && !it.startsWith("#") }
            .map { it.substringAfterLast(',').trim() }.also { assertTrue(it.isNotEmpty()) }
    }

    @Test fun requested1080pSlowX265VideoAndMusicPipeline() = runBlocking {
        withTimeout(600_000) {
            val dir = directory("requested-command")
            fun path(name: String) = File(dir, name).path
            // A 4:3 source exercises aspect-fill cropping; the dark band makes the title measurable.
            run(dir, "-f", "lavfi", "-i",
                "testsrc2=size=640x480:rate=15:duration=3,drawbox=x=0:y=60:w=640:h=40:color=black:t=fill",
                "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000:duration=3",
                "-c:v", "libx264", "-preset", "fast", "-c:a", "aac", path("input.mp4"))
            run(dir, "-f", "lavfi", "-i", "sine=frequency=880:sample_rate=44100:duration=5",
                "-c:a", "libmp3lame", path("background_music.mp3"))
            val graph = "[0:v]fps=30,scale=1920:1080:force_original_aspect_ratio=increase," +
                "crop=1920:1080,eq=contrast=1.12:brightness=0.02:saturation=1.2,unsharp=5:5:0.8," +
                "drawtext=fontfile=/system/fonts/Roboto-Regular.ttf:text='MY VIDEO':fontsize=48:" +
                "fontcolor=white:x=(w-text_w)/2:y=50,format=yuv420p[v];" +
                "[0:a]highpass=f=80,lowpass=f=16000,loudnorm=I=-16:TP=-1.5:LRA=11[voice];" +
                "[1:a]volume=0.15,afade=t=in:st=0:d=2[music];" +
                "[voice][music]amix=inputs=2:duration=first:dropout_transition=2[a]"
            File(dir, "filter-complex.txt").writeText(graph + "\n")
            val inputs = arrayOf("-hide_banner", "-i", path("input.mp4"), "-i", path("background_music.mp3"))
            val encoding = arrayOf("-map", "[v]", "-map", "[a]", "-c:v", "libx265", "-preset", "slow",
                "-crf", "22", "-pix_fmt", "yuv420p", "-c:a", "aac", "-b:a", "192k", "-movflags", "+faststart", "-shortest")
            val start = System.nanoTime()
            run(dir, *inputs, "-filter_complex", graph, *encoding, path("output.mp4"))
            val elapsed = (System.nanoTime() - start) / 1_000_000_000.0
            val metadata = probe(dir, "output.mp4")
            val streams = metadata.getJSONArray("streams")
            assertEquals(2, streams.length())
            val video = streams.getJSONObject(0)
            val audio = streams.getJSONObject(1)
            assertEquals("hevc", video.getString("codec_name"))
            assertEquals(1920, video.getInt("width"))
            assertEquals(1080, video.getInt("height"))
            assertEquals("yuv420p", video.getString("pix_fmt"))
            assertEquals("30/1", video.getString("avg_frame_rate"))
            assertEquals("aac", audio.getString("codec_name"))
            assertEquals(3.0, metadata.getJSONObject("format").getString("duration").toDouble(), 0.15)
            assertEquals("Every encoded HEVC frame must decode", 90, hashes(dir, "output.mp4").size)

            run(dir, "-i", path("output.mp4"), "-frames:v", "1", path("frame.png"))
            val bitmap = BitmapFactory.decodeFile(path("frame.png"))
            var titlePixels = 0
            try {
                for (y in 45 until 110) for (x in 760 until 1160) {
                    val pixel = bitmap.getPixel(x, y)
                    if ((pixel shr 16 and 255) > 210 && (pixel shr 8 and 255) > 210 && (pixel and 255) > 210) titlePixels++
                }
                assertTrue("The white title must be visible: $titlePixels pixels", titlePixels > 500)
            } finally { bitmap.recycle() }
            run(dir, "-i", path("output.mp4"), "-map", "0:a", "-ac", "1", "-ar", "48000",
                "-c:a", "pcm_s16le", "-f", "s16le", path("mixed.pcm"))
            val samples = ByteBuffer.wrap(File(path("mixed.pcm")).readBytes()).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            // Raw PCM counts samples; it does not include gaps represented by packet timestamps.
            val originalAudioDuration = samples.remaining() / 48000.0
            assertTrue("Unexpected decoded audio duration: $originalAudioDuration", originalAudioDuration in 2.85..3.15)
            fun amplitude(frequency: Int, buffer: java.nio.ShortBuffer = samples): Double {
                var real = 0.0
                var imaginary = 0.0
                for (i in 105600 until 129600) {
                    val angle = 2 * Math.PI * frequency * i / 48000
                    real += buffer.get(i) * cos(angle)
                    imaginary += buffer.get(i) * sin(angle)
                }
                return 2 * sqrt(real * real + imaginary * imaginary) / 24000
            }
            val voice = amplitude(440)
            val music = amplitude(880)
            assertTrue("Both tracks must survive mixing: voice=$voice, music=$music", voice > 500 && music > 30)
            val mp4 = ByteBuffer.wrap(File(path("output.mp4")).readBytes()).order(ByteOrder.BIG_ENDIAN)
            val atoms = mutableListOf<String>()
            while (mp4.remaining() >= 8) {
                val offset = mp4.position()
                val size = mp4.int
                val type = ByteArray(4).also { mp4.get(it) }.toString(Charsets.US_ASCII)
                assertTrue(size >= 8 && size <= mp4.limit() - offset)
                atoms.add(type)
                mp4.position(offset + size)
            }
            assertTrue("Faststart must move metadata ahead of media", atoms.indexOf("moov") in 0 until atoms.indexOf("mdat"))
            val continuousGraph = graph.replace("dropout_transition=2[a]", "dropout_transition=2,asetpts=N/SR/TB[a]")
            File(dir, "filter-complex-continuous.txt").writeText(continuousGraph + "\n")
            run(dir, *inputs, "-filter_complex", continuousGraph, *encoding, path("output-continuous-audio.mp4"))
            val continuousMetadata = probe(dir, "output-continuous-audio.mp4")
            assertEquals("hevc", continuousMetadata.getJSONArray("streams").getJSONObject(0).getString("codec_name"))
            assertEquals(90, hashes(dir, "output-continuous-audio.mp4").size)
            run(dir, "-i", path("output-continuous-audio.mp4"), "-map", "0:a", "-ac", "1", "-ar", "48000",
                "-c:a", "pcm_s16le", "-f", "s16le", path("continuous.pcm"))
            val continuous = ByteBuffer.wrap(File(path("continuous.pcm")).readBytes()).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            val continuousAudioDuration = continuous.remaining() / 48000.0
            assertEquals("Reset timestamps must preserve the full audio", 3.0, continuousAudioDuration, 0.03)
            assertTrue(amplitude(440, continuous) > 500 && amplitude(880, continuous) > 30)
            File(dir, "checks.txt").writeText("1080p, 30 fps, HEVC/yuv420p video and AAC audio.\n" +
                "All 90 frames decoded; white title has $titlePixels bright pixels.\n" +
                "Mixed audio: 440 Hz voice amplitude $voice, 880 Hz music amplitude $music.\n" +
                "MP4 atom order: $atoms.\nSlow-preset x265 pipeline wall time: $elapsed seconds.\n" +
                "Original decoded audio duration: $originalAudioDuration seconds.\n" +
                "With asetpts=N/SR/TB after amix: $continuousAudioDuration seconds; all 90 video frames decode.\n")
        }
    }

    @Test fun twoPassLoudnessNormalizationChannelSwapAndResampling() = runBlocking {
        withTimeout(120_000) {
            val dir = directory("audio")
            fun path(name: String) = File(dir, name).path
            fun measurement(log: String) = JSONObject(log.substring(log.lastIndexOf('{'), log.lastIndexOf('}') + 1))
            run(dir, "-f", "lavfi", "-i", "aevalsrc=0.01*sin(2*PI*440*t)|0.01*sin(2*PI*880*t):s=48000:d=4",
                "-c:a", "pcm_s16le", path("quiet.wav"))
            val first = measurement(run(dir, "-i", path("quiet.wav"), "-af",
                "loudnorm=I=-16:TP=-1.5:LRA=11:print_format=json", "-f", "null", "-"))
            File(dir, "loudness-before.json").writeText(first.toString(2))
            val options = "loudnorm=I=-16:TP=-1.5:LRA=11:measured_I=${first.getString("input_i")}:" +
                "measured_TP=${first.getString("input_tp")}:measured_LRA=${first.getString("input_lra")}:" +
                "measured_thresh=${first.getString("input_thresh")}:offset=${first.getString("target_offset")}:linear=true"
            run(dir, "-i", path("quiet.wav"), "-af", options, "-ar", "48000", "-c:a", "flac", path("normalized.flac"))
            val after = measurement(run(dir, "-i", path("normalized.flac"), "-af",
                "loudnorm=I=-16:TP=-1.5:LRA=11:print_format=json", "-f", "null", "-"))
            File(dir, "loudness-after.json").writeText(after.toString(2))
            assertTrue(first.getString("input_i").toDouble() < -30)
            assertEquals(-16.0, after.getString("input_i").toDouble(), 0.5)
            assertTrue(after.getString("input_tp").toDouble() <= -1.0)

            run(dir, "-i", path("normalized.flac"), "-af", "pan=stereo|c0=c1|c1=c0",
                "-c:a", "pcm_s16le", "-f", "s16le", path("swapped.pcm"))
            val samples = ByteBuffer.wrap(File(path("swapped.pcm")).readBytes()).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            assertEquals(4 * 48000 * 2, samples.remaining())
            fun amplitude(channel: Int, frequency: Int): Double {
                var real = 0.0
                var imaginary = 0.0
                for (i in 24000 until 48000) {
                    val angle = 2 * Math.PI * frequency * i / 48000
                    real += samples.get(i * 2 + channel) * cos(angle)
                    imaginary += samples.get(i * 2 + channel) * sin(angle)
                }
                return 2 * sqrt(real * real + imaginary * imaginary) / 24000
            }
            val left880 = amplitude(0, 880)
            val right440 = amplitude(1, 440)
            assertTrue(left880 > 1000 && left880 > amplitude(0, 440) * 20)
            assertTrue(right440 > 1000 && right440 > amplitude(1, 880) * 20)
            run(dir, "-i", path("normalized.flac"), "-ar", "16000", "-ac", "1", "-c:a", "pcm_s16le", path("mono16k.wav"))
            val stream = probe(dir, "mono16k.wav").getJSONArray("streams").getJSONObject(0)
            assertEquals(16000, stream.getString("sample_rate").toInt())
            assertEquals(1, stream.getInt("channels"))
            run(dir, "-i", path("mono16k.wav"), "-f", "s16le", path("mono.pcm"))
            assertEquals(4 * 16000 * 2L, File(path("mono.pcm")).length())
            File(dir, "checks.txt").writeText("Loudness: ${first.getString("input_i")} -> ${after.getString("input_i")} LUFS.\n" +
                "Swapped channels: left 880 Hz amplitude $left880, right 440 Hz amplitude $right440.\n" +
                "Mono resampling: 64,000 samples at 16 kHz.\n")
        }
    }

    @Test fun twoPassH264Encoding() = runBlocking {
        withTimeout(120_000) {
            val dir = directory("two-pass")
            video(dir, 3)
            val common = arrayOf("-i", File(dir, "source.mp4").path, "-c:v", "libx264", "-preset", "fast",
                "-b:v", "120k", "-passlogfile", File(dir, "encode stats").path)
            run(dir, *common, "-pass", "1", "-an", "-f", "null", "-")
            assertTrue(File(dir, "encode stats-0.log").length() > 0)
            run(dir, *common, "-pass", "2", "-c:a", "aac", File(dir, "two-pass.mp4").path)
            val streams = probe(dir, "two-pass.mp4").getJSONArray("streams")
            assertEquals("h264", streams.getJSONObject(0).getString("codec_name"))
            assertEquals("aac", streams.getJSONObject(1).getString("codec_name"))
            assertEquals(3.0, streams.getJSONObject(0).getString("duration").toDouble(), 0.1)
            assertEquals(30, hashes(dir, "two-pass.mp4").size)
            File(dir, "checks.txt").writeText("Both x264 passes succeeded; statistics file created and consumed.\n" +
                "30 frames decoded from three-second H.264/AAC output.\n")
        }
    }

    @Test fun fragmentedMp4AndLosslessRemux() = runBlocking {
        withTimeout(120_000) {
            val dir = directory("containers")
            video(dir, 2)
            run(dir, "-i", File(dir, "source.mp4").path, "-map", "0", "-c", "copy",
                "-movflags", "+frag_keyframe+empty_moov+default_base_moof", "-frag_duration", "500000",
                File(dir, "fragmented.mp4").path)
            val bytes = ByteBuffer.wrap(File(dir, "fragmented.mp4").readBytes()).order(ByteOrder.BIG_ENDIAN)
            val atoms = mutableListOf<String>()
            while (bytes.remaining() >= 8) {
                val start = bytes.position()
                val size = bytes.int
                val type = ByteArray(4).also { bytes.get(it) }.toString(Charsets.US_ASCII)
                assertTrue("Invalid MP4 atom $type/$size", size >= 8 && size <= bytes.limit() - start)
                atoms.add(type)
                bytes.position(start + size)
            }
            assertTrue(atoms.count { it == "moof" } >= 2)
            assertTrue(atoms.indexOf("moov") < atoms.indexOf("moof"))
            run(dir, "-i", File(dir, "fragmented.mp4").path, "-map", "0", "-c", "copy", File(dir, "remux.mkv").path)
            val original = hashes(dir, "source.mp4")
            assertEquals(20, original.size)
            assertEquals(original, hashes(dir, "fragmented.mp4"))
            assertEquals(original, hashes(dir, "remux.mkv"))
            File(dir, "checks.txt").writeText("MP4 atom order: $atoms.\nAll 20 decoded frame hashes match across MP4, fragmented MP4, and MKV.\n")
        }
    }

    @Test fun localHlsSegmentsPlaybackAndSeeking() = runBlocking {
        withTimeout(120_000) {
            val dir = directory("hls")
            video(dir, 3)
            run(dir, "-i", File(dir, "source.mp4").path, "-map", "0", "-c", "copy", "-f", "hls",
                "-hls_time", "1", "-hls_list_size", "0", "-hls_playlist_type", "vod", "-hls_flags", "independent_segments",
                "-hls_segment_filename", File(dir, "part%03d.ts").path, File(dir, "index.m3u8").path)
            val playlist = File(dir, "index.m3u8").readLines()
            assertTrue(playlist.contains("#EXT-X-ENDLIST"))
            assertTrue(playlist.contains("#EXT-X-INDEPENDENT-SEGMENTS"))
            val segments = playlist.filter { it.isNotBlank() && !it.startsWith("#") }
            assertEquals(3, segments.size)
            segments.forEach { assertTrue(File(dir, it).length() > 0) }
            assertEquals(3.0, playlist.filter { it.startsWith("#EXTINF:") }
                .sumOf { it.substringAfter(':').substringBefore(',').toDouble() }, 0.1)
            assertEquals(hashes(dir, "source.mp4"), hashes(dir, "index.m3u8"))
            run(dir, "-ss", "1.4", "-i", File(dir, "index.m3u8").path, "-t", "0.6",
                "-c:v", "libx264", "-c:a", "aac", File(dir, "seek.mp4").path)
            val duration = probe(dir, "seek.mp4").getJSONObject("format").getString("duration").toDouble()
            assertTrue("Unexpected seek output duration: $duration", duration in 0.55..0.75)
            assertEquals(6, hashes(dir, "seek.mp4").size)
            File(dir, "checks.txt").writeText("Three one-second TS segments, complete VOD playlist.\n" +
                "All 30 playlist frame hashes match the source; seek output decodes six frames, duration $duration seconds.\n")
        }
    }

    @Test fun pngSequenceGeometryAndContactSheet() = runBlocking {
        withTimeout(120_000) {
            val dir = directory("images")
            run(dir, "-f", "lavfi", "-i",
                "color=red:size=128x96:rate=5:duration=1,drawbox=x=64:y=0:w=64:h=96:color=blue:t=fill",
                File(dir, "frame%03d.png").path)
            assertEquals(5, dir.listFiles()!!.count { it.name.matches(Regex("frame\\d{3}\\.png")) })
            run(dir, "-framerate", "5", "-i", File(dir, "frame%03d.png").path, "-c:v", "libx264",
                "-crf", "0", "-pix_fmt", "yuv420p", File(dir, "sequence.mp4").path)
            assertEquals(5, hashes(dir, "sequence.mp4").size)
            run(dir, "-i", File(dir, "sequence.mp4").path, "-vf", "crop=96:64:16:16,transpose=1,hflip,eq=saturation=0",
                "-c:v", "libx264", "-crf", "0", File(dir, "geometry.mp4").path)
            val stream = probe(dir, "geometry.mp4").getJSONArray("streams").getJSONObject(0)
            assertEquals(64, stream.getInt("width"))
            assertEquals(96, stream.getInt("height"))
            run(dir, "-i", File(dir, "geometry.mp4").path, "-frames:v", "1", File(dir, "geometry.png").path)
            val bitmap = BitmapFactory.decodeFile(File(dir, "geometry.png").path)
            try {
                val top = bitmap.getPixel(32, 10)
                val bottom = bitmap.getPixel(32, 85)
                fun red(pixel: Int) = pixel shr 16 and 255
                fun green(pixel: Int) = pixel shr 8 and 255
                fun blue(pixel: Int) = pixel and 255
                assertEquals(red(top).toDouble(), green(top).toDouble(), 2.0)
                assertEquals(red(top).toDouble(), blue(top).toDouble(), 2.0)
                assertTrue("Rotation must place red-derived gray above blue-derived gray", red(top) > red(bottom) + 20)
            } finally { bitmap.recycle() }
            run(dir, "-i", File(dir, "sequence.mp4").path, "-vf", "fps=5,scale=64:48,tile=3x2",
                "-frames:v", "1", File(dir, "contact-sheet.png").path)
            val sheet = BitmapFactory.decodeFile(File(dir, "contact-sheet.png").path)
            try {
                assertEquals(192, sheet.width)
                assertEquals(96, sheet.height)
                assertTrue((sheet.getPixel(16, 24) shr 16 and 255) > 200)
                assertTrue((sheet.getPixel(52, 24) and 255) > 200)
                assertEquals(0, sheet.getPixel(160, 72) and 0xFFFFFF)
            } finally { sheet.recycle() }
            File(dir, "checks.txt").writeText("Five PNGs reassembled into five video frames.\n" +
                "Crop/rotate/flip/grayscale: 64x96 with expected pixel order.\nContact sheet: 192x96, five cells populated and sixth black.\n")
        }
    }

    @Test fun metadataChaptersAndMp3CoverArt() = runBlocking {
        withTimeout(120_000) {
            val dir = directory("metadata")
            run(dir, "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=44100:duration=4",
                "-c:a", "pcm_s16le", File(dir, "audio.wav").path)
            File(dir, "chapters.txt").writeText(";FFMETADATA1\ntitle=Feature test\nartist=FFmpegX\n" +
                "[CHAPTER]\nTIMEBASE=1/1000\nSTART=0\nEND=1000\ntitle=Intro\n" +
                "[CHAPTER]\nTIMEBASE=1/1000\nSTART=1000\nEND=4000\ntitle=Main\n")
            run(dir, "-i", File(dir, "audio.wav").path, "-f", "ffmetadata", "-i", File(dir, "chapters.txt").path,
                "-map", "0:a", "-map_metadata", "1", "-map_chapters", "1", "-c:a", "flac", File(dir, "chapters.mka").path)
            val metadata = probe(dir, "chapters.mka")
            assertEquals("Feature test", metadata.getJSONObject("format").getJSONObject("tags").getString("title"))
            val chapters = metadata.getJSONArray("chapters")
            assertEquals(2, chapters.length())
            assertEquals("Intro", chapters.getJSONObject(0).getJSONObject("tags").getString("title"))
            assertEquals("Main", chapters.getJSONObject(1).getJSONObject("tags").getString("title"))
            assertEquals(1.0, chapters.getJSONObject(1).getString("start_time").toDouble(), 0.001)
            assertEquals(4.0, chapters.getJSONObject(1).getString("end_time").toDouble(), 0.001)
            run(dir, "-f", "lavfi", "-i", "color=orange:size=64x64", "-frames:v", "1", File(dir, "cover.png").path)
            run(dir, "-i", File(dir, "audio.wav").path, "-i", File(dir, "cover.png").path,
                "-map", "0:a", "-map", "1:v", "-c:a", "libmp3lame", "-c:v", "png", "-id3v2_version", "3",
                "-disposition:v", "attached_pic", "-metadata", "title=Cover test", "-metadata", "artist=FFmpegX",
                "-metadata:s:v", "title=Cover", "-metadata:s:v", "comment=Cover (front)", File(dir, "cover.mp3").path)
            val cover = probe(dir, "cover.mp3")
            assertEquals("Cover test", cover.getJSONObject("format").getJSONObject("tags").getString("title"))
            val streams = cover.getJSONArray("streams")
            val picture = (0 until streams.length()).map { streams.getJSONObject(it) }
                .single { it.getString("codec_type") == "video" }
            assertEquals(1, picture.getJSONObject("disposition").getInt("attached_pic"))
            assertEquals("png", picture.getString("codec_name"))
            run(dir, "-i", File(dir, "cover.mp3").path, "-map", "0:v", "-c:v", "copy", "-frames:v", "1", File(dir, "extracted-cover.png").path)
            assertArrayEquals(File(dir, "cover.png").readBytes(), File(dir, "extracted-cover.png").readBytes())
            File(dir, "checks.txt").writeText("Title/artist metadata and two chapter markers retained in Matroska audio.\n" +
                "MP3 ID3 metadata and attached PNG cover retained; extracted cover bytes match original.\n")
        }
    }
}
