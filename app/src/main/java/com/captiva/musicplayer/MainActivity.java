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
 * - 扫描本地音乐并展示列表
 * - 绑定 MusicService 进行播放控制
 * - 接收播放状态广播更新 UI
 * - 歌词显示、播放模式、文件夹分类、均衡器入口
 */
public class MainActivity extends AppCompatActivity {

    private static final int REQ_STORAGE = 100;
    private static final int REQ_FOLDER = 200;

    private RecyclerView rvList;
    private TextView tvEmpty, tvNowTitle, tvNowArtist, tvCurrentTime, tvTotalTime, tvCount;
    private SeekBar sbProgress;
    private Button btnPrev, btnPlay, btnNext, btnMode, btnFolder, btnEqualizer;
    private LrcView lrcView;

    private MusicAdapter adapter;
    private MusicService service;
    private boolean bound = false;

    /** 完整音乐库 */
    private final List<MusicBean> allMusic = new ArrayList<>();
    /** 当前显示的列表(可能是文件夹筛选后的子集) */
    private final List<MusicBean> musicList = new ArrayList<>();
    /** 当前筛选的文件夹路径,null 表示全部 */
    private String currentFolder = null;

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
                btnPlay.setText(playing ? "暂停" : "播放");
                btnMode.setText(mode.getLabel());
                // 歌词随状态广播刷新一次(切换曲目时)
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
            // 把已扫描列表交给 service
            if (!allMusic.isEmpty()) {
                service.setPlayList(allMusic, 0);
            }
            // 同步当前状态
            int idx = service.getCurrentIndex();
            adapter.setPlayingIndex(idx);
            updateNowPlaying(idx);
            btnPlay.setText(service.isPlaying() ? "暂停" : "播放");
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

        rvList = findViewById(R.id.rv_list);
        tvEmpty = findViewById(R.id.tv_empty);
        tvCount = findViewById(R.id.tv_count);
        tvNowTitle = findViewById(R.id.tv_now_title);
        tvNowArtist = findViewById(R.id.tv_now_artist);
        tvCurrentTime = findViewById(R.id.tv_current_time);
        tvTotalTime = findViewById(R.id.tv_total_time);
        sbProgress = findViewById(R.id.sb_progress);
        btnPrev = findViewById(R.id.btn_prev);
        btnPlay = findViewById(R.id.btn_play);
        btnNext = findViewById(R.id.btn_next);
        btnMode = findViewById(R.id.btn_mode);
        btnFolder = findViewById(R.id.btn_folder);
        btnEqualizer = findViewById(R.id.btn_equalizer);
        lrcView = findViewById(R.id.lrc_view);

        adapter = new MusicAdapter(this);
        adapter.setOnItemClickListener((position, bean) -> {
            if (service != null) {
                // 把当前显示列表交给 service,从点击位置开始播放
                service.setPlayList(musicList, position);
                service.playIndex(position);
            }
        });
        rvList.setLayoutManager(new LinearLayoutManager(this));
        rvList.setAdapter(adapter);
        tvEmpty.setText("正在扫描本地音乐...");
        tvEmpty.setVisibility(View.VISIBLE);

