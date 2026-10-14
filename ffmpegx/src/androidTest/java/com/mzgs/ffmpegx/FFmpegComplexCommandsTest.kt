package com.mzgs.ffmpegx

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class FFmpegComplexCommandsTest {
    @Test fun complexGraphsMultipleOutputsTransitionsAndStreamMapping() = runBlocking {
        withTimeout(120_000) {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val ffmpeg = FFmpeg.initialize(context)
            val dir = File(context.cacheDir, "complex-proof")
            dir.deleteRecursively()
            assertTrue(dir.mkdirs())
            fun file(name: String) = File(dir, name)
            suspend fun run(vararg args: String) {
                var lastError = ""
                val callback = object : FFmpegHelper.FFmpegCallback {
                    override fun onStart() { }
                    override fun onProgress(progress: Float, time: Long) { }
                    override fun onOutput(line: String) { file("ffmpeg.log").appendText(line + "\n") }
                    override fun onSuccess(output: String?) { }
                    override fun onFailure(error: String) { lastError = error }
                    override fun onFinish() { }
                }
                // Exercise the string API's quoting too, including filenames with spaces.
                val command = FFmpegArguments.format(listOf("-y") + args)
                file("commands.txt").appendText("ffmpeg $command\n\n")
                val success = ffmpeg.execute(command, callback)
                assertTrue("$command\n$lastError", success)
            }
            suspend fun info(name: String): MediaInformation {
                assertTrue("Empty output: $name", file(name).length() > 0)
                return requireNotNull(ffmpeg.getMediaInfo(file(name).path)) { "Cannot probe $name" }
            }
            suspend fun frame(name: String, time: String, width: Int, height: Int): ByteArray {
                run("-ss", time, "-i", file(name).path, "-frames:v", "1", "-pix_fmt", "rgb24",
                    "-f", "rawvideo", file("frame.rgb").path)
                return file("frame.rgb").readBytes().also { assertEquals(width * height * 3, it.size) }
            }
            fun pixel(rgb: ByteArray, width: Int, x: Int, y: Int): List<Int> {
                val offset = (y * width + x) * 3
                return (0..2).map { rgb[offset + it].toInt() and 255 }
            }
            for ((color, frequency) in listOf("red" to 440, "blue" to 880)) {
                run("-f", "lavfi", "-i", "color=$color:size=160x180:rate=10:duration=2",
                    "-f", "lavfi", "-i", "sine=frequency=$frequency:sample_rate=48000:duration=2",
                    "-c:v", "libx264", "-crf", "0", "-c:a", "aac", file("$color source.mp4").path)
            }
            file("captions.srt").writeText("1\n00:00:00,000 --> 00:00:01,500\nComplex FFmpeg works!\n")
            run("-i", file("captions.srt").path, "-c:s", "ass", file("captions.ass").path)

            val composition = "[0:v]trim=duration=1.5,setpts=PTS-STARTPTS[left];" +
                "[1:v]trim=duration=1.5,setpts=PTS-STARTPTS[right];" +
                "[left][right]hstack=inputs=2[base];" +
                "[base][2:v]overlay=10:10:shortest=1," +
                "subtitles=${file("captions.ass").path}:fontsdir=/system/fonts:" +
                "force_style='Fontname=Roboto,PrimaryColour=&H0000FF00,Fontsize=28',split=2[main][small];" +
                "[small]scale=160:90[preview];" +
                "[0:a]atrim=duration=1.5,asetpts=PTS-STARTPTS,volume=0.7[a0];" +
                "[1:a]atrim=duration=1.5,asetpts=PTS-STARTPTS,volume=0.3,adelay=100[a1];" +
                "[a0][a1]amix=inputs=2:duration=first,afade=t=out:st=1:d=0.5," +
                "aresample=48000,aformat=channel_layouts=stereo,asplit=3[am][ap][mp3]"
            run("-i", file("red source.mp4").path, "-i", file("blue source.mp4").path,
                "-f", "lavfi", "-i", "color=white:size=24x24:rate=10:duration=1.5",
                "-filter_complex", composition,
                "-map", "[main]", "-map", "[am]", "-c:v", "libx264", "-crf", "0", "-c:a", "aac",
                "-metadata", "title=Complex composition", "-movflags", "+faststart", file("composite main.mp4").path,
                "-map", "[preview]", "-map", "[ap]", "-c:v", "libvpx-vp9", "-deadline", "realtime",
                "-cpu-used", "8", "-threads", "2", "-c:a", "libopus", file("preview.webm").path,
                "-map", "[mp3]", "-c:a", "libmp3lame", "-b:a", "128k", file("mix.mp3").path)
            for ((name, codec, size) in listOf(Triple("composite main.mp4", "h264", 320 to 180),
                Triple("preview.webm", "vp9", 160 to 90))) {
                val media = info(name)
                assertEquals(codec, media.videoStreams.single().codec)
                assertEquals(size, media.videoStreams.single().let { it.width to it.height })
                assertEquals(10.0, media.videoStreams.single().frameRate, 0.01)
                assertTrue("Unexpected duration: $media", media.duration in 1450..1650)
                assertEquals(if (codec == "h264") "aac" else "opus", media.audioStreams.single().codec)
                assertEquals(2, media.audioStreams.single().channels)
            }
            assertEquals("mp3", info("mix.mp3").audioStreams.single().codec)
            val composed = frame("composite main.mp4", "0.5", 320, 180)
            assertTrue(pixel(composed, 320, 80, 80).let { it[0] > 200 && it[2] < 20 })
            assertTrue(pixel(composed, 320, 240, 80).let { it[2] > 200 && it[0] < 20 })
            assertTrue("White overlay must appear", pixel(composed, 320, 20, 20).all { it > 230 })
            assertTrue("Styled subtitles must appear", composed.indices.step(3).count { i ->
                val r = composed[i].toInt() and 255
                val g = composed[i + 1].toInt() and 255
                val b = composed[i + 2].toInt() and 255
                g > 100 && g > r + 50 && g > b + 50
            } > 100)
            run("-ss", "0.5", "-i", file("composite main.mp4").path, "-frames:v", "1", file("composition.png").path)
            run("-i", file("mix.mp3").path, "-ac", "1", "-ar", "48000", "-f", "s16le", file("mix.pcm").path)
            val samples = ByteBuffer.wrap(file("mix.pcm").readBytes()).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            fun amplitude(frequency: Int): Double {
                var real = 0.0
                var imaginary = 0.0
                for (i in 12000 until 36000) {
                    val angle = 2 * Math.PI * frequency * i / 48000
                    real += samples.get(i) * cos(angle)
                    imaginary += samples.get(i) * sin(angle)
                }
                return 2 * sqrt(real * real + imaginary * imaginary) / 24000
            }
            val tone440 = amplitude(440)
            val tone880 = amplitude(880)
            assertTrue("Both inputs must be audible: $tone440, $tone880", tone440 > 300 && tone880 > 100)

            run("-i", file("red source.mp4").path, "-i", file("blue source.mp4").path, "-filter_complex",
                "[0:v]settb=AVTB,setpts=PTS-STARTPTS[r];[1:v]settb=AVTB,setpts=PTS-STARTPTS[b];" +
                "[r][b]xfade=transition=fade:duration=0.5:offset=1.5,format=yuv420p[v];" +
                "[0:a][1:a]acrossfade=d=0.5:c1=tri:c2=tri[a]",
                "-map", "[v]", "-map", "[a]", "-c:v", "libx264", "-crf", "0", "-c:a", "aac", file("crossfade.mp4").path)
            assertTrue(info("crossfade.mp4").duration in 3450..3650)
            val before = pixel(frame("crossfade.mp4", "1.0", 160, 180), 160, 80, 80)
            val during = pixel(frame("crossfade.mp4", "1.7", 160, 180), 160, 80, 80)
            val after = pixel(frame("crossfade.mp4", "3.0", 160, 180), 160, 80, 80)
            assertTrue(before[0] > 200 && before[2] < 20)
            assertTrue("Transition must blend both clips: $during", during[0] > 70 && during[2] > 70)
            assertTrue(after[2] > 200 && after[0] < 20)

            run("-i", file("red source.mp4").path, "-i", file("blue source.mp4").path, "-filter_complex",
                "[0:v]trim=start=0.2:end=1.2,setpts=PTS-STARTPTS[v0];" +
                "[1:v]trim=start=0.2:end=1.2,setpts=PTS-STARTPTS[v1];" +
                "[0:a]atrim=start=0.2:end=1.2,asetpts=PTS-STARTPTS[a0];" +
                "[1:a]atrim=start=0.2:end=1.2,asetpts=PTS-STARTPTS[a1];" +
                "[v0][a0][v1][a1]concat=n=2:v=1:a=1[v][a];[v]setpts=0.5*PTS[fastv];[a]atempo=2[fasta]",
                "-map", "[fastv]", "-map", "[fasta]", "-c:v", "libx264", "-crf", "0",
                "-r", "20", "-fps_mode", "cfr", "-c:a", "aac", file("trim-concat-fast.mp4").path)
            assertTrue(info("trim-concat-fast.mp4").duration in 950..1050)
            assertEquals(20.0, info("trim-concat-fast.mp4").videoStreams.single().frameRate, 0.01)
            assertTrue(pixel(frame("trim-concat-fast.mp4", "0.2", 160, 180), 160, 80, 80)[0] > 200)
            assertTrue(pixel(frame("trim-concat-fast.mp4", "0.8", 160, 180), 160, 80, 80)[2] > 200)

            run("-i", file("crossfade.mp4").path, "-filter_complex",
                "[0:v]fps=10,scale=160:180:flags=lanczos,split[a][b];" +
                "[a]palettegen=stats_mode=diff[p];[b][p]paletteuse=dither=bayer[out]",
                "-map", "[out]", "-loop", "0", file("palette.gif").path)
            assertTrue(info("palette.gif").duration in 3450..3650)
            run("-i", file("palette.gif").path, "-pix_fmt", "rgb24", "-f", "rawvideo", file("gif.rgb").path)
            assertEquals("All 35 GIF frames must decode", 35 * 160 * 180 * 3, file("gif.rgb").length().toInt())

            run("-i", file("composite main.mp4").path, "-i", file("mix.mp3").path,
                "-i", file("captions.ass").path, "-map", "0:v", "-map", "0:a", "-map", "1:a", "-map", "2:s",
                "-c", "copy", "-metadata:s:a:0", "language=eng", "-metadata:s:a:1", "language=tur",
                "-disposition:a:0", "default", "-disposition:a:1", "0", file("multitrack.mkv").path)
            assertEquals(listOf("aac", "mp3"), info("multitrack.mkv").audioStreams.map { it.codec })
            run("-i", file("multitrack.mkv").path, "-map", "0:v:0", "-map", "0:a:m:language:tur",
                "-c", "copy", file("selected-turkish.mkv").path)
            assertEquals("mp3", info("selected-turkish.mkv").audioStreams.single().codec)
            run("-i", file("multitrack.mkv").path, "-map", "0:s:0", "-c:s", "ass", file("extracted.ass").path)
            assertTrue(file("extracted.ass").readText().contains("Complex FFmpeg works!"))
            file("checks.txt").writeText("All complex command assertions passed.\n" +
                "Mixed audio amplitudes: 440 Hz = $tone440, 880 Hz = $tone880\n" +
                "Crossfade center pixels: before=$before, during=$during, after=$after\n" +
                "GIF: 35 decoded frames.\n")
        }
    }
}
