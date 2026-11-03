/*
 * A small SDL3 stand-in for Minecraft 26.x (LWJGL 3.4's SDL binding loads it as "libSDL3").
 *
 * The real SDL3 wants to own the iOS app (UIApplicationMain, its own UIWindow, event pumping on the main thread), which
 * clashes with the launcher that already owns the screen, the touch controls and the GL bridge. Minecraft only uses a
 * small part of SDL, so this library implements exactly that part on top of the launcher's existing pieces (see
 * qm_backend.h). Every other SDL3 function exists as a stub (sdl3stubs.c) because LWJGL looks up all functions of a
 * header at once.
 */
#include <SDL3/SDL.h>
#include <SDL3/SDL_vulkan.h>
#include <pthread.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#include "qm_backend.h"

#define QM_MAX_WINDOWS 16
#define QM_QUEUE 1024
#define QM_TEXTS 64

typedef struct QMWindow {
    SDL_WindowID id;
    SDL_WindowFlags flags;
    char title[128];
    int relative;
} QMWindow;

static QMWindow g_windows[QM_MAX_WINDOWS];
static int g_inited;
static int g_relative;
static SDL_WindowID g_focus;          /* the visible window that gets input */
static SDL_GLContext g_current;       /* per process is enough: only the render thread draws */
static __thread SDL_GLContext t_current;
static int g_gl_attr[64];
static int g_width = 1280, g_height = 720;
static float g_refresh = 60;
static float g_mx, g_my;              /* cursor in window pixels */
static int g_have_cursor;
static SDL_MouseButtonFlags g_buttons;
static bool g_keys[SDL_SCANCODE_COUNT];
static SDL_Keymod g_mods;
static char g_error[256];

static pthread_mutex_t g_lock = PTHREAD_MUTEX_INITIALIZER;
static SDL_Event g_queue[QM_QUEUE];
static int g_head, g_count;
static char g_texts[QM_TEXTS][16];
static int g_text_next;

static SDL_LogOutputFunction g_log_fn;
static void *g_log_ud;

static void qm_logf(const char *fmt, ...) {
    char buf[512];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(buf, sizeof buf, fmt, ap);
    va_end(ap);
    qmb_log(buf);
}

static Uint64 now_ns(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (Uint64) ts.tv_sec * 1000000000ull + (Uint64) ts.tv_nsec;
}

static int g_trace = -1;
static void push(SDL_Event *e) {
    e->common.timestamp = now_ns();
    if (g_trace < 0) g_trace = getenv("QM_SDL_TRACE") != NULL;
    if (g_trace && e->type != SDL_EVENT_MOUSE_MOTION) qm_logf("SDL shim: event 0x%x", e->type);
    pthread_mutex_lock(&g_lock);
    if (g_count < QM_QUEUE) {
        g_queue[(g_head + g_count) % QM_QUEUE] = *e;
        g_count++;
    }
    pthread_mutex_unlock(&g_lock);
}

static QMWindow *win(SDL_Window *w) { return (QMWindow *) w; }

static SDL_WindowID focus_id(void) {
    if (g_focus) return g_focus;
    for (int i = 0; i < QM_MAX_WINDOWS; i++) if (g_windows[i].id) return g_windows[i].id;
    return 0;
}

/* ---------------- keyboard tables ---------------- */

static SDL_Scancode glfw_to_scancode(int k) {
    if (k >= 65 && k <= 90) return (SDL_Scancode) (SDL_SCANCODE_A + (k - 65));
    if (k >= 49 && k <= 57) return (SDL_Scancode) (SDL_SCANCODE_1 + (k - 49));
    if (k >= 290 && k <= 301) return (SDL_Scancode) (SDL_SCANCODE_F1 + (k - 290));
    if (k >= 302 && k <= 313) return (SDL_Scancode) (SDL_SCANCODE_F13 + (k - 302));
    if (k >= 321 && k <= 329) return (SDL_Scancode) (SDL_SCANCODE_KP_1 + (k - 321));
    switch (k) {
        case 32: return SDL_SCANCODE_SPACE;
        case 39: return SDL_SCANCODE_APOSTROPHE;
        case 44: return SDL_SCANCODE_COMMA;
        case 45: return SDL_SCANCODE_MINUS;
        case 46: return SDL_SCANCODE_PERIOD;
        case 47: return SDL_SCANCODE_SLASH;
        case 48: return SDL_SCANCODE_0;
        case 59: return SDL_SCANCODE_SEMICOLON;
        case 61: return SDL_SCANCODE_EQUALS;
        case 91: return SDL_SCANCODE_LEFTBRACKET;
        case 92: return SDL_SCANCODE_BACKSLASH;
        case 93: return SDL_SCANCODE_RIGHTBRACKET;
        case 96: return SDL_SCANCODE_GRAVE;
        case 161: case 162: return SDL_SCANCODE_NONUSBACKSLASH;
        case 256: return SDL_SCANCODE_ESCAPE;
        case 257: return SDL_SCANCODE_RETURN;
        case 258: return SDL_SCANCODE_TAB;
        case 259: return SDL_SCANCODE_BACKSPACE;
        case 260: return SDL_SCANCODE_INSERT;
        case 261: return SDL_SCANCODE_DELETE;
        case 262: return SDL_SCANCODE_RIGHT;
        case 263: return SDL_SCANCODE_LEFT;
        case 264: return SDL_SCANCODE_DOWN;
        case 265: return SDL_SCANCODE_UP;
        case 266: return SDL_SCANCODE_PAGEUP;
        case 267: return SDL_SCANCODE_PAGEDOWN;
        case 268: return SDL_SCANCODE_HOME;
        case 269: return SDL_SCANCODE_END;
        case 280: return SDL_SCANCODE_CAPSLOCK;
        case 281: return SDL_SCANCODE_SCROLLLOCK;
        case 282: return SDL_SCANCODE_NUMLOCKCLEAR;
        case 283: return SDL_SCANCODE_PRINTSCREEN;
        case 284: return SDL_SCANCODE_PAUSE;
        case 320: return SDL_SCANCODE_KP_0;
        case 330: return SDL_SCANCODE_KP_PERIOD;
        case 331: return SDL_SCANCODE_KP_DIVIDE;
        case 332: return SDL_SCANCODE_KP_MULTIPLY;
        case 333: return SDL_SCANCODE_KP_MINUS;
        case 334: return SDL_SCANCODE_KP_PLUS;
        case 335: return SDL_SCANCODE_KP_ENTER;
        case 336: return SDL_SCANCODE_KP_EQUALS;
        case 340: return SDL_SCANCODE_LSHIFT;
        case 341: return SDL_SCANCODE_LCTRL;
        case 342: return SDL_SCANCODE_LALT;
        case 343: return SDL_SCANCODE_LGUI;
        case 344: return SDL_SCANCODE_RSHIFT;
        case 345: return SDL_SCANCODE_RCTRL;
        case 346: return SDL_SCANCODE_RALT;
        case 347: return SDL_SCANCODE_RGUI;
        case 348: return SDL_SCANCODE_MENU;
        default: return SDL_SCANCODE_UNKNOWN;
    }
}

