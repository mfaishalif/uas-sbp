# Exploratory Testing Backend FinResolve — 28 Oktober 2026

> **Status penyelesaian:** laporan ini adalah bukti kondisi **sebelum perbaikan**. Seluruh temuan F-01–F-07 telah ditutup dan diuji ulang pada JAR baru; lihat [retest-2026-10-28.md](retest-2026-10-28.md). Isi dan angka pada laporan awal ini dipertahankan tanpa perubahan.

Sesi eksplorasi backend melalui HTTP terhadap server Java nyata, database SQLite sementara, dan sampel CFPB yang sudah di-commit. Fokus sesi: perilaku API serta integritas data setelah refaktor berlapis dan penambahan mode `phrases`, termasuk batas kapasitas, konkurensi, restart, dan kegagalan write lock.

> **Kesimpulan singkat:** sebagian besar kontrak, impor, pencarian, konkurensi, kapasitas, dan persistensi lulus. Namun ada **dua temuan integritas data backend** yang masih terbuka: (1) U+0000 di awal field teks wajib menghasilkan **HTTP 500** dan dapat membatalkan seluruh impor, serta (2) lone UTF-16 surrogate diterima lalu **diam-diam berubah menjadi `?`** saat disimpan. Backend belum disarankan ditutup sebagai “lulus” sampai kedua temuan diperbaiki dan diuji ulang. Dua cacat lain berada di test tooling/fixture, bukan di API.

## 1. Lingkungan, artefak, dan konfigurasi

| Item | Nilai |
| --- | --- |
| Commit diuji | `d14808e43a39ac9ce540feae28e4b989ee2ef6c7` (`refactor: enforce layered backend architecture`) |
| Java | OpenJDK 21.0.8 LTS (Microsoft build) |
| Node.js / npm | 24.22.1 / 11.19.1 |
| Maven Wrapper | 3.9.11 |
| Spring Boot | 4.1.2 |
| JAR | `backend/target/finresolve-backend-0.1.0.jar` |
| SHA-256 JAR | `f86ea245434a927c81947c6e7e5570be3c84ae018281900ff6ad341907499cde` |
| Sampel | `data/cfpb/sample.csv`, 500 kasus |
| SHA-256 sampel | `682bb04cf77a7df10104c822ab24af4bd42e1a088cd2678c7c72921b7983a644` |
| SHA-256 manifest | `fd3d1e569a645da76bbe469a5b2b0b4c0bd16d8f9b0673ae16e499029c7957fa` |
| Database | SQLite sementara per sesi, WAL, pool 1 koneksi, `busy_timeout=5000` |
| Bind / demo | `127.0.0.1`, port terpisah 18080–18104, `FINRESOLVE_DEMO_ENABLED=false` |
| Similarity baseline | `tfidf-cosine-v1`, bobot kategori/teks 0.4/0.6, field 0.35/0.15/0.35/0.15, threshold 0.50, limit default 5/maks 20 |
| Similarity phrases | `tfidf-phrases-v2`, konfigurasi bobot sama, kamus `domain-phrases-v1` |
| Batas | 5.000 kasus; 5.000 record/file; file 20 MiB; request multipart 21 MiB; narasi 50.000 code point; catatan 10.000; referensi 20 |
| CORS | `http://localhost:3000`; header `Content-Type`, `Accept`; `Location` diekspos |

`npm run verify` dijalankan dari commit ini dan **berhasil seluruhnya**: validasi/kontrak OpenAPI, generation check, typecheck/client test, **273 tes backend**, packaging JAR, dan **5 live contract test**. README masih menyebut 267 tes backend; angka aktual adalah 273. Perbedaan ini hanya dokumentasi.

## 2. Metode dan batas sesi

