package com.captiva.musicplayer;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Navidrome 服务器配置管理
 * 使用 SharedPreferences 存储服务器地址、用户名、密码
 */
public class NavidromeConfig {

    private static final String PREFS_NAME = "navidrome_config";
    private static final String KEY_SERVER_URL = "server_url";
    private static final String KEY_USERNAME = "username";
    private static final String KEY_PASSWORD = "password";
    private static final String KEY_ENABLED = "enabled";

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

    /** 判断是否已配置完整的服务器信息 */
    public boolean isConfigured() {
        String url = getServerUrl();
        String user = getUsername();
        String pass = getPassword();
        return url != null && !url.isEmpty()
                && user != null && !user.isEmpty()
                && pass != null && !pass.isEmpty();
    }
}
