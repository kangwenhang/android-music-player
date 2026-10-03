package com.captiva.musicplayer;

/**
 * 单行歌词
 */
public class LrcEntry implements Comparable<LrcEntry> {

    private final long time;   // 毫秒
    private final String text;

    public LrcEntry(long time, String text) {
        this.time = time;
        this.text = text;
    }

    public long getTime() {
        return time;
    }

    public String getText() {
        return text;
    }

    @Override
    public int compareTo(LrcEntry another) {
        // 不要用 Long.compare:API 19 才有,车机(API 17)上会 NoSuchMethodError,
        // 歌词解析线程会静默死亡(表现为歌词不显示且无任何报错)
        return this.time < another.time ? -1 : (this.time == another.time ? 0 : 1);
    }
}