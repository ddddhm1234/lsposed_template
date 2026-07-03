#pragma once

#include <jni.h>

#ifdef __cplusplus
extern "C" {
#endif

void native_logger_init(JavaVM *vm);
void native_logger_set_java_logger(JNIEnv *env, jobject logger);
void native_logger_write(int priority,
                         const char *tag,
                         const char *file,
                         int line,
                         const char *fmt,
                         ...)
        __attribute__((format(printf, 5, 6)));

#define NATIVE_LOGD(tag, ...) native_logger_write(3, tag, __FILE__, __LINE__, __VA_ARGS__)
#define NATIVE_LOGI(tag, ...) native_logger_write(4, tag, __FILE__, __LINE__, __VA_ARGS__)
#define NATIVE_LOGW(tag, ...) native_logger_write(5, tag, __FILE__, __LINE__, __VA_ARGS__)
#define NATIVE_LOGE(tag, ...) native_logger_write(6, tag, __FILE__, __LINE__, __VA_ARGS__)

#ifdef __cplusplus
}
#endif
