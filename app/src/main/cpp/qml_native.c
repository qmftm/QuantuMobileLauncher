/* Small helpers that are not part of the PojavLauncher bridge. */
#include <jni.h>
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>
#include <android/log.h>

#define TAG "QMLNative"

/*
 * Sends the process' stdout and stderr (where the JVM and Minecraft write) to the game log file. Opened in append mode so the
 * logcat reader thread can add native log lines to the same file.
 */
JNIEXPORT jboolean JNICALL
Java_com_quantumobile_launcher_game_NativeBridge_redirectOutput(JNIEnv *env, jclass clazz, jstring jPath) {
    const char *path = (*env)->GetStringUTFChars(env, jPath, NULL);
    int fd = open(path, O_WRONLY | O_CREAT | O_TRUNC | O_APPEND | O_CLOEXEC, 0644);
    if (fd < 0) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "Cannot open log %s: %s", path, strerror(errno));
        (*env)->ReleaseStringUTFChars(env, jPath, path);
        return JNI_FALSE;
    }
    (*env)->ReleaseStringUTFChars(env, jPath, path);
    dup2(fd, STDOUT_FILENO);
    dup2(fd, STDERR_FILENO);
    close(fd);
    setvbuf(stdout, NULL, _IONBF, 0);
    setvbuf(stderr, NULL, _IONBF, 0);
    return JNI_TRUE;
}
