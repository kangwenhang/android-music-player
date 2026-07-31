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
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * 性能日志工具
 *
 * 功能:
 * 1. Choreographer 帧率监控:检测掉帧(>16ms=1帧,>33ms=丢2帧)
 * 2. 主线程操作耗时打点:onBindViewHolder / 封面磁盘读取 / onDraw 等
 * 3. 日志写入U盘文件,方便导出分析
 * 4. 环形缓冲区,避免内存无限增长
 *
 * 日志文件路径: <syncPath>/perf_log.txt
 * 每次启动清空旧日志,重新记录
 *
 * 使用方式:
 *   PerfLogger.init(context, syncPath);
 *   PerfLogger.startFrameMonitor();   // 开始帧率监控
 *   PerfLogger.log("onBind", 15);     // 记录耗时操作
 *   PerfLogger.dump();                // 手动刷新到文件
 */
public class PerfLogger {

    private static final String TAG = "PerfLogger";
    private static final String LOG_FILE_NAME = "perf_log.txt";
    private static final int MAX_QUEUE_SIZE = 5000;  // 环形缓冲区上限

    private static volatile boolean enabled = false;
    private static File logFile;
    private static final ConcurrentLinkedQueue<String> logQueue = new ConcurrentLinkedQueue<>();
    private static final SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault());

    // 帧率监控
    private static long lastFrameTimeNanos = 0;
    private static int frameCount = 0;
    private static int droppedFrameCount = 0;
    private static long monitorStartTime = 0;
    private static final long FRAME_INTERVAL_16MS = 16_000_000L;  // 16ms in nanos
    private static final long FRAME_INTERVAL_33MS = 33_000_000L;  // 33ms (dropped 2 frames)

    // 滚动状态标记
    private static volatile boolean scrolling = false;

    private PerfLogger() {}

    /** 初始化日志文件(在U盘上) */
    public static void init(String syncPath) {
        if (syncPath == null || syncPath.isEmpty()) {
            Log.w(TAG, "syncPath 为空,性能日志不可用");
            return;
        }
        try {
            File dir = new File(syncPath);
            if (!dir.exists()) dir.mkdirs();
            logFile = new File(dir, LOG_FILE_NAME);
            enabled = true;
            log("=== PerfLogger 初始化,日志文件: " + logFile.getAbsolutePath() + " ===");
            log("设备信息: " + android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL
                    + " Android " + android.os.Build.VERSION.RELEASE
                    + " SDK=" + android.os.Build.VERSION.SDK_INT);
            log("CPU核心数: " + Runtime.getRuntime().availableProcessors()
                    + ", 最大内存: " + (Runtime.getRuntime().maxMemory() / 1024 / 1024) + "MB");
        } catch (Exception e) {
            Log.w(TAG, "init failed", e);
            enabled = false;
        }
    }

    /** 是否已启用 */
    public static boolean isEnabled() {
        return enabled;
    }

    /** 记录一条日志(带时间戳) */
    public static void log(String tag, String message) {
        if (!enabled) return;
        String time = sdf.format(new Date());
        String entry = time + " [" + tag + "] " + message;
        enqueue(entry);
    }

    /** 记录一条耗时日志 */
    public static void log(String tag, long elapsedMs) {
        if (!enabled) return;
        String time = sdf.format(new Date());
        // 标记是否超时(>16ms 会掉帧)
        String flag = elapsedMs > 16 ? " ⚠️" : "";
        String entry = time + " [" + tag + "] " + elapsedMs + "ms" + flag;
        enqueue(entry);
    }

    /** 记录一条普通日志(无耗时) */
    public static void log(String message) {
        if (!enabled) return;
        String time = sdf.format(new Date());
        String entry = time + " " + message;
        enqueue(entry);
    }

    private static void enqueue(String entry) {
        logQueue.add(entry);
        // 环形缓冲区:超过上限丢弃最旧的
        while (logQueue.size() > MAX_QUEUE_SIZE) {
            logQueue.poll();
        }
        // 每积累 50 条自动刷新一次
        if (logQueue.size() % 50 == 0) {
            flushToFile();
        }
    }

    /** 设置滚动状态(用于日志标注) */
    public static void setScrolling(boolean scrolling) {
        if (!enabled) return;
        if (PerfLogger.scrolling != scrolling) {
            PerfLogger.scrolling = scrolling;
            log(scrolling ? ">>> 列表开始滑动 <<<" : "<<< 列表停止滑动 >>>");
            if (scrolling) {
                monitorStartTime = System.currentTimeMillis();
                frameCount = 0;
                droppedFrameCount = 0;
            } else if (monitorStartTime > 0) {
                long duration = System.currentTimeMillis() - monitorStartTime;
                if (duration > 0 && frameCount > 0) {
                    float fps = frameCount * 1000f / duration;
                    log("滑动统计: 耗时" + duration + "ms, 渲染" + frameCount + "帧, 掉帧" + droppedFrameCount
                            + ", 实际FPS=" + String.format("%.1f", fps));
                }
            }
        }
    }

    /**
     * Choreographer 帧回调:每帧调用
     * 用于检测掉帧
     */
    public static void onFrame(long frameTimeNanos) {
        if (!enabled) return;
        frameCount++;

        if (lastFrameTimeNanos > 0) {
            long delta = frameTimeNanos - lastFrameTimeNanos;
            if (delta > FRAME_INTERVAL_33MS) {
                // 严重掉帧(>33ms,丢了至少2帧)
                int dropped = (int) (delta / FRAME_INTERVAL_16MS) - 1;
                droppedFrameCount += dropped;
                if (scrolling) {
                    log("掉帧", "delta=" + (delta / 1_000_000) + "ms, 丢" + dropped + "帧"
                            + (dropped >= 3 ? " 🔴" : ""));
                }
            }
        }
        lastFrameTimeNanos = frameTimeNanos;
    }

    /** 手动刷新日志到文件 */
    public static void flushToFile() {
        if (!enabled || logFile == null) return;
        OutputStreamWriter writer = null;
        try {
            writer = new OutputStreamWriter(new FileOutputStream(logFile, true), "UTF-8");
            String entry;
            while ((entry = logQueue.poll()) != null) {
                writer.write(entry);
                writer.write('\n');
            }
            writer.flush();
        } catch (Exception e) {
            Log.w(TAG, "flushToFile failed", e);
        } finally {
            if (writer != null) {
                try { writer.close(); } catch (Exception ignored) {}
            }
        }
    }

    /** dump 当前状态(定时调用) */
    public static void dump() {
        if (!enabled) return;
        flushToFile();
    }

    /** 应用退出时调用 */
    public static void shutdown() {
        if (!enabled) return;
        log("=== PerfLogger 关闭 ===");
        flushToFile();
        enabled = false;
    }
}
