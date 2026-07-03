/*
 * so_load_monitor hooks linker64 soinfo::call_constructors() and notifies
 * registered callbacks after mmap/relocation and before init_array runs.
 *
 * The implementation is arm64/linker64 oriented. Some ROMs strip linker
 * symbols; check so_load_monitor_last_error() and SO_LOAD_MONITOR logs when
 * startup fails.
 */

#include "so_load_monitor.h"

#include "native_logger.h"

#include <frida-gum.h>

#include <elf.h>
#include <fcntl.h>
#include <link.h>
#include <pthread.h>
#include <stdatomic.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>

#define SLM_LOG_TAG "SO_LOAD_MONITOR"
#define SLM_LOGI(...) NATIVE_LOGI(SLM_LOG_TAG, __VA_ARGS__)
#define SLM_LOGW(...) NATIVE_LOGW(SLM_LOG_TAG, __VA_ARGS__)
#define SLM_LOGE(...) NATIVE_LOGE(SLM_LOG_TAG, __VA_ARGS__)

#define SLM_MAX_WATCHERS 16
#define SLM_MAX_SEEN_SOINFOS 256
#define SLM_MAX_SONAME 128
#define SLM_MAX_PATH 512

typedef const char *(*GetSonameFn)(void *soinfo);

typedef struct SoLoadWatcher {
    char soname_substring[SLM_MAX_SONAME];
    SoLoadCallback callback;
    void *user_data;
} SoLoadWatcher;

typedef struct ModulePhdrSearch {
    const char *soname_substring;
    uintptr_t base;
} ModulePhdrSearch;

static pthread_mutex_t g_lock = PTHREAD_MUTEX_INITIALIZER;
static SoLoadWatcher g_watchers[SLM_MAX_WATCHERS];
static void *g_seen_soinfos[SLM_MAX_SEEN_SOINFOS];
static size_t g_seen_soinfo_count = 0;
static atomic_int g_started = 0;
static GetSonameFn g_get_soname = NULL;
static GumInvocationListener *g_linker_listener = NULL;
static const char *g_last_error = "not started";

static int find_linker64(char *path_out, size_t path_out_size, uintptr_t *base_out);
static uintptr_t find_elf_symbol_value(const char *path, const char *symbol_name);
static uintptr_t find_elf_symbol_value_any(const char *path,
                                           const char *const *symbol_names,
                                           size_t symbol_count,
                                           const char **matched_symbol);
static int is_elf64_file(const Elf64_Ehdr *ehdr, size_t file_size);
static int range_in_file(size_t file_size, uint64_t offset, uint64_t size);
static int basename_equals(const char *path, const char *name);
static int find_module_by_phdr(struct dl_phdr_info *info, size_t size, void *data);
static size_t collect_matching_callbacks(const char *soname,
                                         SoLoadCallback *callbacks,
                                         void **user_datas);
static int mark_soinfo_seen(void *soinfo);
static void on_call_constructors(GumInvocationContext *ic, gpointer user_data);

const char *so_load_monitor_last_error(void) {
    return g_last_error;
}

int so_load_monitor_register(const char *soname_substring,
                             SoLoadCallback callback,
                             void *user_data) {
    if (soname_substring == NULL || soname_substring[0] == '\0' || callback == NULL) {
        g_last_error = "invalid callback registration";
        return -1;
    }
    if (strlen(soname_substring) >= SLM_MAX_SONAME) {
        g_last_error = "soname substring is too long";
        return -1;
    }

    pthread_mutex_lock(&g_lock);
    for (size_t i = 0; i < SLM_MAX_WATCHERS; i++) {
        if (g_watchers[i].callback == NULL) {
            snprintf(g_watchers[i].soname_substring,
                     sizeof(g_watchers[i].soname_substring),
                     "%s",
                     soname_substring);
            g_watchers[i].callback = callback;
            g_watchers[i].user_data = user_data;
            pthread_mutex_unlock(&g_lock);
            return 0;
        }
    }
    pthread_mutex_unlock(&g_lock);

    g_last_error = "too many so load watchers";
    return -1;
}

