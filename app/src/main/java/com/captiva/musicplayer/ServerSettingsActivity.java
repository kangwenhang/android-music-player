package com.captiva.musicplayer;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.os.Bundle;
import android.os.Environment;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 音乐服务器设置界面
 * 支持两种数据源:Navidrome / Subsonic、飞牛 NAS 音乐服务
 * 输入服务器地址、用户名、密码,支持测试连接
 * 使用系统输入法
 */
public class ServerSettingsActivity extends AppCompatActivity {

    private EditText etUrl, etUser, etPass, etSyncPath;
    private TextView tvResult;
    private Button btnTest, btnSave, btnBack;
    private RadioGroup rgServerType;
    private RadioButton rbTypeNavidrome, rbTypeFnMusic;
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
        rgServerType = findViewById(R.id.rg_server_type);
        rbTypeNavidrome = findViewById(R.id.rb_type_navidrome);
        rbTypeFnMusic = findViewById(R.id.rb_type_fnmusic);

        // 回填服务器类型
        if (MusicSourceFactory.TYPE_FNMUSIC.equals(config.getServerType())) {
            rbTypeFnMusic.setChecked(true);
        } else {
            rbTypeNavidrome.setChecked(true);
        }
        // 切换类型时给出默认地址提示
        rgServerType.setOnCheckedChangeListener((group, checkedId) -> {
            if (checkedId == R.id.rb_type_fnmusic) {
                etUrl.setHint("http://192.168.1.100:5666 或 FN ID(如 k495378412)");
            } else {
                etUrl.setHint("http://192.168.1.100:4533");
            }
        });
        // 初始提示(未触发切换回调时也要正确)
        if (rbTypeFnMusic.isChecked()) {
            etUrl.setHint("http://192.168.1.100:5666 或 FN ID(如 k495378412)");
        }

        // 回填已保存的配置
        etUrl.setText(config.getServerUrl());
        etUser.setText(config.getUsername());
        etPass.setText(config.getPassword());
        // 显示同步路径(如果是默认路径,也显示出来)
        String syncPath = config.getSyncPath();
        String defaultPath = Environment.getExternalStorageDirectory()
                .getAbsolutePath() + "/CaptivaMusic";
        if (syncPath.isEmpty() || syncPath.equals(defaultPath)) {
            etSyncPath.setText(defaultPath);
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

        // 点击同步路径输入框 → 弹出目录选择器(含手动输入选项)
        etSyncPath.setOnClickListener(v -> {
            showDirectoryPicker();
        });
    }

    // ==================== 目录选择器 ====================

    /**
     * 弹出目录选择对话框
     * 先显示快捷选项,再可选进入文件浏览器
     */
    private void showDirectoryPicker() {
        final List<String> quickPaths = new ArrayList<>();
        final List<String> quickLabels = new ArrayList<>();

        // 默认路径
        String defaultPath = Environment.getExternalStorageDirectory()
                .getAbsolutePath() + "/CaptivaMusic";
        quickPaths.add(defaultPath);
        quickLabels.add("默认目录: " + defaultPath);

        // 外部存储根目录
        String sdRoot = Environment.getExternalStorageDirectory().getAbsolutePath();
        quickPaths.add(sdRoot);
        quickLabels.add("外部存储: " + sdRoot);

        // 常见音乐目录
        File musicDir = new File(sdRoot, "Music");
        if (musicDir.exists()) {
            quickPaths.add(musicDir.getAbsolutePath());
            quickLabels.add("Music: " + musicDir.getAbsolutePath());
        }

        // 检测是否有额外SD卡(API 19+)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.KITKAT) {
            File[] extDirs = getExternalMediaDirs();
            if (extDirs != null) {
                for (File dir : extDirs) {
                    if (dir != null && dir.exists() && !dir.getAbsolutePath().startsWith(sdRoot)) {
                        File parent = dir;
                        while (parent.getParentFile() != null
                                && parent.getParentFile().getParentFile() != null
                                && parent.getParentFile().getParentFile().canRead()) {
                            parent = parent.getParentFile();
                            if (parent.getAbsolutePath().equals("/") || parent.getAbsolutePath().length() < 5) {
                                break;
                            }
                        }
                        String parentPath = parent.getAbsolutePath();
                        if (!quickPaths.contains(parentPath)) {
                            quickPaths.add(parentPath);
                            quickLabels.add("SD卡: " + parentPath);
                        }
                    }
                }
            }
        }

        // 检查 /storage 下的其他挂载点
        File storageDir = new File("/storage");
        if (storageDir.exists() && storageDir.isDirectory()) {
            File[] mounts = storageDir.listFiles();
            if (mounts != null) {
                for (File m : mounts) {
                    if (m.isDirectory() && m.canRead() && !m.getAbsolutePath().startsWith(sdRoot)) {
                        String p = m.getAbsolutePath();
                        if (!quickPaths.contains(p)) {
                            quickPaths.add(p);
                            quickLabels.add("存储: " + p);
                        }
                    }
                }
            }
        }

