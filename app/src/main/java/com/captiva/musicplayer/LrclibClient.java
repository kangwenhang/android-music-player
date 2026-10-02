package com.captiva.musicplayer;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URLEncoder;

/**
 * 公开歌词源兜底:lrclib.net
 *
 * 为什么需要:服务器(飞牛 / Navidrome)的歌词接口覆盖有限 —— Subsonic 的 getLyrics
 * 在老版本 / 未装插件的 Navidrome 上直接不可用,飞牛的 lyric/list 也常有漏网之鱼。
 * 而本地歌曲没有 streamId,歌词缓存与按 songId 取词都用不上,一旦服务器取不到就是 0 行。
 * 于是在"服务器也没词"时,按歌手 + 歌名到公开 LRC 库再试一次。
 *
 * 接口(免费、无需 API key):
 *   GET https://lrclib.net/api/get?track_name=&artist_name=&duration=   → 单条,未命中 404
 *   GET https://lrclib.net/api/search?track_name=&artist_name=          → 数组
 * 返回字段里 syncedLyrics 是带时间轴的 LRC,plainLyrics 是无时间轴的纯文本,优先取前者。
 *
 * 使用约束(重要):
 * - 必须在**后台线程**调用(歌词加载的 lyricsExecutor),绝不能在主线程发网络请求
 * - 全程静默降级:取不到 / 超时 / 外网不通都返回 null,绝不影响播放
 * - 走 TlsCompat.open():安卓 4.2.2 的 TLS 栈太旧,直接 openConnection 很可能握手失败
 * - 想彻底关掉:把下面的 ENABLED 改成 false
 */
public final class LrclibClient {

    private static final String TAG = "LrclibClient";

    /** 总开关:false = 完全不请求外网歌词源 */
    public static final boolean ENABLED = true;

    private static final String ENDPOINT_GET = "https://lrclib.net/api/get";
    private static final String ENDPOINT_SEARCH = "https://lrclib.net/api/search";
    private static final int CONNECT_TIMEOUT_MS = 3000;
    private static final int READ_TIMEOUT_MS = 5000;
    /**
     * 一次取词的**总预算**。
     * 歌词加载跑在 MusicService 的 lyricsExecutor 上,那是**单线程**线程池 ——
     * 一首歌的网络取词若无限拖,后面切歌的歌词加载会全部排队卡住。
     * 所以三级查询共享一个预算,超时就不再往下试。
     */
    private static final long BUDGET_TOTAL_MS = 8000L;
    /**
     * 熔断:连续这么多次**网络层异常**后进入冷却。
     * 注意 404 不算异常 —— 那是"链路通、库里没这首",是正常结果,不能因此熔断。
     */
    private static final int FAIL_THRESHOLD = 2;
    /** 冷却时长:车机压根访问不了外网时,不至于每切一首歌都白等一轮超时 */
    private static final long COOLDOWN_MS = 10 * 60 * 1000L;
    /** search 结果按时长优选时允许的误差(秒);超出则认为对不上,退回第一条 */
    private static final long DURATION_TOLERANCE_SEC = 3L;
    /** 单次响应读取上限:车机堆小,防止超大 JSON 把内存吃掉 */
    private static final int MAX_RESPONSE_BYTES = 256 * 1024;

    private static volatile int netFailCount = 0;
    private static volatile long lastNetFailAt = 0L;

    /** lrclib 建议在 UA 里标识应用(否则可能被限流) */
    private static final String USER_AGENT =
            "CaptivaMusicPlayer/5.7 (car headunit; Android 4.2.2)";

    private LrclibClient() {}

