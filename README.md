# Mutqin — Android native (Kotlin + Jetpack Compose)

Port native dari web app **Mutqin** (murajaah Al-Qur'an per halaman dengan flashcard,
Leitner 7 kotak, dan active recall metode Dr. Ali Rajabi).

## Cara membangun APK lewat GitHub Actions
1. Buat repo GitHub, unggah seluruh isi folder ini (termasuk `.github/workflows/android.yml`).
2. Buka tab **Actions** → workflow **Build Android APK** berjalan otomatis (atau jalankan manual).
3. Setelah selesai, unduh APK dari bagian **Artifacts** (`mutqin-debug-apk`).

### APK rilis bertanda tangan (opsional)
Tambahkan 4 *repository secrets*: `MUTQIN_KEYSTORE_BASE64` (isi keystore dalam base64),
`MUTQIN_KEYSTORE_PASSWORD`, `MUTQIN_KEY_ALIAS`, `MUTQIN_KEY_PASSWORD`. Tanpa secrets, hanya APK debug yang dibuat.

## Build lokal
Butuh JDK 17 + Android SDK 35 + Gradle 8.9: `gradle :app:assembleDebug`
(atau buka folder ini di Android Studio).

## Struktur
- `app/src/main/java/id/mutqin/app/MainActivity.kt` — Activity, scaffold, navigasi, dialog
- `.../ui/` — layar Flashcard, Daftar 604 halaman, Statistik (grafik Canvas), dialog target/pengingat/cadangan
- `.../Srs.kt` — Leitner, penilaian, antrean "bayar utang dulu", statistik (logika murni, teruji)
- `.../Codec.kt` — JSON kompatibel dengan file cadangan versi web
- `app/src/main/assets/` — daftar surat, trigger & ayat pembuka 604 halaman, teks offline 6 halaman
- Font Arab: Scheherazade New & Amiri (SIL OFL), disertakan di `res/font`
