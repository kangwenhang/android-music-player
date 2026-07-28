package com.captiva.musicplayer;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.audiofx.Equalizer;
import android.util.Log;

/**
 * 均衡器管理器
 * 封装系统 Equalizer(API 9+),提供预设和自定义频段调节
 *
 * 核心特性:
 * 1. 支持在未播放歌曲时调节 —— 使用全局音频会话(sessionId=0)初始化
 * 2. 设置持久化 —— 均衡器参数保存到 SharedPreferences,重启不丢失
 * 3. 自动恢复 —— 播放歌曲时重新绑定 audioSession,自动应用已保存的设置
 */
public class EqualizerManager {

    private static final String TAG = "EqualizerManager";
    private static final String PREFS_NAME = "equalizer_settings";

    public static final String[] PRESETS_DEFAULT = {"关闭", "流行", "摇滚", "古典", "人声"};

    // 预设参数(针对常见 5 段均衡器)
    private static final String[] PRESET_NAMES = {"流行", "摇滚", "古典", "人声"};
    private static final int[][] PRESET_LEVELS = {
            {0, 200, 400, 200, 0},       // 流行
            {400, 200, -100, 200, 400},  // 摇滚
            {300, 0, 0, 0, 300},         // 古典
            {-200, 0, 400, 200, 0}       // 人声
    };

    private Equalizer equalizer;
    private boolean enabled = false;
    private String currentPreset = "关闭";
    private short[] bandLevels; // 自定义频段等级(mB)
    private int bandCount = 5;  // 默认假设 5 段

    private Context context;

    public EqualizerManager() {
    }

    /** 设置 Context(用于持久化) */
    public void setContext(Context ctx) {
        this.context = ctx.getApplicationContext();
        loadSettings();
    }

    /**
     * 初始化均衡器,绑定到指定音频会话
     * 播放歌曲时调用,会自动恢复已保存的设置
     */
    public void init(int audioSessionId) {
        release();
        try {
            equalizer = new Equalizer(0, audioSessionId);
            bandCount = equalizer.getNumberOfBands();

            // 从持久化恢复设置(可能 bandCount 与保存的不同,需要适配)
            loadSettings();

            // 适配实际频段数:如果保存的 bandLevels 长度不匹配,重建数组
            if (bandLevels == null || bandLevels.length != bandCount) {
                short[] oldLevels = bandLevels;
                bandLevels = new short[bandCount];
                if (oldLevels != null) {
                    // 尽量保留已有值
                    for (short i = 0; i < bandCount && i < oldLevels.length; i++) {
                        bandLevels[i] = oldLevels[i];
                    }
                }
            }

            // 应用已保存的设置到新的 Equalizer 实例
            applyStoredSettings();
        } catch (Exception e) {
            Log.w(TAG, "Equalizer init failed", e);
            equalizer = null;
        }
    }

    /**
     * 静默初始化(使用全局音频会话)
     * 在未播放歌曲时调用,允许用户提前调节均衡器
     */
    public void initSilent() {
        release();
        try {
            // sessionId=0 表示全局音频输出,部分设备支持
            equalizer = new Equalizer(0, 0);
            bandCount = equalizer.getNumberOfBands();

            loadSettings();

            // 适配实际频段数
            if (bandLevels == null || bandLevels.length != bandCount) {
                short[] oldLevels = bandLevels;
                bandLevels = new short[bandCount];
                if (oldLevels != null) {
                    for (short i = 0; i < bandCount && i < oldLevels.length; i++) {
                        bandLevels[i] = oldLevels[i];
                    }
                }
            }

            applyStoredSettings();
        } catch (Exception e) {
            Log.w(TAG, "Equalizer initSilent failed (global session not supported)", e);
            equalizer = null;
            // 即使无法创建 Equalizer,也加载已保存的设置供 UI 显示
            loadSettings();
        }
    }

