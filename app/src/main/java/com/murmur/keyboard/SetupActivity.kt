package com.murmur.keyboard

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings as AndroidSettings
import android.text.Editable
import android.text.SpannableString
import android.text.TextWatcher
import android.text.method.LinkMovementMethod
import android.text.style.ForegroundColorSpan
import android.text.style.UnderlineSpan
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.murmur.keyboard.data.Settings
import com.murmur.keyboard.net.GroqClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SetupActivity : AppCompatActivity() {

    private lateinit var settings: Settings
    private val groqClient = GroqClient()
    private val scope = CoroutineScope(Dispatchers.Main + Job())

    private lateinit var editApiKey: TextInputEditText
    private lateinit var textApiStatus: TextView
    private lateinit var btnTestApiKey: Button
    private lateinit var progressTestApi: ProgressBar
    private lateinit var textApiTestResult: TextView

    private lateinit var btnEnableKeyboard: Button
    private lateinit var textKeyboardStatus: TextView
    private lateinit var textKeyboardStatusIcon: TextView

    private lateinit var btnGrantMic: Button
    private lateinit var textMicStatus: TextView
    private lateinit var textMicStatusIcon: TextView

    private lateinit var radioModel: RadioGroup
    private lateinit var radioTurbo: RadioButton
    private lateinit var radioLarge: RadioButton
    private lateinit var checkHaptics: CheckBox

    private lateinit var btnSelectKeyboard: Button

    // Debounce handler for API key auto-save
    private val debounceHandler = Handler(Looper.getMainLooper())
    private var debounceRunnable: Runnable? = null
    private var rootView: View? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup)

        rootView = findViewById(android.R.id.content)
        settings = Settings(this)

        editApiKey          = findViewById(R.id.edit_api_key)
        textApiStatus       = findViewById(R.id.text_api_status)
        btnTestApiKey       = findViewById(R.id.btn_test_api_key)
        progressTestApi     = findViewById(R.id.progress_test_api)
        textApiTestResult   = findViewById(R.id.text_api_test_result)

        btnEnableKeyboard   = findViewById(R.id.btn_enable_keyboard)
        textKeyboardStatus  = findViewById(R.id.text_keyboard_status)
        textKeyboardStatusIcon = findViewById(R.id.text_keyboard_status_icon)

        btnGrantMic         = findViewById(R.id.btn_grant_mic)
        textMicStatus       = findViewById(R.id.text_mic_status)
        textMicStatusIcon   = findViewById(R.id.text_mic_status_icon)

        radioModel          = findViewById(R.id.radio_model)
        radioTurbo          = findViewById(R.id.radio_turbo)
        radioLarge          = findViewById(R.id.radio_large)
        checkHaptics        = findViewById(R.id.check_haptics)

        btnSelectKeyboard   = findViewById(R.id.btn_select_keyboard)

        // Force dark text color so it's visible on white card background regardless of theme
        editApiKey.setTextColor(android.graphics.Color.parseColor("#1C1B1F"))
        editApiKey.setHintTextColor(android.graphics.Color.parseColor("#888888"))

        setupGroqLink()
        setupApiKey()
        setupEnableKeyboard()
        setupMicPermission()
        setupModel()
        setupHaptics()
        setupSelectKeyboard()
    }

    override fun onResume() {
        super.onResume()
        refreshStatuses()
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.coroutineContext[Job]?.cancel()
    }

    // ── Groq console link ──
    private fun setupGroqLink() {
        val tv = findViewById<TextView>(R.id.text_groq_link)
        val fullText = "Get a free key at console.groq.com →"
        val ss = SpannableString(fullText)
        val start = fullText.indexOf("console.groq.com")
        val end = start + "console.groq.com".length
        ss.setSpan(UnderlineSpan(), start, end, 0)
        val primaryColor = try {
            val typedValue = android.util.TypedValue()
            theme.resolveAttribute(com.google.android.material.R.attr.colorPrimary, typedValue, true)
            typedValue.data
        } catch (e: Exception) {
            Color.parseColor("#6750A4")
        }
        ss.setSpan(ForegroundColorSpan(primaryColor), 0, fullText.length, 0)
        tv.text = ss
        tv.movementMethod = LinkMovementMethod.getInstance()
        tv.setOnClickListener {
            try {
                val intent = Intent(Intent.ACTION_VIEW,
                    android.net.Uri.parse("https://console.groq.com"))
                startActivity(intent)
            } catch (e: Exception) { /* no browser */ }
        }
    }

    // ── API Key: auto-save with debounce + immediate save on Done + Test Button ──
    private fun setupApiKey() {
        editApiKey.setText(settings.apiKey)
        updateApiStatus(settings.apiKey.isNotBlank())

        editApiKey.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                debounceRunnable?.let { debounceHandler.removeCallbacks(it) }
                val text = s?.toString()?.trim() ?: ""
                debounceRunnable = Runnable {
                    saveApiKey(text)
                }
                debounceHandler.postDelayed(debounceRunnable!!, 600)
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        // Also save immediately when user presses Done on keyboard
        editApiKey.setOnEditorActionListener { _, _, _ ->
            debounceRunnable?.let { debounceHandler.removeCallbacks(it) }
            saveApiKey(editApiKey.text?.toString()?.trim() ?: "")
            false
        }

        // Test API Key button
        btnTestApiKey.setOnClickListener {
            val key = editApiKey.text?.toString()?.trim() ?: ""
            if (key.isBlank()) {
                textApiTestResult.visibility = View.VISIBLE
                textApiTestResult.text = "Please enter an API key first"
                textApiTestResult.setTextColor(resolveErrorColor())
                return@setOnClickListener
            }

            saveApiKey(key)

            btnTestApiKey.isEnabled = false
            progressTestApi.visibility = View.VISIBLE
            textApiTestResult.visibility = View.VISIBLE
            textApiTestResult.text = "Testing connection..."
            textApiTestResult.setTextColor(Color.parseColor("#666666"))

            scope.launch {
                val (isValid, message) = withContext(Dispatchers.IO) {
                    groqClient.validateApiKey(key)
                }
                btnTestApiKey.isEnabled = true
                progressTestApi.visibility = View.GONE
                textApiTestResult.text = message
                if (isValid) {
                    textApiTestResult.setTextColor(Color.parseColor("#4CAF50"))
                    updateApiStatus(true)
                } else {
                    textApiTestResult.setTextColor(resolveErrorColor())
                }
            }
        }
    }

    private fun saveApiKey(text: String) {
        settings.apiKey = text
        updateApiStatus(text.isNotBlank())
        if (text.isNotBlank()) {
            rootView?.let { view ->
                Snackbar.make(view, "API key saved ✓", Snackbar.LENGTH_SHORT).show()
            }
        }
    }

    private fun updateApiStatus(hasKey: Boolean) {
        if (hasKey) {
            textApiStatus.text = "✓"
            textApiStatus.setTextColor(Color.parseColor("#4CAF50"))
        } else {
            textApiStatus.text = "✗"
            textApiStatus.setTextColor(resolveErrorColor())
        }
    }

    // ── Enable keyboard ──
    private fun setupEnableKeyboard() {
        btnEnableKeyboard.setOnClickListener {
            startActivity(Intent(AndroidSettings.ACTION_INPUT_METHOD_SETTINGS))
        }
    }

    // ── Mic permission ──
    private fun setupMicPermission() {
        btnGrantMic.setOnClickListener {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_MIC)
        }
    }

    // ── Model selection via RadioGroup ──
    private fun setupModel() {
        when (settings.selectedModel) {
            Settings.MODEL_LARGE -> radioLarge.isChecked = true
            else                 -> radioTurbo.isChecked = true
        }
        radioModel.setOnCheckedChangeListener { _, checkedId ->
            settings.selectedModel = when (checkedId) {
                R.id.radio_large -> Settings.MODEL_LARGE
                else             -> Settings.MODEL_TURBO
            }
        }
    }

    // ── Haptics ──
    private fun setupHaptics() {
        checkHaptics.isChecked = settings.hapticFeedbackEnabled
        checkHaptics.setOnCheckedChangeListener { _, isChecked ->
            settings.hapticFeedbackEnabled = isChecked
        }
    }

    // ── Select keyboard (show picker) ──
    private fun setupSelectKeyboard() {
        btnSelectKeyboard.setOnClickListener {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showInputMethodPicker()
        }
    }

    // ── Refresh status indicators ──
    private fun refreshStatuses() {
        // Mic
        val micGranted = checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (micGranted) {
            textMicStatus.text = "Status: Granted"
            textMicStatus.setTextColor(Color.parseColor("#4CAF50"))
            textMicStatusIcon.text = "✓"
            textMicStatusIcon.setTextColor(Color.parseColor("#4CAF50"))
            btnGrantMic.isEnabled = false
        } else {
            textMicStatus.text = "Status: Not granted"
            textMicStatus.setTextColor(resolveErrorColor())
            textMicStatusIcon.text = "✗"
            textMicStatusIcon.setTextColor(resolveErrorColor())
            btnGrantMic.isEnabled = true
        }

        // Keyboard enabled
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        val isEnabled = imm.enabledInputMethodList.any { it.packageName == packageName }
        if (isEnabled) {
            textKeyboardStatus.text = "Status: Enabled"
            textKeyboardStatus.setTextColor(Color.parseColor("#4CAF50"))
            textKeyboardStatusIcon.text = "✓"
            textKeyboardStatusIcon.setTextColor(Color.parseColor("#4CAF50"))
            btnEnableKeyboard.isEnabled = false
        } else {
            textKeyboardStatus.text = "Status: Not enabled"
            textKeyboardStatus.setTextColor(resolveErrorColor())
            textKeyboardStatusIcon.text = "✗"
            textKeyboardStatusIcon.setTextColor(resolveErrorColor())
            btnEnableKeyboard.isEnabled = true
        }

        // API key status
        updateApiStatus(settings.apiKey.isNotBlank())
    }

    private fun resolveErrorColor(): Int {
        return try {
            val tv = android.util.TypedValue()
            theme.resolveAttribute(com.google.android.material.R.attr.colorError, tv, true)
            tv.data
        } catch (e: Exception) {
            Color.RED
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_MIC) {
            refreshStatuses()
        }
    }

    companion object {
        private const val REQUEST_MIC = 100
    }
}
