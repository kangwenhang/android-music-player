package com.captiva.musicplayer;

import android.content.Context;
import android.os.Environment;
import android.util.Log;
import androidx.multidex.MultiDex;
import androidx.multidex.MultiDexApplication;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Application 入口
 * 启用 multidex,适配老系统方法数限制
 * 初始化封面磁盘缓存
 */
public class App extends MultiDexApplication {

    private static final String TAG = "App";

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
        // 全局崩溃捕获:车机不方便接 adb,任何未捕获异常先把完整堆栈落盘
        // (与 cache_debug.log 同目录,文件管理器直接可看),再交回系统默认处理。
        installCrashLogger();
        // 下载 / 联网播放诊断:只记失败与异常,正常播放不写一行,与 crash_log.txt 同目录
        DownloadDiag.init(this);
    }

    /**
     * 安装崩溃记录器。同步写盘(进程即将死亡,不能用异步队列):
     * - 路径优先 音乐根目录(NavidromeConfig.getSyncPath,与 cache_debug.log 一致),回退 /Music
     * - 文件上限 256KB,超过即重建,避免无限膨胀
     * - 记录失败不影响原有崩溃流程(永远交回 defaultHandler)
     */
    private void installCrashLogger() {
        final Thread.UncaughtExceptionHandler previous =
                Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread thread, Throwable throwable) {
                try {
                    String dir = null;
                    try {
                        dir = new NavidromeConfig(App.this).getSyncPath();
                    } catch (Throwable ignored) {}
                    if (dir == null || dir.isEmpty()) {
                        dir = Environment.getExternalStorageDirectory()
                                .getAbsolutePath() + "/Music";
                    }
                    File parent = new File(dir);
                    if (!parent.exists()) parent.mkdirs();
                    File f = new File(parent, "crash_log.txt");
                    if (f.exists() && f.length() > 256 * 1024L) {
                        f.delete();
                    }
                    StringWriter sw = new StringWriter();
                    throwable.printStackTrace(new PrintWriter(sw));
                    String ts = new SimpleDateFormat(
                            "MM-dd HH:mm:ss", Locale.getDefault()).format(new Date());
                    String record = "==== " + ts + "  thread=" + thread.getName()
                            + " ====\n" + sw.toString() + "\n";
                    OutputStreamWriter w = null;
                    try {
                        w = new OutputStreamWriter(
                                new FileOutputStream(f, true), "UTF-8");
                        w.write(record);
                        w.flush();
                    } finally {
                        if (w != null) {
                            try { w.close(); } catch (Exception ignored) {}
                        }
                    }
                    Log.e(TAG, "崩溃堆栈已写入: " + f.getAbsolutePath());
                } catch (Throwable t) {
                    // 落盘失败也绝不能吞掉原有崩溃流程
                    Log.e(TAG, "崩溃堆栈写盘失败", t);
                }
                if (previous != null) {
                    previous.uncaughtException(thread, throwable);
                }
            }
        });
    }

    @Override
    public void onTerminate() {
        super.onTerminate();
        // 释放 CoverLoader 线程池和数据库连接(车机低内存场景)
        CoverLoader.getInstance().release();
    }
}
