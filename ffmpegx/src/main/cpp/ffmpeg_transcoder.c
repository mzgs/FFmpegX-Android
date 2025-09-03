/**
 * Full FFmpeg transcoding implementation using static libraries
 * This properly decodes and re-encodes video for real compression
 */

#include <jni.h>
#include <android/log.h>
#include <string.h>
#include <stdlib.h>

#ifdef HAVE_FFMPEG_STATIC

#include "libavcodec/avcodec.h"
#include "libavformat/avformat.h"
#include "libavfilter/avfilter.h"
#include "libavfilter/buffersink.h"
#include "libavfilter/buffersrc.h"
#include "libavutil/avutil.h"
#include "libavutil/opt.h"
#include "libavutil/pixdesc.h"
#include "libavutil/imgutils.h"
#include "libavutil/dict.h"
#include "libswscale/swscale.h"
#include "libswresample/swresample.h"

#define LOG_TAG "FFmpegTranscoder"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

typedef struct TranscodeContext {
    AVFormatContext *input_ctx;
    AVFormatContext *output_ctx;
    
    AVCodecContext *video_dec_ctx;
    AVCodecContext *video_enc_ctx;
    AVCodecContext *audio_dec_ctx;
    AVCodecContext *audio_enc_ctx;
    
    struct SwsContext *sws_ctx;
    SwrContext *swr_ctx;
    
    int video_stream_idx;
    int audio_stream_idx;
    
    AVFrame *decoded_frame;
    AVFrame *scaled_frame;
    AVPacket *packet;
    AVPacket *enc_packet;
} TranscodeContext;

static void cleanup_context(TranscodeContext *ctx) {
    if (ctx->sws_ctx) sws_freeContext(ctx->sws_ctx);
    if (ctx->swr_ctx) swr_free(&ctx->swr_ctx);
    
    if (ctx->video_dec_ctx) avcodec_free_context(&ctx->video_dec_ctx);
    if (ctx->video_enc_ctx) avcodec_free_context(&ctx->video_enc_ctx);
    if (ctx->audio_dec_ctx) avcodec_free_context(&ctx->audio_dec_ctx);
    if (ctx->audio_enc_ctx) avcodec_free_context(&ctx->audio_enc_ctx);
    
    if (ctx->decoded_frame) av_frame_free(&ctx->decoded_frame);
    if (ctx->scaled_frame) av_frame_free(&ctx->scaled_frame);
    if (ctx->packet) av_packet_free(&ctx->packet);
    if (ctx->enc_packet) av_packet_free(&ctx->enc_packet);
    
    if (ctx->input_ctx) avformat_close_input(&ctx->input_ctx);
    if (ctx->output_ctx) {
        if (!(ctx->output_ctx->oformat->flags & AVFMT_NOFILE))
            avio_closep(&ctx->output_ctx->pb);
        avformat_free_context(ctx->output_ctx);
    }
}

