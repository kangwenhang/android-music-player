package com.captiva.musicplayer;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 音乐同步管理器
 * 从 Navidrome 服务器下载全部音乐到本地指定目录
 *
 * 同步策略:
 * 1. 每次都从服务器获取最新歌曲列表(确保能发现新加的歌)
 * 2. 服务器获取失败时,回退到 SongCache 缓存(网络容错)
 * 3. 用 HashSet 去重(O(1)复杂度,替代 O(n) 线性扫描)
 * 4. 预扫描已存在文件,跳过已下载的歌曲
 * 5. 只下载不存在的文件(增量同步)
 *
 * 文件命名规则:
 *   {同步目录}/{艺术家}/{专辑}/{歌名.后缀}
 */
public class MusicSyncManager {

    private static final String TAG = "MusicSyncManager";

    private final Context context;
    private final NavidromeApi api;
    private final String syncPath;
    private volatile boolean cancelled = false;

    public interface SyncCallback {
        /** 同步开始 */
        void onStart(int totalSongs);
        /** 进度更新 */
        void onProgress(int downloaded, int total, String currentSong);
        /** 单首下载完成(实时回调,用于主动刷新列表) */
        void onSongDownloaded(int downloaded, int total);
        /** 单首下载失败 */
        void onSongFailed(String songTitle, String reason);
        /** 同步完成 */
        void onComplete(int downloaded, int skipped, int failed, int total);
        /** 同步被取消 */
        void onCancelled(int downloaded, int total);
        /** 同步出错(无法开始) */
        void onError(String message);
    }

    public MusicSyncManager(Context context, NavidromeApi api, String syncPath) {
        this.context = context;
        this.api = api;
        this.syncPath = syncPath;
    }

    /** 取消同步 */
    public void cancel() {
        cancelled = true;
    }

    /** 检查是否已取消 */
    public boolean isCancelled() {
        return cancelled;
    }

    /**
     * 获取已同步的本地文件数(快速统计)
     * @return 同步目录下的音频文件数
     */
    public static int countSyncedFiles(String syncPath) {
        if (syncPath == null || syncPath.isEmpty()) {
            return 0;
        }
        File root = new File(syncPath);
        if (!root.exists() || !root.isDirectory()) {
            return 0;
        }
        List<File> files = new ArrayList<>();
        collectAudioFiles(root, files);
        return files.size();
    }

    /** 递归收集音频文件 */
    private static void collectAudioFiles(File dir, List<File> result) {
        if (dir == null || !dir.exists() || !dir.isDirectory()) {
            return;
        }
        File[] files = dir.listFiles();
        if (files == null) {
            return;
        }
        for (File f : files) {
            if (f.isDirectory()) {
                if (!f.getName().startsWith(".")) {
                    collectAudioFiles(f, result);
                }
            } else if (isAudioFile(f.getName())) {
                result.add(f);
            }
        }
    }

    private static boolean isAudioFile(String name) {
        if (name == null || !name.contains(".")) {
            return false;
        }
        String ext = name.substring(name.lastIndexOf(".")).toLowerCase();
        return ext.equals(".mp3") || ext.equals(".flac") || ext.equals(".wav")
                || ext.equals(".ogg") || ext.equals(".m4a") || ext.equals(".aac")
                || ext.equals(".wma") || ext.equals(".ape") || ext.equals(".m4b")
                || ext.equals(".opus");
    }

    /** 清理文件名中的非法字符 */
    private static String sanitizeFileName(String name) {
        if (name == null || name.isEmpty()) {
            return "未知";
        }
        return name.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
    }

    /**
     * 构建本地文件路径
     * 格式: {syncPath}/{艺术家}/{专辑}/{歌名.后缀}
     */
    private File buildLocalFile(MusicBean song) {
        String artist = sanitizeFileName(song.getArtist());
        String album = sanitizeFileName(song.getAlbum());
        String title = sanitizeFileName(song.getTitle());
        String suffix = song.getLocalSuffix();

        return new File(new File(new File(syncPath, artist), album),
                title + "." + suffix);
    }

