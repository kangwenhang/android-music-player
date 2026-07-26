package com.captiva.musicplayer;

import android.content.Context;
import androidx.multidex.MultiDex;
import androidx.multidex.MultiDexApplication;

/**
 * Application 入口
 * 启用 multidex,适配老系统方法数限制
 * 初始化封面磁盘缓存
 */
public class App extends MultiDexApplication {

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        MultiDex.install(this);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        // 初始化封面磁盘缓存
        CoverLoader.getInstance().initDiskCache(this);
    }
}