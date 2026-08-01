package com.captiva.musicplayer;

import android.content.Context;
import android.util.Log;
import android.util.LruCache;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.util.List;

/**
 * 歌词本地缓存
 * 将从 Navidrome 获取的歌词以 LRC 文本格式缓存到本地文件
 * 断网时可从缓存加载歌词,无需联网
 *
 * 缓存策略:
 * 1. 按 streamId(歌曲唯一ID)作为文件名存储
 * 2. 同时写入同名 .lrc 文件到歌曲所在目录(如果有本地文件路径)
 * 3. 加载时优先查 .lrc 文件,再查缓存目录
 */
public class LyricCache {

    private static final String TAG = "LyricCache";
    private static final String CACHE_DIR = "lyrics";

    /** 内存缓存:最多 20 首歌词(每首约 4KB,共 80KB)
     *  切歌时先查内存,命中则零磁盘 IO */
    private final LruCache<String, List<LrcEntry>> memoryCache = new LruCache<String, List<LrcEntry>>(20) {
        @Override
        protected int sizeOf(String key, List<LrcEntry> value) {
            return 1; // 按条目数计,不按字节
        }
    };

    private final File cacheDir;

    public LyricCache(Context context) {
        cacheDir = new File(context.getCacheDir(), CACHE_DIR);
        if (!cacheDir.exists()) {
            cacheDir.mkdirs();
        }
    }

    /**
     * 缓存歌词(按 streamId)
     * @param streamId 歌曲 ID(Navidrome)
     * @param lyrics 歌词列表
     */
    public void save(String streamId, List<LrcEntry> lyrics) {
        if (streamId == null || streamId.isEmpty() || lyrics == null || lyrics.isEmpty()) {
            return;
        }
        String lrcText = LrcParser.toLrcText(lyrics);
        if (lrcText.isEmpty()) {
            return;
        }
        saveText(streamId, lrcText);
    }

    /**
     * 缓存歌词文本(按 streamId)
     * @param streamId 歌曲 ID
     * @param lrcText LRC 格式文本
     */
    public void saveText(String streamId, String lrcText) {
        if (streamId == null || streamId.isEmpty() || lrcText == null || lrcText.isEmpty()) {
            return;
        }
        // 同时写入内存缓存(切歌时直接命中,零磁盘 IO)
        List<LrcEntry> parsed = LrcParser.parseLrcText(lrcText);
        if (parsed != null && !parsed.isEmpty()) {
            memoryCache.put(streamId, parsed);
        }
        File file = new File(cacheDir, sanitizeFileName(streamId) + ".lrc");
        OutputStreamWriter writer = null;
        try {
            writer = new OutputStreamWriter(new FileOutputStream(file), "UTF-8");
            writer.write(lrcText);
            writer.flush();
            Log.d(TAG, "歌词已缓存: " + streamId + " (" + lrcText.length() + " 字符)");
        } catch (Exception e) {
            Log.e(TAG, "缓存歌词失败", e);
        } finally {
            if (writer != null) {
                try { writer.close(); } catch (Exception ignored) {}
            }
        }
    }

    /**
     * 从缓存加载歌词(按 streamId)
     * @param streamId 歌曲 ID
     * @return 歌词列表,可能为 null(无缓存)
     */
    public List<LrcEntry> load(String streamId) {
        if (streamId == null || streamId.isEmpty()) {
            return null;
        }
        // 1. 优先从内存缓存读取(零磁盘 IO)
        List<LrcEntry> cached = memoryCache.get(streamId);
        if (cached != null) {
            return cached;
        }
        // 2. 回退:从磁盘文件读取
        File file = new File(cacheDir, sanitizeFileName(streamId) + ".lrc");
        if (!file.exists()) {
            return null;
        }
        String text = readTextFile(file);
        if (text == null || text.isEmpty()) {
            return null;
        }
        List<LrcEntry> list = LrcParser.parseLrcText(text);
        if (list != null && !list.isEmpty()) {
            // 写入内存缓存(下次切回这首歌零 IO)
            memoryCache.put(streamId, list);
        }
        Log.d(TAG, "从磁盘缓存加载歌词: " + streamId + " (" + (list != null ? list.size() : 0) + " 行)");
        return list;
    }

