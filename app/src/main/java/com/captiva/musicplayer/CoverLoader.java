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

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 封面图异步加载器
 * - 本地歌曲:从嵌入式专辑封面提取(MediaMetadataRetriever)
 * - Navidrome 歌曲:从 getCoverArt URL 下载
 * - 使用 LruCache 缓存已加载的封面,避免重复加载
 */
public class CoverLoader {

    private static final String TAG = "CoverLoader";
    private static final int CACHE_SIZE = 4 * 1024 * 1024; // 4MB

    private static CoverLoader instance;

    private final LruCache<String, Bitmap> cache;
    private final ExecutorService executor;
    private final Handler mainHandler;

    private CoverLoader() {
        cache = new LruCache<String, Bitmap>(CACHE_SIZE) {
            @Override
            protected int sizeOf(String key, Bitmap value) {
                return value.getRowBytes() * value.getHeight();
            }
        };
        executor = Executors.newFixedThreadPool(3);
        mainHandler = new Handler(Looper.getMainLooper());
    }

    public static synchronized CoverLoader getInstance() {
        if (instance == null) {
            instance = new CoverLoader();
        }
        return instance;
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

        // 先查缓存
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
                final Bitmap bmp = loadBitmap(bean, key, size);
                if (bmp != null) {
                    cache.put(key, bmp);
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            // 检查 ImageView 是否仍对应同一首歌
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

        // 标记当前 ImageView 对应的 key,防止列表复用时错位
        iv.setTag(key);
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
    private Bitmap loadBitmap(MusicBean bean, String key, int size) {
        if (bean.isNetwork()) {
            return loadNetworkCover(bean, size);
        } else {
            return loadLocalCover(bean, size);
        }
    }

    /** 从本地音乐文件提取嵌入式封面 */
    private Bitmap loadLocalCover(MusicBean bean, int size) {
        MediaMetadataRetriever mmr = null;
        try {
            mmr = new MediaMetadataRetriever();
            String path = bean.getData();
            if (path == null || path.isEmpty()) {
                return null;
            }
            mmr.setDataSource(path);
            byte[] art = mmr.getEmbeddedPicture();
            if (art == null || art.length == 0) {
                return null;
            }
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(art, 0, art.length, opts);
            opts.inSampleSize = calculateSampleSize(opts.outWidth, opts.outHeight, size);
            opts.inJustDecodeBounds = false;
            return BitmapFactory.decodeByteArray(art, 0, art.length, opts);
        } catch (Exception e) {
            Log.w(TAG, "loadLocalCover failed: " + bean.getData(), e);
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
    private Bitmap loadNetworkCover(MusicBean bean, int size) {
        String coverArtId = bean.getCoverArtId();
        if (coverArtId == null || coverArtId.isEmpty()) {
            return null;
        }
        // 使用 MusicDataHolder 中保存的 NavidromeApi 构建 URL
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
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            conn.setDoInput(true);
            int code = conn.getResponseCode();
            if (code != 200) {
                return null;
            }
            is = conn.getInputStream();
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inJustDecodeBounds = true;
            // 先读入字节数组再解码(需要两次读)
            byte[] data = readAll(is);
            if (data == null || data.length == 0) {
                return null;
            }
            BitmapFactory.decodeByteArray(data, 0, data.length, opts);
            opts.inSampleSize = calculateSampleSize(opts.outWidth, opts.outHeight, size);
            opts.inJustDecodeBounds = false;
            return BitmapFactory.decodeByteArray(data, 0, data.length, opts);
        } catch (Exception e) {
            Log.w(TAG, "loadNetworkCover failed: " + coverArtId, e);
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
            byte[] buf = new byte[4096];
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