/* US layout, like SDL's default keymap. */
static SDL_Keycode scancode_to_key(SDL_Scancode sc) {
    if (sc >= SDL_SCANCODE_A && sc <= SDL_SCANCODE_Z) return (SDL_Keycode) ('a' + (sc - SDL_SCANCODE_A));
    if (sc >= SDL_SCANCODE_1 && sc <= SDL_SCANCODE_9) return (SDL_Keycode) ('1' + (sc - SDL_SCANCODE_1));
    switch (sc) {
        case SDL_SCANCODE_0: return '0';
        case SDL_SCANCODE_RETURN: return SDLK_RETURN;
        case SDL_SCANCODE_ESCAPE: return SDLK_ESCAPE;
        case SDL_SCANCODE_BACKSPACE: return SDLK_BACKSPACE;
        case SDL_SCANCODE_TAB: return SDLK_TAB;
        case SDL_SCANCODE_SPACE: return SDLK_SPACE;
        case SDL_SCANCODE_MINUS: return '-';
        case SDL_SCANCODE_EQUALS: return '=';
        case SDL_SCANCODE_LEFTBRACKET: return '[';
        case SDL_SCANCODE_RIGHTBRACKET: return ']';
        case SDL_SCANCODE_BACKSLASH: return '\\';
        case SDL_SCANCODE_SEMICOLON: return ';';
        case SDL_SCANCODE_APOSTROPHE: return '\'';
        case SDL_SCANCODE_GRAVE: return '`';
        case SDL_SCANCODE_COMMA: return ',';
        case SDL_SCANCODE_PERIOD: return '.';
        case SDL_SCANCODE_SLASH: return '/';
        case SDL_SCANCODE_DELETE: return SDLK_DELETE;
        default: return SDL_SCANCODE_TO_KEYCODE(sc);
    }
}

static SDL_Keymod mod_for(SDL_Scancode sc) {
    switch (sc) {
        case SDL_SCANCODE_LSHIFT: return SDL_KMOD_LSHIFT;
        case SDL_SCANCODE_RSHIFT: return SDL_KMOD_RSHIFT;
        case SDL_SCANCODE_LCTRL: return SDL_KMOD_LCTRL;
        case SDL_SCANCODE_RCTRL: return SDL_KMOD_RCTRL;
        case SDL_SCANCODE_LALT: return SDL_KMOD_LALT;
        case SDL_SCANCODE_RALT: return SDL_KMOD_RALT;
        case SDL_SCANCODE_LGUI: return SDL_KMOD_LGUI;
        case SDL_SCANCODE_RGUI: return SDL_KMOD_RGUI;
        default: return 0;
    }
}

/* ---------------- input from the backend ---------------- */

void qm_key(int glfw_key, int action, int glfw_mods) {
    (void) glfw_mods;
    SDL_Scancode sc = glfw_to_scancode(glfw_key);
    if (sc == SDL_SCANCODE_UNKNOWN) return;
    bool down = action != 0;
    SDL_Keymod m = mod_for(sc);
    if (m) g_mods = down ? (g_mods | m) : (g_mods & ~m);
    g_keys[sc] = down;
    SDL_Event e;
    memset(&e, 0, sizeof e);
    e.type = down ? SDL_EVENT_KEY_DOWN : SDL_EVENT_KEY_UP;
    e.key.windowID = focus_id();
    e.key.which = 1;
    e.key.scancode = sc;
    e.key.key = scancode_to_key(sc);
    e.key.mod = g_mods;
    e.key.down = down;
    e.key.repeat = action == 2;
    push(&e);
}

void qm_char(unsigned int cp) {
    if (cp < 32 || cp == 127) return;
    char *buf;
    pthread_mutex_lock(&g_lock);
    buf = g_texts[g_text_next];
    g_text_next = (g_text_next + 1) % QM_TEXTS;
    pthread_mutex_unlock(&g_lock);
    int n = 0;
    if (cp < 0x80) buf[n++] = (char) cp;
    else if (cp < 0x800) { buf[n++] = (char) (0xC0 | (cp >> 6)); buf[n++] = (char) (0x80 | (cp & 0x3F)); }
    else if (cp < 0x10000) { buf[n++] = (char) (0xE0 | (cp >> 12)); buf[n++] = (char) (0x80 | ((cp >> 6) & 0x3F)); buf[n++] = (char) (0x80 | (cp & 0x3F)); }
    else { buf[n++] = (char) (0xF0 | (cp >> 18)); buf[n++] = (char) (0x80 | ((cp >> 12) & 0x3F)); buf[n++] = (char) (0x80 | ((cp >> 6) & 0x3F)); buf[n++] = (char) (0x80 | (cp & 0x3F)); }
    buf[n] = 0;
    SDL_Event e;
    memset(&e, 0, sizeof e);
    e.type = SDL_EVENT_TEXT_INPUT;
    e.text.windowID = focus_id();
    e.text.text = buf;
    push(&e);
}

