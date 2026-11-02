# Murmur 🎙️

Murmur is a sleek, ultra-fast, voice-only Android keyboard powered by Groq's Whisper API. Its sole mission is seamless speech-to-text dictation across any app.

It features a minimalistic UI with a responsive glowing microphone orb, real-time audio amplitude ripples, and instant transcription powered by Groq.

---

## ✨ Features
* **Lightning-Fast Dictation**: Powered by Groq's `whisper-large-v3-turbo` (fast) or `whisper-large-v3` (high accuracy).
* **Smooth, Modern UI**: Deep navy theme with breathing glow idle state, amplitude-reactive expanding ripples while speaking, and smooth state animations.
* **In-App API Key Testing**: Instant connection and credentials verification right from the setup screen.
* **Persistent Settings**: Integrated with Android Auto Backup (`backup_rules.xml` & `data_extraction_rules.xml`) — your API key and preferences safely persist across app updates and reinstalls.
* **Rate Limits & Long Dictations**: Graceful error handling for Groq rate limits, with support for recordings capped at up to 5 minutes.
* **Haptic Feedback**: Subtle vibration cues on recording start, stop, and completion.

---

## 🚀 Setup & Usage

1. **Get an API Key**: Grab a free Groq API key from the [Groq Console](https://console.groq.com/keys).
2. **Install the APK**:
   * Build locally via Gradle or download the debug APK.
3. **Configure Murmur**:
   * Open **Murmur** from your launcher.
   * Paste your Groq API key and tap **Test API Key** to verify the connection.
   * Follow the setup steps to **Enable Keyboard** in Android Settings and grant **Microphone** permission.
4. **Start Dictating**:
   * Switch your active keyboard to Murmur (tap **Select Murmur Keyboard** or use the input method switcher).
   * Tap the microphone button in any text field, speak, and watch your words appear!

---

## 🛠️ Building Locally

### Requirements
* Local JDK 17 setup (or Android Studio)
* Android SDK (API 34)

### Build APK
```bash
./gradlew assembleDebug
```
The resulting APK will be at `app/build/outputs/apk/debug/app-debug.apk`.

### Install via ADB

**Over USB:**
```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

**Over Wireless Debugging:**
```bash
adb pair <phone-ip>:<pairing-port> <pairing-code>
adb connect <phone-ip>:<port>
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

---

## 🔒 Permissions
* `RECORD_AUDIO`: Required to capture voice dictation.
* `INTERNET`: Required to send audio to Groq Whisper API for transcription.
* `VIBRATE`: Optional haptic feedback when toggling dictation.