- Server dijalankan dari JAR yang dibangun pada commit diuji, bukan dari IDE atau mock.
- Setiap sesi memakai database baru; sesi kapasitas memakai database terpisah; sesi restart memakai database yang sama sebelum/sesudah restart.
- Semua request dan response dicatat oleh probe: method, path, ringkasan body, status, header penting, body response, dan durasi. Hasil per skenario ada di JSON yang ditautkan pada bagian 7.
- Pemeriksaan atomisitas memakai pembacaan langsung file SQLite yang sama (`node:sqlite`) untuk menghitung kasus/batch sebelum dan sesudah operasi.
- Write lock dikontrol dengan transaksi `BEGIN IMMEDIATE` dari proses terpisah; ini bukan uji beban dan bukan SLA performa.
- Seluruh lima sesi eksplorasi pada rencana dijalankan. Alokasi rencana 480 menit; eksekusi aktual lebih singkat karena probe otomatis, tetapi tidak ada sesi yang dilewati. Durasi request tetap dicatat sebagai observasi, bukan klaim pemenuhan alokasi waktu.
- Docker, UI, autentikasi, uji beban berkepanjangan, dan penilaian relevansi manusia tidak dijalankan.
- Durasi request hanya observasi. Tidak ada SLA baru yang ditetapkan.

## 3. Hasil per sesi

| Sesi | Skenario | PASS | FAIL | BLOCKED | OBSERVASI | Bukti utama |
| --- | ---: | ---: | ---: | ---: | ---: | --- |
| Impor dan provenance | 14 | 10 | 2 | 0 | 2 | `results/2026-10-28-import-provenance.json` |
| Kontrak, validasi, baca | 19 | 18 | 0 | 0 | 1 | `results/2026-10-28-contract-validation.json` |
| Pencarian dan retensi (baseline + phrases + empty) | 73 | 71 | 0 | 2 | 0 | `results/2026-10-28-search-phrases*.json` |
| Konkurensi dan kapasitas | 13 | 13 | 0 | 0 | 0 | `results/2026-10-28-capacity-race.json`, `...-concurrency.json` |
| Persistensi dan kegagalan | 2 | 2 | 0 | 0 | 0 | `results/2026-10-28-persistence-restart.json`, `...-busy.json` |
| Probe Unicode tambahan | 11 | 3 | 7 | 0 | 1 | `results/2026-10-28-unicode-integrity.json` |
| **Total probe terarah** | **132** | **117** | **9** | **2** | **4** |  |

Sembilan FAIL terarah berasal dari dua akar masalah backend: lima percobaan U+0000 menghasilkan 500 dan dua percobaan lone surrogate menghasilkan mutasi data; dua FAIL lainnya adalah pengulangan U+0000 pada sesi impor. Dua BLOCKED adalah Q23 pada baseline dan phrases (fixture, bukan API).

### 3.1 Regression suite bawaan

| Kondisi | Skenario | PASS | FAIL | Catatan |
| --- | ---: | ---: | ---: | --- |
| `tools/exploratory-backend.mjs` apa adanya | 140 | 116 | 24 | 24/24 query evaluasi gagal karena bug parsing kolom |
| Salinan dengan perbaikan satu baris | 140 | 139 | 1 | Q23 gagal karena kategori fixture tidak ada di katalog sampel |
| `npm run verify` | — | lulus | 0 | 273 tes backend, 5 live contract test, JAR terbangun |

Angka 140 berasal dari 24 query evaluasi saat ini. Laporan historis 27 Oktober mencatat 128 skenario karena saat itu memakai 12 query; angka lama tidak dipakai ulang.

## 4. Temuan

### F-01 — U+0000 di awal field teks wajib menghasilkan HTTP 500 dan membatalkan impor

**Prioritas:** P1 — High  
**Klasifikasi:** pelanggaran kontrak/penanganan error dan integritas alur impor  
**Status:** terbuka, reproducible

**Expected:** input dengan karakter kontrol U+0000 ditolak sebagai **400 `VALIDATION_ERROR`**. Untuk impor, baris tersebut menjadi **row-level rejection** (atau file-level 400) tanpa membuang baris valid lain. Tidak boleh ada **500** untuk input yang dikendalikan client.

