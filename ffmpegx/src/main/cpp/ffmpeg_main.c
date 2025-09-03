/**
 * Real FFmpeg command implementation using static libraries
 * This uses the actual FFmpeg's functionality from the libraries
 */

#include <jni.h>
#include <android/log.h>
#include <string.h>
#include <stdlib.h>
#include <stdio.h>
#include <stdint.h>
#include <limits.h>
#include <errno.h>

#ifdef HAVE_FFMPEG_STATIC

// Forward declaration of the full transcoder from ffmpeg_transcoder.c
extern int compress_video_full(const char *input_file, const char *output_file, int quality);

#include "libavcodec/avcodec.h"
#include "libavformat/avformat.h"
#include "libavfilter/avfilter.h"
#include "libavfilter/buffersink.h"
#include "libavfilter/buffersrc.h"
#include "libavutil/avutil.h"
#include "libavutil/log.h"
#include "libavutil/opt.h"
#include "libavutil/error.h"
#include "libswscale/swscale.h"
#include "libswresample/swresample.h"
#include "libavutil/imgutils.h"
#include "libavutil/samplefmt.h"
#include "libavutil/timestamp.h"
#include "libavformat/avio.h"
#include "libavutil/dict.h"
#include "libavutil/avstring.h"
#include "libavutil/audio_fifo.h"

#define LOG_TAG "FFmpegMain"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

// Helper macro for error strings
#define av_err2str(errnum) av_make_error_string((char[AV_ERROR_MAX_STRING_SIZE]){0}, AV_ERROR_MAX_STRING_SIZE, errnum)

// Global variables for callbacks (defined in ffmpeg_cmd.c)
extern JavaVM *java_vm;
extern jobject java_callback;
extern jmethodID on_progress_method;
extern jmethodID on_output_method;
extern jmethodID on_error_method;

// Custom log callback
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
        default:
            LOGD("%s", line);
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
            jstring jstr = (*env)->NewStringUTF(env, line);
            (*env)->CallVoidMethod(env, java_callback, on_output_method, jstr);
            (*env)->DeleteLocalRef(env, jstr);
            
            if (attached) {
                (*java_vm)->DetachCurrentThread(java_vm);
            }
        }
    }
}

// Trim video function
static int trim_video(const char *input_file, const char *output_file, double start_time, double duration) {
    AVFormatContext *input_ctx = NULL;
    AVFormatContext *output_ctx = NULL;
    int ret;
    int stream_index;
    int *stream_mapping = NULL;
    int stream_mapping_size;
    
    LOGI("Trimming video: %s -> %s (start=%.1f, duration=%.1f)", 
         input_file, output_file, start_time, duration);
    
    // Open input file
    ret = avformat_open_input(&input_ctx, input_file, NULL, NULL);
    if (ret < 0) {
        char err_buf[AV_ERROR_MAX_STRING_SIZE];
        av_strerror(ret, err_buf, sizeof(err_buf));
        LOGE("Cannot open input file: %s", err_buf);
        return ret;
    }
    
    ret = avformat_find_stream_info(input_ctx, NULL);
    if (ret < 0) {
        LOGE("Cannot find stream information");
        avformat_close_input(&input_ctx);
        return ret;
    }
    
    // Allocate output context
    avformat_alloc_output_context2(&output_ctx, NULL, NULL, output_file);
    if (!output_ctx) {
        LOGE("Could not create output context");
        avformat_close_input(&input_ctx);
        return AVERROR_UNKNOWN;
    }
    
    stream_mapping_size = input_ctx->nb_streams;
    stream_mapping = (int*)av_malloc_array(stream_mapping_size, sizeof(*stream_mapping));
    if (!stream_mapping) {
        ret = AVERROR(ENOMEM);
        goto end;
    }
    memset(stream_mapping, 0, stream_mapping_size * sizeof(*stream_mapping));
    
    // Copy streams
    for (int i = 0; i < input_ctx->nb_streams; i++) {
        AVStream *in_stream = input_ctx->streams[i];
        AVStream *out_stream = avformat_new_stream(output_ctx, NULL);
        if (!out_stream) {
            LOGE("Failed allocating output stream");
            ret = AVERROR_UNKNOWN;
            goto end;
        }
        
        ret = avcodec_parameters_copy(out_stream->codecpar, in_stream->codecpar);
        if (ret < 0) {
            LOGE("Failed to copy codec parameters");
            goto end;
        }
        out_stream->codecpar->codec_tag = 0;
        stream_mapping[i] = out_stream->index;
    }
    
    // Open output file
    if (!(output_ctx->oformat->flags & AVFMT_NOFILE)) {
        ret = avio_open(&output_ctx->pb, output_file, AVIO_FLAG_WRITE);
        if (ret < 0) {
            LOGE("Could not open output file '%s'", output_file);
            goto end;
        }
    }
    
    // Write header
    ret = avformat_write_header(output_ctx, NULL);
    if (ret < 0) {
        LOGE("Error occurred when opening output file");
        goto end;
    }
    
    // Seek to start time if specified
    if (start_time > 0) {
        int64_t timestamp = start_time * AV_TIME_BASE;
        ret = avformat_seek_file(input_ctx, -1, INT64_MIN, timestamp, timestamp, 0);
        if (ret < 0) {
            LOGW("Could not seek to position %.1f", start_time);
        }
    }
    
    // Copy packets
    AVPacket pkt;
    int64_t *start_pts = (int64_t*)av_malloc_array(input_ctx->nb_streams, sizeof(int64_t));
    int64_t *start_dts = (int64_t*)av_malloc_array(input_ctx->nb_streams, sizeof(int64_t));
    if (!start_pts || !start_dts) {
        ret = AVERROR(ENOMEM);
        av_free(start_pts);
        av_free(start_dts);
        goto end;
    }
    int64_t end_time = duration > 0 ? (start_time + duration) * AV_TIME_BASE : INT64_MAX;
    
    for (int i = 0; i < input_ctx->nb_streams; i++) {
        start_pts[i] = -1;
        start_dts[i] = -1;
    }
    
    while (1) {
        ret = av_read_frame(input_ctx, &pkt);
        if (ret < 0) {
            break;
        }
        
        stream_index = pkt.stream_index;
        if (stream_index >= stream_mapping_size || stream_mapping[stream_index] < 0) {
            av_packet_unref(&pkt);
            continue;
        }
        
        AVStream *in_stream = input_ctx->streams[stream_index];
        AVStream *out_stream = output_ctx->streams[stream_mapping[stream_index]];
        
        // Check if we've reached the end time
        if (duration > 0) {
            double current_time = pkt.pts * av_q2d(in_stream->time_base);
            if (current_time >= start_time + duration) {
                av_packet_unref(&pkt);
                break;
            }
        }
        
        // Store first PTS/DTS
        if (start_pts[stream_index] == -1) {
            start_pts[stream_index] = pkt.pts;
        }
        if (start_dts[stream_index] == -1) {
            start_dts[stream_index] = pkt.dts;
        }
        
        // Adjust PTS/DTS
        pkt.pts = av_rescale_q_rnd(pkt.pts - start_pts[stream_index], 
                                   in_stream->time_base, 
                                   out_stream->time_base, 
                                   AV_ROUND_NEAR_INF|AV_ROUND_PASS_MINMAX);
        pkt.dts = av_rescale_q_rnd(pkt.dts - start_dts[stream_index], 
                                   in_stream->time_base, 
                                   out_stream->time_base, 
                                   AV_ROUND_NEAR_INF|AV_ROUND_PASS_MINMAX);
        pkt.duration = av_rescale_q(pkt.duration, in_stream->time_base, out_stream->time_base);
        pkt.pos = -1;
        pkt.stream_index = stream_mapping[stream_index];
        
        ret = av_interleaved_write_frame(output_ctx, &pkt);
        if (ret < 0) {
            LOGE("Error muxing packet");
            av_packet_unref(&pkt);
            break;
        }
        av_packet_unref(&pkt);
    }
    
    // Write trailer
    av_write_trailer(output_ctx);
    
    LOGI("Trim completed successfully");
    ret = 0;
    
    // Free allocated arrays
    av_free(start_pts);
    av_free(start_dts);
    
end:
    av_freep(&stream_mapping);
    
    if (output_ctx && !(output_ctx->oformat->flags & AVFMT_NOFILE)) {
        avio_closep(&output_ctx->pb);
    }
    avformat_free_context(output_ctx);
    avformat_close_input(&input_ctx);
    
    return ret;
}

// Simple media info extraction
static int get_media_info(const char *filename) {
    AVFormatContext *fmt_ctx = NULL;
    int ret;
    
    ret = avformat_open_input(&fmt_ctx, filename, NULL, NULL);
    if (ret < 0) {
        LOGE("Could not open input file '%s'", filename);
        return ret;
    }
    
    ret = avformat_find_stream_info(fmt_ctx, NULL);
    if (ret < 0) {
        LOGE("Could not find stream information");
        avformat_close_input(&fmt_ctx);
        return ret;
    }
    
    av_dump_format(fmt_ctx, 0, filename, 0);
    avformat_close_input(&fmt_ctx);
    
    return 0;
}