void qm_cursor(double x, double y) {
    float fx = (float) x, fy = (float) y;
    int first = !g_have_cursor;
    if (first) { g_mx = fx; g_my = fy; g_have_cursor = 1; }
    float dx = fx - g_mx, dy = fy - g_my;
    g_mx = fx; g_my = fy;
    if (dx == 0 && dy == 0 && !first) return;
    SDL_Event e;
    memset(&e, 0, sizeof e);
    e.type = SDL_EVENT_MOUSE_MOTION;
    e.motion.windowID = focus_id();
    e.motion.which = 1;
    e.motion.state = g_buttons;
    e.motion.x = fx; e.motion.y = fy;
    e.motion.xrel = dx; e.motion.yrel = dy;
    push(&e);
}

void qm_button(int glfw_button, int action) {
    Uint8 b = glfw_button == 0 ? SDL_BUTTON_LEFT : glfw_button == 1 ? SDL_BUTTON_RIGHT : glfw_button == 2 ? SDL_BUTTON_MIDDLE
        : (Uint8) (glfw_button + 1);
    bool down = action != 0;
    if (down) g_buttons |= SDL_BUTTON_MASK(b); else g_buttons &= ~SDL_BUTTON_MASK(b);
    SDL_Event e;
    memset(&e, 0, sizeof e);
    e.type = down ? SDL_EVENT_MOUSE_BUTTON_DOWN : SDL_EVENT_MOUSE_BUTTON_UP;
    e.button.windowID = focus_id();
    e.button.which = 1;
    e.button.button = b;
    e.button.down = down;
    e.button.clicks = 1;
    e.button.x = g_mx; e.button.y = g_my;
    push(&e);
}

void qm_scroll(double dx, double dy) {
    SDL_Event e;
    memset(&e, 0, sizeof e);
    e.type = SDL_EVENT_MOUSE_WHEEL;
    e.wheel.windowID = focus_id();
    e.wheel.which = 1;
    e.wheel.x = (float) dx; e.wheel.y = (float) dy;
    e.wheel.direction = SDL_MOUSEWHEEL_NORMAL;
    e.wheel.mouse_x = g_mx; e.wheel.mouse_y = g_my;
    e.wheel.integer_x = (Sint32) dx; e.wheel.integer_y = (Sint32) dy;
    push(&e);
}

void qm_resize(int w, int h) {
    if (w <= 0 || h <= 0 || (w == g_width && h == g_height)) return;
    g_width = w; g_height = h;
    SDL_Event e;
    memset(&e, 0, sizeof e);
    e.window.windowID = focus_id();
    e.window.data1 = w; e.window.data2 = h;
    e.type = SDL_EVENT_WINDOW_RESIZED; push(&e);
    e.type = SDL_EVENT_WINDOW_PIXEL_SIZE_CHANGED; push(&e);
}

void qm_focus(int focused) {
    SDL_Event e;
    memset(&e, 0, sizeof e);
    e.type = focused ? SDL_EVENT_WINDOW_FOCUS_GAINED : SDL_EVENT_WINDOW_FOCUS_LOST;
    e.window.windowID = focus_id();
    push(&e);
}

void qm_quit(void) {
    SDL_Event e;
    memset(&e, 0, sizeof e);
    e.type = SDL_EVENT_WINDOW_CLOSE_REQUESTED;
    e.window.windowID = focus_id();
    push(&e);
    e.type = SDL_EVENT_QUIT;
    push(&e);
}

/* ---------------- init, errors, hints, log ---------------- */

bool SDL_Init(SDL_InitFlags flags) {
    if ((flags & SDL_INIT_VIDEO) && !g_inited) {
        if (!qmb_init()) { snprintf(g_error, sizeof g_error, "QuantuMobile SDL shim: backend init failed"); return false; }
        qmb_screen(&g_width, &g_height, &g_refresh);
        g_inited = 1;
        qm_logf("SDL shim: init %dx%d @%.0fHz", g_width, g_height, g_refresh);
    }
    return true;
}
bool SDL_InitSubSystem(SDL_InitFlags flags) { return SDL_Init(flags); }
void SDL_Quit(void) {}
void SDL_QuitSubSystem(SDL_InitFlags flags) { (void) flags; }
SDL_InitFlags SDL_WasInit(SDL_InitFlags flags) { return g_inited ? (flags & SDL_INIT_VIDEO) : 0; }
const char *SDL_GetError(void) { return g_error; }
bool SDL_ClearError(void) { g_error[0] = 0; return true; }
bool SDL_SetHint(const char *name, const char *value) { (void) name; (void) value; return true; }
bool SDL_SetAppMetadataProperty(const char *name, const char *value) { (void) name; (void) value; return true; }
void SDL_SetLogOutputFunction(SDL_LogOutputFunction callback, void *userdata) { g_log_fn = callback; g_log_ud = userdata; }
void SDL_SetLogPriorities(SDL_LogPriority priority) { (void) priority; }
const char *SDL_GetPlatform(void) { return "iOS"; }
int SDL_GetVersion(void) { return SDL_VERSION; }
const char *SDL_GetRevision(void) { return "quantumobile-sdl3-shim"; }
Uint64 SDL_GetTicksNS(void) { return now_ns(); }
Uint64 SDL_GetTicks(void) { return now_ns() / 1000000ull; }
void SDL_free(void *mem) { free(mem); }
void *SDL_malloc(size_t size) { return malloc(size); }
void *SDL_calloc(size_t n, size_t size) { return calloc(n, size); }
bool SDL_OpenURL(const char *url) { qmb_open_url(url); return true; }

