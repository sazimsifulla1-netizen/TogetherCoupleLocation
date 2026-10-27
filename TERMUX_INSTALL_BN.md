# Together Romantic v1.1 — Termux থেকে GitHub Actions APK Build

এই নির্দেশনায় APK-র কোড GitHub-এ আপলোড করে GitHub Actions-এ Build হবে; Termux-এ Android SDK বসাতে হবে না। **Render PostgreSQL ও API server আলাদা Deploy করতে হবে**, না হলে APK ইনস্টল হলেও লাইভ লোকেশন কাজ করবে না।

## ১. ZIP ফোনের Downloads-এ সেভ করুন। Termux খুলে নিচের ব্লকটি দিন

```bash
pkg update -y && pkg upgrade -y
pkg install -y git gh unzip
termux-setup-storage
mkdir -p ~/together-work
cd ~/together-work
unzip -o ~/storage/downloads/Together_Couple_Location_Romantic_v1.1.zip
cd TogetherCoupleLocation_Romantic_v1_1
```

যদি Download করা ZIP-এর নাম আলাদা হয়, `ls ~/storage/downloads/` চালিয়ে আসল নামটি ব্যবহার করুন। `termux-setup-storage`-এর অনুমতি দিন।

## ২. GitHub-এ লগইন করুন (টোকেন চ্যাটে দেবেন না)

```bash
gh auth login --hostname github.com --web --git-protocol https
gh auth setup-git
GH_NAME=$(gh api user -q .login)
GH_ID=$(gh api user -q .id)
git config --global user.name "$GH_NAME"
git config --global user.email "$GH_ID+$GH_NAME@users.noreply.github.com"
git init -b main
git add .
git commit -m "Together Romantic Edition v1.1"
gh repo create TogetherCoupleLocation --private --source=. --remote=origin --push
```

ইমেইল অটোমেটিক GitHub noreply ইমেইল হিসেবে সেট হবে। রিপোজিটরি নাম আগে থেকে থাকলে অন্য নাম ব্যবহার করুন। **Private** রাখুন। এই কমান্ড নতুন repo তৈরি করবে, আগের FunX-কে স্পর্শ করবে না।

## ৩. GitHub Actions থেকে APK Build করুন

```bash
gh workflow run build-apk.yml
gh run list --workflow build-apk.yml --limit 5
gh run watch
```

`gh run watch` এ একটি run নির্বাচন করুন। যদি workflow ইতিমধ্যে push-এর কারণে শুরু হয়ে থাকে, আরেকটি run না দিলেও হবে। বিল্ড সফল হলে:

```bash
mkdir -p ~/storage/downloads/TogetherAPK
gh run download -n TogetherCoupleLocation-debug-apk -D ~/storage/downloads/TogetherAPK
ls -lh ~/storage/downloads/TogetherAPK/
termux-open --view --content-type application/vnd.android.package-archive ~/storage/downloads/TogetherAPK/app-debug.apk
```

প্রয়োজন হলে Android Settings → Install unknown apps → Termux-কে অনুমতি দিন। এই APK **debug build**। দ্বিতীয় ফোনেও একই APK ইনস্টল করুন।

## ৪. Backend (প্রয়োজনীয়)

1. একটি PostgreSQL database (Render/Neon ইত্যাদি) তৈরি করে `DATABASE_URL` সংগ্রহ করুন।
2. Render → New → Blueprint → নতুন `TogetherCoupleLocation` repo নির্বাচন করে `render.yaml` deploy করুন।
3. `DATABASE_URL` দিয়ে দিন। `JWT_SECRET` ও `INVITE_SECRET` Blueprint-এ generate হয়। PostgreSQL SSL certificate configuration provider অনুযায়ী যাচাই করুন।
4. সার্ভার URL-এর শেষ `/health` পেজে `{ "ok": true }` আসে কি না দেখুন।
5. দুই ফোনে Together অ্যাপের Login screen → সার্ভার সংযোগে এক HTTPS URL → সংরক্ষণ → **কানেকশন টেস্ট**।
6. দুজনে আলাদা অ্যাকাউন্ট খুলবেন, Couple Connect কোড পাঠাবেন, এবং প্রত্যেকে নিজের ফোনে Share চালু করবেন।

## সমস্যা হলে

`gh run list --workflow build-apk.yml --limit 10` এবং `gh run view --log-failed` চালিয়ে কারণ দেখুন। `termux-open` না হলে ফাইল ম্যানেজার থেকে Downloads/TogetherAPK/app-debug.apk খুলুন। `gh run download` যদি একাধিক run জিজ্ঞাসা করে, সফল run ID দিয়ে `gh run download RUN_ID -n TogetherCoupleLocation-debug-apk -D ~/storage/downloads/TogetherAPK` দিন।

সীমাবদ্ধতা: এই পরিবেশে Android SDK দিয়ে APK compile বা দুই ফোনের GPS ট্র্যাকিং বাস্তব পরীক্ষা করা হয়নি। ১০–১৫ সেকেন্ড আপডেট লক্ষ্য, নিশ্চিত গ্যারান্টি নয়। ফোন বন্ধ/force stop/শেয়ারিং off হলে নতুন লোকেশন যাবে না।