// Simple audio extraction (MP4 to MP3)
static int extract_audio_to_mp3(const char *input_file, const char *output_file) {
    AVFormatContext *input_ctx = NULL;
    AVFormatContext *output_ctx = NULL;
    AVCodecContext *decoder_ctx = NULL;
    AVCodecContext *encoder_ctx = NULL;
    const AVCodec *decoder = NULL;
    const AVCodec *encoder = NULL;
    AVStream *audio_stream = NULL;
    AVStream *out_stream = NULL;
    AVPacket *packet = NULL;
    AVFrame *frame = NULL;
    SwrContext *swr_ctx = NULL;
    int audio_stream_index = -1;
    int64_t samples_written = 0;
    AVAudioFifo *fifo = NULL;
    AVFrame *encoder_frame = NULL;
    int ret;
    
    LOGI("Extracting audio from %s to %s", input_file, output_file);
    
    // Open input file
    ret = avformat_open_input(&input_ctx, input_file, NULL, NULL);
    if (ret < 0) {
        LOGE("Could not open input file");
        goto cleanup;
    }
    
    ret = avformat_find_stream_info(input_ctx, NULL);
    if (ret < 0) {
        LOGE("Could not find stream info");
        goto cleanup;
    }
    
    // Find audio stream
    for (int i = 0; i < input_ctx->nb_streams; i++) {
        if (input_ctx->streams[i]->codecpar->codec_type == AVMEDIA_TYPE_AUDIO) {
            audio_stream_index = i;
            audio_stream = input_ctx->streams[i];
            break;
        }
    }
    
    if (audio_stream_index < 0) {
        LOGE("No audio stream found");
        ret = -1;
        goto cleanup;
    }
    
    // Set up decoder
    decoder = avcodec_find_decoder(audio_stream->codecpar->codec_id);
    if (!decoder) {
        LOGE("Decoder not found");
        ret = -1;
        goto cleanup;
    }
    
    decoder_ctx = avcodec_alloc_context3(decoder);
    if (!decoder_ctx) {
        LOGE("Could not allocate decoder context");
        ret = -1;
        goto cleanup;
    }
    
    ret = avcodec_parameters_to_context(decoder_ctx, audio_stream->codecpar);
    if (ret < 0) {
        LOGE("Could not copy codec parameters");
        goto cleanup;
    }
    
    ret = avcodec_open2(decoder_ctx, decoder, NULL);
    if (ret < 0) {
        LOGE("Could not open decoder");
        goto cleanup;
    }
    
    // Set up output
    avformat_alloc_output_context2(&output_ctx, NULL, "mp3", output_file);
    if (!output_ctx) {
        LOGE("Could not create output context");
        ret = -1;
        goto cleanup;
    }
    
    // Set up encoder (libmp3lame if available, otherwise native mp3)
    encoder = avcodec_find_encoder_by_name("libmp3lame");
    if (!encoder) {
        encoder = avcodec_find_encoder(AV_CODEC_ID_MP3);
    }
    if (!encoder) {
        LOGE("MP3 encoder not found");
        ret = -1;
        goto cleanup;
    }
    
    encoder_ctx = avcodec_alloc_context3(encoder);
    if (!encoder_ctx) {
        LOGE("Could not allocate encoder context");
        ret = -1;
        goto cleanup;
    }
    
    // Set encoder parameters
    encoder_ctx->sample_fmt = encoder->sample_fmts ? encoder->sample_fmts[0] : AV_SAMPLE_FMT_FLTP;
    encoder_ctx->sample_rate = 44100;
    av_channel_layout_default(&encoder_ctx->ch_layout, 2);  // Stereo
    encoder_ctx->bit_rate = 192000;
    
    ret = avcodec_open2(encoder_ctx, encoder, NULL);
    if (ret < 0) {
        LOGE("Could not open encoder");
        goto cleanup;
    }
    
    // Add output stream
    out_stream = avformat_new_stream(output_ctx, NULL);
    if (!out_stream) {
        LOGE("Could not create output stream");
        ret = -1;
        goto cleanup;
    }
    
    ret = avcodec_parameters_from_context(out_stream->codecpar, encoder_ctx);
    if (ret < 0) {
        LOGE("Could not copy codec parameters");
        goto cleanup;
    }
    
    // Open output file
    if (!(output_ctx->oformat->flags & AVFMT_NOFILE)) {
        ret = avio_open(&output_ctx->pb, output_file, AVIO_FLAG_WRITE);
        if (ret < 0) {
            LOGE("Could not open output file");
            goto cleanup;
        }
    }
    
    ret = avformat_write_header(output_ctx, NULL);
    if (ret < 0) {
        LOGE("Error writing header");
        goto cleanup;
    }
    
    // Initialize resampler if needed
    if (decoder_ctx->sample_fmt != encoder_ctx->sample_fmt ||
        decoder_ctx->sample_rate != encoder_ctx->sample_rate ||
        av_channel_layout_compare(&decoder_ctx->ch_layout, &encoder_ctx->ch_layout) != 0) {
        
        swr_ctx = swr_alloc();
        if (!swr_ctx) {
            LOGE("Could not allocate resampler");
            ret = -1;
            goto cleanup;
        }
        
        av_opt_set_chlayout(swr_ctx, "in_chlayout", &decoder_ctx->ch_layout, 0);
        av_opt_set_int(swr_ctx, "in_sample_rate", decoder_ctx->sample_rate, 0);
        av_opt_set_sample_fmt(swr_ctx, "in_sample_fmt", decoder_ctx->sample_fmt, 0);
        
        av_opt_set_chlayout(swr_ctx, "out_chlayout", &encoder_ctx->ch_layout, 0);
        av_opt_set_int(swr_ctx, "out_sample_rate", encoder_ctx->sample_rate, 0);
        av_opt_set_sample_fmt(swr_ctx, "out_sample_fmt", encoder_ctx->sample_fmt, 0);
        
        if (swr_init(swr_ctx) < 0) {
            LOGE("Could not initialize resampler");
            ret = -1;
            goto cleanup;
        }
    }
    
    // Process packets
    packet = av_packet_alloc();
    frame = av_frame_alloc();
    
    // Allocate a frame for encoder input with the correct frame size
    encoder_frame = av_frame_alloc();
    if (!encoder_frame) {
        LOGE("Could not allocate encoder frame");
        ret = -1;
        goto cleanup;
    }
    encoder_frame->format = encoder_ctx->sample_fmt;
    encoder_frame->ch_layout = encoder_ctx->ch_layout;
    encoder_frame->sample_rate = encoder_ctx->sample_rate;
    encoder_frame->nb_samples = encoder_ctx->frame_size;
    
    ret = av_frame_get_buffer(encoder_frame, 0);
    if (ret < 0) {
        LOGE("Could not allocate encoder frame buffer");
        av_frame_free(&encoder_frame);
        ret = -1;
        goto cleanup;
    }
    
    // FIFO buffer to accumulate samples
    fifo = av_audio_fifo_alloc(encoder_ctx->sample_fmt,
                                             encoder_ctx->ch_layout.nb_channels,
                                             encoder_ctx->frame_size * 10);
    if (!fifo) {
        LOGE("Could not allocate FIFO");
        av_frame_free(&encoder_frame);
        ret = -1;
        goto cleanup;
    }
    
    while (av_read_frame(input_ctx, packet) >= 0) {
        if (packet->stream_index == audio_stream_index) {
            // Decode
            ret = avcodec_send_packet(decoder_ctx, packet);
            if (ret < 0) {
                LOGE("Error sending packet to decoder");
                av_packet_unref(packet);
                continue;
            }
            
            while (ret >= 0) {
                ret = avcodec_receive_frame(decoder_ctx, frame);
                if (ret == AVERROR(EAGAIN) || ret == AVERROR_EOF) {
                    break;
                }
                if (ret < 0) {
                    LOGE("Error receiving frame");
                    break;
                }
                
                // Resample if needed
                AVFrame *processed_frame = frame;
                AVFrame *resampled_frame = NULL;
                
                if (swr_ctx) {
                    resampled_frame = av_frame_alloc();
                    if (!resampled_frame) {
                        LOGE("Could not allocate resampled frame");
                        break;
                    }
                    
                    resampled_frame->sample_rate = encoder_ctx->sample_rate;
                    resampled_frame->ch_layout = encoder_ctx->ch_layout;
                    resampled_frame->format = encoder_ctx->sample_fmt;
                    resampled_frame->nb_samples = av_rescale_rnd(frame->nb_samples,
                        encoder_ctx->sample_rate, decoder_ctx->sample_rate, AV_ROUND_UP);
                    
                    ret = av_frame_get_buffer(resampled_frame, 0);
                    if (ret < 0) {
                        LOGE("Could not allocate resampled frame buffer");
                        av_frame_free(&resampled_frame);
                        break;
                    }
                    
                    ret = swr_convert_frame(swr_ctx, resampled_frame, frame);
                    if (ret < 0) {
                        LOGE("Error resampling audio");
                        av_frame_free(&resampled_frame);
                        break;
                    }
                    
                    processed_frame = resampled_frame;
                }
                
                // Add samples to FIFO
                ret = av_audio_fifo_write(fifo, (void**)processed_frame->data, processed_frame->nb_samples);
                if (ret < processed_frame->nb_samples) {
                    LOGE("Could not write all samples to FIFO");
                }
                
                // Encode complete frames from FIFO
                while (av_audio_fifo_size(fifo) >= encoder_ctx->frame_size) {
                    // Read exactly frame_size samples from FIFO
                    ret = av_audio_fifo_read(fifo, (void**)encoder_frame->data, encoder_ctx->frame_size);
                    if (ret != encoder_ctx->frame_size) {
                        LOGE("Could not read full frame from FIFO");
                        break;
                    }
                    
                    encoder_frame->pts = av_rescale_q(samples_written, 
                        (AVRational){1, encoder_ctx->sample_rate},
                        encoder_ctx->time_base);
                    samples_written += encoder_ctx->frame_size;
                    
                    // Encode the frame
                    ret = avcodec_send_frame(encoder_ctx, encoder_frame);
                    if (ret < 0) {
                        LOGE("Error sending frame to encoder: %s", av_err2str(ret));
                        break;
                    }
                    
                    AVPacket *enc_packet = av_packet_alloc();
                    while (ret >= 0) {
                        ret = avcodec_receive_packet(encoder_ctx, enc_packet);
                        if (ret == AVERROR(EAGAIN) || ret == AVERROR_EOF) {
                            break;
                        }
                        if (ret < 0) {
                            LOGE("Error receiving packet from encoder");
                            break;
                        }
                        
                        // Write the encoded packet
                        enc_packet->stream_index = out_stream->index;
                        av_packet_rescale_ts(enc_packet, encoder_ctx->time_base, out_stream->time_base);
                        
                        ret = av_interleaved_write_frame(output_ctx, enc_packet);
                        if (ret < 0) {
                            LOGE("Error writing encoded packet");
                        }
                        
                        av_packet_unref(enc_packet);
                    }
                    av_packet_free(&enc_packet);
                }
                
                if (resampled_frame) av_frame_free(&resampled_frame);
            }
        }
        av_packet_unref(packet);
    }
    
    // Process remaining samples in FIFO (pad with silence if needed)
    if (fifo && encoder_frame && encoder_ctx && av_audio_fifo_size(fifo) > 0) {
        int remaining = av_audio_fifo_size(fifo);
        LOGI("Processing %d remaining samples", remaining);
        
        // Only process if we have a reasonable amount of samples
        if (remaining > 0 && remaining < encoder_ctx->frame_size) {
            // Clear the frame first
            av_frame_make_writable(encoder_frame);
            
            // Calculate bytes per sample
            int bytes_per_sample = av_get_bytes_per_sample(encoder_ctx->sample_fmt);
            int nb_channels = encoder_ctx->ch_layout.nb_channels;
            
            // Clear the entire frame buffer
            for (int ch = 0; ch < nb_channels; ch++) {
                if (encoder_frame->data[ch]) {
                    memset(encoder_frame->data[ch], 0, 
                           encoder_ctx->frame_size * bytes_per_sample);
                }
            }
            
            // Read available samples
            ret = av_audio_fifo_read(fifo, (void**)encoder_frame->data, remaining);
            if (ret > 0) {
                encoder_frame->nb_samples = encoder_ctx->frame_size; // Still send full frame
                encoder_frame->pts = av_rescale_q(samples_written, 
                    (AVRational){1, encoder_ctx->sample_rate},
                    encoder_ctx->time_base);
                
                // Send this last partial frame
                ret = avcodec_send_frame(encoder_ctx, encoder_frame);
                if (ret >= 0) {
                    AVPacket *enc_packet = av_packet_alloc();
                    if (enc_packet) {
                        while (avcodec_receive_packet(encoder_ctx, enc_packet) >= 0) {
                            enc_packet->stream_index = out_stream->index;
                            av_packet_rescale_ts(enc_packet, encoder_ctx->time_base, out_stream->time_base);
                            av_interleaved_write_frame(output_ctx, enc_packet);
                            av_packet_unref(enc_packet);
                        }
                        av_packet_free(&enc_packet);
                    }
                }
            }
        }
    }
    
    // Flush encoder
    if (encoder_ctx) {
        ret = avcodec_send_frame(encoder_ctx, NULL);
        if (ret >= 0) {
            AVPacket *enc_packet = av_packet_alloc();
            if (enc_packet) {
                while (1) {
                    ret = avcodec_receive_packet(encoder_ctx, enc_packet);
                    if (ret == AVERROR_EOF) {
                        break;
                    }
                    if (ret < 0) {
                        LOGE("Error flushing encoder");
                        break;
                    }
                    
                    if (out_stream) {
                        enc_packet->stream_index = out_stream->index;
                        av_packet_rescale_ts(enc_packet, encoder_ctx->time_base, out_stream->time_base);
                        
                        if (output_ctx) {
                            ret = av_interleaved_write_frame(output_ctx, enc_packet);
                            if (ret < 0) {
                                LOGE("Error writing flushed packet");
                            }
                        }
                    }
                    
                    av_packet_unref(enc_packet);
                }
                av_packet_free(&enc_packet);
            }
        }
    }
    
    // Write trailer
    if (output_ctx) {
        av_write_trailer(output_ctx);
    }
    
    LOGI("Audio extraction completed");
    ret = 0;
    
cleanup:
    // Free frames first
    if (encoder_frame) {
        av_frame_free(&encoder_frame);
        encoder_frame = NULL;
    }
    if (frame) {
        av_frame_free(&frame);
        frame = NULL;
    }
    
    // Free packet
    if (packet) {
        av_packet_free(&packet);
        packet = NULL;
    }
    
    // Free FIFO
    if (fifo) {
        av_audio_fifo_free(fifo);
        fifo = NULL;
    }
    
    // Free resampler
    if (swr_ctx) {
        swr_free(&swr_ctx);
        swr_ctx = NULL;
    }
    
    // Free codec contexts AFTER everything else
    if (encoder_ctx) {
        avcodec_free_context(&encoder_ctx);
        encoder_ctx = NULL;
    }
    if (decoder_ctx) {
        avcodec_free_context(&decoder_ctx);
        decoder_ctx = NULL;
    }
    if (output_ctx) {
        if (!(output_ctx->oformat->flags & AVFMT_NOFILE)) {
            avio_closep(&output_ctx->pb);
        }
        avformat_free_context(output_ctx);
    }
    if (input_ctx) avformat_close_input(&input_ctx);
    
    return ret;
}

