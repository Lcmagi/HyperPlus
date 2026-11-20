// Lcmagi 自用分支修改于 2026-11-20，沿用上游 AGPL-3.0。
package cn.dsr213.hyperplus

/** 以小米 18 Fold 两块屏在 440dpi 时的宽度为锚点，按当前窗口短边平滑插值。 */
internal object HintSizePolicy {
    const val REF_SCREEN_WIDTH_DP = 608
    private const val COVER_WIDTH_DP = 425
    private const val COVER_DIAMETER_DP = 48f
    const val VISUAL_DP_MIN = 40
    const val VISUAL_DP_MAX = 58
    const val TOUCH_DP_MIN = 48

    fun visualDp(smallestWidthDp: Int): Float {
        val fraction = (smallestWidthDp.toFloat() - COVER_WIDTH_DP) / (REF_SCREEN_WIDTH_DP - COVER_WIDTH_DP)
        return (COVER_DIAMETER_DP + fraction * (VISUAL_DP_MAX - COVER_DIAMETER_DP))
            .coerceIn(VISUAL_DP_MIN.toFloat(), VISUAL_DP_MAX.toFloat())
    }

    fun viewDp(visualDp: Float): Float = maxOf(visualDp, TOUCH_DP_MIN.toFloat())
}
