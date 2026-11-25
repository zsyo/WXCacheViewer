package top.aico.wxcacheviewer.ui.viewer

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewerZoomTest {

    // 1080x2000 的竖屏视口，内容正好铺满，方便算边界
    private val viewport = IntSize(1080, 2000)
    private val center = Offset(540f, 1000f)

    private fun transform(
        oldScale: Float = 1f,
        oldOffset: Offset = Offset.Zero,
        zoom: Float = 1f,
        pan: Offset = Offset.Zero,
        centroid: Offset = center,
        contentWidth: Float = 1080f,
        contentHeight: Float = 2000f
    ): ZoomState = zoomTransform(
        oldScale = oldScale,
        oldOffset = oldOffset,
        zoom = zoom,
        pan = pan,
        centroid = centroid,
        viewport = viewport,
        contentWidth = contentWidth,
        contentHeight = contentHeight
    )

    private fun assertOffset(expectedX: Float, expectedY: Float, actual: Offset) {
        assertEquals(expectedX, actual.x, 0.01f)
        assertEquals(expectedY, actual.y, 0.01f)
    }

    @Test
    fun zoomingFromOriginalAroundCenterStaysCentered() {
        val next = transform(zoom = 2f)
        assertEquals(2f, next.scale, 0.001f)
        assertOffset(0f, 0f, next.offset)
    }

    @Test
    fun scaleIsCappedAtFourTimes() {
        val next = transform(oldScale = 2f, zoom = 10f, centroid = Offset.Zero)
        assertEquals(4f, next.scale, 0.001f)
    }

    @Test
    fun zoomingOutNeverGoesBelowOriginalSize() {
        val next = transform(
            oldScale = 2f,
            oldOffset = Offset(100f, 50f),
            zoom = 0.1f
        )
        assertEquals(1f, next.scale, 0.001f)
        assertOffset(0f, 0f, next.offset)
    }

    @Test
    fun dragIsClampedToTheEdgeOfTheContent() {
        // 放大 2 倍时内容 2160x4000，最多左右各拖 540、上下各拖 1000
        val next = transform(
            oldScale = 2f,
            pan = Offset(1000f, 5000f)
        )
        assertEquals(2f, next.scale, 0.001f)
        assertOffset(540f, 1000f, next.offset)
    }

    @Test
    fun contentSmallerThanViewportStaysCentered() {
        // 小图放大后仍比视口窄（1000 < 1080），水平方向不允许拖动
        val next = transform(
            zoom = 2f,
            pan = Offset(300f, 0f),
            contentWidth = 500f,
            contentHeight = 800f
        )
        assertOffset(0f, 0f, next.offset)
    }

    @Test
    fun zoomingAroundCornerPushesContentTowardThatCorner() {
        // 以左上角为焦点放大，内容要往右下移，让焦点下的画面留在原位
        val next = transform(zoom = 2f, centroid = Offset.Zero)
        assertOffset(540f, 1000f, next.offset)
    }

    @Test
    fun panningAtOriginalScaleDoesNothing() {
        val next = transform(pan = Offset(200f, 200f))
        assertEquals(1f, next.scale, 0.001f)
        assertOffset(0f, 0f, next.offset)
    }

    @Test
    fun liftFrameWithoutCentroidKeepsStateFinite() {
        // 抬手那一帧 Compose 取不到质心，返回 Offset.Unspecified（NaN）。
        // 如果不挡住，NaN * 0 会污染 offset，图片整个不绘制（真机上的「松手就变黑」）。
        val next = transform(
            oldScale = 2f,
            oldOffset = Offset(120f, 340f),
            centroid = Offset.Unspecified
        )
        assertEquals(2f, next.scale, 0.001f)
        assertOffset(120f, 340f, next.offset)
    }

    @Test
    fun nonFinitePanIsIgnored() {
        val next = transform(
            oldScale = 2f,
            oldOffset = Offset(100f, 0f),
            pan = Offset(Float.NaN, 0f)
        )
        assertEquals(2f, next.scale, 0.001f)
        assertOffset(100f, 0f, next.offset)
    }

    @Test
    fun resultIsAlwaysFinite() {
        val next = transform(zoom = 2f, pan = Offset(300f, 900f), centroid = Offset.Unspecified)
        assertEquals(2f, next.scale, 0.001f)
        assertTrue(next.offset.x.isFinite())
        assertTrue(next.offset.y.isFinite())
    }
}
