# Together Couple Location — Android + Node/PostgreSQL

**সংস্করণ:** 1.1 Romantic source project. **UI:** Royal Purple + Romantic Pink, হৃদয় অ্যানিমেটেড ইন্ট্রো ও দম্পতি কানেক্ট কোড শেয়ার। **Android:** 8.0+ (API 26+). **লোকেশন:** ১৫ সেকেন্ডের কাঙ্ক্ষিত বিরতি, ডিভাইস-নির্ভর। **হিস্ট্রি:** সর্বোচ্চ ৩০ দিন, গড়ে ১টি নমুনা/মিনিট; লাইভ অবস্থান আলাদাভাবে আপডেট হয়।

## কী কাজ করে

- বাংলাদেশি মোবাইল নম্বর, নাম, পাসওয়ার্ড দিয়ে রেজিস্ট্রেশন/লগইন। OTP/SMS যাচাই নেই।
- মেয়াদ ১০ মিনিটের ১০-অক্ষরের পেয়ার কোড দিয়ে দুজনের অ্যাকাউন্ট যুক্ত করা। এক অ্যাকাউন্ট এক সঙ্গীর সঙ্গে যুক্ত হতে পারে।
- প্রত্যেক ব্যবহারকারীকে নিজ ফোনে **শেয়ারিং চালু করুন** চাপতে হবে এবং Android Location permission দিতে হবে।
- অ্যাপ মিনিমাইজ বা স্ক্রিন অফ থাকলে চলমান Android **foreground location service** আপডেট দেওয়ার চেষ্টা করে এবং দৃশ্যমান নোটিফিকেশন দেখায়।
- লাইভ ম্যাপে সঙ্গীর সর্বশেষ GPS অবস্থান, কতক্ষণ আগে আপডেট হয়েছে এবং সম্ভাব্য নির্ভুলতা দেখায়। ম্যাপে টাইল হিসেবে OpenStreetMap ব্যবহার করে (ইন্টারনেট প্রয়োজন)।
- ৩০ দিনের হিস্ট্রি, দিন অনুযায়ী ফিল্টার, পুরোনো রেকর্ড দেখার ব্যবস্থা। নিজের হিস্ট্রি বা অ্যাকাউন্ট ডিলিট করা যায়।
- দুজনের যে কেউ শেয়ারিং বন্ধ করতে, পেয়ার বিচ্ছিন্ন করতে বা অ্যাকাউন্ট মুছতে পারবেন। বিচ্ছিন্ন করলে উভয়ের পুরোনো পেয়ার লোকেশন রেকর্ড মুছে যায় এবং শেয়ারিং off হয়।
- লগইন টোকেন JavaScript-এ না দিয়ে Android Keystore-এর AES/GCM এনক্রিপশনে সংরক্ষিত। HTTPS-only API।

**ফোন দিয়ে APK বিল্ড করার সম্পূর্ণ Termux কমান্ড:** `TERMUX_INSTALL_BN.md` দেখুন।

## ১. সার্ভার Render-এ চালু করুন

1. আগে একটি PostgreSQL ডেটাবেজ তৈরি করুন (Render Postgres, Neon অথবা PostgreSQL-compatible provider)। `DATABASE_URL` সংগ্রহ করুন।
2. এই ZIP-এর পুরো contents নতুন GitHub repo-র root-এ আপলোড করুন; `server`, `android`, `.github`, `render.yaml` ফোল্ডার একই লেভেলে থাকবে।
3. Render Dashboard → New → Blueprint → সেই GitHub repo নির্বাচন করে `render.yaml` ব্যবহার করুন। বিকল্পভাবে New Web Service ব্যবহার করলে **Root Directory:** `server`, **Build Command:** `npm install --omit=dev`, **Start Command:** `npm start`।
4. Render Environment-এ `DATABASE_URL`, ৩২+ অক্ষরের আলাদা `JWT_SECRET` এবং `INVITE_SECRET`, `NODE_ENV=production` দিন। SSL প্রয়োজন হলে `DB_SSL=true` দিন। `render.yaml` ব্যবহার করলে সিক্রেটগুলো স্বয়ংক্রিয় তৈরি হবে, তবে DATABASE_URL দিতে হবে।
5. সার্ভার চালু হলে `https://YOUR-SERVICE.onrender.com/health` খুলে `ok:true` আছে কি না দেখুন। প্রথম স্টার্টে `schema.sql` স্বয়ংক্রিয়ভাবে প্রয়োগ হয়।

**নোট:** Render-এ ঘুমিয়ে পড়া/free service থাকলে প্রথম সার্ভার অনুরোধে দেরি হতে পারে। PostgreSQL storage ও নিয়মিত uptime দরকার। `DATABASE_URL` বা পাসওয়ার্ড কাউকে দেবেন না।

## ২. Android APK তৈরি — GitHub Actions (ফোন থেকেই)

