#include <jni.h>
#include <pthread.h>
#include <stdlib.h>
#include <string.h>
#include <android/native_window_jni.h>
#include "qml_bridge.h"

#define QUEUE_CAPACITY 2048

static pthread_mutex_t g_lock = PTHREAD_MUTEX_INITIALIZER;
static QmlEvent g_queue[QUEUE_CAPACITY];
static int g_head, g_tail;

static ANativeWindow *g_window;
static int g_width, g_height;
static volatile int g_grabbed;
static char *g_clipboard;

static void push_event(QmlEvent ev) {
    pthread_mutex_lock(&g_lock);
    int next = (g_tail + 1) % QUEUE_CAPACITY;
    if (next != g_head) { /* drop events if the game stops polling rather than blocking the UI */
        g_queue[g_tail] = ev;
        g_tail = next;
    }
    pthread_mutex_unlock(&g_lock);
}

QML_EXPORT int qml_poll_event(QmlEvent *out) {
    int ok = 0;
    pthread_mutex_lock(&g_lock);
    if (g_head != g_tail) {
        *out = g_queue[g_head];
        g_head = (g_head + 1) % QUEUE_CAPACITY;
        ok = 1;
    }
    pthread_mutex_unlock(&g_lock);
    return ok;
}

QML_EXPORT ANativeWindow *qml_get_window(void) { return g_window; }

QML_EXPORT void qml_get_framebuffer_size(int *width, int *height) {
    if (width) *width = g_width;
    if (height) *height = g_height;
}

QML_EXPORT void qml_set_cursor_grabbed(int grabbed) { g_grabbed = grabbed; }
QML_EXPORT int qml_is_cursor_grabbed(void) { return g_grabbed; }

QML_EXPORT const char *qml_get_clipboard(void) { return g_clipboard ? g_clipboard : ""; }

QML_EXPORT void qml_set_clipboard(const char *utf8) {
    pthread_mutex_lock(&g_lock);
    free(g_clipboard);
    g_clipboard = utf8 ? strdup(utf8) : NULL;
    pthread_mutex_unlock(&g_lock);
}

/* ---- JNI: com.quantumobile.launcher.game.NativeBridge ---- */

#define JNI_FN(name) Java_com_quantumobile_launcher_game_NativeBridge_##name

JNIEXPORT void JNICALL JNI_FN(setSurface)(JNIEnv *env, jclass clazz, jobject surface, jint width, jint height) {
    ANativeWindow *old = g_window;
    g_window = surface ? ANativeWindow_fromSurface(env, surface) : NULL;
    g_width = width;
    g_height = height;
    if (old) ANativeWindow_release(old);
    if (g_window) push_event((QmlEvent){.type = QML_EVENT_FRAMEBUFFER, .i1 = width, .i2 = height});
    else push_event((QmlEvent){.type = QML_EVENT_WINDOW_LOST}); /* app went to the background */
}

JNIEXPORT void JNICALL JNI_FN(sendKey)(JNIEnv *env, jclass clazz, jint key, jint scancode, jint action, jint mods) {
    push_event((QmlEvent){.type = QML_EVENT_KEY, .i1 = key, .i2 = scancode, .i3 = action, .i4 = mods});
}

JNIEXPORT void JNICALL JNI_FN(sendChar)(JNIEnv *env, jclass clazz, jint codepoint, jint mods) {
    push_event((QmlEvent){.type = QML_EVENT_CHAR, .i1 = codepoint, .i4 = mods});
}

JNIEXPORT void JNICALL JNI_FN(sendCursorPos)(JNIEnv *env, jclass clazz, jdouble x, jdouble y) {
    push_event((QmlEvent){.type = QML_EVENT_CURSOR_POS, .d1 = x, .d2 = y});
}

JNIEXPORT void JNICALL JNI_FN(sendMouseButton)(JNIEnv *env, jclass clazz, jint button, jint action, jint mods) {
    push_event((QmlEvent){.type = QML_EVENT_MOUSE_BUTTON, .i1 = button, .i3 = action, .i4 = mods});
}

JNIEXPORT void JNICALL JNI_FN(sendScroll)(JNIEnv *env, jclass clazz, jdouble dx, jdouble dy) {
    push_event((QmlEvent){.type = QML_EVENT_SCROLL, .d1 = dx, .d2 = dy});
}

JNIEXPORT jboolean JNICALL JNI_FN(isCursorGrabbed)(JNIEnv *env, jclass clazz) {
    return g_grabbed ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL JNI_FN(setClipboard)(JNIEnv *env, jclass clazz, jstring text) {
    const char *s = text ? (*env)->GetStringUTFChars(env, text, NULL) : NULL;
    qml_set_clipboard(s);
    if (s) (*env)->ReleaseStringUTFChars(env, text, s);
}
