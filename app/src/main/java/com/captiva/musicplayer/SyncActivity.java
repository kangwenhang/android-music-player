package com.captiva.musicplayer;

import android.os.Bundle;
import android.os.Handler;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

/**
 * 手动全量同步界面
 * 从服务器下载全部音乐到本地指定目录(后台自动同步只刷新列表,不下载音频;
 * 本页是用户主动「整库下载」的入口,保留全量模式)
 * 下载完成后云端列表中这些歌会自动转为本地播放
 */
public class SyncActivity extends AppCompatActivity {

    private TextView tvSyncPath, tvSyncedCount, tvProgressText, tvProgressDetail, tvResult;
    private ProgressBar pbSync;
    private LinearLayout llProgress;
    private Button btnSync, btnCancel, btnBack;

    private NavidromeConfig config;
    private MusicSyncManager syncManager;
    private final Handler handler = new Handler();

    /** 是否需要通知 MainActivity 重新加载 */
    private boolean syncCompleted = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 车机场景:界面在前台时保持屏幕常亮(与主界面一致),避免看设置/歌词时被系统息屏打断。
        // FLAG_KEEP_SCREEN_ON:不需要权限,Activity 不可见时系统自动失效,无泄漏风险。
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        hideSystemUI();
        setContentView(R.layout.activity_sync);

        config = new NavidromeConfig(this);

        tvSyncPath = findViewById(R.id.tv_sync_path);
        tvSyncedCount = findViewById(R.id.tv_synced_count);
        tvProgressText = findViewById(R.id.tv_progress_text);
        tvProgressDetail = findViewById(R.id.tv_progress_detail);
        tvResult = findViewById(R.id.tv_result);
        pbSync = findViewById(R.id.pb_sync);
        llProgress = findViewById(R.id.ll_progress);
        btnSync = findViewById(R.id.btn_sync);
        btnCancel = findViewById(R.id.btn_cancel);
        btnBack = findViewById(R.id.btn_back);

        // 显示同步路径
        tvSyncPath.setText(config.getCloudDir());

        // 统计已同步数量
        updateSyncedCount();

        btnBack.setOnClickListener(v -> finish());

        btnSync.setOnClickListener(v -> startSync());

