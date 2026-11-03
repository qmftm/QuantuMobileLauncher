/*
 * iPhone backend for the SDL3 shim: drives Amethyst's own pieces, which live in the app executable.
 *   - GL: pojavCreateContext / pojavMakeCurrent / pojavSwapBuffers (EGL bridge over ANGLE/Metal, with the renderer
 *     library chosen by RENDERER, MobileGlues for 26.x)
 *   - Input: Amethyst's touch controls, keyboard and mouse feed GLFW-style events into its queue; pojavPumpEvents hands
 *     them to the GLFW_invoke_* function pointers, which this backend points at the shim's qm_* callbacks.
 * Everything is looked up with dlsym at run time, so the dylib has no link-time tie to a particular app build.
 */
#import <Foundation/Foundation.h>
#import <UIKit/UIKit.h>
#include <dlfcn.h>
#include <stdlib.h>
#include <string.h>

#include "qm_backend.h"

#define GLFW_CLIENT_API 0x00022001
#define GLFW_CONTEXT_VERSION_MAJOR 0x00022002
#define GLFW_OPENGL_API 0x00030001

static int (*p_pojavInit)(BOOL);
static void *(*p_pojavCreateContext)(void *);
static void (*p_pojavMakeCurrent)(void *);
static void (*p_pojavSwapBuffers)(void);
static void (*p_pojavSwapInterval)(int);
static void (*p_pojavSetWindowHint)(int, int);
static void (*p_pojavPumpEvents)(void *);
static void (*p_pojavRewindEvents)(void);
static void (*p_setGrabbing)(void *env, void *cls, unsigned char grabbing, float x, float y);

static int *p_windowWidth, *p_windowHeight;
static double *p_cursorX, *p_cursorY, *p_cLastX, *p_cLastY;
static long *p_showingWindow;
static void *g_gl_lib;

static void *sym(const char *name) {
    void *p = dlsym(RTLD_DEFAULT, name);
    if (!p) NSLog(@"[SDL shim] missing symbol %s", name);
    return p;
}

/* GLFW callback signatures, called by pojavPumpEvents on the game thread */
static void cb_char(void *w, unsigned int cp) { (void) w; qm_char(cp); }
static void cb_cursor(void *w, double x, double y) { (void) w; qm_cursor(x, y); }
static void cb_key(void *w, int key, int scancode, int action, int mods) { (void) w; (void) scancode; qm_key(key, action, mods); }
static void cb_button(void *w, int button, int action, int mods) { (void) w; (void) mods; qm_button(button, action); }
static void cb_scroll(void *w, double dx, double dy) { (void) w; qm_scroll(dx, dy); }

static void set_ptr(const char *name, void *value) {
    void **slot = (void **) sym(name);
    if (slot) *slot = value;
}

int qmb_init(void) {
    p_pojavInit = sym("pojavInit");
    p_pojavCreateContext = sym("pojavCreateContext");
    p_pojavMakeCurrent = sym("pojavMakeCurrent");
    p_pojavSwapBuffers = sym("pojavSwapBuffers");
    p_pojavSwapInterval = sym("pojavSwapInterval");
    p_pojavSetWindowHint = sym("pojavSetWindowHint");
    p_pojavPumpEvents = sym("pojavPumpEvents");
    p_pojavRewindEvents = sym("pojavRewindEvents");
    p_setGrabbing = sym("Java_org_lwjgl_glfw_CallbackBridge_nativeSetGrabbing");
    p_windowWidth = sym("windowWidth");
    p_windowHeight = sym("windowHeight");
    p_cursorX = sym("cursorX");
    p_cursorY = sym("cursorY");
    p_cLastX = sym("cLastX");
    p_cLastY = sym("cLastY");
    p_showingWindow = sym("showingWindow");
    if (!p_pojavInit || !p_pojavCreateContext || !p_pojavMakeCurrent || !p_pojavSwapBuffers || !p_pojavPumpEvents ||
        !p_pojavRewindEvents || !p_windowWidth || !p_windowHeight) return 0;

    /* What Amethyst's GLFW JNI_OnLoad would have set up (the GLFW Java class is not loaded for 26.x). */
    void **vmSlot = (void **) sym("runtimeJavaVMPtr");
    int (*getVMs)(void **, int, int *) = sym("JNI_GetCreatedJavaVMs");
    if (vmSlot && !*vmSlot && getVMs) {
        void *vm = NULL;
        int n = 0;
        if (getVMs(&vm, 1, &n) == 0 && n > 0) *vmSlot = vm;
    }
    void **keyDown = (void **) sym("keyDownBuffer");
    if (keyDown && !*keyDown) *keyDown = calloc(1, 512);
    if (p_showingWindow) *p_showingWindow = 1;

    set_ptr("GLFW_invoke_Char", (void *) cb_char);
    set_ptr("GLFW_invoke_CursorPos", (void *) cb_cursor);
    set_ptr("GLFW_invoke_Key", (void *) cb_key);
    set_ptr("GLFW_invoke_MouseButton", (void *) cb_button);
    set_ptr("GLFW_invoke_Scroll", (void *) cb_scroll);
    /* FramebufferSize/WindowSize stay unset: Amethyst would call back into the GLFW Java class for those. The shim reads
       windowWidth/windowHeight on every pump instead. */

    p_pojavInit(YES);
    if (p_pojavSetWindowHint) {
        p_pojavSetWindowHint(GLFW_CLIENT_API, GLFW_OPENGL_API);
        p_pojavSetWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3); /* "auto" renderer: picks MobileGlues for GL 3+ */
    }
    NSLog(@"[SDL shim] Amethyst backend ready, renderer %s", getenv("RENDERER") ?: "(unset)");
    return 1;
}

