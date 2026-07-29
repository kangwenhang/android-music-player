package com.captiva.musicplayer;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LRC 歌词解析器
 * 解析 [mm:ss.xx]格式的时间标签
 * 支持 [offset:xxx] 全局偏移校正(毫秒,正值提前,负值延后)
 */
public class LrcParser {

    private static final String TAG = "LrcParser";

    // 匹配 [01:23.45] 或 [01:23] 格式
    private static final Pattern TIME_PATTERN =
            Pattern.compile("\\[(\\d{1,2}):(\\d{1,2})(?:\\.(\\d{1,3}))?]");

    // 匹配 [offset:250] 或 [offset:-250] 格式(毫秒)
    private static final Pattern OFFSET_PATTERN =
            Pattern.compile("\\[offset:\\s*([+-]?\\d+)\\s*]", Pattern.CASE_INSENSITIVE);

    /**
     * 根据音乐文件路径查找同名 .lrc 歌词文件并解析
     * @param musicPath 音乐文件路径
     * @return 歌词列表,可能为空
     */
    public static List<LrcEntry> loadLrc(String musicPath) {
        if (musicPath == null || musicPath.isEmpty()) {
            return new ArrayList<>();
        }
        // 尝试同名 .lrc
        int dot = musicPath.lastIndexOf('.');
        String lrcPath;
        if (dot > 0) {
            lrcPath = musicPath.substring(0, dot) + ".lrc";
        } else {
            lrcPath = musicPath + ".lrc";
        }
        File f = new File(lrcPath);
        if (!f.exists()) {
            return new ArrayList<>();
        }
        return parse(f);
    }

    /**
     * 解析 LRC 文件
     */
    public static List<LrcEntry> parse(File file) {
        List<LrcEntry> list = new ArrayList<>();
        List<String> lines = new ArrayList<>();
        BufferedReader reader = null;
        try {
            // 尝试 UTF-8,失败回退 GBK(国内歌词常见编码)
            reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), "UTF-8"));
            String line;
            while ((line = reader.readLine()) != null) {
                lines.add(line);
            }
        } catch (Exception e) {
            // UTF-8 失败,尝试 GBK
            try {
                if (reader != null) reader.close();
                lines.clear();
                reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), "GBK"));
                String line;
                while ((line = reader.readLine()) != null) {
                    lines.add(line);
                }
            } catch (Exception ex) {
                // 放弃
            }
        } finally {
            if (reader != null) {
                try { reader.close(); } catch (Exception e) {}
            }
        }

        // 先提取全局 offset
        long offset = 0;
        for (String line : lines) {
            Matcher om = OFFSET_PATTERN.matcher(line);
            if (om.find()) {
                try {
                    offset += Long.parseLong(om.group(1));
                } catch (NumberFormatException e) {
                    // 忽略
                }
            }
        }

        // 解析歌词行
        for (String line : lines) {
            parseLine(line, list, offset);
        }
        Collections.sort(list);
        return list;
    }

    /**
     * 解析单行,一行可能带多个时间标签
     * @param offset 全局偏移(毫秒,正值提前显示,负值延后)
     *               LRC标准:正值表示歌词需要提前显示,因此从时间戳中减去 offset
     */
    private static void parseLine(String line, List<LrcEntry> list, long offset) {
        if (line == null || line.trim().isEmpty()) {
            return;
        }
        // 跳过 offset 标签行(已单独处理)
        if (OFFSET_PATTERN.matcher(line).find()) {
            return;
        }
        Matcher matcher = TIME_PATTERN.matcher(line);
        List<Long> times = new ArrayList<>();
        int lastEnd = 0;
        while (matcher.find()) {
            // LRC offset: 正值=提前显示,所以减去 offset
            times.add(parseTime(matcher) - offset);
            lastEnd = matcher.end();
        }
        if (times.isEmpty()) {
            return;
        }
        // 歌词文本为最后一个时间标签之后的内容
        String text = line.substring(lastEnd).trim();
        for (long t : times) {
            list.add(new LrcEntry(t, text));
        }
    }

    private static long parseTime(Matcher m) {
        try {
            int min = Integer.parseInt(m.group(1));
            int sec = Integer.parseInt(m.group(2));
            String msStr = m.group(3);
            int ms = 0;
            if (msStr != null) {
                // 补齐到 3 位
                ms = Integer.parseInt(msStr);
                if (msStr.length() == 1) ms *= 100;
                else if (msStr.length() == 2) ms *= 10;
            }
            return min * 60000L + sec * 1000L + ms;
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * 从 LRC 格式文本解析歌词
     * 用于网络歌词(从 Navidrome API 获取的纯文本 LRC)
     * @param lrcText LRC 格式文本,如 "[00:01.23]歌词内容"
     * @return 歌词列表,可能为空
     */
    public static List<LrcEntry> parseLrcText(String lrcText) {
        List<LrcEntry> list = new ArrayList<>();
        if (lrcText == null || lrcText.isEmpty()) {
            return list;
        }

        String[] lines = lrcText.split("\n");

        // 先提取全局 offset
        long offset = 0;
        for (String line : lines) {
            Matcher om = OFFSET_PATTERN.matcher(line);
            if (om.find()) {
                try {
                    offset += Long.parseLong(om.group(1));
                } catch (NumberFormatException e) {
                    // 忽略
                }
            }
        }

        // 解析歌词行
        for (String line : lines) {
            parseLine(line, list, offset);
        }
        Collections.sort(list);
        return list;
    }

    /**
     * 将纯文本歌词(无时间标签)转换为歌词列表
     * 按固定间隔分配时间戳
     * @param plainText 纯文本歌词
     * @param intervalMs 每行间隔(毫秒)
     * @return 歌词列表
     */
    public static List<LrcEntry> parsePlainTextLyrics(String plainText, long intervalMs) {
        List<LrcEntry> list = new ArrayList<>();
        if (plainText == null || plainText.isEmpty()) {
            return list;
        }
        String[] lines = plainText.split("\n");
        for (int i = 0; i < lines.length; i++) {
            String text = lines[i].trim();
            if (!text.isEmpty()) {
                list.add(new LrcEntry(i * intervalMs, text));
            }
        }
        return list;
    }

    /**
     * 将歌词列表转换为 LRC 格式文本
     * 用于缓存歌词到本地文件
     * @param list 歌词列表
     * @return LRC 格式文本,如 "[00:01.23]歌词内容\n..."
     */
    public static String toLrcText(List<LrcEntry> list) {
        if (list == null || list.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (LrcEntry e : list) {
            long ms = e.getTime();
            long min = ms / 60000;
            long sec = (ms % 60000) / 1000;
            long millis = ms % 1000;
            sb.append(String.format("[%02d:%02d.%03d]", min, sec, millis));
            sb.append(e.getText());
            sb.append("\n");
        }
        return sb.toString();
    }

    /**
     * 根据当前播放位置查找歌词索引
     * 严格按照歌词时间标签匹配,不添加额外偏移
     * 返回时间戳 <= position 的最后一行索引
     */
    public static int findLrcIndex(List<LrcEntry> list, long position) {
        if (list == null || list.isEmpty()) {
            return -1;
        }
        int idx = -1;
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).getTime() <= position) {
                idx = i;
            } else {
                break;
            }
        }
        return idx;
    }
}
