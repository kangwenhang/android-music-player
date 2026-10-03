package com.captiva.musicplayer;

import java.io.BufferedOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.net.URLDecoder;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 本地流代理:让 MediaPlayer "边下边播" 并同时落盘缓存。
 *
 * 背景:安卓 4.2.2 车机的媒体栈只支持 SSLv3/TLSv1.0,直连飞牛中继(要求 TLS 1.2)
 * 必然握手失败(-1011),所以此前只能"下载完整文件后本地播放" —— 点击未缓存歌曲
 * 要静默等待几十秒。本代理把这个链路倒过来:
 *
 *   MediaPlayer ──HTTP──▶ 127.0.0.1:port/stream?sid=xx(本代理,纯 HTTP 无 TLS 问题)
 *                              │ 单一下载线程:TlsCompat 拉上游流(HTTPS,已验证可用)
 *                              ▼
 *                        目标缓存文件(.part,完成后原子 rename 为最终文件)
 *
 * MediaPlayer 的请求直接从**正在增长的本地文件**读:已落盘的字节立即返回,
 * 未落盘的字节等下载推进(wait/notify)。因此:
 * - 播放约 1~2 秒起播(上游 TLS 握手 + 首批字节),不再等整首下完;
 * - 播放与缓存同一条数据流,完成即变本地歌(登记 StreamIdIndex/AutoCacheManifest);
 * - 用户中途切走,下载线程继续跑完(等效预缓存);
 * - 上游失败 → 关闭客户端连接 → MediaPlayer 报错 → 既有"静默等待下载重播"兜底仍可用。
 *
 * 并发与一致性:
 * - 与 MusicSyncManager.autoCacheSong 共用 IN_FLIGHT 互斥(register 时占用),绝不双写同一文件;
 * - 缓存一律写 .part 临时文件、完成后 rename,任何时刻"最终路径"要么不存在要么完整,
 *   promoteToLocalIfCached 的 exists/length 检查永远不会命中半截文件;
 * - 客户端断开(Mediator 切歌/释放)不影响下载线程,继续把缓存拉完。
 *
 * 线程模型:1 个 accept 线程 + 每连接 1 个服务线程(只读文件、wait/notify)+ 每任务 1 个下载线程。
 * 全部守护线程,不阻止进程退出。
 */
public final class LocalStreamProxy {

    /** 缓存完成/失败回调(均在后台线程回调,实现方自行切线程) */
    public interface CacheCallback {
        void onCached(String streamId, File finalFile);

        void onFailed(String streamId, String reason);
    }

    private static final LocalStreamProxy INSTANCE = new LocalStreamProxy();

    public static LocalStreamProxy get() {
        return INSTANCE;
    }

    private LocalStreamProxy() {
    }

    private static final int BASE_PORT = 18765;
    private static final int PORT_TRIES = 20;
    private static final int MAX_REDIRECTS = 5;

    /** 缓存限速(仅测试版 BuildConfig.DEBUG 生效):~100KB/s,让进度条肉眼可见地推进 */
    static final long THROTTLE_BPS = 100 * 1024;

    private static final String TAG = "LocalStreamProxy";

    private volatile ServerSocket server;
    private volatile int port = -1;
    private final ConcurrentHashMap<String, StreamJob> jobs = new ConcurrentHashMap<String, StreamJob>();

    /** 一个 sid 的下载任务 + 共享状态(读线程与下载线程通过 lock/cachedBytes 协同) */
    private static final class StreamJob {
        final String sid;
        final String url;
        final Map<String, String> headers;
        final File partFile;
        final File finalFile;
        final CacheCallback cb;
        final Object lock = new Object();
        long cachedBytes;          // 已落盘字节数(lock 保护)
        long total = -1;           // 上游声明的总长,未知 = -1(lock 保护)
        volatile boolean downloading;
        volatile boolean failed;

        StreamJob(String sid, String url, Map<String, String> headers,
                  File partFile, File finalFile, CacheCallback cb) {
            this.sid = sid;
            this.url = url;
            this.headers = headers;
            this.partFile = partFile;
            this.finalFile = finalFile;
            this.cb = cb;
        }
    }

