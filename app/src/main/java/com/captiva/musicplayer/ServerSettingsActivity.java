package com.captiva.musicplayer;

import android.app.Dialog;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

/**
 * Navidrome 服务器设置界面
 * 输入服务器地址、用户名、密码,支持测试连接
 * 使用内置键盘(适配车机输入法问题)
 */
public class ServerSettingsActivity extends AppCompatActivity {

    private EditText etUrl, etUser, etPass;
    private TextView tvResult;
    private Button btnTest, btnSave, btnBack;
    private NavidromeConfig config;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_server_settings);

        config = new NavidromeConfig(this);

        etUrl = findViewById(R.id.et_server_url);
        etUser = findViewById(R.id.et_username);
        etPass = findViewById(R.id.et_password);
        tvResult = findViewById(R.id.tv_test_result);
        btnTest = findViewById(R.id.btn_test);
        btnSave = findViewById(R.id.btn_save);
        btnBack = findViewById(R.id.btn_back);

        // 回填已保存的配置
        etUrl.setText(config.getServerUrl());
        etUser.setText(config.getUsername());
        etPass.setText(config.getPassword());

        // 点击输入框时弹出内置键盘(禁用系统输入法)
        setupEditTextWithKeyboard(etUrl, "http://192.168.1.100:4533");
        setupEditTextWithKeyboard(etUser, "admin");
        setupEditTextWithKeyboard(etPass, "password");

        btnBack.setOnClickListener(v -> {
            finish();
        });

        btnTest.setOnClickListener(v -> {
            testConnection();
        });

        btnSave.setOnClickListener(v -> {
            saveConfig();
        });
    }

    /** 为 EditText 设置内置键盘(禁用系统输入法) */
    private void setupEditTextWithKeyboard(final EditText et, final String hint) {
        et.setHint(hint);
        et.setFocusable(false);
        et.setFocusableInTouchMode(false);
        et.setOnClickListener(v -> {
            showKeyboardDialog(et);
        });
    }

    /** 弹出内置键盘对话框 */
    private void showKeyboardDialog(final EditText targetEt) {
        final Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(getResources().getColor(R.color.bg_main));
        root.setPadding(16, 16, 16, 16);

        // 输入显示框
        final EditText tvInput = new EditText(this);
        tvInput.setTextSize(18);
        tvInput.setTextColor(getResources().getColor(R.color.text_primary));
        tvInput.setBackgroundColor(getResources().getColor(R.color.search_bg));
        tvInput.setText(targetEt.getText().toString());
        tvInput.setSingleLine(true);
        tvInput.setPadding(24, 16, 24, 16);
        LinearLayout.LayoutParams inputLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        inputLp.bottomMargin = 8;
        root.addView(tvInput, inputLp);

        // 内置键盘
        SimpleKeyboardView keyboard = new SimpleKeyboardView(this);
        LinearLayout.LayoutParams kbLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        root.addView(keyboard, kbLp);

        keyboard.bindInputTextView(tvInput);
        keyboard.setText(targetEt.getText().toString());
        keyboard.setOnTextChangedListener(new SimpleKeyboardView.OnTextChangedListener() {
            @Override
            public void onTextChanged(String text) {
                tvInput.setText(text);
            }
        });

        // 按钮栏
        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setPadding(8, 8, 8, 8);

        Button btnCancel = new Button(this);
        btnCancel.setText("取消");
        btnCancel.setBackgroundResource(R.drawable.bg_btn);
        btnCancel.setTextColor(getResources().getColor(R.color.btn_text));
        LinearLayout.LayoutParams cancelLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        cancelLp.setMargins(0, 0, 8, 0);
        btnCancel.setOnClickListener(v -> dialog.dismiss());
        btnRow.addView(btnCancel, cancelLp);

        Button btnConfirm = new Button(this);
        btnConfirm.setText("确定");
        btnConfirm.setBackgroundResource(R.drawable.bg_btn_play);
        btnConfirm.setTextColor(getResources().getColor(R.color.btn_play_text));
        LinearLayout.LayoutParams confirmLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        btnConfirm.setOnClickListener(v -> {
            targetEt.setText(tvInput.getText().toString());
            dialog.dismiss();
        });
        btnRow.addView(btnConfirm, confirmLp);

        root.addView(btnRow);

        dialog.setContentView(root);
        dialog.getWindow().setLayout(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        dialog.show();
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
        String url = etUrl.getText().toString().trim();
        String user = etUser.getText().toString().trim();
        String pass = etPass.getText().toString().trim();

        if (url.isEmpty() || user.isEmpty() || pass.isEmpty()) {
            Toast.makeText(this, "请填写完整信息", Toast.LENGTH_SHORT).show();
            return;
        }

        config.setServerUrl(url);
        config.setUsername(user);
        config.setPassword(pass);
        config.setEnabled(true);

        // 更新全局 NavidromeApi
        NavidromeApi api = new NavidromeApi(url, user, pass);
        MusicDataHolder.getInstance().setNavidromeApi(api);
        MusicDataHolder.getInstance().setNavidromeEnabled(true);

        Toast.makeText(this, "配置已保存", Toast.LENGTH_SHORT).show();
        finish();
    }

    private void showResult(String msg, boolean success) {
        tvResult.setVisibility(View.VISIBLE);
        tvResult.setText(msg);
        tvResult.setTextColor(success
                ? getResources().getColor(R.color.source_network)
                : getResources().getColor(R.color.btn_paused_bg));
    }
}
