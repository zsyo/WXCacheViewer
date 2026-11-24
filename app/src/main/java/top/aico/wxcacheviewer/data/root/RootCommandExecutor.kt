package top.aico.wxcacheviewer.data.root

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import top.aico.wxcacheviewer.data.log.AppLog
import java.io.IOException
import java.io.InputStream

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

    /** 命名空间探测要跑三次 su，并发首次调用会各探一遍，这里保证只探一次。 */
    private val modeLock = Mutex()

    open suspend fun run(command: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS): ShellResult {
        val resolved = currentMode()
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
        // 用户点「重新检测」通常就是刚从 root 管理器授权回来，命名空间必须重探：
        // 上次没权限时探到的 DIRECT 是「探不出来」，不是「这台设备只能用 DIRECT」。
        invalidateMode()
        return probeRoot()
    }

    /**
     * 轻量复检：沿用已确定的执行方式问一次 su 还给不给用，不重探命名空间。
     *
     * 用于回到前台时发现「授权被撤销」——设备与命名空间都没变，重探只是白跑几次 su。
     * 还没探过（首次调用）时 [currentMode] 仍会走完整探测。
     */
    suspend fun verifyRoot(): RootState = probeRoot()

    private suspend fun probeRoot(): RootState {
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

    /** 丢掉探测结果，下次执行重新探测。授权状态变化后必须调用。 */
    fun invalidateMode() {
        mode = null
    }

    suspend fun currentMode(): NamespaceMode {
        mode?.let { return it }
        return modeLock.withLock {
            mode ?: resolveMode()?.also { mode = it } ?: NamespaceMode.DIRECT
        }
    }

    /**
     * 探测该用哪种方式执行 su。
     *
     * 返回 null 表示**这次探测没有结论**：su 不存在、授权被拒绝、等待授权超时。
     * 这种结果绝不能缓存——用户去 root 管理器点一下允许再回来，同一个进程里重探就能
     * 拿到完整命名空间；缓存下来会一直走裁剪过的视图，表现为「重试后提示未发现账号，
     * 必须杀掉应用重开才正常」。
     */
    private suspend fun resolveMode(): NamespaceMode? {
        val direct = execute(listOf("su", "-c", PROBE_COMMAND), PROBE_TIMEOUT_MS)
        if (direct.exitCode == -127) {
            AppLog.log("root", "su not found, mode unresolved")
            return null
        }
        if (direct.timedOut) {
            AppLog.log("root", "su probe timed out, mode unresolved")
            return null
        }
        if (!direct.stdout.contains(PROBE_MARK)) {
            // su 跑了却没给探针输出：授权被拒时 exit≠0，再试 -mm 和 nsenter 同样是
            // 白等一轮授权/超时，没有信息量。exit=0 时按设备只能 DIRECT 处理。
            AppLog.log(
                "root",
                "su probe failed: exit=${direct.exitCode} " +
                    direct.stderr.take(120).replace("\n", " ")
            )
            return if (direct.exitCode == 0) NamespaceMode.DIRECT else null
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

    /**
     * 执行一条命令并收集输出。
     *
     * stdout/stderr 由独立线程边读边写进缓冲，而不是等进程结束后一次性 `readText()`：
     * 命令超时会被 `destroy()` 掉，管道随即关闭，正在阻塞的读取必然抛
     * “read interrupted by close() on another thread”。旧实现把这个异常漏给了调用方，
     * 于是界面显示一条看不懂的 Java 消息；现在读取异常只记日志，超时则带上已读到的部分输出。
     *
     * 单测覆写它来模拟不同授权状态，不需要真 su。
     */
    protected open suspend fun execute(argv: List<String>, timeoutMs: Long): ShellResult =
        withContext(Dispatchers.IO) {
            AppLog.log("shell", "run[${argv.joinToString(" ").take(120)}]: last=${argv.last().take(300).replace("\n", " ⏎ ")}")
            val process = try {
                ProcessBuilder(argv).start()
            } catch (e: IOException) {
                AppLog.log("shell", "start failed: ${e.message}")
                return@withContext ShellResult("", -127, e.message ?: "su unavailable")
            }

            val stdout = StringBuffer()
            val stderr = StringBuffer()
            val outThread = startReader(process.inputStream, stdout, "stdout")
            val errThread = startReader(process.errorStream, stderr, "stderr")

            // 外层协程取消（切账号、退出页面）会抛 CancellationException 穿透 withTimeoutOrNull，
            // 若只在超时分支清理，su 子进程会残留。用 finally 保证任何路径都回收进程与管道。
            try {
                val finished = withTimeoutOrNull(timeoutMs) {
                    runInterruptible { process.waitFor() }
                }

                if (finished == null) {
                    // 先终止进程并等读取线程收尾，再取已读到的部分输出；finally 里的清理是兜底。
                    // destroy() 是 API 1 方法；isAlive/destroyForcibly 要 API 26，minSdk 24 用不了。
                    process.destroy()
                    closeQuietly(process.inputStream)
                    closeQuietly(process.errorStream)
                    outThread.join(READER_DRAIN_TIMEOUT_MS)
                    errThread.join(READER_DRAIN_TIMEOUT_MS)
                    AppLog.log("shell", "timeout after ${timeoutMs}ms, kept=${stdout.length}B")
                    return@withContext ShellResult(
                        stdout.toString(),
                        -1,
                        TIMEOUT_MESSAGE,
                        timedOut = true
                    )
                }

                outThread.join()
                errThread.join()
                val result = ShellResult(stdout.toString(), process.exitValue(), stderr.toString())
                AppLog.log(
                    "shell",
                    "exit=${result.exitCode} out=${result.stdout.take(600).replace("\n", " ⏎ ")} " +
                        "err=${result.stderr.take(300).replace("\n", " ⏎ ")}"
                )
                result
            } finally {
                // 正常结束时进程已退出，destroy() 对已退出进程无害；取消/异常时靠它回收。
                process.destroy()
                closeQuietly(process.inputStream)
                closeQuietly(process.errorStream)
                // 超时/取消时读取线程可能仍阻塞在已关闭的管道上，短暂等待后返回，
                // StringBuffer 的写入是同步的，已收到的输出不会丢。
                if (outThread.isAlive) outThread.join(READER_DRAIN_TIMEOUT_MS)
                if (errThread.isAlive) errThread.join(READER_DRAIN_TIMEOUT_MS)
            }
        }

    private fun startReader(stream: InputStream, sink: StringBuffer, label: String): Thread =
        Thread {
            try {
                stream.bufferedReader().use { reader ->
                    while (true) {
                        val line = reader.readLine() ?: break
                        sink.append(line).append('\n')
                    }
                }
            } catch (e: IOException) {
                // 进程被 destroy() 后管道关闭，读取中断属预期情况，不能影响调用方。
                AppLog.log("shell", "$label read stopped: ${e.message}")
            }
        }.apply {
            name = "shell-$label"
            isDaemon = true
            start()
        }

    private fun closeQuietly(stream: InputStream) {
        runCatching { stream.close() }
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 10_000L
        private const val PROBE_TIMEOUT_MS = 15_000L
        private const val MIN_PACKAGE_COUNT = 30

        /** 关闭管道后等待读取线程收尾的时间，超时就不再等，避免卡住调用方。 */
        private const val READER_DRAIN_TIMEOUT_MS = 1_000L
        private const val TIMEOUT_MESSAGE = "命令超时"

        /** 探针成功时的输出标记；没有它就说明 su 根本没执行命令。 */
        private const val PROBE_MARK = "visible="

        private const val PROBE_COMMAND =
            "echo visible=\$(ls -1 /data/user/0 2>/dev/null | wc -l) " +
                "packages=\$(pm list packages 2>/dev/null | wc -l)"
    }
}
