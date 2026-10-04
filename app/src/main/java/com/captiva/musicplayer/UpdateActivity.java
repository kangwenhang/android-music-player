package com.captiva.musicplayer;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONObject;

/**
 * 检查更新页面(v5.7.405 之后替代 Toast/对话框流程)
 *
 * 为什么单独出页(用户要求"一点都不直观,单独出个更新页面"):
 * - Toast 一闪而过,对话框层级深,下载过程全程黑箱;
 * - 本页面把"当前版本 / 最新版本 / 状态 / 进度条 / 更新说明"全部常驻可视,
 *   下载有字节级进度条,失败原因直接写在状态行,不再靠猜。
 *
 * 风格(v5.7.406,用户要求"跟设置页面保持一致"):
 * bg_main 背景 + bg_title_bar 标题栏 + bg_info_card 信息卡片 +
 * bg_btn/bg_dialog_btn_positive 按钮,全部复用设置页的颜色/尺寸资源。
 *
 * 流程:进入自动检查 → 发现新版本点亮「下载安装」→ 点击后带进度条下载
 * → sha256 校验 → 调起系统安装器(失败会给 APK 落点提示)。
 */
public class UpdateActivity extends AppCompatActivity {

    private TextView tvCurrent;
    private TextView tvLatest;
    private TextView tvStatus;
    private TextView tvNotes;
    private ProgressBar pbDownload;
    private CheckBox cbDebugChannel;
    private Button btnCheck;
    private Button btnInstall;

    /** 检查到的待安装信息(非空时「下载安装」可用) */
    private UpdateChecker.UpdateInfo pendingInfo;
    private JSONObject pendingApkEntry;
    private boolean busy;

