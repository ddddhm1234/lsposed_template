#include <jni.h>

#include "native_logger.h"
#include "so_load_monitor.h"

#include <stddef.h>
#include <stdatomic.h>
#include <stdint.h>
#include <unistd.h>

#define LOG_TAG "CJMITM_NATIVE"
#define LOGI(...) NATIVE_LOGI(LOG_TAG, __VA_ARGS__)
#define LOGW(...) NATIVE_LOGW(LOG_TAG, __VA_ARGS__)
#define LOGE(...) NATIVE_LOGE(LOG_TAG, __VA_ARGS__)

static const char *const kWatchedModules[] = {
        "libmetasec_ml.so",
        "libEncryptor.so",
};

static atomic_int g_monitor_started = 0;

static void on_so_loaded(const SoLoadInfo *info, void *user_data) {
    const char *target = (const char *) user_data;
    if (info == NULL) {
        LOGW("so load callback without info target=%s", target != NULL ? target : "");
        return;
    }

    LOGI("so loaded target=%s soname=%s base=%p soinfo=%p",
         target != NULL ? target : "",
         info->soname != NULL ? info->soname : "",
         (void *) info->base,
         info->soinfo);
}

static void init_so_load_monitor(void) {
    int expected = 0;
    if (!atomic_compare_exchange_strong(&g_monitor_started, &expected, 1)) {
        return;
    }

    LOGI("native so load monitor init pid=%d", getpid());

    for (size_t i = 0; i < sizeof(kWatchedModules) / sizeof(kWatchedModules[0]); i++) {
        const char *target = kWatchedModules[i];
        if (so_load_monitor_register(target, on_so_loaded, (void *) target) != 0) {
            LOGE("so_load_monitor_register failed target=%s error=%s",
                 target,
                 so_load_monitor_last_error());
        }
    }

    if (so_load_monitor_start() != 0) {
        LOGE("so_load_monitor_start failed error=%s", so_load_monitor_last_error());
        return;
    }

    for (size_t i = 0; i < sizeof(kWatchedModules) / sizeof(kWatchedModules[0]); i++) {
        const char *target = kWatchedModules[i];
        uintptr_t base = so_load_monitor_find_module_base(target);
        if (base != 0) {
            LOGI("so already loaded target=%s soname=%s base=%p soinfo=%p",
                 target,
                 target,
                 (void *) base,
                 (void *) NULL);
        }
    }
}

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    (void) reserved;
    native_logger_init(vm);
    init_so_load_monitor();
    return JNI_VERSION_1_6;
}