int transcode_video(const char *input_file, const char *output_file, 
                   int target_width, int target_height, int target_bitrate) {
    TranscodeContext ctx = {0};
    int ret;
    
    LOGI("Starting full video transcoding: %s -> %s", input_file, output_file);
    LOGI("Target: %dx%d @ %d kbps", target_width, target_height, target_bitrate/1000);
    
    // Open input file
    ret = avformat_open_input(&ctx.input_ctx, input_file, NULL, NULL);
    if (ret < 0) {
        LOGE("Could not open input file");
        goto cleanup;
    }
    
    ret = avformat_find_stream_info(ctx.input_ctx, NULL);
    if (ret < 0) {
        LOGE("Could not find stream info");
        goto cleanup;
    }
    
    // Find video and audio streams
    ctx.video_stream_idx = -1;
    ctx.audio_stream_idx = -1;
    
    for (int i = 0; i < ctx.input_ctx->nb_streams; i++) {
        AVCodecParameters *codecpar = ctx.input_ctx->streams[i]->codecpar;
        if (codecpar->codec_type == AVMEDIA_TYPE_VIDEO && ctx.video_stream_idx < 0) {
            ctx.video_stream_idx = i;
        } else if (codecpar->codec_type == AVMEDIA_TYPE_AUDIO && ctx.audio_stream_idx < 0) {
            ctx.audio_stream_idx = i;
        }
    }
    
    if (ctx.video_stream_idx < 0) {
        LOGE("No video stream found");
        ret = -1;
        goto cleanup;
    }
    
    // Setup video decoder
    AVStream *video_stream = ctx.input_ctx->streams[ctx.video_stream_idx];
    const AVCodec *video_decoder = avcodec_find_decoder(video_stream->codecpar->codec_id);
    if (!video_decoder) {
        LOGE("Video decoder not found");
        ret = -1;
        goto cleanup;
    }
    
    ctx.video_dec_ctx = avcodec_alloc_context3(video_decoder);
    avcodec_parameters_to_context(ctx.video_dec_ctx, video_stream->codecpar);
    
    ret = avcodec_open2(ctx.video_dec_ctx, video_decoder, NULL);
    if (ret < 0) {
        LOGE("Could not open video decoder");
        goto cleanup;
    }
    
    // Create output context
    avformat_alloc_output_context2(&ctx.output_ctx, NULL, "mp4", output_file);
    if (!ctx.output_ctx) {
        LOGE("Could not create output context");
        ret = -1;
        goto cleanup;
    }
    
    // Setup video encoder - try different codecs
    const AVCodec *video_encoder = NULL;
    
    // First try MPEG4 which is most likely to be available
    video_encoder = avcodec_find_encoder(AV_CODEC_ID_MPEG4);
    if (!video_encoder) {
        LOGI("MPEG4 encoder not found, trying H264");
        video_encoder = avcodec_find_encoder(AV_CODEC_ID_H264);
    }
    if (!video_encoder) {
        LOGI("H264 encoder not found, trying H263");
        video_encoder = avcodec_find_encoder(AV_CODEC_ID_H263P);
    }
    if (!video_encoder) {
        LOGE("No suitable video encoder found");
        ret = -1;
        goto cleanup;
    }
    
    LOGI("Using video encoder: %s", video_encoder->name);
    
    AVStream *out_video_stream = avformat_new_stream(ctx.output_ctx, NULL);
    if (!out_video_stream) {
        LOGE("Could not create output video stream");
        ret = -1;
        goto cleanup;
    }
    
    ctx.video_enc_ctx = avcodec_alloc_context3(video_encoder);
    ctx.video_enc_ctx->width = target_width;
    ctx.video_enc_ctx->height = target_height;
    ctx.video_enc_ctx->pix_fmt = AV_PIX_FMT_YUV420P;
    ctx.video_enc_ctx->bit_rate = target_bitrate;
    ctx.video_enc_ctx->time_base = (AVRational){1, 30};
    ctx.video_enc_ctx->framerate = (AVRational){30, 1};
    ctx.video_enc_ctx->gop_size = 30;
    ctx.video_enc_ctx->max_b_frames = 0; // Disable B-frames to avoid DTS issues
    
    // Add strict experimental flag if needed
    ctx.video_enc_ctx->strict_std_compliance = FF_COMPLIANCE_EXPERIMENTAL;
    
    if (ctx.output_ctx->oformat->flags & AVFMT_GLOBALHEADER) {
        ctx.video_enc_ctx->flags |= AV_CODEC_FLAG_GLOBAL_HEADER;
    }
    
    // Set additional options for better compatibility
    AVDictionary *opts = NULL;
    av_dict_set(&opts, "preset", "fast", 0);
    av_dict_set(&opts, "tune", "zerolatency", 0);
    
    ret = avcodec_open2(ctx.video_enc_ctx, video_encoder, &opts);
    av_dict_free(&opts);
    if (ret < 0) {
        LOGE("Could not open video encoder");
        goto cleanup;
    }
    
    avcodec_parameters_from_context(out_video_stream->codecpar, ctx.video_enc_ctx);
    out_video_stream->time_base = ctx.video_enc_ctx->time_base;
    
    // Setup scaling context
    ctx.sws_ctx = sws_getContext(
        ctx.video_dec_ctx->width, ctx.video_dec_ctx->height, ctx.video_dec_ctx->pix_fmt,
        target_width, target_height, AV_PIX_FMT_YUV420P,
        SWS_BILINEAR, NULL, NULL, NULL
    );
    
    if (!ctx.sws_ctx) {
        LOGE("Could not create scaling context");
        ret = -1;
        goto cleanup;
    }
    
    // Setup audio if present
    if (ctx.audio_stream_idx >= 0) {
        AVStream *audio_stream = ctx.input_ctx->streams[ctx.audio_stream_idx];
        const AVCodec *audio_decoder = avcodec_find_decoder(audio_stream->codecpar->codec_id);
        
        if (audio_decoder) {
            ctx.audio_dec_ctx = avcodec_alloc_context3(audio_decoder);
            avcodec_parameters_to_context(ctx.audio_dec_ctx, audio_stream->codecpar);
            avcodec_open2(ctx.audio_dec_ctx, audio_decoder, NULL);
            
            // Setup audio encoder (AAC)
            const AVCodec *audio_encoder = avcodec_find_encoder(AV_CODEC_ID_AAC);
            if (audio_encoder) {
                AVStream *out_audio_stream = avformat_new_stream(ctx.output_ctx, NULL);
                ctx.audio_enc_ctx = avcodec_alloc_context3(audio_encoder);
                ctx.audio_enc_ctx->sample_rate = 44100;
                ctx.audio_enc_ctx->bit_rate = 128000;
                av_channel_layout_default(&ctx.audio_enc_ctx->ch_layout, 2);
                ctx.audio_enc_ctx->sample_fmt = AV_SAMPLE_FMT_FLTP;
                
                if (ctx.output_ctx->oformat->flags & AVFMT_GLOBALHEADER) {
                    ctx.audio_enc_ctx->flags |= AV_CODEC_FLAG_GLOBAL_HEADER;
                }
                
                avcodec_open2(ctx.audio_enc_ctx, audio_encoder, NULL);
                avcodec_parameters_from_context(out_audio_stream->codecpar, ctx.audio_enc_ctx);
            }
        }
    }
    
    // Open output file
    if (!(ctx.output_ctx->oformat->flags & AVFMT_NOFILE)) {
        ret = avio_open(&ctx.output_ctx->pb, output_file, AVIO_FLAG_WRITE);
        if (ret < 0) {
            LOGE("Could not open output file");
            goto cleanup;
        }
    }
    
    // Write header
    ret = avformat_write_header(ctx.output_ctx, NULL);
    if (ret < 0) {
        LOGE("Could not write header");
        goto cleanup;
    }
    
    // Allocate frames and packets
    ctx.decoded_frame = av_frame_alloc();
    ctx.scaled_frame = av_frame_alloc();
    ctx.scaled_frame->format = AV_PIX_FMT_YUV420P;
    ctx.scaled_frame->width = target_width;
    ctx.scaled_frame->height = target_height;
    av_frame_get_buffer(ctx.scaled_frame, 32);
    
    ctx.packet = av_packet_alloc();
    ctx.enc_packet = av_packet_alloc();
    
    // Main transcoding loop
    int frames_processed = 0;
    while (av_read_frame(ctx.input_ctx, ctx.packet) >= 0) {
        if (ctx.packet->stream_index == ctx.video_stream_idx) {
            // Decode video frame
            ret = avcodec_send_packet(ctx.video_dec_ctx, ctx.packet);
            if (ret < 0) {
                LOGE("Error sending packet to decoder");
                av_packet_unref(ctx.packet);
                continue;
            }
            
            while (ret >= 0) {
                ret = avcodec_receive_frame(ctx.video_dec_ctx, ctx.decoded_frame);
                if (ret == AVERROR(EAGAIN) || ret == AVERROR_EOF) {
                    break;
                } else if (ret < 0) {
                    LOGE("Error receiving frame from decoder");
                    goto cleanup;
                }
                
                // Scale frame
                sws_scale(ctx.sws_ctx,
                    (const uint8_t * const *)ctx.decoded_frame->data,
                    ctx.decoded_frame->linesize, 0, ctx.video_dec_ctx->height,
                    ctx.scaled_frame->data, ctx.scaled_frame->linesize
                );
                
                ctx.scaled_frame->pts = ctx.decoded_frame->pts;
                
                // Encode frame
                ret = avcodec_send_frame(ctx.video_enc_ctx, ctx.scaled_frame);
                if (ret < 0) {
                    LOGE("Error sending frame to encoder");
                    continue;
                }
                
                while (ret >= 0) {
                    ret = avcodec_receive_packet(ctx.video_enc_ctx, ctx.enc_packet);
                    if (ret == AVERROR(EAGAIN) || ret == AVERROR_EOF) {
                        break;
                    } else if (ret < 0) {
                        LOGE("Error receiving packet from encoder");
                        goto cleanup;
                    }
                    
                    // Rescale timestamps
                    av_packet_rescale_ts(ctx.enc_packet, ctx.video_enc_ctx->time_base,
                                        out_video_stream->time_base);
                    ctx.enc_packet->stream_index = out_video_stream->index;
                    
                    // Write packet
                    ret = av_interleaved_write_frame(ctx.output_ctx, ctx.enc_packet);
                    if (ret < 0) {
                        LOGE("Error writing video packet");
                    }
                    
                    av_packet_unref(ctx.enc_packet);
                }
                
                frames_processed++;
                if (frames_processed % 30 == 0) {
                    LOGI("Processed %d frames", frames_processed);
                }
            }
        } else if (ctx.audio_stream_idx >= 0 && ctx.packet->stream_index == ctx.audio_stream_idx) {
            // For now, just copy audio packets
            AVStream *in_stream = ctx.input_ctx->streams[ctx.packet->stream_index];
            AVStream *out_stream = ctx.output_ctx->streams[1]; // Assuming audio is stream 1
            
            av_packet_rescale_ts(ctx.packet, in_stream->time_base, out_stream->time_base);
            ctx.packet->stream_index = out_stream->index;
            av_interleaved_write_frame(ctx.output_ctx, ctx.packet);
        }
        
        av_packet_unref(ctx.packet);
    }
    
    // Flush encoders
    avcodec_send_frame(ctx.video_enc_ctx, NULL);
    while (1) {
        ret = avcodec_receive_packet(ctx.video_enc_ctx, ctx.enc_packet);
        if (ret == AVERROR_EOF) break;
        if (ret < 0) {
            LOGE("Error flushing encoder");
            break;
        }
        
        av_packet_rescale_ts(ctx.enc_packet, ctx.video_enc_ctx->time_base,
                            ctx.output_ctx->streams[0]->time_base);
        ctx.enc_packet->stream_index = 0;
        av_interleaved_write_frame(ctx.output_ctx, ctx.enc_packet);
        av_packet_unref(ctx.enc_packet);
    }
    
    // Write trailer
    av_write_trailer(ctx.output_ctx);
    
    LOGI("Transcoding completed! Processed %d frames", frames_processed);
    ret = 0;
    
cleanup:
    cleanup_context(&ctx);
    return ret;
}

// Export function for use in ffmpeg_main.c
int compress_video_full(const char *input_file, const char *output_file, int quality) {
    int width, height, bitrate;
    
    // Set parameters based on quality
    switch (quality) {
        case 0: // LOW
            width = 640;
            height = 360;
            bitrate = 200000; // 200 kbps
            break;
        case 1: // MEDIUM
            width = 854;
            height = 480;
            bitrate = 800000; // 800 kbps
            break;
        case 2: // HIGH
            width = 1280;
            height = 720;
            bitrate = 2000000; // 2 Mbps
            break;
        default:
            width = 1920;
            height = 1080;
            bitrate = 4000000; // 4 Mbps
            break;
    }
    
    return transcode_video(input_file, output_file, width, height, bitrate);
}

#endif // HAVE_FFMPEG_STATIC