**Actual:**
- `POST /api/cases` dengan `narrative: "\u0000transfer"` → **500 `INTERNAL_ERROR`**, tidak ada kasus tertulis.
- `POST /api/cases` dengan `handlingNotes: "\u0000notes"` → **500 `INTERNAL_ERROR`**, tidak ada kasus tertulis.
- `POST /api/imports/cfpb` dengan satu baris valid + satu baris `narrative: "\u0000..."` → **500**; kasus dan batch **tidak berubah**, sehingga baris valid dalam file yang sama juga tidak masuk.
- Baris dengan `Product` atau `Issue` berawalan U+0000 → **500**.
- Metadata `sourceFileName: "\u0000file.csv"` → **500**.

**Reproduksi (ringkas):**

```bash
# database baru, server pada 127.0.0.1:18101
node docs/testing/probes/unicode-integrity.mjs \
  http://127.0.0.1:18101 /tmp/unicode.db \
  docs/testing/results/2026-10-28-unicode-integrity.json
```

Probe `UNI-01` dan `UNI-02` mengirim U+0000 pada narasi/catatan retensi. `UNI-03`–`UNI-05` mengirim baris valid + baris U+0000 pada narasi, produk, dan metadata. Semua berakhir 500. Pengulangan pada sesi impor tercatat sebagai `IMP-12` dan `IMP-13` di `results/2026-10-28-import-provenance.json`.

**Bukti utama:**

- `results/2026-10-28-unicode-integrity.json` — `UNI-01`–`UNI-05`, dengan `before/after` kasus dan batch.
- `results/2026-10-28-import-provenance.json` — `IMP-12`, `IMP-13`.
- Request/response body dan timestamp ada pada array `requests` setiap hasil.

**Dampak:** satu baris malformed dapat menggagalkan seluruh impor dan menghilangkan kesempatan menyimpan baris valid. Retensi juga mengembalikan 500, bukan kesalahan validasi yang dapat ditindaklanjuti client. Tidak ada perubahan parsial, tetapi kontrak “baris invalid tidak membatalkan baris valid” tidak terpenuhi.

**Akar masalah yang didukung pembacaan kode:** validasi Java menganggap U+0000 bukan whitespace dan tokenizer tetap menemukan kata setelah NUL, sehingga request lolos ke persistence. SQLite `length()`/`trim()` berhenti pada NUL, sehingga `CHECK (length(trim(...)) > 0)` pada `cases.product`, `cases.issue`, `cases.narrative`, `analyst_notes.handling_notes`, dan `import_batches.source_file_name` gagal. Exception database tidak dipetakan ke 400 dan keluar sebagai 500. Lokasi relevan: `InputRules`, `CfpbCsvParser`, `ImportMetadataValue`, `RetentionRules`, `ApiExceptionHandler`.

**Saran perbaikan (dijadwalkan terpisah):** tolak U+0000 (dan idealnya karakter kontrol C0 yang tidak diizinkan) pada validasi teks sebelum persistence, termasuk metadata impor. Untuk impor, hasilkan row-level `INVALID_FORMAT` agar baris valid lain tetap tersimpan. Tambahkan tes integrasi yang menulis dan membaca kembali field tersebut.

### F-02 — Lone UTF-16 surrogate diterima lalu diam-diam disimpan sebagai `?`

**Prioritas:** P2 — Medium  
**Klasifikasi:** pelanggaran integritas data/konsistensi response  
**Status:** terbuka, reproducible

**Expected:** karakter Unicode yang bukan scalar value (lone high/low surrogate, misalnya `\uD800` dalam escape JSON) ditolak dengan 400, atau jika diterima harus disimpan dan dikembalikan persis sama. Response POST/PUT, GET berikutnya, laporan impor, replay, dan provenance harus konsisten.

**Actual:**
- `POST /api/cases` dengan `narrative: "transfer\uD800account"`, `handlingNotes: "notes\uD800"`, `company: "company\uD800"`, `evaluationNotes: "eval\uD800"` → **201**. Response POST masih memuat surrogate asli.
- `GET /api/cases/{id}` berikutnya mengembalikan `transfer?account`, `notes?`, `company?`, `eval?` — karakter sudah berubah menjadi `?`.
- Impor dengan metadata `sourceFileName: "sur\uD800.csv"` → **201** dan laporan pertama memuat `sur\uD800.csv`. Replay byte/metadata yang sama → **200**, tetapi laporan replay memuat `sur?.csv`; provenance tersimpan juga `sur?.csv`.

