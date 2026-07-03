#include "native_logger.h"

#include <android/log.h>
#include <pthread.h>
#include <stdarg.h>
#include <stdio.h>
#include <string.h>

#define NL_FALLBACK_TAG "NATIVE_LOGGER"
#define NL_MAX_TAG 64
#define NL_MAX_FILE 96
#define NL_MAX_MESSAGE 1024
#define NL_EARLY_LOG_COUNT 128

typedef struct NativeLogEntry {
    int priority;
    int line;
    char tag[NL_MAX_TAG];
    char file[NL_MAX_FILE];
    char message[NL_MAX_MESSAGE];
} NativeLogEntry;

static pthread_mutex_t g_logger_lock = PTHREAD_MUTEX_INITIALIZER;
static JavaVM *g_vm = NULL;
static jobject g_logger = NULL;
static jmethodID g_write_method = NULL;
static NativeLogEntry g_early_logs[NL_EARLY_LOG_COUNT];
static size_t g_early_start = 0;
static size_t g_early_count = 0;

static const char *safe_tag(const char *tag);
static const char *short_file_name(const char *file);
static void copy_string(char *dst, size_t dst_size, const char *src);
static void store_early_log_locked(const NativeLogEntry *entry);
static size_t drain_early_logs_locked(NativeLogEntry *out, size_t out_count);
static int call_java_logger(const NativeLogEntry *entry);
static JNIEnv *get_env(JavaVM *vm, int *attached);
static void write_fallback_logcat(const NativeLogEntry *entry);

void native_logger_init(JavaVM *vm) {
    pthread_mutex_lock(&g_logger_lock);
    g_vm = vm;
    pthread_mutex_unlock(&g_logger_lock);
}

void native_logger_set_java_logger(JNIEnv *env, jobject logger) {
    if (env == NULL) {
        return;
    }

    jobject new_logger = NULL;
    jmethodID new_method = NULL;
    if (logger != NULL) {
        jclass logger_class = env->GetObjectClass(logger);
        if (logger_class == NULL) {
            env->ExceptionClear();
            return;
        }

        new_method = env->GetMethodID(logger_class,
                                      "writeFromNative",
                                      "(ILjava/lang/String;Ljava/lang/String;ILjava/lang/String;)V");
        env->DeleteLocalRef(logger_class);
        if (new_method == NULL) {
            env->ExceptionClear();
            __android_log_print(ANDROID_LOG_ERROR,
                                NL_FALLBACK_TAG,
                                "FileLogger.writeFromNative method not found");
            return;
        }

        new_logger = env->NewGlobalRef(logger);
        if (new_logger == NULL) {
            env->ExceptionClear();
            return;
        }
    }

    NativeLogEntry pending[NL_EARLY_LOG_COUNT];
    size_t pending_count = 0;

    pthread_mutex_lock(&g_logger_lock);
    jobject old_logger = g_logger;
    g_logger = new_logger;
    g_write_method = new_method;
    if (new_logger != NULL) {
        pending_count = drain_early_logs_locked(pending, NL_EARLY_LOG_COUNT);
    }
    pthread_mutex_unlock(&g_logger_lock);

    if (old_logger != NULL) {
        env->DeleteGlobalRef(old_logger);
    }

    for (size_t i = 0; i < pending_count; i++) {
        call_java_logger(&pending[i]);
    }
}

void native_logger_write(int priority,
                         const char *tag,
                         const char *file,
                         int line,
                         const char *fmt,
                         ...) {
    NativeLogEntry entry;
    entry.priority = priority;
    entry.line = line;
    copy_string(entry.tag, sizeof(entry.tag), safe_tag(tag));
    copy_string(entry.file, sizeof(entry.file), short_file_name(file));

    if (fmt == NULL) {
        entry.message[0] = '\0';
    } else {
        va_list args;
        va_start(args, fmt);
        vsnprintf(entry.message, sizeof(entry.message), fmt, args);
        va_end(args);
    }

    int wrote_to_java = call_java_logger(&entry);
    if (!wrote_to_java) {
        pthread_mutex_lock(&g_logger_lock);
        if (g_logger == NULL) {
            store_early_log_locked(&entry);
        }
        pthread_mutex_unlock(&g_logger_lock);
    }

    write_fallback_logcat(&entry);
}

static const char *safe_tag(const char *tag) {
    return tag != NULL && tag[0] != '\0' ? tag : NL_FALLBACK_TAG;
}

