# FFmpeg static libraries configuration for Android JNI

set(FFMPEG_ROOT_DIR ${CMAKE_CURRENT_LIST_DIR})
set(FFMPEG_INCLUDE_DIR ${FFMPEG_ROOT_DIR}/include)

# Function to add FFmpeg libraries for current ABI
function(add_ffmpeg_static_libraries target)
    # Determine the ABI-specific library directory
    set(FFMPEG_LIB_DIR ${FFMPEG_ROOT_DIR}/${ANDROID_ABI}/lib)
    
    if(NOT EXISTS ${FFMPEG_LIB_DIR})
        message(WARNING "FFmpeg libraries not found for ABI: ${ANDROID_ABI}")
        return()
    endif()
    
    # Include directories
    target_include_directories(${target} PRIVATE ${FFMPEG_INCLUDE_DIR})
    
    # Link FFmpeg static libraries in the correct order
    target_link_libraries(${target}
        ${FFMPEG_LIB_DIR}/libavformat.a
        ${FFMPEG_LIB_DIR}/libavcodec.a
        ${FFMPEG_LIB_DIR}/libavfilter.a
        ${FFMPEG_LIB_DIR}/libswscale.a
        ${FFMPEG_LIB_DIR}/libswresample.a
        ${FFMPEG_LIB_DIR}/libavutil.a
        ${FFMPEG_LIB_DIR}/libavdevice.a
        ${FFMPEG_LIB_DIR}/libpostproc.a
        # System libraries
        z
        m
        log
        android
    )
    
    # Add LAME library if available
    set(LAME_LIB "${CMAKE_CURRENT_LIST_DIR}/../lame-libs/${ANDROID_ABI}/lib/libmp3lame.a")
    if(EXISTS ${LAME_LIB})
        target_link_libraries(${target} ${LAME_LIB})
        message(STATUS "Added LAME MP3 encoder for ${ANDROID_ABI}")
    endif()
    
    message(STATUS "FFmpeg static libraries configured for ${target} (${ANDROID_ABI})")
endfunction()