**Reproduksi:**

```bash
node docs/testing/probes/unicode-integrity.mjs \
  http://127.0.0.1:18101 /tmp/unicode.db \
  docs/testing/results/2026-10-28-unicode-integrity.json
```

Lihat `UNI-07` (POST vs GET) dan `UNI-08` (first import vs replay dan provenance). `UNI-06` menunjukkan emoji valid (surrogate pair) tetap utuh, sehingga masalah spesifik pada lone surrogate.

**Dampak:** data yang dilaporkan berhasil disimpan berbeda dari data yang dapat dibaca kembali. Untuk impor, laporan pertama dan replay dapat menyebut nama berkas/metadata yang berbeda. Ini menyulitkan audit provenance dan dapat memecahkan idempotensi jika client membandingkan response.

**Akar masalah yang paling konsisten dengan bukti:** Jackson menerima escape lone surrogate dan membentuk `String` Java yang bukan Unicode scalar. Lapisan persistence/encoding UTF-8 kemudian menyimpannya sebagai `?`; lokasi tepat (driver atau binder) perlu dikonfirmasi saat perbaikan. Response POST dan laporan impor pertama dibangun dari objek in-memory, sedangkan GET/replay/provenance dibaca dari database, sehingga kedua representasi berbeda. Validasi kode poin saat ini tidak memeriksa scalar value.

**Saran perbaikan:** validasi Unicode scalar value pada body JSON dan metadata (tolak unpaired surrogate), atau normalisasi secara eksplisit dan gunakan hasil normalisasi untuk response. Tambahkan tes round-trip POST → GET dan import → replay → detail.

### F-03 — `tools/exploratory-backend.mjs` salah memetakan kolom query evaluasi

**Prioritas:** P2 — Medium (test infrastructure, bukan API)  
**Klasifikasi:** cacat test tooling  
**Status:** terbuka

**Expected:** script memetakan `kind` dengan benar sehingga query evaluasi dijalankan dengan tuple kategori/narasi aslinya.

`docs/evaluation/queries.csv` memiliki 8 kolom: `queryId,kind,product,subProduct,issue,subIssue,narrative,scenario`. Script masih mendestrukturisasi 7 nilai tanpa melewati `kind`:

```js
const [queryId, product, subProduct, issue, subIssue, narrative, scenario] = csvFields(line);
```

Akibatnya `product` menjadi `"complaint"`/`"negative"`, `subProduct` menjadi produk sebenarnya, dan seterusnya. Semua 24 query evaluasi mengirim tuple yang salah dan gagal `400 INVALID_CATEGORY`/`VALIDATION_ERROR`.

**Actual:** 140 skenario, 116 PASS, 24 FAIL, 176 request.  
**Perbaikan yang diuji:** salinan dengan `const [queryId, , product, ...]` menghasilkan 140 skenario, 139 PASS, 1 FAIL (Q23, lihat F-04). Tidak ada perubahan kode aplikasi.

**Bukti:** `results/2026-10-28-regression-as-shipped.json`, `results/2026-10-28-regression-corrected.json`, `results/2026-10-28-regression-corrected.log`.

**Dampak:** regression suite manual memberi exit code 1 dan 24 kegagalan palsu; sinyal regresi backend menjadi tidak dapat dipercaya. Perbaikan satu baris diperlukan sebelum sesi berikutnya.

### F-04 — Q23 memakai kategori yang tidak ada di katalog sampel

**Prioritas:** P3 — Low (test data/fixture, bukan API)  
**Klasifikasi:** cacat data evaluasi; perilaku backend benar  
**Status:** BLOCKED

**Expected:** seluruh 24 query evaluasi dapat direplay melalui API terhadap sampel yang sama, atau query yang memang offline-only ditandai eksplisit.