    /** 启动本地监听(懒启动,幂等);绑定失败返回 false,调用方回退直连路径 */
    public synchronized boolean start() {
        if (server != null && !server.isClosed()) {
            return true;
        }
        for (int i = 0; i < PORT_TRIES; i++) {
            try {
                ServerSocket ss = new ServerSocket();
                ss.bind(new InetSocketAddress("127.0.0.1", BASE_PORT + i));
                server = ss;
                port = BASE_PORT + i;
                Thread t = new Thread(acceptLoop, "local-stream-proxy");
                t.setDaemon(true);
                t.start();
                return true;
            } catch (Throwable ignored) {
                // 端口被占,试下一个
            }
        }
        return false;
    }

    /** 本地代理播放地址(MediaPlayer setDataSource 用) */
    public String url(String streamId) {
        return "http://127.0.0.1:" + port + "/stream?sid=" + streamId;
    }

    /**
     * 查询某 sid 的缓存进度百分比:下载中返回 0~99;已完成返回 100;
     * 总长未知返回 -1(调用方按心跳语义处理);无任务或已失败返回 -100(停止轮询)。
     * 供 MusicService 以 ~500ms 心跳把边下边播进度广播给界面
     * (播放栏缓存进度条 + 列表行进度条)。
     */
    public int progress(String sid) {
        StreamJob st = (sid == null) ? null : jobs.get(sid);
        if (st == null || st.failed) {
            return -100;
        }
        synchronized (st.lock) {
            if (!st.downloading) {
                return 100;   // 下载线程已结束且未失败 = 缓存完成(rename 落位)
            }
            if (st.total > 0) {
                return (int) (st.cachedBytes * 100 / st.total);
            }
            return -1;
        }
    }

    /**
     * 注册一个边下边播任务。
     * - 同一 sid 已在下载中 → 直接复用(返回 true,不重复下载);
     * - 同一 sid 曾失败 → 重新起一个任务;
     * - beginCache 失败(说明 autoCacheSong 正在下载这首)→ 返回 false,调用方回退直连。
     */
    public boolean register(String sid, String upstreamUrl, Map<String, String> headers,
                            File finalFile, CacheCallback cb) {
        if (sid == null || sid.isEmpty() || upstreamUrl == null
                || finalFile == null || cb == null) {
            return false;
        }
        if (!start()) {
            return false;
        }
        StreamJob old = jobs.get(sid);
        if (old != null && old.downloading) {
            return true;   // 复用在途任务(快速重点同一路歌的场景)
        }
        if (old != null && !old.failed && old.finalFile.exists()
                && old.finalFile.length() > 1024) {
            return true;   // 已完整缓存,继续用旧任务供文件读取
        }
        File part = new File(finalFile.getParentFile(), finalFile.getName() + ".part");
        StreamJob job = new StreamJob(sid, upstreamUrl, headers, part, finalFile, cb);
        if (!MusicSyncManager.beginCache(sid)) {
            return false;  // autoCacheSong 正在下载同一首,让给它
        }
        jobs.put(sid, job);
        Thread t = new Thread(download(job), "proxy-dl");
        t.setDaemon(true);
        t.start();
        return true;
    }

    /**
     * 该 sid 是否有正在进行的下载(供播放失败时判断"代理已在缓存,等它完成即可",
     * 避免再去排队 downloadThenPlay 被 IN_FLIGHT 秒判放弃、错误跳歌)。
     */
    public boolean isDownloading(String sid) {
        StreamJob st = (sid == null) ? null : jobs.get(sid);
        return st != null && st.downloading && !st.failed;
    }

