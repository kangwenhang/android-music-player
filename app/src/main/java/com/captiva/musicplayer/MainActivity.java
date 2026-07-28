package com.captiva.musicplayer;

import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.provider.MediaStore;
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

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 主界面
 * - 本地/Navidrome 双模式音乐播放
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
    private Button btnSource, btnSettings;
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

    /** 本地音乐列表(完整) */
    private final List<MusicBean> localMusicList = new ArrayList<>();
    /** 当前模式使用的列表 */
    private final List<MusicBean> musicList = new ArrayList<>();

    /** 数据源模式 */
    private enum SourceMode { LOCAL, NAVIDROME }
    private SourceMode sourceMode = SourceMode.LOCAL;

    private NavidromeConfig navidromeConfig;
    /** 网络歌曲列表缓存(切换网络模式时秒开) */
    private SongCache songCache;
    /** 从设置页返回时需重新加载 Navidrome */
    private boolean needReloadNavidrome = false;

    /** 服务器状态监控器 */
    private ServerStatusMonitor statusMonitor;

    /** 自动同步管理器(网络模式后台自动下载) */
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

        // 默认加载本地音乐
        if (hasStoragePermission()) {
            loadLocalMusic();
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
        btnSource = findViewById(R.id.btn_source);
        btnSettings = findViewById(R.id.btn_settings);
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
        adapter.setOnItemClickListener((position, bean) -> {
            if (service != null) {
                // 用当前显示的列表作为播放列表
                List<MusicBean> displayList = adapter.getDisplayList();
                service.setPlayList(displayList, position);
                service.playIndex(position);
            }
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
        // 搜索栏:实时搜索(本地即时过滤,Navidrome 防抖搜索)
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

        // 来源切换
        btnSource.setOnClickListener(v -> {
            toggleSource();
        });

        // 设置(均衡器 + 服务器统一入口)
        btnSettings.setOnClickListener(v -> {
            showSettingsMenu();
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
     * 处理搜索栏输入
     * - 本地模式:即时过滤
     * - 网络模式(本地同步文件):即时过滤(不走网络)
     */
    private void handleSearchInput(String query) {
        currentSearchQuery = query != null ? query.trim() : "";
        // 两种模式都是本地文件,即时过滤
        adapter.filter(currentSearchQuery);
        updateCount();
    }

    // ==================== 设置菜单 ====================

    /** 弹出设置菜单:均衡器 / 服务器设置 / 时长过滤 / 扫描目录 / 清除缓存 */
    private void showSettingsMenu() {
        String[] items = {"均衡器", "服务器设置", "时长过滤设置", "扫描目录设置", "清除网络缓存"};
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
                    needReloadNavidrome = true;
                    startActivity(new Intent(MainActivity.this, ServerSettingsActivity.class));
                } else if (which == 2) {
                    // 时长过滤设置
                    showDurationFilterDialog();
                } else if (which == 3) {
                    // 扫描目录设置
                    showScanPathDialog();
                } else if (which == 4) {
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

    /** 扫描目录设置对话框:自定义输入路径 */
    private void showScanPathDialog() {
        final String currentPath = navidromeConfig.getScanPath();
        final String defaultPath = MusicScanner.getDefaultStoragePath();

        // 创建输入框
        final EditText etInput = new EditText(this);
        etInput.setText(currentPath);
        etInput.setHint("留空=扫描全部(默认)\n例如:" + defaultPath + "/Music");
        etInput.setTextColor(getResources().getColor(R.color.text_primary));
        etInput.setHintTextColor(getResources().getColor(R.color.search_hint));
        etInput.setPadding(24, 16, 24, 16);
        etInput.setBackgroundResource(R.drawable.bg_search);
        etInput.setSingleLine(true);

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("本地音乐扫描目录");
        builder.setMessage("输入要扫描的目录路径\n留空=使用系统MediaStore扫描全部\n指定目录=递归扫描该目录下所有音频文件");
        builder.setView(etInput);
        builder.setPositiveButton("确定", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                String input = etInput.getText().toString().trim();
                navidromeConfig.setScanPath(input);
                Toast.makeText(MainActivity.this,
                        input.isEmpty() ? "已设置为扫描全部" : "已设置扫描目录: " + input,
                        Toast.LENGTH_LONG).show();
                // 重新加载本地音乐
                if (sourceMode == SourceMode.LOCAL) {
                    loadLocalMusic();
                }
            }
        });
        builder.setNegativeButton("取消", null);
        // 添加"重置为默认"按钮
        builder.setNeutralButton("清空(扫描全部)", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                navidromeConfig.setScanPath("");
                Toast.makeText(MainActivity.this, "已重置为扫描全部", Toast.LENGTH_SHORT).show();
                if (sourceMode == SourceMode.LOCAL) {
                    loadLocalMusic();
                }
            }
        });
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
                // 重新加载本地音乐
                if (sourceMode == SourceMode.LOCAL) {
                    loadLocalMusic();
                }
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

    // ==================== 来源切换 ====================

    private void toggleSource() {
        if (sourceMode == SourceMode.LOCAL) {
            // 切换到 Navidrome
            if (!navidromeConfig.isConfigured()) {
                Toast.makeText(this, "请先在设置中配置 Navidrome 服务器", Toast.LENGTH_LONG).show();
                needReloadNavidrome = true;
                startActivity(new Intent(this, ServerSettingsActivity.class));
                return;
            }
            sourceMode = SourceMode.NAVIDROME;
            btnSource.setText("网络");
            btnSource.setBackgroundResource(R.drawable.bg_btn_source_network);
            etSearch.setHint("搜索已同步的歌曲、歌手、专辑...");
            etSearch.setText("");
            loadNavidromeMusic();
        } else {
            // 切换到本地(同步继续在后台运行)
            sourceMode = SourceMode.LOCAL;
            btnSource.setText("本地");
            btnSource.setBackgroundResource(R.drawable.bg_btn_source_local);
            etSearch.setHint("搜索本地歌曲、歌手、专辑...");
            etSearch.setText("");
            loadLocalMusic();
        }
    }

    // ==================== 本地音乐 ====================

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
                loadLocalMusic();
            } else {
                Toast.makeText(this, "需要存储权限才能读取本地音乐", Toast.LENGTH_LONG).show();
            }
        }
    }

    private void loadLocalMusic() {
        sourceMode = SourceMode.LOCAL;
        tvEmpty.setText("正在扫描本地音乐...");
        tvEmpty.setVisibility(View.VISIBLE);
        tvCount.setText("统计中...");
        estimatedLocalCount = 0;

        // 1. 后台快速统计总数(比完整扫描快得多)
        new Thread(new Runnable() {
            @Override
            public void run() {
                final int count = MusicScanner.countLocalMusic(MainActivity.this);

                // 优先显示总歌曲数
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (count > 0) {
                            estimatedLocalCount = count;
                            tvCount.setText("共 " + count + " 首(扫描中...)");
                        }
                    }
                });

                // 2. 完整扫描
                final List<MusicBean> list = MusicScanner.scan(MainActivity.this);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        localMusicList.clear();
                        localMusicList.addAll(list);
                        musicList.clear();
                        musicList.addAll(list);
                        adapter.setData(musicList);
                        // 扫描完成,显示实际数量
                        estimatedLocalCount = 0;
                        updateCount();
                        if (musicList.isEmpty()) {
                            tvEmpty.setVisibility(View.VISIBLE);
                            tvEmpty.setText("未找到本地音乐,请将音乐文件放入存储");
                        } else {
                            tvEmpty.setVisibility(View.GONE);
                        }
                        if (service != null && !musicList.isEmpty()) {
                            service.setPlayList(musicList, 0);
                        }
                    }
                });

                // 本地扫描完后,后台自动同步服务器音乐(不影响本地使用)
                startBackgroundSync();
            }
        }).start();
    }

    /**
     * 后台自动同步(本地和网络模式都执行)
     * - 如果已配置 Navidrome 且服务器有未同步歌曲,自动后台下载
     * - 已有同步文件会跳过(增量同步)
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

        new Thread(new Runnable() {
            @Override
            public void run() {
                // 快速统计本地已同步数
                final int syncedCount = MusicSyncManager.countSyncedFiles(syncPath);

                // 获取服务器歌曲总数
                final List<AlbumBean> allAlbums = api.getAllAlbums();
                int serverTotal = 0;
                if (allAlbums != null) {
                    for (AlbumBean album : allAlbums) {
                        serverTotal += album.getSongCount();
                    }
                }
                final int serverCount = serverTotal;

                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (serverCount > syncedCount) {
                            // 有未同步歌曲,启动自动同步
                            startAutoSync(syncPath, serverCount);
                        } else if (syncedCount > 0) {
                            tvSyncStatus.setVisibility(View.VISIBLE);
                            tvSyncStatus.setText("已是最新");
                        }
                    }
                });
            }
        }).start();
    }

    // ==================== Navidrome 音乐(本地同步模式) ====================

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

    /** 网络歌曲预估总数(从专辑列表统计,优先显示) */
    private int estimatedNetworkCount = 0;
    /** 本地歌曲预估总数(快速统计,优先显示) */
    private int estimatedLocalCount = 0;

    /**
     * 加载网络音乐(从同步目录扫描本地文件)
     *
     * 策略:
     * 1. 扫描同步目录已有文件,立即显示(秒开)
     * 2. 后台自动同步服务器新歌曲
     * 3. 每下载一首实时更新列表
     * 4. 只显示已同步完成(下载完毕)的歌曲
     */
    private void loadNavidromeMusic() {
        final String syncPath = navidromeConfig.getSyncPath();

        // 取消之前的同步
        cancelAutoSync();

        estimatedNetworkCount = 0;
        tvCount.setText("统计中...");
        tvSyncStatus.setVisibility(View.GONE);

        new Thread(new Runnable() {
            @Override
            public void run() {
                // 快速统计现有文件
                final int syncedCount = MusicSyncManager.countSyncedFiles(syncPath);

                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (syncedCount > 0) {
                            estimatedNetworkCount = syncedCount;
                            tvCount.setText("共 " + syncedCount + " 首(扫描中...)");
                        }
                    }
                });

                // 扫描同步目录已有文件
                final List<MusicBean> existingList = scanSyncDirectory(syncPath);

                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (!existingList.isEmpty()) {
                            musicList.clear();
                            musicList.addAll(existingList);
                            adapter.setData(musicList);
                            estimatedNetworkCount = 0;
                            updateCount();
                            tvEmpty.setVisibility(View.GONE);
                            if (service != null) {
                                service.setPlayList(musicList, 0);
                            }
                        } else {
                            musicList.clear();
                            adapter.setData(musicList);
                            tvEmpty.setVisibility(View.VISIBLE);
                            tvEmpty.setText("暂无同步歌曲\n正在从服务器获取列表...");
                            tvCount.setText("");
                        }
                    }
                });

                // 后台自动同步(与本地模式共用同一逻辑)
                startBackgroundSync();
            }
        }).start();
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
                                tvSyncStatus.setText("同步 0/" + totalSongs);
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
                        // 累积 N 首后刷新一次列表(避免频繁刷新)
                        pendingSyncRefresh++;
                        if (pendingSyncRefresh >= REFRESH_BATCH_SIZE) {
                            pendingSyncRefresh = 0;
                            handler.post(new Runnable() {
                                @Override
                                public void run() {
                                    tvSyncStatus.setText("同步 " + downloaded + "/" + total);
                                    refreshSyncList(syncPath);
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
                                // 最终刷新列表
                                refreshSyncList(syncPath);
                                tvSyncStatus.setText("已同步");
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
     * 扫描同步目录,将新下载的文件加入列表(不重复添加已有)
     */
    private void refreshSyncList(String syncPath) {
        if (syncPath == null || syncPath.isEmpty()) return;

        new Thread(new Runnable() {
            @Override
            public void run() {
                final List<MusicBean> newList = scanSyncDirectory(syncPath);

                // 计算新增的歌曲(通过 data 路径去重)
                final List<MusicBean> toAdd = new ArrayList<>();
                for (MusicBean bean : newList) {
                    boolean exists = false;
                    for (MusicBean existing : musicList) {
                        if (bean.getData() != null && bean.getData().equals(existing.getData())) {
                            exists = true;
                            break;
                        }
                    }
                    if (!exists) {
                        toAdd.add(bean);
                    }
                }

                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        // 如果有搜索过滤,刷新整个列表;否则增量添加
                        if (currentSearchQuery.isEmpty()) {
                            if (!toAdd.isEmpty()) {
                                musicList.addAll(toAdd);
                                // 按标题排序
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
                            // 有搜索过滤:重新设置全部数据
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

    /**
     * 扫描同步目录下的音频文件
     * 复用 MusicScanner 的目录扫描逻辑,但指定为同步目录
     */
    private List<MusicBean> scanSyncDirectory(String syncPath) {
        List<MusicBean> list = new ArrayList<>();
        if (syncPath == null || syncPath.isEmpty()) {
            return list;
        }

        File rootDir = new File(syncPath);
        if (!rootDir.exists() || !rootDir.isDirectory()) {
            return list;
        }

        // 收集音频文件
        List<File> audioFiles = new ArrayList<>();
        collectAudioFiles(rootDir, audioFiles);

        int minDurationSec = navidromeConfig.getMinDuration();
        long minDurationMs = minDurationSec * 1000L;

        ContentResolver resolver = getContentResolver();
        for (File file : audioFiles) {
            try {
                // 尝试从 MediaStore 查询该文件的元数据
                MusicBean bean = queryFromMediaStoreByPath(resolver, file.getAbsolutePath(), minDurationMs);
                if (bean == null) {
                    // MediaStore 没有记录,手动创建
                    bean = createBeanFromFile(file, minDurationMs);
                }
                if (bean != null) {
                    list.add(bean);
                }
            } catch (Exception e) {
                // ignore
            }
        }

        // 按标题排序
        java.util.Collections.sort(list, new java.util.Comparator<MusicBean>() {
            @Override
            public int compare(MusicBean a, MusicBean b) {
                return a.getTitle().compareToIgnoreCase(b.getTitle());
            }
        });

        return list;
    }

    /** 递归收集目录下所有音频文件 */
    private void collectAudioFiles(File dir, List<File> result) {
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

    /** 判断文件是否为音频 */
    private boolean isAudioFile(String name) {
        if (name == null || !name.contains(".")) {
            return false;
        }
        String ext = name.substring(name.lastIndexOf(".")).toLowerCase();
        return ext.equals(".mp3") || ext.equals(".flac") || ext.equals(".wav")
                || ext.equals(".ogg") || ext.equals(".m4a") || ext.equals(".aac")
                || ext.equals(".wma") || ext.equals(".ape") || ext.equals(".m4b")
                || ext.equals(".opus");
    }

    /** 通过文件路径从 MediaStore 查询元数据 */
    private MusicBean queryFromMediaStoreByPath(ContentResolver resolver, String filePath, long minDurationMs) {
        try {
            String selection = MediaStore.Audio.Media.DATA + "=?"
                    + " AND " + MediaStore.Audio.Media.IS_MUSIC + "=1"
                    + " AND " + MediaStore.Audio.Media.DURATION + " > " + minDurationMs;
            String[] selectionArgs = new String[]{filePath};

            String[] projection = new String[]{
                    MediaStore.Audio.Media._ID,
                    MediaStore.Audio.Media.TITLE,
                    MediaStore.Audio.Media.ARTIST,
                    MediaStore.Audio.Media.ALBUM,
                    MediaStore.Audio.Media.DURATION,
                    MediaStore.Audio.Media.DATA,
                    MediaStore.Audio.Media.ALBUM_ID
            };

            Cursor cursor = resolver.query(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    projection, selection, selectionArgs, null);

            if (cursor != null) {
                try {
                    if (cursor.moveToFirst()) {
                        MusicBean bean = new MusicBean();
                        bean.setId(cursor.getLong(cursor.getColumnIndex(MediaStore.Audio.Media._ID)));
                        bean.setTitle(cursor.getString(cursor.getColumnIndex(MediaStore.Audio.Media.TITLE)));
                        bean.setArtist(cursor.getString(cursor.getColumnIndex(MediaStore.Audio.Media.ARTIST)));
                        bean.setAlbum(cursor.getString(cursor.getColumnIndex(MediaStore.Audio.Media.ALBUM)));
                        bean.setDuration(cursor.getLong(cursor.getColumnIndex(MediaStore.Audio.Media.DURATION)));
                        bean.setData(cursor.getString(cursor.getColumnIndex(MediaStore.Audio.Media.DATA)));
                        bean.setUri(Uri.withAppendedPath(
                                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                                String.valueOf(bean.getId())).toString());
                        return bean;
                    }
                } finally {
                    cursor.close();
                }
            }
        } catch (Exception e) {
            // ignore
        }
        return null;
    }

    /** 手动从文件创建 MusicBean(无 MediaStore 记录时) */
    private MusicBean createBeanFromFile(File file, long minDurationMs) {
        if (file == null || !file.exists()) {
            return null;
        }

        String filePath = file.getAbsolutePath();
        String fileName = file.getName();

        String title = fileName;
        int dotIdx = fileName.lastIndexOf(".");
        if (dotIdx > 0) {
            title = fileName.substring(0, dotIdx);
        }

        long duration = 0;
        android.media.MediaMetadataRetriever mmr = new android.media.MediaMetadataRetriever();
        try {
            mmr.setDataSource(filePath);
            String durStr = mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (durStr != null && !durStr.isEmpty()) {
                duration = Long.parseLong(durStr);
            }
            String artist = mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_ARTIST);
            String album = mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_ALBUM);

            if (duration < minDurationMs && minDurationMs > 0) {
                return null;
            }

            MusicBean bean = new MusicBean();
            bean.setTitle(title);
            bean.setArtist(artist != null ? artist : "未知艺术家");
            bean.setAlbum(album != null ? album : "未知专辑");
            bean.setDuration(duration);
            bean.setData(filePath);
            bean.setUri(filePath);
            return bean;
        } catch (Exception e) {
            if (file.length() > 1024) {
                MusicBean bean = new MusicBean();
                bean.setTitle(title);
                bean.setArtist("未知艺术家");
                bean.setAlbum("未知专辑");
                bean.setDuration(0);
                bean.setData(filePath);
                bean.setUri(filePath);
                return bean;
            }
            return null;
        } finally {
            try {
                mmr.release();
            } catch (Exception e) {
                // ignore
            }
        }
    }

    // ==================== UI 更新 ====================

    private void updateCount() {
        int count = adapter.getTotalFilteredCount();
        // 本地扫描中:使用预估总数优先显示
        if (sourceMode == SourceMode.LOCAL && estimatedLocalCount > count) {
            tvCount.setText("共 " + estimatedLocalCount + " 首(扫描中...)");
            return;
        }
        // 网络加载中:使用预估总数优先显示
        if (sourceMode == SourceMode.NAVIDROME && estimatedNetworkCount > count) {
            tvCount.setText("共 " + estimatedNetworkCount + " 首(已加载 " + count + ")");
            return;
        }
        tvCount.setText(count == 0 ? "" : "共 " + count + " 首");
    }

    private void updateNowPlaying(int index) {
        if (index < 0 || index >= musicList.size()) {
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
        MusicBean bean = musicList.get(index);
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
        if (needReloadNavidrome) {
            needReloadNavidrome = false;
            NavidromeApi api = MusicDataHolder.getInstance().getNavidromeApi();
            // 更新监控器的 API 实例(会触发重新检测)
            if (statusMonitor != null) {
                statusMonitor.updateApi(api);
            }
            if (api != null && sourceMode == SourceMode.NAVIDROME) {
                loadNavidromeMusic();
            }
            // 无论什么模式,重新加载本地(可能改了时长过滤)
            if (sourceMode == SourceMode.LOCAL) {
                loadLocalMusic();
            }
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
