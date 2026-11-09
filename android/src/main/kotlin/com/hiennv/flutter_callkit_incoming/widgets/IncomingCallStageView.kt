package com.hiennv.flutter_callkit_incoming.widgets

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import android.view.animation.LinearInterpolator
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import com.hiennv.flutter_callkit_incoming.IncomingScreenSpec
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/**
 * The centre of the ringing screen: the caller's round avatar with pulse rings
 * spreading out of its edge — the native twin of the app's `PersonStage`
 * (ringing mode). Geometry and motion come from [IncomingScreenSpec]:
 *
 * * avatar diameter = shortest side / `ringSpread`, clamped to
 *   `avatarMin…avatarMax`; the rings travel from the avatar's edge to
 *   `diameter × ringSpread / 2`;
 * * `pulseRings` rings, evenly phased over `pulsePeriodMs`, radius eased out
 *   (the `Curves.easeOut` cubic), alpha `peak · sin(πp) · (1 − p)`, stroke thinning
 *   from `pulseStrokeStart` by `pulseStrokeFade`;
 * * reduced motion (animator scale 0): no rings — one static hairline ring.
 *
 * The avatar itself is the single child, centred and sized by this view; its
 * shadow is rasterised once per size (a blur mask on every frame would repaint
 * the most expensive thing on screen 60 times a second).
 */
class IncomingCallStageView(
    context: Context,
    private val spec: IncomingScreenSpec,
    private val reducedMotion: Boolean,
) : FrameLayout(context) {

    private val density = resources.displayMetrics.density
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val easeOut = PathInterpolator(0f, 0f, 0.58f, 1f)
    private var shadow: Bitmap? = null
    private var progress = 0f

    /** Avatar diameter in px, decided in [onMeasure]. */
    var avatarDiameterPx = 0
        private set

    private val animator: ValueAnimator? = if (reducedMotion) null else
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = spec.pulsePeriodMs
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                progress = it.animatedValue as Float
                invalidate()
            }
        }

    init {
        setWillNotDraw(false)
        clipChildren = false
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        val shortest = min(w, h) / density
        val base = (shortest / spec.ringSpread).coerceIn(spec.avatarMin, spec.avatarMax)
        avatarDiameterPx = (base * density).toInt()
        val childSpec = MeasureSpec.makeMeasureSpec(avatarDiameterPx, MeasureSpec.EXACTLY)
        for (i in 0 until childCount) getChildAt(i).measure(childSpec, childSpec)
        setMeasuredDimension(w, h)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val cx = (right - left) / 2
        val cy = (bottom - top) / 2
        val r = avatarDiameterPx / 2
        for (i in 0 until childCount) getChildAt(i).layout(cx - r, cy - r, cx + r, cy + r)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        shadow?.recycle()
        shadow = buildShadow()
    }

    /** The avatar's soft drop shadow (`avatarShadow`, `avatarShadowBlur`). */
    private fun buildShadow(): Bitmap? {
        if (avatarDiameterPx <= 0) return null
        // Flutter's blurRadius maps to a Gaussian sigma of ≈ radius · 0.577 + 0.5.
        val sigma = (spec.avatarShadowBlur * 0.57735f + 0.5f) * density
        val pad = (sigma * 3).toInt()
        val size = avatarDiameterPx + pad * 2
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = spec.avatarShadow
            maskFilter = BlurMaskFilter(sigma, BlurMaskFilter.Blur.NORMAL)
        }
        // spreadRadius 2 in the app.
        Canvas(bitmap).drawCircle(size / 2f, size / 2f, avatarDiameterPx / 2f + 2 * density, paint)
        return bitmap
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        animator?.start()
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        shadow?.recycle()
        shadow = null
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val avatarRadius = avatarDiameterPx / 2f
        if (reducedMotion) {
            ringPaint.color = spec.staticRing
            ringPaint.strokeWidth = spec.staticRingWidth * density
            canvas.drawCircle(cx, cy, avatarRadius * spec.staticRingScale, ringPaint)
        } else {
            val maxRadius = avatarRadius * spec.ringSpread
            val travel = maxRadius - avatarRadius
            if (travel > 0) {
                for (i in 0 until spec.pulseRings) {
                    val p = (progress + i.toFloat() / spec.pulseRings) % 1f
                    val radius = avatarRadius + travel * easeOut.getInterpolation(p)
                    val alpha = spec.pulsePeakAlpha *
                        sin(PI * p).toFloat().coerceIn(0f, 1f) * (1 - p)
                    ringPaint.color = Color.argb((alpha * 255).toInt(), 255, 255, 255)
                    ringPaint.strokeWidth =
                        (spec.pulseStrokeStart - spec.pulseStrokeFade * p) * density
                    canvas.drawCircle(cx, cy, radius, ringPaint)
                }
            }
        }
        shadow?.let { canvas.drawBitmap(it, cx - it.width / 2f, cy - it.height / 2f, null) }
    }

    /** The avatar sits above the shadow and rings (children draw after [onDraw]). */
    fun setAvatar(view: View) {
        removeAllViews()
        addView(view)
    }
}
