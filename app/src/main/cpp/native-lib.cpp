#include <jni.h>
#include <string>
#include <cstring>
#include <fcntl.h>
#include <unistd.h>
#include <errno.h>
#include <android/log.h>

namespace {
    // 内核字符设备路径，由驱动暴露。
    constexpr const char* DEVICE_PATH = "/dev/led_ctrl";
    constexpr size_t BUFFER_SIZE = 256;
    int led_fd = -1;

    // 确保打开设备文件，失败时返回负值供调用方处理。
    int ensure_fd() {
        if (led_fd >= 0) {
            return led_fd;
        }
        led_fd = open(DEVICE_PATH, O_RDWR | O_CLOEXEC);
        return led_fd;
    }

    // 当驱动不可用时返回的兜底状态，保持协议一致。
    std::string fallback_state() {
        return "unknown 0 0 0";
    }
}

extern "C"
JNIEXPORT jint JNICALL
// 将 Java 传入的命令写入驱动，返回写入的字节数或错误码。
Java_com_kieran_ledcontroller_NativeLib_nativeWriteImpl(JNIEnv* env, jobject /*thiz*/, jstring cmd) {
    const char* command_chars = env->GetStringUTFChars(cmd, nullptr);
    if (command_chars == nullptr) {
        return -EINVAL;
    }

    int fd = ensure_fd();
    if (fd < 0) {
        __android_log_print(ANDROID_LOG_ERROR, "LedController", "Failed to open %s: %s", DEVICE_PATH, strerror(errno));
        env->ReleaseStringUTFChars(cmd, command_chars);
        return -errno;
    }

    ssize_t written = write(fd, command_chars, strlen(command_chars));
    if (written < 0) {
        __android_log_print(ANDROID_LOG_ERROR, "LedController", "Write failed: %s", strerror(errno));
    }

    env->ReleaseStringUTFChars(cmd, command_chars);
    return static_cast<jint>(written);
}



extern "C"
JNIEXPORT jstring JNICALL
// 从驱动读取状态文本，失败时返回 fallback_state。
Java_com_kieran_ledcontroller_NativeLib_nativeReadImpl(JNIEnv* env, jobject /*thiz*/) {
    int fd = ensure_fd();
    if (fd < 0) {
        __android_log_print(ANDROID_LOG_ERROR, "LedController", "Failed to open %s: %s", DEVICE_PATH, strerror(errno));
        return env->NewStringUTF(fallback_state().c_str());
    }

    if (lseek(fd, 0, SEEK_SET) < 0) {
        __android_log_print(ANDROID_LOG_WARN, "LedController", "Seek on %s failed: %s, reopening", DEVICE_PATH, strerror(errno));
        close(fd);
        led_fd = -1;
        fd = ensure_fd();
        if (fd < 0) {
            __android_log_print(ANDROID_LOG_ERROR, "LedController", "Reopen after seek failure failed: %s", DEVICE_PATH, strerror(errno));
            return env->NewStringUTF(fallback_state().c_str());
        }
    }

    char buffer[BUFFER_SIZE] = {0};
    ssize_t bytes_read = read(fd, buffer, BUFFER_SIZE - 1);
    if (bytes_read <= 0) {
        __android_log_print(ANDROID_LOG_ERROR, "LedController", "Read failed (%zd): %s", bytes_read, strerror(errno));
        return env->NewStringUTF(fallback_state().c_str());
    }

    buffer[bytes_read] = '\0';
    return env->NewStringUTF(buffer);
}
