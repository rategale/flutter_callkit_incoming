package com.hiennv.flutter_callkit_incoming.widgets

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.provider.Settings
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Space
import android.widget.TextView
import androidx.core.graphics.scale
import coil.bitmap.BitmapPool
import coil.request.ImageRequest
import coil.size.Size
import coil.transform.Transformation
import com.hiennv.flutter_callkit_incoming.ImageLoaderProvider
import com.hiennv.flutter_callkit_incoming.IncomingScreenSpec
import com.hiennv.flutter_callkit_incoming.R
import de.hdodenhof.circleimageview.CircleImageView
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The ringing screen drawn from an [IncomingScreenSpec] — the native twin of the
 * host app's in-app ringing scene (Rategale: `PersonCallScene` in the ringing
 * phase). Built in code rather than XML because every value is data.
 *
 * Top to bottom, as in the app:
 * * zemin: the caller's avatar blurred (`blurSigma`) over the brand gradient,
 *   darkened by the shade gradient; no avatar → the gradient alone;
 * * a spacer the height of the app's top bar, then the caption row
 *   ("Rategale · Voice call" with the call-type glyph);
 * * the stage: avatar + pulse rings ([IncomingCallStageView]);
 * * name (28sp bold, two lines) and status ("Incoming voice call");
 * * the answer row: decline (left) · message (middle, optional) · accept
 *   (right, nudging). Two big targets on opposite sides so the thumb cannot hit
 *   the wrong one.
 *
 * Wiring (clicks, drag-to-answer, finishing) stays in the activity; this class
 * only builds views and owns their animations.
 */
