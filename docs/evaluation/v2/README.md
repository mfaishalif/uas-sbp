# Studi evaluasi relevansi v2

Status: **penilaian AI independen, belum divalidasi manusia** (`humanValidated=false`). Studi ini
menggantikan studi eksploratif 27 Oktober 2026 di direktori induk untuk semua perbandingan baru.
Laporan dan label lama tetap disimpan sebagai arsip dan tidak dipakai untuk memilih konfigurasi.

## Hasil

- 104 query: 64 pengaduan dan 40 negatif.
- 24 query lama menjadi data pengembangan dan selalu berada di kalibrasi.
- 80 query baru disusun dalam 40 kelompok skenario × 2 parafrasa (48 pengaduan, 32 negatif).
- Pembagian kelompok memakai seed `20261027`: 64 query kalibrasi dan 40 query pengujian
  (24 pengaduan + 16 negatif).
- Grid 2 mode × 147 titik = **294 konfigurasi**.
- Label dibuat oleh `reviewer-a` dan `reviewer-b` (deepseek-v4.1-flash, konteks terpisah), lalu
  semua perbedaan diselesaikan `adjudicator` (deepseek-v4.1-flash).

| Tahap | Baseline saat ini | Baseline terbaik layak | Frasa terbaik layak | Terpilih |
| --- | ---: | ---: | ---: | ---: |
| Kalibrasi precision@5 | 0.105 | 0.140 | 0.145 | 0.145 |
| Kalibrasi macro precision | 0.246 | 0.273 | 0.277 | 0.277 |
| Kalibrasi micro precision | 0.525 | 0.528 | 0.547 | 0.547 |
| Kalibrasi keberhasilan per query | 0.300 | 0.350 | 0.350 | 0.350 |
| Kalibrasi kegagalan negatif | 9 | 9 | 9 | 9 |

Konfigurasi terpilih: `phrases/w=0.5/t=0.575`. Baseline mentah terbaik setelah penyetelan parameter
(`baseline/w=0.3/t=0.250`) memiliki precision@5 0.445, tetapi seluruh 24 query negatif mendapat hasil
sehingga tidak layak. Perbandingan layak menunjukkan penyetelan parameter menaikkan precision@5
0.105 → 0.140, sedangkan normalisasi frasa menambah 0.140 → 0.145 pada parameter yang sama.

| Pengujian akhir (sekali) | Baseline | Terpilih |
| --- | ---: | ---: |
| precision@5 | 0.175 | 0.242 |
| Macro precision | 0.377 | 0.494 |
| Micro precision | 0.568 | 0.644 |
| Keberhasilan per query | 0.458 | 0.583 |
| Hasil pengaduan | 37 | 45 |
| Hasil negatif | 7 | 11 |
| Query negatif mendapat hasil | 5 | 7 |

Gerbang penerapan gagal karena kegagalan negatif naik dari 5 menjadi 7. Karena itu default **tetap**
`mode=baseline`, bobot kategori/teks `0.4/0.6`, ambang `0.50`. Tidak ada kandidat pengganti yang
dipilih dari hasil pengujian.

Ringkasan lengkap ada di [results/report.md](results/report.md), mesin-baca
[results/summary.json](results/summary.json), tabel 294 konfigurasi
[results/calibration-configurations.csv](results/calibration-configurations.csv), hasil per query
[results/per-query-results.csv](results/per-query-results.csv), dan audit label
[results/label-audit.json](results/label-audit.json).

## Data dan label

- [queries.csv](queries.csv) memuat 104 query dengan kolom `queryId,kind,groupId,cohort,product,
  subProduct,issue,subIssue,narrative,scenario`. Kutipan Q21/Q22 sudah diperbaiki; runner menolak
  jumlah kolom yang salah, ID duplikat, dan kelompok yang mencampur kind/cohort.
- [split.csv](split.csv) menyimpan pembagian kelompok. Kedua parafrasa selalu satu bagian.
- [calibration-candidates.csv](calibration-candidates.csv) adalah gabungan top-5 dari 294 konfigurasi
  pada kalibrasi (467 pasangan).
