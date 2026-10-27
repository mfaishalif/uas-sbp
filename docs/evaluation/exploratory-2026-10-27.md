# Exploratory testing API backend — 27 Oktober 2026

Backend berhasil dibangun dan dijalankan. Dari **128 skenario eksplorasi / 164 request HTTP**, **120 lulus dan 8 gagal**, yang dikelompokkan menjadi **4 temuan**. Seluruh tes bawaan lulus. Kode aplikasi belum diubah; temuan berikut masih terbuka.

> **Status 27 Oktober 2026:** keempat temuan sudah diperbaiki setelah laporan awal ini dibuat. Angka dan bukti di bagian temuan menggambarkan kondisi sebelum perbaikan; lihat [Tindak lanjut perbaikan](#tindak-lanjut-perbaikan-27-oktober-2026).

## Lingkungan dan bukti

- Commit: `fa10431cdd629f8377ebbd1d77ac30d8be6e14e0`; Java 21.0.8, Node.js 24.22.1, Maven Wrapper 3.9.11.
- Server nyata dari `backend/target/finresolve-backend-0.1.0.jar`, HTTP pada `127.0.0.1:8080`.
- Database eksplorasi terpisah: `/tmp/finresolve-exploratory-20261027/test-final.db`.
- Sampel: `data/cfpb/sample.csv`, 500 kasus, SHA-256 `682bb04cf77a7df10104c822ab24af4bd42e1a088cd2678c7c72921b7983a644`.
- [Hasil per skenario dan bukti status HTTP](exploratory-2026-10-27-results.json), termasuk observasi 12 query dan pemeriksaan setelah restart.
- [Script eksplorasi](../../tools/exploratory-backend.mjs); respons API diperiksa dengan validator kontrak yang sudah ada di `tests/contract/schema.mjs`.
- Log lengkap sesi: `/tmp/finresolve-exploratory-20261027/`. Lokasi `/tmp` bersifat sementara.

Perubahan `.devcontainer/Dockerfile` dan `.devcontainer/devcontainer-lock.json` sudah ada sebelum pengujian dan tidak diubah oleh pekerjaan ini.

## Temuan

| ID | Prioritas | Masalah | Skenario gagal |
| --- | --- | --- | ---: |
| API-01 | Sedang | Tanggal di luar format empat digit tahun menghasilkan HTTP 500 | 3 |
| API-02 | Sedang | Metadata multipart menerima tipe JSON yang melanggar kontrak | 3 |
| API-03 | Rendah | `limit: 1.0` ditolak walaupun valid menurut skema integer | 1 |
| API-04 | Rendah | Respons 405 tidak menyertakan header `Allow` | 1 |

### API-01 — tanggal tidak valid mencapai database

`POST /api/cases` dengan `receivedDate: "+10000-01-01"` atau `"-0001-01-01"` menghasilkan **500 `INTERNAL_ERROR`**. `PUT /api/cases/{id}` dengan tanggal expanded year juga menghasilkan 500. Diharapkan **400** dengan informasi validasi tanggal. Sesudah PUT gagal, detail kasus tetap sama; tidak teramati perubahan parsial.

Contoh dari root repository, setelah katalog diisi:

```bash
curl -i http://127.0.0.1:8080/api/cases \
  -H 'Content-Type: application/json' \
  -d '{"product":"Checking or savings account","issue":"Managing an account","narrative":"Missing bank transfer.","handlingNotes":"Requested transfer trace.","outcomeAssessment":"UNKNOWN","receivedDate":"+10000-01-01"}'
```

Penyebab yang didukung pembacaan kode: `RequestRules.validateString` memakai `LocalDate.parse`, yang menerima tahun diperluas/negatif. Constraint SQLite pada `cases.received_date` hanya menerima bentuk empat digit tahun. Log request tersebut mencatat `UncategorizedSQLException`.

Lokasi: `backend/src/main/java/id/ac/finresolve/http/RequestRules.java:311` dan `backend/src/main/resources/db/migration/V001__initial.sql:30`. Perbaikan yang disarankan: periksa bentuk `YYYY-MM-DD` dan validitas kalender sebelum binding/penulisan.

### API-02 — tipe metadata berubah diam-diam

Pada `POST /api/imports/cfpb`, ketiga input berikut menghasilkan **201**, sementara kontrak mensyaratkan string dan seharusnya menolak dengan **400**:

| Properti yang dikirim | Nilai yang diterima/disimpan dalam metadata |
| --- | --- |
| `"sourceFileName": 123` | `"123"` |
| `"sourceFileName": true` | `"true"` |
| `"periodStart": [2024,11,1]` | `"2024-11-01"` |

Contoh:

```bash
curl -i http://127.0.0.1:8080/api/imports/cfpb \
  -F 'file=@data/cfpb/sample.csv;type=text/csv' \
  -F 'metadata={"archiveUrl":"https://example.com/archive.zip","sourceFileName":123,"periodStart":"2024-11-01","periodEnd":"2024-12-31"};type=application/json'
```

Dampak: input di luar kontrak diterima, provenance dan kunci replay dibentuk dari nilai hasil konversi. Pemeriksaan JSON ketat untuk body biasa tidak mencakup metadata `@RequestPart`; validator metadata bekerja setelah konversi Jackson.

Lokasi: `backend/src/main/java/id/ac/finresolve/imports/ImportsController.java` dan `backend/src/main/java/id/ac/finresolve/http/RequestRules.java`. Perbaikan yang disarankan: validasi tipe JSON mentah pada part metadata, atau batasi coercion pada binding metadata tersebut.

### API-03 — integer JSON diperiksa berdasarkan representasi parser

```bash
curl -i http://127.0.0.1:8080/api/search \
  -H 'Content-Type: application/json' \
  -d '{"product":"Checking or savings account","issue":"Managing an account","narrative":"Missing bank transfer.","limit":1.0}'
```

Aktual: **400 `VALIDATION_ERROR`**, sedangkan `limit: 1` menghasilkan **200**. Validator AJV terhadap `SearchRequest` dari kontrak menerima hasil `JSON.parse` untuk `1.0`; nilai tersebut secara matematis integer dan berada dalam rentang 1–20.

Penyebab: `RequestRules.matchesType` pada baris 409 hanya menerima `isIntegralNumber()`. Perbaikan yang disarankan: terima angka yang bernilai bulat, tetap menolak pecahan dan memeriksa batas sebelum konversi DTO.

### API-04 — respons metode salah tidak mengiklankan metode yang tersedia

```bash
curl -i -X PATCH http://127.0.0.1:8080/api/health
```

Aktual: **405 `METHOD_NOT_ALLOWED`** dengan body JSON yang benar, tetapi tanpa header `Allow`. Diharapkan header tersebut menyertakan metode yang tersedia, termasuk `GET` untuk endpoint ini.

Lokasi: `backend/src/main/java/id/ac/finresolve/http/ApiExceptionHandler.java:148`. Handler membentuk ulang respons dan tidak meneruskan daftar metode dari exception Spring. Perbaikan yang disarankan: tambahkan `Allow` dari supported methods exception.

## Tindak lanjut perbaikan (27 Oktober 2026)

Keempat temuan sudah diperbaiki. Bukti eksplorasi di atas tetap dipertahankan sebagai catatan kondisi sebelum perbaikan.

| ID | Perbaikan | Berkas utama | Tes |
| --- | --- | --- | --- |
| API-01 | Tanggal wajib cocok dengan pola `YYYY-MM-DD` sebelum `LocalDate.parse`, sehingga tahun diperluas/negatif ditolak sebagai 400 tanpa mencapai database. | `RequestRules.java` | `RequestRulesTest`, `ErrorApiTest` |
| API-02 | Part metadata divalidasi sebagai pohon JSON mentah memakai aturan kontrak sebelum binding Jackson, sehingga `123`, `true`, dan array tanggal ditolak 400. Normalisasi trim URL/nama berkas tetap dipertahankan. | `StrictImportMetadataConverter.java`, `WebConfiguration.java` | `ImportApiTest` |
| API-03 | Tipe `integer` menerima angka floating-point yang bernilai bulat (`1.0`, `1e0`) dan tetap menolak pecahan, lalu batas minimum/maksimum diperiksa seperti sebelumnya. | `RequestRules.java` | `RequestRulesTest`, `SearchApiTest` |
| API-04 | Handler 405 meneruskan header `Allow` yang dihitung Spring dari metode yang dipetakan. | `ApiExceptionHandler.java` | `ErrorApiTest` |

Verifikasi ulang pada kondisi setelah perbaikan:

- `npm run verify`: seluruh tahap lulus, termasuk API validation/check, generation, client, backend, package, dan contract.
- `cd backend && ./mvnw test`: **213 tes lulus**, 0 gagal/error/skipped.
- Script eksplorasi yang sama pada database baru: **128/128 skenario lulus, 0 gagal**; exit code 0. Keempat probe API-01 sampai API-04 termasuk dalam hasil tersebut.
- `cd backend && ./mvnw -DskipTests package`: BUILD SUCCESS.

## Cakupan yang lulus

- Database kosong: health, kategori/daftar kosong, `EMPTY_KNOWLEDGE_BASE`, validasi narasi, dan larangan retensi sebelum impor.
- Impor: 500 diterima, checksum cocok, 85 tuple kategori unik; replay identik maupun metadata ber-spasi; metadata berbeda menghasilkan 500 duplikat tanpa menambah kasus.
- CSV salah: part wajib hilang, file kosong/header saja, header wajib hilang/duplikat, kutip rusak, UTF-8 salah, metadata URL/periode/path salah.
- Batas: file di atas 20 MiB ditolak 413; 5.001 record ditolak atomik; narasi tepat 50.000 karakter diterima; narasi lebih panjang dan label/catatan terlalu panjang ditolak; batas Unicode dihitung per code point.
- Baca: 5 halaman memuat 500 UUID unik dengan urutan stabil, filter sumber, halaman di luar jangkauan, angka page ekstrem, parameter salah, UUID salah, dan detail yang tidak ditemukan.
- Pencarian: narasi kasus asli menempatkan kasus itu di urutan pertama dengan skor mendekati 1; status hasil kosong; normalisasi kategori; penolakan kategori/token/tipe/null salah; limit 1 dan 20; kesesuaian rumus skor dan penjelasannya; query tidak menambah kasus.
- Retensi: `Location`, detail/provenance/catatan/referensi, kasus analis langsung dapat dicari, katalog tidak bertambah dari retensi, PUT penuh menghapus field opsional dan mempertahankan identitas/createdAt, CFPB immutable, referensi salah/duplikat/diri sendiri ditolak, kegagalan tidak menulis sebagian.
- Konkruensi: enam penyimpanan bersamaan semuanya tersimpan dengan ID berbeda; tiga impor identik bersamaan menghasilkan satu batch baru dan dua replay.
- Protokol: error JSON untuk route/metode/content type salah, JSON rusak/trailing token ditolak, CORS origin/header yang diizinkan dan ditolak, `Accept: application/xml` menghasilkan 406.
- Restart dengan file database sama: health, halaman daftar/filter, total **511 kasus (503 CFPB + 8 ANALYST)**, dan seluruh detail kasus analis yang diperiksa sama dengan sebelum restart. Data tambahan merupakan fixture eksplorasi.

Nama properti JSON duplikat memakai nilai terakhir. Ini dicatat sebagai observasi, bukan bug, karena kontrak belum menentukan kebijakan penolakan duplikasi key.

## Observasi pencarian pada 500 kasus asli

Seluruh 12 query evaluasi berhasil secara teknis. Jumlah hasil Q01–Q12 berturut-turut: **0, 1, 5, 0, 0, 0, 1, 2, 0, 0, 0, 2**, sama dengan observasi sebelumnya pada panduan evaluasi. Waktu yang diamati untuk request tersebut 83–124 ms pada sesi ini; ini bukan benchmark beban.

Q07 yang berisi narasi tidak terkait masih memperoleh satu hasil dengan skor `0.5424393331124167`. Ini menguatkan keterbatasan ambang yang sudah dicatat di panduan evaluasi. Tidak dibuat label relevansi manusia atau klaim precision@5.

## Verifikasi bawaan

Perintah dijalankan bertahap, bukan satu pemanggilan `npm run verify`:

| Pemeriksaan | Hasil |
| --- | --- |
| `npm ci` | Berhasil; audit melaporkan 0 kerentanan |
| `cd backend && ./mvnw package` | BUILD SUCCESS; 210 tes, 0 gagal/error/skipped |
| `npm run api:validate` | Lulus; 8 operasi, 42 contoh |
| `npm run api:check` | Lulus |
| `npm run test:generation` | 13 lulus |
| `npm run client:typecheck` | Lulus |
| `npm run client:test` | 3 lulus |
| `npm run test:contract` | 5 lulus; termasuk batas kapasitas, database busy, dan error internal terkontrol |

## Menjalankan ulang

Gunakan database baru untuk script eksplorasi. Script menolak database yang sudah berisi kasus; input pengujian akan membuat kasus/batch sintetis.

Terminal pertama, dari root repository:

```bash
FINRESOLVE_DB_PATH="$(mktemp -d)/finresolve.db" \
  SERVER_ADDRESS=127.0.0.1 SERVER_PORT=8081 \
  java -jar backend/target/finresolve-backend-0.1.0.jar
```

Setelah health siap, terminal kedua:

```bash
node tools/exploratory-backend.mjs \
  http://127.0.0.1:8081 /tmp/finresolve-exploratory-results.json
```

Script menghasilkan exit code 1 selama masih ada ekspektasi yang gagal, termasuk empat temuan di atas.

Pada akhir sesi, server demo terpisah ditinggalkan berjalan di **http://127.0.0.1:8080** dengan **500 kasus CFPB asli dan 0 kasus analis**. Database demo: `/tmp/finresolve-exploratory-20261027/demo.db`; log: `/tmp/finresolve-exploratory-20261027/demo.log`; PID: `/tmp/finresolve-exploratory-20261027/demo.pid`. Untuk menghentikannya:

```bash
kill -TERM "$(cat /tmp/finresolve-exploratory-20261027/demo.pid)"
```

Pengujian ini mencakup backend melalui HTTP lokal. Docker Compose, UI, uji beban berkepanjangan, dan penilaian relevansi oleh manusia tidak dijalankan.
