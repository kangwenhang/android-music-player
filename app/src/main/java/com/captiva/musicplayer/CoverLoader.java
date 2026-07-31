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

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 封面图异步加载器(优化版)
 *
 * 核心优化:预提取封面到内部存储,滚动时只读内部缓存
 * - 同步/扫描后调用 preloadAllCovers() 将U盘封面提取到内部磁盘缓存
 * - 滚动时开启 cacheOnlyMode,只从内存/磁盘缓存读,不碰U盘
 * - 停止滚动后关闭 cacheOnlyMode,补加载缺失封面
 * - 无封面黑名单持久化,避免重启后重复读U盘
 *
 * 三级缓存:内存LruCache → 磁盘缓存(内部存储) → 网络/U盘加载
 */
public class CoverLoader {

    private static final String TAG = "CoverLoader";
    private static final int CACHE_SIZE = 4 * 1024 * 1024; // 4MB内存缓存(车机内存小)
    private static final String DISK_CACHE_DIR = "cover_cache";
    private static final long DISK_CACHE_MAX_SIZE = 50 * 1024 * 1024; // 50MB磁盘缓存(预提取需要更大)

    /** 每隔多少次保存才检查一次磁盘缓存大小 */
    private static final int CLEAN_INTERVAL = 10;

    private static CoverLoader instance;

    private final LruCache<String, Bitmap> cache;
    private final ThreadPoolExecutor executor;
    private final Handler mainHandler;
    private File diskCacheDir;

