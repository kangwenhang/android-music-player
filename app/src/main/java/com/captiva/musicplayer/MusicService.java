package com.captiva.musicplayer;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.RemoteControlClient;
import android.media.audiofx.Equalizer;
import android.os.Build;
import android.os.Binder;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;
import android.view.KeyEvent;

import androidx.core.app.NotificationCompat;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.regex.Pattern;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 音乐后台服务
 * - MediaPlayer 播放
 * - 前台通知栏控制
 * - 媒体按键(方向盘)处理
 * - 播放状态广播,供 UI 更新
 */
public class MusicService extends Service {

    private static final String TAG = "MusicService";
    public static final int NOTIF_ID = 1001;
    private static final String CHANNEL_ID = "captiva_music_channel";
    /**
     * LRC 时间轴标签,形如 [00:12.34]。
     * 用来区分「带时间轴的 LRC」和「纯文本歌词」——
     * 只判断「含 [ 且含 : 且含 ]」会把「作词: 某某」这类纯文本误判成 LRC。
     */
    private static final Pattern LRC_TIME_TAG = Pattern.compile("\\[\\d{1,2}:\\d{2}");

    /** 自动缓存完成广播:通知界面刷新来源标识(云端→本地) */
    public static final String ACTION_CACHE_AVAILABILITY_CHANGED =
            "com.captiva.musicplayer.CACHE_AVAILABILITY_CHANGED";

    /**
     * 自动缓存下载进度广播(未缓存歌曲的进度条显示)。
     * extras: streamId(歌曲服务端ID)、percent(0-100;-1=总长未知用不定进度;-2=下载结束,隐藏进度条)
     */
    public static final String ACTION_CACHE_PROGRESS =
            "com.captiva.musicplayer.CACHE_PROGRESS";

    // 对外广播 action
    public static final String ACTION_STATE_CHANGED = "com.captiva.musicplayer.STATE_CHANGED";
    public static final String ACTION_PROGRESS = "com.captiva.musicplayer.PROGRESS";
    // 内部命令 action
    public static final String CMD_PLAY = "com.captiva.musicplayer.PLAY";
    public static final String CMD_PAUSE = "com.captiva.musicplayer.PAUSE";
    public static final String CMD_NEXT = "com.captiva.musicplayer.NEXT";
    public static final String CMD_PREV = "com.captiva.musicplayer.PREV";
    public static final String CMD_STOP = "com.captiva.musicplayer.STOP";
    public static final String CMD_TOGGLE = "com.captiva.musicplayer.TOGGLE";
    public static final String CMD_PLAY_INDEX = "com.captiva.musicplayer.PLAY_INDEX";

    private MediaPlayer player;
    private final List<MusicBean> playList = new ArrayList<>();
    private int currentIndex = -1;
    private boolean isPrepared = false;
    /**
     * 播放器当前数据源是否本地 fd(true=本地文件/content uri,false=HTTP 流)。
     * 决定 seekTo 策略:本地 fd 直接 seekTo(任何播放栈都可靠);HTTP 流在车机
     * vendor 栈上 seek 静默失效(384/385 日志实锤),必须走挂起重播/本地重启。
     * 注意不能拿 bean.isNetwork() 判断 —— onCached 会把 bean 原地转本地,
     * 但活跃播放器仍挂在代理 HTTP 源上,直到下次 prepareAndPlay。
     */
    private boolean playerSourceIsLocal = false;

    /** 混合位置追踪:用系统时钟校正 VBR MP3 位置偏差(安卓4.x老设备常见问题) */
    private long posTrackRealtime = 0;   // 播放开始时的 SystemClock.elapsedRealtime()
    private int posTrackStartPos = 0;     // 播放开始时的位置(ms)
    private boolean posTrackingActive = false;
    /** 上次与 MediaPlayer 同步的时间,定期校正时钟漂移 */
    private long lastSyncRealtime = 0;
    private static final long SYNC_INTERVAL_MS = 10000; // 每10秒同步一次
    private static final int VBR_DRIFT_THRESHOLD = 1500; // 偏差超过1.5秒认为是VBR问题

    private RemoteControlClient remoteControlClient;
    private AudioManager audioManager;

    /** 音频焦点监听器:导航播报时压低音量,播报结束恢复 */
    private AudioManager.OnAudioFocusChangeListener audioFocusListener;
    /** 是否因失去焦点而暂停(用于焦点恢复时自动继续播放) */
    private boolean pausedByFocusLoss = false;
    /** 是否因 ducking 降低音量(用于恢复时还原音量) */
    private boolean ducked = false;
    /** ducking 前的原始音量 */
    private float volumeBeforeDuck = 1.0f;

    // 播放模式
    private PlayMode playMode = PlayMode.SEQUENCE;
    private final Random random = new Random();

    // 均衡器
    private EqualizerManager equalizerManager;

    // 主线程 Handler(用于异步歌词加载后更新 UI)
    private final android.os.Handler mainHandler = new android.os.Handler();

    /** 歌词加载线程池(单线程,可取消,避免Service销毁后线程泄漏) */
    private final ExecutorService lyricsExecutor = Executors.newSingleThreadExecutor();

    /** 自动缓存下载线程池(单线程,串行避免并发打爆服务器与存储;预缓存等后台任务) */
    private final ExecutorService cacheExecutor = Executors.newSingleThreadExecutor();
    /**
     * 当前播放歌曲的下载线程池(优先队列):当前歌的 播放时缓存/失败重播 在这里
     * **立即执行**,不排在预缓存任务后面 —— 资源倾斜于当前播放(2026-10-03 用户需求:
     * 旧单队列下重播下载最坏要等 3 首 × 40 秒预缓存)。与后台队列可并行,同歌互斥
     * 由 MusicSyncManager.IN_FLIGHT 保证;当前歌限速也用更高的优先档(400KB/s)。
     */
    private final ExecutorService priorityCacheExecutor = Executors.newSingleThreadExecutor();
    /** 配置(读取自动缓存开关/配额等) */
    private NavidromeConfig navidromeConfig;