int so_load_monitor_start(void) {
    int expected = 0;
    if (!atomic_compare_exchange_strong(&g_started, &expected, 1)) {
        return 0;
    }

    gum_init_embedded();

    char linker_path[SLM_MAX_PATH];
    uintptr_t linker_base = 0;
    if (!find_linker64(linker_path, sizeof(linker_path), &linker_base)) {
        g_last_error = "failed to find linker64 mapping";
        SLM_LOGE("%s", g_last_error);
        atomic_store(&g_started, 0);
        return -1;
    }

    static const char *const kGetSonameSymbols[] = {
            "__dl__ZNK6soinfo10get_sonameEv",
            "_ZNK6soinfo10get_sonameEv",
    };
    static const char *const kCallConstructorsSymbols[] = {
            "__dl__ZN6soinfo17call_constructorsEv",
            "_ZN6soinfo17call_constructorsEv",
    };

    const char *matched_get_soname = NULL;
    const char *matched_call_constructors = NULL;
    uintptr_t get_soname_offset = find_elf_symbol_value_any(
            linker_path,
            kGetSonameSymbols,
            sizeof(kGetSonameSymbols) / sizeof(kGetSonameSymbols[0]),
            &matched_get_soname);
    uintptr_t call_constructors_offset = find_elf_symbol_value_any(
            linker_path,
            kCallConstructorsSymbols,
            sizeof(kCallConstructorsSymbols) / sizeof(kCallConstructorsSymbols[0]),
            &matched_call_constructors);

    if (get_soname_offset == 0 || call_constructors_offset == 0) {
        g_last_error = "failed to resolve linker64 soinfo symbols";
        SLM_LOGE("%s path=%s get_soname=%p call_constructors=%p",
                 g_last_error,
                 linker_path,
                 (void *) get_soname_offset,
                 (void *) call_constructors_offset);
        atomic_store(&g_started, 0);
        return -1;
    }

    g_get_soname = (GetSonameFn) (linker_base + get_soname_offset);
    void *call_constructors_addr = (void *) (linker_base + call_constructors_offset);

    GumInterceptor *interceptor = gum_interceptor_obtain();
    g_linker_listener = gum_make_call_listener(on_call_constructors, NULL, NULL, NULL);
    GumAttachReturn ret = gum_interceptor_attach(
            interceptor,
            call_constructors_addr,
            g_linker_listener,
            NULL);
    if (ret != GUM_ATTACH_OK) {
        g_last_error = "failed to attach linker64 call_constructors";
        SLM_LOGE("%s ret=%d addr=%p", g_last_error, ret, call_constructors_addr);
        atomic_store(&g_started, 0);
        return -1;
    }

    g_last_error = "ok";
    SLM_LOGI("installed linker hook path=%s base=%p get_soname=%p(%s) call_constructors=%p(%s)",
             linker_path,
             (void *) linker_base,
             (void *) g_get_soname,
             matched_get_soname,
             call_constructors_addr,
             matched_call_constructors);
    return 0;
}

uintptr_t so_load_monitor_find_module_base(const char *soname_substring) {
    if (soname_substring == NULL || soname_substring[0] == '\0') {
        return 0;
    }

    GumModule *module = gum_process_find_module_by_name(soname_substring);
    if (module != NULL) {
        const GumMemoryRange *range = gum_module_get_range(module);
        uintptr_t base = range != NULL ? (uintptr_t) range->base_address : 0;
        g_object_unref(module);
        if (base != 0) {
            return base;
        }
    }

    ModulePhdrSearch search;
    search.soname_substring = soname_substring;
    search.base = 0;
    dl_iterate_phdr(find_module_by_phdr, &search);
    if (search.base != 0) {
        return search.base;
    }

    FILE *fp = fopen("/proc/self/maps", "r");
    if (fp == NULL) {
        return 0;
    }

    uintptr_t best = 0;
    char line[1024];
    while (fgets(line, sizeof(line), fp) != NULL) {
        unsigned long start = 0;
        unsigned long end = 0;
        unsigned long offset = 0;
        unsigned long inode = 0;
        char perms[8] = {0};
        char dev[32] = {0};
        char path[SLM_MAX_PATH] = {0};
        int fields = sscanf(line,
                            "%lx-%lx %7s %lx %31s %lu %511s",
                            &start,
                            &end,
                            perms,
                            &offset,
                            dev,
                            &inode,
                            path);
        if (fields < 7 || path[0] == '\0') {
            continue;
        }
        if (strstr(path, soname_substring) == NULL || start < offset) {
            continue;
        }

        uintptr_t candidate = (uintptr_t) (start - offset);
        if (best == 0 || candidate < best) {
            best = candidate;
        }
    }

    fclose(fp);
    return best;
}

