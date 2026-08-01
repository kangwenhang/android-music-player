package com.captiva.musicplayer;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.audiofx.Equalizer;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * 均衡器管理器
 * 封装系统 Equalizer(API 9+),提供预设和自定义频段调节
 *
 * 核心特性:
 * 1. 支持在未播放歌曲时调节 —— 使用全局音频会话(sessionId=0)初始化
 * 2. 设置持久化 —— 均衡器参数保存到 SharedPreferences,重启不丢失
 * 3. 自动恢复 —— 播放歌曲时重新绑定 audioSession,自动应用已保存的设置
 * 4. 自定义预设 —— 用户可保存自定义均衡器模式,持久化存储
 * 5. 单曲EQ绑定 —— 每首歌可绑定独立均衡器模式,切歌时自动应用
 */
public class EqualizerManager {

    private static final String TAG = "EqualizerManager";
    private static final String PREFS_NAME = "equalizer_settings";
    private static final String PREFS_CUSTOM = "equalizer_custom_presets";
    private static final String PREFS_SONG_BINDING = "equalizer_song_binding";

    /** 默认预设(含"关闭"),顺序固定 */
    public static final String[] PRESETS_DEFAULT = {"关闭", "流行", "摇滚", "古典", "人声"};

    // 内置预设参数(针对常见 5 段均衡器)
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

    /** 当前应用的单曲EQ模式名(null表示使用全局设置) */
    private String currentSongPreset = null;

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
            // 使用高优先级(1)确保均衡器效果不被其他音频效果覆盖
            equalizer = new Equalizer(1, audioSessionId);
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
            // sessionId=0 表示全局音频输出,使用高优先级
            equalizer = new Equalizer(1, 0);
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
            // 先应用频段设置,再开启均衡器(确保开启时参数已就位)
            if (!"关闭".equals(currentPreset) && !"自定义".equals(currentPreset)) {
                applyPresetInternal(currentPreset);
            } else if (bandLevels != null) {
                for (short i = 0; i < bandCount && i < bandLevels.length; i++) {
                    try {
                        equalizer.setBandLevel(i, bandLevels[i]);
                    } catch (Exception ignored) {}
                }
            }
            // 最后设置开关状态
            equalizer.setEnabled(enabled);
            Log.d(TAG, "applyStoredSettings: enabled=" + enabled
                    + " preset=" + currentPreset + " bands=" + bandCount);
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

    /**
     * 将预设应用到均衡器硬件(不修改全局设置状态,不保存)
     * 用于单曲EQ绑定:应用歌曲绑定的预设,但不覆盖全局设置
     */
    private void applyPresetToHardware(String preset) {
        if (equalizer == null) return;
        short bc = equalizer.getNumberOfBands();
        if ("关闭".equals(preset)) {
            try { equalizer.setEnabled(false); } catch (Exception ignored) {}
            for (short i = 0; i < bc; i++) {
                try { equalizer.setBandLevel(i, (short) 0); } catch (Exception ignored) {}
            }
            return;
        }
        int[] target = getPresetLevels(preset);
        if (target == null) return;
        try { equalizer.setEnabled(true); } catch (Exception ignored) {}
        for (short i = 0; i < bc && i < target.length; i++) {
            try { equalizer.setBandLevel(i, (short) target[i]); } catch (Exception ignored) {}
        }
    }

