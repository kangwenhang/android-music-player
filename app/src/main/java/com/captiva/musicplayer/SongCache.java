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
 * 网络歌曲列表缓存(按服务器类型隔离)
 * 将当前服务器(Navidrome / 飞牛 FN Music)的歌曲列表序列化为 JSON 存到本地文件,
 * 文件名随服务器类型变化(navidrome_songs.json / fn_songs.json),
 * 切换服务器即切换缓存,两种服务器的歌曲列表互不串味。
 * 切换到网络模式时先从对应缓存加载(秒开),再后台从服务器更新
 *
 * 线程安全:
 * - save/saveAsync 内部同步,防止并发写文件
 * - load 可在任意线程调用(文件读操作自带并发安全)
 */
public class SongCache {

    private static final String TAG = "SongCache";
    private static final String CACHE_FILE_NAVIDROME = "navidrome_songs.json";
    private static final String CACHE_FILE_FNMUSIC = "fn_songs.json";

    private final File cacheFile;
    /** 同步锁,防止并发写缓存 */
    private final Object writeLock = new Object();
    /** 上次保存的歌曲数,避免重复保存相同数据 */
    private volatile int lastSavedCount = 0;

    /**
     * 按服务器类型取缓存文件名,实现"切服务器即切缓存"。
     */
    private static String cacheFileNameFor(String serverType) {
        if (MusicSourceFactory.TYPE_FNMUSIC.equals(serverType)) {
            return CACHE_FILE_FNMUSIC;
        }
        return CACHE_FILE_NAVIDROME;
    }

    /**
     * 构造按服务器类型隔离的歌曲列表缓存。
     * @param serverType {@link MusicSourceFactory#TYPE_NAVIDROME} 或 {@link MusicSourceFactory#TYPE_FNMUSIC}
     */
    public SongCache(Context context, String serverType) {
        cacheFile = new File(context.getFilesDir(), cacheFileNameFor(serverType));
        migrateFromLegacyCacheDir(context, cacheFileNameFor(serverType));
    }

    /**
     * 旧版缓存文件在 getCacheDir()(系统临时缓存目录,存储压力下会被系统自动清理,
     * 或被用户"清缓存"一并清掉)。云端歌单缓存是列表唯一来源,丢了会导致
     * 离线/首次启动时整个云端歌单(含收藏标记的歌)消失,直到下次联网同步。
     * 已改存 getFilesDir()(持久数据目录)。这里做一次性迁移:新位置不存在
     * 而旧位置有完整文件时,搬过来,老用户升级无感。
     */
    private static void migrateFromLegacyCacheDir(Context context, String fileName) {
        if (context == null) return;
        File legacy = new File(context.getCacheDir(), fileName);
        File target = new File(context.getFilesDir(), fileName);
        if (!legacy.exists() || legacy.length() == 0 || target.exists()) {
            return;
        }
        java.io.InputStream in = null;
        java.io.OutputStream out = null;
        try {
            in = new java.io.FileInputStream(legacy);
            out = new java.io.FileOutputStream(target);
            byte[] buf = new byte[8192];
            int len;
            while ((len = in.read(buf)) != -1) {
                out.write(buf, 0, len);
            }
            Log.i(TAG, "云端歌单缓存已从 cacheDir 迁移到 filesDir: " + fileName);
        } catch (Exception e) {
            Log.w(TAG, "迁移云端歌单缓存失败(忽略,等待下次同步重建)", e);
            target.delete();
        } finally {
            try { if (in != null) in.close(); } catch (Exception ignored) {}
            try { if (out != null) out.close(); } catch (Exception ignored) {}
        }
    }

    /**
     * 进程内解析结果缓存。
     *
     * 为什么需要:云端列表每次「切换来源 / 重载」都要 load() 一遍,
     * 而 load() 是「读整个 JSON 文件 + org.json 逐个解析 810 个对象 × 14 个字段」。
     * 车机实测:冷 1470ms、热 442ms,是切换卡顿的最大单项。
     * 文件内容没变时没必要重复解析,直接复用上一次的解析结果。
     *
     * 失效判定:文件路径 + 长度 + 修改时间(保存是新文件 rename 过来的,长度/时间必变);
     * 另外 save()/clear() 里会显式置空,避免依赖文件系统时间戳精度。
     */
    private static volatile List<MusicBean> memList;
    private static volatile String memKey;

    /** 本次 load 是否命中了内存缓存(仅供诊断日志读取) */
    public static volatile boolean lastLoadFromMemory = false;

