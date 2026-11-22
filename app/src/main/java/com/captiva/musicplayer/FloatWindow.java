package com.captiva.musicplayer;

import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

/**
 * 桌面悬浮控制条(app 退到后台时显示)。
 *
 * 归属 MusicService 持有(服务常驻、有播放状态):
 * - 显示/隐藏由 MainActivity 的前后台判定触发(SHOW/HIDE 命令经 service 转交);
 * - 控件直接调 service 的 toggle()/next()/prev(),状态经 notifyState() 反刷;
 * - 歌名区域是拖动把手,可整条拖动调整位置;点歌名回主界面。
 *
 * API 17 车机:TYPE_PHONE + SYSTEM_ALERT_WINDOW 清单声明即授,无需弹窗授权。
 */
public class FloatWindow {
    private static final String TAG = "FloatWindow";

    private final MusicService service;
    private final WindowManager wm;
    private final Handler main = new Handler(Looper.getMainLooper());

    private View root;
    private TextView tvTitle, btnToggle;
    private WindowManager.LayoutParams params;
    private boolean showing;

    /** 拖动判定阈值:移动超过此距离才算拖,否则视为点击 */
    private static final int DRAG_SLOP_DP = 10;

    public FloatWindow(MusicService service) {
        this.service = service;
        this.wm = (WindowManager) service.getSystemService(android.app.Service.WINDOW_SERVICE);
    }

    /** 显示悬浮条(无当前曲目时不显示) */
    public void show() {
        main.post(new Runnable() {
            @Override
            public void run() {
                showInternal();
            }
        });
    }

    private void showInternal() {
        if (showing) {
            updateStateInternal();
            return;
        }
        if (service.getCurrentMusic() == null) {
            return; // 没有曲目,悬浮条无内容可显示
        }
        try {
            if (root == null) {
                inflateView();
            }
            wm.addView(root, params);
            showing = true;
            // 先置 showing 再刷状态:updateStateInternal 有 showing 门禁
            updateStateInternal();
            Log.i(TAG, "悬浮条已显示");
        } catch (Exception e) {
            Log.w(TAG, "addView failed", e);
        }
    }

    /** 隐藏悬浮条 */
    public void hide() {
        main.post(new Runnable() {
            @Override
            public void run() {
                hideInternal();
            }
        });
    }

    private void hideInternal() {
        if (!showing) return;
        try {
            wm.removeView(root);
        } catch (Exception e) {
            Log.w(TAG, "removeView failed", e);
        }
        showing = false;
        Log.i(TAG, "悬浮条已隐藏");
    }

    /** 播放状态/曲目变化时刷新歌名与播放键(notifyState 转发) */
    public void updateState() {
        main.post(new Runnable() {
            @Override
            public void run() {
                updateStateInternal();
            }
        });
    }

    private void updateStateInternal() {
        if (!showing || root == null) return;
        MusicBean bean = service.getCurrentMusic();
        if (bean == null) {
            hideInternal();
            return;
        }
        String title = bean.getTitle();
        String artist = bean.getArtist();
        String text = (artist != null && !artist.isEmpty())
                ? artist + " - " + title : title;
        tvTitle.setText(text);
        btnToggle.setText(service.isPlaying() ? "❚❚" : "▶");
    }

    /** 服务销毁时移除窗口 */
    public void destroy() {
        main.post(new Runnable() {
            @Override
            public void run() {
                hideInternal();
                root = null;
            }
        });
    }

    // ---------- 构建 View ----------

    private void inflateView() {
        root = LayoutInflater.from(service).inflate(R.layout.float_control, null);
        tvTitle = (TextView) root.findViewById(R.id.float_title);
        btnToggle = (TextView) root.findViewById(R.id.float_toggle);
        TextView btnPrev = (TextView) root.findViewById(R.id.float_prev);
        TextView btnNext = (TextView) root.findViewById(R.id.float_next);
        TextView btnClose = (TextView) root.findViewById(R.id.float_close);

        btnPrev.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                service.prev();
            }
        });
        btnNext.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                service.next();
            }
        });
        btnToggle.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                service.toggle(); // notifyState 会反刷 updateState
            }
        });
        btnClose.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // 点×只隐藏本次;下次退到后台再由 MainActivity 重新触发显示
                hideInternal();
            }
        });

        // 歌名区域:拖动 + 点击回主界面
        tvTitle.setOnTouchListener(new View.OnTouchListener() {
            private float downRawX, downRawY;
            private int startX, startY;
            private boolean dragging;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        downRawX = event.getRawX();
                        downRawY = event.getRawY();
                        startX = params.x;
                        startY = params.y;
                        dragging = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float dx = event.getRawX() - downRawX;
                        float dy = event.getRawY() - downRawY;
                        float slop = DRAG_SLOP_DP
                                * service.getResources().getDisplayMetrics().density;
                        if (!dragging && Math.abs(dx) + Math.abs(dy) > slop) {
                            dragging = true;
                        }
                        if (dragging) {
                            params.x = startX + (int) dx;
                            params.y = startY - (int) dy;
                            try {
                                wm.updateViewLayout(root, params);
                            } catch (Exception ignored) {
                            }
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (!dragging) {
                            openMainActivity();
                        }
                        return true;
                    default:
                        return true;
                }
            }
        });

        params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                // API 26+ 废弃 TYPE_PHONE(非系统 app 直接 BadTokenException),
                // 必须 TYPE_APPLICATION_OVERLAY + 用户授权悬浮窗;
                // 车机 API 17 仍走 TYPE_PHONE,清单声明即授
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        // 默认屏幕底部居中,贴底留 12dp
        params.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        params.y = dp(12);
    }

    /** 点歌名回到主界面(singleTask,回前台不重建) */
    private void openMainActivity() {
        try {
            Intent i = new Intent(service, MainActivity.class);
            i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            service.startActivity(i);
        } catch (Exception e) {
            Log.w(TAG, "open main failed", e);
        }
    }

    private int dp(int v) {
        return (int) (v * service.getResources().getDisplayMetrics().density + 0.5f);
    }
}
