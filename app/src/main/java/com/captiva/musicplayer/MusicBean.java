package com.captiva.musicplayer;

/**
 * 音乐数据模型
 * 对应一条本地音频文件或 Navidrome 网络歌曲
 */
public class MusicBean {

    private long id;
    private String title;      // 歌曲名
    private String artist;     // 艺术家
    private String album;      // 专辑
    private long duration;     // 时长(毫秒)
    private String data;       // 文件路径(本地)
    private String uri;        // content uri 字符串(本地)

    // 网络播放(Navidrome)相关字段
    private boolean network;   // 是否为网络歌曲
    private String coverArtId; // Navidrome 封面 art ID(getCoverArt 用)
    private String streamId;   // Navidrome 歌曲 ID(stream 用)
    private String streamUrl;  // 完整流式播放 URL(可选,预先生成)
    private String localSuffix; // 音频文件后缀(如 mp3, flac),用于下载
    private int bitRate;        // 比特率(kbps),用于下载时选择质量

    /** 缓存的 song key,避免重复调用 getCanonicalPath()(文件系统 I/O) */
    private String cachedKey;

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

    public boolean isNetwork() {
        return network;
    }

    public void setNetwork(boolean network) {
        this.network = network;
    }

    public String getCoverArtId() {
        return coverArtId;
    }

    public void setCoverArtId(String coverArtId) {
        this.coverArtId = coverArtId;
    }

    public String getStreamId() {
        return streamId;
    }

    public void setStreamId(String streamId) {
        this.streamId = streamId;
    }

    public String getStreamUrl() {
        return streamUrl;
    }

    public void setStreamUrl(String streamUrl) {
        this.streamUrl = streamUrl;
    }

    public String getLocalSuffix() {
        return localSuffix != null && !localSuffix.isEmpty() ? localSuffix : "mp3";
    }

    public void setLocalSuffix(String localSuffix) {
        this.localSuffix = localSuffix;
    }

    public int getBitRate() {
        return bitRate;
    }

    public void setBitRate(int bitRate) {
        this.bitRate = bitRate;
    }

    /**
     * 格式化时长 mm:ss
     * 手动拼接避免 String.format 创建 Formatter 对象(减少 GC)
     */
    public static String formatDuration(long ms) {
        long total = ms / 1000;
        long m = total / 60;
        long s = total % 60;
        // 手动格式化,避免 String.format 的 Formatter 分配
        StringBuilder sb = new StringBuilder(5);
        if (m < 10) sb.append('0');
        sb.append(m).append(':');
        if (s < 10) sb.append('0');
        sb.append(s);
        return sb.toString();
    }

    /**
     * 获取缓存的 song key(懒加载,线程安全)
     *
     * key 规则(与各处 getSongKey 一致):
     * - 网络歌曲: net_{streamId}
     * - 本地歌曲: local_{normalizePath(data)} 或 local_{id}
     *
     * 关键优化:normalizePath() 内部调用 getCanonicalPath() 是文件系统 I/O,
     * 在车机上每次约 0.3ms。810 首歌遍历一次 = 243ms 主线程卡顿。
     * 缓存后只需计算一次,后续调用为纯内存操作。
     */
    public synchronized String getCachedKey() {
        if (cachedKey == null) {
            if (network) {
                cachedKey = "net_" + streamId;
            } else {
                if (data != null && !data.isEmpty()) {
                    cachedKey = "local_" + MusicScanner.normalizePath(data);
                } else {
                    cachedKey = "local_" + id;
                }
            }
        }
        return cachedKey;
    }
}
