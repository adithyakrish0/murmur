package com.murmur.keyboard.ui

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import com.murmur.keyboard.R

class KeyboardView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    val micButton: MicButtonView
    val hintText: TextView
    val timerText: TextView

    val btnGlobe: ImageButton
    val btnSpace: ImageButton
    val btnBackspace: ImageButton
    val btnEnter: ImageButton

    private val handler = Handler(Looper.getMainLooper())
    private var recordingStartTime = 0L

    init {
        LayoutInflater.from(context).inflate(R.layout.keyboard_view, this, true)

        micButton = findViewById(R.id.mic_button)
        hintText = findViewById(R.id.hint_text)
        timerText = findViewById(R.id.timer_text)

        btnGlobe = findViewById(R.id.btn_globe)
        btnSpace = findViewById(R.id.btn_space)
        btnBackspace = findViewById(R.id.btn_backspace)
        btnEnter = findViewById(R.id.btn_enter)
    }

    private val timerRunnable = object : Runnable {
        override fun run() {
            val elapsed = (System.currentTimeMillis() - recordingStartTime) / 1000
            val minutes = elapsed / 60
            val seconds = elapsed % 60
            timerText.text = String.format("%d:%02d", minutes, seconds)
            handler.postDelayed(this, 1000)
        }
    }

    fun setState(state: MicState, message: String? = null) {
        micButton.setState(state)

        when (state) {
            MicState.IDLE -> {
                hintText.text = message ?: "Tap to speak"
                stopTimer()
            }
            MicState.RECORDING -> {
                hintText.text = message ?: "Listening... tap to stop"
                startTimer()
            }
            MicState.PROCESSING -> {
                hintText.text = message ?: "Transcribing..."
                stopTimer()
            }
            MicState.DONE -> {
                hintText.text = message ?: ""
                stopTimer()
            }
            MicState.ERROR -> {
                hintText.text = message ?: "Error occurred"
                stopTimer()

                // Auto clear error
                handler.removeCallbacksAndMessages(null)
                handler.postDelayed({
                    setState(MicState.IDLE)
                }, 3000)
            }
        }
    }

    private fun startTimer() {
        recordingStartTime = System.currentTimeMillis()
        timerText.visibility = View.VISIBLE
        timerText.text = "0:00"
        handler.postDelayed(timerRunnable, 1000)
    }

    private fun stopTimer() {
        handler.removeCallbacks(timerRunnable)
        timerText.visibility = View.GONE
    }
}
