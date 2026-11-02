# Murmur

Murmur is a voice-only Android keyboard powered by Groq's Whisper API. Its sole purpose is voice typing.
It presents a simple UI with a large microphone button that records your voice, sends the audio to the Whisper API, and inserts the transcribed text into the currently active text field.

## Features
* Fast and accurate transcription using `whisper-large-v3-turbo` or `whisper-large-v3` models.
* Beautiful, minimalistic UI built natively with `DynamicAnimation`.
* Clean handling of rate limits and long recordings (caps at 5 minutes).

## Setup
1. Get a free Groq API key from [Groq Console](https://console.groq.com/keys).
2. Install the `murmur-debug-apk` artifact produced by GitHub Actions.
3. Open the Murmur app from your launcher to view the setup screen.
4. Paste your Groq API key and choose your preferred model.
5. Enable the keyboard in Android Settings and grant Microphone permission.
6. Select Murmur as your current keyboard.
7. Tap the microphone button in any text field to start speaking!

## Limitations
* Because the free tier of the Groq API has rate limits (Requests per minute, audio seconds per hour), you might hit them if you dictate extensively. The app will gracefully indicate when rate limits are hit.

## Building Locally
* Requires Android Studio or a local JDK 17 setup.
* Run `./gradlew assembleDebug` to build the debug APK.
