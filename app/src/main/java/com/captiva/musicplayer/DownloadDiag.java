package com.captiva.musicplayer;

import android.content.Context;
import android.os.PowerManager;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 下载 / 联网播放诊断日志(统一诊断日志目录入口)
 *
 * 三类诊断日志(下载/缓存/崩溃)都通过本类的 resolveLogDir() 取"统一存放目录":
 * 1. download_debug.log —— 本类负责(只记失败与异常,常开)
 * 2. cache_debug.log   —— CacheDebugLog.init(dir) 传入 resolveLogDir()
 * 3. crash_log.txt     —— App.installCrashLogger() 写入 resolveLogDir()
 *
 * 目录规则:设置页(测试版)可选定一个自定义目录;为空或不可写时回退音乐根目录(getSyncPath)。
 * 因此"诊断日志目录"是三类日志的唯一真相来源,改一处即三处生效。
 *
 * 存在的理由:车机没有 adb,logcat 拿不到;而"云端歌曲播不了/下不动"这类问题的
 * 失败原因在界面上完全不可见(播放失败只是静默跳下一首,下载失败只是 result=false)。
 * 于是把关键节点与真实失败原因落到文件,复现后把文件拷出来就能定位。
 *
 * 与 CacheDebugLog 的区别(为什么不复用它):
 * - CacheDebugLog 是**高频性能埋点**(每次切换/重建一行),平时必须关掉,否则车机扛不住;
 * - 这里只记**异常与失败**,正常播放不写一行,体积极小,可以长期开着。
 * - 两者 ENABLED 开关独立,互不牵连。
 *
 * 想彻底关掉:把 ENABLED 改成 false(本类所有写入变 no-op;cached/crash 各自另有开关)。
 */
public final class DownloadDiag {

    private static final String TAG = "DownloadDiag";

    /**
     * 总开关:false = 一行都不写(同时关掉下方两类日志)。
     *
     * 【v6.0 彻底静默】卡顿排查主线全部闭环(创建风暴/reset/setDataSource/正则,
     * 车机 25 号日志复核通过),正式版完全关闭落盘:download_debug.log 不再产生,
     * Watchdog / 生命周期 / 联网播放等记录全部停止。崩溃取证不受影响
     * (crash_log.txt 由 UncaughtExceptionHandler 独立写入)。
     * 需要重新排查:改回 true 重新构建即可,调用点无需改动(log 内部短路)。
     */
    public static final boolean ENABLED = false;

    /**
     * 列表/收藏夹调试开关:false = 关掉本轮排查"列表错位(第一首下面是第13首)"时
     * 临时加的 [列表] / [列表诊断] / [VISIBLE] / [BUILD] / [收藏夹] 等诊断日志。
     *
     * 与 ENABLED 独立:ENABLED 管的是"下载 / 缓存 / 播放失败"这类真问题日志
     * (车机无法缓存歌曲、播放失败等),必须常开;LIST_DIAG 只管"列表渲染调试",
     * 问题修好后关掉即可,不影响上面的失败诊断。
     *
     * 【v5.7.435 收尾】卡顿排查闭环(创建风暴/reset/setDataSource/正则四个根因
     * 逐一修掉,车机 25 号日志复核通过),列表调试与全部探测随此开关关闭:
     * MainActivity 的 RV 快照 ExtraProbe + 脏视图探测器、MusicAdapter 的
     * 创建风暴/挂载抽样/回收明细热路径埋点均以 LIST_DIAG 门控。
     * 需要重新排查时改回 true 即整体恢复,调用点无需改动。
     */
    public static final boolean LIST_DIAG = false;

    private static final String FILE_NAME = "download_debug.log";
    /** 超过这个体积就整体重写(只保留最新一轮),防止日志把车机存储吃满 */
    private static final long MAX_BYTES = 256 * 1024L;

