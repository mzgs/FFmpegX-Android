/**
 * FFmpeg command implementation using actual FFmpeg libraries
 * This replaces the stub and provides real FFmpeg functionality through JNI
 */

#include <jni.h>
#include <android/log.h>
#include <string.h>
#include <stdlib.h>
#include <pthread.h>

#ifdef HAVE_FFMPEG_STATIC
// Include FFmpeg headers
#include "libavcodec/avcodec.h"
#include "libavformat/avformat.h"
#include "libavfilter/avfilter.h"
#include "libavutil/avutil.h"
#include "libavutil/log.h"
#include "libavutil/error.h"
#include "libswscale/swscale.h"
#include "libswresample/swresample.h"

// External FFmpeg main function implemented in ffmpeg_main.c
extern int ffmpeg_main(int argc, char **argv);
#endif

#define LOG_TAG "FFmpegCmd"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

// Global variables for callback (shared with ffmpeg_main.c)
JavaVM *java_vm = NULL;
jobject java_callback = NULL;
jmethodID on_progress_method = NULL;
jmethodID on_output_method = NULL;
jmethodID on_error_method = NULL;

#ifdef HAVE_FFMPEG_STATIC
// Custom log callback to send FFmpeg logs to Android logcat and Java
static void ffmpeg_log_callback(void *ptr, int level, const char *fmt, va_list vargs) {
    char line[1024];
    vsnprintf(line, sizeof(line), fmt, vargs);
    
    // Remove trailing newline
    size_t len = strlen(line);
    if (len > 0 && line[len - 1] == '\n') {
        line[len - 1] = '\0';
    }
    
    // Log to Android logcat
    switch (level) {
        case AV_LOG_ERROR:
            LOGE("%s", line);
            break;
        case AV_LOG_WARNING:
            LOGW("%s", line);
            break;
        case AV_LOG_INFO:
            LOGI("%s", line);
            break;
        case AV_LOG_DEBUG:
        case AV_LOG_VERBOSE:
            LOGD("%s", line);
            break;
        default:
            LOGI("%s", line);
            break;
    }
    
    // Send to Java callback if available
    if (java_vm && java_callback && on_output_method) {
        JNIEnv *env = NULL;
        int attached = 0;
        
        if ((*java_vm)->GetEnv(java_vm, (void**)&env, JNI_VERSION_1_6) != JNI_OK) {
            if ((*java_vm)->AttachCurrentThread(java_vm, &env, NULL) == JNI_OK) {
                attached = 1;
            }
        }
        
        if (env) {
            jstring jline = (*env)->NewStringUTF(env, line);
            if (jline) {
                (*env)->CallVoidMethod(env, java_callback, on_output_method, jline);
                (*env)->DeleteLocalRef(env, jline);
            }
            
            if (attached) {
                (*java_vm)->DetachCurrentThread(java_vm);
            }
        }
    }
}
#endif // HAVE_FFMPEG_STATIC

#ifdef HAVE_FFMPEG_STATIC

// Progress callback structure
typedef struct {
    int64_t total_size;
    int64_t current_size;
    double total_time;
    double current_time;
} ProgressInfo;

static ProgressInfo progress_info = {0};

// Report progress to Java
static void report_progress(double percentage) {
    if (java_vm && java_callback && on_progress_method) {
        JNIEnv *env = NULL;
        int attached = 0;
        
        if ((*java_vm)->GetEnv(java_vm, (void**)&env, JNI_VERSION_1_6) != JNI_OK) {
            if ((*java_vm)->AttachCurrentThread(java_vm, &env, NULL) == JNI_OK) {
                attached = 1;
            }
        }
        
        if (env) {
            char progress_str[256];
            snprintf(progress_str, sizeof(progress_str), "progress:%.1f", percentage);
            jstring jprogress = (*env)->NewStringUTF(env, progress_str);
            if (jprogress) {
                (*env)->CallVoidMethod(env, java_callback, on_progress_method, jprogress);
                (*env)->DeleteLocalRef(env, jprogress);
            }
            
            if (attached) {
                (*java_vm)->DetachCurrentThread(java_vm);
            }
        }
    }
}

