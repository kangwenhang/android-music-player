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
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 性能日志工具
 *
 * 功能:
 * 1. Choreographer 帧率监控(加严):单帧 >20ms 记一次卡顿(>=33ms 标🔴);滑动平均 FPS<55 告警
 * 2. 主线程操作耗时打点:onBindViewHolder / 封面磁盘读取 / onDraw 等
 * 3. 日志写入U盘文件,方便导出分析
 * 4. 环形缓冲区,避免内存无限增长
 *
 * 日志文件路径: <syncPath>/perf_log.txt(同步目录为空时回退到 App 私有 perf/ 目录)
 * 每次启动清空旧日志,重新记录。
 * 仅调试版(BuildConfig.DEBUG=true)启用;正式版不调用 init,perf 日志完全关闭。
 *
 * 【写盘策略 —— 全异步】
 * 早期实现里 enqueue() 会直接调 flushToFile(),也就是"谁写日志谁写盘":
 * dump() 由主线程的定时任务调用,onFrame() 的掉帧日志也在主线程,
 * 于是每攒 50 条就在主线程上做一次完整的 open→write→flush→close。
 * 滑动列表时帧率日志累积很快,这个"第 50 条"的边界落在哪儿完全不可控,
 * 可能在用户点击的瞬间正好触发一次主线程写盘 → 可见卡顿。
 * 现在改为:enqueue()/dump() 只发出刷新信号,由独立守护线程批量落盘。
 * 需要"立刻落盘"的场合(应用退出)仍可调 flushToFile() 同步写。
 *
 * 使用方式(在 MainActivity.loadMusic 中,已按 BuildConfig.DEBUG 判断后调用):
 *   PerfLogger.init(context, syncPath);  // 初始化并开始写日志
 *   PerfLogger.log("onBind", 15);        // 记录耗时操作
 *   PerfLogger.dump();                   // 请求刷新到文件(异步,另有 50 条自动请求)
 */
public class PerfLogger {

    private static final String TAG = "PerfLogger";
    /**
     * 总开关:false = 完全关闭(不 init、不写 perf_log.txt、不建写线程)。
     *
     * 原本由调用方按 BuildConfig.DEBUG 决定是否 init,正式版(assembleRelease)天然不写;
     * 但调试包仍会生成 perf_log.txt。正式发布统一关闭:init() 直接返回,enabled 保持 false,
     * 于是所有 log()/onFrame()/dump() 全部空转,连 logcat 都不输出。
     * 需要重新做卡顿分析时把这里改回 true 重新构建即可。
     */
    public static final boolean ENABLED = false;

    private static final String LOG_FILE_NAME = "perf_log.txt";
    private static final int MAX_QUEUE_SIZE = 5000;  // 环形缓冲区上限
    /** 每积累多少条请求一次异步落盘 */
    private static final int FLUSH_EVERY = 50;

    private static volatile boolean enabled = false;
    private static File logFile;
    private static final ConcurrentLinkedQueue<String> logQueue = new ConcurrentLinkedQueue<>();

    /**
     * 队列长度的近似计数。
     * 之所以需要它:ConcurrentLinkedQueue.size() 是 O(n) 的,而原实现每次 enqueue
     * 都要调两次,等于每写一条日志就遍历一遍整个队列(可能上千个节点),且都在主线程上。
     * 这里用原子计数替代,只在极少数超过上限时才做一次精确核对。
     */
    private static final AtomicInteger logSize = new AtomicInteger(0);
    /** 距上次请求落盘的条数(达到 FLUSH_EVERY 就发一次刷新信号) */
    private static final AtomicInteger sinceFlush = new AtomicInteger(0);

    /** 落盘串行化:写线程与 shutdown() 的同步落盘不能同时写同一个文件 */
    private static final Object WRITE_LOCK = new Object();
    /** 刷新信号量:release=请求落盘,写线程 acquire 后被唤醒 */
    private static final Semaphore FLUSH_SIGNAL = new Semaphore(0);
    private static volatile Thread writerThread;