static int find_linker64(char *path_out, size_t path_out_size, uintptr_t *base_out) {
    FILE *fp = fopen("/proc/self/maps", "r");
    if (fp == NULL) {
        return 0;
    }

    uintptr_t best_base = 0;
    char best_path[SLM_MAX_PATH] = {0};
    char line[1024];
    while (fgets(line, sizeof(line), fp) != NULL) {
        unsigned long start = 0;
        unsigned long end = 0;
        unsigned long offset = 0;
        unsigned long inode = 0;
        char perms[8] = {0};
        char dev[32] = {0};
        char path[SLM_MAX_PATH] = {0};
        int fields = sscanf(line,
                            "%lx-%lx %7s %lx %31s %lu %511s",
                            &start,
                            &end,
                            perms,
                            &offset,
                            dev,
                            &inode,
                            path);
        if (fields < 7 || path[0] == '\0' || !basename_equals(path, "linker64")) {
            continue;
        }
        if (start < offset) {
            continue;
        }

        uintptr_t candidate = (uintptr_t) (start - offset);
        if (best_base == 0 || candidate < best_base) {
            best_base = candidate;
            snprintf(best_path, sizeof(best_path), "%s", path);
        }
    }
    fclose(fp);

    if (best_base == 0 || best_path[0] == '\0') {
        return 0;
    }

    if (access(best_path, R_OK) != 0) {
        static const char *const kFallbackPaths[] = {
                "/apex/com.android.runtime/bin/linker64",
                "/system/bin/linker64",
        };
        for (size_t i = 0; i < sizeof(kFallbackPaths) / sizeof(kFallbackPaths[0]); i++) {
            if (access(kFallbackPaths[i], R_OK) == 0) {
                snprintf(best_path, sizeof(best_path), "%s", kFallbackPaths[i]);
                break;
            }
        }
    }

    snprintf(path_out, path_out_size, "%s", best_path);
    *base_out = best_base;
    return access(path_out, R_OK) == 0;
}

static uintptr_t find_elf_symbol_value_any(const char *path,
                                           const char *const *symbol_names,
                                           size_t symbol_count,
                                           const char **matched_symbol) {
    for (size_t i = 0; i < symbol_count; i++) {
        uintptr_t value = find_elf_symbol_value(path, symbol_names[i]);
        if (value != 0) {
            if (matched_symbol != NULL) {
                *matched_symbol = symbol_names[i];
            }
            return value;
        }
    }
    return 0;
}

static uintptr_t find_elf_symbol_value(const char *path, const char *symbol_name) {
    int fd = open(path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) {
        return 0;
    }

    struct stat st;
    if (fstat(fd, &st) != 0 || st.st_size <= 0) {
        close(fd);
        return 0;
    }

    size_t file_size = (size_t) st.st_size;
    void *mapping = mmap(NULL, file_size, PROT_READ, MAP_PRIVATE, fd, 0);
    close(fd);
    if (mapping == MAP_FAILED) {
        return 0;
    }

    uintptr_t result = 0;
    const Elf64_Ehdr *ehdr = (const Elf64_Ehdr *) mapping;
    if (!is_elf64_file(ehdr, file_size) ||
        !range_in_file(file_size, ehdr->e_shoff, (uint64_t) ehdr->e_shentsize * ehdr->e_shnum)) {
        munmap(mapping, file_size);
        return 0;
    }

    const Elf64_Shdr *shdrs = (const Elf64_Shdr *) ((const uint8_t *) mapping + ehdr->e_shoff);
    for (uint16_t i = 0; i < ehdr->e_shnum && result == 0; i++) {
        const Elf64_Shdr *symtab = &shdrs[i];
        if (symtab->sh_type != SHT_SYMTAB && symtab->sh_type != SHT_DYNSYM) {
            continue;
        }
        if (symtab->sh_entsize != sizeof(Elf64_Sym) || symtab->sh_link >= ehdr->e_shnum) {
            continue;
        }

        const Elf64_Shdr *strtab = &shdrs[symtab->sh_link];
        if (!range_in_file(file_size, symtab->sh_offset, symtab->sh_size) ||
            !range_in_file(file_size, strtab->sh_offset, strtab->sh_size)) {
            continue;
        }

        const Elf64_Sym *symbols = (const Elf64_Sym *) ((const uint8_t *) mapping + symtab->sh_offset);
        const char *strings = (const char *) mapping + strtab->sh_offset;
        size_t symbol_count = (size_t) (symtab->sh_size / symtab->sh_entsize);
        for (size_t j = 0; j < symbol_count; j++) {
            if (symbols[j].st_name >= strtab->sh_size) {
                continue;
            }
            const char *name = strings + symbols[j].st_name;
            if (strcmp(name, symbol_name) == 0) {
                result = (uintptr_t) symbols[j].st_value;
                break;
            }
        }
    }

    munmap(mapping, file_size);
    return result;
}