// Simple transcoding function (example implementation)
static int transcode_file(const char *input_file, const char *output_file) {
    AVFormatContext *input_ctx = NULL, *output_ctx = NULL;
    AVCodecContext *decoder_ctx = NULL, *encoder_ctx = NULL;
    AVStream *input_stream = NULL, *output_stream = NULL;
    AVPacket *packet = NULL;
    AVFrame *frame = NULL;
    const AVCodec *decoder = NULL, *encoder = NULL;
    int ret = 0;
    int stream_index = -1;
    
    LOGI("Starting transcoding: %s -> %s", input_file, output_file);
    
    // Open input file
    ret = avformat_open_input(&input_ctx, input_file, NULL, NULL);
    if (ret < 0) {
        LOGE("Could not open input file '%s'", input_file);
        return ret;
    }
    
    ret = avformat_find_stream_info(input_ctx, NULL);
    if (ret < 0) {
        LOGE("Could not find stream information");
        goto cleanup;
    }
    
    // Find first video stream
    for (int i = 0; i < input_ctx->nb_streams; i++) {
        if (input_ctx->streams[i]->codecpar->codec_type == AVMEDIA_TYPE_VIDEO) {
            stream_index = i;
            input_stream = input_ctx->streams[i];
            break;
        }
    }
    
    if (stream_index == -1) {
        LOGE("Could not find video stream");
        ret = AVERROR(EINVAL);
        goto cleanup;
    }
    
    // Set up decoder
    decoder = avcodec_find_decoder(input_stream->codecpar->codec_id);
    if (!decoder) {
        LOGE("Could not find decoder");
        ret = AVERROR(EINVAL);
        goto cleanup;
    }
    
    decoder_ctx = avcodec_alloc_context3(decoder);
    if (!decoder_ctx) {
        LOGE("Could not allocate decoder context");
        ret = AVERROR(ENOMEM);
        goto cleanup;
    }
    
    ret = avcodec_parameters_to_context(decoder_ctx, input_stream->codecpar);
    if (ret < 0) {
        LOGE("Could not copy codec parameters");
        goto cleanup;
    }
    
    ret = avcodec_open2(decoder_ctx, decoder, NULL);
    if (ret < 0) {
        LOGE("Could not open decoder");
        goto cleanup;
    }
    
    // Create output context
    avformat_alloc_output_context2(&output_ctx, NULL, NULL, output_file);
    if (!output_ctx) {
        LOGE("Could not create output context");
        ret = AVERROR(ENOMEM);
        goto cleanup;
    }
    
    // Add video stream to output
    output_stream = avformat_new_stream(output_ctx, NULL);
    if (!output_stream) {
        LOGE("Could not create output stream");
        ret = AVERROR(ENOMEM);
        goto cleanup;
    }
    
    // Set up encoder (using same codec for simplicity)
    encoder = avcodec_find_encoder(decoder_ctx->codec_id);
    if (!encoder) {
        // Fallback to H.264
        encoder = avcodec_find_encoder(AV_CODEC_ID_H264);
    }
    
    if (!encoder) {
        LOGE("Could not find encoder");
        ret = AVERROR(EINVAL);
        goto cleanup;
    }
    
    encoder_ctx = avcodec_alloc_context3(encoder);
    if (!encoder_ctx) {
        LOGE("Could not allocate encoder context");
        ret = AVERROR(ENOMEM);
        goto cleanup;
    }
    
    // Copy decoder parameters to encoder
    encoder_ctx->width = decoder_ctx->width;
    encoder_ctx->height = decoder_ctx->height;
    encoder_ctx->pix_fmt = decoder_ctx->pix_fmt;
    encoder_ctx->time_base = input_stream->time_base;
    encoder_ctx->framerate = av_guess_frame_rate(input_ctx, input_stream, NULL);
    
    // Open encoder
    ret = avcodec_open2(encoder_ctx, encoder, NULL);
    if (ret < 0) {
        LOGE("Could not open encoder");
        goto cleanup;
    }
    
    ret = avcodec_parameters_from_context(output_stream->codecpar, encoder_ctx);
    if (ret < 0) {
        LOGE("Could not copy codec parameters");
        goto cleanup;
    }
    
    // Open output file
    if (!(output_ctx->oformat->flags & AVFMT_NOFILE)) {
        ret = avio_open(&output_ctx->pb, output_file, AVIO_FLAG_WRITE);
        if (ret < 0) {
            LOGE("Could not open output file '%s'", output_file);
            goto cleanup;
        }
    }
    
    ret = avformat_write_header(output_ctx, NULL);
    if (ret < 0) {
        LOGE("Could not write header");
        goto cleanup;
    }
    
    // Allocate packet and frame
    packet = av_packet_alloc();
    frame = av_frame_alloc();
    
    if (!packet || !frame) {
        ret = AVERROR(ENOMEM);
        goto cleanup;
    }
    
    // Simple copy loop (for demonstration - real transcoding would decode/encode)
    while (av_read_frame(input_ctx, packet) >= 0) {
        if (packet->stream_index == stream_index) {
            // Rescale timestamps
            av_packet_rescale_ts(packet, input_stream->time_base, output_stream->time_base);
            packet->stream_index = output_stream->index;
            
            // Write packet
            ret = av_interleaved_write_frame(output_ctx, packet);
            if (ret < 0) {
                LOGE("Error writing frame");
                break;
            }
            
            // Report progress
            if (input_ctx->duration > 0 && packet->pts != AV_NOPTS_VALUE) {
                double progress = (double)packet->pts / (double)input_stream->duration * 100.0;
                report_progress(progress);
            }
        }
        av_packet_unref(packet);
    }
    
    av_write_trailer(output_ctx);
    
    LOGI("Transcoding completed");
    
cleanup:
    if (frame) av_frame_free(&frame);
    if (packet) av_packet_free(&packet);
    if (encoder_ctx) avcodec_free_context(&encoder_ctx);
    if (decoder_ctx) avcodec_free_context(&decoder_ctx);
    if (output_ctx) {
        if (!(output_ctx->oformat->flags & AVFMT_NOFILE)) {
            avio_closep(&output_ctx->pb);
        }
        avformat_free_context(output_ctx);
    }
    if (input_ctx) avformat_close_input(&input_ctx);
    
    return ret;
}