/* ---------------- displays ---------------- */

static SDL_DisplayMode g_mode;
static SDL_DisplayMode *mode_now(void) {
    qmb_screen(&g_width, &g_height, &g_refresh);
    memset(&g_mode, 0, sizeof g_mode);
    g_mode.displayID = 1;
    g_mode.format = SDL_PIXELFORMAT_XRGB8888;
    g_mode.w = g_width; g_mode.h = g_height;
    g_mode.pixel_density = 1.0f;
    g_mode.refresh_rate = g_refresh;
    g_mode.refresh_rate_numerator = (int) g_refresh;
    g_mode.refresh_rate_denominator = 1;
    return &g_mode;
}

SDL_DisplayID *SDL_GetDisplays(int *count) {
    SDL_DisplayID *ids = calloc(2, sizeof *ids);
    ids[0] = 1;
    if (count) *count = 1;
    return ids;
}
SDL_DisplayID SDL_GetPrimaryDisplay(void) { return 1; }
const char *SDL_GetDisplayName(SDL_DisplayID id) { (void) id; return "Built-in Screen"; }
bool SDL_GetDisplayBounds(SDL_DisplayID id, SDL_Rect *r) {
    (void) id; mode_now();
    if (r) { r->x = 0; r->y = 0; r->w = g_width; r->h = g_height; }
    return true;
}
bool SDL_GetDisplayUsableBounds(SDL_DisplayID id, SDL_Rect *r) { return SDL_GetDisplayBounds(id, r); }
const SDL_DisplayMode *SDL_GetDesktopDisplayMode(SDL_DisplayID id) { (void) id; return mode_now(); }
const SDL_DisplayMode *SDL_GetCurrentDisplayMode(SDL_DisplayID id) { (void) id; return mode_now(); }
SDL_DisplayMode **SDL_GetFullscreenDisplayModes(SDL_DisplayID id, int *count) {
    (void) id;
    /* one allocation, freed with SDL_free: [ptr, NULL, mode] */
    char *block = calloc(1, 2 * sizeof(SDL_DisplayMode *) + sizeof(SDL_DisplayMode));
    SDL_DisplayMode **list = (SDL_DisplayMode **) block;
    SDL_DisplayMode *m = (SDL_DisplayMode *) (block + 2 * sizeof(SDL_DisplayMode *));
    *m = *mode_now();
    list[0] = m; list[1] = NULL;
    if (count) *count = 1;
    return list;
}
bool SDL_GetClosestFullscreenDisplayMode(SDL_DisplayID id, int w, int h, float refresh, bool hd, SDL_DisplayMode *closest) {
    (void) id; (void) w; (void) h; (void) refresh; (void) hd;
    if (closest) *closest = *mode_now();
    return true;
}
SDL_DisplayID SDL_GetDisplayForWindow(SDL_Window *w) { (void) w; return 1; }
float SDL_GetDisplayContentScale(SDL_DisplayID id) { (void) id; return 1.0f; }
const char *SDL_GetCurrentVideoDriver(void) { return "quantumobile"; }
int SDL_GetNumVideoDrivers(void) { return 1; }
const char *SDL_GetVideoDriver(int i) { return i == 0 ? "quantumobile" : NULL; }

static SDL_PixelFormatDetails g_fmt = { SDL_PIXELFORMAT_XRGB8888, 32, 4, {0, 0}, 0x00FF0000, 0x0000FF00, 0x000000FF, 0, 8, 8, 8, 0, 16, 8, 0, 0 };
const SDL_PixelFormatDetails *SDL_GetPixelFormatDetails(SDL_PixelFormat format) { (void) format; return &g_fmt; }

/* ---------------- windows ---------------- */

