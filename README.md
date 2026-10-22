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