        quickLabels.add("手动输入路径...");
        quickLabels.add("浏览更多目录...");

        String[] labels = quickLabels.toArray(new String[0]);

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("选择同步目录");
        builder.setItems(labels, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                if (which == quickLabels.size() - 1) {
                    showDirectoryBrowser(Environment.getExternalStorageDirectory());
                } else if (which == quickLabels.size() - 2) {
                    showManualPathInput();
                } else {
                    setSyncPath(quickPaths.get(which));
                }
            }
        });
        builder.setNegativeButton("取消", null);
        builder.show();
    }

    /** 手动输入同步路径对话框 */
    private void showManualPathInput() {
        final EditText etInput = new EditText(this);
        etInput.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
        String currentPath = etSyncPath.getText().toString().trim();
        if (currentPath.isEmpty()) {
            currentPath = Environment.getExternalStorageDirectory()
                    .getAbsolutePath() + "/CaptivaMusic";
        }
        etInput.setText(currentPath);
        etInput.setSelection(currentPath.length());
        etInput.setTextColor(getResources().getColor(R.color.text_primary));
        etInput.setHint("输入完整路径,如 /storage/emulated/0/Music");
        etInput.setHintTextColor(getResources().getColor(R.color.search_hint));
        etInput.setPadding(24, 16, 24, 16);
        etInput.setBackgroundResource(R.drawable.bg_search);

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("手动输入同步目录");
        builder.setMessage("请输入完整的目录路径\n路径必须以 / 开头");
        builder.setView(etInput);
        builder.setPositiveButton("确定", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                String path = etInput.getText().toString().trim();
                if (path.isEmpty()) {
                    Toast.makeText(ServerSettingsActivity.this,
                            "路径不能为空", Toast.LENGTH_SHORT).show();
                    return;
                }
                if (!path.startsWith("/")) {
                    Toast.makeText(ServerSettingsActivity.this,
                            "路径必须以 / 开头", Toast.LENGTH_SHORT).show();
                    return;
                }
                setSyncPath(path);
            }
        });
        builder.setNegativeButton("取消", null);
        builder.show();
    }

    /** 目录浏览器对话框(可逐级浏览) */
    private void showDirectoryBrowser(final File startDir) {
        if (startDir == null || !startDir.exists() || !startDir.isDirectory()) {
            Toast.makeText(this, "无法访问该目录", Toast.LENGTH_SHORT).show();
            return;
        }
        File[] children = startDir.listFiles();
        if (children == null) {
            Toast.makeText(this, "无法读取目录内容", Toast.LENGTH_SHORT).show();
            return;
        }
        List<File> subDirs = new ArrayList<>();
        for (File f : children) {
            if (f.isDirectory() && !f.getName().startsWith(".") && f.canRead()) {
                subDirs.add(f);
            }
        }
        Collections.sort(subDirs, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                return a.getName().compareToIgnoreCase(b.getName());
            }
        });
        List<String> items = new ArrayList<>();
        items.add("✓ 选定此目录");
        File parent = startDir.getParentFile();
        if (parent != null && parent.canRead()) {
            items.add("📁 返回上级");
        }
        for (File d : subDirs) {
            items.add("📁 " + d.getName());
        }
        String[] arr = items.toArray(new String[0]);

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(startDir.getAbsolutePath());
        builder.setItems(arr, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                if (which == 0) {
                    setSyncPath(startDir.getAbsolutePath());
                } else if (which == 1 && parent != null && parent.canRead()) {
                    showDirectoryBrowser(parent);
                } else {
                    int index = which;
                    if (parent != null && parent.canRead()) {
                        index--;
                    }
                    index--;
                    if (index >= 0 && index < subDirs.size()) {
                        showDirectoryBrowser(subDirs.get(index));
                    }
                }
            }
        });
        builder.setNegativeButton("取消", null);
        builder.show();
    }

    /** 设置同步路径:立即持久化 + 更新UI */
    private void setSyncPath(String path) {
        config.setSyncPath(path);  // 立即保存到 SharedPreferences
        etSyncPath.setText(path);
        File dir = new File(path);
        if (!dir.exists()) {
            dir.mkdirs();
        }
        Toast.makeText(this, "已设置同步目录: " + path, Toast.LENGTH_LONG).show();
    }

    // ==================== 测试连接 ====================

    /** 测试连接(异步) */
    private void testConnection() {
        final String url = etUrl.getText().toString().trim();
        final String user = etUser.getText().toString().trim();
        final String pass = etPass.getText().toString().trim();

        if (url.isEmpty() || user.isEmpty() || pass.isEmpty()) {
            showResult("请填写完整的服务器信息", false);
            return;
        }
        final String type = getCurrentServerType();

        tvResult.setVisibility(View.VISIBLE);
        btnTest.setEnabled(false);

        // 飞牛 + 填的是 FN ID:先联网解析出可达地址(内网 / 公网 IPv6 / 公网 IPv4 / 中继),再测登录
        if (MusicSourceFactory.TYPE_FNMUSIC.equals(type) && MusicSourceFactory.looksLikeFnId(url)) {
            tvResult.setText("正在解析 FN ID,请稍候...");
            new Thread(new Runnable() {
                @Override
                public void run() {
                    final FnIdResolver.Addr addr = MusicSourceFactory.resolveFnId(url);
                    if (addr == null) {
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                btnTest.setEnabled(true);
                                showResult("FN ID 解析失败:该 NAS 当前不可达。"
                                        + "请确认 NAS 已开机、FN Connect 已开启", false);
                            }
                        });
                        return;
                    }
                    MusicSourceApi api = MusicSourceFactory.createFn(addr.url, user, pass, addr.relay);
                    final boolean ok = api.ping();
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            btnTest.setEnabled(true);
                            String route = addr.relay ? "飞牛中继" : "直连";
                            if (ok) {
                                showResult("连接成功!(" + route + ") " + addr.url, true);
                            } else {
                                showResult("已解析到 " + addr.url + "(" + route
                                        + "),但登录失败,请检查账号密码", false);
                            }
                        }
                    });
                }
            }).start();
            return;
        }

        tvResult.setText("正在测试连接...");
        new Thread(new Runnable() {
            @Override
            public void run() {
                MusicSourceApi api = MusicSourceFactory.create(type, url, user, pass);
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

    // ==================== 保存配置 ====================

    /** 保存配置 */
    private void saveConfig() {
        final String url = etUrl.getText().toString().trim();
        final String user = etUser.getText().toString().trim();
        final String pass = etPass.getText().toString().trim();
        final String syncPath = etSyncPath.getText().toString().trim();

        if (url.isEmpty() || user.isEmpty() || pass.isEmpty()) {
            Toast.makeText(this, "请填写完整的服务器信息", Toast.LENGTH_SHORT).show();
            return;
        }
        if (syncPath.isEmpty()) {
            Toast.makeText(this, "同步目录不能为空", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!syncPath.startsWith("/")) {
            Toast.makeText(this, "同步目录路径必须以 / 开头", Toast.LENGTH_SHORT).show();
            return;
        }

        final String type = getCurrentServerType();

        // 飞牛 + FN ID:先解析,拿到真实地址与中继标志后再落盘
        if (MusicSourceFactory.TYPE_FNMUSIC.equals(type) && MusicSourceFactory.looksLikeFnId(url)) {
            tvResult.setVisibility(View.VISIBLE);
            tvResult.setText("正在解析 FN ID,请稍候...");
            btnSave.setEnabled(false);
            new Thread(new Runnable() {
                @Override
                public void run() {
                    final FnIdResolver.Addr addr = MusicSourceFactory.resolveFnId(url);
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            btnSave.setEnabled(true);
                            if (addr == null) {
                                showResult("FN ID 解析失败,未保存。请确认 NAS 开机 / FN Connect 已开启", false);
                                return;
                            }
                            applyAndSave(addr.url, addr.relay, url, user, pass, syncPath);
                        }
                    });
                }
            }).start();
            return;
        }

        applyAndSave(url, false, null, user, pass, syncPath);
    }

    /**
     * 写入配置 + 更新全局数据源 + 关闭页面
     *
     * @param serverUrl 实际连接用的地址(FN ID 场景为解析后的地址)
     * @param relay     该地址是否走飞牛中继
     * @param fnId      原始 FN ID(非 FN ID 场景传 null)
     */
    private void applyAndSave(String serverUrl, boolean relay, String fnId,
                              String user, String pass, String syncPath) {
        config.setServerUrl(serverUrl);
        config.setUsername(user);
        config.setPassword(pass);
        config.setSyncPath(syncPath);
        config.setServerType(getCurrentServerType());
        config.setFnId(fnId);
        config.setFnRelay(relay);
        config.setEnabled(true);

        // 确保目录存在
        File dir = new File(syncPath);
        if (!dir.exists()) {
            dir.mkdirs();
        }

        // 更新全局数据源实例(飞牛必须带上中继标志,否则中继地址连不上)
        MusicSourceApi api;
        if (MusicSourceFactory.TYPE_FNMUSIC.equals(config.getServerType())) {
            api = MusicSourceFactory.createFn(serverUrl, user, pass, relay);
        } else {
            api = MusicSourceFactory.create(config.getServerType(), serverUrl, user, pass);
        }
        MusicDataHolder.getInstance().setMusicSourceApi(api);
        MusicDataHolder.getInstance().setNavidromeEnabled(true);

        Toast.makeText(this, "配置已保存", Toast.LENGTH_SHORT).show();
        finish();
    }

    /** 读取当前界面上选中的服务器类型 */
    private String getCurrentServerType() {
        if (rbTypeFnMusic != null && rbTypeFnMusic.isChecked()) {
            return MusicSourceFactory.TYPE_FNMUSIC;
        }
        return MusicSourceFactory.TYPE_NAVIDROME;
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

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            hideSystemUI();
        }
    }
}
