package com.captiva.musicplayer;

import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 主线程卡顿看门狗(黑屏取证用)。
 *
 * 为什么需要它:本项目历史上出现过两次"黑屏",查下来根因都是**主线程被同步任务占住**
 * (封面 Bitmap.createScaledBitmap、scrollToCurrentSong 里的 O(n) 查找),第一帧迟迟
 * 画不出来 → 窗口一片纯黑,点一下才恢复。这类卡顿在车机上没有任何可见线索,
 * 所以用最笨也最可靠的办法量:后台线程定期往主线程丢一个空任务,量往返耗时。
 *
 * 只记"超过阈值"的那些次,正常情况一行都不写,可以长期开着。
 * 日志里出现「[主线程] 卡顿 Xms」→ 黑屏是 App 自己卡出来的;
 * 没有这行、却有「[屏幕] 系统广播 熄屏」→ 是系统息屏;
 * 两者都没有、堆也正常 → 是显示/合成层(模拟器宿主 GPU 等)。
 *
 * 线程是 daemon,不持有 Activity / Context,不会拖住进程退出。
 */
public final class MainThreadWatchdog {

    /** 采样间隔:诊断期加密到 1s(388 后用户仍报"下一首"卡顿,但阈值内抓不到现场;
     *  每秒 post 一个空任务对主线程负担可忽略)。定位完成后应改回 5000 */
    private static final long INTERVAL_MS = 1000L;
    /** 超过这个耗时才算卡顿并落盘。诊断期 300ms:车机正常抖动 <100ms,
     *  300ms 以上的主线程占用都值得抓堆栈看(2026-10-04 用户要求调低) */
    private static final long WARN_MS = 300L;
    /** 同一次连续卡顿只记一行,避免把日志刷爆 */
    private static final long DEDUP_MS = 3000L;

    private static volatile boolean running;
    private static volatile long lastReportTs;

    /**
     * 卡顿现场附加探测钩子(2026-10-05):由界面注册,卡顿报告时在 watchdog 线程调用,
     * 返回一行诊断文本(如 rvList.childCount)。实现必须**非阻塞、只读**——主线程此刻
     * 正卡着,probe 里做任何同步操作都拿不到数据甚至死锁。
     */
    public interface ExtraProbe {
        String probe();
    }

    private static volatile ExtraProbe extraProbe;

    public static void setExtraProbe(ExtraProbe p) {
        extraProbe = p;
    }

    private MainThreadWatchdog() {
    }

