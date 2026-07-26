package com.captiva.musicplayer;

/**
 * 播放模式
 */
public enum PlayMode {

    /** 顺序播放(播完列表后停止) */
    SEQUENCE(0, "顺序"),
    /** 单曲循环 */
    REPEAT_ONE(1, "单曲循环"),
    /** 随机播放 */
    SHUFFLE(2, "随机");

    private final int value;
    private final String label;

    PlayMode(int value, String label) {
        this.value = value;
        this.label = label;
    }

    public int getValue() {
        return value;
    }

    public String getLabel() {
        return label;
    }

    /** 切换到下一个模式 */
    public PlayMode next() {
        PlayMode[] all = values();
        return all[(this.ordinal() + 1) % all.length];
    }

    public static PlayMode fromValue(int v) {
        for (PlayMode m : values()) {
            if (m.value == v) {
                return m;
            }
        }
        return SEQUENCE;
    }
}