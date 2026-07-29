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

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

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

    private RemoteControlClient remoteControlClient;
    private AudioManager audioManager;

    // 播放模式
    private PlayMode playMode = PlayMode.SEQUENCE;
    private final Random random = new Random();

    // 均衡器
    private EqualizerManager equalizerManager;

    // 主线程 Handler(用于异步歌词加载后更新 UI)
    private final android.os.Handler mainHandler = new android.os.Handler();

    // 当前歌词(供 UI 查询)
    private List<LrcEntry> currentLrc = new ArrayList<>();

    /** 防止快速切歌导致卡死:记录当前播放请求的唯一标识 */
    private volatile int playToken = 0;
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
        // 初始化均衡器并注册到全局,供 EqualizerActivity 使用
        // 设置 Context 用于持久化,并在启动时静默初始化(允许未播放时调节)
        equalizerManager = new EqualizerManager();
        equalizerManager.setContext(this);
        equalizerManager.initSilent();
        MusicDataHolder.getInstance().setEqualizerManager(equalizerManager);
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
                return player.getCurrentPosition();
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
        if (player != null && isPrepared) {
            try {
                player.seekTo(msec);
            } catch (Exception e) {
                Log.w(TAG, "seekTo failed", e);
            }
        }
    }

    /** 播放指定索引 */
    public void playIndex(int index) {
        if (playList.isEmpty() || index < 0 || index >= playList.size()) {
            return;
        }
        currentIndex = index;
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
            updateRemoteControlPlayState(true);
            notifyState();
            updateNotification();
        }
    }

    public void pause() {
        if (player != null && isPrepared && player.isPlaying()) {
            player.pause();
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

        new Thread(new Runnable() {
            @Override
            public void run() {
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

                // 3. 本地歌词都没有,尝试从 Navidrome 按歌手+歌名获取歌词
                if (lyrics == null || lyrics.isEmpty()) {
                    NavidromeApi api = MusicDataHolder.getInstance().getNavidromeApi();
                    if (api != null && MusicDataHolder.getInstance().isNavidromeEnabled()) {
                        try {
                            String plainText = api.getLyrics(bean.getArtist(), bean.getTitle());
                            if (plainText != null && !plainText.isEmpty()) {
                                if (plainText.contains("[") && plainText.contains(":") && plainText.contains("]")) {
                                    lyrics = LrcParser.parseLrcText(plainText);
                                } else {
                                    lyrics = LrcParser.parsePlainTextLyrics(plainText, 5000);
                                }
                                Log.d(TAG, "本地歌曲从Navidrome获取歌词: " + lyrics.size() + " 行");
                            }
                        } catch (Exception e) {
                            Log.w(TAG, "从Navidrome获取本地歌曲歌词失败", e);
                        }
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
        }).start();
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

    /** 异步加载网络歌曲歌词 */
    private void loadNetworkLyrics(final MusicBean bean) {
        final String songId = bean.getStreamId();
        if (songId == null || songId.isEmpty()) {
            return;
        }

        new Thread(new Runnable() {
            @Override
            public void run() {
                List<LrcEntry> lyrics = null;
                NavidromeApi api = MusicDataHolder.getInstance().getNavidromeApi();

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
        }).start();
    }

    /** 准备并播放当前曲目(增加防抖,避免快速切歌卡死) */
    private void prepareAndPlay() {
        MusicBean bean = getCurrentMusic();
        if (bean == null) {
            return;
        }
        // 增加 token:每次切歌都递增,旧请求自动作废
        final int token = ++playToken;
        
        // 先重置 MediaPlayer,取消之前的异步准备
        resetPlayer();
        
        try {
            // 网络歌曲:用 Navidrome stream URL
            // 本地歌曲:优先用 content uri,失败回退文件路径
            if (bean.isNetwork() && bean.getStreamUrl() != null) {
                player.setDataSource(bean.getStreamUrl());
            } else if (bean.getUri() != null && !bean.getUri().isEmpty()) {
                player.setDataSource(this, android.net.Uri.parse(bean.getUri()));
            } else if (bean.getData() != null && !bean.getData().isEmpty()) {
                player.setDataSource(bean.getData());
            } else {
                return;
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
                        mp.start();
                        // 初始化均衡器(绑定当前 audioSession)
                        try {
                            int sessionId = mp.getAudioSessionId();
                            equalizerManager.init(sessionId);
                        } catch (Exception e) {
                            Log.w(TAG, "equalizer init failed", e);
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
                    // 出错时自动跳下一首(避免卡住)
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
        } catch (Exception e) {
            Log.e(TAG, "prepareAndPlay failed", e);
            isPrepared = false;
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

    private void resetPlayer() {
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
        Intent i = new Intent(ACTION_STATE_CHANGED);
        i.putExtra("index", currentIndex);
        i.putExtra("playing", isPlaying());
        i.putExtra("playMode", playMode.getValue());
        i.putExtra("hasLrc", currentLrc != null && !currentLrc.isEmpty());
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
        try {
            if (remoteControlClient != null && audioManager != null) {
                audioManager.unregisterRemoteControlClient(remoteControlClient);
            }
        } catch (Exception e) {
            Log.w(TAG, "unregister RCC failed", e);
        }
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
        super.onDestroy();
    }
}
