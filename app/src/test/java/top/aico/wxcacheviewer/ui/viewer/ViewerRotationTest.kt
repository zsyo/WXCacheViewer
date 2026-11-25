package top.aico.wxcacheviewer.ui.viewer

import org.junit.Assert.assertEquals
import org.junit.Test

class ViewerRotationTest {

    @Test
    fun eachTapTurns90DegreesClockwise() {
        assertEquals(90, nextRotation(0))
        assertEquals(180, nextRotation(90))
        assertEquals(270, nextRotation(180))
    }

    @Test
    fun fullTurnWrapsBackToZero() {
        assertEquals(0, nextRotation(270))
    }

    @Test
    fun fourTapsReturnToOriginalAngle() {
        var angle = 0
        repeat(4) { angle = nextRotation(angle) }
        assertEquals(0, angle)
    }

    @Test
    fun angleOutsideRangeIsNormalisedFirst() {
        // 已经转过一圈再点，仍然只前进 90 度而不是越界
        assertEquals(90, nextRotation(360))
        assertEquals(180, nextRotation(450))
        // 负数按取模归一：-90 视作 270，再顺时针转 90 回到 0
        assertEquals(0, nextRotation(-90))
        assertEquals(270, nextRotation(-180))
    }
}
