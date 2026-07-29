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
}