    /** 应用预设 */
    public void applyPreset(String preset) {
        // 手动切换预设时清除单曲EQ状态(用户主动覆盖了歌曲绑定)
        currentSongPreset = null;
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

    /** 获取预设对应的频段参数(内置+自定义) */
    private int[] getPresetLevels(String preset) {
        // 先查内置预设
        for (int i = 0; i < PRESET_NAMES.length; i++) {
            if (PRESET_NAMES[i].equals(preset)) {
                return PRESET_LEVELS[i];
            }
        }
        // 再查自定义预设
        return getCustomPresetLevels(preset);
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

    // ==================== 自定义预设 ====================

    /**
     * 保存自定义预设
     * @param name 预设名称
     * @param levels 频段等级数组(mB)
     * @return true 保存成功,false 名称已存在或为空
     */
    public boolean saveCustomPreset(String name, short[] levels) {
        if (name == null || name.trim().isEmpty()) return false;
        name = name.trim();
        // 不允许与内置预设重名
        for (String builtin : PRESETS_DEFAULT) {
            if (builtin.equals(name)) return false;
        }
        if (context == null) return false;
        try {
            SharedPreferences sp = context.getSharedPreferences(PREFS_CUSTOM, Context.MODE_PRIVATE);
            String existing = sp.getString("presets", "{}");
            JSONObject root = new JSONObject(existing);
            // 检查是否已存在
            if (root.has(name)) return false;
            JSONArray arr = new JSONArray();
            if (levels != null) {
                for (short v : levels) {
                    arr.put((int) v);
                }
            }
            root.put(name, arr);
            sp.edit().putString("presets", root.toString()).apply();
            Log.d(TAG, "保存自定义预设: " + name);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "saveCustomPreset failed", e);
            return false;
        }
    }

    /** 覆盖已存在的自定义预设(用于编辑) */
    public boolean overwriteCustomPreset(String name, short[] levels) {
        if (name == null || name.trim().isEmpty()) return false;
        name = name.trim();
        for (String builtin : PRESETS_DEFAULT) {
            if (builtin.equals(name)) return false;
        }
        if (context == null) return false;
        try {
            SharedPreferences sp = context.getSharedPreferences(PREFS_CUSTOM, Context.MODE_PRIVATE);
            String existing = sp.getString("presets", "{}");
            JSONObject root = new JSONObject(existing);
            JSONArray arr = new JSONArray();
            if (levels != null) {
                for (short v : levels) {
                    arr.put((int) v);
                }
            }
            root.put(name, arr);
            sp.edit().putString("presets", root.toString()).apply();
            Log.d(TAG, "覆盖自定义预设: " + name);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "overwriteCustomPreset failed", e);
            return false;
        }
    }

