# ELKASARI DSP S3 — Proyek Android APK (Android 8.0+ / API 26+)

Proyek ini membungkus file HTML kontrol DSP ESP32-S3 (`app/src/main/assets/www/index.html`) menjadi aplikasi Android APK siap instal untuk **Android 8.0 Oreo (API 26) ke atas**, **tanpa mengubah fungsi komunikasi dua arah BLE**.

## Cara Kerja Jembatan BLE Dua Arah
Android `WebView` standar tidak menyediakan `navigator.bluetooth`. Proyek ini menyertakan **`BleGattBridge.kt`** dan polyfill `navigator.bluetooth` di **`MainActivity.kt`** yang meneruskan:
- `SERVICE_UUID`: `4fafc201-1fb5-459e-8fcc-c5c9c331914b`
- `CHAR_CONTROL` (TX): `beb5483e-36e1-4688-b7f5-ea07361b26a8` (`writeValueWithResponse` / `writeValueWithoutResponse`)
- `CHAR_STATUS` (RX): `beb5483e-36e1-4688-b7f5-ea07361b26a9` (`startNotifications` + `characteristicvaluechanged` + `readValue`)
- Negosiasi **MTU 247 byte** otomatis agar paket PEQ 47 byte & snapshot status DSP 89/93 byte diterima utuh.
- Dukungan izin **Android 8–11** (`ACCESS_FINE_LOCATION`) dan **Android 12–15** (`BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT`, `RECORD_AUDIO`, `READ_MEDIA_AUDIO`).

---

## Opsi 1: Build APK Otomatis via GitHub Actions (Tanpa Install Android Studio)
1. Upload seluruh isi folder ZIP ini ke repository GitHub baru.
2. Buka tab **Actions** di GitHub → pilih workflow **Build Android APK** → klik **Run workflow**.
3. Tunggu ~2 menit, lalu download file `.apk` di bagian **Artifacts** dan instal langsung di HP Android 8+.

## Opsi 2: Build APK di PC dengan Android Studio
1. Buka folder proyek ini menggunakan **Android Studio** (Jellyfish / Koala / Ladybug atau lebih baru).
2. Tunggu proses Gradle Sync selesai.
3. Klik menu **Build > Build Bundle(s) / APK(s) > Build APK(s)**.
4. File APK siap instal ada di `app/build/outputs/apk/debug/app-debug.apk`.
