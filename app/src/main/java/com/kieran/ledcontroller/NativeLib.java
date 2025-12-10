package com.kieran.ledcontroller;

import android.util.Log;

/**
 * NativeLib 负责加载 native-lib.so 并暴露 Java 可调用的读写方法。
 */
public class NativeLib {
    private static final String TAG = "NativeLib";
    private static boolean loaded;

    static {
        try {
            System.loadLibrary("native-lib");
            loaded = true;
        } catch (UnsatisfiedLinkError e) {
            loaded = false;
            Log.e(TAG, "Failed to load native library", e);
        }
    }

    /**
     * 写入命令字符串，若库未加载则直接返回错误。
     */
    public int nativeWrite(String cmd) {
        if (!loaded) {
            Log.w(TAG, "nativeWrite skipped, library not loaded");
            return -1;
        }
        return nativeWriteImpl(cmd);
    }

    /**
     * 读取驱动给出的状态文本，未加载时返回占位值保证上层逻辑可继续运行。
     */
    public String nativeRead() {
        if (!loaded) {
            Log.w(TAG, "nativeRead fallback, library not loaded");
            return "unknown 0 0 0";
        }
        return nativeReadImpl();
    }

    private native int nativeWriteImpl(String cmd);

    private native String nativeReadImpl();
}