    /**
     * 本地流代理(LocalStreamProxy)缓存回调:边下边播完成/失败时回调(后台线程)。
     * 完成后与 autoCacheSong 成功路径相同的三件套登记(StreamIdIndex / AutoCacheManifest /
     * 配额清理),并在主线程把当前 bean 原地转为本地歌 + 广播来源变化 + 触发预缓存后三首。
     */
    private final LocalStreamProxy.CacheCallback proxyCallback =
            new LocalStreamProxy.CacheCallback() {
        @Override
        public void onCached(final String streamId, final java.io.File finalFile) {
            // 当前歌已下载完:下载链路空出,预缓存可以恢复(播放只剩轻量本地文件读)
            currentViaProxy = false;
            // ---- 后台线程:登记(与 autoCacheSongLocked 成功路径一致) ----
            try {
                MusicBean song = findBeanByStreamId(streamId);
                String syncPath = navidromeConfig != null
                        ? navidromeConfig.getCloudDir() : null;
                if (song != null && syncPath != null && !syncPath.isEmpty()) {
                    StreamIdIndex.registerSong(MusicService.this, song, syncPath);
                }
                if (finalFile != null) {
                    AutoCacheManifest.add(MusicService.this,
                            finalFile.getAbsolutePath(), finalFile.length());
                    if (navidromeConfig != null && syncPath != null
                            && navidromeConfig.getAutoCacheMaxMb() > 0) {
                        long maxBytes =
                                (long) navidromeConfig.getAutoCacheMaxMb() * 1024L * 1024L;
                        AutoCacheManifest.evictToFit(MusicService.this, syncPath, maxBytes);
                    }
                }
                DownloadDiag.log("边下边播: 缓存登记完成 sid=" + streamId);
            } catch (Throwable t) {
                DownloadDiag.log("边下边播: 缓存登记异常 " + t);
            }
            // ---- 主线程:UI 状态 + 等待中的重播 + 预缓存 ----
            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    MusicBean cur = getCurrentMusic();
                    if (cur != null && streamId.equals(cur.getStreamId())) {
                        if (cur.isNetwork()) {
                            // 正在播的就是这首歌:原地转为本地歌(下次切回来直接走本地 fd)
                            cur.setNetwork(false);
                            cur.setData(finalFile.getAbsolutePath());
                            cur.setUri(null);
                            MusicDataHolder.getInstance().setCurrentPlayingMusic(cur);
                            Intent i = new Intent(ACTION_CACHE_AVAILABILITY_CHANGED);
                            i.putExtra("streamId", streamId);
                            sendBroadcast(i);
                        }
                        // 播放失败时登记过"等代理缓存完重播":现在缓存完成,本地重播。
                        // (典型场景:serve 竞态/瞬时错误导致播放失败,但代理仍在把
                        // 这首歌拉完 —— 拉完直接重播,不跳歌。)
                        if (streamId.equals(waitingProxyReplaySid)) {
                            waitingProxyReplaySid = null;
                            DownloadDiag.log("边下边播: 缓存完成,触发等待中的重播 "
                                    + cur.getTitle());
                            prepareAndPlay();
                            return;   // 重播路径(!viaProxy)末尾会自己触发预缓存
                        }
                    } else if (streamId.equals(waitingProxyReplaySid)) {
                        // 已经切到别的歌:作废重播意图
                        waitingProxyReplaySid = null;
                    }
                    // 带宽先给正在播的歌:现在它缓存完了,才开始预缓存后面的歌
                    preCacheUpcoming(3);
                }
            });
        }

        @Override
        public void onFailed(final String streamId, String reason) {
            // 失败只记日志:当前歌由 MediaPlayer 报错走 downloadThenPlay 兜底,
            // 预缓存由下一次播放成功路径触发,这里不抢带宽。
            currentViaProxy = false;   // 下载链路已结束(无论成败),解除预缓存让路
            DownloadDiag.log("边下边播: 缓存失败 sid=" + streamId + " 原因=" + reason);
            // 广播 percent=-1 清掉界面上的缓存进度(播放条缓冲段),语义同下载失败心跳
            Intent pi = new Intent(ACTION_CACHE_PROGRESS);
            pi.putExtra("streamId", streamId);
            pi.putExtra("percent", -1);
            sendBroadcast(pi);
            if (streamId != null && streamId.equals(waitingProxyReplaySid)) {
                // 等待重播中的歌代理也失败了:作废意图,走跳下一首兜底
                waitingProxyReplaySid = null;
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        postNextIfCurrent(playToken);
                    }
                });
            }
        }
    };

    // 当前歌词(供 UI 查询)
    private List<LrcEntry> currentLrc = new ArrayList<>();

    /** 防止快速切歌导致卡死:记录当前播放请求的唯一标识 */
    private volatile int playToken = 0;
    /**
     * 当前这首歌是否正走本地流代理(边下边播)下载中(2026-10-04 v5.7.379)。
     * 车机日志(09:34-09:41 段)实锤:流代理下载(400KB/s)+ 预缓存 3 首(100KB/s)
     * + 用户滚 810 首大列表同时发生时,2 核 CPU 与慢速 SD 卡被打满,主线程卡 5~20 秒。
     * 流代理期间预缓存一律让路:同一时刻只留"当前歌"这一条下载链路。
     */
    private volatile boolean currentViaProxy = false;
    /**
     * 播放失败但该歌的代理仍在缓存时的重播意图(streamId):
     * onCached 回调里检测到它就自动本地重播;切歌/代理失败时作废。
     */
    private volatile String waitingProxyReplaySid;
    /** 待跳转的播放位置(ms),prepareAndPlay完成后seekTo */
    private int pendingSeekPosition = 0;
    /** 切歌防抖:最小间隔(ms),避免连续快速切歌 */
    private static final long SWITCH_DEBOUNCE_MS = 300;

    private final IBinder binder = new MusicBinder();

    /** 供 Activity 绑定调用 */
    public class MusicBinder extends Binder {
        public MusicService getService() {
            return MusicService.this;
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        registerMediaButton();
        initAudioFocus();
        // 初始化均衡器并注册到全局,供 EqualizerActivity 使用
        // 设置 Context 用于持久化,并在启动时静默初始化(允许未播放时调节)
        equalizerManager = new EqualizerManager();
        equalizerManager.setContext(this);
        equalizerManager.initSilent();
        MusicDataHolder.getInstance().setEqualizerManager(equalizerManager);
        // 恢复上次播放模式
        NavidromeConfig config = new NavidromeConfig(this);
        // 一次性迁移:旧的"播放时自动缓存=关"重置为开(与 MainActivity 幂等,双保险)
        config.migrateAutoCacheOnPlayIfNeeded();
        playMode = PlayMode.fromValue(config.getPlayMode());
        // 【必须】给字段赋值:maybeAutoCache / promoteToLocalIfCached 都依赖它,
        // 此前该字段从未初始化(恒为 null),导致"播放时按需缓存"与"播放优先用缓存"
        // 这两条路径在判断处就直接返回,功能静默失效。
        navidromeConfig = config;
    }

    /**
     * 初始化音频焦点监听
     *
     * 导航播报时系统会请求 AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK,
     * 我们收到 AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK 时降低音量(ducking),
     * 焦点恢复后还原音量。
     *
     * 如果导航请求的是 AUDIOFOCUS_GAIN_TRANSIENT(不可压低),
     * 我们暂停播放,焦点恢复后自动继续。
     */
    private void initAudioFocus() {
        audioFocusListener = new AudioManager.OnAudioFocusChangeListener() {
            @Override
            public void onAudioFocusChange(int focusChange) {
                switch (focusChange) {
                    case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                        // 导航播报(可压低):降低音量到 20%
                        if (player != null && isPrepared && player.isPlaying()) {
                            ducked = true;
                            player.setVolume(0.2f, 0.2f);
                        }
                        break;

                    case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                        // 电话等(不可压低):暂停播放,等焦点恢复后自动继续
                        if (player != null && isPrepared && player.isPlaying()) {
                            pausedByFocusLoss = true;
                            player.pause();
                            stopPosTracking();
                            updateRemoteControlPlayState(false);
                            notifyState();
                        }
                        break;

                    case AudioManager.AUDIOFOCUS_GAIN:
                        // 焦点恢复:先还原音量
                        if (ducked) {
                            ducked = false;
                            if (player != null && isPrepared) {
                                player.setVolume(1.0f, 1.0f);
                            }
                        }
                        // 如果是因失去焦点而暂停的,自动恢复播放
                        if (pausedByFocusLoss) {
                            pausedByFocusLoss = false;
                            if (player != null && isPrepared && !player.isPlaying()) {
                                player.start();
                                startPosTracking(player.getCurrentPosition());
                                updateRemoteControlPlayState(true);
                                notifyState();
                            }
                        }
                        break;

                    case AudioManager.AUDIOFOCUS_LOSS:
                        // 永久失去焦点(如其他音乐应用):暂停播放,不自动恢复
                        pausedByFocusLoss = false;
                        ducked = false;
                        if (player != null && isPrepared && player.isPlaying()) {
                            player.pause();
                            stopPosTracking();
                            updateRemoteControlPlayState(false);
                            notifyState();
                            updateNotification();
                        }
                        // 释放音频焦点
                        if (audioManager != null) {
                            audioManager.abandonAudioFocus(audioFocusListener);
                        }
                        break;
                }
            }
        };
    }

    /**
     * 请求音频焦点(在开始播放时调用)
     * @return true 如果获得焦点
     */
    private boolean requestAudioFocus() {
        if (audioManager == null || audioFocusListener == null) return true;
        int result = audioManager.requestAudioFocus(
                audioFocusListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN);
        return result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
    }

    /**
     * 释放音频焦点(在停止播放时调用)
     */
    private void abandonAudioFocus() {
        if (audioManager != null && audioFocusListener != null) {
            audioManager.abandonAudioFocus(audioFocusListener);
            ducked = false;
            pausedByFocusLoss = false;
        }
    }

    /** 注册媒体按键接收,响应方向盘控制 */
    private void registerMediaButton() {
        try {
            ComponentName comp = new ComponentName(getPackageName(), MediaButtonReceiver.class.getName());
            audioManager.registerMediaButtonEventReceiver(comp);
            // 构建 RemoteControlClient(API 14+),用于锁屏/车机方控
            Intent mediaButtonIntent = new Intent(Intent.ACTION_MEDIA_BUTTON);
            mediaButtonIntent.setComponent(comp);
            PendingIntent pendingIntent = PendingIntent.getBroadcast(this, 0, mediaButtonIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT);
            remoteControlClient = new RemoteControlClient(pendingIntent);
            remoteControlClient.setTransportControlFlags(
                    RemoteControlClient.FLAG_KEY_MEDIA_PLAY
                            | RemoteControlClient.FLAG_KEY_MEDIA_PAUSE
                            | RemoteControlClient.FLAG_KEY_MEDIA_PLAY_PAUSE
                            | RemoteControlClient.FLAG_KEY_MEDIA_NEXT
                            | RemoteControlClient.FLAG_KEY_MEDIA_PREVIOUS);
            audioManager.registerRemoteControlClient(remoteControlClient);
        } catch (Exception e) {
            Log.w(TAG, "registerMediaButton failed", e);
        }
    }

    /** 设置播放列表并指定起始位置 */
    public void setPlayList(List<MusicBean> list, int startIndex) {
        playList.clear();
        if (list != null) {
            playList.addAll(list);
        }
        currentIndex = startIndex >= 0 && startIndex < playList.size() ? startIndex : 0;
    }

    /**
     * 增量追加歌曲到播放队列末尾(不改变 currentIndex、不重启当前播放)。
     * 用于同步下载新歌时:只把新歌接到队列末尾,供当前歌曲播完后继续播放,
     * 完全不重设/重排现有队列,确保正在播放的歌不跳变、不被打断。
     */
    public void appendToPlayList(List<MusicBean> songs) {
        if (songs == null || songs.isEmpty()) return;
        playList.addAll(songs);
    }

    public List<MusicBean> getPlayList() {
        return playList;
    }

    public int getCurrentIndex() {
        return currentIndex;
    }

    public MusicBean getCurrentMusic() {
        if (currentIndex >= 0 && currentIndex < playList.size()) {
            return playList.get(currentIndex);
        }
        return null;
    }

    public boolean isPlaying() {
        return player != null && isPrepared && player.isPlaying();
    }

    public int getCurrentPosition() {
        if (player != null && isPrepared) {
            try {
                int playerPos = player.getCurrentPosition();

                // 混合位置追踪:播放中用系统时钟估算,校正 VBR MP3 位置偏差
                // (安卓4.x 老设备 MediaPlayer 对 VBR 文件位置报告不准,歌词会不同步)
                if (posTrackingActive && posTrackRealtime > 0) {
                    long elapsed = android.os.SystemClock.elapsedRealtime() - posTrackRealtime;
                    int estimatedPos = posTrackStartPos + (int) elapsed;

                    // 定期与 MediaPlayer 同步(校正时钟漂移,但不同步到 VBR 错误位置)
                    long now = android.os.SystemClock.elapsedRealtime();
                    if (now - lastSyncRealtime > SYNC_INTERVAL_MS) {
                        lastSyncRealtime = now;
                        // 如果 MediaPlayer 位置与估算接近,以 MediaPlayer 为准(消除时钟漂移)
                        if (Math.abs(playerPos - estimatedPos) < VBR_DRIFT_THRESHOLD) {
                            posTrackRealtime = now;
                            posTrackStartPos = playerPos;
                        }
                        // 如果差异大(VBR 偏差),保持估算位置不做同步
                    }

                    // 差异超过阈值:VBR 偏差,用估算位置(更准确)
                    if (Math.abs(playerPos - estimatedPos) > VBR_DRIFT_THRESHOLD) {
                        return estimatedPos;
                    }
                    // 差异小:用 MediaPlayer 位置(CBR 更精确)
                    return playerPos;
                }
                return playerPos;
            } catch (Exception e) {
                return 0;
            }
        }
        return 0;
    }

    public int getDuration() {
        if (player != null && isPrepared) {
            try {
                return player.getDuration();
            } catch (Exception e) {
                return 0;
            }
        }
        return 0;
    }

    public void seekTo(int msec) {
        if (player != null && isPrepared && playerSourceIsLocal) {
            // 本地 fd(本地歌/缓存完成后重启的歌):直接 seek,任何播放栈都可靠
            try {
                player.seekTo(msec);
                // 拖动进度条后重置位置追踪起点
                if (posTrackingActive) {
                    posTrackRealtime = android.os.SystemClock.elapsedRealtime();
                    posTrackStartPos = msec;
                    lastSyncRealtime = posTrackRealtime;
                }
                DownloadDiag.log("seek: 本地跳转到 " + (msec / 1000) + "s");
            } catch (Exception e) {
                Log.w(TAG, "seekTo failed", e);
            }
            return;
        }
        if (player != null && isPrepared) {
            // HTTP 源:车机 vendor 栈对网络流 seekTo 静默失效(384/385 日志实锤:
            // seekTo 成功返回但不发任何新请求,重启 HTTP 源后 onPrepared 的 seekTo
            // 同样被吞,还会把位置追踪起点错设到目标点 → 时间显示 05:47/04:43)。
            MusicBean cur = getCurrentMusic();
            String sid = (cur != null) ? cur.getStreamId() : null;
            if (sid != null && !sid.isEmpty() && currentViaProxy) {
                // 下载进行中:不停下载链路,挂起目标点;缓存完成后 onCached
                // 自动本地重播(bean 已转本地 fd,seek 可靠),从目标点继续。
                pendingSeekPosition = msec;
                waitingProxyReplaySid = sid;
                DownloadDiag.log("seek: 网络流下载中 → 挂起目标点 "
                        + (msec / 1000) + "s,缓存完成后本地重播");
                return;
            }
            if (sid != null && !sid.isEmpty()) {
                // 缓存已完成(onCached 已把 bean 转本地):本地 fd 重启,秒级跳转
                pendingSeekPosition = msec;
                DownloadDiag.log("seek: 网络流缓存已完成 → 本地重启跳转到 "
                        + (msec / 1000) + "s");
                prepareAndPlay();
                return;
            }
            // 无 streamId(纯直连,无缓存链路):尽力直接 seek + 看门狗兜底
            try {
                player.seekTo(msec);
                if (posTrackingActive) {
                    posTrackRealtime = android.os.SystemClock.elapsedRealtime();
                    posTrackStartPos = msec;
                    lastSyncRealtime = posTrackRealtime;
                }
                DownloadDiag.log("seek: 直连流尝试跳转 " + (msec / 1000) + "s (无缓存兜底)");
                scheduleSeekVerify(msec);
            } catch (Exception e) {
                Log.w(TAG, "seekTo failed", e);
            }
            return;
        }
        // 未 prepared:挂起,onPrepared 应用(本地 fd 直接生效;HTTP 源由
        // onPrepared 的看门狗校验,未生效转"缓存完成后本地重播"兜底)
        pendingSeekPosition = msec;
        DownloadDiag.log("seek: 跳转到 " + (msec / 1000) + "s (未prepared,挂起待起播生效)");
    }

    // ==== seek 生效看门狗 ====
    // 部分车机定制播放栈(mediaserver 原生进程)对 HTTP 流源的 seekTo 不响应也不报错:
    // seekTo 返回成功,但 getCurrentPosition() 纹丝不动,也无任何新 Range 请求发到
    // 代理(2026-10-04 车机日志实锤:42 次 seek 全部"已prepared",越界 seek 却连一条
    // 代理跳跃日志都没有;同版本 APK 在 AOSP 模拟器上完全正常)。对策:seek 后 2.5s
    // 校验实际位置,偏差 >8s 判定未生效 → 重启当前曲目到目标点(prepareAndPlay 会
    // 先 promoteToLocalIfCached 转本地 fd,onPrepared 应用 pendingSeekPosition,
    // 本地 seek 不依赖 vendor 栈的 HTTP seek 实现)。
    private int seekVerifyTarget = -1;
    private int seekVerifyToken = -1;
    private final Runnable seekVerifyRunnable = new Runnable() {
        @Override
        public void run() {
            if (seekVerifyTarget < 0 || player == null || !isPrepared) {
                return;
            }
            if (seekVerifyToken != playToken) {
                return;   // 校验期间已切歌,作废
            }
            int target = seekVerifyTarget;
            seekVerifyTarget = -1;
            int cur = -1;
            try {
                cur = player.getCurrentPosition();
            } catch (Throwable ignored) {
            }
            if (cur < 0 || Math.abs(cur - target) > 8000) {
                MusicBean b = getCurrentMusic();
                String sid = (b != null) ? b.getStreamId() : null;
                if (b != null && b.isNetwork() && sid != null && !sid.isEmpty()
                        && currentViaProxy) {
                    // HTTP 流:重启 HTTP 源没有意义(vendor 栈连 onPrepared 的
                    // seekTo 都吞)→ 停播挂起,缓存完成后 onCached 本地重播
                    DownloadDiag.log("seek: 未生效(实际 " + (Math.max(cur, 0) / 1000)
                            + "s ≠ 目标 " + (target / 1000) + "s)→ 停播挂起,缓存完成后本地重播");
                    pendingSeekPosition = target;
                    waitingProxyReplaySid = sid;
                    try {
                        player.stop();
                        player.release();
                    } catch (Throwable ignored) {
                    }
                    player = null;
                    isPrepared = false;
                    stopPosTracking();
                    notifyState();
                } else if (b != null && !b.isNetwork()) {
                    // 本地 fd seek 失败(罕见):重启本地播放跳转
                    DownloadDiag.log("seek: 未生效(实际 " + (Math.max(cur, 0) / 1000)
                            + "s ≠ 目标 " + (target / 1000) + "s)→ 重启本地播放跳转");
                    pendingSeekPosition = target;
                    prepareAndPlay();
                } else {
                    DownloadDiag.log("seek: 未生效(实际 " + (Math.max(cur, 0) / 1000)
                            + "s ≠ 目标 " + (target / 1000) + "s),无缓存链路可兜底,放弃");
                }
            }
        }
    };

    private void scheduleSeekVerify(int targetMsec) {
        seekVerifyTarget = targetMsec;
        seekVerifyToken = playToken;
        mainHandler.removeCallbacks(seekVerifyRunnable);
        mainHandler.postDelayed(seekVerifyRunnable, 2500);
    }

    /** 开始位置追踪(播放开始/恢复时调用) */
    private void startPosTracking(int startPosition) {
        posTrackRealtime = android.os.SystemClock.elapsedRealtime();
        posTrackStartPos = startPosition;
        posTrackingActive = true;
        lastSyncRealtime = posTrackRealtime;
    }

    /** 停止位置追踪(暂停/切歌时调用) */
    private void stopPosTracking() {
        posTrackingActive = false;
    }

    /** 播放指定索引 */
    public void playIndex(int index) {
        if (playList.isEmpty() || index < 0 || index >= playList.size()) {
            return;
        }
        currentIndex = index;
        pendingSeekPosition = 0;
        prepareAndPlay();
    }

    /** 播放指定索引并跳转到指定进度(ms) */
    public void playIndexWithSeek(int index, int positionMs) {
        if (playList.isEmpty() || index < 0 || index >= playList.size()) {
            return;
        }
        currentIndex = index;
        pendingSeekPosition = positionMs;
        prepareAndPlay();
    }

    /** 切换播放/暂停 */
    public void toggle() {
        if (player != null && isPrepared) {
            if (player.isPlaying()) {
                pause();
            } else {
                resume();
            }
        } else {
            playIndex(currentIndex < 0 ? 0 : currentIndex);
        }
    }

    public void resume() {
        if (player != null && isPrepared && !player.isPlaying()) {
            player.start();
            // 恢复位置追踪(从当前播放位置开始计时)
            startPosTracking(player.getCurrentPosition());
            updateRemoteControlPlayState(true);
            notifyState();
            updateNotification();
        }
    }

    public void pause() {
        if (player != null && isPrepared && player.isPlaying()) {
            player.pause();
            // 停止位置追踪
            stopPosTracking();
            updateRemoteControlPlayState(false);
            notifyState();
            updateNotification();
        }
    }

    public void next() {
        if (playList.isEmpty()) {
            return;
        }
        if (playMode == PlayMode.REPEAT_ONE) {
            // 单曲循环:重新播放当前
            prepareAndPlay();
            return;
        }
        if (playMode == PlayMode.SHUFFLE) {
            if (playList.size() == 1) {
                currentIndex = 0;
            } else {
                int n;
                do {
                    n = random.nextInt(playList.size());
                } while (n == currentIndex);
                currentIndex = n;
            }
            prepareAndPlay();
            return;
        }
        // 顺序播放:到末尾停止
        if (currentIndex >= playList.size() - 1) {
            // 列表结束,停留在最后一首(不自动停止,保持可恢复)
            currentIndex = playList.size() - 1;
            prepareAndPlay();
        } else {
            currentIndex = (currentIndex + 1) % playList.size();
            prepareAndPlay();
        }
    }

    public void prev() {
        if (playList.isEmpty()) {
            return;
        }
        if (playMode == PlayMode.REPEAT_ONE) {
            prepareAndPlay();
            return;
        }
        if (playMode == PlayMode.SHUFFLE) {
            if (playList.size() == 1) {
                currentIndex = 0;
            } else {
                int n;
                do {
                    n = random.nextInt(playList.size());
                } while (n == currentIndex);
                currentIndex = n;
            }
            prepareAndPlay();
            return;
        }
        currentIndex = (currentIndex - 1 + playList.size()) % playList.size();
        prepareAndPlay();
    }

    /** 设置播放模式 */
    public void setPlayMode(PlayMode mode) {
        this.playMode = mode;
        notifyState();
    }

    public PlayMode getPlayMode() {
        return playMode;
    }

    /** 切换到下一个播放模式 */
    public PlayMode cyclePlayMode() {
        playMode = playMode.next();
        // 持久化保存播放模式
        new NavidromeConfig(this).setPlayMode(playMode.getValue());
        notifyState();
        return playMode;
    }

    /** 获取当前歌词列表 */
    public List<LrcEntry> getCurrentLrc() {
        return currentLrc;
    }

    /**
     * 加载歌词
     * 优先策略:
     * 1. 如果歌曲有本地文件,优先从内嵌ID3标签提取,再回退同名 .lrc 文件
     * 2. 本地没有歌词时,再尝试从 Navidrome 获取(网络歌曲或配置了服务器的本地歌曲)
     * 3. 纯网络歌曲(无本地文件)直接走 Navidrome API
     *
     * 关键点:同步下载到本地的歌曲 originally 是网络歌曲(network=true),
     * 但只要本地有文件,就应该优先用本地歌词,断网也能正常显示。
     */
    private void loadLyrics(final MusicBean bean) {
        // 先清空当前歌词
        currentLrc = new ArrayList<>();

        String filePath = bean.getData();
        boolean hasLocalFile = filePath != null && !filePath.isEmpty() && new File(filePath).exists();

        if (hasLocalFile) {
            // 本地有文件:优先加载本地歌词(内嵌 + .lrc),本地没有才回退网络
            loadLocalLyrics(bean);
        } else {
            // 纯网络歌曲:从 Navidrome 获取
            loadNetworkLyrics(bean);
        }
    }

    /** 异步加载本地歌曲歌词 */
    private void loadLocalLyrics(final MusicBean bean) {
        final String filePath = bean.getData();

        lyricsExecutor.execute(new Runnable() {
            @Override
            public void run() {
                // 歌词加载是纯后台任务,降为后台优先级(2026-10-04)
                try {
                    android.os.Process.setThreadPriority(
                            android.os.Process.THREAD_PRIORITY_BACKGROUND);
                } catch (Throwable ignored) {
                }
                List<LrcEntry> lyrics = null;

                // 1. 优先从音乐文件内嵌标签提取歌词
                if (filePath != null && !filePath.isEmpty()) {
                    try {
                        String embedded = EmbeddedLyricsExtractor.extract(filePath);
                        if (embedded != null && !embedded.isEmpty()) {
                            // 判断是 LRC 格式(含时间标签)还是纯文本
                            if (embedded.contains("[") && embedded.contains(":") && embedded.contains("]")) {
                                lyrics = LrcParser.parseLrcText(embedded);
                                Log.d(TAG, "内嵌LRC歌词解析: " + lyrics.size() + " 行");
                            } else {
                                // 纯文本歌词:按 5 秒间隔分配时间戳
                                lyrics = LrcParser.parsePlainTextLyrics(embedded, 5000);
                                Log.d(TAG, "内嵌纯文本歌词解析: " + lyrics.size() + " 行");
                            }
                        }
                    } catch (Exception e) {
                        Log.w(TAG, "提取内嵌歌词失败", e);
                    }

                    // 2. 内嵌歌词没有,回退同名 .lrc 文件
                    if (lyrics == null || lyrics.isEmpty()) {
                        try {
                            lyrics = LrcParser.loadLrc(filePath);
                            if (lyrics != null && !lyrics.isEmpty()) {
                                Log.d(TAG, "从 .lrc 文件加载歌词: " + lyrics.size() + " 行");
                            }
                        } catch (Exception e) {
                            Log.w(TAG, "加载 .lrc 文件失败", e);
                        }
                    }
                }

                // 3. 本地歌词都没有,先查歌词缓存(断网时可用)
                if (lyrics == null || lyrics.isEmpty()) {
                    LyricCache lyricCache = new LyricCache(MusicService.this);
                    String sid = bean.getStreamId();
                    if (sid != null && !sid.isEmpty()) {
                        lyrics = lyricCache.load(sid);
                        if (lyrics != null && !lyrics.isEmpty()) {
                            Log.d(TAG, "从歌词缓存加载(本地歌曲): " + lyrics.size() + " 行");
                        }
                    }
                }

                // 4. 缓存也没有,尝试从 Navidrome 按歌手+歌名获取歌词(需联网)
                if (lyrics == null || lyrics.isEmpty()) {
                    MusicSourceApi api = MusicDataHolder.getInstance().getMusicSourceApi();
                    if (api != null && MusicDataHolder.getInstance().isNavidromeEnabled()) {
                        try {
                            // 优先尝试 getLyricsBySongId(结构化同步歌词)
                            String sid = bean.getStreamId();
                            if (sid != null && !sid.isEmpty()) {
                                lyrics = api.getLyricsBySongId(sid);
                            }
                            // 回退到 getLyrics(纯文本)
                            if (lyrics == null || lyrics.isEmpty()) {
                                String plainText = api.getLyrics(bean.getArtist(), bean.getTitle());
                                if (plainText != null && !plainText.isEmpty()) {
                                    if (plainText.contains("[") && plainText.contains(":") && plainText.contains("]")) {
                                        lyrics = LrcParser.parseLrcText(plainText);
                                    } else {
                                        lyrics = LrcParser.parsePlainTextLyrics(plainText, 5000);
                                    }
                                }
                            }
                            // 获取成功,缓存到本地(断网下次可用)
                            if (lyrics != null && !lyrics.isEmpty()) {
                                Log.d(TAG, "本地歌曲从Navidrome获取歌词: " + lyrics.size() + " 行,缓存到本地");
                                LyricCache lyricCache = new LyricCache(MusicService.this);
                                lyricCache.saveBoth(filePath, bean.getStreamId(), lyrics);
                            }
                        } catch (Exception e) {
                            Log.w(TAG, "从Navidrome获取本地歌曲歌词失败", e);
                        }
                    }
                }

                // 5. 服务器也没词 → 公开歌词源兜底(lrclib.net,按歌手+歌名)
                if (lyrics == null || lyrics.isEmpty()) {
                    List<LrcEntry> pub = fetchFromPublicSource(bean);
                    if (pub != null && !pub.isEmpty()) {
                        lyrics = pub;
                        Log.d(TAG, "本地歌曲从公开歌词源获取: " + lyrics.size() + " 行,写回本地");
                        LyricCache pubCache = new LyricCache(MusicService.this);
                        pubCache.saveBoth(filePath, bean.getStreamId(), lyrics);
                    }
                }

                final List<LrcEntry> result = lyrics != null ? lyrics : new ArrayList<LrcEntry>();
                // 在主线程更新歌词并通知 UI
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        // 确保仍然是当前歌曲(用歌名+歌手比较,兼容无文件路径的情况)
                        MusicBean current = getCurrentMusic();
                        if (current != null && isSameSong(current, bean)) {
                            currentLrc = result;
                            notifyState();
                            Log.d(TAG, "本地歌词加载完成: " + result.size() + " 行");
                        }
                    }
                });
            }
        });
    }

    /** 判断两首歌曲是否为同一首(优先用文件路径,其次用歌名+歌手) */
    private boolean isSameSong(MusicBean a, MusicBean b) {
        if (a == null || b == null) return false;
        // 优先比较文件路径
        if (a.getData() != null && b.getData() != null) {
            return a.getData().equals(b.getData());
        }
        // 回退到歌名+歌手比较
        String aKey = (a.getTitle() != null ? a.getTitle() : "") + "|" + (a.getArtist() != null ? a.getArtist() : "");
        String bKey = (b.getTitle() != null ? b.getTitle() : "") + "|" + (b.getArtist() != null ? b.getArtist() : "");
        return aKey.equals(bKey);
    }

    /**
     * 公开歌词源兜底:服务器(飞牛 / Navidrome)也取不到词时,按歌手 + 歌名到 lrclib.net 再试一次。
     * 必须在后台线程调用(内部发网络请求);取不到 / 外网不通 / TLS 失败一律返回 null,静默降级。
     */
    private List<LrcEntry> fetchFromPublicSource(MusicBean bean) {
        try {
            long durationSec = bean.getDuration() > 0 ? bean.getDuration() / 1000 : 0;
            String text = LrclibClient.fetchLyrics(bean.getArtist(), bean.getTitle(), durationSec);
            if (text == null || text.trim().isEmpty()) {
                return null;
            }
            // 判断是否带时间轴:[00:12.34] 这样的标签才算 LRC。
            // 不能用「含 [ 且含 : 且含 ]」—— 纯文本里的「作词: 某某」会被误判成时间轴歌词。
            if (LRC_TIME_TAG.matcher(text).find()) {
                return LrcParser.parseLrcText(text);
            }
            return LrcParser.parsePlainTextLyrics(text, 5000);
        } catch (Exception e) {
            Log.w(TAG, "公开歌词源兜底失败", e);
            return null;
        }
    }

    /** 异步加载网络歌曲歌词 */
    private void loadNetworkLyrics(final MusicBean bean) {
        final String songId = bean.getStreamId();
        if (songId == null || songId.isEmpty()) {
            return;
        }

        lyricsExecutor.execute(new Runnable() {
            @Override
            public void run() {
                // 歌词加载是纯后台任务,降为后台优先级(2026-10-04)
                try {
                    android.os.Process.setThreadPriority(
                            android.os.Process.THREAD_PRIORITY_BACKGROUND);
                } catch (Throwable ignored) {
                }
                List<LrcEntry> lyrics = null;

                // 0. 先查歌词缓存(断网时可用)
                LyricCache lyricCache = new LyricCache(MusicService.this);
                lyrics = lyricCache.load(songId);
                if (lyrics != null && !lyrics.isEmpty()) {
                    Log.d(TAG, "从歌词缓存加载(网络歌曲): " + lyrics.size() + " 行");
                    final List<LrcEntry> cachedResult = lyrics;
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            MusicBean current = getCurrentMusic();
                            if (current != null && songId.equals(current.getStreamId())) {
                                currentLrc = cachedResult;
                                notifyState();
                            }
                        }
                    });
                    return;
                }

                // 缓存没有,从 Navidrome 获取
                MusicSourceApi api = MusicDataHolder.getInstance().getMusicSourceApi();

                if (api != null) {
                    // 优先尝试 getLyricsBySongId(结构化同步歌词)
                    try {
                        lyrics = api.getLyricsBySongId(songId);
                    } catch (Exception e) {
                        Log.w(TAG, "getLyricsBySongId failed", e);
                    }

                    // 如果没有获取到,回退到 getLyrics(纯文本)
                    if (lyrics == null || lyrics.isEmpty()) {
                        try {
                            String plainText = api.getLyrics(bean.getArtist(), bean.getTitle());
                            if (plainText != null && !plainText.isEmpty()) {
                                // 检查是否为 LRC 格式(含时间标签)
                                if (plainText.contains("[") && plainText.contains(":") && plainText.contains("]")) {
                                    lyrics = LrcParser.parseLrcText(plainText);
                                } else {
                                    // 纯文本歌词:按 5 秒间隔分配时间戳
                                    lyrics = LrcParser.parsePlainTextLyrics(plainText, 5000);
                                }
                            }
                        } catch (Exception e) {
                            Log.w(TAG, "getLyrics fallback failed", e);
                        }
                    }

                    // 获取成功,缓存到本地(断网下次可用)
                    if (lyrics != null && !lyrics.isEmpty()) {
                        Log.d(TAG, "网络歌词获取成功: " + lyrics.size() + " 行,缓存到本地");
                        lyricCache.save(songId, lyrics);
                    }
                }

                // 服务器也没词 → 公开歌词源兜底(lrclib.net,按歌手+歌名)
                if (lyrics == null || lyrics.isEmpty()) {
                    List<LrcEntry> pub = fetchFromPublicSource(bean);
                    if (pub != null && !pub.isEmpty()) {
                        lyrics = pub;
                        Log.d(TAG, "网络歌曲从公开歌词源获取: " + lyrics.size() + " 行,缓存到本地");
                        lyricCache.save(songId, lyrics);
                    }
                }

                final List<LrcEntry> result = lyrics != null ? lyrics : new ArrayList<LrcEntry>();
                // 在主线程更新歌词并通知 UI
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        // 确保仍然是当前歌曲(避免切歌后更新旧歌词)
                        MusicBean current = getCurrentMusic();
                        if (current != null && songId.equals(current.getStreamId())) {
                            currentLrc = result;
                            notifyState();
                            Log.d(TAG, "网络歌词加载完成: " + result.size() + " 行");
                        }
                    }
                });
            }
        });
    }

    /** 准备并播放当前曲目(增加防抖,避免快速切歌卡死) */
    private void prepareAndPlay() {
        MusicBean bean = getCurrentMusic();
        if (bean == null) {
            return;
        }
        // 播放优先使用缓存:列表状态可能滞后(如刚在别的入口下载完成),
        // 播放前再做一次本地文件检查,已缓存则直接转本地播放,绝不联网重复拉流。
        //
        // ===== 主线程耗时诊断 =====
        // 本方法由 binder 调用,与服务端同进程 → 整体跑在主线程上,任何一步的
        // 磁盘 I/O / 网络请求都会直接变成 UI 卡顿。下面逐段计时并汇总成一行日志
        // (CacheDebugLog 已改为异步落盘,记录本身不产生阻塞)。
        final long tStart = System.currentTimeMillis();
        bean = promoteToLocalIfCached(bean);
        // 更新全局当前播放歌曲(供 EqualizerActivity 等获取)
        MusicDataHolder.getInstance().setCurrentPlayingMusic(bean);
        // 增加 token:每次切歌都递增,旧请求自动作废
        final int token = ++playToken;
        final long tPromote = System.currentTimeMillis() - tStart;

        // 先重置 MediaPlayer,取消之前的异步准备
        resetPlayer();
        final long tReset = System.currentTimeMillis() - tStart - tPromote;
        long tAuth = 0L;
        long tSetDs = 0L;
        /** 本次播放是否走了本地流代理(边下边播):决定预缓存的触发时机 */
        boolean viaProxy = false;
        currentViaProxy = false;   // 每次播放先复位,下方走代理再置位

        try {
            // 网络歌曲:用 Navidrome stream URL
            // 本地歌曲:优先用 content uri,失败回退文件路径
            if (bean.isNetwork() && bean.getStreamUrl() != null) {
                playerSourceIsLocal = false;   // HTTP 源(代理或直连)
                long tA = System.currentTimeMillis();
                // 部分数据源(如飞牛)的流地址不含凭据,必须走请求头
                java.util.Map<String, String> headers = null;
                MusicSourceApi src = MusicDataHolder.getInstance().getMusicSourceApi();
                if (src != null) {
                    headers = src.getAuthHeaders();
                }
                tAuth = System.currentTimeMillis() - tA;
                long tB = System.currentTimeMillis();
                // ===== 边下边播:本地流代理优先 =====
                // MediaPlayer 直连 HTTPS 走它自己的老网络栈(4.2.2 只开 SSLv3/TLSv1.0),
                // 飞牛中继等要求 TLS 1.2 的站点必然握手失败(-1011)。代理把链路倒过来:
                // MediaPlayer 连本机 127.0.0.1 纯 HTTP(无 TLS 问题),代理用 TlsCompat
                // 拉上游流并同步写 .part 落盘,客户端从"正在增长的本地文件"读 ——
                // 起播只需 1~2 秒(上游握手+首批字节),不再等整首下完。
                // 注册失败(端口占用/autoCacheSong 正在下载同一首/已完整缓存)则回退直连。
                try {
                    String proxySyncPath = navidromeConfig != null
                            ? navidromeConfig.getCloudDir() : null;
                    if (navidromeConfig != null && navidromeConfig.isAutoCacheOnPlay()
                            && proxySyncPath != null && !proxySyncPath.isEmpty()
                            && bean.getStreamId() != null && !bean.getStreamId().isEmpty()) {
                        java.io.File target =
                                MusicSyncManager.buildLocalFile(bean, proxySyncPath);
                        // 已完整缓存的目标不走代理(promoteToLocalIfCached 通常已拦截,这里双保险)
                        if (target != null && !(target.exists() && target.length() > 1024)
                                && LocalStreamProxy.get().register(bean.getStreamId(),
                                        bean.getStreamUrl(), headers, target, proxyCallback)) {
                            player.setDataSource(this, android.net.Uri.parse(
                                    LocalStreamProxy.get().url(bean.getStreamId())));
                            viaProxy = true;
                            currentViaProxy = true;
                            DownloadDiag.log("联网播放: 走本地流代理(边下边播) "
                                    + bean.getTitle());
                        }
                    }
                } catch (Throwable t) {
                    // 代理任何异常都不能影响播放:回退直连
                    DownloadDiag.log("边下边播代理异常,回退直连: " + t);
                }
                if (!viaProxy) {
                // 注意:MediaPlayer 自己发网络请求,**不走 TlsCompat**。
                // 安卓 4.2.2 的媒体栈只开 SSLv3/TLSv1.0,遇到要求 TLS 1.2 的 HTTPS 站点
                // (飞牛中继等)会直接握手失败 —— 这就是"列表能刷出来、点击却播不了"的典型根因。
                DownloadDiag.log("联网播放: " + bean.getTitle()
                        + " url=" + DownloadDiag.safeUrl(bean.getStreamUrl())
                        + " 鉴权头=" + (headers == null ? "无" : headers.size() + "个"));
                if (headers != null && !headers.isEmpty()) {
                    player.setDataSource(this, android.net.Uri.parse(bean.getStreamUrl()), headers);
                } else {
                    player.setDataSource(bean.getStreamUrl());
                }
                }
                tSetDs = System.currentTimeMillis() - tB;
            } else if (bean.getUri() != null && bean.getUri().startsWith("content://")) {
                // MediaStore 扫描出的本地歌:content uri 由应用侧打开 fd,车机可正常播放
                playerSourceIsLocal = true;
                player.setDataSource(this, android.net.Uri.parse(bean.getUri()));
            } else {
                // 本地文件:应用进程自己打开、把 fd 交给 MediaPlayer。
                playerSourceIsLocal = true;
                // ★ 不能用 setDataSource(路径) —— 路径方式由 mediaserver(native 服务进程)
                //   打开文件,车机上 mediaserver 对 /storage/sdcard1(U盘/SD 二级存储)无读权限,
                //   prepare 直接报 (1,-1011) —— 这就是"缓存成功但不自动重播、重启后才能播"
                //   的根因(2026-10-03 日志实锤:缓存转换的 bean 播放全失败,重启后同批文件
                //   经 MediaStore content uri 就能播)。
                //   应用进程有读写权限(java.io 写入/读取都正常),fd 经 binder 传给
                //   mediaserver 时由系统 dup,本侧 close 安全。
                String localPath = (bean.getData() != null && !bean.getData().isEmpty())
                        ? bean.getData()
                        : bean.getUri();   // 兼容扫描器回退写入的"路径形式的 uri"
                if (localPath != null && localPath.startsWith("file://")) {
                    localPath = localPath.substring("file://".length());
                }
                if (localPath == null || localPath.isEmpty()) {
                    return;
                }
                java.io.FileInputStream fis = null;
                try {
                    fis = new java.io.FileInputStream(localPath);
                    player.setDataSource(fis.getFD());
                } finally {
                    if (fis != null) {
                        try {
                            fis.close();
                        } catch (Exception ignored) {
                        }
                    }
                }
            }
            // API 21 之前用 setAudioStreamType
            player.setAudioStreamType(AudioManager.STREAM_MUSIC);
            player.setWakeMode(this, PowerManager.PARTIAL_WAKE_LOCK);
            final MusicBean currentBean = bean;
            player.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                @Override
                public void onPrepared(MediaPlayer mp) {
                    // 检查 token:如果已切到下一首,放弃这次准备
                    if (token != playToken) {
                        return;
                    }
                    try {
                        isPrepared = true;
                        // 能正常起播就不再需要"等代理缓存完重播"的意图了
                        waitingProxyReplaySid = null;
                        // 初始化均衡器(绑定当前 audioSession,必须在 start() 之前)
                        // 这样均衡器才能从第一帧开始生效
                        try {
                            int sessionId = mp.getAudioSessionId();
                            equalizerManager.init(sessionId);
                            // 切歌时应用单曲绑定的EQ(有绑定则用绑定的预设,无则恢复全局设置)
                            equalizerManager.applySongEq(currentBean);
                        } catch (Exception e) {
                            Log.w(TAG, "equalizer init failed", e);
                        }
                        // 请求音频焦点(导航播报时系统才能压低音乐音量)
                        requestAudioFocus();
                        // 确保音量正常(可能之前 ducking 后未恢复)
                        mp.setVolume(1.0f, 1.0f);
                        mp.start();
                        // 恢复上次播放进度
                        if (pendingSeekPosition > 0) {
                            try {
                                mp.seekTo(pendingSeekPosition);
                            } catch (Exception e) {
                                Log.w(TAG, "seekTo failed", e);
                            }
                            // 位置追踪从恢复的进度开始(VBR 校正)
                            startPosTracking(pendingSeekPosition);
                            if (currentBean.isNetwork()) {
                                // HTTP 源:车机 vendor 栈对起播时的首次 seekTo 同样
                                // 静默失效(表现为时间显示错乱,如 05:47/04:43)。
                                // 校验实际位置,未生效转"缓存完成后本地重播"兜底。
                                scheduleSeekVerify(pendingSeekPosition);
                            }
                            pendingSeekPosition = 0;
                        } else {
                            // 从头播放,位置追踪从 0 开始
                            startPosTracking(0);
                        }
                        // 加载歌词
                        loadLyrics(currentBean);
                        updateRemoteControlMetadata(currentBean);
                        updateRemoteControlPlayState(true);
                        notifyState();
                        updateNotification();
                    } catch (Exception e) {
                        Log.e(TAG, "onPrepared start failed", e);
                    }
                }
            });
            player.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                @Override
                public void onCompletion(MediaPlayer mp) {
                    // 自动下一首
                    next();
                }
            });
            player.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                @Override
                public boolean onError(MediaPlayer mp, int what, int extra) {
                    Log.e(TAG, "MediaPlayer error: " + what + ", " + extra);
                    isPrepared = false;
                    DownloadDiag.log("播放失败: " + currentBean.getTitle() + " "
                            + DownloadDiag.mpError(what, extra)
                            + " url=" + DownloadDiag.safeUrl(currentBean.getStreamUrl())
                            + " network=" + currentBean.isNetwork());

                    // 联网播放失败:下载到本地再播,下载期间静默等待,不自动跳下一首。
                    // MediaPlayer 的网络栈不走 TlsCompat,HTTPS 站点在安卓 4.2.2 上常因
                    // TLS 过旧握手失败;下载走 HttpURLConnection + TlsCompat(已验证可用),
                    // 下载完用本地文件播放则完全绕开 MediaPlayer 的网络能力。
                    // 2026-10-03 用户决策(下载期间静默等待):旧逻辑在已有下载在途时直接
                    // 1 秒跳下一首,造成"每秒一首失败"的级联刷屏、永远等不到缓存完成;
                    // 现在统一排队下载(单线程队列 + autoCacheSong 的 已缓存/在下载中 判重,
                    // 不会重复下载同一文件),完成后自动本地重播,只有下载真正失败才跳下一首。
                    if (token == playToken
                            && currentBean.isNetwork()
                            && currentBean.getStreamUrl() != null) {
                        // 代理正在缓存这首歌:别再排队 downloadThenPlay(会因 IN_FLIGHT
                        // 立刻"放弃"并跳歌 —— 2026-10-03 日志实锤的跳歌刷屏来源),
                        // 改为登记重播意图,等代理缓存完成后由 onCached 自动本地重播。
                        if (currentBean.getStreamId() != null && LocalStreamProxy.get()
                                .isDownloading(currentBean.getStreamId())) {
                            waitingProxyReplaySid = currentBean.getStreamId();
                            DownloadDiag.log("播放失败: 代理正在缓存 "
                                    + currentBean.getTitle() + ",等待缓存完成后重播");
                            return true;
                        }
                        downloadThenPlay(currentBean, token);
                        return true;
                    }

                    // 本地歌播放失败:自动跳下一首兜底(避免卡住)
                    if (token == playToken) {
                        mainHandler.postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                if (token == playToken) {
                                    next();
                                }
                            }
                        }, 1000);
                    }
                    return true;
                }
            });
            player.prepareAsync();
            // 云端歌曲:按设置异步下载到本地(自动缓存),不影响当前播放
            // (走代理时 autoCacheSong 会因 IN_FLIGHT 被代理占用而快速跳过,不双写)
            long tC = System.currentTimeMillis();
            maybeAutoCache(bean);
            long tAuto = System.currentTimeMillis() - tC;
            // 预缓存后三首:走后台队列 + 后台限速档(100KB/s),与当前歌的优先队列
            // 分离,永远不会挡住当前歌的缓存/重播(资源倾斜于当前播放)。
            // 代理路径等 onCached 回调后再排队,带宽完全让给正在播的歌。
            // 代理路径同时启动进度心跳:边下边播没有 autoCacheSong 的进度回调,
            // 由主线程 ~500ms 轮询代理进度并广播(ACTION_CACHE_PROGRESS),
            // 驱动播放进度条上的淡蓝缓冲段。
            if (viaProxy) {
                startProxyProgressPoll(bean.getStreamId(), token);
            } else {
                preCacheUpcoming(3);
            }
            // 主线程点击路径耗时汇总:定位"点击未下载歌曲卡一下"这类问题的直接证据
            CacheDebugLog.log("点击播放主线程耗时: " + bean.getTitle()
                    + " promote=" + tPromote + "ms"
                    + " reset=" + tReset + "ms"
                    + " auth=" + tAuth + "ms"
                    + " setDataSource=" + tSetDs + "ms"
                    + " maybeAutoCache=" + tAuto + "ms"
                    + " 合计=" + (System.currentTimeMillis() - tStart) + "ms"
                    + " network=" + bean.isNetwork());
        } catch (Exception e) {
            Log.e(TAG, "prepareAndPlay failed", e);
            isPrepared = false;
            // setDataSource 抛异常(而不是回调 onError)同样可能是联网播放失败,
            // 例如 HTTPS 握手直接抛 SSLException,所以这里也要兜底。
            DownloadDiag.logError("prepareAndPlay 异常: " + bean.getTitle()
                    + " url=" + DownloadDiag.safeUrl(bean.getStreamUrl())
                    + " network=" + bean.isNetwork(), e);
            if (bean.isNetwork() && bean.getStreamUrl() != null) {
                // 与 onError 路径一致:代理正在缓存就等它完成重播,别排队互踩
                if (bean.getStreamId() != null
                        && LocalStreamProxy.get().isDownloading(bean.getStreamId())) {
                    waitingProxyReplaySid = bean.getStreamId();
                    DownloadDiag.log("prepareAndPlay 异常: 代理正在缓存 "
                            + bean.getTitle() + ",等待缓存完成后重播");
                    return;
                }
                // 与 onError 路径一致:下载期间静默等待,不再自动跳下一首
                downloadThenPlay(bean, token);
                return;
            }
            // 异常时也尝试跳下一首
            mainHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (token == playToken) {
                        next();
                    }
                }
            }, 1000);
        }
    }

    /**
     * 联网播放失败时的兜底:把这首歌下载到本地,再用本地文件重新播放。
     *
     * 为什么需要:MediaPlayer.setDataSource(url) 走的是**媒体播放器自己的网络栈**,
     * 不经过 TlsCompat —— 安卓 4.2.2 只开 SSLv3/TLSv1.0,遇到要求 TLS 1.2 的 HTTPS
     * 站点(飞牛中继等)会直接握手失败,表现为"列表能刷出来、点击却播不了"。
     * 而下载走 HttpURLConnection + TlsCompat(登录、取列表都靠它,已验证可用),
     * 下载完用本地文件播放则完全不依赖 MediaPlayer 的网络能力。
     *
     * 下载期间静默等待(2026-10-03 用户决策):联网播放失败后不再自动跳下一首,
     * 排队下载、完成即自动本地重播;仅下载真正失败才跳下一首(postNextIfCurrent)。
     * 同一首的并发下载由 MusicSyncManager.IN_FLIGHT 与"已缓存"检查挡住,不会抢写同一文件。
     */
    private void downloadThenPlay(final MusicBean bean, final int token) {
        // 当前播放的歌:走优先队列立即执行(资源倾斜于当前播放)
        priorityCacheExecutor.submit(new Runnable() {
            @Override
            public void run() {
                // 降为次低优先级(2026-10-04):车机 2 核,下载线程满载时主线程
                // inflate 被饿 10~18s(见 download_debug.log 卡顿堆栈)。
                try {
                    android.os.Process.setThreadPriority(
                            android.os.Process.THREAD_PRIORITY_LESS_FAVORABLE);
                } catch (Throwable ignored) {
                }
                try {
                    MusicSourceApi api = MusicDataHolder.getInstance().getMusicSourceApi();
                    String syncPath = navidromeConfig != null
                            ? navidromeConfig.getCloudDir() : null;
                    long maxBytes = 0L;
                    if (navidromeConfig != null && navidromeConfig.getAutoCacheMaxMb() > 0) {
                        maxBytes = (long) navidromeConfig.getAutoCacheMaxMb() * 1024L * 1024L;
                    }
                    boolean ok = MusicSyncManager.autoCacheSong(
                            getApplicationContext(), api, bean, syncPath, maxBytes, null,
                            MusicSyncManager.currentSongThrottleBps());
                    DownloadDiag.log("下载后重播: autoCacheSong=" + ok + " " + bean.getTitle());
                    if (!ok) {
                        // 双队列后,预缓存(后台队列)可能正巧在下载同一首:IN_FLIGHT 命中
                        // 导致跳过。等它下完(2 秒后重试):它完成后要么文件已缓存直接
                        // 重播,要么互斥释放后由本任务自己下载 —— 不再因抢跑而跳歌。
                        if (MusicSyncManager.isCacheInFlight(bean.getStreamId())) {
                            DownloadDiag.log("下载后重播: 另一队列正在下载,2 秒后重试 "
                                    + bean.getTitle());
                            retryDownloadThenPlay(bean, token);
                        } else {
                            DownloadDiag.log("下载后重播放弃: 下载未成功 " + bean.getTitle());
                            postNextIfCurrent(token);
                        }
                        return;
                    }
                    java.io.File f = MusicSyncManager.buildLocalFile(bean, syncPath);
                    if (f == null || !f.exists() || f.length() <= 1024) {
                        DownloadDiag.log("下载后重播放弃: 本地文件异常 "
                                + (f == null ? "(null)" : f.getAbsolutePath())
                                + " len=" + (f == null ? -1 : (f.exists() ? f.length() : -1)));
                        postNextIfCurrent(token);
                        return;
                    }
                    // 原地转为本地歌(bean 与界面列表共享同一对象,改了即生效)
                    bean.setNetwork(false);
                    bean.setData(f.getAbsolutePath());
                    bean.setUri(null);
                    Intent i = new Intent(ACTION_CACHE_AVAILABILITY_CHANGED);
                    i.putExtra("streamId", bean.getStreamId());
                    sendBroadcast(i);
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            if (token != playToken) {
                                return;   // 已经切歌,放弃这次重播
                            }
                            DownloadDiag.log("下载后重播: 用本地文件重播 " + bean.getTitle());
                            prepareAndPlay();
                        }
                    });
                } catch (Throwable e) {
                    // 接 Throwable(不只是 Exception):NoSuchMethodError 这类 Error 曾让
                    // 下载任务静默死亡、下一条任务照常跑,日志里毫无痕迹(2026-10-03 根因)
                    DownloadDiag.logError("下载后重播异常: " + bean.getTitle(), e);
                    postNextIfCurrent(token);
                }
            }
        });
    }

    /** 仍是当前歌曲才跳下一首(避免切歌后被旧回调带偏) */
    private void postNextIfCurrent(final int token) {
        mainHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (token == playToken) {
                    next();
                }
            }
        }, 300);
    }

    /**
     * 下载后重播的延迟重试:另一队列(预缓存)正在下载同一首时,不抢互斥、
     * 不跳歌,2 秒后再走一次 downloadThenPlay(届时要么已缓存直接重播,
     * 要么互斥已释放由本任务下载)。仍校验 token,切歌即作废。
     */
    private void retryDownloadThenPlay(final MusicBean bean, final int token) {
        mainHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (token == playToken) {
                    downloadThenPlay(bean, token);
                }
            }
        }, 2000);
    }

    /** 在当前播放列表里按 streamId 找 bean(代理缓存回调的登记用) */
    private MusicBean findBeanByStreamId(String sid) {
        if (sid == null) {
            return null;
        }
        for (MusicBean b : playList) {
            if (b != null && sid.equals(b.getStreamId())) {
                return b;
            }
        }
        return null;
    }

    /**
     * 预缓存播放列表中当前歌曲之后的 count 首(只有网络歌占名额,本地歌直接跳过)。
     *
     * 走 cacheExecutor 后台队列 + 后台限速档(100KB/s):autoCacheSong 内部有
     * 已缓存/IN_FLIGHT 判重,不会重复下载、不会双写同一文件;与当前歌的
     * 优先队列(priorityCacheExecutor)分离,永不挡住当前播放的需求。
     * 触发时机:
     * - 非代理路径:prepareAndPlay 末尾触发(当前歌的缓存任务已在优先队列立即执行);
     * - 代理路径:当前歌 onCached 回调触发 —— 边下边播占着带宽,播完才开始预缓存。
     */
    private void preCacheUpcoming(int count) {
        if (navidromeConfig == null || !navidromeConfig.isAutoCacheOnPlay()) {
            return;
        }
        // 【让路,2026-10-04 v5.7.379】当前歌正走流代理边下边播时不再排预缓存:
        // 日志(09:34-09:41 段)实锤两条下载链路 + 滚动 810 首大列表并发时,
        // 2 核 CPU 与慢速 SD 卡被打满,主线程连环卡 5~20 秒。378 的 8 秒冷却只是
        // 把碰撞推迟了 8 秒,治本是把"同一时刻的下载链路"收敛到一条 ——
        // 流代理歌播完/切走后,下一次触发(切歌/重播路径末尾)自然会恢复预缓存。
        if (currentViaProxy) {
            DownloadDiag.log("预缓存: 跳过(当前歌边下边播中,让路)");
            return;
        }
        final String syncPath = navidromeConfig.getCloudDir();
        if (syncPath == null || syncPath.isEmpty()) {
            return;
        }
        final MusicSourceApi api = MusicDataHolder.getInstance().getMusicSourceApi();
        if (api == null) {
            return;
        }
        final long maxBytes = navidromeConfig.getAutoCacheMaxMb() > 0
                ? (long) navidromeConfig.getAutoCacheMaxMb() * 1024L * 1024L : 0L;
        int queued = 0;
        for (int i = currentIndex + 1; i < playList.size() && queued < count; i++) {
            final MusicBean b = playList.get(i);
            if (b == null || !b.isNetwork() || b.getStreamUrl() == null
                    || b.getStreamId() == null || b.getStreamId().isEmpty()) {
                continue;   // 本地歌/信息不全:不占预缓存名额
            }
            final String title = b.getTitle();
            queued++;
            cacheExecutor.submit(new Runnable() {
                @Override
                public void run() {
                    // 降为后台优先级(2026-10-04):批量预缓存纯后台任务,
                    // 网络受限速 100KB/s,CPU/IO 不该与主线程抢占
                    try {
                        android.os.Process.setThreadPriority(
                                android.os.Process.THREAD_PRIORITY_BACKGROUND);
                    } catch (Throwable ignored) {
                    }
                    // 【2026-10-04 v5.7.378 冷却窗口】车机日志(09:14 段)显示:排队
                    // 3 首预缓存紧跟 onResume/切歌,下载写盘+TLS 解密与主线程的
                    // 列表滚动/封面加载争抢 CPU 与慢速 SD 卡 IO,watchdog 记录到
                    // 连环 10~18 秒卡顿。开头睡 8 秒:切歌/回界面后的敏感窗口
                    // 先让给用户,反正预缓存不赶时间(串行+限速本身就是慢任务)。
                    try {
                        Thread.sleep(8000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    try {
                        boolean ok = MusicSyncManager.autoCacheSong(
                                MusicService.this, api, b, syncPath, maxBytes, null);
                        DownloadDiag.log("预缓存: " + (ok ? "成功 " : "跳过/失败 ") + title);
                    } catch (Throwable t) {
                        // 接 Throwable:getContentLengthLong 一类的 Error 曾让任务静默死亡
                        DownloadDiag.logError("预缓存异常: " + title, t);
                    }
                }
            });
        }
        if (queued > 0) {
            DownloadDiag.log("预缓存: 已排队 " + queued + " 首(当前位置 "
                    + currentIndex + ",列表 " + playList.size() + " 首)");
        }
    }

    /**
     * 代理边下边播期间的进度心跳(主线程 ~500ms 轮询):查询 LocalStreamProxy 的
     * 缓存进度并发 ACTION_CACHE_PROGRESS 广播,驱动播放栏缓存条与列表行进度条。
     * 退出条件:切歌(token 变化)、任务结束(完成=100 / 失败或无任务=-100)。
     * total 未知时 progress 返回 -1,与 autoCacheSong 的心跳语义一致(界面隐藏)。
     */
    private void startProxyProgressPoll(final String sid, final int token) {
        final Runnable[] holder = new Runnable[1];
        holder[0] = new Runnable() {
            @Override
            public void run() {
                if (token != playToken) {
                    return;   // 已切歌:过期轮询自动作废
                }
                int pct = LocalStreamProxy.get().progress(sid);
                if (pct == -100) {
                    return;   // 任务失败或已被清理:停止(失败路径由 onFailed 广播收尾)
                }
                Intent pi = new Intent(ACTION_CACHE_PROGRESS);
                pi.putExtra("streamId", sid);
                pi.putExtra("percent", pct);
                sendBroadcast(pi);
                if (pct < 100) {
                    mainHandler.postDelayed(this, 500);
                }
                // pct>=100:发一次完成进度后停止,后续由 onCached 的可用性广播收尾
            }
        };
        mainHandler.postDelayed(holder[0], 400);
    }

    /**
     * 播放前的缓存优先检查:若云端歌曲的本地缓存文件已存在(与同步/自动缓存同一固定路径),
     * 则原地转为本地播放并广播来源变化。列表状态可能滞后(刚下载完成尚未重建列表),
     * 所以在播放瞬间用一次文件 stat 兜底,保证"播放优先使用缓存"。
     *
     * @return 传入的 bean(可能已被改为本地可用)
     */
    private MusicBean promoteToLocalIfCached(MusicBean bean) {
        if (bean == null || !bean.isNetwork()
                || bean.getStreamId() == null || bean.getStreamId().isEmpty()) {
            return bean;
        }
        try {
            if (navidromeConfig == null) {
                // 兜底:正常路径 onCreate 已赋值
                navidromeConfig = new NavidromeConfig(this);
            }
            String cloudDir = navidromeConfig.getCloudDir();
            if (cloudDir == null || cloudDir.isEmpty()) {
                return bean;
            }
            java.io.File localFile = MusicSyncManager.buildLocalFile(bean, cloudDir);
            if (localFile.exists() && localFile.length() > 1024) {
                CacheDebugLog.log("promoteToLocalIfCached: 已缓存,本地播放 " + bean.getTitle());
                bean.setNetwork(false);
                bean.setData(localFile.getAbsolutePath());
                bean.setUri(null);
                // 通知界面刷新来源标识(云端→本地)
                Intent i = new Intent(ACTION_CACHE_AVAILABILITY_CHANGED);
                i.putExtra("streamId", bean.getStreamId());
                sendBroadcast(i);
            }
        } catch (Exception e) {
            Log.w(TAG, "promoteToLocalIfCached failed", e);
        }
        return bean;
    }

    /**
     * 播放云端歌曲时,按设置异步下载到本地(自动缓存)。
     * 下载到与手动同步相同的固定路径,完成后即时把当前 bean 标记为本地可用,
     * 并广播通知界面刷新来源标识。任何不满足前置条件(未开启/缺信息)
     * 的情况都直接返回,绝不影响正在进行的播放。
     */
    private void maybeAutoCache(final MusicBean bean) {
        if (bean == null) {
            return;
        }
        if (navidromeConfig != null) {
            // 诊断日志统一目录(DownloadDiag.resolveLogDir):与 download_debug.log / crash_log.txt 同目录
            CacheDebugLog.init(this, DownloadDiag.resolveLogDir(this));
        }
        // 诊断日志:无论走不走缓存,都记录决策依据(定位"看不到进度条"类问题)
        CacheDebugLog.log("maybeAutoCache: " + bean.getTitle()
                + " network=" + bean.isNetwork()
                + " autoCacheOnPlay=" + (navidromeConfig != null && navidromeConfig.isAutoCacheOnPlay())
                + " streamId=" + bean.getStreamId()
                + " streamUrl=" + (bean.getStreamUrl() != null ? "有" : "无"));
        if (!bean.isNetwork() || bean.getStreamUrl() == null) {
            DownloadDiag.log("跳过缓存: 已是本地歌或流地址为空 network=" + bean.isNetwork()
                    + " streamUrl=" + (bean.getStreamUrl() != null));
            return;
        }
        if (navidromeConfig == null) {
            // 兜底:正常路径 onCreate 已赋值;这里防止异常路径再次导致功能静默失效
            navidromeConfig = new NavidromeConfig(this);
        }
        if (!navidromeConfig.isAutoCacheOnPlay()) {
            DownloadDiag.log("跳过缓存: 设置里「播放时自动缓存」为关(请在设置中勾选)");
            return;
        }
        final MusicSourceApi api = MusicDataHolder.getInstance().getMusicSourceApi();
        if (api == null) {
            DownloadDiag.log("跳过缓存: 数据源 api 为空");
            return;
        }
        if (bean.getStreamId() == null || bean.getStreamId().isEmpty()) {
            DownloadDiag.log("跳过缓存: streamId 为空");
            return;
        }
        final String syncPath = navidromeConfig.getCloudDir();
        if (syncPath == null || syncPath.isEmpty()) {
            DownloadDiag.log("跳过缓存: 云端目录为空");
            return;
        }
        final long maxBytes = navidromeConfig.getAutoCacheMaxMb() > 0
                ? (long) navidromeConfig.getAutoCacheMaxMb() * 1024L * 1024L : 0L;
        // 当前播放的歌:走优先队列 + 优先限速档(资源倾斜于当前播放);
        // 预缓存走 cacheExecutor 后台队列 + 后台档,互不排队
        priorityCacheExecutor.submit(new Runnable() {
            @Override
            public void run() {
                // 降为次低优先级(2026-10-04):不与主线程抢 CPU,理由同上
                try {
                    android.os.Process.setThreadPriority(
                            android.os.Process.THREAD_PRIORITY_LESS_FAVORABLE);
                } catch (Throwable ignored) {
                }
                // 进度节流(三重),避免"每个百分点广播一次"把主线程刷爆:
                // 整首下载原本会发上百次广播,每次都要写一行诊断日志 + 重绑一行列表,
                // 在车机上表现为播放过程中持续卡顿。
                //   1) 完成(100%)必发
                //   2) 总长未知:按 500ms 心跳
                //   3) 总长已知:最快 250ms 一次,且进度至少跳 2%(4dp 进度条看不出更细的差别)
                final long[] lastTime = {0L};
                final int[] lastPct = {-100};
                final boolean[] firstLogged = {false};
                MusicSourceApi.DownloadProgressListener listener =
                        new MusicSourceApi.DownloadProgressListener() {
                    @Override
                    public void onProgress(long bytes, long contentLength) {
                        final int pct = contentLength > 0
                                ? (int) (bytes * 100 / contentLength) : -1;
                        final long now = System.currentTimeMillis();
                        final boolean finished = pct >= 100;
                        if (!finished) {
                            if (pct < 0) {
                                // 总长未知:只能按时间心跳
                                if (now - lastTime[0] < 500) {
                                    return;
                                }
                            } else {
                                if (pct == lastPct[0]) {
                                    return;
                                }
                                if (now - lastTime[0] < 250) {
                                    return;
                                }
                                if (lastPct[0] >= 0 && pct - lastPct[0] < 2) {
                                    return;
                                }
                            }
                        }
                        if (!firstLogged[0]) {
                            firstLogged[0] = true;
                            CacheDebugLog.log("下载开始: " + bean.getTitle()
                                    + " 首个进度事件 bytes=" + bytes
                                    + " contentLength=" + contentLength + " pct=" + pct);
                        }
                        lastPct[0] = pct;
                        lastTime[0] = now;
                        Intent pi = new Intent(ACTION_CACHE_PROGRESS);
                        pi.putExtra("streamId", bean.getStreamId());
                        pi.putExtra("percent", pct);
                        sendBroadcast(pi);
                    }
                };
                try {
                    boolean ok = MusicSyncManager.autoCacheSong(
                            getApplicationContext(), api, bean, syncPath, maxBytes, listener,
                            MusicSyncManager.currentSongThrottleBps());
                    DownloadDiag.log("autoCacheSong result=" + ok + ": " + bean.getTitle());
                    if (ok) {
                        // 即时把当前播放条目标记为本地可用(与手动同步后行为一致)。
                        // bean 是 service.playList 与界面列表共享的同一对象,原地修改即生效。
                        java.io.File localFile =
                                MusicSyncManager.buildLocalFile(bean, syncPath);
                        bean.setNetwork(false);
                        bean.setData(localFile.getAbsolutePath());
                        bean.setUri(null);
                        // 通知界面刷新来源标识(云端→本地)
                        Intent i = new Intent(ACTION_CACHE_AVAILABILITY_CHANGED);
                        i.putExtra("streamId", bean.getStreamId());
                        sendBroadcast(i);
                    }
                } catch (Throwable e) {
                    // 接 Throwable(不只是 Exception)并写入 download_debug.log:
                    // 之前只 Log.w 到 logcat —— NoSuchMethodError(getContentLengthLong,
                    // API 19+)这类 Error 曾让下载任务无声死亡,车机日志里毫无痕迹
                    Log.w(TAG, "auto cache failed: " + bean.getTitle(), e);
                    DownloadDiag.logError("自动缓存异常: " + bean.getTitle(), e);
                } finally {
                    // 无论成功失败都通知界面结束该条的进度条显示(percent=-2)
                    Intent pi = new Intent(ACTION_CACHE_PROGRESS);
                    pi.putExtra("streamId", bean.getStreamId());
                    pi.putExtra("percent", -2);
                    sendBroadcast(pi);
                }
            }
        });
    }

    private void resetPlayer() {
        stopPosTracking();
        if (player == null) {
            player = new MediaPlayer();
        } else {
            try {
                player.reset();
            } catch (Exception e) {
                player = new MediaPlayer();
            }
        }
        isPrepared = false;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.getAction() != null) {
            String action = intent.getAction();
            if (Intent.ACTION_MEDIA_BUTTON.equals(action)) {
                handleMediaButton(intent);
            } else if (CMD_PLAY.equals(action)) {
                playIndex(currentIndex < 0 ? 0 : currentIndex);
            } else if (CMD_PAUSE.equals(action)) {
                pause();
            } else if (CMD_NEXT.equals(action)) {
                next();
            } else if (CMD_PREV.equals(action)) {
                prev();
            } else if (CMD_TOGGLE.equals(action)) {
                toggle();
            } else if (CMD_STOP.equals(action)) {
                stopSelfSafely();
            } else if (CMD_PLAY_INDEX.equals(action)) {
                int idx = intent.getIntExtra("index", 0);
                playIndex(idx);
            }
        }
        // 确保前台运行,避免被回收
        startForeground(NOTIF_ID, buildNotification());
        return START_STICKY;
    }

    /** 处理方向盘/耳机媒体按键 */
    private void handleMediaButton(Intent intent) {
        KeyEvent event = intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT);
        if (event == null || event.getAction() != KeyEvent.ACTION_UP) {
            return;
        }
        switch (event.getKeyCode()) {
            case KeyEvent.KEYCODE_MEDIA_PLAY:
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                toggle();
                break;
            case KeyEvent.KEYCODE_MEDIA_NEXT:
                next();
                break;
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
                prev();
                break;
            case KeyEvent.KEYCODE_HEADSETHOOK:
                toggle();
                break;
            default:
                break;
        }
    }

    /** 状态变化广播 */
    private void notifyState() {
        // 保存当前播放索引,下次启动可恢复
        try {
            NavidromeConfig config = new NavidromeConfig(this);
            config.setLastPlayIndex(currentIndex);
        } catch (Exception ignored) {
        }
        Intent i = new Intent(ACTION_STATE_CHANGED);
        i.putExtra("index", currentIndex);
        i.putExtra("playing", isPlaying());
        i.putExtra("playMode", playMode.getValue());
        i.putExtra("hasLrc", currentLrc != null && !currentLrc.isEmpty());
        // 附带当前生效的EQ模式名(供主界面EQ按钮显示)
        if (equalizerManager != null) {
            i.putExtra("eqPreset", equalizerManager.getActivePreset());
        }
        sendBroadcast(i);
    }

    /** 进度广播(由 Activity 轮询更简单,这里保留接口) */
    public void broadcastProgress() {
        Intent i = new Intent(ACTION_PROGRESS);
        i.putExtra("position", getCurrentPosition());
        i.putExtra("duration", getDuration());
        sendBroadcast(i);
    }

    // ---------- 通知栏 ----------

    private void updateNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.notify(NOTIF_ID, buildNotification());
        }
    }

    private Notification buildNotification() {
        ensureChannel();
        MusicBean bean = getCurrentMusic();
        String title = bean != null ? bean.getTitle() : "音乐播放器";
        String text = bean != null ? bean.getArtist() : "";
        boolean playing = isPlaying();

        Intent contentIntent = new Intent(this, MainActivity.class);
        contentIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent contentPi = PendingIntent.getActivity(this, 0, contentIntent,
                PendingIntent.FLAG_UPDATE_CURRENT);

        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_music_note)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(contentPi)
                .setOngoing(playing)
                .setShowWhen(false)
                .setPriority(NotificationCompat.PRIORITY_HIGH);

        // 上一首(icon 传 0,低版本仅显示文字,避免依赖额外图标资源)
        b.addAction(0, "上一首", buildCommandPi(CMD_PREV));
        // 播放/暂停
        if (playing) {
            b.addAction(0, "暂停", buildCommandPi(CMD_PAUSE));
        } else {
            b.addAction(0, "播放", buildCommandPi(CMD_PLAY));
        }
        // 下一首
        b.addAction(0, "下一首", buildCommandPi(CMD_NEXT));

        return b.build();
    }

    private PendingIntent buildCommandPi(String cmd) {
        Intent i = new Intent(this, MusicService.class);
        i.setAction(cmd);
        return PendingIntent.getService(this, cmd.hashCode(), i, PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private void ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null && nm.getNotificationChannel(CHANNEL_ID) == null) {
                NotificationChannel ch = new NotificationChannel(CHANNEL_ID,
                        "音乐播放", NotificationManager.IMPORTANCE_LOW);
                ch.setDescription("音乐后台播放控制");
                nm.createNotificationChannel(ch);
            }
        }
    }

    // ---------- RemoteControlClient 更新 ----------

    private void updateRemoteControlPlayState(boolean playing) {
        if (remoteControlClient != null) {
            remoteControlClient.setPlaybackState(playing
                    ? RemoteControlClient.PLAYSTATE_PLAYING
                    : RemoteControlClient.PLAYSTATE_PAUSED);
        }
    }

    private void updateRemoteControlMetadata(MusicBean bean) {
        if (remoteControlClient == null || bean == null) {
            return;
        }
        android.media.RemoteControlClient.MetadataEditor editor =
                remoteControlClient.editMetadata(true);
        editor.putString(android.media.MediaMetadataRetriever.METADATA_KEY_TITLE, bean.getTitle());
        editor.putString(android.media.MediaMetadataRetriever.METADATA_KEY_ARTIST, bean.getArtist());
        editor.putString(android.media.MediaMetadataRetriever.METADATA_KEY_ALBUM, bean.getAlbum());
        editor.putLong(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION, bean.getDuration());
        editor.apply();
    }

    // ---------- 生命周期 ----------

    private void stopSelfSafely() {
        abandonAudioFocus();
        if (player != null) {
            try {
                if (player.isPlaying()) {
                    player.stop();
                }
                player.release();
            } catch (Exception e) {
                Log.w(TAG, "release failed", e);
            }
            player = null;
        }
        isPrepared = false;
        stopForeground(true);
        stopSelf();
    }

    @Override
    public void onDestroy() {
        // 保存播放状态,下次自动播放可恢复
        try {
            NavidromeConfig config = new NavidromeConfig(this);
            config.setLastPlayIndex(currentIndex);
            if (player != null && isPrepared) {
                config.setLastPlayPosition(getCurrentPosition());
            }
        } catch (Exception e) {
            Log.w(TAG, "save play state failed", e);
        }
        try {
            if (remoteControlClient != null && audioManager != null) {
                audioManager.unregisterRemoteControlClient(remoteControlClient);
            }
        } catch (Exception e) {
            Log.w(TAG, "unregister RCC failed", e);
        }
        // 释放音频焦点
        abandonAudioFocus();
        if (player != null) {
            try {
                player.release();
            } catch (Exception e) {
                Log.w(TAG, "release failed", e);
            }
            player = null;
        }
        if (equalizerManager != null) {
            equalizerManager.release();
        }
        // 取消所有未完成的歌词加载任务,避免Service销毁后线程继续运行
        lyricsExecutor.shutdownNow();
        try {
            lyricsExecutor.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
        }
        // 取消未完成的自动缓存下载任务
        if (cacheExecutor != null) {
            cacheExecutor.shutdownNow();
        }
        if (priorityCacheExecutor != null) {
            priorityCacheExecutor.shutdownNow();
        }
        // 反注册媒体按键接收器(补充修复:之前缺少此调用)
        try {
            if (audioManager != null) {
                ComponentName comp = new ComponentName(getPackageName(),
                        MediaButtonReceiver.class.getName());
                audioManager.unregisterMediaButtonEventReceiver(comp);
            }
        } catch (Exception e) {
            Log.w(TAG, "unregisterMediaButton failed", e);
        }
        super.onDestroy();
    }
}
