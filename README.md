# Audio Cutter Pro

Aplikasi Android native untuk memotong rekaman commentator / voice game secara manual menjadi banyak bagian.

## Fitur
- Import MP3, MP4, M4A/AAC melalui Android file picker.
- Waveform audio.
- Tap waveform untuk seek.
- Drag garis cut untuk koreksi posisi.
- Tombol +100 ms / -100 ms untuk fine adjustment.
- Target default 29 bagian (28 titik cut), dapat diubah.
- Preview setiap part.
- Export semua part sekaligus ke folder pilihan.
- MP4/M4A dengan audio AAC diexport lossless ke M4A.
- MP3 diexport tetap MP3 tanpa re-encode.
- Nama file otomatis: `commentator_01`, `commentator_02`, dst.

## Build
Project memakai Android Gradle Plugin 8.5.2, Java 17, minSdk 26, target/compileSdk 35.

GitHub Actions otomatis membuat APK debug pada setiap push ke branch `main`.
