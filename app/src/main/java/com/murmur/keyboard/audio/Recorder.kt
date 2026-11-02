package com.murmur.keyboard.audio

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import java.io.File
import java.io.IOException

class Recorder(private val context: Context) {
    private var mediaRecorder: MediaRecorder? = null
    var outputFile: File? = null
        private set

    private var isRecording = false

    fun startRecording(onMaxDurationReached: () -> Unit): Boolean {
        if (isRecording) return false

        val cacheDir = context.cacheDir
        outputFile = File(cacheDir, "murmur_audio_\${System.currentTimeMillis()}.m4a")

        mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }

        return try {
            mediaRecorder?.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioSamplingRate(16000)
                setAudioChannels(1)
                setAudioEncodingBitRate(64000)
                setOutputFile(outputFile?.absolutePath)
                setMaxDuration(5 * 60 * 1000) // 5 minutes cap

                setOnInfoListener { _, what, _ ->
                    if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) {
                        onMaxDurationReached()
                    }
                }

                prepare()
                start()
            }
            isRecording = true
            true
        } catch (e: Exception) {
            Log.e("Recorder", "Failed to start recording", e)
            release()
            false
        }
    }

    fun stopRecording() {
        if (!isRecording) return
        try {
            mediaRecorder?.stop()
        } catch (e: Exception) {
            Log.e("Recorder", "Failed to stop recording (maybe too short?)", e)
        } finally {
            release()
        }
    }

    fun getMaxAmplitude(): Int {
        return if (isRecording) {
            try {
                mediaRecorder?.maxAmplitude ?: 0
            } catch (e: Exception) {
                0
            }
        } else {
            0
        }
    }

    fun release() {
        isRecording = false
        try {
            mediaRecorder?.release()
        } catch (e: Exception) {
            Log.e("Recorder", "Failed to release", e)
        }
        mediaRecorder = null
    }

    fun deleteRecording() {
        outputFile?.let {
            if (it.exists()) {
                it.delete()
            }
        }
        outputFile = null
    }
}
