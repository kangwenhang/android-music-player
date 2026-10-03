package com.captiva.musicplayer;

import android.content.BroadcastReceiver;
import android.content.ComponentCallbacks2;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
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
        // (统一诊断日志目录,与 download_debug.log / cache_debug.log 同目录,文件管理器直接可看),再交回系统默认处理。
        installCrashLogger();
        // 下载 / 联网播放诊断:只记失败与异常,正常播放不写一行,与 cache_debug.log / crash_log.txt 同目录
        DownloadDiag.init(this);
        // 黑屏取证:系统熄屏/亮屏/解锁 与 内存回收回调 都落到同一个日志里
        watchScreenState();
        // 黑屏取证的另一半:主线程卡顿(本项目历史黑屏根因就是它)
        MainThreadWatchdog.start();
    }

    /**
     * 黑屏取证:系统熄屏 / 亮屏 / 解锁各记一行到 download_debug.log。
     *
     * 为什么挂在 Application 而不是某个 Activity:
     * 黑屏可能发生在任何一个页面,甚至发生在"息屏之后"(广播就是那时候发出来的),
     * 挂在 Activity 上必然漏。挂在 Application 上,只要进程还活着就一定记得到。
     *
     * 怎么用这份日志判定根因(复现黑屏后照下面三条对号):
     * - 画面黑了 + 日志里有「[生命周期] onPause | 屏幕=灭」/「[屏幕] 系统广播 熄屏」
     *   → 是**系统把屏幕关了**(息屏超时/电源策略),不是 App 把界面画黑,更不是内存回收;
     * - 画面黑了 + 屏幕=亮 + 有「[主线程] 卡顿 Xms」→ 是 **App 自己把主线程卡住了**,
     *   窗口在卡的这段时间里画不出新帧(本项目历史两次黑屏都是这个原因);
     * - 画面黑了 + 屏幕=亮 + 没有卡顿行、堆也没暴涨 → 是**显示/合成层**没合成画面
     *   (雷电这类模拟器的宿主 GPU 重置、车机的 SurfaceFlinger),App 改不了,
     *   换设备或关掉模拟器硬件加速再验;
     * - 日志里出现第二次「==== download_debug.log 开始记录 ====」→ 进程被系统杀掉重启过,
     *   这才是真正的"内存回收"证据,配合上面 [内存] 那几行还能看出是哪个档位触发的。
     */
    private void watchScreenState() {
        try {
            IntentFilter filter = new IntentFilter();
            filter.addAction(Intent.ACTION_SCREEN_OFF);
            filter.addAction(Intent.ACTION_SCREEN_ON);
            filter.addAction(Intent.ACTION_USER_PRESENT);
            registerReceiver(new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    String action = intent == null ? "" : intent.getAction();
                    String what;
                    if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                        what = "系统广播 熄屏(屏幕被关)";
                    } else if (Intent.ACTION_SCREEN_ON.equals(action)) {
                        what = "系统广播 亮屏(屏幕被点亮)";
                    } else if (Intent.ACTION_USER_PRESENT.equals(action)) {
                        what = "系统广播 解锁";
                    } else {
                        what = "系统广播 " + action;
                    }
                    DownloadDiag.log("[屏幕] " + what + " | " + DownloadDiag.env());
                }
            }, filter);
        } catch (Throwable t) {
            // 注册失败不能影响启动(诊断信息,丢了就丢了)
            Log.w(TAG, "屏幕状态监听注册失败", t);
        }
    }

    /**
     * 系统要求释放内存时记一行。
     * 本项目**没有任何** onTrimMemory 处理逻辑(既不主动释放,也不 System.gc),
     * 这里只做取证:把"是不是系统在回收内存"这件事变成日志里看得见的数字。
     */
    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        DownloadDiag.log("[内存] 系统要求释放内存 level=" + level
                + "(" + trimName(level) + ") | " + DownloadDiag.env());
    }

    @Override
    public void onLowMemory() {
        super.onLowMemory();
        DownloadDiag.log("[内存] onLowMemory:系统内存告急 | " + DownloadDiag.env());
    }

    /** 把 trim level 数字翻译成人话 */
    private static String trimName(int level) {
        switch (level) {
            case ComponentCallbacks2.TRIM_MEMORY_COMPLETE:
                return "COMPLETE 后台最尾,即将被杀";
            case ComponentCallbacks2.TRIM_MEMORY_MODERATE:
                return "MODERATE 后台偏后";
            case ComponentCallbacks2.TRIM_MEMORY_BACKGROUND:
                return "BACKGROUND 刚进后台";
            case ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN:
                return "UI_HIDDEN 界面已不可见";
            case ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL:
                return "RUNNING_CRITICAL 前台内存告急";
            case ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW:
                return "RUNNING_LOW 前台内存偏低";
            case ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE:
                return "RUNNING_MODERATE 前台略紧张";
            default:
                return "未知档位";
        }
    }

    /**
     * 安装崩溃记录器。同步写盘(进程即将死亡,不能用异步队列):
     * - 路径优先 统一诊断日志目录(DownloadDiag.resolveLogDir),与 download_debug.log / cache_debug.log 同目录;
     *   未设置自定义目录时回退 音乐根目录(getSyncPath),再回退 /Music
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
                        dir = DownloadDiag.resolveLogDir(App.this);
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
