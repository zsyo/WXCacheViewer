package top.aico.wxcacheviewer.domain

import org.junit.Assert.assertEquals
import org.junit.Test
import top.aico.wxcacheviewer.domain.model.CacheFile
import top.aico.wxcacheviewer.domain.model.FileType
import top.aico.wxcacheviewer.domain.model.SortDirection
import top.aico.wxcacheviewer.domain.model.SortField
import top.aico.wxcacheviewer.domain.model.SortOption

class CacheFileSorterTest {

    private val hash = "ffdef473a3a56cbf6d38456a41f52f46"

    private fun file(
        name: String,
        size: Long,
        modifiedAt: Long,
        sourceId: String = "finder_video",
        relativePath: String = name
    ) = CacheFile(hash, name, size, modifiedAt, FileType.MP4, relativePath, sourceId)

    private val files = listOf(
        file("finder_video__aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", 300, 1_700_000_100),
        file("finder_video__bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", 100, 1_700_000_300),
        file("finder_video__cccccccccccccccccccccccccccccccc", 200, 1_700_000_200)
    )

    @Test
    fun defaultOptionSortsByModifiedTimeDesc() {
        val sorted = CacheFileSorter.sort(files, SortOption())

        assertEquals(listOf(1_700_000_300L, 1_700_000_200L, 1_700_000_100L), sorted.map { it.modifiedAt })
    }

    @Test
    fun sortsByModifiedTimeAsc() {
        val sorted = CacheFileSorter.sort(
            files,
            SortOption(SortField.MODIFIED_TIME, SortDirection.ASC)
        )

        assertEquals(listOf(1_700_000_100L, 1_700_000_200L, 1_700_000_300L), sorted.map { it.modifiedAt })
    }

    @Test
    fun sortsBySizeDesc() {
        val sorted = CacheFileSorter.sort(files, SortOption(SortField.SIZE, SortDirection.DESC))

        assertEquals(listOf(300L, 200L, 100L), sorted.map { it.size })
    }

    @Test
    fun sortsBySizeAsc() {
        val sorted = CacheFileSorter.sort(files, SortOption(SortField.SIZE, SortDirection.ASC))

        assertEquals(listOf(100L, 200L, 300L), sorted.map { it.size })
    }

    @Test
    fun equalValuesFallBackToNameAscending() {
        val same = listOf(
            file("finder_video__dddddddddddddddddddddddddddddddd", 100, 1L),
            file("finder_video__cccccccccccccccccccccccccccccccc", 100, 1L)
        )

        val sorted = CacheFileSorter.sort(same, SortOption(SortField.SIZE, SortDirection.DESC))

        assertEquals(
            listOf(
                "finder_video__cccccccccccccccccccccccccccccccc",
                "finder_video__dddddddddddddddddddddddddddddddd"
            ),
            sorted.map { it.name }
        )
    }

    @Test
    fun equalValuesTieBreakAcrossSourcesByKey() {
        // 相同 relativePath、不同 sourceId 时按完整 key（源:路径）升序，结果稳定且跨源不抖动
        val same = listOf(
            file("same.bin", 100, 1L, sourceId = "sns_image"),
            file("same.bin", 100, 1L, sourceId = "chat_image")
        )

        val sorted = CacheFileSorter.sort(same, SortOption(SortField.SIZE, SortDirection.ASC))

        assertEquals(listOf("chat_image:same.bin", "sns_image:same.bin"), sorted.map { it.key })
    }
}