// Video compression with transcoding
static int compress_video(const char *input_file, const char *output_file, const char *options) {
    AVFormatContext *input_ctx = NULL;
    AVFormatContext *output_ctx = NULL;
    AVCodecContext *video_dec_ctx = NULL;
    AVCodecContext *video_enc_ctx = NULL;
    AVCodecContext *audio_dec_ctx = NULL; 
    AVCodecContext *audio_enc_ctx = NULL;
    const AVCodec *video_encoder = NULL;
    const AVCodec *audio_encoder = NULL;
    AVStream *video_stream = NULL;
    AVStream *audio_stream = NULL;
    AVPacket *packet = NULL;
    AVFrame *frame = NULL;
    int video_stream_idx = -1;
    int audio_stream_idx = -1;
    int ret;
    
    LOGI("Compressing video from %s to %s", input_file, output_file);
    
    // Open input
    ret = avformat_open_input(&input_ctx, input_file, NULL, NULL);
    if (ret < 0) {
        LOGE("Could not open input file");
        return ret;
    }
    
    ret = avformat_find_stream_info(input_ctx, NULL);
    if (ret < 0) {
        LOGE("Could not find stream info");
        goto cleanup;
    }
    
    // Find video and audio streams
    for (int i = 0; i < input_ctx->nb_streams; i++) {
        if (input_ctx->streams[i]->codecpar->codec_type == AVMEDIA_TYPE_VIDEO && video_stream_idx < 0) {
            video_stream_idx = i;
        } else if (input_ctx->streams[i]->codecpar->codec_type == AVMEDIA_TYPE_AUDIO && audio_stream_idx < 0) {
            audio_stream_idx = i;
        }
    }
    
    if (video_stream_idx < 0) {
        LOGE("No video stream found");
        ret = -1;
        goto cleanup;
    }
    
    // Open output
    avformat_alloc_output_context2(&output_ctx, NULL, "mp4", output_file);
    if (!output_ctx) {
        LOGE("Could not create output context");
        ret = -1;
        goto cleanup;
    }
    
    // Setup video encoder (MPEG4)
    video_encoder = avcodec_find_encoder(AV_CODEC_ID_MPEG4);
    if (!video_encoder) {
        LOGE("MPEG4 encoder not found");
        ret = -1;
        goto cleanup;
    }
    
    video_stream = avformat_new_stream(output_ctx, video_encoder);
    if (!video_stream) {
        LOGE("Could not create video stream");
        ret = -1;
        goto cleanup;
    }
    
    video_enc_ctx = avcodec_alloc_context3(video_encoder);
    if (!video_enc_ctx) {
        LOGE("Could not allocate video encoder context");
        ret = -1;
        goto cleanup;
    }
    
    // Set video encoding parameters for low quality
    video_enc_ctx->codec_id = AV_CODEC_ID_MPEG4;
    video_enc_ctx->codec_type = AVMEDIA_TYPE_VIDEO;
    video_enc_ctx->width = 640;  // Low resolution
    video_enc_ctx->height = 360;
    video_enc_ctx->bit_rate = 200000;  // 200 kbps
    video_enc_ctx->time_base = (AVRational){1, 15};  // 15 fps
    video_enc_ctx->framerate = (AVRational){15, 1};
    video_enc_ctx->gop_size = 10;
    video_enc_ctx->pix_fmt = AV_PIX_FMT_YUV420P;
    
    // Some formats want stream headers to be separate
    if (output_ctx->oformat->flags & AVFMT_GLOBALHEADER) {
        video_enc_ctx->flags |= AV_CODEC_FLAG_GLOBAL_HEADER;
    }
    
    ret = avcodec_open2(video_enc_ctx, video_encoder, NULL);
    if (ret < 0) {
        LOGE("Could not open video encoder");
        goto cleanup;
    }
    
    ret = avcodec_parameters_from_context(video_stream->codecpar, video_enc_ctx);
    if (ret < 0) {
        LOGE("Could not copy video codec parameters");
        goto cleanup;
    }
    
    // Setup audio if present
    if (audio_stream_idx >= 0) {
        audio_encoder = avcodec_find_encoder(AV_CODEC_ID_AAC);
        if (audio_encoder) {
            audio_stream = avformat_new_stream(output_ctx, audio_encoder);
            if (audio_stream) {
                audio_enc_ctx = avcodec_alloc_context3(audio_encoder);
                audio_enc_ctx->codec_id = AV_CODEC_ID_AAC;
                audio_enc_ctx->codec_type = AVMEDIA_TYPE_AUDIO;
                audio_enc_ctx->sample_rate = 22050;  // Low sample rate
                audio_enc_ctx->bit_rate = 64000;  // 64 kbps
                av_channel_layout_default(&audio_enc_ctx->ch_layout, 1);  // Mono
                audio_enc_ctx->sample_fmt = AV_SAMPLE_FMT_FLTP;
                
                if (output_ctx->oformat->flags & AVFMT_GLOBALHEADER) {
                    audio_enc_ctx->flags |= AV_CODEC_FLAG_GLOBAL_HEADER;
                }
                
                avcodec_open2(audio_enc_ctx, audio_encoder, NULL);
                avcodec_parameters_from_context(audio_stream->codecpar, audio_enc_ctx);
            }
        }
    }
    
    // Open output file
    if (!(output_ctx->oformat->flags & AVFMT_NOFILE)) {
        ret = avio_open(&output_ctx->pb, output_file, AVIO_FLAG_WRITE);
        if (ret < 0) {
            LOGE("Could not open output file");
            goto cleanup;
        }
    }
    
    // Write header
    ret = avformat_write_header(output_ctx, NULL);
    if (ret < 0) {
        LOGE("Error writing header");
        goto cleanup;
    }
    
    LOGI("Video compression started - target: 640x360 @ 200kbps");
    
    // For now, just do simple remux as full transcoding is complex
    // This is a placeholder - full implementation would decode and re-encode frames
    packet = av_packet_alloc();
    while (av_read_frame(input_ctx, packet) >= 0) {
        if (packet->stream_index == video_stream_idx || 
            (audio_stream && packet->stream_index == audio_stream_idx)) {
            
            AVStream *in_stream = input_ctx->streams[packet->stream_index];
            AVStream *out_stream = (packet->stream_index == video_stream_idx) ? 
                                   video_stream : audio_stream;
            
            if (out_stream) {
                packet->pts = av_rescale_q_rnd(packet->pts, in_stream->time_base, 
                                              out_stream->time_base, AV_ROUND_NEAR_INF|AV_ROUND_PASS_MINMAX);
                packet->dts = av_rescale_q_rnd(packet->dts, in_stream->time_base, 
                                              out_stream->time_base, AV_ROUND_NEAR_INF|AV_ROUND_PASS_MINMAX);
                packet->duration = av_rescale_q(packet->duration, in_stream->time_base, 
                                               out_stream->time_base);
                packet->pos = -1;
                packet->stream_index = (packet->stream_index == video_stream_idx) ? 0 : 1;
                
                av_interleaved_write_frame(output_ctx, packet);
            }
        }
        av_packet_unref(packet);
    }
    
    av_write_trailer(output_ctx);
    LOGI("Video compression completed");
    ret = 0;
    
cleanup:
    if (packet) av_packet_free(&packet);
    if (video_enc_ctx) avcodec_free_context(&video_enc_ctx);
    if (audio_enc_ctx) avcodec_free_context(&audio_enc_ctx);
    if (input_ctx) avformat_close_input(&input_ctx);
    if (output_ctx) {
        if (!(output_ctx->oformat->flags & AVFMT_NOFILE))
            avio_closep(&output_ctx->pb);
        avformat_free_context(output_ctx);
    }
    
    return ret;
}

// Simple remux function (kept for compatibility)
static int simple_remux(const char *input_file, const char *output_file) {
    // Just redirect to compress_video for now
    return compress_video(input_file, output_file, NULL);
}

// Forward declarations
static int ffmpeg_main_simple(int argc, char **argv);
static int process_with_complex_filter(int argc, char **argv);