    /**
     * 按歌手 + 歌名取歌词。
     * @param artist      歌手名,可为空
     * @param title       歌曲名,必填
     * @param durationSec 时长(秒),用于精确匹配;<=0 时省略该参数
     * @return 歌词文本(优先带时间轴的 LRC),取不到返回 null
     */
    public static String fetchLyrics(String artist, String title, long durationSec) {
        if (!ENABLED) {
            return null;
        }
        if (title == null || title.trim().isEmpty()) {
            return null;
        }
        if (isCoolingDown()) {
            Log.d(TAG, "lrclib 冷却中(外网不可达),跳过: " + title);
            return null;
        }

        final boolean hasArtist = artist != null && !artist.trim().isEmpty();
        final long deadline = System.currentTimeMillis() + BUDGET_TOTAL_MS;
        try {
            // 1) 精确:get + duration(带时长能显著减少匹配到同名不同版本的情况)
            String text = null;
            if (System.currentTimeMillis() < deadline) {
                StringBuilder url = new StringBuilder(ENDPOINT_GET)
                        .append("?track_name=").append(enc(title));
                if (hasArtist) {
                    url.append("&artist_name=").append(enc(artist));
                }
                if (durationSec > 0) {
                    url.append("&duration=").append(durationSec);
                }
                text = getAndExtract(url.toString(), false, deadline, durationSec);
                if (text != null) {
                    Log.d(TAG, "lrclib 精确命中: " + title + " (" + text.length() + " 字符)");
                    return text;
                }
            }

            // 2) 放宽:不带时长再查一次(库里存的时长常常是 0,对不上就 404)
            if (durationSec > 0 && System.currentTimeMillis() < deadline) {
                StringBuilder url2 = new StringBuilder(ENDPOINT_GET)
                        .append("?track_name=").append(enc(title));
                if (hasArtist) {
                    url2.append("&artist_name=").append(enc(artist));
                }
                text = getAndExtract(url2.toString(), false, deadline, durationSec);
                if (text != null) {
                    Log.d(TAG, "lrclib 命中(忽略时长): " + title);
                    return text;
                }
            }

            // 3) 最后:搜索接口取第一条。
            //    没有歌手名时不做搜索 —— 泛用词名会搜到同名不同歌,匹配错的词比没词更糟。
            if (hasArtist && System.currentTimeMillis() < deadline) {
                StringBuilder url3 = new StringBuilder(ENDPOINT_SEARCH)
                        .append("?track_name=").append(enc(title));
                url3.append("&artist_name=").append(enc(artist));
                text = getAndExtract(url3.toString(), true, deadline, durationSec);
                if (text != null) {
                    Log.d(TAG, "lrclib 搜索命中: " + title);
                    return text;
                }
            }

            Log.d(TAG, "lrclib 无结果: " + title);
            return null;
        } catch (Exception e) {
            Log.w(TAG, "lrclib 取词失败(静默降级)", e);
            return null;
        }
    }

    /** 是否处于冷却期(外网连续不可达时,短时间内不再白等超时) */
    private static boolean isCoolingDown() {
        return netFailCount >= FAIL_THRESHOLD
                && (System.currentTimeMillis() - lastNetFailAt) < COOLDOWN_MS;
    }

    /** 拿到 HTTP 响应 = 链路是通的,清空失败计数 */
    private static void onNetworkOk() {
        netFailCount = 0;
    }

    /** 网络层异常(超时 / DNS / TLS 握手失败):累计失败次数 */
    private static void onNetworkFail() {
        netFailCount++;
        lastNetFailAt = System.currentTimeMillis();
        Log.w(TAG, "lrclib 网络异常(" + netFailCount + "/" + FAIL_THRESHOLD + "),累计到阈值后暂时不再尝试");
    }

