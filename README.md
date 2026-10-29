# FinResolve

FinResolve adalah aplikasi web untuk membantu analis menangani pengaduan konsumen jasa keuangan dengan pendekatan **Case-Based Reasoning (CBR)**. Aplikasi mencari kasus terdahulu yang serupa dan menampilkan informasi penanganannya sebagai bahan pertimbangan analis. Proyek ini menggunakan frontend Next.js, backend Java Spring Boot, dan database SQLite.

## Prasyarat

- Docker Engine atau Docker Desktop dengan Docker Compose **2.32.2 atau lebih baru**.
- Koneksi internet untuk mengunduh image dan dependensi saat build pertama.

Jalankan semua perintah berikut dari folder root proyek.

## Menjalankan demo

```bash
docker compose -f compose.demo.yaml up --build
```

Sebanyak 500 kasus sampel dimuat otomatis saat startup. Tunggu hingga layanan API siap dan frontend berjalan, lalu akses:

- Frontend: <http://127.0.0.1:3000>
- API: <http://127.0.0.1:8080>

Untuk menghentikan demo:

```bash
docker compose -f compose.demo.yaml down
```

## Development dengan hot reload

```bash
docker compose up --build --watch
```

Mode ini memakai alamat yang sama dengan demo dan menerapkan perubahan kode secara otomatis. Hentikan mode yang sedang berjalan sebelum beralih mode karena keduanya memakai port yang sama.

Untuk menghentikan development:

```bash
docker compose down
```
