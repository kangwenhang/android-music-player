package com.captiva.musicplayer;

import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.SimpleItemAnimator;

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
    private Button btnSettings, btnFavorites;
    private TextView tvServerStatus;
    // UI - 控制区
    private TextView tvNowTitle, tvNowArtist, tvCurrentTime, tvTotalTime;
    private SeekBar sbProgress;
    private Button btnPrev, btnPlay, btnNext, btnMode;
    // UI - 歌词区(封面做底色)
    private LrcView lrcView;

    private MusicAdapter adapter;
    private MusicService service;
    private boolean bound = false;

    /** 当前音乐列表(扫描同步目录) */
    private final List<MusicBean> musicList = new ArrayList<>();

    private NavidromeConfig navidromeConfig;
    /** 网络歌曲列表缓存(同步后保存,下次秒开) */
    private SongCache songCache;
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

    // 进度刷新(动态频率:播放时500ms,空闲时2000ms)
    private final Handler handler = new Handler();
    private final Runnable progressTask = new Runnable() {
        @Override
        public void run() {
            updateProgress();
            updateLrc();
            // 根据播放状态调整刷新频率
            boolean playing = service != null && service.isPlaying();
            handler.postDelayed(this, playing ? 500 : 2000);
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

                adapter.setPlayingIndex(index);
                updateNowPlaying(index);
                updatePlayButton(playing);
                btnMode.setText(mode.getShortLabel());
                if (service != null) {
                    lrcView.setLrcList(service.getCurrentLrc());
                }
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
                service.setPlayList(musicList, 0);
            }
            int idx = service.getCurrentIndex();
            adapter.setPlayingIndex(idx);
            updateNowPlaying(idx);
            updatePlayButton(service.isPlaying());
            btnMode.setText(service.getPlayMode().getShortLabel());
            lrcView.setLrcList(service.getCurrentLrc());
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

        // 启动服务器状态监控
        statusMonitor.start(MusicDataHolder.getInstance().getNavidromeApi());

        // 启动并绑定服务
        Intent si = new Intent(this, MusicService.class);
        startService(si);
        bindService(si, connection, Context.BIND_AUTO_CREATE);

        // 默认加载音乐(扫描同步目录)
        if (hasStoragePermission()) {
            loadMusic();
        } else {
            ActivityCompat.requestPermissions(this,
                    new String[]{android.Manifest.permission.READ_EXTERNAL_STORAGE}, REQ_STORAGE);
        }
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
        tvServerStatus = findViewById(R.id.tv_server_status);
        tvNowTitle = findViewById(R.id.tv_now_title);
        tvNowArtist = findViewById(R.id.tv_now_artist);
        tvCurrentTime = findViewById(R.id.tv_current_time);
        tvTotalTime = findViewById(R.id.tv_total_time);
        sbProgress = findViewById(R.id.sb_progress);
        btnPrev = findViewById(R.id.btn_prev);
        btnPlay = findViewById(R.id.btn_play);
        btnNext = findViewById(R.id.btn_next);
        btnMode = findViewById(R.id.btn_mode);
        lrcView = findViewById(R.id.lrc_view);

        adapter = new MusicAdapter(this);
        adapter.setFavoriteManager(favoriteManager);
        adapter.setOnItemClickListener((position, bean) -> {
            if (service != null) {
                // 用当前显示的列表作为播放列表
                List<MusicBean> displayList = adapter.getDisplayList();
                service.setPlayList(displayList, position);
                service.playIndex(position);
            }
        });
        adapter.setOnFavoriteClickListener((bean, isNowFavorite) -> {
            // 收藏状态变化时,如果在收藏夹模式,刷新列表
            if (favoritesOnly) {
                applyFavoritesFilter();
            }
            Toast.makeText(this, isNowFavorite ? "已收藏" : "取消收藏", Toast.LENGTH_SHORT).show();
        });
        rvList.setLayoutManager(new LinearLayoutManager(this));
        // 关闭 item 动画(车机性能弱,动画卡顿)
        RecyclerView.ItemAnimator animator = rvList.getItemAnimator();
        if (animator instanceof SimpleItemAnimator) {
            ((SimpleItemAnimator) animator).setSupportsChangeAnimations(false);
        }
        // 增大缓存池(减少滑动时重新绑定)
        rvList.setItemViewCacheSize(20);
        // 硬件层加速列表滑动(车机性能弱时减少 CPU 绘制)
        rvList.setHasFixedSize(true);
        rvList.setAdapter(adapter);
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

    /** 应用收藏过滤:只显示已收藏的歌曲 */
    private void applyFavoritesFilter() {
        int count = favoriteManager.size();
        if (count == 0) {
            Toast.makeText(this, "还没有收藏的歌曲", Toast.LENGTH_SHORT).show();
        }
        adapter.filterFavorites(favoriteManager);
        updateCount();
        if (count == 0) {
            tvEmpty.setVisibility(View.VISIBLE);
            tvEmpty.setText("还没有收藏的歌曲\n点击歌曲右侧爱心收藏");
        } else {
            tvEmpty.setVisibility(View.GONE);
        }
    }

    // ==================== 设置菜单 ====================

    /** 弹出设置菜单:均衡器 / 服务器设置 / 时长过滤 / 清除缓存 */
    private void showSettingsMenu() {
        String[] items = {"均衡器", "服务器设置", "时长过滤设置", "清除网络缓存"};
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("设置");
        builder.setItems(items, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                if (which == 0) {
                    // 均衡器
                    openEqualizer();
                } else if (which == 1) {
                    // 服务器设置
                    needReload = true;
                    startActivity(new Intent(MainActivity.this, ServerSettingsActivity.class));
                } else if (which == 2) {
                    // 时长过滤设置
                    showDurationFilterDialog();
                } else if (which == 3) {
                    // 清除网络缓存
                    showClearCacheDialog();
                }
            }
        });
        builder.show();
    }

    /** 清除网络缓存确认对话框 */
    private void showClearCacheDialog() {
        boolean hasCache = songCache.exists();
        String msg = hasCache
                ? "缓存时间: " + formatCacheTime(songCache.getCachedAt())
                : "当前无缓存数据";
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("清除歌曲列表缓存");
        builder.setMessage(msg + "\n此操作仅清除歌曲列表缓存,不影响已下载的音乐文件");
        if (hasCache) {
            builder.setPositiveButton("清除", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    songCache.clear();
                    Toast.makeText(MainActivity.this, "缓存已清除", Toast.LENGTH_SHORT).show();
                }
            });
            builder.setNegativeButton("取消", null);
        } else {
            builder.setPositiveButton("确定", null);
        }
        builder.show();
    }

    /** 时长过滤设置对话框:自定义输入秒数 */
    private void showDurationFilterDialog() {
        final int currentMin = navidromeConfig.getMinDuration();

        // 创建输入框
        final EditText etInput = new EditText(this);
        etInput.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        etInput.setText(currentMin > 0 ? String.valueOf(currentMin) : "");
        etInput.setHint("输入秒数,如30(0表示不过滤)");
        etInput.setTextColor(getResources().getColor(R.color.text_primary));
        etInput.setHintTextColor(getResources().getColor(R.color.search_hint));
        etInput.setPadding(24, 16, 24, 16);
        etInput.setBackgroundResource(R.drawable.bg_search);

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("最小时长过滤(秒)");
        builder.setMessage("低于此时长的音频将被过滤\n输入0表示显示全部");
        builder.setView(etInput);
        builder.setPositiveButton("确定", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
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
                // 重新加载音乐
                loadMusic();
            }
        });
        builder.setNegativeButton("取消", null);
        builder.show();
    }

    /** 打开均衡器(需播放状态) */
    private void openEqualizer() {
        if (service != null && service.isPlaying()) {
            startActivity(new Intent(this, EqualizerActivity.class));
        } else {
            Toast.makeText(this, "请先开始播放音乐,均衡器才能生效", Toast.LENGTH_SHORT).show();
            if (service != null && !musicList.isEmpty()) {
                service.setPlayList(musicList, 0);
                service.playIndex(0);
            }
        }
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

        // 1. 先用 MediaStore 快速加载(秒开)
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
            // 立即设置播放列表,本地音乐可播
            if (service != null) {
                service.setPlayList(musicList, 0);
            }
        }

        // 2. 后台递归扫描补全 + 同步(不阻塞 UI)
        new Thread(new Runnable() {
            @Override
            public void run() {
                // 后台完整扫描(递归遍历目录,含 MediaStore 未收录的文件)
                final List<MusicBean> fullList = MusicScanner.scanDirectoryOnly(MainActivity.this, syncPath);

                // 合并新发现的文件(MediaStore 没有的)
                // 用规范化路径去重(消除 /sdcard vs /storage/emulated/0 差异)
                final List<MusicBean> toAdd = new ArrayList<>();
                java.util.Set<String> existingPaths = new java.util.HashSet<>();
                for (MusicBean b : quickList) {
                    String p = MusicScanner.normalizePath(b.getData());
                    if (!p.isEmpty()) {
                        existingPaths.add(p);
                    }
                }
                for (MusicBean b : fullList) {
                    String p = MusicScanner.normalizePath(b.getData());
                    if (!p.isEmpty() && !existingPaths.contains(p)) {
                        toAdd.add(b);
                    }
                }

                if (!toAdd.isEmpty()) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            musicList.addAll(toAdd);
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
                            if (service != null && !musicList.isEmpty()) {
                                service.setPlayList(musicList, 0);
                            }
                        }
                    });
                }

                // 3. 后台自动同步服务器新歌
                startBackgroundSync();
            }
        }).start();
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
        int count = adapter.getTotalFilteredCount();
        // 扫描中:使用预估总数优先显示
        if (estimatedCount > count) {
            tvCount.setText("共 " + estimatedCount + " 首(扫描中...)");
            return;
        }
        tvCount.setText(count == 0 ? "" : "共 " + count + " 首");
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
            return;
        }
        tvNowTitle.setText(bean.getTitle());
        tvNowArtist.setText(bean.getArtist());
        sbProgress.setMax((int) bean.getDuration());
        tvTotalTime.setText(MusicBean.formatDuration(bean.getDuration()));

        // 加载封面到歌词区作为背景(车机内存有限,控制在300px)
        int coverSize = 300; // 背景封面尺寸
        CoverLoader.getInstance().loadBitmap(bean, coverSize,
                new CoverLoader.BitmapCallback() {
                    @Override
                    public void onBitmapLoaded(android.graphics.Bitmap bitmap) {
                        lrcView.setCoverBitmap(bitmap);
                    }
                });
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
