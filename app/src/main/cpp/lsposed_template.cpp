#include <jni.h>
#include <frida-gum.h>

__attribute__((constructor))
int main() {
    gum_init_embedded();


    return 0;
}