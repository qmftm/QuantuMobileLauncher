/*
 * Test backend: X11 + GLX (Mesa on Xvfb is enough). Lets the shim run the real Minecraft 26.x on a Linux desktop so the
 * SDL side can be checked without a phone. Not shipped.
 */
#include <X11/Xlib.h>
#include <X11/Xutil.h>
#include <X11/keysym.h>
#include <dlfcn.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "qm_backend.h"

typedef struct __GLXcontextRec *GLXContext;
typedef struct __GLXFBConfigRec *GLXFBConfig;
#define GLX_X_RENDERABLE 0x8012
#define GLX_DRAWABLE_TYPE 0x8010
#define GLX_WINDOW_BIT 0x00000001
#define GLX_RENDER_TYPE 0x8011
#define GLX_RGBA_BIT 0x00000001
#define GLX_DOUBLEBUFFER 5
#define GLX_RED_SIZE 8
#define GLX_GREEN_SIZE 9
#define GLX_BLUE_SIZE 10
#define GLX_DEPTH_SIZE 12
#define GLX_STENCIL_SIZE 13
#define GLX_CONTEXT_MAJOR_VERSION_ARB 0x2091
#define GLX_CONTEXT_MINOR_VERSION_ARB 0x2092
#define GLX_CONTEXT_PROFILE_MASK_ARB 0x9126
#define GLX_CONTEXT_CORE_PROFILE_BIT_ARB 0x00000001

static GLXFBConfig *(*p_ChooseFBConfig)(Display *, int, const int *, int *);
static XVisualInfo *(*p_GetVisualFromFBConfig)(Display *, GLXFBConfig);
static GLXContext (*p_CreateContextAttribs)(Display *, GLXFBConfig, GLXContext, int, const int *);
static int (*p_MakeCurrent)(Display *, Window, GLXContext);
static void (*p_SwapBuffers)(Display *, Window);
static void *(*p_GetProcAddress)(const unsigned char *);

static Display *dpy;
static Window xwin;
static GLXFBConfig fbc;
static int width = 1280, height = 720;

int qmb_init(void) {
    XInitThreads();
    void *gl = dlopen("libGL.so.1", RTLD_NOW | RTLD_GLOBAL);
    if (!gl) return 0;
    p_ChooseFBConfig = dlsym(gl, "glXChooseFBConfig");
    p_GetVisualFromFBConfig = dlsym(gl, "glXGetVisualFromFBConfig");
    p_MakeCurrent = dlsym(gl, "glXMakeCurrent");
    p_SwapBuffers = dlsym(gl, "glXSwapBuffers");
    p_GetProcAddress = dlsym(gl, "glXGetProcAddressARB");
    p_CreateContextAttribs = (void *) p_GetProcAddress((const unsigned char *) "glXCreateContextAttribsARB");
    dpy = XOpenDisplay(NULL);
    if (!dpy) return 0;
    int attrs[] = { GLX_X_RENDERABLE, 1, GLX_DRAWABLE_TYPE, GLX_WINDOW_BIT, GLX_RENDER_TYPE, GLX_RGBA_BIT,
                    GLX_RED_SIZE, 8, GLX_GREEN_SIZE, 8, GLX_BLUE_SIZE, 8, GLX_DEPTH_SIZE, 24, GLX_STENCIL_SIZE, 8,
                    GLX_DOUBLEBUFFER, 1, 0 };
    int n = 0;
    GLXFBConfig *list = p_ChooseFBConfig(dpy, DefaultScreen(dpy), attrs, &n);
    if (!list || n == 0) return 0;
    fbc = list[0];
    XVisualInfo *vi = p_GetVisualFromFBConfig(dpy, fbc);
    XSetWindowAttributes swa;
    memset(&swa, 0, sizeof swa);
    swa.colormap = XCreateColormap(dpy, RootWindow(dpy, vi->screen), vi->visual, AllocNone);
    swa.event_mask = KeyPressMask | KeyReleaseMask | ButtonPressMask | ButtonReleaseMask | PointerMotionMask | StructureNotifyMask;
    width = DisplayWidth(dpy, vi->screen);
    height = DisplayHeight(dpy, vi->screen);
    xwin = XCreateWindow(dpy, RootWindow(dpy, vi->screen), 0, 0, (unsigned) width, (unsigned) height, 0, vi->depth, InputOutput,
                         vi->visual, CWColormap | CWEventMask, &swa);
    XMapWindow(dpy, xwin);
    XFlush(dpy);
    return 1;
}