SDL_Window *SDL_CreateWindow(const char *title, int w, int h, SDL_WindowFlags flags) {
    (void) w; (void) h;
    static SDL_WindowID next = 1;
    for (int i = 0; i < QM_MAX_WINDOWS; i++) {
        if (g_windows[i].id) continue;
        QMWindow *qw = &g_windows[i];
        memset(qw, 0, sizeof *qw);
        qw->id = next++;
        qw->flags = flags | SDL_WINDOW_INPUT_FOCUS | SDL_WINDOW_MOUSE_FOCUS;
        if (title) snprintf(qw->title, sizeof qw->title, "%s", title);
        if (!(flags & SDL_WINDOW_HIDDEN)) {
            g_focus = qw->id;
            SDL_Event e;
            memset(&e, 0, sizeof e);
            e.window.windowID = qw->id;
            e.type = SDL_EVENT_WINDOW_SHOWN; push(&e);
            e.type = SDL_EVENT_WINDOW_FOCUS_GAINED; push(&e);
        }
        qm_logf("SDL shim: window %u '%s' flags=0x%llx", qw->id, qw->title, (unsigned long long) flags);
        return (SDL_Window *) qw;
    }
    snprintf(g_error, sizeof g_error, "too many windows");
    return NULL;
}
void SDL_DestroyWindow(SDL_Window *w) {
    if (!w) return;
    if (win(w)->id == g_focus) g_focus = 0;
    win(w)->id = 0;
}
SDL_WindowID SDL_GetWindowID(SDL_Window *w) { return w ? win(w)->id : 0; }
SDL_Window *SDL_GetWindowFromID(SDL_WindowID id) {
    for (int i = 0; i < QM_MAX_WINDOWS; i++) if (id && g_windows[i].id == id) return (SDL_Window *) &g_windows[i];
    return NULL;
}
SDL_Window *SDL_GetWindowFromEvent(const SDL_Event *e) {
    if (!e) return NULL;
    SDL_WindowID id = 0;
    switch (e->type) {
        case SDL_EVENT_KEY_DOWN: case SDL_EVENT_KEY_UP: id = e->key.windowID; break;
        case SDL_EVENT_TEXT_INPUT: id = e->text.windowID; break;
        case SDL_EVENT_MOUSE_MOTION: id = e->motion.windowID; break;
        case SDL_EVENT_MOUSE_BUTTON_DOWN: case SDL_EVENT_MOUSE_BUTTON_UP: id = e->button.windowID; break;
        case SDL_EVENT_MOUSE_WHEEL: id = e->wheel.windowID; break;
        default:
            if (e->type >= SDL_EVENT_WINDOW_FIRST && e->type <= SDL_EVENT_WINDOW_LAST) id = e->window.windowID;
    }
    return SDL_GetWindowFromID(id);
}
SDL_WindowFlags SDL_GetWindowFlags(SDL_Window *w) {
    if (!w) return 0;
    SDL_WindowFlags f = win(w)->flags;
    if (win(w)->relative) f |= SDL_WINDOW_MOUSE_RELATIVE_MODE;
    return f;
}
bool SDL_GetWindowSize(SDL_Window *w, int *pw, int *ph) {
    (void) w; qmb_screen(&g_width, &g_height, &g_refresh);
    if (pw) *pw = g_width;
    if (ph) *ph = g_height;
    return true;
}
bool SDL_GetWindowSizeInPixels(SDL_Window *w, int *pw, int *ph) { return SDL_GetWindowSize(w, pw, ph); }
float SDL_GetWindowPixelDensity(SDL_Window *w) { (void) w; return 1.0f; }
float SDL_GetWindowDisplayScale(SDL_Window *w) { (void) w; return 1.0f; }
bool SDL_GetWindowPosition(SDL_Window *w, int *x, int *y) { (void) w; if (x) *x = 0; if (y) *y = 0; return true; }
const SDL_DisplayMode *SDL_GetWindowFullscreenMode(SDL_Window *w) { (void) w; return NULL; }
bool SDL_SetWindowFullscreenMode(SDL_Window *w, const SDL_DisplayMode *m) { (void) w; (void) m; return true; }
bool SDL_SetWindowFullscreen(SDL_Window *w, bool fs) {
    if (w) { if (fs) win(w)->flags |= SDL_WINDOW_FULLSCREEN; else win(w)->flags &= ~SDL_WINDOW_FULLSCREEN; }
    return true;
}
bool SDL_SetWindowBordered(SDL_Window *w, bool b) { (void) w; (void) b; return true; }
bool SDL_SetWindowIcon(SDL_Window *w, SDL_Surface *s) { (void) w; (void) s; return true; }
bool SDL_SetWindowMaximumSize(SDL_Window *w, int a, int b) { (void) w; (void) a; (void) b; return true; }
bool SDL_SetWindowMinimumSize(SDL_Window *w, int a, int b) { (void) w; (void) a; (void) b; return true; }
bool SDL_SetWindowMouseGrab(SDL_Window *w, bool g) { (void) w; (void) g; return true; }
bool SDL_SetWindowPosition(SDL_Window *w, int x, int y) { (void) w; (void) x; (void) y; return true; }
bool SDL_SetWindowSize(SDL_Window *w, int a, int b) { (void) w; (void) a; (void) b; return true; }
bool SDL_SetWindowTitle(SDL_Window *w, const char *t) { if (w && t) snprintf(win(w)->title, sizeof win(w)->title, "%s", t); return true; }
const char *SDL_GetWindowTitle(SDL_Window *w) { return w ? win(w)->title : ""; }
bool SDL_SyncWindow(SDL_Window *w) { (void) w; return true; }
bool SDL_RestoreWindow(SDL_Window *w) { (void) w; return true; }
bool SDL_ShowWindow(SDL_Window *w) {
    if (!w) return false;
    win(w)->flags &= ~SDL_WINDOW_HIDDEN;
    g_focus = win(w)->id;
    return true;
}
bool SDL_HideWindow(SDL_Window *w) { if (w) win(w)->flags |= SDL_WINDOW_HIDDEN; return true; }
bool SDL_RaiseWindow(SDL_Window *w) { (void) w; return true; }

/* ---------------- OpenGL ---------------- */

