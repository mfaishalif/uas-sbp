> **Arsip studi eksploratif v1 (27 Oktober 2026).** Dokumen, label, dan hasil di direktori ini
> dipertahankan sebagai arsip. Semua perbandingan baru memakai studi v2 di
> [v2/README.md](v2/README.md) dengan label subagent yang sama untuk kedua mode. Jangan memakai
> angka v1 untuk memilih konfigurasi baru.

# Evaluasi relevansi dan walkthrough demo

Dokumen ini menyediakan (1) walkthrough penerimaan yang dapat dijalankan pada checkout baru dan (2) lembar evaluasi relevansi. Evaluasi di repository ini bersifat **eksploratif**: label relevansi dibuat otomatis dan **belum divalidasi manusia**. Label tersebut tidak ditampilkan pada respons API dan tidak boleh diperlakukan sebagai penilaian final.

Sampel demo adalah 500 kasus CFPB yang di-commit di `data/cfpb/sample.csv`; fixture sintetis di tes backend/live contract bukan bagian sampel dan tidak boleh dicampur dengan evaluasi ini.

## Hasil kalibrasi eksploratif (27 Oktober 2026)

Eksperimen membandingkan baseline `tfidf-cosine-v1` kategori/teks `0.4/0.6` dan ambang `0.50` dengan 147 konfigurasi pada grid bobot kategori `0.1–0.7` (langkah `0.1`) dan ambang `0.20–0.70` (langkah `0.025`). Bobot teks selalu `1 − bobot kategori`; bobot field `0.35/0.15/0.35/0.15`, limit 5, dan syarat skor teks positif tidak berubah.

| Bagian | Baseline | Konfigurasi terpilih `w=0.2/t=0.275` |
| --- | ---: | ---: |
| Kalibrasi: precision@5 | 0.180 | 0.440 |
| Kalibrasi: precision hasil | 0.410 | 0.530 |
| Kalibrasi: query negatif mendapat hasil | 2/4 | 2/4 |
| Pengujian: precision@5 | 0.100 | 0.367 |
| Pengujian: precision hasil | 0.167 | 0.678 |
| Pengujian: query negatif mendapat hasil | 0/4 | 1/4 |

Konfigurasi terpilih lolos peningkatan precision@5 dan precision hasil, tetapi **gagal** syarat negatif: satu query negatif pada bagian pengujian mendapat hasil, sedangkan baseline tidak. Sesuai aturan yang dibekukan sebelum pengujian, konfigurasi terpilih **tidak diterapkan** dan default `0.4/0.6`, ambang `0.50` dipertahankan. Konfigurasi baseline tetap dicatat untuk rollback; karena default tidak berubah, tidak ada rollback yang perlu dijalankan.

Rincian lengkap, tabel 147 konfigurasi, hasil per query, contoh keberhasilan/kegagalan, checksum sampel, versi algoritme, dan keputusan ada di [results/report.md](results/report.md), [results/summary.json](results/summary.json), [results/calibration-configurations.csv](results/calibration-configurations.csv), dan [results/per-query-results.csv](results/per-query-results.csv). Label eksploratif ada di [exploratory-labels.csv](exploratory-labels.csv); lembar penilaian manusia tetap di [judgments.csv](judgments.csv).

## Walkthrough penerimaan

Kebutuhan: Java 21 dan Node.js 24. Perintah dijalankan dari root repository. Untuk demo offline, gunakan sampel yang sudah di-commit; jangan mengunduh arsip CFPB.

### 1. Verifikasi otomatis

```bash
npm ci
npm run verify
```

Hasil yang diharapkan: validasi kontrak, `api:check`, tes generation, typecheck/test client, seluruh tes backend, packaged jar, dan live contract/client test lulus. Perintah ini tidak mengunduh arsip CFPB dan tidak mengubah file generated yang di-track. Lulusnya tes teknis tidak membuktikan relevansi pencarian.

### 2. Jalankan server dengan database sementara

```bash
cd backend
./mvnw -DskipTests package
DEMO_DB=$(mktemp -d)/finresolve.db
FINRESOLVE_DB_PATH="$DEMO_DB" SERVER_ADDRESS=127.0.0.1 SERVER_PORT=8080 \
  java -jar target/finresolve-backend-0.1.0.jar
```

Log startup harus mencetak lokasi absolut database. `curl -i http://127.0.0.1:8080/api/health` menghasilkan **200** `{"status":"UP","database":"UP"}`.

### 3. Impor dan replay

```bash
curl -sS -X POST http://127.0.0.1:8080/api/imports/cfpb \
  -F 'file=@../data/cfpb/sample.csv;type=text/csv' \
  -F 'metadata={"archiveUrl":"https://files.consumerfinance.gov/f/documents/CCDB_Export_8_November_2024_through_December_2024.zip","sourceFileName":"finresolve-sample.csv","periodStart":"2024-11-01","periodEnd":"2024-12-31"};type=application/json'
```

