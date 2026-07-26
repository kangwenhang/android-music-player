package com.captiva.musicplayer;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Navidrome / Subsonic API 客户端
 * 兼容 Subsonic API 1.16.1
 * 支持:ping / getAlbumList2 / getAlbum / search3 / getCoverArt / stream
 *
 * 认证方式:token = MD5(password + salt),salt 为随机六位十六进制
 */
public class NavidromeApi {

    private static final String TAG = "NavidromeApi";
    private static final String API_VERSION = "1.16.1";
    private static final String CLIENT_NAME = "CaptivaMusic";
    private static final int CONNECT_TIMEOUT = 10000;
    private static final int READ_TIMEOUT = 15000;

    private final String serverUrl;
    private final String username;
    private final String password;

    public NavidromeApi(String serverUrl, String username, String password) {
        // 统一去掉末尾斜杠
        if (serverUrl != null && serverUrl.endsWith("/")) {
            serverUrl = serverUrl.substring(0, serverUrl.length() - 1);
        }
        this.serverUrl = serverUrl;
        this.username = username;
        this.password = password;
    }

    // ==================== 认证相关 ====================

    /** 生成随机 salt(6 位十六进制) */
    private String generateSalt() {
        SecureRandom rnd = new SecureRandom();
        byte[] bytes = new byte[3];
        rnd.nextBytes(bytes);
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b & 0xFF));
        }
        return sb.toString();
    }

    /** 计算 MD5(password + salt) */
    private String md5Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(input.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b & 0xFF));
            }
            return sb.toString();
        } catch (Exception e) {
            Log.e(TAG, "MD5 failed", e);
            return "";
        }
    }

    /** 构建带认证参数的 base query string */
    private String authParams() {
        String salt = generateSalt();
        String token = md5Hex(password + salt);
        return "u=" + URLEncoder.encode(username)
                + "&t=" + token
                + "&s=" + salt
                + "&v=" + API_VERSION
                + "&c=" + CLIENT_NAME
                + "&f=json";
    }

    /** 构建完整 API URL */
    private String apiUrl(String endpoint, String extraParams) {
        StringBuilder sb = new StringBuilder();
        sb.append(serverUrl);
        sb.append("/rest/");
        sb.append(endpoint);
        sb.append(".view?");
        sb.append(authParams());
        if (extraParams != null && !extraParams.isEmpty()) {
            sb.append("&").append(extraParams);
        }
        return sb.toString();
    }

    // ==================== HTTP 请求 ====================

    /** 执行 GET 请求,返回响应体字符串 */
    private String httpGet(String urlStr) throws Exception {
        HttpURLConnection conn = null;
        InputStream is = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(CONNECT_TIMEOUT);
            conn.setReadTimeout(READ_TIMEOUT);
            conn.setDoInput(true);

            int code = conn.getResponseCode();
            if (code != 200) {
                throw new Exception("HTTP " + code);
            }
            is = conn.getInputStream();
            BufferedReader reader = new BufferedReader(new InputStreamReader(is, "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            return sb.toString();
        } finally {
            if (is != null) try { is.close(); } catch (Exception ignored) {}
            if (conn != null) conn.disconnect();
        }
    }

    // ==================== API 方法 ====================

    /**
     * ping:测试服务器连通性
     * @return true 如果服务器正常响应
     */
    public boolean ping() {
        try {
            String json = httpGet(apiUrl("ping", null));
            JSONObject root = new JSONObject(json);
            JSONObject resp = root.optJSONObject("subsonic-response");
            if (resp != null) {
                return "ok".equals(resp.optString("status"));
            }
        } catch (Exception e) {
            Log.e(TAG, "ping failed", e);
        }
        return false;
    }

    /**
     * getAlbumList2:获取专辑列表
     * @param type  newest | random | frequent | recent | alphabeticalByName
     * @param count 返回数量
     * @return 专辑列表
     */
    public List<AlbumBean> getAlbumList(String type, int count) {
        return getAlbumList(type, count, 0);
    }

    /**
     * getAlbumList2:获取专辑列表(支持分页)
     * @param type   newest | random | frequent | recent | alphabeticalByName
     * @param size   每页数量
     * @param offset 偏移量
     * @return 该页专辑列表
     */
    public List<AlbumBean> getAlbumList(String type, int size, int offset) {
        List<AlbumBean> list = new ArrayList<>();
        try {
            String params = "type=" + type + "&size=" + size + "&offset=" + offset;
            String json = httpGet(apiUrl("getAlbumList2", params));
            JSONObject root = new JSONObject(json);
            JSONObject resp = root.optJSONObject("subsonic-response");
            if (resp != null && "ok".equals(resp.optString("status"))) {
                JSONObject albumList = resp.optJSONObject("albumList2");
                if (albumList != null) {
                    JSONArray albums = albumList.optJSONArray("album");
                    if (albums != null) {
                        for (int i = 0; i < albums.length(); i++) {
                            JSONObject a = albums.optJSONObject(i);
                            if (a != null) {
                                AlbumBean bean = new AlbumBean();
                                bean.setId(a.optString("id"));
                                bean.setName(a.optString("name"));
                                bean.setArtist(a.optString("artist"));
                                bean.setCoverArtId(a.optString("coverArt"));
                                bean.setSongCount(a.optInt("songCount", 0));
                                bean.setDuration(a.optLong("duration", 0));
                                list.add(bean);
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "getAlbumList failed", e);
        }
        return list;
    }

    /**
     * getAlbum:获取专辑详情(含歌曲列表)
     * @param albumId 专辑 ID
     * @return 歌曲列表
     */
    public List<MusicBean> getAlbum(String albumId) {
        List<MusicBean> list = new ArrayList<>();
        try {
            String params = "id=" + URLEncoder.encode(albumId);
            String json = httpGet(apiUrl("getAlbum", params));
            JSONObject root = new JSONObject(json);
            JSONObject resp = root.optJSONObject("subsonic-response");
            if (resp != null && "ok".equals(resp.optString("status"))) {
                JSONObject album = resp.optJSONObject("album");
                if (album != null) {
                    JSONArray songs = album.optJSONArray("song");
                    list = parseSongs(songs);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "getAlbum failed", e);
        }
        return list;
    }

    /**
     * search3:搜索歌曲
     * @param query 搜索关键词
     * @param count 返回数量
     * @return 匹配的歌曲列表
     */
    public List<MusicBean> search(String query, int count) {
        List<MusicBean> list = new ArrayList<>();
        try {
            String params = "query=" + URLEncoder.encode(query, "UTF-8")
                    + "&songCount=" + count
                    + "&albumCount=0"
                    + "&artistCount=0";
            String json = httpGet(apiUrl("search3", params));
            JSONObject root = new JSONObject(json);
            JSONObject resp = root.optJSONObject("subsonic-response");
            if (resp != null && "ok".equals(resp.optString("status"))) {
                JSONObject result = resp.optJSONObject("searchResult3");
                if (result != null) {
                    JSONArray songs = result.optJSONArray("song");
                    list = parseSongs(songs);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "search failed", e);
        }
        return list;
    }

    /**
     * getRandomSongs:获取随机歌曲
     * @param count 返回数量
     */
    public List<MusicBean> getRandomSongs(int count) {
        List<MusicBean> list = new ArrayList<>();
        try {
            String params = "size=" + count;
            String json = httpGet(apiUrl("getRandomSongs", params));
            JSONObject root = new JSONObject(json);
            JSONObject resp = root.optJSONObject("subsonic-response");
            if (resp != null && "ok".equals(resp.optString("status"))) {
                JSONObject result = resp.optJSONObject("randomSongs");
                if (result != null) {
                    JSONArray songs = result.optJSONArray("song");
                    list = parseSongs(songs);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "getRandomSongs failed", e);
        }
        return list;
    }

    /**
     * getStarred2:获取收藏的歌曲
     */
    public List<MusicBean> getStarredSongs() {
        List<MusicBean> list = new ArrayList<>();
        try {
            String json = httpGet(apiUrl("getStarred2", null));
            JSONObject root = new JSONObject(json);
            JSONObject resp = root.optJSONObject("subsonic-response");
            if (resp != null && "ok".equals(resp.optString("status"))) {
                JSONObject result = resp.optJSONObject("starred2");
                if (result != null) {
                    JSONArray songs = result.optJSONArray("song");
                    list = parseSongs(songs);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "getStarred2 failed", e);
        }
        return list;
    }

    /**
     * 获取全部歌曲(分页获取,无数量限制)
     * 使用 search3 接口以空查询匹配全部,分页拉取直到没有更多
     * @return 全部歌曲列表
     */
    public List<MusicBean> getAllSongs() {
        List<MusicBean> all = new ArrayList<>();
        try {
            int pageSize = 500;
            int offset = 0;
            while (true) {
                List<MusicBean> page = getSongsPage(offset, pageSize);
                if (page == null || page.isEmpty()) {
                    break;
                }
                all.addAll(page);
                if (page.size() < pageSize) {
                    break;
                }
                offset += pageSize;
            }
            Log.d(TAG, "getAllSongs: 共获取 " + all.size() + " 首");
        } catch (Exception e) {
            Log.e(TAG, "getAllSongs failed", e);
        }
        return all;
    }

    /**
     * 分页获取歌曲(单次请求)
     * 使用 search3 接口以空查询匹配全部,分页拉取
     * 注意:search3 的空查询搜索可能有最大返回限制和分页不精确问题,
     * 建议用 getSongsPageByAlbum 替代以获得准确数量
     * @param offset 偏移量
     * @param count 每页数量
     * @return 该页歌曲列表
     */
    public List<MusicBean> getSongsPage(int offset, int count) {
        List<MusicBean> list = new ArrayList<>();
        try {
            String params = "query="
                    + "&songCount=" + count
                    + "&songOffset=" + offset
                    + "&albumCount=0"
                    + "&artistCount=0";
            String json = httpGet(apiUrl("search3", params));
            JSONObject root = new JSONObject(json);
            JSONObject resp = root.optJSONObject("subsonic-response");
            if (resp != null && "ok".equals(resp.optString("status"))) {
                JSONObject result = resp.optJSONObject("searchResult3");
                if (result != null) {
                    JSONArray songs = result.optJSONArray("song");
                    list = parseSongs(songs);
                }
            }
            Log.d(TAG, "getSongsPage: offset=" + offset + " count=" + count + " got=" + list.size());
        } catch (Exception e) {
            Log.e(TAG, "getSongsPage failed", e);
        }
        return list;
    }

    /**
     * 通过专辑列表分页获取全部歌曲(准确,无重复)
     * 流程:按字母排序分页获取专辑 → 逐专辑获取歌曲
     * 相比 search3 空查询,此方式能准确获取全部歌曲且不会重复
     *
     * @param albumOffset 专辑偏移量(用于分页)
     * @param albumCount   每次获取的专辑数量
     * @return 该批专辑下的所有歌曲
     */
    public List<MusicBean> getSongsByAlbumPage(int albumOffset, int albumCount) {
        List<MusicBean> list = new ArrayList<>();
        try {
            // 1. 获取一批专辑
            List<AlbumBean> albums = getAlbumList("alphabeticalByName", albumCount, albumOffset);
            if (albums == null || albums.isEmpty()) {
                Log.d(TAG, "getSongsByAlbumPage: no more albums at offset=" + albumOffset);
                return list;
            }

            // 2. 逐专辑获取歌曲
            for (AlbumBean album : albums) {
                if (album.getId() == null || album.getId().isEmpty()) {
                    continue;
                }
                List<MusicBean> albumSongs = getAlbum(album.getId());
                if (albumSongs != null && !albumSongs.isEmpty()) {
                    list.addAll(albumSongs);
                }
            }

            Log.d(TAG, "getSongsByAlbumPage: albumOffset=" + albumOffset
                    + " albumCount=" + albumCount
                    + " albums=" + albums.size()
                    + " songs=" + list.size());
        } catch (Exception e) {
            Log.e(TAG, "getSongsByAlbumPage failed", e);
        }
        return list;
    }

    /**
     * 获取全部专辑列表(分页拉取到底),用于统计总歌曲数
     * 每个专辑的 songCount 累加即为总歌曲数
     * @return 全部专辑列表
     */
    public List<AlbumBean> getAllAlbums() {
        List<AlbumBean> all = new ArrayList<>();
        try {
            int offset = 0;
            int size = 50;
            while (true) {
                List<AlbumBean> page = getAlbumList("alphabeticalByName", size, offset);
                if (page == null || page.isEmpty()) {
                    break;
                }
                all.addAll(page);
                if (page.size() < size) {
                    break;
                }
                offset += size;
            }
            Log.d(TAG, "getAllAlbums: 共 " + all.size() + " 张专辑");
        } catch (Exception e) {
            Log.e(TAG, "getAllAlbums failed", e);
        }
        return all;
    }

    /**
     * getLyricsBySongId:获取歌曲歌词(结构化,支持同步)
     * Subsonic API 1.16.1+
     * @param songId 歌曲 ID
     * @return 歌词列表,可能为空
     */
    public List<LrcEntry> getLyricsBySongId(String songId) {
        List<LrcEntry> list = new ArrayList<>();
        try {
            String params = "id=" + URLEncoder.encode(songId, "UTF-8");
            String json = httpGet(apiUrl("getLyricsBySongId", params));
            JSONObject root = new JSONObject(json);
            JSONObject resp = root.optJSONObject("subsonic-response");
            if (resp != null && "ok".equals(resp.optString("status"))) {
                JSONObject lyricsList = resp.optJSONObject("lyricsList");
                if (lyricsList != null) {
                    // 优先取 structuredLyrics 中 synced=true 的
                    JSONArray structured = lyricsList.optJSONArray("structuredLyrics");
                    if (structured != null) {
                        for (int i = 0; i < structured.length(); i++) {
                            JSONObject sl = structured.optJSONObject(i);
                            if (sl != null && sl.optBoolean("synced", false)) {
                                JSONArray lines = sl.optJSONArray("line");
                                if (lines != null) {
                                    for (int j = 0; j < lines.length(); j++) {
                                        JSONObject line = lines.optJSONObject(j);
                                        if (line != null) {
                                            String text = line.optString("value", "");
                                            long start = line.optLong("start", -1);
                                            if (start >= 0) {
                                                list.add(new LrcEntry(start, text));
                                            }
                                        }
                                    }
                                    // 找到同步歌词就返回
                                    if (!list.isEmpty()) {
                                        Collections.sort(list);
                                        return list;
                                    }
                                }
                            }
                        }
                        // 没有同步歌词,尝试非同步歌词(无时间戳)
                        for (int i = 0; i < structured.length(); i++) {
                            JSONObject sl = structured.optJSONObject(i);
                            if (sl != null) {
                                JSONArray lines = sl.optJSONArray("line");
                                if (lines != null && lines.length() > 0) {
                                    // 非同步歌词:按固定间隔生成时间戳
                                    long interval = 5000; // 每 5 秒一行
                                    for (int j = 0; j < lines.length(); j++) {
                                        JSONObject line = lines.optJSONObject(j);
                                        if (line != null) {
                                            String text = line.optString("value", "");
                                            list.add(new LrcEntry(j * interval, text));
                                        }
                                    }
                                    if (!list.isEmpty()) {
                                        return list;
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "getLyricsBySongId failed", e);
        }
        return list;
    }

    /**
     * getLyrics:获取歌曲歌词(纯文本,通过艺术家和标题)
     * 作为 getLyricsBySongId 的回退方案
     * @param artist 艺术家
     * @param title 歌曲标题
     * @return 歌词文本,可能为 null
     */
    public String getLyrics(String artist, String title) {
        try {
            String params = "artist=" + URLEncoder.encode(artist, "UTF-8")
                    + "&title=" + URLEncoder.encode(title, "UTF-8");
            String json = httpGet(apiUrl("getLyrics", params));
            JSONObject root = new JSONObject(json);
            JSONObject resp = root.optJSONObject("subsonic-response");
            if (resp != null && "ok".equals(resp.optString("status"))) {
                JSONObject lyrics = resp.optJSONObject("lyrics");
                if (lyrics != null) {
                    return lyrics.optString("value", null);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "getLyrics failed", e);
        }
        return null;
    }

    // ==================== URL 构建 ====================

    /** 构建封面图 URL */
    public String getCoverArtUrl(String coverArtId, int size) {
        if (coverArtId == null || coverArtId.isEmpty()) {
            return null;
        }
        StringBuilder params = new StringBuilder();
        params.append("id=").append(URLEncoder.encode(coverArtId));
        if (size > 0) {
            params.append("&size=").append(size);
        }
        return apiUrl("getCoverArt", params.toString());
    }

    /** 构建流式播放 URL */
    public String getStreamUrl(String songId) {
        if (songId == null || songId.isEmpty()) {
            return null;
        }
        String params = "id=" + URLEncoder.encode(songId);
        return apiUrl("stream", params);
    }

    // ==================== 解析辅助 ====================

    /** 从 JSONArray 解析歌曲列表 */
    private List<MusicBean> parseSongs(JSONArray songs) {
        List<MusicBean> list = new ArrayList<>();
        if (songs == null) {
            return list;
        }
        for (int i = 0; i < songs.length(); i++) {
            JSONObject s = songs.optJSONObject(i);
            if (s != null) {
                MusicBean bean = new MusicBean();
                bean.setNetwork(true);
                bean.setStreamId(s.optString("id"));
                bean.setTitle(s.optString("title"));
                bean.setArtist(s.optString("artist"));
                bean.setAlbum(s.optString("album"));
                bean.setDuration(s.optLong("duration", 0) * 1000); // Subsonic 返回秒,转毫秒
                bean.setCoverArtId(s.optString("coverArt"));
                // 预生成流式 URL
                bean.setStreamUrl(getStreamUrl(s.optString("id")));
                list.add(bean);
            }
        }
        return list;
    }
}