bool SDL_GL_LoadLibrary(const char *path) { (void) path; return true; }
void SDL_GL_UnloadLibrary(void) {}
SDL_FunctionPointer SDL_GL_GetProcAddress(const char *proc) { return (SDL_FunctionPointer) qmb_gl_proc(proc); }
bool SDL_GL_ExtensionSupported(const char *ext) { (void) ext; return false; }
void SDL_GL_ResetAttributes(void) { memset(g_gl_attr, 0, sizeof g_gl_attr); }
bool SDL_GL_SetAttribute(SDL_GLAttr attr, int value) {
    if ((int) attr >= 0 && (int) attr < 64) g_gl_attr[attr] = value;
    return true;
}
bool SDL_GL_GetAttribute(SDL_GLAttr attr, int *value) {
    if (!value) return false;
    switch (attr) {
        case SDL_GL_RED_SIZE: case SDL_GL_GREEN_SIZE: case SDL_GL_BLUE_SIZE: case SDL_GL_ALPHA_SIZE: *value = 8; break;
        case SDL_GL_DEPTH_SIZE: *value = 24; break;
        case SDL_GL_STENCIL_SIZE: *value = 8; break;
        case SDL_GL_DOUBLEBUFFER: *value = 1; break;
        case SDL_GL_ACCELERATED_VISUAL: *value = 1; break;
        default: *value = ((int) attr >= 0 && (int) attr < 64) ? g_gl_attr[attr] : 0;
    }
    return true;
}
SDL_GLContext SDL_GL_CreateContext(SDL_Window *w) {
    (void) w;
    void *share = g_gl_attr[SDL_GL_SHARE_WITH_CURRENT_CONTEXT] ? (void *) t_current : NULL;
    int major = g_gl_attr[SDL_GL_CONTEXT_MAJOR_VERSION] ? g_gl_attr[SDL_GL_CONTEXT_MAJOR_VERSION] : 3;
    int minor = g_gl_attr[SDL_GL_CONTEXT_MINOR_VERSION];
    int core = (g_gl_attr[SDL_GL_CONTEXT_PROFILE_MASK] & SDL_GL_CONTEXT_PROFILE_CORE) != 0;
    void *ctx = qmb_gl_create(share, major, minor, core);
    qm_logf("SDL shim: GL context %p (GL %d.%d%s, share %p) for window %u", ctx, major, minor, core ? " core" : "", share,
            w ? win(w)->id : 0);
    if (!ctx) { snprintf(g_error, sizeof g_error, "could not create an OpenGL context"); return NULL; }
    /* SDL makes the new context current */
    qmb_gl_make_current(ctx);
    t_current = (SDL_GLContext) ctx;
    g_current = t_current;
    return (SDL_GLContext) ctx;
}
bool SDL_GL_MakeCurrent(SDL_Window *w, SDL_GLContext ctx) {
    (void) w;
    qmb_gl_make_current((void *) ctx);
    t_current = ctx;
    if (ctx) g_current = ctx;
    return true;
}
SDL_GLContext SDL_GL_GetCurrentContext(void) { return t_current; }
SDL_Window *SDL_GL_GetCurrentWindow(void) { return SDL_GetWindowFromID(focus_id()); }
bool SDL_GL_DestroyContext(SDL_GLContext ctx) { (void) ctx; return true; }
bool SDL_GL_SetSwapInterval(int interval) { qmb_gl_interval(interval); return true; }
bool SDL_GL_GetSwapInterval(int *interval) { if (interval) *interval = 1; return true; }
bool SDL_GL_SwapWindow(SDL_Window *w) { (void) w; qmb_gl_swap(); return true; }

/* ---------------- Vulkan (not offered: the game runs with --graphicsBackend opengl) ---------------- */

bool SDL_Vulkan_LoadLibrary(const char *path) { (void) path; snprintf(g_error, sizeof g_error, "Vulkan is not available"); return false; }
void SDL_Vulkan_UnloadLibrary(void) {}
SDL_FunctionPointer SDL_Vulkan_GetVkGetInstanceProcAddr(void) { return NULL; }
char const *const *SDL_Vulkan_GetInstanceExtensions(Uint32 *count) { if (count) *count = 0; return NULL; }
bool SDL_Vulkan_CreateSurface(SDL_Window *w, VkInstance i, const struct VkAllocationCallbacks *a, VkSurfaceKHR *s) {
    (void) w; (void) i; (void) a; (void) s; return false;
}
bool SDL_Vulkan_GetPresentationSupport(VkInstance i, VkPhysicalDevice d, Uint32 q) { (void) i; (void) d; (void) q; return false; }

/* ---------------- events ---------------- */

void SDL_PumpEvents(void) {
    if (!g_inited) return;
    qmb_pump();
    int w, h;
    float r;
    qmb_screen(&w, &h, &r);
    qm_resize(w, h);
}

bool SDL_PollEvent(SDL_Event *event) {
    SDL_PumpEvents();
    pthread_mutex_lock(&g_lock);
    bool have = g_count > 0;
    if (have && event) {
        *event = g_queue[g_head];
        g_head = (g_head + 1) % QM_QUEUE;
        g_count--;
    }
    pthread_mutex_unlock(&g_lock);
    return have;
}
bool SDL_WaitEventTimeout(SDL_Event *event, Sint32 ms) {
    (void) ms;
    return SDL_PollEvent(event);
}
bool SDL_WaitEvent(SDL_Event *event) { return SDL_PollEvent(event); }
bool SDL_PushEvent(SDL_Event *event) { if (event) push(event); return true; }
void SDL_FlushEvents(Uint32 minType, Uint32 maxType) {
    pthread_mutex_lock(&g_lock);
    SDL_Event keep[QM_QUEUE];
    int n = 0;
    for (int i = 0; i < g_count; i++) {
        SDL_Event *e = &g_queue[(g_head + i) % QM_QUEUE];
        if (e->type < minType || e->type > maxType) keep[n++] = *e;
    }
    for (int i = 0; i < n; i++) g_queue[i] = keep[i];
    g_head = 0; g_count = n;
    pthread_mutex_unlock(&g_lock);
}
void SDL_FlushEvent(Uint32 type) { SDL_FlushEvents(type, type); }
bool SDL_HasEvent(Uint32 type) {
    pthread_mutex_lock(&g_lock);
    bool found = false;
    for (int i = 0; i < g_count && !found; i++) found = g_queue[(g_head + i) % QM_QUEUE].type == type;
    pthread_mutex_unlock(&g_lock);
    return found;
}

/* ---------------- keyboard ---------------- */

