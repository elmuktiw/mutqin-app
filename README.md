# Mutqin — Android native (Kotlin + Jetpack Compose)

Port native dari web app **Mutqin** (murajaah Al-Qur'an per halaman dengan flashcard,
Leitner 7 kotak, dan active recall metode Dr. Ali Rajabi).

## Cara membangun APK lewat GitHub Actions
1. Buat repo GitHub, unggah seluruh isi folder ini (termasuk `.github/workflows/` dan `app/mutqin-debug.keystore`).
2. **Sekali saja:** tab **Actions** -> **Generate Assets** -> *Run workflow*. Ia mengunduh teks 604 halaman dan audio
   3,5 detik tiap halaman, memvalidasinya (604 halaman, 6.236 ayat, 604 klip), lalu menyimpannya ke repo.
   Jika gagal saat *push*, buka Settings -> Actions -> General -> Workflow permissions -> **Read and write**.
3. Tab **Actions** -> **Build Android APK** -> *Run workflow* (commit dari langkah 2 tidak memicunya otomatis).
4. Unduh `mutqin-debug-apk` dari bagian **Artifacts**.

APK sudah memuat seluruh teks dan audio, jadi aplikasi berjalan sepenuhnya offline (internet hanya untuk sinkronisasi cloud).
Build akan GAGAL dengan pesan jelas bila aset belum lengkap.

### Memperbarui aplikasi tanpa kehilangan data
Semua build ditandatangani keystore tetap (`app/mutqin-debug.keystore`), sehingga APK baru bisa dipasang **di atas** versi lama
tanpa uninstall. Khusus peralihan pertama dari APK lama (keystore berubah-ubah): ekspor cadangan JSON dulu dari aplikasi lama
(ikon sinkronisasi), pasang APK baru, lalu impor cadangan itu.

### Login Google dan sinkronisasi cloud (Firebase)
- Proyek Firebase: `mutqin-5dbe6`; berkas `app/google-services.json` sudah ada di repo (tidak berisi kata sandi).
- Di aplikasi: ikon sinkronisasi (kanan atas) -> **Masuk dengan Google**. Setiap murajaah dikirim otomatis, dan aplikasi
  menyinkronkan diri saat dibuka. Penggabungan per halaman: murajaah paling baru menang, riwayat digabung.
- Struktur data: `users/{uid}/sync_data/page_{n}` dan `users/{uid}/sync_data/settings` (aturan keamanan: hanya pemilik akun).
- Jika `google-services.json` tidak ada, build tetap jalan tanpa fitur cloud.

### Pengingat dan tema
- Pengingat memakai alarm tepat waktu (jika diizinkan) ditambah pemeriksa cadangan WorkManager (~15 menit) yang menyusulkan
  notifikasi terlewat; tiap jam hanya ditampilkan sekali. Alarm dijadwalkan ulang saat reboot, pembaruan aplikasi, dan perubahan jam/zona waktu.
- Tema: ikon palet di kanan atas -> Mushaf (krem terang), Zamrud, atau Gelap.

### Dua APK: debug dan rilis
Build menghasilkan dua artifact: `mutqin-debug-apk` (untuk uji/diagnosis) dan `mutqin-release-apk`
(dioptimalkan R8 dan profil baseline, jauh lebih mulus saat scroll). Pakai yang rilis untuk sehari-hari.
Keduanya memakai ID aplikasi dan keystore tetap yang sama, sehingga saling menimpa tanpa uninstall.

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
- `app/src/main/assets/` — daftar surat, trigger & ayat pembuka 604 halaman; `pages/` teks 604 halaman dan `audio/` klip 3,5 detik (dibuat `tools/generate_assets.py`)
- `app/src/test/` — unit test (antrean, perubahan rentang, SRS, pemetaan ayat->halaman); dijalankan CI tiap push
- Font Arab: Scheherazade New & Amiri (SIL OFL), disertakan di `res/font`
