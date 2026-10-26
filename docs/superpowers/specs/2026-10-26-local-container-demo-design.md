# Demo lokal FinResolve dengan container

Status: disetujui pengguna pada 26 Oktober 2026.

## Intent
Memudahkan demo backend melalui API tanpa memasang Java atau mengimpor dataset secara manual. Frontend belum tersedia dan tidak termasuk pekerjaan ini.

## Goals
- `docker compose up --build` membangun backend Java 21 dan menjalankannya pada `http://localhost:8080`.
- Sampel CFPB 500 kasus yang sudah di-commit tersedia otomatis, tanpa unduhan arsip saat runtime.
- Restart atau pembuatan ulang container tidak menggandakan kasus atau menghapus data analis.
- SQLite disimpan pada named volume; port host hanya terikat pada loopback.
- README menyediakan pencarian siap salin, penghentian, dan reset demo.

## Non-goals dan anti-goals
Tidak menambah frontend, autentikasi, deployment publik, atau database eksternal. Jangan menghapus database yang sudah ada, menjalankan proses sebagai root, atau mengunduh ulang dataset saat startup.

## Constraints
Java 21, Spring Boot dan Maven Wrapper yang ada tetap digunakan. Build membutuhkan `backend/`, `docs/api/openapi.json`, serta `tools/openapi/java.json`. Devcontainer tetap menjadi lingkungan pengembangan yang terpisah.

## Approaches
1. Dockerfile multi-stage dan Docker Compose: build serta port dan volume dideklarasikan bersama; satu perintah untuk demo. Dipilih pengguna karena mudah dijalankan.
2. Dockerfile saja: konfigurasi lebih sedikit, tetapi pengguna harus mengatur port dan volume lewat perintah manual. Tidak dipilih.

## Design
Image build mengompilasi packaged jar menggunakan Maven Wrapper. Image runtime Java 21 menjalankan aplikasi sebagai pengguna non-root, menyertakan CSV sampel, dan menggunakan `/data/finresolve.db`. Compose mengaktifkan impor demo serta memetakan `127.0.0.1:8080:8080` dan named volume `/data`.

Keputusan implementer: impor demo merupakan komponen startup opt-in yang memanggil ImportService yang sudah memiliki transaksi dan replay idempoten. Fitur ini mati secara default di luar Compose. Kegagalan impor membuat startup gagal dengan pesan yang jelas. Metadata mengikuti contoh impor README. Healthcheck memeriksa endpoint kesehatan serta kesiapan impor demo.

## Verification
Buktikan startup mengisi 500 kasus, restart tidak menambah kasus atau menghilangkan data analis, pencarian API bekerja, dan reset volume menghasilkan demo baru. Jalankan tes backend yang relevan serta build dan smoke test Docker jika daemon tersedia. Laporkan keterbatasan lingkungan secara eksplisit.