static int is_elf64_file(const Elf64_Ehdr *ehdr, size_t file_size) {
    if (file_size < sizeof(Elf64_Ehdr)) {
        return 0;
    }
    return ehdr->e_ident[EI_MAG0] == ELFMAG0 &&
           ehdr->e_ident[EI_MAG1] == ELFMAG1 &&
           ehdr->e_ident[EI_MAG2] == ELFMAG2 &&
           ehdr->e_ident[EI_MAG3] == ELFMAG3 &&
           ehdr->e_ident[EI_CLASS] == ELFCLASS64 &&
           ehdr->e_ident[EI_DATA] == ELFDATA2LSB &&
           ehdr->e_shoff != 0 &&
           ehdr->e_shnum != 0 &&
           ehdr->e_shentsize == sizeof(Elf64_Shdr);
}

static int range_in_file(size_t file_size, uint64_t offset, uint64_t size) {
    return offset <= file_size && size <= file_size - offset;
}

static int basename_equals(const char *path, const char *name) {
    const char *base = strrchr(path, '/');
    base = base == NULL ? path : base + 1;
    return strcmp(base, name) == 0;
}

static int find_module_by_phdr(struct dl_phdr_info *info, size_t size, void *data) {
    (void) size;
    ModulePhdrSearch *search = (ModulePhdrSearch *) data;
    if (info == NULL || search == NULL || info->dlpi_name == NULL) {
        return 0;
    }
    if (strstr(info->dlpi_name, search->soname_substring) == NULL) {
        return 0;
    }

    search->base = (uintptr_t) info->dlpi_addr;
    return 1;
}

static size_t collect_matching_callbacks(const char *soname,
                                         SoLoadCallback *callbacks,
                                         void **user_datas) {
    size_t count = 0;
    pthread_mutex_lock(&g_lock);
    for (size_t i = 0; i < SLM_MAX_WATCHERS && count < SLM_MAX_WATCHERS; i++) {
        if (g_watchers[i].callback != NULL &&
            strstr(soname, g_watchers[i].soname_substring) != NULL) {
            callbacks[count] = g_watchers[i].callback;
            user_datas[count] = g_watchers[i].user_data;
            count++;
        }
    }
    pthread_mutex_unlock(&g_lock);
    return count;
}

static int mark_soinfo_seen(void *soinfo) {
    if (soinfo == NULL) {
        return 0;
    }

    pthread_mutex_lock(&g_lock);
    for (size_t i = 0; i < g_seen_soinfo_count; i++) {
        if (g_seen_soinfos[i] == soinfo) {
            pthread_mutex_unlock(&g_lock);
            return 0;
        }
    }
    if (g_seen_soinfo_count < SLM_MAX_SEEN_SOINFOS) {
        g_seen_soinfos[g_seen_soinfo_count++] = soinfo;
    }
    pthread_mutex_unlock(&g_lock);
    return 1;
}

static void on_call_constructors(GumInvocationContext *ic, gpointer user_data) {
    (void) user_data;
    if (g_get_soname == NULL) {
        return;
    }

    void *soinfo = gum_invocation_context_get_nth_argument(ic, 0);
    const char *soname = NULL;
    if (soinfo != NULL) {
        soname = g_get_soname(soinfo);
    }
    if (soname == NULL || soname[0] == '\0') {
        return;
    }

    SoLoadCallback callbacks[SLM_MAX_WATCHERS];
    void *user_datas[SLM_MAX_WATCHERS];
    size_t callback_count = collect_matching_callbacks(soname, callbacks, user_datas);
    if (callback_count == 0 || !mark_soinfo_seen(soinfo)) {
        return;
    }

    SoLoadInfo info;
    info.soname = soname;
    info.soinfo = soinfo;
    info.base = so_load_monitor_find_module_base(soname);

    SLM_LOGI("matched loaded so soname=%s soinfo=%p base=%p callbacks=%zu",
             soname,
             soinfo,
             (void *) info.base,
             callback_count);
    for (size_t i = 0; i < callback_count; i++) {
        callbacks[i](&info, user_datas[i]);
    }
}