    /** 将已保存的设置应用到当前 Equalizer 实例 */
    private void applyStoredSettings() {
        if (equalizer == null) return;
        try {
            if (enabled) {
                equalizer.setEnabled(true);
            }
            // 应用预设或自定义频段
            if (!"关闭".equals(currentPreset)) {
                applyPresetInternal(currentPreset);
            } else if (bandLevels != null) {
                for (short i = 0; i < bandCount && i < bandLevels.length; i++) {
                    equalizer.setBandLevel(i, bandLevels[i]);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "applyStoredSettings failed", e);
        }
    }

    public void release() {
        if (equalizer != null) {
            try {
                equalizer.setEnabled(false);
                equalizer.release();
            } catch (Exception e) {
                Log.w(TAG, "release failed", e);
            }
            equalizer = null;
        }
    }

    public boolean isAvailable() {
        return equalizer != null;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** 获取频段数量 */
    public short getBandCount() {
        if (equalizer != null) {
            return equalizer.getNumberOfBands();
        }
        return (short) bandCount;
    }

    /** 获取频段中心频率(Hz) */
    public int getCenterFreq(short band) {
        if (equalizer == null) {
            // 返回默认频率(5段均衡器典型值)
            int[] defaultFreqs = {60000, 230000, 910000, 3600000, 14000000};
            if (band >= 0 && band < defaultFreqs.length) {
                return defaultFreqs[band];
            }
            return 0;
        }
        try {
            return equalizer.getCenterFreq(band);
        } catch (Exception e) {
            return 0;
        }
    }

    /** 获取频段当前等级(mB) */
    public short getBandLevel(short band) {
        if (equalizer != null) {
            try {
                return equalizer.getBandLevel(band);
            } catch (Exception e) {
                return 0;
            }
        }
        // 没有 Equalizer 实例时返回已保存的值
        if (bandLevels != null && band >= 0 && band < bandLevels.length) {
            return bandLevels[band];
        }
        return 0;
    }

    /** 获取等级范围 [min, max](mB) */
    public short[] getBandLevelRange() {
        if (equalizer != null) {
            try {
                return equalizer.getBandLevelRange();
            } catch (Exception e) {
                return new short[]{-1500, 1500};
            }
        }
        // 默认范围
        return new short[]{-1500, 1500};
    }

    /** 设置某个频段等级 */
    public void setBandLevel(short band, short level) {
        // 保存到内存
        if (bandLevels == null) {
            bandLevels = new short[Math.max(bandCount, band + 1)];
        }
        if (band < bandLevels.length) {
            bandLevels[band] = level;
        }
        // 应用到 Equalizer
        if (equalizer != null) {
            try {
                equalizer.setBandLevel(band, level);
            } catch (Exception e) {
                Log.w(TAG, "setBandLevel failed", e);
            }
        }
        // 切换到自定义模式
        currentPreset = "自定义";
        saveSettings();
    }

    /** 开关均衡器 */
    public void setEnabled(boolean on) {
        enabled = on;
        if (equalizer != null) {
            try {
                equalizer.setEnabled(on);
            } catch (Exception e) {
                Log.w(TAG, "setEnabled failed", e);
            }
        }
        if (!on) {
            currentPreset = "关闭";
        }
        saveSettings();
    }

    /** 应用预设(内部方法,不保存) */
    private void applyPresetInternal(String preset) {
        if (equalizer == null) return;
        short bc = equalizer.getNumberOfBands();
        if ("关闭".equals(preset)) {
            try { equalizer.setEnabled(false); } catch (Exception ignored) {}
            for (short i = 0; i < bc; i++) {
                try { equalizer.setBandLevel(i, (short) 0); } catch (Exception ignored) {}
                if (bandLevels != null && i < bandLevels.length) bandLevels[i] = 0;
            }
            return;
        }
        int[] target = getPresetLevels(preset);
        if (target == null) return;
        for (short i = 0; i < bc && i < target.length; i++) {
            try { equalizer.setBandLevel(i, (short) target[i]); } catch (Exception ignored) {}
            if (bandLevels != null && i < bandLevels.length) bandLevels[i] = (short) target[i];
        }
    }

    /** 应用预设 */
    public void applyPreset(String preset) {
        currentPreset = preset;
        if (equalizer != null) {
            if ("关闭".equals(preset)) {
                enabled = false;
                applyPresetInternal("关闭");
            } else {
                enabled = true;
                try { equalizer.setEnabled(true); } catch (Exception ignored) {}
                applyPresetInternal(preset);
            }
        } else {
            // 没有 Equalizer 实例,只保存设置
            if ("关闭".equals(preset)) {
                enabled = false;
            } else {
                enabled = true;
                int[] target = getPresetLevels(preset);
                if (target != null && bandLevels == null) {
                    bandLevels = new short[target.length];
                }
                if (target != null && bandLevels != null) {
                    for (int i = 0; i < target.length && i < bandLevels.length; i++) {
                        bandLevels[i] = (short) target[i];
                    }
                }
            }
        }
        saveSettings();
    }

    /** 获取预设对应的频段参数 */
    private int[] getPresetLevels(String preset) {
        for (int i = 0; i < PRESET_NAMES.length; i++) {
            if (PRESET_NAMES[i].equals(preset)) {
                return PRESET_LEVELS[i];
            }
        }
        return null;
    }

    public String getCurrentPreset() {
        return currentPreset;
    }

    /** 获取可用的系统预设名(部分设备支持) */
    public String[] getSystemPresets() {
        if (equalizer == null) return PRESETS_DEFAULT;
        try {
            short n = equalizer.getNumberOfPresets();
            String[] arr = new String[n];
            for (short i = 0; i < n; i++) {
                arr[i] = equalizer.getPresetName(i);
            }
            return arr;
        } catch (Exception e) {
            return PRESETS_DEFAULT;
        }
    }

    // ==================== 设置持久化 ====================

    private void loadSettings() {
        if (context == null) return;
        SharedPreferences sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        enabled = sp.getBoolean("enabled", false);
        currentPreset = sp.getString("preset", "关闭");
        bandCount = sp.getInt("bandCount", 5);

        bandLevels = new short[bandCount];
        for (int i = 0; i < bandCount; i++) {
            bandLevels[i] = (short) sp.getInt("band_" + i, 0);
        }
    }

    private void saveSettings() {
        if (context == null) return;
        SharedPreferences sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        SharedPreferences.Editor ed = sp.edit();
        ed.putBoolean("enabled", enabled);
        ed.putString("preset", currentPreset);
        ed.putInt("bandCount", bandCount);
        if (bandLevels != null) {
            for (int i = 0; i < bandLevels.length; i++) {
                ed.putInt("band_" + i, bandLevels[i]);
            }
        }
        ed.apply();
    }
}
