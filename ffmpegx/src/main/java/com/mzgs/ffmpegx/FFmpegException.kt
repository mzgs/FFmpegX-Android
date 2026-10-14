package com.mzgs.ffmpegx

sealed class FFmpegException(message: String) : Exception(message) {
    
    class InstallationException(message: String) : FFmpegException(message)
    
    class InvalidInputException(message: String) : FFmpegException(message)
    
    class InvalidOutputException(message: String) : FFmpegException(message)
    
    class CommandExecutionException(message: String) : FFmpegException(message)
    
    class FileNotFoundException(path: String) : FFmpegException("File not found: $path")
    
    class InvalidFormatException(format: String) : FFmpegException("Invalid format: $format")
    
    class InsufficientPermissionsException(message: String) : FFmpegException(message)
    
    class ProcessCancelledException : FFmpegException("Process was cancelled")
    
    class TimeoutException(timeout: Long) : FFmpegException("Process timed out after ${timeout}ms")
    
    companion object {
        fun fromReturnCode(returnCode: Int): FFmpegException {
            return when (returnCode) {
                255 -> ProcessCancelledException()
                1 -> CommandExecutionException("General error during execution")
                else -> CommandExecutionException("Unknown error with return code: $returnCode")
            }
        }
    }
}