        btnCancel.setOnClickListener(v -> stopSync());
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            hideSystemUI();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemUI();
        // 更新同步路径(可能从设置修改过)
        tvSyncPath.setText(config.getCloudDir());
        updateSyncedCount();
    }

    /** 更新已同步文件数 */
    private void updateSyncedCount() {
        int count = MusicSyncManager.countSyncedFiles(config.getCloudDir());
        tvSyncedCount.setText("已同步: " + count + " 首");
    }

    /** 开始同步 */
    private void startSync() {
        MusicSourceApi api = MusicDataHolder.getInstance().getMusicSourceApi();
        if (api == null || !config.isConfigured()) {
            Toast.makeText(this, "请先配置音乐服务器", Toast.LENGTH_LONG).show();
            return;
        }

        // 检查同步目录
        String path = config.getCloudDir();
        if (path == null || path.isEmpty()) {
            Toast.makeText(this, "请先设置同步目录", Toast.LENGTH_LONG).show();
            return;
        }

        // 创建同步管理器
        syncManager = new MusicSyncManager(this, api, path);

        // UI 切换到同步中状态
        btnSync.setEnabled(false);
        btnCancel.setEnabled(true);
        tvResult.setVisibility(View.GONE);
        llProgress.setVisibility(View.VISIBLE);
        tvProgressText.setText("正在获取歌曲列表...");
        pbSync.setProgress(0);
        pbSync.setIndeterminate(true); // 获取列表时用不确定进度

        // 后台执行同步
        new Thread(new Runnable() {
            @Override
            public void run() {
                syncManager.sync(new MusicSyncManager.SyncCallback() {
                    @Override
                    public void onStart(final int totalSongs) {
                        handler.post(new Runnable() {
                            @Override
                            public void run() {
                                pbSync.setIndeterminate(false);
                                pbSync.setMax(totalSongs);
                                tvProgressText.setText("开始同步 " + totalSongs + " 首歌曲...");
                            }
                        });
                    }

                    @Override
                    public void onProgress(final int downloaded, final int total, final String currentSong) {
                        handler.post(new Runnable() {
                            @Override
                            public void run() {
                                pbSync.setProgress(downloaded);
                                int percent = total > 0 ? (downloaded * 100 / total) : 0;
                                tvProgressText.setText(currentSong);
                                tvProgressDetail.setText(downloaded + " / " + total + " (" + percent + "%)");
                            }
                        });
                    }

                    @Override
                    public void onSongDownloaded(final int downloaded, final int total) {
                        handler.post(new Runnable() {
                            @Override
                            public void run() {
                                pbSync.setProgress(downloaded);
                                int percent = total > 0 ? (downloaded * 100 / total) : 0;
                                tvProgressText.setText("下载中...");
                                tvProgressDetail.setText(downloaded + " / " + total + " (" + percent + "%)");
                            }
                        });
                    }

                    @Override
                    public void onSongFailed(final String songTitle, final String reason) {
                        handler.post(new Runnable() {
                            @Override
                            public void run() {
                                tvResult.setVisibility(View.VISIBLE);
                                tvResult.append("失败: " + songTitle + " (" + reason + ")\n");
                            }
                        });
                    }

                    @Override
                    public void onComplete(final int downloaded, final int skipped, final int failed, final int total) {
                        handler.post(new Runnable() {
                            @Override
                            public void run() {
                                syncCompleted = true;
                                btnSync.setEnabled(true);
                                btnCancel.setEnabled(false);
                                pbSync.setProgress(total);
                                tvProgressText.setText("同步完成");
                                tvProgressDetail.setText(downloaded + " / " + total);

                                StringBuilder sb = new StringBuilder();
                                sb.append("同步完成!\n");
                                sb.append("新下载: ").append(downloaded).append(" 首\n");
                                sb.append("已跳过: ").append(skipped).append(" 首\n");
                                if (failed > 0) {
                                    sb.append("失败: ").append(failed).append(" 首\n");
                                }
                                sb.append("总计: ").append(total).append(" 首");
                                tvResult.setVisibility(View.VISIBLE);
                                tvResult.setText(sb.toString());

                                updateSyncedCount();
                                Toast.makeText(SyncActivity.this,
                                        "同步完成,新下载 " + downloaded + " 首",
                                        Toast.LENGTH_LONG).show();
                            }
                        });
                    }

                    @Override
                    public void onCancelled(final int downloaded, final int total) {
                        handler.post(new Runnable() {
                            @Override
                            public void run() {
                                syncCompleted = true;
                                btnSync.setEnabled(true);
                                btnCancel.setEnabled(false);
                                tvProgressText.setText("已取消");
                                tvProgressDetail.setText(downloaded + " / " + total);
                                tvResult.setVisibility(View.VISIBLE);
                                tvResult.setText("同步已取消\n已完成: " + downloaded + " / " + total + " 首");
                                updateSyncedCount();
                            }
                        });
                    }

                    @Override
                    public void onError(final String message) {
                        handler.post(new Runnable() {
                            @Override
                            public void run() {
                                btnSync.setEnabled(true);
                                btnCancel.setEnabled(false);
                                pbSync.setIndeterminate(false);
                                tvProgressText.setText("同步失败");
                                tvResult.setVisibility(View.VISIBLE);
                                tvResult.setText("错误: " + message);
                                Toast.makeText(SyncActivity.this, message, Toast.LENGTH_LONG).show();
                            }
                        });
                    }
                });
            }
        }).start();
    }

    /** 取消同步 */
    private void stopSync() {
        if (syncManager != null) {
            syncManager.cancel();
            btnCancel.setEnabled(false);
            tvProgressText.setText("正在取消...");
        }
    }

    /** 隐藏系统 UI,全屏沉浸模式 */
    private void hideSystemUI() {
        View decorView = getWindow().getDecorView();
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.KITKAT) {
            decorView.setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
        } else {
            decorView.setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LOW_PROFILE
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION);
            getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                    WindowManager.LayoutParams.FLAG_FULLSCREEN);
        }
    }

    @Override
    public void finish() {
        // 如果同步完成,设置结果码通知 MainActivity 重新加载
        if (syncCompleted) {
            setResult(RESULT_OK);
        }
        super.finish();
    }
}
