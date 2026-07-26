package com.captiva.musicplayer;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.view.View;
import android.widget.Button;
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
 * - 搜索(点击搜索按钮弹出内置键盘搜索对话框)
 * - 歌词叠加在封面上(封面作为底色背景)
 * - 播放按钮颜色:播放蓝色 / 暂停红色
 * - 水波纹/selector 点击反馈(无振动)
 */
public class MainActivity extends AppCompatActivity {

    private static final int REQ_STORAGE = 100;

    // UI - 列表区
    private RecyclerView rvList;
    private TextView tvEmpty, tvCount;
    // UI - 顶栏按钮
    private Button btnSearch, btnSource, btnServer, btnEqualizer;
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

        initViews();
        setupListeners();

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

    private void initViews() {
        rvList = findViewById(R.id.rv_list);
        tvEmpty = findViewById(R.id.tv_empty);
        tvCount = findViewById(R.id.tv_count);
        btnSearch = findViewById(R.id.btn_search);
        btnSource = findViewById(R.id.btn_source);
        btnServer = findViewById(R.id.btn_server);
        btnEqualizer = findViewById(R.id.btn_equalizer);
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
        // 搜索:点击弹出搜索对话框(内置键盘)
        btnSearch.setOnClickListener(v -> {
            showSearchDialog();
        });

        // 来源切换
        btnSource.setOnClickListener(v -> {
            toggleSource();
        });

        // Navidrome 设置
        btnServer.setOnClickListener(v -> {
            needReloadNavidrome = true;
            startActivity(new Intent(this, ServerSettingsActivity.class));
        });

        // 均衡器
        btnEqualizer.setOnClickListener(v -> {
            if (service != null && service.isPlaying()) {
                startActivity(new Intent(this, EqualizerActivity.class));
            } else {
                Toast.makeText(this, "请先开始播放音乐,均衡器才能生效", Toast.LENGTH_SHORT).show();
                if (service != null && !musicList.isEmpty()) {
                    service.setPlayList(musicList, 0);
                    service.playIndex(0);
                }
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

    // ==================== 搜索对话框 ====================

    private void showSearchDialog() {
        String hint = sourceMode == SourceMode.LOCAL
                ? "搜索歌曲、艺术家、专辑..."
                : "搜索 Navidrome 音乐...";
        SearchDialog dialog = new SearchDialog(this, hint, "");
        dialog.setOnSearchListener(new SearchDialog.OnSearchListener() {
            @Override
            public void onSearch(String query) {
                doSearch(query);
            }
        });
        dialog.show();
    }

    // ==================== 来源切换 ====================

    private void toggleSource() {
        if (sourceMode == SourceMode.LOCAL) {
            // 切换到 Navidrome
            if (!navidromeConfig.isConfigured()) {
                Toast.makeText(this, "请先配置 Navidrome 服务器", Toast.LENGTH_LONG).show();
                startActivity(new Intent(this, ServerSettingsActivity.class));
                return;
            }
            sourceMode = SourceMode.NAVIDROME;
            btnSource.setText("网络");
            loadNavidromeMusic();
        } else {
            // 切换到本地
            sourceMode = SourceMode.LOCAL;
            btnSource.setText("本地");
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
                // 先尝试获取随机歌曲,若无结果则获取最新专辑的歌曲
                List<MusicBean> list = api.getRandomSongs(100);
                if (list == null || list.isEmpty()) {
                    // 回退:获取最新专辑列表,再获取第一个专辑的歌曲
                    List<AlbumBean> albums = api.getAlbumList("newest", 20);
                    if (albums != null && !albums.isEmpty()) {
                        list = new ArrayList<>();
                        for (AlbumBean album : albums) {
                            List<MusicBean> songs = api.getAlbum(album.getId());
                            if (songs != null && !songs.isEmpty()) {
                                list.addAll(songs);
                                if (list.size() >= 100) break;
                            }
                        }
                    }
                }
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
            }
            return;
        }

        query = query.trim();

        if (sourceMode == SourceMode.LOCAL) {
            adapter.filter(query);
            updateCount();
            Toast.makeText(this, "搜索: " + query, Toast.LENGTH_SHORT).show();
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
        btnSearch.setEnabled(false);

        new Thread(new Runnable() {
            @Override
            public void run() {
                final List<MusicBean> result = api.search(query, 100);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        btnSearch.setEnabled(true);
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

    /** 更新播放按钮:播放=蓝色,暂停=红色 */
    private void updatePlayButton(boolean playing) {
        if (playing) {
            btnPlay.setText("暂停");
            btnPlay.setBackgroundResource(R.drawable.bg_btn_playing);
            btnPlay.setTextColor(ContextCompat.getColor(this, R.color.btn_playing_text));
        } else {
            btnPlay.setText("播放");
            btnPlay.setBackgroundResource(R.drawable.bg_btn_paused);
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
        // 从设置页面返回时,如果配置有更新则重新加载 Navidrome
        if (needReloadNavidrome) {
            needReloadNavidrome = false;
            NavidromeApi api = MusicDataHolder.getInstance().getNavidromeApi();
            if (api != null && sourceMode == SourceMode.NAVIDROME) {
                loadNavidromeMusic();
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
        if (bound) {
            unbindService(connection);
            bound = false;
        }
        super.onDestroy();
    }
}
