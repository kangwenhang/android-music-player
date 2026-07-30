package com.captiva.musicplayer;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.util.List;

/**
 * 均衡器界面(美化版)
 * - 支持开关、预设选择(内置+自定义)、各频段手动调节
 * - 保存自定义预设模式(持久化)
 * - 绑定均衡器到单曲(切歌自动恢复)
 * - 长按自定义预设可删除
 * - 无需播放歌曲即可调节(使用全局音频会话)
 * - 设置自动保存,播放时自动恢复
 */
public class EqualizerActivity extends AppCompatActivity {

    private Switch swEnable;
    private LinearLayout llPresets;
    private LinearLayout llBands;
    private TextView tvHint;
    private Button btnSaveCustom;
    private Button btnBindSong;
    private TextView tvSongBinding;

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
        btnSaveCustom = findViewById(R.id.btn_save_custom);
        btnBindSong = findViewById(R.id.btn_bind_song);
        tvSongBinding = findViewById(R.id.tv_song_binding);
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

        // 保存自定义预设按钮
        btnSaveCustom.setOnClickListener(v -> showSaveCustomPresetDialog());

        // 绑定当前EQ到歌曲按钮
        btnBindSong.setOnClickListener(v -> showBindSongDialog());

        // 根据状态显示提示
        updateHint();
        updateSongBindingStatus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 返回时刷新预设按钮(可能在其他地方添加了自定义预设)
        buildPresetButtons();
        updateSongBindingStatus();
    }

    /** 更新提示文字 */
    private void updateHint() {
        if (!eqManager.isAvailable()) {
            tvHint.setText("设置已保存,开始播放后自动生效");
        } else {
            tvHint.setText("设置会自动保存,播放音乐时生效");
        }
    }

    /** 更新当前歌曲EQ绑定状态显示 */
    private void updateSongBindingStatus() {
        // 尝试获取当前播放歌曲
        MusicBean currentSong = getCurrentPlayingSong();
        if (currentSong == null) {
            tvSongBinding.setText("当前无播放歌曲");
            btnBindSong.setEnabled(false);
            btnBindSong.setText("绑定当前EQ到此歌曲");
            return;
        }
        btnBindSong.setEnabled(true);
        String songEq = eqManager.getSongEqPreset(currentSong);
        if (songEq != null && !songEq.isEmpty()) {
            tvSongBinding.setText("当前歌曲「" + truncate(currentSong.getTitle(), 15)
                    + "」已绑定: " + songEq);
            btnBindSong.setText("修改绑定 / 取消绑定");
        } else {
            tvSongBinding.setText("当前歌曲「" + truncate(currentSong.getTitle(), 15)
                    + "」未绑定EQ");
            btnBindSong.setText("绑定当前EQ到此歌曲");
        }
    }

    /** 获取当前播放歌曲(通过 MusicDataHolder 获取) */
    private MusicBean getCurrentPlayingSong() {
        return MusicDataHolder.getInstance().getCurrentPlayingMusic();
    }

    /** 截断字符串 */
    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) + "..." : s;
    }

    /** 构建预设按钮(内置 + 自定义) */
    private void buildPresetButtons() {
        llPresets.removeAllViews();
        String current = eqManager.getActivePreset();

        // 内置预设
        for (String p : EqualizerManager.PRESETS_DEFAULT) {
            Button btn = createPresetButton(p, p.equals(current), false);
            llPresets.addView(btn);
        }

        // 自定义预设
        List<String> customNames = eqManager.getCustomPresetNames();
        for (String p : customNames) {
            Button btn = createPresetButton(p, p.equals(current), true);
            llPresets.addView(btn);
        }
    }

    /** 创建单个预设按钮 */
    private Button createPresetButton(String preset, boolean selected, boolean isCustom) {
        Button btn = new Button(this);
        btn.setText(preset);
        btn.setMinWidth(96);
        btn.setMinHeight(48);
        btn.setPadding(32, 16, 32, 16);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 12, 0);
        btn.setLayoutParams(lp);
        highlightPreset(btn, selected);
        btn.setOnClickListener(v -> {
            if ("关闭".equals(preset)) {
                swEnable.setChecked(false);
                eqManager.applyPreset("关闭");
            } else {
                swEnable.setChecked(true);
                eqManager.applyPreset(preset);
            }
            refreshPresetButtons(preset);
            refreshBandSeekbars();
            updateSongBindingStatus();
        });

        // 自定义预设支持长按删除
        if (isCustom) {
            btn.setOnLongClickListener(v -> {
                showDeleteCustomPresetDialog(preset);
                return true;
            });
        }
        return btn;
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

    // ==================== 自定义预设操作 ====================

    /** 弹出保存自定义预设对话框 */
    private void showSaveCustomPresetDialog() {
        // 收集当前频段设置
        short count = eqManager.getBandCount();
        if (count == 0) count = 5;
        final short[] currentLevels = new short[count];
        for (short i = 0; i < count; i++) {
            currentLevels[i] = eqManager.getBandLevel(i);
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("保存自定义预设");

        final EditText etName = new EditText(this);
        etName.setHint("输入预设名称");
        etName.setInputType(InputType.TYPE_CLASS_TEXT);
        int padding = (int) (20 * getResources().getDisplayMetrics().density);
        etName.setPadding(padding, padding, padding, padding);
        builder.setView(etName);

        builder.setPositiveButton("保存", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                String name = etName.getText().toString().trim();
                if (name.isEmpty()) {
                    Toast.makeText(EqualizerActivity.this, "请输入名称", Toast.LENGTH_SHORT).show();
                    return;
                }
                // 检查是否与内置预设重名
                boolean isBuiltin = false;
                for (String builtin : EqualizerManager.PRESETS_DEFAULT) {
                    if (builtin.equals(name)) {
                        isBuiltin = true;
                        break;
                    }
                }
                if (isBuiltin) {
                    Toast.makeText(EqualizerActivity.this,
                            "不能与内置预设重名", Toast.LENGTH_SHORT).show();
                    return;
                }
                // 尝试保存(如果已存在,询问是否覆盖)
                if (eqManager.getCustomPresetLevels(name) != null) {
                    // 已存在,询问覆盖
                    new AlertDialog.Builder(EqualizerActivity.this)
                            .setTitle("预设已存在")
                            .setMessage("预设「" + name + "」已存在,是否覆盖?")
                            .setPositiveButton("覆盖", (d, w) -> {
                                eqManager.overwriteCustomPreset(name, currentLevels);
                                Toast.makeText(EqualizerActivity.this,
                                        "已覆盖预设: " + name, Toast.LENGTH_SHORT).show();
                                buildPresetButtons();
                            })
                            .setNegativeButton("取消", null)
                            .show();
                } else {
                    boolean ok = eqManager.saveCustomPreset(name, currentLevels);
                    if (ok) {
                        Toast.makeText(EqualizerActivity.this,
                                "已保存预设: " + name, Toast.LENGTH_SHORT).show();
                        buildPresetButtons();
                    } else {
                        Toast.makeText(EqualizerActivity.this,
                                "保存失败", Toast.LENGTH_SHORT).show();
                    }
                }
            }
        });
        builder.setNegativeButton("取消", null);
        builder.show();
    }

    /** 弹出删除自定义预设确认对话框 */
    private void showDeleteCustomPresetDialog(final String presetName) {
        new AlertDialog.Builder(this)
                .setTitle("删除自定义预设")
                .setMessage("确定删除预设「" + presetName + "」吗?")
                .setPositiveButton("删除", (d, w) -> {
                    boolean ok = eqManager.deleteCustomPreset(presetName);
                    if (ok) {
                        Toast.makeText(this, "已删除: " + presetName, Toast.LENGTH_SHORT).show();
                        buildPresetButtons();
                    } else {
                        Toast.makeText(this, "删除失败", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ==================== 单曲EQ绑定 ====================

    /** 弹出绑定/取消绑定对话框 */
    private void showBindSongDialog() {
        final MusicBean currentSong = getCurrentPlayingSong();
        if (currentSong == null) {
            Toast.makeText(this, "当前无播放歌曲", Toast.LENGTH_SHORT).show();
            return;
        }

        String songEq = eqManager.getSongEqPreset(currentSong);
        if (songEq != null && !songEq.isEmpty()) {
            // 已有绑定,提供修改或取消选项
            List<String> allPresets = eqManager.getAllPresetNames();
            final String[] items = new String[allPresets.size() + 1];
            for (int i = 0; i < allPresets.size(); i++) {
                items[i] = allPresets.get(i);
            }
            items[allPresets.size()] = "取消绑定";

            new AlertDialog.Builder(this)
                    .setTitle("修改歌曲EQ绑定\n「" + truncate(currentSong.getTitle(), 20) + "」")
                    .setItems(items, (d, which) -> {
                        if (which == allPresets.size()) {
                            // 取消绑定
                            eqManager.unbindSongEq(currentSong);
                            Toast.makeText(this, "已取消绑定", Toast.LENGTH_SHORT).show();
                        } else {
                            String preset = allPresets.get(which);
                            eqManager.bindSongEq(currentSong, preset);
                            eqManager.applyPreset(preset);
                            refreshPresetButtons(preset);
                            refreshBandSeekbars();
                            Toast.makeText(this, "已绑定: " + preset, Toast.LENGTH_SHORT).show();
                        }
                        updateSongBindingStatus();
                    })
                    .show();
        } else {
            // 无绑定,直接绑定当前生效的预设
            String activePreset = eqManager.getActivePreset();
            if ("关闭".equals(activePreset)) {
                Toast.makeText(this, "当前EQ为关闭状态,请先选择一个预设", Toast.LENGTH_SHORT).show();
                return;
            }
            new AlertDialog.Builder(this)
                    .setTitle("绑定歌曲EQ")
                    .setMessage("将「" + activePreset + "」绑定到「"
                            + truncate(currentSong.getTitle(), 20) + "」?\n"
                            + "绑定后,播放此歌曲时自动切换到此EQ模式")
                    .setPositiveButton("绑定", (d, w) -> {
                        eqManager.bindSongEq(currentSong, activePreset);
                        Toast.makeText(this, "已绑定: " + activePreset, Toast.LENGTH_SHORT).show();
                        updateSongBindingStatus();
                    })
                    .setNegativeButton("取消", null)
                    .show();
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
