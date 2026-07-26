package com.captiva.musicplayer;

import android.media.audiofx.Equalizer;
import android.util.Log;

/**
 * 均衡器管理器
 * 封装系统 Equalizer(API 9+),提供预设和自定义频段调节
 * 注意:Equalizer 需绑定到 MediaPlayer 的 audioSessionId,在 prepare 后才能创建
 */
public class EqualizerManager {

    private static final String TAG = "EqualizerManager";

    public static final String[] PRESETS_DEFAULT = {"关闭", "流行", "摇滚", "古典", "人声"};

    private Equalizer equalizer;
    private boolean enabled = false;
    private String currentPreset = "关闭";
    private short[] bandLevels; // 自定义频段等级(mB)

    /**
     * 初始化均衡器,绑定到指定音频会话
     */
    public void init(int audioSessionId) {
        release();
        try {
            equalizer = new Equalizer(0, audioSessionId);
            bandLevels = new short[equalizer.getNumberOfBands()];
            // 默认归零
            short min = equalizer.getBandLevelRange()[0];
            short max = equalizer.getBandLevelRange()[1];
            for (short i = 0; i < bandLevels.length; i++) {
                bandLevels[i] = 0;
            }
        } catch (Exception e) {
            Log.w(TAG, "Equalizer init failed", e);
            equalizer = null;
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
        enabled = false;
    }

    public boolean isAvailable() {
        return equalizer != null;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** 获取频段数量 */
    public short getBandCount() {
        return equalizer != null ? equalizer.getNumberOfBands() : 0;
    }

    /** 获取频段中心频率(Hz) */
    public int getCenterFreq(short band) {
        if (equalizer == null) return 0;
        try {
            return equalizer.getCenterFreq(band);
        } catch (Exception e) {
            return 0;
        }
    }

    /** 获取频段当前等级(mB) */
    public short getBandLevel(short band) {
        if (equalizer == null) return 0;
        try {
            return equalizer.getBandLevel(band);
        } catch (Exception e) {
            return 0;
        }
    }

    /** 获取等级范围 [min, max](mB) */
    public short[] getBandLevelRange() {
        if (equalizer == null) return new short[]{0, 0};
        try {
            return equalizer.getBandLevelRange();
        } catch (Exception e) {
            return new short[]{0, 0};
        }
    }

    /** 设置某个频段等级 */
    public void setBandLevel(short band, short level) {
        if (equalizer == null) return;
        try {
            equalizer.setBandLevel(band, level);
            if (band < bandLevels.length) {
                bandLevels[band] = level;
            }
        } catch (Exception e) {
            Log.w(TAG, "setBandLevel failed", e);
        }
    }

    /** 开关均衡器 */
    public void setEnabled(boolean on) {
        if (equalizer == null) return;
        try {
            equalizer.setEnabled(on);
            enabled = on;
        } catch (Exception e) {
            Log.w(TAG, "setEnabled failed", e);
        }
    }

    /** 应用预设(简易实现,用固定参数) */
    public void applyPreset(String preset) {
        currentPreset = preset;
        if (equalizer == null) return;
        short bandCount = equalizer.getNumberOfBands();
        if ("关闭".equals(preset)) {
            setEnabled(false);
            for (short i = 0; i < bandCount; i++) {
                setBandLevel(i, (short) 0);
            }
            return;
        }
        setEnabled(true);
        // 简易预设参数,针对常见 5 段均衡器
        String[] names = {"流行", "摇滚", "古典", "人声"};
        int[][] levels = {
                {0, 200, 400, 200, 0},
                {400, 200, -100, 200, 400},
                {300, 0, 0, 0, 300},
                {-200, 0, 400, 200, 0}
        };
        int[] target = null;
        for (int i = 0; i < names.length; i++) {
            if (names[i].equals(preset)) {
                target = levels[i];
                break;
            }
        }
        if (target == null) return;
        for (short i = 0; i < bandCount && i < target.length; i++) {
            setBandLevel(i, (short) target[i]);
        }
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
}