const bool *SDL_GetKeyboardState(int *numkeys) { if (numkeys) *numkeys = SDL_SCANCODE_COUNT; return g_keys; }
SDL_Keymod SDL_GetModState(void) { return g_mods; }
void SDL_SetModState(SDL_Keymod m) { g_mods = m; }
SDL_Keycode SDL_GetKeyFromScancode(SDL_Scancode sc, SDL_Keymod mod, bool key_event) { (void) mod; (void) key_event; return scancode_to_key(sc); }
SDL_Scancode SDL_GetScancodeFromKey(SDL_Keycode key, SDL_Keymod *mod) {
    if (mod) *mod = 0;
    if (key & SDLK_SCANCODE_MASK) return (SDL_Scancode) (key & ~SDLK_SCANCODE_MASK);
    for (int sc = 0; sc < SDL_SCANCODE_COUNT; sc++) if (scancode_to_key((SDL_Scancode) sc) == key) return (SDL_Scancode) sc;
    return SDL_SCANCODE_UNKNOWN;
}

static const char *scancode_name(SDL_Scancode sc) {
    switch (sc) {
        case SDL_SCANCODE_RETURN: return "Return";
        case SDL_SCANCODE_ESCAPE: return "Escape";
        case SDL_SCANCODE_BACKSPACE: return "Backspace";
        case SDL_SCANCODE_TAB: return "Tab";
        case SDL_SCANCODE_SPACE: return "Space";
        case SDL_SCANCODE_CAPSLOCK: return "CapsLock";
        case SDL_SCANCODE_PRINTSCREEN: return "PrintScreen";
        case SDL_SCANCODE_SCROLLLOCK: return "ScrollLock";
        case SDL_SCANCODE_PAUSE: return "Pause";
        case SDL_SCANCODE_INSERT: return "Insert";
        case SDL_SCANCODE_HOME: return "Home";
        case SDL_SCANCODE_PAGEUP: return "PageUp";
        case SDL_SCANCODE_DELETE: return "Delete";
        case SDL_SCANCODE_END: return "End";
        case SDL_SCANCODE_PAGEDOWN: return "PageDown";
        case SDL_SCANCODE_RIGHT: return "Right";
        case SDL_SCANCODE_LEFT: return "Left";
        case SDL_SCANCODE_DOWN: return "Down";
        case SDL_SCANCODE_UP: return "Up";
        case SDL_SCANCODE_NUMLOCKCLEAR: return "Numlock";
        case SDL_SCANCODE_KP_DIVIDE: return "Keypad /";
        case SDL_SCANCODE_KP_MULTIPLY: return "Keypad *";
        case SDL_SCANCODE_KP_MINUS: return "Keypad -";
        case SDL_SCANCODE_KP_PLUS: return "Keypad +";
        case SDL_SCANCODE_KP_ENTER: return "Keypad Enter";
        case SDL_SCANCODE_KP_PERIOD: return "Keypad .";
        case SDL_SCANCODE_KP_EQUALS: return "Keypad =";
        case SDL_SCANCODE_KP_0: return "Keypad 0";
        case SDL_SCANCODE_KP_1: return "Keypad 1";
        case SDL_SCANCODE_KP_2: return "Keypad 2";
        case SDL_SCANCODE_KP_3: return "Keypad 3";
        case SDL_SCANCODE_KP_4: return "Keypad 4";
        case SDL_SCANCODE_KP_5: return "Keypad 5";
        case SDL_SCANCODE_KP_6: return "Keypad 6";
        case SDL_SCANCODE_KP_7: return "Keypad 7";
        case SDL_SCANCODE_KP_8: return "Keypad 8";
        case SDL_SCANCODE_KP_9: return "Keypad 9";
        case SDL_SCANCODE_MENU: return "Menu";
        case SDL_SCANCODE_LCTRL: return "Left Ctrl";
        case SDL_SCANCODE_LSHIFT: return "Left Shift";
        case SDL_SCANCODE_LALT: return "Left Alt";
        case SDL_SCANCODE_LGUI: return "Left GUI";
        case SDL_SCANCODE_RCTRL: return "Right Ctrl";
        case SDL_SCANCODE_RSHIFT: return "Right Shift";
        case SDL_SCANCODE_RALT: return "Right Alt";
        case SDL_SCANCODE_RGUI: return "Right GUI";
        default: break;
    }
    if (sc >= SDL_SCANCODE_F1 && sc <= SDL_SCANCODE_F12) {
        static char f[12][4];
        snprintf(f[sc - SDL_SCANCODE_F1], 4, "F%d", 1 + (sc - SDL_SCANCODE_F1));
        return f[sc - SDL_SCANCODE_F1];
    }
    if (sc >= SDL_SCANCODE_F13 && sc <= SDL_SCANCODE_F24) {
        static char f2[12][4];
        snprintf(f2[sc - SDL_SCANCODE_F13], 4, "F%d", 13 + (sc - SDL_SCANCODE_F13));
        return f2[sc - SDL_SCANCODE_F13];
    }
    return "";
}
const char *SDL_GetKeyName(SDL_Keycode key) {
    static char one[128][2];
    if (key & SDLK_SCANCODE_MASK) return scancode_name((SDL_Scancode) (key & ~SDLK_SCANCODE_MASK));
    switch (key) {
        case SDLK_RETURN: return "Return";
        case SDLK_ESCAPE: return "Escape";
        case SDLK_BACKSPACE: return "Backspace";
        case SDLK_TAB: return "Tab";
        case SDLK_SPACE: return "Space";
        case SDLK_DELETE: return "Delete";
        default: break;
    }
    if (key > 32 && key < 127) {
        char c = (char) key;
        if (c >= 'a' && c <= 'z') c = (char) (c - 'a' + 'A');
        one[key][0] = c; one[key][1] = 0;
        return one[key];
    }
    return "";
}
const char *SDL_GetScancodeName(SDL_Scancode sc) {
    const char *n = scancode_name(sc);
    return *n ? n : SDL_GetKeyName(scancode_to_key(sc));
}
bool SDL_StartTextInput(SDL_Window *w) { (void) w; qmb_text_input(1); return true; }
bool SDL_StartTextInputWithProperties(SDL_Window *w, SDL_PropertiesID p) { (void) p; return SDL_StartTextInput(w); }
bool SDL_StopTextInput(SDL_Window *w) { (void) w; qmb_text_input(0); return true; }
bool SDL_TextInputActive(SDL_Window *w) { (void) w; return true; }
bool SDL_ClearComposition(SDL_Window *w) { (void) w; return true; }
bool SDL_SetTextInputArea(SDL_Window *w, const SDL_Rect *r, int c) { (void) w; (void) r; (void) c; return true; }
bool SDL_HasScreenKeyboardSupport(void) { return true; }

