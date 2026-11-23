# Ocalix (Android)

Port dari Ocalix (Node.js) ke app Android Kotlin. UI-nya sama persis (`assets/public/index.html`),
server `index.js` dipindah ke Kotlin dan jalan di dalam app (`127.0.0.1:7777`), lalu dimuat lewat WebView.

## Build lewat GitHub Actions
1. Buat repo baru di GitHub, upload semua isi folder ini (termasuk folder `.github`).
2. Buka tab **Actions** -> workflow **Build APK** jalan otomatis tiap push (atau klik *Run workflow*).
3. Selesai, download APK di bagian **Artifacts** -> `Ocalix-debug-apk`.
4. Mau otomatis jadi Release: push tag, misalnya `git tag v1.2.3 && git push --tags`.

## Build lokal
Butuh JDK 17 + Android SDK + Gradle 8.10+: `gradle assembleDebug`
(APK ada di `app/build/outputs/apk/debug/`).

## Pengaturan
Ada di `app/src/main/java/com/ocalix/app/Config.kt` (port, audio latar belakang, instance Piped sendiri).

## Struktur
- `OcalixServer.kt` : /api/search, /api/audio (proxy + Range), /api/img, /api/why, file statis
- `YouTube.kt`      : pencarian YouTube/iTunes, resolve audio (VisionOS/Android client + Piped)
- `PlaybackService.kt` : foreground service + wake lock supaya musik lanjut saat layar mati
- `MainActivity.kt` : WebView
