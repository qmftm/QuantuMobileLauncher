#ifndef POJAVLAUNCHER_LOG_H
#define POJAVLAUNCHER_LOG_H

#include <android/log.h>
#include <stdio.h>

#ifndef TAG
#define TAG "jrelog"
#endif

#ifdef __cplusplus
extern "C" {
#endif

/* Errors and warnings also go to stderr, which the launcher points at the game log file, so they show up in order there. */
#define LOGE(...) do { __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__); fprintf(stderr, "[native E/" TAG "] "); fprintf(stderr, __VA_ARGS__); fputc('\n', stderr); } while (0)
#define LOGW(...) do { __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__); fprintf(stderr, "[native W/" TAG "] "); fprintf(stderr, __VA_ARGS__); fputc('\n', stderr); } while (0)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,    TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG,    TAG, __VA_ARGS__)

#ifdef __cplusplus
}
#endif

#endif

