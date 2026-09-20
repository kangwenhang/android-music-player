package com.captiva.musicplayer;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.os.Bundle;
import android.os.Environment;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
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

    /** 地址栏提示文案(集中管理,避免三处不一致) */
    private static final String HINT_FN = "FN ID(如 k495378412)或 http://192.168.1.100:5666";
    private static final String HINT_NAVIDROME = "http://192.168.1.100:4533";

    private EditText etUrl, etUser, etPass, etSyncPath, etLocalPath, etAutoCacheMax;
    private CheckBox cbAutoCache, cbAutoCacheWifi;
    /** 目录选择器当前目标:false=同步目录,true=本地模式目录 */
    private boolean pickingLocalPath = false;
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
        etLocalPath = findViewById(R.id.et_local_path);
        tvResult = findViewById(R.id.tv_test_result);
        btnTest = findViewById(R.id.btn_test);
        btnSave = findViewById(R.id.btn_save);
        btnBack = findViewById(R.id.btn_back);
        rgServerType = findViewById(R.id.rg_server_type);
        rbTypeNavidrome = findViewById(R.id.rb_type_navidrome);
        rbTypeFnMusic = findViewById(R.id.rb_type_fnmusic);
        cbAutoCache = findViewById(R.id.cb_auto_cache);
        cbAutoCacheWifi = findViewById(R.id.cb_auto_cache_wifi);
        etAutoCacheMax = findViewById(R.id.et_auto_cache_max);

        // 回填服务器类型
        if (MusicSourceFactory.TYPE_FNMUSIC.equals(config.getServerType())) {
            rbTypeFnMusic.setChecked(true);
        } else {
            rbTypeNavidrome.setChecked(true);
        }
        // 切换类型时给出默认地址提示
        rgServerType.setOnCheckedChangeListener((group, checkedId) -> {
            if (checkedId == R.id.rb_type_fnmusic) {
                etUrl.setHint(HINT_FN);
            } else {
                etUrl.setHint(HINT_NAVIDROME);
            }
        });
        // 初始提示(未触发切换回调时也要正确)
        if (rbTypeFnMusic.isChecked()) {
            etUrl.setHint(HINT_FN);
        } else {
            etUrl.setHint(HINT_NAVIDROME);
        }

        // 回填已保存的配置
        // 如果上次是用 FN ID 保存的,地址栏回填 FN ID 本身(而不是当时解析出来的地址),
        // 否则在家存的内网 IP 换个网络就失效,用户也看不出自己当初填的是什么。
        String savedFnId = config.getFnId();
        if (savedFnId != null && !savedFnId.trim().isEmpty()) {
            etUrl.setText(savedFnId);
        } else {
            etUrl.setText(config.getServerUrl());
        }
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
            pickingLocalPath = false;
            showDirectoryPicker();
        });
        // 点击本地路径输入框 → 同款目录选择器,目标为本地模式目录
        etLocalPath.setOnClickListener(v -> {
            pickingLocalPath = true;
            showDirectoryPicker();
        });
        // 回填本地模式目录(留空表示默认与同步目录相同,由 hint 提示)
        String savedLocalPath = config.getLocalScanPath();
        String syncPathDefault = config.getSyncPath();
        if (savedLocalPath != null && !savedLocalPath.isEmpty()
                && !savedLocalPath.equals(syncPathDefault)) {
            etLocalPath.setText(savedLocalPath);
        }

        // 回填自动缓存设置(默认:关闭 / 仅 Wi-Fi / 上限 2048MB)
        cbAutoCache.setChecked(config.isAutoCacheOnPlay());
        cbAutoCacheWifi.setChecked(config.isAutoCacheWifiOnly());
        etAutoCacheMax.setText(String.valueOf(config.getAutoCacheMaxMb()));
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
        builder.setTitle(pickingLocalPath ? "选择本地目录" : "选择同步目录");
        builder.setItems(labels, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                if (which == quickLabels.size() - 1) {
                    showDirectoryBrowser(Environment.getExternalStorageDirectory());
                } else if (which == quickLabels.size() - 2) {
                    showManualPathInput();
                } else {
                    applyPickedPath(quickPaths.get(which));
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
        String currentPath;
        if (pickingLocalPath) {
            currentPath = etLocalPath.getText().toString().trim();
        } else {
            currentPath = etSyncPath.getText().toString().trim();
        }
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
        builder.setTitle(pickingLocalPath ? "手动输入本地目录" : "手动输入同步目录");
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
                applyPickedPath(path);
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
                    applyPickedPath(startDir.getAbsolutePath());
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

    /** 目录选择器统一落点:按当前目标写入 同步目录 或 本地目录 */
    private void applyPickedPath(String path) {
        if (pickingLocalPath) {
            setLocalScanTarget(path);
        } else {
            setSyncPath(path);
        }
    }

    /** 设置本地模式目录:立即持久化 + 更新UI */
    private void setLocalScanTarget(String path) {
        config.setLocalScanPath(path);  // 立即保存到 SharedPreferences
        etLocalPath.setText(path);
        File dir = new File(path);
        if (!dir.exists()) {
            dir.mkdirs();
        }
        Toast.makeText(this, "已设置本地目录: " + path, Toast.LENGTH_LONG).show();
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

        // 兜底:裸 FN ID(不含 : / .)不可能是合法 URL。
        // 无论当前选的是哪种数据源,都按飞牛 FN ID 处理 —— 否则会掉进 Navidrome 分支,
        // 最后只报一句"连接失败,请检查地址和凭据",完全看不出真实原因。
        final boolean fnIdInput = MusicSourceFactory.looksLikeFnId(url);

        tvResult.setVisibility(View.VISIBLE);
        btnTest.setEnabled(false);

        // 飞牛 + 填的是 FN ID:先联网解析出可达地址(内网 / 公网 IPv6 / 公网 IPv4 / 中继),再测登录
        if (fnIdInput) {
            if (!MusicSourceFactory.TYPE_FNMUSIC.equals(type)) {
                // 同步界面选中项,避免"选着 Navidrome 实际在连飞牛"的错位
                rbTypeFnMusic.setChecked(true);
            }
            tvResult.setText("正在解析 FN ID,请稍候...");
            new Thread(new Runnable() {
                @Override
                public void run() {
                    final FnIdResolver.Addr addr = MusicSourceFactory.resolveFnId(url);
                    if (addr == null) {
                        final String why = buildResolveFailReason(url);
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                btnTest.setEnabled(true);
                                showResult("FN ID 解析失败\n" + why, false);
                            }
                        });
                        return;
                    }
                    MusicSourceApi api = MusicSourceFactory.createFn(addr.url, user, pass, addr.relay);
                    final boolean ok = api.ping();
                    final String reason = ok ? null : lastErrorOf(api);
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            btnTest.setEnabled(true);
                            String route = addr.relay ? "飞牛中继" : "直连";
                            if (ok) {
                                showResult("连接成功!(" + route + ") " + addr.url, true);
                            } else {
                                showResult("已解析到 " + addr.url + "(" + route + ")\n"
                                        + "但登录失败:" + reason, false);
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
                final String reason = ok ? null : lastErrorOf(api);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        btnTest.setEnabled(true);
                        if (ok) {
                            showResult("连接成功!服务器响应正常", true);
                        } else {
                            // 带上真实原因:HTTP 状态码 / TLS 版本过旧 / DNS 失败……
                            showResult("连接失败:" + reason + "\n地址: " + url, false);
                        }
                    }
                });
            }
        }).start();
    }

    /** FN ID 解析失败时,把原因讲清楚(解析服务 / 探测结果) */
    private String buildResolveFailReason(String fnId) {
        String e1 = FnIdResolver.getLastError();
        String e2 = FnIdResolver.getLastProbeError();
        StringBuilder sb = new StringBuilder();
        sb.append("FN ID: ").append(fnId);
        if (e1 != null) {
            sb.append("\n· 解析阶段:").append(e1);
        }
        if (e2 != null) {
            sb.append("\n· 探测阶段:").append(e2);
        }
        if (e1 == null && e2 == null) {
            sb.append("\n· NAS 未开机,或 FN Connect / 远程访问未开启");
        }
        return sb.toString();
    }

    /** 取数据源记录的失败原因;没有则给一句兜底说明 */
    private static String lastErrorOf(MusicSourceApi api) {
        if (api instanceof FnMusicApi) {
            String e = ((FnMusicApi) api).getLastError();
            return e != null ? e : "服务器无响应(地址错误 / 未开机 / 不在同一网络)";
        }
        if (api instanceof NavidromeApi) {
            String e = ((NavidromeApi) api).getLastError();
            return e != null ? e : "服务器无响应(地址错误 / 未开机 / 不在同一网络)";
        }
        return "服务器无响应";
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

        // 与测试连接同一套判定:裸 FN ID 一律按飞牛处理(不依赖单选状态)
        boolean fnIdInput = MusicSourceFactory.looksLikeFnId(url);
        if (fnIdInput && !MusicSourceFactory.TYPE_FNMUSIC.equals(type)) {
            rbTypeFnMusic.setChecked(true);
        }

        // 飞牛 + FN ID:先解析,拿到真实地址与中继标志后再落盘
        if (fnIdInput) {
            tvResult.setVisibility(View.VISIBLE);
            tvResult.setText("正在解析 FN ID,请稍候...");
            btnSave.setEnabled(false);
            new Thread(new Runnable() {
                @Override
                public void run() {
                    final FnIdResolver.Addr addr = MusicSourceFactory.resolveFnId(url);
                    final String why = addr == null ? buildResolveFailReason(url) : null;
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            btnSave.setEnabled(true);
                            if (addr == null) {
                                showResult("FN ID 解析失败,未保存\n" + why, false);
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
        // 自动缓存设置(播放云端歌曲时下载到本地)
        config.setAutoCacheOnPlay(cbAutoCache.isChecked());
        config.setAutoCacheWifiOnly(cbAutoCacheWifi.isChecked());
        String maxStr = etAutoCacheMax.getText().toString().trim();
        int maxMb = 2048;
        try {
            maxMb = Integer.parseInt(maxStr);
        } catch (Exception e) {
            maxMb = 2048;
        }
        if (maxMb < 0) {
            maxMb = 0;
        }
        config.setAutoCacheMaxMb(maxMb);
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
