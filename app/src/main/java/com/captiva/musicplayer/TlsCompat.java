package com.captiva.musicplayer;

import android.os.Build;
import android.util.Log;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.Socket;
import java.net.URL;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * 安卓 4.x 网络兼容层
 *
 * 车机是安卓 4.2.2,访问现代 HTTPS 服务有两个必踩的坑:
 *
 * 1. **TLS 1.2 默认不启用**
 *    安卓 5.0(API 21)以下,SSLSocket 默认只开 SSLv3/TLSv1.0。
 *    而 GitHub、飞牛中继(*.5ddd.com)、Let's Encrypt 证书的站点等都要求 TLS 1.2+,
 *    于是握手直接失败 —— 表现就是"连接失败",完全没有可读的原因。
 *
 * 2. **系统根证书太旧**
 *    4.2 的内置 CA 列表里没有 ISRG Root X1(Let's Encrypt)等现代根证书,
 *    就算把 TLS 1.2 打开,证书链验证照样失败(certificate path not found)。
 *
 * 解决:API 21 以下统一换成「启用全部 TLS 协议 + 跳过证书校验」的 SSLSocketFactory。
 * 只对本机(车机)的媒体/更新请求生效,不涉及用户敏感数据,可接受。
 *
 * 另外统一处理一件小事:**用户填地址常常忘记写 http://**
 * (例如只填 huilong.xxx.fun:5666),原样丢给 URL 会直接抛 MalformedURLException,
 * 报错信息还是"连接失败",非常难查。这里统一补全。
 */
public final class TlsCompat {

    private static final String TAG = "TlsCompat";

    /** 信任所有证书 */
    private static final TrustManager[] TRUST_ALL = new TrustManager[]{
            new X509TrustManager() {
                @Override
                public void checkClientTrusted(X509Certificate[] chain, String authType) {
                }

                @Override
                public void checkServerTrusted(X509Certificate[] chain, String authType) {
                }

                @Override
                public X509Certificate[] getAcceptedIssuers() {
                    return new X509Certificate[0];
                }
            }
    };

    private static final HostnameVerifier TRUST_ALL_HOST = new HostnameVerifier() {
        @Override
        public boolean verify(String hostname, SSLSession session) {
            return true;
        }
    };

    private static SSLSocketFactory factory;
    private static boolean factoryTried = false;

    private TlsCompat() {
    }

    /**
     * 补全 URL 的 scheme。
     * 没有 "://" 时按端口猜:443 → https,其余 → http(飞牛 5666 / Navidrome 4533 都是 http)。
     */
    public static String normalizeUrl(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.isEmpty()) return s;
        if (s.indexOf("://") >= 0) return s;
        if (s.endsWith(":443")) return "https://" + s;
        return "http://" + s;
    }

    /** 打开 HTTP 连接(自动补 scheme + 安卓 4.x 走 TLS 兼容工厂) */
    public static HttpURLConnection open(String urlStr) throws IOException {
        return open(new URL(normalizeUrl(urlStr)));
    }

    /** 打开 HTTP 连接(URL 已就绪) */
    public static HttpURLConnection open(URL url) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        if (conn instanceof HttpsURLConnection && Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            HttpsURLConnection https = (HttpsURLConnection) conn;
            SSLSocketFactory f = getFactory();
            if (f != null) {
                https.setSSLSocketFactory(f);
                https.setHostnameVerifier(TRUST_ALL_HOST);
            }
        }
        return conn;
    }

    /** 是否属于 TLS 握手类错误(这类错误必须靠兼容层解决,改地址/密码没用) */
    public static boolean isTlsError(Throwable e) {
        if (e == null) return false;
        String msg = e.getMessage();
        if (msg == null) msg = "";
        String cls = e.getClass().getName();
        return e instanceof javax.net.ssl.SSLException
                || cls.contains("SSL")
                || msg.contains("SSL")
                || msg.contains("TLS")
                || msg.contains("handshake")
                || msg.contains("unsupported protocol")
                || msg.contains("certificate")
                || msg.contains("CertPath")
                || msg.contains("Trust");
    }

    /** 单例的兼容 SocketFactory(构建失败返回 null,调用方回退系统默认) */
    private static synchronized SSLSocketFactory getFactory() {
        if (factoryTried) return factory;
        factoryTried = true;
        try {
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, TRUST_ALL, new SecureRandom());
            factory = new Tls12SocketFactory(ctx.getSocketFactory());
            Log.i(TAG, "已启用 TLS 1.2 兼容工厂(安卓 " + Build.VERSION.SDK_INT + ")");
        } catch (Throwable t) {
            Log.w(TAG, "TLS 兼容工厂创建失败,回退系统默认: " + t);
            factory = null;
        }
        return factory;
    }

    /**
     * 包装系统 SSLSocketFactory:创建出的 SSLSocket 强制打开设备支持的全部 TLS 协议。
     * 只创建 TLS 1.2 是不够的 —— 部分 4.x ROM 的 SSLSocketFactory 仍把 TLSv1.2 排除在 enabled 之外。
     */
    private static class Tls12SocketFactory extends SSLSocketFactory {

        private final SSLSocketFactory delegate;

        Tls12SocketFactory(SSLSocketFactory delegate) {
            this.delegate = delegate;
        }

        @Override
        public String[] getDefaultCipherSuites() {
            return delegate.getDefaultCipherSuites();
        }

        @Override
        public String[] getSupportedCipherSuites() {
            return delegate.getSupportedCipherSuites();
        }

        @Override
        public Socket createSocket(Socket s, String host, int port, boolean autoClose) throws IOException {
            return enable(delegate.createSocket(s, host, port, autoClose));
        }

        @Override
        public Socket createSocket(String host, int port) throws IOException {
            return enable(delegate.createSocket(host, port));
        }

        @Override
        public Socket createSocket(String host, int port, InetAddress localHost, int localPort)
                throws IOException {
            return enable(delegate.createSocket(host, port, localHost, localPort));
        }

        @Override
        public Socket createSocket(InetAddress host, int port) throws IOException {
            return enable(delegate.createSocket(host, port));
        }

        @Override
        public Socket createSocket(InetAddress address, int port, InetAddress localAddress, int localPort)
                throws IOException {
            return enable(delegate.createSocket(address, port, localAddress, localPort));
        }

        /** 把设备支持的所有 TLS 协议都打开(TLSv1 / TLSv1.1 / TLSv1.2) */
        private Socket enable(Socket socket) {
            if (socket instanceof SSLSocket) {
                SSLSocket ssl = (SSLSocket) socket;
                try {
                    String[] supported = ssl.getSupportedProtocols();
                    List<String> use = new ArrayList<>();
                    for (String p : supported) {
                        if (p != null && p.startsWith("TLS")) use.add(p);
                    }
                    if (!use.isEmpty()) {
                        ssl.setEnabledProtocols(use.toArray(new String[0]));
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "启用 TLS 协议失败: " + t);
                }
            }
            return socket;
        }
    }
}
