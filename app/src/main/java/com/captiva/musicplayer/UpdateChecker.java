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

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.Socket;
import java.net.URL;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.Provider;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

/**
 * 自更新:从 TeraCloud 公开分享目录取版本信息与 APK。
 *
 * <p><b>为什么换成 TeraCloud 公开分享(2026-10-23)</b></p>
 * <p>原来的飞牛(fnOS)分享链路要在 app 里硬编码 NAS 地址、并逆向飞牛的
 * 签名协议才能列目录,共 1160 行且链路时好时坏。TeraCloud 的公开分享
 * 只需要一个分享 ID,两步拿到文件:</p>
 * <ol>
 * <li>{@code POST https://yura.teracloud.jp/v2/api/share/public/<id>}
 *     → 响应头 {@code Set-Cookie: tcs_ticket=xxxx; Path=/ds/dav/; Max-Age=3600}</li>
 * <li>{@code GET https://yura.teracloud.jp/ds/dav/<id>/<文件名>}
 *     带上一步的 cookie → 文件内容</li>
 * </ol>
 * <p>票据是"公开分享"的匿名下载凭证,<b>不含任何账号/密码</b>,1 小时过期。
 * 上传仍然走需要账号的 WebDAV({@code /dav/apk},见 cli/webdav_upload.py),
 * 凭证存在 Windows DPAPI 加密的 cli/.webdav_cred 里,既不进 APK 也不进仓库。
 * 因此就算把 APK 反编译,也拿不到任何能改动机上文件的凭据 —— 分享是只读的。</p>
 *
 * <p>版本信息文件由 cli 上传,两个频道格式相同:</p>
 * <pre>
 * {
 *   "versionCode": 100398,          // 可选,CI versionCode,装新包防降级
 *   "versionName": "6.2",           // 必填,用于比对
 *   "fileName": "app-release.apk",  // 必填,同目录下的 APK 文件名
 *   "sha256": "HEX64",              // 可选,APK 的 sha256,校验失败不安装
 *   "notes": "修复xxx"               // 可选,弹窗里展示
 * }
 * </pre>
 *
 * <p><b>频道分离</b>(调试版本与正式版本分开):</p>
 * <ul>
 * <li>正式频道:update.json(描述正式包 app-release.apk)</li>
 * <li>测试频道:update-debug.json(描述调试包 app-debug.apk)</li>
 * <li>正式包默认只收正式频道;调试包两个频道都收;正式包打开"接收测试版更新"后也收测试频道。</li>
 * </ul>
 *
 * <p><b>车机(安卓 4.2.2)的两个必踩的坑</b>,都在 {@link UpdateSsl} 里解决:</p>
 * <ol>
 * <li><b>JSSE 里根本没有 TLS 1.2</b>(TeraCloud 只接受 TLS 1.2,TLS 1.0/1.1 直接握手失败)。
 *     官方说"API 16 起支持、API 20 起默认启用",但 4.2/4.3 实测
 *     {@code getSupportedProtocols()} 只有 SSLv3/TLSv1,开不出来
 *     → API 21 以下自带 Conscrypt(BoringSSL)这套 TLS 栈;</li>
 * <li>系统根证书太旧,没有 2025 年的 GlobalSign R46
 *     → 内置 TeraCloud 证书链(res/raw/teracloud_ca.pem)作为兜底信任锚,
 *       系统信任优先、校验不过才用内置证书,<b>不做无条件信任</b>。</li>
 * </ol>
 */
public final class UpdateChecker {

    private static final String TAG = "UpdateChecker";

    /** 正式频道版本信息文件名 */
    static final String META_RELEASE = "update.json";
    /** 测试频道版本信息文件名 */
    static final String META_DEBUG = "update-debug.json";

    private static final String PREFS = "update_checker";

    /** TeraCloud 站点(唯一写死的信息,公开只读分享,app 里没有任何账号/密码) */
    private static final String HOST = "https://yura.teracloud.jp";
    /** 公开分享 ID */
    private static final String SHARE_ID = "1152388ab50a47a8";
    /** 换票据:POST 它,响应头里拿 Set-Cookie */
    private static final String PATH_TICKET = "/v2/api/share/public/";
    /** 取文件:GET 它并带上票据 cookie */
    private static final String PATH_FILE = "/ds/dav/";
    /** 票据 cookie 名 */
    private static final String TICKET_COOKIE = "tcs_ticket=";

