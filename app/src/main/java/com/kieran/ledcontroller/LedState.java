package com.kieran.ledcontroller;

/**
 * LedState 封装驱动里一次状态快照，包括模式、电平以及闪烁节奏。
 */
public class LedState {
    private final String mode; // 运行模式：on/off/blink/unknown
    private final int level;
    private final int delayOn;
    private final int delayOff;

    public LedState(String mode, int level, int delayOn, int delayOff) {
        this.mode = mode;
        this.level = level;
        this.delayOn = delayOn;
        this.delayOff = delayOff;
    }

    public String getMode() {
        return mode;
    }

    public int getLevel() {
        return level;
    }

    public int getDelayOn() {
        return delayOn;
    }

    public int getDelayOff() {
        return delayOff;
    }
}