    /**
     * 开始同步(在后台线程调用)
     * 每次从服务器获取最新列表,用HashSet去重,跳过已存在文件
     * @param callback 进度回调
     */
    public void sync(final SyncCallback callback) {
        if (api == null) {
            callback.onError("Navidrome 未配置");
            return;
        }
        if (syncPath == null || syncPath.isEmpty()) {
            callback.onError("同步目录未设置");
            return;
        }

        cancelled = false;

        // 确保同步目录存在
        File syncDir = new File(syncPath);
        if (!syncDir.exists()) {
            syncDir.mkdirs();
        }
        if (!syncDir.isDirectory()) {
            callback.onError("无法创建同步目录: " + syncPath);
            return;
        }

        SongCache cache = new SongCache(context);
        List<MusicBean> allSongs = null;

        // 1. 每次都从服务器获取最新歌曲列表(确保能发现新加的歌)
        callback.onStart(0);
        callback.onProgress(0, 0, "正在获取服务器歌曲列表...");
        allSongs = fetchSongsFromServer(callback);

        if (cancelled) {
            callback.onCancelled(0, 0);
            return;
        }

        // 2. 服务器获取失败(网络问题等),回退到缓存
        if (allSongs == null || allSongs.isEmpty()) {
            Log.w(TAG, "服务器获取失败,尝试从缓存加载");
            if (cache.exists()) {
                allSongs = cache.load();
                Log.d(TAG, "从缓存加载歌曲列表: " + (allSongs != null ? allSongs.size() : 0) + " 首");
            }
        }

        if (allSongs == null || allSongs.isEmpty()) {
            callback.onError("无法获取歌曲列表(服务器无响应且无缓存)");
            return;
        }

        // 3. 用最新服务器列表更新缓存
        cache.save(allSongs);

        Log.d(TAG, "同步开始: 共 " + allSongs.size() + " 首歌曲");

        // 4. 预扫描已存在文件(快速统计,不逐首回调进度)
        int existingCount = 0;
        for (MusicBean song : allSongs) {
            if (cancelled) {
                callback.onCancelled(0, allSongs.size());
                return;
            }
            File localFile = buildLocalFile(song);
            if (localFile.exists() && localFile.length() > 1024) {
                existingCount++;
            }
        }

        // 如果全部已存在,直接完成(无需下载)
        if (existingCount == allSongs.size()) {
            Log.d(TAG, "所有歌曲已存在,跳过同步");
            callback.onStart(allSongs.size());
            callback.onComplete(0, existingCount, 0, allSongs.size());
            return;
        }

        // 5. 以已有数量作为初始进度(避免从0开始显示)
        callback.onStart(allSongs.size());
        callback.onProgress(existingCount, allSongs.size(),
                existingCount > 0 ? "继续同步..." : "开始同步...");

        int downloaded = 0;
        int skipped = existingCount;
        int failed = 0;

        // 6. 只下载不存在的文件(已存在的静默跳过)
        for (int i = 0; i < allSongs.size(); i++) {
            if (cancelled) {
                callback.onCancelled(downloaded + skipped, allSongs.size());
                return;
            }

            MusicBean song = allSongs.get(i);
            File localFile = buildLocalFile(song);

            // 增量同步:已存在则静默跳过(已在预扫描中统计)
            if (localFile.exists() && localFile.length() > 1024) {
                continue;
            }

            // 下载
            callback.onProgress(downloaded + skipped, allSongs.size(),
                    "下载: " + song.getTitle());

            long bytes = api.downloadFile(song.getStreamId(), localFile);
            if (bytes > 0) {
                downloaded++;
                callback.onSongDownloaded(downloaded + skipped, allSongs.size());
            } else {
                failed++;
                callback.onSongFailed(song.getTitle(), "下载失败");
            }
        }

        callback.onComplete(downloaded, skipped, failed, allSongs.size());
    }

    /**
     * 从服务器获取全部歌曲列表(通过专辑分页)
     * 使用 HashSet 去重(O(1)复杂度)
     * @return 歌曲列表;返回 null 表示获取失败(网络错误等),空列表表示服务器无歌曲
     */
    private List<MusicBean> fetchSongsFromServer(SyncCallback callback) {
        List<MusicBean> allSongs = new ArrayList<>();
        Set<String> seenIds = new HashSet<>(); // O(1) 去重

        int albumOffset = 0;
        int albumPageSize = 20;
        boolean gotAnyData = false;

        while (!cancelled) {
            List<MusicBean> batch = api.getSongsByAlbumPage(albumOffset, albumPageSize);
            if (batch == null || batch.isEmpty()) {
                break;
            }
            gotAnyData = true;
            // 用 HashSet 去重(比线性扫描快得多)
            for (MusicBean b : batch) {
                String streamId = b.getStreamId();
                if (streamId != null && !seenIds.contains(streamId)) {
                    seenIds.add(streamId);
                    allSongs.add(b);
                }
            }
            albumOffset += albumPageSize;
            callback.onProgress(0, allSongs.size(),
                    "正在获取歌曲列表(" + allSongs.size() + ")...");
        }

        // 一页都没获取到,返回 null 表示获取失败(可能网络问题)
        if (!gotAnyData) {
            return null;
        }
        return allSongs;
    }
}
