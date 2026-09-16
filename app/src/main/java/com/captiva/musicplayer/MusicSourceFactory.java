package com.captiva.musicplayer;

import java.util.regex.Pattern;

/**
 * 音乐数据源工厂
 *
 * 根据配置中的 server_type 创建对应的 {@link MusicSourceApi} 实现。
 * 新增数据源时:1) 实现接口 2) 在此登记类型常量与创建分支。
 *
 * 飞牛额外支持 FN ID:用户只填 FN ID(如 k495378412),由本类解析出
 * 当前可达地址(内网 / 公网 IPv6 / 公网 IPv4 / 飞牛中继),并按需开启中继模式。
 */
public class MusicSourceFactory {

    /** Navidrome / Subsonic */
    public static final String TYPE_NAVIDROME = "navidrome";
    /** 飞牛 NAS 音乐服务(FN Music) */
    public static final String TYPE_FNMUSIC = "fnmusic";

    /**
     * FN ID 规则(与开源客户端 fn-music-tv 一致):字母数字开头,
     * 仅含 字母/数字/_/-,总长至少 6。
     * 注意:不含点号,所以 IP 与域名(huilong.xxx.fun / 192.168.1.10)都不会被误判成 FN ID。
     */
    private static final Pattern FN_ID = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9_-]{5,}$");

    private MusicSourceFactory() {
    }

    /**
     * 创建数据源实例
     *
     * @param type     服务器类型,见本类 TYPE_* 常量;未知值按 Navidrome 处理
     * @param serverUrl 服务器地址
     * @param username 用户名
     * @param password 密码
     */
    public static MusicSourceApi create(String type, String serverUrl, String username, String password) {
        if (TYPE_FNMUSIC.equals(type)) {
            return createFn(serverUrl, username, password, false);
        }
        return new NavidromeApi(serverUrl, username, password);
    }

    /**
     * 创建飞牛数据源,并显式指定是否走官方中继。
     *
     * @param relayMode true = 走飞牛中继(远程 FN ID 访问),请求会带 Cookie: mode=relay
     */
    public static MusicSourceApi createFn(String serverUrl, String username, String password, boolean relayMode) {
        FnMusicApi api = new FnMusicApi(serverUrl, username, password);
        api.setRelayMode(relayMode);
        return api;
    }

    /**
     * 判断输入是否像 FN ID(而不是 URL / IP / 域名)。
     * 判定:不含 "://"、不含 ":"、不含 "." 且符合 FN ID 正则。
     */
    public static boolean looksLikeFnId(String input) {
        if (input == null) return false;
        String v = input.trim();
        if (v.isEmpty() || v.contains("://") || v.contains(":") || v.contains(".")) return false;
        return FN_ID.matcher(v).matches();
    }

    /**
     * 解析 FN ID 并挑出当前真正可达的地址(必须在子线程调用,内部有联网探测)。
     *
     * @return 可达地址(含 relay 标记);全部不可达或 FN ID 无效时返回 null
     */
    public static FnIdResolver.Addr resolveFnId(String fnId) {
        return FnIdResolver.pickReachableAddr(FnIdResolver.resolve(fnId));
    }

    /** 类型的中文显示名 */
    public static String displayName(String type) {
        if (TYPE_FNMUSIC.equals(type)) {
            return "飞牛音乐 (FN Music)";
        }
        return "Navidrome / Subsonic";
    }
}
