# FoxScan Lite — সম্পূর্ণ আর্কিটেকচার ও ব্লুপ্রিন্ট

এই ডকুমেন্টটা এমনভাবে লেখা যেন যেকোনো agent এটা পড়ে পুরো অ্যাপটা শূন্য থেকে বানাতে পারে।

## ০. মূল সীমাবদ্ধতা (ভাঙলে প্রজেক্ট ব্যর্থ)

1. **কোনো external dependency নেই**: ML Kit নেই, OpenCV নেই, Play services নেই, JNI/native lib নেই। `app/build.gradle.kts`-এর dependencies-এ শুধু AndroidX কোর + Compose + CameraX। ডিটেকশন লাইভ প্রিভিউতেই চলে, তাই কোনো বাহ্যিক স্ক্যানার মডিউল দরকারই পড়ে না।
2. **APK সাইজ টার্গেট < 2 MB** (বর্তমানে 1.69 MB)। তাই Compose ব্যবহার করলে R8 full mode + `isMinifyEnabled = true` জরুরি।
3. **মেমরি বাজেট**: বিল্ড হোস্টে `org.gradle.jvmargs=-Xmx1536m -XX:MaxMetaspaceSize=512m` (gradle.properties) — এটা ছাড়া বিল্ড OOM করে।
4. minSdk 24, targetSdk/compileSdk 36, applicationId `com.rebelroot.foxscan`।

## ১. রিপো স্ট্রাকচার

```
FoxScan/
├── settings.gradle.kts          # pluginManagement + repos (google, mavenCentral)
├── build.gradle.kts             # রুট: AGP + Kotlin প্লাগইন ঘোষণা
├── gradle.properties            # jvmargs (উপরে দেখো), AndroidX flags
├── local.properties             # sdk.dir=<android-sdk-path>
├── app/
│   ├── build.gradle.kts
│   └── src/main/
│       ├── AndroidManifest.xml  # CAMERA permission, FileProvider
│       ├── res/xml/file_paths.xml
│       └── java/com/rebelroot/foxscan/
│           ├── MainActivity.kt          # (~606 লাইন) UI + CameraX + স্ক্রিনগুলো
│           └── engine/                  # নিজস্ব ইঞ্জিন, UI থেকে সম্পূর্ণ আলাদা
│               ├── Quad.kt              # (49) ডাটা মডেল
│               ├── QuadDetector.kt      # (162) ফ্রেম-প্রতি ডিটেক্টর
│               ├── QuadStreamAnalyzer.kt# (150) টেম্পোরাল স্টেট মেশিন
│               └── ImageOps.kt          # (173) ওয়ার্প/ফিল্টার/PDF
```

## ২. ডাটা মডেল (engine/Quad.kt)

```kotlin
/** ডকুমেন্ট কোয়াড, normalized (0..1) কোঅর্ডিনেট, কর্নার TL,TR,BR,BL */
data class Quad(tlX, tlY, trX, trY, brX, brY, blX, blY: Float) {
    val isValid: Boolean        // সব ৮টা মান finite
    fun isEmpty(): Boolean      // সব শূন্য = invalid sentinel
    fun area(): Float           // shoelace / 2
    fun convex(): Boolean       // ক্রস-প্রোডাক্ট সাইন চেক
    fun inset(r: Float): Quad   // centroid-এর দিকে r (0..1) অনুপাতে সংকোচন
    fun points(): List<Pair<Float, Float>>
}
```

**কেন normalized:** ডিটেক্টর 160×120 গ্রিডে কাজ করে, প্রিভিউ অন্য রেজোলিউশনে — normalized রাখলে প্রতিটা স্তরে স্কেল-স্বাধীন।

## ৩. ফ্রেম-প্রতি ডিটেক্টর (engine/QuadDetector.kt)

`object QuadDetector` — **প্রতি ফ্রেম স্বাধীন, stateless**। নয়েজি হওয়াটা স্বাভাবিক; মসৃণকরণ `QuadStreamAnalyzer`-এর কাজ।

