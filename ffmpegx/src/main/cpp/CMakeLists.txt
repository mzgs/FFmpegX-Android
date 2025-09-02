cmake_minimum_required(VERSION 3.22.1)

project("ffmpegx")

set(CMAKE_CXX_STANDARD 17)

# Fix for Android 15+ 16KB page size requirement
# Ensures all LOAD segments are aligned at 16KB boundaries
set(CMAKE_SHARED_LINKER_FLAGS "${CMAKE_SHARED_LINKER_FLAGS} -Wl,-z,max-page-size=16384")

find_library(log-lib log)
find_library(android-lib android)

# Main FFmpeg wrapper library
add_library(ffmpeg-wrapper SHARED
        ffmpeg-wrapper.cpp)

# Native execution wrapper for Android 10+
add_library(ffmpeg_wrapper SHARED
        ffmpeg_wrapper.c)

target_link_libraries(ffmpeg-wrapper
        ${log-lib}
        ${android-lib})

target_link_libraries(ffmpeg_wrapper
        ${log-lib})

# FFmpeg JNI library for Android 10+ compatibility
add_library(ffmpeg_lib_jni SHARED
        ffmpeg_lib_jni.c)

target_link_libraries(ffmpeg_lib_jni
        ${log-lib}
        ${android-lib})

# Original JNI library
add_library(ffmpeg-jni SHARED
        ffmpeg-jni.cpp)

target_link_libraries(ffmpeg-jni
        ${log-lib}
        ${android-lib})