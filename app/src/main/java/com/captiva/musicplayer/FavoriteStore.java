package com.captiva.musicplayer;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.List;

/**
 * 云端收藏歌单快照(独立持久化,按服务器类型隔离)。
 *
 * 设计动机(2026-10-11,康提出"歌单与歌曲分离"):
 *   收藏视图此前只是"主列表的一个过滤视角"——云端拉 starred 成功只留下
 *   streamId 集合,断网首开/云端缓存被清/未同步时,收藏歌单整个消失。
 *   歌单元数据就几 KB,应当作为独立数据源优先落地:每次云端收藏拉取成功
 *   都把完整快照(含标题/歌手/封面/streamUrl 等可播元数据)存到这里,
 *   断网时收藏夹从快照渲染,永远可见;已下载的歌还能本地播放。
 *
 * 序列化字段与 SongCache 完全一致(同一套 MusicBean 字段),便于维护。
 * 存 getFilesDir()(持久数据目录,系统不会随意清理)。
 */
public class FavoriteStore {

    private static final String TAG = "FavoriteStore";
    private static final String FILE_NAVIDROME = "starred_navidrome.json";
    private static final String FILE_FNMUSIC = "starred_fnmusic.json";

    private FavoriteStore() {
    }

    private static File fileFor(Context context, String serverType) {
        String name = MusicSourceFactory.TYPE_FNMUSIC.equals(serverType)
                ? FILE_FNMUSIC : FILE_NAVIDROME;
        return new File(context.getFilesDir(), name);
    }

    /** 保存云端收藏快照(原子写:先写 .tmp 再改名;建议在后台线程调用) */
    public static void save(Context context, String serverType, List<MusicBean> songs) {
        if (context == null || songs == null) {
            return;
        }
        File file = fileFor(context, serverType);
        File tmp = new File(file.getParentFile(), file.getName() + ".tmp");
        OutputStreamWriter writer = null;
        try {
            JSONArray arr = new JSONArray();
            for (MusicBean b : songs) {
                if (b == null) continue;
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
                obj.put("suffix", b.getLocalSuffix());
                obj.put("bitRate", b.getBitRate());
                arr.put(obj);
            }
            JSONObject root = new JSONObject();
            root.put("songs", arr);
            root.put("count", songs.size());
            root.put("savedAt", System.currentTimeMillis());

            writer = new OutputStreamWriter(new FileOutputStream(tmp), "UTF-8");
            writer.write(root.toString());
            writer.flush();
            writer.close();
            writer = null;
            if (file.exists()) {
                file.delete();
            }
            tmp.renameTo(file);
            Log.d(TAG, "收藏快照已保存[" + serverType + "]: " + songs.size() + " 首");
        } catch (Exception e) {
            Log.e(TAG, "保存收藏快照[" + serverType + "]失败", e);
        } finally {
            if (writer != null) {
                try { writer.close(); } catch (Exception ignored) {}
            }
        }
    }

    /** 读取云端收藏快照;无快照/解析失败返回 null(调用方退化到原有逻辑) */
    public static List<MusicBean> load(Context context, String serverType) {
        if (context == null) {
            return null;
        }
        File file = fileFor(context, serverType);
        if (!file.exists() || file.length() == 0) {
            return null;
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
                b.setLocalSuffix(obj.optString("suffix"));
                b.setBitRate(obj.optInt("bitRate", 0));
                list.add(b);
            }
            Log.d(TAG, "收藏快照已加载[" + serverType + "]: " + list.size() + " 首");
            return list;
        } catch (Exception e) {
            Log.w(TAG, "加载收藏快照[" + serverType + "]失败(忽略,退化到本地收藏)", e);
            return null;
        } finally {
            if (reader != null) {
                try { reader.close(); } catch (Exception ignored) {}
            }
        }
    }
}
