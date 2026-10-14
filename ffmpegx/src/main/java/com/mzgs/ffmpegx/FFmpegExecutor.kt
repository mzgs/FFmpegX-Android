package com.mzgs.ffmpegx

import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

object FFmpegExecutor {
    interface ExecutorCallback {
        fun onOutput(output: String)
        fun onError(error: String)
        fun onComplete(exitCode: Int)
    }

    val sessionManager = FFmpegSessionManager()
    private val nextId = AtomicLong()
    private class Task {
        var process: Process? = null
        var cancelled = false
    }
    private val tasks = ConcurrentHashMap<Long, Task>()

    fun execute(binaryPath: String, command: String, callback: ExecutorCallback? = null): Long =
        execute(binaryPath, FFmpegArguments.parse(command), callback)

    internal fun execute(
        binaryPath: String,
        arguments: List<String>,
        callback: ExecutorCallback? = null,
        processEnvironment: Map<String, String> = emptyMap()
    ): Long {
        val id = nextId.incrementAndGet()
        val task = Task()
        val progressParser = FFmpegProgressParser()
        tasks[id] = task
        sessionManager.addSession(id, "FFmpeg Command", FFmpegArguments.format(arguments))
        Thread({
            var code = -1
            fun notify(action: () -> Unit) {
                try { action() } catch (e: Exception) { Log.w("FFmpegExecutor", "Callback failed", e) }
            }
            try {
                val process = synchronized(task) {
                    if (task.cancelled) null else ProcessBuilder(listOf(binaryPath) + arguments)
                        .redirectErrorStream(true).apply {
                            environment().putAll(processEnvironment)
                        }.start().also { task.process = it }
                }
                if (process != null) {
                    process.outputStream.close()
                    process.inputStream.bufferedReader().use { reader ->
                        reader.forEachLine { line ->
                            sessionManager.updateSessionOutput(id, line)
                            progressParser.parseProgress(line)?.let { sessionManager.updateSessionProgress(id, it.percentage) }
                            notify { callback?.onOutput(line) }
                        }
                    }
                    code = process.waitFor()
                }
            } catch (e: Exception) {
                notify { callback?.onError(e.message ?: e.toString()) }
            } finally {
                synchronized(task) {
                    task.process?.destroy()
                    if (task.cancelled) code = 255
                }
                tasks.remove(id)
                if (code == 0) sessionManager.updateSessionProgress(id, 1f)
                sessionManager.updateSessionState(id, when (code) {
                    0 -> FFmpegSessionManager.SessionState.COMPLETED
                    255 -> FFmpegSessionManager.SessionState.CANCELLED
                    else -> FFmpegSessionManager.SessionState.FAILED
                })
                notify { callback?.onComplete(code) }
            }
        }, "ffmpeg-$id").start()
        return id
    }

    fun cancel(sessionId: Long): Boolean {
        val task = tasks[sessionId] ?: return false
        synchronized(task) {
            task.cancelled = true
            task.process?.destroyForcibly()
        }
        return true
    }

    fun cancelAll() { tasks.keys.toList().forEach { cancel(it) } }
    fun isRunning(sessionId: Long): Boolean = tasks.containsKey(sessionId)
}
