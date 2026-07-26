package com.captiva.musicplayer;

import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

/**
 * 均衡器界面
 * 支持开关、预设选择、各频段手动调节
 */
public class EqualizerActivity extends AppCompatActivity {

    private Switch swEnable;
    private LinearLayout llPresets;
    private LinearLayout llBands;
    private TextView tvHint;

    private EqualizerManager eqManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 全屏沉浸模式
        hideSystemUI();
        setContentView(R.layout.activity_equalizer);

        swEnable = findViewById(R.id.sw_eq_enable);
        llPresets = findViewById(R.id.ll_presets);
        llBands = findViewById(R.id.ll_bands);
        tvHint = findViewById(R.id.tv_eq_hint);
        Button btnBack = findViewById(R.id.btn_eq_back);
        btnBack.setOnClickListener(v -> finish());

        // 从 Service 获取已初始化的均衡器实例
        eqManager = MusicDataHolder.getInstance().getEqualizerManager();

        if (eqManager == null || !eqManager.isAvailable()) {
            tvHint.setText("均衡器不可用,请先开始播放音乐后再试");
            swEnable.setEnabled(false);
            llPresets.setEnabled(false);
            return;
        }

        swEnable.setChecked(eqManager.isEnabled());
        swEnable.setOnCheckedChangeListener((button, checked) -> {
            eqManager.setEnabled(checked);
            if (checked && "关闭".equals(eqManager.getCurrentPreset())) {
                eqManager.applyPreset("流行");
                refreshPresetButtons("流行");
            } else if (!checked) {
                refreshPresetButtons("关闭");
            }
        });

        buildPresetButtons();
        buildBandSeekbars();
    }

    private void buildPresetButtons() {
        String[] presets = EqualizerManager.PRESETS_DEFAULT;
        llPresets.removeAllViews();
        String current = eqManager.getCurrentPreset();
        for (String p : presets) {
            Button btn = new Button(this);
            btn.setText(p);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, 0, 12, 0);
            btn.setLayoutParams(lp);
            highlightPreset(btn, p.equals(current));
            btn.setOnClickListener(v -> {
                if ("关闭".equals(p)) {
                    swEnable.setChecked(false);
                    eqManager.applyPreset("关闭");
                } else {
                    swEnable.setChecked(true);
                    eqManager.applyPreset(p);
                }
                refreshPresetButtons(p);
            });
            llPresets.addView(btn);
        }
    }

    private void refreshPresetButtons(String selected) {
        for (int i = 0; i < llPresets.getChildCount(); i++) {
            View child = llPresets.getChildAt(i);
            if (child instanceof Button) {
                Button b = (Button) child;
                highlightPreset(b, b.getText().toString().equals(selected));
            }
        }
    }

    private void highlightPreset(Button btn, boolean selected) {
        if (selected) {
            btn.setBackgroundColor(0xFF4FC3F7);
            btn.setTextColor(0xFF0A1A2A);
        } else {
            btn.setBackgroundColor(0xFF3A3A42);
            btn.setTextColor(0xFFFFFFFF);
        }
    }

    private void buildBandSeekbars() {
        llBands.removeAllViews();
        short count = eqManager.getBandCount();
        if (count == 0) {
            tvHint.setText("当前设备不支持频段调节");
            return;
        }
        short[] range = eqManager.getBandLevelRange();
        int min = range[0];
        int max = range[1];

        for (short i = 0; i < count; i++) {
            final short band = i;
            int centerFreq = eqManager.getCenterFreq(band);
            String freqText = formatFreq(centerFreq);

            // 垂直布局:频率标签 + 垂直 SeekBar + 当前值
            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setGravity(Gravity.CENTER_HORIZONTAL);
            LinearLayout.LayoutParams colLp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            colLp.setMargins(8, 0, 8, 0);
            col.setLayoutParams(colLp);

            TextView tvFreq = new TextView(this);
            tvFreq.setText(freqText);
            tvFreq.setTextColor(0xFF9A9AA0);
            tvFreq.setTextSize(14f);
            tvFreq.setGravity(Gravity.CENTER);

            // 垂直 SeekBar(通过 rotation 实现)
            SeekBar sb = new SeekBar(this);
            sb.setMax(max - min);
            sb.setProgress(eqManager.getBandLevel(band) - min);
            sb.setRotation(270f);
            LinearLayout.LayoutParams sbLp = new LinearLayout.LayoutParams(
                    160, 400);
            sbLp.gravity = Gravity.CENTER;
            sbLp.setMargins(0, 40, 0, 40);
            sb.setLayoutParams(sbLp);
            sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    short level = (short) (min + progress);
                    eqManager.setBandLevel(band, level);
                }

                @Override
                public void onStartTrackingTouch(SeekBar seekBar) {
                }

                @Override
                public void onStopTrackingTouch(SeekBar seekBar) {
                }
            });

            col.addView(tvFreq);
            col.addView(sb);
            llBands.addView(col);
        }
    }

    private String formatFreq(int milliHz) {
        // centerFreq 单位为 milliHz
        float hz = milliHz / 1000f;
        if (hz >= 1000) {
            return String.format("%.1fk", hz / 1000f);
        }
        return String.format("%dHz", (int) hz);
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
            getWindow().setFlags(
                    android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN,
                    android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN);
        }
    }
}