Hasil yang diharapkan: **201**, `processed=500`, `imported=500`, `duplicates=0`, `rejected=0`. Membandingkan angka ini dengan `data/cfpb/manifest.json` (`selectedCount=500`, periode dan checksum sama) memastikan sampel yang diimpor adalah sampel yang di-commit. Mengulang request yang sama menghasilkan **200** dengan `replayed=true` dan hitungan asli.

### 4. Cari, retensi, dan restart

```bash
curl -sS -X POST http://127.0.0.1:8080/api/search \
  -H 'Content-Type: application/json' \
  -d '{"product":"Checking or savings account","subProduct":"Checking account","issue":"Managing an account","subIssue":"Deposits and withdrawals","narrative":"Money left my bank balance twice even though I only authorized one payment.","limit":5}'
```

Hasil yang diharapkan: **200**, `status` `MATCHES` atau `NO_RELEVANT_CASES`; keduanya sukses. Ambil UUID CFPB dari `GET /api/cases?source=CFPB`, lalu retensi:

```bash
curl -sS -i -X POST http://127.0.0.1:8080/api/cases \
  -H 'Content-Type: application/json' \
  -d '{"product":"Checking or savings account","subProduct":"Checking account","issue":"Managing an account","subIssue":null,"narrative":"My transfer is missing from my checking account.","receivedDate":"2024-12-01","company":"Example Bank","companyResponse":"Closed with explanation","handlingNotes":"Reviewed transfer evidence and requested a trace.","outcomeAssessment":"UNKNOWN","evaluationNotes":"Awaiting confirmation.","referenceCaseIds":["<UUID-CFPB>"]}'
```

Hasil yang diharapkan: **201** dan `Location: /api/cases/{id}`. Hentikan server, jalankan ulang dengan `FINRESOLVE_DB_PATH` yang sama, lalu `GET` lokasi tersebut: **200**, `source=ANALYST`, `provenance=null`, notes dan referensi tetap ada.

`PUT /api/cases/{id}` dengan hanya field wajib harus menghasilkan **200**, `id` dan `createdAt` sama, `company`/`subProduct`/`referenceCaseIds` kosong, `evaluationNotes=null`, dan `updatedAt` tidak lebih lama. Ini adalah penggantian penuh, bukan PATCH.

### 5. Client tanpa Next.js

Kembali ke root repository terlebih dahulu (`cd ..`).

```bash
npm run client:build
npm run client:typecheck
npm run test:contract
```

Live contract test menjalankan packaged jar dengan database sementara, memvalidasi body/status/header terhadap `docs/api/openapi.json` sebelum deserialisasi generated, lalu menguji adapter `@finresolve/api-client`.

### Hasil smoke yang tercatat

Walkthrough di atas dijalankan pada 26 Oktober 2026 dengan packaged jar dan database sementara:

| Langkah | Hasil aktual |
| --- | --- |
| Health | 200, `UP`/`UP` |
| Impor sampel | 201, `processed=500 imported=500 duplicates=0 rejected=0` |
| Pencarian contoh | 200, `MATCHES`, `returnedCount=1`, `corpusSize=500` |
| Retensi | 201, `Location: /api/cases/{id}` |
| Restart + detail | 200, `source=ANALYST`, `provenance=null`, notes/referensi utuh |
| PUT penuh | 200, ID/createdAt sama, field opsional dihapus, referensi `[]`, outcome `RESOLVED` |

`npm run verify` dan `cd backend && ./mvnw clean verify` juga harus lulus sebelum demo dianggap siap. Catat penyimpangan apa pun; jangan menganggap walkthrough lulus bila salah satu status berbeda.

## Dataset dan penilaian eksploratif

### Query dan pembagian

[queries.csv](queries.csv) berisi **24 query yang dibekukan sebelum eksperimen**: 16 skenario pengaduan dan 8 skenario negatif. Skenario mencakup kemiripan kata dekat, parafrasa dengan tumpang tindih leksikal rendah, field opsional yang dihilangkan, cabang opsional independen, tumpang tindih lintas produk, angka/tanggal pengisi, masalah transfer domestik/internasional, dompet digital, mata uang virtual, biaya tabungan, ATM, serta narasi tidak terkait yang berbagi kata umum.

Pembagian terstratifikasi memakai seed `20261027` dan disimpan di [split.csv](split.csv):

- kalibrasi: 10 query pengaduan + 4 query negatif;
- pengujian akhir: 6 query pengaduan + 4 query negatif.

Pembagian hanya dipakai untuk memilih konfigurasi pada bagian kalibrasi. Bagian pengujian dijalankan sekali untuk konfigurasi terpilih dan baseline; bagian itu tidak dipakai untuk memilih ulang.

### Identitas kasus dan kandidat

ID pengaduan CFPB adalah identitas evaluasi yang stabil. UUID internal evaluasi diturunkan secara deterministik dari ID tersebut, sehingga urutan seri dapat direproduksi tanpa memakai UUID acak database demo. Untuk setiap query, [candidates.csv](candidates.csv) menggabungkan kandidat top-5 dari seluruh 147 konfigurasi dan menampilkan setiap pasangan query/kasus sekali **tanpa skor, peringkat, atau identitas konfigurasi**.

### Label eksploratif

