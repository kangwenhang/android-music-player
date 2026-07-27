package com.captiva.musicplayer;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 音乐同步管理器
 * 从 Navidrome 服务器下载全部音乐到本地指定目录
 *
 * 同步策略:
 * 1. 从服务器获取全部歌曲列表(通过专辑分页)
 * 2. 按艺术家/专辑创建目录结构
 * 3. 逐首下载,跳过已存在的文件(增量同步)
 * 4. 通过回调通知 UI 进度
 *
 * 文件命名规则:
 *   {同步目录}/{艺术家}/{专辑}/{序号-歌名.后缀}
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
        // 替换文件系统不允许的字符
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

        // 1. 获取全部歌曲列表(通过专辑分页)
        List<MusicBean> allSongs = new ArrayList<>();
        int albumOffset = 0;
        int albumPageSize = 20;
        while (!cancelled) {
            List<MusicBean> batch = api.getSongsByAlbumPage(albumOffset, albumPageSize);
            if (batch == null || batch.isEmpty()) {
                break;
            }
            // 去重
            for (MusicBean b : batch) {
                if (!containsSong(allSongs, b)) {
                    allSongs.add(b);
                }
            }
            albumOffset += albumPageSize;
            // 报告获取列表的进度
            callback.onProgress(0, allSongs.size(), "正在获取歌曲列表(" + allSongs.size() + ")...");
        }

        if (cancelled) {
            callback.onCancelled(0, allSongs.size());
            return;
        }

        if (allSongs.isEmpty()) {
            callback.onError("服务器上未找到音乐");
            return;
        }

        Log.d(TAG, "同步开始: 共 " + allSongs.size() + " 首歌曲");

        // 2. 逐首下载
        callback.onStart(allSongs.size());

        int downloaded = 0;
        int skipped = 0;
        int failed = 0;

        for (int i = 0; i < allSongs.size(); i++) {
            if (cancelled) {
                callback.onCancelled(downloaded + skipped, allSongs.size());
                return;
            }

            MusicBean song = allSongs.get(i);
            File localFile = buildLocalFile(song);

            // 增量同步:已存在则跳过
            if (localFile.exists() && localFile.length() > 1024) {
                skipped++;
                callback.onProgress(downloaded + skipped, allSongs.size(), song.getTitle());
                continue;
            }

            // 下载
            callback.onProgress(downloaded + skipped, allSongs.size(),
                    "下载: " + song.getTitle());

            long bytes = api.downloadFile(song.getStreamId(), localFile);
            if (bytes > 0) {
                downloaded++;
            } else {
                failed++;
                callback.onSongFailed(song.getTitle(), "下载失败");
            }
        }

        // 3. 保存歌曲列表缓存(用于网络模式快速显示)
        SongCache cache = new SongCache(context);
        cache.save(allSongs);

        callback.onComplete(downloaded, skipped, failed, allSongs.size());
    }

    /** 检查列表中是否已包含某首歌(用 streamId 去重) */
    private boolean containsSong(List<MusicBean> list, MusicBean target) {
        if (target == null || target.getStreamId() == null) return false;
        for (MusicBean b : list) {
            if (target.getStreamId().equals(b.getStreamId())) {
                return true;
            }
        }
        return false;
    }
}
