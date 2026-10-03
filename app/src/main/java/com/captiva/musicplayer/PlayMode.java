package com.captiva.musicplayer;

/**
 * 播放模式
 */
public enum PlayMode {

    /** 顺序播放(播完列表后停止) */
    SEQUENCE(0, "顺序", "顺", R.drawable.ic_mode_seq),
    /** 单曲循环 */
    REPEAT_ONE(1, "单曲循环", "单", R.drawable.ic_mode_one),
    /** 随机播放 */
    SHUFFLE(2, "随机", "随", R.drawable.ic_mode_shuf);

    private final int value;
    private final String label;
    private final String shortLabel;
    /** 圆形按钮上的 Material 图标(2026-10-04 v5.7.366:文字"随/顺/单"升级为图标) */
    private final int iconRes;

    PlayMode(int value, String label, String shortLabel, int iconRes) {
        this.value = value;
        this.label = label;
        this.shortLabel = shortLabel;
        this.iconRes = iconRes;
    }

    public int getValue() {
        return value;
    }

    public String getLabel() {
        return label;
    }

    /** 圆形按钮用的单字标签 */
    public String getShortLabel() {
        return shortLabel;
    }

    /** 播放模式图标资源 */
    public int getIconRes() {
        return iconRes;
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
