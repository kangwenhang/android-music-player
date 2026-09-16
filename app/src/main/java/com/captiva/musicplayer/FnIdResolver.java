package com.captiva.musicplayer;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 飞牛 FN ID 解析器
 *
 * 作用:把用户填的 FN ID(如 k495378412)解析成一组可访问地址,并挑出当前真正可用的那个。
 *
 * 逆向依据(对照开源客户端 fn-music-tv / QiaoKes,已确认可行):
 *   POST {CON_HOST}/api/v1/fn/con
 *   headers: authx = nonce=<6位>&timestamp=<ms>&sign=<md5>
 *   body  : {"fnId":"<id>"}
 *
 * 签名(与飞牛音乐 API 同一套 authx 结构):
 *   sign = MD5( PREFIX _ "/api/v1/fn/con" _ nonce _ timestamp _ MD5(body) _ APIKEY )
 *   注意:开源客户端只发 authx,不发 fn-sign。早期 Web 前端的 fn-sign 是另一个端点用的,这里不需要。
 *
 * 返回 data:
 *   ipv4[]        内网 IPv4
 *   publicIpv4[]  公网 IPv4
 *   publicIpv6[]  公网 IPv6
 *   fn[]          飞牛中继地址(形如 xxx.5ddd.com,可能带 :443)
 *   port          {httpPort:5666, httpsPort:5667}
 *
 * 候选顺序(实测可用的优先):内网 IPv4 → 公网 IPv6 → 公网 IPv4 → 飞牛中继(https)
 *   - 中继候选探测必须带 Cookie: mode=relay,否则被挡回 SPA
 *   - 中继实际访问音乐 API 时也要带 Cookie: mode=relay; music-token=<token>(见 FnMusicApi.setRelayMode)
 *
 * 用法(必须在子线程):
 *   FnIdResolver.Addr a = FnIdResolver.pickReachableAddr(FnIdResolver.resolve(fnId));
 *   if (a != null) {
 *       FnMusicApi api = new FnMusicApi(a.url, user, pass);
 *       api.setRelayMode(a.relay);   // 中继模式要带 mode=relay cookie
 *       ...
 *   }
 */
public class FnIdResolver {

    /** 解析服务地址。开源客户端实测用 5ddd.com(fnos.net 同样能解析,但 5ddd 是官方中继域名) */
    private static final String CON_HOST = "https://5ddd.com";
    private static final String CON_PATH = "/api/v1/fn/con";

    private static final String PREFIX = "NDzZTVxnRKP8Z0jXg1VAMonaG8akvh";
    private static final String APIKEY = "zIGtkc3dqZnJpd29qZXJqa2w7c";

    private static final int TIMEOUT_MS = 6000;

    /** 一个候选地址 */
    public static class Addr {
        public String url;    // 完整 base URL,如 http://192.168.2.250:5666 或 https://xxx.5ddd.com
        public String type;   // lan / publicIpv4 / publicIpv6 / relay
        public boolean relay; // 是否走飞牛中继(需带 mode=relay cookie)

        public Addr(String url, String type, boolean relay) {
            this.url = url;
            this.type = type;
            this.relay = relay;
        }

        @Override
        public String toString() {
            return (relay ? "[relay] " : "") + type + " " + url;
        }
    }

    /**
     * 解析 FN ID,返回候选地址列表(按推荐顺序:内网 → 公网IPv6 → 公网IPv4 → 中继)。
     * 解析失败或 FN ID 不存在时返回空列表(不抛异常)。
     */
    public static List<Addr> resolve(String fnId) {
        List<Addr> out = new ArrayList<>();
        if (fnId == null) return out;
        fnId = fnId.trim();
        if (fnId.isEmpty()) return out;

        try {
            String body = "{\"fnId\":\"" + fnId + "\"}";
            JSONObject root = new JSONObject(post(CON_HOST + CON_PATH, body));
            if (root.optInt("code", -1) != 0) return out;

            JSONObject d = root.optJSONObject("data");
            if (d == null) return out;

            String httpPort = "5666";
            String httpsPort = "5667";
            JSONObject port = d.optJSONObject("port");
            if (port != null) {
                if (port.optString("httpPort", "").length() > 0) httpPort = port.optString("httpPort");
                if (port.optString("httpsPort", "").length() > 0) httpsPort = port.optString("httpsPort");
            }

            // 1) 内网 IPv4(http 直连,车机在家时最快)
            addAddrs(out, d.optJSONArray("ipv4"), "lan", false, "http", httpPort, false);
            // 2) 公网 IPv6(https,注意加方括号)
            addAddrs(out, d.optJSONArray("publicIpv6"), "publicIpv6", false, "https", httpsPort, true);
            // 3) 公网 IPv4(http 直连,不限速)
            addAddrs(out, d.optJSONArray("publicIpv4"), "publicIpv4", false, "http", httpPort, false);
            // 4) 飞牛中继(https 443,带 mode=relay cookie)
            List<String> relays = toStringList(d.optJSONArray("fn"));
            if (relays.isEmpty()) relays.add(fnId + ".5ddd.com"); // 兜底
            for (String r : relays) {
                String host = r;
                int colon = r.lastIndexOf(':');
                if (colon >= 0 && r.substring(colon + 1).matches("\\d+")) {
                    host = r.substring(0, colon); // 去掉 :443
                }
                out.add(new Addr("https://" + host, "relay", true));
            }

        } catch (Exception e) {
            // 解析失败一律返回空列表,由调用方提示用户
        }
        return out;
    }