1. সম্পূর্ণ ফোল্ডার GitHub repo root-এ আপলোড করুন।
2. GitHub → Actions → **Build Android APK** → Run workflow চাপুন। `main`-এ push করলে Android বা workflow ফাইল বদলালেও বিল্ড শুরু হতে পারে।
3. কাজ সফল হলে ওই workflow run-এর **Artifacts** থেকে `TogetherCoupleLocation-debug-apk` ডাউনলোড করুন। ZIP-এর মধ্যে `app-debug.apk` থাকবে।
4. আপনার Android-এ APK ইনস্টল করুন। এটি **Debug APK** (Play Store release signature নয়)।
5. অ্যাপ খুলে **সার্ভার সংযোগ** ঘরে আপনার Render-এর `https://...onrender.com` URL দিয়ে **সংরক্ষণ** চাপুন। তারপরে রেজিস্টার করুন।

> এই প্যাকেজে Android source code ও GitHub Actions build workflow আছে। এই পরিবেশে Android SDK/Gradle না থাকায় APK কম্পাইল করে পরীক্ষা করা হয়নি। তাই compile result যাচাইয়ের জন্য GitHub Actions log প্রয়োজন।

## ৩. দুটো ফোনে ব্যবহার

1. দুই ফোনে একই APK ইনস্টল করুন, একই সার্ভার URL দিন।
2. দুইজন দুটি আলাদা নম্বর/পাসওয়ার্ড দিয়ে রেজিস্টার করুন।
3. প্রথম ফোন → **কানেক্ট** → **ইনভাইট কোড তৈরি**।
4. দ্বিতীয় ফোন → **কানেক্ট** → কোড বসিয়ে কানেক্ট করুন।
5. দুই ফোনের **হোম** → **শেয়ারিং চালু করুন** চাপুন, লোকেশন অনুমতি দিন। Notification permission চাইলে দেওয়াই ভালো।
6. সঙ্গীর লোকেশন হোম ম্যাপে দেখতে পারবেন; **হিস্ট্রি** পেজে ৩০ দিনের নমুনা, দিনের ফিল্টার দেখতে পারবেন।
7. বন্ধ করতে **হোম → বন্ধ করুন**, অথবা চলমান নোটিফিকেশনের **শেয়ারিং বন্ধ করুন** ব্যবহার করুন।

## গুরুত্বপূর্ণ বাস্তব সীমাবদ্ধতা

- Android/iOS-এ নিরবচ্ছিন্ন ১০–১৫ সেকেন্ড GPS গ্যারান্টি করা যায় না। GPS signal, battery saver, permissions, মোবাইল ডেটা, phone manufacturer-এর restrictions, server response সময় প্রভাব ফেলে। GPS অফ বা ইন্টারনেট অফ হলে নতুন লোকেশন পৌঁছাবে না।
- Android Location Foreground Service চলছে এমন নোটিফিকেশন দেখা যাবে। গোপন বা অনুমতিবিহীন ট্র্যাকিং নেই। অ্যাপ force-stop হলে service বন্ধ হবে; এটি গোপনে নিজে চালু হবে না।
- অনলাইন OpenStreetMap tiles ব্যবহার করা হয়েছে; বড় পরিসরে চালালে উপযুক্ত paid/self-hosted tile provider ব্যবহার করুন এবং OpenStreetMap tile usage policy মেনে চলুন।
- প্রাথমিক সংস্করণে চ্যাট, ভয়েস কল, SOS, geofence, SMS OTP, battery percentage ও push notifications **বাস্তবায়িত হয়নি**। এগুলো আলাদা module হিসেবে যোগ করতে হবে।
- উৎপাদনে যাওয়ার আগে বাস্তব দুই ফোনে GPS/background/battery tests, PostgreSQL integration tests, Android release signing, privacy policy, Play Store policy review, নিরাপত্তা audit প্রয়োজন।
- এটি ফোন নম্বরের মালিকানা SMS দিয়ে যাচাই করে না; ফোন নম্বর login identifier মাত্র। সার্ভার-পাসওয়ার্ড গোপন রাখুন।

## ফাইল পরিচিতি

```
TogetherCoupleLocation/
  android/
    app/src/main/assets/index.html           # Royal Purple UI + map logic
    app/src/main/java/com/together/couple/
      MainActivity.java                        # Login/permissions/native bridge
      ShareService.java                        # Background foreground GPS service
      ApiClient.java                           # HTTPS API client
      TokenVault.java                          # Encrypted login token
    app/src/main/AndroidManifest.xml
  server/
    index.js                                   # API/auth/pair/location/privacy/history
    schema.sql                                 # PostgreSQL schema
    helpers.js                                 # Validation helpers
    helpers.test.js                            # Automated helper tests
  .github/workflows/build-apk.yml             # GitHub Actions Android builder
  render.yaml                                  # Render Blueprint
  design-preview.html                         # Browser-only sample dashboard
```

## টেস্ট

`cd server && npm install && npm run check` — backend JS syntax ও unit tests। Live API পরীক্ষা করতে বাস্তব PostgreSQL ও secrets প্রয়োজন। `design-preview.html` শুধু স্ট্যাটিক নমুনা; বাস্তব লোকেশন নয়।