    /** 删除自定义预设 */
    public boolean deleteCustomPreset(String name) {
        if (name == null || context == null) return false;
        try {
            SharedPreferences sp = context.getSharedPreferences(PREFS_CUSTOM, Context.MODE_PRIVATE);
            String existing = sp.getString("presets", "{}");
            JSONObject root = new JSONObject(existing);
            if (!root.has(name)) return false;
            root.remove(name);
            sp.edit().putString("presets", root.toString()).apply();
            Log.d(TAG, "删除自定义预设: " + name);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "deleteCustomPreset failed", e);
            return false;
        }
    }

    /** 获取所有自定义预设名 */
    public List<String> getCustomPresetNames() {
        List<String> names = new ArrayList<>();
        if (context == null) return names;
        try {
            SharedPreferences sp = context.getSharedPreferences(PREFS_CUSTOM, Context.MODE_PRIVATE);
            String existing = sp.getString("presets", "{}");
            JSONObject root = new JSONObject(existing);
            Iterator<String> keys = root.keys();
            while (keys.hasNext()) {
                names.add(keys.next());
            }
        } catch (Exception e) {
            Log.w(TAG, "getCustomPresetNames failed", e);
        }
        return names;
    }

    /** 获取自定义预设的频段参数 */
    public int[] getCustomPresetLevels(String name) {
        if (name == null || context == null) return null;
        try {
            SharedPreferences sp = context.getSharedPreferences(PREFS_CUSTOM, Context.MODE_PRIVATE);
            String existing = sp.getString("presets", "{}");
            JSONObject root = new JSONObject(existing);
            if (!root.has(name)) return null;
            JSONArray arr = root.getJSONArray(name);
            int[] levels = new int[arr.length()];
            for (int i = 0; i < arr.length(); i++) {
                levels[i] = arr.getInt(i);
            }
            return levels;
        } catch (Exception e) {
            Log.w(TAG, "getCustomPresetLevels failed", e);
            return null;
        }
    }

    /**
     * 获取所有预设名(内置 + 自定义),用于显示
     * 顺序:关闭, 流行, 摇滚, 古典, 人声, [自定义1, 自定义2, ...]
     */
    public List<String> getAllPresetNames() {
        List<String> all = new ArrayList<>();
        for (String p : PRESETS_DEFAULT) {
            all.add(p);
        }
        all.addAll(getCustomPresetNames());
        return all;
    }

    /** 判断是否为自定义预设 */
    public boolean isCustomPreset(String name) {
        if (name == null) return false;
        for (String builtin : PRESETS_DEFAULT) {
            if (builtin.equals(name)) return false;
        }
        return true;
    }

    // ==================== 单曲EQ绑定 ====================

    /**
     * 生成歌曲唯一标识(使用 MusicBean 缓存,避免重复文件系统 I/O)
     */
    public static String getSongKey(MusicBean bean) {
        if (bean == null) return "";
        return bean.getCachedKey();
    }

    /**
     * 给歌曲绑定均衡器预设
     * @param bean 歌曲对象
     * @param presetName 预设名(内置或自定义),null 表示取消绑定
     */
    public void bindSongEq(MusicBean bean, String presetName) {
        if (context == null || bean == null) return;
        String key = getSongKey(bean);
        if (key.isEmpty()) return;
        SharedPreferences sp = context.getSharedPreferences(PREFS_SONG_BINDING, Context.MODE_PRIVATE);
        SharedPreferences.Editor ed = sp.edit();
        if (presetName == null || presetName.isEmpty()) {
            ed.remove(key);
        } else {
            ed.putString(key, presetName);
        }
        ed.apply();
        Log.d(TAG, "绑定歌曲EQ: " + key + " -> " + presetName);
    }

    /** 获取歌曲绑定的均衡器预设名(未绑定返回null) */
    public String getSongEqPreset(MusicBean bean) {
        if (context == null || bean == null) return null;
        String key = getSongKey(bean);
        if (key.isEmpty()) return null;
        SharedPreferences sp = context.getSharedPreferences(PREFS_SONG_BINDING, Context.MODE_PRIVATE);
        return sp.getString(key, null);
    }

    /** 取消歌曲的均衡器绑定 */
    public void unbindSongEq(MusicBean bean) {
        bindSongEq(bean, null);
    }

    /**
     * 应用歌曲绑定的均衡器(切歌时调用)
     * 如果歌曲有绑定预设,应用该预设(不覆盖全局设置);否则恢复全局设置
     * @param bean 当前播放歌曲
     */
    public void applySongEq(MusicBean bean) {
        if (bean == null) return;
        String songPreset = getSongEqPreset(bean);
        if (songPreset != null && !songPreset.isEmpty()) {
            // 有单曲绑定,将绑定的预设应用到均衡器硬件(不修改全局设置)
            currentSongPreset = songPreset;
            if (equalizer != null) {
                applyPresetToHardware(songPreset);
            }
            Log.d(TAG, "应用单曲EQ: " + songPreset);
        } else {
            // 无单曲绑定,恢复全局设置
            currentSongPreset = null;
            loadSettings();
            if (equalizer != null) {
                applyStoredSettings();
            }
            Log.d(TAG, "无单曲EQ绑定,恢复全局设置: " + currentPreset);
        }
    }

    /** 获取当前生效的EQ模式名(优先单曲绑定) */
    public String getActivePreset() {
        if (currentSongPreset != null && !currentSongPreset.isEmpty()) {
            return currentSongPreset;
        }
        return currentPreset;
    }

    /** 清除当前单曲EQ状态(切歌前调用) */
    public void clearSongEqState() {
        currentSongPreset = null;
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
