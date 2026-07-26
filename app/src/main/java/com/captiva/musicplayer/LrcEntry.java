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
        return Long.compare(this.time, another.time);
    }
}