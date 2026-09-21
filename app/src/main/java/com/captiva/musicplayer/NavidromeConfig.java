package com.captiva.musicplayer;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 应用配置管理
 * 使用 SharedPreferences 存储服务器地址、用户名、密码、时长过滤等设置
 */
public class NavidromeConfig {

    private static final String PREFS_NAME = "navidrome_config";
    private static final String KEY_SERVER_URL = "server_url";
    private static final String KEY_USERNAME = "username";
    private static final String KEY_PASSWORD = "password";
    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_MIN_DURATION = "min_duration"; // 最小时长(秒)
    private static final String KEY_SCAN_PATH = "scan_path"; // 自定义扫描目录
    private static final String KEY_SYNC_PATH = "sync_path"; // 网络音乐同步下载目录
    private static final String KEY_AUTO_PLAY = "auto_play"; // 打开软件自动播放
    private static final String KEY_LAST_INDEX = "last_play_index"; // 上次播放索引
    private static final String KEY_LAST_POSITION = "last_play_position"; // 上次播放进度(ms)
    private static final String KEY_PLAY_MODE = "play_mode"; // 播放模式(0=顺序,1=单曲循环,2=随机)
    private static final String KEY_SERVER_TYPE = "server_type"; // 服务器类型:navidrome / fnmusic
    private static final String KEY_LOCAL_SCAN_PATH = "local_scan_path"; // 本地模式自定义扫描目录
    private static final String KEY_LOCAL_MODE = "local_mode"; // 列表模式:true=本地列表,false=云端列表
    private static final String KEY_FNID = "fn_id";           // 原始 FN ID(用户填的,可为空)
    private static final String KEY_FN_RELAY = "fn_relay";    // 上次解析出的地址是否走飞牛中继
    private static final String KEY_AUTO_CACHE_ON_PLAY = "auto_cache_on_play"; // 播放时自动缓存
    private static final String KEY_AUTO_CACHE_MAX_MB = "auto_cache_max_mb"; // 自动缓存上限(MB)
    private static final int DEFAULT_MIN_DURATION = 30; // 默认30秒

    private final SharedPreferences prefs;