    private static final int TIMEOUT_CONNECT_MS = 8000;
    private static final int TIMEOUT_READ_MS = 20000;
    /** 换票据最多试几次(每次失败都会消耗掉连接池里的一条死连接) */
    private static final int TICKET_TRIES = 3;

    /** 版本信息文件上限 64KB(正常不到 1KB,防呆) */
    private static final int MAX_META_BYTES = 64 * 1024;
    /** 下载的 APK 上限 64MB(防呆,正常包约 2MB) */
    private static final long MAX_APK_BYTES = 64L * 1024L * 1024L;

    /** 取票据的 POST 是 0 字节体(TeraCloud 只认 POST + 空体,GET 会被拒) */
    private static final byte[] EMPTY_BODY = new byte[0];

    /** 版本信息读取结果:成功 / 文件不存在(没上传)/ 读取或解析失败 */
    private static final int META_OK = 0;
    private static final int META_MISSING = 1;
    private static final int META_FAILED = 2;

    /** 更新链路锁死的 TLS 版本(TeraCloud 只接受 1.2,见 UpdateSsl 的说明) */
    private static final String TLS_12 = "TLSv1.2";

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

    /** 检查过程状态回调(全部回调在主线程),更新页面用它在状态行里显示进度 */
    public interface StatusListener {
        void onStatus(String msg);
    }

    /** 发现新版本回调(更新页面自己渲染,不弹系统对话框);回调在主线程 */
    public interface UpdateCallback {
        void onUpdateFound(UpdateInfo info);
    }

    /** 下载进度回调(done/total 字节;total 为 -1 表示服务器未给长度)。主线程外,UI 侧自行 post */
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

    // ------------------------------------------------------------------
    // 入口
    // ------------------------------------------------------------------

    /** 手动检查更新(更新页面"检查更新"点击 + 进入页面自动检查) */
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
    // 主流程(后台线程):取票据 → 读版本信息 → 比对 → 交付 → (下载 → 校验 → 安装)
    // ------------------------------------------------------------------

    private static void runCheck(final Context ctx, final StatusListener listener,
                                 final UpdateCallback callback) {
        try {
            status(listener, "正在连接更新服务器…");
            DownloadDiag.log("[自更新] 检查开始, 分享=" + SHARE_ID);

            // 1) 换匿名下载票据(1 小时有效)
            String ticket = obtainTicket(ctx);
            if (ticket == null) {
                fail(ctx, listener, "无法连接更新服务器(拿不到下载票据)");
                return;
            }

            // 2) 频道分离:正式包默认只看 update.json;调试包或开了"接收测试版更新"再加测试频道
            List<String> metaNames = new ArrayList<String>();
            metaNames.add(META_RELEASE);
            if (BuildConfig.DEBUG || isAllowDebugUpdates(ctx)) {
                metaNames.add(META_DEBUG);
            }
            DownloadDiag.log("[自更新] 检查频道: " + metaNames
                    + " (调试包=" + BuildConfig.DEBUG
                    + ", 接收测试版=" + isAllowDebugUpdates(ctx) + ")");

            // 3) 逐个下载解析,在"比当前新"的候选里挑最新的一个
            PackageInfo pi = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
            String curName = pi.versionName;
            status(listener, "正在读取版本信息…");
            UpdateInfo best = null;
            int readable = 0;
            int missing = 0;
            for (int i = 0; i < metaNames.size(); i++) {
                JSONObject[] out = new JSONObject[1];
                int state = fetchMeta(ctx, ticket, metaNames.get(i), out);
                if (state == META_MISSING) {
                    missing++;
                    continue;
                }
                if (state != META_OK) {
                    continue;
                }
                readable++;
                JSONObject meta = out[0];
                UpdateInfo cand = new UpdateInfo();
                cand.versionName = meta.optString("versionName", "").trim();
                cand.versionCode = meta.optLong("versionCode", -1L);
                cand.fileName = meta.optString("fileName", "").trim();
                cand.sha256 = meta.optString("sha256", "").trim();
                cand.notes = meta.optString("notes", "");
                if (cand.versionName.isEmpty() || cand.fileName.isEmpty()) {
                    DownloadDiag.log("[自更新] 版本信息缺 versionName/fileName, 跳过: "
                            + metaNames.get(i));
                    continue;
                }
                boolean newer = isNewer(cand.versionName, curName);
                boolean sameNameButNewerCode = (!isNewerOrOlder(cand.versionName, curName)
                        && cand.versionCode > 0 && cand.versionCode > pi.versionCode);
                if (!newer && !sameNameButNewerCode) {
                    continue;
                }
                if (betterCandidate(cand, best)) {
                    best = cand;
                }
            }
            if (best == null) {
                // 一个频道都没读到内容时,要区分"文件根本没上传"和"上传了但读不出来",
                // 两种情况的下一步动作完全不同(前者去上传,后者查网络/权限)
                if (readable == 0) {
                    if (missing == metaNames.size()) {
                        fail(ctx, listener, "分享目录里没有版本信息(" + metaNames
                                + "),请先用 mp-upload 上传");
                    } else {
                        fail(ctx, listener, "版本信息读取失败(共 "
                                + metaNames.size() + " 个频道)");
                    }
                    return;
                }
                status(listener, "已是最新版本 " + curName);
                DownloadDiag.log("[自更新] 已是最新(" + curName + "), 不提示");
                return;
            }
            DownloadDiag.log("[自更新] 选中更新: " + best.versionName
                    + " (code=" + best.versionCode + ") 文件=" + best.fileName);

            // 4) 先确认 APK 真的存在再提示(HEAD 很轻,避免用户点了才发现 404)
            status(listener, "正在确认安装包…");
            if (!apkExists(ctx, ticket, best.fileName)) {
                fail(ctx, listener, "分享目录里没有 APK(" + best.fileName
                        + "),请检查是否已上传");
                return;
            }

            status(listener, "发现新版本 " + best.versionName);

            // 5) 回主线程交付结果:更新页面走 callback,旧路径弹系统对话框
            //    票据不随结果传递 —— 用户可能在弹窗前停留很久,
            //    下载阶段会自己换一张新票据,避免 1 小时过期导致下载 401
            final UpdateInfo infoFinal = best;
            MAIN.post(new Runnable() {
                @Override
                public void run() {
                    if (callback != null) {
                        callback.onUpdateFound(infoFinal);
                    } else {
                        promptUpdate(ctx, infoFinal, listener);
                    }
                }
            });
        } catch (final Throwable t) {
            Log.w(TAG, "检查更新失败", t);
            DownloadDiag.logError("[自更新] 检查失败", t);
            fail(ctx, listener, "检查更新失败: " + t.getMessage());
        }
    }