// FFmpeg main is implemented in ffmpeg_main.c
// Import the implementation from ffmpeg_main.c
extern int ffmpeg_main_impl(int argc, char **argv);

#else // !HAVE_FFMPEG_STATIC

// Stub implementation when FFmpeg libraries are not available
int ffmpeg_main(int argc, char **argv) {
    LOGW("FFmpeg static libraries not linked. This is a stub implementation.");
    LOGI("To enable real FFmpeg functionality:");
    LOGI("1. Run: ./build-ffmpeg-static-libs.sh");
    LOGI("2. Rebuild the project");
    
    // Log the arguments for debugging
    for (int i = 0; i < argc; i++) {
        LOGI("  arg[%d]: %s", i, argv[i]);
    }
    
    return 0;
}

#endif // HAVE_FFMPEG_STATIC

// JNI function to set callback
JNIEXPORT void JNICALL
Java_com_mzgs_ffmpegx_FFmpegNative_nativeSetCallback(JNIEnv *env, jobject thiz, jobject callback) {
    // Store JavaVM reference
    (*env)->GetJavaVM(env, &java_vm);
    
    // Clear previous callback
    if (java_callback) {
        (*env)->DeleteGlobalRef(env, java_callback);
        java_callback = NULL;
    }
    
    if (callback) {
        // Store global reference to callback
        java_callback = (*env)->NewGlobalRef(env, callback);
        
        // Get method IDs
        jclass callback_class = (*env)->GetObjectClass(env, callback);
        on_progress_method = (*env)->GetMethodID(env, callback_class, "onProgress", "(Ljava/lang/String;)V");
        on_output_method = (*env)->GetMethodID(env, callback_class, "onOutput", "(Ljava/lang/String;)V");
        on_error_method = (*env)->GetMethodID(env, callback_class, "onError", "(Ljava/lang/String;)V");
    }
}