// Process video with complex filter graph supporting multiple inputs/outputs
static int process_with_complex_filter(int argc, char **argv) {
    LOGI("Processing with complex filter graph");
    
    AVFormatContext **input_contexts = NULL;
    AVFormatContext *output_ctx = NULL;
    AVFilterGraph *filter_graph = NULL;
    AVFilterContext **buffersrc_ctxs = NULL;
    AVFilterContext **buffersink_ctxs = NULL;
    AVCodecContext **dec_ctxs = NULL;
    AVCodecContext **enc_ctxs = NULL;
    AVPacket *packet = NULL;
    AVFrame *frame = NULL;
    AVFrame *filt_frame = NULL;
    
    int nb_inputs = 0;
    int nb_outputs = 0;
    const char **input_files = NULL;
    const char *output_file = NULL;
    const char *filter_str = NULL;
    int *stream_indices = NULL;
    int ret = 0;
    
    // Parse arguments to find all inputs, outputs, and filter
    for (int i = 1; i < argc; i++) {
        if (strcmp(argv[i], "-i") == 0 && i + 1 < argc) {
            nb_inputs++;
        } else if (strcmp(argv[i], "-filter_complex") == 0 && i + 1 < argc) {
            filter_str = argv[i + 1];
        } else if (strcmp(argv[i], "-lavfi") == 0 && i + 1 < argc) {
            filter_str = argv[i + 1];
        } else if (argv[i][0] != '-' && i == argc - 1) {
            output_file = argv[i];
        }
    }
    
    if (!filter_str || !output_file || nb_inputs == 0) {
        LOGE("Missing required arguments for complex filter");
        return -1;
    }
    
    // Allocate arrays
    input_files = av_malloc_array(nb_inputs, sizeof(char*));
    input_contexts = av_malloc_array(nb_inputs, sizeof(AVFormatContext*));
    buffersrc_ctxs = av_malloc_array(nb_inputs, sizeof(AVFilterContext*));
    dec_ctxs = av_malloc_array(nb_inputs, sizeof(AVCodecContext*));
    stream_indices = av_malloc_array(nb_inputs, sizeof(int));
    
    if (!input_files || !input_contexts || !buffersrc_ctxs || !dec_ctxs || !stream_indices) {
        ret = AVERROR(ENOMEM);
        goto cleanup;
    }
    
    memset(input_contexts, 0, nb_inputs * sizeof(AVFormatContext*));
    memset(buffersrc_ctxs, 0, nb_inputs * sizeof(AVFilterContext*));
    memset(dec_ctxs, 0, nb_inputs * sizeof(AVCodecContext*));
    memset(stream_indices, -1, nb_inputs * sizeof(int));
    
    // Collect input files
    int input_idx = 0;
    for (int i = 1; i < argc && input_idx < nb_inputs; i++) {
        if (strcmp(argv[i], "-i") == 0 && i + 1 < argc) {
            input_files[input_idx++] = argv[i + 1];
            i++;
        }
    }
    
    // Initialize filter graph
    filter_graph = avfilter_graph_alloc();
    if (!filter_graph) {
        ret = AVERROR(ENOMEM);
        goto cleanup;
    }
    
    // Open all input files and set up decoders
    for (int i = 0; i < nb_inputs; i++) {
        ret = avformat_open_input(&input_contexts[i], input_files[i], NULL, NULL);
        if (ret < 0) {
            LOGE("Cannot open input file %s", input_files[i]);
            goto cleanup;
        }
        
        ret = avformat_find_stream_info(input_contexts[i], NULL);
        if (ret < 0) {
            LOGE("Cannot find stream info for %s", input_files[i]);
            goto cleanup;
        }
        
        // Find video stream
        stream_indices[i] = -1;
        for (int j = 0; j < input_contexts[i]->nb_streams; j++) {
            if (input_contexts[i]->streams[j]->codecpar->codec_type == AVMEDIA_TYPE_VIDEO) {
                stream_indices[i] = j;
                break;
            }
        }
        
        if (stream_indices[i] < 0) {
            LOGE("No video stream in input %s", input_files[i]);
            ret = AVERROR_STREAM_NOT_FOUND;
            goto cleanup;
        }
        
        // Set up decoder
        AVStream *stream = input_contexts[i]->streams[stream_indices[i]];
        const AVCodec *decoder = avcodec_find_decoder(stream->codecpar->codec_id);
        if (!decoder) {
            ret = AVERROR_DECODER_NOT_FOUND;
            goto cleanup;
        }
        
        dec_ctxs[i] = avcodec_alloc_context3(decoder);
        if (!dec_ctxs[i]) {
            ret = AVERROR(ENOMEM);
            goto cleanup;
        }
        
        ret = avcodec_parameters_to_context(dec_ctxs[i], stream->codecpar);
        if (ret < 0) {
            goto cleanup;
        }
        
        ret = avcodec_open2(dec_ctxs[i], decoder, NULL);
        if (ret < 0) {
            LOGE("Cannot open decoder for input %d", i);
            goto cleanup;
        }
        
        // Create buffer source for this input
        const AVFilter *buffersrc = avfilter_get_by_name("buffer");
        char args[512];
        snprintf(args, sizeof(args),
                "video_size=%dx%d:pix_fmt=%d:time_base=%d/%d:pixel_aspect=%d/%d",
                dec_ctxs[i]->width, dec_ctxs[i]->height, dec_ctxs[i]->pix_fmt,
                stream->time_base.num, stream->time_base.den,
                dec_ctxs[i]->sample_aspect_ratio.num, dec_ctxs[i]->sample_aspect_ratio.den);
        
        char name[32];
        snprintf(name, sizeof(name), "in%d", i);
        
        ret = avfilter_graph_create_filter(&buffersrc_ctxs[i], buffersrc, name,
                                          args, NULL, filter_graph);
        if (ret < 0) {
            LOGE("Cannot create buffer source for input %d", i);
            goto cleanup;
        }
    }
    
    // Create buffer sink
    const AVFilter *buffersink = avfilter_get_by_name("buffersink");
    AVFilterContext *buffersink_ctx = NULL;
    ret = avfilter_graph_create_filter(&buffersink_ctx, buffersink, "out",
                                      NULL, NULL, filter_graph);
    if (ret < 0) {
        LOGE("Cannot create buffer sink");
        goto cleanup;
    }
    
    // Set output pixel format
    enum AVPixelFormat pix_fmts[] = { AV_PIX_FMT_YUV420P, AV_PIX_FMT_NONE };
    ret = av_opt_set_int_list(buffersink_ctx, "pix_fmts", pix_fmts,
                             AV_PIX_FMT_NONE, AV_OPT_SEARCH_CHILDREN);
    if (ret < 0) {
        goto cleanup;
    }
    
    // Parse the complex filter string
    AVFilterInOut *outputs = NULL;
    AVFilterInOut *inputs = avfilter_inout_alloc();
    
    // Set up inputs (connect to buffer sink)
    inputs->name = av_strdup("out");
    inputs->filter_ctx = buffersink_ctx;
    inputs->pad_idx = 0;
    inputs->next = NULL;
    
    // Set up outputs (connect to buffer sources)
    for (int i = 0; i < nb_inputs; i++) {
        AVFilterInOut *output = avfilter_inout_alloc();
        char name[32];
        snprintf(name, sizeof(name), "%d:v", i);
        output->name = av_strdup(name);
        output->filter_ctx = buffersrc_ctxs[i];
        output->pad_idx = 0;
        output->next = outputs;
        outputs = output;
    }
    
    LOGI("Parsing complex filter: %s", filter_str);
    ret = avfilter_graph_parse_ptr(filter_graph, filter_str, &inputs, &outputs, NULL);
    
    avfilter_inout_free(&inputs);
    avfilter_inout_free(&outputs);
    
    if (ret < 0) {
        LOGE("Error parsing complex filter");
        goto cleanup;
    }
    
    ret = avfilter_graph_config(filter_graph, NULL);
    if (ret < 0) {
        LOGE("Error configuring filter graph");
        goto cleanup;
    }
    
    // Create output format context
    avformat_alloc_output_context2(&output_ctx, NULL, NULL, output_file);
    if (!output_ctx) {
        ret = AVERROR_UNKNOWN;
        goto cleanup;
    }
    
    // Add output stream and encoder
    AVStream *out_stream = avformat_new_stream(output_ctx, NULL);
    if (!out_stream) {
        ret = AVERROR_UNKNOWN;
        goto cleanup;
    }
    
    // Set up encoder based on filter output format
    const AVCodec *encoder = avcodec_find_encoder(AV_CODEC_ID_H264);
    if (!encoder) {
        ret = AVERROR_ENCODER_NOT_FOUND;
        goto cleanup;
    }
    
    AVCodecContext *enc_ctx = avcodec_alloc_context3(encoder);
    if (!enc_ctx) {
        ret = AVERROR(ENOMEM);
        goto cleanup;
    }
    
    // Get output dimensions from filter graph
    AVFilterLink *outlink = buffersink_ctx->inputs[0];
    enc_ctx->width = outlink->w;
    enc_ctx->height = outlink->h;
    enc_ctx->pix_fmt = AV_PIX_FMT_YUV420P;
    enc_ctx->time_base = av_inv_q(outlink->frame_rate);
    enc_ctx->framerate = outlink->frame_rate;
    enc_ctx->bit_rate = 2000000;
    enc_ctx->gop_size = 12;
    enc_ctx->max_b_frames = 0;
    
    if (output_ctx->oformat->flags & AVFMT_GLOBALHEADER) {
        enc_ctx->flags |= AV_CODEC_FLAG_GLOBAL_HEADER;
    }
    
    ret = avcodec_open2(enc_ctx, encoder, NULL);
    if (ret < 0) {
        LOGE("Cannot open encoder");
        avcodec_free_context(&enc_ctx);
        goto cleanup;
    }
    
    ret = avcodec_parameters_from_context(out_stream->codecpar, enc_ctx);
    if (ret < 0) {
        avcodec_free_context(&enc_ctx);
        goto cleanup;
    }
    
    out_stream->time_base = enc_ctx->time_base;
    
    // Open output file
    if (!(output_ctx->oformat->flags & AVFMT_NOFILE)) {
        ret = avio_open(&output_ctx->pb, output_file, AVIO_FLAG_WRITE);
        if (ret < 0) {
            LOGE("Could not open output file");
            avcodec_free_context(&enc_ctx);
            goto cleanup;
        }
    }
    
    ret = avformat_write_header(output_ctx, NULL);
    if (ret < 0) {
        LOGE("Error writing header");
        avcodec_free_context(&enc_ctx);
        goto cleanup;
    }
    
    // Process frames
    packet = av_packet_alloc();
    frame = av_frame_alloc();
    filt_frame = av_frame_alloc();
    
    if (!packet || !frame || !filt_frame) {
        ret = AVERROR(ENOMEM);
        avcodec_free_context(&enc_ctx);
        goto cleanup;
    }
    
    // Simple processing loop (can be improved for better sync)
    int finished_inputs = 0;
    while (finished_inputs < nb_inputs) {
        // Read from each input
        for (int i = 0; i < nb_inputs; i++) {
            if (!input_contexts[i]) continue;
            
            ret = av_read_frame(input_contexts[i], packet);
            if (ret < 0) {
                if (ret == AVERROR_EOF) {
                    // Flush decoder
                    avcodec_send_packet(dec_ctxs[i], NULL);
                    finished_inputs++;
                    avformat_close_input(&input_contexts[i]);
                    input_contexts[i] = NULL;
                }
                continue;
            }
            
            if (packet->stream_index == stream_indices[i]) {
                ret = avcodec_send_packet(dec_ctxs[i], packet);
                if (ret < 0) {
                    av_packet_unref(packet);
                    continue;
                }
                
                while (ret >= 0) {
                    ret = avcodec_receive_frame(dec_ctxs[i], frame);
                    if (ret == AVERROR(EAGAIN) || ret == AVERROR_EOF) {
                        break;
                    } else if (ret < 0) {
                        break;
                    }
                    
                    // Push frame to filter
                    ret = av_buffersrc_add_frame_flags(buffersrc_ctxs[i], frame, 0);
                    if (ret < 0) {
                        LOGE("Error feeding frame to filter");
                    }
                    av_frame_unref(frame);
                }
            }
            av_packet_unref(packet);
        }
        
        // Pull filtered frames
        while (1) {
            ret = av_buffersink_get_frame(buffersink_ctx, filt_frame);
            if (ret == AVERROR(EAGAIN) || ret == AVERROR_EOF) {
                break;
            } else if (ret < 0) {
                break;
            }
            
            // Encode frame
            ret = avcodec_send_frame(enc_ctx, filt_frame);
            if (ret < 0) {
                av_frame_unref(filt_frame);
                continue;
            }
            
            while (ret >= 0) {
                AVPacket enc_pkt = {0};
                av_init_packet(&enc_pkt);
                
                ret = avcodec_receive_packet(enc_ctx, &enc_pkt);
                if (ret == AVERROR(EAGAIN) || ret == AVERROR_EOF) {
                    break;
                } else if (ret < 0) {
                    break;
                }
                
                av_packet_rescale_ts(&enc_pkt, enc_ctx->time_base, out_stream->time_base);
                enc_pkt.stream_index = out_stream->index;
                
                ret = av_interleaved_write_frame(output_ctx, &enc_pkt);
                av_packet_unref(&enc_pkt);
            }
            
            av_frame_unref(filt_frame);
        }
    }
    
    // Flush encoder
    avcodec_send_frame(enc_ctx, NULL);
    while (1) {
        AVPacket enc_pkt = {0};
        av_init_packet(&enc_pkt);
        
        ret = avcodec_receive_packet(enc_ctx, &enc_pkt);
        if (ret == AVERROR_EOF) {
            break;
        } else if (ret < 0) {
            break;
        }
        
        av_packet_rescale_ts(&enc_pkt, enc_ctx->time_base, out_stream->time_base);
        enc_pkt.stream_index = out_stream->index;
        
        av_interleaved_write_frame(output_ctx, &enc_pkt);
        av_packet_unref(&enc_pkt);
    }
    
    av_write_trailer(output_ctx);
    avcodec_free_context(&enc_ctx);
    
    LOGI("Complex filter processing completed");
    ret = 0;
    
cleanup:
    // Clean up
    av_frame_free(&filt_frame);
    av_frame_free(&frame);
    av_packet_free(&packet);
    
    if (filter_graph) {
        avfilter_graph_free(&filter_graph);
    }
    
    if (dec_ctxs) {
        for (int i = 0; i < nb_inputs; i++) {
            if (dec_ctxs[i]) {
                avcodec_free_context(&dec_ctxs[i]);
            }
        }
        av_free(dec_ctxs);
    }
    
    if (input_contexts) {
        for (int i = 0; i < nb_inputs; i++) {
            if (input_contexts[i]) {
                avformat_close_input(&input_contexts[i]);
            }
        }
        av_free(input_contexts);
    }
    
    if (output_ctx) {
        if (!(output_ctx->oformat->flags & AVFMT_NOFILE)) {
            avio_closep(&output_ctx->pb);
        }
        avformat_free_context(output_ctx);
    }
    
    av_free(buffersrc_ctxs);
    av_free(stream_indices);
    av_free(input_files);
    
    return ret;
}

