package com.captiva.musicplayer;

import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

/**
 * 均衡器界面(美化版)
 * - 支持开关、预设选择、各频段手动调节
 * - 无需播放歌曲即可调节(使用全局音频会话)
 * - 设置自动保存,播放时自动恢复
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

        if (eqManager == null) {
            // 兜底:创建新的均衡器管理器
            eqManager = new EqualizerManager();
            eqManager.setContext(this);
            MusicDataHolder.getInstance().setEqualizerManager(eqManager);
        }

        // 如果均衡器尚未初始化(未播放歌曲),使用静默模式初始化
        if (!eqManager.isAvailable()) {
            eqManager.setContext(this);
            eqManager.initSilent();
        }

        // UI 初始化
        swEnable.setChecked(eqManager.isEnabled());
        swEnable.setOnCheckedChangeListener((button, checked) -> {
            eqManager.setEnabled(checked);
            if (checked && "关闭".equals(eqManager.getCurrentPreset())) {
                eqManager.applyPreset("流行");
                refreshPresetButtons("流行");
            } else if (!checked) {
                refreshPresetButtons("关闭");
            }
            // 开关变化时刷新频段显示
            refreshBandSeekbars();
        });

        buildPresetButtons();
        buildBandSeekbars();

        // 根据状态显示提示
        updateHint();
    }

    /** 更新提示文字 */
    private void updateHint() {
        if (!eqManager.isAvailable()) {
            tvHint.setText("设置已保存,开始播放后自动生效");
        } else {
            tvHint.setText("设置会自动保存,播放音乐时生效");
        }
    }

    /** 构建预设按钮 */
    private void buildPresetButtons() {
        String[] presets = EqualizerManager.PRESETS_DEFAULT;
        llPresets.removeAllViews();
        String current = eqManager.getCurrentPreset();
        for (String p : presets) {
            Button btn = new Button(this);
            btn.setText(p);
            btn.setMinWidth(96);
            btn.setMinHeight(48);
            btn.setPadding(32, 16, 32, 16);
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
                refreshBandSeekbars();
            });
            llPresets.addView(btn);
        }
    }

    /** 刷新预设按钮高亮 */
    private void refreshPresetButtons(String selected) {
        for (int i = 0; i < llPresets.getChildCount(); i++) {
            View child = llPresets.getChildAt(i);
            if (child instanceof Button) {
                Button b = (Button) child;
                highlightPreset(b, b.getText().toString().equals(selected));
            }
        }
    }

    /** 设置预设按钮高亮样式 */
    private void highlightPreset(Button btn, boolean selected) {
        if (selected) {
            btn.setBackgroundResource(R.drawable.bg_eq_preset_selected);
            btn.setTextColor(0xFF0A1A2A);
        } else {
            btn.setBackgroundResource(R.drawable.bg_eq_preset);
            btn.setTextColor(0xFFC0C0C5);
        }
    }

    /** 构建频段 SeekBar */
    private void buildBandSeekbars() {
        llBands.removeAllViews();
        short count = eqManager.getBandCount();
        if (count == 0) {
            // 没有 Equalizer 实例时,用默认 5 段构建 UI
            count = 5;
        }
        short[] range = eqManager.getBandLevelRange();
        final int min = range[0];
        final int max = range[1];

        for (short i = 0; i < count; i++) {
            final short band = i;
            int centerFreq = eqManager.getCenterFreq(band);
            String freqText = formatFreq(centerFreq);
            short currentLevel = eqManager.getBandLevel(band);

            // 垂直布局:频率标签 + 垂直 SeekBar + 当前值
            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setGravity(Gravity.CENTER_HORIZONTAL);
            LinearLayout.LayoutParams colLp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
            colLp.setMargins(8, 0, 8, 0);
            col.setLayoutParams(colLp);

            // 频率标签
            TextView tvFreq = new TextView(this);
            tvFreq.setText(freqText);
            tvFreq.setTextColor(0xFF9A9AA0);
            tvFreq.setTextSize(14f);
            tvFreq.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams freqLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            freqLp.gravity = Gravity.CENTER;
            freqLp.bottomMargin = 12;
            tvFreq.setLayoutParams(freqLp);

            // 垂直 SeekBar(自定义 View)
            VerticalSeekBar sb = new VerticalSeekBar(this);
            sb.setMax(max - min);
            sb.setProgress(currentLevel - min);
            LinearLayout.LayoutParams sbLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    0);
            sbLp.weight = 1f;
            sbLp.gravity = Gravity.CENTER;
            sbLp.setMargins(0, 8, 0, 8);
            sb.setLayoutParams(sbLp);
            sb.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(android.widget.SeekBar seekBar, int progress, boolean fromUser) {
                    short level = (short) (min + progress);
                    eqManager.setBandLevel(band, level);
                    // 更新值标签
                    if (seekBar.getParent() instanceof LinearLayout) {
                        LinearLayout col = (LinearLayout) seekBar.getParent();
                        for (int j = 0; j < col.getChildCount(); j++) {
                            View child = col.getChildAt(j);
                            if (child instanceof TextView && child.getTag() != null
                                    && "value".equals(child.getTag())) {
                                ((TextView) child).setText(formatLevel(level));
                            }
                        }
                    }
                    // 手动调节后取消预设高亮
                    if (fromUser) {
                        refreshPresetButtons("");
                    }
                }

                @Override
                public void onStartTrackingTouch(android.widget.SeekBar seekBar) {
                }

                @Override
                public void onStopTrackingTouch(android.widget.SeekBar seekBar) {
                }
            });

            // 当前值标签
            TextView tvValue = new TextView(this);
            tvValue.setTag("value");
            tvValue.setText(formatLevel(currentLevel));
            tvValue.setTextColor(0xFF4FC3F7);
            tvValue.setTextSize(13f);
            tvValue.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams valLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            valLp.gravity = Gravity.CENTER;
            valLp.topMargin = 12;
            tvValue.setLayoutParams(valLp);

            col.addView(tvFreq);
            col.addView(sb);
            col.addView(tvValue);
            llBands.addView(col);
        }
    }

    /** 刷新频段 SeekBar(预设切换后调用) */
    private void refreshBandSeekbars() {
        short count = eqManager.getBandCount();
        if (count == 0) count = 5;
        short[] range = eqManager.getBandLevelRange();
        int min = range[0];

        for (int i = 0; i < llBands.getChildCount() && i < count; i++) {
            View child = llBands.getChildAt(i);
            if (child instanceof LinearLayout) {
                LinearLayout col = (LinearLayout) child;
                for (int j = 0; j < col.getChildCount(); j++) {
                    View v = col.getChildAt(j);
                    if (v instanceof VerticalSeekBar) {
                        VerticalSeekBar sb = (VerticalSeekBar) v;
                        short level = eqManager.getBandLevel((short) i);
                        sb.setProgress(level - min);
                    } else if (v instanceof TextView && v.getTag() != null
                            && "value".equals(v.getTag())) {
                        short level = eqManager.getBandLevel((short) i);
                        ((TextView) v).setText(formatLevel(level));
                    }
                }
            }
        }
    }

    /** 格式化频率显示 */
    private String formatFreq(int milliHz) {
        // centerFreq 单位为 milliHz
        float hz = milliHz / 1000f;
        if (hz >= 1000) {
            return String.format("%.1fk", hz / 1000f);
        }
        return String.format("%dHz", (int) hz);
    }

    /** 格式化增益值显示 */
    private String formatLevel(short level) {
        float db = level / 100f;
        if (db > 0) {
            return String.format("+%.1f", db);
        }
        return String.format("%.1f", db);
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