Q23 mengirim `issue: "Unexpected or other fees"` untuk `Checking or savings account`. Tuple tersebut tidak ada pada katalog 500 kasus sampel. `POST /api/search` dengan corpus non-kosong mengembalikan **400 `INVALID_CATEGORY`** pada field `issue`, sesuai kontrak keanggotaan katalog.

Evaluasi offline memakai `EvaluationRunner` dan tidak melewati validasi katalog API, sehingga Q23 tetap dapat dihitung di studi, tetapi tidak dapat direplay melalui HTTP dengan sampel ini. Baseline dan phrases sama-sama BLOCKED. 23 query lainnya lulus di kedua mode.

**Bukti:** `results/2026-10-28-search-phrases-baseline.json` dan `...-phrases.json`, entri `SRCH-EVAL-Q23`; `results/2026-10-28-regression-corrected.json` juga mencatat satu kegagalan Q23.

**Dampak:** tidak ada bug API; cakupan replay 24/24 melalui HTTP tidak tercapai. Perlu keputusan: ubah tuple Q23 ke kategori yang valid di sampel, atau dokumentasikan Q23 sebagai offline-only.

### F-05 — Referensi UUID dengan kapitalisasi berbeda dideduplikasi diam-diam

**Prioritas:** P3 — Low  
**Klasifikasi:** perilaku belum ditentukan/ambiguitas kontrak  
**Status:** observasi

**Expected:** duplikat referensi semantik (UUID yang sama, termasuk perbedaan kapitalisasi) ditolak konsisten, atau normalisasi didokumentasikan sebagai perilaku resmi.

- `referenceCaseIds: [id, id]` → **400 `VALIDATION_ERROR`**, field `referenceCaseIds[1]`, code `DUPLICATE`.
- `referenceCaseIds: [id, id.toUpperCase()]` → **201**, dan response hanya memuat satu referensi.

`uniqueItems` pada JSON membandingkan string, sehingga dua bentuk kapitalisasi adalah dua string berbeda; tetapi secara identitas UUID keduanya merujuk kasus yang sama. Model generated memakai `Set<UUID>` sehingga deduplikasi terjadi sebelum `RetentionRules` melihat duplikat.

**Bukti:** `results/2026-10-28-contract-validation.json`, `CTR-07`.

**Dampak:** normalisasi diam-diam; tidak ada baris referensi ganda di database. Perlu keputusan kontrak apakah duplikat semantik harus 400. Jika ya, normalisasi UUID sebelum pemeriksaan `uniqueItems` atau bandingkan ukuran list mentah dengan set.

### F-06 — `sourceFileName` menerima `.`, `..`, dan `C:x`

**Prioritas:** P3 — Low  
**Klasifikasi:** gap validasi level deskripsi (belum tentu pelanggaran skema)  
**Status:** observasi

**Expected:** nama berkas yang jelas berupa path-segment/drive-relative (`.`, `..`, `C:x`) juga ditolak, sesuai deskripsi “tanpa path lokal”.

`../x.csv`, `a/b.csv`, dan `a\b.csv` ditolak 400. Namun `.`, `..`, dan `C:x` diterima 201 dan disimpan sebagai provenance. OpenAPI hanya memberi `minLength`/`maxLength`; deskripsi menyatakan “tanpa path lokal”.

**Bukti:** `results/2026-10-28-import-provenance.json`, `IMP-11`.

**Dampak:** nilai provenance dapat berbentuk path-segment/drive-relative. Tidak ada akses filesystem; dampak utama adalah kualitas metadata. Jika deskripsi ingin ditegakkan, batasi ke basename dan tolak `.`/`..`/prefix drive.

### F-07 — Baris kosong di akhir file dihitung sebagai record ditolak

**Prioritas:** P4 — Low  
**Klasifikasi:** perilaku belum ditentukan  
**Status:** observasi

**Expected:** kebijakan baris kosong dinyatakan eksplisit. Jika baris kosong dianggap bukan record, ia tidak boleh menambah `processed`/`rejected`; jika dianggap record invalid, perilaku saat ini konsisten.

