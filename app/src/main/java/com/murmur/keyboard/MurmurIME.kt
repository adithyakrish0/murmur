package com.murmur.keyboard

import android.annotation.SuppressLint
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
import android.view.ContextThemeWrapper
import android.view.KeyEvent
import android.view.MotionEvent
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
import java.io.File

class MurmurIME : InputMethodService() {

    private lateinit var keyboardView: KeyboardView
    private lateinit var recorder: Recorder
    private lateinit var settings: Settings
    private val groqClient = GroqClient()

    private var lastRecordedAudioFile: File? = null

    private val coroutineScope = CoroutineScope(Dispatchers.Main + Job())
    private var transcriptionJob: Job? = null

    private val handler = Handler(Looper.getMainLooper())
    private val amplitudeRunnable = object : Runnable {
        override fun run() {
            if (::keyboardView.isInitialized &&
                keyboardView.micButton.currentState == MicState.RECORDING) {
                keyboardView.micButton.updateAmplitude(recorder.getMaxAmplitude())
                handler.postDelayed(this, 50)
            }
        }
    }

    private var backspaceRepeatRunnable: Runnable? = null
    private var backspaceRepeatCount = 0

    private var currentFocusRequest: Any? = null // AudioFocusRequest on API 26+

    override fun onCreate() {
        super.onCreate()
        settings = Settings(this)
        recorder = Recorder(this)
    }

    override fun onCreateInputView(): View {
        // Wrap with Theme.Murmur so Material attrs (colorSurface, etc.) resolve correctly
        val themedContext = ContextThemeWrapper(this, R.style.Theme_Murmur)
        keyboardView = KeyboardView(themedContext)
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
        cancelBackspaceRepeat()
        transcriptionJob?.cancel()
    }

    override fun onWindowHidden() {
        super.onWindowHidden()
        stopRecordingIfNeeded()
        cancelBackspaceRepeat()
        transcriptionJob?.cancel()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopRecordingIfNeeded()
        cancelBackspaceRepeat()
        lastRecordedAudioFile?.delete()
        lastRecordedAudioFile = null
        coroutineScope.coroutineContext[Job]?.cancel()
    }