        // 拖动进度
        sbProgress.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser && service != null) {
                    service.seekTo(progress);
                    tvCurrentTime.setText(MusicBean.formatDuration(progress));
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });

        btnPrev.setOnClickListener(v -> { if (service != null) service.prev(); });
        btnPlay.setOnClickListener(v -> { if (service != null) service.toggle(); });
        btnNext.setOnClickListener(v -> { if (service != null) service.next(); });

        // 播放模式切换
        btnMode.setOnClickListener(v -> {
            if (service != null) {
                PlayMode mode = service.cyclePlayMode();
                btnMode.setText(mode.getLabel());
                Toast.makeText(this, "播放模式: " + mode.getLabel(), Toast.LENGTH_SHORT).show();
            }
        });

        // 文件夹分类
        btnFolder.setOnClickListener(v -> {
            MusicDataHolder.getInstance().setMusicList(allMusic);
            Intent intent = new Intent(this, FolderActivity.class);
            startActivityForResult(intent, REQ_FOLDER);
        });

        // 均衡器
        btnEqualizer.setOnClickListener(v -> {
            if (service != null && service.isPlaying()) {
                startActivity(new Intent(this, EqualizerActivity.class));
            } else {
                Toast.makeText(this, "请先开始播放音乐,均衡器才能生效", Toast.LENGTH_SHORT).show();
                if (service != null && !allMusic.isEmpty()) {
                    service.setPlayList(allMusic, 0);
                    service.playIndex(0);
                }
            }
        });

        // 启动并绑定服务
        Intent si = new Intent(this, MusicService.class);
        startService(si);
        bindService(si, connection, Context.BIND_AUTO_CREATE);

        // 检查存储权限(API 23+ 需要,4.0 直接放行)
        if (hasStoragePermission()) {
            loadMusic();
        } else {
            ActivityCompat.requestPermissions(this,
                    new String[]{android.Manifest.permission.READ_EXTERNAL_STORAGE}, REQ_STORAGE);
        }
    }

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
                Toast.makeText(this, "需要存储权限才能读取本地音乐", Toast.LENGTH_LONG).show();
            }
        }
    }

    /** 异步扫描音乐,避免阻塞 UI */
    private void loadMusic() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                final List<MusicBean> list = MusicScanner.scan(MainActivity.this);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        allMusic.clear();
                        allMusic.addAll(list);
                        MusicDataHolder.getInstance().setMusicList(allMusic);
                        applyFilterAndShow();
                        if (service != null && !allMusic.isEmpty()) {
                            service.setPlayList(allMusic, 0);
                        }
                    }
                });
            }
        }).start();
    }

    /** 根据当前文件夹筛选,刷新列表显示 */
    private void applyFilterAndShow() {
        musicList.clear();
        if (currentFolder == null) {
            musicList.addAll(allMusic);
        } else {
            for (MusicBean b : allMusic) {
                if (b.getData() != null && b.getData().startsWith(currentFolder)) {
                    musicList.add(b);
                }
            }
        }
        adapter.setData(musicList);
        tvCount.setText(musicList.isEmpty() ? "" : "共 " + musicList.size() + " 首");
        if (musicList.isEmpty()) {
            tvEmpty.setVisibility(View.VISIBLE);
            tvEmpty.setText(currentFolder == null ? "未找到本地音乐,请将音乐文件放入存储" : "该文件夹无音乐");
        } else {
            tvEmpty.setVisibility(View.GONE);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_FOLDER && resultCode == RESULT_OK && data != null) {
            String folderPath = data.getStringExtra(FolderActivity.EXTRA_FOLDER_PATH);
            String folderName = data.getStringExtra(FolderActivity.EXTRA_FOLDER_NAME);
            currentFolder = folderPath;
            applyFilterAndShow();
            Toast.makeText(this, "已切换到: " + folderName, Toast.LENGTH_SHORT).show();
        }
    }

    private void updateNowPlaying(int index) {
        if (index < 0 || index >= musicList.size()) {
            tvNowTitle.setText("未在播放");
            tvNowArtist.setText("");
            sbProgress.setMax(0);
            sbProgress.setProgress(0);
            tvCurrentTime.setText("00:00");
            tvTotalTime.setText("00:00");
            return;
        }
        MusicBean bean = musicList.get(index);
        tvNowTitle.setText(bean.getTitle());
        tvNowArtist.setText(bean.getArtist());
        sbProgress.setMax((int) bean.getDuration());
        tvTotalTime.setText(MusicBean.formatDuration(bean.getDuration()));
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

    /** 刷新歌词当前行 */
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

    @Override
    protected void onResume() {
        super.onResume();
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