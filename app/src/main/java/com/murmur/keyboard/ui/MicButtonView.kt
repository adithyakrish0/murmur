package com.murmur.keyboard.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.OvershootInterpolator
import androidx.core.content.ContextCompat
import com.murmur.keyboard.R

enum class MicState {
    IDLE, RECORDING, PROCESSING, DONE, ERROR
}

class MicButtonView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // Hardcoded colors — no ?attr/ refs, safe for IME context
    private val accentColor  = Color.parseColor("#6750A4")
    private val errorColor   = Color.parseColor("#CF4040")
    private val successColor = Color.parseColor("#4CAF50")
    private val recordingRed = Color.parseColor("#CF4040")

    private val buttonPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = accentColor
    }

    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val ripplePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f * context.resources.displayMetrics.density
        color = recordingRed
    }

    private val ripple2Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * context.resources.displayMetrics.density
        color = recordingRed
    }

    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 6f * context.resources.displayMetrics.density
        color = Color.WHITE
        strokeCap = Paint.Cap.ROUND
    }

    private var micIcon: Drawable? = ContextCompat.getDrawable(context, R.drawable.ic_mic)
    private var stopIcon: Drawable? = ContextCompat.getDrawable(context, R.drawable.ic_stop)
    private var checkIcon: Drawable? = ContextCompat.getDrawable(context, R.drawable.ic_check)

    var currentState: MicState = MicState.IDLE
        private set

    private var buttonScale = 1f
    private var glowAlpha = 0f           // for idle glow breathing
    private var amplitudeRadiusOffset = 0f

    // Two staggered ripple rings for recording
    private var rippleRadius1 = 0f
    private var rippleAlpha1 = 0
    private var rippleRadius2 = 0f
    private var rippleAlpha2 = 0

    private var progressAngle = 0f
    private var errorOffset = 0f

    private var pulseAnimator: ValueAnimator? = null
    private var rippleAnimator: ValueAnimator? = null
    private var progressAnimator: ValueAnimator? = null
    private var errorAnimator: ValueAnimator? = null

    init {
        // Tint icons white
        listOf(micIcon, stopIcon, checkIcon).forEach { d ->
            d?.mutate()?.setTint(Color.WHITE)
        }
        startIdlePulse()
    }

    fun setState(state: MicState) {
        if (currentState == state) return
        currentState = state

        pulseAnimator?.cancel()
        rippleAnimator?.cancel()
        progressAnimator?.cancel()
        errorAnimator?.cancel()

        buttonScale = 1f
        glowAlpha = 0f
        amplitudeRadiusOffset = 0f
        rippleRadius1 = 0f; rippleAlpha1 = 0
        rippleRadius2 = 0f; rippleAlpha2 = 0
        progressAngle = 0f
        errorOffset = 0f

        when (state) {
            MicState.IDLE -> {
                buttonPaint.color = accentColor
                startIdlePulse()
            }
            MicState.RECORDING -> {
                buttonPaint.color = recordingRed
                startRippleAnimation()
            }
            MicState.PROCESSING -> {
                buttonPaint.color = accentColor
                startProgressAnimation()
            }
            MicState.DONE -> {
                buttonPaint.color = successColor
                animateDoneBounce()
            }
            MicState.ERROR -> {
                buttonPaint.color = errorColor
                startErrorShake()
            }
        }
        invalidate()
    }

    fun updateAmplitude(amplitude: Int) {
        if (currentState == MicState.RECORDING) {
            val normalized = (amplitude.coerceIn(0, 32767).toFloat() / 32767f)
            amplitudeRadiusOffset = normalized * 80f
            invalidate()
        }
    }

    // ── Idle: smooth breathing glow (scale 1.0 → 1.08, ease in/out, 1800ms) ──
    private fun startIdlePulse() {
        pulseAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1800
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { anim ->
                val t = anim.animatedValue as Float
                buttonScale = 1f + 0.08f * t
                glowAlpha = t
                invalidate()
            }
            start()
        }
    }

    // ── Recording: two expanding ripple rings ──
    private fun startRippleAnimation() {
        rippleAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1200
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { anim ->
                val t = anim.animatedValue as Float
                // ring 1: starts at t=0
                rippleRadius1 = t
                rippleAlpha1 = (255 * (1f - t)).toInt()
                // ring 2: starts at t=0.5, wraps
                val t2 = (t + 0.5f) % 1f
                rippleRadius2 = t2
                rippleAlpha2 = (255 * (1f - t2)).toInt()
                invalidate()
            }
            start()
        }
    }

    // ── Processing: spinning arc ──
    private fun startProgressAnimation() {
        progressAnimator = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = 1000
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener {
                progressAngle = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    // ── Done: satisfying bounce ──
    private fun animateDoneBounce() {
        val anim = ValueAnimator.ofFloat(0.75f, 1.15f, 1f).apply {
            duration = 450
            interpolator = OvershootInterpolator(2f)
            addUpdateListener {
                buttonScale = it.animatedValue as Float
                invalidate()
            }
        }
        anim.start()
    }

    // ── Error: horizontal shake ──
    private fun startErrorShake() {
        errorAnimator = ValueAnimator.ofFloat(0f, 22f, -22f, 12f, -12f, 0f).apply {
            duration = 400
            addUpdateListener {
                errorOffset = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val cx = width / 2f + errorOffset
        val cy = height / 2f
        val baseRadius = (width.coerceAtMost(height) / 2f) * 0.80f
        val currentRadius = baseRadius * buttonScale

        // ── Idle glow halo ──
        if (currentState == MicState.IDLE && glowAlpha > 0f) {
            val glowRadius = currentRadius * 1.55f
            glowPaint.shader = RadialGradient(
                cx, cy, glowRadius,
                intArrayOf(
                    Color.argb((80 * glowAlpha).toInt(), 103, 80, 164),
                    Color.argb(0, 103, 80, 164)
                ),
                floatArrayOf(0.4f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawCircle(cx, cy, glowRadius, glowPaint)
        }

        // ── Recording ripple rings ──
        if (currentState == MicState.RECORDING) {
            val ampExtra = amplitudeRadiusOffset
            val maxRipple = baseRadius * 0.7f + ampExtra

            ripplePaint.alpha = rippleAlpha1
            canvas.drawCircle(cx, cy, currentRadius + maxRipple * rippleRadius1, ripplePaint)

            ripple2Paint.alpha = rippleAlpha2
            canvas.drawCircle(cx, cy, currentRadius + maxRipple * rippleRadius2, ripple2Paint)
        }

        // ── Button circle ──
        canvas.drawCircle(cx, cy, currentRadius, buttonPaint)

        // ── Processing arc ──
        if (currentState == MicState.PROCESSING) {
            val rect = RectF(
                cx - currentRadius + 10f, cy - currentRadius + 10f,
                cx + currentRadius - 10f, cy + currentRadius - 10f
            )
            canvas.drawArc(rect, progressAngle, 120f, false, progressPaint)
        } else {
            // ── Icon ──
            val icon = when (currentState) {
                MicState.RECORDING  -> stopIcon
                MicState.DONE       -> checkIcon
                else                -> micIcon
            }
            icon?.let {
                val iconSize = (currentRadius * 0.75f).toInt()
                it.setBounds(
                    (cx - iconSize).toInt(), (cy - iconSize).toInt(),
                    (cx + iconSize).toInt(), (cy + iconSize).toInt()
                )
                it.draw(canvas)
            }
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        pulseAnimator?.cancel()
        rippleAnimator?.cancel()
        progressAnimator?.cancel()
        errorAnimator?.cancel()
    }
}