File dengan satu baris valid dan satu baris kosong tambahan menghasilkan `processed=2, imported=1, rejected=1, duplicates=0`; error `recordNumber=2`, field `Complaint ID`, code `REQUIRED`. Baris valid tetap masuk.

Parser memakai `setIgnoreEmptyLines(false)` secara eksplisit. Jika kebijakan “baris kosong adalah record invalid” disengaja, perilaku ini konsisten; jika file CSV umum dengan baris kosong akhir harus diabaikan, perlu perubahan dan tes.

**Bukti:** `results/2026-10-28-import-provenance.json`, `IMP-10`.

## 5. Perilaku yang belum ditentukan / observasi non-bug

| ID | Observasi | Klasifikasi |
| --- | --- | --- |
| OBS-01 | Write lock eksternal menghasilkan 503 `DATABASE_BUSY` dalam 26–32 ms, bukan setelah 5 detik. Transaksi yang membaca lalu menulis mendapat `SQLITE_BUSY`/snapshot upgrade segera. Health dan read tetap 200. | Observasi; kontrak hanya mensyaratkan 503 dan pemulihan |
| OBS-02 | BOM UTF-8 diterima. Checksum dihitung atas byte mentah, sehingga CSV yang sama tanpa BOM bukan replay, melainkan duplikat (201, `imported=0`, `duplicates=1`). | Sesuai definisi replay byte+metadata; bukan bug |
| OBS-03 | `option` kosong pada query `page`/`size`/`source` ditolak; `page=abc`/`1.5` memakai kode `MALFORMED_REQUEST`, sedangkan `page=-1`/`size=0` memakai `VALIDATION_ERROR`. Keduanya 400. | Perbedaan kode tidak diatur eksplisit |
| OBS-04 | `Accept: application/xml` → 406 tanpa body; `*/*`/`application/json` → 200 JSON. | Perilaku kontrak yang wajar |
| OBS-05 | Actual request dengan origin asing → 403 `Invalid CORS request` (text), tanpa ACAO; preflight asing → 403. | Tidak didefinisikan detail body 403 |
| OBS-06 | Nama field dan pesan error impor memakai kolom CSV asli; `NO_MEANINGFUL_TOKENS` dipetakan ke `INVALID_FORMAT` pada `ImportRowError` karena enum kontrak tidak memuat kode tersebut. | Pemetaan yang diperlukan |
| OBS-07 | `README` menyebut 267 tes backend; hasil `npm run verify` adalah 273. | Dokumentasi stale |

## 6. Cakupan yang lulus