পাইপলাইন: Y-plane বাইট → **160×120 স্যাম্পল গ্রিড** → rotation ঠিক করা (90/270-তে dim swap) → **Sobel gradient** (gx, gy, magnitude) → **hysteresis threshold**: strong = max(2.5 × mean-gradient, 40), weak = 0.4 × strong → **1-pass hysteresis**: strong-এর সংলগ্ন weak পিক্সেল strong-এ উন্নীত → **largest 8-connected component** (explicit stack flood fill, সাইজ ≥ w×h/40) → **৪টা extreme corner**: min/max of (x+y) ও (x−y) → TL,TR,BR,BL অর্ডার → **ভ্যালিডেশন**: convex ∧ area ≥ 0.08 ∧ প্রতিটা বাহু ≥ ছোট dim-এর 0.08। ব্যর্থ হলে `null`।

```kotlin
object QuadDetector {
    const val DW = 160; const val DH = 120
    fun detect(luma: ByteArray, rowStride: Int, srcW: Int, srcH: Int, rotation: Int): Quad?
}
```

**পারফরম্যান্স:** 160×120 গ্রিডে পুরো পাইপলাইন ~1–3 ms/ফ্রেম (Kotlin, JVM-এ)। বেশি বড় গ্রিডে যেয়ো না, লাভ নেই — টেম্পোরাল ফিল্টারই আসল কাজ করে।

## ৪. টেম্পোরাল স্টেট মেশিন (engine/QuadStreamAnalyzer.kt)

FoxScan-এর নিজস্ব অটো-ক্যাপচার ইঞ্জিন। **এটাই অ্যাপের হৃদয়; কনস্ট্যান্টগুলো ভালো করে টিউন করা, হুবহু রাখতে হবে:**

```kotlin
class QuadStreamAnalyzer {
    enum class State { SEARCHING, ABOUT_TO_TRIGGER, TRIGGER, STABLE }
    companion object {
        const val QUEUE_SIZE = 90            // 30 fps-এ ৩ সেকেন্ড
        const val T3 = 0.05f                 // ৩-ফ্রেম উইন্ডো থ্রেশহোল্ড
        const val T20 = 0.025f               // ২০/৪০-ফ্রেম উইন্ডো থ্রেশহোল্ড
        const val MIN_FRAMES_3 = 3; const val MIN_FRAMES_20 = 20; const val MIN_FRAMES_40 = 40
        const val ABOUT_TO_TRIGGER_HOLD_US = 1_501_000L  // 1.5s + 1ms
        const val TRIGGER_HOLD_US = 1_001_000L           // 1.0s + 1ms
    }
    fun reset()              // নতুন পেজ ক্যাপচারের পরে অবশ্যই কল করো
    fun addQuad(quad: Quad?, nowUs: Long): State
    val displayQuad: Quad    // প্রদর্শনের জন্য ভ্যালিড ফ্রেমগুলোর মিয়ান
}
```

**স্টেট ট্রানজিশন টেবিল** (উপর থেকে নিচে প্রথম মিল):

| শর্ত | নতুন স্টেট |
|---|---|
| count < 3 | SEARCHING |
| শেষ ৩ ফ্রেম unstable (T3) | SEARCHING |
| শেষ ৩ ফ্রেম stable | ABOUT_TO_TRIGGER |
| count < 20 | ABOUT_TO_TRIGGER |
| শেষ ২০ ফ্রেম unstable (T20) | ABOUT_TO_TRIGGER |
| নতুন ফ্রেমের কোয়াড invalid | ABOUT_TO_TRIGGER |
| state-এ < 1.501 s | ABOUT_TO_TRIGGER |
| count < 40 | TRIGGER |
| শেষ ৪০ ফ্রেম unstable (T20) | TRIGGER |
| state-এ < 1.001 s | TRIGGER |
| বাকি সব | STABLE |

**স্থিরতা পরিমাপ:** উইন্ডোর ভ্যালিড (non-empty) ফ্রেমগুলোর mean কোয়াড → প্রতিটা কর্নার কোঅর্ডিনেটের RMS deviation → **৮টাই** থ্রেশহোল্ডের নিচে হলে stable। ট্রানজিশনে tAbout (ABOUT_TO_TRIGGER-এ প্রবেশ) ও tTrigger (TRIGGER-এ প্রবেশ) টাইমস্ট্যাম্প সেভ হয়। **ডিজাইন মূল্য:** ৩-ফ্রেম দ্রুত সেন্স → অন্তত ১.৫ সেকেন্ডের ২০-ফ্রেম স্থিরতা → ট্রিগার। এজন্যই ঝাপসা মুহূর্তে ক্যাপচার হয় না, আর হাত কাঁপলেও তাড়াতাড়ি ফায়ার হয় না। invalid (all-zero) ফ্রেম মিয়ান ও স্ট্যাবিলিটি থেকে বাদ — এজন্যই ডিটেক্টর একবার ফেল করলেও ওভারলে লাফায় না।

