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
 */
public class LrcParser {

    // 匹配 [01:23.45] 或 [01:23] 格式
    private static final Pattern TIME_PATTERN =
            Pattern.compile("\\[(\\d{1,2}):(\\d{1,2})(?:\\.(\\d{1,3}))?]");

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
        BufferedReader reader = null;
        try {
            // 尝试 UTF-8,失败回退 GBK(国内歌词常见编码)
            reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), "UTF-8"));
            String line;
            while ((line = reader.readLine()) != null) {
                parseLine(line, list);
            }
        } catch (Exception e) {
            // UTF-8 失败,尝试 GBK
            try {
                if (reader != null) reader.close();
                reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), "GBK"));
                String line;
                while ((line = reader.readLine()) != null) {
                    parseLine(line, list);
                }
            } catch (Exception ex) {
                // 放弃
            }
        } finally {
            if (reader != null) {
                try { reader.close(); } catch (Exception e) {}
            }
        }
        Collections.sort(list);
        return list;
    }

    /**
     * 解析单行,一行可能带多个时间标签
     */
    private static void parseLine(String line, List<LrcEntry> list) {
        if (line == null || line.trim().isEmpty()) {
            return;
        }
        Matcher matcher = TIME_PATTERN.matcher(line);
        List<Long> times = new ArrayList<>();
        int lastEnd = 0;
        while (matcher.find()) {
            times.add(parseTime(matcher));
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
        for (String line : lines) {
            parseLine(line, list);
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
     * 根据当前播放位置查找歌词索引
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
