package com.captiva.musicplayer;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * 飞牛 NAS 音乐服务(FN Music)API 客户端
 *
 * 【已实测通过 huilong.k1483162508.fun:5666,版本 mediasrv 0.8.41】
 *
 * 接口前缀: {服务器地址}/music/api/v1
 *   ↑ 注意必须有 /v1。少了 /v1 会被 nginx 回落成前端 SPA 页面(返回 HTML)。
 *
 * 鉴权三步:
 *   1. 每个请求带 authx 头:nonce/timestamp/sign(sign = MD5)
 *   2. POST /user/password-login,{username, password=SHA256(明文), deviceId} -> userToken
 *   3. 后续请求带 Cookie: music-token=<userToken>
 *
 * 已知特性 / 坑:
 *   - 分页参数叫 size(offset 实测无效),size 可放大到 1000+ 一次取全量
 *   - 搜索参数是 q,不是 query
 *   - 实体 ID 参数是 实体名+大写 GUID:trackGUID / albumGUID
 *   - 歌词返回的是 LRC 文本,不是结构化数组
 *   - 封面参数是 coverId,路径是 /static/cover(不是 /static/cover/track)
 *   - HEAD 请求会被 nginx 兜底成 HTML,取媒体信息要用 GET + Range
 */
public class FnMusicApi implements MusicSourceApi {

    private static final String TAG = "FnMusicApi";

    /** 接口前缀(结尾无斜杠) */
    private static final String API_BASE = "/music/api/v1";
    /** 客户端内置签名盐值(与 FN Music 客户端一致) */
    private static final String SIGN_SALT = "NDzZTVxnRKP8Z0jXg1VAMonaG8akvh";
    private static final String USER_AGENT = "CaptivaMusic";
    private static final int CONNECT_TIMEOUT = 10000;
    private static final int READ_TIMEOUT = 15000;
    /** 单次拉取曲目上限(服务端无有效分页,靠放大量级一次取完) */
    private static final int MAX_PAGE_SIZE = 5000;
    /** 中继模式手动跟随重定向的最大次数(飞牛中继会返回 302,需带 mode=relay 重发) */
    private static final int MAX_RELAY_REDIRECTS = 5;

    private final String serverUrl;
    private final String username;
    private final String password;
    /** 设备标识,32 位十六进制(FN Music 用 crypto.randomUUID 去横线) */
    private final String deviceId;

    /** 登录令牌 */
    private String userToken;
    private boolean loginFailed = false;

    /** 是否走飞牛中继(远程 FN ID 访问)。默认 false = 直连 NAS。开启后所有请求带 Cookie: mode=relay */
    private boolean relayMode = false;
    /** 外网访问码(NAS 开启访问码保护时填);为空表示不启用 */
    private String accessCode = null;

    /** 全量曲目缓存(offset 分页无效,故一次拉全后本地切片) */
    private List<MusicBean> allSongsCache = null;
    /** 全量专辑缓存 */
    private List<AlbumBean> allAlbumsCache = null;

