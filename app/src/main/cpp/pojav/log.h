#ifndef POJAVLAUNCHER_LOG_H
#define POJAVLAUNCHER_LOG_H

#include <android/log.h>
#include <stdio.h>
#include <stdarg.h>

#ifndef TAG
#define TAG "jrelog"
#endif

#ifdef __cplusplus
extern "C" {
#endif

/*
 * Errors and warnings also go to stderr, which the launcher points at the game log file, so they show up in order there.
 * The message is formatted once: arguments such as dlerror() must not be evaluated twice (the second call returns NULL).
 */
static inline void qml_log_both(int prio, const char* tag, const char* fmt, ...) {
    char buf[1024];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(buf, sizeof(buf), fmt, ap);
    va_end(ap);
    __android_log_write(prio, tag, buf);
    fprintf(stderr, "[native %c/%s] %s\n", prio == ANDROID_LOG_ERROR ? 'E' : 'W', tag, buf);
}
#define LOGE(...) qml_log_both(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#define LOGW(...) qml_log_both(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,    TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG,    TAG, __VA_ARGS__)

#ifdef __cplusplus
}
#endif

#endif

