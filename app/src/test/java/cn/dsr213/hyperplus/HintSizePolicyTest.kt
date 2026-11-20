// Lcmagi 自用分支修改于 2026-11-20，沿用上游 AGPL-3.0。
package cn.dsr213.hyperplus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HintSizePolicyTest {
    @Test fun panelSizesFollowCurrentDensity() {
        for (dpi in listOf(370, 400, 440)) {
            val cover = HintSizePolicy.visualDp(1168 * 160 / dpi)
            val inner = HintSizePolicy.visualDp(1672 * 160 / dpi)
            assertTrue(cover < inner)
        }
        assertEquals(48f, HintSizePolicy.visualDp(425), 0f)
        assertEquals(58f, HintSizePolicy.visualDp(608), 0f)
    }

    @Test fun narrowWindowsRetainTouchArea() {
        assertEquals(40f, HintSizePolicy.visualDp(200), 0f)
        for (width in 200..1000) {
            val visual = HintSizePolicy.visualDp(width)
            assertTrue(visual >= HintSizePolicy.visualDp(width - 1))
            assertTrue(HintSizePolicy.viewDp(visual) >= maxOf(48f, visual))
        }
    }
}
