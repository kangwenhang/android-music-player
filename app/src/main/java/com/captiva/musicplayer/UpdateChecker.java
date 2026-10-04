package com.captiva.musicplayer;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Random;

/**
 * 车机自更新:从 fnOS 外部分享链接检查并安装新版本(仅手动触发,不自动检查)。
 *
 * <p>背景:车机复制 APK 经常"解析安装包错误"(传输/下载环节损坏,sha256 对不上),
 * 每次升级都要"GitHub 下载 → 电脑 → U盘 → 车机"太繁琐。改为 app 内点击"检查更新"
 * 直接从 fnOS 分享链接拉取新包,下载后先做 sha256 校验(根治半截包),再调起系统安装器。</p>
 *
 * <p>协议(2026-10 对 share.fnnas.net 页面 JS 逆向所得,全部实测通过):</p>
 * <ol>
 * <li><b>解析分享</b>:POST {CONNECT_BASE}/api/v1/fn/share, body={"shareId":..},
 *     header authx(签名常量 KEY_CONNECT),返回 data.ipv4[](局域网地址)、
 *     data.fn[](FN Connect 中继域名)、data.port.httpPort(5666)。
 *     注意:http 明文即可访问(实测),老车机信任库问题不影响这一步。</li>
 * <li><b>取会话 token</b>:GET {base}/s/{shareId}(base 依次尝试
 *     http://ipv4:5666 → https://fn域名),页面 HTML 内嵌
 *     &lt;script id="share-data"&gt;{"data":{"token":..}}&lt;/script&gt;,正则提取。</li>
 * <li><b>列文件</b>:POST {base}/s/{shareId}/api/v1/share/list,
 *     body={"shareId":..,"path":"..","fileId":N}(fileId 进子目录时必带,
 *     path 做路径校验),header Auth: {token} + authx(签名常量 KEY_SHARE)。</li>
 * <li><b>下载</b>:POST {base}/s/{shareId}/api/v1/share/download,
 *     body={"files":[{"path":"父路径/文件名","fileId":N}],"shareId":..,
 *     "downloadFilename":文件名},返回 data.path(下载 URL,相对/绝对都有可能),
 *     GET 该 URL 得到文件内容。<b>【关键】GET 必须带
 *     Cookie: {shareId}={token},否则 nginx 直接 400(空 body)——
 *     这是与 URL ?token= 相互独立的第二道校验,浏览器由分享页 JS
 *     自动写入 cookie,脚本/HttpURLConnection 必须手动补上。</b></li>
 * </ol>
 *
 * <p>签名算法(两个 key 同一前缀):MD5("_"连接[PREFIX, 请求路径, nonce(6位),
 * 时间戳(毫秒), MD5(请求体原文), key]) → header "authx: nonce=..&timestamp=..&sign=.."。
 * 请求体必须与参与签名的字符串逐字节一致。</p>
 *
 * <p><b>只写死分享链接,不写死内部目录</b>(用户要求):版本信息文件的位置靠运行时
 * 扫描发现 —— 先看分享根目录,再逐个进一层目录找。</p>
 *
 * <p><b>频道分离</b>(用户要求:调试版本与正式版本分开):</p>
 * <ul>
 * <li>正式频道:update.json(描述正式包,如 app-release.apk)</li>
 * <li>测试频道:update-debug.json(描述调试包,如 app-debug.apk)</li>
 * <li>正式包默认只收正式频道;调试包两个频道都收(能收到下一个版本的正式包);
 *     正式包打开"接收测试版更新"开关后也收测试频道。</li>
 * </ul>
 *
 * <p>交互流程(用户拍板:不自动检查,仅点击才检查):<br>
 * 设置页点"检查更新" → 扫描两个频道的版本信息 → 比对版本 → 有新版弹"发现新版本"
 * → 用户点"下载安装" → 下载 APK + sha256 校验 → 自动调起系统安装器。</p>
 *
 * <p>版本信息文件格式(用户上传到分享目录,两个文件格式相同):</p>
 * <pre>
 * {
 *   "versionCode": 100398,          // 可选,CI versionCode,装新包防降级
 *   "versionName": "5.7.398",       // 必填,与 git tag 一致,用于比对
 *   "fileName": "app-debug.apk",    // 必填,同目录下的 APK 文件名
 *   "sha256": "HEX64",              // 可选,APK 的 sha256,校验失败不安装
 *   "notes": "修复xxx"               // 可选,弹窗里展示
 * }
 * </pre>
 */
public final class UpdateChecker {

    private static final String TAG = "UpdateChecker";

    /** 正式频道版本信息文件名 */
    static final String META_RELEASE = "update.json";
    /** 测试频道版本信息文件名 */
    static final String META_DEBUG = "update-debug.json";

    private static final String PREFS = "update_checker";

    /** fnOS 外部分享链接(唯一写死的信息,app 只按此链接访问,只读权限) */
    private static final String SHARE_PAGE_URL = "https://share.fnnas.net/s/968b74e503034b9cb2";

