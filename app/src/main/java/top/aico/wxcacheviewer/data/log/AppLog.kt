package top.aico.wxcacheviewer.data.log

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 落盘日志：应用内 files/logs/app.log，超过大小上限后滚动到 app.log.1。
 * 便于在无法连接 adb 的设备上直接取回运行现场。
 */
object AppLog {

    private const val TAG = "WXCacheViewer"
    private const val MAX_BYTES = 256 * 1024L
    private const val DATE_PATTERN = "MM-dd HH:mm:ss.SSS"

    @Volatile
    private var logDir: File? = null

    private val dateFormat = SimpleDateFormat(DATE_PATTERN, Locale.US)
    private val lock = Any()

    fun init(context: Context) {
        logDir = File(context.filesDir, "logs")
    }

    fun file(context: Context): File = File(File(context.filesDir, "logs"), "app.log")

    fun log(tag: String, message: String) {
        Log.d(TAG, "[$tag] $message")
        val dir = logDir ?: return
        synchronized(lock) {
            runCatching {
                if (!dir.exists()) dir.mkdirs()
                val file = File(dir, "app.log")
                rotateIfNeeded(file)
                val line = "${dateFormat.format(Date())} [$tag] $message\n"
                file.appendText(line)
            }
        }
    }

    private fun rotateIfNeeded(file: File) {
        if (file.exists() && file.length() > MAX_BYTES) {
            val backup = File(file.parentFile, "app.log.1")
            if (backup.exists()) backup.delete()
            file.renameTo(backup)
        }
    }
}
