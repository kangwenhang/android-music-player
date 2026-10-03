package com.captiva.musicplayer;

import android.content.Context;
import android.os.Environment;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * 缓存诊断日志(正式版 / release 包也生效,与 PerfLogger 不同,不受 BuildConfig.DEBUG 门控)
 *
 * 用途:车机/手机上不方便接 adb 抓 logcat 时,把关键诊断信息直接落盘成文本文件,
 * 用任意文件管理器打开即可查看(如「点击播放没有出现缓存进度条」这类问题)。
 *
 * 日志文件路径: 统一诊断日志目录 /cache_debug.log
 * (目录由调用方传入 DownloadDiag.resolveLogDir 的结果,与 download_debug.log / crash_log.txt 同目录)
 *
 * 【写入策略 —— 全异步,绝不阻塞调用线程】
 * 早期实现是"每次 log() 都 open→write→flush→close",这在主线程上代价极高:
 * 一次点击播放会写 1~2 行,下载期间每个进度广播还会再写一行(整首下载上百行),
 * 每次都是完整的外部存储文件事务,直接造成车机上的可见卡顿。
 * 现在改为:log() 只做入队(内存操作,微秒级),由单独的后台守护线程批量落盘
 * —— 一批日志只 open/close 一次,主线程磁盘 I/O 归零。
 *
 * 代价:进程被杀时可能丢掉最后一小批日志。诊断日志可接受,换取主线程零阻塞。
 *
 * 线程安全:阻塞队列,天然线程安全;单写线程串行落盘。
 * 失败策略:任何 IO 异常静默吞掉 —— 诊断日志绝不能影响正常播放。
 */
public class CacheDebugLog {

    private static final String TAG = "CacheDebugLog";
    /**
     * 总开关:false = 完全关闭(不写文件、不建写线程、也不进 logcat)。
     *
     * 本类原本是**正式版也生效**的诊断日志(不受 BuildConfig.DEBUG 门控),
     * 目的是车机不接 adb 时也能靠文件排查问题。诊断期结束后正式发布即关闭:
     * 每次播放/切换都会往 U 盘目录写文件,在车机上属于无谓的 I/O 与噪音。
     * 需要重新排查时:把这里改回 true 重新构建即可,调用点无需改动(log 内部短路)。
     *
     * 【2026-10-04 关闭】进度条"圆点与深蓝条不同步"排查定案(v5.7.353 运行时标定
     * 模拟器全程验证通过:frac 0.006~0.940 填充终点与圆点中心偏差 ≤1px),
     * 诊断期结束,正式版关闭落盘。
     */
    public static final boolean ENABLED = false;

    private static final String FILE_NAME = "cache_debug.log";
    /** 单文件大小上限(超过即重建),避免长期运行把存储写满 */
    private static final long MAX_BYTES = 256 * 1024L;
    /** 待写队列上限:超出则丢弃新日志,避免异常刷屏时把内存吃光 */
    private static final int MAX_QUEUE = 4096;
    /** 空闲时的轮询间隔(ms):有新日志会立即被 drain,不必等满这个时间 */
    private static final long IDLE_POLL_MS = 400L;
    /** 单批最多写多少行,避免一次拼出超长字符串 */
    private static final int MAX_BATCH = 200;

    /**
     * 日期格式化。SimpleDateFormat 非线程安全,而 log() 会被主线程、下载线程、
     * 广播回调等同时调用,因此必须用 ThreadLocal 隔离,不能共享同一个实例。
     */
    private static final ThreadLocal<java.text.SimpleDateFormat> SDF_HOLDER =
            new ThreadLocal<java.text.SimpleDateFormat>() {
                @Override
                protected java.text.SimpleDateFormat initialValue() {
                    return new java.text.SimpleDateFormat(
                            "MM-dd HH:mm:ss", java.util.Locale.getDefault());
                }
            };