[exploratory-labels.csv](exploratory-labels.csv) berisi label biner dan alasan singkat untuk setiap pasangan pada kandidat. Label dibuat otomatis oleh peninjauan berbantuan AI, bukan oleh manusia. Relevan berarti kasus memiliki masalah dan konteks yang membantu memahami atau menangani pengaduan; kesamaan kategori atau kata saja tidak cukup. Label ini **tidak** ditampilkan pada API dan tidak mengubah hasil pencarian.

`judgments.csv` tetap disediakan untuk penilaian manusia dan masih hanya berisi header. Jangan menyalin label otomatis ke lembar tersebut tanpa peninjauan manusia.

### Metrik dan aturan pemilihan

- **Precision@5** query pengaduan memakai penyebut tetap 5; slot kosong dihitung tidak relevan.
- **Precision hasil yang dikembalikan** = hasil relevan / hasil yang dikembalikan; didefinisikan 0 bila tidak ada hasil.
- **Jumlah hasil** dilaporkan per query dan agregat.
- **Proporsi query negatif yang mendapat hasil** = query negatif dengan setidaknya satu hasil / seluruh query negatif.
- **Recall tidak dilaporkan** karena seluruh korpus tidak dilabeli menyeluruh.
- Pemilihan hanya memakai kalibrasi: jumlah query negatif yang mendapat hasil tidak boleh melebihi baseline; lalu maksimalkan rata-rata precision@5; pemecah seri berturut-turut adalah precision hasil, lebih sedikit kegagalan negatif, lalu jarak grid ke baseline.
- Konfigurasi terpilih diuji sekali pada bagian pengujian. Konfigurasi hanya diterapkan bila precision@5 meningkat, precision hasil tidak turun, dan kegagalan negatif tidak bertambah.

### Perintah evaluasi

Runner Java memakai parser CSV dan scorer produksi terhadap sampel yang di-commit; runner tidak menyentuh database demo dan tidak mengunduh arsip. Profil Maven `evaluation` menyediakan perintah berikut dari root repository:

```bash
npm run eval:split       # tulis pembagian deterministik
npm run eval:export      # ekspor kandidat top-5 seluruh konfigurasi (blinded)
npm run eval:labels      # validasi label; gagal bila ada yang hilang/duplikat
npm run eval:run         # hitung metrik, pilih, uji, tulis results/
npm run eval:compare     # tampilkan perbandingan baseline/terpilih
```

Perintah yang sama dapat dipanggil langsung:

```bash
cd backend
./mvnw -q -Pevaluation exec:java -Dexec.args="export-candidates"
```

Label yang hilang, duplikat, atau tidak valid menggagalkan perhitungan; label yang tidak ada tidak pernah dianggap tidak relevan. Hasil eksperimen disimpan di [results/](results/) bersama checksum sampel, versi algoritme, tabel konfigurasi, hasil per query, dan konfigurasi terpilih.

## Prosedur penilaian manusia

1. Jalankan sampel 500 kasus dan setiap query pada konfigurasi yang akan dilaporkan. Simpan commit/versi, nilai `finresolve.similarity.*`, dan `corpusSize`.
2. Untuk setiap query, periksa maksimal lima hasil teratas. Labeli `relevant=1` bila preseden benar-benar membantu analis memahami atau menangani pengaduan; selain itu `0`. Jangan memberi label berdasarkan skor atau kategori saja.
3. Tulis satu baris per slot pada [judgments.csv](judgments.csv): `queryId,rank,caseId,relevant,reviewer,notes`. Bila hasil kurang dari lima, tambahkan slot yang hilang dengan `caseId` kosong dan `relevant=0` saat evaluasi dinyatakan selesai. Isi `reviewer` dengan inisial/identitas penilai dan `notes` dengan alasan singkat.
4. Hitung `precision@5 = jumlah slot relevant / 5`; slot yang hilang dihitung tidak relevan. Laporkan nilai per query dan rata-rata **hanya setelah semua query dinilai manusia**. Jangan mengisi label sintetis atau menyalin label dari model.
5. Bandingkan hasil manusia dengan label eksploratif dan laporkan kesepakatan atau perbedaan; jangan menggabungkan keduanya tanpa menjelaskan aturannya.

## Batas

- Label eksploratif dibuat otomatis dan belum divalidasi manusia; angka precision@5 di dokumen ini bukan klaim kualitas final.
- Sampel 500 kasus tidak mewakili seluruh CFPB dan hanya mencakup dua produk.
- Respons historis bukan bukti keberhasilan penanganan; skor kemiripan bukan probabilitas.
- Kandidat dinilai pada penggabungan top-5 seluruh konfigurasi, sehingga perbandingan terbatas pada kandidat tersebut.
- Baseline dipertahankan karena konfigurasi terpilih gagal syarat negatif pada pengujian akhir; ini bukan bukti bahwa baseline optimal atau bahwa konfigurasi lain tidak lebih baik.
- `judgments.csv` masih kosong; jangan mengutip precision@5 manusia, akurasi, atau klaim kualitas apa pun sebelum penilaian manusia selesai.
