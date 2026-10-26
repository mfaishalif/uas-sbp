# Evaluasi relevansi dan walkthrough demo

Dokumen ini menyediakan (1) walkthrough penerimaan yang dapat dijalankan pada checkout baru dan (2) lembar evaluasi relevansi manual. **Tidak ada label relevansi atau klaim kualitas pencarian di repository ini.** Penilaian harus dilakukan manusia setelah membaca hasil nyata.

Sampel demo adalah 500 kasus CFPB yang di-commit di `data/cfpb/sample.csv`; fixture sintetis di tes backend/live contract bukan bagian sampel dan tidak boleh dicampur dengan evaluasi ini.

## Walkthrough penerimaan

Kebutuhan: Java 21 dan Node.js 24. Perintah dijalankan dari root repository. Untuk demo offline, gunakan sampel yang sudah di-commit; jangan mengunduh arsip CFPB.

### 1. Verifikasi otomatis

```bash
npm ci
npm run verify
```

Hasil yang diharapkan: validasi kontrak, `api:check`, 13 tes generation, 202 tes backend, packaged jar, dan 5 live contract/client test lulus. Perintah ini tidak mengunduh arsip CFPB dan tidak mengubah file generated yang di-track.

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

## Lembar evaluasi relevansi

### Query

[queries.csv](queries.csv) berisi 12 query berbahasa Inggris yang memakai label kategori aktual sampel. Skenario mencakup:

- kemiripan kata dekat dan parafrasa dengan tumpang tindih leksikal rendah;
- field opsional yang dihilangkan dan cabang opsional independen;
- tumpang tindih lintas produk;
- angka/tanggal pengisi bercampur teks bermakna;
- narasi tidak terkait;
- masalah transfer domestik, internasional, dompet digital, mata uang virtual, biaya tabungan, dan ATM.

Query belum memiliki label relevansi. Menjalankan query bukan penilaian.

### Pengamatan awal (bukan label)

Dengan sampel 500 kasus, konfigurasi default (`tfidf-cosine-v1`, bobot kategori 0.4/teks 0.6, ambang 0.50, limit 5), hasil yang teramati pada 26 Oktober 2026:

| Query | Skenario | Status | `returnedCount` |
| --- | --- | --- | --- |
| Q01 | Transfer tidak diterima | `NO_RELEVANT_CASES` | 0 |
| Q02 | Parafrasa pembayaran ganda | `MATCHES` | 1 |
| Q03 | Field opsional dihilangkan | `MATCHES` | 5 |
| Q04 | Cabang opsional independen | `NO_RELEVANT_CASES` | 0 |
| Q05 | Lintas produk | `NO_RELEVANT_CASES` | 0 |
| Q06 | Angka/tanggal pengisi | `NO_RELEVANT_CASES` | 0 |
| Q07 | Narasi tidak terkait | `MATCHES` | 1 |
| Q08 | Transfer domestik fraud | `MATCHES` | 2 |
| Q09 | Transfer internasional belum tersedia | `NO_RELEVANT_CASES` | 0 |
| Q10 | Mata uang virtual | `NO_RELEVANT_CASES` | 0 |
| Q11 | Biaya tabungan | `NO_RELEVANT_CASES` | 0 |
| Q12 | ATM tidak mengeluarkan uang | `MATCHES` | 2 |

Angka ini hanya observasi teknis. Q07 yang tidak terkait tetapi mendapat satu hasil menunjukkan ambang awal belum dikalibrasi; angka tersebut **bukan** bukti kualitas, dan hasil kosong **bukan** bukti bahwa tidak ada preseden yang relevan.

### Prosedur penilaian manusia

1. Jalankan sampel 500 kasus dan setiap query pada konfigurasi yang akan dilaporkan. Simpan commit/versi, nilai `finresolve.similarity.*`, dan `corpusSize`.
2. Untuk setiap query, periksa maksimal lima hasil teratas. Labeli `relevant=1` bila preseden benar-benar membantu analis memahami atau menangani pengaduan; selain itu `0`. Jangan memberi label berdasarkan skor atau kategori saja.
3. Tulis satu baris per slot pada [judgments.csv](judgments.csv): `queryId,rank,caseId,relevant,reviewer,notes`. Bila hasil kurang dari lima, tambahkan slot yang hilang dengan `caseId` kosong dan `relevant=0` saat evaluasi dinyatakan selesai. Isi `reviewer` dengan inisial/identitas penilai dan `notes` dengan alasan singkat.
4. Hitung `precision@5 = jumlah slot relevant / 5`; slot yang hilang dihitung tidak relevan. Laporkan nilai per query dan rata-rata **hanya setelah semua query dinilai manusia**. Jangan mengisi label sintetis atau menyalin label dari model.
5. Bila ada beberapa penilai, laporkan kesepakatan atau hasil terpisah; jangan menggabungkan tanpa menjelaskan aturannya.

### Batas

- Sampel 500 kasus tidak mewakili seluruh CFPB dan hanya mencakup dua produk.
- Respons historis bukan bukti keberhasilan penanganan; skor kemiripan bukan probabilitas.
- Bobot/ambang awal belum dikalibrasi dan tidak boleh disebut optimal.
- `judgments.csv` masih kosong. Jangan mengutip precision@5, akurasi, atau klaim kualitas apa pun sebelum penilaian manusia selesai.