    private static final LinkedBlockingQueue<String> QUEUE = new LinkedBlockingQueue<>(MAX_QUEUE);

    /** 日志目录(由调用方 init 指定,通常为云端歌曲目录;为空时回退 外部存储/Music) */
    private static volatile String dirPath;
    /** 是否已写过文件头(含完整绝对路径),方便用户直接定位文件 */
    private static volatile boolean headerLogged = false;

    /** 写盘线程(懒启动,守护线程,不阻止进程退出) */
    private static volatile Thread writerThread;

    private CacheDebugLog() {}

    /**
     * 初始化日志目录(建议在加载音乐 / 播放服务启动时调用)。
     * dir 由调用方传入 DownloadDiag.resolveLogDir(统一诊断日志目录)的结果,
     * 与 download_debug.log / crash_log.txt 同目录;首次调用会写入一行含完整路径的文件头。
     * 本方法只入队,不做磁盘 I/O,可从主线程安全调用。
     */
    public static void init(Context context, String dir) {
        if (!ENABLED) {
            return;   // 关闭时不记录目录,也不写文件头
        }
        if (dir != null && !dir.isEmpty()) {
            dirPath = dir;
        }
        if (!headerLogged) {
            headerLogged = true;
            // 入队(而非直接写盘):文件头同样交给写线程,保持调用线程零 I/O
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

    /**
     * 追加一条诊断日志。**只入队,立即返回** —— 调用线程不做任何磁盘 I/O,
     * 因此可以放心地在主线程 / UI 回调里调用。
     * 同时输出到 logcat,便于接 adb 时对照。
     */
    public static void log(String msg) {
        if (!ENABLED || msg == null) {
            return;   // 关闭:不入队、不落盘、不进 logcat
        }
        Log.i(TAG, msg);
        enqueue(SDF_HOLDER.get().format(new java.util.Date()) + "  " + msg);
    }

    /** 入队并确保写线程已启动(队列满时静默丢弃,绝不阻塞调用方) */
    private static void enqueue(String line) {
        ensureWriter();
        // offer 非阻塞:队列满说明日志爆炸,丢日志也不能阻塞业务线程
        QUEUE.offer(line);
    }

    private static void ensureWriter() {
        if (writerThread != null) {
            return;
        }
        synchronized (CacheDebugLog.class) {
            if (writerThread != null) {
                return;
            }
            Thread t = new Thread(new Runnable() {
                @Override
                public void run() {
                    writeLoop();
                }
            }, "cache-debug-log");
            t.setDaemon(true);
            t.start();
            writerThread = t;
        }
    }

    /** 写线程主循环:攒够一批(或空闲超时)后一次性落盘 */
    private static void writeLoop() {
        while (true) {
            try {
                // 阻塞等第一条;等到说明有活了
                String first = QUEUE.poll(IDLE_POLL_MS, TimeUnit.MILLISECONDS);
                if (first == null) {
                    continue;
                }
                List<String> batch = new ArrayList<>(MAX_BATCH);
                batch.add(first);
                QUEUE.drainTo(batch, MAX_BATCH - 1);
                writeBatch(batch);
            } catch (InterruptedException ie) {
                // 被中断:静默退出即可(守护线程)
                return;
            } catch (Throwable t) {
                // 静默:诊断日志写入失败不影响播放
            }
        }
    }

    /** 一次性写入一批日志(整批只 open/close 一次) */
    private static void writeBatch(List<String> lines) {
        if (lines.isEmpty()) {
            return;
        }
        String dir = dirPath;
        if (dir == null || dir.isEmpty()) {
            dir = Environment.getExternalStorageDirectory().getAbsolutePath() + "/Music";
        }
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
            StringBuilder sb = new StringBuilder(256 * lines.size());
            for (int i = 0; i < lines.size(); i++) {
                sb.append(lines.get(i)).append('\n');
            }
            writer.write(sb.toString());
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