    private final Handler main = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_update);

        tvCurrent = (TextView) findViewById(R.id.tvCurrent);
        tvLatest = (TextView) findViewById(R.id.tvLatest);
        tvStatus = (TextView) findViewById(R.id.tvStatus);
        tvNotes = (TextView) findViewById(R.id.tvNotes);
        pbDownload = (ProgressBar) findViewById(R.id.pbDownload);
        cbDebugChannel = (CheckBox) findViewById(R.id.cbDebugChannel);
        btnCheck = (Button) findViewById(R.id.btnCheck);
        btnInstall = (Button) findViewById(R.id.btnInstall);
        Button btnClose = (Button) findViewById(R.id.btnClose);

        // 当前版本
        try {
            android.content.pm.PackageInfo pi = getPackageManager()
                    .getPackageInfo(getPackageName(), 0);
            tvCurrent.setText(pi.versionName + " (" + pi.versionCode + ")");
        } catch (Throwable t) {
            tvCurrent.setText("未知");
        }

        // 测试频道开关(记忆在 UpdateChecker 的 SharedPreferences)
        cbDebugChannel.setChecked(UpdateChecker.isAllowDebugUpdates(this));
        cbDebugChannel.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                UpdateChecker.setAllowDebugUpdates(UpdateActivity.this, isChecked);
                Toast.makeText(UpdateActivity.this,
                        isChecked ? "已开启:正式包也能收到测试版更新"
                                : "已关闭:正式包只收正式版更新",
                        Toast.LENGTH_SHORT).show();
            }
        });

        btnCheck.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startCheck();
            }
        });

        btnInstall.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startDownload();
            }
        });

        btnClose.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });

        // 进入页面自动检查一次(用户就是为检查更新而来的)
        startCheck();
    }

    // ------------------------------------------------------------------
    // 检查
    // ------------------------------------------------------------------

    private void startCheck() {
        if (busy) {
            return;
        }
        busy = true;
        pendingInfo = null;
        pendingApkEntry = null;
        btnCheck.setEnabled(false);
        btnInstall.setEnabled(false);
        setInstallEnabled(false);
        pbDownload.setVisibility(View.GONE);
        tvNotes.setVisibility(View.GONE);
        tvNotes.setText("");
        tvLatest.setText("检查中…");
        tvLatest.setTextColor(getResources().getColor(R.color.text_secondary));
        setStatus("正在解析分享链接…");

        UpdateChecker.check(this, new UpdateChecker.StatusListener() {
            @Override
            public void onStatus(String msg) {
                setStatus(msg);
                // 检查阶段的终止态(无更新/各类失败):解锁按钮。
                // UpdateCallback 只在"发现新版本"时被调,这两条路都不走回调。
                if (msg != null && (msg.startsWith("已是最新")
                        || msg.contains("失败")
                        || msg.contains("没有版本信息")
                        || msg.contains("没有 APK"))) {
                    busy = false;
                    btnCheck.setEnabled(true);
                    tvLatest.setText("无新版本");
                    tvLatest.setTextColor(getResources().getColor(R.color.text_secondary));
                }
            }
        }, new UpdateChecker.UpdateCallback() {
            @Override
            public void onUpdateFound(UpdateChecker.UpdateInfo info, JSONObject apkEntry) {
                busy = false;
                btnCheck.setEnabled(true);
                pendingInfo = info;
                pendingApkEntry = apkEntry;
                tvLatest.setText(info.versionName);
                tvLatest.setTextColor(getResources().getColor(R.color.accent));
                setStatus("发现新版本,点「下载安装」开始升级");
                tvNotes.setVisibility(View.VISIBLE);
                tvNotes.setText("更新说明:\n"
                        + (info.notes == null || info.notes.trim().isEmpty()
                        ? "(无)" : info.notes.trim()));
                btnInstall.setEnabled(true);
                setInstallEnabled(true);
            }
        });
    }

    // ------------------------------------------------------------------
    // 下载 + 安装
    // ------------------------------------------------------------------

    private void startDownload() {
        if (busy || pendingInfo == null || pendingApkEntry == null) {
            return;
        }
        busy = true;
        btnCheck.setEnabled(false);
        btnInstall.setEnabled(false);
        setInstallEnabled(false);
        cbDebugChannel.setEnabled(false);
        pbDownload.setVisibility(View.VISIBLE);
        pbDownload.setIndeterminate(true);
        setStatus("正在下载 " + pendingInfo.fileName + " …");

        UpdateChecker.downloadAndInstall(this, pendingInfo, pendingApkEntry,
                new UpdateChecker.StatusListener() {
                    @Override
                    public void onStatus(String msg) {
                        setStatus(msg);
                        // 到"校验通过,开始安装"即认为流程交给系统安装器了
                        if (msg != null && msg.contains("开始安装")) {
                            onInstallHandoff();
                        }
                    }
                },
                new UpdateChecker.ProgressListener() {
                    @Override
                    public void onProgress(final long done, final long total) {
                        main.post(new Runnable() {
                            @Override
                            public void run() {
                                if (total > 0) {
                                    pbDownload.setIndeterminate(false);
                                    pbDownload.setMax(100);
                                    pbDownload.setProgress((int) (done * 100 / total));
                                    setStatus("正在下载… " + done / 1024 + " KB / "
                                            + total / 1024 + " KB("
                                            + (done * 100 / total) + "%)");
                                } else {
                                    setStatus("正在下载… " + done / 1024 + " KB");
                                }
                            }
                        });
                    }
                });
    }

    /** 安装器已调起(或已提示手动安装):解锁页面 */
    private void onInstallHandoff() {
        busy = false;
        btnCheck.setEnabled(true);
        cbDebugChannel.setEnabled(true);
        pbDownload.setVisibility(View.GONE);
        // 下载安装到本版本完成,清掉待装状态(装完后当前版本即最新)
        btnInstall.setEnabled(false);
        pendingInfo = null;
        pendingApkEntry = null;
    }

    /** 「下载安装」按钮禁用态降透明度,与启用态形成视觉区分 */
    private void setInstallEnabled(boolean enabled) {
        btnInstall.setAlpha(enabled ? 1.0f : 0.45f);
    }

    private void setStatus(String msg) {
        tvStatus.setText(msg == null ? "" : msg);
    }
}
