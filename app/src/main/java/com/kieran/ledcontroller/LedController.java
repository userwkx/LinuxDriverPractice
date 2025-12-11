package com.kieran.ledcontroller;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.Locale;

/**
 * LedController 是 Java 层对原生驱动的包装，负责调度异步读写并提供状态解析。
 */
public class LedController {

    public interface StateCallback {
        void onState(LedState state);
    }

    private static final long POLL_INTERVAL_MS = 100L;

    // JNI 桥接对象，真正与 /dev/led_ctrl 交互。
    private final NativeLib nativeLib = new NativeLib();
    // 单线程调度器，确保所有读写在后台串行执行，避免阻塞 UI。
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private ScheduledFuture<?> monitorFuture;
    // 保存最近一次读取到的原始字符串方便 UI 直接展示。
    private final AtomicReference<String> lastRawState = new AtomicReference<>("");

    public void turnOn() {
        writeCommand("1");
    }

    public void turnOff() {
        writeCommand("0");
    }

    public void blink() {
        writeCommand("3");
    }

    public void breath() {
        writeCommand("4");
    }

    /**
     * 读取驱动返回的原始文本并解析为 LedState 对象。
     */
    public LedState readState() {
        String raw = nativeLib.nativeRead();
        String safeRaw = raw == null ? "" : raw;
        lastRawState.set(safeRaw);
        return parseState(safeRaw);
    }

    /**
     * 以固定间隔轮询驱动状态，将结果通过回调传给调用方。
     */
    public void startStateMonitor(StateCallback callback) {
        if (monitorFuture != null && !monitorFuture.isCancelled()) {
            return;
        }
        monitorFuture = scheduler.scheduleWithFixedDelay(() -> {
            LedState state = readState();
            if (callback != null) {
                callback.onState(state);
            }
        }, 0, POLL_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    public void stopStateMonitor() {
        if (monitorFuture != null) {
            monitorFuture.cancel(true);
            monitorFuture = null;
        }
    }

    public String getLastRawState() {
        return lastRawState.get();
    }

    /**
     * 生命周期结束时释放调度器，防止线程泄露。
     */
    public void release() {
        stopStateMonitor();
        scheduler.shutdownNow();
    }

    // 将写操作丢到后台线程，保证 native write 不会卡顿主线程。
    private void writeCommand(String cmd) {
        scheduler.execute(() -> nativeLib.nativeWrite(cmd));
    }

    /**
     * 将形如 "on 255 500 500" 的字符串拆解成结构化数据。
     */
    public static LedState parseState(String raw) {
        if (raw == null) {
            return new LedState("unknown", 0, 0, 0);
        }
        String[] parts = raw.trim().split("\\s+");
        String mode = parts.length > 0 ? parts[0].toLowerCase(Locale.US) : "unknown";
        int level = safeParse(parts, 1);
        int delayOn = safeParse(parts, 2);
        int delayOff = safeParse(parts, 3);
        return new LedState(mode, level, delayOn, delayOff);
    }

    /**
     * 安全解析整型，出现缺失或异常时返回 0，避免影响 UI。
     */
    private static int safeParse(String[] parts, int index) {
        if (parts.length <= index) {
            return 0;
        }
        try {
            return Integer.parseInt(parts[index]);
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }
}
