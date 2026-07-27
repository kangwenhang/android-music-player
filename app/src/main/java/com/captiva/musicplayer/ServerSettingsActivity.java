package com.captiva.musicplayer;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

/**
 * Navidrome 服务器设置界面
 * 输入服务器地址、用户名、密码,支持测试连接
 * 使用系统输入法
 */
public class ServerSettingsActivity extends AppCompatActivity {

    private EditText etUrl, etUser, etPass, etSyncPath;
    private TextView tvResult;
    private Button btnTest, btnSave, btnBack, btnOpenSync;
    private NavidromeConfig config;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 全屏沉浸模式
        hideSystemUI();
        setContentView(R.layout.activity_server_settings);

        config = new NavidromeConfig(this);

        etUrl = findViewById(R.id.et_server_url);
        etUser = findViewById(R.id.et_username);
        etPass = findViewById(R.id.et_password);
        etSyncPath = findViewById(R.id.et_sync_path);
        tvResult = findViewById(R.id.tv_test_result);
        btnTest = findViewById(R.id.btn_test);
        btnSave = findViewById(R.id.btn_save);
        btnBack = findViewById(R.id.btn_back);
        btnOpenSync = findViewById(R.id.btn_open_sync);

        // 回填已保存的配置
        etUrl.setText(config.getServerUrl());
        etUser.setText(config.getUsername());
        etPass.setText(config.getPassword());
        // 显示同步路径(如果是默认路径,显示空让用户知道是默认)
        String syncPath = config.getSyncPath();
        String defaultPath = android.os.Environment.getExternalStorageDirectory()
                .getAbsolutePath() + "/CaptivaMusic";
        if (syncPath.equals(defaultPath)) {
            etSyncPath.setText("");
            etSyncPath.setHint("默认: " + defaultPath);
        } else {
            etSyncPath.setText(syncPath);
        }

        btnBack.setOnClickListener(v -> {
            finish();
        });

        btnTest.setOnClickListener(v -> {
            testConnection();
        });

        btnSave.setOnClickListener(v -> {
            saveConfig();
        });

        btnOpenSync.setOnClickListener(v -> {
            // 先保存配置,再跳转
            saveConfigSilently();
            startActivity(new Intent(ServerSettingsActivity.this, SyncActivity.class));
        });
    }

    /** 测试连接(异步) */
    private void testConnection() {
        final String url = etUrl.getText().toString().trim();
        final String user = etUser.getText().toString().trim();
        final String pass = etPass.getText().toString().trim();

        if (url.isEmpty() || user.isEmpty() || pass.isEmpty()) {
            showResult("请填写完整的服务器信息", false);
            return;
        }

        tvResult.setVisibility(View.VISIBLE);
        tvResult.setText("正在测试连接...");
        btnTest.setEnabled(false);

        new Thread(new Runnable() {
            @Override
            public void run() {
                NavidromeApi api = new NavidromeApi(url, user, pass);
                final boolean ok = api.ping();
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        btnTest.setEnabled(true);
                        if (ok) {
                            showResult("连接成功!服务器响应正常", true);
                        } else {
                            showResult("连接失败,请检查地址和凭据", false);
                        }
                    }
                });
            }
        }).start();
    }

    /** 保存配置 */
    private void saveConfig() {
        // 先保存同步路径(与服务器配置无关,独立保存)
        saveSyncPath();
        if (!saveServerConfigSilently()) {
            return;
        }
        Toast.makeText(this, "配置已保存", Toast.LENGTH_SHORT).show();
        finish();
    }

    /** 独立保存同步路径 */
    private void saveSyncPath() {
        config.setSyncPath(etSyncPath.getText().toString().trim());
    }

    /** 静默保存服务器配置(不弹提示,不关闭页面),返回是否成功 */
    private boolean saveServerConfigSilently() {
        String url = etUrl.getText().toString().trim();
        String user = etUser.getText().toString().trim();
        String pass = etPass.getText().toString().trim();

        if (url.isEmpty() || user.isEmpty() || pass.isEmpty()) {
            Toast.makeText(this, "请填写完整的服务器信息", Toast.LENGTH_SHORT).show();
            return false;
        }

        config.setServerUrl(url);
        config.setUsername(user);
        config.setPassword(pass);
        config.setEnabled(true);

        // 更新全局 NavidromeApi
        NavidromeApi api = new NavidromeApi(url, user, pass);
        MusicDataHolder.getInstance().setNavidromeApi(api);
        MusicDataHolder.getInstance().setNavidromeEnabled(true);

        return true;
    }

    /** 静默保存全部配置(服务器+同步路径),返回是否成功 */
    private boolean saveConfigSilently() {
        // 同步路径始终保存
        saveSyncPath();
        // 服务器配置可能为空(如果还没配服务器但想改同步路径)
        String url = etUrl.getText().toString().trim();
        String user = etUser.getText().toString().trim();
        String pass = etPass.getText().toString().trim();

        if (url.isEmpty() || user.isEmpty() || pass.isEmpty()) {
            return false;
        }

        config.setServerUrl(url);
        config.setUsername(user);
        config.setPassword(pass);
        config.setEnabled(true);

        NavidromeApi api = new NavidromeApi(url, user, pass);
        MusicDataHolder.getInstance().setNavidromeApi(api);
        MusicDataHolder.getInstance().setNavidromeEnabled(true);

        return true;
    }

    private void showResult(String msg, boolean success) {
        tvResult.setVisibility(View.VISIBLE);
        tvResult.setText(msg);
        tvResult.setTextColor(success
                ? getResources().getColor(R.color.source_network)
                : getResources().getColor(R.color.btn_paused_bg));
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
}
