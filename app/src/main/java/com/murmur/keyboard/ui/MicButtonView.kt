package com.murmur.keyboard.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.View
import android.view.animation.OvershootInterpolator
import androidx.core.content.ContextCompat
import com.murmur.keyboard.R

enum class MicState {
    IDLE, RECORDING, PROCESSING, DONE, ERROR
}

class MicButtonView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val accentColor = ContextCompat.getColor(context, R.color.colorPrimary)
    private val errorColor = ContextCompat.getColor(context, R.color.colorError)
    private val successColor = Color.parseColor("#4CAF50") // Green

    private val buttonPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = accentColor
    }

    private val ripplePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f * context.resources.displayMetrics.density
        color = accentColor
        alpha = 100
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
    private var amplitudeRadiusOffset = 0f
    private var pulseAnimator: ValueAnimator? = null

    private var progressAngle = 0f
    private var progressAnimator: ValueAnimator? = null

    private var errorOffset = 0f
    private var errorAnimator: ValueAnimator? = null

    init {
        startIdlePulse()
    }

    fun setState(state: MicState) {
        if (currentState == state) return
        currentState = state

        pulseAnimator?.cancel()
        progressAnimator?.cancel()
        errorAnimator?.cancel()

        buttonScale = 1f
        amplitudeRadiusOffset = 0f
        progressAngle = 0f
        errorOffset = 0f

        when (state) {
            MicState.IDLE -> {
                buttonPaint.color = accentColor
                startIdlePulse()
            }
            MicState.RECORDING -> {
                buttonPaint.color = Color.parseColor("#E53935") // Active red
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
            // Map amplitude (0 to ~32767) to a radius offset
            val maxAmp = 32767f
            val normalized = (amplitude.coerceIn(0, 32767).toFloat() / maxAmp)
            // smooth update would be better, but direct assignment works for now with polling
            amplitudeRadiusOffset = normalized * 100f // max 100px expand
            invalidate()
        }
    }

    private fun startIdlePulse() {
        pulseAnimator = ValueAnimator.ofFloat(1f, 1.05f).apply {
            duration = 1500
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener {
                buttonScale = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

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

    private fun startErrorShake() {
        errorAnimator = ValueAnimator.ofFloat(0f, 20f, -20f, 10f, -10f, 0f).apply {
            duration = 400
            addUpdateListener {
                errorOffset = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun animateDoneBounce() {
        val anim = ValueAnimator.ofFloat(0.8f, 1.1f, 1f).apply {
            duration = 400
            interpolator = OvershootInterpolator()
            addUpdateListener {
                buttonScale = it.animatedValue as Float
                invalidate()
            }
        }
        anim.start()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val cx = width / 2f + errorOffset
        val cy = height / 2f
        val baseRadius = (width.coerceAtMost(height) / 2f) * 0.8f
        val currentRadius = baseRadius * buttonScale

        // Draw ripple for recording
        if (currentState == MicState.RECORDING) {
            canvas.drawCircle(cx, cy, currentRadius + 20f + amplitudeRadiusOffset, ripplePaint)
        }

        // Draw button
        canvas.drawCircle(cx, cy, currentRadius, buttonPaint)

        // Draw progress
        if (currentState == MicState.PROCESSING) {
            val progressRect = android.graphics.RectF(
                cx - currentRadius + 10f, cy - currentRadius + 10f,
                cx + currentRadius - 10f, cy + currentRadius - 10f
            )
            canvas.drawArc(progressRect, progressAngle, 120f, false, progressPaint)
        } else {
            // Draw Icon
            val icon = when (currentState) {
                MicState.RECORDING -> stopIcon
                MicState.DONE -> checkIcon
                else -> micIcon
            }

            icon?.let {
                val iconSize = (currentRadius * 0.8f).toInt()
                it.setBounds((cx - iconSize).toInt(), (cy - iconSize).toInt(),
                            (cx + iconSize).toInt(), (cy + iconSize).toInt())
                it.draw(canvas)
            }
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        pulseAnimator?.cancel()
        progressAnimator?.cancel()
        errorAnimator?.cancel()
    }
}