void qmb_screen(int *w, int *h, float *refresh) {
    int ww = p_windowWidth ? *p_windowWidth : 0, hh = p_windowHeight ? *p_windowHeight : 0;
    *w = ww > 0 ? ww : 1280;
    *h = hh > 0 ? hh : 720;
    static float fps;
    if (fps <= 0) {
        if ([NSThread isMainThread]) fps = (float) UIScreen.mainScreen.maximumFramesPerSecond;
        else dispatch_sync(dispatch_get_main_queue(), ^{ fps = (float) UIScreen.mainScreen.maximumFramesPerSecond; });
        if (fps <= 0) fps = 60;
    }
    *refresh = fps;
}

void *qmb_gl_create(void *share, int major, int minor, int core) {
    (void) minor; (void) core;
    if (p_pojavSetWindowHint) p_pojavSetWindowHint(GLFW_CONTEXT_VERSION_MAJOR, major);
    return p_pojavCreateContext(share);
}

void qmb_gl_make_current(void *ctx) { p_pojavMakeCurrent(ctx); }
void qmb_gl_swap(void) { p_pojavSwapBuffers(); }
void qmb_gl_interval(int interval) { if (p_pojavSwapInterval) p_pojavSwapInterval(interval); }

void *qmb_gl_proc(const char *name) {
    if (!g_gl_lib) {
        const char *renderer = getenv("RENDERER");
        if (renderer && strcmp(renderer, "auto") != 0) {
            char path[512];
            snprintf(path, sizeof path, "@rpath/%s", renderer);
            g_gl_lib = dlopen(path, RTLD_LAZY | RTLD_GLOBAL);
            NSLog(@"[SDL shim] GL functions from %s: %p", path, g_gl_lib);
        }
    }
    void *p = g_gl_lib ? dlsym(g_gl_lib, name) : NULL;
    return p ? p : dlsym(RTLD_DEFAULT, name);
}

void qmb_pump(void) {
    p_pojavPumpEvents(p_showingWindow ? (void *) *p_showingWindow : (void *) 1);
    p_pojavRewindEvents();
}

void qmb_set_relative(int on) {
    if (p_setGrabbing) p_setGrabbing(NULL, NULL, (unsigned char) (on ? 1 : 0), 0, 0);
}

void qmb_warp(float x, float y) {
    if (p_cursorX) *p_cursorX = x;
    if (p_cursorY) *p_cursorY = y;
    if (p_cLastX) *p_cLastX = x;
    if (p_cLastY) *p_cLastY = y;
}

char *qmb_clipboard_get(void) {
    __block NSString *text = nil;
    void (^read)(void) = ^{ text = UIPasteboard.generalPasteboard.string; };
    if ([NSThread isMainThread]) read(); else dispatch_sync(dispatch_get_main_queue(), read);
    return text ? strdup(text.UTF8String) : NULL;
}

void qmb_clipboard_set(const char *utf8) {
    NSString *text = [NSString stringWithUTF8String:utf8 ?: ""];
    dispatch_async(dispatch_get_main_queue(), ^{ UIPasteboard.generalPasteboard.string = text; });
}

void qmb_open_url(const char *url) {
    NSURL *u = [NSURL URLWithString:[NSString stringWithUTF8String:url ?: ""]];
    if (!u) return;
    dispatch_async(dispatch_get_main_queue(), ^{ [UIApplication.sharedApplication openURL:u options:@{} completionHandler:nil]; });
}

void qmb_text_input(int on) { (void) on; /* the keyboard button in Amethyst's controls opens the keyboard */ }

void qmb_log(const char *msg) { NSLog(@"[SDL shim] %s", msg); }