    /**
     * 把 .part 落位为最终文件:优先 rename(原子、零拷贝);失败退化为 复制+删除。
     *
     * 为什么需要兜底:FUSE(/storage/emulated)上 rename 一个仍被打开(读或写)的
     * 文件会失败 —— "边下边播"的 serve 线程持有 .part 读句柄流式给 MediaPlayer,
     * 下载完成时的 rename 正好撞上这种场景(2026-10-03 日志实锤:心里有一场雪
     * 边播边 rename 必败,失败后又删不掉 .part → 残留全长度 → 416 → 落位再败 → 死循环)。
     * 复制 3~5MB 闪存耗时 <1s,期间 serve 继续读 .part 不受影响;复制完删 .part
     * (删不掉也无碍,残留由 416/重下路径处理,新连接优先读 final)。
     */
    public static boolean promote(File part, File target) {
        try {
            if (target.exists()) {
                target.delete();   // 删旧(即便 MediaPlayer 还开着旧 fd,unlink 也安全)
            }
        } catch (Throwable ignored) {
        }
        if (part.renameTo(target)) {
            return true;
        }
        java.io.FileInputStream in = null;
        java.io.FileOutputStream out = null;
        try {
            in = new java.io.FileInputStream(part);
            out = new java.io.FileOutputStream(target);
            byte[] buf = new byte[32 * 1024];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
            out.flush();
            out.close();
            out = null;
            // 长度校验:复制完整才算成功
            if (target.length() != part.length()) {
                DownloadDiag.log("promote 复制长度不符: target=" + target.length()
                        + " part=" + part.length());
                return false;
            }
            try {
                part.delete();
            } catch (Throwable ignored) {
            }
            return true;
        } catch (Throwable t) {
            DownloadDiag.log("promote 复制失败: " + t);
            return false;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Exception ignored) {
                }
            }
            if (out != null) {
                try {
                    out.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    // ==================== 下载线程 ====================

    private Runnable download(final StreamJob st) {
        return new Runnable() {
            @Override
            public void run() {
                st.downloading = true;
                FileOutputStream out = null;
                InputStream is = null;
                HttpURLConnection conn = null;
                try {
                    // 缓存目录必须先建好:目标路径含 歌手/专辑 子目录,多半不存在,
                    // 不建目录直接 open .part 必然 ENOENT(2026-10-03 车机日志实锤:
                    // "伴虎.mp3.part: open failed: ENOENT" 级联到播放失败)。
                    // autoCacheSongLocked / downloadFile 都有 mkdirs,唯独代理此前漏了。
                    File parent = st.partFile.getParentFile();
                    if (parent != null && !parent.exists() && !parent.mkdirs()) {
                        throw new IOException("缓存目录创建失败: " + parent.getAbsolutePath());
                    }
                    long start = st.partFile.exists() ? st.partFile.length() : 0;
                    conn = openUpstream(st, start);
                    int code = conn.getResponseCode();
                    if (code == 416 && start > 0) {
                        // 416 = Range 起点越界:.part 是上次进程被杀/中断的残留,
                        // 长度可能已达总长。若 416 的 Content-Range("bytes */N")
                        // 标明总长且 start>=总长 → .part 其实已完整,直接落位;
                        // 否则删掉残留重下(只重试一次,start 归 0)。
                        String cr = conn.getHeaderField("Content-Range");
                        long total416 = -1;
                        if (cr != null) {
                            int slash = cr.lastIndexOf('/');
                            if (slash >= 0 && !"*".equals(cr.substring(slash + 1).trim())) {
                                try {
                                    total416 = Long.parseLong(cr.substring(slash + 1).trim());
                                } catch (NumberFormatException ignored) {
                                }
                            }
                        }
                        conn.disconnect();
                        conn = null;
                        if (total416 > 0 && start >= total416) {
                            File f = st.finalFile;
                            if (!promote(st.partFile, f)) {
                                throw new IOException("缓存落位失败: " + f.getAbsolutePath());
                            }
                            MusicSyncManager.endCache(st.sid);
                            DownloadDiag.log("边下边播: .part 残留已完整,直接落位 "
                                    + f.getName() + " " + f.length() + " bytes");
                            try {
                                st.cb.onCached(st.sid, f);
                            } catch (Throwable ignored) {
                            }
                            return;
                        }
                        DownloadDiag.log("边下边播: .part 残留越界(416),删除重下 start="
                                + start + " total=" + total416);
                        st.partFile.delete();
                        start = 0;
                        conn = openUpstream(st, 0);
                        code = conn.getResponseCode();
                    }
                    if (code != 200 && code != 206) {
                        throw new IOException("上游 HTTP " + code);
                    }
                    long total = parseTotal(conn, code);
                    synchronized (st.lock) {
                        st.total = total;
                        st.lock.notifyAll();
                    }
                    is = conn.getInputStream();
                    boolean append = (code == 206);
                    if (code == 200 && start > 0) {
                        // 上游不支持 Range(返回 200):从 0 重新下,丢弃前 start 字节
                        long skip = start;
                        while (skip > 0) {
                            long n = is.skip(skip);
                            if (n <= 0) {
                                if (is.read() == -1) {
                                    throw new EOFException("上游数据不足(无法跳过 " + start + " 字节)");
                                }
                                skip--;
                            } else {
                                skip -= n;
                            }
                        }
                        start = 0;
                    }
                    out = new FileOutputStream(st.partFile, append);
                    byte[] buf = new byte[16 * 1024];
                    long pos = start;
                    // 限速(仅测试版):~100KB/s 让缓存进度条肉眼可见;128kbps MP3 实时
                    // 播放仅需 ~16KB/s,限速后仍有 6 倍余量,边下边播不会因限速卡顿;
                    // seek 到未下载区间时的等待与不限速时一致(等下载推进)。
                    final boolean throttle = BuildConfig.DEBUG;
                    final long throttleStart = System.currentTimeMillis();
                    long written = 0;
                    int n;
                    while ((n = is.read(buf)) != -1) {
                        out.write(buf, 0, n);
                        out.flush();   // flush 后才推进 cachedBytes,保证读线程永远读到已完整落盘的数据
                        pos += n;
                        written += n;
                        if (throttle) {
                            // 按"本次已写字节 ÷ 速率"与实际耗时之差 sleep;单次最多睡 400ms,
                            // 保证中断仍有响应
                            long want = written * 1000 / THROTTLE_BPS;
                            long elapsed = System.currentTimeMillis() - throttleStart;
                            if (want > elapsed) {
                                try {
                                    Thread.sleep(Math.min(want - elapsed, 400));
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                }
                            }
                        }
                        synchronized (st.lock) {
                            st.cachedBytes = pos;
                            st.lock.notifyAll();
                        }
                    }
                    out.flush();
                    out.close();
                    out = null;
                    // 落位:.part → 最终文件(此后 promoteToLocalIfCached 才可见)
                    // rename 优先(原子);serve 线程持有 .part 读句柄时 FUSE 上 rename
                    // 会失败 → promote 内部退化为 复制+删除
                    File f = st.finalFile;
                    if (!promote(st.partFile, f)) {
                        throw new IOException("缓存落位失败: " + f.getAbsolutePath());
                    }
                    MusicSyncManager.endCache(st.sid);
                    DownloadDiag.log("边下边播: 缓存完成 " + f.getName() + " " + f.length() + " bytes");
                    try {
                        st.cb.onCached(st.sid, f);
                    } catch (Throwable ignored) {
                    }
                } catch (Throwable t) {
                    synchronized (st.lock) {
                        st.failed = true;
                        st.lock.notifyAll();   // 唤醒所有等待数据的读线程 → 连接关闭 → MediaPlayer 报错走兜底
                    }
                    if (out != null) {
                        try {
                            out.close();
                        } catch (Exception ignored) {
                        }
                    }
                    try {
                        st.partFile.delete();   // 半截文件绝不留在最终可见路径
                    } catch (Exception ignored) {
                    }
                    MusicSyncManager.endCache(st.sid);
                    DownloadDiag.log("边下边播: 缓存失败 " + st.sid + " | " + t);
                    try {
                        st.cb.onFailed(st.sid, String.valueOf(t));
                    } catch (Throwable ignored) {
                    }
                } finally {
                    st.downloading = false;
                    if (is != null) {
                        try {
                            is.close();
                        } catch (Exception ignored) {
                        }
                    }
                    if (conn != null) {
                        conn.disconnect();
                    }
                }
            }
        };
    }

    /** 打开上游连接(带鉴权头;中继 302 手动跟随并重放头,避免跟随丢 Cookie) */
    private HttpURLConnection openUpstream(StreamJob st, long rangeStart) throws IOException {
        URL url = new URL(st.url);
        for (int i = 0; i < MAX_REDIRECTS; i++) {
            HttpURLConnection conn = TlsCompat.open(url);
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(60000);
            if (st.headers != null) {
                for (Map.Entry<String, String> e : st.headers.entrySet()) {
                    conn.setRequestProperty(e.getKey(), e.getValue());
                }
            }
            if (rangeStart > 0) {
                conn.setRequestProperty("Range", "bytes=" + rangeStart + "-");
            }
            conn.setInstanceFollowRedirects(false);
            int code = conn.getResponseCode();
            if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                String loc = conn.getHeaderField("Location");
                conn.disconnect();
                if (loc == null || loc.isEmpty()) {
                    throw new IOException("重定向缺少 Location");
                }
                url = new URL(url, loc);
                continue;
            }
            return conn;
        }
        throw new IOException("重定向次数过多");
    }

    /** 解析上游总长:206 从 Content-Range 取,200 从 Content-Length 取;未知返回 -1 */
    private long parseTotal(HttpURLConnection conn, int code) {
        String cr = conn.getHeaderField("Content-Range");
        if (cr != null) {
            int slash = cr.lastIndexOf('/');
            if (slash >= 0) {
                String t = cr.substring(slash + 1).trim();
                if (!"*".equals(t)) {
                    try {
                        return Long.parseLong(t);
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
        }
        if (code == 200) {
            long cl = conn.getContentLength();
            if (cl > 0) {
                return cl;
            }
        }
        return -1;
    }

    // ==================== 连接服务 ====================

    private final Runnable acceptLoop = new Runnable() {
        @Override
        public void run() {
            while (true) {
                try {
                    ServerSocket ss = server;
                    if (ss == null || ss.isClosed()) {
                        return;
                    }
                    final Socket s = ss.accept();
                    Thread t = new Thread(new Runnable() {
                        @Override
                        public void run() {
                            serve(s);
                        }
                    }, "proxy-conn");
                    t.setDaemon(true);
                    t.start();
                } catch (Throwable e) {
                    ServerSocket ss = server;
                    if (ss == null || ss.isClosed()) {
                        return;
                    }
                }
            }
        }
    };

    /** 解析请求行/Range 头,定位任务后把"正在增长的本地文件"流给 MediaPlayer */
    private void serve(Socket socket) {
        try {
            socket.setSoTimeout(30000);   // 仅读请求头用
            java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(socket.getInputStream(), "ASCII"));
            String line = reader.readLine();
            if (line == null || !line.startsWith("GET")) {
                return;
            }
            String sid = param(line, "sid");
            long start = 0;
            while ((line = reader.readLine()) != null && !line.isEmpty()) {
                if (line.length() > 6 && "range:".equalsIgnoreCase(line.substring(0, 6))) {
                    int eq = line.indexOf('=');
                    if (eq > 0) {
                        String spec = line.substring(eq + 1).trim();
                        int dash = spec.indexOf('-');
                        if (dash > 0) {
                            try {
                                start = Long.parseLong(spec.substring(0, dash).trim());
                            } catch (NumberFormatException ignored) {
                            }
                        }
                    }
                }
            }
            StreamJob st = (sid == null) ? null : jobs.get(sid);
            if (st == null || start < 0) {
                return;   // finally 里关闭
            }
            socket.setSoTimeout(0);   // 之后只写不读
            handleServe(socket, st, start);
        } catch (Throwable ignored) {
            // 客户端断开(切歌/reset/播完)是常态,静默
        } finally {
            try {
                socket.close();
            } catch (Exception ignored) {
            }
        }
    }

    private void handleServe(Socket socket, StreamJob st, long start) throws Exception {
        OutputStream out = new BufferedOutputStream(socket.getOutputStream());
        long total;
        synchronized (st.lock) {
            total = st.total;
        }
        StringBuilder h = new StringBuilder(192);
        if (total > 0) {
            if (start >= total) {
                return;   // 越界请求,直接关闭
            }
            h.append("HTTP/1.1 206 Partial Content\r\n");
            h.append("Content-Range: bytes ").append(start).append('-')
                    .append(total - 1).append('/').append(total).append("\r\n");
            h.append("Content-Length: ").append(total - start).append("\r\n");
        } else {
            h.append("HTTP/1.1 200 OK\r\n");
        }
        h.append("Content-Type: audio/mpeg\r\nAccept-Ranges: bytes\r\nConnection: close\r\n\r\n");
        out.write(h.toString().getBytes("ASCII"));
        out.flush();

        // 等待下载线程创建 .part:MediaPlayer 连 127.0.0.1 几乎瞬时,而下载线程要先
        // mkdirs + 上游 TLS 握手(中继 1~3 秒)才落第一个字节 —— 不等的话 serve 在这里
        // ENOENT 静默退出、连接关闭,MediaPlayer 同秒报错,表现为"点歌秒失败还跳歌"
        // (2026-10-03 日志实锤:浪漫曲/回音 注册与播放失败同一秒)。
        synchronized (st.lock) {
            long deadline = System.currentTimeMillis() + 15000;
            while (!st.partFile.exists() && !st.finalFile.exists()
                    && st.downloading && !st.failed
                    && System.currentTimeMillis() < deadline) {
                try {
                    st.lock.wait(1000);
                } catch (InterruptedException ignored) {
                }
            }
        }
        // part 文件可能在流式中途被 rename 为最终文件(Linux/Android 下 rename 后旧 fd 仍有效);
        // 新连接则直接打开存在的那个。两者都不存在(失败/超时)→ RandomAccessFile 抛异常,
        // 上层 catch 关闭连接 → MediaPlayer 报错走兜底。
        File readFrom = st.partFile.exists() ? st.partFile : st.finalFile;
        RandomAccessFile raf = new RandomAccessFile(readFrom, "r");
        try {
            raf.seek(start);
            long pos = start;
            byte[] buf = new byte[16 * 1024];
            while (true) {
                long cached;
                boolean downloading;
                synchronized (st.lock) {
                    cached = st.cachedBytes;
                    downloading = st.downloading;
                }
                if (pos < cached) {
                    int want = (int) Math.min(buf.length, cached - pos);
                    int n = raf.read(buf, 0, want);
                    if (n <= 0) {
                        break;
                    }
                    out.write(buf, 0, n);
                    pos += n;
                } else if (total > 0 && pos >= total) {
                    break;   // 完整送达
                } else if (downloading) {
                    // 等下载推进(用户 seek 到未下载区间时在这里等待,等效"静默等待")
                    synchronized (st.lock) {
                        if (st.cachedBytes <= pos && st.downloading) {
                            st.lock.wait(15000);
                        }
                    }
                } else {
                    break;   // 下载已结束且无更多数据(失败 → 连接关闭 → MediaPlayer 报错走兜底)
                }
            }
            out.flush();
        } finally {
            try {
                raf.close();
            } catch (Exception ignored) {
            }
        }
    }

    /** 从请求行 "GET /stream?sid=xx HTTP/1.1" 里取 query 参数 */
    private static String param(String requestLine, String key) {
        try {
            int sp1 = requestLine.indexOf(' ');
            int sp2 = requestLine.lastIndexOf(' ');
            if (sp1 < 0 || sp2 <= sp1) {
                return null;
            }
            String target = requestLine.substring(sp1 + 1, sp2);
            int q = target.indexOf('?');
            if (q < 0) {
                return null;
            }
            for (String kv : target.substring(q + 1).split("&")) {
                int eq = kv.indexOf('=');
                if (eq > 0 && key.equals(kv.substring(0, eq))) {
                    return URLDecoder.decode(kv.substring(eq + 1), "UTF-8");
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }
}
