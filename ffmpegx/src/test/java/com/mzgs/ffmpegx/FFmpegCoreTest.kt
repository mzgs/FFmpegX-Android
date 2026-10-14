package com.mzgs.ffmpegx

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class FFmpegCoreTest {
    @Test fun argumentsRoundTripWithoutShellExpansion() {
        val args = listOf("-i", "a 'quoted' \"video\".mp4", "-vf", "drawtext=text='a b':x=10", "", "C:\\video", "$(id)", "a\nb")
        assertEquals(args, FFmpegArguments.parse(FFmpegArguments.format(args)))
        assertEquals(listOf("-i", "a b.mp4", "-vf", "scale=10:20"),
            FFmpegArguments.parse("-i \"a b.mp4\" -vf 'scale=10:20'"))
        assertEquals(listOf("subtitles=foo\\:bar"), FFmpegArguments.parse("\"subtitles=foo\\:bar\""))
        for (invalid in listOf("'unfinished", "trailing\\", "nul\u0000")) {
            assertThrows(IllegalArgumentException::class.java) { FFmpegArguments.parse(invalid) }
        }
        val builder = FFmpegCommandBuilder().input(args[1]).videoFilter(args[3]).output("o 'file'.mp4")
        assertEquals(builder.buildAsList(), FFmpegArguments.parse(builder.build()))
    }

    @Test fun extensionsDoNotTreatDirectoryDotsAsFileExtensions() {
        assertEquals("", FFmpegUtils.getFileExtension("/path.with.dot/video"))
        assertEquals("/path.with.dot/video.mp4", FFmpegUtils.changeFileExtension("/path.with.dot/video", ".mp4"))
        assertEquals("clip.mkv", FFmpegUtils.changeFileExtension("clip.mp4", "mkv"))
    }

    @Test fun progressUsesFractions() {
        val parser = FFmpegProgressParser()
        assertNull(parser.parseProgress("Duration: 00:00:10.00"))
        val progress = parser.parseProgress("frame= 10 fps= 25 time=00:00:05.00 speed=2.0x")!!
        assertEquals(0.5f, progress.percentage, 0.0001f)
        assertEquals(50, progress.percentageInt)
        assertEquals(2500L, progress.estimatedRemainingTimeMs)
        assertNull(parser.parseProgress("time=00:00:05.00"))
        var update: FFmpegProgress? = null
        FFmpegProgressHandler(10_000) { update = it }.handleOutput("time=00:00:05.123456")
        assertEquals(5123L, update!!.timeMs)
        assertEquals(0.5123f, update!!.percentage, 0.0001f)
        assertEquals(51, update!!.percentageInt)
    }

    @Test fun processSessionsCompleteFailAndCancelIndependently() {
        val done = CountDownLatch(2)
        val codes = java.util.concurrent.ConcurrentHashMap<String, Int>()
        fun callback(name: String) = object : FFmpegExecutor.ExecutorCallback {
            override fun onOutput(output: String) { }
            override fun onError(error: String) { }
            override fun onComplete(exitCode: Int) { codes[name] = exitCode; done.countDown() }
        }
        val slow = FFmpegExecutor.execute("/bin/sleep", listOf("10"), callback("slow"))
        val fast = FFmpegExecutor.execute("/usr/bin/true", emptyList(), callback("fast"))
        assertNotEquals(slow, fast)
        assertTrue(FFmpegExecutor.cancel(slow))
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertEquals(255, codes["slow"])
        assertEquals(0, codes["fast"])
        assertEquals(FFmpegSessionManager.SessionState.CANCELLED, FFmpegExecutor.sessionManager.getSessionInfo(slow)!!.state)
        assertEquals(FFmpegSessionManager.SessionState.COMPLETED, FFmpegExecutor.sessionManager.getSessionInfo(fast)!!.state)
        assertFalse(FFmpegExecutor.isRunning(slow))
        assertFalse(FFmpegExecutor.cancel(fast))
    }
}
