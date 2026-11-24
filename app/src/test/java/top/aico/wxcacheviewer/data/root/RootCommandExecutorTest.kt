package top.aico.wxcacheviewer.data.root

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 回归测试：没授权时探测出的「DIRECT」不能缓存。
 *
 * 真机上出现过的现象——用户在 root 管理器里点允许后回来点「重新检测」，看到「未发现微信账号」，
 * 必须杀掉应用重开才好。原因是首次探测在 su 被拒时把 DIRECT 当结论缓存了，
 * 重试时仍用 `su -c`，继承应用被裁剪的命名空间，读不到微信私有目录。
 */
class RootCommandExecutorTest {

    /**
     * 模拟一台 Android 17 设备：`su -c` 下 /data/user/0 被裁剪（只能看到极少目录），
     * 只有 `su -mm` 能拿到完整视图。
     */
    private class FakeShell : RootCommandExecutor() {
        var granted = false
        val invocations = mutableListOf<List<String>>()

        override suspend fun execute(argv: List<String>, timeoutMs: Long): ShellResult {
            invocations += argv
            if (!granted) return ShellResult("", 1, "su: permission denied")
            val probe = argv.last().contains("visible=")
            return when {
                !probe -> ShellResult("0", 0, "")
                argv.contains("-mm") -> ShellResult("visible=180 packages=180", 0, "")
                else -> ShellResult("visible=3 packages=180", 0, "")
            }
        }
    }

    @Test
    fun deniedProbeIsNotCachedSoRetryUpgradesAfterGrant() = runBlocking {
        val shell = FakeShell()
        shell.granted = false

        // 未授权：拿不到结论，本次只能用 DIRECT 硬试，但绝不能留在缓存里
        assertEquals(NamespaceMode.DIRECT, shell.currentMode())

        // 用户在 root 管理器点允许后回来重试，同一实例、不重启进程
        shell.granted = true
        assertEquals(NamespaceMode.MOUNT_MASTER, shell.currentMode())
    }

    @Test
    fun checkRootReflectsNewGrantWithoutRestart() = runBlocking {
        val shell = FakeShell()
        assertEquals(RootState.Unavailable(RootState.Reason.DENIED), shell.checkRoot())

        shell.granted = true
        assertEquals(RootState.Available, shell.checkRoot())
        assertEquals(NamespaceMode.MOUNT_MASTER, shell.currentMode())
    }

    @Test
    fun resolvedModeIsReusedUntilInvalidated() = runBlocking {
        val shell = FakeShell().apply { granted = true }
        assertEquals(NamespaceMode.MOUNT_MASTER, shell.currentMode())
        val afterFirst = shell.invocations.size

        assertEquals(NamespaceMode.MOUNT_MASTER, shell.currentMode())
        assertEquals("已确定的模式不该重复探测", afterFirst, shell.invocations.size)

        shell.invalidateMode()
        assertEquals(NamespaceMode.MOUNT_MASTER, shell.currentMode())
        assertTrue("失效后必须重新探测", shell.invocations.size > afterFirst)
    }

    @Test
    fun verifyRootReusesResolvedModeAndSeesRevokedGrant() = runBlocking {
        val shell = FakeShell().apply { granted = true }
        assertEquals(RootState.Available, shell.checkRoot())
        val afterCheck = shell.invocations.size

        // 撤销授权后回到前台：轻量复检只跑一次 su，不重走命名空间探测
        shell.granted = false
        assertEquals(RootState.Unavailable(RootState.Reason.DENIED), shell.verifyRoot())
        assertEquals("复检不该重探命名空间", afterCheck + 1, shell.invocations.size)

        shell.granted = true
        assertEquals(RootState.Available, shell.verifyRoot())
        assertEquals(afterCheck + 2, shell.invocations.size)
    }

    @Test
    fun missingSuReportsNoSuInsteadOfDenied() = runBlocking {
        val shell = object : RootCommandExecutor() {
            override suspend fun execute(argv: List<String>, timeoutMs: Long): ShellResult =
                ShellResult("", -127, "Cannot run program \"su\"")
        }
        assertEquals(RootState.Unavailable(RootState.Reason.NO_SU), shell.checkRoot())
    }
}