    /** 幂等;在 Application.onCreate 里调一次 */
    public static synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        final Handler main = new Handler(Looper.getMainLooper());
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                while (running) {
                    long t0 = System.currentTimeMillis();
                    final CountDownLatch latch = new CountDownLatch(1);
                    boolean posted = main.post(new Runnable() {
                        @Override
                        public void run() {
                            latch.countDown();
                        }
                    });
                    if (posted) {
                        try {
                            // 两段式等待:先只等 WARN_MS —— 若超时未返回,说明主线程
                            // 确实已卡顿超过阈值,**趁它还卡着立刻抓现场堆栈**(此时
                            // 栈顶就是凶手);恢复后再抓就只剩"恢复后的 peaceful 栈"了。
                            boolean stuckConfirmed = !latch.await(WARN_MS, TimeUnit.MILLISECONDS);
                            java.lang.StackTraceElement[] stuckStack = null;
                            long cpuAtStuck = -1L;
                            java.util.Map<Long, Long> cpuPerThreadAtStuck = null;
                            if (stuckConfirmed) {
                                stuckStack = grabMainThreadStack();
                                cpuAtStuck = readProcessCpuMs();
                                cpuPerThreadAtStuck = readThreadCpuMap();
                                // 继续等主线程恢复(总上限 180s),拿到真实卡顿时长
                                latch.await(180_000L - WARN_MS, TimeUnit.MILLISECONDS);
                            }
                            long cost = System.currentTimeMillis() - t0;
                            if (cost >= WARN_MS) {
                                long now = System.currentTimeMillis();
                                if (now - lastReportTs >= DEDUP_MS) {
                                    lastReportTs = now;
                                    DownloadDiag.log("[主线程] 卡顿 " + cost
                                            + "ms(界面在这段时间里画不出新帧,表现就是黑屏/僵住) | "
                                            + DownloadDiag.env());
                                    // 卡住时刻的堆栈:定位"主线程那几秒在干什么"的直接证据
                                    if (stuckStack != null && stuckStack.length > 0) {
                                        DownloadDiag.log("[主线程] 卡顿现场堆栈(卡住时刻抓取,栈顶=正在执行):");
                                        int limit = Math.min(stuckStack.length, 30);
                                        for (int i = 0; i < limit; i++) {
                                            DownloadDiag.log("    at " + stuckStack[i].toString());
                                        }
                                        if (stuckStack.length > limit) {
                                            DownloadDiag.log("    ...(共 " + stuckStack.length + " 帧,截断)");
                                        }
                                    }
                                    // 全线程清单(2026-10-04):卡顿=主线程被抢时的"在场人员名单"。
                                    // 每线程记 优先级/状态/栈顶一帧,谁在占用 CPU 一眼可见
                                    dumpThreadInventory();
                                    // 附加探测(2026-10-05):界面注册的现场快照(如 rvList.childCount)。
                                    // 卡顿时 RecyclerView 挂着多少个 child 是"fill 病态创建"假说的
                                    // 决定性证据:正常 ~12 个,病态时会等于 adapter 总数(如 811)。
                                    ExtraProbe p = extraProbe;
                                    if (p != null) {
                                        try {
                                            String extra = p.probe();
                                            if (extra != null) {
                                                DownloadDiag.log("[主线程] 卡顿现场附加探测: " + extra);
                                            }
                                        } catch (Throwable ignored) {
                                        }
                                    }
                                    // 按线程 CPU 增量排行(394):卡顿期间每个线程烧了多少 CPU,
                                    // 直接点名元凶(TLS 握手/GC/主线程文本排版一目了然)
                                    dumpPerThreadCpuDelta(cpuPerThreadAtStuck);
                                    // 内因/外因判据:卡顿期间本进程 CPU 时间增量。
                                    // <30% 墙钟 → 主线程没分到 CPU(外部抢占:mediaserver 解码/
                                    //   系统进程吃满 2 核);>70% → 自己烧的(GC 风暴/分配风暴)
                                    long cpuTotal = readProcessCpuMs();
                                    if (cpuAtStuck >= 0 && cpuTotal >= cpuAtStuck && cost > 0) {
                                        long used = cpuTotal - cpuAtStuck;
                                        int pct = (int) (used * 100 / cost);
                                        String verdict = (pct >= 70) ? "内因:本进程(GC/分配风暴)"
                                                : (pct <= 30) ? "外因:CPU 被外部进程抢占"
                                                : "混合";
                                        DownloadDiag.log("[主线程] 卡顿期间本进程 CPU=" + used
                                                + "ms/" + cost + "ms(" + pct + "%) → " + verdict);
                                    }
                                }
                            }
                        } catch (InterruptedException e) {
                            break;
                        }
                    }
                    try {
                        Thread.sleep(INTERVAL_MS);
                    } catch (InterruptedException e) {
                        break;
                    }
                }
            }
        }, "main-thread-watchdog");
        t.setDaemon(true);
        try {
            t.start();
        } catch (Throwable e) {
            running = false;
            DownloadDiag.logError("[主线程] 看门狗启动失败", e);
        }
    }

    /** 读 /proc/self/task 下每个 tid 的 stat,返回 tid 到 (utime+stime) 毫秒的映射 */
    private static java.util.Map<Long, Long> readThreadCpuMap() {
        java.util.Map<Long, Long> map = new java.util.HashMap<Long, Long>();
        try {
            java.io.File taskDir = new java.io.File("/proc/self/task");
            java.io.File[] tids = taskDir.listFiles();
            if (tids == null) {
                return map;
            }
            for (java.io.File f : tids) {
                try {
                    long cpu = readStatCpuMs(f.getAbsolutePath());
                    if (cpu >= 0) {
                        map.put(Long.parseLong(f.getName()), cpu);
                    }
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
        return map;
    }

    /** 读一个 /proc/<pid|tid>/stat 的 utime+stime,转毫秒;失败返回 -1 */
    private static long readStatCpuMs(String path) {
        try {
            byte[] buf = new byte[512];
            int n;
            java.io.FileInputStream fis = new java.io.FileInputStream(path);
            try {
                n = fis.read(buf);
            } finally {
                fis.close();
            }
            if (n <= 0) {
                return -1L;
            }
            String s = new String(buf, 0, n);
            int close = s.lastIndexOf(')');
            if (close < 0 || close + 2 >= s.length()) {
                return -1L;
            }
            String[] f = s.substring(close + 2).split(" ");
            // 去掉 comm 后:0=state,10=utime,11=stime,15=nice(1-based 字段 3/14/15/20)
            long utime = Long.parseLong(f[10]);
            long stime = Long.parseLong(f[11]);
            long hz = 100L;   // Android USER_HZ 固定 100
            return (utime + stime) * 1000L / hz;
        } catch (Throwable ignored) {
            return -1L;
        }
    }

    /** 读 /proc/self/stat 的 utime+stime(本进程累计 CPU 毫秒),判内因/外因用 */
    private static long readProcessCpuMs() {
        return readStatCpuMs("/proc/self/stat");
    }

    /**
     * 按线程 CPU 增量排行:卡顿期间每个线程烧了多少 CPU(394)。
     * 元凶直接点名(TLS 握手线程/GC/主线程文本排版一目了然),取前 8 名。
     */
    private static void dumpPerThreadCpuDelta(java.util.Map<Long, Long> atStuck) {
        if (atStuck == null) {
            return;
        }
        if (atStuck.isEmpty()) {
            DownloadDiag.log("[主线程] 线程 CPU 基线为空(/proc/self/task 枚举失败)");
            return;
        }
        try {
            java.util.Map<Long, Long> now = readThreadCpuMap();
            java.util.List<long[]> deltas = new java.util.ArrayList<long[]>();
            long sum = 0;
            for (java.util.Map.Entry<Long, Long> e : now.entrySet()) {
                long base = atStuck.containsKey(e.getKey()) ? atStuck.get(e.getKey()) : 0L;
                long d = e.getValue() - base;
                if (d > 0) {
                    deltas.add(new long[]{e.getKey(), d});
                    sum += d;
                }
            }
            if (deltas.isEmpty()) {
                DownloadDiag.log("[主线程] 卡顿期间无线程 CPU 增量(时间被进程外消耗)");
                return;
            }
            java.util.Collections.sort(deltas, new java.util.Comparator<long[]>() {
                public int compare(long[] a, long[] b) {
                    return (b[1] > a[1]) ? 1 : (b[1] < a[1]) ? -1 : 0;
                }
            });
            DownloadDiag.log("[主线程] 卡顿期间各线程 CPU 增量排行:");
            for (int i = 0; i < deltas.size() && i < 8; i++) {
                long tid = deltas.get(i)[0];
                long ms = deltas.get(i)[1];
                Thread t = findThreadById(tid);
                String name = (t != null) ? t.getName() : ("tid-" + tid);
                String top = "(已退出)";
                if (t != null) {
                    java.lang.StackTraceElement[] st = t.getStackTrace();
                    top = (st != null && st.length > 0) ? st[0].toString() : "(无栈)";
                }
                DownloadDiag.log("    " + name + " = " + ms + "ms | " + top);
            }
        } catch (Throwable t) {
            // 排行失败要留痕,否则又变成"静默没输出"排查半天
            DownloadDiag.log("[主线程] 线程 CPU 排行失败: " + t);
        }
    }

    /** 按 tid 找线程对象(找名字/栈顶用) */
    private static Thread findThreadById(long tid) {
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            if (t.getId() == tid) {
                return t;
            }
        }
        return null;
    }

    /** 读线程的 OS nice 值(验 BACKGROUND 降级是否真生效);失败返回 -99 */
    private static long readThreadNice(long tid) {
        try {
            byte[] buf = new byte[512];
            int n;
            java.io.FileInputStream fis = new java.io.FileInputStream(
                    "/proc/self/task/" + tid + "/stat");
            try {
                n = fis.read(buf);
            } finally {
                fis.close();
            }
            if (n <= 0) {
                return -99L;
            }
            String s = new String(buf, 0, n);
            int close = s.lastIndexOf(')');
            if (close < 0 || close + 2 >= s.length()) {
                return -99L;
            }
            String[] f = s.substring(close + 2).split(" ");
            return Long.parseLong(f[15]);   // nice = 去掉 comm 后第 15 字段
        } catch (Throwable t) {
            return -99L;
        }
    }

    /** 进程退出/测试时停止采样 */
    public static void stop() {
        running = false;
    }

    /** 从全局线程列表里找主线程并取当前堆栈(卡顿现场用) */
    private static java.lang.StackTraceElement[] grabMainThreadStack() {
        try {
            for (Thread t : Thread.getAllStackTraces().keySet()) {
                if ("main".equals(t.getName())) {
                    return t.getStackTrace();
                }
            }
        } catch (Throwable ignored) {
            // 取不到就退化为无堆栈(时长日志照打)
        }
        return null;
    }

    /**
     * 卡顿时刻的全线程清单:名字/优先级/状态/栈顶一帧。
     * 最多记 20 条(超出的合并为一行计数),避免刷爆日志。
     * 诊断期(300ms 阈值)专用;根因定位后可随阈值一起收掉。
     */
    private static void dumpThreadInventory() {
        try {
            java.util.Map<Thread, java.lang.StackTraceElement[]> all =
                    Thread.getAllStackTraces();
            DownloadDiag.log("[主线程] 卡顿时刻线程清单(共 " + all.size() + " 线程):");
            int logged = 0;
            int skipped = 0;
            for (java.util.Map.Entry<Thread, java.lang.StackTraceElement[]> e
                    : all.entrySet()) {
                Thread t = e.getKey();
                if (t == null || "main".equals(t.getName())) {
                    continue;
                }
                java.lang.StackTraceElement[] st = e.getValue();
                String top = (st != null && st.length > 0)
                        ? st[0].toString() : "(无栈)";
                if (logged < 20) {
                    DownloadDiag.log("    [" + t.getName()
                            + " pri=" + t.getPriority()
                            + " nice=" + readThreadNice(t.getId())
                            + " " + t.getState() + "] " + top);
                    logged++;
                } else {
                    skipped++;
                }
            }
            if (skipped > 0) {
                DownloadDiag.log("    ...(另有 " + skipped + " 线程未列出)");
            }
        } catch (Throwable ignored) {
        }
    }
}