/* ---------------- mouse ---------------- */

static int g_cursor_dummy;
SDL_MouseButtonFlags SDL_GetMouseState(float *x, float *y) { if (x) *x = g_mx; if (y) *y = g_my; return g_buttons; }
SDL_MouseButtonFlags SDL_GetGlobalMouseState(float *x, float *y) { return SDL_GetMouseState(x, y); }
SDL_MouseButtonFlags SDL_GetRelativeMouseState(float *x, float *y) { if (x) *x = 0; if (y) *y = 0; return g_buttons; }
bool SDL_SetWindowRelativeMouseMode(SDL_Window *w, bool enabled) {
    if (w) win(w)->relative = enabled;
    if (g_relative != (int) enabled) {
        g_relative = enabled;
        qm_logf("SDL shim: relative mouse %s", enabled ? "on" : "off");
        qmb_set_relative(enabled);
    }
    return true;
}
bool SDL_GetWindowRelativeMouseMode(SDL_Window *w) { return w ? win(w)->relative : g_relative; }
void SDL_WarpMouseInWindow(SDL_Window *w, float x, float y) { (void) w; g_mx = x; g_my = y; qmb_warp(x, y); }
SDL_Cursor *SDL_CreateSystemCursor(SDL_SystemCursor id) { (void) id; return (SDL_Cursor *) &g_cursor_dummy; }
SDL_Cursor *SDL_CreateCursor(const Uint8 *d, const Uint8 *m, int w, int h, int x, int y) {
    (void) d; (void) m; (void) w; (void) h; (void) x; (void) y; return (SDL_Cursor *) &g_cursor_dummy;
}
SDL_Cursor *SDL_CreateColorCursor(SDL_Surface *s, int x, int y) { (void) s; (void) x; (void) y; return (SDL_Cursor *) &g_cursor_dummy; }
SDL_Cursor *SDL_GetDefaultCursor(void) { return (SDL_Cursor *) &g_cursor_dummy; }
SDL_Cursor *SDL_GetCursor(void) { return (SDL_Cursor *) &g_cursor_dummy; }
bool SDL_SetCursor(SDL_Cursor *c) { (void) c; return true; }
void SDL_DestroyCursor(SDL_Cursor *c) { (void) c; }
bool SDL_ShowCursor(void) { return true; }
bool SDL_HideCursor(void) { return true; }
bool SDL_CursorVisible(void) { return !g_relative; }
bool SDL_HasMouse(void) { return true; }
bool SDL_HasKeyboard(void) { return true; }

/* ---------------- clipboard, message boxes, surfaces ---------------- */

char *SDL_GetClipboardText(void) {
    char *t = qmb_clipboard_get();
    return t ? t : calloc(1, 1);
}
bool SDL_SetClipboardText(const char *text) { qmb_clipboard_set(text ? text : ""); return true; }
bool SDL_HasClipboardText(void) { char *t = qmb_clipboard_get(); bool has = t && *t; free(t); return has; }

bool SDL_ShowSimpleMessageBox(SDL_MessageBoxFlags flags, const char *title, const char *message, SDL_Window *w) {
    (void) flags; (void) w;
    qm_logf("SDL shim: message box '%s': %s", title ? title : "", message ? message : "");
    return true;
}
bool SDL_ShowMessageBox(const SDL_MessageBoxData *d, int *buttonid) {
    if (d) qm_logf("SDL shim: message box '%s': %s", d->title ? d->title : "", d->message ? d->message : "");
    if (buttonid) {
        *buttonid = -1;
        if (d) for (int i = 0; i < d->numbuttons; i++)
            if (d->buttons[i].flags & SDL_MESSAGEBOX_BUTTON_RETURNKEY_DEFAULT) *buttonid = d->buttons[i].buttonID;
        if (*buttonid < 0 && d && d->numbuttons > 0) *buttonid = d->buttons[0].buttonID;
    }
    return true;
}

SDL_Surface *SDL_CreateSurfaceFrom(int width, int height, SDL_PixelFormat format, void *pixels, int pitch) {
    SDL_Surface *s = calloc(1, sizeof *s);
    s->format = format;
    s->w = width; s->h = height;
    s->pitch = pitch;
    s->pixels = pixels;
    s->refcount = 1;
    return s;
}
SDL_Surface *SDL_CreateSurface(int width, int height, SDL_PixelFormat format) {
    SDL_Surface *s = SDL_CreateSurfaceFrom(width, height, format, calloc((size_t) width * (size_t) height, 4), width * 4);
    s->flags = 0x1; /* we own the pixels */
    return s;
}
void SDL_DestroySurface(SDL_Surface *s) {
    if (!s) return;
    if (s->flags & 0x1) free(s->pixels);
    free(s);
}
bool SDL_AddSurfaceAlternateImage(SDL_Surface *s, SDL_Surface *img) { (void) s; (void) img; return true; }
