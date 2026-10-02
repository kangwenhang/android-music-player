package com.captiva.musicplayer;

import android.media.MediaMetadataRetriever;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;

/**
 * 从音乐文件内嵌标签提取歌词
 * 支持 ID3 USLT/SYLT 标签(大多数音乐文件内嵌歌词的存储方式)
 * 兼容 Android 4.0(API 10+)
 *
 * 【为什么要自己解析 ID3】
 * 车机(安卓 4.2.2)的 MediaMetadataRetriever 对 METADATA_KEY_LYRICS 支持缺失,
 * 实测:文件里明明有 USLT 歌词,extractMetadata(15) 却返回 null。
 * 而本地歌曲没有 streamId,歌词缓存与网络兜底都用不上,结果就是"文件里有词、车机不显示"。
 * 因此这里保留系统 API 作为首选(快、且某些 ROM 能用),系统读不到时**自己解析 ID3v2 的
 * USLT 帧**兜底:只读标签区(通常几 KB~几十 KB),在歌词加载的后台线程上执行,不碰主线程。
 *
 * 覆盖范围:ID3v2.2 / 2.3 / 2.4 的 USLT 帧,编码 ISO-8859-1 / UTF-16(带BOM) /
 * UTF-16BE / UTF-8,并处理反同步(unsynchronisation)字节。
 * 不覆盖:ID3v1(本身不含歌词)、FLAC/MP4 的歌词标签(本项目本地歌曲以 MP3 为主)。
 */
public class EmbeddedLyricsExtractor {

    private static final String TAG = "EmbeddedLyrics";

    /** 标签大小上限(8MB):防止头部被误判时申请超大内存 */
    private static final int MAX_TAG_BYTES = 8 * 1024 * 1024;

    /**
     * 从音乐文件提取内嵌歌词
     * @param filePath 音乐文件路径
     * @return 歌词文本(LRC 格式或纯文本),null 表示无歌词
     */
    public static String extract(String filePath) {
        if (filePath == null || filePath.isEmpty()) {
            return null;
        }

        // 1) 先用系统 API(快);车机上多数返回 null
        String bySystem = extractViaRetriever(filePath);
        if (bySystem != null && !bySystem.trim().isEmpty()) {
            return bySystem;
        }

        // 2) 系统读不到 → 自己解析 ID3v2 USLT 兜底
        String byOwn = extractUslt(filePath);
        if (byOwn != null && !byOwn.trim().isEmpty()) {
            Log.d(TAG, "系统未返回歌词,自解析 ID3 USLT 成功: " + byOwn.length() + " 字符");
            return byOwn;
        }
        return null;
    }

    /** 通过 MediaMetadataRetriever 取歌词(原实现,原样保留) */
    private static String extractViaRetriever(String filePath) {
        MediaMetadataRetriever retriever = null;
        try {
            retriever = new MediaMetadataRetriever();
            retriever.setDataSource(filePath);

            String lyrics = null;
            try {
                // MediaMetadataRetriever.METADATA_KEY_LYRICS = 15
                lyrics = retriever.extractMetadata(15);
            } catch (Exception e) {
                Log.w(TAG, "extractMetadata LYRICS failed", e);
            }

            // 部分设备歌词存在 DESCRIPTION 字段
            if (lyrics == null || lyrics.isEmpty()) {
                try {
                    // METADATA_KEY_DESCRIPTION = 13
                    String desc = retriever.extractMetadata(13);
                    if (desc != null && desc.contains("[") && desc.contains(":") && desc.contains("]")) {
                        lyrics = desc;
                    }
                } catch (Exception e) {
                    // 忽略
                }
            }

            if (lyrics != null && !lyrics.trim().isEmpty()) {
                Log.d(TAG, "成功提取内嵌歌词: " + lyrics.length() + " 字符");
                return lyrics;
            }
            return null;
        } catch (IllegalArgumentException e) {
            Log.w(TAG, "setDataSource failed", e);
            return null;
        } catch (Exception e) {
            Log.w(TAG, "提取内嵌歌词失败", e);
            return null;
        } finally {
            if (retriever != null) {
                try {
                    retriever.release();
                } catch (Exception ignored) {
                }
            }
        }
    }

