package top.aico.wxcacheviewer.domain

import top.aico.wxcacheviewer.domain.model.CacheFile
import top.aico.wxcacheviewer.domain.model.SortDirection
import top.aico.wxcacheviewer.domain.model.SortField
import top.aico.wxcacheviewer.domain.model.SortOption

object CacheFileSorter {

    /**
     * 按排序选项对缓存文件排序。取值相同时统一按文件名升序，保证结果稳定。
     */
    fun sort(files: List<CacheFile>, option: SortOption): List<CacheFile> {
        val byField = when (option.field) {
            SortField.SIZE -> compareBy<CacheFile> { it.size }
            SortField.MODIFIED_TIME -> compareBy<CacheFile> { it.modifiedAt }
        }
        val ordered = if (option.direction == SortDirection.DESC) byField.reversed() else byField
        return files.sortedWith(ordered.thenBy { it.name })
    }
}
