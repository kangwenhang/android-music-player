package com.captiva.musicplayer;

/**
 * 专辑数据模型(Navidrome)
 */
public class AlbumBean {

    private String id;
    private String name;
    private String artist;
    private String coverArtId;
    private int songCount;
    private long duration; // 秒

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name == null ? "" : name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getArtist() {
        return artist == null || artist.isEmpty() ? "未知艺术家" : artist;
    }

    public void setArtist(String artist) {
        this.artist = artist;
    }

    public String getCoverArtId() {
        return coverArtId;
    }

    public void setCoverArtId(String coverArtId) {
        this.coverArtId = coverArtId;
    }

    public int getSongCount() {
        return songCount;
    }

    public void setSongCount(int songCount) {
        this.songCount = songCount;
    }

    public long getDuration() {
        return duration;
    }

    public void setDuration(long duration) {
        this.duration = duration;
    }
}
