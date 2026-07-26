# ProGuard 规则
# 本工程不开启混淆,这里保留默认规则

# 保留 MediaPlayer 相关
-keep class android.media.MediaPlayer { *; }

# 保留 Service / Activity
-keep class com.captiva.musicplayer.** { *; }