# ProGuard/R8 规则
#
# 【2026-11-03】debug 分发包(车机 OTA 实际形态)开启 R8 裁剪:
# minifyEnabled + shrinkResources,只裁"没被引用到的 androidx 代码/资源",
# 不做混淆改名、不做激进优化,把车机运行时风险压到最低。

# 不混淆改名:车机日志/PerfLogger 堆栈保持可读
-dontobfuscate

# 保留 MediaPlayer 相关
-keep class android.media.MediaPlayer { *; }

# 保留自家全部代码(体量小,裁它省不了多少;裁剪收益全在 androidx)
-keep class com.captiva.musicplayer.** { *; }

# Conscrypt(TLS 1.2)自带 consumer 规则,无需额外 keep
