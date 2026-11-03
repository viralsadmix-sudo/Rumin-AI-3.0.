# Rumin AI 3.0

রুমিন AI 3.0 Android app source project.

## APK তৈরি
1. এই repository-র **Actions** ট্যাবে যান।
2. **Build Rumin AI 3.0 APK** workflow খুলুন।
3. সাম্প্রতিক workflow run-এর ফলাফল দেখুন।
4. Build সফল হলে **Artifacts** থেকে `Rumin-AI-3.0-APK` ডাউনলোড করুন।
5. ZIP extract করে `app-release.apk` Android ফোনে ইনস্টল করুন।

Workflow প্রতিবার `main` branch-এ নতুন commit হলে নিজে থেকে APK build করার চেষ্টা করে।

## গুরুত্বপূর্ণ
- এটি পরীক্ষামূলক APK; release APK-টি debug signing key দিয়ে signed, Play Store release signing নয়।
- Gemini/Groq API key অ্যাপের Settings-এ ব্যবহারকারীর ডিভাইসে সংরক্ষিত হয়। API key গোপন রাখুন।
- Gemini model availability/API access account ও Google API configuration-এর ওপর নির্ভরশীল।
- Native Bengali voice recognition Android-এর speech recognition service এবং microphone permission-এর ওপর নির্ভরশীল।


## AI Studio upgrade (development branch)

The additive upgrade is being developed on branch feature/ai-video-voice-studio. The existing Android project is Kotlin + WebView (not a Flutter module); the original Home, Chat, Actions and Settings screens are retained.

### Current Studio integration status
- Added a separate AI Studio screen with managed-backend connection settings.
- Added Android document picker support for video/audio file inputs.
- Added a FastAPI backend foundation with bearer-token protection, Redis/RQ jobs and S3-compatible object storage.
- Added FFmpeg scene detection/MP4 assembly and optional Whisper subtitle burn-in.
- Added ElevenLabs voice-clone and Bengali/English TTS API routes. Voice cloning requires an explicit consent checkbox.
- Added backend CI smoke tests.

### Important limitations
This is an integration increment, not a finished production feature set. Prompt-driven editing/scene rearrangement, motion graphics, intro/outro generation, background music and sound-effect mixing are not implemented. Android UI/API calls have not yet been validated against a deployed managed backend or a paid/eligible ElevenLabs account. A release APK has not been built or tested as part of this change.

See backend/README.md and backend/.env.example for managed deployment and environment configuration. Never place ElevenLabs, Gemini, Groq or backend provider secrets inside the APK or commit them to Git.
