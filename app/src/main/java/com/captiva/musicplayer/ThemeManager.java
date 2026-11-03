package com.captiva.musicplayer;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.appcompat.app.AppCompatDelegate;

/**
 * 主题管理器(v6.4):白天/夜间自动切换
 *
 * 三种模式:
 * - MODE_AUTO  自动(默认):7:00-19:00 用白天浅色,其余时间用夜间深色
 * - MODE_DAY   强制白天
 * - MODE_NIGHT 强制夜间
 *
 * 实现方式:Theme.AppCompat.DayNight 主题 + values-night 资源限定符,
 * 通过 AppCompatDelegate.setDefaultNightMode 全局生效。
 * 模式切换时 appcompat 会自动 recreate 所有存活的 Activity。
 *
 * 自动模式的定时检测由 MainActivity 的分钟轮询驱动(ThemeManager.checkAutoTick),
 * 其他 Activity 在 onResume 时各调一次 apply() 即可跟上切换。
 */
public final class ThemeManager {

    private static final String TAG = "ThemeManager";

    public static final int MODE_AUTO = 0;
    public static final int MODE_DAY = 1;
    public static final int MODE_NIGHT = 2;

    /** 自动模式的昼夜分界:>=7 点且 <19 点视为白天 */
    private static final int AUTO_DAY_START_HOUR = 7;
    private static final int AUTO_DAY_END_HOUR = 19;

    private static final String PREFS = "theme_prefs";
    private static final String KEY_MODE = "ui_theme_mode";
    /** 日出日落切换的坐标(定位一次后持久化) */
    private static final String KEY_LAT = "sun_lat";
    private static final String KEY_LON = "sun_lon";

    private ThemeManager() {}

    /** 读取用户设置的主题模式(默认自动) */
    public static int getMode(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return sp.getInt(KEY_MODE, MODE_AUTO);
    }

    /** 保存主题模式并立即应用到全局 */
    public static void setMode(Context ctx, int mode) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putInt(KEY_MODE, mode).apply();
        apply(ctx);
    }

    /** 中国范围粗界(纬 18~53.6 / 经 73.5~135.1):超出视为定位垃圾,不采纳 */
    private static final double LAT_MIN = 18.0, LAT_MAX = 53.6;
    private static final double LON_MIN = 73.5, LON_MAX = 135.1;

    /**
     * 读取定位到的坐标 {纬度, 经度};未定位过或坐标不合理(如无 GMS 车机的
     * NETWORK 服务商返回的境外预置垃圾缓存)返回 null → 自动模式回退固定时间。
     * 范围校验放在读取侧:历史版本存进去的脏坐标在这里自动失效,无需迁移。
     */
    public static float[] getCoords(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (!sp.contains(KEY_LAT) || !sp.contains(KEY_LON)) return null;
        float lat = sp.getFloat(KEY_LAT, 999f);
        float lon = sp.getFloat(KEY_LON, 999f);
        if (lat < LAT_MIN || lat > LAT_MAX || lon < LON_MIN || lon > LON_MAX) return null;
        return new float[]{lat, lon};
    }

    /** 保存定位坐标(日出日落切换立即生效) */
    public static void setCoords(Context ctx, double lat, double lon) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putFloat(KEY_LAT, (float) lat).putFloat(KEY_LON, (float) lon).apply();
    }

    /** 按当前用户模式把 AppCompat 的夜间档位设置好(进程启动时也要调) */
    public static void apply(Context ctx) {
        int mode = getMode(ctx);
        int nightMode;
        switch (mode) {
            case MODE_DAY:
                nightMode = AppCompatDelegate.MODE_NIGHT_NO;
                break;
            case MODE_NIGHT:
                nightMode = AppCompatDelegate.MODE_NIGHT_YES;
                break;
            case MODE_AUTO:
            default:
                nightMode = isDayNow(ctx)
                        ? AppCompatDelegate.MODE_NIGHT_NO
                        : AppCompatDelegate.MODE_NIGHT_YES;
                break;
        }
        if (AppCompatDelegate.getDefaultNightMode() != nightMode) {
            AppCompatDelegate.setDefaultNightMode(nightMode);
            android.util.Log.i(TAG, "夜间档位切换 -> "
                    + (nightMode == AppCompatDelegate.MODE_NIGHT_YES ? "夜间" : "白天")
                    + " (模式=" + modeName(mode) + ")");
        }
    }

    /** 当前时刻是否属于白天 */
    public static boolean isDayNow(Context ctx) {
        java.util.Calendar now = java.util.Calendar.getInstance();
        // 优先按日出日落(已定位过):天文时间随季节自适应,冬夏切换点自然偏移
        float[] ll = getCoords(ctx);
        if (ll != null) {
            long[] rs = SunTime.sunRiseSetUtcMillis(
                    now.get(java.util.Calendar.YEAR),
                    now.get(java.util.Calendar.MONTH) + 1,
                    now.get(java.util.Calendar.DAY_OF_MONTH), ll[0], ll[1]);
            if (rs != null) {
                long t = now.getTimeInMillis();
                return t >= rs[0] && t < rs[1];
            }
            // 极昼/极夜(国内实际碰不到):落到下面的固定时间兜底
        }
        int h = now.get(java.util.Calendar.HOUR_OF_DAY);
        return h >= AUTO_DAY_START_HOUR && h < AUTO_DAY_END_HOUR;
    }

    /** 自动模式专用:每分钟调一次,时间越过昼夜分界时自动切换 */
    public static void checkAutoTick(Context ctx) {
        if (getMode(ctx) == MODE_AUTO) {
            apply(ctx);
        }
    }

    /** 模式的中文显示名(设置界面用) */
    public static String modeName(int mode) {
        switch (mode) {
            case MODE_DAY:
                return "白天";
            case MODE_NIGHT:
                return "夜间";
            case MODE_AUTO:
            default:
                return "自动";
        }
    }
}