- [final-candidates.csv](final-candidates.csv) adalah gabungan top-5 baseline dan kandidat terpilih
  pada pengujian (59 pasangan).
- Paket buta ada di `subagents/input/{calibration,test}/`. Paket hanya memuat identitas pasangan,
  narasi, dan kategori; tidak ada skor, peringkat, konfigurasi, label lama, split, atau penanda
  negatif.
- Review awal, adjudikasi, dan label final ada di `labels/`. Setiap baris wajib memiliki alasan dan
  kutipan verbatim dari narasi kasus. Runner gagal bila label hilang/duplikat, kutipan tidak
  ditemukan, atau adjudikasi belum lengkap.

Kesepakatan reviewer dan Cohen's kappa:

| Bagian | Pasangan | Sepakat | Beda | Kesepakatan | Kappa |
| --- | ---: | ---: | ---: | ---: | ---: |
| Kalibrasi | 467 | 437 | 30 | 93.58% | 0.8457 |
| Pengujian | 59 | 55 | 4 | 93.22% | 0.8626 |

[judgments.csv](judgments.csv) adalah lembar penilaian manusia dan tetap hanya berisi header. Label AI
tidak boleh disalin ke lembar tersebut tanpa peninjauan manusia.

## Reproduksi

Dari root repository:

```bash
npm ci
npm run eval:split        # tulis ulang split deterministik dari manifest
npm run eval:labels       # validasi label kalibrasi dan pengujian
npm run eval:run          # hitung 294 konfigurasi, reproduksi pilihan, tulis results/
npm run eval:compare      # tampilkan keputusan tersimpan
```

Untuk mengulang alur lengkap dari nol, gunakan urutan:
`eval:split` → `eval:export` → penilaian subagent kalibrasi → `eval:select` →
`eval:export-final` → penilaian subagent pengujian → `eval:labels` → `eval:run`.

`eval:select` hanya memakai label kalibrasi dan membekukan kandidat di
[selection.json](selection.json) bersama checksum kode, kamus, query, split, kandidat, label, prompt,
dan paket input. `eval:run` memverifikasi checksum tersebut sebelum menghitung. Jika precision@5
kalibrasi tidak meningkat, `select` menulis keputusan `KEEP_BASELINE` dan pengujian tidak diekspor.

Parameter yang dibekukan: kamus `domain-phrases-v1` (SHA-256
`60b1ad61268f5541a7cbeb40b48efb9fdcaae07afa611188d0d1ad192de726ec`), seed `20261027`, 40 kelompok
skenario, 294 konfigurasi, bobot field `0.35/0.15/0.35/0.15`, dan limit 5.

## Evaluasi dan rollback

Evaluasi tidak memakai panggilan model saat aplikasi berjalan. Subagent hanya dipakai untuk
menghasilkan label offline; scorer produksi tetap deterministik. Request/response dan status HTTP
tidak berubah. Respons selalu memuat `algorithmVersion` aktual: `tfidf-cosine-v1` untuk baseline dan
`tfidf-phrases-v2` untuk phrases.

Rollback ke konfigurasi awal:

```yaml
finresolve:
  similarity:
    mode: baseline
    category-weight: 0.4
    text-weight: 0.6
    threshold: 0.50
```

Tidak ada migrasi database. Mengubah mode tidak mengubah narasi tersimpan; normalisasi hanya
diterapkan pada tokenisasi.

## Batas

- Label dibuat AI dan belum divalidasi manusia; angka di sini bukan klaim kualitas final.
- Sampel 500 kasus hanya mencakup dua produk dan tidak mewakili seluruh CFPB.
- Recall seluruh korpus tidak dihitung; kandidat dibatasi pada gabungan top-5.
- Pengujian akhir hanya membandingkan baseline saat ini dengan kandidat terpilih yang dibekukan.
- Skor kemiripan bukan probabilitas relevansi atau keberhasilan penanganan.