    public FnMusicApi(String serverUrl, String username, String password) {
        String base = serverUrl;
        if (base != null && base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        this.serverUrl = base;
        this.username = username;
        this.password = password;
        this.deviceId = UUID.randomUUID().toString().replace("-", "");
    }

    /** 设置是否走飞牛中继(远程 FN ID 访问)。中继模式下所有请求带 Cookie: mode=relay */
    public void setRelayMode(boolean relayMode) {
        this.relayMode = relayMode;
    }

    /** 设置外网访问码(NAS 开启访问码保护时填);传 null/空表示不启用 */
    public void setAccessCode(String accessCode) {
        this.accessCode = accessCode;
    }

    // ==================== 工具:摘要 ====================

    private static String md5Hex(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] d = md.digest(s.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) sb.append(String.format("%02x", b & 0xFF));
            return sb.toString();
        } catch (Exception e) {
            Log.e(TAG, "md5 failed", e);
            return "";
        }
    }

    private static String sha256Hex(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(s.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) sb.append(String.format("%02x", b & 0xFF));
            return sb.toString();
        } catch (Exception e) {
            Log.e(TAG, "sha256 failed", e);
            return "";
        }
    }

    // ==================== 工具:URL / 签名 ====================

    private String apiUrl(String endpoint) {
        return serverUrl + API_BASE + endpoint;
    }

    /** 把 query 拼成 "k=v&k=v"(按 key 排序,空格编码为 %20) */
    private String buildQuery(Map<String, String> params) {
        if (params == null || params.isEmpty()) {
            return "";
        }
        List<String> keys = new ArrayList<>(params.keySet());
        Collections.sort(keys);
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (String k : keys) {
            String v = params.get(k);
            if (v == null) continue;
            if (!first) sb.append('&');
            sb.append(k).append('=').append(v.replace("+", "%20"));
            first = false;
        }
        return sb.toString();
    }

    /** 构造完整 URL(query 值需 URL 编码) */
    private String urlWithQuery(String endpoint, Map<String, String> params) {
        String url = apiUrl(endpoint);
        if (params == null || params.isEmpty()) {
            return url;
        }
        StringBuilder sb = new StringBuilder(url).append('?');
        boolean first = true;
        List<String> keys = new ArrayList<>(params.keySet());
        Collections.sort(keys);
        for (String k : keys) {
            String v = params.get(k);
            if (v == null) continue;
            if (!first) sb.append('&');
            sb.append(k).append('=').append(URLEncoder.encode(v));
            first = false;
        }
        return sb.toString();
    }

    /**
     * 构造 authx 头值
     * GET  : body 摘要 = MD5(排序后的 query 串)
     * POST : body 摘要 = MD5(JSON 串)
     */
    private String buildAuthx(String path, String canonicalBody) {
        String nonce = String.valueOf(new Random().nextInt(900000) + 100000);
        String timestamp = String.valueOf(System.currentTimeMillis());
        String raw = SIGN_SALT + "_" + path + "_" + nonce + "_" + timestamp
                + "_" + md5Hex(canonicalBody == null ? "" : canonicalBody) + "_";
        return "nonce=" + nonce + "&timestamp=" + timestamp + "&sign=" + md5Hex(raw);
    }

    // ==================== HTTP ====================

    /** GET 请求(Cookie + authx) */
    private String httpGet(String endpoint, Map<String, String> params) {
        String path = API_BASE + endpoint;
        String urlStr = urlWithQuery(endpoint, params);
        return httpGetByUrl(urlStr, path, buildQuery(params));
    }

    /** 统一注入鉴权头:authx + music-token cookie;中继模式追加 mode=relay;访问码追加 x-access-code */
    private void applyHeaders(HttpURLConnection conn, String signPath, String canonicalBody) {
        conn.setRequestProperty("Accept", "application/json");
        conn.setRequestProperty("User-Agent", USER_AGENT);
        conn.setRequestProperty("authx", buildAuthx(signPath, canonicalBody));
        StringBuilder cookie = new StringBuilder();
        if (userToken != null) cookie.append("music-token=").append(userToken);
        if (relayMode) {
            if (cookie.length() > 0) cookie.append("; ");
            cookie.append("mode=relay");
        }
        if (cookie.length() > 0) conn.setRequestProperty("Cookie", cookie.toString());
        if (accessCode != null && !accessCode.isEmpty()) {
            conn.setRequestProperty("x-access-code", base64(accessCode));
            conn.setRequestProperty("x-access-source", "app");
        }
    }

    private static String base64(String s) {
        try {
            return android.util.Base64.encodeToString(s.getBytes("UTF-8"), android.util.Base64.NO_WRAP);
        } catch (Exception e) {
            return "";
        }
    }

    private static boolean isRedirect(int code) {
        return code == 301 || code == 302 || code == 303 || code == 307 || code == 308;
    }

    private static String absoluteUrl(String base, String loc) {
        try {
            if (loc.startsWith("http://") || loc.startsWith("https://")) return loc;
            java.net.URL b = new java.net.URL(base);
            if (loc.startsWith("/")) return b.getProtocol() + "://" + b.getAuthority() + loc;
            String path = b.getPath();
            int slash = path.lastIndexOf('/');
            String dir = slash >= 0 ? path.substring(0, slash + 1) : "/";
            return b.getProtocol() + "://" + b.getAuthority() + dir + loc;
        } catch (Exception e) {
            return loc;
        }
    }

    private String httpGetByUrl(String urlStr, String signPath, String canonicalBody) {
        String currentUrl = urlStr;
        for (int redirect = 0; redirect <= MAX_RELAY_REDIRECTS; redirect++) {
            HttpURLConnection conn = null;
            try {
                URL url = new URL(currentUrl);
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(CONNECT_TIMEOUT);
                conn.setReadTimeout(READ_TIMEOUT);
                conn.setDoInput(true);
                // 中继模式需手动跟随重定向(重发 mode=relay cookie);直连保持默认自动跟随,行为不变
                if (relayMode) conn.setInstanceFollowRedirects(false);
                applyHeaders(conn, signPath, canonicalBody);
                int code = conn.getResponseCode();
                if (relayMode && isRedirect(code)) {
                    String loc = conn.getHeaderField("Location");
                    conn.disconnect();
                    if (loc == null) break;
                    currentUrl = absoluteUrl(urlStr, loc);
                    continue;
                }
                if (code != 200) {
                    Log.e(TAG, "GET " + signPath + " -> HTTP " + code);
                    return null;
                }
                InputStream is = conn.getInputStream();
                BufferedReader reader = new BufferedReader(new InputStreamReader(is, "UTF-8"));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) sb.append(line);
                return sb.toString();
            } catch (Exception e) {
                Log.e(TAG, "GET failed: " + signPath, e);
                return null;
            } finally {
                if (conn != null) conn.disconnect();
            }
        }
        return null;
    }

    /** POST JSON */
    private String httpPost(String endpoint, JSONObject body) {
        String json = body.toString();
        String urlStr = apiUrl(endpoint);
        String currentUrl = urlStr;
        String currentBody = json;
        for (int redirect = 0; redirect <= MAX_RELAY_REDIRECTS; redirect++) {
            HttpURLConnection conn = null;
            OutputStream os = null;
            try {
                URL url = new URL(currentUrl);
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setConnectTimeout(CONNECT_TIMEOUT);
                conn.setReadTimeout(READ_TIMEOUT);
                conn.setDoInput(true);
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                if (relayMode) conn.setInstanceFollowRedirects(false);
                applyHeaders(conn, API_BASE + endpoint, currentBody);
                os = conn.getOutputStream();
                os.write(currentBody.getBytes("UTF-8"));
                os.flush();

                int code = conn.getResponseCode();
                if (relayMode && isRedirect(code)) {
                    String loc = conn.getHeaderField("Location");
                    closeQuietly(os);
                    conn.disconnect();
                    if (loc == null) break;
                    currentUrl = absoluteUrl(urlStr, loc);
                    continue;
                }
                InputStream is = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
                if (is == null) {
                    Log.e(TAG, "POST " + endpoint + " -> HTTP " + code);
                    return null;
                }
                BufferedReader reader = new BufferedReader(new InputStreamReader(is, "UTF-8"));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) sb.append(line);
                return sb.toString();
            } catch (Exception e) {
                Log.e(TAG, "POST failed: " + endpoint, e);
                return null;
            } finally {
                closeQuietly(os);
                if (conn != null) conn.disconnect();
            }
        }
        return null;
    }

    // ==================== 认证 ====================

    /** 登录并缓存 userToken */
    private synchronized String ensureToken() {
        if (userToken != null) {
            return userToken;
        }
        if (loginFailed) {
            return null;
        }
        try {
            JSONObject body = new JSONObject();
            body.put("username", username);
            // 关键:服务端存的是密码的 SHA256,不接收明文
            body.put("password", sha256Hex(password == null ? "" : password));
            body.put("deviceId", deviceId);

            String resp = httpPost("/user/password-login", body);
            JSONObject root = resp == null ? null : new JSONObject(resp);
            if (root != null && root.optInt("code", -1) == 0) {
                JSONObject data = root.optJSONObject("data");
                if (data != null) {
                    String t = data.optString("userToken", null);
                    if (t != null && !t.isEmpty()) {
                        userToken = t;
                        Log.d(TAG, "登录成功");
                        return userToken;
                    }
                }
            }
            Log.e(TAG, "登录失败: " + resp);
        } catch (Exception e) {
            Log.e(TAG, "login failed", e);
        }
        loginFailed = true;
        return null;
    }

    @Override
    public boolean ping() {
        userToken = null;
        loginFailed = false;
        return ensureToken() != null;
    }

    @Override
    public String getSourceType() {
        return MusicSourceFactory.TYPE_FNMUSIC;
    }

    @Override
    public String getSourceName() {
        return "飞牛音乐";
    }

    /**
     * 媒体资源鉴权头
     * 实测:URL 上带 token= 参数无效(401),必须用 Authorization 头传裸 token
     * (注意不是 "Bearer xxx",带 Bearer 前缀会被拒)
     */
    @Override
    public Map<String, String> getAuthHeaders() {
        if (ensureToken() == null) {
            return null;
        }
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Authorization", userToken);
        if (relayMode) {
            h.put("Cookie", "mode=relay; music-token=" + userToken);
        }
        if (accessCode != null && !accessCode.isEmpty()) {
            h.put("x-access-code", base64(accessCode));
            h.put("x-access-source", "app");
        }
        return h;
    }

    // ==================== 响应解析 ====================

    private JSONObject requestJsonGet(String endpoint, Map<String, String> params) {
        if (ensureToken() == null) {
            return null;
        }
        String resp = httpGet(endpoint, params);
        if (resp == null) {
            return null;
        }
        try {
            JSONObject root = new JSONObject(resp);
            int code = root.optInt("code", -1);
            if (code != 0) {
                Log.w(TAG, endpoint + " code=" + code + " msg=" + root.optString("msg"));
                // token 失效:清掉缓存,允许下次重试
                if (code == 99999 || code == 120001) {
                    userToken = null;
                }
                return null;
            }
            return root.optJSONObject("data");
        } catch (Exception e) {
            Log.e(TAG, "解析失败: " + endpoint, e);
            return null;
        }
    }

    private static LinkedHashMap<String, String> map(String... kv) {
        LinkedHashMap<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }

    // ==================== 曲目解析 ====================

    private MusicBean parseTrack(JSONObject s) {
        if (s == null) return null;
        MusicBean bean = new MusicBean();
        bean.setNetwork(true);

        String guid = s.optString("guid", "");
        bean.setStreamId(guid);
        bean.setTitle(s.optString("title", ""));

        // 艺术家:数组,多个用 、 连接
        JSONArray artists = s.optJSONArray("artists");
        StringBuilder artistSb = new StringBuilder();
        if (artists != null) {
            for (int i = 0; i < artists.length(); i++) {
                JSONObject a = artists.optJSONObject(i);
                String n = a != null ? a.optString("name", "") : "";
                if (!n.isEmpty()) {
                    if (artistSb.length() > 0) artistSb.append("、");
                    artistSb.append(n);
                }
            }
        }
        bean.setArtist(artistSb.length() > 0 ? artistSb.toString() : "未知艺术家");

        JSONObject album = s.optJSONObject("album");
        bean.setAlbum(album != null ? album.optString("name", "") : "");

        // duration 单位就是毫秒
        bean.setDuration(s.optLong("duration", 0));

        // 封面:优先自己的 coverId,没有就用专辑的
        String cover = s.optString("coverId", "");
        if (cover.isEmpty() && album != null) {
            cover = album.optString("coverId", "");
        }
        bean.setCoverArtId(cover);

        JSONObject spec = s.optJSONObject("audioSpec");
        if (spec != null) {
            String codec = spec.optString("codec", "");
            if (!codec.isEmpty()) {
                bean.setLocalSuffix(codec);
            }
            int bitrate = spec.optInt("bitrate", 0);
            if (bitrate > 0) {
                bean.setBitRate(bitrate / 1000); // 服务端是 bps,项目用 kbps
            }
        }

        bean.setStreamUrl(getStreamUrl(guid));
        return bean;
    }

    private List<MusicBean> parseTrackList(JSONArray arr) {
        List<MusicBean> list = new ArrayList<>();
        if (arr == null) return list;
        for (int i = 0; i < arr.length(); i++) {
            MusicBean b = parseTrack(arr.optJSONObject(i));
            if (b != null && !b.getStreamId().isEmpty()) {
                list.add(b);
            }
        }
        return list;
    }

    // ==================== 专辑解析 ====================

    private AlbumBean parseAlbum(JSONObject a) {
        if (a == null) return null;
        AlbumBean bean = new AlbumBean();
        bean.setId(a.optString("guid", ""));
        bean.setName(a.optString("name", ""));
        JSONArray artists = a.optJSONArray("artists");
        StringBuilder sb = new StringBuilder();
        if (artists != null) {
            for (int i = 0; i < artists.length(); i++) {
                JSONObject ar = artists.optJSONObject(i);
                String n = ar != null ? ar.optString("name", "") : "";
                if (!n.isEmpty()) {
                    if (sb.length() > 0) sb.append("、");
                    sb.append(n);
                }
            }
        }
        bean.setArtist(sb.length() > 0 ? sb.toString() : "未知艺术家");
        bean.setCoverArtId(a.optString("coverId", ""));
        bean.setSongCount(a.optInt("trackCount", 0));
        bean.setDuration(a.optLong("duration", 0));
        return bean;
    }

    private List<AlbumBean> parseAlbumList(JSONArray arr) {
        List<AlbumBean> list = new ArrayList<>();
        if (arr == null) return list;
        for (int i = 0; i < arr.length(); i++) {
            AlbumBean b = parseAlbum(arr.optJSONObject(i));
            if (b != null && !b.getId().isEmpty()) {
                list.add(b);
            }
        }
        return list;
    }

    // ==================== MusicSourceApi 实现 ====================

    @Override
    public List<AlbumBean> getAlbumList(String type, int count) {
        return getAlbumList(type, count, 0);
    }

    @Override
    public List<AlbumBean> getAlbumList(String type, int size, int offset) {
        List<AlbumBean> all = getAllAlbumsInternal(false);
        if (all == null) return new ArrayList<>();
        if (offset >= all.size()) return new ArrayList<>();
        int end = Math.min(all.size(), offset + Math.max(size, 1));
        return new ArrayList<>(all.subList(offset, end));
    }

    @Override
    public List<AlbumBean> getAllAlbums() {
        List<AlbumBean> all = getAllAlbumsInternal(true);
        return all == null ? new ArrayList<AlbumBean>() : all;
    }

    /** 拉取全部专辑(服务端分页无效,故一次拉全) */
    private synchronized List<AlbumBean> getAllAlbumsInternal(boolean force) {
        if (allAlbumsCache != null && !force) {
            return allAlbumsCache;
        }
        JSONObject data = requestJsonGet("/album/list", map("size", String.valueOf(MAX_PAGE_SIZE)));
        if (data == null) {
            return allAlbumsCache;
        }
        List<AlbumBean> list = parseAlbumList(data.optJSONArray("list"));
        Log.d(TAG, "album/list: " + list.size() + " 张 / total=" + data.optInt("total", -1));
        allAlbumsCache = list;
        return list;
    }

    @Override
    public List<MusicBean> getAlbum(String albumId) {
        List<MusicBean> list = new ArrayList<>();
        if (albumId == null || albumId.isEmpty()) return list;
        JSONObject data = requestJsonGet("/track/album-detail/list",
                map("albumGUID", albumId, "size", String.valueOf(MAX_PAGE_SIZE)));
        if (data == null) return list;
        list = parseTrackList(data.optJSONArray("list"));
        return list;
    }

    @Override
    public List<MusicBean> getSongsByAlbumPage(int albumOffset, int albumCount) {
        List<MusicBean> result = new ArrayList<>();
        List<AlbumBean> all = getAllAlbumsInternal(false);
        if (all == null || albumOffset >= all.size()) return result;
        int end = Math.min(all.size(), albumOffset + albumCount);
        for (int i = albumOffset; i < end; i++) {
            List<MusicBean> songs = getAlbum(all.get(i).getId());
            if (songs != null && !songs.isEmpty()) {
                result.addAll(songs);
            }
        }
        Log.d(TAG, "getSongsByAlbumPage: albums[" + albumOffset + "," + end + ") -> " + result.size() + " 首");
        return result;
    }

    @Override
    public List<MusicBean> getSongsPage(int offset, int count) {
        List<MusicBean> all = getAllSongsInternal();
        if (all == null) return new ArrayList<>();
        if (offset >= all.size()) return new ArrayList<>();
        int end = Math.min(all.size(), offset + count);
        return new ArrayList<>(all.subList(offset, end));
    }

    @Override
    public List<MusicBean> getAllSongs() {
        List<MusicBean> all = getAllSongsInternal();
        return all == null ? new ArrayList<MusicBean>() : new ArrayList<>(all);
    }

    /** 拉取全部曲目(服务端 offset 无效,直接放大 size 一次取完) */
    private synchronized List<MusicBean> getAllSongsInternal() {
        if (allSongsCache != null) return allSongsCache;
        // 先取总量
        JSONObject probe = requestJsonGet("/track/list", map("size", "1"));
        int total = probe != null ? probe.optInt("total", -1) : -1;
        int size = (total > 0) ? Math.min(total, MAX_PAGE_SIZE) : MAX_PAGE_SIZE;
        JSONObject data = requestJsonGet("/track/list", map("size", String.valueOf(Math.max(size, 500))));
        if (data == null) return null;
        allSongsCache = parseTrackList(data.optJSONArray("list"));
        Log.d(TAG, "track/list: " + allSongsCache.size() + " 首 / total=" + total);
        return allSongsCache;
    }

    @Override
    public List<MusicBean> search(String query, int count) {
        List<MusicBean> list = new ArrayList<>();
        if (query == null || query.isEmpty()) return list;
        JSONObject data = requestJsonGet("/search/track",
                map("q", query, "size", String.valueOf(Math.max(count, 1))));
        if (data == null) return list;
        return parseTrackList(data.optJSONArray("list"));
    }

    @Override
    public List<MusicBean> getRandomSongs(int count) {
        List<MusicBean> all = getAllSongsInternal();
        List<MusicBean> list = new ArrayList<>();
        if (all == null || all.isEmpty()) return list;
        List<MusicBean> pool = new ArrayList<>(all);
        Collections.shuffle(pool);
        for (int i = 0; i < Math.min(count, pool.size()); i++) {
            list.add(pool.get(i));
        }
        return list;
    }

    @Override
    public List<MusicBean> getStarredSongs() {
        JSONObject data = requestJsonGet("/favorite-track/list",
                map("size", String.valueOf(MAX_PAGE_SIZE)));
        if (data == null) return new ArrayList<>();
        return parseTrackList(data.optJSONArray("list"));
    }

    @Override
    public List<LrcEntry> getLyricsBySongId(String songId) {
        List<LrcEntry> empty = new ArrayList<>();
        if (songId == null || songId.isEmpty()) return empty;
        JSONObject data = requestJsonGet("/lyric/list", map("trackGUID", songId));
        if (data == null) return empty;
        JSONArray arr = data.optJSONArray("list");
        if (arr == null || arr.length() == 0) return empty;
        // 服务端返回 LRC 纯文本(content 字段),交给项目自带的解析器
        JSONObject first = arr.optJSONObject(0);
        String content = first != null ? first.optString("content", "") : "";
        if (content.isEmpty()) return empty;
        List<LrcEntry> parsed = LrcParser.parseLrcText(content);
        return parsed != null ? parsed : empty;
    }

    @Override
    public String getLyrics(String artist, String title) {
        // 飞牛按 trackGUID 取歌词,没有按 艺术家+标题 的端点
        return null;
    }

    // ==================== URL / 下载 ====================

    @Override
    public String getCoverArtUrl(String coverArtId, int size) {
        if (coverArtId == null || coverArtId.isEmpty()) return null;
        String url = apiUrl("/static/cover") + "?coverId=" + URLEncoder.encode(coverArtId);
        if (size > 0) {
            url = url + "&size=" + size;
        }
        return url;
    }

    @Override
    public String getStreamUrl(String songId) {
        if (songId == null || songId.isEmpty()) return null;
        return apiUrl("/track/stream") + "?guid=" + URLEncoder.encode(songId);
    }

    @Override
    public long downloadFile(String songId, File destFile) {
        if (songId == null || songId.isEmpty() || destFile == null) return -1;
        if (ensureToken() == null) return -1;
        File parent = destFile.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();

        HttpURLConnection conn = null;
        InputStream is = null;
        FileOutputStream fos = null;
        try {
            String urlStr = getStreamUrl(songId);
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(CONNECT_TIMEOUT);
            conn.setReadTimeout(60000);
            conn.setDoInput(true);
            conn.setRequestProperty("User-Agent", USER_AGENT);
            conn.setRequestProperty("authx", buildAuthx(API_BASE + "/track/stream", "guid=" + songId));
            String cookie = "music-token=" + userToken;
            if (relayMode) cookie = "mode=relay; " + cookie;
            conn.setRequestProperty("Cookie", cookie);
            if (accessCode != null && !accessCode.isEmpty()) {
                conn.setRequestProperty("x-access-code", base64(accessCode));
                conn.setRequestProperty("x-access-source", "app");
            }

            int code = conn.getResponseCode();
            if (code != 200) {
                Log.e(TAG, "download failed: HTTP " + code);
                return -1;
            }
            is = conn.getInputStream();
            fos = new FileOutputStream(destFile);
            byte[] buf = new byte[8192];
            int len;
            long total = 0;
            while ((len = is.read(buf)) != -1) {
                fos.write(buf, 0, len);
                total += len;
            }
            fos.flush();
            return total;
        } catch (Exception e) {
            Log.e(TAG, "downloadFile failed: " + songId, e);
            if (destFile.exists()) destFile.delete();
            return -1;
        } finally {
            closeQuietly(fos);
            closeQuietly(is);
            if (conn != null) conn.disconnect();
        }
    }

    /** 缓存失效(曲目库变更、切换用户时调用) */
    public void clearCache() {
        allSongsCache = null;
        allAlbumsCache = null;
    }

    private void closeQuietly(java.io.Closeable c) {
        if (c != null) {
            try {
                c.close();
            } catch (Exception ignored) {
            }
        }
    }
}