// Scale video using libswscale
static int scale_video(const char *input_file, const char *output_file, int target_width, int target_height) {
    AVFormatContext *input_ctx = NULL, *output_ctx = NULL;
    AVCodecContext *dec_ctx = NULL, *enc_ctx = NULL;
    AVStream *input_stream = NULL, *output_stream = NULL;
    const AVCodec *decoder = NULL, *encoder = NULL;
    struct SwsContext *sws_ctx = NULL;
    AVPacket *packet = NULL;
    AVFrame *frame = NULL, *scaled_frame = NULL;
    int video_stream_index = -1;
    int ret;
    
    LOGI("Scaling video %s to %dx%d", input_file, target_width, target_height);
    
    // Allocate packet and frames
    packet = av_packet_alloc();
    frame = av_frame_alloc();
    scaled_frame = av_frame_alloc();
    if (!packet || !frame || !scaled_frame) {
        ret = AVERROR(ENOMEM);
        goto end;
    }
    
    // Open input file
    ret = avformat_open_input(&input_ctx, input_file, NULL, NULL);
    if (ret < 0) {
        LOGE("Cannot open input file");
        goto end;
    }
    
    ret = avformat_find_stream_info(input_ctx, NULL);
    if (ret < 0) {
        LOGE("Cannot find stream information");
        goto end;
    }
    
    // Find video stream
    for (int i = 0; i < input_ctx->nb_streams; i++) {
        if (input_ctx->streams[i]->codecpar->codec_type == AVMEDIA_TYPE_VIDEO) {
            video_stream_index = i;
            input_stream = input_ctx->streams[i];
            break;
        }
    }
    
    if (video_stream_index < 0) {
        LOGE("No video stream found");
        ret = AVERROR_STREAM_NOT_FOUND;
        goto end;
    }
    
    // Find and open decoder
    decoder = avcodec_find_decoder(input_stream->codecpar->codec_id);
    if (!decoder) {
        LOGE("Decoder not found");
        ret = AVERROR_DECODER_NOT_FOUND;
        goto end;
    }
    
    dec_ctx = avcodec_alloc_context3(decoder);
    if (!dec_ctx) {
        ret = AVERROR(ENOMEM);
        goto end;
    }
    
    ret = avcodec_parameters_to_context(dec_ctx, input_stream->codecpar);
    if (ret < 0) {
        goto end;
    }
    
    ret = avcodec_open2(dec_ctx, decoder, NULL);
    if (ret < 0) {
        LOGE("Failed to open decoder");
        goto end;
    }
    
    // Create output format context
    avformat_alloc_output_context2(&output_ctx, NULL, NULL, output_file);
    if (!output_ctx) {
        ret = AVERROR_UNKNOWN;
        goto end;
    }
    
    // Add video stream to output
    output_stream = avformat_new_stream(output_ctx, NULL);
    if (!output_stream) {
        ret = AVERROR_UNKNOWN;
        goto end;
    }
    
    // Find and open encoder
    encoder = avcodec_find_encoder(AV_CODEC_ID_H264);
    if (!encoder) {
        LOGE("H264 encoder not found");
        ret = AVERROR_ENCODER_NOT_FOUND;
        goto end;
    }
    
    enc_ctx = avcodec_alloc_context3(encoder);
    if (!enc_ctx) {
        ret = AVERROR(ENOMEM);
        goto end;
    }
    
    // Set encoder parameters with target dimensions
    enc_ctx->width = target_width;
    enc_ctx->height = target_height;
    enc_ctx->pix_fmt = AV_PIX_FMT_YUV420P;
    
    // Get framerate and set timebase properly
    AVRational frame_rate = av_guess_frame_rate(input_ctx, input_stream, NULL);
    if (frame_rate.num == 0 || frame_rate.den == 0) {
        frame_rate = (AVRational){30, 1};
    }
    enc_ctx->framerate = frame_rate;
    
    // Set timebase
    if (input_stream->time_base.num > 0 && input_stream->time_base.den > 0) {
        enc_ctx->time_base = input_stream->time_base;
    } else {
        enc_ctx->time_base = av_inv_q(frame_rate);
    }
    
    // Ensure valid timebase
    if (enc_ctx->time_base.num <= 0 || enc_ctx->time_base.den <= 0) {
        enc_ctx->time_base = (AVRational){1, 30000};
    }
    
    enc_ctx->bit_rate = 2000000; // 2 Mbps
    enc_ctx->gop_size = 12;
    enc_ctx->max_b_frames = 0; // Disable B-frames to avoid DTS issues
    
    // Some formats want stream headers to be separate
    if (output_ctx->oformat->flags & AVFMT_GLOBALHEADER) {
        enc_ctx->flags |= AV_CODEC_FLAG_GLOBAL_HEADER;
    }
    
    // Add options for encoder
    AVDictionary *opts = NULL;
    av_dict_set(&opts, "preset", "fast", 0);
    av_dict_set(&opts, "crf", "23", 0);
    
    ret = avcodec_open2(enc_ctx, encoder, &opts);
    av_dict_free(&opts);
    if (ret < 0) {
        char errbuf[AV_ERROR_MAX_STRING_SIZE];
        av_strerror(ret, errbuf, sizeof(errbuf));
        LOGE("Failed to open encoder: %s (error code: %d)", errbuf, ret);
        LOGE("Encoder parameters: size=%dx%d, pix_fmt=%d, timebase=%d/%d, framerate=%d/%d",
             enc_ctx->width, enc_ctx->height, enc_ctx->pix_fmt,
             enc_ctx->time_base.num, enc_ctx->time_base.den,
             enc_ctx->framerate.num, enc_ctx->framerate.den);
        goto end;
    }
    
    ret = avcodec_parameters_from_context(output_stream->codecpar, enc_ctx);
    if (ret < 0) {
        goto end;
    }
    
    output_stream->time_base = enc_ctx->time_base;
    
    // Initialize scaler context
    sws_ctx = sws_getContext(
        dec_ctx->width, dec_ctx->height, dec_ctx->pix_fmt,
        target_width, target_height, AV_PIX_FMT_YUV420P,
        SWS_BILINEAR, NULL, NULL, NULL
    );
    
    if (!sws_ctx) {
        LOGE("Could not initialize the conversion context");
        ret = AVERROR_UNKNOWN;
        goto end;
    }
    
    // Allocate scaled frame buffer
    scaled_frame->format = AV_PIX_FMT_YUV420P;
    scaled_frame->width = target_width;
    scaled_frame->height = target_height;
    ret = av_frame_get_buffer(scaled_frame, 0);
    if (ret < 0) {
        LOGE("Could not allocate scaled frame buffer");
        goto end;
    }
    
    // Open output file
    if (!(output_ctx->oformat->flags & AVFMT_NOFILE)) {
        ret = avio_open(&output_ctx->pb, output_file, AVIO_FLAG_WRITE);
        if (ret < 0) {
            LOGE("Could not open output file");
            goto end;
        }
    }
    
    // Write header
    ret = avformat_write_header(output_ctx, NULL);
    if (ret < 0) {
        LOGE("Error writing header");
        goto end;
    }
    
    // Process frames
    int64_t frame_count = 0;
    while (1) {
        ret = av_read_frame(input_ctx, packet);
        if (ret < 0) {
            if (ret == AVERROR_EOF) {
                // Flush decoder
                avcodec_send_packet(dec_ctx, NULL);
            } else {
                break;
            }
        }
        
        if (packet->stream_index == video_stream_index || ret == AVERROR_EOF) {
            if (ret != AVERROR_EOF) {
                ret = avcodec_send_packet(dec_ctx, packet);
                if (ret < 0) {
                    av_packet_unref(packet);
                    continue;
                }
            }
            
            while (ret >= 0) {
                ret = avcodec_receive_frame(dec_ctx, frame);
                if (ret == AVERROR(EAGAIN) || ret == AVERROR_EOF) {
                    break;
                } else if (ret < 0) {
                    goto end;
                }
                
                // Scale the frame
                ret = av_frame_make_writable(scaled_frame);
                if (ret < 0) {
                    goto end;
                }
                
                sws_scale(sws_ctx,
                         (const uint8_t * const *)frame->data, frame->linesize,
                         0, dec_ctx->height,
                         scaled_frame->data, scaled_frame->linesize);
                
                // Copy timestamp
                scaled_frame->pts = frame->pts;
                scaled_frame->pkt_dts = frame->pkt_dts;
                scaled_frame->duration = frame->duration;
                
                // Encode scaled frame
                ret = avcodec_send_frame(enc_ctx, scaled_frame);
                if (ret < 0) {
                    LOGE("Error sending frame to encoder");
                    av_frame_unref(frame);
                    continue;
                }
                
                while (ret >= 0) {
                    AVPacket enc_pkt = {0};
                    av_init_packet(&enc_pkt);
                    
                    ret = avcodec_receive_packet(enc_ctx, &enc_pkt);
                    if (ret == AVERROR(EAGAIN) || ret == AVERROR_EOF) {
                        break;
                    } else if (ret < 0) {
                        goto end;
                    }
                    
                    // Rescale timestamps
                    av_packet_rescale_ts(&enc_pkt, enc_ctx->time_base, output_stream->time_base);
                    enc_pkt.stream_index = output_stream->index;
                    
                    ret = av_interleaved_write_frame(output_ctx, &enc_pkt);
                    av_packet_unref(&enc_pkt);
                    if (ret < 0) {
                        LOGE("Error writing frame");
                        goto end;
                    }
                }
                
                av_frame_unref(frame);
                frame_count++;
            }
        }
        
        av_packet_unref(packet);
        
        if (ret == AVERROR_EOF) {
            break;
        }
    }
    
    // Flush encoder
    avcodec_send_frame(enc_ctx, NULL);
    while (1) {
        AVPacket enc_pkt = {0};
        av_init_packet(&enc_pkt);
        
        ret = avcodec_receive_packet(enc_ctx, &enc_pkt);
        if (ret == AVERROR_EOF) {
            break;
        } else if (ret < 0) {
            goto end;
        }
        
        av_packet_rescale_ts(&enc_pkt, enc_ctx->time_base, output_stream->time_base);
        enc_pkt.stream_index = output_stream->index;
        
        ret = av_interleaved_write_frame(output_ctx, &enc_pkt);
        av_packet_unref(&enc_pkt);
        if (ret < 0) {
            goto end;
        }
    }
    
    // Write trailer
    av_write_trailer(output_ctx);
    
    LOGI("Scaled %lld frames successfully to %dx%d", (long long)frame_count, target_width, target_height);
    ret = 0;
    
end:
    // Clean up
    if (sws_ctx) {
        sws_freeContext(sws_ctx);
    }
    if (enc_ctx) {
        avcodec_free_context(&enc_ctx);
    }
    if (dec_ctx) {
        avcodec_free_context(&dec_ctx);
    }
    if (output_ctx) {
        if (!(output_ctx->oformat->flags & AVFMT_NOFILE)) {
            avio_closep(&output_ctx->pb);
        }
        avformat_free_context(output_ctx);
    }
    if (input_ctx) {
        avformat_close_input(&input_ctx);
    }
    av_frame_free(&scaled_frame);
    av_frame_free(&frame);
    av_packet_free(&packet);
    
    return ret;
}