    /**
     * 保存歌曲列表到缓存文件(同步)
     * 如果歌曲数与上次相同,跳过保存(避免重复IO)
     */
    public void save(List<MusicBean> songs) {
        if (songs == null || songs.isEmpty()) {
            return;
        }
        synchronized (writeLock) {
            // 避免重复保存相同数量(增量加载时数量变化才保存)
            if (songs.size() == lastSavedCount) {
                return;
            }
            doSave(songs);
            lastSavedCount = songs.size();
            // 写穿:保存后立刻把内存缓存刷新为刚写入的内容(不再作废)。
            // 原实现在这里 invalidateMemoryCache(),而"播放自动缓存完成"会触发 save(),
            // 结果紧接着的云端切换就从「2ms 内存命中」退化成「570~590ms 重新解析 JSON」
            // (车机实测:读云端缓存=586ms(重新解析) 遍历同步目录=1176ms 合计 1974ms)。
            refreshMemoryCacheAfterSave(songs);
        }
    }

    /**
     * 保存后直写内存缓存:用刚写入的内容 + 新文件指纹重建 memList/memKey。
     * beans 必须交副本 —— memList 是只读模板,load() 还会再 copy 一次给调用方就地改写。
     * 任何异常都退回作废,宁可慢也不能拿到脏数据。
     */
    private void refreshMemoryCacheAfterSave(List<MusicBean> songs) {
        try {
            if (songs == null || songs.isEmpty()) {
                invalidateMemoryCache();
                return;
            }
            List<MusicBean> snapshot = new ArrayList<>(songs.size());
            for (int i = 0; i < songs.size(); i++) {
                MusicBean b = songs.get(i);
                if (b != null) {
                    snapshot.add(b.copy());
                }
            }
            memList = snapshot;
            memKey = cacheFile.getAbsolutePath() + "|" + cacheFile.length()
                    + "|" + cacheFile.lastModified();
        } catch (Throwable t) {
            Log.w(TAG, "保存后刷新内存缓存失败,退回作废", t);
            invalidateMemoryCache();
        }
    }

    /**
     * 异步保存(在后台线程执行,不阻塞UI)
     * 适合在 UI 线程调用,避免序列化大列表时卡顿
     */
    public void saveAsync(final List<MusicBean> songs) {
        if (songs == null || songs.isEmpty()) {
            return;
        }
        // 复制一份,防止后台保存时列表被修改
        final List<MusicBean> copy = new ArrayList<>(songs);
        new Thread(new Runnable() {
            @Override
            public void run() {
                save(copy);
            }
        }, "SongCacheSave").start();
    }

    /** 实际执行保存逻辑 */
    private void doSave(List<MusicBean> songs) {
        // 先写临时文件,再重命名,防止写一半中断导致缓存损坏
        // 临时文件名随缓存文件走,避免两种服务器的临时文件互相覆盖
        File tmpFile = new File(cacheFile.getParent(), cacheFile.getName() + ".tmp");
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
                obj.put("suffix", b.getLocalSuffix());
                obj.put("bitRate", b.getBitRate());
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
            invalidateMemoryCache();
            return null;
        }
        // 1. 先看内存里有没有同一版本的解析结果
        String key = cacheFile.getAbsolutePath() + "|" + cacheFile.length() + "|" + cacheFile.lastModified();
        List<MusicBean> cached = memList;
        if (cached != null && key.equals(memKey)) {
            lastLoadFromMemory = true;
            return copyOf(cached);
        }
        lastLoadFromMemory = false;

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
                b.setLocalSuffix(obj.optString("suffix"));
                b.setBitRate(obj.optInt("bitRate", 0));
                list.add(b);
            }
            // 记录已加载的缓存数量,避免load后立即save相同数据
            lastSavedCount = list.size();
            Log.d(TAG, "缓存已加载: " + list.size() + " 首");
            // 存"干净"的一份,并把副本交给调用方:
            // 调用方(buildCloudDrivenList)会就地改写 bean 的 network/data/uri,
            // 若把同一份对象交出去,缓存就被污染了。
            memList = list;
            memKey = key;
            return copyOf(list);
        } catch (Exception e) {
            Log.e(TAG, "加载缓存失败", e);
            return null;
        } finally {
            if (reader != null) {
                try { reader.close(); } catch (Exception ignored) {}
            }
        }
    }

    /** 逐条复制出独立实例(810 条只需几毫秒,远低于重新解析 JSON 的数百毫秒) */
    private static List<MusicBean> copyOf(List<MusicBean> src) {
        List<MusicBean> out = new ArrayList<>(src.size());
        for (int i = 0; i < src.size(); i++) {
            out.add(src.get(i).copy());
        }
        return out;
    }

    /** 作废进程内解析缓存(文件被写/清空后调用) */
    private static void invalidateMemoryCache() {
        memList = null;
        memKey = null;
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
        synchronized (writeLock) {
            if (cacheFile.exists()) {
                cacheFile.delete();
            }
            lastSavedCount = 0;
            invalidateMemoryCache();
        }
    }
}
