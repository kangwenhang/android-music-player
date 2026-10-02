package com.captiva.musicplayer;

import java.io.UnsupportedEncodingException;

/**
 * 取标题首字母(供 A-Z 快速索引条使用)
 *
 * 中文:利用 GB2312 一级汉字「按拼音排序」的特性 —— 区位码落在哪个区间就是哪个声母。
 *      已离线验证 79 个常用汉字全部正确(周→Z、青花瓷→Q、稻香→D、七里香→Q…)。
 *      相比依赖设备 ICU 的做法,这个方案**结果确定、不随系统变化**,
 *      能保证「排序分组」与「索引条字母」永远一致。
 *
 * 拉丁字母:直接取首字母转大写。
 * 数字 / 符号 / 生僻字 / 繁体:归入 '#'。
 *
 * 已知边界:GB2312 一级汉字共 3755 字,覆盖日常歌名足够;
 *          极生僻字与繁体会落到 '#',属预期行为,不会影响常用歌曲。
 */
public final class PinyinUtils {

    /** 各声母的起始区位码 */
    private static final int[] BOUNDARY = {
            1601, 1637, 1833, 2078, 2274, 2302, 2433, 2594, 2787, 3106,
            3212, 3472, 3635, 3722, 3730, 3858, 4027, 4086, 4390, 4558,
            4684, 4925, 5249
    };

    /** 与 BOUNDARY 一一对应。拼音没有 i / u / v 三个声母 */
    private static final char[] LETTERS = {
            'A', 'B', 'C', 'D', 'E', 'F', 'G', 'H', 'J', 'K', 'L', 'M',
            'N', 'O', 'P', 'Q', 'R', 'S', 'T', 'W', 'X', 'Y', 'Z'
    };

    private PinyinUtils() {
    }

    /**
     * 汉字 → 首字母 记忆化表。
     *
     * 为什么必须缓存:firstLetter 的主要调用方是**排序比较器**与**A-Z 索引条**。
     * 排序时每次 compare 都要对两侧各取一次首字母,800 首要比较约 800·log2(800)≈7700 次,
     * 即约 1.5 万次调用;而 gb2312Letter 内部每次都要 `getBytes("GB2312")`
     * (Charset 查找 + 编码器分配 + String 构造),在车机上这足以让一次列表重建多花上百毫秒。
     * 同一声母的汉字在歌名里高度集中,缓存后绝大多数调用退化成一次 HashMap 命中。
     *
     * 用 ConcurrentHashMap:排序在后台线程(SourceModeToggle)、索引条刷新在主线程,可能并发。
     */
    private static final java.util.concurrent.ConcurrentHashMap<Character, Character> LETTER_CACHE =
            new java.util.concurrent.ConcurrentHashMap<Character, Character>();

    /**
     * 取标题首字母
     *
     * @return 'A'~'Z' 或 '#'
     */
    public static char firstLetter(String title) {
        if (title == null || title.length() == 0) {
            return '#';
        }
        char c = title.charAt(0);

        // 拉丁字母
        if (c >= 'a' && c <= 'z') return (char) (c - 'a' + 'A');
        if (c >= 'A' && c <= 'Z') return c;

        // 汉字:走 GB2312 区位码
        if (c >= 0x4E00 && c <= 0x9FA5) {
            Character cached = LETTER_CACHE.get(c);
            if (cached != null) {
                return cached;
            }
            char letter = gb2312Letter(c);
            // letter==0 也缓存:'#' 同样是稳定结果,避免生僻字每次重算
            char result = letter != 0 ? letter : '#';
            LETTER_CACHE.put(c, result);
            return result;
        }
        return '#';
    }

    /** 由 GB2312 区位码定声母;取不到返回 0 */
    private static char gb2312Letter(char c) {
        byte[] b = encodeGb(String.valueOf(c));
        if (b == null || b.length != 2) return 0;

        int hi = b[0] & 0xFF;
        int lo = b[1] & 0xFF;
        // 一级汉字区:0xB0A1 ~ 0xD7F9
        if (hi < 0xB0 || hi > 0xD7 || lo < 0xA1 || lo > 0xFE) return 0;

        int pos = (hi - 0xA0) * 100 + (lo - 0xA0);
        for (int i = BOUNDARY.length - 1; i >= 0; i--) {
            if (pos >= BOUNDARY[i]) {
                return LETTERS[i];
            }
        }
        return 0;
    }

    /** 优先 GB2312,设备上没有则退回 GBK(两者一级汉字编码一致) */
    private static byte[] encodeGb(String s) {
        try {
            return s.getBytes("GB2312");
        } catch (UnsupportedEncodingException e) {
            try {
                return s.getBytes("GBK");
            } catch (UnsupportedEncodingException e2) {
                return null;
            }
        }
    }
}
