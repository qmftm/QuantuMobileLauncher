/*
 * Starts an Android-built OpenJDK inside the current (":game") process through JLI_Launch,
 * the same entry point the `java` executable uses.
 */
#include <jni.h>
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <android/log.h>

#define TAG "QMLBridge"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

#if defined(__aarch64__)
#define JRE_ARCH "aarch64"
#elif defined(__arm__)
#define JRE_ARCH "aarch32"
#elif defined(__x86_64__)
#define JRE_ARCH "amd64"
#elif defined(__i386__)
#define JRE_ARCH "i386"
#else
#error "Unsupported architecture"
#endif

typedef int (*JLI_Launch_fn)(int argc, char **argv,
                             int jargc, const char **jargv,
                             int appclassc, const char **appclassv,
                             const char *fullversion, const char *dotversion,
                             const char *pname, const char *lname,
                             jboolean javaargs, jboolean cpwildcard,
                             jboolean javaw, jint ergo);

/* Java 9+ keeps libraries in lib/, Java 8 in lib/<arch>/. */
static void *open_runtime_lib(const char *home, const char *rel, int required) {
    char path[4096];
    const char *layouts[] = {"%s/lib/%s", "%s/lib/" JRE_ARCH "/%s", "%s/jre/lib/" JRE_ARCH "/%s"};
    for (size_t i = 0; i < sizeof(layouts) / sizeof(layouts[0]); i++) {
        snprintf(path, sizeof(path), layouts[i], home, rel);
        if (access(path, R_OK) != 0) continue;
        void *handle = dlopen(path, RTLD_NOW | RTLD_GLOBAL);
        if (handle) {
            LOGI("Loaded %s", path);
            return handle;
        }
        LOGE("dlopen(%s) failed: %s", path, dlerror());
    }
    if (required) LOGE("Runtime library %s not found under %s", rel, home);
    return NULL;
}

static void redirect_output(const char *log_path) {
    int fd = open(log_path, O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0644);
    if (fd < 0) {
        LOGE("Cannot open log %s: %s", log_path, strerror(errno));
        return;
    }
    dup2(fd, STDOUT_FILENO);
    dup2(fd, STDERR_FILENO);
    close(fd);
    setvbuf(stdout, NULL, _IONBF, 0);
    setvbuf(stderr, NULL, _IONBF, 0);
}

JNIEXPORT jint JNICALL
Java_com_quantumobile_launcher_game_NativeBridge_launchJvm(JNIEnv *env, jclass clazz,
                                                           jstring jJavaHome, jobjectArray jArgs,
                                                           jobjectArray jEnv, jstring jWorkDir,
                                                           jstring jLogPath) {
    const char *java_home = (*env)->GetStringUTFChars(env, jJavaHome, NULL);
    const char *work_dir = (*env)->GetStringUTFChars(env, jWorkDir, NULL);
    const char *log_path = (*env)->GetStringUTFChars(env, jLogPath, NULL);

    redirect_output(log_path);

    jsize env_count = (*env)->GetArrayLength(env, jEnv);
    for (jsize i = 0; i < env_count; i++) {
        jstring item = (jstring) (*env)->GetObjectArrayElement(env, jEnv, i);
        const char *kv = (*env)->GetStringUTFChars(env, item, NULL);
        const char *eq = strchr(kv, '=');
        if (eq) {
            char *key = strndup(kv, (size_t) (eq - kv));
            setenv(key, eq + 1, 1);
            free(key);
        }
        (*env)->ReleaseStringUTFChars(env, item, kv);
        (*env)->DeleteLocalRef(env, item);
    }

    if (chdir(work_dir) != 0) LOGE("chdir(%s) failed: %s", work_dir, strerror(errno));

    /*
     * Android's linker only resolves DT_NEEDED entries against libraries that are already loaded
     * (it ignores LD_LIBRARY_PATH changes at runtime), so preload the JVM in dependency order.
     */
    if (!open_runtime_lib(java_home, "server/libjvm.so", 0) &&
        !open_runtime_lib(java_home, "client/libjvm.so", 1)) {
        printf("[QML] libjvm.so not found in %s\n", java_home);
        return -1;
    }
    const char *preload[] = {"libverify.so", "libjava.so", "libzip.so", "libnet.so", "libnio.so",
                             "libjimage.so", "libawt.so", "libawt_headless.so", "libmanagement.so"};
    for (size_t i = 0; i < sizeof(preload) / sizeof(preload[0]); i++) open_runtime_lib(java_home, preload[i], 0);

    void *jli = open_runtime_lib(java_home, "libjli.so", 0);
    if (!jli) jli = open_runtime_lib(java_home, "jli/libjli.so", 1);
    JLI_Launch_fn jli_launch = jli ? (JLI_Launch_fn) dlsym(jli, "JLI_Launch") : NULL;
    if (!jli_launch) {
        printf("[QML] JLI_Launch not found in %s\n", java_home);
        return -2;
    }

    jsize argc = (*env)->GetArrayLength(env, jArgs);
    char **argv = calloc((size_t) argc + 2, sizeof(char *));
    argv[0] = "java";
    for (jsize i = 0; i < argc; i++) {
        jstring item = (jstring) (*env)->GetObjectArrayElement(env, jArgs, i);
        const char *s = (*env)->GetStringUTFChars(env, item, NULL);
        argv[i + 1] = strdup(s);
        (*env)->ReleaseStringUTFChars(env, item, s);
        (*env)->DeleteLocalRef(env, item);
        int secret = i > 0 && strcmp(argv[i], "--accessToken") == 0;
        printf("[QML] arg[%d] %s\n", (int) i, secret ? "<redacted>" : argv[i + 1]);
    }

    (*env)->ReleaseStringUTFChars(env, jJavaHome, java_home);
    (*env)->ReleaseStringUTFChars(env, jWorkDir, work_dir);
    (*env)->ReleaseStringUTFChars(env, jLogPath, log_path);

    LOGI("Calling JLI_Launch with %d arguments", (int) argc);
    /* Returns only when the JVM exits (or fails to start); Minecraft normally ends with System.exit. */
    int rc = jli_launch(argc + 1, argv, 0, NULL, 0, NULL, "1.8.0-internal", "1.8", "java", "openjdk",
                        JNI_FALSE, JNI_TRUE, JNI_FALSE, 0);
    LOGI("JLI_Launch returned %d", rc);
    return rc;
}