    /**
     * 同时保存到歌曲目录(同名 .lrc)和缓存目录
     * @param musicFilePath 音乐文件路径(如 /sdcard/Music/Artist/Album/song.mp3)
     * @param streamId 歌曲 ID(用于缓存目录备份)
     * @param lyrics 歌词列表
     */
    public void saveBoth(String musicFilePath, String streamId, List<LrcEntry> lyrics) {
        if (lyrics == null || lyrics.isEmpty()) {
            return;
        }
        String lrcText = LrcParser.toLrcText(lyrics);

        // 1. 保存到缓存目录(按 streamId)
        if (streamId != null && !streamId.isEmpty()) {
            saveText(streamId, lrcText);
        }

        // 2. 保存到歌曲目录(同名 .lrc)
        if (musicFilePath != null && !musicFilePath.isEmpty()) {
            try {
                int dot = musicFilePath.lastIndexOf('.');
                String lrcPath;
                if (dot > 0) {
                    lrcPath = musicFilePath.substring(0, dot) + ".lrc";
                } else {
                    lrcPath = musicFilePath + ".lrc";
                }
                File lrcFile = new File(lrcPath);
                File parent = lrcFile.getParentFile();
                if (parent != null && !parent.exists()) {
                    parent.mkdirs();
                }
                OutputStreamWriter writer = null;
                try {
                    writer = new OutputStreamWriter(new FileOutputStream(lrcFile), "UTF-8");
                    writer.write(lrcText);
                    writer.flush();
                    Log.d(TAG, "歌词已保存到歌曲目录: " + lrcPath);
                } finally {
                    if (writer != null) {
                        try { writer.close(); } catch (Exception ignored) {}
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "保存 .lrc 到歌曲目录失败", e);
            }
        }
    }

    /** 检查缓存是否存在 */
    public boolean exists(String streamId) {
        if (streamId == null || streamId.isEmpty()) {
            return false;
        }
        File file = new File(cacheDir, sanitizeFileName(streamId) + ".lrc");
        return file.exists() && file.length() > 0;
    }

    /** 清除全部歌词缓存(内存+磁盘) */
    public void clear() {
        memoryCache.evictAll();
        File[] files = cacheDir.listFiles();
        if (files != null) {
            for (File f : files) {
                f.delete();
            }
        }
    }

    /** 预加载歌词到内存缓存(切歌前提前加载,避免切歌时磁盘 IO)
     * @param streamId 歌曲 ID
     */
    public void preloadToMemory(String streamId) {
        if (streamId == null || streamId.isEmpty()) return;
        if (memoryCache.get(streamId) != null) return; // 已在内存
        // 后台读取文件,写入内存缓存
        File file = new File(cacheDir, sanitizeFileName(streamId) + ".lrc");
        if (!file.exists()) return;
        String text = readTextFile(file);
        if (text == null || text.isEmpty()) return;
        List<LrcEntry> list = LrcParser.parseLrcText(text);
        if (list != null && !list.isEmpty()) {
            memoryCache.put(streamId, list);
        }
    }

    /** 读取文本文件 */
    private String readTextFile(File file) {
        InputStreamReader reader = null;
        try {
            StringBuilder sb = new StringBuilder();
            reader = new InputStreamReader(new FileInputStream(file), "UTF-8");
            char[] buf = new char[4096];
            int len;
            while ((len = reader.read(buf)) != -1) {
                sb.append(buf, 0, len);
            }
            return sb.toString();
        } catch (Exception e) {
            Log.e(TAG, "读取缓存文件失败", e);
            return null;
        } finally {
            if (reader != null) {
                try { reader.close(); } catch (Exception ignored) {}
            }
        }
    }

    /** 清理文件名中的非法字符 */
    private static String sanitizeFileName(String name) {
        if (name == null || name.isEmpty()) {
            return "unknown";
        }
        return name.replaceAll("[\\\\/:*?\"<>|]", "_");
    }
}