static const char *short_file_name(const char *file) {
    if (file == NULL || file[0] == '\0') {
        return "";
    }
    const char *slash = strrchr(file, '/');
    return slash != NULL ? slash + 1 : file;
}

static void copy_string(char *dst, size_t dst_size, const char *src) {
    if (dst_size == 0) {
        return;
    }
    if (src == NULL) {
        dst[0] = '\0';
        return;
    }
    snprintf(dst, dst_size, "%s", src);
}

static void store_early_log_locked(const NativeLogEntry *entry) {
    if (entry == NULL) {
        return;
    }
    size_t index;
    if (g_early_count < NL_EARLY_LOG_COUNT) {
        index = (g_early_start + g_early_count) % NL_EARLY_LOG_COUNT;
        g_early_count++;
    } else {
        index = g_early_start;
        g_early_start = (g_early_start + 1) % NL_EARLY_LOG_COUNT;
    }
    g_early_logs[index] = *entry;
}

static size_t drain_early_logs_locked(NativeLogEntry *out, size_t out_count) {
    if (out == NULL || out_count == 0) {
        return 0;
    }

    size_t count = g_early_count < out_count ? g_early_count : out_count;
    for (size_t i = 0; i < count; i++) {
        size_t index = (g_early_start + i) % NL_EARLY_LOG_COUNT;
        out[i] = g_early_logs[index];
    }
    g_early_start = 0;
    g_early_count = 0;
    return count;
}

static int call_java_logger(const NativeLogEntry *entry) {
    if (entry == NULL) {
        return 0;
    }

    pthread_mutex_lock(&g_logger_lock);
    JavaVM *vm = g_vm;
    int has_logger = g_logger != NULL && g_write_method != NULL;
    pthread_mutex_unlock(&g_logger_lock);
    if (vm == NULL || !has_logger) {
        return 0;
    }

    int attached = 0;
    JNIEnv *env = get_env(vm, &attached);
    if (env == NULL) {
        return 0;
    }

    jobject logger = NULL;
    jmethodID write_method = NULL;
    pthread_mutex_lock(&g_logger_lock);
    if (g_logger != NULL && g_write_method != NULL) {
        logger = env->NewLocalRef(g_logger);
        write_method = g_write_method;
    }
    pthread_mutex_unlock(&g_logger_lock);

    if (logger == NULL || write_method == NULL) {
        if (attached) {
            vm->DetachCurrentThread();
        }
        return 0;
    }

    jstring tag = env->NewStringUTF(entry->tag);
    jstring file = env->NewStringUTF(entry->file);
    jstring message = env->NewStringUTF(entry->message);
    if (tag == NULL || file == NULL || message == NULL) {
        env->ExceptionClear();
        if (tag != NULL) {
            env->DeleteLocalRef(tag);
        }
        if (file != NULL) {
            env->DeleteLocalRef(file);
        }
        if (message != NULL) {
            env->DeleteLocalRef(message);
        }
        env->DeleteLocalRef(logger);
        if (attached) {
            vm->DetachCurrentThread();
        }
        return 0;
    }

    env->CallVoidMethod(logger,
                        write_method,
                        entry->priority,
                        tag,
                        file,
                        entry->line,
                        message);
    int ok = !env->ExceptionCheck();
    if (!ok) {
        env->ExceptionClear();
    }

    env->DeleteLocalRef(message);
    env->DeleteLocalRef(file);
    env->DeleteLocalRef(tag);
    env->DeleteLocalRef(logger);

    if (attached) {
        vm->DetachCurrentThread();
    }
    return ok;
}

static JNIEnv *get_env(JavaVM *vm, int *attached) {
    if (attached != NULL) {
        *attached = 0;
    }

    JNIEnv *env = NULL;
    jint result = vm->GetEnv((void **) &env, JNI_VERSION_1_6);
    if (result == JNI_OK) {
        return env;
    }
    if (result != JNI_EDETACHED) {
        return NULL;
    }

    if (vm->AttachCurrentThread(&env, NULL) != JNI_OK) {
        return NULL;
    }
    if (attached != NULL) {
        *attached = 1;
    }
    return env;
}

static void write_fallback_logcat(const NativeLogEntry *entry) {
    if (entry == NULL) {
        return;
    }
    __android_log_print(entry->priority,
                        safe_tag(entry->tag),
                        "%s:%d %s",
                        entry->file,
                        entry->line,
                        entry->message);
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_lsposed_1template_NativeBridge_nativeSetLogger(JNIEnv *env,
                                                                jclass clazz,
                                                                jobject logger) {
    (void) clazz;
    native_logger_set_java_logger(env, logger);
}
