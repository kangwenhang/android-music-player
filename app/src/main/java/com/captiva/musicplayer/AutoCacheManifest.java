package com.captiva.musicplayer;

import android.content.Context;
import android.util.Log;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * 自动缓存清单(仅记录"播放时自动下载"的文件,与手动同步文件严格隔离)。
 *
 * 为什么需要它:
 *   自动缓存可能把车机存储撑满,需要配额清理。但若直接按目录删文件,
 *   会把用户手动同步的歌也误删。因此单独记录自动缓存项(路径→{大小,时间}),
 *   配额超限时只清理"清单内、最旧"的文件,手动同步文件绝不触碰。
 *
 * 存储: {filesDir}/autocache_manifest.json
 *
 * 线程安全:所有读写在静态锁 LOCK 上同步,与 StreamIdIndex 类似。
 */
public class AutoCacheManifest {

    private static final String TAG = "AutoCacheManifest";
    private static final String FILE_NAME = "autocache_manifest.json";
    private static final Object LOCK = new Object();
    /** 内存副本: 规范化路径 -> {size, time} */
    private static Map<String, Entry> map = new HashMap<String, Entry>();
    private static boolean loaded = false;

    private static class Entry {
        long size;
        long time;
        Entry(long size, long time) {
            this.size = size;
            this.time = time;
        }
    }

    private AutoCacheManifest() {
    }

    /** 规范化路径(与 StreamIdIndex 保持一致) */
    private static String norm(String path) {
        return MusicScanner.normalizePath(path);
    }

    /** 新增/更新一条自动缓存记录 */
    public static void add(Context context, String path, long size) {
        if (path == null || path.isEmpty()) {
            return;
        }
        synchronized (LOCK) {
            ensureLoaded(context);
            map.put(norm(path), new Entry(size, System.currentTimeMillis()));
            save(context);
        }
    }

    /** 移除一条记录(文件已被手动删除或移出时调用) */
    public static void remove(Context context, String path) {
        if (path == null || path.isEmpty()) {
            return;
        }
        synchronized (LOCK) {
            ensureLoaded(context);
            if (map.remove(norm(path)) != null) {
                save(context);
            }
        }
    }

    /** 当前自动缓存总字节数 */
    public static long totalBytes(Context context) {
        synchronized (LOCK) {
            ensureLoaded(context);
            return totalBytesLocked();
        }
    }

    /**
     * 配额清理:当自动缓存总字节数超过 maxBytes 时,
     * 按时间从旧到新删除清单内的文件,直到回到配额内(或清单清空)。
     * 只删清单记录的文件,绝不碰手动同步文件;且额外校验文件位于 syncPath 之下。
     */
    public static void evictToFit(Context context, String syncPath, long maxBytes) {
        if (maxBytes <= 0) {
            return;
        }
        synchronized (LOCK) {
            ensureLoaded(context);
            // 先剔除磁盘上已不存在的项,避免统计虚高
            Iterator<Map.Entry<String, Entry>> it = map.entrySet().iterator();
            while (it.hasNext()) {
                if (!new File(it.next().getKey()).exists()) {
                    it.remove();
                }
            }
            if (totalBytesLocked() <= maxBytes) {
                save(context);
                return;
            }
            // 按时间排序(旧在前)
            List<Map.Entry<String, Entry>> entries =
                    new ArrayList<Map.Entry<String, Entry>>(map.entrySet());
            java.util.Collections.sort(entries,
                    new java.util.Comparator<Map.Entry<String, Entry>>() {
                        @Override
                        public int compare(Map.Entry<String, Entry> a,
                                           Map.Entry<String, Entry> b) {
                            long d = a.getValue().time - b.getValue().time;
                            return d < 0 ? -1 : (d > 0 ? 1 : 0);
                        }
                    });
            for (Map.Entry<String, Entry> en : entries) {
                if (totalBytesLocked() <= maxBytes) {
                    break;
                }
                File f = new File(en.getKey());
                // 安全护栏:只删确实位于 syncPath 之下、且仍存在的清单文件
                if (f.exists() && isUnder(f, syncPath)) {
                    if (f.delete()) {
                        Log.d(TAG, "配额清理:删除最旧自动缓存 " + en.getKey());
                    }
                }
                map.remove(en.getKey());
            }
            save(context);
        }
    }

    /** 路径是否位于 base 之下(防止误删 syncPath 之外的文件) */
    private static boolean isUnder(File file, String base) {
        if (file == null || base == null) {
            return false;
        }
        try {
            String p = file.getCanonicalPath();
            String b = new File(base).getCanonicalPath();
            return p.equals(b) || p.startsWith(b + File.separator);
        } catch (Exception e) {
            return false;
        }
    }

    private static long totalBytesLocked() {
        long total = 0;
        for (Entry e : map.values()) {
            total += e.size;
        }
        return total;
    }

    private static void totalBytesSubtract(long delta) {
        // no-op:totalBytesLocked 重新累加,这里仅作语义占位
    }

    private static void ensureLoaded(Context context) {
        if (loaded) {
            return;
        }
        load(context);
        loaded = true;
    }

    private static void load(Context context) {
        map = new HashMap<String, Entry>();
        if (context == null) {
            return;
        }
        File file = new File(context.getFilesDir(), FILE_NAME);
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
                Iterator<String> it = m.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    JSONObject o = m.optJSONObject(k);
                    if (o != null) {
                        long size = o.optLong("size", 0);
                        long time = o.optLong("time", 0);
                        map.put(k, new Entry(size, time));
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "加载自动缓存清单失败(忽略)", e);
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private static void save(Context context) {
        if (context == null) {
            return;
        }
        File file = new File(context.getFilesDir(), FILE_NAME);
        File tmp = new File(context.getFilesDir(), FILE_NAME + ".tmp");
        OutputStreamWriter writer = null;
        try {
            JSONObject root = new JSONObject();
            JSONObject m = new JSONObject();
            for (Map.Entry<String, Entry> en : map.entrySet()) {
                JSONObject o = new JSONObject();
                o.put("size", en.getValue().size);
                o.put("time", en.getValue().time);
                m.put(en.getKey(), o);
            }
            root.put("map", m);
            root.put("count", map.size());
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
            Log.e(TAG, "保存自动缓存清单失败", e);
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