    /**
     * 自己解析 ID3v2 标签里的 USLT 帧。
     * 只读标签区:ID3 头里就带标签总长度,不需要读整个音频文件。
     */
    private static String extractUslt(String filePath) {
        FileInputStream fis = null;
        BufferedInputStream in = null;
        try {
            File f = new File(filePath);
            if (!f.exists() || f.length() < 10) {
                return null;
            }
            fis = new FileInputStream(f);
            in = new BufferedInputStream(fis, 64 * 1024);

            // --- ID3v2 头(10 字节) ---
            byte[] header = new byte[10];
            if (readFully(in, header, 10) < 10) {
                return null;
            }
            if (!(header[0] == 'I' && header[1] == 'D' && header[2] == '3')) {
                return null;   // 没有 ID3v2 标签
            }
            int major = header[3] & 0xFF;
            boolean unsyncTag = (header[5] & 0x80) != 0;      // v2.3/2.4 标签级反同步
            boolean hasExtended = (header[5] & 0x40) != 0;    // v2.3/2.4 扩展头
            int tagSize = synchsafeInt(header[6], header[7], header[8], header[9]);
            if (tagSize <= 0 || tagSize > MAX_TAG_BYTES) {
                return null;
            }

            byte[] tag = new byte[tagSize];
            if (readFully(in, tag, tagSize) < tagSize) {
                return null;
            }

            int pos = 0;
            // --- 扩展头:整块跳过 ---
            if (hasExtended && major >= 3) {
                if (tag.length < 4) {
                    return null;
                }
                int extSize = (major == 4)
                        ? synchsafeInt(tag[0], tag[1], tag[2], tag[3])
                        : bigEndianInt(tag[0], tag[1], tag[2], tag[3]);
                pos = 4 + extSize;
                if (pos < 0 || pos > tag.length) {
                    return null;
                }
            }

            // --- 逐帧扫描 ---
            while (pos + 6 <= tag.length) {
                String id;
                int size;
                int bodyStart;
                boolean frameUnsync = false;

                if (major == 2) {
                    // ID3v2.2:3 字节 ID + 3 字节长度(无 flags)
                    id = new String(tag, pos, 3, "ISO-8859-1");
                    size = bigEndianInt3(tag[pos + 3], tag[pos + 4], tag[pos + 5]);
                    bodyStart = pos + 6;
                } else {
                    // ID3v2.3 / 2.4:4 字节 ID + 4 字节长度 + 2 字节 flags
                    if (pos + 10 > tag.length) {
                        break;
                    }
                    id = new String(tag, pos, 4, "ISO-8859-1");
                    size = (major == 4)
                            ? synchsafeInt(tag[pos + 4], tag[pos + 5], tag[pos + 6], tag[pos + 7])
                            : bigEndianInt(tag[pos + 4], tag[pos + 5], tag[pos + 6], tag[pos + 7]);
                    frameUnsync = major == 4 && (tag[pos + 9] & 0x02) != 0;  // v2.4 帧级反同步
                    bodyStart = pos + 10;
                }

                // ID 首字节为 0 → 已到 padding,结束
                if (id.isEmpty() || tag[pos] == 0) {
                    break;
                }
                if (size <= 0 || bodyStart + size > tag.length) {
                    break;
                }

                if ("USLT".equals(id) || "ULT".equals(id)) {
                    byte[] body = new byte[size];
                    System.arraycopy(tag, bodyStart, body, 0, size);
                    if (unsyncTag || frameUnsync) {
                        body = removeUnsynchronisation(body);
                    }
                    String text = parseUsltBody(body);
                    if (text != null && !text.trim().isEmpty()) {
                        return text;
                    }
                }

                pos = bodyStart + size;
            }
            return null;
        } catch (Exception e) {
            Log.w(TAG, "自解析 ID3 USLT 失败", e);
            return null;
        } finally {
            if (in != null) {
                try { in.close(); } catch (Exception ignored) {}
            }
            if (fis != null) {
                try { fis.close(); } catch (Exception ignored) {}
            }
        }
    }

