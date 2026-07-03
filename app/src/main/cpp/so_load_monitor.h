#pragma once

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct SoLoadInfo {
    const char *soname;
    void *soinfo;
    uintptr_t base;
} SoLoadInfo;

typedef void (*SoLoadCallback)(const SoLoadInfo *info, void *user_data);

int so_load_monitor_register(const char *soname_substring,
                             SoLoadCallback callback,
                             void *user_data);
int so_load_monitor_start(void);
const char *so_load_monitor_last_error(void);
uintptr_t so_load_monitor_find_module_base(const char *soname_substring);

#ifdef __cplusplus
}
#endif