**UI-তে স্টেট ম্যাপিং:** SEARCHING → ওভারলে নীল/লাল (ডিটেক্ট হলে নীল), ABOUT_TO_TRIGGER → হলুদ, TRIGGER/STABLE → সবুজ + **TRIGGER-এ প্রথমবার ক্যাপচার ফায়ার** (STABLE = post-capture hold, আর ট্রিগার হয় না)।

## ৫. ইমেজ অপস (engine/ImageOps.kt)

```kotlin
object ImageOps {
    fun warp(src: Bitmap, q: Quad, inset: Float = 1.0f): Bitmap
        // Matrix.setPolyToPoly, বর্ডার inset-এর জন্য 0.96f ব্যবহার করো (এজ-হাগ এড়াতে)
    fun applyFilter(src: Bitmap, filter: Filter): Bitmap   // MAGIC, GRAY, BW, ORIGINAL
    private fun autoContrast(b: Bitmap)   // luminance হিস্টোগ্রাম স্ট্রেচ 1%–99%
    fun rotate(src: Bitmap, degrees: Int): Bitmap
    fun saveJpeg(b: Bitmap, file: File, quality: Int = 90): File
    fun exportPdf(pages: List<Bitmap>, out: File): File   // android.graphics.pdf.PdfDocument
    fun detectQuadOnBitmap(src: Bitmap): Quad?            // এডিট স্ক্রিনের অটো-ক্রপ
}
```

- MAGIC = autoContrast (1%–99% লুমিন্যান্স ক্লিপ) + saturation boost; GRAY/BW = ColorMatrix।
- PDF: `PdfDocument`, প্রতি পেজে JPEG compress → পেজ সাইজ ইমেজের aspect ratio অনুযায়ী।

## ৬. UI ও থ্রেডিং (MainActivity.kt)

**ফ্রেমওয়ার্ক:** Jetpack Compose, একটাই Activity, স্ক্রিন নেভিগেশন `var screen` স্টেট দিয়ে (হোস্ট-নেভ লাগে না)।

স্ক্রিন: `FoxScanApp` (হোম: পেজ থাম্বনেইল গ্রিড) → `ScanScreen` (ক্যামেরা) → `EditScreen` (ক্রপ/ফিল্টার/রোটেট) → `ExportScreen` (PDF + শেয়ার)।

**ScanScreen এর ডেটা ফ্লো:**

```
CameraX Preview (bindToLifecycle)
  + ImageAnalysis (YUV_420_888, STRATEGY_KEEP_ONLY_LATEST)
  + ImageCapture
        │ analyzer (ব্যাকগ্রাউন্ড এক্সিকিউটর, single thread)
        ▼
   planes[0] (Y plane) → QuadDetector.detect(...)
        ▼
   QuadStreamAnalyzer.addQuad(q, nowUs)  // nowUs = System.nanoTime()/1000
        ▼
   state + displayQuad → mainHandler.post { ui state আপডেট }
        ▼
   Compose Canvas-এ ওভারলে (Path, normalized → প্রিভিউ ভিউ কোঅর্ডে রূপান্তর)
        ▼
   state == TRIGGER এবং আগে ট্রিগার হয়নি → doCapture()
```

**ক্যাপচার:** `ImageCapture.takePicture` ফাইল-মোড নয়, in-memory (`onCaptureSuccess(ImageProxy)`) → JPEG বাইট → `BitmapFactory` → rotation fix → `ImageOps.warp(auto-detected quad, inset 0.96)` → `store.newPageFile()` এ JPEG 90 → **`analyzer.reset()`** (নতুন পেজ re-arm — ভুললে পরের পেজ অটো-ট্রিগার হবে না)।

