package com.captiva.musicplayer;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 封面图异步加载器(优化版)
 * - 三级缓存:内存LruCache → 磁盘缓存 → 网络/文件加载
 * - 本地歌曲:从嵌入式专辑封面提取(MediaMetadataRetriever)
 * - Navidrome 歌曲:从 getCoverArt URL 下载
 * - 磁盘缓存避免重复网络请求,大幅提升二次加载速度
 */
public class CoverLoader {

    private static final String TAG = "CoverLoader";
    private static final int CACHE_SIZE = 4 * 1024 * 1024; // 4MB内存缓存(车机内存小)
    private static final String DISK_CACHE_DIR = "cover_cache";
    private static final long DISK_CACHE_MAX_SIZE = 30 * 1024 * 1024; // 30MB磁盘缓存

    private static CoverLoader instance;

    private final LruCache<String, Bitmap> cache;
    /** 正在加载中的回调集合,防止重复请求 */
    private final Map<String, BitmapCallback> pendingCallbacks = new HashMap<>();
    private final ExecutorService executor;
    private final Handler mainHandler;
    private File diskCacheDir;

    private CoverLoader() {
        cache = new LruCache<String, Bitmap>(CACHE_SIZE) {
            @Override
            protected int sizeOf(String key, Bitmap value) {
                return value.getRowBytes() * value.getHeight();
            }

            @Override
            protected void entryRemoved(boolean evicted, String key, Bitmap oldValue, Bitmap newValue) {
                // 内存缓存淘汰时不需要回收,GC会处理
            }
        };
        // 车机性能弱,只用1个线程加载封面,避免OOM
        executor = Executors.newFixedThreadPool(1);
        mainHandler = new Handler(Looper.getMainLooper());
    }

    public static synchronized CoverLoader getInstance() {
        if (instance == null) {
            instance = new CoverLoader();
        }
        return instance;
    }

    /** 初始化磁盘缓存目录(需在 Application 或 Activity 中调用) */
    public void initDiskCache(Context context) {
        if (context == null) return;
        try {
            File cacheDir = context.getCacheDir();
            diskCacheDir = new File(cacheDir, DISK_CACHE_DIR);
            if (!diskCacheDir.exists()) {
                diskCacheDir.mkdirs();
            }
        } catch (Exception e) {
            Log.w(TAG, "initDiskCache failed", e);
        }
    }