    /** FN Connect 解析服务(http 明文实测可用,签名 key 与 NAS 侧不同) */
    private static final String CONNECT_BASE = "http://share.fnnas.net";
    private static final String PATH_FN_SHARE = "/api/v1/fn/share";

    /** authx 签名公共前缀与两个 key(FN Connect 页面 JS 逆向) */
    private static final String SIGN_PREFIX = "NDzZTVxnRKP8Z0jXg1VAMonaG8akvh";
    private static final String KEY_CONNECT = "zIGtkc3dqZnJpd29qZXJqa2w7c";
    private static final String KEY_SHARE = "814&d6470861a4cfbbb4fe2fd3f$6581f6";

    private static final int TIMEOUT_CONNECT_MS = 8000;
    private static final int TIMEOUT_READ_MS = 15000;

    /** 扫描 update.json 时最多进入的子目录数(防止异常目录结构拖死检查) */
    private static final int MAX_DIRS_TO_SCAN = 10;

    /** 下载的 APK 大小上限 64MB(防呆,正常包约 2MB) */
    private static final long MAX_APK_BYTES = 64L * 1024L * 1024L;

    private static final Random RANDOM = new Random();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private UpdateChecker() {
    }

    // ------------------------------------------------------------------
    // "接收测试版更新"开关(正式包默认收不到调试包;开关打开后才能收到)
    // ------------------------------------------------------------------

    public static boolean isAllowDebugUpdates(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean("allowDebugUpdates", false);
    }

