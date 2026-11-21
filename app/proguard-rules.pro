# Add project specific ProGuard rules here.

# JNI 按「包名_类名_方法名」查找本地方法，混淆后入口就丢了，整类保留
-keep class top.aico.wxcacheviewer.data.audio.SilkCodec { *; }
