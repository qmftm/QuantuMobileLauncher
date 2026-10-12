/*
 * QuantuMobileLauncher native bridge — public ABI.
 *
 * A GLFW implementation for Android (e.g. a port of LWJGL's GLFW backend) links against
 * libqmlbridge.so and uses these functions to obtain the Android window and to receive input
 * that the launcher UI collects from touch controls, keyboards and mice.
 *
 * All coordinates are framebuffer pixels. Key codes, actions and modifier bits use GLFW values.
 */
#ifndef QML_BRIDGE_H
#define QML_BRIDGE_H

#include <android/native_window.h>

#ifdef __cplusplus
extern "C" {
#endif

#define QML_EXPORT __attribute__((visibility("default")))

enum {
    QML_EVENT_KEY = 1,          /* i1=key, i2=scancode, i3=action, i4=mods */
    QML_EVENT_CHAR = 2,         /* i1=codepoint, i4=mods */
    QML_EVENT_CURSOR_POS = 3,   /* d1=x, d2=y */
    QML_EVENT_MOUSE_BUTTON = 4, /* i1=button, i3=action, i4=mods */
    QML_EVENT_SCROLL = 5,       /* d1=xoffset, d2=yoffset */
    QML_EVENT_FRAMEBUFFER = 6,  /* i1=width, i2=height: a window is available (again) — recreate the EGL surface */
    QML_EVENT_WINDOW_LOST = 7,  /* the window is gone (app in background): stop rendering and destroy the EGL surface,
                                   but keep the game running; a FRAMEBUFFER event follows when the user returns */
};

typedef struct {
    int type;
    int i1, i2, i3, i4;
    double d1, d2;
} QmlEvent;

/* Window currently backing the game surface, or NULL. Caller must not release it. */
QML_EXPORT ANativeWindow *qml_get_window(void);
QML_EXPORT void qml_get_framebuffer_size(int *width, int *height);

/* Pops the next queued input event. Returns 1 if *out was filled, 0 if the queue is empty. */
QML_EXPORT int qml_poll_event(QmlEvent *out);

/* Called by the GLFW backend when the game captures/releases the cursor (GLFW_CURSOR_DISABLED). */
QML_EXPORT void qml_set_cursor_grabbed(int grabbed);
QML_EXPORT int qml_is_cursor_grabbed(void);

/* Clipboard text shared with the Android UI; returned string is owned by the bridge. */
QML_EXPORT const char *qml_get_clipboard(void);
QML_EXPORT void qml_set_clipboard(const char *utf8);

#ifdef __cplusplus
}
#endif
#endif
