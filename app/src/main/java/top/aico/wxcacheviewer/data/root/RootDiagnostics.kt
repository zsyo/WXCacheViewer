package top.aico.wxcacheviewer.data.root

import android.content.Context
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 账号发现失败时的现场取证。结果返回给界面，同时落盘：
 * 应用内 files/diagnostics.txt，并通过 root 复制到设备上便于取回的位置。
 */
object RootDiagnostics {

    private const val SHARED_FILE = "/sdcard/MT2/mcp/diagnostics.txt"

    suspend fun collect(
        context: Context,
        executor: RootCommandExecutor
    ): String = withContext(Dispatchers.IO) {
        val report = StringBuilder()
        report.appendLine("sdk=${Build.VERSION.SDK_INT} model=${Build.MODEL} abi=${Build.SUPPORTED_ABIS.firstOrNull()}")
        appendProbe(report, executor, "id", "id")
        appendProbe(report, executor, "su-path", "command -v su; echo \"PATH=\$PATH\"")
        WxPaths.WX_BASES.forEach { base ->
            appendProbe(report, executor, "ls $base/cache", "ls -la ${WxPaths.shellQuote("$base/cache")} 2>&1 | head -40")
            appendProbe(report, executor, "find video $base", "find ${WxPaths.shellQuote("$base/cache")} -maxdepth 3 -type d -name video 2>&1 | head -20")
            appendProbe(report, executor, "glob video $base", "ls -d ${WxPaths.shellQuote("$base/cache")}/*/finder/video 2>&1 | head -20")
        }
        appendProbe(report, executor, "discovery-command", WxPaths.discoverAccountsCommand())

        val text = report.toString()
        publish(context, executor, text)
        text
    }

    private suspend fun publish(context: Context, executor: RootCommandExecutor, text: String) {
        val local = File(context.filesDir, "diagnostics.txt")
        runCatching { local.writeText(text) }
        if (!local.exists()) return
        val command = "cp ${WxPaths.shellQuote(local.absolutePath)} " +
            "${WxPaths.shellQuote(SHARED_FILE)} && chmod 0644 ${WxPaths.shellQuote(SHARED_FILE)}"
        runCatching { executor.run(command) }
    }

    private suspend fun appendProbe(
        report: StringBuilder,
        executor: RootCommandExecutor,
        label: String,
        command: String
    ) {
        val result = executor.run(command)
        report.appendLine("== $label ==")
        report.appendLine("exit=${result.exitCode} timeout=${result.timedOut}")
        if (result.stdout.isNotBlank()) report.appendLine("stdout:\n${result.stdout.trimEnd()}")
        if (result.stderr.isNotBlank()) report.appendLine("stderr:\n${result.stderr.trimEnd()}")
    }
}
