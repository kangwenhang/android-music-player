package com.captiva.musicplayer;

import android.content.Context;
import android.os.PowerManager;

/**
 * 屏幕常亮守卫(用户明确要求:用界面时屏幕一直亮着,不要自己黑掉)。
 *
 * 为什么两套机制一起上:
 * 1) Activity 的 {@code FLAG_KEEP_SCREEN_ON} 是首选 —— 不需要权限,Activity 不可见时
 *    系统自动失效,没有"忘记释放"的风险。但它依赖 WindowManager 的 hold-screen 记账
 *    (同一时刻只有一个窗口被认定持有屏幕),息屏/解锁/系统弹窗插进来之后偶尔会没被
 *    重新认定,表现就是"窗口标志明明加着,过一会儿还是黑屏"。
 * 2) 这里再直接持一个 {@code SCREEN_BRIGHT_WAKE_LOCK},绕开那层记账作为兜底。
 *
 * 只在 Activity 前台(onResume ~ onPause)持有,后台播放时不持锁 ——
 * 避免出现"挂着导航在后台听歌导致屏幕永不熄灭"的问题。
 * 多个 Activity 切换用引用计数,不会因为中间某个 onPause 提前把锁放掉。
 *
 * API 13 起 SCREEN_BRIGHT_WAKE_LOCK 标记为废弃,但在安卓 4.2.2(API 17)上仍然有效;
 * 本项目 minSdk=17,没有更合适的替代 API。
 */
public final class ScreenOnKeeper {

    /** 单一共享锁 + 引用计数(否则 Main→Equalizer 切换时会闪一下熄屏) */
    private static PowerManager.WakeLock lock;
    private static int refCount;
    /** 获取失败只记一次日志,避免每次 onResume 都刷一行 */
    private static boolean acquireFailedLogged;

    private ScreenOnKeeper() {
    }

    /** 进入前台时调用(Activity.onResume),必须与 {@link #release()} 成对 */
    @SuppressWarnings("deprecation")
    public static synchronized void acquire(Context ctx) {
        refCount++;
        if (refCount != 1) {
            return;   // 已有别的 Activity 持有,加个计数就够了
        }
        try {
            if (lock == null) {
                PowerManager pm = (PowerManager) ctx.getApplicationContext()
                        .getSystemService(Context.POWER_SERVICE);
                lock = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK,
                        "captiva:screen-on");
                lock.setReferenceCounted(false);
            }
            lock.acquire();
            acquireFailedLogged = false;
            DownloadDiag.log("[屏幕] 前台常亮:已持有唤醒锁(FLAG_KEEP_SCREEN_ON 之外的第二层保障)");
        } catch (Throwable t) {
            refCount = Math.max(0, refCount - 1);
            if (!acquireFailedLogged) {
                acquireFailedLogged = true;
                DownloadDiag.logError("[屏幕] 前台常亮锁获取失败(退回仅靠窗口标志)", t);
            }
        }
    }

    /** 退出前台时调用(Activity.onPause) */
    public static synchronized void release() {
        refCount--;
        if (refCount > 0) {
            return;
        }
        refCount = 0;
        try {
            if (lock != null && lock.isHeld()) {
                lock.release();
                DownloadDiag.log("[屏幕] 前台常亮:已释放唤醒锁(界面已退到后台)");
            }
        } catch (Throwable ignored) {
            // 释放失败也不能崩(系统会在进程退出时回收)
        }
    }
}
