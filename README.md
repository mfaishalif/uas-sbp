# FinResolve

FinResolve membantu analis mencari kasus pengaduan konsumen jasa keuangan yang serupa sebagai referensi penanganan.

## Prasyarat

- Docker Engine atau Docker Desktop dalam keadaan berjalan, dengan Docker Compose **2.32.2 atau lebih baru**.
- Koneksi internet untuk build pertama.

Jalankan semua perintah dari folder root proyek. Pilih salah satu mode berikut.

## Menjalankan demo

```bash
docker compose -f compose.demo.yaml up -d --build --wait
```

Setelah perintah selesai, buka <http://localhost:13000>. Sebanyak 500 kasus sampel dimuat otomatis.

Jika `.env` mengatur `FINRESOLVE_FRONTEND_PORT`, gunakan port tersebut pada alamat browser.

Untuk menghentikan demo:

```bash
docker compose -f compose.demo.yaml down
```

## Development dengan hot reload

```bash
docker compose up --build --watch
```

Setelah frontend siap, buka <http://localhost:13000>, atau gunakan port dari `.env` jika tersedia. Perubahan kode diterapkan secara otomatis.

Untuk menghentikan development, tekan **Ctrl+C**, lalu jalankan:

```bash
docker compose down
```

Hentikan mode yang sedang berjalan sebelum beralih ke mode lainnya.

Untuk menyiapkan lingkungan kerja VS Code, lihat [panduan Dev Container](.devcontainer/README.md).