    /** 仅缓存模式:只从内存/磁盘缓存读,不读U盘/网络(滚动时开启) */
    private volatile boolean cacheOnlyMode = false;
    /** 保存计数器(降频清理磁盘缓存) */
    private int saveCount = 0;
    /** 已确认无封面的歌曲 key 集合,避免重复尝试 MediaMetadataRetriever(U盘IO极慢) */
    private final Set<String> noCoverSet = new HashSet<>();
    /** 无封面黑名单文件(持久化,避免重启后重复读U盘) */
    private File noCoverFile;
    /** 预提取是否正在运行 */
    private volatile boolean preloading = false;

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
        executor = new ThreadPoolExecutor(
                1, 1, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<Runnable>());
        mainHandler = new Handler(Looper.getMainLooper());
    }

    public static synchronized CoverLoader getInstance() {
        if (instance == null) {
            instance = new CoverLoader();
        }
        return instance;
    }

    /**
     * 初始化磁盘缓存目录(需在 Application 或 Activity 中调用)
     * 默认使用内部存储 cacheDir,启动时立即可用
     */
    public void initDiskCache(Context context) {
        if (context == null) return;
        try {
            File cacheDir = context.getCacheDir();
            diskCacheDir = new File(cacheDir, DISK_CACHE_DIR);
            if (!diskCacheDir.exists()) {
                diskCacheDir.mkdirs();
            }
            // 无封面黑名单持久化文件(小文件,保持在内部存储)
            noCoverFile = new File(cacheDir, "no_cover_list.txt");
            loadNoCoverSet();
        } catch (Exception e) {
            Log.w(TAG, "initDiskCache failed", e);
        }
    }

    /**
     * 切换磁盘缓存目录到U盘(车机内部eMMC比U盘慢时调用)
     * 在 loadMusic() 获取到 syncPath 后调用
     * 切换后:新提取的封面写入U盘,读取也从U盘读(比内部eMMC快)
     *
     * @param dir U盘上的缓存目录(如 /storage/XXXX/CaptivaMusic/.cover_cache)
     */
    public void setDiskCacheDir(File dir) {
        if (dir == null) return;
        try {
            if (!dir.exists()) {
                dir.mkdirs();
            }
            if (dir.canWrite()) {
                diskCacheDir = dir;
                Log.d(TAG, "磁盘缓存切换到U盘: " + dir.getAbsolutePath());
            } else {
                Log.w(TAG, "U盘缓存目录不可写,保持内部存储: " + dir.getAbsolutePath());
            }
        } catch (Exception e) {
            Log.w(TAG, "setDiskCacheDir failed", e);
        }
    }

    // ==================== 无封面黑名单持久化 ====================

    /** 从磁盘加载无封面黑名单 */
    private void loadNoCoverSet() {
        if (noCoverFile == null || !noCoverFile.exists()) return;
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new InputStreamReader(new FileInputStream(noCoverFile), "UTF-8"));
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (!line.isEmpty()) {
                    noCoverSet.add(line);
                }
            }
            Log.d(TAG, "加载无封面黑名单: " + noCoverSet.size() + " 项");
        } catch (Exception e) {
            Log.w(TAG, "loadNoCoverSet failed", e);
        } finally {
            if (reader != null) { try { reader.close(); } catch (Exception ignored) {} }
        }
    }

    /** 保存无封面黑名单到磁盘 */
    private synchronized void saveNoCoverSet() {
        if (noCoverFile == null) return;
        FileOutputStream fos = null;
        try {
            fos = new FileOutputStream(noCoverFile);
            StringBuilder sb = new StringBuilder();
            for (String key : noCoverSet) {
                sb.append(key).append('\n');
            }
            fos.write(sb.toString().getBytes("UTF-8"));
            fos.flush();
        } catch (Exception e) {
            Log.w(TAG, "saveNoCoverSet failed", e);
        } finally {
            if (fos != null) { try { fos.close(); } catch (Exception ignored) {} }
        }
    }

    // ==================== 模式控制 ====================

    /**
     * 设置仅缓存模式(滚动时开启)
     * 开启后:只从内存/磁盘缓存读封面,不触发U盘/网络IO
     * 关闭后:恢复正常加载(可从U盘/网络读取)
     */
    public void setCacheOnlyMode(boolean cacheOnly) {
        this.cacheOnlyMode = cacheOnly;
        if (cacheOnly) {
            // 清空积压队列(避免之前的U盘读取请求继续执行)
            executor.getQueue().clear();
        }
    }

    /**
     * 清除无封面缓存(黑名单)
     * 同步新文件后调用,允许重新尝试加载封面
     */
    public void clearNoCoverCache() {
        if (!noCoverSet.isEmpty()) {
            Log.d(TAG, "清除无封面缓存(" + noCoverSet.size() + "项),允许重新尝试");
            noCoverSet.clear();
            saveNoCoverSet();
        }
    }

    // ==================== 预提取所有封面到内部存储 ====================

    /**
     * 后台预提取所有歌曲封面到内部磁盘缓存
     * 在同步/扫描完成后调用,后续滚动列表时只从内部存储读取
     *
     * @param songs 歌曲列表
     * @param coverSize 列表封面尺寸(px)
     */
    public void preloadAllCovers(final List<MusicBean> songs, final int coverSize) {
        if (songs == null || songs.isEmpty()) return;
        if (preloading) {
            Log.d(TAG, "预提取已在进行中,跳过");
            return;
        }
        preloading = true;

        new Thread(new Runnable() {
            @Override
            public void run() {
                int total = songs.size();
                int cached = 0;
                int noCover = 0;
                int alreadyCached = 0;
                long startTime = System.currentTimeMillis();

                for (int i = 0; i < total; i++) {
                    MusicBean bean = songs.get(i);
                    String key = getCacheKey(bean);
                    if (key == null) continue;

                    // 已在内存缓存
                    if (cache.get(key) != null) {
                        alreadyCached++;
                        continue;
                    }

                    // 已在磁盘缓存
                    String fileName = md5(key) + ".cover";
                    File diskFile = new File(diskCacheDir, fileName);
                    if (diskFile.exists()) {
                        alreadyCached++;
                        continue;
                    }

                    // 已确认无封面
                    if (noCoverSet.contains(key)) {
                        noCover++;
                        continue;
                    }

                    // 从U盘/网络提取封面,写入磁盘缓存
                    Bitmap bmp;
                    if (bean.isNetwork()) {
                        bmp = loadNetworkCover(bean, coverSize, false);
                    } else {
                        bmp = loadLocalCover(bean, coverSize, false);
                    }

                    if (bmp != null) {
                        saveToDiskCache(key, bmp);
                        // 同时放入内存缓存(列表可见时直接命中)
                        cache.put(key, bmp);
                        cached++;
                    } else {
                        // 确认无封面,加入黑名单
                        noCoverSet.add(key);
                        noCover++;
                    }
                }

                // 持久化无封面黑名单
                if (noCover > 0) {
                    saveNoCoverSet();
                }

                long elapsed = System.currentTimeMillis() - startTime;
                Log.d(TAG, "预提取封面完成: 共" + total + "首, 新提取" + cached
                        + ", 已缓存" + alreadyCached + ", 无封面" + noCover
                        + ", 耗时" + elapsed + "ms");
                PerfLogger.log("CoverPreload", "完成: 共" + total + "首, 新提取" + cached
                        + ", 已缓存" + alreadyCached + ", 无封面" + noCover
                        + ", 耗时" + elapsed + "ms");
                preloading = false;
            }
        }, "CoverPreload").start();
    }

    // ==================== 封面加载 ====================

    /**
     * 异步加载封面并设置到 ImageView
     * cacheOnlyMode 开启时:只从内存/磁盘缓存读,不碰U盘/网络
     */
    public void load(MusicBean bean, final ImageView iv, final int size) {
        if (bean == null || iv == null) {
            return;
        }

        final String key = getCacheKey(bean);
        if (key == null) {
            return;
        }

        // 0. 已确认无封面:直接显示占位图,不经过任何线程池/U盘IO
        if (noCoverSet.contains(key)) {
            iv.setImageResource(android.R.color.transparent);
            iv.setBackgroundResource(R.drawable.bg_cover_placeholder);
            return;
        }

        // 1. 先查内存缓存(命中则直接设置,不经过线程池)
        Bitmap cached = cache.get(key);
        if (cached != null) {
            iv.setTag(key);
            iv.setImageBitmap(cached);
            return;
        }

        // 占位图
        iv.setImageResource(android.R.color.transparent);
        iv.setBackgroundResource(R.drawable.bg_cover_placeholder);
        iv.setTag(key);

        // 2. cacheOnlyMode:只从磁盘缓存读,不碰U盘
        executor.execute(new Runnable() {
            @Override
            public void run() {
                // 先查磁盘缓存
                long t0 = PerfLogger.isEnabled() ? System.currentTimeMillis() : 0;
                Bitmap diskCached = loadFromDiskCache(key, size, false);
                if (PerfLogger.isEnabled()) {
                    PerfLogger.log("CoverDisk", System.currentTimeMillis() - t0);
                }
                if (diskCached != null) {
                    cache.put(key, diskCached);
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            Object tag = iv.getTag();
                            if (tag != null && tag.equals(key)) {
                                iv.setBackgroundResource(0);
                                iv.setImageBitmap(cache.get(key));
                            }
                        }
                    });
                    return;
                }

                // cacheOnlyMode:磁盘缓存没有就显示占位图,不读U盘
                if (cacheOnlyMode) {
                    return;
                }

                // 非 cacheOnlyMode:从U盘/网络加载
                long t1 = PerfLogger.isEnabled() ? System.currentTimeMillis() : 0;
                final Bitmap bmp = loadBitmap(bean, key, size, false);
                if (PerfLogger.isEnabled()) {
                    PerfLogger.log("CoverUSB", System.currentTimeMillis() - t1);
                }
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
    }

    /**
     * 预加载封面到内存缓存(不绑定 ImageView)
     * 滑动停止后提前加载即将可见的封面
     * cacheOnlyMode 时只从磁盘缓存读
     */
    public void preload(MusicBean bean, int size) {
        if (bean == null) return;
        final String key = getCacheKey(bean);
        if (key == null || noCoverSet.contains(key)) return;
        if (cache.get(key) != null) return; // 已在内存缓存中

        executor.execute(new Runnable() {
            @Override
            public void run() {
                // 先查磁盘缓存
                Bitmap diskCached = loadFromDiskCache(key, size, false);
                if (diskCached != null) {
                    cache.put(key, diskCached);
                    return;
                }
                // cacheOnlyMode:不读U盘
                if (cacheOnlyMode) return;
                // 正常模式:从U盘/网络加载
                final Bitmap bmp = loadBitmap(bean, key, size, false);
                if (bmp != null) {
                    cache.put(key, bmp);
                }
            }
        });
    }

    /**
     * 异步加载封面 Bitmap(不绑定 ImageView,用于歌词背景等)
     * 不受 cacheOnlyMode 限制(需要完整封面)
     */
    public void loadBitmap(MusicBean bean, final int size, final BitmapCallback callback) {
        loadBitmapInternal(bean, size, callback, false);
    }

    /**
     * 异步加载封面 Bitmap(全分辨率,不限制200px,用于歌词背景)
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

    /** 实际加载 Bitmap(内存→磁盘→U盘/网络) */
    private Bitmap loadBitmap(MusicBean bean, String key, int size, boolean fullRes) {
        // 先查磁盘缓存
        Bitmap diskCached = loadFromDiskCache(key, size, fullRes);
        if (diskCached != null) {
            return diskCached;
        }
        Bitmap bmp;
        if (bean.isNetwork()) {
            bmp = loadNetworkCover(bean, size, fullRes);
        } else {
            bmp = loadLocalCover(bean, size, fullRes);
        }
        if (bmp != null) {
            // 写入磁盘缓存
            saveToDiskCache(key, bmp);
        } else {
            // 确认无封面,加入黑名单避免重复尝试(U盘IO极慢)
            noCoverSet.add(key);
        }
        return bmp;
    }

    /** 从磁盘缓存加载(使用采样率+RGB_565,避免解码全尺寸大图) */
    private Bitmap loadFromDiskCache(String key, int targetSize, boolean fullRes) {
        if (diskCacheDir == null) return null;
        FileInputStream fis = null;
        try {
            String fileName = md5(key) + ".cover";
            File file = new File(diskCacheDir, fileName);
            if (!file.exists()) return null;

            fis = new FileInputStream(file);
            // 先获取图片尺寸
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inJustDecodeBounds = true;
            BitmapFactory.decodeStream(fis, null, opts);
            // 关闭后重新打开(Stream不能重用)
            fis.close();
            fis = new FileInputStream(file);

            // 计算采样率(列表缩略图限制200px,全分辨率用传入尺寸)
            int actualTarget = fullRes ? targetSize : Math.min(targetSize, 200);
            opts.inSampleSize = calculateSampleSize(opts.outWidth, opts.outHeight, actualTarget);
            opts.inJustDecodeBounds = false;
            opts.inPreferredConfig = Bitmap.Config.RGB_565; // 减少内存
            opts.inPurgeable = true;

            Bitmap bmp = BitmapFactory.decodeStream(fis, null, opts);
            return bmp;
        } catch (Exception e) {
            return null;
        } finally {
            if (fis != null) {
                try { fis.close(); } catch (Exception ignored) {}
            }
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
            // 降频清理磁盘缓存(每 CLEAN_INTERVAL 次保存才检查一次)
            saveCount++;
            if (saveCount >= CLEAN_INTERVAL) {
                saveCount = 0;
                cleanDiskCacheIfNeeded();
            }
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
