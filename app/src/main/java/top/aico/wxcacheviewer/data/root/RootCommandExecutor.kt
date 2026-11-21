package top.aico.wxcacheviewer.data.root

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import top.aico.wxcacheviewer.data.log.AppLog
import java.io.IOException

data class ShellResult(
    val stdout: String,
    val exitCode: Int,
    val stderr: String,
    val timedOut: Boolean = false
)

sealed interface RootState {
    data object Available : RootState
    data class Unavailable(val reason: Reason) : RootState

    enum class Reason { NO_SU, DENIED, TIMEOUT, UNKNOWN }
}

/**
 * su 命令所在的挂载命名空间。Android 17 起，应用进程的命名空间只保留少量目录的可见性，
 * 直接执行 su 看到的 /data/user/0 是被裁剪过的，必须提升到全局命名空间才能读到其他应用的数据。
 */
enum class NamespaceMode {
    /** su -c，直接继承应用命名空间。 */
    DIRECT,

    /** su -mm -c，Magisk / KernelSU 的 mount master 全局命名空间。 */
    MOUNT_MASTER,

    /** su -c "nsenter -t 1 -m -- sh -c ..."，进入 init 的全局命名空间。 */
    NSENTER
}

open class RootCommandExecutor {

    @Volatile
    private var mode: NamespaceMode? = null

    open suspend fun run(command: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS): ShellResult {
        val resolved = mode ?: resolveMode().also { mode = it }
        return when (resolved) {
            NamespaceMode.DIRECT -> execute(listOf("su", "-c", command), timeoutMs)
            NamespaceMode.MOUNT_MASTER -> execute(listOf("su", "-mm", "-c", command), timeoutMs)
            NamespaceMode.NSENTER -> execute(
                listOf("su", "-c", "nsenter -t 1 -m -- sh -c " + WxPaths.shellQuote(command)),
                timeoutMs
            )
        }
    }

    suspend fun checkRoot(): RootState {
        val result = run("id -u")
        if (result.exitCode == -127) return RootState.Unavailable(RootState.Reason.NO_SU)
        if (result.timedOut) return RootState.Unavailable(RootState.Reason.TIMEOUT)
        if (result.exitCode != 0) return RootState.Unavailable(RootState.Reason.DENIED)
        return if (result.stdout.trim() == "0") {
            RootState.Available
        } else {
            RootState.Unavailable(RootState.Reason.DENIED)
        }
    }

    suspend fun currentMode(): NamespaceMode = mode ?: resolveMode().also { mode = it }

    private suspend fun resolveMode(): NamespaceMode {
        val direct = execute(listOf("su", "-c", PROBE_COMMAND), PROBE_TIMEOUT_MS)
        if (direct.exitCode == -127) {
            AppLog.log("root", "su not found, keep DIRECT")
            return NamespaceMode.DIRECT
        }
        if (isNamespaceComplete(direct.stdout)) {
            AppLog.log("root", "mode=DIRECT ${direct.stdout.trim()}")
            return NamespaceMode.DIRECT
        }
        AppLog.log("root", "DIRECT filtered: ${direct.stdout.trim().replace("\n", " ")}")

        val mountMaster = execute(listOf("su", "-mm", "-c", PROBE_COMMAND), PROBE_TIMEOUT_MS)
        if (isNamespaceComplete(mountMaster.stdout)) {
            AppLog.log("root", "mode=MOUNT_MASTER ${mountMaster.stdout.trim()}")
            return NamespaceMode.MOUNT_MASTER
        }
        AppLog.log("root", "MOUNT_MASTER failed: exit=${mountMaster.exitCode} " +
            "${mountMaster.stderr.take(200).replace("\n", " ")}")

        val nsenter = execute(
            listOf("su", "-c", "nsenter -t 1 -m -- sh -c " + WxPaths.shellQuote(PROBE_COMMAND)),
            PROBE_TIMEOUT_MS
        )
        if (isNamespaceComplete(nsenter.stdout)) {
            AppLog.log("root", "mode=NSENTER ${nsenter.stdout.trim()}")
            return NamespaceMode.NSENTER
        }
        AppLog.log("root", "NSENTER failed: exit=${nsenter.exitCode} " +
            "${nsenter.stderr.take(200).replace("\n", " ")}")
        return NamespaceMode.DIRECT
    }

    /**
     * 用 /data/user/0 的可见目录数和已安装包数比较，判断当前命名空间是否被裁剪。
     */
    private fun isNamespaceComplete(stdout: String): Boolean {
        val visible = Regex("visible=(\\d+)").find(stdout)?.groupValues?.get(1)?.toIntOrNull() ?: return false
        val packages = Regex("packages=(\\d+)").find(stdout)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val expected = if (packages > 0) packages else MIN_PACKAGE_COUNT
        return visible >= expected * 0.8
    }

    private suspend fun execute(argv: List<String>, timeoutMs: Long): ShellResult =
        withContext(Dispatchers.IO) {
            AppLog.log("shell", "run[${argv.joinToString(" ").take(120)}]: last=${argv.last().take(300).replace("\n", " ⏎ ")}")
            val process = try {
                ProcessBuilder(argv).start()
            } catch (e: IOException) {
                AppLog.log("shell", "start failed: ${e.message}")
                return@withContext ShellResult("", -127, e.message ?: "su unavailable")
            }

            val stdoutDeferred = async { process.inputStream.bufferedReader().use { it.readText() } }
            val stderrDeferred = async { process.errorStream.bufferedReader().use { it.readText() } }

            val finished = withTimeoutOrNull(timeoutMs) {
                runInterruptible { process.waitFor() }
            }

            if (finished == null) {
                process.destroy()
                AppLog.log("shell", "timeout after ${timeoutMs}ms")
                return@withContext ShellResult("", -1, "command timed out", timedOut = true)
            }

            val result = ShellResult(stdoutDeferred.await(), process.exitValue(), stderrDeferred.await())
            AppLog.log(
                "shell",
                "exit=${result.exitCode} out=${result.stdout.take(600).replace("\n", " ⏎ ")} " +
                    "err=${result.stderr.take(300).replace("\n", " ⏎ ")}"
            )
            result
        }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 10_000L
        private const val PROBE_TIMEOUT_MS = 15_000L
        private const val MIN_PACKAGE_COUNT = 30

        private const val PROBE_COMMAND =
            "echo visible=\$(ls -1 /data/user/0 2>/dev/null | wc -l) " +
                "packages=\$(pm list packages 2>/dev/null | wc -l)"
    }
}
