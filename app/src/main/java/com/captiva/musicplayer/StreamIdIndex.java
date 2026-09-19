package com.captiva.musicplayer;

import android.content.Context;
import android.util.Log;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 路径 → Navidrome streamId 持久化索引
 *
 * 背景:
 *   MusicScanner 扫描本地文件重建 MusicBean 时,只认得到文件路径与媒体元数据,
 *   拿不到 Navidrome 的 streamId(服务端身份)。而 MusicSyncManager 在下载时
 *   恰好同时握有 streamId 与落盘路径。本类把"规范化路径 → streamId"持久化下来,
 *   让 MusicScanner 在扫描时按路径回填 streamId,使 MainActivity.getDedupKey
 *   的 net_<streamId> 分支生效 —— 等价于 Navidrome 的 Persistent ID 稳定去重,
 *   且零额外计算(比本机音频指纹在车机上可行得多)。
 *
 * 线程安全:
 *   所有读写均在静态锁 LOCK 上同步。build() 在同步线程(后台)调用并落盘,
 *   ensureLoaded()/lookup() 在扫描线程(后台)调用,均不占用主线程做文件 IO。
 */
public class StreamIdIndex {

    private static final String TAG = "StreamIdIndex";
    private static final String INDEX_FILE = "streamid_index.json";

    private static final Object LOCK = new Object();
    private static Map<String, String> map = new HashMap<String, String>();
    private static boolean loaded = false;

    private StreamIdIndex() {
    }

    /**
     * 用同步列表(每个 song 带 streamId)重建索引并落盘。
     * 在 MusicSyncManager.sync() 取得全部歌曲后立即调用,
     * 此时无论已下载/已存在/待下载,路径与 streamId 的对应关系都已确定。
     */
    public static void build(Context context, List<MusicBean> songs, String syncPath) {
        if (context == null || songs == null || syncPath == null) {
            return;
        }
        Map<String, String> newMap = new HashMap<String, String>();
        for (MusicBean song : songs) {
            if (song == null) {
                continue;
            }
            String sid = song.getStreamId();
            if (sid == null || sid.isEmpty()) {
                continue;
            }
            String key = MusicSyncManager.localPathKey(song, syncPath);
            if (key == null || key.isEmpty()) {
                continue;
            }
            newMap.put(key, sid);
        }
        synchronized (LOCK) {
            map = newMap;
            loaded = true;
            save(context, newMap);
        }
        Log.d(TAG, "索引已重建: " + newMap.size() + " 条 路径→streamId");
    }

    /** 按规范化路径查 streamId;查不到返回 null */
    public static String lookup(Context context, String normalizedPath) {
        if (normalizedPath == null || normalizedPath.isEmpty()) {
            return null;
        }
        synchronized (LOCK) {
            ensureLoaded(context);
            return map.get(normalizedPath);
        }
    }

    /** 确保索引已加载(最多加载一次) */
    private static void ensureLoaded(Context context) {
        if (loaded) {
            return;
        }
        load(context);
        loaded = true;
    }

    private static void load(Context context) {
        map = new HashMap<String, String>();
        if (context == null) {
            return;
        }
        File file = new File(context.getFilesDir(), INDEX_FILE);
        if (!file.exists() || file.length() == 0) {
            return;
        }
        InputStreamReader reader = null;
        try {
            StringBuilder sb = new StringBuilder();
            reader = new InputStreamReader(new FileInputStream(file), "UTF-8");
            char[] buf = new char[4096];
            int len;
            while ((len = reader.read(buf)) != -1) {
                sb.append(buf, 0, len);
            }
            JSONObject root = new JSONObject(sb.toString());
            JSONObject m = root.optJSONObject("map");
            if (m != null) {
                java.util.Iterator<String> it = m.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    String v = m.optString(k, "");
                    if (!v.isEmpty()) {
                        map.put(k, v);
                    }
                }
            }
            Log.d(TAG, "索引已加载: " + map.size() + " 条");
        } catch (Exception e) {
            Log.w(TAG, "加载 streamId 索引失败(忽略,回退启发式去重)", e);
            map = new HashMap<String, String>();
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private static void save(Context context, Map<String, String> data) {
        if (context == null) {
            return;
        }
        File file = new File(context.getFilesDir(), INDEX_FILE);
        File tmp = new File(context.getFilesDir(), INDEX_FILE + ".tmp");
        OutputStreamWriter writer = null;
        try {
            JSONObject root = new JSONObject();
            JSONObject m = new JSONObject();
            for (java.util.Map.Entry<String, String> e : data.entrySet()) {
                m.put(e.getKey(), e.getValue());
            }
            root.put("map", m);
            root.put("count", data.size());
            writer = new OutputStreamWriter(new FileOutputStream(tmp), "UTF-8");
            writer.write(root.toString());
            writer.flush();
            writer.close();
            writer = null;
            if (file.exists()) {
                file.delete();
            }
            tmp.renameTo(file);
        } catch (Exception e) {
            Log.e(TAG, "保存 streamId 索引失败", e);
        } finally {
            if (writer != null) {
                try {
                    writer.close();
                } catch (Exception ignored) {
                }
            }
        }
    }
}
