package com.captiva.musicplayer;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.List;

/**
 * 网络歌曲列表缓存
 * 将 Navidrome 歌曲列表序列化为 JSON 存到本地文件
 * 切换到网络模式时先从缓存加载(秒开),再后台从服务器更新
 */
public class SongCache {

    private static final String TAG = "SongCache";
    private static final String CACHE_FILE = "navidrome_songs.json";
    private static final String CACHE_FILE_TMP = "navidrome_songs.json.tmp";

    private final File cacheFile;

    public SongCache(Context context) {
        cacheFile = new File(context.getCacheDir(), CACHE_FILE);
    }

    /** 保存歌曲列表到缓存文件 */
    public void save(List<MusicBean> songs) {
        if (songs == null || songs.isEmpty()) {
            return;
        }
        // 先写临时文件,再重命名,防止写一半中断导致缓存损坏
        File tmpFile = new File(cacheFile.getParent(), CACHE_FILE_TMP);
        OutputStreamWriter writer = null;
        try {
            JSONArray arr = new JSONArray();
            for (MusicBean b : songs) {
                JSONObject obj = new JSONObject();
                obj.put("id", b.getId());
                obj.put("title", b.getTitle());
                obj.put("artist", b.getArtist());
                obj.put("album", b.getAlbum());
                obj.put("duration", b.getDuration());
                obj.put("data", b.getData());
                obj.put("uri", b.getUri());
                obj.put("network", b.isNetwork());
                obj.put("coverArtId", b.getCoverArtId() != null ? b.getCoverArtId() : "");
                obj.put("streamId", b.getStreamId() != null ? b.getStreamId() : "");
                obj.put("streamUrl", b.getStreamUrl() != null ? b.getStreamUrl() : "");
                arr.put(obj);
            }

            JSONObject root = new JSONObject();
            root.put("songs", arr);
            root.put("cachedAt", System.currentTimeMillis());
            root.put("count", songs.size());

            writer = new OutputStreamWriter(new FileOutputStream(tmpFile), "UTF-8");
            writer.write(root.toString());
            writer.flush();
            writer.close();
            writer = null;

            // 原子替换
            if (cacheFile.exists()) {
                cacheFile.delete();
            }
            tmpFile.renameTo(cacheFile);
            Log.d(TAG, "缓存已保存: " + songs.size() + " 首");
        } catch (Exception e) {
            Log.e(TAG, "保存缓存失败", e);
        } finally {
            if (writer != null) {
                try { writer.close(); } catch (Exception ignored) {}
            }
        }
    }

    /** 从缓存文件加载歌曲列表 */
    public List<MusicBean> load() {
        if (!cacheFile.exists()) {
            return null;
        }
        InputStreamReader reader = null;
        try {
            StringBuilder sb = new StringBuilder();
            reader = new InputStreamReader(new FileInputStream(cacheFile), "UTF-8");
            char[] buf = new char[4096];
            int len;
            while ((len = reader.read(buf)) != -1) {
                sb.append(buf, 0, len);
            }
            reader.close();
            reader = null;

            JSONObject root = new JSONObject(sb.toString());
            JSONArray arr = root.optJSONArray("songs");
            if (arr == null) {
                return null;
            }

            List<MusicBean> list = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.optJSONObject(i);
                if (obj == null) continue;
                MusicBean b = new MusicBean();
                b.setId(obj.optLong("id"));
                b.setTitle(obj.optString("title"));
                b.setArtist(obj.optString("artist"));
                b.setAlbum(obj.optString("album"));
                b.setDuration(obj.optLong("duration"));
                b.setData(obj.optString("data"));
                b.setUri(obj.optString("uri"));
                b.setNetwork(obj.optBoolean("network"));
                b.setCoverArtId(obj.optString("coverArtId"));
                b.setStreamId(obj.optString("streamId"));
                b.setStreamUrl(obj.optString("streamUrl"));
                list.add(b);
            }
            Log.d(TAG, "缓存已加载: " + list.size() + " 首");
            return list;
        } catch (Exception e) {
            Log.e(TAG, "加载缓存失败", e);
            return null;
        } finally {
            if (reader != null) {
                try { reader.close(); } catch (Exception ignored) {}
            }
        }
    }

    /** 缓存是否存在且有效 */
    public boolean exists() {
        return cacheFile.exists() && cacheFile.length() > 0;
    }

    /** 获取缓存时间戳(毫秒),0表示无缓存 */
    public long getCachedAt() {
        if (!cacheFile.exists()) return 0;
        try {
            InputStreamReader reader = null;
            try {
                StringBuilder sb = new StringBuilder();
                reader = new InputStreamReader(new FileInputStream(cacheFile), "UTF-8");
                char[] buf = new char[4096];
                int len;
                while ((len = reader.read(buf)) != -1) {
                    sb.append(buf, 0, len);
                }
                JSONObject root = new JSONObject(sb.toString());
                return root.optLong("cachedAt", 0);
            } finally {
                if (reader != null) reader.close();
            }
        } catch (Exception e) {
            return 0;
        }
    }

    /** 清除缓存 */
    public void clear() {
        if (cacheFile.exists()) {
            cacheFile.delete();
        }
    }
}
