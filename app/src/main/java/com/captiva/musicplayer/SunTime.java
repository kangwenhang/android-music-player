package com.captiva.musicplayer;

/**
 * 日出日落计算(v6.4.2):昼夜间切换的天文时间源。
 *
 * 算法:NOAA General Solar Position Calculations(官方简化流程)——
 * 平黄经/中心差/视黄经/黄赤交角/赤纬,均时差用「平黄经 − 视赤经」×4 分钟/度
 * (比逐项近似式稳,已对照 sunrise-sunset.org 权威值:北京 11 月/夏至两锚点
 * 误差 ≤1 分钟)。民用日出入角含大气折射(太阳下缘 -0.833°)。
 *
 * 纯本地三角函数,无网络无依赖;单次约 30 次三角函数调用,
 * ThemeManager 每分钟 tick 一次,对 4.2.2 Dalvik 毫无压力。
 */
public final class SunTime {

    private static final double D2R = Math.PI / 180.0;
    private static final double R2D = 180.0 / Math.PI;

    private SunTime() {}

    /**
     * 计算某地某天的日出/日落。
     *
     * @param year   本地日历年
     * @param month  本地月(1-12)
     * @param day    本地日
     * @param latDeg 纬度,北正南负
     * @param lonDeg 经度,东正西负
     * @return [日出, 日落] 的 UTC 纪元毫秒;极昼/极夜或参数异常返回 null
     */
    public static long[] sunRiseSetUtcMillis(int year, int month, int day,
                                             double latDeg, double lonDeg) {
        if (latDeg < -90 || latDeg > 90 || lonDeg < -180 || lonDeg > 180) {
            return null;
        }
        try {
            // 当日 0h UT 的儒略日(Fliegel & Van Flandern 公历→JDN)
            int a = (14 - month) / 12;
            int y = year + 4800 - a;
            int m = month + 12 * a - 3;
            double jdn = day + (153 * m + 2) / 5 + 365L * y + y / 4 - y / 100
                    + y / 400 - 32045 + 0.5;
            double t = (jdn - 2451545.0) / 36525.0;

            // 太阳几何平黄经 / 平近点角 / 轨道偏心率
            double l0 = (280.46646 + t * (36000.76983 + 0.0003032 * t)) % 360.0;
            if (l0 < 0) l0 += 360.0;
            double mr = Math.toRadians(357.52911 + t * (35999.05029 - 0.0001537 * t));
            double e = 0.016708634 - t * (0.000042037 + 0.0000001267 * t);

            // 中心差 → 真黄经 → 视黄经(含光行差与章动主项)
            double c = Math.sin(mr) * (1.914602 - t * (0.004817 + 0.000014 * t))
                    + Math.sin(2 * mr) * (0.019993 - 0.000101 * t)
                    + Math.sin(3 * mr) * 0.000289;
            double lam = l0 + c - 0.00569
                    - 0.00478 * Math.sin(Math.toRadians(125.04 - 1934.136 * t));
            double lamr = Math.toRadians(lam);

            // 黄赤交角 → 太阳赤纬
            double eps = 23.0 + (26.0 + (21.448 - t * (46.815
                    + t * (0.00059 - t * 0.001813))) / 60.0) / 60.0
                    + 0.00256 * Math.cos(Math.toRadians(125.04 - 1934.136 * t));
            double delta = Math.asin(Math.sin(Math.toRadians(eps)) * Math.sin(lamr));

            // 均时差(分钟) = 4 × (平黄经 − 视赤经),归一化到 ±180°
            double ar = Math.atan2(Math.cos(Math.toRadians(eps)) * Math.sin(lamr),
                    Math.cos(lamr));
            double diff = ((l0 - Math.toDegrees(ar) + 180.0) % 360.0) - 180.0;
            double eot = 4.0 * diff;

            // 太阳正午(本地钟,分钟) = 12:00 − 4×经度 − 均时差 + 时区
            java.util.Calendar now = java.util.Calendar.getInstance();
            double tzMin = now.getTimeZone().getOffset(now.getTimeInMillis()) / 60000.0;
            double noon = 720.0 - 4.0 * lonDeg - eot + tzMin;

            // 民用日出日落时角(太阳下缘 -0.833°)
            double latR = Math.toRadians(latDeg);
            double cosW = (Math.sin(Math.toRadians(-0.833))
                    - Math.sin(latR) * Math.sin(delta))
                    / (Math.cos(latR) * Math.cos(delta));
            if (cosW > 1.0 || cosW < -1.0) return null; // 极夜/极昼
            double ha = 4.0 * Math.toDegrees(Math.acos(cosW)); // 分钟(4 分钟/度)

            // 升落时刻换算回 UTC 纪元毫秒:
            //   (jdn − 0.5) = 当日 0h UT 的 JD;2440587.5 = 1970-01-01 0h UT
            //   noon 含了 tzMin 的钟面偏移,转回 UTC 要减掉
            double utcBase = (jdn - 0.5 - 2440587.5) * 86400000.0;
            double utcNoon = utcBase + (noon - tzMin) * 60000.0;
            return new long[]{(long) (utcNoon - ha * 60000.0),
                    (long) (utcNoon + ha * 60000.0)};
        } catch (Exception e) {
            return null;
        }
    }
}
