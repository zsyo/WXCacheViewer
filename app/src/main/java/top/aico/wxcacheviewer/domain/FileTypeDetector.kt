package top.aico.wxcacheviewer.domain

import top.aico.wxcacheviewer.domain.model.FileType

object FileTypeDetector {
    fun detect(headerHex: String): FileType {
        val cleaned = headerHex.filter { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }
        val byteCount = cleaned.length / 2
        if (byteCount < 4) return FileType.UNKNOWN

        val bytes = ByteArray(byteCount) { index ->
            val high = Character.digit(cleaned[index * 2], 16)
            val low = Character.digit(cleaned[index * 2 + 1], 16)
            ((high shl 4) or low).toByte()
        }
        return detect(bytes)
    }

    fun detect(bytes: ByteArray): FileType {
        fun at(index: Int): Int = bytes[index].toInt() and 0xFF

        if (bytes.size >= 12) {
            if (at(4) == 0x66 && at(5) == 0x74 && at(6) == 0x79 && at(7) == 0x70) {
                return FileType.MP4
            }
            if (at(0) == 0x52 && at(1) == 0x49 && at(2) == 0x46 && at(3) == 0x46 &&
                at(8) == 0x57 && at(9) == 0x45 && at(10) == 0x42 && at(11) == 0x50
            ) {
                return FileType.WEBP
            }
        }
        if (bytes.size >= 3 && at(0) == 0xFF && at(1) == 0xD8 && at(2) == 0xFF) {
            return FileType.JPEG
        }
        if (bytes.size >= 8 &&
            at(0) == 0x89 && at(1) == 0x50 && at(2) == 0x4E && at(3) == 0x47 &&
            at(4) == 0x0D && at(5) == 0x0A && at(6) == 0x1A && at(7) == 0x0A
        ) {
            return FileType.PNG
        }
        if (bytes.size >= 6 &&
            at(0) == 0x47 && at(1) == 0x49 && at(2) == 0x46 &&
            ((at(3) == 0x38 && at(4) == 0x37 && at(5) == 0x61) ||
                (at(3) == 0x38 && at(4) == 0x39 && at(5) == 0x61))
        ) {
            return FileType.GIF
        }
        return FileType.UNKNOWN
    }
}
