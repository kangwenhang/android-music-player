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
 * 【重要】拉取歌曲列表时绝不能把"某一页取到 0 首"当作"已经取完":
 * 单页请求失败(网络抖动 / 超时 / 会话过期)会被 API 层吞掉、返回空列表,
 * 一旦据此 break,整个同步就会在这里静默截断(如 815 首只同步出 233 首)。
 * 因此列表拉取以"服务器专辑总数"为循环边界,并对单页/单专辑做重试与补取。
 *
 * 文件命名规则:
 *   {同步目录}/{艺术家}/{专辑}/{歌名.后缀}
 */
public class MusicSyncManager {

    private static final String TAG = "MusicSyncManager";

    /** 单页 / 单专辑取数据的最大尝试次数(首次 + 重试) */
    private static final int FETCH_ATTEMPTS = 3;
    /** 重试前的退避等待(毫秒) */
    private static final long RETRY_BACKOFF_MS = 400L;
    /** 枚举专辑时的分页大小(只影响进度回调粒度,不影响正确性) */
    private static final int ALBUM_PAGE_SIZE = 20;
    /** 兜底路径:连续多少页为空才判定"已经取完"(防止单页抖动被误当成结束) */
    private static final int EMPTY_PAGE_TOLERANCE = 3;

    private final Context context;
    private final MusicSourceApi api;
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

    public MusicSyncManager(Context context, MusicSourceApi api, String syncPath) {
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
     * 计算歌曲的本地规范化路径键(供 StreamIdIndex 建立 路径→streamId 映射)。
     * 必须与 buildLocalFile 的命名规则完全一致,否则和磁盘真实路径对不上。
     */
    public static String localPathKey(MusicBean song, String syncPath) {
        if (song == null || syncPath == null || syncPath.isEmpty()) {
            return "";
        }
        File f = new File(new File(new File(syncPath,
                        sanitizeFileName(song.getArtist())),
                        sanitizeFileName(song.getAlbum())),
                sanitizeFileName(song.getTitle()) + "." + song.getLocalSuffix());
        return MusicScanner.normalizePath(f.getAbsolutePath());
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

        // 3.1 用同步列表(每个 song 带 streamId)重建 路径→streamId 索引,
        //     让 MusicScanner 扫描本地文件时能回填 streamId,启用稳定的服务端身份去重。
        StreamIdIndex.build(context, allSongs, syncPath);

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
        LyricCache lyricCache = new LyricCache(context);

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
                // 下载成功后,顺便获取歌词并缓存
                downloadLyricsForSong(song, localFile, lyricCache);
            } else {
                failed++;
                callback.onSongFailed(song.getTitle(), "下载失败");
            }
        }

        // 7. 为已跳过的歌曲补充歌词缓存(断网可用)
        int lyricsCached = 0;
        for (MusicBean song : allSongs) {
            if (cancelled) break;
            String sid = song.getStreamId();
            if (sid == null || sid.isEmpty()) continue;
            // 已有缓存则跳过
            if (lyricCache.exists(sid)) continue;
            File localFile = buildLocalFile(song);
            if (downloadLyricsForSong(song, localFile, lyricCache)) {
                lyricsCached++;
            }
        }
        if (lyricsCached > 0) {
            Log.d(TAG, "补充缓存歌词: " + lyricsCached + " 首");
        }