    /**
     * 时间戳格式化。SimpleDateFormat 非线程安全,而 log() 会被主线程、
     * 封面加载线程(磁盘读取打点)、下载线程等并发调用,必须用 ThreadLocal 隔离。
     */
    private static final ThreadLocal<SimpleDateFormat> SDF_HOLDER =
            new ThreadLocal<SimpleDateFormat>() {
                @Override
                protected SimpleDateFormat initialValue() {
                    return new SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault());
                }
            };

    // 帧率监控
    private static long lastFrameTimeNanos = 0;
    private static int frameCount = 0;
    private static int droppedFrameCount = 0;
    private static long monitorStartTime = 0;
    // 帧率监控:调试版"加严"判定阈值(电脑性能好,需更敏感才能暴露车机上的真实卡顿)
    private static final long FRAME_INTERVAL_16MS = 16_000_000L;  // 16ms in nanos(单帧预算,60fps)
    private static final long FRAME_INTERVAL_33MS = 33_000_000L;  // 33ms = 严重卡顿边界(>=即🔴)
    private static final long FRAME_HITCH_MS = 20_000_000L;      // 加严:单帧 >20ms 即记为一次卡顿
    private static final int OP_WARN_MS = 8;                     // 主线程操作 >8ms 标 ⚠️
    private static final int OP_SEVERE_MS = 16;                  // 主线程操作 >16ms 标 🔴
    private static final float FPS_FLOOR = 55f;                  // 滑动平均 FPS 低于此值告警(PC 应≈60)

    // 滚动状态标记
    private static volatile boolean scrolling = false;

    private PerfLogger() {}

    /**
     * 初始化日志文件。
     *
     * 仅调试版(BuildConfig.DEBUG=true)由 MainActivity 调用;正式版不调用,perf 日志完全关闭。
     * 同步目录为空时,回退到 App 私有外部存储下的 perf 子目录,
     * 保证没有 U 盘时也能记录卡顿信息用于分析。
     *
     * @param context  用于获取兜底存储目录
     * @param syncPath 首选日志目录(同步/U盘目录),可为空
     */
    public static void init(Context context, String syncPath) {
        if (!ENABLED) {
            enabled = false;   // 关闭:不建文件、不建写线程
            return;
        }
        if (context == null) {
            Log.w(TAG, "context 为空,性能日志不可用");
            return;
        }
        // 同步打印到 logcat,便于 'adb logcat -s PerfLogger' 直接看到初始化结果(无需先找文件)
        Log.i(TAG, "init() 调用, syncPath=" + syncPath);
        File dir;
        if (syncPath != null && !syncPath.isEmpty()) {
            dir = new File(syncPath);
        } else {
            // 兜底:同步目录为空时写入 App 私有外部存储,确保无 U 盘也能分析卡顿
            File base = context.getExternalFilesDir(null);
            if (base == null) base = context.getFilesDir();
            dir = new File(base, "perf");
        }
        try {
            if (!dir.exists()) dir.mkdirs();
            logFile = new File(dir, LOG_FILE_NAME);
            // 每次启动清空旧日志(环形缓冲 + 自动刷新,单文件足够定位卡顿)
            if (logFile.exists() && !logFile.delete()) {
                Log.w(TAG, "无法删除旧日志文件,将追加写入");
            }
            enabled = true;
            Log.i(TAG, "init 成功, 日志文件: " + (logFile != null ? logFile.getAbsolutePath() : "null"));
            log("=== PerfLogger 初始化(调试版),日志文件: " + logFile.getAbsolutePath() + " ===");
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
        String time = SDF_HOLDER.get().format(new Date());
        String entry = time + " [" + tag + "] " + message;
        enqueue(entry);
    }

    /** 记录一条耗时日志 */
    public static void log(String tag, long elapsedMs) {
        if (!enabled) return;
        String time = SDF_HOLDER.get().format(new Date());
        // 加严:>8ms 标 ⚠️,>16ms 标 🔴(车机上这些会被放大成明显卡顿)
        String flag = elapsedMs > OP_SEVERE_MS ? " 🔴"
                : elapsedMs > OP_WARN_MS ? " ⚠️" : "";
        String entry = time + " [" + tag + "] " + elapsedMs + "ms" + flag;
        enqueue(entry);
        // 同步镜像到 logcat:严重(🔴)>16ms 用 Log.e,告警(⚠️)>8ms 用 Log.w,
        // 这样即使文件没找到,也能在 'adb logcat -s PerfLogger' 实时看到关键耗时
        if (elapsedMs > OP_SEVERE_MS) {
            Log.e(TAG, "[" + tag + "] " + elapsedMs + "ms 🔴");
        } else if (elapsedMs > OP_WARN_MS) {
            Log.w(TAG, "[" + tag + "] " + elapsedMs + "ms ⚠️");
        }
    }

    /** 记录一条普通日志(无耗时) */
    public static void log(String message) {
        if (!enabled) return;
        String time = SDF_HOLDER.get().format(new Date());
        String entry = time + " " + message;
        enqueue(entry);
    }

    /**
     * 入队。只做内存操作 —— 不写盘、不阻塞调用线程。
     * 达到 FLUSH_EVERY 条时发出一次异步刷新信号(由写线程落盘)。
     */
    private static void enqueue(String entry) {
        logQueue.add(entry);
        int size = logSize.incrementAndGet();
        // 环形缓冲区:超过上限丢弃最旧的。
        // 注意不要在这里无脑调 logQueue.size()(O(n));用近似计数判断,只有确实超限时才精确核对一次
        if (size > MAX_QUEUE_SIZE) {
            int real = logQueue.size();
            while (real > MAX_QUEUE_SIZE && logQueue.poll() != null) {
                real--;
            }
            logSize.set(real);
        }
        // 每积累 FLUSH_EVERY 条请求一次落盘(异步,调用线程立刻返回)
        if (sinceFlush.incrementAndGet() >= FLUSH_EVERY) {
            sinceFlush.set(0);
            requestFlush();
        }
    }

    /** 发出一次异步落盘请求(非阻塞,可从主线程安全调用) */
    private static void requestFlush() {
        ensureWriter();
        FLUSH_SIGNAL.release();
    }

    /** 懒启动写线程(守护线程,不阻止进程退出) */
    private static void ensureWriter() {
        if (writerThread != null) {
            return;
        }
        synchronized (PerfLogger.class) {
            if (writerThread != null) {
                return;
            }
            Thread t = new Thread(new Runnable() {
                @Override
                public void run() {
                    writeLoop();
                }
            }, "perf-log-writer");
            t.setDaemon(true);
            t.start();
            writerThread = t;
        }
    }

    /** 写线程主循环:收到信号就落盘;连续多次信号合并成一次(drain 会把队列写空) */
    private static void writeLoop() {
        while (true) {
            try {
                FLUSH_SIGNAL.acquire();
                // 合并积压的信号:一次 flushToFile 就能把队列写空,多余的信号不必重复处理
                while (FLUSH_SIGNAL.tryAcquire()) {
                    // 故意留空:把 permits 消耗掉
                }
                flushToFile();
            } catch (InterruptedException ie) {
                return;
            } catch (Throwable t) {
                // 静默:性能日志失败绝不影响业务
            }
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
                    // 加严:滑动平均 FPS 低于下限即告警(电脑应≈60,低于 55 说明列表渲染有压力)
                    String fpsFlag = fps < FPS_FLOOR ? " ⚠️(FPS偏低)" : "";
                    log("滑动统计: 耗时" + duration + "ms, 渲染" + frameCount + "帧, 掉帧" + droppedFrameCount
                            + ", 实际FPS=" + String.format("%.1f", fps) + fpsFlag);
                }
                // 关键:滑动停止时清空上一帧时间戳。否则下次滑动首帧会把"两次滑动之间的空闲间隔"
                // 误判成一次巨长掉帧(出现 1~2 秒的假 🔴严重)。首帧因 lastFrameTimeNanos>0 守卫会被跳过。
                lastFrameTimeNanos = 0;
            }
        }
    }

    /**
     * Choreographer 帧回调:每帧调用
     * 用于检测掉帧
     */
    public static void onFrame(long frameTimeNanos) {
        if (!enabled) return;
        // 不在滑动中:跳过帧间隔统计,并清空上一帧时间戳。
        // 否则滑动停止后那次"未续投"的尾帧仍会执行到末尾的 lastFrameTimeNanos=frameTimeNanos,
        // 把上一次滑动的帧时间写回,导致下次滑动首帧把"两次滑动之间的空闲间隔"误判成巨长掉帧(假 🔴严重)。
        if (!scrolling) {
            lastFrameTimeNanos = 0;
            return;
        }
        frameCount++;

        if (lastFrameTimeNanos > 0) {
            long delta = frameTimeNanos - lastFrameTimeNanos;
            // 加严:单帧超过 20ms 即记为一次卡顿(车机上会被放大成明显卡顿)
            if (delta > FRAME_HITCH_MS) {
                droppedFrameCount++;
                boolean severe = delta >= FRAME_INTERVAL_33MS; // >=33ms 视为严重卡顿
                log("掉帧", "delta=" + (delta / 1_000_000) + "ms"
                        + (severe ? " 🔴严重" : " ⚠️"));
            }
        }
        lastFrameTimeNanos = frameTimeNanos;
    }

    /**
     * 把队列里积压的日志写入文件(同步方法)。
     *
     * 正常情况下由写线程调用;另在应用退出(shutdown)时由调用方同步调用,
     * 以保证最后一批日志不丢。加写锁串行化,避免与写线程同时写同一个文件。
     */
    public static void flushToFile() {
        if (!enabled || logFile == null) return;
        synchronized (WRITE_LOCK) {
            OutputStreamWriter writer = null;
            try {
                writer = new OutputStreamWriter(new FileOutputStream(logFile, true), "UTF-8");
                String entry;
                int drained = 0;
                while ((entry = logQueue.poll()) != null) {
                    writer.write(entry);
                    writer.write('\n');
                    drained++;
                }
                writer.flush();
                if (drained > 0) {
                    // 修正近似计数(只用于上限裁剪;并发 enqueue 会让它略有偏差,夹到 0 以上即可)
                    int left = logSize.addAndGet(-drained);
                    if (left < 0) {
                        logSize.set(0);
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "flushToFile failed", e);
            } finally {
                if (writer != null) {
                    try { writer.close(); } catch (Exception ignored) {}
                }
            }
        }
    }

    /**
     * dump 当前状态(定时调用)。
     * 只发出异步落盘请求,不阻塞调用线程 —— 这个方法由主线程的定时任务调用,
     * 早先的同步写盘会在车机上造成周期性卡顿。
     */
    public static void dump() {
        if (!enabled) return;
        requestFlush();
    }

    /** 应用退出时调用:这里同步落盘,确保最后一批日志不丢 */
    public static void shutdown() {
        if (!enabled) return;
        log("=== PerfLogger 关闭 ===");
        flushToFile();
        enabled = false;
    }
}
