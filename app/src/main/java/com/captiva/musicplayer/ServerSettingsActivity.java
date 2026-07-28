package com.captiva.musicplayer;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.os.Bundle;
import android.os.Environment;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Navidrome 服务器设置界面
 * 输入服务器地址、用户名、密码,支持测试连接
 * 使用系统输入法
 */
public class ServerSettingsActivity extends AppCompatActivity {

    private EditText etUrl, etUser, etPass;
    private TextView etSyncPath;
    private TextView tvResult;
    private Button btnTest, btnSave, btnBack, btnSelectSyncPath;
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
        btnSelectSyncPath = findViewById(R.id.btn_select_sync_path);

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

        // 选择同步目录:弹出自定义目录浏览器
        btnSelectSyncPath.setOnClickListener(v -> {
            showDirectoryPicker();
        });

        // 点击路径文本也可选择
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
        // 快捷目录列表
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

        // 检测是否有额外SD卡(API 19+ 可用 getExternalMediaDirs,低版本跳过)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.KITKAT) {
            File[] extDirs = getExternalMediaDirs();
            if (extDirs != null) {
                for (File dir : extDirs) {
                    if (dir != null && dir.exists() && !dir.getAbsolutePath().startsWith(sdRoot)) {
                        String p = dir.getAbsolutePath();
                        // 取到存储根目录
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

        // 检查 /storage 下的其他挂载点(兼容低版本)
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

        // 添加"浏览更多目录"选项
        quickLabels.add("浏览更多目录...");

        String[] labels = quickLabels.toArray(new String[0]);

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("选择同步目录");
        builder.setItems(labels, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                if (which == quickLabels.size() - 1) {
                    // 最后一个:浏览更多目录
                    showDirectoryBrowser(Environment.getExternalStorageDirectory());
                } else {
                    String selected = quickPaths.get(which);
                    setSyncPath(selected);
                }
            }
        });
        builder.setNegativeButton("取消", null);
        builder.show();
    }

    /**
     * 目录浏览器对话框(可逐级浏览文件系统)
     * @param startDir 起始目录
     */
    private void showDirectoryBrowser(final File startDir) {
        if (startDir == null || !startDir.exists() || !startDir.isDirectory()) {
            Toast.makeText(this, "无法访问该目录", Toast.LENGTH_SHORT).show();
            return;
        }

        // 列出子目录
        File[] children = startDir.listFiles();
        if (children == null) {
            Toast.makeText(this, "无法读取目录内容", Toast.LENGTH_SHORT).show();
            return;
        }

        // 筛选子目录
        List<File> subDirs = new ArrayList<>();
        for (File f : children) {
            if (f.isDirectory() && !f.getName().startsWith(".") && f.canRead()) {
                subDirs.add(f);
            }
        }

        // 按名称排序
        Collections.sort(subDirs, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                return a.getName().compareToIgnoreCase(b.getName());
            }
        });

        // 构建列表项
        List<String> items = new ArrayList<>();
        // 第一项:选择当前目录
        items.add("✓ 选定此目录");
        // 第二项:返回上级
        File parent = startDir.getParentFile();
        if (parent != null && parent.canRead()) {
            items.add("📁 返回上级");
        }
        // 子目录列表
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
                    // 选定当前目录
                    setSyncPath(startDir.getAbsolutePath());
                } else if (which == 1 && parent != null && parent.canRead()) {
                    // 返回上级
                    showDirectoryBrowser(parent);
                } else {
                    // 进入子目录
                    int index = which;
                    if (parent != null && parent.canRead()) {
                        index--; // 跳过"返回上级"
                    }
                    index--; // 跳过"选定此目录"
                    if (index >= 0 && index < subDirs.size()) {
                        showDirectoryBrowser(subDirs.get(index));
                    }
                }
            }
        });
        builder.setNegativeButton("取消", null);
        builder.show();
    }

    /** 设置同步路径并更新UI */
    private void setSyncPath(String path) {
        config.setSyncPath(path);
        etSyncPath.setText(path);
        // 确保目录存在
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

    // ==================== 保存配置 ====================

    /** 保存配置 */
    private void saveConfig() {
        // 同步路径已经在选择时保存了,这里只需保存服务器配置
        if (!saveServerConfigSilently()) {
            return;
        }
        Toast.makeText(this, "配置已保存", Toast.LENGTH_SHORT).show();
        finish();
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