    private static final ExecutorService WRITER = Executors.newSingleThreadExecutor();
    private static final SimpleDateFormat FMT =
            new SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA);

    private static File file;
    private static boolean inited;
    /** Application 上下文(只用于 env() 查屏幕状态 / 系统服务,不长期持有 Activity) */
    private static Context appCtx;
    /** 第一行的启动标记只写一次;出现 N 次 = 进程重启 N 次 = 崩了 N-1 次 */
    private static boolean headerLogged;

    private DownloadDiag() {
    }

    /** 在 Application / Service 初始化时调用一次;未 init 时所有 log 都是 no-op */
    public static synchronized void init(Context ctx) {
        if (!ENABLED || inited) {
            return;
        }
        try {
            appCtx = ctx.getApplicationContext();
            String root = resolveLogDir(ctx);
            File dir = new File(root);
            if (!dir.exists()) {
                dir.mkdirs();
            }
            file = new File(dir, FILE_NAME);
            inited = true;
            if (!headerLogged) {
                headerLogged = true;
                log("==== download_debug.log 开始记录 ====");
                log("日志路径: " + file.getAbsolutePath());
                log("App 版本: " + appVersion());
            }
        } catch (Throwable t) {
            Log.w(TAG, "初始化失败(诊断日志不可用)", t);
        }
    }

    /**
     * 解析统一诊断日志目录:三类日志(download_debug.log / cache_debug.log / crash_log.txt)
     * 都从这里取目录。优先用设置里的自定义目录(测试版设置页可选,仅 DEBUG 下 UI 可见),
     * 父级不可写或为空时回退到音乐根目录(getSyncPath,默认行为)。
     */
    public static String resolveLogDir(Context ctx) {
        try {
            NavidromeConfig cfg = new NavidromeConfig(ctx.getApplicationContext());
            String custom = cfg.getLogDir();
            if (custom != null && !custom.trim().isEmpty()) {
                File d = new File(custom.trim());
                File parent = d.getParentFile();
                if (parent != null && (parent.canWrite()
                        || (!parent.exists() && parent.mkdirs()))) {
                    return custom.trim();
                }
                Log.w(TAG, "自定义日志目录不可用,回退音乐根目录: " + custom);
            }
        } catch (Throwable ignored) {
            // 取配置失败就回落默认
        }
        return new NavidromeConfig(ctx.getApplicationContext()).getSyncPath();
    }

    /** 当前日志文件绝对路径(设置页预览/确认落盘位置用;未初始化时返回 null) */
    public static String getLogFilePath() {
        return (file != null) ? file.getAbsolutePath() : null;
    }

    /** 重新初始化:设置页改了目录后即时生效,无需重启(旧日志文件保留在原位) */
    public static synchronized void reinit(Context ctx) {
        if (!ENABLED) {
            return;
        }
        inited = false;
        file = null;
        init(ctx);
        if (file != null) {
            log("日志目录已切换: " + file.getAbsolutePath());
        }
    }

    /** 记一行(异步落盘,绝不阻塞调用方) */
    public static void log(final String msg) {
        if (!ENABLED || msg == null) {
            return;
        }
        final String line = FMT.format(new Date()) + " " + msg + "\n";
        try {
            WRITER.execute(new Runnable() {
                @Override
                public void run() {
                    write(line);
                }
            });
        } catch (Throwable ignored) {
            // 线程池拒绝(进程在退出)时静默丢弃,绝不影响播放
        }
    }

    /**
     * 列表/收藏夹调试日志:仅当 LIST_DIAG=true 时写。
     * 用于排查"列表错位(第一首下面是第13首)"时临时加的 [列表]/[列表诊断]/[VISIBLE]/[BUILD]/[收藏夹] 等。
     * LIST_DIAG=false(默认)时整类静默,不影响 ENABLED 旗下的"下载/缓存/播放失败"诊断。
     */
    public static void listDiag(String msg) {
        if (!LIST_DIAG) {
            return;
        }
        log(msg);
    }

    /** 记一行失败原因(带异常类型与 message,不写完整堆栈以控制体积) */
    public static void logError(String msg, Throwable t) {
        if (!ENABLED) {
            return;
        }
        StringBuilder sb = new StringBuilder(msg == null ? "" : msg);
        if (t != null) {
            sb.append(" | ").append(t.getClass().getSimpleName())
                    .append(": ").append(t.getMessage());
        }
        log(sb.toString());
    }

    /**
     * 环境快照:屏幕亮灭 + 堆占用。
     *
     * 「过一会儿黑一下、点一下又亮」这类问题最需要的就是这两个数:
     * - 屏幕=灭 → 是系统把屏幕关了(息屏超时/电源策略),不是 App 把界面画黑;
     * - 屏幕=亮 而画面确实黑了 → 显示/合成层(模拟器宿主 GPU、SurfaceFlinger)没有合成,
     *   App 这边无能为力;
     * - 顺带记堆占用,用来回答"是不是内存回收把界面搞黑的"。
     *
     * 注意:所有调用点都在主线程附近的轻量路径上,这里只读几个数字,不做任何阻塞 IO。
     */
    public static String env() {
        String screen;
        try {
            Context c = appCtx;
            if (c == null) {
                screen = "?";
            } else {
                PowerManager pm = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
                boolean on;
                if (android.os.Build.VERSION.SDK_INT >= 20) {
                    // API 20 起 isScreenOn 被 isInteractive 取代
                    on = pm.isInteractive();
                } else {
                    on = pm.isScreenOn();
                }
                screen = on ? "亮" : "灭";
            }
        } catch (Throwable t) {
            screen = "?";
        }
        long max = Runtime.getRuntime().maxMemory() / (1024 * 1024);
        long used = (Runtime.getRuntime().totalMemory()
                - Runtime.getRuntime().freeMemory()) / (1024 * 1024);
        return "屏幕=" + screen + " 堆=" + used + "/" + max + "MB";
    }

    /** 当前安装包版本(versionName-versionCode),诊断日志定位"这台设备跑的哪版"用 */
    public static String appVersion() {
        try {
            Context c = appCtx;
            if (c == null) {
                return "?";
            }
            android.content.pm.PackageInfo pi = c.getPackageManager()
                    .getPackageInfo(c.getPackageName(), 0);
            return pi.versionName + " (" + pi.versionCode + ")";
        } catch (Throwable t) {
            return "?";
        }
    }

    /**
     * URL 脱敏:去掉 query(里面可能有 token / guid / 签名),只留 scheme://host/path。
     * 日志是要拷出来给人看的,不该带凭据。
     */
    public static String safeUrl(String url) {
        if (url == null) {
            return "null";
        }
        if (url.isEmpty()) {
            return "(空)";
        }
        int q = url.indexOf('?');
        String base = q >= 0 ? url.substring(0, q) : url;
        return base + (q >= 0 ? "?<query已省略>" : "");
    }

    /** 把 MediaPlayer 的 what/extra 翻译成人话(-1004 这类裸数字没法看) */
    public static String mpError(int what, int extra) {
        String w;
        if (what == android.media.MediaPlayer.MEDIA_ERROR_UNKNOWN) {
            w = "MEDIA_ERROR_UNKNOWN(1)";
        } else if (what == android.media.MediaPlayer.MEDIA_ERROR_SERVER_DIED) {
            w = "MEDIA_ERROR_SERVER_DIED(100)";
        } else {
            w = "what=" + what;
        }
        String e;
        switch (extra) {
            case -1004:
                e = "MEDIA_ERROR_IO: 网络/读写失败(HTTPS 握手失败、连接被拒、流中断)";
                break;
            case -1007:
                e = "MEDIA_ERROR_MALFORMED: 响应不是合法媒体(可能是登录页/JSON 报错)";
                break;
            case -1010:
                e = "MEDIA_ERROR_UNSUPPORTED: 码流不支持";
                break;
            case -110:
                e = "MEDIA_ERROR_TIMED_OUT: 连接超时";
                break;
            case 200:
                e = "NOT_VALID_FOR_PROGRESSIVE_PLAYBACK: 不支持边下边播";
                break;
            default:
                e = "extra=" + extra;
        }
        return w + " / " + e;
    }

    private static synchronized void write(String line) {
        if (file == null) {
            return;
        }
        OutputStreamWriter w = null;
        try {
            if (file.exists() && file.length() > MAX_BYTES) {
                // 超上限:整体重写,只留最新一轮(不做复杂轮转,车机上够用)
                file.delete();
            }
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            w = new OutputStreamWriter(new FileOutputStream(file, true), "UTF-8");
            w.write(line);
            w.flush();
        } catch (Throwable ignored) {
            // 写日志失败绝不能影响播放
        } finally {
            if (w != null) {
                try {
                    w.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }
}