    private static void addAddrs(List<Addr> out, JSONArray arr, String type,
                                 boolean relay, String scheme, String port, boolean bracket) {
        if (arr == null) return;
        for (int i = 0; i < arr.length(); i++) {
            String v = arr.optString(i, "");
            if (v == null || v.length() == 0) continue;
            String host = bracket ? ("[" + v + "]") : v;
            String p = (port == null || port.length() == 0) ? "" : (":" + port);
            if (v.indexOf(':') >= 0 && !bracket) host = v; // 自带端口则原样
            out.add(new Addr(scheme + "://" + host + p, type, relay));
        }
    }

    private static List<String> toStringList(JSONArray arr) {
        List<String> list = new ArrayList<>();
        if (arr == null) return list;
        for (int i = 0; i < arr.length(); i++) {
            String v = arr.optString(i, "");
            if (v != null && v.length() > 0) list.add(v);
        }
        return list;
    }

    /**
     * 逐个探测候选地址,返回第一个能用的 Addr(含 relay 标记);全部不可达返回 null。
     */
    public static Addr pickReachableAddr(List<Addr> list) {
        if (list == null) return null;
        for (Addr a : list) {
            if (a == null || a.url == null) continue;
            if (probe(a.url, a.relay)) return a;
        }
        return null;
    }

    /** 兼容旧调用:返回第一个可达的 base URL(不含 relay 信息) */
    public static String pickReachable(List<Addr> list) {
        Addr a = pickReachableAddr(list);
        return a == null ? null : a.url;
    }

    /** 探测某个 base URL 是否可用。relay 候选带 Cookie: mode=relay */
    public static boolean probe(String baseUrl, boolean relay) {
        HttpURLConnection conn = null;
        try {
            String u = baseUrl;
            if (u.endsWith("/")) u = u.substring(0, u.length() - 1);
            // 中继只验证连通性(返回的是 SPA/重定向,不是 JSON code0);直连验证音乐 init 接口
            String probeUrl = relay ? u : (u + "/music/api/v1/initialization/state");
            conn = (HttpURLConnection) new URL(probeUrl).openConnection();
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Accept", "application/json");
            if (relay) conn.setRequestProperty("Cookie", "mode=relay");
            conn.setInstanceFollowRedirects(false);
            int code = conn.getResponseCode();
            // 中继:2xx/3xx 都算可达(后面实际请求会带 token)
            if (relay) return code >= 200 && code < 400;
            if (code != 200) return false;
            String s = readAll(conn.getInputStream());
            JSONObject o = new JSONObject(s);
            return o.optInt("code", -1) == 0;
        } catch (Exception e) {
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    // ==================== 内部实现 ====================

    private static String post(String urlStr, String body) throws Exception {
        long ts = System.currentTimeMillis();
        String nonce = String.valueOf(new Random().nextInt(900000) + 100000);
        String sign = md5(PREFIX + "_" + CON_PATH + "_" + nonce + "_" + ts + "_" + md5(body) + "_" + APIKEY);
        String authx = "nonce=" + nonce + "&timestamp=" + ts + "&sign=" + sign;

        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        try {
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("authx", authx);
            conn.getOutputStream().write(body.getBytes("UTF-8"));
            conn.getOutputStream().flush();
            int code = conn.getResponseCode();
            InputStream is = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
            return is == null ? "" : readAll(is);
        } finally {
            conn.disconnect();
        }
    }

    private static String readAll(InputStream is) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
        is.close();
        return new String(bos.toByteArray(), "UTF-8");
    }

    private static String md5(String s) throws Exception {
        MessageDigest md = MessageDigest.getInstance("MD5");
        byte[] b = md.digest(s.getBytes("UTF-8"));
        StringBuilder sb = new StringBuilder();
        for (byte x : b) {
            String h = Integer.toHexString(x & 0xff);
            if (h.length() == 1) sb.append('0');
            sb.append(h);
        }
        return sb.toString();
    }
}
