package top.aico.wxcacheviewer.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UinHashTest {

    /** 目录名 = md5("mm" + uin)，期望值用示例 uin 固定下来防止实现被改动。 */
    @Test
    fun derivesDirectoryNameFromUin() {
        assertEquals("ffdef473a3a56cbf6d38456a41f52f46", UinHash.of("1000000001"))
    }

    @Test
    fun trimsWhitespaceAndKeepsLeadingZeros() {
        assertEquals(UinHash.of("1000000001"), UinHash.of(" 1000000001\n"))
        assertNotEquals(UinHash.of("1000000001"), UinHash.of("01000000001"))
    }

    @Test
    fun rejectsInvalidUin() {
        assertNull(UinHash.of(""))
        assertNull(UinHash.of("wxid_example00000000"))
        assertNull(UinHash.of("1000000001;rm -rf /"))
        assertNull(UinHash.of("1".repeat(21)))
    }
}