    /**
     * 请求并把响应里的歌词字段取出来。
     * @param isArray  true = 响应是数组(search 接口)
     * @param wantDur  期望时长(秒),用于在数组结果里挑最接近的版本;<=0 表示不挑
     */
    private static String getAndExtract(String urlStr, boolean isArray, long deadline, long wantDur)
            throws Exception {
        String body = httpGet(urlStr, deadline);
        if (body == null || body.isEmpty()) {
            return null;
        }
        if (!isArray) {
            return extract(new JSONObject(body));
        }
        // 数组:search 一次能返回 20 条完整歌词(每条约 7KB),车机堆小,要挑准而不能随手取第一条。
        // 实测同一首歌会有多个版本(如「我知道」有 250s 与 214s 两条,词可能不同),
        // 所以按时长挑最接近的;对不上(±3 秒外)才退回第一条有词的。
        JSONArray arr = new JSONArray(body);
        if (arr.length() == 0) {
            return null;
        }
        String first = null;
        String best = null;
        long bestDiff = Long.MAX_VALUE;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            String t = extract(o);
            if (t == null) {
                continue;
            }
            if (first == null) {
                first = t;
            }
            if (wantDur > 0 && o != null) {
                double d = o.optDouble("duration", 0);
                if (d > 0) {
                    long diff = (long) Math.abs(d - wantDur);
                    if (diff < bestDiff) {
                        bestDiff = diff;
                        best = t;
                    }
                }
            }
        }
        if (best != null && bestDiff <= DURATION_TOLERANCE_SEC) {
            return best;
        }
        return first;
    }

    /** 优先带时间轴的 syncedLyrics,退回纯文本 plainLyrics */
    private static String extract(JSONObject obj) {
        if (obj == null) {
            return null;
        }
        if (obj.optBoolean("instrumental", false)) {
            return null;   // 纯音乐,没有歌词
        }
        String synced = obj.optString("syncedLyrics", "");
        if (synced != null && !synced.trim().isEmpty()) {
            return synced;
        }
        String plain = obj.optString("plainLyrics", "");
        if (plain != null && !plain.trim().isEmpty()) {
            return plain;
        }
        return null;
    }

    /** GET 一个 URL,返回响应体;404 / 超时 / 异常一律返回 null(静默降级) */
    private static String httpGet(String urlStr, long deadline) throws Exception {
        HttpURLConnection conn = null;
        InputStream is = null;
        try {
            conn = TlsCompat.open(urlStr);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", USER_AGENT);
            long remaining = deadline - System.currentTimeMillis();
            if (remaining < 1000L) {
                remaining = 1000L;     // 至少给 1 秒,别让预算把单次请求压成必超时
            }
            conn.setConnectTimeout((int) Math.min(CONNECT_TIMEOUT_MS, remaining));
            conn.setReadTimeout((int) Math.min(READ_TIMEOUT_MS, remaining));

            int code;
            try {
                code = conn.getResponseCode();
            } catch (java.io.FileNotFoundException notFound) {
                // 部分实现把 404 以异常形式抛出:链路其实是通的,只是库里没这首 —— 不算网络失败
                onNetworkOk();
                return null;
            } catch (Exception e) {
                onNetworkFail();
                throw e;
            }
            onNetworkOk();
            if (code != HttpURLConnection.HTTP_OK) {
                return null;
            }
            is = conn.getInputStream();
            BufferedReader reader = null;
            try {
                reader = new BufferedReader(new InputStreamReader(is, "UTF-8"));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line).append('\n');
                    if (sb.length() > MAX_RESPONSE_BYTES) {
                        // 截断的 JSON 已无法解析,直接放弃(= 静默降级),别把车机内存吃光
                        Log.w(TAG, "lrclib 响应超过 " + MAX_RESPONSE_BYTES + " 字节,放弃本次取词");
                        return null;
                    }
                }
                return sb.toString();
            } finally {
                if (reader != null) {
                    try { reader.close(); } catch (Exception ignored) {}
                }
            }
        } finally {
            if (is != null) {
                try { is.close(); } catch (Exception ignored) {}
            }
            if (conn != null) {
                try { conn.disconnect(); } catch (Exception ignored) {}
            }
        }
    }

    private static String enc(String s) throws Exception {
        return URLEncoder.encode(s, "UTF-8");
    }
}