    public NavidromeConfig(Context context) {
        prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public String getServerUrl() {
        String url = prefs.getString(KEY_SERVER_URL, "");
        // 去掉末尾斜杠
        if (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        return url;
    }

    public void setServerUrl(String url) {
        prefs.edit().putString(KEY_SERVER_URL, url).apply();
    }

    public String getUsername() {
        return prefs.getString(KEY_USERNAME, "");
    }

    public void setUsername(String username) {
        prefs.edit().putString(KEY_USERNAME, username).apply();
    }

    public String getPassword() {
        return prefs.getString(KEY_PASSWORD, "");
    }

    public void setPassword(String password) {
        prefs.edit().putString(KEY_PASSWORD, password).apply();
    }

    public boolean isEnabled() {
        return prefs.getBoolean(KEY_ENABLED, false);
    }

    public void setEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply();
    }

    /**
     * 获取服务器类型
     * @return {@link MusicSourceFactory#TYPE_NAVIDROME} 或 {@link MusicSourceFactory#TYPE_FNMUSIC}
     */
    public String getServerType() {
        return prefs.getString(KEY_SERVER_TYPE, MusicSourceFactory.TYPE_NAVIDROME);
    }

    /** 设置服务器类型 */
    public void setServerType(String type) {
        prefs.edit().putString(KEY_SERVER_TYPE, type).apply();
    }

    /**
     * 原始 FN ID(仅飞牛、且用户填的是 FN ID 时才有值)。
     * 保存它是为了以后网络环境变化时可重新解析;实际连接用 getServerUrl() 里的已解析地址。
     */
    public String getFnId() {
        return prefs.getString(KEY_FNID, "");
    }

    /** 保存原始 FN ID;传 null 表示清空 */
    public void setFnId(String fnId) {
        prefs.edit().putString(KEY_FNID, fnId == null ? "" : fnId.trim()).apply();
    }

    /** 上次解析出的地址是否走飞牛中继(true = 远程中继访问) */
    public boolean isFnRelay() {
        return prefs.getBoolean(KEY_FN_RELAY, false);
    }

    /** 设置是否走飞牛中继 */
    public void setFnRelay(boolean relay) {
        prefs.edit().putBoolean(KEY_FN_RELAY, relay).apply();
    }

    /** 按当前配置创建数据源实例(飞牛会带上中继标志) */
    public MusicSourceApi createSource() {
        String type = getServerType();
        if (MusicSourceFactory.TYPE_FNMUSIC.equals(type)) {
            return MusicSourceFactory.createFn(getServerUrl(), getUsername(), getPassword(), isFnRelay());
        }
        return MusicSourceFactory.create(type, getServerUrl(), getUsername(), getPassword());
    }

    /** 获取最小时长过滤(秒),低于此时长的音频不显示 */
    public int getMinDuration() {
        return prefs.getInt(KEY_MIN_DURATION, DEFAULT_MIN_DURATION);
    }

    /** 设置最小时长过滤(秒) */
    public void setMinDuration(int seconds) {
        prefs.edit().putInt(KEY_MIN_DURATION, seconds).apply();
    }

    /** 获取自定义扫描目录(空表示扫描全部) */
    public String getScanPath() {
        return prefs.getString(KEY_SCAN_PATH, "");
    }

    /** 设置自定义扫描目录 */
    public void setScanPath(String path) {
        prefs.edit().putString(KEY_SCAN_PATH, path != null ? path.trim() : "").apply();
    }

    /**
     * 获取网络音乐同步下载目录
     * 网络模式会把服务器所有音乐下载到此目录,然后从本地播放
     * 空表示使用默认路径(外部存储/CaptivaMusic)
     */
    public String getSyncPath() {
        String path = prefs.getString(KEY_SYNC_PATH, "");
        if (path == null || path.isEmpty()) {
            // 默认路径
            path = android.os.Environment.getExternalStorageDirectory()
                    .getAbsolutePath() + "/CaptivaMusic";
        }
        return path;
    }

    /** 设置网络音乐同步下载目录 */
    public void setSyncPath(String path) {
        prefs.edit().putString(KEY_SYNC_PATH, path != null ? path.trim() : "").apply();
    }

    /**
     * 本地模式扫描目录(本地列表的数据来源,与云端/同步目录相互独立)。
     * 未设置时回退为同步目录(向后兼容:老用户本地列表 = 同步目录)。
     */
    public String getLocalScanPath() {
        String path = prefs.getString(KEY_LOCAL_SCAN_PATH, "");
        if (path == null || path.isEmpty()) {
            return getSyncPath();
        }
        return path;
    }

    /** 设置本地模式扫描目录;传空串表示回退为同步目录 */
    public void setLocalScanPath(String path) {
        prefs.edit().putString(KEY_LOCAL_SCAN_PATH, path != null ? path.trim() : "").apply();
    }

    /** 列表模式:true=本地列表(扫描本地目录),false=云端列表(云端歌单,默认) */
    public boolean isLocalMode() {
        return prefs.getBoolean(KEY_LOCAL_MODE, false);
    }

    /** 设置列表模式(持久化,下次启动保持) */
    public void setLocalMode(boolean local) {
        prefs.edit().putBoolean(KEY_LOCAL_MODE, local).apply();
    }

    /** 播放云端歌曲时是否自动下载缓存到本地(默认关,避免车机存储与流量失控) */
    public boolean isAutoCacheOnPlay() {
        return prefs.getBoolean(KEY_AUTO_CACHE_ON_PLAY, false);
    }

    /** 设置是否开启播放时自动缓存 */
    public void setAutoCacheOnPlay(boolean on) {
        prefs.edit().putBoolean(KEY_AUTO_CACHE_ON_PLAY, on).apply();
    }

    /** 自动缓存上限(MB);0=不限。默认 2048MB(2GB) */
    public int getAutoCacheMaxMb() {
        return prefs.getInt(KEY_AUTO_CACHE_MAX_MB, 2048);
    }

    /** 设置自动缓存上限(MB),负数按 0(不限)处理 */
    public void setAutoCacheMaxMb(int mb) {
        prefs.edit().putInt(KEY_AUTO_CACHE_MAX_MB, mb < 0 ? 0 : mb).apply();
    }

    /** 判断是否已配置完整的服务器信息 */
    public boolean isConfigured() {
        String url = getServerUrl();
        String user = getUsername();
        String pass = getPassword();
        return url != null && !url.isEmpty()
                && user != null && !user.isEmpty()
                && pass != null && !pass.isEmpty();
    }

    /** 打开软件是否自动播放音乐(默认关闭) */
    public boolean isAutoPlay() {
        return prefs.getBoolean(KEY_AUTO_PLAY, false);
    }

    /** 设置打开软件自动播放 */
    public void setAutoPlay(boolean autoPlay) {
        prefs.edit().putBoolean(KEY_AUTO_PLAY, autoPlay).apply();
    }

    /** 获取上次播放的歌曲索引 */
    public int getLastPlayIndex() {
        return prefs.getInt(KEY_LAST_INDEX, 0);
    }

    /** 保存上次播放的歌曲索引 */
    public void setLastPlayIndex(int index) {
        prefs.edit().putInt(KEY_LAST_INDEX, index).apply();
    }

    /** 获取上次播放进度(ms) */
    public int getLastPlayPosition() {
        return prefs.getInt(KEY_LAST_POSITION, 0);
    }

    /** 保存上次播放进度(ms) */
    public void setLastPlayPosition(int position) {
        prefs.edit().putInt(KEY_LAST_POSITION, position).apply();
    }

    /** 获取播放模式(0=顺序,1=单曲循环,2=随机) */
    public int getPlayMode() {
        return prefs.getInt(KEY_PLAY_MODE, 0);
    }

    /** 保存播放模式 */
    public void setPlayMode(int mode) {
        prefs.edit().putInt(KEY_PLAY_MODE, mode).apply();
    }
}
