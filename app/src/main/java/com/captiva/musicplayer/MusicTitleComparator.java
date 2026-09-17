package com.captiva.musicplayer;

import java.text.Collator;
import java.util.Comparator;
import java.util.Locale;

/**
 * 歌曲标题排序器(按字母 / 拼音顺序)
 *
 * 为什么不用 String.compareToIgnoreCase:
 *   它按 Unicode 码点比较,拉丁字母没问题,但**中文是按编码排的,不是按拼音**,
 *   所以中文歌名看起来完全是乱序(这正是"歌曲没有按字母顺序排列"的根因)。
 *
 * 改用 ICU 的中文 collation(zh_CN):
 *   - 中文:按拼音 A→Z 排序(啊/阿 在 A,波 在 B,张 在 Z)
 *   - 拉丁字母:按字母序;ICU 先比基础字母再比大小写,所以 "apple" 仍排在 "Banana" 前
 *   - 数字/符号:排在字母之前(与系统通讯录一致)
 *
 * 强度用默认(TERTIARY):保留声调/大小写的次级差异,保证任意两条记录都有确定的先后顺序,
 * 不会因为"同拼音不同声调"被判相等而导致顺序不稳定。
 *
 * 用法:
 *   Collections.sort(list, MusicTitleComparator.INSTANCE);
 */
public final class MusicTitleComparator implements Comparator<MusicBean> {

    /** 无状态,全局复用一个实例 */
    public static final Comparator<MusicBean> INSTANCE = new MusicTitleComparator();

    private static final Collator COLLATOR = createCollator();

    private MusicTitleComparator() {
    }

    private static Collator createCollator() {
        try {
            // zh_CN 的 CLDR 默认排序规则就是拼音
            Collator c = Collator.getInstance(Locale.CHINA);
            return c != null ? c : Collator.getInstance();
        } catch (Throwable t) {
            // 极端情况下(ICU 数据缺失)退回原行为,不至于崩
            return null;
        }
    }

    @Override
    public int compare(MusicBean a, MusicBean b) {
        String ta = a == null ? null : a.getTitle();
        String tb = b == null ? null : b.getTitle();

        // 空标题统一排到最后,避免 null 参与比较
        boolean emptyA = ta == null || ta.length() == 0;
        boolean emptyB = tb == null || tb.length() == 0;
        if (emptyA && emptyB) return 0;
        if (emptyA) return 1;
        if (emptyB) return -1;

        // 先按首字母分组(A~Z,'#' 排最后)。
        // 这里刻意用 PinyinUtils 而不是 Collator:索引条用的是同一套算法,
        // 因此「列表分组」与「索引条字母」必然一致,且不受设备 ICU 中文排序实现影响。
        char la = PinyinUtils.firstLetter(ta);
        char lb = PinyinUtils.firstLetter(tb);
        if (la != lb) {
            if (la == '#') return 1;
            if (lb == '#') return -1;
            return la - lb;
        }

        // 同字母组内再用 Collator 细分(拉丁大小写、同声母汉字之间)
        if (COLLATOR != null) {
            return COLLATOR.compare(ta, tb);
        }
        // 兜底:与改动前行为一致
        return ta.compareToIgnoreCase(tb);
    }
}