void qmb_screen(int *w, int *h, float *refresh) { *w = width; *h = height; *refresh = 60; }

void *qmb_gl_create(void *share, int major, int minor, int core) {
    int attrs[] = { GLX_CONTEXT_MAJOR_VERSION_ARB, major, GLX_CONTEXT_MINOR_VERSION_ARB, minor,
                    GLX_CONTEXT_PROFILE_MASK_ARB, core ? GLX_CONTEXT_CORE_PROFILE_BIT_ARB : 2, 0 };
    return p_CreateContextAttribs(dpy, fbc, (GLXContext) share, 1, attrs);
}
void qmb_gl_make_current(void *ctx) { p_MakeCurrent(dpy, ctx ? xwin : None, (GLXContext) ctx); }
void qmb_gl_swap(void) { p_SwapBuffers(dpy, xwin); }
void qmb_gl_interval(int interval) { (void) interval; }
void *qmb_gl_proc(const char *name) { return p_GetProcAddress((const unsigned char *) name); }

static int glfw_key(KeySym k) {
    if (k >= XK_a && k <= XK_z) return 65 + (int) (k - XK_a);
    if (k >= XK_0 && k <= XK_9) return 48 + (int) (k - XK_0);
    switch (k) {
        case XK_space: return 32;
        case XK_Escape: return 256;
        case XK_Return: return 257;
        case XK_Tab: return 258;
        case XK_BackSpace: return 259;
        case XK_Left: return 263;
        case XK_Right: return 262;
        case XK_Up: return 265;
        case XK_Down: return 264;
        case XK_Shift_L: return 340;
        case XK_Control_L: return 341;
        case XK_slash: return 47;
        default: return -1;
    }
}

void qmb_pump(void) {
    while (XPending(dpy)) {
        XEvent ev;
        XNextEvent(dpy, &ev);
        switch (ev.type) {
            case KeyPress: case KeyRelease: {
                KeySym ks = XLookupKeysym(&ev.xkey, 0);
                int k = glfw_key(ks);
                if (k >= 0) qm_key(k, ev.type == KeyPress, 0);
                if (ev.type == KeyPress) {
                    char buf[8];
                    int n = XLookupString(&ev.xkey, buf, sizeof buf, NULL, NULL);
                    if (n == 1) qm_char((unsigned char) buf[0]);
                }
                break;
            }
            case ButtonPress: case ButtonRelease:
                if (ev.xbutton.button == 4 || ev.xbutton.button == 5) {
                    if (ev.type == ButtonPress) qm_scroll(0, ev.xbutton.button == 4 ? 1 : -1);
                } else {
                    qm_cursor(ev.xbutton.x, ev.xbutton.y);
                    qm_button(ev.xbutton.button == 1 ? 0 : ev.xbutton.button == 3 ? 1 : 2, ev.type == ButtonPress);
                }
                break;
            case MotionNotify:
                qm_cursor(ev.xmotion.x, ev.xmotion.y);
                break;
            case ConfigureNotify:
                width = ev.xconfigure.width;
                height = ev.xconfigure.height;
                break;
        }
    }
}

void qmb_set_relative(int on) { (void) on; }
void qmb_warp(float x, float y) { (void) x; (void) y; }
char *qmb_clipboard_get(void) { return NULL; }
void qmb_clipboard_set(const char *text) { (void) text; }
void qmb_open_url(const char *url) { fprintf(stderr, "SDL shim: open %s\n", url); }
void qmb_text_input(int on) { (void) on; }
void qmb_log(const char *msg) { fprintf(stderr, "%s\n", msg); }
