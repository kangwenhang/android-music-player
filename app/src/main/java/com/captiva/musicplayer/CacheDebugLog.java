package com.captiva.musicplayer;

import android.content.Context;
import android.os.Environment;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 缓存诊断日志(正式版 / release 包也生效,与 PerfLogger 不同,不受 BuildConfig.DEBUG 门控)
 *
 * 用途:车机/手机上不方便接 adb 抓 logcat 时,把关键诊断信息直接落盘成文本文件,
 * 用任意文件管理器打开即可查看(如「点击播放没有出现缓存进度条」这类问题)。
 *
 * 日志文件路径: &lt;Music根目录&gt;/cache_debug.log
 * 写入策略:追加写;文件超过 {@link #MAX_BYTES} 时先删除再重建,避免无限增长。
 * 线程安全:静态锁串行化写入。
 * 失败策略:任何 IO 异常静默吞掉 —— 诊断日志绝不能影响正常播放。
 */
public class CacheDebugLog {

    private static final String TAG = "CacheDebugLog";
    private static final String FILE_NAME = "cache_debug.log";
    /** 单文件大小上限(超过即重建),避免长期运行把存储写满 */
    private static final long MAX_BYTES = 256 * 1024L;

    private static final Object LOCK = new Object();
    private static final SimpleDateFormat SDF =
            new SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault());

    /** 日志目录(由调用方 init 指定,通常为云端歌曲目录;为空时回退 外部存储/Music) */
    private static volatile String dirPath;
    /** 是否已写过文件头(含完整绝对路径),方便用户直接定位文件 */
    private static boolean headerLogged = false;

    private CacheDebugLog() {}

    /**
     * 初始化日志目录(建议在加载音乐 / 播放服务启动时调用)。
     * 与 perf_log.txt 同目录(云端歌曲目录),首次调用会写入一行含完整路径的文件头。
     */
    public static void init(Context context, String dir) {
        if (dir != null && !dir.isEmpty()) {
            dirPath = dir;
        }
        if (!headerLogged) {
            headerLogged = true;
            log("==== cache_debug.log 开始记录,文件路径: " + currentFilePath() + " ====");
        }
    }

    /** 当前日志文件绝对路径(供日志头与排查使用) */
    public static String currentFilePath() {
        String dir = dirPath;
        if (dir == null || dir.isEmpty()) {
            dir = Environment.getExternalStorageDirectory().getAbsolutePath() + "/Music";
        }
        return new File(dir, FILE_NAME).getAbsolutePath();
    }

    /** 追加一条诊断日志(同时输出到 logcat,便于接 adb 时对照) */
    public static void log(String msg) {
        if (msg == null) {
            return;
        }
        Log.i(TAG, msg);
        String dir = dirPath;
        if (dir == null || dir.isEmpty()) {
            dir = Environment.getExternalStorageDirectory().getAbsolutePath() + "/Music";
        }
        synchronized (LOCK) {
            OutputStreamWriter writer = null;
            try {
                File f = new File(dir, FILE_NAME);
                if (f.exists() && f.length() > MAX_BYTES) {
                    f.delete();
                }
                File parent = f.getParentFile();
                if (parent != null && !parent.exists()) {
                    parent.mkdirs();
                }
                writer = new OutputStreamWriter(new FileOutputStream(f, true), "UTF-8");
                writer.write(SDF.format(new Date()) + "  " + msg + "\n");
                writer.flush();
            } catch (Exception e) {
                // 静默:诊断日志写入失败不影响播放
            } finally {
                if (writer != null) {
                    try { writer.close(); } catch (Exception ignored) {}
                }
            }
        }
    }
}
