package com.mzgs.ffmpegx

class FFmpegCommandBuilder {
    private val command = mutableListOf<String>()
    
    private fun escapeFilePath(path: String): String {
        // Escape special characters for shell command
        // Wrap in quotes if path contains spaces, parentheses, or other special characters
        return if (path.contains(Regex("[\\s()\\[\\]{}!@#\$%^&*]"))) {
            "\"$path\""
        } else {
            path
        }
    }
    
    fun input(path: String): FFmpegCommandBuilder {
        command.add("-i")
        command.add(escapeFilePath(path))
        return this
    }
    
    fun overwriteOutput(): FFmpegCommandBuilder {
        command.add("-y")
        return this
    }
    
    fun videoCodec(codec: String): FFmpegCommandBuilder {
        command.add("-c:v")
        command.add(codec)
        return this
    }
    
    fun audioCodec(codec: String): FFmpegCommandBuilder {
        command.add("-c:a")
        command.add(codec)
        return this
    }
    
    fun videoBitrate(bitrate: String): FFmpegCommandBuilder {
        command.add("-b:v")
        command.add(bitrate)
        return this
    }
    
    fun audioBitrate(bitrate: String): FFmpegCommandBuilder {
        command.add("-b:a")
        command.add(bitrate)
        return this
    }
    
    fun frameRate(fps: Int): FFmpegCommandBuilder {
        command.add("-r")
        command.add(fps.toString())
        return this
    }
    
    fun resolution(width: Int, height: Int): FFmpegCommandBuilder {
        command.add("-s")
        command.add("${width}x${height}")
        return this
    }
    
    fun scale(width: Int, height: Int): FFmpegCommandBuilder {
        command.add("-vf")
        command.add("scale=$width:$height")
        return this
    }
    
    fun aspectRatio(ratio: String): FFmpegCommandBuilder {
        command.add("-aspect")
        command.add(ratio)
        return this
    }
    
    fun duration(seconds: Double): FFmpegCommandBuilder {
        command.add("-t")
        command.add(seconds.toString())
        return this
    }
    
    fun startTime(seconds: Double): FFmpegCommandBuilder {
        command.add("-ss")
        command.add(seconds.toString())
        return this
    }
    
    fun noVideo(): FFmpegCommandBuilder {
        command.add("-vn")
        return this
    }
    
    fun noAudio(): FFmpegCommandBuilder {
        command.add("-an")
        return this
    }
    
    fun noSubtitle(): FFmpegCommandBuilder {
        command.add("-sn")
        return this
    }
    
    fun copyVideo(): FFmpegCommandBuilder {
        command.add("-c:v")
        command.add("copy")
        return this
    }
    
    fun copyAudio(): FFmpegCommandBuilder {
        command.add("-c:a")
        command.add("copy")
        return this
    }
    
    fun copyAllCodecs(): FFmpegCommandBuilder {
        command.add("-c")
        command.add("copy")
        return this
    }
    
    fun preset(preset: String): FFmpegCommandBuilder {
        command.add("-preset")
        command.add(preset)
        return this
    }
    
    fun crf(value: Int): FFmpegCommandBuilder {
        command.add("-crf")
        command.add(value.toString())
        return this
    }
    
    fun audioChannels(channels: Int): FFmpegCommandBuilder {
        command.add("-ac")
        command.add(channels.toString())
        return this
    }
    
    fun audioSampleRate(rate: Int): FFmpegCommandBuilder {
        command.add("-ar")
        command.add(rate.toString())
        return this
    }
    
    fun videoFilter(filter: String): FFmpegCommandBuilder {
        command.add("-vf")
        command.add(filter)
        return this
    }
    
    fun audioFilter(filter: String): FFmpegCommandBuilder {
        command.add("-af")
        command.add(filter)
        return this
    }
    
    fun complexFilter(filter: String): FFmpegCommandBuilder {
        command.add("-filter_complex")
        command.add(filter)
        return this
    }
    
    fun map(stream: String): FFmpegCommandBuilder {
        command.add("-map")
        command.add(stream)
        return this
    }
    
    fun customOption(option: String, value: String? = null): FFmpegCommandBuilder {
        command.add(option)
        value?.let { command.add(it) }
        return this
    }
    
    fun output(path: String): FFmpegCommandBuilder {
        command.add(escapeFilePath(path))
        return this
    }
    
    fun build(): String {
        return command.joinToString(" ")
    }
    
    fun buildAsList(): List<String> {
        return command.toList()
    }
    
    fun clear(): FFmpegCommandBuilder {
        command.clear()
        return this
    }
}