    /**
     * 异步加载封面并设置到 ImageView
     * @param bean  歌曲信息
     * @param iv    目标 ImageView
     * @param size  期望尺寸(px),用于缩放
     */
    public void load(MusicBean bean, final ImageView iv, final int size) {
        if (bean == null || iv == null) {
            return;
        }

        final String key = getCacheKey(bean);
        if (key == null) {
            return;
        }

        // 1. 先查内存缓存
        Bitmap cached = cache.get(key);
        if (cached != null) {
            iv.setImageBitmap(cached);
            return;
        }

        // 占位图
        iv.setImageResource(android.R.color.transparent);
        iv.setBackgroundResource(R.drawable.bg_cover_placeholder);

        executor.execute(new Runnable() {
            @Override
            public void run() {
                final Bitmap bmp = loadBitmap(bean, key, size, false);
                if (bmp != null) {
                    cache.put(key, bmp);
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            Object tag = iv.getTag();
                            if (tag != null && tag.equals(key)) {
                                iv.setBackgroundResource(0);
                                iv.setImageBitmap(bmp);
                            }
                        }
                    });
                }
            }
        });

        iv.setTag(key);
    }

    /**
     * 异步加载封面 Bitmap(不绑定 ImageView)
     * @param bean     歌曲信息
     * @param size     期望尺寸
     * @param callback 回调
     */
    public void loadBitmap(MusicBean bean, final int size, final BitmapCallback callback) {
        loadBitmapInternal(bean, size, callback, false);
    }

    /**
     * 异步加载封面 Bitmap(全分辨率,不限制200px,用于歌词背景)
     * @param bean     歌曲信息
     * @param size     期望尺寸
     * @param callback 回调
     */
    public void loadBitmapFull(MusicBean bean, final int size, final BitmapCallback callback) {
        loadBitmapInternal(bean, size, callback, true);
    }

    private void loadBitmapInternal(MusicBean bean, final int size, final BitmapCallback callback, final boolean fullRes) {
        if (bean == null || callback == null) {
            return;
        }
        final String key = getCacheKey(bean) + (fullRes ? "_full" : "");
        if (key == null) {
            callback.onBitmapLoaded(null);
            return;
        }
        // 1. 先查内存缓存(不阻塞)
        Bitmap cached = cache.get(key);
        if (cached != null) {
            callback.onBitmapLoaded(cached);
            return;
        }

        // 2. 异步加载(磁盘缓存+网络/文件,全部在后台线程)
        executor.execute(new Runnable() {
            @Override
            public void run() {
                final Bitmap bmp = loadBitmap(bean, key, size, fullRes);
                if (bmp != null) {
                    cache.put(key, bmp);
                }
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        callback.onBitmapLoaded(bmp);
                    }
                });
            }
        });
    }

    /** Bitmap 加载回调 */
    public interface BitmapCallback {
        void onBitmapLoaded(Bitmap bitmap);
    }

    /** 生成缓存 key */
    private String getCacheKey(MusicBean bean) {
        if (bean.isNetwork()) {
            return "net_" + bean.getCoverArtId();
        } else {
            if (bean.getData() != null && !bean.getData().isEmpty()) {
                return "local_" + bean.getData();
            }
            if (bean.getUri() != null && !bean.getUri().isEmpty()) {
                return "local_" + bean.getUri();
            }
        }
        return null;
    }

    /** 实际加载 Bitmap */
    private Bitmap loadBitmap(MusicBean bean, String key, int size, boolean fullRes) {
        // 先查磁盘缓存
        Bitmap diskCached = loadFromDiskCache(key);
        if (diskCached != null) {
            return diskCached;
        }
        Bitmap bmp;
        if (bean.isNetwork()) {
            bmp = loadNetworkCover(bean, size, fullRes);
        } else {
            bmp = loadLocalCover(bean, size, fullRes);
        }
        // 写入磁盘缓存(后台线程,不阻塞UI)
        if (bmp != null) {
            saveToDiskCache(key, bmp);
        }
        return bmp;
    }

    /** 从磁盘缓存加载 */
    private Bitmap loadFromDiskCache(String key) {
        if (diskCacheDir == null) return null;
        try {
            String fileName = md5(key) + ".cover";
            File file = new File(diskCacheDir, fileName);
            if (!file.exists()) return null;
            FileInputStream fis = null;
            try {
                fis = new FileInputStream(file);
                return BitmapFactory.decodeStream(fis);
            } finally {
                if (fis != null) fis.close();
            }
        } catch (Exception e) {
            return null;
        }
    }

    /** 保存到磁盘缓存 */
    private void saveToDiskCache(String key, Bitmap bmp) {
        if (diskCacheDir == null || bmp == null) return;
        try {
            String fileName = md5(key) + ".cover";
            File file = new File(diskCacheDir, fileName);
            FileOutputStream fos = null;
            try {
                fos = new FileOutputStream(file);
                bmp.compress(Bitmap.CompressFormat.JPEG, 85, fos);
                fos.flush();
            } finally {
                if (fos != null) fos.close();
            }
            // 清理过期缓存
            cleanDiskCacheIfNeeded();
        } catch (Exception e) {
            Log.w(TAG, "saveToDiskCache failed", e);
        }
    }

    /** 清理磁盘缓存(超过大小限制时删除最旧文件) */
    private void cleanDiskCacheIfNeeded() {
        try {
            if (diskCacheDir == null) return;
            File[] files = diskCacheDir.listFiles();
            if (files == null) return;
            long totalSize = 0;
            for (File f : files) {
                totalSize += f.length();
            }
            if (totalSize > DISK_CACHE_MAX_SIZE) {
                // 按最后修改时间排序,删除最旧的
                java.util.Arrays.sort(files, new java.util.Comparator<File>() {
                    @Override
                    public int compare(File a, File b) {
                        return Long.compare(a.lastModified(), b.lastModified());
                    }
                });
                for (File f : files) {
                    if (totalSize <= DISK_CACHE_MAX_SIZE) break;
                    totalSize -= f.length();
                    f.delete();
                }
            }
        } catch (Exception ignored) {
        }
    }

    /** MD5 哈希(用于磁盘缓存文件名) */
    private String md5(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(input.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b & 0xFF));
            }
            return sb.toString();
        } catch (Exception e) {
            return String.valueOf(input.hashCode());
        }
    }

    /** 从本地音乐文件提取嵌入式封面 */
    private Bitmap loadLocalCover(MusicBean bean, int size, boolean fullRes) {
        MediaMetadataRetriever mmr = null;
        try {
            String path = bean.getData();
            if (path == null || path.isEmpty()) {
                return null;
            }
            mmr = new MediaMetadataRetriever();
            mmr.setDataSource(path);
            byte[] art = mmr.getEmbeddedPicture();
            if (art == null || art.length == 0) {
                return null;
            }
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(art, 0, art.length, opts);
            // 全分辨率模式不限制尺寸;列表缩略图限制200px
            int targetSize = fullRes ? size : Math.min(size, 200);
            opts.inSampleSize = calculateSampleSize(opts.outWidth, opts.outHeight, targetSize);
            opts.inJustDecodeBounds = false;
            opts.inPreferredConfig = Bitmap.Config.RGB_565; // 减少内存
            opts.inPurgeable = true; // 允许回收解码内存
            return BitmapFactory.decodeByteArray(art, 0, art.length, opts);
        } catch (Exception e) {
            Log.w(TAG, "loadLocalCover failed: " + bean.getData(), e);
            return null;
        } catch (OutOfMemoryError e) {
            Log.e(TAG, "loadLocalCover OOM: " + bean.getData(), e);
            // 清理内存缓存
            cache.evictAll();
            return null;
        } finally {
            if (mmr != null) {
                try {
                    mmr.release();
                } catch (Exception ignored) {
                }
            }
        }
    }

    /** 从 Navidrome getCoverArt 下载封面 */
    private Bitmap loadNetworkCover(MusicBean bean, int size, boolean fullRes) {
        String coverArtId = bean.getCoverArtId();
        if (coverArtId == null || coverArtId.isEmpty()) {
            return null;
        }
        NavidromeApi api = MusicDataHolder.getInstance().getNavidromeApi();
        if (api == null) {
            return null;
        }
        String urlStr = api.getCoverArtUrl(coverArtId, size);
        if (urlStr == null) {
            return null;
        }
        HttpURLConnection conn = null;
        InputStream is = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            conn.setDoInput(true);
            int code = conn.getResponseCode();
            if (code != 200) {
                return null;
            }
            is = conn.getInputStream();
            byte[] data = readAll(is);
            if (data == null || data.length == 0) {
                return null;
            }
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, opts);
            // 全分辨率模式不限制尺寸;列表缩略图限制200px
            int targetSize = fullRes ? size : Math.min(size, 200);
            opts.inSampleSize = calculateSampleSize(opts.outWidth, opts.outHeight, targetSize);
            opts.inJustDecodeBounds = false;
            opts.inPreferredConfig = Bitmap.Config.RGB_565;
            opts.inPurgeable = true;
            return BitmapFactory.decodeByteArray(data, 0, data.length, opts);
        } catch (Exception e) {
            Log.w(TAG, "loadNetworkCover failed: " + coverArtId, e);
            return null;
        } catch (OutOfMemoryError e) {
            Log.e(TAG, "loadNetworkCover OOM: " + coverArtId, e);
            cache.evictAll();
            return null;
        } finally {
            if (is != null) try { is.close(); } catch (Exception ignored) {}
            if (conn != null) conn.disconnect();
        }
    }

    /** 读取 InputStream 全部字节 */
    private byte[] readAll(InputStream is) {
        try {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) != -1) {
                bos.write(buf, 0, n);
            }
            return bos.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }

    /** 计算采样率,避免 OOM */
    private int calculateSampleSize(int width, int height, int target) {
        if (target <= 0 || width <= 0 || height <= 0) {
            return 1;
        }
        int sample = 1;
        while (width / sample > target * 2 || height / sample > target * 2) {
            sample *= 2;
        }
        return sample;
    }
}