**EditScreen:** বিটম্যাপ fit-করা বক্সে রেন্ডার, ৪টা ড্র্যাগেবল কর্নার হ্যান্ডেল (nearest corner + moveCorner), অটো-ক্রপ বাটন (detectQuadOnBitmap), ফিল্টার টগল, 90° রোটেট। Save করলে warp + saveJpeg।

**DocStore:** `context.filesDir/docs/page_<timestamp>.jpg`, flat list, প্রতিটা পেজ আলাদা ফাইল। মাল্টি-ডকুমেন্ট পরে (রোডম্যাপ)।

**থ্রেড নিয়ম:** ডিটেকশন ও ইমেজ অপস সব Executor-এ; UI টাচ শুধু main thread। একটা analysis executor শেয়ার করা, কখনো ক্যামেরা কলব্যাকে UI টাচ নয়।

## ৭. Manifest ও পারমিশন

- `<uses-permission android:name="android.permission.CAMERA"/>`
- FileProvider (export/share এর জন্য) + `res/xml/file_paths.xml` (files-path `docs`)
- অন্য কিছুই নয় — ইন্টারনেট পারমিশনও নেই।

## ৮. বিল্ড ও রিলিজ

```bash
# gradle.properties (অপরিহার্য)
org.gradle.jvmargs=-Xmx1536m -XX:MaxMetaspaceSize=512m

# বিল্ড
JAVA_HOME=<jdk-21> ./gradlew :app:assembleRelease

# সাইনিং: release-এ signingConfig = debug keystore (v0.1.0-এর পছন্দ)
# সার্টিফিকেট SHA-256: 1b68ee2824b38a73c700dbac9d45d527e7481f704a32cf3f3d35d4a8edb57139
```

রিলিজ: `gh release create vX.Y.Z dist/app-release.apk --title ... --notes ...` (রিপো: `Maynul-haque/FoxScanner`)।

**পরিচিত বিল্ড-ট্র্যাপ:**
- Compose + R8 ছাড়া APK ~12 MB হয়ে যায়; minify বাধ্যতামূলক।
- Kotlin 2.x-এ Compose প্লাগইন আলাদা (`org.jetbrains.kotlin.plugin.compose`)।
- Compose BOM থেকে `material3`, `activity-compose`, CameraX 1.4.x — সরাসরি ডিপেন্ডেন্সি রাখা সরল।

## ৯. রোডম্যাপ (v0.2+)

1. **orientation ডিটেকশন**: হালকা heuristic (টেক্সট লাইন প্রজেকশন) দিয়ে শুরু, পরে ছোট TFLite মডেল।
2. **মাল্টি-ডকুমেন্ট/গ্রুপিং**: DocStore-এ doc-id ফোল্ডার স্ট্রাকচার।
3. **curvature correction** (বইয়ের ভাঁজ সোজা করা)।
4. **OCR**: Tesseract বা ছোট TFLite মডেল; নেটওয়ার্ক নির্ভরতা নেই।
5. পেজ রিঅর্ডার/ডিলিট UI, মাল্টি-পেজ PDF সিলেকশন।

## ১০. অন্য agent-এর জন্য টেস্ট চেকলিস্ট

- [ ] ডকুমেন্ট ফ্রেমে ধরলে ওভারলে নীল → হলুদ → সবুজ, তারপর অটো-ক্যাপচার (~২.৫–৩ সে মোট)
- [ ] ডকুমেন্ট সরালে সাথে সাথে নীলে ফেরত (unstable → SEARCHING/ABOUT_TO_TRIGGER)
- [ ] ক্যাপচারের পরে ওভারলে রিসেট, নতুন পেজে আবার ট্রিগার
- [ ] বাতিল/ঝাপসা ফ্রেমে কোনো ট্রিগার নয়, কোনো ক্র্যাশ নয়
- [ ] ডার্ক পরিবেশে ডিটেক্টর null দেয়, অ্যাপ বেঁচে থাকে
- [ ] এডিটে ৪ কর্নার ড্র্যাগ + ম্যাজিক ফিল্টার + PDF এক্সপোর্ট
- [ ] ফোনে ইনস্টল: সার্টিফিকেট SHA-256 হ্যাশ উপরের সাথে মিলবে
