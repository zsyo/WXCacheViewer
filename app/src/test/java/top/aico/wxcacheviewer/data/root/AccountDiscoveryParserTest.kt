package top.aico.wxcacheviewer.data.root

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountDiscoveryParserTest {

    private val hashA = "ffdef473a3a56cbf6d38456a41f52f46"
    private val hashB = "0d47c347baae6495d75394c75bdc1208"
    private val hashC = "ffffffffffffffffffffffffffffffff"

    @Test
    fun findsAccountWithVideoCache() {
        val output = "/data/data/com.tencent.mm/cache/$hashA/finder/video\n"
        val accounts = AccountDiscoveryParser.parse(output)

        assertEquals(1, accounts.size)
        assertEquals(hashA, accounts[0].hash)
        assertTrue(accounts[0].hasVideoCache)
    }

    @Test
    fun mergesCacheDirectoriesIdentityRecordsAndVideoPaths() {
        val output = """
            /data/data/com.tencent.mm/cache
            /data/data/com.tencent.mm/cache/$hashA
            /data/data/com.tencent.mm/cache/$hashA/finder/video
            /data/data/com.tencent.mm/cache/Crash Reports
            /data/data/com.tencent.mm/files/identity_records/$hashA
            /data/data/com.tencent.mm/files/identity_records/$hashB
            /data/user/0/com.tencent.mm/cache/$hashC/finder/video
        """.trimIndent()

        val accounts = AccountDiscoveryParser.parse(output)

        assertEquals(3, accounts.size)
        assertEquals(listOf(hashA, hashC, hashB), accounts.map { it.hash })
        assertEquals(listOf(true, true, false), accounts.map { it.hasVideoCache })
    }

    @Test
    fun ignoresUnrelatedEntriesAndDuplicates() {
        val output = """
            /data/user/0/com.tencent.mm/cache/ffdef473a3a56cbf6d38456a41f52f46/finder/video
            /data/data/com.tencent.mm/cache/ffdef473a3a56cbf6d38456a41f52f46/finder/video
            /data/data/com.tencent.mm/files/identity_records/ffdef473a3a56cbf6d38456a41f52f46
            /data/data/com.tencent.mm/cache/QQMusic
            /data/data/com.tencent.mm/cache/finder_video_xWT101_0651b1fbc2d34e5f8a6b7c8d9e0f1e19
        """.trimIndent()

        val accounts = AccountDiscoveryParser.parse(output)

        assertEquals(1, accounts.size)
        assertEquals(hashA, accounts.single().hash)
        assertTrue(accounts.single().hasVideoCache)
    }

    @Test
    fun returnsEmptyForBlankOutput() {
        assertTrue(AccountDiscoveryParser.parse("").isEmpty())
    }

    @Test
    fun videoPathWithInvalidHashIsIgnored() {
        val accounts = AccountDiscoveryParser.parse("/data/data/com.tencent.mm/cache/notahash/finder/video")
        assertTrue(accounts.isEmpty())
        assertFalse(accounts.any { it.hasVideoCache })
    }
}
