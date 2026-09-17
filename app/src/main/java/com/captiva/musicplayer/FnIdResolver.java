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

    /** 解析服务地址。5ddd.com 与 fnos.net 都能解析,主用前者,失败自动切后者 */
    private static final String[] CON_HOSTS = {"https://5ddd.com", "https://fnos.net"};
    private static final String CON_PATH = "/api/v1/fn/con";

    private static final String PREFIX = "NDzZTVxnRKP8Z0jXg1VAMonaG8akvh";
    private static final String APIKEY = "zIGtkc3dqZnJpd29qZXJqa2w7c";

    private static final int TIMEOUT_MS = 6000;

    /** 最近一次解析失败的原因(给设置页显示,避免只看到一句"连接失败") */
    private static volatile String lastError = null;
    /** 最近一次探测失败的原因 */
    private static volatile String lastProbeError = null;

    /** 取解析失败原因;成功时为 null */
    public static String getLastError() {
        return lastError;
    }

    /** 取探测失败原因;成功时为 null */
    public static String getLastProbeError() {
        return lastProbeError;
    }

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
        lastError = null;
        if (fnId == null) {
            lastError = "FN ID 为空";
            return out;
        }
        fnId = fnId.trim();
        if (fnId.isEmpty()) {
            lastError = "FN ID 为空";
            return out;
        }

        try {
            String body = "{\"fnId\":\"" + fnId + "\"}";
            String resp = null;
            // 逐个解析服务尝试(5ddd.com → fnos.net),拿到有效应答即停
            for (String host : CON_HOSTS) {
                resp = post(host + CON_PATH, body);
                if (resp != null && !resp.trim().isEmpty()) {
                    try {
                        JSONObject probe = new JSONObject(resp);
                        // code=3000037/3000006 是业务层"查不到",换域名也没用,直接判定失败
                        int c = probe.optInt("code", -1);
                        if (c == 0) break;
                        if (c == 3000037 || c == 3000006) {
                            lastError = describeCode(c, fnId);
                            return out;
                        }
                    } catch (Exception ignored) {
                    }
                }
            }
            if (resp == null || resp.trim().isEmpty()) {
                if (lastError == null) {
                    lastError = "解析服务无响应(" + CON_HOSTS[0] + ")";
                }
                return out;
            }
            JSONObject root = new JSONObject(resp);
            int code = root.optInt("code", -1);
            if (code != 0) {
                lastError = describeCode(code, fnId);
                return out;
            }

            JSONObject d = root.optJSONObject("data");
            if (d == null) {
                lastError = "解析服务返回结构异常(data 为空)";
                return out;
            }
            // 解析成功,清掉过程中可能留下的中间态错误(例如第一个域名试错时记下的)
            lastError = null;

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
            // 解析失败一律返回空列表,由调用方提示用户;原因记下来给设置页显示。
            // 注意不要覆盖更具体的原因(例如 post() 里已记下的 "HTTP 400")
            if (lastError == null) {
                lastError = describe(e);
            }
        }
        return out;
    }

    /**
     * 把服务端业务码翻译成用户能照着改的提示。
     *
     * 实测:
     *   正确 ID   → code 0
     *   k1483162508 → code 3000037 Not Found Error(格式合法但这台设备查不到)
     *   zzzzzzzzzz  → code 3000006
     */
    private static String describeCode(int code, String fnId) {
        if (code == 3000037) {
            return "FN ID「" + fnId + "」查不到(code 3000037):飞牛服务器没有这台设备。\n"
                    + "注意 FN ID 不是 DDNS 域名里的那段数字。\n"
                    + "正确位置:飞牛 App → 设置 → 远程访问 → FN ID(形如 k495378412)";
        }
        if (code == 3000006) {
            return "FN ID「" + fnId + "」不存在或格式不对(code 3000006)";
        }
        return "解析失败(code " + code + ")";
    }

    /** 把异常翻译成看得懂的一句话 */
    private static String describe(Exception e) {
        if (TlsCompat.isTlsError(e)) {
            return "TLS 握手失败(系统 SSL 版本过旧)";
        }
        String m = e.getMessage();
        if (m == null || m.trim().isEmpty()) m = e.getClass().getSimpleName();
        return m;
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
        lastProbeError = null;
        if (list == null) return null;
        if (list.isEmpty()) {
            lastProbeError = "没有可用的候选地址";
            return null;
        }
        for (Addr a : list) {
            if (a == null || a.url == null) continue;
            if (probe(a.url, a.relay)) {
                lastProbeError = null;
                return a;
            }
        }
        if (lastProbeError == null) lastProbeError = "所有候选地址均不可达";
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
            conn = TlsCompat.open(probeUrl);
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Accept", "application/json");
            if (relay) conn.setRequestProperty("Cookie", "mode=relay");
            conn.setInstanceFollowRedirects(false);
            int code = conn.getResponseCode();
            // 中继:2xx/3xx 都算可达(后面实际请求会带 token)
            if (relay) {
                if (code >= 200 && code < 400) return true;
                lastProbeError = "中继 " + u + " 返回 HTTP " + code;
                return false;
            }
            if (code != 200) {
                lastProbeError = u + " 返回 HTTP " + code;
                return false;
            }
            String s = readAll(conn.getInputStream());
            JSONObject o = new JSONObject(s);
            boolean ok = o.optInt("code", -1) == 0;
            if (!ok) lastProbeError = u + " 应答非音乐服务(可能被网关/反代拦截)";
            return ok;
        } catch (Exception e) {
            lastProbeError = baseUrl + " → " + describe(e);
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

        HttpURLConnection conn = TlsCompat.open(urlStr);
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
            if (code < 200 || code >= 300) {
                lastError = "解析服务返回 HTTP " + code;
            }
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
