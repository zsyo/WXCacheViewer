package top.aico.wxcacheviewer.data.root

import android.content.Context
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.aico.wxcacheviewer.data.log.AppLog
import java.io.File

/**
 * 账号发现失败时的现场取证。结果返回给界面，同时写入应用内 files/diagnostics.txt
 * 并通过 root 复制到 /sdcard/MT2/mcp/diagnostics.txt。
 */
object RootDiagnostics {

    private const val SHARED_FILE = "/sdcard/MT2/mcp/diagnostics.txt"

    suspend fun collect(
        context: Context,
        executor: RootCommandExecutor
    ): String = withContext(Dispatchers.IO) {
        val ownDir = context.filesDir.parentFile?.absolutePath ?: context.filesDir.absolutePath
        val report = StringBuilder()
        report.appendLine("sdk=${Build.VERSION.SDK_INT} release=${Build.VERSION.RELEASE} model=${Build.MODEL}")
        report.appendLine("abi=${Build.SUPPORTED_ABIS.firstOrNull()}")
        report.appendLine("namespaceMode=${executor.currentMode()}")
        report.appendLine("appSees /data=${File("/data").exists()} /data/user/0=${File("/data/user/0").exists()} " +
            "/data/user/0/com.tencent.mm=${File("/data/user/0/com.tencent.mm").exists()}")

        appendProbe(report, executor, "id", "id")
        appendProbe(report, executor, "grep", "echo abc123 | grep -E '^[a-z]+[0-9]+\$'")
        appendProbe(report, executor, "data-root", "ls -la /data 2>&1 | head -20")
        appendProbe(report, executor, "data-users", "ls -1 /data/user 2>&1")
        appendProbe(report, executor, "data-user-0", "ls -1 /data/user/0 2>&1 | head -20")
        appendProbe(report, executor, "data-data", "ls -1 /data/data 2>&1 | head -20")
        appendProbe(report, executor, "own-dir", "ls -la ${WxPaths.shellQuote(ownDir)} 2>&1 | head -12")
        appendProbe(
            report,
            executor,
            "tencent-dirs",
            "ls -d /data/user/*/com.tencent.mm /data/data/com.tencent.mm 2>&1"
        )
        appendProbe(report, executor, "pm-tencent", "pm list packages 2>&1 | grep -i tencent | head -10")
        appendProbe(
            report,
            executor,
            "dumpsys-dataDir",
            "dumpsys package com.tencent.mm 2>&1 | grep -m4 -E 'dataDir|userId=|codePath'"
        )
        appendProbe(report, executor, "mount-data", "mount 2>&1 | grep -E ' /data' | head -10")
        appendProbe(
            report,
            executor,
            "nsenter-global",
            "nsenter -t 1 -m -- ls -d /data/user/0/com.tencent.mm 2>&1"
        )
        appendProbe(
            report,
            executor,
            "su-mount-master",
            "su -mm -c 'ls -d /data/user/0/com.tencent.mm' 2>&1"
        )
        WxPaths.WX_BASES.forEach { base ->
            appendProbe(report, executor, "ls $base/cache", "ls -la ${WxPaths.shellQuote("$base/cache")} 2>&1 | head -20")
            appendProbe(report, executor, "find video $base", "find ${WxPaths.shellQuote("$base/cache")} -maxdepth 3 -type d -name video 2>&1 | head -20")
        }
        appendProbe(report, executor, "discovery-command", WxPaths.discoverAccountsCommand())

        val text = report.toString()
        publish(context, executor, text)
        AppLog.log("diag", "collected ${text.length} chars")
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