        callback.onComplete(downloaded, skipped, failed, allSongs.size());
    }

    /**
     * 获取单首歌曲歌词并缓存到本地
     * @param song 歌曲信息
     * @param localFile 本地文件路径(用于写同名 .lrc)
     * @param lyricCache 歌词缓存管理器
     * @return true 如果成功获取并缓存了歌词
     */
    private boolean downloadLyricsForSong(MusicBean song, File localFile, LyricCache lyricCache) {
        String sid = song.getStreamId();
        if (sid == null || sid.isEmpty()) return false;

        try {
            List<LrcEntry> lyrics = api.getLyricsBySongId(sid);
            if (lyrics == null || lyrics.isEmpty()) {
                // 回退到 getLyrics
                String plainText = api.getLyrics(song.getArtist(), song.getTitle());
                if (plainText != null && !plainText.isEmpty()) {
                    if (plainText.contains("[") && plainText.contains(":") && plainText.contains("]")) {
                        lyrics = LrcParser.parseLrcText(plainText);
                    } else {
                        lyrics = LrcParser.parsePlainTextLyrics(plainText, 5000);
                    }
                }
            }
            if (lyrics != null && !lyrics.isEmpty()) {
                lyricCache.saveBoth(localFile.getAbsolutePath(), sid, lyrics);
                return true;
            }
        } catch (Exception e) {
            Log.w(TAG, "获取歌词失败: " + song.getTitle(), e);
        }
        return false;
    }

    /**
     * 从服务器获取全部歌曲列表
     *
     * 【为什么重写】旧实现把"这一页取到 0 首歌"当成"服务器没有更多歌"并 break,
     * 而单页请求失败会被 API 层吞掉、返回空列表,所以任意一次网络抖动 / 超时 /
     * 会话过期都会让整个同步在此处静默截断 —— 表现为"总数偏少"
     * (例如实际 815 首,只同步出 233 首),界面还把它当成功显示。
     *
     * 新实现:
     * 1. 先取全量专辑列表,用"专辑总数"当循环边界,不再用"本页有没有歌"当结束条件;
     * 2. 单张专辑取歌失败会重试;仍失败只跳过这一张并记下,不影响其他专辑;
     * 3. 首轮结束后对失败的专辑补取一次;
     * 4. 再用数据源的"一次性全量列表"补漏(只做加法,少了/失败了就忽略);
     * 5. 最后用"专辑声称的歌曲数"做交叉校验,数量对不上会打 WARN 方便定位。
     *
     * 使用 HashSet 去重(O(1)复杂度)
     * @return 歌曲列表;返回 null 表示获取失败(网络错误等),空列表表示服务器无歌曲
     */
    private List<MusicBean> fetchSongsFromServer(SyncCallback callback) {
        List<MusicBean> allSongs = new ArrayList<>();
        Set<String> seenIds = new HashSet<>(); // O(1) 去重

        // ---- 1. 全量专辑列表:库到底有多大,以专辑数为准 ----
        List<AlbumBean> albums = fetchAllAlbumsWithRetry();
        int totalAlbums = (albums == null) ? 0 : albums.size();
        int expectedSongs = 0;
        if (albums != null) {
            for (AlbumBean a : albums) {
                if (a != null && a.getSongCount() > 0) {
                    expectedSongs += a.getSongCount();
                }
            }
        }
        Log.d(TAG, "服务器专辑总数=" + totalAlbums + ", 专辑声称歌曲数合计=" + expectedSongs);

        if (totalAlbums == 0) {
            // ---- 2. 兜底:拿不到专辑列表时退化为按专辑分页(连续多页为空才停) ----
            Log.w(TAG, "取不到专辑列表,退化为按专辑分页拉取");
            List<MusicBean> fallback = fetchByAlbumPagesTolerant(callback, allSongs, seenIds);
            if (fallback == null) {
                return null;
            }
            topUpFromBulkList(allSongs, seenIds);
            return allSongs;
        }

        // ---- 3. 逐张专辑取歌:循环边界是专辑总数,单张失败不会终止整体 ----
        List<AlbumBean> missedAlbums = new ArrayList<>();
        for (int offset = 0; offset < totalAlbums && !cancelled; offset += ALBUM_PAGE_SIZE) {
            int end = Math.min(offset + ALBUM_PAGE_SIZE, totalAlbums);
            for (int i = offset; i < end && !cancelled; i++) {
                AlbumBean album = albums.get(i);
                if (album == null || album.getId() == null || album.getId().isEmpty()) {
                    continue;
                }
                List<MusicBean> songs = fetchAlbumWithRetry(album);
                if (songs == null || songs.isEmpty()) {
                    // 专辑自称有歌却一首都没取到 → 记下来,稍后补取
                    if (album.getSongCount() > 0) {
                        missedAlbums.add(album);
                    }
                    continue;
                }
                addDeduped(allSongs, seenIds, songs);
            }
            callback.onProgress(0, allSongs.size(),
                    "正在获取歌曲列表(" + allSongs.size() + ")...");
        }

        // ---- 4. 首轮没取到歌的专辑,再补取一次(多为网络抖动) ----
        if (!missedAlbums.isEmpty() && !cancelled) {
            Log.w(TAG, "首轮有 " + missedAlbums.size() + " 张专辑未取到歌曲,开始补取");
            int repaired = 0;
            for (AlbumBean album : missedAlbums) {
                if (cancelled) break;
                List<MusicBean> songs = fetchAlbumWithRetry(album);
                if (songs != null && !songs.isEmpty()) {
                    int before = allSongs.size();
                    addDeduped(allSongs, seenIds, songs);
                    if (allSongs.size() > before) {
                        repaired++;
                    }
                }
            }
            Log.d(TAG, "补取成功 " + repaired + " 张专辑");
        }

        if (allSongs.isEmpty()) {
            // 专辑列表拿到了却一首都没取到 → 视为获取失败,让上层回退缓存
            Log.w(TAG, "专辑列表已获取但一首歌都没取到,判定为获取失败");
            return null;
        }

        // ---- 5. 用一次性全量列表补漏,并交叉校验数量 ----
        topUpFromBulkList(allSongs, seenIds);

        if (expectedSongs > 0 && allSongs.size() < expectedSongs) {
            Log.w(TAG, "歌曲数校验不一致:实际取到 " + allSongs.size()
                    + " 首 < 专辑合计 " + expectedSongs
                    + " 首(可能是同一首歌归属多张专辑,或仍有专辑取歌失败)");
        } else {
            Log.d(TAG, "歌曲数校验通过:实际 " + allSongs.size() + " 首");
        }
        return allSongs;
    }

    /**
     * 兜底路径:按专辑分页拉取,连续 {@link #EMPTY_PAGE_TOLERANCE} 页为空才判定到底
     * @return 取到过数据则返回 allSongs;一页都没成功返回 null(获取失败)
     */
    private List<MusicBean> fetchByAlbumPagesTolerant(SyncCallback callback,
                                                      List<MusicBean> allSongs,
                                                      Set<String> seenIds) {
        int albumOffset = 0;
        int consecutiveEmpty = 0;
        boolean gotAnyData = false;

        while (!cancelled && consecutiveEmpty < EMPTY_PAGE_TOLERANCE) {
            List<MusicBean> batch = fetchAlbumPageWithRetry(albumOffset, ALBUM_PAGE_SIZE);
            if (batch == null || batch.isEmpty()) {
                consecutiveEmpty++;
                albumOffset += ALBUM_PAGE_SIZE;
                continue;
            }
            consecutiveEmpty = 0;
            gotAnyData = true;
            addDeduped(allSongs, seenIds, batch);
            albumOffset += ALBUM_PAGE_SIZE;
            callback.onProgress(0, allSongs.size(),
                    "正在获取歌曲列表(" + allSongs.size() + ")...");
        }

        if (!gotAnyData) {
            return null;
        }
        return allSongs;
    }

    /** 带重试获取全量专辑列表;取不到返回 null */
    private List<AlbumBean> fetchAllAlbumsWithRetry() {
        for (int attempt = 0; attempt < FETCH_ATTEMPTS && !cancelled; attempt++) {
            List<AlbumBean> albums = api.getAllAlbums();
            if (albums != null && !albums.isEmpty()) {
                return albums;
            }
            if (attempt < FETCH_ATTEMPTS - 1) {
                sleepQuietly(RETRY_BACKOFF_MS);
            }
        }
        return null;
    }

    /**
     * 带重试获取单张专辑的歌曲
     * 专辑自称没有歌曲(songCount &lt;= 0)时不重试,避免对空专辑做无谓请求
     */
    private List<MusicBean> fetchAlbumWithRetry(AlbumBean album) {
        if (album == null || album.getId() == null || album.getId().isEmpty()) {
            return null;
        }
        boolean mayHaveSongs = album.getSongCount() > 0;
        List<MusicBean> last = null;
        for (int attempt = 0; attempt < FETCH_ATTEMPTS && !cancelled; attempt++) {
            last = api.getAlbum(album.getId());
            if (last != null && !last.isEmpty()) {
                return last;
            }
            if (!mayHaveSongs) {
                return last; // 专辑本身就没歌,重试没有意义
            }
            if (attempt < FETCH_ATTEMPTS - 1) {
                sleepQuietly(RETRY_BACKOFF_MS);
            }
        }
        return last;
    }

    /** 带重试按专辑分页取歌;取不到返回 null */
    private List<MusicBean> fetchAlbumPageWithRetry(int albumOffset, int albumCount) {
        for (int attempt = 0; attempt < FETCH_ATTEMPTS && !cancelled; attempt++) {
            List<MusicBean> batch = api.getSongsByAlbumPage(albumOffset, albumCount);
            if (batch != null && !batch.isEmpty()) {
                return batch;
            }
            if (attempt < FETCH_ATTEMPTS - 1) {
                sleepQuietly(RETRY_BACKOFF_MS);
            }
        }
        return null;
    }

    /**
     * 用数据源的"一次性全量列表"把按专辑取歌时漏掉的歌补进来。
     * 只做加法:它失败、返回空、或者并不比已取到的多时,一律忽略。
     * 这样即便某些数据源的该接口本身结果不全,也不会把已经拿到的正确结果弄坏。
     */
    private void topUpFromBulkList(List<MusicBean> allSongs, Set<String> seenIds) {
        List<MusicBean> bulk = null;
        for (int attempt = 0; attempt < FETCH_ATTEMPTS && !cancelled; attempt++) {
            try {
                bulk = api.getAllSongs();
            } catch (Exception e) {
                Log.w(TAG, "全量列表补漏请求失败(忽略)", e);
                bulk = null;
            }
            if (bulk != null && !bulk.isEmpty()) {
                break;
            }
            if (attempt < FETCH_ATTEMPTS - 1) {
                sleepQuietly(RETRY_BACKOFF_MS);
            }
        }
        if (bulk == null || bulk.isEmpty()) {
            return;
        }
        int before = allSongs.size();
        addDeduped(allSongs, seenIds, bulk);
        int added = allSongs.size() - before;
        if (added > 0) {
            Log.w(TAG, "补漏:全量列表比按专辑取到的多 " + added + " 首,已补入");
        } else {
            Log.d(TAG, "补漏:全量列表未发现遗漏(共 " + bulk.size() + " 首)");
        }
    }

    /** 按 streamId 去重后追加(保持与旧实现一致的判空语义) */
    private static void addDeduped(List<MusicBean> target, Set<String> seenIds,
                                   List<MusicBean> src) {
        if (src == null || src.isEmpty()) {
            return;
        }
        for (MusicBean b : src) {
            if (b == null) {
                continue;
            }
            String streamId = b.getStreamId();
            if (streamId == null) {
                continue;
            }
            if (seenIds.add(streamId)) {
                target.add(b);
            }
        }
    }

    /** 静默休眠(重试退避用,不向上抛中断异常) */
    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
