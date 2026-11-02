package com.murmur.keyboard

import android.content.Context
import android.content.Intent
import android.inputmethodservice.InputMethodService
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import com.murmur.keyboard.audio.Recorder
import com.murmur.keyboard.data.Settings
import com.murmur.keyboard.net.GroqClient
import com.murmur.keyboard.net.TranscriptionResult
import com.murmur.keyboard.ui.KeyboardView
import com.murmur.keyboard.ui.MicState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MurmurIME : InputMethodService() {

    private lateinit var keyboardView: KeyboardView
    private lateinit var recorder: Recorder
    private lateinit var settings: Settings
    private val groqClient = GroqClient()

    private val coroutineScope = CoroutineScope(Dispatchers.Main + Job())
    private var transcriptionJob: Job? = null

    private val handler = Handler(Looper.getMainLooper())
    private val amplitudeRunnable = object : Runnable {
        override fun run() {
            if (keyboardView.micButton.currentState == MicState.RECORDING) {
                keyboardView.micButton.updateAmplitude(recorder.getMaxAmplitude())
                handler.postDelayed(this, 50)
            }
        }
    }

    private var currentFocusRequest: Any? = null // AudioFocusRequest on API 26+

    override fun onCreate() {
        super.onCreate()
        settings = Settings(this)
        recorder = Recorder(this)
    }

    override fun onCreateInputView(): View {
        keyboardView = KeyboardView(this)
        setupListeners()
        return keyboardView
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        keyboardView.setState(MicState.IDLE)
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        stopRecordingIfNeeded()
        transcriptionJob?.cancel()
    }

    override fun onWindowHidden() {
        super.onWindowHidden()
        stopRecordingIfNeeded()
        transcriptionJob?.cancel()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopRecordingIfNeeded()
        coroutineScope.coroutineContext[Job]?.cancel()
    }

    private fun setupListeners() {
        keyboardView.micButton.setOnClickListener {
            handleMicTap()
        }

        keyboardView.btnGlobe.setOnClickListener {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            val token = window.window?.attributes?.token

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                switchToNextInputMethod(false)
            } else {
                try {
                    imm.switchToNextInputMethod(token, false)
                } catch (e: Exception) {
                    imm.showInputMethodPicker()
                }
            }
        }

        // Backspace handling
        keyboardView.btnBackspace.setOnClickListener {
            currentInputConnection?.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL))
            currentInputConnection?.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL))
        }
        keyboardView.btnBackspace.setOnLongClickListener {
            currentInputConnection?.deleteSurroundingText(50, 0)
            true
        }

        keyboardView.btnSpace.setOnClickListener {
            currentInputConnection?.commitText(" ", 1)
        }

        keyboardView.btnEnter.setOnClickListener {
            val action = currentInputEditorInfo?.imeOptions?.and(EditorInfo.IME_MASK_ACTION)
            if (action == EditorInfo.IME_ACTION_NONE || action == EditorInfo.IME_ACTION_UNSPECIFIED) {
                currentInputConnection?.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
                currentInputConnection?.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
            } else {
                currentInputConnection?.performEditorAction(action ?: EditorInfo.IME_ACTION_DONE)
            }
        }
    }

    private fun handleMicTap() {
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            keyboardView.setState(MicState.ERROR, "Mic permission needed")
            val intent = Intent(this, SetupActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
            return
        }

        if (settings.apiKey.isBlank()) {
            keyboardView.setState(MicState.ERROR, "No API key set. Tap to open settings.")
            return
        }

        when (keyboardView.micButton.currentState) {
            MicState.IDLE, MicState.ERROR, MicState.DONE -> startRecording()
            MicState.RECORDING -> stopRecordingAndTranscribe()
            MicState.PROCESSING -> { /* ignore */ }
        }
    }

    private fun startRecording() {
        if (!requestAudioFocus()) {
            keyboardView.setState(MicState.ERROR, "Audio focus denied")
            return
        }

        val started = recorder.startRecording {
            // Auto stop when max duration hit
            handler.post {
                vibrate()
                stopRecordingAndTranscribe()
            }
        }

        if (started) {
            vibrate()
            keyboardView.setState(MicState.RECORDING)
            handler.post(amplitudeRunnable)
        } else {
            abandonAudioFocus()
            keyboardView.setState(MicState.ERROR, "Failed to start recording")
        }
    }

    private fun stopRecordingIfNeeded() {
        if (keyboardView.micButton.currentState == MicState.RECORDING) {
            recorder.stopRecording()
            recorder.deleteRecording()
            handler.removeCallbacks(amplitudeRunnable)
            abandonAudioFocus()
            keyboardView.setState(MicState.IDLE)
        }
    }

    private fun stopRecordingAndTranscribe() {
        recorder.stopRecording()
        handler.removeCallbacks(amplitudeRunnable)
        abandonAudioFocus()
        vibrate()

        keyboardView.setState(MicState.PROCESSING)

        val file = recorder.outputFile
        if (file == null || !file.exists()) {
            keyboardView.setState(MicState.ERROR, "Recording failed")
            return
        }

        val apiKey = settings.apiKey
        val model = settings.selectedModel

        transcriptionJob = coroutineScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    groqClient.transcribeAudio(apiKey, file, model)
                }
                if (isActive) {
                    handleTranscriptionResult(result)
                }
            } finally {
                recorder.deleteRecording()
            }
        }
    }

    private fun handleTranscriptionResult(result: TranscriptionResult) {
        when (result) {
            is TranscriptionResult.Success -> {
                val text = result.text.trim()
                if (text.isEmpty()) {
                    keyboardView.setState(MicState.ERROR, "Didn't catch anything")
                    return
                }

                val ic = currentInputConnection
                if (ic != null) {
                    val beforeCursor = ic.getTextBeforeCursor(1, 0)
                    val textToInsert = if (!beforeCursor.isNullOrEmpty() && !beforeCursor[0].isWhitespace()) {
                        " \$text"
                    } else {
                        text
                    }

                    ic.beginBatchEdit()
                    ic.commitText(textToInsert, 1)
                    ic.endBatchEdit()

                    keyboardView.setState(MicState.DONE)
                    handler.postDelayed({
                        if (keyboardView.micButton.currentState == MicState.DONE) {
                            keyboardView.setState(MicState.IDLE)
                        }
                    }, 1000)
                } else {
                    keyboardView.setState(MicState.ERROR, "No input field active")
                }
            }
            is TranscriptionResult.Error -> {
                keyboardView.setState(MicState.ERROR, result.message)
            }
        }
    }

    private fun requestAudioFocus(): Boolean {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                .setAudioAttributes(attributes)
                .build()
            currentFocusRequest = request
            val res = audioManager.requestAudioFocus(request)
            res == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } else {
            @Suppress("DEPRECATION")
            val res = audioManager.requestAudioFocus(null, AudioManager.STREAM_VOICE_CALL, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
            res == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
    }

    private fun abandonAudioFocus() {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            (currentFocusRequest as? AudioFocusRequest)?.let {
                audioManager.abandonAudioFocusRequest(it)
            }
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(null)
        }
        currentFocusRequest = null
    }

    private fun vibrate() {
        if (!settings.hapticFeedbackEnabled) return
        val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        if (vibrator.hasVibrator()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(20, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(20)
            }
        }
    }
}
