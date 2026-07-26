package com.captiva.musicplayer;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

/**
 * 服务器连接状态监控器
 * - 定期 ping 服务器检测连通性
 * - 连接失败时每 30 秒自动重试
 * - 通过回调通知 UI 更新状态显示
 */
public class ServerStatusMonitor {

    private static final String TAG = "ServerStatusMonitor";

    public enum Status {
        /** 已连接 */
        CONNECTED,
        /** 未连接(连接失败) */
        DISCONNECTED,
        /** 连接中(正在尝试连接) */
        CONNECTING,
        /** 重连中(等待重试) */
        RETRYING,
        /** 离线(未配置服务器) */
        OFFLINE
    }

    public interface StatusCallback {
        /** 状态变化时回调(主线程) */
        void onStatusChanged(Status status, String message);
    }

    /** 重试间隔:30 秒 */
    private static final long RETRY_INTERVAL_MS = 30000;
    /** 正常状态下的检查间隔:60 秒 */
    private static final long CHECK_INTERVAL_MS = 60000;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private StatusCallback callback;
    private NavidromeApi api;
    private Status currentStatus = Status.OFFLINE;
    private boolean monitoring = false;
    private boolean checking = false;

    /** 倒计时剩余秒数(重连模式下) */
    private int retryCountdown = 0;

    private final Runnable checkRunnable = new Runnable() {
        @Override
        public void run() {
            if (!monitoring) return;
            checkConnection();
        }
    };

    private final Runnable countdownRunnable = new Runnable() {
        @Override
        public void run() {
            if (!monitoring) return;
            if (currentStatus == Status.RETRYING) {
                retryCountdown--;
                if (retryCountdown <= 0) {
                    // 倒计时结束,尝试重连
                    checkConnection();
                } else {
                    // 继续倒计时
                    notifyStatus(Status.RETRYING, "重连中(" + retryCountdown + "s)");
                    mainHandler.postDelayed(countdownRunnable, 1000);
                }
            }
        }
    };

    public void setCallback(StatusCallback callback) {
        this.callback = callback;
    }

    /** 获取当前状态 */
    public Status getStatus() {
        return currentStatus;
    }

    /**
     * 启动监控
     * @param api NavidromeApi 实例,null 则状态为 OFFLINE
     */
    public void start(NavidromeApi api) {
        this.api = api;
        monitoring = true;
        if (api == null) {
            notifyStatus(Status.OFFLINE, "未配置服务器");
            return;
        }
        // 立即执行一次检测
        checkConnection();
    }

    /** 停止监控 */
    public void stop() {
        monitoring = false;
        mainHandler.removeCallbacks(checkRunnable);
        mainHandler.removeCallbacks(countdownRunnable);
    }

    /** 更新 API 实例(配置变更后调用) */
    public void updateApi(NavidromeApi api) {
        this.api = api;
        if (api == null) {
            notifyStatus(Status.OFFLINE, "未配置服务器");
        } else {
            // 配置变更后立即检测
            mainHandler.removeCallbacks(checkRunnable);
            mainHandler.removeCallbacks(countdownRunnable);
            checkConnection();
        }
    }

    /** 手动触发一次连接检测 */
    public void checkNow() {
        if (api == null) {
            notifyStatus(Status.OFFLINE, "未配置服务器");
            return;
        }
        mainHandler.removeCallbacks(checkRunnable);
        mainHandler.removeCallbacks(countdownRunnable);
        checkConnection();
    }

    /** 执行连接检测(异步) */
    private void checkConnection() {
        if (checking || api == null) return;
        checking = true;
        notifyStatus(Status.CONNECTING, "连接中...");

        final NavidromeApi apiRef = api;
        new Thread(new Runnable() {
            @Override
            public void run() {
                final boolean ok = apiRef.ping();
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        checking = false;
                        if (!monitoring) return;
                        if (ok) {
                            notifyStatus(Status.CONNECTED, "已连接");
                            // 正常状态下,60 秒后再次检测
                            mainHandler.postDelayed(checkRunnable, CHECK_INTERVAL_MS);
                        } else {
                            // 连接失败,启动 30 秒重连倒计时
                            startRetry();
                        }
                    }
                });
            }
        }).start();
    }

    /** 启动 30 秒重连倒计时 */
    private void startRetry() {
        retryCountdown = (int) (RETRY_INTERVAL_MS / 1000);
        notifyStatus(Status.RETRYING, "重连中(" + retryCountdown + "s)");
        mainHandler.postDelayed(countdownRunnable, 1000);
    }

    /** 通知状态变化 */
    private void notifyStatus(Status status, String message) {
        Status old = currentStatus;
        currentStatus = status;
        Log.d(TAG, "状态变更: " + old + " -> " + status + " (" + message + ")");
        if (callback != null) {
            callback.onStatusChanged(status, message);
        }
    }
}