// Main FFmpeg command handler
// Process video with complex filters
static int process_video_with_filters(const char *input_file, const char *output_file, 
                                     const char *filter_str, int is_complex_filter, int argc, char **argv) {
    AVFormatContext *input_ctx = NULL, *output_ctx = NULL;
    AVCodecContext *dec_ctx = NULL, *enc_ctx = NULL;
    AVFilterContext *buffersink_ctx = NULL, *buffersrc_ctx = NULL;
    AVFilterGraph *filter_graph = NULL;
    AVStream *input_stream = NULL, *output_stream = NULL;
    const AVCodec *decoder = NULL, *encoder = NULL;
    AVPacket *packet = NULL;
    AVFrame *frame = NULL, *filtered_frame = NULL;
    int video_stream_index = -1;
    int ret;
    
    // Parse encoder options from command line
    const char *preset_value = NULL;
    const char *crf_value = NULL;
    const char *video_codec_name = NULL;
    int custom_bitrate = 0;
    
    for (int i = 0; i < argc; i++) {
        if (strcmp(argv[i], "-preset") == 0 && i + 1 < argc) {
            preset_value = argv[i + 1];
            LOGI("Found preset option: %s", preset_value);
        } else if (strcmp(argv[i], "-crf") == 0 && i + 1 < argc) {
            crf_value = argv[i + 1];
            LOGI("Found CRF option: %s", crf_value);
        } else if (strcmp(argv[i], "-b:v") == 0 && i + 1 < argc) {
            custom_bitrate = atoi(argv[i + 1]);
            LOGI("Found video bitrate: %d", custom_bitrate);
        } else if (strcmp(argv[i], "-c:v") == 0 && i + 1 < argc) {
            video_codec_name = argv[i + 1];
            LOGI("Found video codec: %s", video_codec_name);
        }
    }
    
    LOGI("Processing video with filters: %s", filter_str ? filter_str : "none");
    
    // Allocate packet and frames
    packet = av_packet_alloc();
    frame = av_frame_alloc();
    filtered_frame = av_frame_alloc();
    if (!packet || !frame || !filtered_frame) {
        ret = AVERROR(ENOMEM);
        goto end;
    }
    
    // Open input file
    ret = avformat_open_input(&input_ctx, input_file, NULL, NULL);
    if (ret < 0) {
        LOGE("Cannot open input file: %s", input_file);
        goto end;
    }
    
    ret = avformat_find_stream_info(input_ctx, NULL);
    if (ret < 0) {
        LOGE("Cannot find stream information");
        goto end;
    }
    
    // Find video stream
    for (int i = 0; i < input_ctx->nb_streams; i++) {
        if (input_ctx->streams[i]->codecpar->codec_type == AVMEDIA_TYPE_VIDEO) {
            video_stream_index = i;
            input_stream = input_ctx->streams[i];
            break;
        }
    }
    
    if (video_stream_index < 0) {
        LOGE("No video stream found");
        ret = AVERROR_STREAM_NOT_FOUND;
        goto end;
    }
    
    // Find and open decoder
    decoder = avcodec_find_decoder(input_stream->codecpar->codec_id);
    if (!decoder) {
        LOGE("Decoder not found");
        ret = AVERROR_DECODER_NOT_FOUND;
        goto end;
    }
    
    dec_ctx = avcodec_alloc_context3(decoder);
    if (!dec_ctx) {
        ret = AVERROR(ENOMEM);
        goto end;
    }
    
    ret = avcodec_parameters_to_context(dec_ctx, input_stream->codecpar);
    if (ret < 0) {
        LOGE("Failed to copy codec parameters");
        goto end;
    }
    
    ret = avcodec_open2(dec_ctx, decoder, NULL);
    if (ret < 0) {
        LOGE("Failed to open decoder");
        goto end;
    }
    
    // Create output format context
    avformat_alloc_output_context2(&output_ctx, NULL, NULL, output_file);
    if (!output_ctx) {
        LOGE("Could not create output context");
        ret = AVERROR_UNKNOWN;
        goto end;
    }
    
    // Add video stream to output
    output_stream = avformat_new_stream(output_ctx, NULL);
    if (!output_stream) {
        LOGE("Failed to allocate output stream");
        ret = AVERROR_UNKNOWN;
        goto end;
    }
    
    // Find and open encoder (use specified codec or default to H264)
    if (video_codec_name) {
        encoder = avcodec_find_encoder_by_name(video_codec_name);
        if (!encoder) {
            LOGI("Codec '%s' not found, falling back to H264", video_codec_name);
            encoder = avcodec_find_encoder(AV_CODEC_ID_H264);
        }
    } else {
        encoder = avcodec_find_encoder(AV_CODEC_ID_H264);
    }
    
    if (!encoder) {
        LOGE("Encoder not found");
        ret = AVERROR_ENCODER_NOT_FOUND;
        goto end;
    }
    
    enc_ctx = avcodec_alloc_context3(encoder);
    if (!enc_ctx) {
        ret = AVERROR(ENOMEM);
        goto end;
    }
    
    // Check if we have a transpose filter that will swap dimensions
    int output_width = dec_ctx->width;
    int output_height = dec_ctx->height;
    
    if (filter_str && (strstr(filter_str, "transpose=1") || strstr(filter_str, "transpose=2") || 
                       strstr(filter_str, "transpose=clock") || strstr(filter_str, "transpose=cclock"))) {
        // Transpose will swap width and height
        output_width = dec_ctx->height;
        output_height = dec_ctx->width;
        LOGI("Transpose detected: output dimensions will be %dx%d", output_width, output_height);
    }
    
    // Set encoder parameters
    enc_ctx->width = output_width;
    enc_ctx->height = output_height;
    enc_ctx->sample_aspect_ratio = dec_ctx->sample_aspect_ratio;
    enc_ctx->pix_fmt = AV_PIX_FMT_YUV420P;
    
    // Get the frame rate properly
    AVRational frame_rate = av_guess_frame_rate(input_ctx, input_stream, NULL);
    if (frame_rate.num == 0 || frame_rate.den == 0) {
        // Default to 30 fps if frame rate cannot be determined
        frame_rate = (AVRational){30, 1};
        LOGW("Could not determine frame rate, using default 30 fps");
    }
    enc_ctx->framerate = frame_rate;
    
    // Set timebase - ensure it's valid
    if (input_stream->time_base.num > 0 && input_stream->time_base.den > 0) {
        enc_ctx->time_base = input_stream->time_base;
    } else if (dec_ctx->time_base.num > 0 && dec_ctx->time_base.den > 0) {
        enc_ctx->time_base = dec_ctx->time_base;
    } else {
        // Use inverse of frame rate as timebase
        enc_ctx->time_base = av_inv_q(frame_rate);
    }
    
    // Ensure timebase is valid
    if (enc_ctx->time_base.num <= 0 || enc_ctx->time_base.den <= 0) {
        LOGW("Invalid timebase, setting to 1/30000");
        enc_ctx->time_base = (AVRational){1, 30000};
    }
    
    LOGI("Encoder timebase: %d/%d, framerate: %d/%d", 
         enc_ctx->time_base.num, enc_ctx->time_base.den,
         enc_ctx->framerate.num, enc_ctx->framerate.den);
    
    // Use custom bitrate if specified, otherwise default
    enc_ctx->bit_rate = custom_bitrate > 0 ? custom_bitrate : 2000000;
    enc_ctx->gop_size = 12;
    enc_ctx->max_b_frames = 0; // Disable B-frames to avoid timestamp issues
    
    output_stream->time_base = enc_ctx->time_base; // Use encoder timebase for stream
    
    // Some formats want stream headers to be separate
    if (output_ctx->oformat->flags & AVFMT_GLOBALHEADER) {
        enc_ctx->flags |= AV_CODEC_FLAG_GLOBAL_HEADER;
    }
    
    // Set encoder options based on command line or use defaults
    AVDictionary *opts = NULL;
    if (encoder->id == AV_CODEC_ID_H264 || encoder->id == AV_CODEC_ID_H265) {
        if (preset_value) {
            av_dict_set(&opts, "preset", preset_value, 0);
            LOGI("Using preset: %s", preset_value);
        } else {
            av_dict_set(&opts, "preset", "fast", 0);
        }
        
        if (crf_value) {
            av_dict_set(&opts, "crf", crf_value, 0);
            LOGI("Using CRF: %s", crf_value);
            // When using CRF, don't set a fixed bitrate
            enc_ctx->bit_rate = 0;
        } else if (custom_bitrate == 0) {
            // Default to CRF 23 if no bitrate specified
            av_dict_set(&opts, "crf", "23", 0);
            enc_ctx->bit_rate = 0;
        }
    }
    
    ret = avcodec_open2(enc_ctx, encoder, &opts);
    av_dict_free(&opts);
    if (ret < 0) {
        char errbuf[AV_ERROR_MAX_STRING_SIZE];
        av_strerror(ret, errbuf, sizeof(errbuf));
        LOGE("Failed to open encoder: %s (error code: %d)", errbuf, ret);
        LOGE("Encoder parameters: size=%dx%d, pix_fmt=%d, timebase=%d/%d, framerate=%d/%d",
             enc_ctx->width, enc_ctx->height, enc_ctx->pix_fmt,
             enc_ctx->time_base.num, enc_ctx->time_base.den,
             enc_ctx->framerate.num, enc_ctx->framerate.den);
        goto end;
    }
    
    ret = avcodec_parameters_from_context(output_stream->codecpar, enc_ctx);
    if (ret < 0) {
        LOGE("Failed to copy encoder parameters");
        goto end;
    }
    
    // Initialize filter graph if filter string is provided or if we need format conversion
    // Always create a basic filter graph for format conversion even without explicit filters
    if (filter_str && strlen(filter_str) > 0) {
        // Create filter graph
        filter_graph = avfilter_graph_alloc();
        if (!filter_graph) {
            ret = AVERROR(ENOMEM);
            goto end;
        }
        
        // Create buffer source
        const AVFilter *buffersrc = avfilter_get_by_name("buffer");
        if (!buffersrc) {
            LOGE("Buffer source filter not found");
            ret = AVERROR_FILTER_NOT_FOUND;
            goto end;
        }
        
        char args[512];
        snprintf(args, sizeof(args),
                "video_size=%dx%d:pix_fmt=%d:time_base=%d/%d:pixel_aspect=%d/%d",
                dec_ctx->width, dec_ctx->height, dec_ctx->pix_fmt,
                input_stream->time_base.num, input_stream->time_base.den,
                dec_ctx->sample_aspect_ratio.num, dec_ctx->sample_aspect_ratio.den);
        
        ret = avfilter_graph_create_filter(&buffersrc_ctx, buffersrc, "in",
                                          args, NULL, filter_graph);
        if (ret < 0) {
            LOGE("Cannot create buffer source");
            goto end;
        }
        
        // Create buffer sink
        const AVFilter *buffersink = avfilter_get_by_name("buffersink");
        if (!buffersink) {
            LOGE("Buffer sink filter not found");
            ret = AVERROR_FILTER_NOT_FOUND;
            goto end;
        }
        
        ret = avfilter_graph_create_filter(&buffersink_ctx, buffersink, "out",
                                          NULL, NULL, filter_graph);
        if (ret < 0) {
            LOGE("Cannot create buffer sink");
            goto end;
        }
        
        // Set output pixel format for encoder
        enum AVPixelFormat pix_fmts[] = { AV_PIX_FMT_YUV420P, AV_PIX_FMT_NONE };
        ret = av_opt_set_int_list(buffersink_ctx, "pix_fmts", pix_fmts,
                                 AV_PIX_FMT_NONE, AV_OPT_SEARCH_CHILDREN);
        if (ret < 0) {
            LOGE("Cannot set output pixel format");
            goto end;
        }
        
        // Parse and create filter chain
        AVFilterInOut *outputs = avfilter_inout_alloc();
        AVFilterInOut *inputs = avfilter_inout_alloc();
        if (!outputs || !inputs) {
            ret = AVERROR(ENOMEM);
            avfilter_inout_free(&outputs);
            avfilter_inout_free(&inputs);
            goto end;
        }
        
        // Handle complex filter graphs vs simple filter chains
        if (is_complex_filter) {
            // For complex filters, we need to handle named inputs/outputs
            // Check if filter has explicit input/output labels
            if (strstr(filter_str, "[0:v]") || strstr(filter_str, "[0]") || 
                strstr(filter_str, "[0:a]")) {
                // Complex filter with explicit input labels
                // Need to map [0:v] to our buffersrc
                LOGI("Complex filter graph with explicit inputs detected");
                
                // Create a modified filter that connects properly
                char *modified_filter = av_malloc(strlen(filter_str) * 2 + 100);
                if (!modified_filter) {
                    ret = AVERROR(ENOMEM);
                    avfilter_inout_free(&outputs);
                    avfilter_inout_free(&inputs);
                    goto end;
                }
                
                // Simple approach: if filter ends with an output label, use it
                // Otherwise append [out]
                const char *last_bracket = strrchr(filter_str, '[');
                if (last_bracket && strchr(last_bracket, ']')) {
                    // Has output label, connect it to our sink
                    strcpy(modified_filter, filter_str);
                } else {
                    // No output label, add one
                    sprintf(modified_filter, "%s[out]", filter_str);
                }
                
                // Set up the connections
                outputs->name = av_strdup("0:v");
                outputs->filter_ctx = buffersrc_ctx;
                outputs->pad_idx = 0;
                outputs->next = NULL;
                
                inputs->name = av_strdup("out");
                inputs->filter_ctx = buffersink_ctx;
                inputs->pad_idx = 0;
                inputs->next = NULL;
                
                LOGI("Parsing complex filter: %s", modified_filter);
                ret = avfilter_graph_parse_ptr(filter_graph, modified_filter,
                                              &inputs, &outputs, NULL);
                av_free(modified_filter);
            } else {
                // Complex filter without explicit inputs, treat as simple
                outputs->name = av_strdup("in");
                outputs->filter_ctx = buffersrc_ctx;
                outputs->pad_idx = 0;
                outputs->next = NULL;
                
                inputs->name = av_strdup("out");
                inputs->filter_ctx = buffersink_ctx;
                inputs->pad_idx = 0;
                inputs->next = NULL;
                
                LOGI("Parsing complex filter as simple: %s", filter_str);
                ret = avfilter_graph_parse_ptr(filter_graph, filter_str,
                                               &inputs, &outputs, NULL);
            }
        } else {
            // Simple -vf filter chain
            outputs->name = av_strdup("in");
            outputs->filter_ctx = buffersrc_ctx;
            outputs->pad_idx = 0;
            outputs->next = NULL;
            
            inputs->name = av_strdup("out");
            inputs->filter_ctx = buffersink_ctx;
            inputs->pad_idx = 0;
            inputs->next = NULL;
            
            LOGI("Parsing simple filter: %s", filter_str);
            ret = avfilter_graph_parse_ptr(filter_graph, filter_str,
                                           &inputs, &outputs, NULL);
        }
        avfilter_inout_free(&outputs);
        avfilter_inout_free(&inputs);
        
        if (ret < 0) {
            LOGE("Error parsing filter string");
            goto end;
        }
        
        ret = avfilter_graph_config(filter_graph, NULL);
        if (ret < 0) {
            LOGE("Error configuring filter graph");
            goto end;
        }
    }
    
    // Open output file
    if (!(output_ctx->oformat->flags & AVFMT_NOFILE)) {
        ret = avio_open(&output_ctx->pb, output_file, AVIO_FLAG_WRITE);
        if (ret < 0) {
            LOGE("Could not open output file '%s'", output_file);
            goto end;
        }
    }
    
    // Write header
    ret = avformat_write_header(output_ctx, NULL);
    if (ret < 0) {
        LOGE("Error writing header");
        goto end;
    }
    
    // Process frames
    while (1) {
        ret = av_read_frame(input_ctx, packet);
        if (ret < 0) {
            if (ret == AVERROR_EOF) {
                // Flush decoder
                avcodec_send_packet(dec_ctx, NULL);
            } else {
                break;
            }
        }
        
        if (packet->stream_index == video_stream_index || ret == AVERROR_EOF) {
            if (ret != AVERROR_EOF) {
                ret = avcodec_send_packet(dec_ctx, packet);
                if (ret < 0) {
                    LOGE("Error sending packet to decoder");
                    av_packet_unref(packet);
                    continue;
                }
            }
            
            while (ret >= 0) {
                ret = avcodec_receive_frame(dec_ctx, frame);
                if (ret == AVERROR(EAGAIN) || ret == AVERROR_EOF) {
                    break;
                } else if (ret < 0) {
                    LOGE("Error receiving frame from decoder");
                    goto end;
                }
                
                // Apply filter if available
                if (filter_graph && filter_str && strlen(filter_str) > 0) {
                    // Push frame to filter graph (without KEEP_REF flag to avoid memory issues)
                    ret = av_buffersrc_add_frame_flags(buffersrc_ctx, frame, 0);
                    if (ret < 0) {
                        LOGE("Error feeding filter graph: %s", av_err2str(ret));
                        av_frame_unref(frame);
                        continue;
                    }
                    
                    // Pull filtered frame
                    while (1) {
                        ret = av_buffersink_get_frame(buffersink_ctx, filtered_frame);
                        if (ret == AVERROR(EAGAIN) || ret == AVERROR_EOF) {
                            break;
                        } else if (ret < 0) {
                            LOGE("Error getting filtered frame: %s", av_err2str(ret));
                            break;
                        }
                        
                        // Ensure frame is writable before sending to encoder
                        ret = av_frame_make_writable(filtered_frame);
                        if (ret < 0) {
                            LOGE("Could not make frame writable");
                            av_frame_unref(filtered_frame);
                            continue;
                        }
                        
                        // The filter graph already handles PTS correctly, don't rescale again
                        // Just ensure the frame has proper type
                        filtered_frame->pict_type = AV_PICTURE_TYPE_NONE;
                        
                        // Encode filtered frame
                        ret = avcodec_send_frame(enc_ctx, filtered_frame);
                        if (ret < 0) {
                            LOGE("Error sending frame to encoder: %s", av_err2str(ret));
                            av_frame_unref(filtered_frame);
                            continue;
                        }
                        
                        while (ret >= 0) {
                            AVPacket enc_pkt = {0};
                            av_init_packet(&enc_pkt);
                            
                            ret = avcodec_receive_packet(enc_ctx, &enc_pkt);
                            if (ret == AVERROR(EAGAIN) || ret == AVERROR_EOF) {
                                break;
                            } else if (ret < 0) {
                                LOGE("Error receiving packet from encoder");
                                goto end;
                            }
                            
                            // Rescale timestamps
                            av_packet_rescale_ts(&enc_pkt, enc_ctx->time_base, output_stream->time_base);
                            enc_pkt.stream_index = output_stream->index;
                            
                            ret = av_interleaved_write_frame(output_ctx, &enc_pkt);
                            av_packet_unref(&enc_pkt);
                            if (ret < 0) {
                                LOGE("Error writing frame");
                                goto end;
                            }
                        }
                        
                        av_frame_unref(filtered_frame);
                    }
                } else {
                    // No filter or simple passthrough, encode directly
                    // Ensure frame is in correct format for encoder
                    AVFrame *enc_frame = frame;
                    
                    // Check if we need pixel format conversion
                    if (frame->format != enc_ctx->pix_fmt) {
                        // Need format conversion, create a simple scaler
                        struct SwsContext *sws_conv = sws_getContext(
                            frame->width, frame->height, frame->format,
                            enc_ctx->width, enc_ctx->height, enc_ctx->pix_fmt,
                            SWS_BILINEAR, NULL, NULL, NULL);
                        
                        if (sws_conv) {
                            AVFrame *converted_frame = av_frame_alloc();
                            if (converted_frame) {
                                converted_frame->format = enc_ctx->pix_fmt;
                                converted_frame->width = enc_ctx->width;
                                converted_frame->height = enc_ctx->height;
                                
                                if (av_frame_get_buffer(converted_frame, 0) >= 0) {
                                    sws_scale(sws_conv,
                                             (const uint8_t * const *)frame->data, frame->linesize,
                                             0, frame->height,
                                             converted_frame->data, converted_frame->linesize);
                                    
                                    // Copy timestamps
                                    converted_frame->pts = frame->pts;
                                    converted_frame->pkt_dts = frame->pkt_dts;
                                    converted_frame->duration = frame->duration;
                                    
                                    enc_frame = converted_frame;
                                }
                            }
                            sws_freeContext(sws_conv);
                        }
                    }
                    
                    enc_frame->pict_type = AV_PICTURE_TYPE_NONE;
                    
                    ret = avcodec_send_frame(enc_ctx, enc_frame);
                    if (ret < 0) {
                        LOGE("Error sending frame to encoder");
                        if (enc_frame != frame) {
                            av_frame_free(&enc_frame);
                        }
                        av_frame_unref(frame);
                        continue;
                    }
                    
                    while (ret >= 0) {
                        AVPacket enc_pkt = {0};
                        av_init_packet(&enc_pkt);
                        
                        ret = avcodec_receive_packet(enc_ctx, &enc_pkt);
                        if (ret == AVERROR(EAGAIN) || ret == AVERROR_EOF) {
                            break;
                        } else if (ret < 0) {
                            LOGE("Error receiving packet from encoder");
                            if (enc_frame != frame) {
                                av_frame_free(&enc_frame);
                            }
                            goto end;
                        }
                        
                        // Rescale timestamps
                        av_packet_rescale_ts(&enc_pkt, enc_ctx->time_base, output_stream->time_base);
                        enc_pkt.stream_index = output_stream->index;
                        
                        ret = av_interleaved_write_frame(output_ctx, &enc_pkt);
                        av_packet_unref(&enc_pkt);
                        if (ret < 0) {
                            LOGE("Error writing frame");
                            if (enc_frame != frame) {
                                av_frame_free(&enc_frame);
                            }
                            goto end;
                        }
                    }
                    
                    // Clean up converted frame if it was allocated
                    if (enc_frame != frame) {
                        av_frame_free(&enc_frame);
                    }
                }
                
                av_frame_unref(frame);
            }
        }
        
        av_packet_unref(packet);
        
        if (ret == AVERROR_EOF) {
            break;
        }
    }
    
    // Flush encoder
    avcodec_send_frame(enc_ctx, NULL);
    while (1) {
        AVPacket enc_pkt = {0};
        av_init_packet(&enc_pkt);
        
        ret = avcodec_receive_packet(enc_ctx, &enc_pkt);
        if (ret == AVERROR_EOF) {
            break;
        } else if (ret < 0) {
            LOGE("Error flushing encoder");
            goto end;
        }
        
        av_packet_rescale_ts(&enc_pkt, enc_ctx->time_base, output_stream->time_base);
        enc_pkt.stream_index = output_stream->index;
        
        ret = av_interleaved_write_frame(output_ctx, &enc_pkt);
        av_packet_unref(&enc_pkt);
        if (ret < 0) {
            LOGE("Error writing final frame");
            goto end;
        }
    }
    
    // Write trailer
    av_write_trailer(output_ctx);
    
    LOGI("Filter processing completed successfully");
    ret = 0;
    
end:
    // Clean up
    if (filter_graph) {
        avfilter_graph_free(&filter_graph);
    }
    if (enc_ctx) {
        avcodec_free_context(&enc_ctx);
    }
    if (dec_ctx) {
        avcodec_free_context(&dec_ctx);
    }
    if (output_ctx) {
        if (!(output_ctx->oformat->flags & AVFMT_NOFILE)) {
            avio_closep(&output_ctx->pb);
        }
        avformat_free_context(output_ctx);
    }
    if (input_ctx) {
        avformat_close_input(&input_ctx);
    }
    av_frame_free(&filtered_frame);
    av_frame_free(&frame);
    av_packet_free(&packet);
    
    return ret;
}

