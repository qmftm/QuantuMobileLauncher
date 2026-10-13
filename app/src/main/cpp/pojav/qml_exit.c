/*
 * Replacement for PojavLauncher's exit hook (which needs the bytehook library): when the JVM aborts, record the
 * signal in the game log and end the process so the launcher is not left with a hung game screen.
 */
#include <stdbool.h>
#include <stdio.h>
#include <stdlib.h>
#include <unistd.h>

#define TAG "jrelog"
#include "log.h"

_Noreturn void nominal_exit(int code, bool is_signal) {
    if (is_signal) {
        printf("\n[QML] The Java VM crashed (signal %d). See hs_err_pid*.log in the game folder if present.\n", code);
        LOGE("Java VM aborted with signal %d", code);
    } else {
        LOGI("Exiting with code %d", code);
    }
    fflush(stdout);
    fflush(stderr);
    _exit(code);
}
