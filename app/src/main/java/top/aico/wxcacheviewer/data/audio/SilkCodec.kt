package top.aico.wxcacheviewer.data.audio

/**
 * SILK 解码的原生入口。
 *
 * so 由本模块 `src/main/cpp` 随应用一起编译（Skype SILK v3 定点解码器 + LAME），
 * 不用第三方预编译库：那个库的段对齐只有 4KB，在 16KB 页设备上会被系统判定为
 * 兼容模式并每次启动弹窗。自己编可以把 LOAD 段对齐到 16KB。
 *
 * 每个方法返回 0 表示成功，负数表示失败。
 */
internal object SilkCodec {

    init {
        System.loadLibrary("wxsilk")
    }

    /** 解码成 16bit 单声道 wav，供应用内播放。 */
    external fun decodeToWav(srcPath: String, dstPath: String, sampleRate: Int): Int

    /** 解码并按指定码率编码成 mp3，供导出。 */
    external fun decodeToMp3(srcPath: String, dstPath: String, sampleRate: Int, bitrate: Int): Int
}