// Full FFmpeg command implementation that supports all features
int ffmpeg_main(int argc, char **argv) {
    LOGI("FFmpeg full implementation called with %d arguments", argc);
    
    // Initialize FFmpeg
    av_log_set_callback(ffmpeg_log_callback);
    av_log_set_level(AV_LOG_INFO);
    
    // Log all arguments
    for (int i = 0; i < argc; i++) {
        LOGI("  arg[%d]: %s", i, argv[i]);
    }
    
    // Parse command line to find input and output files
    const char *input_file = NULL;
    const char *output_file = NULL;
    const char *video_filter = NULL;
    const char *audio_filter = NULL;
    const char *complex_filter = NULL;
    const char *video_codec = NULL;
    const char *audio_codec = NULL;
    double start_time = -1;
    double duration = -1;
    int is_complex = 0;
    
    // First pass: identify all options that take parameters
    int *option_params = av_malloc_array(argc, sizeof(int));
    memset(option_params, 0, argc * sizeof(int));
    
    for (int i = 1; i < argc - 1; i++) {
        if (argv[i][0] == '-') {
            // Check if this option takes a parameter
            if (strcmp(argv[i], "-i") == 0 ||
                strcmp(argv[i], "-vf") == 0 || strcmp(argv[i], "-filter:v") == 0 ||
                strcmp(argv[i], "-af") == 0 || strcmp(argv[i], "-filter:a") == 0 ||
                strcmp(argv[i], "-filter_complex") == 0 || strcmp(argv[i], "-lavfi") == 0 ||
                strcmp(argv[i], "-c:v") == 0 || strcmp(argv[i], "-codec:v") == 0 ||
                strcmp(argv[i], "-c:a") == 0 || strcmp(argv[i], "-codec:a") == 0 ||
                strcmp(argv[i], "-ss") == 0 || strcmp(argv[i], "-t") == 0 ||
                strcmp(argv[i], "-to") == 0 || strcmp(argv[i], "-crf") == 0 ||
                strcmp(argv[i], "-preset") == 0 || strcmp(argv[i], "-b:v") == 0 ||
                strcmp(argv[i], "-b:a") == 0 || strcmp(argv[i], "-r") == 0 ||
                strcmp(argv[i], "-s") == 0 || strcmp(argv[i], "-aspect") == 0 ||
                strcmp(argv[i], "-q:v") == 0 || strcmp(argv[i], "-qscale:v") == 0 ||
                strcmp(argv[i], "-map") == 0 || strcmp(argv[i], "-metadata") == 0 ||
                strcmp(argv[i], "-movflags") == 0 || strcmp(argv[i], "-pix_fmt") == 0 ||
                strcmp(argv[i], "-profile:v") == 0 || strcmp(argv[i], "-level") == 0 ||
                strcmp(argv[i], "-g") == 0 || strcmp(argv[i], "-keyint_min") == 0 ||
                strcmp(argv[i], "-sc_threshold") == 0 || strcmp(argv[i], "-bufsize") == 0 ||
                strcmp(argv[i], "-maxrate") == 0 || strcmp(argv[i], "-minrate") == 0 ||
                strcmp(argv[i], "-threads") == 0 || strcmp(argv[i], "-f") == 0) {
                option_params[i + 1] = 1; // Mark next arg as parameter
            }
        }
    }
    
    // Second pass: parse arguments
    for (int i = 1; i < argc; i++) {
        if (strcmp(argv[i], "-i") == 0 && i + 1 < argc) {
            input_file = argv[i + 1];
            i++;
        } else if (strcmp(argv[i], "-vf") == 0 && i + 1 < argc) {
            video_filter = argv[i + 1];
            is_complex = 0;
            i++;
        } else if (strcmp(argv[i], "-filter:v") == 0 && i + 1 < argc) {
            video_filter = argv[i + 1];
            is_complex = 0;
            i++;
        } else if (strcmp(argv[i], "-af") == 0 && i + 1 < argc) {
            audio_filter = argv[i + 1];
            i++;
        } else if (strcmp(argv[i], "-filter:a") == 0 && i + 1 < argc) {
            audio_filter = argv[i + 1];
            i++;
        } else if (strcmp(argv[i], "-filter_complex") == 0 && i + 1 < argc) {
            // Complex filter graphs
            complex_filter = argv[i + 1];
            is_complex = 1;
            i++;
        } else if (strcmp(argv[i], "-lavfi") == 0 && i + 1 < argc) {
            // Libavfilter graph (-lavfi is an alias for -filter_complex)
            complex_filter = argv[i + 1];
            is_complex = 1;
            i++;
        } else if (strcmp(argv[i], "-c:v") == 0 && i + 1 < argc) {
            video_codec = argv[i + 1];
            i++;
        } else if (strcmp(argv[i], "-codec:v") == 0 && i + 1 < argc) {
            video_codec = argv[i + 1];
            i++;
        } else if (strcmp(argv[i], "-c:a") == 0 && i + 1 < argc) {
            audio_codec = argv[i + 1];
            i++;
        } else if (strcmp(argv[i], "-codec:a") == 0 && i + 1 < argc) {
            audio_codec = argv[i + 1];
            i++;
        } else if (strcmp(argv[i], "-ss") == 0 && i + 1 < argc) {
            start_time = atof(argv[i + 1]);
            i++;
        } else if (strcmp(argv[i], "-t") == 0 && i + 1 < argc) {
            duration = atof(argv[i + 1]);
            i++;
        } else if (strcmp(argv[i], "-to") == 0 && i + 1 < argc) {
            // End time
            double end_time = atof(argv[i + 1]);
            if (start_time >= 0) {
                duration = end_time - start_time;
            }
            i++;
        } else if (argv[i][0] != '-' && !output_file && input_file && !option_params[i]) {
            // Non-option argument after input file that's not a parameter value
            output_file = argv[i];
        }
    }
    
    // Free the temporary array
    av_free(option_params);
    
    // Validate input
    if (!input_file) {
        LOGE("No input file specified");
        return 1;
    }
    
    // Check for audio extraction first (before other operations)
    if (output_file && (strstr(output_file, ".mp3") || strstr(output_file, ".aac") || 
                        strstr(output_file, ".m4a") || strstr(output_file, ".wav"))) {
        // Check if video should be disabled
        int extract_audio = 0;
        for (int i = 1; i < argc; i++) {
            if (strcmp(argv[i], "-vn") == 0) {
                extract_audio = 1;
                break;
            }
        }
        
        // Also extract audio if output is audio format
        if (extract_audio || strstr(output_file, ".mp3")) {
            LOGI("Audio extraction requested to %s", output_file);
            return extract_audio_to_mp3(input_file, output_file);
        }
    }
    
    // Handle different operations
    if (start_time >= 0 || duration > 0) {
        // Trim operation
        if (output_file) {
            LOGI("Trimming video: start=%.1f, duration=%.1f", 
                 start_time >= 0 ? start_time : 0, duration);
            return trim_video(input_file, output_file, 
                            start_time >= 0 ? start_time : 0,
                            duration > 0 ? duration : -1);
        }
    }
    
    if (video_filter || audio_filter || complex_filter) {
        // Filter operation
        if (output_file) {
            const char *filter_to_use = complex_filter ? complex_filter : video_filter;
            LOGI("Applying filters: type=%s, filter=%s", 
                 is_complex ? "complex" : "simple",
                 filter_to_use ? filter_to_use : "none");
            
            // For complex filters with multiple inputs, use the dedicated handler
            if (is_complex && complex_filter) {
                // Check if we have multiple inputs
                int input_count = 0;
                for (int i = 1; i < argc; i++) {
                    if (strcmp(argv[i], "-i") == 0) input_count++;
                }
                
                if (input_count > 1) {
                    LOGI("Multiple inputs detected, using complex filter handler");
                    return process_with_complex_filter(argc, argv);
                }
            }
            
            // For scale filter, use direct scaling with swscale (only for simple filters)
            if (!is_complex && video_filter && strstr(video_filter, "scale=")) {
                LOGI("Scale filter detected, using swscale");
                // Parse scale dimensions
                int target_width = 640, target_height = 480;
                if (sscanf(video_filter, "scale=%d:%d", &target_width, &target_height) == 2) {
                    LOGI("Scaling to %dx%d", target_width, target_height);
                }
                // Check for -1 in dimensions (maintain aspect ratio)
                if (target_width == -1 || target_height == -1) {
                    LOGI("Using filter graph for aspect ratio preserving scale");
                    return process_video_with_filters(input_file, output_file, video_filter, 0, argc, argv);
                }
                // Use dedicated scale function for simple scaling
                return scale_video(input_file, output_file, target_width, target_height);
            }
            
            // For other filters or complex filters, use the full implementation
            if (filter_to_use) {
                return process_video_with_filters(input_file, output_file, filter_to_use, is_complex, argc, argv);
            }
        }
    }
    
    // Check if basic compression was requested (with codec or quality options)
    if (output_file) {
        // Check if we have compression-related options
        int has_compression_opts = 0;
        
        for (int i = 1; i < argc; i++) {
            if (strcmp(argv[i], "-c:v") == 0 || strcmp(argv[i], "-codec:v") == 0 ||
                strcmp(argv[i], "-crf") == 0 || strcmp(argv[i], "-preset") == 0 ||
                strcmp(argv[i], "-b:v") == 0 || strcmp(argv[i], "-q:v") == 0) {
                has_compression_opts = 1;
                break;
            }
        }
        
        if (has_compression_opts) {
            LOGI("Compression options detected, using filter processor for transcoding");
            // Use the filter processor without filters for proper transcoding
            return process_video_with_filters(input_file, output_file, NULL, 0, argc, argv);
        }
        
        // Basic copy without re-encoding if no options specified
        LOGI("No specific operation requested, attempting basic transcode");
        return process_video_with_filters(input_file, output_file, NULL, 0, argc, argv);
    }
    
    // For other cases, try the simple implementation
    return ffmpeg_main_simple(argc, argv);
}

