package com.murmur.keyboard

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.murmur.keyboard.data.Settings

class SetupActivity : AppCompatActivity() {

    private lateinit var settings: Settings

    private lateinit var editApiKey: EditText
    private lateinit var btnSaveKey: Button

    private lateinit var btnEnableKeyboard: Button
    private lateinit var textKeyboardStatus: TextView

    private lateinit var btnGrantMic: Button
    private lateinit var textMicStatus: TextView

    private lateinit var spinnerModel: Spinner
    private lateinit var checkHaptics: CheckBox

    private lateinit var btnSelectKeyboard: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup)

        settings = Settings(this)

        editApiKey = findViewById(R.id.edit_api_key)
        btnSaveKey = findViewById(R.id.btn_save_key)

        btnEnableKeyboard = findViewById(R.id.btn_enable_keyboard)
        textKeyboardStatus = findViewById(R.id.text_keyboard_status)

        btnGrantMic = findViewById(R.id.btn_grant_mic)
        textMicStatus = findViewById(R.id.text_mic_status)

        spinnerModel = findViewById(R.id.spinner_model)
        checkHaptics = findViewById(R.id.check_haptics)

        btnSelectKeyboard = findViewById(R.id.btn_select_keyboard)

        setupViews()
    }

    override fun onResume() {
        super.onResume()
        refreshStatuses()
    }

    private fun setupViews() {
        editApiKey.setText(settings.apiKey)

        btnSaveKey.setOnClickListener {
            val key = editApiKey.text.toString().trim()
            settings.apiKey = key
            Toast.makeText(this, "API Key saved", Toast.LENGTH_SHORT).show()
        }

        btnEnableKeyboard.setOnClickListener {
            startActivity(Intent(AndroidSettings.ACTION_INPUT_METHOD_SETTINGS))
        }

        btnGrantMic.setOnClickListener {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 100)
        }

        // Model Spinner
        val models = arrayOf(Settings.MODEL_TURBO, Settings.MODEL_LARGE)
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, models)
        spinnerModel.adapter = adapter
        spinnerModel.setSelection(models.indexOf(settings.selectedModel).coerceAtLeast(0))
        spinnerModel.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                settings.selectedModel = models[position]
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // Haptics Toggle
        checkHaptics.isChecked = settings.hapticFeedbackEnabled
        checkHaptics.setOnCheckedChangeListener { _, isChecked ->
            settings.hapticFeedbackEnabled = isChecked
        }

        btnSelectKeyboard.setOnClickListener {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showInputMethodPicker()
        }
    }

    private fun refreshStatuses() {
        // Check Mic
        val micGranted = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (micGranted) {
            textMicStatus.text = "Status: Granted"
            textMicStatus.setTextColor(Color.parseColor("#4CAF50"))
            btnGrantMic.isEnabled = false
        } else {
            textMicStatus.text = "Status: Not granted"
            textMicStatus.setTextColor(Color.RED)
            btnGrantMic.isEnabled = true
        }

        // Check Keyboard
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        val list = imm.enabledInputMethodList
        val isEnabled = list.any { it.packageName == packageName }

        if (isEnabled) {
            textKeyboardStatus.text = "Status: Enabled"
            textKeyboardStatus.setTextColor(Color.parseColor("#4CAF50"))
            btnEnableKeyboard.isEnabled = false
        } else {
            textKeyboardStatus.text = "Status: Not enabled"
            textKeyboardStatus.setTextColor(Color.RED)
            btnEnableKeyboard.isEnabled = true
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100) {
            refreshStatuses()
        }
    }
}
