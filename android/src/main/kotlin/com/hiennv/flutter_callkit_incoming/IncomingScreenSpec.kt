package com.hiennv.flutter_callkit_incoming

import org.json.JSONArray
import org.json.JSONObject

/**
 * The look of the full-screen ringing activity, sent by the host app with every
 * call (`AndroidParams.incomingScreen`, JSON).
 *
 * WHY: an app whose in-app ringing screen is drawn by Flutter shows the same call
 * on two screens — its own while it is open, this activity while it is closed,
 * in the background or behind the lock screen. Native calling apps (WhatsApp,
 * Telegram, Signal) have one screen for both. Keeping two implementations equal
 * only works when the design values have ONE source: the host app owns them and
 * sends them here, so no colour, size or timing is hard-coded on this side.
 *
 * Absent or unreadable → [parse] returns null and the activity keeps the
 * package's original look (other apps using this fork are unaffected).
 *
 * Sizes are logical pixels (dp), durations milliseconds, colours ARGB integers.
 */
class IncomingScreenSpec(
    val caption: String,
    val status: String,
    val message: String,
    val isVideo: Boolean,
    val gradientColors: IntArray,
    val gradientStops: FloatArray,
    val shadeColors: IntArray,
    val shadeStops: FloatArray,
    val blurSigma: Float,
    val accept: Int,
    val decline: Int,
    val glass: Int,
    val textPrimary: Int,
    val textSecondary: Int,
    val avatarFallback: Int,
    val avatarShadow: Int,
    val avatarShadowBlur: Float,
    val staticRing: Int,
    val staticRingScale: Float,
    val staticRingWidth: Float,
    val answerButton: Float,
    val secondaryButton: Float,
    val iconScale: Float,
    val avatarMin: Float,
    val avatarMax: Float,
    val ringSpread: Float,
    val answerRowInset: Float,
    val pulsePeriodMs: Long,
    val pulseRings: Int,
    val pulsePeakAlpha: Float,
    val pulseStrokeStart: Float,
    val pulseStrokeFade: Float,
    val nudgePeriodMs: Long,
    val nudgeActivePart: Float,
    val nudgeAmplitude: Float,
    val nudgeOscillations: Int,
) {
    /** The message button exists only when the app gave it a label. */
    val hasMessage: Boolean get() = message.isNotEmpty()

    companion object {
        /** The contract version this activity understands. */
        private const val VERSION = 1

        fun parse(json: String?): IncomingScreenSpec? {
            if (json.isNullOrEmpty()) return null
            return try {
                val o = JSONObject(json)
                if (o.optInt("version") != VERSION) return null
                IncomingScreenSpec(
                    caption = o.getString("caption"),
                    status = o.getString("status"),
                    message = o.optString("message", ""),
                    isVideo = o.optBoolean("isVideo", false),
                    gradientColors = o.getJSONArray("gradientColors").toIntArray(),
                    gradientStops = o.getJSONArray("gradientStops").toFloatArray(),
                    shadeColors = o.getJSONArray("shadeColors").toIntArray(),
                    shadeStops = o.getJSONArray("shadeStops").toFloatArray(),
                    blurSigma = o.float("blurSigma"),
                    accept = o.color("accept"),
                    decline = o.color("decline"),
                    glass = o.color("glass"),
                    textPrimary = o.color("textPrimary"),
                    textSecondary = o.color("textSecondary"),
                    avatarFallback = o.color("avatarFallback"),
                    avatarShadow = o.color("avatarShadow"),
                    avatarShadowBlur = o.float("avatarShadowBlur"),
                    staticRing = o.color("staticRing"),
                    staticRingScale = o.float("staticRingScale"),
                    staticRingWidth = o.float("staticRingWidth"),
                    answerButton = o.float("answerButton"),
                    secondaryButton = o.float("secondaryButton"),
                    iconScale = o.float("iconScale"),
                    avatarMin = o.float("avatarMin"),
                    avatarMax = o.float("avatarMax"),
                    ringSpread = o.float("ringSpread"),
                    answerRowInset = o.float("answerRowInset"),
                    pulsePeriodMs = o.getLong("pulsePeriodMs"),
                    pulseRings = o.getInt("pulseRings"),
                    pulsePeakAlpha = o.float("pulsePeakAlpha"),
                    pulseStrokeStart = o.float("pulseStrokeStart"),
                    pulseStrokeFade = o.float("pulseStrokeFade"),
                    nudgePeriodMs = o.getLong("nudgePeriodMs"),
                    nudgeActivePart = o.float("nudgeActivePart"),
                    nudgeAmplitude = o.float("nudgeAmplitude"),
                    nudgeOscillations = o.getInt("nudgeOscillations"),
                ).takeIf {
                    it.gradientColors.size == it.gradientStops.size &&
                        it.shadeColors.size == it.shadeStops.size &&
                        it.gradientColors.size >= 2 &&
                        it.shadeColors.size >= 2
                }
            } catch (e: Exception) {
                // A malformed contract must never cost the user the call: the
                // activity falls back to the package's own look.
                android.util.Log.w("IncomingScreenSpec", "unreadable incoming screen spec", e)
                null
            }
        }

        private fun JSONObject.float(key: String): Float = getDouble(key).toFloat()

        /** ARGB integers arrive as JSON numbers that may exceed Int range (0xFF…). */
        private fun JSONObject.color(key: String): Int = getLong(key).toInt()

        private fun JSONArray.toIntArray(): IntArray =
            IntArray(length()) { getLong(it).toInt() }

        private fun JSONArray.toFloatArray(): FloatArray =
            FloatArray(length()) { getDouble(it).toFloat() }
    }
}
