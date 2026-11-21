/*
 * SILK 语音解码 JNI。
 *
 * 只暴露两个入口：解码成 16bit 单声道 wav（应用内播放）、解码成 mp3（导出）。
 * 解码器本体是 Skype 的 SILK v3 定点实现，跟着源码一起编译，避免依赖第三方预编译 so。
 */

#include <jni.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <android/log.h>

#include "silk.h"
#include "lame.h"

#define WX_TAG "wxsilk"
#define WX_LOGE(...) __android_log_print(ANDROID_LOG_ERROR, WX_TAG, __VA_ARGS__)

#define WAV_HEADER_BYTES 44

static void put_u16(FILE *f, unsigned int v) {
    fputc(v & 0xff, f);
    fputc((v >> 8) & 0xff, f);
}

static void put_u32(FILE *f, unsigned int v) {
    fputc(v & 0xff, f);
    fputc((v >> 8) & 0xff, f);
    fputc((v >> 16) & 0xff, f);
    fputc((v >> 24) & 0xff, f);
}

/* 16bit 单声道 PCM 的 wav 头；data 长度先写 0，解完再回填。 */
static void write_wav_header(FILE *f, int rate, unsigned int data_bytes) {
    fseek(f, 0, SEEK_SET);
    fwrite("RIFF", 1, 4, f);
    put_u32(f, 36 + data_bytes);
    fwrite("WAVE", 1, 4, f);
    fwrite("fmt ", 1, 4, f);
    put_u32(f, 16);
    put_u16(f, 1);
    put_u16(f, 1);
    put_u32(f, (unsigned int) rate);
    put_u32(f, (unsigned int) rate * 2);
    put_u16(f, 2);
    put_u16(f, 16);
    fwrite("data", 1, 4, f);
    put_u32(f, data_bytes);
}

JNIEXPORT jint JNICALL
Java_top_aico_wxcacheviewer_data_audio_SilkCodec_decodeToWav(
        JNIEnv *env, jclass clazz, jstring src_path, jstring dst_path, jint rate) {
    const char *src = (*env)->GetStringUTFChars(env, src_path, NULL);
    const char *dst = (*env)->GetStringUTFChars(env, dst_path, NULL);
    if (src == NULL || dst == NULL) {
        if (src != NULL) (*env)->ReleaseStringUTFChars(env, src_path, src);
        if (dst != NULL) (*env)->ReleaseStringUTFChars(env, dst_path, dst);
        return -1;
    }

    int result = -1;
    FILE *out = fopen(dst, "wb+");
    if (out == NULL) {
        WX_LOGE("open %s failed", dst);
    } else {
        write_wav_header(out, rate, 0);
        int rc = convertSilk2PCM(src, out, rate);
        long data_bytes = ftell(out) - WAV_HEADER_BYTES;
        if (rc == 0 && data_bytes > 0) {
            write_wav_header(out, rate, (unsigned int) data_bytes);
            result = 0;
        } else {
            WX_LOGE("decode wav failed rc=%d bytes=%ld", rc, data_bytes);
        }
        fclose(out);
    }

    (*env)->ReleaseStringUTFChars(env, src_path, src);
    (*env)->ReleaseStringUTFChars(env, dst_path, dst);
    return result;
}

JNIEXPORT jint JNICALL
Java_top_aico_wxcacheviewer_data_audio_SilkCodec_decodeToMp3(
        JNIEnv *env, jclass clazz, jstring src_path, jstring dst_path, jint rate, jint bitrate) {
    const char *src = (*env)->GetStringUTFChars(env, src_path, NULL);
    const char *dst = (*env)->GetStringUTFChars(env, dst_path, NULL);
    if (src == NULL || dst == NULL) {
        if (src != NULL) (*env)->ReleaseStringUTFChars(env, src_path, src);
        if (dst != NULL) (*env)->ReleaseStringUTFChars(env, dst_path, dst);
        return -1;
    }

    int result = -1;
    FILE *pcm = tmpfile();
    FILE *out = NULL;
    lame_t lame = NULL;
    if (pcm == NULL) {
        WX_LOGE("tmpfile failed");
        goto cleanup;
    }
    if (convertSilk2PCM(src, pcm, rate) != 0) {
        WX_LOGE("decode pcm failed");
        goto cleanup;
    }
    fseek(pcm, 0, SEEK_SET);

    out = fopen(dst, "wb");
    if (out == NULL) {
        WX_LOGE("open %s failed", dst);
        goto cleanup;
    }

    lame = lame_init();
    if (lame == NULL) {
        WX_LOGE("lame_init failed");
        goto cleanup;
    }
    lame_set_in_samplerate(lame, rate);
    lame_set_out_samplerate(lame, rate);
    lame_set_num_channels(lame, 1);
    lame_set_mode(lame, MONO);
    lame_set_brate(lame, bitrate);
    lame_set_quality(lame, 5);
    if (lame_init_params(lame) < 0) {
        WX_LOGE("lame_init_params failed");
        goto cleanup;
    }

    {
        short pcm_buffer[8192];
        unsigned char mp3_buffer[8192];
        size_t read;
        int written;
        while ((read = fread(pcm_buffer, sizeof(short), 8192, pcm)) > 0) {
            written = lame_encode_buffer(lame, pcm_buffer, NULL, (int) read, mp3_buffer,
                                         sizeof(mp3_buffer));
            if (written < 0) {
                WX_LOGE("lame_encode_buffer failed %d", written);
                goto cleanup;
            }
            fwrite(mp3_buffer, 1, (size_t) written, out);
        }
        do {
            written = lame_encode_flush(lame, mp3_buffer, sizeof(mp3_buffer));
            if (written > 0) {
                fwrite(mp3_buffer, 1, (size_t) written, out);
            }
        } while (written > 0);
    }
    result = ftell(out) > 0 ? 0 : -1;

    cleanup:
    if (lame != NULL) lame_close(lame);
    if (out != NULL) fclose(out);
    if (pcm != NULL) fclose(pcm);
    if (result != 0 && dst != NULL) remove(dst);

    (*env)->ReleaseStringUTFChars(env, src_path, src);
    (*env)->ReleaseStringUTFChars(env, dst_path, dst);
    return result;
}
