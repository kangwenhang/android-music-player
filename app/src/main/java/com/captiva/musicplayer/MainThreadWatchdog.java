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

    /** 采样间隔:5 秒一次,对主线程的额外负担可以忽略 */
    private static final long INTERVAL_MS = 5000L;
    /** 超过这个耗时才算卡顿并落盘(2 核车机上偶发 1s 内的抖动是正常的) */
    private static final long WARN_MS = 1500L;
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
                            if (stuckConfirmed) {
                                stuckStack = grabMainThreadStack();
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
}
