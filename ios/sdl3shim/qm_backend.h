/*
 * The platform side of the SDL3 shim. sdl3shim.c implements the SDL3 functions Minecraft 26.x calls; everything that
 * touches a real window, GL context or input source goes through these functions.
 *   backend_amethyst.m  iPhone: Amethyst's EGL bridge and input queue (pojav* functions in the app executable)
 *   backend_glx.c       Linux/X11 + GLX, used to test the shim against the real game on a desktop
 */
#pragma once

int qmb_init(void);                                  /* once, from SDL_Init(SDL_INIT_VIDEO); 0 on failure */
void qmb_screen(int *w, int *h, float *refresh);     /* current drawable size in pixels and refresh rate */
void *qmb_gl_create(void *share, int major, int minor, int core_profile);
void qmb_gl_make_current(void *ctx);                 /* ctx may be NULL */
void qmb_gl_swap(void);
void qmb_gl_interval(int interval);
void *qmb_gl_proc(const char *name);
void qmb_pump(void);                                 /* delivers pending input through the qm_* callbacks */
void qmb_set_relative(int on);                       /* mouse captured for camera look (in-game) or free (menus) */
void qmb_warp(float x, float y);
char *qmb_clipboard_get(void);                       /* malloc'd UTF-8 or NULL */
void qmb_clipboard_set(const char *text);
void qmb_open_url(const char *url);
void qmb_text_input(int on);                         /* a text field wants the on-screen keyboard */
void qmb_log(const char *msg);

/* Called by the backend (from qmb_pump or any thread) with GLFW-style values. */
void qm_key(int glfw_key, int action, int glfw_mods);    /* action: 0 release, 1 press, 2 repeat */
void qm_char(unsigned int codepoint);
void qm_cursor(double x, double y);
void qm_button(int glfw_button, int action);
void qm_scroll(double dx, double dy);
void qm_resize(int w, int h);
void qm_focus(int focused);
void qm_quit(void);
