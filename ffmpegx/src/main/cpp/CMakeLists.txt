cmake_minimum_required(VERSION 3.22.1)

project("ffmpeglib")

set(CMAKE_CXX_STANDARD 17)

# Fix for Android 15+ 16KB page size requirement
# Ensures all LOAD segments are aligned at 16KB boundaries
set(CMAKE_SHARED_LINKER_FLAGS "${CMAKE_SHARED_LINKER_FLAGS} -Wl,-z,max-page-size=16384")

find_library(log-lib log)
find_library(android-lib android)

# Add path to FFmpeg static libraries (built by build-ffmpeg-static-libs.sh)
set(FFMPEG_LIBS_DIR ${CMAKE_SOURCE_DIR}/ffmpeg-libs)

# Check if FFmpeg libraries exist
if(EXISTS ${FFMPEG_LIBS_DIR}/FFmpegConfig.cmake)
    include(${FFMPEG_LIBS_DIR}/FFmpegConfig.cmake)
    message(STATUS "Found FFmpeg static libraries at ${FFMPEG_LIBS_DIR}")
    set(HAVE_FFMPEG_STATIC TRUE)
else()
    message(WARNING "FFmpeg static libraries not found. Run build-ffmpeg-static-libs.sh first")
    set(HAVE_FFMPEG_STATIC FALSE)
endif()

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

# New FFmpeg Native JNI library for SDK 34+
add_library(ffmpeg_native_jni SHARED
        ffmpeg_native_jni.cpp
        ffmpeg_native_executor_jni.cpp
        ffmpeg_native_loader_jni.cpp
        ffmpeg_cmd.c
        ffmpeg_main.c
        ffmpeg_transcoder.c)  # Add the full transcoding implementation

# Link with FFmpeg static libraries if available
if(HAVE_FFMPEG_STATIC)
    # First add the external library paths
    set(LAME_LIB_PATH ${CMAKE_SOURCE_DIR}/lame-libs/${ANDROID_ABI}/lib/libmp3lame.a)
    
    # Check if external libraries exist in /tmp/ffmpeg-full-build
    set(X264_LIB_PATH /tmp/ffmpeg-full-build/x264-install/${ANDROID_ABI}/lib/libx264.a)
    set(OPENSSL_SSL_LIB /tmp/ffmpeg-full-build/openssl-install/${ANDROID_ABI}/lib/libssl.a)
    set(OPENSSL_CRYPTO_LIB /tmp/ffmpeg-full-build/openssl-install/${ANDROID_ABI}/lib/libcrypto.a)
    
    # Link libraries in the correct order (dependencies must come after the libraries that use them)
    target_include_directories(ffmpeg_native_jni PRIVATE ${FFMPEG_LIBS_DIR}/include)
    target_link_libraries(ffmpeg_native_jni
        ${FFMPEG_LIBS_DIR}/${ANDROID_ABI}/lib/libavformat.a
        ${FFMPEG_LIBS_DIR}/${ANDROID_ABI}/lib/libavcodec.a
        ${X264_LIB_PATH}  # x264 must come after libavcodec which uses it
        ${LAME_LIB_PATH}  # LAME must come after libavcodec
        ${FFMPEG_LIBS_DIR}/${ANDROID_ABI}/lib/libavfilter.a
        ${FFMPEG_LIBS_DIR}/${ANDROID_ABI}/lib/libswscale.a
        ${FFMPEG_LIBS_DIR}/${ANDROID_ABI}/lib/libswresample.a
        ${FFMPEG_LIBS_DIR}/${ANDROID_ABI}/lib/libavutil.a
        ${FFMPEG_LIBS_DIR}/${ANDROID_ABI}/lib/libavdevice.a
        ${FFMPEG_LIBS_DIR}/${ANDROID_ABI}/lib/libpostproc.a
        ${OPENSSL_SSL_LIB}  # OpenSSL SSL library
        ${OPENSSL_CRYPTO_LIB}  # OpenSSL crypto library
        z
        m
        dl  # Dynamic loader for OpenSSL
        log
        android
    )
    target_compile_definitions(ffmpeg_native_jni PRIVATE 
        HAVE_FFMPEG_STATIC=1
        _GNU_SOURCE=1
        FFMPEG_CONFIGURATION=""
        )
else()
    # Fallback: just link with system libraries
    target_link_libraries(ffmpeg_native_jni
            ${log-lib}
            ${android-lib})
endif()