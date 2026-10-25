# AI Command Centre V1 — Android Termux

এই ফোল্ডারটি ফোনে Termux দিয়ে চালানোর জন্য। এটি RUMI AI Android app-এর মূল কোড পরিবর্তন করে না।

## চালু করার নিয়ম
1. GitHub-এর `termux-ai-command-centre` branch ZIP ডাউনলোড করুন এবং Android Downloads-এ Extract করুন।
2. Termux খুলে চালান:

```sh
pkg update && pkg upgrade -y
pkg install python unzip -y
termux-setup-storage
cd ~/storage/downloads
```

Extract করা ফোল্ডারের ভেতর `termux-command-centre` ফোল্ডারে যান। উদাহরণ:

```sh
cd ~/storage/downloads/Rumin-AI-3.0.-termux-ai-command-centre/termux-command-centre
python -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
cp .env.example .env
uvicorn app:app --host 127.0.0.1 --port 8000
```

Chrome-এ `http://127.0.0.1:8000` খুলুন। Termux-এর সার্ভার চলতে থাকতে হবে।

## সীমাবদ্ধতা ও নিরাপত্তা
- ডেটা ফোনের SQLite database-এ থাকবে।
- Task, Notes, daily report এবং approval request local dashboard-এ কাজ করে।
- Approval Queue শুধু সিদ্ধান্ত সংরক্ষণ করে; অনুমোদন দিলেও browser/email/calendar action নিজে চালায় না।
- Telegram/Hermes সংযোগ এই minimal phone build-এ সক্রিয় নয়। API key বা bot token HTML-এ দেবেন না।
- `127.0.0.1` শুধু এই ফোনে পাওয়া যায়; এটি public website নয়। Android ব্যাটারি সেটিংস background process বন্ধ করতে পারে।