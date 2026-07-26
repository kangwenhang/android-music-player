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
    /** Navidrome 搜索防抖延迟(ms) */
    private static final long SEARCH_DEBOUNCE_MS = 600;

    // UI - 列表区
    private RecyclerView rvList;
    private TextView tvEmpty, tvCount;
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
    /** 从设置页返回时需重新加载 Navidrome */
    private boolean needReloadNavidrome = false;

    /** 服务器状态监控器 */
    private ServerStatusMonitor statusMonitor;

    // 进度刷新
    private final Handler handler = new Handler();
    private final Runnable progressTask = new Runnable() {
        @Override
        public void run() {
            updateProgress();
            updateLrc();
            handler.postDelayed(this, 500);
        }
    };

    /** Navidrome 搜索防抖 Runnable */
    private Runnable searchDebounceRunnable;
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
                btnMode.setText(mode.getLabel());
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
            btnMode.setText(service.getPlayMode().getLabel());
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
     * - 兼容 Android 4.0(API 14)到新版本
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
            // API 14-18:隐藏状态栏和导航键(非沉浸式)
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
                btnMode.setText(mode.getLabel());
                Toast.makeText(this, "播放模式: " + mode.getLabel(), Toast.LENGTH_SHORT).show();
            }
        });
    }

    // ==================== 搜索 ====================

    /**
     * 处理搜索栏输入
     * - 本地模式:即时过滤
     * - Navidrome 模式:防抖延迟搜索(避免频繁网络请求)
     */
    private void handleSearchInput(String query) {
        currentSearchQuery = query != null ? query.trim() : "";

        if (sourceMode == SourceMode.LOCAL) {
            // 本地:即时过滤
            adapter.filter(currentSearchQuery);
            updateCount();
        } else {
            // Navidrome:防抖搜索
            if (searchDebounceRunnable != null) {
                handler.removeCallbacks(searchDebounceRunnable);
            }
            searchDebounceRunnable = new Runnable() {
                @Override
                public void run() {
                    doSearch(currentSearchQuery);
                }
            };
            handler.postDelayed(searchDebounceRunnable, SEARCH_DEBOUNCE_MS);
        }
    }

    // ==================== 设置菜单 ====================

    /** 弹出设置菜单:均衡器 / 服务器设置 / 时长过滤 */
    private void showSettingsMenu() {
        String[] items = {"均衡器", "服务器设置", "时长过滤设置"};
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
            etSearch.setHint("搜索网络歌曲、歌手、专辑...");
            etSearch.setText("");
            loadNavidromeMusic();
        } else {
            // 切换到本地
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

        new Thread(new Runnable() {
            @Override
            public void run() {
                final List<MusicBean> list = MusicScanner.scan(MainActivity.this);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        localMusicList.clear();
                        localMusicList.addAll(list);
                        musicList.clear();
                        musicList.addAll(list);
                        adapter.setData(musicList);
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
            }
        }).start();
    }

    // ==================== Navidrome 音乐 ====================

    private void loadNavidromeMusic() {
        NavidromeApi api = MusicDataHolder.getInstance().getNavidromeApi();
        if (api == null) {
            Toast.makeText(this, "Navidrome 未配置", Toast.LENGTH_SHORT).show();
            return;
        }

        tvEmpty.setText("正在从 Navidrome 加载...");
        tvEmpty.setVisibility(View.VISIBLE);

        new Thread(new Runnable() {
            @Override
            public void run() {
                // 获取全部歌曲:先按字母排序分页获取,确保覆盖全部曲库
                List<MusicBean> list = api.getAllSongs();

                final List<MusicBean> result = list != null ? list : new ArrayList<MusicBean>();
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        musicList.clear();
                        musicList.addAll(result);
                        adapter.setData(musicList);
                        updateCount();
                        if (musicList.isEmpty()) {
                            tvEmpty.setVisibility(View.VISIBLE);
                            tvEmpty.setText("Navidrome 上未找到音乐");
                        } else {
                            tvEmpty.setVisibility(View.GONE);
                        }
                        if (service != null && !musicList.isEmpty()) {
                            service.setPlayList(musicList, 0);
                        }
                    }
                });
            }
        }).start();
    }

    // ==================== 搜索 ====================

    private void doSearch(String query) {
        if (query == null || query.trim().isEmpty()) {
            if (sourceMode == SourceMode.LOCAL) {
                adapter.filter("");
                updateCount();
            } else {
                // Navidrome 空搜索:重新加载列表
                loadNavidromeMusic();
            }
            return;
        }

        query = query.trim();

        if (sourceMode == SourceMode.LOCAL) {
            adapter.filter(query);
            updateCount();
        } else {
            searchNavidrome(query);
        }
    }

    private void searchNavidrome(final String query) {
        NavidromeApi api = MusicDataHolder.getInstance().getNavidromeApi();
        if (api == null) {
            Toast.makeText(this, "Navidrome 未配置", Toast.LENGTH_SHORT).show();
            return;
        }

        tvEmpty.setText("正在搜索...");
        tvEmpty.setVisibility(View.VISIBLE);

        new Thread(new Runnable() {
            @Override
            public void run() {
                final List<MusicBean> result = api.search(query, 5000);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        musicList.clear();
                        if (result != null) {
                            musicList.addAll(result);
                        }
                        adapter.setData(musicList);
                        updateCount();
                        if (musicList.isEmpty()) {
                            tvEmpty.setVisibility(View.VISIBLE);
                            tvEmpty.setText("未找到匹配的歌曲");
                        } else {
                            tvEmpty.setVisibility(View.GONE);
                        }
                        if (service != null && !musicList.isEmpty()) {
                            service.setPlayList(musicList, 0);
                        }
                    }
                });
            }
        }).start();
    }

    // ==================== UI 更新 ====================

    private void updateCount() {
        int count = adapter.getItemCount();
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

        // 加载封面到歌词区作为背景
        int coverSize = 400; // 较大尺寸用于背景
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
            btnPlay.setText("▌▌");
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
        // 移除搜索防抖
        if (searchDebounceRunnable != null) {
            handler.removeCallbacks(searchDebounceRunnable);
        }
    }

    @Override
    protected void onDestroy() {
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