- **Database kosong:** health `UP/UP`, kategori/daftar kosong, `EMPTY_KNOWLEDGE_BASE` tanpa validasi katalog, retensi ditolak 409 `EMPTY_CATEGORY_CATALOG`.
- **Impor:** sampel 500 diterima dengan checksum manifest; replay byte identik dan metadata ber-spasi; metadata berbeda menghasilkan 500 duplikat tanpa overwrite provenance; campuran valid/invalid/duplikat terklasifikasi; record valid pertama menang; ID melebihi batas integer aman JavaScript dipertahankan sebagai string; multiline quoted field dan CRLF; BOM; semua baris invalid tetap membuat batch 201 dengan maksimal 20 error dan `errorsTruncated`; 5.001 record dan file >20 MiB atomik; CSV rusak/header hilang/duplikat/UTF-8 invalid 400.
- **Baca dan kontrak:** delapan operasi; pagination total/pages/hasNext stabil dan setiap kasus muncul sekali; filter `CFPB`/`ANALYST`; halaman ekstrem kosong dengan total benar; 404/405/415/413; `Allow`, `Location`, CORS aktual/preflight, content negotiation; validasi field hilang/null/blank/tipe salah/properti asing/UUID/tanggal; batas 500/2.000/10.000/50.000 code point termasuk emoji.
- **Pencarian:** tiga status; limit default/1/20; kategori opsional null/blank; normalisasi case/whitespace; penolakan narasi tanpa token; formula skor, threshold, urutan, empat explanation, `sharedTerms` ≤10; kueri tidak menulis data; 23/24 query evaluasi lulus di baseline dan phrases terhadap korpus identik 512 kasus.
- **Phrases:** `tfidf-phrases-v2` dilaporkan; token kanonis `atm`, `duplicatecharge`, `heldfunds` muncul; frasa case-insensitive, whitespace berulang, word-bounded, tidak melintasi tanda kalimat; narasi asli tetap identik; baseline melaporkan `tfidf-cosine-v1`.
- **Retensi:** `Location`; detail/notes/references; kasus analis langsung dapat dicari; katalog tidak bertambah; PUT penuh membersihkan field dan mempertahankan `createdAt`; CFPB immutable 409; referensi tidak ada/duplikat/diri sendiri 400; kegagalan tidak menulis parsial.
- **Konkurensi:** empat impor identik → satu batch baru + tiga replay; enam POST → enam ID berbeda; delapan PUT bersamaan → final persis salah satu payload lengkap, GET tidak melihat campuran; pencarian saat impor hanya melihat korpus sebelum atau sesudah, tidak campuran; dua impor ID sama → satu kasus, dua batch, provenance salah satu pemenang.
- **Kapasitas:** 4.998 → 4.999 → race impor/retensi untuk slot ke-5.000; retensi menang, impor 409, total tepat 5.000, batch impor kalah tidak dibuat; impor/retensi baru 409; replay 200; impor duplikat saja 201 tanpa menambah kasus; PUT at 5.000 200; pencarian `corpusSize=5000`.
- **Persistensi:** restart dengan file DB sama mempertahankan health, kategori, semua halaman, ID, detail, notes, referensi, timestamp, dan replay batch; replay 200 dengan batch yang sama.
- **Kegagalan:** write lock → PUT/POST/impor 503 `DATABASE_BUSY`, health/read 200, jumlah kasus/batch tidak berubah, PUT lama tidak berubah; setelah lock dilepas PUT 200 dan impor 201.

## 7. Bukti dan probe

Semua hasil JSON menyimpan `session`, `summary`, `results` (ID, hipotesis, hasil, durasi, detail/data change, request), dan `requests` (method, path, ringkasan body, status, header, body, ms).

| Berkas | Isi |
| --- | --- |
| `results/2026-10-28-import-provenance.json` | IMP-01–IMP-14, termasuk temuan U+0000 dan observasi baris kosong/path |
| `results/2026-10-28-contract-validation.json` | CTR-01–CTR-10, termasuk CORS, header, batas, referensi |
| `results/2026-10-28-search-phrases.json` | Sesi phrases: 23 query evaluasi + probe frasa + alur retensi |
| `results/2026-10-28-search-phrases-baseline.json` | Sesi baseline dengan korpus identik |
| `results/2026-10-28-search-phrases-empty.json` | Status `EMPTY_KNOWLEDGE_BASE` pada DB kosong |
| `results/2026-10-28-capacity-race.json` | Kapasitas 4.998/4.999/5.000 dan race |
| `results/2026-10-28-concurrency.json` | Impor identik, POST/PUT bersamaan, snapshot pencarian, ID bertabrakan |
| `results/2026-10-28-persistence-restart.json` | Perbandingan state sebelum/sesudah restart |
| `results/2026-10-28-persistence-busy.json` | Write lock, 503, atomisitas, retry |
| `results/2026-10-28-unicode-integrity.json` | U+0000, lone surrogate, emoji valid |
| `results/2026-10-28-regression-as-shipped.json` | Regression suite apa adanya: 116/140 |
| `results/2026-10-28-regression-corrected.json` | Regression suite dengan perbaikan parsing: 139/140 |
| `results/2026-10-28-regression-corrected.log` | Log console sesi corrected |

Probe dapat dijalankan ulang dengan Node.js 24:

```bash
# contoh: temuan Unicode pada database baru
FINRESOLVE_DEMO_ENABLED=false java -jar backend/target/finresolve-backend-0.1.0.jar \
  --server.address=127.0.0.1 --server.port=18101 \
  --finresolve.db.path=/tmp/finresolve-unicode.db
node docs/testing/probes/unicode-integrity.mjs \
  http://127.0.0.1:18101 /tmp/finresolve-unicode.db /tmp/unicode.json

# impor/provenance
node docs/testing/probes/import-provenance.mjs \
  http://127.0.0.1:18100 /tmp/finresolve-import.db /tmp/import.json

# kapasitas (DB kosong) dan konkurensi
node docs/testing/probes/capacity-race.mjs http://127.0.0.1:18102 /tmp/capacity.db /tmp/capacity.json
node docs/testing/probes/concurrency.mjs http://127.0.0.1:18103 /tmp/concurrency.db /tmp/concurrency.json

# pencarian baseline/phrases: seed korpus identik pada dua server lebih dulu
node docs/testing/probes/seed-search-corpus.mjs http://127.0.0.1:18083
node docs/testing/probes/seed-search-corpus.mjs http://127.0.0.1:18082
node docs/testing/probes/search-phrases.mjs \
  http://127.0.0.1:18083 http://127.0.0.1:18082 http://127.0.0.1:18094 /tmp/search.json

# persistensi: seed, restart server dengan DB yang sama, verify, lalu busy
node docs/testing/probes/persistence.mjs seed http://127.0.0.1:18104 /tmp/state.json
# ...restart JAR pada port/DB yang sama...
node docs/testing/probes/persistence.mjs verify http://127.0.0.1:18104 /tmp/state.json /tmp/restart.json
node docs/testing/probes/persistence.mjs busy http://127.0.0.1:18104 /tmp/persist.db /tmp/busy.json
```

Untuk regression suite, salinan corrected dibuat dengan melewati kolom `kind`:

```bash
cp tools/exploratory-backend.mjs tools/.exploratory-fixed.tmp.mjs
sed -i 's/const \[queryId, product/const [queryId, , product/' tools/.exploratory-fixed.tmp.mjs
node tools/.exploratory-fixed.tmp.mjs http://127.0.0.1:18085 /tmp/regression-corrected.json
rm tools/.exploratory-fixed.tmp.mjs
```

## 8. Kesiapan

**Belum disarankan menutup backend sebagai lulus.** Dua temuan backend terbuka:

1. **F-01 (P1)** harus diperbaiki karena input client dapat menghasilkan 500 dan membatalkan impor baris valid.
2. **F-02 (P2)** harus diperbaiki karena data yang dilaporkan tersimpan berbeda dari data yang dibaca kembali, termasuk metadata provenance.

Setelah kedua perbaikan, regression corrected (139/140, Q23 fixture) dan 23/24 query evaluasi di kedua mode menunjukkan kondisi kontrak yang baik. F-03/F-04 perlu diselesaikan agar regression suite dan replay query evaluasi dapat dipercaya; F-05–F-07 adalah keputusan kontrak/observasi, bukan blocker fungsional.

Waktu pengujian yang berakhir tidak dianggap sebagai bukti lulus. Kesiapan akhir bergantung pada penutupan temuan terbuka dan pengujian ulang pada kondisi awal yang diketahui.

## 9. Batasan

- Docker Compose, UI, autentikasi, deployment publik, dan uji beban berkepanjangan tidak dijalankan.
- Penilaian relevansi manusia tidak dilakukan; skor 0–1 adalah kemiripan, bukan probabilitas keberhasilan atau precision@5 final.
- Write lock dikontrol dari proses terpisah; hasil 503 dan durasi hanya observasi, bukan SLA.
- Database/probe berjalan di `/tmp`; hasil JSON yang di-commit adalah bukti utama. Log server mentah bersifat sementara.
- Laporan historis 27 Oktober tetap di `docs/evaluation/exploratory-2026-10-27.md` dan tidak diubah. Laporan sesi ini berada di `docs/testing/` agar tidak menimpa arsip evaluasi.
