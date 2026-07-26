package com.captiva.musicplayer;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * 媒体按键接收器
 * 接收系统分发的 ACTION_MEDIA_BUTTON,转发给 MusicService
 */
public class MediaButtonReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) {
            return;
        }
        if (Intent.ACTION_MEDIA_BUTTON.equals(intent.getAction())) {
            Intent service = new Intent(context, MusicService.class);
            service.setAction(Intent.ACTION_MEDIA_BUTTON);
            service.putExtras(intent);
            context.startService(service);
        }
    }
}