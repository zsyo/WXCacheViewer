package top.aico.wxcacheviewer.ui.common

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/** 读取本应用自身的版本信息，关于页展示与检查更新比较都用它。 */
object AppInfo {

    /** 取不到时返回空串，调用方按「版本未知」处理。 */
    fun versionName(context: Context): String = runCatching {
        val packageManager = context.packageManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getPackageInfo(
                context.packageName,
                PackageManager.PackageInfoFlags.of(0)
            ).versionName.orEmpty()
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
        }
    }.getOrDefault("")
}
