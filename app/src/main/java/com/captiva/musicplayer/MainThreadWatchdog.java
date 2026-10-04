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
                            if (stuckConfirmed) {
                                stuckStack = grabMainThreadStack();
                                cpuAtStuck = readProcessCpuMs();
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

    /** 读 /proc/self/stat 的 utime+stime(本进程累计 CPU 毫秒),判内因/外因用 */
    private static long readProcessCpuMs() {
        try {
            byte[] buf = new byte[512];
            int n;
            java.io.FileInputStream fis = new java.io.FileInputStream("/proc/self/stat");
            try {
                n = fis.read(buf);
            } finally {
                fis.close();
            }
            if (n <= 0) {
                return -1L;
            }
            String s = new String(buf, 0, n);
            // comm 字段可能含空格/括号,从最后一个 ')' 后取字段
            int close = s.lastIndexOf(')');
            if (close < 0 || close + 2 >= s.length()) {
                return -1L;
            }
            String[] f = s.substring(close + 2).split(" ");
            // 去掉 comm 后 state 是第 0 字段,utime=第 11,stime=第 12(1-based)
            long utime = Long.parseLong(f[11]);
            long stime = Long.parseLong(f[12]);
            long hz = 100L;   // Android USER_HZ 固定 100
            return (utime + stime) * 1000L / hz;
        } catch (Throwable ignored) {
            return -1L;
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
