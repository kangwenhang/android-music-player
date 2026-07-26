package com.captiva.musicplayer;

/**
 * 音乐数据模型
 * 对应一条本地音频文件
 */
public class MusicBean {

    private long id;
    private String title;      // 歌曲名
    private String artist;     // 艺术家
    private String album;      // 专辑
    private long duration;     // 时长(毫秒)
    private String data;       // 文件路径
    private String uri;        // content uri 字符串

    public MusicBean() {
    }

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public String getTitle() {
        return title == null ? "" : title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getArtist() {
        return artist == null || artist.isEmpty() ? "未知艺术家" : artist;
    }

    public void setArtist(String artist) {
        this.artist = artist;
    }

    public String getAlbum() {
        return album == null || album.isEmpty() ? "未知专辑" : album;
    }

    public void setAlbum(String album) {
        this.album = album;
    }

    public long getDuration() {
        return duration;
    }

    public void setDuration(long duration) {
        this.duration = duration;
    }

    public String getData() {
        return data;
    }

    public void setData(String data) {
        this.data = data;
    }

    public String getUri() {
        return uri;
    }

    public void setUri(String uri) {
        this.uri = uri;
    }

    /**
     * 格式化时长 mm:ss
     */
    public static String formatDuration(long ms) {
        long total = ms / 1000;
        long m = total / 60;
        long s = total % 60;
        return String.format("%02d:%02d", m, s);
    }
}