@SuppressLint("ViewConstructor")
class SpecIncomingScreen(
    context: Context,
    private val spec: IncomingScreenSpec,
) : FrameLayout(context) {

    private val density = resources.displayMetrics.density

    /** "Remove animations" in developer/accessibility settings. */
    private val reducedMotion = Settings.Global.getFloat(
        context.contentResolver,
        Settings.Global.ANIMATOR_DURATION_SCALE,
        1f,
    ) == 0f

    private val backdrop = ImageView(context).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        visibility = View.GONE
    }
    private val shade = View(context).apply {
        background = gradient(spec.shadeColors, spec.shadeStops)
        visibility = View.GONE
    }
    private val stage = IncomingCallStageView(context, spec, reducedMotion)
    private val initials = InitialsView(context, spec)
    private val avatar = CircleImageView(context).apply { visibility = View.INVISIBLE }

    val nameView = TextView(context).apply {
        setTextColor(spec.textPrimary)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f)
        typeface = Typeface.DEFAULT_BOLD
        letterSpacing = -0.3f / 28f
        gravity = Gravity.CENTER
        maxLines = 2
        ellipsize = TextUtils.TruncateAt.END
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            lineHeight = sp(28f * 1.15f)
        }
    }

    val declineButton: View
    val acceptButton: View
    val messageButton: View?
    val acceptLabel: TextView
    val declineLabel: TextView

    private var nudge: ValueAnimator? = null

    init {
        background = gradient(spec.gradientColors, spec.gradientStops)
        addView(backdrop, LayoutParams(MATCH_PARENT, MATCH_PARENT))
        addView(shade, LayoutParams(MATCH_PARENT, MATCH_PARENT))

        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            fitsSystemWindows = true
        }
        addView(column, LayoutParams(MATCH_PARENT, MATCH_PARENT))

        // The app's top bar (10 + 44 + 6 dp) holds only a minimise button while
        // ringing; there is nothing to minimise to here, its height is kept so
        // the caption and the stage sit where they sit in the app.
        column.addView(Space(context), LinearLayout.LayoutParams(MATCH_PARENT, dp(60f)))

        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(16f), dp(4f), dp(16f), dp(8f))
        }
        column.addView(body, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        body.addView(captionRow(), LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
            topMargin = dp(6f)
        })

        val avatarFrame = FrameLayout(context).apply {
            addView(initials, LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(avatar, LayoutParams(MATCH_PARENT, MATCH_PARENT))
        }
        stage.setAvatar(avatarFrame)
        body.addView(stage, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        body.addView(nameView, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        body.addView(
            TextView(context).apply {
                text = spec.status
                setTextColor(spec.textSecondary)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                gravity = Gravity.CENTER
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            },
            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(8f) },
        )
        body.addView(Space(context), LinearLayout.LayoutParams(MATCH_PARENT, dp(16f)))

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
            val inset = dp(spec.answerRowInset)
            setPadding(inset, dp(8f), inset, dp(20f))
        }
        column.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        val decline = actionButton(R.drawable.ic_rg_phone_off, spec.decline, spec.answerButton)
        val message = if (spec.hasMessage) {
            actionButton(R.drawable.ic_rg_message_circle, spec.glass, spec.secondaryButton)
        } else null
        val accept = actionButton(R.drawable.ic_rg_phone, spec.accept, spec.answerButton)
        declineButton = decline.circle
        acceptButton = accept.circle
        messageButton = message?.circle
        message?.label?.text = spec.message

        row.addView(decline.column)
        row.addView(Space(context), LinearLayout.LayoutParams(0, 0, 1f))
        message?.let {
            row.addView(it.column)
            row.addView(Space(context), LinearLayout.LayoutParams(0, 0, 1f))
        }
        row.addView(accept.column)

        acceptLabel = accept.label
        declineLabel = decline.label
    }

    fun setCaller(name: String) {
        nameView.text = name
        initials.setName(name)
    }

    /**
     * The avatar for both the circle and the blurred backdrop. The backdrop and
     * shade appear only with an avatar (as in the app); a failed load keeps the
     * initials and the gradient behind them.
     */
    fun setAvatarUrl(url: String, headers: HashMap<String, Any?>?) {
        if (url.isEmpty()) return
        backdrop.visibility = View.VISIBLE
        shade.visibility = View.VISIBLE
        val loader = ImageLoaderProvider.get(context, headers)
        loader.enqueue(
            request(url, headers)
                .target(avatar)
                .listener(
                    onSuccess = { _, _ -> avatar.visibility = View.VISIBLE },
                    onError = { _, _ -> avatar.visibility = View.INVISIBLE },
                )
                .build()
        )
        val blurRadius = spec.blurSigma * density
        val backdropRequest = request(url, headers).target(backdrop)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            backdrop.setRenderEffect(
                RenderEffect.createBlurEffect(blurRadius, blurRadius, Shader.TileMode.MIRROR)
            )
        } else {
            // No RenderEffect before Android 12: shrinking the image to a few
            // dozen pixels and letting the view upscale it with bilinear
            // filtering gives the same colour wash the blur exists for.
            backdropRequest.transformations(DownsampleBlur)
        }
        loader.enqueue(backdropRequest.build())
    }

    private fun request(url: String, headers: HashMap<String, Any?>?) =
        ImageRequest.Builder(context).data(url).allowHardware(false).apply {
            headers?.forEach { (k, v) -> v?.toString()?.let { addHeader(k, it) } }
        }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!reducedMotion) startNudge()
    }

    override fun onDetachedFromWindow() {
        nudge?.cancel()
        nudge = null
        super.onDetachedFromWindow()
    }

    /**
     * The accept button's intermittent shake: three damped oscillations in the
     * first `nudgeActivePart` of each `nudgePeriodMs`, then rest — the app's
     * `_Nudge`, angle for angle.
     */
    private fun startNudge() {
        nudge = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = spec.nudgePeriodMs
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                val t = it.animatedValue as Float
                // Paused while the finger drags the button (the drag owns the transform).
                if (acceptButton.translationY == 0f) {
                    acceptButton.rotation = Math.toDegrees(nudgeAngle(t).toDouble()).toFloat()
                }
            }
            start()
        }
    }

    fun stopNudge() {
        nudge?.cancel()
        acceptButton.rotation = 0f
    }

    fun nudgeAngle(t: Float): Float {
        if (t >= spec.nudgeActivePart) return 0f
        val u = t / spec.nudgeActivePart
        return spec.nudgeAmplitude * (1 - u) *
            sin(u * spec.nudgeOscillations * 2 * PI).toFloat()
    }

    private class ActionButton(val column: LinearLayout, val circle: View, val label: TextView)

    /** Circle + label as ONE column, like the app's labelled call button. */
    private fun actionButton(icon: Int, color: Int, diameter: Float): ActionButton {
        val size = dp(diameter)
        val circle = FrameLayout(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(color)
            }
            isClickable = true
            isFocusable = true
            addView(
                ImageView(context).apply {
                    setImageResource(icon)
                    setColorFilter(spec.textPrimary)
                },
                LayoutParams((size * spec.iconScale).roundToInt(), (size * spec.iconScale).roundToInt(), Gravity.CENTER),
            )
        }
        val label = TextView(context).apply {
            setTextColor(spec.textSecondary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER
            maxLines = 1
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            // The app reserves the larger button's width for every column so the
            // three labels share one baseline row.
            minimumWidth = dp(spec.answerButton)
            addView(circle, LinearLayout.LayoutParams(size, size))
            addView(label, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
                topMargin = dp(8f)
            })
        }
        return ActionButton(column, circle, label)
    }

    private fun gradient(colors: IntArray, stops: FloatArray) = GradientDrawable(
        GradientDrawable.Orientation.TOP_BOTTOM,
        colors,
    ).apply {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) setColors(colors, stops)
    }

    private fun captionRow(): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(
            ImageView(context).apply {
                setImageResource(if (spec.isVideo) R.drawable.ic_rg_video else R.drawable.ic_rg_phone)
                setColorFilter(spec.textSecondary)
            },
            LinearLayout.LayoutParams(dp(14f), dp(14f)),
        )
        addView(
            TextView(context).apply {
                text = spec.caption
                setTextColor(spec.textSecondary)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                letterSpacing = 0.2f / 13f
                maxLines = 1
            },
            LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = dp(6f) },
        )
    }

    private fun dp(v: Float): Int = (v * density).roundToInt()

    private fun sp(v: Float): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics,
    ).roundToInt()

    /** Initials on the brand colour — the app's `AppAvatar` without a picture. */
    private class InitialsView(context: Context, spec: IncomingScreenSpec) : TextView(context) {
        init {
            gravity = Gravity.CENTER
            setTextColor(spec.textPrimary)
            typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(spec.avatarFallback)
            }
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }

        fun setName(name: String) {
            text = initialsOf(name)
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, w * 0.4f)
        }
    }

    /** Downsampling stand-in for a Gaussian blur (Android < 12). */
    private object DownsampleBlur : Transformation {
        private const val TARGET_PX = 24

        override fun key(): String = "rg-downsample-blur-$TARGET_PX"

        override suspend fun transform(pool: BitmapPool, input: Bitmap, size: Size): Bitmap {
            val scale = TARGET_PX.toFloat() / max(input.width, input.height)
            if (scale >= 1f) return input
            return input.scale(
                max(1, (input.width * scale).roundToInt()),
                max(1, (input.height * scale).roundToInt()),
                filter = true,
            )
        }
    }

    companion object {
        private val whitespace = Regex("\\s+")

        /** First letter of the first and last word, upper case; `?` when empty. */
        fun initialsOf(name: String): String {
            val parts = name.trim().split(whitespace).filter { it.isNotEmpty() }
            if (parts.isEmpty()) return "?"
            if (parts.size == 1) return parts.first().take(1).uppercase()
            return (parts.first().take(1) + parts.last().take(1)).uppercase()
        }
    }
}
