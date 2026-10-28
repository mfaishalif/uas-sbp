# Testing artifacts — 28 Oktober 2026

- Laporan eksplorasi awal: [exploratory-backend-2026-10-28.md](exploratory-backend-2026-10-28.md)
- **Laporan retest dan status penutupan temuan: [retest-2026-10-28.md](retest-2026-10-28.md)**

## Status

Seluruh temuan F-01–F-07 dari laporan awal telah ditutup dan diuji ulang pada JAR baru. Regression suite resmi **140/140 PASS**, seluruh probe terarah retest **141 PASS, 0 FAIL, 0 BLOCKED**, dan Q23 lulus sebagai tes negatif `400 INVALID_CATEGORY` pada baseline, phrases, serta regression. `npm run verify` lulus dengan **294 tes backend**, 4 tes tooling, dan 5 live contract test.

Laporan awal beserta hasil dan log bertanggal `2026-10-28-*.json` dipertahankan tanpa perubahan sebagai bukti kondisi sebelum perbaikan. Hasil retest memakai nama `2026-10-28-retest-*`.

## Struktur

- `probes/` — probe yang dapat dijalankan ulang terhadap JAR dan database SQLite sementara.
- `results/` — hasil JSON per sesi, berisi status, header, body, durasi, perubahan data, dan request/response.
- `results/2026-10-28-verify.log` — log `npm run verify` pada commit yang diuji awal.
- `results/2026-10-28-retest-verify.log` — log `npm run verify` pada retest.

## Ringkasan awal (sebelum perbaikan)

| Hasil | Jumlah |
| --- | ---: |
| Probe terarah PASS | 117 |
| Probe terarah FAIL | 9 (dua akar masalah backend) |
| Probe terarah BLOCKED | 2 (Q23 baseline + phrases) |
| Observasi/undefined | 4 |
| Regression as-shipped | 116/140 PASS |
| Regression corrected | 139/140 PASS (Q23 fixture) |

## Ringkasan retest (setelah perbaikan)

| Hasil | Jumlah |
| --- | ---: |
| Probe terarah PASS | 141 |
| Probe terarah FAIL | 0 |
| Probe terarah BLOCKED | 0 |
| Observasi terbuka | 0 |
| Regression resmi | **140/140 PASS** (Q23 lulus sebagai tes negatif) |
| `npm run verify` | PASS — 294 tes backend, 4 tes tooling, 5 live contract test |

## Menjalankan ulang

Semua probe memakai Node.js 24 dan JAR hasil `npm run verify`. Gunakan database baru untuk setiap sesi; lihat contoh perintah di bagian 7 laporan awal dan bagian 6 laporan retest. `node:sqlite` dipakai hanya untuk memeriksa atomisitas file database, bukan untuk menulis data kecuali pada probe write lock.

Laporan historis 27 Oktober tetap berada di `docs/evaluation/exploratory-2026-10-27.md`; direktori ini tidak menimpa arsip tersebut.