    public static void setAllowDebugUpdates(Context ctx, boolean allow) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean("allowDebugUpdates", allow).commit();
    }

    /** 检查过程状态回调(全部回调在主线程),设置页用它在结果行里显示进度 */
    public interface StatusListener {
        void onStatus(String msg);
    }

    /**
     * 发现新版本回调(独立更新页面用):检查到新版本时不弹系统对话框,
     * 而是把信息交给页面自己渲染;传 null 保持旧的弹窗行为。
     * 回调在主线程。
     */
    public interface UpdateCallback {
        void onUpdateFound(UpdateInfo info, JSONObject apkEntry);
    }

    /** 下载进度回调(done/total 字节;total 可能为 -1 表示服务器未给长度)。主线程外,UI 侧自行 post */
    public interface ProgressListener {
        void onProgress(long done, long total);
    }

    /** 新版本信息(update.json 的投影) */
    public static class UpdateInfo {
        public String versionName;
        public long versionCode = -1;
        public String fileName;
        public String sha256;
        public String notes;
    }

    /**
     * 手动检查更新(设置页"检查更新"点击入口)。
     * 全程后台线程;状态经 listener 回主线程;发现新版本弹窗确认后才下载。
     */
    public static void check(final Context ctx, final StatusListener listener) {
        check(ctx, listener, null);
    }

    /** 同上,但发现新版本时优先走 callback(更新页面);callback 为 null 时弹系统对话框 */
    public static void check(final Context ctx, final StatusListener listener,
                             final UpdateCallback callback) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                runCheck(ctx, listener, callback);
            }
        }, "update-check").start();
    }

    // ------------------------------------------------------------------
    // 主流程(后台线程):检查 → 比对 → 弹窗确认 → 下载 → 校验 → 安装
    // ------------------------------------------------------------------

    private static void runCheck(final Context ctx, final StatusListener listener,
                                 final UpdateCallback callback) {
        try {
            final String shareId = extractShareId(SHARE_PAGE_URL);
            status(listener, "正在解析分享链接…");
            DownloadDiag.log("[自更新] 手动检查开始, shareId=" + shareId);

            // 1) 解析分享 → NAS 地址(局域网优先,中继兜底)
            String[] bases = resolveBases(shareId);
            if (bases == null || bases.length == 0) {
                fail(ctx, listener, "解析分享链接失败(已自动重试 3 次)\n原因: "
                        + (lastResolveError.isEmpty() ? "未知" : lastResolveError));
                return;
            }

            // 2) 逐个 base 取会话 token
            status(listener, "正在连接 NAS…");
            String base = null;
            String token = null;
            for (int i = 0; i < bases.length && token == null; i++) {
                try {
                    token = fetchShareToken(bases[i], shareId);
                    if (token != null) {
                        base = bases[i];
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "base 不可达: " + bases[i] + " : " + t);
                }
            }
            if (base == null) {
                fail(ctx, listener, "NAS 分享页不可达(局域网与中继都失败)");
                return;
            }
            DownloadDiag.log("[自更新] 分享页可达: " + base);

            // 3) 频道分离:正式包默认只看 update.json;调试包或开了"接收测试版更新"再加 update-debug.json
            java.util.List<String> metaNames = new java.util.ArrayList<String>();
            metaNames.add(META_RELEASE);
            if (BuildConfig.DEBUG || isAllowDebugUpdates(ctx)) {
                metaNames.add(META_DEBUG);
            }
            DownloadDiag.log("[自更新] 检查频道: " + metaNames
                    + " (调试包=" + BuildConfig.DEBUG + ", 接收测试版=" + isAllowDebugUpdates(ctx) + ")");

            // 4) 扫描版本信息(根目录 → 一层子目录)
            status(listener, "正在查找版本信息…");
            java.util.List<JSONObject> hits = findMetaCandidates(base, shareId, token, metaNames);
            if (hits.isEmpty()) {
                fail(ctx, listener, "分享目录里没有版本信息(" + metaNames + ")");
                return;
            }

            // 5) 逐个下载解析,在"比当前新"的候选里挑最新的一个
            PackageInfo pi = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
            String curName = pi.versionName;
            JSONObject bestHit = null;
            UpdateInfo info = null;
            int metaDlFail = 0;
            for (int i = 0; i < hits.size(); i++) {
                JSONObject hit = hits.get(i);
                String jsonText = downloadEntryToString(base, shareId, token, hit);
                if (jsonText == null) {
                    metaDlFail++;
                    Log.w(TAG, "版本信息下载失败,跳过: " + hit.optString("file"));
                    continue;
                }
                JSONObject meta = new JSONObject(jsonText);
                UpdateInfo cand = new UpdateInfo();
                cand.versionName = meta.optString("versionName", "").trim();
                cand.versionCode = meta.optLong("versionCode", -1L);
                cand.fileName = meta.optString("fileName", "").trim();
                cand.sha256 = meta.optString("sha256", "").trim();
                cand.notes = meta.optString("notes", "");
                if (cand.versionName.isEmpty() || cand.fileName.isEmpty()) {
                    Log.w(TAG, "版本信息缺 versionName/fileName,跳过: " + hit.optString("file"));
                    continue;
                }
                boolean newer = isNewer(cand.versionName, curName);
                boolean sameNameButNewerCode = (!isNewerOrOlder(cand.versionName, curName)
                        && cand.versionCode > 0 && cand.versionCode > pi.versionCode);
                if (!newer && !sameNameButNewerCode) {
                    continue;
                }
                if (betterCandidate(cand, info)) {
                    bestHit = hit;
                    info = cand;
                }
            }
            if (bestHit == null || info == null) {
                if (metaDlFail >= hits.size()) {
                    // 全部版本信息都下载失败(常见:旧版 app 没有 Cookie 头,服务端 400)
                    // 如实报错,而不是误报"已是最新版本"
                    fail(ctx, listener, "版本信息下载失败(共 " + hits.size()
                            + " 个),服务端拒绝;若本机版本较旧,请手动安装新 APK");
                    return;
                }
                status(listener, "已是最新版本 " + curName);
                DownloadDiag.log("[自更新] 已是最新(" + curName + "), 不提示");
                return;
            }
            DownloadDiag.log("[自更新] 选中更新: " + info.versionName
                    + " (code=" + info.versionCode + ") 文件=" + info.fileName);

            // 6) 在该版本信息同目录找 APK(先确认存在,再弹窗)
            JSONObject apkEntry = pickApkEntry(bestHit.optJSONArray("_listing"), info.fileName);
            if (apkEntry == null) {
                fail(ctx, listener, "分享目录里没有 APK(" + info.fileName + ")");
                return;
            }
            status(listener, "发现新版本 " + info.versionName);

            // 7) 回主线程交付结果:更新页面走 callback,旧路径弹系统对话框(inner class 引用需 final)
            final JSONObject apkEntryFinal = apkEntry;
            final UpdateInfo infoFinal = info;
            MAIN.post(new Runnable() {
                @Override
                public void run() {
                    if (callback != null) {
                        callback.onUpdateFound(infoFinal, apkEntryFinal);
                    } else {
                        promptUpdate(ctx, infoFinal, apkEntryFinal, listener);
                    }
                }
            });
        } catch (final Throwable t) {
            Log.w(TAG, "检查更新失败", t);
            DownloadDiag.logError("[自更新] 检查失败", t);
            fail(ctx, listener, "检查更新失败: " + t.getMessage());
        }
    }

    private static void fail(final Context ctx, final StatusListener listener, final String msg) {
        DownloadDiag.log("[自更新] " + msg);
        status(listener, msg);
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                try {
                    new AlertDialog.Builder(activityOrApp(ctx))
                            .setTitle("检查更新")
                            .setMessage(msg)
                            .setPositiveButton("知道了", null)
                            .show();
                } catch (Throwable ignored) {
                }
            }
        });
    }

    private static void status(final StatusListener listener, final String msg) {
        if (listener == null) {
            return;
        }
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                listener.onStatus(msg);
            }
        });
    }

    // ------------------------------------------------------------------
    // 弹窗与安装
    // ------------------------------------------------------------------

    private static void promptUpdate(final Context ctx, final UpdateInfo info,
                                     final JSONObject apkEntry, final StatusListener listener) {
        try {
            Context uiCtx = activityOrApp(ctx);
            if (!(uiCtx instanceof android.app.Activity)) {
                return;
            }
            if (((android.app.Activity) uiCtx).isFinishing()) {
                return;
            }
            StringBuilder msg = new StringBuilder();
            msg.append("发现新版本 ").append(info.versionName).append("\n");
            if (info.notes != null && !info.notes.trim().isEmpty()) {
                msg.append("\n").append(info.notes.trim()).append("\n");
            }
            msg.append("\n确认后开始下载并安装(下载后自动做 sha256 校验,防止坏包)。");
            new AlertDialog.Builder(uiCtx)
                    .setTitle("发现新版本")
                    .setMessage(msg.toString())
                    .setPositiveButton("下载安装", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            final Context app = ctx.getApplicationContext();
                            new Thread(new Runnable() {
                                @Override
                                public void run() {
                                    downloadAndInstall(app, info, apkEntry, listener, null);
                                }
                            }, "update-download").start();
                        }
                    })
                    .setNegativeButton("取消", null)
                    .show();
        } catch (Throwable t) {
            Log.w(TAG, "弹窗失败", t);
        }
    }

    /** 后台:下载 APK → sha256 校验 → 自动调起安装器(公开给更新页面复用) */
    public static void downloadAndInstall(Context ctx, UpdateInfo info,
                                          JSONObject apkEntry, StatusListener listener,
                                          ProgressListener progress) {
        try {
            String shareId = extractShareId(SHARE_PAGE_URL);
            status(listener, "正在下载 " + info.fileName + " …");

            // 安装器要读文件,下载前重新建立会话(与检查阶段同样的链路)
            String[] bases = resolveBases(shareId);
            if (bases == null || bases.length == 0) {
                fail(ctx, listener, "下载失败: 解析分享链接失败(已重试)\n原因: "
                        + (lastResolveError.isEmpty() ? "未知" : lastResolveError));
                return;
            }
            String base = null;
            String token = null;
            for (int i = 0; i < bases.length && token == null; i++) {
                try {
                    token = fetchShareToken(bases[i], shareId);
                    if (token != null) {
                        base = bases[i];
                    }
                } catch (Throwable ignored) {
                }
            }
            if (base == null) {
                fail(ctx, listener, "下载失败: NAS 分享页不可达");
                return;
            }

            File dest = prepareApkFile(ctx);
            String err = downloadEntryToFile(base, shareId, token, apkEntry, dest,
                    MAX_APK_BYTES, progress);
            if (err != null) {
                fail(ctx, listener, "APK 下载失败: " + err);
                return;
            }
            String actual = sha256(dest);
            if (info.sha256 != null && info.sha256.trim().length() == 64
                    && !info.sha256.trim().equalsIgnoreCase(actual)) {
                //noinspection ResultOfMethodCallIgnored
                dest.delete();
                DownloadDiag.log("[自更新] sha256 不匹配! 期望=" + info.sha256 + " 实际=" + actual);
                fail(ctx, listener, "APK 校验失败(sha256 不匹配),已删除坏包,请重新检查更新");
                return;
            }
            DownloadDiag.log("[自更新] APK 就绪: " + dest.getAbsolutePath() + " sha256=" + actual);
            status(listener, "校验通过,开始安装…");
            installApk(ctx, dest);
        } catch (Throwable t) {
            Log.w(TAG, "下载安装失败", t);
            DownloadDiag.logError("[自更新] 下载安装失败", t);
            fail(ctx, listener, "下载安装失败: " + t.getMessage());
        }
    }

    static void installApk(Context ctx, File apk) {
        try {
            if (!apk.exists()) {
                DownloadDiag.log("[自更新] 安装失败: 文件不存在 " + apk.getAbsolutePath());
                return;
            }
            Intent i = new Intent(Intent.ACTION_VIEW);
            Uri apkUri;
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                // 安卓 7.0+ 禁止 file:// URI 跨进程传递,必须用 FileProvider
                apkUri = androidx.core.content.FileProvider.getUriForFile(
                        ctx.getApplicationContext(),
                        ctx.getPackageName() + ".fileprovider", apk);
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } else {
                // 安卓 4.2.2 ~ 6.0 可直接用 file://(车机是 4.2.2,走这里)
                apkUri = Uri.fromFile(apk);
            }
            i.setDataAndType(apkUri, "application/vnd.android.package-archive");
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
            DownloadDiag.log("[自更新] 已调起安装器: " + apk.getAbsolutePath());
        } catch (Throwable t) {
            Log.w(TAG, "调起安装器失败", t);
            DownloadDiag.logError("[自更新] 调起安装器失败", t);
            // 【不再静默】车机 ROM 可能没有/锁了系统安装器(ActivityNotFoundException),
            // 必须告诉用户 APK 在哪、可手动安装,否则表现为"点了没反应"
            showMainThreadToast(ctx, "无法调起系统安装器(" + t.getClass().getSimpleName()
                    + ")\nAPK 已下载到:\n" + apk.getAbsolutePath()
                    + "\n可用文件管理器手动安装");
        }
    }

    /** 主线程 Toast(安装链路跑在后台线程,直接 Toast 会因无 Looper 崩溃) */
    private static void showMainThreadToast(final Context ctx, final String msg) {
        try {
            new android.os.Handler(android.os.Looper.getMainLooper()).post(new Runnable() {
                @Override
                public void run() {
                    try {
                        Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show();
                    } catch (Throwable ignored) {
                    }
                }
            });
        } catch (Throwable ignored) {
        }
    }

    /** 有 Activity 用 Activity(弹窗挂它上面),否则用 applicationContext */
    private static Context activityOrApp(Context ctx) {
        return (ctx instanceof android.app.Activity) ? ctx : ctx.getApplicationContext();
    }

    private static File prepareApkFile(Context ctx) {
        File dir;
        try {
            dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            if (dir != null && !dir.exists()) {
                //noinspection ResultOfMethodCallIgnored
                dir.mkdirs();
            }
        } catch (Throwable t) {
            dir = null;
        }
        if (dir == null || !dir.canWrite()) {
            dir = ctx.getExternalFilesDir(null);
        }
        if (dir == null) {
            dir = ctx.getCacheDir();
        }
        return new File(dir, "update.apk");
    }

    // ------------------------------------------------------------------
    // 第 1 步:解析分享 → 候选 base 列表
    // ------------------------------------------------------------------

    /** 最近一次 resolve 失败的真实原因(给失败对话框看,替代干巴巴的"不可达?") */
    private static volatile String lastResolveError = "";

    /**
     * 返回候选 base(http://ip:5666 优先,https://中继域名 兜底),失败返回 null。
     * 【2026-10-04 自动重试】车机实测(23:46)share.fnnas.net 的 resolve 链路
     * 时好时坏:第一次 EOFException 秒失败,40 秒后重试即成功。这里失败自动
     * 重试 2 次(间隔 2s),避免用户手动多点几次"检查更新"。
     */
    private static String[] resolveBases(String shareId) {
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                String body = "{\"shareId\":\"" + shareId + "\"}";
                String resp = postJson(CONNECT_BASE + PATH_FN_SHARE, body, KEY_CONNECT, null);
                JSONObject root = new JSONObject(resp);
                if (root.optInt("code", -1) != 0) {
                    Log.w(TAG, "fn/share code=" + root.optInt("code") + " " + root.optString("msg"));
                    lastResolveError = "服务端返回 code=" + root.optInt("code")
                            + " " + root.optString("msg");
                    return null;
                }
                JSONObject data = root.optJSONObject("data");
                if (data == null) {
                    lastResolveError = "服务端应答缺少 data";
                    return null;
                }
                java.util.List<String> out = new java.util.ArrayList<String>();
                String ip = firstOf(data.optJSONArray("ipv4"));
                String fn = firstOf(data.optJSONArray("fn"));
                int httpPort = data.optJSONObject("port") != null
                        ? data.optJSONObject("port").optInt("httpPort", 5666) : 5666;
                if (ip != null && !ip.isEmpty()) {
                    out.add("http://" + ip + ":" + httpPort);
                }
                if (fn != null && !fn.isEmpty()) {
                    out.add("https://" + fn);
                }
                DownloadDiag.log("[自更新] 候选地址: " + out);
                return out.toArray(new String[0]);
            } catch (Throwable t) {
                lastResolveError = t.getClass().getSimpleName()
                        + (t.getMessage() != null ? ": " + t.getMessage() : "");
                Log.w(TAG, "resolveBases 第" + attempt + "次失败: " + lastResolveError, t);
                DownloadDiag.log("[自更新] resolve 第" + attempt + "次失败: " + lastResolveError);
                if (attempt < 3) {
                    try {
                        Thread.sleep(2000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return null;
                    }
                }
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // 第 2 步:分享页 → 会话 token
    // ------------------------------------------------------------------

    private static String fetchShareToken(String base, String shareId) throws Exception {
        String html = getString(base + "/s/" + shareId, null);
        int key = html.indexOf("share-data");
        if (key < 0) {
            return null;
        }
        int start = html.indexOf('{', key);
        int end = html.indexOf("</script>", start);
        if (start < 0 || end < 0) {
            return null;
        }
        JSONObject data = new JSONObject(html.substring(start, end)).optJSONObject("data");
        return (data != null) ? data.optString("token", null) : null;
    }

    // ------------------------------------------------------------------
    // 第 3 步:扫描版本信息文件(频道: update.json / update-debug.json)
    // ------------------------------------------------------------------

    /** 扫描根目录和一层子目录,收集所有频道命中的版本信息条目(各附 "_listing") */
    private static java.util.List<JSONObject> findMetaCandidates(String base, String shareId,
                                                                 String token,
                                                                 java.util.List<String> metaNames) {
        java.util.List<JSONObject> out = new java.util.ArrayList<JSONObject>();
        try {
            JSONObject root = listDir(base, shareId, token, "/", -1);
            JSONArray files = (root != null) ? root.optJSONArray("files") : null;
            if (files != null) {
                collectMetaHits(files, "", metaNames, out);
                // 逐个子目录找(最多 MAX_DIRS_TO_SCAN 个)
                int scanned = 0;
                for (int i = 0; i < files.length() && scanned < MAX_DIRS_TO_SCAN; i++) {
                    JSONObject f = files.optJSONObject(i);
                    if (f == null || f.optInt("isDir", 0) != 1) {
                        continue;
                    }
                    scanned++;
                    String name = f.optString("file", "");
                    if (name.isEmpty()) {
                        continue;
                    }
                    String dirPath = entryPath(f);
                    JSONObject sub = listDir(base, shareId, token, dirPath, f.optInt("fileId", -1));
                    collectMetaHits(sub == null ? null : sub.optJSONArray("files"), dirPath, metaNames, out);
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "findMetaCandidates 失败", t);
        }
        return out;
    }

    /** files 里找频道版本信息文件,命中则克隆条目并附 "_dirPath"/"_listing" 追加到 out */
    private static void collectMetaHits(JSONArray files, String dirPath,
                                        java.util.List<String> metaNames,
                                        java.util.List<JSONObject> out) {
        if (files == null) {
            return;
        }
        for (int i = 0; i < files.length(); i++) {
            JSONObject f = files.optJSONObject(i);
            if (f == null || f.optInt("isDir", 0) != 0) {
                continue;
            }
            String name = f.optString("file", "");
            boolean match = false;
            for (int m = 0; m < metaNames.size(); m++) {
                if (metaNames.get(m).equalsIgnoreCase(name)) {
                    match = true;
                    break;
                }
            }
            if (!match) {
                continue;
            }
            JSONObject clone = new JSONObject();
            java.util.Iterator<String> it = f.keys();
            while (it.hasNext()) {
                String k = it.next();
                put(clone, k, f.opt(k));
            }
            put(clone, "_dirPath", dirPath);
            put(clone, "_listing", files);
            out.add(clone);
        }
    }

    /** 候选择优:版本三段更大者优先,同版本比 versionCode;b 为 null(尚无候选)时 a 直接胜出 */
    private static boolean betterCandidate(UpdateInfo a, UpdateInfo b) {
        if (b == null) {
            return true;
        }
        int[] va = parseVersion(a.versionName);
        int[] vb = parseVersion(b.versionName);
        for (int i = 0; i < 3; i++) {
            if (va[i] != vb[i]) {
                return va[i] > vb[i];
            }
        }
        return a.versionCode > b.versionCode;
    }

    private static JSONObject put(JSONObject o, String k, Object v) {
        try {
            o.put(k, v);
        } catch (Throwable ignored) {
        }
        return o;
    }

    /** 在 update.json 所在目录清单里挑 APK:优先精确匹配 fileName,否则取最新的 *.apk */
    static JSONObject pickApkEntry(JSONArray files, String preferName) {
        if (files == null) {
            return null;
        }
        JSONObject newest = null;
        long newestTime = -1L;
        for (int i = 0; i < files.length(); i++) {
            JSONObject f = files.optJSONObject(i);
            if (f == null || f.optInt("isDir", 0) != 0) {
                continue;
            }
            String name = f.optString("file", "");
            if (name.toLowerCase(java.util.Locale.US).endsWith(".apk")) {
                if (preferName != null && !preferName.trim().isEmpty()
                        && preferName.equalsIgnoreCase(name)) {
                    return f;
                }
                long mt = f.optLong("modTime", 0L);
                if (mt > newestTime) {
                    newestTime = mt;
                    newest = f;
                }
            }
        }
        return newest;
    }

    private static JSONObject listDir(String base, String shareId, String token,
                                      String path, int fileId) {
        try {
            String body;
            if (fileId >= 0) {
                body = "{\"shareId\":\"" + shareId + "\",\"path\":\"" + jsonEscape(path)
                        + "\",\"fileId\":" + fileId + "}";
            } else {
                body = "{\"shareId\":\"" + shareId + "\",\"path\":\"" + jsonEscape(path) + "\"}";
            }
            String resp = postJson(base + "/s/" + shareId + "/api/v1/share/list",
                    body, KEY_SHARE, token);
            JSONObject root = new JSONObject(resp);
            return root.optInt("code", -1) == 0 ? root.optJSONObject("data") : null;
        } catch (Throwable t) {
            Log.w(TAG, "listDir 失败: " + path, t);
            return null;
        }
    }

    // ------------------------------------------------------------------
    // 第 4 步:下载(POST download 拿 URL → GET 拉流)
    // ------------------------------------------------------------------

    /** 调 download 接口拿最终下载 URL(相对路径自动按 base 补全) */
    private static String resolveDownloadUrl(String base, String shareId, String token,
                                             JSONObject entry) throws Exception {
        String filePath = entryPath(entry);
        long fileId = entry.optLong("fileId", -1L);
        String name = entry.optString("file", "file");
        String body = "{\"files\":[{\"path\":\"" + jsonEscape(filePath)
                + "\",\"fileId\":" + fileId + "}],\"shareId\":\"" + shareId
                + "\",\"downloadFilename\":\"" + jsonEscape(name) + "\"}";
        String resp = postJson(base + "/s/" + shareId + "/api/v1/share/download",
                body, KEY_SHARE, token);
        JSONObject root = new JSONObject(resp);
        int code = root.optInt("code", -1);
        if (code != 0) {
            throw new IllegalStateException("download code=" + code + " " + root.optString("msg"));
        }
        String url = root.getJSONObject("data").optString("path", "");
        if (url == null || url.isEmpty()) {
            throw new IllegalStateException("download 返回空 path");
        }
        if (url.startsWith("/")) {
            // 相对路径 → 拼 base 的 origin
            URL u = new URL(base);
            url = u.getProtocol() + "://" + u.getHost()
                    + (u.getPort() > 0 ? ":" + u.getPort() : "") + url;
        }
        return url;
    }

    private static String downloadEntryToString(String base, String shareId, String token,
                                                JSONObject entry) {
        try {
            String url = resolveDownloadUrl(base, shareId, token, entry);
            // 【关键】fnOS 下载端点要求双重校验:URL ?token= 之外还必须带
            // Cookie: <shareId>=<token>(浏览器由分享页 JS 写入,脚本必须手动补)
            return getStringWithCookie(url, shareId + "=" + token);
        } catch (Throwable t) {
            Log.w(TAG, "downloadEntryToString 失败", t);
            return null;
        }
    }

    /** 下载到文件(带大小上限与进度回调);成功返回 null,失败返回原因 */
    private static String downloadEntryToFile(String base, String shareId, String token,
                                              JSONObject entry, File dest, long maxBytes,
                                              ProgressListener progress) {
        HttpURLConnection conn = null;
        InputStream in = null;
        FileOutputStream fos = null;
        try {
            String url = resolveDownloadUrl(base, shareId, token, entry);
            conn = TlsCompat.open(url);
            conn.setConnectTimeout(TIMEOUT_CONNECT_MS);
            conn.setReadTimeout(60000);
            conn.setRequestMethod("GET");
            // 【关键】fnOS 下载端点双重校验:必须带 Cookie: <shareId>=<token>,
            // 否则 nginx 直接 400(空 body),与 URL 里的 ?token= 是两道独立检查
            conn.setRequestProperty("Cookie", shareId + "=" + token);
            int code = conn.getResponseCode();
            if (code != 200) {
                return "HTTP " + code;
            }
            long total = conn.getContentLength();
            in = conn.getInputStream();
            fos = new FileOutputStream(dest);
            byte[] buf = new byte[8192];
            long done = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                done += n;
                if (done > maxBytes) {
                    return "文件超过大小上限(" + maxBytes + ")";
                }
                fos.write(buf, 0, n);
                if (progress != null) {
                    progress.onProgress(done, total);
                }
            }
            fos.flush();
            if (total > 0 && done != total) {
                return "下载不完整: " + done + "/" + total;
            }
            DownloadDiag.log("[自更新] 下载完成: " + dest.getName() + " " + done + " 字节");
            return null;
        } catch (Throwable t) {
            Log.w(TAG, "downloadEntryToFile 失败", t);
            return t.getClass().getSimpleName() + ": " + t.getMessage();
        } finally {
            closeQuietly(fos);
            closeQuietly(in);
            if (conn != null) {
                try {
                    conn.disconnect();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // 版本比对
    // ------------------------------------------------------------------

    /** "5.7.398" / "v5.7.398" / "5.7.398.dev2(abc)" → [5,7,398] */
    static int[] parseVersion(String v) {
        int[] out = {0, 0, 0};
        if (v == null) {
            return out;
        }
        String s = v.trim();
        if (s.startsWith("v") || s.startsWith("V")) {
            s = s.substring(1);
        }
        String[] parts = s.split("\\.");
        for (int i = 0; i < 3 && i < parts.length; i++) {
            StringBuilder num = new StringBuilder();
            String p = parts[i];
            for (int j = 0; j < p.length(); j++) {
                char c = p.charAt(j);
                if (Character.isDigit(c)) {
                    num.append(c);
                } else {
                    break;
                }
            }
            if (num.length() > 0) {
                try {
                    out[i] = Integer.parseInt(num.toString());
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return out;
    }

    static boolean isNewer(String update, String current) {
        int[] u = parseVersion(update);
        int[] c = parseVersion(current);
        for (int i = 0; i < 3; i++) {
            if (u[i] != c[i]) {
                return u[i] > c[i];
            }
        }
        return false;
    }

    private static boolean isNewerOrOlder(String update, String current) {
        return isNewer(update, current) || isNewer(current, update);
    }

    // ------------------------------------------------------------------
    // HTTP 工具
    // ------------------------------------------------------------------

    private static String postJson(String url, String body, String signKey, String authHeader) throws Exception {
        HttpURLConnection conn = TlsCompat.open(url);
        try {
            conn.setConnectTimeout(TIMEOUT_CONNECT_MS);
            conn.setReadTimeout(TIMEOUT_READ_MS);
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            byte[] payload = body.getBytes("UTF-8");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("authx", authx(new URL(url).getPath(), body, signKey));
            if (authHeader != null) {
                conn.setRequestProperty("Auth", authHeader);
            }
            conn.setFixedLengthStreamingMode(payload.length);
            java.io.OutputStream os = conn.getOutputStream();
            os.write(payload);
            os.flush();
            os.close();
            int code = conn.getResponseCode();
            String text = readStream(code >= 400 ? conn.getErrorStream() : conn.getInputStream());
            if (code >= 400) {
                throw new IllegalStateException("HTTP " + code + " " + text);
            }
            return text;
        } finally {
            try {
                conn.disconnect();
            } catch (Throwable ignored) {
            }
        }
    }

    private static String getString(String url, String authHeader) throws Exception {
        HttpURLConnection conn = TlsCompat.open(url);
        try {
            conn.setConnectTimeout(TIMEOUT_CONNECT_MS);
            conn.setReadTimeout(TIMEOUT_READ_MS);
            conn.setRequestMethod("GET");
            if (authHeader != null) {
                conn.setRequestProperty("Auth", authHeader);
            }
            int code = conn.getResponseCode();
            String text = readStream(code >= 400 ? conn.getErrorStream() : conn.getInputStream());
            if (code >= 400) {
                throw new IllegalStateException("HTTP " + code);
            }
            return text;
        } finally {
            try {
                conn.disconnect();
            } catch (Throwable ignored) {
            }
        }
    }

    /** GET + 自定义 Cookie(fnOS 下载端点要求 Cookie: shareId=token) */
    private static String getStringWithCookie(String url, String cookie) throws Exception {
        HttpURLConnection conn = TlsCompat.open(url);
        try {
            conn.setConnectTimeout(TIMEOUT_CONNECT_MS);
            conn.setReadTimeout(TIMEOUT_READ_MS);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Cookie", cookie);
            int code = conn.getResponseCode();
            String text = readStream(code >= 400 ? conn.getErrorStream() : conn.getInputStream());
            if (code >= 400) {
                throw new IllegalStateException("HTTP " + code);
            }
            return text;
        } finally {
            try {
                conn.disconnect();
            } catch (Throwable ignored) {
            }
        }
    }

    private static String readStream(InputStream is) throws Exception {
        if (is == null) {
            return "";
        }
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) > 0) {
            bos.write(buf, 0, n);
        }
        is.close();
        return new String(bos.toByteArray(), "UTF-8");
    }

    private static void closeQuietly(java.io.Closeable c) {
        if (c != null) {
            try {
                c.close();
            } catch (Throwable ignored) {
            }
        }
    }

    // ------------------------------------------------------------------
    // 签名与杂项
    // ------------------------------------------------------------------

    /**
     * authx 签名头。
     * sign = MD5(PREFIX_url_nonce_ts_md5(body)_key 五段下划线连接),
     * body 必须与实际发送的请求体逐字节一致。
     */
    static String authx(String urlPath, String body, String key) {
        String nonce = String.format(java.util.Locale.US, "%06d", 100000 + RANDOM.nextInt(900000));
        String ts = String.valueOf(System.currentTimeMillis());
        String sign = md5Hex(SIGN_PREFIX + "_" + urlPath + "_" + nonce + "_" + ts
                + "_" + md5Hex(body) + "_" + key);
        return "nonce=" + nonce + "&timestamp=" + ts + "&sign=" + sign;
    }

    static String md5Hex(String s) {
        try {
            return hex(MessageDigest.getInstance("MD5").digest(s.getBytes("UTF-8")));
        } catch (Throwable e) {
            // MD5/UTF-8 都是 JVM 必备,理论不可达;兜底返回空让服务端拒绝而不是崩溃
            return "";
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (int i = 0; i < bytes.length; i++) {
            sb.append(Character.forDigit((bytes[i] >> 4) & 0xF, 16));
            sb.append(Character.forDigit(bytes[i] & 0xF, 16));
        }
        return sb.toString();
    }

    static String sha256(File f) throws Exception {
        InputStream in = null;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            in = new java.io.FileInputStream(f);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                digest.update(buf, 0, n);
            }
            return hex(digest.digest());
        } finally {
            closeQuietly(in);
        }
    }

    private static String extractShareId(String shareUrl) {
        String s = shareUrl;
        int q = s.indexOf('?');
        if (q >= 0) {
            s = s.substring(0, q);
        }
        int slash = s.lastIndexOf('/');
        return (slash >= 0) ? s.substring(slash + 1) : s;
    }

    /** entry 在分享内的完整路径(与官方 UI 公式一致: entry.path + "/" + entry.file) */
    private static String entryPath(JSONObject entry) {
        String p = entry.optString("path", "");
        String name = entry.optString("file", "");
        if (p == null || p.isEmpty()) {
            return "/" + name;
        }
        return (p.endsWith("/") ? p : p + "/") + name;
    }

    private static String firstOf(JSONArray arr) {
        if (arr != null && arr.length() > 0) {
            String v = arr.optString(0, null);
            return (v == null || v.equals("null")) ? null : v;
        }
        return null;
    }

    private static String jsonEscape(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' || c == '\\') {
                sb.append('\\').append(c);
            } else if (c < 0x20) {
                sb.append(String.format(java.util.Locale.US, "\\u%04x", (int) c));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