    /**
     * 下载 APK → sha256 校验 → 自动调起安装器(更新页面复用)。
     *
     * <p>本方法自己开后台线程执行,调用方无需(也不应)再包线程 ——
     * 安卓主线程网络请求一律抛 NetworkOnMainThreadException。
     * status/fail 内部已 MAIN.post 回主线程;ProgressListener 由 UI 侧自行 post。</p>
     */
    public static void downloadAndInstall(final Context ctx, final UpdateInfo info,
                                          final StatusListener listener,
                                          final ProgressListener progress) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                downloadAndInstallBg(ctx, info, listener, progress);
            }
        }, "update-download").start();
    }

    /** downloadAndInstall 的同步实现,仅在后台线程调用 */
    private static void downloadAndInstallBg(Context ctx, UpdateInfo info,
                                             StatusListener listener,
                                             ProgressListener progress) {
        try {
            status(listener, "正在下载 " + info.fileName + " …");

            // 现换一张票据(用户在"发现新版本"页面停留多久都不会过期)
            File dest = prepareApkFile(ctx);
            String err = null;
            for (int attempt = 1; attempt <= 2; attempt++) {
                String ticket = obtainTicket(ctx);
                if (ticket == null) {
                    err = "拿不到下载票据";
                    break;
                }
                err = downloadToFile(ctx, ticket, info.fileName, dest, MAX_APK_BYTES, progress);
                if (err == null) {
                    break;
                }
                DownloadDiag.log("[自更新] 第 " + attempt + " 次下载失败(" + err + ")");
                if (attempt < 2) {
                    status(listener, "下载中断,正在重试…");
                }
            }
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
            DownloadDiag.log("[自更新] APK 就绪: " + dest.getAbsolutePath()
                    + " sha256=" + actual);
            status(listener, "校验通过,开始安装…");
            installApk(ctx, dest);
        } catch (Throwable t) {
            Log.w(TAG, "下载安装失败", t);
            DownloadDiag.logError("[自更新] 下载安装失败", t);
            fail(ctx, listener, "下载安装失败: " + t.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // 弹窗与安装
    // ------------------------------------------------------------------

    private static void promptUpdate(final Context ctx, final UpdateInfo info,
                                     final StatusListener listener) {
        try {
            Context uiCtx = (ctx instanceof android.app.Activity)
                    ? ctx : ctx.getApplicationContext();
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
                            downloadAndInstall(ctx.getApplicationContext(), info, listener, null);
                        }
                    })
                    .setNegativeButton("取消", null)
                    .show();
        } catch (Throwable t) {
            Log.w(TAG, "弹窗失败", t);
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
            new Handler(Looper.getMainLooper()).post(new Runnable() {
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
    // TeraCloud 传输层
    // ------------------------------------------------------------------

    /**
     * 换一张匿名下载票据(内含重试)。
     *
     * <p>【2026-10-23 实测根因】4.2.2 模拟器和车机上都是同一种表现:
     * 「检查更新」一路顺畅,一点「下载安装」就报"拿不到下载票据",
     * 抛 EOFException 卡在 readResponseHeaders。原因是 HttpURLConnection 的
     * 连接池留着服务端已关掉的 keep-alive 连接:服务端是 Apache,空闲几秒就 FIN,
     * 且默认不在响应里回 Connection: close,libcore 便照常回收。
     * 池里有死连接时,GET 能被 HttpEngine 自动重放(所以检查阶段毫无异常),
     * 而 <b>带体的 POST 无法自动重放</b>,就直接 EOF 了。</p>
     *
     * <p>两道保险:① UpdateSsl.open 全链路带 Connection: close,让连接压根不进池;
     * ② 万一还有残留的死连接,换票据最多试 {@link #TICKET_TRIES} 次 ——
     * 每次失败都会消耗掉池里的一条,下一次就是新连接了。
     * 车机网络本就不稳,重试顺带也覆盖了偶发的握手/RST。</p>
     *
     * 失败返回 null(原因写进 DownloadDiag)。
     */
    private static String obtainTicket(Context ctx) {
        for (int i = 1; i <= TICKET_TRIES; i++) {
            String t = obtainTicketOnce(ctx);
            if (t != null) {
                return t;
            }
            DownloadDiag.log("[自更新] 换票据第 " + i + " 次失败"
                    + (i < TICKET_TRIES ? ", 换新连接重试" : ", 已用尽次数"));
        }
        return null;
    }

    /** 换票据的单次尝试。POST 分享 API(空体),服务端在 Set-Cookie 里下发
     * {@code tcs_ticket=xxxx; Path=/ds/dav/; Max-Age=3600}。失败返回 null。 */
    private static String obtainTicketOnce(Context ctx) {
        HttpURLConnection conn = null;
        try {
            conn = UpdateSsl.open(ctx, HOST + PATH_TICKET + SHARE_ID);
            conn.setConnectTimeout(TIMEOUT_CONNECT_MS);
            conn.setReadTimeout(TIMEOUT_READ_MS);
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            conn.setFixedLengthStreamingMode(0);
            OutputStream os = conn.getOutputStream();
            os.write(EMPTY_BODY);
            os.flush();
            os.close();
            int code = conn.getResponseCode();
            if (code >= 400) {
                DownloadDiag.log("[自更新] 换票据失败 HTTP " + code);
                return null;
            }
            // 顺手把响应体读掉,让连接能复用
            readStream(conn.getInputStream(), 4096);
            String raw = conn.getHeaderField("Set-Cookie");
            String ticket = parseTicket(raw);
            if (ticket == null) {
                DownloadDiag.log("[自更新] 换票据响应没有 tcs_ticket, Set-Cookie="
                        + (raw == null ? "(无)" : raw));
            }
            return ticket;
        } catch (Throwable t) {
            Log.w(TAG, "换票据失败", t);
            DownloadDiag.logError("[自更新] 换票据异常", t);
            return null;
        } finally {
            if (conn != null) {
                try {
                    conn.disconnect();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /** 从 "tcs_ticket=xxxx;Version=1;Path=/ds/dav/;Max-Age=3600;Secure" 里抠出 xxxx */
    static String parseTicket(String setCookie) {
        if (setCookie == null) {
            return null;
        }
        int i = setCookie.indexOf(TICKET_COOKIE);
        if (i < 0) {
            return null;
        }
        int s = i + TICKET_COOKIE.length();
        int e = setCookie.indexOf(';', s);
        if (e < 0) {
            e = setCookie.length();
        }
        String v = setCookie.substring(s, e).trim();
        return v.isEmpty() ? null : v;
    }

    /** 打开分享文件 URL(已带票据 cookie),交给调用方设置方法/超时 */
    private static HttpURLConnection openFile(Context ctx, String ticket, String name)
            throws Exception {
        HttpURLConnection conn = UpdateSsl.open(ctx,
                HOST + PATH_FILE + SHARE_ID + "/" + name);
        conn.setRequestProperty("Cookie", TICKET_COOKIE + ticket);
        return conn;
    }

    /**
     * 读版本信息 JSON。
     *
     * @param out 成功时 out[0] 为解析好的 JSON
     * @return {@link #META_OK} / {@link #META_MISSING} / {@link #META_FAILED}
     */
    private static int fetchMeta(Context ctx, String ticket, String name, JSONObject[] out) {
        HttpURLConnection conn = null;
        try {
            conn = openFile(ctx, ticket, name);
            conn.setConnectTimeout(TIMEOUT_CONNECT_MS);
            conn.setReadTimeout(TIMEOUT_READ_MS);
            conn.setRequestMethod("GET");
            int code = conn.getResponseCode();
            if (code == 404) {
                DownloadDiag.log("[自更新] 频道文件不存在(可忽略): " + name);
                return META_MISSING;
            }
            if (code >= 400) {
                DownloadDiag.log("[自更新] 读 " + name + " 失败 HTTP " + code);
                return META_FAILED;
            }
            String text = readStream(conn.getInputStream(), MAX_META_BYTES);
            if (text == null || text.trim().isEmpty()) {
                return META_FAILED;
            }
            out[0] = new JSONObject(text);
            return META_OK;
        } catch (Throwable t) {
            Log.w(TAG, "读版本信息失败: " + name, t);
            DownloadDiag.logError("[自更新] 读 " + name + " 异常", t);
            return META_FAILED;
        } finally {
            if (conn != null) {
                try {
                    conn.disconnect();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /** HEAD 确认 APK 存在(很轻,用于提示前先探一下) */
    private static boolean apkExists(Context ctx, String ticket, String name) {
        HttpURLConnection conn = null;
        try {
            conn = openFile(ctx, ticket, name);
            conn.setConnectTimeout(TIMEOUT_CONNECT_MS);
            conn.setReadTimeout(TIMEOUT_READ_MS);
            conn.setRequestMethod("HEAD");
            int code = conn.getResponseCode();
            if (code >= 400) {
                DownloadDiag.log("[自更新] APK 不存在: " + name + " (HTTP " + code + ")");
                return false;
            }
            return true;
        } catch (Throwable t) {
            // HEAD 探活失败不阻断流程:下载阶段还会再试,这里只当"没确认到"
            Log.w(TAG, "APK 探活失败: " + name, t);
            return true;
        } finally {
            if (conn != null) {
                try {
                    conn.disconnect();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /**
     * 下载 APK 到文件。成功返回 null,失败返回可读的中文原因。
     * 边下边算大小上限,超了立刻停,避免把磁盘写爆。
     */
    private static String downloadToFile(Context ctx, String ticket, String name, File dest,
                                         long maxBytes, ProgressListener progress) {
        HttpURLConnection conn = null;
        InputStream is = null;
        FileOutputStream fos = null;
        try {
            conn = openFile(ctx, ticket, name);
            conn.setConnectTimeout(TIMEOUT_CONNECT_MS);
            conn.setReadTimeout(TIMEOUT_READ_MS);
            conn.setRequestMethod("GET");
            int code = conn.getResponseCode();
            if (code >= 400) {
                return "HTTP " + code;
            }
            String lenHeader = conn.getHeaderField("Content-Length");
            long total = -1L;
            try {
                if (lenHeader != null) {
                    total = Long.parseLong(lenHeader.trim());
                }
            } catch (NumberFormatException ignored) {
            }
            if (total > maxBytes) {
                return "文件过大(" + total / 1024 / 1024 + "MB)";
            }
            is = conn.getInputStream();
            //noinspection ResultOfMethodCallIgnored
            dest.delete();
            fos = new FileOutputStream(dest);
            byte[] buf = new byte[16 * 1024];
            long done = 0L;
            int n;
            while ((n = is.read(buf)) > 0) {
                done += n;
                if (done > maxBytes) {
                    fos.close();
                    fos = null;
                    //noinspection ResultOfMethodCallIgnored
                    dest.delete();
                    return "文件超过 " + maxBytes / 1024 / 1024 + "MB 上限";
                }
                fos.write(buf, 0, n);
                if (progress != null) {
                    progress.onProgress(done, total);
                }
            }
            if (done == 0) {
                return "下载到 0 字节";
            }
            return null;
        } catch (Throwable t) {
            Log.w(TAG, "下载失败: " + name, t);
            return t.getClass().getSimpleName()
                    + (t.getMessage() != null ? ": " + t.getMessage() : "");
        } finally {
            closeQuietly(is);
            closeQuietly(fos);
            if (conn != null) {
                try {
                    conn.disconnect();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // 更新链路专用 TLS:内置 TeraCloud 证书链 + 强制 TLS 1.2
    // ------------------------------------------------------------------

    /**
     * 更新链路专用 TLS 设置。
     *
     * <p>刻意<b>不复用</b> {@link TlsCompat}:那个类为了兼容车机上各种自签名的
     * 媒体服务器,在 API 21 以下无条件信任所有证书(TRUST_ALL)。更新链路是
     * 唯一会被远程代码替换的路径(装进来的就是 APK),必须真校验。</p>
     *
     * <p>信任策略:系统信任优先,校验不过再退到 res/raw/teracloud_ca.pem 里
     * 内置的 TeraCloud 证书链(GlobalSign GCC R46 AlphaSSL CA 2025 + 交叉签名的
     * GlobalSign Root R46)。这样新设备照常走系统根证书,车机那种 2015 年的
     * 根证书列表也能校验通过,但没有任何一条路径是无条件信任。</p>
     *
     * <p>【2026-10-23 追加】车机 4.2.2 除了证书老,还有更硬的一关:
     * <b>它的 JSSE 里根本没有 TLS 1.2</b>。官方文档说 TLSv1.1/1.2「API 16 起支持、
     * API 20 起默认启用」,但 4.2/4.3 的实测结果是
     * {@code getSupportedProtocols()} 只返回 SSLv3/TLSv1,
     * 怎么 {@code setEnabledProtocols} 都开不出 TLS 1.2(4.4 起才真能开)。
     * 而 TeraCloud 只接受 TLS 1.2 → 唯一的办法是自带一套 TLS 栈,
     * 即 Conscrypt(BoringSSL 的 JCA Provider)。</p>
     *
     * <p>为避免连累其它网络请求(Navidrome / 歌词等照旧走系统),
     * 这里<b>不做</b> {@code Security.insertProviderAt}(那会全局换 provider),
     * 而是把 provider 实例显式传给 {@code SSLContext.getInstance},
     * 于是只有更新链路用到它,加载也只发生在用户点"检查更新"之后。</p>
     */
    private static final class UpdateSsl {

        private static SSLSocketFactory factory;
        private static boolean tried;

        private UpdateSsl() {
        }

        /** 打开 HTTPS 连接(内置证书 + TLS 1.2 都已装好) */
        static HttpURLConnection open(Context ctx, String urlStr) throws Exception {
            HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
            // 【2026-10-23 实测】必须显式关掉 keep-alive,根因见 obtainTicket 的注释。
            // TeraCloud 是 Apache,空闲连接几秒就被 FIN 掉,而它默认不在响应里回
            // Connection: close,4.2 的 libcore 于是把这条死连接照常回收进池;
            // 主动声明后服务端原样回显(实测会回 Connection: close),连接不再入池,
            // 每次请求都新开一条。代价是每请求多一次 TLS 握手(约 0.3s/次),
            // 换来的收益是彻底消灭"检查正常、一点下载就拿不到票据"。
            conn.setRequestProperty("Connection", "close");
            if (conn instanceof HttpsURLConnection) {
                SSLSocketFactory f = get(ctx);
                if (f != null) {
                    ((HttpsURLConnection) conn).setSSLSocketFactory(f);
                }
            }
            return conn;
        }

        private static synchronized SSLSocketFactory get(Context ctx) {
            if (tried) {
                return factory;
            }
            tried = true;

            // 信任管理器先建好,两条路共用:系统信任优先,不过再用内置 TeraCloud 证书
            X509TrustManager tm = null;
            try {
                X509TrustManager system = defaultTrustManager();
                X509TrustManager embedded = loadBundledCa(ctx);
                tm = (embedded == null) ? system : new FallbackTrustManager(system, embedded);
                Log.i(TAG, "更新链路: 内置 TeraCloud 证书=" + (embedded != null));
            } catch (Throwable t) {
                Log.w(TAG, "信任管理器构建失败", t);
                DownloadDiag.logError("[自更新] 信任管理器构建失败", t);
            }

            // API 21 以下(车机 4.2.2)系统栈开不出 TLS 1.2,优先用自带的 Conscrypt;
            // API 21+ 系统原生就支持 1.2,没必要多加载一套 .so
            if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.LOLLIPOP) {
                SSLSocketFactory conscrypt = buildConscrypt(tm);
                if (conscrypt != null) {
                    factory = conscrypt;
                    return factory;
                }
            }
            factory = buildSystem(tm);
            return factory;
        }

        /**
         * 用 APK 自带的 Conscrypt 建 TLS 上下文。
         *
         * <p>关键:provider 实例<b>不</b>插进 {@code Security} 全局列表,
         * 而是显式传给 getInstance。这样 Conscrypt 只服务于更新链路,
         * Navidrome/歌词等请求完全不受影响。</p>
         */
        private static SSLSocketFactory buildConscrypt(X509TrustManager tm) {
            try {
                Provider p = org.conscrypt.Conscrypt.newProvider();
                TrustManager[] managers;
                if (tm != null) {
                    managers = new TrustManager[]{tm};
                } else {
                    managers = new TrustManager[]{
                            org.conscrypt.Conscrypt.getDefaultX509TrustManager()};
                }
                SSLContext sc = SSLContext.getInstance("TLS", p);
                sc.init(null, managers, new SecureRandom());
                Log.i(TAG, "更新链路 TLS: Conscrypt(自带 TLS 1.2)");
                DownloadDiag.log("[自更新] 更新链路 TLS = Conscrypt");
                return new ForcedProtocolSocketFactory(sc.getSocketFactory(), TLS_12);
            } catch (Throwable t) {
                // Conscrypt 拿不到(缺 .so / 链接失败)就退回系统栈,
                // 并在日志里留痕,方便判断是"没装进去"还是"装了也谈不成"
                Log.w(TAG, "Conscrypt 不可用,回退系统 TLS", t);
                DownloadDiag.logError("[自更新] Conscrypt 不可用,回退系统 TLS", t);
                return null;
            }
        }

        /** 用系统自带 TLS 栈建上下文(API 21+ 走这条) */
        private static SSLSocketFactory buildSystem(X509TrustManager tm) {
            try {
                SSLContext sc = SSLContext.getInstance("TLS");
                sc.init(null, tm != null ? new TrustManager[]{tm} : null, new SecureRandom());
                Log.i(TAG, "更新链路 TLS: 系统(API " + android.os.Build.VERSION.SDK_INT + ")");
                return new ForcedProtocolSocketFactory(sc.getSocketFactory(), TLS_12);
            } catch (Throwable t) {
                Log.w(TAG, "更新链路 TLS 初始化失败,回退系统默认", t);
                return null;
            }
        }

        /** 系统默认信任管理器 */
        private static X509TrustManager defaultTrustManager() throws Exception {
            TrustManagerFactory tmf =
                    TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init((KeyStore) null);
            return (X509TrustManager) tmf.getTrustManagers()[0];
        }

        /** 加载 res/raw/teracloud_ca.pem(可含多张证书)作为兜底信任锚;失败返回 null */
        private static X509TrustManager loadBundledCa(Context ctx) {
            InputStream in = null;
            try {
                KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
                ks.load(null, null);
                CertificateFactory cf = CertificateFactory.getInstance("X.509");
                in = ctx.getResources().openRawResource(R.raw.teracloud_ca);
                Collection<? extends Certificate> certs = cf.generateCertificates(in);
                int i = 0;
                for (Certificate c : certs) {
                    ks.setCertificateEntry("teracloud" + (i++), c);
                }
                if (i == 0) {
                    return null;
                }
                TrustManagerFactory tmf =
                        TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
                tmf.init(ks);
                return (X509TrustManager) tmf.getTrustManagers()[0];
            } catch (Throwable t) {
                Log.w(TAG, "内置证书加载失败", t);
                return null;
            } finally {
                closeQuietly(in);
            }
        }

        /**
         * 系统信任优先,失败再用内置证书。
         * 两者都不过就把内置证书的异常抛出去(它更能说明"是不是证书问题")。
         */
        private static final class FallbackTrustManager implements X509TrustManager {

            private final X509TrustManager system;
            private final X509TrustManager embedded;

            FallbackTrustManager(X509TrustManager system, X509TrustManager embedded) {
                this.system = system;
                this.embedded = embedded;
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType)
                    throws CertificateException {
                try {
                    system.checkServerTrusted(chain, authType);
                } catch (CertificateException e) {
                    embedded.checkServerTrusted(chain, authType);
                }
            }

            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) {
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        }

        /**
         * 包装 SSLSocketFactory:创建出的 SSLSocket 只启用指定协议。
         *
         * <p>为什么强制到 TLS 1.2 而不是"全开":TeraCloud 只接受 TLS 1.2
         * (TLS 1.0/1.1 握手直接失败)。客户端同时报 1.0/1.1 虽不至于谈崩,
         * 但把老协议也亮出来没任何好处,不如锁死 1.2 让行为可预测。</p>
         */
        private static final class ForcedProtocolSocketFactory extends SSLSocketFactory {

            private final SSLSocketFactory delegate;
            /** 只想启用的协议名 */
            private final String only;

            ForcedProtocolSocketFactory(SSLSocketFactory delegate, String only) {
                this.delegate = delegate;
                this.only = only;
            }

            @Override
            public String[] getDefaultCipherSuites() {
                return delegate.getDefaultCipherSuites();
            }

            @Override
            public String[] getSupportedCipherSuites() {
                return delegate.getSupportedCipherSuites();
            }

            @Override
            public Socket createSocket(Socket s, String host, int port, boolean autoClose)
                    throws IOException {
                return enable(delegate.createSocket(s, host, port, autoClose));
            }

            @Override
            public Socket createSocket(String host, int port) throws IOException {
                return enable(delegate.createSocket(host, port));
            }

            @Override
            public Socket createSocket(String host, int port, InetAddress localHost,
                                       int localPort) throws IOException {
                return enable(delegate.createSocket(host, port, localHost, localPort));
            }

            @Override
            public Socket createSocket(InetAddress host, int port) throws IOException {
                return enable(delegate.createSocket(host, port));
            }

            @Override
            public Socket createSocket(InetAddress address, int port, InetAddress localAddress,
                                       int localPort) throws IOException {
                return enable(delegate.createSocket(address, port, localAddress, localPort));
            }

            private Socket enable(Socket socket) {
                if (socket instanceof SSLSocket) {
                    SSLSocket ssl = (SSLSocket) socket;
                    try {
                        String[] supported = ssl.getSupportedProtocols();
                        List<String> use = new ArrayList<String>();
                        for (String p : supported) {
                            if (only.equals(p)) {
                                use.add(p);
                            }
                        }
                        if (use.isEmpty()) {
                            // 该 TLS 栈连 1.2 都没有 —— 车机 4.2.2 的系统栈正是如此。
                            // 退而求其次全开,让握手有机会成功,并把真相写进日志:
                            // 看到这行就说明"系统栈确实开不出 1.2",必须靠 Conscrypt。
                            for (String p : supported) {
                                if (p != null && p.startsWith("TLS")) {
                                    use.add(p);
                                }
                            }
                            if (!use.isEmpty()) {
                                String msg = "SSLSocket 不支持 " + only
                                        + ", 可用协议仅 " + use;
                                Log.w(TAG, msg);
                                DownloadDiag.log("[自更新] " + msg);
                            }
                        }
                        if (!use.isEmpty()) {
                            ssl.setEnabledProtocols(use.toArray(new String[0]));
                        }
                    } catch (Throwable t) {
                        Log.w(TAG, "启用 " + only + " 失败", t);
                    }
                }
                return socket;
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

    /** 版本名更高者胜;版本名相同则 versionCode 高者胜 */
    private static boolean betterCandidate(UpdateInfo cand, UpdateInfo current) {
        if (current == null) {
            return true;
        }
        if (isNewer(cand.versionName, current.versionName)) {
            return true;
        }
        if (isNewer(current.versionName, cand.versionName)) {
            return false;
        }
        return cand.versionCode > current.versionCode;
    }

    // ------------------------------------------------------------------
    // 杂项
    // ------------------------------------------------------------------

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

    /** 有 Activity 用 Activity(弹窗要 Activity 的 window token),否则用 applicationContext */
    private static Context activityOrApp(Context ctx) {
        return (ctx instanceof android.app.Activity) ? ctx : ctx.getApplicationContext();
    }

    /** 读流为 UTF-8 字符串;超过 max 字节直接放弃(防呆) */
    private static String readStream(InputStream is, int max) throws Exception {
        if (is == null) {
            return "";
        }
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        int total = 0;
        while ((n = is.read(buf)) > 0) {
            total += n;
            if (total > max) {
                break;
            }
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
}
