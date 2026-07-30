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
import android.os.Handler;
import android.os.IBinder;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
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
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

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

    private static final int REQ_STORAGE = 100;

    // UI - 列表区
    private RecyclerView rvList;
    private TextView tvEmpty, tvCount, tvSyncStatus;
    // UI - 顶栏
    private EditText etSearch;
    private Button btnSettings, btnFavorites, btnEq;
    private TextView tvServerStatus;
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

    /** 当前音乐列表(扫描同步目录) */
    private final List<MusicBean> musicList = new ArrayList<>();

    private NavidromeConfig navidromeConfig;
    /** 网络歌曲列表缓存(同步后保存,下次秒开) */
    private SongCache songCache;
    /** 本地歌曲列表缓存(扫描后保存,下次秒开) */
    private LocalMusicCache localMusicCache;
    /** 收藏管理器 */
    private FavoriteManager favoriteManager;
    /** 是否正在只显示收藏(收藏夹模式) */
    private boolean favoritesOnly = false;
    /** 从设置页返回时需重新加载 */
    private boolean needReload = false;

    /** 服务器状态监控器 */
    private ServerStatusMonitor statusMonitor;

    /** 自动同步管理器(后台自动下载) */
    private MusicSyncManager syncManager;
    /** 是否正在自动同步 */
    private boolean isAutoSyncing = false;
    /** 待刷新计数器(累积N首后刷新一次列表) */
    private int pendingSyncRefresh = 0;
    private static final int REFRESH_BATCH_SIZE = 5;

    // 进度刷新(动态频率:播放时100ms高精度歌词同步,空闲时2000ms)
    private final Handler handler = new Handler();
    private final Runnable progressTask = new Runnable() {
        @Override
        public void run() {
            updateProgress();
            updateLrc();
            // 根据播放状态调整刷新频率
            boolean playing = service != null && service.isPlaying();
            handler.postDelayed(this, playing ? 100 : 2000);
        }
    };

    /** 当前搜索关键词 */
    private String currentSearchQuery = "";

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
                btnMode.setText(mode.getShortLabel());
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
            btnMode.setText(service.getPlayMode().getShortLabel());
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

        // 全屏沉浸模式:隐藏状态栏和虚拟导航键
        hideSystemUI();

        setContentView(R.layout.activity_main);

        navidromeConfig = new NavidromeConfig(this);
        songCache = new SongCache(this);
        localMusicCache = new LocalMusicCache(this);
        favoriteManager = new FavoriteManager(this);

        // 初始化 NavidromeApi(如果已配置)
        if (navidromeConfig.isConfigured()) {
            NavidromeApi api = new NavidromeApi(
                    navidromeConfig.getServerUrl(),
                    navidromeConfig.getUsername(),
                    navidromeConfig.getPassword());
            MusicDataHolder.getInstance().setNavidromeApi(api);
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
                statusMonitor.start(MusicDataHolder.getInstance().getNavidromeApi());

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
        tvCount = findViewById(R.id.tv_count);
        tvSyncStatus = findViewById(R.id.tv_sync_status);
        etSearch = findViewById(R.id.et_search);
        btnSettings = findViewById(R.id.btn_settings);
        btnFavorites = findViewById(R.id.btn_favorites);
        btnEq = findViewById(R.id.btn_eq);
        tvServerStatus = findViewById(R.id.tv_server_status);
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
        adapter.setOnItemClickListener((position, bean) -> {
            if (service != null) {
                // 用当前显示的列表作为播放列表
                List<MusicBean> displayList = adapter.getDisplayList();
                // 用 bean 身份验证位置(防止列表变化后位置错位导致"乱跳")
                int realPos = adapter.findPositionByBean(bean);
                if (realPos >= 0 && realPos != position) {
                    position = realPos;
                }
                service.setPlayList(displayList, position);
                service.playIndex(position);
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
        // 滑动状态监听:快速滑动(惯性)时暂停封面加载,停止后恢复
        // 避免大量 MediaMetadataRetriever 调用阻塞单线程,导致卡顿
        rvList.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(RecyclerView recyclerView, int newState) {
                if (newState == RecyclerView.SCROLL_STATE_SETTLING) {
                    // 惯性滑动中:暂停封面加载,清空积压队列
                    CoverLoader.getInstance().setPaused(true);
                } else if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                    // 停止滑动:恢复加载,刷新当前可见项并预加载附近封面
                    CoverLoader.getInstance().setPaused(false);
                    LinearLayoutManager lm = (LinearLayoutManager) rvList.getLayoutManager();
                    if (lm == null) return;
                    int firstVisible = lm.findFirstVisibleItemPosition();
                    int lastVisible = lm.findLastVisibleItemPosition();
                    if (firstVisible < 0 || lastVisible < 0) return;

                    // 刷新当前可见项(触发封面重新加载)
                    adapter.notifyItemRangeChanged(firstVisible, lastVisible - firstVisible + 1);

                    // 预加载上下各 10 个 item 的封面(U盘场景下提前缓存,减少后续滚动IO)
                    int preloadRange = 10;
                    int preloadStart = Math.max(0, firstVisible - preloadRange);
                    int preloadEnd = Math.min(adapter.getItemCount() - 1, lastVisible + preloadRange);
                    int coverSizePx = (int) getResources().getDimension(R.dimen.cover_size_list);
                    for (int i = preloadStart; i <= preloadEnd; i++) {
                        MusicBean bean = adapter.getFilteredItem(i);
                        if (bean != null) {
                            CoverLoader.getInstance().preload(bean, coverSizePx);
                        }
                    }
                }
            }
        });
        tvEmpty.setText("正在扫描本地音乐...");
        tvEmpty.setVisibility(View.VISIBLE);
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

        // 设置(均衡器 + 服务器统一入口)
        btnSettings.setOnClickListener(v -> {
            showSettingsMenu();
        });

        // 均衡器快捷按钮:弹出预设模式快速切换
        btnEq.setOnClickListener(v -> {
            showEqualizerQuickSwitch();
        });

        // 收藏夹:切换只看收藏
        btnFavorites.setOnClickListener(v -> {
            favoritesOnly = !favoritesOnly;
            if (favoritesOnly) {
                btnFavorites.setBackgroundResource(R.drawable.bg_btn_play);
                applyFavoritesFilter();
            } else {
                btnFavorites.setBackgroundResource(R.drawable.bg_btn);
                // 恢复搜索或全部
                adapter.filter(currentSearchQuery);
                updateCount();
                // 隐藏"还没有收藏"的空提示
                if (!musicList.isEmpty()) {
                    tvEmpty.setVisibility(View.GONE);
                }
            }
        });

        // 点击服务器状态可手动刷新
        tvServerStatus.setOnClickListener(v -> {
            if (statusMonitor != null && MusicDataHolder.getInstance().getNavidromeApi() != null) {
                Toast.makeText(this, "正在检测服务器连接...", Toast.LENGTH_SHORT).show();
                statusMonitor.checkNow();
            }
        });

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
                btnMode.setText(mode.getShortLabel());
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
            // 收藏状态变化时,如果在收藏夹模式,刷新列表
            if (favoritesOnly) {
                applyFavoritesFilter();
            }
            Toast.makeText(this, nowFav ? "已收藏" : "取消收藏", Toast.LENGTH_SHORT).show();
        });
    }

    // ==================== 搜索 ====================

    /**
     * 处理搜索栏输入(即时过滤本地文件)
     */
    private void handleSearchInput(String query) {
        currentSearchQuery = query != null ? query.trim() : "";
        if (favoritesOnly) {
            applyFavoritesFilter();
        } else {
            adapter.filter(currentSearchQuery);
        }
        updateCount();
    }

    // ==================== 收藏夹 ====================

    /** 应用收藏过滤:只显示已收藏的歌曲(同时应用搜索关键词) */
    private void applyFavoritesFilter() {
        int count = favoriteManager.size();
        if (count == 0) {
            Toast.makeText(this, "还没有收藏的歌曲", Toast.LENGTH_SHORT).show();
        }
        // 设置搜索关键词,使 filterFavorites 也按搜索过滤
        adapter.setSearchKeyword(currentSearchQuery);
        adapter.filterFavorites(favoriteManager);
        updateCount();
        if (count == 0) {
            tvEmpty.setVisibility(View.VISIBLE);
            tvEmpty.setText("还没有收藏的歌曲\n播放歌曲时点击底栏爱心收藏");
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

        boolean hasNetCache = songCache.exists();
        boolean hasLocalCache = localMusicCache.exists();
        StringBuilder sb = new StringBuilder();
        if (hasNetCache) {
            sb.append("网络缓存: ").append(formatCacheTime(songCache.getCachedAt())).append("\n");
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
                    songCache.clear();
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
                    URL url = new URL("https://api.github.com/repos/" + GITHUB_OWNER + "/" + GITHUB_REPO
                            + "/releases?per_page=30");
                    conn = (HttpURLConnection) url.openConnection();
                    conn.setRequestMethod("GET");
                    conn.setRequestProperty("Accept", "application/vnd.github+json");
                    conn.setConnectTimeout(15000);
                    conn.setReadTimeout(15000);
                    conn.connect();

                    int code = conn.getResponseCode();
                    if (code != 200) {
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                Toast.makeText(MainActivity.this, "检查更新失败: 网络错误(" + code + ")",
                                        Toast.LENGTH_SHORT).show();
                            }
                        });
                        return;
                    }

                    InputStream is = conn.getInputStream();
                    BufferedReader reader = new BufferedReader(new InputStreamReader(is, "UTF-8"));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        sb.append(line);
                    }
                    reader.close();

                    JSONArray releases = new JSONArray(sb.toString());

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
            // 有 APK 直链,应用内下载并显示进度条
            Button btnDownload = createDialogButton("下载更新", true);
            btnDownload.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    dRef[0].dismiss();
                    downloadAndInstallApk(apkUrl, tag);
                }
            });
            sd.buttons.addView(btnDownload);

            Button btnDetails = createDialogButton("查看详情", false);
            btnDetails.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(releaseUrl));
                    startActivity(browserIntent);
                }
            });
            sd.buttons.addView(btnDetails);
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
                HttpURLConnection conn = null;
                InputStream is = null;
                java.io.FileOutputStream fos = null;
                try {
                    URL url = new URL(apkUrl);
                    conn = (HttpURLConnection) url.openConnection();
                    conn.setRequestMethod("GET");
                    conn.setRequestProperty("Accept", "application/octet-stream");
                    conn.setConnectTimeout(30000);
                    conn.setReadTimeout(30000);
                    conn.connect();

                    int responseCode = conn.getResponseCode();
                    if (responseCode != 200) {
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                if (downloadDialog != null && downloadDialog.isShowing()) {
                                    downloadDialog.dismiss();
                                }
                                Toast.makeText(MainActivity.this, "下载失败: 服务器错误(" + responseCode + ")",
                                        Toast.LENGTH_SHORT).show();
                            }
                        });
                        return;
                    }

                    final int totalSize = conn.getContentLength();
                    is = conn.getInputStream();

                    // 保存到外部存储(安卓 4.2.2 兼容)
                    File downloadDir = android.os.Environment.getExternalStoragePublicDirectory(
                            android.os.Environment.DIRECTORY_DOWNLOADS);
                    if (!downloadDir.exists()) {
                        downloadDir.mkdirs();
                    }
                    String fileName = "captiva-music-" + versionTag + ".apk";
                    final File apkFile = new File(downloadDir, fileName);
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
                                // 捕获当前已下载字节数(匿名类只能引用final变量)
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
                                            downloadStatus.setText("正在下载: " + sizeStr);
                                        }
                                    }
                                });
                            }
                        }
                    }

                    fos.flush();
                    fos.close();
                    fos = null;

                    // 下载完成,关闭进度对话框,弹出安装
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (downloadDialog != null && downloadDialog.isShowing()) {
                                downloadDialog.dismiss();
                            }
                            Toast.makeText(MainActivity.this, "下载完成,正在安装...",
                                    Toast.LENGTH_SHORT).show();
                            installApk(apkFile);
                        }
                    });

                } catch (final Exception e) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (downloadDialog != null && downloadDialog.isShowing()) {
                                downloadDialog.dismiss();
                            }
                            Toast.makeText(MainActivity.this, "下载失败: " + e.getMessage(),
                                    Toast.LENGTH_SHORT).show();
                        }
                    });
                } finally {
                    if (fos != null) { try { fos.close(); } catch (Exception e) {} }
                    if (is != null) { try { is.close(); } catch (Exception e) {} }
                    if (conn != null) conn.disconnect();
                }
            }
        }, "ApkDownload");
        downloadThread.start();
    }

    /** 弹出系统安装器安装 APK(安卓 4.2.2 兼容) */
    private void installApk(File apkFile) {
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(Uri.fromFile(apkFile), "application/vnd.android.package-archive");
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
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
        if (tvServerStatus == null) return;

        String text;
        int color;

        switch (status) {
            case CONNECTED:
                text = "●已连接";
                color = ContextCompat.getColor(this, R.color.server_status_connected);
                break;
            case CONNECTING:
                text = "●连接中";
                color = ContextCompat.getColor(this, R.color.server_status_connecting);
                break;
            case RETRYING:
                text = "●" + message;
                color = ContextCompat.getColor(this, R.color.server_status_retrying);
                break;
            case DISCONNECTED:
                text = "●未连接";
                color = ContextCompat.getColor(this, R.color.server_status_disconnected);
                break;
            case OFFLINE:
            default:
                text = "●离线";
                color = ContextCompat.getColor(this, R.color.server_status_offline);
                break;
        }

        tvServerStatus.setText(text);
        tvServerStatus.setTextColor(color);
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
     * 1. 先用 MediaStore 快速加载(秒开,本地音乐立即可见可播)
     * 2. 后台递归扫描补全(MediaStore 未收录的文件)
     * 3. 后台自动同步服务器新歌(不阻塞 UI)
     */
    private void loadMusic() {
        final String syncPath = navidromeConfig.getSyncPath();

        // 设置扫描路径为同步目录
        navidromeConfig.setScanPath(syncPath);

        // 0. 优先从本地缓存加载(秒开,完全不读U盘)
        //    列表纯从缓存来,只有用户点击歌曲时才从U盘读取文件播放
        //    新增/删除歌曲需手动"刷新列表"(设置菜单)
        List<MusicBean> cachedList = localMusicCache.load();
        if (cachedList != null && !cachedList.isEmpty()) {
            musicList.clear();
            musicList.addAll(cachedList);
            // 排序(确保和刷新后的顺序一致,避免视觉跳动)
            java.util.Collections.sort(musicList, new java.util.Comparator<MusicBean>() {
                @Override
                public int compare(MusicBean a, MusicBean b) {
                    return a.getTitle().compareToIgnoreCase(b.getTitle());
                }
            });
            adapter.setData(musicList);
            updateCount();
            tvEmpty.setVisibility(View.GONE);
            // 立即设置播放列表
            if (service != null) {
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
            // 缓存路径不扫描U盘(用户要求:点击歌曲才读U盘)
            // 仅启动后台服务器同步(如果配置了Navidrome)
            startBackgroundSync();
            return;
        }

        // 1. 无缓存:用 MediaStore 快速加载(秒开)
        final List<MusicBean> quickList = MusicScanner.scanMediaStoreOnly(this, syncPath);

        musicList.clear();
        musicList.addAll(quickList);
        adapter.setData(musicList);
        updateCount();

        if (musicList.isEmpty()) {
            tvEmpty.setVisibility(View.VISIBLE);
            tvEmpty.setText("未找到音乐\n请在设置中配置服务器并同步");
        } else {
            tvEmpty.setVisibility(View.GONE);
            if (service != null) {
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
        backgroundScanAndMerge(syncPath, quickList, false);
    }

    /**
     * 手动刷新歌曲列表:从U盘重新扫描
     * 适用场景:用户在U盘新增/删除了歌曲,需要更新列表
     * 扫描在后台线程执行,不阻塞UI
     */
    private void refreshMusicList() {
        final String syncPath = navidromeConfig.getSyncPath();
        if (syncPath == null || syncPath.isEmpty()) {
            Toast.makeText(this, "未配置扫描目录", Toast.LENGTH_SHORT).show();
            return;
        }

        // 显示扫描进度
        tvSyncStatus.setVisibility(View.VISIBLE);
        tvSyncStatus.setText("正在扫描U盘...");

        new Thread(new Runnable() {
            @Override
            public void run() {
                // 完整扫描U盘目录
                final List<MusicBean> fullList = MusicScanner.scanDirectoryOnly(MainActivity.this, syncPath);

                // 排序
                java.util.Collections.sort(fullList, new java.util.Comparator<MusicBean>() {
                    @Override
                    public int compare(MusicBean a, MusicBean b) {
                        return a.getTitle().compareToIgnoreCase(b.getTitle());
                    }
                });

                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        tvSyncStatus.setVisibility(View.GONE);

                        if (fullList.isEmpty()) {
                            Toast.makeText(MainActivity.this, "未找到音乐文件", Toast.LENGTH_SHORT).show();
                            return;
                        }

                        // 记录旧数量用于提示
                        int oldCount = musicList.size();

                        // 用扫描结果替换当前列表
                        musicList.clear();
                        musicList.addAll(fullList);
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
                            adapter.setPlayingIndex(newIndex);
                        }

                        // 保存到缓存(下次秒开) — 强制保存(内容可能变化但数量不变)
                        localMusicCache.forceSaveAsync(musicList);

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
                        java.util.Collections.sort(musicList, new java.util.Comparator<MusicBean>() {
                            @Override
                            public int compare(MusicBean a, MusicBean b) {
                                return a.getTitle().compareToIgnoreCase(b.getTitle());
                            }
                        });
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
                            // 同步更新列表高亮(避免高亮错位)
                            adapter.setPlayingIndex(newIndex);
                        }

                        // 保存缓存(下次秒开) — 强制保存(扫描后内容可能变化)
                        localMusicCache.forceSaveAsync(musicList);
                    }
                });

                // 3. 后台自动同步服务器新歌
                startBackgroundSync();
            }
        }).start();
    }

    /** 生成歌曲唯一key(用于匹配播放位置,与 MusicAdapter.getSongKey 规则一致) */
    private String getSongKey(MusicBean b) {
        if (b == null) return "";
        if (b.isNetwork()) {
            return "net_" + b.getStreamId();
        } else {
            String data = b.getData();
            if (data != null && !data.isEmpty()) {
                return "local_" + MusicScanner.normalizePath(data);
            }
            return "local_" + b.getId();
        }
    }

    /**
     * 后台自动同步
     * 直接启动 MusicSyncManager.sync()
     * - sync() 内部从服务器获取最新歌曲列表(确保发现新歌)
     * - 逐首检查文件是否存在,已存在的跳过(增量同步)
     * - 全部已存在才显示"已是最新",否则只下载缺失的文件
     * 注意:此方法可能从后台线程调用,startAutoSync 内部操作了 UI,
     *       所以必须切到主线程执行。
     */
    private void startBackgroundSync() {
        final NavidromeApi api = MusicDataHolder.getInstance().getNavidromeApi();
        if (api == null || !MusicDataHolder.getInstance().isNavidromeEnabled()) {
            return;
        }
        if (isAutoSyncing) {
            return; // 已在同步中
        }

        final String syncPath = navidromeConfig.getSyncPath();

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
        final NavidromeApi api = MusicDataHolder.getInstance().getNavidromeApi();
        if (api == null) return;

        isAutoSyncing = true;
        pendingSyncRefresh = 0;
        syncManager = new MusicSyncManager(this, api, syncPath);

        tvSyncStatus.setVisibility(View.VISIBLE);
        tvSyncStatus.setText("同步中...");

        new Thread(new Runnable() {
            @Override
            public void run() {
                syncManager.sync(new MusicSyncManager.SyncCallback() {
                    @Override
                    public void onStart(final int totalSongs) {
                        handler.post(new Runnable() {
                            @Override
                            public void run() {
                                tvSyncStatus.setVisibility(View.VISIBLE);
                                tvSyncStatus.setText("同步 准备中.../" + totalSongs);
                            }
                        });
                    }

                    @Override
                    public void onProgress(final int downloaded, final int total, final String currentSong) {
                        handler.post(new Runnable() {
                            @Override
                            public void run() {
                                tvSyncStatus.setText("同步 " + downloaded + "/" + total);
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
                                refreshSyncList();
                                // 同步完成:清除无封面黑名单,允许重新尝试(新文件可能带封面)
                                CoverLoader.getInstance().clearNoCoverCache();
                                if (downloaded > 0) {
                                    tvSyncStatus.setText("已同步 +" + downloaded + " 首");
                                } else {
                                    tvSyncStatus.setText("已是最新");
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
                });
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
        final String syncPath = navidromeConfig.getSyncPath();
        if (syncPath == null || syncPath.isEmpty()) return;

        new Thread(new Runnable() {
            @Override
            public void run() {
                navidromeConfig.setScanPath(syncPath);
                final List<MusicBean> newList = MusicScanner.scan(MainActivity.this);

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
                        if (favoritesOnly) {
                            // 收藏夹模式:重新设置数据后重新过滤收藏
                            if (!toAdd.isEmpty()) {
                                musicList.addAll(toAdd);
                                java.util.Collections.sort(musicList, new java.util.Comparator<MusicBean>() {
                                    @Override
                                    public int compare(MusicBean a, MusicBean b) {
                                        return a.getTitle().compareToIgnoreCase(b.getTitle());
                                    }
                                });
                            }
                            adapter.setData(musicList);
                            applyFavoritesFilter();
                            if (service != null && !musicList.isEmpty()) {
                                service.setPlayList(musicList, 0);
                            }
                        } else if (currentSearchQuery.isEmpty()) {
                            if (!toAdd.isEmpty()) {
                                musicList.addAll(toAdd);
                                java.util.Collections.sort(musicList, new java.util.Comparator<MusicBean>() {
                                    @Override
                                    public int compare(MusicBean a, MusicBean b) {
                                        return a.getTitle().compareToIgnoreCase(b.getTitle());
                                    }
                                });
                                adapter.setData(musicList);
                                if (service != null && !musicList.isEmpty()) {
                                    service.setPlayList(musicList, 0);
                                }
                            }
                        } else {
                            musicList.clear();
                            musicList.addAll(newList);
                            adapter.setData(musicList);
                            adapter.filter(currentSearchQuery);
                            if (service != null) {
                                service.setPlayList(musicList, 0);
                            }
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
        int coverSize = 1024; // 背景封面尺寸
        CoverLoader.getInstance().loadBitmapFull(bean, coverSize,
                new CoverLoader.BitmapCallback() {
                    @Override
                    public void onBitmapLoaded(android.graphics.Bitmap bitmap) {
                        lrcView.setCoverBitmap(bitmap);
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
        if (pos < 0) return;

        // 确保该位置数据已加载(分批加载机制)
        adapter.ensureLoaded(pos);

        // 更新高亮索引
        adapter.setPlayingIndex(pos);

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
        int idx = LrcParser.findLrcIndex(lrc, pos);
        lrcView.setCurrentIndex(idx);
    }

    // ==================== 生命周期 ====================

    @Override
    protected void onResume() {
        super.onResume();
        // 从其他页面返回时重新隐藏系统 UI
        hideSystemUI();
        // 从均衡器页面返回时刷新EQ按钮显示(可能修改了设置或新增了自定义预设)
        updateEqButtonText(null);
        // 从设置页面返回时,如果配置有更新则重新加载
        if (needReload) {
            needReload = false;
            NavidromeApi api = MusicDataHolder.getInstance().getNavidromeApi();
            // 更新监控器的 API 实例(会触发重新检测)
            if (statusMonitor != null) {
                statusMonitor.updateApi(api);
            }
            // 重新加载音乐(可能改了同步目录或时长过滤)
            loadMusic();
        }

        IntentFilter f = new IntentFilter(MusicService.ACTION_STATE_CHANGED);
        registerReceiver(stateReceiver, f);
        handler.post(progressTask);
    }

    @Override
    protected void onPause() {
        super.onPause();
        unregisterReceiver(stateReceiver);
        handler.removeCallbacks(progressTask);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        // 窗口重新获得焦点时(如关闭弹窗后)重新隐藏系统UI,保持全屏
        if (hasFocus) {
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
