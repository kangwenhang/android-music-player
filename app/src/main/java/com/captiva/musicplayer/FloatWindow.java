package com.captiva.musicplayer;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.TextView;


/**
 * 桌面悬浮控制条 v7(app 退到后台时显示)。
 *
 * 归属 MusicService 持有(服务常驻、有播放状态):
 * - 显示/隐藏由 MainActivity 的前后台判定触发(SHOW/HIDE 命令经 service 转交);
 * - 控件直接调 service 的 toggle()/next()/prev(),状态经 notifyState() 反刷;
 * - 封面方形圆角 48dp + 文字列(歌名/当前歌词行) + 进度条(白点);时间不显示,
 *   总时长由进度条表达,歌词独占整行
 * - 封面与文字列是拖动把手,可整条拖动;点文字列回主界面;
 * - 显示期间 500ms 轮询刷新歌词/时间/进度(服务内直接取状态,不经广播)。
 *
 * API 17 车机:TYPE_PHONE + SYSTEM_ALERT_WINDOW 清单声明即授,无需弹窗授权。
 */
public class FloatWindow {
    private static final String TAG = "FloatWindow";

    private final MusicService service;
    private final WindowManager wm;
    private final Handler main = new Handler(Looper.getMainLooper());

    private View root, textArea;
    private TextView tvTitle, btnToggle;
    private ImageView ivCover;
    private FloatProgressView progressView;
    private WindowManager.LayoutParams params;
    private boolean showing;

    /** 封面加载代次:切歌后旧回调自动作废,防止封面错配 */
    private int coverToken = 0;

    /** 拖动判定阈值:移动超过此距离才算拖,否则视为点击 */
    private static final int DRAG_SLOP_DP = 10;

    /** 歌词/时间/进度轮询周期 */
    private static final long POLL_MS = 500;

    /** 轮询循环:显示期间持续刷新动态内容 */
    private final Runnable pollTask = new Runnable() {
        @Override
        public void run() {
            if (!showing) return;
            refreshDynamic();
            main.postDelayed(this, POLL_MS);
        }
    };

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
            main.post(pollTask);
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
        main.removeCallbacks(pollTask);
        try {
            wm.removeView(root);
        } catch (Exception e) {
            Log.w(TAG, "removeView failed", e);
        }
        showing = false;
        Log.i(TAG, "悬浮条已隐藏");
    }

    /** 播放状态/曲目变化时刷新歌名/播放键/封面(notifyState 转发) */
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
        // 跑马灯:悬浮窗无焦点,setSelected 才能驱动 marquee 循环滚动
        tvTitle.setSelected(true);
        boolean playing = service.isPlaying();
        btnToggle.setText(playing ? "❚❚" : "▶");
        // 底色随状态:播放中蓝色 / 暂停红色(2026-11-22 用户定稿)
        btnToggle.setBackgroundResource(playing
                ? R.drawable.bg_float_toggle : R.drawable.bg_float_toggle_paused);
        // ▶ 字形在字体基线里自带左侧空白,居中后视觉偏左:加左内边距修正视觉居中
        btnToggle.setPadding(playing ? 0 : dp(3), 0, 0, 0);

        // 封面:代次递增,旧回调作废;先清图显占位底色再异步加载
        final int token = ++coverToken;
        ivCover.setImageDrawable(null);
        CoverLoader.getInstance().loadBitmap(bean, dp(48), new CoverLoader.BitmapCallback() {
            @Override
            public void onBitmapLoaded(Bitmap bitmap) {
                if (!showing || token != coverToken) return;
                ivCover.setImageBitmap(roundCorner(bitmap, dp(48), dp(10)));
            }
        });

        refreshDynamic();
    }

    /** 进度刷新(500ms 轮询 + 状态变化时立即调);歌词已去掉,总时长由进度条表达 */
    private void refreshDynamic() {
        int pos = service.getCurrentPosition();
        int dur = service.getDuration();
        progressView.setFraction(dur > 0 ? (float) pos / dur : 0f);
    }

    /**
     * 圆角裁切(方形封面 10dp 圆角;API 17 无 clipToOutline,用 BitmapShader 自绘)。
     * 先缩放到显示尺寸再画圆角:真机封面位图常远大于 48dp,若按原图像素画 10dp 圆角,
     * centerCrop 缩小显示后圆角只剩 1~2px 肉眼不可见(真机翻车,2026-11-22)。
     */
    private Bitmap roundCorner(Bitmap src, int sizePx, int radiusPx) {
        if (src == null) return null;
        try {
            Bitmap scaled = Bitmap.createScaledBitmap(src, sizePx, sizePx, true);
            Bitmap out = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(out);
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setShader(new BitmapShader(scaled, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
            c.drawRoundRect(new RectF(0, 0, sizePx, sizePx), radiusPx, radiusPx, p);
            return out;
        } catch (Throwable t) {
            return src; // 裁切失败退回原图
        }
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
        ivCover = (ImageView) root.findViewById(R.id.float_cover);
        textArea = root.findViewById(R.id.float_text_area);
        tvTitle = (TextView) root.findViewById(R.id.float_title);
        progressView = (FloatProgressView) root.findViewById(R.id.float_progress);
        btnToggle = (TextView) root.findViewById(R.id.float_toggle);
        TextView btnPrev = (TextView) root.findViewById(R.id.float_prev);
        TextView btnNext = (TextView) root.findViewById(R.id.float_next);

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

        // 封面 + 文字列 = 拖动把手(点击回主界面);按键区不受影响
        View.OnTouchListener touch = new HandleTouchListener();
        ivCover.setOnTouchListener(touch);
        textArea.setOnTouchListener(touch);

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

    /** 封面/文字列共用的触摸处理:拖动整条 + 点击回主界面 */
    private class HandleTouchListener implements View.OnTouchListener {
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
    }

    /** 点文字列回到主界面(singleTask,回前台不重建) */
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
