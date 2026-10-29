# Testing artifacts — 28 Oktober 2026

- **Retest FS-01 dan perbaikan alat uji (terbaru): [retest-fs01-2026-10-28.md](retest-fs01-2026-10-28.md)** — bukti di `results/2026-10-28-retest-fs01-170711/`
- **Sesi pengujian penuh (historis): [fullstack-2026-10-28.md](fullstack-2026-10-28.md)** — bukti di `results/2026-10-28-full-155053/`
- Laporan eksplorasi awal: [exploratory-backend-2026-10-28.md](exploratory-backend-2026-10-28.md)
- Laporan retest backend dan status penutupan temuan F-01–F-07: [retest-2026-10-28.md](retest-2026-10-28.md)

## Status retest FS-01 (terbaru)

`npm run verify` **PASS** dengan **348 tes otomatis** (294 backend, 29 frontend Vitest, 13 generation, 4 tooling, 3 client, 5 live contract), `npm run frontend:e2e` **2/2 PASS**, regression baru **140/140 PASS**, probe backend **141/141 PASS**, browser exploratory **42/42 PASS**, restart **5/5 PASS**, phrases **2/2 PASS**, reproduksi FS-01 lama **2/2 PASS**, probe batas Unicode **27/27 PASS**, dan self-test runner **4/4 PASS**.

FS-01 diperbaiki pada frontend: atribut `maxLength` dihapus dari field yang batasnya dinyatakan dalam code point; validasi code point menolak input berlebih sebelum request API tanpa memotong isi. Runner aktif dipindahkan ke [`runners/`](runners/) dan tidak lagi menimpa bukti historis atau menghapus database sebelum verifikasi restart. Rincian dan batas retest di [retest-fs01-2026-10-28.md](retest-fs01-2026-10-28.md).

## Status fullstack historis (28 Oktober 2026)

`npm run verify` **PASS** (341 tes otomatis), `npm run frontend:e2e` **2/2 PASS**, probe backend **141/141 PASS**, browser exploratory **42/42 PASS**, verifikasi restart **5/5 PASS**, dan phrases UI **2/2 PASS**. Satu temuan **P3 / FS-01** (batas karakter frontend menghitung UTF-16 dan memotong input astral) membuat total skenario terarah **191 PASS, 1 FAIL, 0 BLOCKED**. Tidak ada perubahan kode aplikasi pada sesi tersebut.

> Catatan: FAIL FS-01 di laporan fullstack dan `results/2026-10-28-full-155053/emoji-input.json` adalah bukti historis dan **tidak diubah**. Status PASS berasal dari retest terpisah setelah perbaikan.

## Status retest backend historis

Seluruh temuan F-01–F-07 dari laporan awal telah ditutup dan diuji ulang pada JAR baru. Regression suite resmi **140/140 PASS**, seluruh probe terarah retest **141 PASS, 0 FAIL, 0 BLOCKED**, dan Q23 lulus sebagai tes negatif `400 INVALID_CATEGORY` pada baseline, phrases, serta regression. `npm run verify` lulus dengan **294 tes backend**, 4 tes tooling, dan 5 live contract test.

Laporan awal beserta hasil dan log bertanggal `2026-10-28-*.json` dipertahankan tanpa perubahan sebagai bukti kondisi sebelum perbaikan. Hasil retest memakai nama `2026-10-28-retest-*`.

## Struktur

- `runners/` — runner aktif dengan direktori hasil unik, database sementara unik, PID per server, penolakan port terpakai, cleanup, dan propagasi exit code. Lihat [`runners/README.md`](runners/README.md).
- `probes/` — probe yang dapat dijalankan ulang terhadap JAR dan database SQLite sementara; `browser-exploratory.mjs`, `browser-restart-verify.mjs`, `browser-phrases-check.mjs`, `emoji-input-check.mjs`, dan `unicode-boundary-check.mjs` memakai Playwright/Chromium.
- `results/` — hasil JSON per sesi, berisi status, header, body, durasi, perubahan data, dan request/response.
- `results/2026-10-28-retest-fs01-170711/` — bukti retest FS-01 dan runner terbaru.
- `results/2026-10-28-full-155053/` — arsip sesi fullstack historis; skrip di dalamnya tidak dipakai untuk eksekusi baru.
- `results/2026-10-28-verify.log` — log `npm run verify` pada commit yang diuji awal.
- `results/2026-10-28-retest-verify.log` — log `npm run verify` pada retest backend historis.

## Ringkasan awal (sebelum perbaikan)

| Hasil | Jumlah |
| --- | ---: |
| Probe terarah PASS | 117 |
| Probe terarah FAIL | 9 (dua akar masalah backend) |
| Probe terarah BLOCKED | 2 (Q23 baseline + phrases) |
| Observasi/undefined | 4 |
| Regression as-shipped | 116/140 PASS |
| Regression corrected | 139/140 PASS (Q23 fixture) |

## Ringkasan retest backend historis (setelah perbaikan)

| Hasil | Jumlah |
| --- | ---: |
| Probe terarah PASS | 141 |
| Probe terarah FAIL | 0 |
| Probe terarah BLOCKED | 0 |
| Observasi terbuka | 0 |
| Regression resmi | **140/140 PASS** (Q23 lulus sebagai tes negatif) |
| `npm run verify` | PASS — 294 tes backend, 4 tes tooling, 5 live contract test |

## Menjalankan ulang

Semua probe memakai Node.js 24 dan JAR hasil `npm run verify`. Gunakan runner aktif di `runners/`; setiap eksekusi memakai database baru dan menulis ke direktori hasil baru.

```bash
npm run verify
npm run frontend:e2e
bash docs/testing/runners/run-backend-probes.sh
bash docs/testing/runners/run-regression.sh
bash docs/testing/runners/run-browser-session.sh
bash docs/testing/runners/run-emoji-check.sh
bash docs/testing/runners/run-unicode-boundary.sh
bash docs/testing/runners/self-test.sh
```

`node:sqlite` dipakai hanya untuk memeriksa atomisitas file database, bukan untuk menulis data kecuali pada probe write lock.

Laporan historis 27 Oktober tetap berada di `docs/evaluation/exploratory-2026-10-27.md`; direktori ini tidak menimpa arsip tersebut.