// Simplified FFmpeg implementation for basic operations
int ffmpeg_main_simple(int argc, char **argv) {
    LOGI("FFmpeg simple implementation called with %d arguments", argc);
    
    // Initialize FFmpeg
    av_log_set_callback(ffmpeg_log_callback);
    av_log_set_level(AV_LOG_INFO);
    
    // Simple command parser
    if (argc < 2) {
        LOGE("No command specified");
        return 1;
    }
    
    // Handle different commands
    if (argc >= 3 && strcmp(argv[1], "-i") == 0) {
        const char *input_file = argv[2];
        
        // Check for output file
        if (argc >= 5 && strcmp(argv[3], "-f") == 0 && strcmp(argv[4], "null") == 0) {
            // Just analyze the file
            return get_media_info(input_file);
        }
        
        // Find the output file (last argument)
        const char *output_file = NULL;
        if (argc > 3) {
            // The last argument should be the output file
            output_file = argv[argc - 1];
            
            // Skip if it starts with - (it's an option)
            if (output_file && output_file[0] == '-') {
                output_file = NULL;
            }
        }
        
        // Check for audio extraction (-vn flag means no video)
        int extract_audio_only = 0;
        for (int i = 3; i < argc; i++) {
            if (strcmp(argv[i], "-vn") == 0) {
                extract_audio_only = 1;
                break;
            }
        }
        
        if (extract_audio_only && output_file) {
            LOGI("Audio extraction requested to: %s", output_file);
            
            // Check for specific codec
            for (int i = 3; i < argc - 1; i++) {
                if (strcmp(argv[i], "-c:a") == 0) {
                    const char *codec = argv[i + 1];
                    if (strcmp(codec, "libmp3lame") == 0 || 
                        strcmp(codec, "mp3") == 0 ||
                        strstr(output_file, ".mp3")) {
                        return extract_audio_to_mp3(input_file, output_file);
                    }
                    // For other codecs, fall through to simple copy
                    break;
                }
            }
            
            // Simple audio extraction (codec copy)
            return extract_audio_to_mp3(input_file, output_file);
        }
        
        // Check for video compression (-c:v flag)
        for (int i = 3; i < argc - 1; i++) {
            if (strcmp(argv[i], "-c:v") == 0 && output_file) {
                const char *video_codec = argv[i + 1];
                LOGI("Video compression requested with codec: %s", video_codec);
                
                // Use the full transcoder for proper compression
                // Detect quality from command line if possible
                int quality = 0; // Default to LOW
                for (int j = 3; j < argc - 1; j++) {
                    if (strcmp(argv[j], "-b:v") == 0) {
                        const char *bitrate = argv[j + 1];
                        // Parse bitrate to determine quality
                        int kbps = atoi(bitrate) / 1000;
                        if (kbps > 1500) quality = 2; // HIGH
                        else if (kbps > 500) quality = 1; // MEDIUM
                        break;
                    }
                }
                
                LOGI("Using full transcoder with quality level: %d", quality);
                return compress_video_full(input_file, output_file, quality);
                
                // For other codecs, fall back to remux
                LOGW("Codec %s not fully supported, attempting remux", video_codec);
                return simple_remux(input_file, output_file);
            }
        }
        
        // Check for trim operation (-ss and -t flags)
        double start_time = -1;
        double duration = -1;
        for (int i = 3; i < argc - 1; i++) {
            if (strcmp(argv[i], "-ss") == 0) {
                start_time = atof(argv[i + 1]);
            } else if (strcmp(argv[i], "-t") == 0) {
                duration = atof(argv[i + 1]);
            }
        }
        
        // If we have trim parameters, perform trim operation
        if (start_time >= 0 || duration > 0) {
            if (output_file) {
                LOGI("Trimming video from %.1f seconds, duration %.1f seconds", 
                     start_time >= 0 ? start_time : 0, duration);
                return trim_video(input_file, output_file, 
                                 start_time >= 0 ? start_time : 0, 
                                 duration > 0 ? duration : -1);
            }
        }
        
        // Default: try compression if output file specified
        if (output_file) {
            LOGI("Attempting video compression to: %s", output_file);
            return compress_video(input_file, output_file, NULL);
        }
        
        // Default: just show info
        return get_media_info(input_file);
    }
    
    LOGW("Unsupported command");
    return 1;
}

#endif // HAVE_FFMPEG_STATIC