    /**
     * 解析 USLT 帧体:
     * [1B 编码][3B 语言][描述符(以编码对应的 0 结尾)][歌词正文]
     */
    private static String parseUsltBody(byte[] body) {
        if (body == null || body.length < 5) {
            return null;
        }
        int enc = body[0] & 0xFF;   // 0=ISO-8859-1, 1=UTF-16(BOM), 2=UTF-16BE, 3=UTF-8
        int p = 4;                  // 跳过 encoding(1) + language(3)

        // 描述符:以 0 结尾。ISO/UTF-8 是单字节 0;UTF-16 是双字节 00 00
        int termWidth = (enc == 1 || enc == 2) ? 2 : 1;
        while (p + termWidth <= body.length) {
            boolean isTerm = true;
            for (int i = 0; i < termWidth; i++) {
                if (body[p + i] != 0) {
                    isTerm = false;
                    break;
                }
            }
            p += termWidth;
            if (isTerm) {
                break;
            }
        }
        if (p >= body.length) {
            return null;
        }

        String charset;
        switch (enc) {
            case 1:
                charset = "UTF-16";    // 带 BOM,Java 会自动按 BOM 判端序
                break;
            case 2:
                charset = "UTF-16BE";
                break;
            case 3:
                charset = "UTF-8";
                break;
            default:
                charset = "ISO-8859-1";
                break;
        }
        try {
            String text = new String(body, p, body.length - p, charset);
            // UTF-8 解出乱码(替身字符)时回退 GBK:国内不少歌词是 GBK 写进 UTF-8 字段的
            if (text.indexOf('\uFFFD') >= 0) {
                try {
                    String gbk = new String(body, p, body.length - p, "GBK");
                    if (gbk.indexOf('\uFFFD') < 0) {
                        text = gbk;
                    }
                } catch (Exception ignored) {
                }
            }
            return text.trim().isEmpty() ? null : text;
        } catch (Exception e) {
            Log.w(TAG, "USLT 正文解码失败", e);
            return null;
        }
    }

    /** 反同步还原:0xFF 0x00 → 0xFF */
    private static byte[] removeUnsynchronisation(byte[] src) {
        int outLen = 0;
        for (int i = 0; i < src.length; i++) {
            if (i + 1 < src.length && (src[i] & 0xFF) == 0xFF && src[i + 1] == 0) {
                i++;
            }
            outLen++;
        }
        if (outLen == src.length) {
            return src;
        }
        byte[] out = new byte[outLen];
        int w = 0;
        for (int i = 0; i < src.length; i++) {
            if (i + 1 < src.length && (src[i] & 0xFF) == 0xFF && src[i + 1] == 0) {
                out[w++] = (byte) 0xFF;
                i++;
            } else {
                out[w++] = src[i];
            }
        }
        return out;
    }

    /** ID3 synchsafe 整数:每字节只用低 7 位 */
    private static int synchsafeInt(byte b0, byte b1, byte b2, byte b3) {
        return ((b0 & 0x7F) << 21) | ((b1 & 0x7F) << 14)
                | ((b2 & 0x7F) << 7) | (b3 & 0x7F);
    }

    private static int bigEndianInt(byte b0, byte b1, byte b2, byte b3) {
        return ((b0 & 0xFF) << 24) | ((b1 & 0xFF) << 16)
                | ((b2 & 0xFF) << 8) | (b3 & 0xFF);
    }

    private static int bigEndianInt3(byte b0, byte b1, byte b2) {
        return ((b0 & 0xFF) << 16) | ((b1 & 0xFF) << 8) | (b2 & 0xFF);
    }

    /** 循环读满 len 字节,返回实际读到的字节数 */
    private static int readFully(BufferedInputStream in, byte[] buf, int len) throws Exception {
        int read = 0;
        while (read < len) {
            int n = in.read(buf, read, len - read);
            if (n < 0) {
                break;
            }
            read += n;
        }
        return read;
    }
}
