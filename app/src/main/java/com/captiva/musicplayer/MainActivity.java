package com.captiva.musicplayer;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Build;
import android.os.FileObserver;
import android.os.Handler;
import android.os.Looper;
import android.os.IBinder;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import android.view.Choreographer;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * 主界面
 * - 统一音乐播放(本地同步目录扫描)
 * - 搜索栏实时搜索(系统输入法)
 * - 均衡器/服务器统一到设置入口
 * - 服务器状态实时显示,断线30秒自动重连
 * - 歌词叠加在封面上(封面作为底色背景)
 * - 播放按钮颜色:播放蓝色 / 暂停红色
 * - 水波纹/selector 点击反馈(无振动)
 */
public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";
    private static final int REQ_STORAGE = 100;

    /** TLS 1.2 SSLSocketFactory(安卓 4.2 默认禁用 TLS 1.2,需要手动启用) */
    private SSLSocketFactory tls12Factory;

    /**
     * 创建 HTTPS 连接,安卓 4.2 及以下强制启用 TLS 1.2 + 信任所有证书
     *
     * 两个问题:
     * 1. 安卓 4.x 默认禁用 TLS 1.2(GitHub 要求 TLS 1.2+)
     * 2. 安卓 4.x 根证书太旧,不信任 GitHub 用的现代 CA 证书
     *
     * 解决:手动启用 TLS 1.2 + TrustAllManager 跳过证书验证
     * (下载 APK 不是敏感操作,可接受跳过验证)
     */
    private HttpURLConnection createConnection(String urlStr) throws Exception {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        if (conn instanceof HttpsURLConnection && Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            if (tls12Factory == null) {
                try {
                    // 创建信任所有证书的 TrustManager
                    TrustManager[] trustAllCerts = new TrustManager[]{
                        new X509TrustManager() {
                            @Override
                            public void checkClientTrusted(java.security.cert.X509Certificate[] chain, String authType) {}
                            @Override
                            public void checkServerTrusted(java.security.cert.X509Certificate[] chain, String authType) {}
                            @Override
                            public java.security.cert.X509Certificate[] getAcceptedIssuers() { return null; }
                        }
                    };

                    SSLContext sslContext = SSLContext.getInstance("TLSv1.2");
                    sslContext.init(null, trustAllCerts, new java.security.SecureRandom());
                    tls12Factory = sslContext.getSocketFactory();
                    Log.d(TAG, "TLS 1.2 + TrustAll 已启用");
                } catch (Exception e) {
                    Log.w(TAG, "TLS 1.2 启用失败,使用系统默认: " + e.getMessage());
                }
            }
            if (tls12Factory != null) {
                HttpsURLConnection https = (HttpsURLConnection) conn;
                https.setSSLSocketFactory(tls12Factory);
                // 跳过主机名验证(TrustAll 模式下必须)
                https.setHostnameVerifier(new javax.net.ssl.HostnameVerifier() {
                    @Override
                    public boolean verify(String hostname, javax.net.ssl.SSLSession session) {
                        return true;
                    }
                });
            }
        }
        return conn;
    }

    /** 判断异常是否是 SSL/TLS 协议错误 */
    private boolean isSslError(Throwable e) {
        if (e == null) return false;
        String msg = e.getMessage();
        if (msg == null) msg = "";
        return e instanceof javax.net.ssl.SSLException
                || msg.contains("SSL")
                || msg.contains("TLS")
                || msg.contains("handshake")
                || msg.contains("unsupported protocol")
                || msg.contains("SSLProtocolException");
    }

    // UI - 列表区
    private RecyclerView rvList;
    private TextView tvEmpty, tvCount, tvSyncStatus;
    // UI - 顶栏
    private EditText etSearch;
    private Button btnSettings, btnFavorites, btnEq, btnSourceToggle;
    /** 服务器状态小圆点(只显示颜色,不显示文字:绿=已连接 橙=连接中 红=未连接) */
    private View vServerStatus;
    /** 圆点外层的透明命中区(电阻屏上放大点击范围),点击=手动重测服务器连接 */
    private View flServerStatus;
    /** 小圆点的圆形背景(复用同一实例,只改颜色,避免每次状态变化都新建 Drawable) */
    private android.graphics.drawable.GradientDrawable statusDotDrawable;
    /** 上一次已应用的圆点颜色/描述(避免重复 setColor 触发无谓重绘) */
    private int lastStatusDotColor = 0;
    private String lastStatusDotDesc = null;
    // UI - 控制区
    private TextView tvNowTitle, tvNowArtist, tvCurrentTime, tvTotalTime;
    private SeekBar sbProgress;
    private Button btnPrev, btnPlay, btnNext, btnMode, btnFav;
    // UI - 歌词区(封面做底色)
    private LrcView lrcView;

    // 收藏颜色缓存
    private int colorFavActive, colorFavInactive;

    private MusicAdapter adapter;
    private MusicService service;
    private boolean bound = false;
    /** 标记是否需要自动播放(仅首次加载时触发) */
    private boolean autoPlayPending = false;
    /** 用户点击歌曲时 service 还没绑定好,记录待播放位置,onServiceConnected 后自动播放 */
    private int pendingPlayIndex = -1;
    /**
     * 点击歌曲后"下一帧再启动播放"的回调。
     * 用 Choreographer 而不是 Handler.post:post 只是排到消息队列末尾,不保证高亮那一帧
     * 已经画出来;postFrameCallback 是在下一次 VSYNC 回调,此时高亮帧必然已上屏。
     * 保存引用是为了连点时能取消上一次,避免排队重复触发播放。
     */
    private Choreographer.FrameCallback pendingPlayFrame;

    /** 当前音乐列表(扫描同步目录) */
    private final List<MusicBean> musicList = new ArrayList<>();

    private NavidromeConfig navidromeConfig;
    /** 本地歌曲列表缓存(扫描后保存,下次秒开) */
    private LocalMusicCache localMusicCache;
    /** 本地目录文件监听器(FileObserver):本地模式放新歌/删歌时自动重扫描并刷新列表 */
    private FileObserver localDirObserver;
    /** FileObserver 重扫描防抖间隔:合并大批量拷歌时的连续文件事件,避免反复刷新卡顿 */
    private static final long LOCAL_DIR_RESCAN_DEBOUNCE_MS = 1000;
    /** 收藏管理器 */
    private FavoriteManager favoriteManager;
    /** 歌词偏移管理器(每首歌可手动调整歌词同步偏移) */
    private LyricOffsetManager lyricOffsetManager;
    /** 是否正在只显示收藏(收藏夹模式) */
    private boolean favoritesOnly = false;
    /** 刚发生过"模式切换(收藏↔全部)":下一次过滤完成时强制清池 + 滚回顶部,纠正 LM 位置塌缩 */
    private boolean modeSwitchPending = false;
    /**
     * 云端收藏拉取的代次。
     * 拉取是异步的(几十毫秒到几秒),这期间用户可能已经退出收藏夹甚至又进了一次;
     * 每次发起拉取 / 退出收藏夹都 ++,让在途结果自动作废,
     * 避免旧结果回来后把用户当前的列表状态覆盖回去。
     */
    private int cloudFavGen = 0;
    /** 本地/云端切换:false=云端模式(默认,云端歌单全部,已下载本地播/未下载联网播);true=本地模式(仅已下载的歌) */
    private boolean localOnlyMode = false;
    /** 来源切换是否正在执行(单飞:快速连点只重建最终目标,不并发开多个扫描/构建线程) */
    private boolean sourceSwitchInFlight = false;
    /**
     * 连点防抖窗口(ms)。
     * 一次切换 = 一次全量重建(云端 815 首:读缓存 + 遍历目录 + 组装 + 排序 + 去重 ≈ 600ms CPU,
     * 外加 815 个 MusicBean 副本)。车机实测连点 20 次就是 20 次重建,持续高分配把主线程
     * GC 卡到 345ms(表现为"按钮+持久化=345ms"、遮罩上屏延迟 1520ms),最终崩溃重启。
     * 防抖后:窗口内的连发点击只按**最终目标模式**重建一次;按钮与状态区仍然即时响应。
     */
    private static final long SOURCE_SWITCH_DEBOUNCE_MS = 250L;
    /** 当前列表实际是按哪个模式构建的(用于短路:目标模式没变就不需要重建) */
    private boolean lastAppliedMode = false;
    /** 云端优先启动:本进程是否已经强制过一次(static,Activity 重建不重复触发) */
    private static boolean forceCloudLaunchApplied = false;
    /** 防抖后的真正切换动作(合并连点,按最终目标模式重建一次) */
    private final Runnable pendingSourceSwitch = new Runnable() {
        @Override
        public void run() {
            if (sourceSwitchInFlight) {
                // 上一次重建还在跑:再等一轮,完成后由 finishSourceSwitch 收敛到最终目标
                handler.postDelayed(pendingSourceSwitch, SOURCE_SWITCH_DEBOUNCE_MS);
                return;
            }
            if (localOnlyMode == lastAppliedMode) {
                // 连点后最终目标又切回了当前列表所属模式:一次都不用重建
                CacheDebugLog.log("切换防抖: 目标模式与当前列表一致,跳过重建");
                return;
            }
            sourceSwitchInFlight = true;
            applySourceMode(localOnlyMode);
        }
    };

    /**
     * 列表加载遮罩:本地/云端切换时后台要读歌单 + 排序 + 去重,期间把列表藏掉,
     * 空区里显示「正在加载音乐...」—— 与开机 loadMusic 的观感完全一致。
     * 只在"加载确实要花时间"时才出现 —— 延迟 {@link #LOADING_MASK_DELAY_MS} 再显示,
     * 秒开(本地缓存命中)的场景根本不会闪一下。
     */
    private volatile boolean loadingMaskActive = false;
    /** 遮罩延迟显示的阈值:快于此值的切换不显示遮罩,避免"刚盖上去就撤掉"的闪烁 */
    private static final long LOADING_MASK_DELAY_MS = 150;
    /** 遮罩期间 tvEmpty 显示的文案(hide 时用文案比对判断是否需要兜底恢复空提示) */
    private static final String LOADING_MASK_TEXT = "正在加载音乐...";
    /** 本轮列表重建的开始时间 / 遮罩真正显示的时间(仅用于日志:遮罩是否盖住了加载窗口) */
    private volatile long listLoadStartTs = 0;
    private volatile long listMaskShownTs = 0;
    /** 被 handler.postDelayed 排队的"显示遮罩"任务(撤回用同一个实例) */
    private final Runnable showLoadingMaskTask = new Runnable() {
        @Override
        public void run() {
            if (tvEmpty == null || rvList == null) return;
            loadingMaskActive = true;
            listMaskShownTs = System.currentTimeMillis();
            tvEmpty.setText(LOADING_MASK_TEXT);
            tvEmpty.setVisibility(View.VISIBLE);
            // 藏掉列表与索引条:和开机一样的"干净空区 + 一行加载文案",
            // 而不是半透明蒙层压在旧列表上(旧内容隐约晃动,观感发卡)。
            // INVISIBLE 不参与触摸分发,遮罩期间也不会误点旧条目。
            rvList.setVisibility(View.INVISIBLE);
            if (sideIndexBar != null) sideIndexBar.setVisibility(View.GONE);
        }
    };

    /** 从设置页返回时需重新加载 */
    private boolean needReload = false;

    /** 右侧 A-Z 索引条(被动显示:跟随列表滚动高亮"当前字母") */
    private SideIndexBar sideIndexBar;

    /** 服务器状态监控器 */
    private ServerStatusMonitor statusMonitor;

    /** 自动同步管理器(后台自动下载) */
    private MusicSyncManager syncManager;
    /** 是否正在自动同步 */
    private boolean isAutoSyncing = false;
    /** 待刷新计数器(累积N首后刷新一次列表) */
    private int pendingSyncRefresh = 0;
    private static final int REFRESH_BATCH_SIZE = 5;

    /** 封面加载代次(每次 updateNowPlaying 递增,旧回调自动作废,避免暂停/恢复后封面错乱) */
    private int coverLoadToken = 0;

    // 进度刷新(三档频率:播放200ms / 滑动500ms / 空闲2000ms)
    private final Handler handler = new Handler();
    private final Runnable progressTask = new Runnable() {
        @Override
        public void run() {
            if (!listScrolling) {
                // 非滑动:正常更新进度和歌词
                updateProgress();
                updateLrc();
                boolean playing = service != null && service.isPlaying();
                handler.postDelayed(this, playing ? 200 : 2000);
            } else {
                // 滑动中:不更新内容,降低轮询频率(减少主线程消息队列压力)
                handler.postDelayed(this, 500);
            }
        }
    };

    /** 当前搜索关键词 */
    private String currentSearchQuery = "";

    /** 列表正在滑动(暂停歌词/进度刷新,避免抢主线程导致卡顿) */
    private volatile boolean listScrolling = false;

    /** Choreographer 帧回调(性能监控:仅滑动时启用,减少非滑动时的开销) */
    private final Choreographer.FrameCallback frameCallback = new Choreographer.FrameCallback() {
        @Override
        public void doFrame(long frameTimeNanos) {
            PerfLogger.onFrame(frameTimeNanos);
            // 仅滑动时持续回调,停止滑动后自动停止(减少2核设备的帧回调开销)
            if (listScrolling) {
                Choreographer.getInstance().postFrameCallback(this);
            }
        }
    };

    /** 定时刷新日志到U盘(每10秒) */
    private final Runnable logFlushTask = new Runnable() {
        @Override
        public void run() {
            PerfLogger.dump();
            handler.postDelayed(this, 10000);
        }
    };

    // 自动缓存完成/进度广播接收:刷新来源标识(云端→本地)与未缓存歌曲的进度条
    private final BroadcastReceiver cacheReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (MusicService.ACTION_CACHE_AVAILABILITY_CHANGED.equals(action)) {
                // bean 已由 MusicService 原地翻转为本地可用(与界面列表共享同一对象),
                // 这里只刷新列表视图与高亮,无需重新构建列表。
                String sid = intent.getStringExtra("streamId");
                if (adapter != null) {
                    // 下载完成:清除该行的缓存进度条,再单独刷新这一行的来源标识。
                    // 注意:这里不能用 notifyDataSetChanged() —— 整表重绑在车机上会明显卡顿,
                    // 而实际只有一个格子的状态点(云端→本地)变了。
                    adapter.clearCacheProgress(sid);
                    adapter.refreshRowByStreamId(sid);
                }
                updatePlayingHighlight();
            } else if (MusicService.ACTION_CACHE_PROGRESS.equals(action)) {
                // 未缓存歌曲正在按需下载:更新对应行的进度条
                int percent = intent.getIntExtra("percent", -2);
                CacheDebugLog.log("UI 收到进度广播 streamId=" + intent.getStringExtra("streamId")
                        + " percent=" + percent + " adapter=" + (adapter != null));
                if (adapter != null) {
                    adapter.updateCacheProgress(intent.getStringExtra("streamId"), percent);
                }
            }
        }
    };

    // 播放状态广播接收
    private final BroadcastReceiver stateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (MusicService.ACTION_STATE_CHANGED.equals(intent.getAction())) {
                int index = intent.getIntExtra("index", -1);
                boolean playing = intent.getBooleanExtra("playing", false);
                int modeValue = intent.getIntExtra("playMode", 0);
                PlayMode mode = PlayMode.fromValue(modeValue);

                updateNowPlaying(index);
                updatePlayButton(playing);
                updatePlayModeIcon(mode);
                if (service != null) {
                    lrcView.setLrcList(service.getCurrentLrc());
                }
                // 更新EQ按钮显示(切歌后可能应用了单曲绑定的EQ)
                String eqPreset = intent.getStringExtra("eqPreset");
                updateEqButtonText(eqPreset);
                // 滚动列表到当前播放歌曲(含高亮+确保数据加载)
                scrollToCurrentSong();
            }
        }
    };

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder ibinder) {
            MusicService.MusicBinder b = (MusicService.MusicBinder) ibinder;
            service = b.getService();
            bound = true;
            if (!musicList.isEmpty()) {
                // 恢复上次播放位置
                int lastIndex = navidromeConfig.getLastPlayIndex();
                if (lastIndex < 0 || lastIndex >= musicList.size()) {
                    lastIndex = 0;
                }
                service.setPlayList(musicList, lastIndex);
            }
            int idx = service.getCurrentIndex();
            updateNowPlaying(idx);
            updatePlayButton(service.isPlaying());
            updatePlayModeIcon(service.getPlayMode());
            lrcView.setLrcList(service.getCurrentLrc());
            // 更新EQ按钮显示
            updateEqButtonText(null);
            // 滚动列表到当前播放歌曲
            scrollToCurrentSong();

            // 自动播放:恢复上次歌曲和进度
            if (autoPlayPending && !service.isPlaying() && !musicList.isEmpty()) {
                autoPlayPending = false;
                int lastIndex = navidromeConfig.getLastPlayIndex();
                int lastPos = navidromeConfig.getLastPlayPosition();
                if (lastIndex < 0 || lastIndex >= musicList.size()) {
                    lastIndex = 0;
                }
                service.playIndexWithSeek(lastIndex, lastPos);
            }

            // 处理用户在 service 绑定前点击的歌曲
            if (pendingPlayIndex >= 0 && !musicList.isEmpty()) {
                int playPos = pendingPlayIndex;
                pendingPlayIndex = -1;
                if (playPos >= 0 && playPos < musicList.size()) {
                    List<MusicBean> displayList = adapter.getDisplayList();
                    service.setPlayList(displayList, playPos);
                    service.playIndex(playPos);
                }
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            bound = false;
            service = null;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 车机场景:界面在前台时保持屏幕常亮(用户明确要求),
        // 否则系统息屏超时一到就黑屏,看歌词/封面都得先点一下唤醒。
        // 两层保障:
        // 1) FLAG_KEEP_SCREEN_ON —— 不需要权限,Activity 不可见时系统自动失效,无泄漏;
        // 2) ScreenOnKeeper(前台 SCREEN_BRIGHT_WAKE_LOCK)—— 窗口标志依赖 WindowManager
        //    的 hold-screen 记账,息屏/解锁/弹窗之后偶尔会没被重新认定(表现为"标志还在
        //    却照样黑屏"),直接持锁绕开这层;onResume 申请 / onPause 释放,后台不持锁。
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        // 全屏沉浸模式:隐藏状态栏和虚拟导航键
        hideSystemUI();

        setContentView(R.layout.activity_main);

        navidromeConfig = new NavidromeConfig(this);
        // 一次性迁移:把旧版本遗留的"播放时自动缓存=关"重置为开(云端已改为点击播放按需缓存)
        navidromeConfig.migrateAutoCacheOnPlayIfNeeded();
        // 恢复上次的列表模式(true=本地列表,false=云端列表)
        localOnlyMode = navidromeConfig.isLocalMode();
        // 开 app 固定进云端列表(用户明确选择"始终云端启动")。
        // 背景:上次若停在「本地」而本地目录(根目录/本地歌曲)没歌,一开 app 就是空列表,
        // 容易被误判成"歌单丢了"(车机日志 19:40:05 模式=本地 即此场景)。
        // 只在**本进程首次**进 onCreate 时强制一次 —— Activity 因配置变化重建时不再打断
        // 用户本次会话里手动切到的本地模式;会话内仍可自由切换,只是下次启动回到云端。
        if (!forceCloudLaunchApplied) {
            forceCloudLaunchApplied = true;
            if (localOnlyMode) {
                localOnlyMode = false;
                navidromeConfig.setLocalMode(false);   // 同步落盘,保证下次启动也是云端
                // 这里 CacheDebugLog 还没 init(目录未知),只进 logcat,避免写歪到默认 /Music
                Log.i(TAG, "启动: 上次为本地模式,已按云端优先重置为云端");
            }
        }
        // 启动时的列表就是按此模式构建的(loadMusic),作为连点防抖的短路基准
        lastAppliedMode = localOnlyMode;
        localMusicCache = new LocalMusicCache(this);
        favoriteManager = new FavoriteManager(this);
        lyricOffsetManager = new LyricOffsetManager(this);

        // 初始化数据源(如果已配置):Navidrome 或飞牛音乐
        if (navidromeConfig.isConfigured()) {
            MusicSourceApi api = navidromeConfig.createSource();
            MusicDataHolder.getInstance().setMusicSourceApi(api);
            MusicDataHolder.getInstance().setNavidromeEnabled(navidromeConfig.isEnabled());
        }

        // 初始化服务器状态监控器
        statusMonitor = new ServerStatusMonitor();
        statusMonitor.setCallback(new ServerStatusMonitor.StatusCallback() {
            @Override
            public void onStatusChanged(ServerStatusMonitor.Status status, String message) {
                updateServerStatusDisplay(status, message);
            }
        });

        initViews();
        setupListeners();

        // 延迟启动重量级初始化,让 UI 先渲染(避免启动时白屏/卡顿)
        // 先显示"加载中"提示,等 UI 渲染完再执行扫描等耗时操作
        handler.post(new Runnable() {
            @Override
            public void run() {
                // 启动服务器状态监控
                statusMonitor.start(MusicDataHolder.getInstance().getMusicSourceApi());

                // 启动并绑定服务
                Intent si = new Intent(MainActivity.this, MusicService.class);
                startService(si);
                bindService(si, connection, Context.BIND_AUTO_CREATE);

                // 加载音乐(扫描同步目录)
                if (hasStoragePermission()) {
                    autoPlayPending = navidromeConfig.isAutoPlay();
                    loadMusic();
                } else {
                    ActivityCompat.requestPermissions(MainActivity.this,
                            new String[]{android.Manifest.permission.READ_EXTERNAL_STORAGE}, REQ_STORAGE);
                }
            }
        });
    }

    /**
     * 隐藏系统 UI,进入全屏沉浸模式
     * - 隐藏状态栏
     * - 隐藏虚拟导航键
     * - 兼容 Android 4.2.2(API 17)到新版本
     */
    private void hideSystemUI() {
        View decorView = getWindow().getDecorView();
        // API 19+ 使用沉浸式 sticky 模式
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.KITKAT) {
            decorView.setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
        } else {
            // API 17-18:隐藏状态栏和导航键(非沉浸式)
            decorView.setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LOW_PROFILE
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION);
            // 同时请求全屏窗口
            getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                    WindowManager.LayoutParams.FLAG_FULLSCREEN);
        }
    }

    private void initViews() {
        rvList = findViewById(R.id.rv_list);
        tvEmpty = findViewById(R.id.tv_empty);
        sideIndexBar = findViewById(R.id.side_index_bar);
        tvCount = findViewById(R.id.tv_count);
        tvSyncStatus = findViewById(R.id.tv_sync_status);
        // 顶栏状态文字兼作"手动更新列表"入口:自动同步完成后显示"列表已更新",
        // 点它即可再手动刷新一次(云端=重新拉列表,本地=重扫本地目录)
        tvSyncStatus.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                manualRefreshList();
            }
        });
        etSearch = findViewById(R.id.et_search);
        btnSettings = findViewById(R.id.btn_settings);
        btnFavorites = findViewById(R.id.btn_favorites);
        btnSourceToggle = findViewById(R.id.btn_source_toggle);
        // 按持久化的模式设置按钮外观(云端/本地)
        updateSourceToggleUi();
        btnEq = findViewById(R.id.btn_eq);
        // 服务器状态小圆点:用代码创建的圆形 Drawable 上色
        // (不用 setBackgroundTintList —— 那是 API 21+,本应用最低要跑安卓 4.2.2/API 17)
        flServerStatus = findViewById(R.id.fl_server_status);
        vServerStatus = findViewById(R.id.v_server_status);
        statusDotDrawable = new android.graphics.drawable.GradientDrawable();
        statusDotDrawable.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        statusDotDrawable.setColor(ContextCompat.getColor(this, R.color.server_status_disconnected));
        vServerStatus.setBackground(statusDotDrawable);
        setServerStatusDot(ServerStatusMonitor.Status.OFFLINE, "未连接");
        // 按当前模式决定顶部服务器状态区(圆点 + "列表已更新")的显隐
        updateServerStatusAreaVisibility();
        tvNowTitle = findViewById(R.id.tv_now_title);
        tvNowArtist = findViewById(R.id.tv_now_artist);
        tvCurrentTime = findViewById(R.id.tv_current_time);
        tvTotalTime = findViewById(R.id.tv_total_time);
        sbProgress = findViewById(R.id.sb_progress);

        // 修复安卓4.x进度条圆圈黑块(三重修复):
        // 1. 用Bitmap绘制圆圈thumb,保证ARGB_8888正确透明(无ShapeDrawable黑块)
        // 2. 清除SeekBar默认背景(消除系统残留thumb阴影)
        // 3. 设置thumbOffset=0(消除clip与thumb之间的缝隙)
        float density = getResources().getDisplayMetrics().density;
        int thumbSize = (int) (18 * density);
        android.graphics.Bitmap thumbBmp = android.graphics.Bitmap.createBitmap(
                thumbSize, thumbSize, android.graphics.Bitmap.Config.ARGB_8888);
        android.graphics.Canvas canvas = new android.graphics.Canvas(thumbBmp);
        android.graphics.Paint paint = new android.graphics.Paint();
        paint.setAntiAlias(true);
        // 蓝色描边圆
        paint.setColor(0xFF4FC3F7);
        canvas.drawCircle(thumbSize / 2f, thumbSize / 2f, thumbSize / 2f - 1, paint);
        // 白色内圆
        paint.setColor(0xFFFFFFFF);
        canvas.drawCircle(thumbSize / 2f, thumbSize / 2f, thumbSize / 2f - 1 - 3 * density, paint);
        android.graphics.drawable.BitmapDrawable thumbDrawable =
                new android.graphics.drawable.BitmapDrawable(getResources(), thumbBmp);
        sbProgress.setThumb(thumbDrawable);
        // 清除默认背景(消除系统thumb残留)
        sbProgress.setBackgroundDrawable(null);
        // 消除clip与thumb之间的缝隙
        sbProgress.setThumbOffset(0);

        btnPrev = findViewById(R.id.btn_prev);
        btnPlay = findViewById(R.id.btn_play);
        btnNext = findViewById(R.id.btn_next);
        btnMode = findViewById(R.id.btn_mode);
        btnFav = findViewById(R.id.btn_fav);
        lrcView = findViewById(R.id.lrc_view);

        // 缓存收藏颜色
        colorFavActive = ContextCompat.getColor(this, R.color.favorite_active);
        colorFavInactive = ContextCompat.getColor(this, R.color.favorite_inactive);

        adapter = new MusicAdapter(this);
        adapter.setFavoriteManager(favoriteManager);
        // 本地模式下全部是本地歌曲,来源状态点无信息量 → 隐藏
        adapter.setShowSourceDot(!localOnlyMode);
        // 异步过滤(搜索/收藏/去重)完成后,filteredData 才是最终态,这里刷新计数,
        // 避免 "updateCount() 跑在 filter() 异步返回之前" 导致的统计数错误。
        adapter.setOnFilterCompleteListener(new MusicAdapter.OnFilterCompleteListener() {
            @Override
            public void onFilterComplete() {
                updateCount();
                if (modeSwitchPending) {
                    modeSwitchPending = false;
                    // 模式切换后强制清掉 RecyclerView 的废弃/缓存 ViewHolder 池,并把列表滚回顶部再触发重布局,
                    // 避免 LinearLayoutManager 沿用切换前的陈旧布局状态(表现为"第一首下面是第13首"、
                    // 下滑上滑才正常这类位置塌缩)。这是 320 的 notifyDataSetChanged 补不到的一层。
                    rvList.getRecycledViewPool().clear();
                    rvList.scrollToPosition(0);
                    DownloadDiag.log("[列表] 模式切换: 清池 + 滚顶部");
                }
                rvList.postDelayed(new Runnable() {
                    @Override public void run() { dumpVisibleRows("after-filter"); }
                }, 300);
            }
        });
        adapter.setOnItemClickListener((position, bean) -> {
            if (service != null && bound) {
                // ===== 点击反馈优先:先高亮,下一帧再启动播放 =====
                // 原来高亮要等播放真正开始后才有值可查(updatePlayingHighlight 依赖
                // service.getCurrentMusic()),而在此之前 playIndex 已经在主线程做完了
                // promoteToLocalIfCached(磁盘 stat)、resetPlayer、setDataSource
                // (联网歌曲可能触发 HTTPS 握手)—— 车机上这段几百毫秒,
                // 表现就是"点下去没反应,过一会儿才亮"。
                // 现在:点击瞬间先把高亮挪过去(只重绑两行,极快),播放的重活推到下一帧。
                long tClick = System.currentTimeMillis();
                final List<MusicBean> displayList = adapter.getDisplayList();
                int realPos = adapter.findPositionByBean(bean);
                if (realPos >= 0 && realPos != position) {
                    position = realPos;
                }
                adapter.setPlayingIndex(position);
                final long tHighlight = System.currentTimeMillis() - tClick;
                final int playPos = position;

                // 连点时取消上一次待执行的播放,避免排队重复触发
                if (pendingPlayFrame != null) {
                    Choreographer.getInstance().removeFrameCallback(pendingPlayFrame);
                }
                pendingPlayFrame = new Choreographer.FrameCallback() {
                    @Override
                    public void doFrame(long frameTimeNanos) {
                        pendingPlayFrame = null;
                        long t0 = System.currentTimeMillis();
                        service.setPlayList(displayList, playPos);
                        long tSetList = System.currentTimeMillis() - t0;
                        service.playIndex(playPos);
                        CacheDebugLog.log("点击播放(下一帧): "
                                + (bean != null ? bean.getTitle() : "?")
                                + " 高亮=" + tHighlight + "ms"
                                + " setPlayList=" + tSetList + "ms"
                                + " playIndex=" + (System.currentTimeMillis() - t0 - tSetList) + "ms");
                    }
                };
                Choreographer.getInstance().postFrameCallback(pendingPlayFrame);
            } else {
                // service 还没绑定好:同样先给高亮反馈,再记录待播放位置
                adapter.setPlayingIndex(position);
                pendingPlayIndex = position;
                Toast.makeText(this, "正在初始化播放器,请稍候...", Toast.LENGTH_SHORT).show();
            }
        });
        rvList.setLayoutManager(new LinearLayoutManager(this));
        // 完全禁用 item 动画(车机性能弱,任何动画都卡顿)
        rvList.setItemAnimator(null);
        // 增大缓存池(减少滑动时重新绑定),但不要太大(车机内存有限)
        rvList.setItemViewCacheSize(10);
        // 硬件层加速列表滑动(车机性能弱时减少 CPU 绘制)
        rvList.setHasFixedSize(true);
        rvList.setAdapter(adapter);
        setupIndexBar();
        // 滑动状态监听:拖拽和惯性滑动时开启cacheOnlyMode(只读内部缓存),停止后关闭
        rvList.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(RecyclerView recyclerView, int newState) {
                if (newState == RecyclerView.SCROLL_STATE_DRAGGING
                        || newState == RecyclerView.SCROLL_STATE_SETTLING) {
                    // 滑动中:暂停封面U盘读取 + 暂停歌词渲染 + 暂停进度更新
                listScrolling = true;
                CoverLoader.getInstance().setCacheOnlyMode(true);
                lrcView.setSkipDraw(true);
                PerfLogger.setScrolling(true);
                // 启动帧率监控回调(回调内部仅在 listScrolling 时自续,停止滑动后自动停止)
                if (BuildConfig.DEBUG && PerfLogger.isEnabled()) {
                    Choreographer.getInstance().postFrameCallback(frameCallback);
                }
                } else if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                    // 停止滑动:恢复一切
                    listScrolling = false;
                    CoverLoader.getInstance().setCacheOnlyMode(false);
                    // 恢复歌词渲染(setSkipDraw(false) 会触发一次重绘)
                    lrcView.setSkipDraw(false);
                    PerfLogger.setScrolling(false);
                    // 立即补一次进度和歌词(补偿滑动期间跳过的更新)
                    updateProgress();
                    updateLrc();
                    dumpVisibleRows("scroll-idle");
                    LinearLayoutManager lm = (LinearLayoutManager) rvList.getLayoutManager();
                    if (lm == null) return;
                    int firstVisible = lm.findFirstVisibleItemPosition();
                    int lastVisible = lm.findLastVisibleItemPosition();
                    if (firstVisible < 0 || lastVisible < 0) return;

                    // 刷新可见项封面(直接查找 ImageView,不触发 onBindViewHolder → 消除 46ms 尖峰)
                    int coverSizePx = (int) getResources().getDimension(R.dimen.cover_size_list);
                    adapter.refreshCovers(rvList, firstVisible, lastVisible, coverSizePx);

                    // 预加载上下各 10 个 item 的封面(后台线程,不影响主线程)
                    int preloadRange = 10;
                    int preloadStart = Math.max(0, firstVisible - preloadRange);
                    int preloadEnd = Math.min(adapter.getItemCount() - 1, lastVisible + preloadRange);
                    for (int i = preloadStart; i <= preloadEnd; i++) {
                        MusicBean bean = adapter.getFilteredItem(i);
                        if (bean != null) {
                            CoverLoader.getInstance().preload(bean, coverSizePx);
                        }
                    }

                    // 【性能】不在每次滑动停止都触发「全量封面载入内存」。
                    // preloadAllToMemory 会顺序读取最多 810 张封面(车机封面缓存多在 U 盘上),
                    // 在 2 核车机 + 慢速 U 盘上形成一次磁盘 I/O 爆发,挤占封面 load 执行线程与 CPU,
                    // 表现为「刚停手列表像冻住、封面迟迟不显示」——正是「列表很卡」的元凶之一。
                    // 全量预热已在扫描/同步完成后由 CoverLoader.preloadAllCovers 末尾触发一次,
                    // 此处只补可见区上下各 10 首(上面 for 循环已做),足以覆盖滚动需要。
                    // 若要恢复全量预热,应改为「仅首次进入 IDLE 且内存缓存仍冷」才触发,而非每次停手都跑。
                    // CoverLoader.getInstance().preloadAllToMemory(musicList);
                }
            }

            @Override
            public void onScrolled(RecyclerView recyclerView, int dx, int dy) {
                // 被动索引条:随列表滚动更新"当前字母"高亮(字母不变时内部不会重绘)
                updateCurrentLetterFromScroll();
            }
        });
        tvEmpty.setText("正在扫描本地音乐...");
        tvEmpty.setVisibility(View.VISIBLE);
    }

    // ===== 右侧 A-Z 快速索引条 =====

    /** 索引条初始化:绑定监听 + 数据变化自动刷新 */
    private void setupIndexBar() {
        if (sideIndexBar == null) return;
        // 被动显示模式:索引条只跟随列表滚动显示"当前字母",不再响应拖动(车机电阻屏拖动卡顿)。
        // 仍监听数据变化以重算哪些字母有歌(置灰)。
        adapter.registerAdapterDataObserver(new RecyclerView.AdapterDataObserver() {
            @Override
            public void onChanged() {
                refreshIndexBar();
            }
            // DiffUtil 增量刷新走的是 range 回调(insert/remove/change/move),
            // 不会触发 onChanged();必须在这里也重算索引条,否则 A-Z 字母栏在
            // setData / 搜索 / 收藏过滤后不再刷新(表现为"字母滚动不见了")。
            @Override
            public void onItemRangeChanged(int positionStart, int itemCount) {
                refreshIndexBar();
            }
            @Override
            public void onItemRangeInserted(int positionStart, int itemCount) {
                refreshIndexBar();
            }
            @Override
            public void onItemRangeRemoved(int positionStart, int itemCount) {
                refreshIndexBar();
            }
            @Override
            public void onItemRangeMoved(int fromPosition, int toPosition, int itemCount) {
                refreshIndexBar();
            }
        });
        refreshIndexBar();
    }

    /** 切主线程执行索引条刷新(后台线程如果误触发,不能去动 View) */
    private final Runnable refreshIndexBarTask = new Runnable() {
        @Override
        public void run() {
            refreshIndexBarInternal();
        }
    };

    /**
     * 刷新索引条三件事:
     * 1. 统计哪些字母有歌 —— 没歌的置灰,手指按上去会自动落到最近的字母
     * 2. 记录每个字母在列表里的首个位置 —— 拖动时直接跳,不用每次全表扫描
     * 3. 有搜索关键词或显示列表为空时隐藏;普通 / 收藏夹模式都显示
     *    (收藏夹模式下列表仍按标题 A-Z 排序,字母索引不会错位,故不再隐藏)
     */
    private void refreshIndexBar() {
        if (sideIndexBar == null || adapter == null) return;
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.removeCallbacks(refreshIndexBarTask);
            handler.post(refreshIndexBarTask);
            return;
        }
        refreshIndexBarInternal();
    }

    private void refreshIndexBarInternal() {
        final long t0 = System.currentTimeMillis();
        boolean searchEmpty = currentSearchQuery == null || currentSearchQuery.trim().isEmpty();
        // 收藏夹模式下列表仍按标题 A-Z 排序,字母索引不会错位,因此不再隐藏;
        // 仅在有搜索关键词(子集过滤)或当前显示列表为空时隐藏。
        List<MusicBean> list = adapter.getDisplayList();
        if (!searchEmpty || list == null || list.isEmpty()) {
            sideIndexBar.setVisibility(View.GONE);
            updateIndexBarTouchDelegate();
            logIndexBarCost(t0, 0);
            return;
        }

        boolean[] hasSong = new boolean[SideIndexBar.LETTERS.length];
        for (int i = 0; i < list.size(); i++) {
            MusicBean b = list.get(i);
            if (b == null) continue;
            int idx = indexOfLetter(PinyinUtils.firstLetter(b.getTitle()));
            if (idx < 0) continue;
            hasSong[idx] = true;
        }

        sideIndexBar.setAvailableLetters(hasSong);
        sideIndexBar.setVisibility(View.VISIBLE);
        updateIndexBarTouchDelegate();
        // 被动模式:根据当前顶部可见项高亮"当前字母"
        updateCurrentLetterFromScroll();
        logIndexBarCost(t0, list.size());
    }

    /**
     * 索引条刷新是「每次列表数据变化」的必经路径(800 首就要逐首取拼音首字母),
     * 超过 30ms 才记一笔:既能看到它是不是卡顿源,又不会刷屏。
     */
    private void logIndexBarCost(long t0, int count) {
        long cost = System.currentTimeMillis() - t0;
        if (cost >= 30) {
            CacheDebugLog.log("索引条刷新耗时=" + cost + "ms 条数=" + count);
        }
    }

    /**
     * 被动显示模式:索引条不再扩展命中区去"抢"列表的触摸(此前该扩展用于拖动滚轮),
     * 列表可正常手动滑动;这里仅确保父布局没有遗留的 TouchDelegate。
     */
    private void updateIndexBarTouchDelegate() {
        final ViewParent vp = sideIndexBar.getParent();
        if (!(vp instanceof View)) return;
        final View parent = (View) vp;
        parent.post(new Runnable() {
            @Override
            public void run() {
                parent.setTouchDelegate(null);
            }
        });
    }

    /** 首字母 → LETTERS 下标;'#' 永远在最后一位 */
    private static int indexOfLetter(char c) {
        if (c >= 'A' && c <= 'Z') return c - 'A';
        if (c == '#') return SideIndexBar.LETTERS.length - 1;
        return -1;
    }

    /**
     * 被动模式:根据列表当前顶部可见项,更新索引条高亮的"当前字母"。
     * 字母仅在跨过边界时变化,因此只在变化时触发一次重绘,开销极小(车机友好)。
     */
    private void updateCurrentLetterFromScroll() {
        if (sideIndexBar == null || sideIndexBar.getVisibility() != View.VISIBLE) return;
        RecyclerView.LayoutManager lm = rvList.getLayoutManager();
        if (!(lm instanceof LinearLayoutManager)) return;
        int pos = ((LinearLayoutManager) lm).findFirstVisibleItemPosition();
        if (pos < 0) return;
        MusicBean b = adapter.getFilteredItem(pos);
        if (b == null) return;
        char c = PinyinUtils.firstLetter(b.getTitle());
        String letter = (c == '#') ? "#" : String.valueOf(c);
        sideIndexBar.setCurrentLetter(letter);
    }

    private void setupListeners() {
        // 搜索栏:实时搜索
        etSearch.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}

            @Override
            public void afterTextChanged(Editable s) {
                handleSearchInput(s != null ? s.toString() : "");
            }
        });

        // 搜索栏:IME 搜索按钮(软键盘回车)
        etSearch.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                if (actionId == EditorInfo.IME_ACTION_SEARCH ||
                    (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                    long t0 = System.currentTimeMillis();
                    String query = etSearch.getText() != null ? etSearch.getText().toString().trim() : "";
                    Log.i(TAG, "[SearchSubmit] query='" + query + "' 长度=" + query.length());
                    // 空搜索:清除焦点+收起键盘,不做过滤(避免不必要的 notifyDataSetChanged)
                    if (query.isEmpty()) {
                        etSearch.clearFocus();
                        android.view.inputmethod.InputMethodManager imm =
                            (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
                        if (imm != null) {
                            imm.hideSoftInputFromWindow(etSearch.getWindowToken(), 0);
                        }
                        Log.i(TAG, "[SearchSubmit] 空搜索,清除焦点 " + (System.currentTimeMillis() - t0) + "ms");
                    } else {
                        // 非空搜索:执行过滤
                        handleSearchInput(query);
                        Log.i(TAG, "[SearchSubmit] 过滤完成 " + (System.currentTimeMillis() - t0) + "ms");
                    }
                    return true;
                }
                return false;
            }
        });

        // 设置(均衡器 + 服务器统一入口)
        btnSettings.setOnClickListener(v -> {
            showSettingsMenu();
        });

        // 均衡器快捷按钮:弹出预设模式快速切换
        btnEq.setOnClickListener(v -> {
            showEqualizerQuickSwitch();
        });

        // 收藏夹:切换只看收藏(云端看服务器收藏,本地看本机收藏)
        btnFavorites.setOnClickListener(v -> {
            long t0 = System.currentTimeMillis();
            favoritesOnly = !favoritesOnly;
            modeSwitchPending = true;
            if (favoritesOnly) {
                btnFavorites.setBackgroundResource(R.drawable.bg_btn_play);
                Log.i(TAG, "[FavToggle] 切到收藏模式 localOnlyMode=" + localOnlyMode);
                if (!localOnlyMode) {
                    // 云端模式:收藏来自服务器,跨设备同步
                    if (adapter.isCloudFavoritesMode()) {
                        // 本次会话已经拉过:直接用现有 ID 集合重过滤,**秒开**。
                        // 以前每次进收藏夹都要 loadCloudFavorites() 走一整轮网络往返
                        // (飞牛还要翻页),进出几次就是"点了半天没反应"。
                        // 缓存只在切换本地/云端时作废,不会一直不更新。
                        Log.i(TAG, "[FavToggle] 复用已缓存的云端收藏集合(秒开)");
                        adapter.setSearchKeyword(currentSearchQuery);
                        // 强制通道:模式切换是关键操作,不该被防抖决定成败
                        adapter.filterFavorites(null, true);
                        updateCount();
                        updateCloudFavEmptyHint();
                        // 高亮推到下一帧:findPositionByBean 是 O(n) 遍历,
                        // 同步执行会阻塞 filter 的第一帧渲染
                        rvList.post(new Runnable() {
                            @Override
                            public void run() {
                                updatePlayingHighlight();
                            }
                        });
                    } else {
                        loadCloudFavorites();
                    }
                } else {
                    // 本地模式:收藏来自本机,不联网
                    adapter.setCloudStarredIds(null);
                    applyFavoritesFilter();
                }
                Log.i(TAG, "[FavToggle] 收藏模式完成 " + (System.currentTimeMillis() - t0) + "ms");
            } else {
                btnFavorites.setBackgroundResource(R.drawable.bg_btn);
                Log.i(TAG, "[FavToggle] 切回全部歌曲 query='" + currentSearchQuery + "'");
                // 退出收藏夹:清掉云端收藏集合与过滤模式,恢复完整列表。
                // 先把在途的云端收藏拉取代次作废 —— 它回来后若发现 favoritesOnly 已为 false
                // 就只会更新缓存、不再动列表(否则会把刚恢复的"全部歌曲"又切回收藏夹)。
                cloudFavGen++;
                DownloadDiag.log("[收藏夹] 退出 → 恢复全部歌曲(收藏集合已清空)");
                adapter.setCloudStarredIds(null);
                adapter.setFavoritesMode(false);
                // 恢复搜索或全部
                long t1 = System.currentTimeMillis();
                // 强制通道:退出收藏夹是模式切换,关键词往往没变,若被 120ms 防抖
                // 判定为"重复请求"就会静默跳过 —— 这类操作不该由防抖决定成败。
                adapter.filter(currentSearchQuery, true);
                Log.i(TAG, "[FavToggle] adapter.filter=" + (System.currentTimeMillis() - t1) + "ms");
                updateCount();
                // 隐藏"还没有收藏"的空提示
                if (!musicList.isEmpty()) {
                    tvEmpty.setVisibility(View.GONE);
                }
                // 延迟更新高亮:避免 filter 的 notifyDataSetChanged + highlight 的
                // findPositionByBean(5000遍历) + ensureLoaded(多批次加载) 叠加卡顿
                // post 到下一帧执行,让 filter 的 UI 更新先渲染
                rvList.post(new Runnable() {
                    @Override
                    public void run() {
                        long t2 = System.currentTimeMillis();
                        updatePlayingHighlight();
                        Log.i(TAG, "[FavToggle] highlight(post)=" + (System.currentTimeMillis() - t2) + "ms"
                                + " 总=" + (System.currentTimeMillis() - t0) + "ms");
                    }
                });
                // 自愈兜底:上面那次 filter 是**异步**的,而 updateCount() 是同步跑的 ——
                // 所以"顶部计数已变成共 810 首"**不代表**列表真的切过来了(用户两次反馈
                // 的正是这种"计数变了、列表还是收藏"的错位)。
                // 500ms 后若发现"无搜索关键词、但过滤结果条数仍少于全量",说明这次过滤
                // 没能生效(被作废/被防抖吃掉/被后续请求覆盖),再强制过滤一次。
                // 只在异常路径动手,正常路径零开销、零闪烁。
                rvList.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        if (favoritesOnly) return;                  // 用户又切回收藏夹了,不插手
                        if (!currentSearchQuery.isEmpty()) return;  // 带搜索时无法便宜地判定期望条数
                        int got = adapter.getTotalFilteredCount();
                        int all = adapter.getTotalCount();
                        if (got < all) {
                            DownloadDiag.log("[收藏夹] 自愈:退出后列表仍只有 " + got + "/" + all
                                    + " 首 → 重新强制过滤");
                            adapter.filter(currentSearchQuery, true);
                            updateCount();
                        }
                    }
                }, 500);
            }
            if (PerfLogger.isEnabled()) {
                PerfLogger.log("FavToggle", "总=" + (System.currentTimeMillis() - t0) + "ms favoritesOnly=" + favoritesOnly);
            }
        });

        // 本地/云端切换:云端=云端歌单(已下载本地播,未下载联网播);本地=扫描本地目录的全部歌曲。
        // 两个列表相互独立,各有各的数据来源与缓存;模式持久化,下次启动保持。
        btnSourceToggle.setOnClickListener(v -> toggleSource());

        // 点击服务器状态圆点可手动刷新连接状态
        // (监听挂在外层 32dp 命中区上 —— 12dp 的圆点在电阻屏上点不准)
        if (flServerStatus != null) {
            flServerStatus.setOnClickListener(v -> {
                if (statusMonitor != null && MusicDataHolder.getInstance().getMusicSourceApi() != null) {
                    Toast.makeText(this, "正在检测服务器连接...", Toast.LENGTH_SHORT).show();
                    statusMonitor.checkNow();
                }
            });
        }

        // 进度条
        sbProgress.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser && service != null) {
                    service.seekTo(progress);
                    tvCurrentTime.setText(MusicBean.formatDuration(progress));
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        // 播放控制
        btnPrev.setOnClickListener(v -> { if (service != null) service.prev(); });
        btnPlay.setOnClickListener(v -> { if (service != null) service.toggle(); });
        btnNext.setOnClickListener(v -> { if (service != null) service.next(); });

        // 播放模式
        btnMode.setOnClickListener(v -> {
            if (service != null) {
                PlayMode mode = service.cyclePlayMode();
                updatePlayModeIcon(mode);
                Toast.makeText(this, "播放模式: " + mode.getLabel(), Toast.LENGTH_SHORT).show();
            }
        });

        // 收藏当前播放歌曲(底栏收藏按钮)
        btnFav.setOnClickListener(v -> {
            if (service == null) {
                Toast.makeText(this, "未在播放", Toast.LENGTH_SHORT).show();
                return;
            }
            MusicBean current = service.getCurrentMusic();
            if (current == null) {
                Toast.makeText(this, "未在播放", Toast.LENGTH_SHORT).show();
                return;
            }
            boolean nowFav = favoriteManager.toggleFavorite(current);
            updateFavoriteButton(current);
            // 收藏状态变化时刷新列表:
            // - 云端收藏夹:**乐观更新** —— 本地改 ID 集合 + 立即重过滤,列表马上就动。
            //   以前这里要 loadCloudFavorites() 把服务器收藏整个重拉一遍才刷新,
            //   一次网络往返(飞牛还要试多个候选端点)下来就是"点了半天没反应";
            //   服务器同步失败时再由 syncStarToServer 回滚(重新拉真实状态)。
            // - 本地收藏夹重新过滤即可
            if (favoritesOnly) {
                String sid = current.getStreamId();
                if (adapter.isCloudFavoritesMode() && sid != null && !sid.isEmpty()) {
                    boolean changed = adapter.updateCloudStarredId(sid, nowFav);
                    updateCloudFavEmptyHint();
                    DownloadDiag.log("[收藏夹] " + (nowFav ? "收藏" : "取消收藏")
                            + " " + current.getTitle() + " sid=" + sid
                            + " 集合改动=" + changed
                            + " 收藏夹剩余=" + adapter.getCloudStarredCount() + " 首");
                } else {
                    DownloadDiag.log("[收藏夹] " + (nowFav ? "收藏" : "取消收藏")
                            + " 走本机过滤分支 sid="
                            + (sid == null ? "(空)" : sid)
                            + " 云端模式=" + adapter.isCloudFavoritesMode());
                    applyFavoritesFilter();
                }
            }
            // 云端歌曲:同时同步到服务器收藏(后台线程;失败会回滚并提示)
            syncStarToServer(current, nowFav);
            Toast.makeText(this, nowFav ? "已收藏" : "取消收藏", Toast.LENGTH_SHORT).show();
        });

        // 长按歌词区:调整歌词偏移(校正个别歌曲歌词不同步)
        lrcView.setOnLongClickListener(v -> {
            showLyricOffsetDialog();
            return true;
        });
    }

    /**
     * 歌词偏移调整对话框
     * 长按歌词区弹出,可微调当前歌曲的歌词同步偏移
     * 正值=歌词延后显示,负值=歌词提前显示
     */
    private void showLyricOffsetDialog() {
        if (service == null || !bound) {
            Toast.makeText(this, "未在播放", Toast.LENGTH_SHORT).show();
            return;
        }
        final MusicBean current = service.getCurrentMusic();
        if (current == null) {
            Toast.makeText(this, "未在播放", Toast.LENGTH_SHORT).show();
            return;
        }

        final long[] offset = {lyricOffsetManager.getOffset(current)};

        SubDialog sd = createSubDialog("🎵", "歌词偏移调整");

        // 歌曲名
        TextView tvSong = new TextView(this);
        tvSong.setText(current.getTitle());
        tvSong.setTextSize(14);
        tvSong.setGravity(Gravity.CENTER);
        tvSong.setTextColor(0xFF888888);
        tvSong.setPadding(0, 0, 0, dp(15));
        sd.body.addView(tvSong);

        // 当前偏移值显示
        final TextView tvOffset = new TextView(this);
        tvOffset.setTextSize(28);
        tvOffset.setGravity(Gravity.CENTER);
        tvOffset.setPadding(0, dp(10), 0, dp(10));
        String offsetText = offset[0] == 0 ? "0 ms (默认)" : (offset[0] > 0 ? "+" : "") + offset[0] + " ms";
        tvOffset.setText(offsetText);
        sd.body.addView(tvOffset);

        // 说明文字
        TextView tvHint = new TextView(this);
        tvHint.setText("正值=歌词延后显示(歌词快了)\n负值=歌词提前显示(歌词慢了)\n每步 200ms");
        tvHint.setTextSize(13);
        tvHint.setGravity(Gravity.CENTER);
        tvHint.setTextColor(0xFF999999);
        tvHint.setPadding(0, dp(5), 0, dp(15));
        sd.body.addView(tvHint);

        // 按钮行:提前 / 重置 / 延后
        LinearLayout llBtns = new LinearLayout(this);
        llBtns.setOrientation(LinearLayout.HORIZONTAL);
        llBtns.setGravity(Gravity.CENTER);
        llBtns.setPadding(0, dp(5), 0, dp(10));

        Button btnEarlier = createDialogButton("◀ 提前", false);
        Button btnReset = createDialogButton("重置", false);
        Button btnLater = createDialogButton("延后 ▶", true);

        llBtns.addView(btnEarlier);
        llBtns.addView(btnReset);
        llBtns.addView(btnLater);
        sd.body.addView(llBtns);

        // 更新偏移显示
        final Runnable updateOffsetText = () -> {
            String text = offset[0] == 0 ? "0 ms (默认)" : (offset[0] > 0 ? "+" : "") + offset[0] + " ms";
            tvOffset.setText(text);
        };

        btnEarlier.setOnClickListener(v -> {
            offset[0] -= 200;
            lyricOffsetManager.setOffset(current, offset[0]);
            updateOffsetText.run();
        });

        btnLater.setOnClickListener(v -> {
            offset[0] += 200;
            lyricOffsetManager.setOffset(current, offset[0]);
            updateOffsetText.run();
        });

        btnReset.setOnClickListener(v -> {
            offset[0] = 0;
            lyricOffsetManager.setOffset(current, 0);
            updateOffsetText.run();
        });

        // 底部关闭按钮
        Button btnClose = createDialogButton("关闭", true);
        sd.buttons.addView(btnClose);
        btnClose.setOnClickListener(v -> sd.dialog.dismiss());

        showDialogFull(sd.dialog);
    }

    // ==================== 搜索 ====================

    /**
     * 处理搜索栏输入(即时过滤本地文件)
     */
    private void handleSearchInput(String query) {
        long t0 = System.currentTimeMillis();
        currentSearchQuery = query != null ? query.trim() : "";
        Log.i(TAG, "[handleSearchInput] query='" + currentSearchQuery + "' favoritesOnly=" + favoritesOnly);
        if (PerfLogger.isEnabled()) {
            PerfLogger.log("SearchInput", "query='" + currentSearchQuery + "' favoritesOnly=" + favoritesOnly);
        }
        if (favoritesOnly) {
            applyFavoritesFilter();
        } else {
            long t1 = System.currentTimeMillis();
            adapter.filter(currentSearchQuery);
            Log.i(TAG, "[handleSearchInput] adapter.filter=" + (System.currentTimeMillis() - t1) + "ms");
        }
        long t2 = System.currentTimeMillis();
        updateCount();
        updatePlayingHighlight();
        Log.i(TAG, "[handleSearchInput] updateCount+highlight=" + (System.currentTimeMillis() - t2) + "ms");
        Log.i(TAG, "[handleSearchInput] 总耗时=" + (System.currentTimeMillis() - t0) + "ms");
        if (PerfLogger.isEnabled()) {
            PerfLogger.log("SearchInput", "总耗时=" + (System.currentTimeMillis() - t0) + "ms");
        }
    }

    // ==================== 收藏夹 ====================

    /** 应用收藏过滤:只显示已收藏的歌曲(同时应用搜索关键词) */
    private void applyFavoritesFilter() {
        long t0 = System.currentTimeMillis();
        int count = favoriteManager.size();
        if (count == 0) {
            Toast.makeText(this, "还没有收藏的歌曲", Toast.LENGTH_SHORT).show();
        }
        // 设置搜索关键词,使 filterFavorites 也按搜索过滤
        adapter.setSearchKeyword(currentSearchQuery);
        long t1 = System.currentTimeMillis();
        adapter.filterFavorites(favoriteManager);
        Log.i(TAG, "[applyFavoritesFilter] filterFavorites=" + (System.currentTimeMillis() - t1) + "ms");
        updateCount();
        if (count == 0) {
            tvEmpty.setVisibility(View.VISIBLE);
            tvEmpty.setText("还没有收藏的歌曲\n播放歌曲时点击底栏爱心收藏");
        } else {
            tvEmpty.setVisibility(View.GONE);
        }
        // 更新高亮:只标记当前播放歌曲(如果不在收藏列表中则清除高亮)
        long t2 = System.currentTimeMillis();
        updatePlayingHighlight();
        Log.i(TAG, "[applyFavoritesFilter] highlight=" + (System.currentTimeMillis() - t2) + "ms"
                + " 总=" + (System.currentTimeMillis() - t0) + "ms");
        if (PerfLogger.isEnabled()) {
            PerfLogger.log("applyFavFilter", "总=" + (System.currentTimeMillis() - t0) + "ms");
        }
    }

    /**
     * 云端收藏夹:从服务器拉取收藏列表(后台线程),拿到后按 streamId 过滤当前列表。
     *
     * 为什么按 streamId 过滤、而不是直接把服务器返回的列表塞进去:
     * 服务器返回的 bean 可能与当前列表里的不是同一份(字段/路径/是否已下载都可能不同),
     * 直接替换会让播放队列、高亮、来源标识全部错位;按 ID 过滤则沿用列表里已有的
     * bean 对象,行为与普通列表完全一致,后台刷新时也只要重新过滤一次即可。
     *
     * 失败时退回本地收藏(本机收藏照常可用),绝不留一个空白列表。
     */
    private void loadCloudFavorites() {
        final MusicSourceApi api = MusicDataHolder.getInstance().getMusicSourceApi();
        if (api == null) {
            Toast.makeText(this, "未连接服务器,改用本地收藏", Toast.LENGTH_SHORT).show();
            adapter.setCloudStarredIds(null);
            applyFavoritesFilter();
            return;
        }
        // 本次拉取的代次:网络回来之前用户可能已经退出收藏夹、甚至又进了一次,
        // 旧结果必须作废(否则会把用户当前的列表状态覆盖回去)
        final int gen = ++cloudFavGen;
        Toast.makeText(this, "正在获取云端收藏...", Toast.LENGTH_SHORT).show();
        new Thread(new Runnable() {
            @Override
            public void run() {
                List<MusicBean> starred = null;
                try {
                    starred = api.getStarredSongs();
                } catch (Throwable t) {
                    DownloadDiag.logError("获取云端收藏失败", t);
                }
                final List<MusicBean> result = starred;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (gen != cloudFavGen) {
                            // 已经有更新的拉取在跑/已完成,这次结果直接丢弃
                            DownloadDiag.log("云端收藏: 丢弃过期结果(gen=" + gen
                                    + " 当前=" + cloudFavGen + ")");
                            return;
                        }
                        if (result == null) {
                            Toast.makeText(MainActivity.this,
                                    "获取云端收藏失败,改用本地收藏", Toast.LENGTH_SHORT).show();
                            adapter.setCloudStarredIds(null);
                            applyFavoritesFilter();
                            return;
                        }
                        java.util.Set<String> ids = new java.util.HashSet<>();
                        for (MusicBean b : result) {
                            String sid = b != null ? b.getStreamId() : null;
                            if (sid != null && !sid.isEmpty()) {
                                ids.add(sid);
                            }
                        }
                        // 统计"当前列表里能匹配上几首" —— 收藏夹是按 streamId 过滤
                        // 现有列表的,若某首收藏歌不在当前列表里就显示不出来。
                        // 记下来才能区分:是服务端只给了 50 首,还是列表里只匹配到 50 首。
                        int matched = 0;
                        for (MusicBean b : musicList) {
                            String sid = (b != null) ? b.getStreamId() : null;
                            if (sid != null && ids.contains(sid)) {
                                matched++;
                            }
                        }
                        DownloadDiag.log("云端收藏: 服务器 " + result.size() + " 首, 有效 ID "
                                + ids.size() + " 个, 当前列表匹配 " + matched + " 首");
                        // 集合照常更新(下次进收藏夹就能秒开),但**不一定**要动列表:
                        // 网络是异步的,这几十毫秒到几秒里用户可能已经点了退出收藏夹。
                        // 以前这里无条件 filterFavorites,于是把用户刚恢复的"全部歌曲"
                        // 又强行切回收藏夹 —— 表现就是"取消收藏后列表没切回全部歌曲"。
                        adapter.setCloudStarredIds(ids);
                        if (!favoritesOnly) {
                            DownloadDiag.log("云端收藏: 用户已退出收藏夹,只更新缓存不动列表");
                            return;
                        }
                        Toast.makeText(MainActivity.this,
                                "云端收藏 " + ids.size() + " 首", Toast.LENGTH_SHORT).show();
                        // 强制通道:"收藏列表刚拿回来,必须切过去"是关键操作,
                        // 不能被防抖当成重复请求静默跳过
                        adapter.filterFavorites(null, true);
                        updateCount();
                        if (ids.isEmpty()) {
                            tvEmpty.setVisibility(View.VISIBLE);
                            tvEmpty.setText("云端还没有收藏的歌曲\n播放歌曲时点击底栏爱心收藏");
                        } else {
                            tvEmpty.setVisibility(View.GONE);
                        }
                        updatePlayingHighlight();
                    }
                });
            }
        }).start();
    }

    /**
     * 云端歌曲的收藏同步到服务器(后台线程)。
     *
     * 与列表刷新的关系:调用方已经做过**乐观更新**(本地改收藏集合 + 立即重过滤),
     * 所以这里只负责把结果落到服务器。同步失败才回滚 —— 重新拉一次服务器真实状态,
     * 并给出可见提示,避免"界面显示已收藏、服务器其实没有"这种看不见的不一致。
     */
    private void syncStarToServer(final MusicBean bean, final boolean star) {
        if (localOnlyMode || bean == null) {
            return;
        }
        final String sid = bean.getStreamId();
        if (sid == null || sid.isEmpty()) {
            return;
        }
        final MusicSourceApi api = MusicDataHolder.getInstance().getMusicSourceApi();
        if (api == null) {
            return;
        }
        final Runnable onFail = new Runnable() {
            @Override
            public void run() {
                // 回滚:以服务器真实状态为准重拉一次(只在还停在收藏夹里时才需要)
                if (favoritesOnly && adapter.isCloudFavoritesMode()) {
                    loadCloudFavorites();
                }
                Toast.makeText(MainActivity.this,
                        star ? "已收藏(服务器同步失败,仅本机)" : "已取消(服务器同步失败,仅本机)",
                        Toast.LENGTH_SHORT).show();
            }
        };
        new Thread(new Runnable() {
            @Override
            public void run() {
                long t0 = System.currentTimeMillis();
                boolean ok;
                try {
                    ok = star ? api.starSong(sid) : api.unstarSong(sid);
                } catch (Throwable t) {
                    DownloadDiag.logError("收藏同步服务器异常", t);
                    ok = false;
                }
                long cost = System.currentTimeMillis() - t0;
                DownloadDiag.log("收藏同步服务器: " + (ok ? "成功" : "失败")
                        + " star=" + star + " 耗时=" + cost + "ms " + bean.getTitle());
                if (!ok) {
                    runOnUiThread(onFail);
                }
            }
        }).start();
    }

    /**
     * 云端收藏夹的空提示。
     * 乐观更新后要立刻刷新一次:取消掉最后一首收藏时列表会变空,提示得马上出来。
     */
    private void updateCloudFavEmptyHint() {
        if (!adapter.isCloudFavoritesMode()) {
            return;
        }
        if (adapter.getCloudStarredCount() <= 0) {
            tvEmpty.setVisibility(View.VISIBLE);
            tvEmpty.setText("云端还没有收藏的歌曲\n播放歌曲时点击底栏爱心收藏");
        } else {
            tvEmpty.setVisibility(View.GONE);
        }
    }

    // ==================== 设置菜单 ====================

    /** 全屏子弹窗持有器(统一风格) */
    private static class SubDialog {
        Dialog dialog;
        LinearLayout body;
        LinearLayout buttons;
    }

    /** dp 转 px */
    private int dp(int dp) {
        return (int) (dp * getResources().getDisplayMetrics().density + 0.5f);
    }

    /**
     * 显示 Dialog 并强制全屏
     * 使用普通 Dialog(非 AlertDialog),避免内部容器包裹导致无法全屏
     */
    private void showDialogFull(Dialog dialog) {
        dialog.show();
        android.view.Window window = dialog.getWindow();
        if (window != null) {
            window.setLayout(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT);
            window.getDecorView().setPadding(0, 0, 0, 0);
            // 清除默认 Dialog 背景Drawable(可能带圆角/padding)
            window.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0xFF16161C));
        }
    }

    /**
     * 创建全屏设置子弹窗:深色头部 + 关闭按钮 + 内容区 + 按钮区
     * 使用 Dialog.setContentView 直接设置视图,无 AlertDialog 包裹层
     */
    private SubDialog createSubDialog(String icon, String title) {
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_sub_content, null);
        SubDialog sd = new SubDialog();
        sd.body = (LinearLayout) view.findViewById(R.id.ll_dialog_body);
        sd.buttons = (LinearLayout) view.findViewById(R.id.ll_dialog_buttons);
        ((TextView) view.findViewById(R.id.tv_dialog_icon)).setText(icon);
        ((TextView) view.findViewById(R.id.tv_dialog_title)).setText(title);
        sd.dialog = new Dialog(this, R.style.Theme_CaptivaDialog);
        sd.dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        sd.dialog.setContentView(view);
        final Dialog d = sd.dialog;
        view.findViewById(R.id.btn_dialog_close).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { d.dismiss(); }
        });
        return sd;
    }

    /** 创建美化按钮(正面=深蓝调+亮字,负面=深灰) */
    private Button createDialogButton(String text, boolean positive) {
        Button btn = new Button(this);
        btn.setText(text);
        btn.setTextColor(positive
                ? getResources().getColor(R.color.accent) : 0xFFC0C0C5);
        btn.setTextSize(16);
        btn.setMinWidth(0);
        btn.setMinimumWidth(0);
        btn.setMinHeight(0);
        btn.setMinimumHeight(0);
        btn.setPadding(48, 18, 48, 18);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        lp.setMargins(8, 0, 8, 0);
        btn.setLayoutParams(lp);
        btn.setBackgroundResource(positive
                ? R.drawable.bg_dialog_btn_positive : R.drawable.bg_dialog_btn_negative);
        return btn;
    }

    /** 创建信息卡片(圆角深色背景,内含文字) */
    private TextView createInfoCard(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(getResources().getColor(R.color.text_primary));
        tv.setTextSize(17);
        tv.setLineSpacing(4, 1);
        tv.setPadding(28, 24, 28, 24);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = 16;
        tv.setLayoutParams(lp);
        tv.setBackgroundResource(R.drawable.bg_info_card);
        return tv;
    }

    /** 弹出设置菜单:均衡器 / 服务器设置 / 自动播放 / 时长过滤 / 刷新列表 / 屏幕信息 / 清除缓存 / 关于 */
    private void showSettingsMenu() {
        final String[] itemTexts = {
            "均衡器", "服务器设置",
            "自动播放: " + (navidromeConfig.isAutoPlay() ? "开启" : "关闭"),
            "时长过滤设置", "刷新歌曲列表", "屏幕分辨率与DPI", "清除列表缓存", "关于"
        };
        final String[] itemIcons = {"♪", "📡", "▶", "⏱", "🔄", "📐", "🗑", "ℹ"};

        // 自定义 Adapter:图标 + 文字 + 箭头
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(
                this, R.layout.dialog_settings_item, itemTexts) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                if (convertView == null) {
                    convertView = LayoutInflater.from(getContext()).inflate(
                            R.layout.dialog_settings_item, parent, false);
                }
                TextView tvIcon = (TextView) convertView.findViewById(R.id.tv_item_icon);
                TextView tvText = (TextView) convertView.findViewById(R.id.tv_item_text);
                tvIcon.setText(itemIcons[position]);
                tvText.setText(itemTexts[position]);
                return convertView;
            }
        };

        // 使用自定义布局
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_settings, null);
        ListView lv = (ListView) dialogView.findViewById(R.id.lv_settings);
        lv.setAdapter(adapter);

        final Dialog dialog = new Dialog(this, R.style.Theme_CaptivaDialog);
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        dialog.setContentView(dialogView);

        // 关闭按钮
        dialogView.findViewById(R.id.btn_settings_close).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { dialog.dismiss(); }
        });

        lv.setOnItemClickListener(new android.widget.AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(android.widget.AdapterView<?> parent, View view, int which, long id) {
                dialog.dismiss();
                if (which == 0) {
                    openEqualizer();
                } else if (which == 1) {
                    needReload = true;
                    startActivity(new Intent(MainActivity.this, ServerSettingsActivity.class));
                } else if (which == 2) {
                    showAutoPlayDialog();
                } else if (which == 3) {
                    showDurationFilterDialog();
                } else if (which == 4) {
                    refreshMusicList();
                } else if (which == 5) {
                    showScreenInfoDialog();
                } else if (which == 6) {
                    showClearCacheDialog();
                } else if (which == 7) {
                    showAboutDialog();
                }
            }
        });

        showDialogFull(dialog);
    }

    /** 自动播放设置对话框(全屏美化) */
    private void showAutoPlayDialog() {
        final SubDialog sd = createSubDialog("▶", "打开软件自动播放");
        boolean current = navidromeConfig.isAutoPlay();
        final String[] items = {"开启", "关闭"};
        final boolean[] values = {true, false};
        final Dialog[] dRef = new Dialog[1];
        dRef[0] = sd.dialog;

        // 提示信息
        sd.body.addView(createInfoCard("选择打开应用时是否自动播放上次的歌曲"));

        for (int i = 0; i < items.length; i++) {
            final int index = i;
            final boolean selected = (current == values[i]);
            // 选项行
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(28, 22, 28, 22);
            row.setBackgroundResource(R.drawable.bg_dialog_option);
            LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            rowLp.bottomMargin = 12;
            row.setLayoutParams(rowLp);

            // 选中圆点
            final TextView dot = new TextView(this);
            dot.setTextSize(22);
            dot.setText(selected ? "●" : "○");
            dot.setTextColor(selected
                    ? getResources().getColor(R.color.accent) : 0xFF6A6A70);
            dot.setPadding(0, 0, 18, 0);

            // 文字
            TextView label = new TextView(this);
            label.setText(items[i]);
            label.setTextColor(getResources().getColor(R.color.text_primary));
            label.setTextSize(20);
            label.setLayoutParams(new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

            row.addView(dot);
            row.addView(label);

            row.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    navidromeConfig.setAutoPlay(values[index]);
                    Toast.makeText(MainActivity.this,
                            "自动播放已" + (values[index] ? "开启" : "关闭"),
                            Toast.LENGTH_SHORT).show();
                    dRef[0].dismiss();
                }
            });

            sd.body.addView(row);
        }

        // 底部按钮
        Button btnCancel = createDialogButton("关闭", false);
        btnCancel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { dRef[0].dismiss(); }
        });
        sd.buttons.addView(btnCancel);

        showDialogFull(sd.dialog);
    }

    /** 清除歌曲列表缓存确认对话框(全屏美化) */
    private void showClearCacheDialog() {
        final SubDialog sd = createSubDialog("🗑", "清除歌曲列表缓存");
        final Dialog[] dRef = new Dialog[1];
        dRef[0] = sd.dialog;

        // 按当前服务器类型取对应的网络缓存(切服务器后清的是当前服务器的缓存)
        SongCache curNetCache = new SongCache(this, navidromeConfig.getServerType());
        boolean hasNetCache = curNetCache.exists();
        boolean hasLocalCache = localMusicCache.exists();
        StringBuilder sb = new StringBuilder();
        if (hasNetCache) {
            sb.append("网络缓存: ").append(formatCacheTime(curNetCache.getCachedAt())).append("\n");
        }
        if (hasLocalCache) {
            sb.append("本地缓存: ").append(formatCacheTime(localMusicCache.getCachedAt())).append("\n");
        }
        if (sb.length() == 0) {
            sb.append("当前无缓存数据");
        }
        sb.append("\n\n此操作清除歌曲列表缓存(网络+本地),不影响已下载的音乐文件\n清除后下次打开将从U盘重新扫描");

        sd.body.addView(createInfoCard(sb.toString()));

        if (hasNetCache || hasLocalCache) {
            Button btnClear = createDialogButton("清除", true);
            btnClear.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    curNetCache.clear();
                    localMusicCache.clear();
                    Toast.makeText(MainActivity.this, "缓存已清除", Toast.LENGTH_SHORT).show();
                    dRef[0].dismiss();
                }
            });
            Button btnCancel = createDialogButton("取消", false);
            btnCancel.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) { dRef[0].dismiss(); }
            });
            sd.buttons.addView(btnClear);
            sd.buttons.addView(btnCancel);
        } else {
            Button btnOk = createDialogButton("确定", true);
            btnOk.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) { dRef[0].dismiss(); }
            });
            sd.buttons.addView(btnOk);
        }

        showDialogFull(sd.dialog);
    }

    /** 屏幕分辨率与DPI信息对话框(全屏美化) */
    private void showScreenInfoDialog() {
        final SubDialog sd = createSubDialog("📐", "屏幕分辨率与DPI");
        final Dialog[] dRef = new Dialog[1];
        dRef[0] = sd.dialog;

        // 获取屏幕分辨率和DPI
        android.util.DisplayMetrics dm = new android.util.DisplayMetrics();
        getWindowManager().getDefaultDisplay().getMetrics(dm);

        int widthPx = dm.widthPixels;
        int heightPx = dm.heightPixels;
        int densityDpi = dm.densityDpi;
        float density = dm.density;
        float xdpi = dm.xdpi;
        float ydpi = dm.ydpi;
        float scaledDensity = dm.scaledDensity;

        // 计算物理尺寸(英寸)
        double physicalInch = 0;
        try {
            double widthInch = widthPx / (double) xdpi;
            double heightInch = heightPx / (double) ydpi;
            physicalInch = Math.sqrt(widthInch * widthInch + heightInch * heightInch);
        } catch (Exception e) {
            // ignore
        }

        // dp 尺寸
        float widthDp = widthPx / density;
        float heightDp = heightPx / density;

        // 判断 DPI 等级
        String dpiLevel;
        if (densityDpi <= 120) {
            dpiLevel = "ldpi (低)";
        } else if (densityDpi <= 160) {
            dpiLevel = "mdpi (中)";
        } else if (densityDpi <= 240) {
            dpiLevel = "hdpi (高)";
        } else if (densityDpi <= 320) {
            dpiLevel = "xhdpi (超高)";
        } else if (densityDpi <= 480) {
            dpiLevel = "xxhdpi (超超高)";
        } else if (densityDpi <= 640) {
            dpiLevel = "xxxhdpi (超超超高)";
        } else {
            dpiLevel = "未知";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("屏幕分辨率: ").append(widthPx).append(" × ").append(heightPx).append(" px\n");
        sb.append("DP 尺寸: ").append(String.format("%.1f", widthDp))
                .append(" × ").append(String.format("%.1f", heightDp)).append(" dp\n\n");
        sb.append("屏幕密度(DPI): ").append(densityDpi).append("\n");
        sb.append("密度等级: ").append(dpiLevel).append("\n");
        sb.append("密度因子: ").append(String.format("%.2f", density)).append("\n\n");
        sb.append("X轴 DPI: ").append(String.format("%.1f", xdpi)).append("\n");
        sb.append("Y轴 DPI: ").append(String.format("%.1f", ydpi)).append("\n");
        sb.append("字体缩放: ").append(String.format("%.2f", scaledDensity)).append("\n\n");
        if (physicalInch > 0) {
            sb.append("物理尺寸: ").append(String.format("%.1f", physicalInch)).append(" 英寸\n");
        }
        sb.append("总像素: ").append(widthPx * heightPx).append("\n");
        sb.append("宽高比: ").append(String.format("%.2f", (double) widthPx / heightPx));

        sd.body.addView(createInfoCard(sb.toString()));

        Button btnOk = createDialogButton("确定", true);
        btnOk.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { dRef[0].dismiss(); }
        });
        sd.buttons.addView(btnOk);

        showDialogFull(sd.dialog);
    }

    /** 时长过滤设置对话框(全屏美化):自定义输入秒数 */
    private void showDurationFilterDialog() {
        final SubDialog sd = createSubDialog("⏱", "最小时长过滤(秒)");
        final Dialog[] dRef = new Dialog[1];
        dRef[0] = sd.dialog;
        final int currentMin = navidromeConfig.getMinDuration();

        // 提示卡片
        sd.body.addView(createInfoCard("低于此时长的音频将被过滤\n输入 0 表示显示全部\n范围 0~600 秒"));

        // 创建美化输入框
        final EditText etInput = new EditText(this);
        etInput.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        etInput.setText(currentMin > 0 ? String.valueOf(currentMin) : "");
        etInput.setHint("输入秒数,如 30(0 表示不过滤)");
        etInput.setTextColor(getResources().getColor(R.color.text_primary));
        etInput.setHintTextColor(getResources().getColor(R.color.search_hint));
        etInput.setTextSize(18);
        etInput.setPadding(28, 20, 28, 20);
        etInput.setBackgroundResource(R.drawable.bg_info_card);
        LinearLayout.LayoutParams etLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        etLp.bottomMargin = 16;
        etInput.setLayoutParams(etLp);
        sd.body.addView(etInput);

        // 底部按钮
        Button btnOk = createDialogButton("确定", true);
        btnOk.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String input = etInput.getText().toString().trim();
                int newMin = 0;
                try {
                    newMin = Integer.parseInt(input);
                    if (newMin < 0) newMin = 0;
                    if (newMin > 600) newMin = 600; // 最大10分钟
                } catch (NumberFormatException e) {
                    Toast.makeText(MainActivity.this, "输入无效,保持原设置", Toast.LENGTH_SHORT).show();
                    return;
                }
                navidromeConfig.setMinDuration(newMin);
                Toast.makeText(MainActivity.this,
                        "已设置最小时长: " + (newMin == 0 ? "不过滤" : newMin + "秒"),
                        Toast.LENGTH_SHORT).show();
                dRef[0].dismiss();
                // 重新加载音乐
                loadMusic();
            }
        });
        Button btnCancel = createDialogButton("取消", false);
        btnCancel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { dRef[0].dismiss(); }
        });
        sd.buttons.addView(btnOk);
        sd.buttons.addView(btnCancel);

        showDialogFull(sd.dialog);
    }

    /** 打开均衡器(无需播放状态,支持静默调节) */
    private void openEqualizer() {
        startActivity(new Intent(this, EqualizerActivity.class));
    }

    // ==================== 关于与检测更新 ====================

    /** 关于对话框(全屏美化) */
    private void showAboutDialog() {
        final SubDialog sd = createSubDialog("ℹ", "关于");
        final Dialog[] dRef = new Dialog[1];
        dRef[0] = sd.dialog;

        String verName = "1.0";
        int verCode = 1;
        try {
            PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
            verName = pi.versionName;
            verCode = pi.versionCode;
        } catch (PackageManager.NameNotFoundException e) {
            // ignore
        }

        // 版本信息卡片
        StringBuilder sb = new StringBuilder();
        sb.append("科帕奇音乐播放器\n\n");
        sb.append("版本: ").append(verName).append(" (").append(verCode).append(")\n");
        sb.append("适配: 安卓 4.2.2+ 车机\n");
        sb.append("分辨率: 1024×600 横屏");
        sd.body.addView(createInfoCard(sb.toString()));

        // 功能列表卡片
        StringBuilder sb2 = new StringBuilder();
        sb2.append("功能:\n");
        sb2.append("• 本地/Navidrome 网络双模式播放\n");
        sb2.append("• 歌词叠加封面显示\n");
        sb2.append("• 均衡器(预设/自定义/单曲绑定)\n");
        sb2.append("• 收藏夹 / 搜索 / 自动播放");
        sd.body.addView(createInfoCard(sb2.toString()));

        // GitHub 信息卡片
        sd.body.addView(createInfoCard("GitHub:\nkangwenhang/android-music-player"));

        // 底部按钮
        Button btnUpdate = createDialogButton("检测更新", true);
        btnUpdate.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dRef[0].dismiss();
                checkForUpdate();
            }
        });
        Button btnClose = createDialogButton("关闭", false);
        btnClose.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { dRef[0].dismiss(); }
        });
        sd.buttons.addView(btnUpdate);
        sd.buttons.addView(btnClose);

        showDialogFull(sd.dialog);
    }

    /** GitHub 仓库信息 */
    private static final String GITHUB_OWNER = "kangwenhang";
    private static final String GITHUB_REPO = "android-music-player";

    /** GitHub 下载代理镜像(国内网络 github.com 经常连不上) */
    private static final String[] GITHUB_DL_MIRRORS = {
        "http://ghproxy.net/",       // HTTP 镜像(旧系统SSL fallback,优先)
        "",                          // 直连 HTTPS
        "https://ghproxy.net/",      // 镜像1 HTTPS
        "https://gh-proxy.com/",     // 镜像2
        "https://mirror.ghproxy.com/", // 镜像3
        "https://githubproxy.cc/",   // 镜像4
        "https://ghfast.top/",       // 镜像5
    };

    /**
     * 检测更新:查询 GitHub Releases API,只获取正式版(非 prerelease)
     * 对比当前版本与最新正式版,弹窗提示下载
     */
    private void checkForUpdate() {
        Toast.makeText(this, "正在检查更新...", Toast.LENGTH_SHORT).show();

        new Thread(new Runnable() {
            @Override
            public void run() {
                HttpURLConnection conn = null;
                try {
                    // 尝试多个 API 地址(HTTP代理优先,避免旧系统SSL问题)
                    String[] apiUrls = {
                        "http://ghproxy.net/https://api.github.com/repos/" + GITHUB_OWNER + "/" + GITHUB_REPO
                            + "/releases?per_page=30",
                        "https://api.github.com/repos/" + GITHUB_OWNER + "/" + GITHUB_REPO
                            + "/releases?per_page=30",
                        "https://ghproxy.net/https://api.github.com/repos/" + GITHUB_OWNER + "/" + GITHUB_REPO
                            + "/releases?per_page=30",
                        "https://gh-proxy.com/https://api.github.com/repos/" + GITHUB_OWNER + "/" + GITHUB_REPO
                            + "/releases?per_page=30",
                        "https://githubproxy.cc/https://api.github.com/repos/" + GITHUB_OWNER + "/" + GITHUB_REPO
                            + "/releases?per_page=30",
                    };

                    int code = -1;
                    InputStream is = null;
                    Exception lastErr = null;
                    StringBuilder sb = new StringBuilder();

                    for (String apiUrl : apiUrls) {
                        try {
                            conn = createConnection(apiUrl);
                            conn.setRequestMethod("GET");
                            conn.setRequestProperty("Accept", "application/vnd.github+json");
                            conn.setConnectTimeout(15000);
                            conn.setReadTimeout(15000);
                            conn.connect();
                            code = conn.getResponseCode();
                            if (code == 200) {
                                is = conn.getInputStream();
                                BufferedReader reader = new BufferedReader(new InputStreamReader(is, "UTF-8"));
                                String line;
                                while ((line = reader.readLine()) != null) {
                                    sb.append(line);
                                }
                                reader.close();
                                break; // 成功,跳出
                            }
                        } catch (Exception e) {
                            lastErr = e;
                            Log.w(TAG, "API 请求失败(" + apiUrl + "): " + e.getMessage());
                        } finally {
                            if (conn != null) { conn.disconnect(); conn = null; }
                        }
                    }

                    final int finalCode = code;
                    if (code != 200) {
                        final Exception finalErr = lastErr;
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                if (finalErr != null && isSslError(finalErr)) {
                                    Toast.makeText(MainActivity.this,
                                            "检查更新失败: 系统 SSL/TLS 版本过旧\n已尝试跳过证书验证但仍无法连接\n建议用手机浏览器下载APK传到车机安装",
                                            Toast.LENGTH_LONG).show();
                                } else if (finalErr != null) {
                                    Toast.makeText(MainActivity.this,
                                            "检查更新失败: 网络无法连接GitHub\n" + finalErr.getMessage(),
                                            Toast.LENGTH_LONG).show();
                                } else {
                                    Toast.makeText(MainActivity.this, "检查更新失败: 服务器错误(" + finalCode + ")",
                                            Toast.LENGTH_SHORT).show();
                                }
                            }
                        });
                        return;
                    }

                    // 检查响应是否是合法 JSON(代理镜像可能返回 HTML 错误页)
                    String responseStr = sb.toString().trim();
                    if (responseStr.startsWith("<") || responseStr.startsWith("<!")) {
                        // 收到 HTML 页面而非 JSON,说明代理镜像返回了错误页
                        // 继续尝试下一个镜像(如果还有的话)
                        Log.w(TAG, "API 返回 HTML 而非 JSON(代理错误页),尝试下一个镜像");
                        // 重新遍历剩余的镜像
                        boolean found = false;
                        for (int retry = 0; retry < apiUrls.length; retry++) {
                            try {
                                conn = createConnection(apiUrls[retry]);
                                conn.setRequestMethod("GET");
                                conn.setRequestProperty("Accept", "application/vnd.github+json");
                                conn.setConnectTimeout(15000);
                                conn.setReadTimeout(15000);
                                conn.connect();
                                if (conn.getResponseCode() == 200) {
                                    is = conn.getInputStream();
                                    BufferedReader reader2 = new BufferedReader(new InputStreamReader(is, "UTF-8"));
                                    sb.setLength(0);
                                    String line2;
                                    while ((line2 = reader2.readLine()) != null) {
                                        sb.append(line2);
                                    }
                                    reader2.close();
                                    responseStr = sb.toString().trim();
                                    if (responseStr.startsWith("[")) {
                                        found = true;
                                        break;
                                    }
                                }
                            } catch (Exception e) {
                                Log.w(TAG, "重试镜像失败(" + apiUrls[retry] + "): " + e.getMessage());
                            } finally {
                                if (conn != null) { conn.disconnect(); conn = null; }
                            }
                        }
                        if (!found) {
                            final String errMsg = "所有镜像均返回非JSON响应\n可能是网络代理拦截\n建议用手机浏览器下载APK传到车机安装";
                            runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    Toast.makeText(MainActivity.this,
                                            "检查更新失败: " + errMsg,
                                            Toast.LENGTH_LONG).show();
                                }
                            });
                            return;
                        }
                    }

                    JSONArray releases = new JSONArray(responseStr);

                    // 只找正式版(prerelease=false),取第一个(最新)
                    JSONObject latestRelease = null;
                    for (int i = 0; i < releases.length(); i++) {
                        JSONObject r = releases.getJSONObject(i);
                        if (!r.optBoolean("prerelease", false)) {
                            latestRelease = r;
                            break;
                        }
                    }

                    if (latestRelease == null) {
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                Toast.makeText(MainActivity.this, "暂无正式版本可用",
                                        Toast.LENGTH_SHORT).show();
                            }
                        });
                        return;
                    }

                    final String latestTag = latestRelease.optString("tag_name", "");
                    final String releaseName = latestRelease.optString("name", latestTag);
                    final String releaseUrl = latestRelease.optString("html_url", "");
                    final String releaseBody = latestRelease.optString("body", "");

                    // 获取 APK 下载地址
                    String apkUrl = "";
                    JSONArray assets = latestRelease.optJSONArray("assets");
                    if (assets != null && assets.length() > 0) {
                        for (int i = 0; i < assets.length(); i++) {
                            JSONObject asset = assets.getJSONObject(i);
                            String name = asset.optString("name", "");
                            if (name.endsWith(".apk")) {
                                apkUrl = asset.optString("browser_download_url", "");
                                break;
                            }
                        }
                    }

                    // 获取当前版本
                    String currentVer = "1.0";
                    try {
                        PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
                        currentVer = pi.versionName;
                    } catch (Exception e) {
                        // ignore
                    }

                    // 比较版本(去除 v 前缀)
                    String currentClean = currentVer.replaceFirst("^v", "");
                    String latestClean = latestTag.replaceFirst("^v", "");
                    final boolean hasUpdate = compareVersion(latestClean, currentClean) > 0;

                    final String finalApkUrl = apkUrl;
                    final String currentVerFinal = currentVer;

                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (hasUpdate) {
                                showUpdateDialog(latestTag, releaseName, releaseBody,
                                        releaseUrl, finalApkUrl, currentVerFinal);
                            } else {
                                Toast.makeText(MainActivity.this,
                                        "已是最新版本 (" + currentVerFinal + ")",
                                        Toast.LENGTH_SHORT).show();
                            }
                        }
                    });

                } catch (final Exception e) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            Toast.makeText(MainActivity.this,
                                    "检查更新失败: " + e.getMessage(),
                                    Toast.LENGTH_SHORT).show();
                        }
                    });
                } finally {
                    if (conn != null) conn.disconnect();
                }
            }
        }).start();
    }

    /** 版本号比较:返回 >0 表示 v2 大于 v1 */
    private int compareVersion(String v1, String v2) {
        String[] parts1 = v1.split("[.\\-]");
        String[] parts2 = v2.split("[.\\-]");
        int len = Math.max(parts1.length, parts2.length);
        for (int i = 0; i < len; i++) {
            int n1 = 0, n2 = 0;
            try { n1 = Integer.parseInt(parts1[i]); } catch (Exception e) { }
            try { n2 = Integer.parseInt(parts2[i]); } catch (Exception e) { }
            if (n1 != n2) return n1 - n2;
        }
        return 0;
    }

    /** 显示有新版本的更新对话框 */
    private void showUpdateDialog(final String tag, String name, String body,
                                  final String releaseUrl, final String apkUrl,
                                  String currentVer) {
        final SubDialog sd = createSubDialog("⬆", "发现新版本");
        final Dialog[] dRef = new Dialog[1];
        dRef[0] = sd.dialog;

        StringBuilder msg = new StringBuilder();
        msg.append("当前版本: ").append(currentVer).append("\n");
        msg.append("最新版本: ").append(tag).append("\n");
        if (name != null && !name.isEmpty() && !name.equals(tag)) {
            msg.append("版本名称: ").append(name).append("\n");
        }
        msg.append("\n更新内容:\n");
        if (body != null && !body.isEmpty()) {
            String preview = body.length() > 500 ? body.substring(0, 500) + "..." : body;
            msg.append(preview);
        } else {
            msg.append("详见 GitHub Release 页面");
        }
        sd.body.addView(createInfoCard(msg.toString()));

        if (apkUrl != null && !apkUrl.isEmpty()) {
            // 有 APK 直链:应用内下载 + 浏览器下载备选
            Button btnDownload = createDialogButton("自动下载", true);
            btnDownload.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    dRef[0].dismiss();
                    downloadAndInstallApk(apkUrl, tag);
                }
            });
            sd.buttons.addView(btnDownload);

            Button btnBrowser = createDialogButton("浏览器下载", false);
            btnBrowser.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    dRef[0].dismiss();
                    // 尝试直接打开 APK 下载链接,浏览器通常有更好的证书支持
                    Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(apkUrl));
                    try {
                        startActivity(browserIntent);
                    } catch (Exception e) {
                        // 如果打不开 APK 链接,打开 Release 页面
                        Intent releaseIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(releaseUrl));
                        startActivity(releaseIntent);
                    }
                }
            });
            sd.buttons.addView(btnBrowser);
        } else {
            // 无 APK 直链,跳转到 Release 页面
            Button btnDetails = createDialogButton("查看详情", true);
            btnDetails.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(releaseUrl));
                    startActivity(browserIntent);
                }
            });
            sd.buttons.addView(btnDetails);
        }

        Button btnLater = createDialogButton("以后再说", false);
        btnLater.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { dRef[0].dismiss(); }
        });
        sd.buttons.addView(btnLater);

        showDialogFull(sd.dialog);
    }

    // ==================== 应用内下载更新 ====================

    /** 下载进度对话框 */
    private Dialog downloadDialog;
    /** 进度条 */
    private ProgressBar downloadProgress;
    /** 进度文字 */
    private TextView downloadPercent;
    /** 下载状态文字 */
    private TextView downloadStatus;
    /** 下载线程(用于取消) */
    private volatile Thread downloadThread;
    private volatile boolean downloadCancelled = false;

    /**
     * 应用内下载 APK 并显示进度条,下载完成后自动弹出安装
     * @param apkUrl APK 下载地址
     * @param versionTag 版本标签(用于文件名)
     */
    private void downloadAndInstallApk(final String apkUrl, final String versionTag) {
        downloadCancelled = false;

        // 创建下载进度对话框
        SubDialog sd = createSubDialog("⬇", "正在下载更新");

        // 进度信息卡片
        LinearLayout progressLayout = new LinearLayout(this);
        progressLayout.setOrientation(LinearLayout.VERTICAL);
        progressLayout.setPadding(28, 24, 28, 24);

        downloadStatus = new TextView(this);
        downloadStatus.setText("正在连接服务器...");
        downloadStatus.setTextColor(getResources().getColor(R.color.text_primary));
        downloadStatus.setTextSize(15);
        downloadStatus.setLineSpacing(4, 1);
        progressLayout.addView(downloadStatus);

        // 间距
        View spacer = new View(this);
        spacer.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 16));
        progressLayout.addView(spacer);

        // 进度条
        downloadProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        downloadProgress.setMax(100);
        downloadProgress.setProgress(0);
        downloadProgress.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        progressLayout.addView(downloadProgress);

        // 百分比文字
        downloadPercent = new TextView(this);
        downloadPercent.setText("0%");
        downloadPercent.setTextColor(getResources().getColor(R.color.text_secondary));
        downloadPercent.setTextSize(14);
        downloadPercent.setGravity(android.view.Gravity.CENTER);
        downloadPercent.setPadding(0, 8, 0, 0);
        progressLayout.addView(downloadPercent);

        sd.body.addView(progressLayout);

        // 取消按钮
        Button btnCancel = createDialogButton("取消下载", false);
        btnCancel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                downloadCancelled = true;
                if (downloadThread != null) {
                    downloadThread.interrupt();
                }
                if (downloadDialog != null && downloadDialog.isShowing()) {
                    downloadDialog.dismiss();
                }
                Toast.makeText(MainActivity.this, "下载已取消", Toast.LENGTH_SHORT).show();
            }
        });
        sd.buttons.addView(btnCancel);

        downloadDialog = sd.dialog;
        showDialogFull(downloadDialog);

        // 后台线程下载
        downloadThread = new Thread(new Runnable() {
            @Override
            public void run() {
                // 构建下载地址列表: 直连 + 镜像
                String[] urls = new String[GITHUB_DL_MIRRORS.length];
                for (int i = 0; i < GITHUB_DL_MIRRORS.length; i++) {
                    urls[i] = GITHUB_DL_MIRRORS[i] + apkUrl;
                }

                final File apkFile;
                Exception lastError = null;
                boolean downloadSuccess = false;

                // 保存到外部存储(安卓 4.2.2 兼容)
                File downloadDir = android.os.Environment.getExternalStoragePublicDirectory(
                        android.os.Environment.DIRECTORY_DOWNLOADS);
                if (!downloadDir.exists()) {
                    downloadDir.mkdirs();
                }
                String fileName = "captiva-music-" + versionTag + ".apk";
                apkFile = new File(downloadDir, fileName);

                // 逐个尝试下载地址
                for (int mirrorIdx = 0; mirrorIdx < urls.length; mirrorIdx++) {
                    if (downloadCancelled) break;

                    final String tryUrl = urls[mirrorIdx];
                    final String sourceName = mirrorIdx == 0 ? "直连" : ("镜像" + mirrorIdx);

                    // 更新状态文字
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (downloadStatus != null) {
                                downloadStatus.setText("正在连接(" + sourceName + ")...");
                            }
                            if (downloadProgress != null) {
                                downloadProgress.setProgress(0);
                            }
                            if (downloadPercent != null) {
                                downloadPercent.setText("0%");
                            }
                        }
                    });

                    HttpURLConnection conn = null;
                    InputStream is = null;
                    java.io.FileOutputStream fos = null;
                    try {
                        Log.d(TAG, "尝试下载(" + sourceName + "): " + tryUrl);
                        conn = createConnection(tryUrl);
                        conn.setRequestMethod("GET");
                        conn.setRequestProperty("Accept", "application/octet-stream");
                        conn.setConnectTimeout(30000);
                        conn.setReadTimeout(30000);
                        conn.setInstanceFollowRedirects(true);
                        conn.connect();

                        final int responseCode = conn.getResponseCode();
                        if (responseCode != 200) {
                            Log.w(TAG, sourceName + " 服务器错误: " + responseCode);
                            lastError = new Exception("服务器错误(" + responseCode + ")");
                            continue; // 试下一个镜像
                        }

                        final int totalSize = conn.getContentLength();
                        is = conn.getInputStream();
                        fos = new java.io.FileOutputStream(apkFile);

                        byte[] buffer = new byte[8192];
                        int bytesRead;
                        long totalRead = 0;
                        int lastPercent = -1;

                        while ((bytesRead = is.read(buffer)) != -1) {
                            if (downloadCancelled) {
                                fos.close();
                                fos = null;
                                apkFile.delete();
                                return;
                            }
                            fos.write(buffer, 0, bytesRead);
                            totalRead += bytesRead;

                            if (totalSize > 0) {
                                final int percent = (int) (totalRead * 100 / totalSize);
                                if (percent != lastPercent) {
                                    lastPercent = percent;
                                    final long currentRead = totalRead;
                                    runOnUiThread(new Runnable() {
                                        @Override
                                        public void run() {
                                            if (downloadProgress != null) {
                                                downloadProgress.setProgress(percent);
                                            }
                                            if (downloadPercent != null) {
                                                downloadPercent.setText(percent + "%");
                                            }
                                            if (downloadStatus != null) {
                                                String sizeStr = formatSize(currentRead) + " / " + formatSize(totalSize);
                                                downloadStatus.setText("正在下载(" + sourceName + "): " + sizeStr);
                                            }
                                        }
                                    });
                                }
                            }
                        }

                        fos.flush();
                        fos.close();
                        fos = null;

                        Log.d(TAG, sourceName + " 下载成功: " + apkFile.length() + " bytes");
                        downloadSuccess = true;
                        break; // 成功,不再试其他镜像

                    } catch (Exception e) {
                        Log.w(TAG, sourceName + " 下载失败: " + e.getMessage());
                        lastError = e;
                        // 删除可能的不完整文件
                        if (apkFile.exists()) {
                            apkFile.delete();
                        }
                    } finally {
                        if (fos != null) { try { fos.close(); } catch (Exception e) {} }
                        if (is != null) { try { is.close(); } catch (Exception e) {} }
                        if (conn != null) conn.disconnect();
                    }
                }

                if (downloadCancelled) return;

                if (!downloadSuccess) {
                    final Exception finalErr = lastError;
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (downloadDialog != null && downloadDialog.isShowing()) {
                                downloadDialog.dismiss();
                            }
                            String msg = finalErr != null ? finalErr.getMessage() : "未知错误";
                            if (finalErr != null && isSslError(finalErr)) {
                                // SSL 错误:提示用浏览器下载(浏览器证书通常更新)
                                new AlertDialog.Builder(MainActivity.this)
                                        .setTitle("自动下载失败")
                                        .setMessage("系统 SSL/TLS 版本过旧,已尝试跳过证书验证但仍无法连接。\n\n建议:点击「浏览器下载」用系统浏览器打开下载链接,或用手机下载后传到车机。")
                                        .setPositiveButton("浏览器下载", new DialogInterface.OnClickListener() {
                                            @Override
                                            public void onClick(DialogInterface dialog, int which) {
                                                Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(apkUrl));
                                                try {
                                                    startActivity(browserIntent);
                                                } catch (Exception e) {
                                                    Toast.makeText(MainActivity.this,
                                                            "无法打开浏览器: " + e.getMessage(),
                                                            Toast.LENGTH_SHORT).show();
                                                }
                                            }
                                        })
                                        .setNegativeButton("取消", null)
                                        .show();
                            } else {
                                Toast.makeText(MainActivity.this,
                                        "下载失败(所有镜像均不可用):\n" + msg,
                                        Toast.LENGTH_LONG).show();
                            }
                        }
                    });
                    return;
                }

                // 下载完成,关闭进度对话框,弹出安装
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (downloadDialog != null && downloadDialog.isShowing()) {
                            downloadDialog.dismiss();
                        }
                        // Activity 已销毁则不继续
                        if (isFinishing() || isDestroyed()) {
                            return;
                        }
                        // 验证文件完整性
                        if (apkFile == null || !apkFile.exists()) {
                            Toast.makeText(MainActivity.this, "下载文件不存在,安装失败",
                                    Toast.LENGTH_SHORT).show();
                            return;
                        }
                        if (!apkFile.canRead()) {
                            apkFile.setReadable(true, false);
                        }
                        Log.d(TAG, "下载完成: " + apkFile.getAbsolutePath()
                                + " 大小: " + apkFile.length() + " bytes");
                        Toast.makeText(MainActivity.this, "下载完成,正在安装...",
                                Toast.LENGTH_SHORT).show();
                        try {
                            installApk(apkFile);
                        } catch (Exception e) {
                            Log.e(TAG, "安装失败: " + e.getMessage(), e);
                            Toast.makeText(MainActivity.this,
                                    "自动安装失败,请手动安装\n文件: " + apkFile.getAbsolutePath(),
                                    Toast.LENGTH_LONG).show();
                        }
                    }
                });
            }
        }, "ApkDownload");
        downloadThread.start();
    }

    /** 弹出系统安装器安装 APK(兼容安卓 4.2.2 ~ 12+) */
    private void installApk(File apkFile) {
        Log.d(TAG, "开始安装 APK: " + apkFile.getAbsolutePath()
                + " (" + apkFile.length() + " bytes)");
        Intent intent = new Intent(Intent.ACTION_VIEW);
        Uri apkUri;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            // 安卓 7.0+ 禁止 file:// URI 跨进程传递,必须用 FileProvider
            apkUri = FileProvider.getUriForFile(this,
                    getPackageName() + ".fileprovider", apkFile);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            Log.d(TAG, "使用 FileProvider URI: " + apkUri);
        } else {
            // 安卓 4.2.2 ~ 6.0 可直接用 file://
            apkUri = Uri.fromFile(apkFile);
            Log.d(TAG, "使用 file:// URI: " + apkUri);
        }
        intent.setDataAndType(apkUri, "application/vnd.android.package-archive");
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        // 检查系统是否有安装器能处理此 Intent
        if (intent.resolveActivity(getPackageManager()) == null) {
            Log.e(TAG, "系统未找到 APK 安装器");
            throw new RuntimeException("系统未找到 APK 安装器");
        }
        startActivity(intent);
    }

    /** 格式化文件大小 */
    private String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
    }

    // ==================== 均衡器快捷切换 ====================

    /** 更新EQ按钮显示当前模式名 */
    private void updateEqButtonText(String eqPreset) {
        if (btnEq == null) return;
        String preset = eqPreset;
        if (preset == null || preset.isEmpty()) {
            EqualizerManager eqMgr = MusicDataHolder.getInstance().getEqualizerManager();
            if (eqMgr != null) {
                preset = eqMgr.getActivePreset();
            }
        }
        if (preset == null || preset.isEmpty()) {
            preset = "关闭";
        }
        btnEq.setText("EQ:" + preset);
    }

    /**
     * 弹出均衡器预设快速切换弹窗
     * 显示所有预设(内置+自定义),点击即切换
     * 含"进入均衡器"入口
     */
    private void showEqualizerQuickSwitch() {
        EqualizerManager eqMgr = MusicDataHolder.getInstance().getEqualizerManager();
        if (eqMgr == null) {
            Toast.makeText(this, "均衡器未初始化", Toast.LENGTH_SHORT).show();
            return;
        }

        // 获取所有预设名(内置 + 自定义)
        List<String> allPresets = eqMgr.getAllPresetNames();
        // 在末尾添加"进入均衡器"和"绑定当前歌曲"选项
        List<String> items = new ArrayList<>(allPresets);
        items.add("进入均衡器调节");

        // 检查当前歌曲是否有绑定EQ
        MusicBean currentSong = (service != null) ? service.getCurrentMusic() : null;
        String songEq = (currentSong != null) ? eqMgr.getSongEqPreset(currentSong) : null;
        if (currentSong != null) {
            if (songEq != null) {
                items.add("取消当前歌曲EQ绑定(当前: " + songEq + ")");
            } else {
                items.add("绑定当前EQ到此歌曲");
            }
        }

        final String[] itemsArray = items.toArray(new String[0]);
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("均衡器模式" + (currentSong != null && songEq != null
                ? "  (歌曲已绑定: " + songEq + ")" : ""));
        // 自定义适配器,加大列表项字体(车机电阻屏优化)
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(this,
                android.R.layout.simple_list_item_1, itemsArray) {
            @Override
            public View getView(int position, View convertView, android.view.ViewGroup parent) {
                View view = super.getView(position, convertView, parent);
                if (view instanceof TextView) {
                    TextView tv = (TextView) view;
                    tv.setTextSize(20f);
                    tv.setPadding(48, 32, 48, 32);
                }
                return view;
            }
        };
        builder.setAdapter(adapter, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                if (which < allPresets.size()) {
                    // 选择了预设模式
                    String preset = allPresets.get(which);
                    eqMgr.applyPreset(preset);
                    // 如果有当前歌曲,也更新绑定(如果之前有绑定的话保持绑定,否则只改全局)
                    updateEqButtonText(preset);
                    Toast.makeText(MainActivity.this,
                            "均衡器: " + preset, Toast.LENGTH_SHORT).show();
                } else if (itemsArray[which].startsWith("进入均衡器")) {
                    // 进入均衡器界面
                    openEqualizer();
                } else if (itemsArray[which].startsWith("绑定当前EQ")) {
                    // 绑定当前EQ到当前歌曲
                    if (currentSong != null) {
                        String currentActive = eqMgr.getActivePreset();
                        eqMgr.bindSongEq(currentSong, currentActive);
                        Toast.makeText(MainActivity.this,
                                "已将 \"" + currentActive + "\" 绑定到此歌曲",
                                Toast.LENGTH_SHORT).show();
                    }
                } else if (itemsArray[which].startsWith("取消当前歌曲")) {
                    // 取消绑定
                    if (currentSong != null) {
                        eqMgr.unbindSongEq(currentSong);
                        Toast.makeText(MainActivity.this,
                                "已取消此歌曲的EQ绑定", Toast.LENGTH_SHORT).show();
                        updateEqButtonText(eqMgr.getActivePreset());
                    }
                }
            }
        });
        builder.show();
    }

    // ==================== 服务器状态显示 ====================

    /** 更新服务器状态显示 */
    private void updateServerStatusDisplay(ServerStatusMonitor.Status status, String message) {
        setServerStatusDot(status, message);
    }

    /**
     * 给服务器指示圆点上色。
     *
     * 需求:只显示一个小圆点、不带文字 ——
     *   绿 = 已连接,橙 = 连接中,红 = 未连接。
     * 其中"重连倒计时(RETRYING)"同属连接中语义,统一用橙色;
     * "未配置服务器(OFFLINE)"同属未连接语义,统一用红色。
     *
     * message 不再直接显示,但会写进 contentDescription ——
     * 这样重连倒计时等文字信息在无障碍/调试视图里仍然可查。
     */
    private void setServerStatusDot(ServerStatusMonitor.Status status, String message) {
        if (vServerStatus == null || statusDotDrawable == null) {
            return;
        }
        int color;
        String desc;
        switch (status) {
            case CONNECTED:
                color = ContextCompat.getColor(this, R.color.server_status_connected);
                desc = "已连接";
                break;
            case CONNECTING:
                color = ContextCompat.getColor(this, R.color.server_status_connecting);
                desc = "连接中";
                break;
            case RETRYING:
                color = ContextCompat.getColor(this, R.color.server_status_connecting);
                desc = (message != null && !message.isEmpty()) ? message : "连接中";
                break;
            case DISCONNECTED:
                color = ContextCompat.getColor(this, R.color.server_status_disconnected);
                desc = "未连接";
                break;
            case OFFLINE:
            default:
                color = ContextCompat.getColor(this, R.color.server_status_disconnected);
                desc = "未连接";
                break;
        }
        if (color != lastStatusDotColor) {
            lastStatusDotColor = color;
            statusDotDrawable.setColor(color);
            vServerStatus.invalidate();
        }
        if (desc != null && !desc.equals(lastStatusDotDesc)) {
            lastStatusDotDesc = desc;
            vServerStatus.setContentDescription("服务器" + desc);
        }
    }

    // ==================== 音乐加载 ====================

    private boolean hasStoragePermission() {
        return ContextCompat.checkSelfPermission(this,
                android.Manifest.permission.READ_EXTERNAL_STORAGE)
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_STORAGE) {
            if (grantResults.length > 0 && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                autoPlayPending = navidromeConfig.isAutoPlay();
                loadMusic();
            } else {
                Toast.makeText(this, "需要存储权限才能读取音乐", Toast.LENGTH_LONG).show();
            }
        }
    }

    /** 快速统计预估总数(优先显示) */
    private int estimatedCount = 0;

    /**
     * 加载音乐(扫描同步目录)
     * 1. 先从本地缓存加载(秒开,完全不读U盘)
     * 2. 无缓存时后台扫描 MediaStore(不阻塞主线程)
     * 3. 后台自动同步服务器新歌(不阻塞 UI)
     *
     * 注意:缓存加载和 MediaStore 扫描都在后台线程,避免阻塞主线程导致点击无响应
     */
    private void loadMusic() {
        DownloadDiag.log("[BUILD] 321 diag-enabled (含可见行 dump + 模式切换清池滚顶)");
        final String syncPath = navidromeConfig.getCloudDir();
        // 本地模式扫描目录(可在设置中自定义;未设置时 = 根目录/本地文件夹)
        final String localDir = navidromeConfig.getLocalScanPath();

        // 性能日志:仅调试版(BuildConfig.DEBUG)开启,自动写入 perf_log.txt 用于分析卡顿
        if (BuildConfig.DEBUG) {
            PerfLogger.init(this, syncPath);
            handler.post(logFlushTask);
            PerfLogger.log("loadMusic 开始, syncPath=" + syncPath);
        }

        // 缓存诊断日志:正式版也开(与 perf_log.txt 同目录,便于车机上直接查看)
        CacheDebugLog.init(this, syncPath);
        CacheDebugLog.log("loadMusic 开始, 模式=" + (localOnlyMode ? "本地" : "云端")
                + " 云端目录=" + syncPath
                + " 播放自动缓存=" + navidromeConfig.isAutoCacheOnPlay()
                + " 缓存上限MB=" + navidromeConfig.getAutoCacheMaxMb()
                + " 服务器类型=" + navidromeConfig.getServerType());

        // 显示加载中提示
        tvEmpty.setText("正在加载音乐...");
        tvEmpty.setVisibility(View.VISIBLE);

        // 后台线程执行:缓存加载 + 排序 + MediaStore 扫描
        new Thread(new Runnable() {
            @Override
            public void run() {
                // 0. 云端模式:列表以云端歌单(SongCache 离线缓存)为准,
                //    每首歌按固定本地路径判断是否在本地,决定本地播还是联网播。
                //    列表只有云端一份枚举,重复在结构上不存在。
                //    本地模式(localOnlyMode)跳过此分支,走下方本地扫描流程。
                if (!localOnlyMode) {
                final String serverType = navidromeConfig.getServerType();
                List<MusicBean> cloudList = buildCloudDrivenList(serverType, syncPath);
                if (cloudList != null && !cloudList.isEmpty()) {
                    java.util.Collections.sort(cloudList, MusicTitleComparator.INSTANCE);
                    // 后台预热 getCanonicalPath:已下载本地播的云端歌 setData/dedupe 主线程会逐首取路径键,
                    // 不预热则主线程磁盘 I/O 掉帧(与 applySourceMode 云端分支同一根因)
                    warmKeys(cloudList);
                    final List<MusicBean> finalList = cloudList;
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            musicList.clear();
                            musicList.addAll(finalList);
                            dedupeMusicList();
                            adapter.setData(musicList);
                            updateCount();
                            if (musicList.isEmpty()) {
                                tvEmpty.setVisibility(View.VISIBLE);
                                tvEmpty.setText("未找到音乐\n请在设置中配置服务器并同步");
                            } else {
                                tvEmpty.setVisibility(View.GONE);
                                if (service != null && !service.isPlaying()) {
                                    int lastIndex = navidromeConfig.getLastPlayIndex();
                                    if (lastIndex < 0 || lastIndex >= musicList.size()) {
                                        lastIndex = 0;
                                    }
                                    service.setPlayList(musicList, lastIndex);
                                    if (autoPlayPending && !service.isPlaying()) {
                                        autoPlayPending = false;
                                        int lastPos = navidromeConfig.getLastPlayPosition();
                                        service.playIndexWithSeek(lastIndex, lastPos);
                                    }
                                }
                            }
                            // 后台刷新云端缓存(发现新歌);下载完成的歌下次重开即转为本地播
                            startBackgroundSync();
                        }
                    });
                    return;
                }
                } // end if (!localOnlyMode)

                // ---- 本地模式,或云端列表不可用(无缓存/未同步/未配置):走本地扫描 ----
                // 0. 优先从本地缓存加载(秒开,完全不读U盘)
                List<MusicBean> cachedList = localMusicCache.load();
                if (cachedList != null && !cachedList.isEmpty()) {
                    // 排序(后台线程,不阻塞UI)
                    java.util.Collections.sort(cachedList, MusicTitleComparator.INSTANCE);

                    final List<MusicBean> finalList = cachedList;
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            musicList.clear();
                            musicList.addAll(finalList);
                            dedupeMusicList();
                            localMusicCache.forceSaveAsync(musicList);
                            adapter.setData(musicList);
                            updateCount();
                            tvEmpty.setVisibility(View.GONE);
                            // 设置播放列表(service 可能还没绑定,onServiceConnected 会再设一次)
                            if (service != null && !service.isPlaying()) {
                                int lastIndex = navidromeConfig.getLastPlayIndex();
                                if (lastIndex < 0 || lastIndex >= musicList.size()) {
                                    lastIndex = 0;
                                }
                                service.setPlayList(musicList, lastIndex);
                                if (autoPlayPending && !service.isPlaying()) {
                                    autoPlayPending = false;
                                    int lastPos = navidromeConfig.getLastPlayPosition();
                                    service.playIndexWithSeek(lastIndex, lastPos);
                                }
                            }
                            // 后台预提取封面
                            int coverSize = (int) getResources().getDimension(R.dimen.cover_size_list);
                            CoverLoader.getInstance().preloadAllCovers(musicList, coverSize);
                            // 启动后台服务器同步
                            startBackgroundSync();
                        }
                    });
                    return;
                }

                // 1. 无缓存:用 MediaStore 快速加载(后台线程,不阻塞UI)
                final List<MusicBean> quickList = MusicScanner.scanMediaStoreOnly(MainActivity.this, localDir);

                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        musicList.clear();
                        musicList.addAll(quickList);
                        dedupeMusicList();
                        adapter.setData(musicList);
                        updateCount();

                        if (musicList.isEmpty()) {
                            tvEmpty.setVisibility(View.VISIBLE);
                            tvEmpty.setText("未找到音乐\n请在设置中配置服务器并同步");
                        } else {
                            tvEmpty.setVisibility(View.GONE);
                            if (service != null && !service.isPlaying()) {
                                int lastIndex = navidromeConfig.getLastPlayIndex();
                                if (lastIndex < 0 || lastIndex >= musicList.size()) {
                                    lastIndex = 0;
                                }
                                service.setPlayList(musicList, lastIndex);
                                if (autoPlayPending && !service.isPlaying()) {
                                    autoPlayPending = false;
                                    int lastPos = navidromeConfig.getLastPlayPosition();
                                    service.playIndexWithSeek(lastIndex, lastPos);
                                }
                            }
                        }

                        // 2. 后台递归扫描补全 + 同步
                        backgroundScanAndMerge(localDir, quickList, false);
                    }
                });
            }
        }, "LoadMusic").start();
    }

    /**
     * 云端为主构建列表(纯云端镜像):读按服务器隔离的 SongCache(云端歌单离线缓存),
     * 每首歌按固定本地路径判断是否在本地,决定"本地播"还是"联网播"。
     *
     * 为什么能根治重复:列表只有云端一份枚举,同一首歌不可能出现两条;
     * 跨文件夹 / 重命名都不影响,因为云端条目只认一个规范本地路径。
     * 本地扫描退化为回退(见 loadMusic),不再作为列表来源。
     *
     * @return 云端列表(已按本地可用性设好 network/data);云端列表不可用时返回 null
     *         (调用方应回退到本地扫描,保证界面不空白)。
     */
    private List<MusicBean> buildCloudDrivenList(String serverType, String syncPath) {
        final long t0 = System.currentTimeMillis();
        SongCache cloudCache = new SongCache(this, serverType);
        List<MusicBean> cloud = cloudCache.load();
        final long tLoad = System.currentTimeMillis();
        if (cloud == null || cloud.isEmpty()) {
            CacheDebugLog.log("构建云端列表: 读云端缓存=" + (tLoad - t0) + "ms -> 列表为空,回退本地");
            return null;
        }
        // 一次性遍历同步目录,收集「真实存在且 >1024 字节」的音频文件绝对路径集合。
        // 旧实现是对每一首云端歌都做 localPathKey(getCanonicalPath,磁盘 I/O) + exists() + length()
        // —— 约 810 首就要 ~1620 次独立 stat,在车机/USB 存储上是「切换成云端很卡」的根因。
        // 改为:单次递归遍历(遍历成本只与「已存在文件数」成正比,而非歌曲总数) + 每首 O(1) 查表,
        // 把 N 次散落 stat 压成一次顺序遍历,列表秒出。
        Set<String> localFiles = collectExistingLocalPaths(syncPath);
        final long tWalk = System.currentTimeMillis();
        final boolean hasSyncDir = !localFiles.isEmpty();
        for (MusicBean b : cloud) {
            if (b == null) {
                continue;
            }
            // 期望的本地固定路径(与 MusicSyncManager.buildLocalFile 命名规则一致,且不触发任何磁盘 I/O)
            String expected = MusicSyncManager.buildLocalFile(b, syncPath).getAbsolutePath();
            if (hasSyncDir && localFiles.contains(expected)) {
                // 本地已下载:改本地播放,用真实文件路径;
                // 清掉服务端 uri(MusicService 会优先用 uri,可能误指向服务端地址)
                b.setNetwork(false);
                b.setData(expected);
                b.setUri(null);
            } else {
                // 未下载:保持联网播放(streamUrl 已在缓存中)
                b.setNetwork(true);
            }
        }
        final long tBind = System.currentTimeMillis();
        CacheDebugLog.log("构建云端列表: 读云端缓存=" + (tLoad - t0) + "ms"
                + (SongCache.lastLoadFromMemory ? "(内存命中)" : "(重新解析)")
                + " 遍历同步目录=" + (tWalk - tLoad) + "ms"
                + " 组装=" + (tBind - tWalk) + "ms"
                + " 合计=" + (tBind - t0) + "ms 条数=" + cloud.size()
                + " 本地已有=" + localFiles.size());
        return cloud;
    }

    /**
     * 单次遍历同步目录,收集所有「存在且 >1024 字节」的文件的绝对路径。
     * 用与 buildLocalFile 相同的绝对路径形式作为 key,使 buildCloudDrivenList 里
     * 每首歌的 expected 路径能直接 O(1) 命中,从而避免逐首 stat。
     * 成本仅与磁盘上「实际存在的文件数」成正比,而非云端歌曲总数。
     */
    private static Set<String> collectExistingLocalPaths(String syncPath) {
        Set<String> set = new HashSet<String>();
        if (syncPath == null || syncPath.isEmpty()) {
            return set;
        }
        File root = new File(syncPath);
        if (!root.exists() || !root.isDirectory()) {
            return set;
        }
        collectAudioInto(set, root);
        return set;
    }

    private static void collectAudioInto(Set<String> set, File dir) {
        File[] files = dir.listFiles();
        if (files == null) {
            return;
        }
        for (File f : files) {
            if (f.isDirectory()) {
                if (!f.getName().startsWith(".")) {
                    collectAudioInto(set, f);
                }
            } else if (f.length() > 1024) {
                // 仅收集绝对路径;歌曲侧用 buildLocalFile(...).getAbsolutePath() 同形式比较,保证一致
                set.add(f.getAbsolutePath());
            }
        }
    }

    /**
     * 更新本地/云端切换按钮文案。
     * 注意:按需求「取消高亮变色」,按钮背景不再随模式切换,始终是普通底色(bg_btn),
     * 仅文字在「本地 / 云端」之间切换,避免本地模式时按钮变蓝造成误导。
     */
    private void updateSourceToggleUi() {
        btnSourceToggle.setBackgroundResource(R.drawable.bg_btn);
        btnSourceToggle.setText(localOnlyMode ? "本地" : "云端");
    }

    /**
     * 顶部「服务器状态区」的可见性,跟随列表模式。
     *
     * 本地模式:列表全部是本地歌 —— 「服务器连接圆点」和「列表已更新」都没有意义,
     * 一并隐藏(用户截图反馈:切到本地后右上角还挂着绿点、左上角还留着"列表已更新")。
     * 同时隐藏的 tvSyncStatus 兼作手动更新列表的入口,本地模式的刷新入口是
     * 「设置里的目录」与 FileObserver 自动监听,不需要顶栏入口。
     *
     * 云端模式:圆点恢复显示;tvSyncStatus 若不在同步中则恢复成「列表已更新」
     * (它兼作手动更新入口,切回云端后入口要回来),同步中的文案由同步流程自己管理。
     */
    private void updateServerStatusAreaVisibility() {
        if (flServerStatus != null) {
            flServerStatus.setVisibility(localOnlyMode ? View.GONE : View.VISIBLE);
        }
        if (tvSyncStatus == null) {
            return;
        }
        if (localOnlyMode) {
            tvSyncStatus.setVisibility(View.GONE);
        } else if (!isAutoSyncing && tvSyncStatus.getVisibility() != View.VISIBLE) {
            tvSyncStatus.setVisibility(View.VISIBLE);
            tvSyncStatus.setText("列表已更新");
        }
    }

    /**
     * 本地/云端切换入口(按钮点击)。
     * 单飞:若上一次切换仍在后台构建/刷新中,只更新 localOnlyMode(最终目标),不重复开线程;
     * 当前切换完成后会校验最终目标,必要时自动补一次。这样快速连点只会产生「最终模式」的
     * 一次重建,杜绝并发扫描/多次 setData 卡顿。
     */
    private void toggleSource() {
        final long t0 = System.currentTimeMillis();
        final boolean toLocal = !localOnlyMode;
        localOnlyMode = toLocal;
        navidromeConfig.setLocalMode(toLocal);
        // 收藏来源随模式切换:云端看服务器收藏、本地看本机收藏。
        // 不重置的话,从云端收藏夹切到本地时仍会用"服务器收藏 ID"去过滤本地歌,
        // 结果就是列表空空如也(本地歌大多没有对应的服务器收藏 ID)。
        adapter.setCloudStarredIds(null);
        adapter.setFavoritesMode(false);
        if (favoritesOnly) {
            favoritesOnly = false;
            btnFavorites.setBackgroundResource(R.drawable.bg_btn);
        }
        updateSourceToggleUi();
        // 本地模式:隐藏右上角服务器状态圆点与「列表已更新」(全部是本地歌,均无意义)
        updateServerStatusAreaVisibility();
        // 本地模式隐藏来源状态点(全部是本地歌,点无信息量)
        // notify=false:紧接着 applySourceMode 就会整表 setData,新数据自带最新状态;
        // 这里再 notifyDataSetChanged() 只会在下一帧白重绑一次(实测 ~180ms),拖长上屏延迟。
        // 例外见「云端不可用回退本地」分支 —— 那条路不重建列表,需显式刷新。
        final long t1 = System.currentTimeMillis();
        adapter.setShowSourceDot(!toLocal, false);
        final long t2 = System.currentTimeMillis();
        syncLocalDirObserver();
        final long t3 = System.currentTimeMillis();
        // 这三段都在主线程上,且发生在遮罩「上屏」之前 —— 若某段大,
        // 用户看到的就是"点完先僵一下,加载文案才出来"。
        // 注意 notifyDataSetChanged 的真正成本落在下一次 traversal,这里只记调用耗时。
        CacheDebugLog.log("切换UI侧: 按钮+持久化=" + (t1 - t0) + "ms"
                + " setShowSourceDot=" + (t2 - t1) + "ms"
                + " 目录监听=" + (t3 - t2) + "ms"
                + " 合计=" + (t3 - t0) + "ms");
        // 连点防抖:窗口内的连发点击合并为一次重建(见 SOURCE_SWITCH_DEBOUNCE_MS 说明)。
        // 只合并"重建",按钮文案 / 状态区显隐 / 持久化上面都已完成,所以点按手感不变。
        handler.removeCallbacks(pendingSourceSwitch);
        handler.postDelayed(pendingSourceSwitch, SOURCE_SWITCH_DEBOUNCE_MS);
    }

    /**
     * 一次来源切换在主线程刷新完成后调用。
     * 若期间又有点击(本地变量 localOnlyMode 已是最新目标)导致 appliedToLocal 与之不符,
     * 自动补一次切换,确保最终停在 localOnlyMode 对应列表。
     */
    private void finishSourceSwitch(boolean appliedToLocal) {
        sourceSwitchInFlight = false;
        if (appliedToLocal != localOnlyMode) {
            sourceSwitchInFlight = true;
            applySourceMode(localOnlyMode);   // 新一轮:遮罩保持(或重新排队),由新列表就绪时撤掉
        } else {
            // 已收敛到最终模式:兜底撤掉遮罩。
            // 云端不可用回退本地、本地扫描为空保留缓存等路径不会走 applyMusicListCore,
            // 不在这里撤的话会一直停在「正在加载音乐...」。
            hideLoadingMask();
        }
    }

    /**
     * 显示列表加载遮罩(可任意线程调用,内部切主线程)。
     * 延迟 {@link #LOADING_MASK_DELAY_MS} 才真正显示:本地缓存命中这类秒开场景
     * 在延迟内就被 {@link #hideLoadingMask()} 撤掉排队任务,不会出现"闪一下"。
     */
    private void showLoadingMask() {
        handler.removeCallbacks(showLoadingMaskTask);
        listLoadStartTs = System.currentTimeMillis();
        handler.postDelayed(showLoadingMaskTask, LOADING_MASK_DELAY_MS);
    }

    /** 撤掉列表加载遮罩(可任意线程调用,内部切主线程) */
    private void hideLoadingMask() {
        handler.removeCallbacks(showLoadingMaskTask);   // 还没显示就被撤:直接取消排队
        final long now = System.currentTimeMillis();
        final boolean wasActive = loadingMaskActive;
        if (!wasActive) {
            // 本轮重建没走到"需要遮罩"的程度(< 阈值就结束了),记一笔便于对照车机实际表现
            if (listLoadStartTs > 0) {
                CacheDebugLog.log("列表加载遮罩: 未显示(列表就绪仅 "
                        + (now - listLoadStartTs) + "ms < " + LOADING_MASK_DELAY_MS + "ms)");
                listLoadStartTs = 0;
            }
        } else {
            loadingMaskActive = false;
            if (listLoadStartTs > 0) {
                // 「上屏延迟」= 排队显示到真正可见的间隔。阈值只有 150ms,若远大于它,
                // 说明点完按钮后主线程被别的重活占住,遮罩排不上队(会表现为"点完先僵一下")。
                CacheDebugLog.log("列表加载遮罩: 覆盖 " + (now - listMaskShownTs) + "ms"
                        + ", 上屏延迟 " + (listMaskShownTs - listLoadStartTs) + "ms(阈值 " + LOADING_MASK_DELAY_MS + "ms)"
                        + ", 本轮列表重建全程 " + (now - listLoadStartTs) + "ms");
                listLoadStartTs = 0;
            }
        }
        // 无论走上面哪条分支都投一次主线程收尾。
        // 为什么要这样做:`hideLoadingMask` 允许后台线程调用(applyMusicListCore 在后台线程里),
        // 而 `loadingMaskActive` 与 `rvList` 的可见性都属于主线程视图状态 —— 两边一旦错开
        // (后台线程读到旧的 false 就 return),`rvList` 会永久停在 INVISIBLE:
        // 列表区一片纯黑,而且 INVISIBLE 不接收触摸,用户怎么点都不会自愈。
        // 所以这里不看标记、直接按 View 的真实状态做自愈。
        // 收尾动作只在"本分支真的盖过遮罩"或"确实自愈了"时做,避免给秒开的快路径
        // 白白多加一次 O(n) 的索引条重建(refreshIndexBar)。
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (loadingMaskActive) {
                    return;   // 新一轮遮罩正盖着,别抢
                }
                boolean healed = false;
                if (rvList != null && rvList.getVisibility() == View.INVISIBLE) {
                    rvList.setVisibility(View.VISIBLE);
                    healed = true;
                    DownloadDiag.log("[界面] 列表区被加载遮罩留在 INVISIBLE,已兜底恢复可见");
                }
                if (wasActive || healed) {
                    // 索引条按当前列表内容恢复(空列表/搜索态会保持隐藏)
                    if (sideIndexBar != null) refreshIndexBar();
                    // tvEmpty 正常由 applyMusicListCore 恢复;若走的是回退分支(云端不可用等),
                    // tvEmpty 还停在加载文案,这里兜底恢复成正确的空提示
                    if (tvEmpty != null && LOADING_MASK_TEXT.contentEquals(tvEmpty.getText())) {
                        restoreEmptyHintForCurrentMode();
                    }
                }
            }
        });
    }

    /**
     * 列表可见性兜底(只在主线程调用)。
     * 与 hideLoadingMask 里那段自愈逻辑的区别:这是在 Activity 回到前台时做的一次体检,
     * 覆盖"漏恢复发生在上一次会话里、且之后再没触发过 hideLoadingMask"的情况。
     */
    private void ensureListVisible() {
        if (rvList == null || loadingMaskActive) {
            return;
        }
        if (rvList.getVisibility() != View.INVISIBLE) {
            return;
        }
        rvList.setVisibility(View.VISIBLE);
        if (sideIndexBar != null) refreshIndexBar();
        if (tvEmpty != null && LOADING_MASK_TEXT.contentEquals(tvEmpty.getText())) {
            restoreEmptyHintForCurrentMode();
        }
        DownloadDiag.log("[界面] onResume 体检: 列表区曾被遮罩留在 INVISIBLE,已恢复可见");
    }

    /** 按当前模式与列表内容恢复 tvEmpty 空提示(遮罩兜底用,与 applyMusicListCore 的分支一致) */
    private void restoreEmptyHintForCurrentMode() {
        if (musicList.isEmpty()) {
            tvEmpty.setVisibility(View.VISIBLE);
            tvEmpty.setText(localOnlyMode
                    ? "本地目录没有找到歌曲\n把歌曲放进 音乐根目录/本地歌曲 即可"
                    : "未找到音乐\n请在设置中配置服务器并同步");
        } else {
            tvEmpty.setVisibility(View.GONE);
        }
    }

    /**
     * 按指定模式重建列表(后台构建,主线程刷新)。两个列表相互独立:
     * 云端模式 = 云端歌单全部(已下载的本地播,未下载的联网播),来源 SongCache;
     * 本地模式 = 优先从 local_songs.json 缓存秒开(避免每首 MediaMetadataRetriever 全量重扫
     *           导致切换卡顿数秒、列表迟迟不出),再后台扫描刷新发现新增文件。
     * 单飞由调用方(toggleSource)保证:同一时刻只有一个此方法的实例在跑。
     * 完成(或云端不可用时回退)后通过 finishSourceSwitch 收尾,以便收敛到最终目标模式。
     */
    private void applySourceMode(final boolean toLocal) {
        // 切换期间先排队遮罩:后台要读歌单 + 排序 + 去重,新列表回来才 setData,
        // 中间这段时间让用户看到「正在加载音乐...」,而不是点完没反应 / 旧列表一闪。
        showLoadingMask();
        final String syncPath = navidromeConfig.getCloudDir();
        final String localDir = navidromeConfig.getLocalScanPath();
        final String serverType = navidromeConfig.getServerType();
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                if (toLocal) {
                    // 本地模式:优先从 local_songs.json 缓存秒开(缓存即上一次成功扫描、已去重的完整结果),
                    // 避免每首 MediaMetadataRetriever 全量重扫导致切换卡顿数秒、列表迟迟不出。
                    final long tBg0 = System.currentTimeMillis();
                    List<MusicBean> cached = localMusicCache.load();
                    final long tCacheLoad = System.currentTimeMillis();
                    if (cached != null && !cached.isEmpty()) {
                        warmKeys(cached);            // 后台预热 getCanonicalPath,避免 setData 主线程掉帧
                        // 先秒开缓存(预览,不收尾);收尾交给扫描结果
                        final List<MusicBean> previewList = cached;
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                applyMusicListCore(previewList, true);
                            }
                        });
                        // 后台扫描刷新:扫描完成直接以新结果覆盖显示(不再仅在数量变化时刷新),
                        // 保证新增/替换/删除的歌即时出现;扫描为空(目录不存在/无音频)则保留缓存,不覆盖
                        final long tScan0 = System.currentTimeMillis();
                        List<MusicBean> fresh = MusicScanner.scanDirectoryOnly(MainActivity.this, localDir);
                        final long tScan = System.currentTimeMillis();
                        java.util.Collections.sort(fresh, MusicTitleComparator.INSTANCE);
                        final long tSort = System.currentTimeMillis();
                        List<MusicBean> deduped = dedupeList(fresh);
                        final long tDedupe = System.currentTimeMillis();
                        CacheDebugLog.log("切换重建[本地] 读列表缓存=" + (tCacheLoad - tBg0) + "ms"
                                + " 目录扫描=" + (tScan - tScan0) + "ms"
                                + " 排序=" + (tSort - tScan) + "ms"
                                + " 去重=" + (tDedupe - tSort) + "ms"
                                + " 后台合计=" + (tDedupe - tBg0) + "ms 条数=" + deduped.size());
                        if (!deduped.isEmpty()) {
                            final List<MusicBean> finalList = deduped;
                            runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    final long tUi0 = System.currentTimeMillis();
                                    applyMusicListCore(finalList, true);
                                    CacheDebugLog.log("切换重建[本地] 主线程刷新="
                                            + (System.currentTimeMillis() - tUi0) + "ms");
                                    finishSourceSwitch(true);
                                }
                            });
                        } else {
                            Log.w(TAG, "本地目录扫描为空,保留缓存列表");
                            finishSourceSwitch(true);   // 缓存已展示,视为已应用本地模式
                        }
                        return;
                    }
                    // 无缓存:全量扫描(首启 / 缓存损坏)
                    final long tScan0 = System.currentTimeMillis();
                    List<MusicBean> list = MusicScanner.scanDirectoryOnly(MainActivity.this, localDir);
                    final long tScan = System.currentTimeMillis();
                    java.util.Collections.sort(list, MusicTitleComparator.INSTANCE);
                    final long tSort = System.currentTimeMillis();
                    final List<MusicBean> finalList = dedupeList(list);
                    final long tDedupe = System.currentTimeMillis();
                    CacheDebugLog.log("切换重建[本地-无缓存] 目录扫描=" + (tScan - tScan0) + "ms"
                            + " 排序=" + (tSort - tScan) + "ms"
                            + " 去重=" + (tDedupe - tSort) + "ms"
                            + " 后台合计=" + (tDedupe - tBg0) + "ms 条数=" + finalList.size());
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            final long tUi0 = System.currentTimeMillis();
                            applyMusicListCore(finalList, true);
                            CacheDebugLog.log("切换重建[本地] 主线程刷新="
                                    + (System.currentTimeMillis() - tUi0) + "ms");
                            finishSourceSwitch(true);
                        }
                    });
                    return;
                }
                // 云端模式:读云端缓存构建列表(与本地无关)
                final long tBg0 = System.currentTimeMillis();
                List<MusicBean> list = buildCloudDrivenList(serverType, syncPath);
                final long tBuild = System.currentTimeMillis();
                if (list == null) {
                    // 云端不可用:回退本地模式
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            localOnlyMode = true;
                            updateSourceToggleUi();
                            // 回退到本地:服务器状态区同样收起
                            updateServerStatusAreaVisibility();
                            // 云端不可用回退本地:同样隐藏状态点。
                            // 这条路径**不重建列表**(沿用原来那份),所以 toggleSource 里那次
                            // 静默设值不会有机会被 setData 渲染出来,必须在这里显式刷一次。
                            adapter.setShowSourceDot(false, false);
                            adapter.notifyDataSetChanged();
                            Toast.makeText(MainActivity.this,
                                    "云端列表不可用(未同步或未配置服务器)", Toast.LENGTH_SHORT).show();
                            syncLocalDirObserver();
                            finishSourceSwitch(true);   // 实际已落到本地模式
                        }
                    });
                    return;
                }
                java.util.Collections.sort(list, MusicTitleComparator.INSTANCE);
                final long tSort = System.currentTimeMillis();
                List<MusicBean> deduped = dedupeList(list);
                final long tDedupe = System.currentTimeMillis();
                // 后台预热:已下载本地播的云端歌在 buildCloudDrivenList 里被置为 network=false + 本地路径,
                // 但其去重/身份键走 getIdentityKey = net_{streamId}(流式身份,无需磁盘路径),
                // 故预热 getIdentityKey(而非 getCachedKey)即可,避免对全部已下载歌做无谓的
                // getCanonicalPath 磁盘 I/O。
                // 注:「切换成云端很卡」的主因曾是 buildCloudDrivenList 对每首歌做 localPathKey(getCanonicalPath)
                // + exists() + length() 的 ~1620 次散落 stat,现已改为「单次目录遍历 + O(1) 查表」根治。
                warmKeys(deduped);
                final long tWarm = System.currentTimeMillis();
                CacheDebugLog.log("切换重建[云端] 构建列表=" + (tBuild - tBg0) + "ms"
                        + " 排序=" + (tSort - tBuild) + "ms"
                        + " 去重=" + (tDedupe - tSort) + "ms"
                        + " 预热=" + (tWarm - tDedupe) + "ms"
                        + " 后台合计=" + (tWarm - tBg0) + "ms 条数=" + deduped.size());
                final List<MusicBean> finalList = deduped;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        final long tUi0 = System.currentTimeMillis();
                        applyMusicListCore(finalList, false);
                        CacheDebugLog.log("切换重建[云端] 主线程刷新="
                                + (System.currentTimeMillis() - tUi0) + "ms");
                        finishSourceSwitch(false);
                    }
                });
                } catch (final Throwable t) {
                    // 后台线程未捕获异常会直接杀进程(连点切换时任何一次构建抛错都是闪退)。
                    // 兜底:记录完整堆栈、复位单飞、撤遮罩,保留旧列表;用户再点即可重试。
                    Log.e(TAG, "切换重建异常", t);
                    CacheDebugLog.log("切换重建异常: " + Log.getStackTraceString(t));
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            sourceSwitchInFlight = false;
                            hideLoadingMask();
                            restoreEmptyHintForCurrentMode();
                            Toast.makeText(MainActivity.this,
                                    "切换失败: " + t.getClass().getSimpleName(),
                                    Toast.LENGTH_SHORT).show();
                        }
                    });
                }
            }
        }, "SourceModeToggle").start();
    }

    /** 后台预热每首歌的身份键缓存(getIdentityKey,适配器去重/DiffUtil 真正使用的键) */
    private void warmKeys(List<MusicBean> list) {
        if (list == null) return;
        for (MusicBean b : list) {
            // 预热 getIdentityKey:已下载本地播的云端歌(network=false 但带 streamId)返回 net_{streamId},
            // 无需磁盘 I/O;只有纯本地歌才走 getCanonicalPath(必要的一次性磁盘 I/O,仍在后台线程)。
            // 原实现预热 getCachedKey 会对所有已下载云端歌做无谓 getCanonicalPath(810 首≈220ms),
            // 是「本地→云端」切换延迟/卡顿的主因,故改为预热 getIdentityKey。
            b.getIdentityKey();
        }
    }

    /** 核心:主线程刷新列表 UI(须在主线程调用)。供 applyMusicListToUi 与 applySourceMode 共用 */
    private void applyMusicListCore(final List<MusicBean> list, final boolean toLocal) {
        // 记录当前列表是按哪个模式构建的:连点防抖用它判断"目标模式没变 → 无需重建"
        lastAppliedMode = toLocal;
        musicList.clear();
        musicList.addAll(list);
        adapter.setData(list);
        // 重新应用收藏/搜索过滤,保持各过滤维度一致
        if (favoritesOnly) {
            applyFavoritesFilter();
        } else {
            adapter.filter(currentSearchQuery);
        }
        updateCount();
        if (musicList.isEmpty()) {
            tvEmpty.setVisibility(View.VISIBLE);
            tvEmpty.setText(toLocal
                    ? "本地目录没有找到歌曲\n把歌曲放进 音乐根目录/本地歌曲 即可"
                    : "未找到音乐\n请在设置中配置服务器并同步");
        } else {
            tvEmpty.setVisibility(View.GONE);
        }
        // 本地模式:保存本地扫描缓存(local_songs.json,与云端缓存隔离)
        if (toLocal) {
            localMusicCache.forceSaveAsync(musicList);
        }
        updatePlayingHighlight();
        // 新列表已进 UI,撤掉加载遮罩(未显示时为空操作)
        hideLoadingMask();
    }

    /** 将列表交给主线程刷新 UI(供 FileObserver 重扫描等无单飞场景) */
    private void applyMusicListToUi(final List<MusicBean> list, final boolean toLocal) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                applyMusicListCore(list, toLocal);
            }
        });
    }

    // ===== 本地目录 FileObserver 监听 =====
    // 本地模式下监听本地扫描目录的文件增删改,自动重新扫描并刷新列表,
    // 用户"放新歌/删歌/替换歌"后无需手动刷新即可看到变化。
    // 云端模式列表由服务端驱动,不监听;监听目录不存在时不启动(避免崩溃)。

    /**
     * 启动本地目录 FileObserver(幂等)。
     * 先判断本地模式/存储权限/目录存在,满足才创建并 startWatching;
     * 创建或停止 FileObserver 必须在主线程执行,调用方需保证在 UI 线程。
     */
    private void startLocalDirObserver() {
        if (localDirObserver != null) {
            return; // 已启动,幂等
        }
        if (!localOnlyMode) {
            return; // 仅本地模式需要监听
        }
        if (!hasStoragePermission()) {
            return;
        }
        final String dir = navidromeConfig.getLocalScanPath();
        if (dir == null || dir.isEmpty()) {
            return;
        }
        final File dirFile = new File(dir);
        if (!dirFile.exists() || !dirFile.isDirectory()) {
            Log.w(TAG, "本地目录不存在,FileObserver 未启动: " + dir);
            return;
        }
        final int mask = FileObserver.CREATE | FileObserver.DELETE
                | FileObserver.MODIFY | FileObserver.MOVED_TO | FileObserver.MOVED_FROM
                | FileObserver.CLOSE_WRITE | FileObserver.DELETE_SELF | FileObserver.MOVE_SELF;
        localDirObserver = new FileObserver(dir, mask) {
            @Override
            public void onEvent(int event, String path) {
                if (event == 0) {
                    return; // 部分设备会重复上报 0,忽略
                }
                // 目录自身被删除/移动:路径已失效,停止监听
                if ((event & (FileObserver.DELETE_SELF | FileObserver.MOVE_SELF)) != 0) {
                    Log.w(TAG, "本地目录自身被删除/移动,停止 FileObserver");
                    stopLocalDirObserver();
                    return;
                }
                // 仅关心会改变列表内容的事件
                if ((event & (FileObserver.CREATE | FileObserver.DELETE
                        | FileObserver.MOVED_TO | FileObserver.MOVED_FROM
                        | FileObserver.CLOSE_WRITE | FileObserver.MODIFY)) == 0) {
                    return;
                }
                scheduleLocalDirRescan();
            }
        };
        localDirObserver.startWatching();
        Log.i(TAG, "FileObserver 启动,监听本地目录: " + dir);
    }

    /**
     * 停止并释放本地目录 FileObserver(幂等)。
     */
    private void stopLocalDirObserver() {
        if (localDirObserver != null) {
            try {
                localDirObserver.stopWatching();
            } catch (Exception ignored) {
            }
            localDirObserver = null;
            Log.i(TAG, "FileObserver 已停止");
        }
    }

    /**
     * 同步 FileObserver 状态:满足监听条件(本地模式 + 已授权 + 目录存在)则启动,否则停止。
     * 统一入口,可在 onResume/模式切换/云端回退等场景调用,避免重复判断。
     */
    private void syncLocalDirObserver() {
        if (localOnlyMode && hasStoragePermission()) {
            final String dir = navidromeConfig.getLocalScanPath();
            if (dir != null && !dir.isEmpty()) {
                final File dirFile = new File(dir);
                if (dirFile.exists() && dirFile.isDirectory()) {
                    startLocalDirObserver();
                    return;
                }
            }
        }
        stopLocalDirObserver();
    }

    /**
     * 防抖调度:多次连续文件事件合并为一次重新扫描,避免大批量拷歌时反复刷新卡顿。
     */
    private void scheduleLocalDirRescan() {
        handler.removeCallbacks(localDirRescanRunnable);
        handler.postDelayed(localDirRescanRunnable, LOCAL_DIR_RESCAN_DEBOUNCE_MS);
    }

    /** 防抖后的实际重扫描任务(由主线程 Handler 调度) */
    private final Runnable localDirRescanRunnable = new Runnable() {
        @Override
        public void run() {
            rescanLocalDirAndRefresh();
        }
    };

    /**
     * 后台重新扫描本地目录并刷新列表(供 FileObserver 调用)。
     * 复用与 applySourceMode 相同的 scan → dedupe → applyMusicListToUi 链路,
     * 保证"放新歌后列表即时更新"。扫描为空则保留当前列表,避免误清空。
     */
    private void rescanLocalDirAndRefresh() {
        if (!localOnlyMode) {
            return; // 已切回云端,无需刷新本地列表
        }
        final String localDir = navidromeConfig.getLocalScanPath();
        if (localDir == null || localDir.isEmpty()) {
            return;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    List<MusicBean> fresh = MusicScanner.scanDirectoryOnly(MainActivity.this, localDir);
                    if (fresh == null || fresh.isEmpty()) {
                        Log.w(TAG, "FileObserver 重扫描为空,保留当前列表");
                        return;
                    }
                    java.util.Collections.sort(fresh, MusicTitleComparator.INSTANCE);
                    final List<MusicBean> deduped = dedupeList(fresh);
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (!localOnlyMode) {
                                return; // 重扫描期间切回云端,丢弃结果
                            }
                            Log.i(TAG, "FileObserver 触发本地列表刷新,共 " + deduped.size() + " 首");
                            applyMusicListToUi(deduped, true);
                        }
                    });
                } catch (Throwable t) {
                    Log.e(TAG, "FileObserver 重扫描失败", t);
                }
            }
        }, "LocalDirObserverRescan").start();
    }

    /**
     * 手动刷新歌曲列表:按当前模式刷新对应列表。
     * 本地模式 = 重新扫描本地自定义目录(全部歌曲,缓存 local_songs.json);
     * 云端模式 = 重新执行云端列表构建(已下载→本地播,未下载→联网播),
     *            并触发一次后台同步刷新云端缓存(发现新歌);
     *            云端不可用(无缓存/未同步/未配置)时回退为本地扫描,保证界面不空白。
     */
    private void refreshMusicList() {
        final String syncPath = navidromeConfig.getCloudDir();
        final String localDir = navidromeConfig.getLocalScanPath();
        if (syncPath == null || syncPath.isEmpty()) {
            Toast.makeText(this, "未配置扫描目录", Toast.LENGTH_SHORT).show();
            return;
        }

        // 显示刷新进度
        tvSyncStatus.setVisibility(View.VISIBLE);
        tvSyncStatus.setText("正在刷新...");

        new Thread(new Runnable() {
            @Override
            public void run() {
                // ---- 云端模式:重新构建云端列表(本地模式跳过,走下方本地重扫) ----
                if (!localOnlyMode) {
                List<MusicBean> cloudList = buildCloudDrivenList(navidromeConfig.getServerType(), syncPath);
                if (cloudList != null) {
                    java.util.Collections.sort(cloudList, MusicTitleComparator.INSTANCE);
                    // 后台预热 getCanonicalPath:已下载本地播的云端歌 setData/dedupe 主线程会逐首取路径键,
                    // 不预热则主线程磁盘 I/O 掉帧(与 applySourceMode 云端分支同一根因)
                    warmKeys(cloudList);
                    final List<MusicBean> finalList = cloudList;
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            // 若后台同步仍在进行,不要隐藏同步状态
                            if (isAutoSyncing) {
                                tvSyncStatus.setVisibility(View.VISIBLE);
                                tvSyncStatus.setText("同步中...");
                            } else {
                                tvSyncStatus.setVisibility(View.GONE);
                            }

                            int oldCount = musicList.size();
                            musicList.clear();
                            musicList.addAll(finalList);
                            dedupeMusicList();
                            adapter.setData(musicList);
                            if (favoritesOnly) {
                                applyFavoritesFilter();
                            } else {
                                adapter.filter(currentSearchQuery);
                            }
                            updateCount();
                            if (musicList.isEmpty()) {
                                tvEmpty.setVisibility(View.VISIBLE);
                                tvEmpty.setText(localOnlyMode
                                        ? "本地还没有已下载的歌曲\n切到\"云端\"查看全部歌曲"
                                        : "未找到音乐\n请在设置中配置服务器并同步");
                            } else {
                                tvEmpty.setVisibility(View.GONE);
                            }

                            // 更新播放列表(保留当前播放歌曲位置)
                            if (service != null && !musicList.isEmpty()) {
                                MusicBean currentSong = service.getCurrentMusic();
                                int newIndex = 0;
                                if (currentSong != null) {
                                    String curKey = getSongKey(currentSong);
                                    for (int i = 0; i < musicList.size(); i++) {
                                        if (curKey.equals(getSongKey(musicList.get(i)))) {
                                            newIndex = i;
                                            break;
                                        }
                                    }
                                }
                                service.setPlayList(musicList, newIndex);
                                updatePlayingHighlight();
                            }

                            // 云端列表不写本地扫描缓存(localMusicCache 仅服务回退路径)
                            CoverLoader.getInstance().clearNoCoverCache();
                            int coverSize = (int) getResources().getDimension(R.dimen.cover_size_list);
                            CoverLoader.getInstance().preloadAllCovers(musicList, coverSize);

                            int diff = musicList.size() - oldCount;
                            String msg;
                            if (diff > 0) {
                                msg = "刷新完成: " + musicList.size() + " 首(新增 " + diff + " 首)";
                            } else if (diff < 0) {
                                msg = "刷新完成: " + musicList.size() + " 首(减少 " + (-diff) + " 首)";
                            } else {
                                msg = "刷新完成: " + musicList.size() + " 首";
                            }
                            Toast.makeText(MainActivity.this, msg, Toast.LENGTH_SHORT).show();
                        }
                    });
                    // 顺带后台同步一次,刷新云端缓存(发现服务器新增/删除的歌)
                    startBackgroundSync();
                    return;
                }
                } // end if (!localOnlyMode)

                // ---- 本地模式,或云端不可用:本地扫描(原逻辑,扫本地自定义目录) ----
                // 完整扫描本地目录
                final List<MusicBean> fullList = MusicScanner.scanDirectoryOnly(MainActivity.this, localDir);

                // 排序
                java.util.Collections.sort(fullList, MusicTitleComparator.INSTANCE);

                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        // 若后台同步仍在进行,不要隐藏同步状态:
                        // 刷新列表只是本地扫描,不应打断正在运行的同步指示。
                        // 否则同步其实仍在继续,但左上角的"同步中"会凭空消失。
                        if (isAutoSyncing) {
                            tvSyncStatus.setVisibility(View.VISIBLE);
                            tvSyncStatus.setText("同步中...");
                        } else {
                            tvSyncStatus.setVisibility(View.GONE);
                        }

                        if (fullList.isEmpty()) {
                            Toast.makeText(MainActivity.this, "未找到音乐文件", Toast.LENGTH_SHORT).show();
                            return;
                        }

                        // 记录旧数量用于提示
                        int oldCount = musicList.size();

                        // 用扫描结果替换当前列表
                        musicList.clear();
                        musicList.addAll(fullList);
                        dedupeMusicList();
                        adapter.setData(musicList);
                        updateCount();

                        if (tvEmpty.getVisibility() == View.VISIBLE && !musicList.isEmpty()) {
                            tvEmpty.setVisibility(View.GONE);
                        }

                        // 更新播放列表(保留当前播放歌曲位置)
                        if (service != null && !musicList.isEmpty()) {
                            MusicBean currentSong = service.getCurrentMusic();
                            int newIndex = 0;
                            if (currentSong != null) {
                                String curKey = getSongKey(currentSong);
                                for (int i = 0; i < musicList.size(); i++) {
                                    if (curKey.equals(getSongKey(musicList.get(i)))) {
                                        newIndex = i;
                                        break;
                                    }
                                }
                            }
                            service.setPlayList(musicList, newIndex);
                            // 用歌曲身份在当前显示列表中定位高亮(避免过滤时索引错位)
                            updatePlayingHighlight();
                        }

                        // 保存到缓存(下次秒开) — 强制保存(内容可能变化但数量不变)
                        localMusicCache.forceSaveAsync(musicList);

                        // 清除无封面黑名单(重新扫描后可能有新封面)
                        CoverLoader.getInstance().clearNoCoverCache();
                        // 预提取所有封面到内部存储
                        int coverSize = (int) getResources().getDimension(R.dimen.cover_size_list);
                        CoverLoader.getInstance().preloadAllCovers(musicList, coverSize);

                        int diff = fullList.size() - oldCount;
                        String msg;
                        if (diff > 0) {
                            msg = "扫描完成: " + fullList.size() + " 首(新增 " + diff + " 首)";
                        } else if (diff < 0) {
                            msg = "扫描完成: " + fullList.size() + " 首(减少 " + (-diff) + " 首)";
                        } else {
                            msg = "扫描完成: " + fullList.size() + " 首";
                        }
                        Toast.makeText(MainActivity.this, msg, Toast.LENGTH_SHORT).show();
                    }
                });
            }
        }).start();
    }

    /**
     * 后台扫描U盘并合并到列表(不阻塞UI)
     * 仅用于:首次无缓存时的补全扫描 / 手动刷新列表
     * 缓存路径不再调用此方法(用户要求:缓存秒开,点击歌曲才读U盘)
     *
     * @param syncPath 扫描路径
     * @param existingList 当前已有的列表(用于去重)
     * @param fromCache 是否从缓存加载(existingList来自缓存,需校验文件存在性)
     */
    private void backgroundScanAndMerge(final String syncPath, final List<MusicBean> existingList, final boolean fromCache) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                // 后台完整扫描(递归遍历目录,含 MediaStore 未收录的文件)
                final List<MusicBean> fullList = MusicScanner.scanDirectoryOnly(MainActivity.this, syncPath);

                // 合并新发现的文件
                final List<MusicBean> toAdd = new ArrayList<>();
                java.util.Set<String> existingPaths = new java.util.HashSet<>();
                for (MusicBean b : existingList) {
                    String p = MusicScanner.normalizePath(b.getData());
                    if (!p.isEmpty()) {
                        existingPaths.add(p);
                    }
                }
                // 校验已有文件是否仍存在(移除已删除的)
                final List<MusicBean> validList = new ArrayList<>();
                if (fromCache) {
                    java.util.Set<String> fullPaths = new java.util.HashSet<>();
                    for (MusicBean b : fullList) {
                        String p = MusicScanner.normalizePath(b.getData());
                        if (!p.isEmpty()) fullPaths.add(p);
                    }
                    for (MusicBean b : existingList) {
                        String p = MusicScanner.normalizePath(b.getData());
                        if (p.isEmpty() || fullPaths.contains(p)) {
                            validList.add(b);
                        }
                    }
                }
                for (MusicBean b : fullList) {
                    String p = MusicScanner.normalizePath(b.getData());
                    if (!p.isEmpty() && !existingPaths.contains(p)) {
                        toAdd.add(b);
                    }
                }

                // 判断是否有实际变化(无变化则不刷新列表,避免视觉跳动)
                final boolean hasChanges;
                if (fromCache) {
                    // 缓存模式:文件被删除或新增了文件才算变化
                    hasChanges = (validList.size() != existingList.size()) || !toAdd.isEmpty();
                } else {
                    hasChanges = !toAdd.isEmpty();
                }

                if (!hasChanges) {
                    // 无变化:首次无缓存时仍需保存缓存(让下次秒开)
                    if (!fromCache && !musicList.isEmpty()) {
                        localMusicCache.forceSaveAsync(musicList);
                    }
                    // 仅启动后台服务器同步
                    startBackgroundSync();
                    return;
                }

                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (fromCache) {
                            // 缓存模式:用校验后的列表(移除已删除文件)+ 新增文件
                            musicList.clear();
                            musicList.addAll(validList);
                            musicList.addAll(toAdd);
                        } else {
                            // 非缓存模式:追加新发现的
                            musicList.addAll(toAdd);
                        }

                        // 排序
                        java.util.Collections.sort(musicList, MusicTitleComparator.INSTANCE);
                        dedupeMusicList();
                        adapter.setData(musicList);
                        updateCount();
                        if (tvEmpty.getVisibility() == View.VISIBLE && !musicList.isEmpty()) {
                            tvEmpty.setVisibility(View.GONE);
                        }

                        // 更新播放列表(保留当前播放歌曲,不重置索引)
                        if (service != null && !musicList.isEmpty()) {
                            MusicBean currentSong = service.getCurrentMusic();
                            int newIndex = 0;
                            if (currentSong != null) {
                                // 用 song key 在新列表中查找当前播放歌曲的位置
                                String curKey = getSongKey(currentSong);
                                for (int i = 0; i < musicList.size(); i++) {
                                    if (curKey.equals(getSongKey(musicList.get(i)))) {
                                        newIndex = i;
                                        break;
                                    }
                                }
                            }
                            service.setPlayList(musicList, newIndex);
                            // 用歌曲身份在当前显示列表中定位高亮(避免过滤时索引错位)
                            updatePlayingHighlight();
                        }

                        // 保存缓存(下次秒开) — 强制保存(扫描后内容可能变化)
                        localMusicCache.forceSaveAsync(musicList);
                        // 扫描后预提取新歌曲的封面到内部存储
                        int coverSize = (int) getResources().getDimension(R.dimen.cover_size_list);
                        CoverLoader.getInstance().preloadAllCovers(musicList, coverSize);
                    }
                });

                // 3. 后台自动同步服务器新歌
                startBackgroundSync();
            }
        }).start();
    }

    /** 生成歌曲唯一key(跨列表身份键:同一首歌云端/本地同键,高亮与收藏跨列表一致) */
    private String getSongKey(MusicBean b) {
        if (b == null) return "";
        return b.getIdentityKey();
    }

    /**
     * 列表去重:移除 musicList 中"同一首歌多次出现"的条目,保留质量更高的一条。
     * 必须在主线程调用(会修改 musicList 与 adapter)。
     *
     * 两层去重(先按路径、再按逻辑身份),可同时覆盖"同文件重复"与"跨文件夹同名歌":
     *   第一层(路径,见 getDedupKey):规范化文件路径 path_<canonical> 最权威。
     *      本应用 musicList 每条目都对应磁盘一个真实文件,同一文件(无论挂载点前缀、
     *      是否已被 MediaStore 索引)路径规范化后必一致,覆盖:
     *        (a) 同步期间 refreshSyncList 被多次并发调用,同一批刚下载的文件被加了两遍;
     *        (b) 刚下载完 MediaStore 尚未索引,首扫用"文件名当标题"、再扫用"真实标题",
     *            元数据不同导致旧键无法合并 —— 现在同路径直接合并;
     *        (c) U盘重新挂载导致路径前缀变化(如 /sdcard/ ↔ /storage/emulated/0/)。
     *   第二层(逻辑身份,见 getLogicalKey):忽略文件夹,按 标题+歌手+时长(秒) 判定同一首。
     *      同一首歌放在不同文件夹 / 两个不同文件名,只要标题、歌手、时长一致就折叠成一条;
     *      时长作第三道保险,同名同歌手但时长不同的"不同歌"不会误并。
     *
     * 两层冲突时均保留"质量更高"的条目(带真实歌手/标题优先于"未知艺术家"/文件名标题),
     * 避免把"七里香.mp3"这种文件名标题残留进缓存。
     */
    private void dedupeMusicList() {
        if (musicList == null || musicList.isEmpty()) return;
        List<MusicBean> out = dedupeByList(musicList, false);  // 第一层:同一文件(路径)合并
        out = dedupeByList(out, true);                          // 第二层:跨文件夹同名歌(标题+歌手+时长)合并
        // 未发生变化时 dedupeByList 返回原引用,无需重写;否则写回去重结果
        if (out != musicList) {
            musicList.clear();
            musicList.addAll(out);
        }
    }

    /**
     * 后台线程安全版去重:对传入列表做两层去重,返回去重后的新列表(不修改入参,
     * 未变化则返回原引用)。供 applySourceMode 在后台线程调用,避免把
     * getDedupKey 的 normalizePath 磁盘 I/O(810 首约 240ms)放在主线程造成切换掉帧。
     */
    private List<MusicBean> dedupeList(List<MusicBean> src) {
        if (src == null || src.isEmpty()) return src;
        List<MusicBean> out = dedupeByList(src, false);
        out = dedupeByList(out, true);
        return out;
    }

    /**
     * 单层去重:按指定维度对列表去重,保留质量更高的条目,返回去重后的列表。
     * 若未移除任何条目则返回原列表(同一引用),便于调用方判断是否发生变化。
     * @param useLogical true=按逻辑身份(标题+歌手+时长,忽略文件夹)去重;
     *                  false=按 getDedupKey(路径/streamId)去重。
     */
    private List<MusicBean> dedupeByList(List<MusicBean> src, boolean useLogical) {
        if (src == null || src.isEmpty()) return src;
        java.util.LinkedHashMap<String, MusicBean> best = new java.util.LinkedHashMap<>();
        int removed = 0;
        int uniqueCounter = 0;
        for (MusicBean b : src) {
            String key = useLogical ? getLogicalKey(b) : getDedupKey(b);
            if (key == null) {
                // 缺少可比对字段(如逻辑去重缺时长)不参与合并,原样保留
                best.put("__u" + (uniqueCounter++), b);
                continue;
            }
            MusicBean prev = best.get(key);
            if (prev == null) {
                best.put(key, b);
            } else {
                if (beanQuality(b) > beanQuality(prev)) {
                    best.put(key, b);
                }
                removed++;
            }
        }
        if (removed > 0) {
            Log.d(TAG, "列表去重: 移除 " + removed + " 首重复条目");
            return new ArrayList<>(best.values());
        }
        return src;
    }

    /** 逻辑身份键:忽略文件夹,按 标题+歌手+时长(秒,四舍五入) 判定同一首歌,用于跨文件夹去重 */
    private String getLogicalKey(MusicBean b) {
        if (b == null) return null;
        long dur = b.getDuration();
        if (dur <= 0) return null;   // 时长缺失不参与逻辑去重,避免无元数据文件被误并
        String title = b.getTitle();
        String artist = b.getArtist();
        String t = (title != null ? title : "").trim().replaceAll("\\s+", " ");
        String a = (artist != null ? artist : "").trim().replaceAll("\\s+", " ");
        long sec = (dur + 500) / 1000;   // 归到秒,容忍不同编码间 <1s 的时长抖动
        return "meta_" + t + "|" + a + "|" + sec;
    }

    /** 条目质量评分,用于同键去重时择优保留(分数越高越优) */
    private int beanQuality(MusicBean b) {
        if (b == null) return -1;
        int score = 0;
        String sid = b.getStreamId();
        if (sid != null && !sid.isEmpty()) score += 4;          // 服务器身份最权威
        String artist = b.getArtist();
        if (artist != null && !artist.isEmpty() && !"未知艺术家".equals(artist)) score += 2;
        String title = b.getTitle();
        String data = b.getData();
        String fname = "";
        if (data != null && !data.isEmpty()) {
            int idx = data.lastIndexOf('/');
            fname = idx >= 0 ? data.substring(idx + 1) : data;
            int dot = fname.lastIndexOf('.');
            if (dot > 0) fname = fname.substring(0, dot);
        }
        if (title != null && !title.isEmpty() && !title.equals(fname)) score += 1; // 真实标题优于"文件名当标题"
        return score;
    }

    /** 计算去重键(见 dedupeMusicList 说明) */
    private String getDedupKey(MusicBean b) {
        if (b == null) return "";
        // 1. 服务器身份(最权威)
        String sid = b.getStreamId();
        if (sid != null && !sid.isEmpty()) {
            return "net_" + sid;
        }
        // 2. 磁盘身份:同一文件规范化路径必一致 —— 主要去重依据
        //    复用 MusicBean 缓存的规范化路径,Migration 到后台线程后只算一次(避免切换主线程掉帧)
        String data = b.getData();
        if (data != null && !data.isEmpty()) {
            String cp = b.getCachedCanonicalPath();
            if (cp != null && !cp.isEmpty()) {
                return "path_" + cp;
            }
        }
        // 3. 兜底:无路径时用 艺术家|专辑|标题|文件名 折叠
        String artist = b.getArtist() != null ? b.getArtist() : "";
        String album = b.getAlbum() != null ? b.getAlbum() : "";
        String title = b.getTitle() != null ? b.getTitle() : "";
        String fname = "";
        if (data != null && !data.isEmpty()) {
            int idx = data.lastIndexOf('/');
            fname = idx >= 0 ? data.substring(idx + 1) : data;
        }
        return "meta_" + artist + "|" + album + "|" + title + "|" + fname;
    }

    /**
     * 手动更新列表(点击顶栏状态文字触发)。
     *
     * 云端模式:重跑一次"只刷新列表"的同步(与后台自动同步同一条路径,不下载音频);
     * 本地模式:重建本地列表(复用来源切换的 applySourceMode,
     *           它自带单飞保护,连点不会并发扫描)。
     * 各前置条件不满足时给出明确提示,而不是静默什么都不发生。
     */
    private void manualRefreshList() {
        if (isAutoSyncing) {
            Toast.makeText(this, "正在更新列表,请稍候...", Toast.LENGTH_SHORT).show();
            return;
        }

        if (localOnlyMode) {
            if (sourceSwitchInFlight) {
                Toast.makeText(this, "正在刷新列表,请稍候...", Toast.LENGTH_SHORT).show();
                return;
            }
            // 复用来源切换的单飞机制:置位后由 finishSourceSwitch 收尾复位
            sourceSwitchInFlight = true;
            Toast.makeText(this, "正在重新扫描本地列表...", Toast.LENGTH_SHORT).show();
            applySourceMode(true);
            return;
        }

        MusicSourceApi api = MusicDataHolder.getInstance().getMusicSourceApi();
        if (api == null || !MusicDataHolder.getInstance().isNavidromeEnabled()) {
            Toast.makeText(this, "未配置云端服务器,无法更新列表", Toast.LENGTH_SHORT).show();
            return;
        }
        String syncPath = navidromeConfig.getCloudDir();
        if (syncPath == null || syncPath.isEmpty()) {
            Toast.makeText(this, "未设置云端目录,请先在设置中配置", Toast.LENGTH_SHORT).show();
            return;
        }

        Toast.makeText(this, "正在更新云端列表...", Toast.LENGTH_SHORT).show();
        startAutoSync(syncPath, 0);
    }

    /**
     * 后台自动刷新云端列表
     * 云端为「点击播放」模式:后台只刷新云端歌曲列表(发现新歌/更新streamUrl),
     * 不再全量下载音频;音频在点击播放时按需下载缓存(MusicService.maybeAutoCache)。
     * 全量下载仍可在「同步」界面手动触发(SyncActivity)。
     * 注意:此方法可能从后台线程调用,startAutoSync 内部操作了 UI,
     *       所以必须切到主线程执行。
     */
    private void startBackgroundSync() {
        final MusicSourceApi api = MusicDataHolder.getInstance().getMusicSourceApi();
        if (api == null || !MusicDataHolder.getInstance().isNavidromeEnabled()) {
            return;
        }
        if (isAutoSyncing) {
            return; // 已在同步中
        }
        // 本地模式下不触发云端后台同步:本地列表与云端目录相互独立,
        // 避免本地浏览时仍去连服务器/写云端子目录,造成切换不协调。
        if (localOnlyMode) {
            return;
        }

        final String syncPath = navidromeConfig.getCloudDir();

        // 切到主线程执行(startAutoSync 内部操作了 UI 控件)
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                startAutoSync(syncPath, 0);
            }
        });
    }

    // ==================== 缓存工具 ====================

    /** 格式化缓存时间为相对时间描述 */
    private String formatCacheTime(long timestamp) {
        if (timestamp == 0) return "未知时间";
        long diff = System.currentTimeMillis() - timestamp;
        long minutes = diff / (60 * 1000);
        if (minutes < 1) return "刚刚";
        if (minutes < 60) return minutes + "分钟前";
        long hours = minutes / 60;
        if (hours < 24) return hours + "小时前";
        long days = hours / 24;
        return days + "天前";
    }

    /** 启动自动同步 */
    private void startAutoSync(String syncPath, int serverCount) {
        final MusicSourceApi api = MusicDataHolder.getInstance().getMusicSourceApi();
        if (api == null) return;

        isAutoSyncing = true;
        pendingSyncRefresh = 0;
        syncManager = new MusicSyncManager(this, api, syncPath);

        tvSyncStatus.setVisibility(View.VISIBLE);
        tvSyncStatus.setText("同步中...");

        new Thread(new Runnable() {
            @Override
            public void run() {
                // 仅刷新云端列表(不下载音频):音频由播放时按需缓存
                syncManager.sync(new MusicSyncManager.SyncCallback() {
                    @Override
                    public void onStart(final int totalSongs) {
                        handler.post(new Runnable() {
                            @Override
                            public void run() {
                                tvSyncStatus.setVisibility(View.VISIBLE);
                                tvSyncStatus.setText("更新云端列表...");
                            }
                        });
                    }

                    @Override
                    public void onProgress(final int downloaded, final int total, final String currentSong) {
                        handler.post(new Runnable() {
                            @Override
                            public void run() {
                                tvSyncStatus.setText("更新列表 " + total);
                            }
                        });
                    }

                    @Override
                    public void onSongDownloaded(final int downloaded, final int total) {
                        pendingSyncRefresh++;
                        if (pendingSyncRefresh >= REFRESH_BATCH_SIZE) {
                            pendingSyncRefresh = 0;
                            handler.post(new Runnable() {
                                @Override
                                public void run() {
                                    tvSyncStatus.setText("同步 " + downloaded + "/" + total);
                                    refreshSyncList();
                                }
                            });
                        } else {
                            handler.post(new Runnable() {
                                @Override
                                public void run() {
                                    tvSyncStatus.setText("同步 " + downloaded + "/" + total);
                                }
                            });
                        }
                    }

                    @Override
                    public void onSongFailed(final String songTitle, final String reason) {
                        // 静默忽略
                    }

                    @Override
                    public void onComplete(final int downloaded, final int skipped, final int failed, final int total) {
                        handler.post(new Runnable() {
                            @Override
                            public void run() {
                                isAutoSyncing = false;
                                // 同步完成必须刷新歌曲列表,让新同步的歌曲出现在列表里。
                                // refreshSyncList() 内部仅当确实发现新文件(toAdd 非空)才重建列表;
                                // "已是最新"时只是一次整目录扫描、不会重建,开销可接受。
                                refreshSyncList();
                                // 同步完成:清除无封面黑名单,允许重新尝试(新文件可能带封面)
                                CoverLoader.getInstance().clearNoCoverCache();
                                // 预提取新同步歌曲的封面到内部存储
                                int coverSize = (int) getResources().getDimension(R.dimen.cover_size_list);
                                CoverLoader.getInstance().preloadAllCovers(musicList, coverSize);
                                if (downloaded > 0) {
                                    tvSyncStatus.setText("已同步 +" + downloaded + " 首");
                                } else {
                                    tvSyncStatus.setText("列表已更新");
                                }
                                updateCount();
                                if (tvEmpty.getVisibility() == View.VISIBLE && !musicList.isEmpty()) {
                                    tvEmpty.setVisibility(View.GONE);
                                }
                            }
                        });
                    }

                    @Override
                    public void onCancelled(final int downloaded, final int total) {
                        handler.post(new Runnable() {
                            @Override
                            public void run() {
                                isAutoSyncing = false;
                                tvSyncStatus.setVisibility(View.GONE);
                            }
                        });
                    }

                    @Override
                    public void onError(final String message) {
                        handler.post(new Runnable() {
                            @Override
                            public void run() {
                                isAutoSyncing = false;
                                tvSyncStatus.setVisibility(View.VISIBLE);
                                tvSyncStatus.setText(message);
                            }
                        });
                    }
                }, false);   // 列表刷新模式:不下载音频,播放时按需缓存
            }
        }).start();
    }

    /** 取消自动同步 */
    private void cancelAutoSync() {
        if (syncManager != null) {
            syncManager.cancel();
            syncManager = null;
        }
        isAutoSyncing = false;
        pendingSyncRefresh = 0;
        tvSyncStatus.setVisibility(View.GONE);
    }

    /**
     * 增量刷新同步列表
     * 重新扫描同步目录,将新下载的文件加入列表
     */
    private void refreshSyncList() {
        final String syncPath = navidromeConfig.getCloudDir();
        if (syncPath == null || syncPath.isEmpty()) return;

        new Thread(new Runnable() {
            @Override
            public void run() {
                // 只扫同步目录(新下载文件落在这里);不再覆写 scan_path 配置
                // (scan_path 已归"本地模式目录"所有,见 NavidromeConfig.getLocalScanPath)
                final List<MusicBean> newList = MusicScanner.scanDirectoryOnly(MainActivity.this, syncPath);

                // 计算新增的歌曲(用规范化路径去重,消除符号链接差异)
                final List<MusicBean> toAdd = new ArrayList<>();
                final java.util.Set<String> existingPaths = new java.util.HashSet<>();
                for (MusicBean b : musicList) {
                    String p = MusicScanner.normalizePath(b.getData());
                    if (!p.isEmpty()) {
                        existingPaths.add(p);
                    }
                }
                for (MusicBean bean : newList) {
                    String p = MusicScanner.normalizePath(bean.getData());
                    if (!p.isEmpty() && !existingPaths.contains(p)) {
                        toAdd.add(bean);
                    }
                }

                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        // 防竞态:同步期间 refreshSyncList 可能被并发触发多次(每批下载 + 完成各一次),
                        // 多个后台扫描会对着同一份尚未更新的 musicList 各自算出重叠的 toAdd,
                        // 导致同一文件被 addAll 两遍。这里在 UI 线程用"最新的 musicList"再过滤一次。
                        {
                            java.util.Set<String> livePaths = new java.util.HashSet<String>();
                            for (MusicBean b : musicList) {
                                String p = MusicScanner.normalizePath(b.getData());
                                if (!p.isEmpty()) livePaths.add(p);
                            }
                            java.util.Iterator<MusicBean> it = toAdd.iterator();
                            while (it.hasNext()) {
                                MusicBean b = it.next();
                                String p = MusicScanner.normalizePath(b.getData());
                                if (p.isEmpty() || livePaths.contains(p)) it.remove();
                            }
                        }
                        if (favoritesOnly) {
                            // 收藏夹模式:重新设置数据后重新过滤收藏
                            if (!toAdd.isEmpty()) {
                                musicList.addAll(toAdd);
                                java.util.Collections.sort(musicList, MusicTitleComparator.INSTANCE);
                                dedupeMusicList();
                            }
                            adapter.setData(musicList);
                            // 云端收藏夹:沿用内存里已缓存的服务器收藏 ID 重新过滤(不联网);
                            // 本地收藏夹:用本机 FavoriteManager 过滤。
                            if (adapter.isCloudFavoritesMode()) {
                                adapter.filterFavorites(null, true);
                            } else {
                                applyFavoritesFilter();
                            }
                            if (service != null && !musicList.isEmpty()) {
                                // 关键:播放队列必须用收藏夹列表,而不是全部曲目。
                                // 否则后台扫描补全完成后会把正在播放的收藏夹队列悄悄换成
                                // 全部曲目,导致当前歌曲播完跳回"所有曲目"继续播放。
                                List<MusicBean> favList = adapter.getDisplayList();
                                int startIdx = 0;
                                MusicBean cur = service.getCurrentMusic();
                                if (cur != null) {
                                    String curKey = getSongKey(cur);
                                    for (int i = 0; i < favList.size(); i++) {
                                        if (curKey.equals(getSongKey(favList.get(i)))) {
                                            startIdx = i;
                                            break;
                                        }
                                    }
                                }
                                service.setPlayList(favList, startIdx);
                            }
                        } else if (currentSearchQuery.isEmpty()) {
                            if (!toAdd.isEmpty()) {
                                musicList.addAll(toAdd);
                                java.util.Collections.sort(musicList, MusicTitleComparator.INSTANCE);
                                dedupeMusicList();
                                adapter.setData(musicList);
                                // 关键:同步新增歌曲后要更新播放队列。
                                // - 正在播放:只把新歌增量追加到队列末尾(appendToPlayList),绝不重设/重排整个队列,
                                //   当前歌照常播放、不被打断;新歌播完当前歌后续播(与收藏夹分支目标一致)。
                                // - 未在播放:用完整列表以"当前歌所在位置"为起点重设队列,避免重置到第 0 首。
                                if (service != null && !musicList.isEmpty()) {
                                    if (service.isPlaying()) {
                                        service.appendToPlayList(toAdd);
                                    } else {
                                        int startIdx = 0;
                                        MusicBean cur = service.getCurrentMusic();
                                        if (cur != null) {
                                            String curKey = getSongKey(cur);
                                            for (int i = 0; i < musicList.size(); i++) {
                                                if (curKey.equals(getSongKey(musicList.get(i)))) {
                                                    startIdx = i;
                                                    break;
                                                }
                                            }
                                        }
                                        service.setPlayList(musicList, startIdx);
                                    }
                                }
                            }
                            // 更新高亮:数据更新后重新定位当前播放歌曲
                            updatePlayingHighlight();
                        } else {
                            musicList.clear();
                            musicList.addAll(newList);
                            dedupeMusicList();
                            adapter.setData(musicList);
                            adapter.filter(currentSearchQuery);
                            // 重设队列时同样保留当前播放位置,避免重置到第 0 首打断续播
                            if (service != null && !musicList.isEmpty()) {
                                int startIdx = 0;
                                MusicBean cur = service.getCurrentMusic();
                                if (cur != null) {
                                    String curKey = getSongKey(cur);
                                    for (int i = 0; i < musicList.size(); i++) {
                                        if (curKey.equals(getSongKey(musicList.get(i)))) {
                                            startIdx = i;
                                            break;
                                        }
                                    }
                                }
                                service.setPlayList(musicList, startIdx);
                            }
                            // 更新高亮:过滤后重新定位当前播放歌曲
                            updatePlayingHighlight();
                        }

                        if (tvEmpty.getVisibility() == View.VISIBLE && !musicList.isEmpty()) {
                            tvEmpty.setVisibility(View.GONE);
                        }
                        updateCount();
                    }
                });
            }
        }).start();
    }

    // ==================== UI 更新 ====================

    /** 诊断用:dump 当前可见行的 槽位→adapter位置→歌曲,定位"第一首下面是第13首"这类位置塌缩 */
    private void dumpVisibleRows(String tag) {
        if (rvList == null) return;
        RecyclerView.LayoutManager lm = rvList.getLayoutManager();
        if (!(lm instanceof LinearLayoutManager)) return;
        LinearLayoutManager llm = (LinearLayoutManager) lm;
        int fv = llm.findFirstVisibleItemPosition();
        int lv = llm.findLastVisibleItemPosition();
        StringBuilder sb = new StringBuilder();
        sb.append("fv=").append(fv).append(" lv=").append(lv)
          .append(" childCount=").append(rvList.getChildCount());
        for (int i = 0; i < rvList.getChildCount(); i++) {
            View v = rvList.getChildAt(i);
            int ap = rvList.getChildAdapterPosition(v);
            MusicBean b = (ap >= 0) ? adapter.getItem(ap) : null;
            sb.append(" | slot").append(i).append(":ap=").append(ap)
              .append("(").append(b == null ? "null" : b.getTitle()).append(")");
        }
        DownloadDiag.log("[VISIBLE] " + tag + " " + sb.toString());
    }

    private void updateCount() {
        int totalCount = adapter.getTotalCount();
        int filteredCount = adapter.getTotalFilteredCount();

        // 扫描中:使用预估总数优先显示
        if (estimatedCount > totalCount) {
            tvCount.setText("共 " + estimatedCount + " 首(扫描中...)");
            return;
        }

        if (totalCount == 0) {
            tvCount.setText("");
            return;
        }

        // 有搜索或收藏过滤时,显示 "匹配数/总数"
        boolean isFiltering = !currentSearchQuery.isEmpty() || favoritesOnly;
        if (isFiltering && filteredCount != totalCount) {
            tvCount.setText(filteredCount + "/" + totalCount + " 首");
        } else {
            tvCount.setText("共 " + totalCount + " 首");
        }
    }

    private void updateNowPlaying(int index) {
        // 优先从 service 获取当前歌曲(播放列表可能和 musicList 不同)
        MusicBean bean = null;
        if (service != null) {
            bean = service.getCurrentMusic();
        }
        if (bean == null && index >= 0 && index < musicList.size()) {
            bean = musicList.get(index);
        }
        if (bean == null) {
            tvNowTitle.setText("未在播放");
            tvNowArtist.setText("");
            sbProgress.setMax(0);
            sbProgress.setProgress(0);
            tvCurrentTime.setText("00:00");
            tvTotalTime.setText("00:00");
            // 清除歌词区封面
            lrcView.setCoverBitmap(null);
            // 重置收藏按钮
            btnFav.setText("\u2661");
            btnFav.setTextColor(colorFavInactive);
            return;
        }
        tvNowTitle.setText(bean.getTitle());
        tvNowArtist.setText(bean.getArtist());
        sbProgress.setMax((int) bean.getDuration());
        tvTotalTime.setText(MusicBean.formatDuration(bean.getDuration()));

        // 更新底栏收藏按钮状态
        updateFavoriteButton(bean);

        // 加载封面到歌词区作为背景(高清大图,全分辨率)
        // 使用 token 防止旧回调覆盖:多次暂停/恢复会产生多个异步封面加载请求
        // 只允许最新一次请求的回调设置封面,避免封面错乱
        final int token = ++coverLoadToken;
        int coverSize = 1024; // 背景封面尺寸
        CoverLoader.getInstance().loadBitmapFull(bean, coverSize,
                new CoverLoader.BitmapCallback() {
                    @Override
                    public void onBitmapLoaded(android.graphics.Bitmap bitmap) {
                        if (token == coverLoadToken) {
                            lrcView.setCoverBitmap(bitmap);
                        }
                    }
                });
    }

    /** 更新底栏收藏按钮图标(根据当前歌曲收藏状态) */
    private void updateFavoriteButton(MusicBean bean) {
        if (bean == null || favoriteManager == null) {
            btnFav.setText("\u2661");
            btnFav.setTextColor(colorFavInactive);
            return;
        }
        boolean isFav = favoriteManager.isFavorite(bean);
        btnFav.setText(isFav ? "\u2665" : "\u2661");
        btnFav.setTextColor(isFav ? colorFavActive : colorFavInactive);
    }

    /**
     * 滚动列表到当前播放歌曲位置
     * 用歌曲身份匹配 filteredData,确保即使播放列表和显示列表不一致也能正确定位
     */
    private void scrollToCurrentSong() {
        if (service == null) return;
        MusicBean current = service.getCurrentMusic();
        if (current == null) return;

        // 在显示列表中查找当前播放歌曲的位置
        int pos = adapter.findPositionByBean(current);

        // 无论是否找到都更新高亮(找不到时清除高亮,避免错误高亮)
        updatePlayingHighlight();

        if (pos < 0) return;

        // 确保该位置数据已加载(分批加载机制)
        adapter.ensureLoaded(pos);

        // 滚动到该位置并定位到列表中间(车机性能弱,不用平滑滚动)
        LinearLayoutManager lm = (LinearLayoutManager) rvList.getLayoutManager();
        if (lm != null) {
            // 检查当前是否可见,不可见才滚动(避免不必要的跳动)
            int firstVisible = lm.findFirstVisibleItemPosition();
            int lastVisible = lm.findLastVisibleItemPosition();
            if (pos < firstVisible || pos > lastVisible) {
                int rvHeight = rvList.getHeight();
                // 用已有子项高度估算 item 高度,计算居中偏移
                int itemHeight = 80;
                View firstChild = lm.getChildAt(0);
                if (firstChild != null && firstChild.getHeight() > 0) {
                    itemHeight = firstChild.getHeight();
                }
                int offset = Math.max(0, (rvHeight - itemHeight) / 2);
                lm.scrollToPositionWithOffset(pos, offset);
            }
        }
    }

    /**
     * 仅更新播放高亮(不滚动列表)
     * 在切换收藏/搜索过滤后调用,确保高亮跟随当前播放歌曲
     * 如果当前播放歌曲不在过滤后的列表中,清除高亮
     */
    private void updatePlayingHighlight() {
        long t0 = System.currentTimeMillis();
        if (service == null) {
            Log.i(TAG, "[updatePlayingHighlight] service=null " + (System.currentTimeMillis() - t0) + "ms");
            return;
        }
        MusicBean current = service.getCurrentMusic();
        if (current == null) {
            adapter.setPlayingIndex(-1);
            Log.i(TAG, "[updatePlayingHighlight] current=null " + (System.currentTimeMillis() - t0) + "ms");
            return;
        }
        long t1 = System.currentTimeMillis();
        int pos = adapter.findPositionByBean(current);
        Log.i(TAG, "[updatePlayingHighlight] findPosition=" + pos + " " + (System.currentTimeMillis() - t1) + "ms");
        if (pos >= 0) {
            // 确保该位置数据已加载(搜索清空后当前歌曲可能在分批加载范围之外)
            long t2 = System.currentTimeMillis();
            adapter.ensureLoaded(pos);
            Log.i(TAG, "[updatePlayingHighlight] ensureLoaded=" + (System.currentTimeMillis() - t2) + "ms");
        }
        adapter.setPlayingIndex(pos);
        Log.i(TAG, "[updatePlayingHighlight] 总=" + (System.currentTimeMillis() - t0) + "ms pos=" + pos);
        if (PerfLogger.isEnabled()) {
            PerfLogger.log("Highlight", "pos=" + pos + " " + (System.currentTimeMillis() - t0) + "ms");
        }
    }

    /** 更新播放按钮:播放中=蓝色圆形+暂停图标,暂停中=红色圆形+播放图标 */
    private void updatePlayButton(boolean playing) {
        if (playing) {
            btnPlay.setText("❚❚");
            btnPlay.setBackgroundResource(R.drawable.bg_btn_circle_big_playing);
            btnPlay.setTextColor(ContextCompat.getColor(this, R.color.btn_playing_text));
        } else {
            btnPlay.setText("▶");
            btnPlay.setBackgroundResource(R.drawable.bg_btn_circle_big_paused);
            btnPlay.setTextColor(ContextCompat.getColor(this, R.color.btn_paused_text));
        }
    }

    /** 更新播放模式按钮文字(顺/随/单) */
    private void updatePlayModeIcon(PlayMode mode) {
        btnMode.setText(mode.getShortLabel());
        btnMode.setCompoundDrawables(null, null, null, null);
    }

    private void updateProgress() {
        if (service == null || !bound) {
            return;
        }
        if (service.isPlaying() || service.getCurrentPosition() > 0) {
            int pos = service.getCurrentPosition();
            int dur = service.getDuration();
            if (dur > 0) {
                sbProgress.setMax(dur);
                sbProgress.setProgress(pos);
                tvCurrentTime.setText(MusicBean.formatDuration(pos));
                tvTotalTime.setText(MusicBean.formatDuration(dur));
            }
        }
    }

    private void updateLrc() {
        if (service == null || !bound) {
            return;
        }
        List<LrcEntry> lrc = service.getCurrentLrc();
        if (lrc == null || lrc.isEmpty()) {
            return;
        }
        int pos = service.getCurrentPosition();
        // 应用每歌曲手动歌词偏移(校正个别歌曲歌词不同步)
        MusicBean current = service.getCurrentMusic();
        if (current != null && lyricOffsetManager != null) {
            long offset = lyricOffsetManager.getOffset(current);
            pos += (int) offset;
        }
        int idx = LrcParser.findLrcIndex(lrc, pos);
        lrcView.setCurrentIndex(idx);
    }

    // ==================== 生命周期 ====================

    @Override
    protected void onResume() {
        long t0 = System.currentTimeMillis();
        super.onResume();
        Log.i(TAG, "[onResume] 开始");
        // 屏幕常亮:onCreate 已加窗口标志,这里再补一次 —— 息屏/解锁/被系统 UI 打扰之后
        // 窗口标志偶尔会没被重新认定(见 ScreenOnKeeper 注释),补一次成本几乎为零。
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        ScreenOnKeeper.acquire(this);
        DownloadDiag.log("[生命周期] onResume | " + DownloadDiag.env());
        // 遮罩兜底:列表区若被加载遮罩留在 INVISIBLE(纯黑且不接收触摸,用户点不动不会自愈),
        // 每次回到前台都检查一次
        ensureListVisible();
        // 从其他页面返回时重新隐藏系统 UI
        hideSystemUI();
        Log.i(TAG, "[onResume] hideSystemUI=" + (System.currentTimeMillis() - t0) + "ms");
        // 从均衡器页面返回时刷新EQ按钮显示(可能修改了设置或新增了自定义预设)
        updateEqButtonText(null);
        // 从设置页面返回时,如果配置有更新则重新加载
        if (needReload) {
            needReload = false;
            MusicSourceApi api = MusicDataHolder.getInstance().getMusicSourceApi();
            // 更新监控器的 API 实例(会触发重新检测)
            if (statusMonitor != null) {
                statusMonitor.updateApi(api);
            }
            // 重新加载音乐(可能改了同步目录或时长过滤)
            loadMusic();
        }

        IntentFilter f = new IntentFilter(MusicService.ACTION_STATE_CHANGED);
        registerReceiver(stateReceiver, f);
        // 注册自动缓存完成/进度接收器(刷新来源标识与缓存进度条)
        IntentFilter cf = new IntentFilter(MusicService.ACTION_CACHE_AVAILABILITY_CHANGED);
        cf.addAction(MusicService.ACTION_CACHE_PROGRESS);
        registerReceiver(cacheReceiver, cf);
        handler.post(progressTask);
        // 恢复本地目录监听(仅本地模式会真正启动 FileObserver)
        syncLocalDirObserver();

        // 同步当前播放状态:从桌面返回时可能已自动切歌,需更新UI
        // onPause 期间 stateReceiver 被注销,自动切歌的广播被错过
        if (service != null && bound) {
            long t1 = System.currentTimeMillis();
            int idx = service.getCurrentIndex();
            updateNowPlaying(idx);
            Log.i(TAG, "[onResume] updateNowPlaying=" + (System.currentTimeMillis() - t1) + "ms");
            long t2 = System.currentTimeMillis();
            updatePlayButton(service.isPlaying());
            updatePlayModeIcon(service.getPlayMode());
            Log.i(TAG, "[onResume] updatePlayButton+Mode=" + (System.currentTimeMillis() - t2) + "ms");
            long t3 = System.currentTimeMillis();
            lrcView.setLrcList(service.getCurrentLrc());
            Log.i(TAG, "[onResume] setLrcList=" + (System.currentTimeMillis() - t3) + "ms");
            // 延迟滚动和高亮到下一帧:scrollToCurrentSong 内部会做 findPositionByBean
            // (O(n)遍历) + ensureLoaded(多批次加载) + scrollToPositionWithOffset,
            // 同步执行会阻塞第一帧渲染导致黑屏。post 让第一帧先画出来
            rvList.post(new Runnable() {
                @Override
                public void run() {
                    long t4 = System.currentTimeMillis();
                    scrollToCurrentSong();
                    Log.i(TAG, "[onResume] scrollToCurrentSong(post)=" + (System.currentTimeMillis() - t4) + "ms");
                }
            });
        }
        Log.i(TAG, "[onResume] 总耗时=" + (System.currentTimeMillis() - t0) + "ms");
        if (PerfLogger.isEnabled()) {
            PerfLogger.log("onResume", "总=" + (System.currentTimeMillis() - t0) + "ms");
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        DownloadDiag.log("[生命周期] onPause | " + DownloadDiag.env());
        ScreenOnKeeper.release();
        unregisterReceiver(stateReceiver);
        try {
            unregisterReceiver(cacheReceiver);
        } catch (Exception ignored) {
        }
        handler.removeCallbacks(progressTask);
        // 退到后台时停止目录监听,节省 2 核车机资源(FileObserver 内部 inotify 线程)
        stopLocalDirObserver();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        // 窗口重新获得焦点时(如关闭弹窗后)重新隐藏系统UI,保持全屏
        if (hasFocus) {
            // 顺带把常亮标志补回来:某些系统在窗口焦点变化时会重算窗口属性,
            // 标志丢了就会表现为"过一会儿黑屏"
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            hideSystemUI();
        }
    }

    /** 上次按返回键的时间戳,用于双击退出判断 */
    private long lastBackPressTime = 0;

    @Override
    public void onBackPressed() {
        long now = System.currentTimeMillis();
        if (now - lastBackPressTime < 2000) {
            // 2秒内再按一次 → 真正退出,停止后台服务
            if (service != null) {
                service.stopSelf();
            }
            if (bound) {
                unbindService(connection);
                bound = false;
            }
            Intent stopIntent = new Intent(this, MusicService.class);
            stopService(stopIntent);
            finish();
        } else {
            // 第一次按 → 提示再按一次退出
            lastBackPressTime = now;
            Toast.makeText(this, "再按一次返回键退出", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onDestroy() {
        // 停止帧率监控和日志刷新
        Choreographer.getInstance().removeFrameCallback(frameCallback);
        handler.removeCallbacks(logFlushTask);
        // 清除 Handler 消息队列中所有残留回调(防止 Activity 销毁后 Runnable 仍执行)
        handler.removeCallbacksAndMessages(null);
        // 停止目录监听,释放 inotify 资源
        stopLocalDirObserver();
        PerfLogger.shutdown();
        // 取消自动同步
        cancelAutoSync();
        // 停止服务器状态监控
        if (statusMonitor != null) {
            statusMonitor.stop();
        }
        if (bound) {
            unbindService(connection);
            bound = false;
        }
        super.onDestroy();
    }
}