    private fun setupListeners() {
        keyboardView.micButton.setOnClickListener {
            handleMicTap()
        }

        keyboardView.onRetryListener = {
            retryLastRecording()
        }

        keyboardView.btnSettings.setOnClickListener {
            val intent = Intent(this, SetupActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
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

        setupBackspace()

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

    @SuppressLint("ClickableViewAccessibility")
    private fun setupBackspace() {
        keyboardView.btnBackspace.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    v.isPressed = true
                    handleSingleDelete()
                    vibrate(12)

                    backspaceRepeatCount = 0
                    backspaceRepeatRunnable = object : Runnable {
                        override fun run() {
                            backspaceRepeatCount++
                            if (backspaceRepeatCount < 6) {
                                // First 5 repeats: single character deletion with smooth 50ms delay
                                handleSingleDelete()
                                vibrate(12)
                                handler.postDelayed(this, 50L)
                            } else {
                                // 6+ repeats: word-by-word deletion with distinct 160ms delay (like Gboard)
                                handleWordDelete()
                                vibrate(25)
                                handler.postDelayed(this, 160L)
                            }
                        }
                    }
                    handler.postDelayed(backspaceRepeatRunnable!!, 350L)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    // Stop if finger slides off the button
                    if (event.x < 0 || event.x > v.width || event.y < 0 || event.y > v.height) {
                        stopBackspaceRepeat(v)
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    stopBackspaceRepeat(v)
                    true
                }
                else -> false
            }
        }
    }

    private fun stopBackspaceRepeat(v: View) {
        v.isPressed = false
        cancelBackspaceRepeat()
    }

    private fun cancelBackspaceRepeat() {
        backspaceRepeatRunnable?.let { handler.removeCallbacks(it) }
        backspaceRepeatRunnable = null
        backspaceRepeatCount = 0
    }

    private fun handleSingleDelete() {
        val ic = currentInputConnection ?: return
        val selected = ic.getSelectedText(0)
        if (!selected.isNullOrEmpty()) {
            ic.commitText("", 1)
        } else {
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL))
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL))
        }
    }

    private fun handleWordDelete() {
        val ic = currentInputConnection ?: return
        val selected = ic.getSelectedText(0)
        if (!selected.isNullOrEmpty()) {
            ic.commitText("", 1)
            return
        }

        ic.beginBatchEdit()
        try {
            // Inspect up to 100 characters before cursor to find word boundary
            val textBefore = ic.getTextBeforeCursor(100, 0)
            if (textBefore.isNullOrEmpty()) {
                ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL))
                ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL))
                return
            }

            val text = textBefore.toString()
            var index = text.length

            // Step 1: Skip trailing whitespace
            while (index > 0 && text[index - 1].isWhitespace()) {
                index--
            }

            // If only whitespace was present, delete that whitespace
            if (index == 0) {
                ic.deleteSurroundingText(text.length, 0)
                return
            }

            // Step 2: Traverse backward through the word characters (or punctuation cluster)
            val lastChar = text[index - 1]
            if (lastChar.isLetterOrDigit()) {
                while (index > 0 && text[index - 1].isLetterOrDigit()) {
                    index--
                }
            } else {
                // Punctuation / symbols cluster (e.g. "...", "?!", etc.)
                while (index > 0 && !text[index - 1].isLetterOrDigit() && !text[index - 1].isWhitespace()) {
                    index--
                }
            }

            val countToDelete = text.length - index
            if (countToDelete > 0) {
                ic.deleteSurroundingText(countToDelete, 0)
            } else {
                ic.deleteSurroundingText(1, 0)
            }
        } finally {
            ic.endBatchEdit()
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
            keyboardView.setState(MicState.ERROR, "No API key set. Tap settings gear.", canRetry = false)
            return
        }

        when (keyboardView.micButton.currentState) {
            MicState.IDLE, MicState.DONE -> startRecording()
            MicState.ERROR -> {
                // If in error, tapping the mic starts a fresh recording and discards previous failed audio
                lastRecordedAudioFile?.delete()
                lastRecordedAudioFile = null
                startRecording()
            }
            MicState.RECORDING -> stopRecordingAndTranscribe()
            MicState.PROCESSING -> { /* ignore while transcribing */ }
        }
    }

    private fun startRecording() {
        // Discard any previous audio file when starting fresh recording
        lastRecordedAudioFile?.delete()
        lastRecordedAudioFile = null

        if (!requestAudioFocus()) {
            keyboardView.setState(MicState.ERROR, "Audio focus denied", canRetry = false)
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
            keyboardView.setState(MicState.ERROR, "Failed to start recording", canRetry = false)
        }
    }

    private fun stopRecordingIfNeeded() {
        if (::keyboardView.isInitialized &&
            keyboardView.micButton.currentState == MicState.RECORDING) {
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
            keyboardView.setState(MicState.ERROR, "Recording failed", canRetry = false)
            return
        }

        // Cache the file so it can be retried if network fails
        lastRecordedAudioFile = file

        val apiKey = settings.apiKey
        val model = settings.selectedModel

        transcriptionJob = coroutineScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    groqClient.transcribeAudio(apiKey, file, model)
                }
                if (isActive) {
                    handleTranscriptionResult(result, file)
                }
            } catch (e: Exception) {
                if (isActive) {
                    handleTranscriptionResult(
                        TranscriptionResult.Error(e.localizedMessage ?: "Transcription failed"),
                        file
                    )
                }
            }
        }
    }

    private fun retryLastRecording() {
        val file = lastRecordedAudioFile
        if (file == null || !file.exists()) {
            keyboardView.setState(MicState.ERROR, "No recording to retry", canRetry = false)
            return
        }

        val apiKey = settings.apiKey
        if (apiKey.isBlank()) {
            keyboardView.setState(MicState.ERROR, "No API key set. Tap settings.", canRetry = false)
            return
        }

        val model = settings.selectedModel
        vibrate()
        keyboardView.setState(MicState.PROCESSING)

        transcriptionJob = coroutineScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    groqClient.transcribeAudio(apiKey, file, model)
                }
                if (isActive) {
                    handleTranscriptionResult(result, file)
                }
            } catch (e: Exception) {
                if (isActive) {
                    handleTranscriptionResult(
                        TranscriptionResult.Error(e.localizedMessage ?: "Retry failed"),
                        file
                    )
                }
            }
        }
    }

    private fun handleTranscriptionResult(result: TranscriptionResult, file: File) {
        when (result) {
            is TranscriptionResult.Success -> {
                val text = result.text.trim()
                if (text.isEmpty()) {
                    file.delete()
                    lastRecordedAudioFile = null
                    keyboardView.setState(MicState.ERROR, "Didn't catch anything", canRetry = false)
                    return
                }

                // Transcription succeeded: safely delete cached file
                file.delete()
                lastRecordedAudioFile = null

                val ic = currentInputConnection
                if (ic != null) {
                    val beforeCursor = ic.getTextBeforeCursor(1, 0)
                    val textToInsert = if (!beforeCursor.isNullOrEmpty() && !beforeCursor[0].isWhitespace()) {
                        " $text"
                    } else {
                        text
                    }

                    ic.beginBatchEdit()
                    ic.commitText(textToInsert, 1)
                    ic.endBatchEdit()

                    keyboardView.setState(MicState.DONE)
                    handler.postDelayed({
                        if (::keyboardView.isInitialized &&
                            keyboardView.micButton.currentState == MicState.DONE) {
                            keyboardView.setState(MicState.IDLE)
                        }
                    }, 1000)
                } else {
                    keyboardView.setState(MicState.ERROR, "No input field active", canRetry = false)
                }
            }
            is TranscriptionResult.Error -> {
                // Keep file saved in lastRecordedAudioFile and enable retry!
                lastRecordedAudioFile = file
                keyboardView.setState(MicState.ERROR, result.message, canRetry = true)
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

    private fun vibrate(durationMs: Long = 20) {
        if (!settings.hapticFeedbackEnabled) return
        val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        if (vibrator.hasVibrator()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(durationMs)
            }
        }
    }
}
