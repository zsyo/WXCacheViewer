package top.aico.wxcacheviewer.data.root

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.TimeUnit

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

class RootCommandExecutor {
    suspend fun run(command: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS): ShellResult =
        withContext(Dispatchers.IO) {
            val process = try {
                ProcessBuilder("su", "-c", command).start()
            } catch (e: IOException) {
                return@withContext ShellResult("", -127, e.message ?: "su unavailable")
            }

            val stdoutDeferred = async { process.inputStream.bufferedReader().use { it.readText() } }
            val stderrDeferred = async { process.errorStream.bufferedReader().use { it.readText() } }

            val finished = try {
                runInterruptible { process.waitFor(timeoutMs, TimeUnit.MILLISECONDS) }
            } catch (e: InterruptedException) {
                process.destroyForcibly()
                return@withContext ShellResult("", -1, e.message ?: "interrupted")
            }

            if (!finished) {
                process.destroyForcibly()
                return@withContext ShellResult("", -1, "command timed out", timedOut = true)
            }

            ShellResult(stdoutDeferred.await(), process.exitValue(), stderrDeferred.await())
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

    companion object {
        const val DEFAULT_TIMEOUT_MS = 10_000L
    }
}
