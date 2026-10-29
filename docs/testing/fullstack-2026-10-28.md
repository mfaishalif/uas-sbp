# Pengujian Fungsional dan Eksploratif FinResolve — 28 Oktober 2026

Sesi pengujian penuh terhadap backend/API, frontend Next.js, integrasi proxy, dan browser Chromium desktop serta emulasi mobile. Kode aplikasi tidak diubah. Seluruh artefak sesi ini berada di [`results/2026-10-28-full-155053/`](results/2026-10-28-full-155053/); laporan dan hasil historis sebelumnya tidak diubah.

> **Status sesi:** `npm run verify` **PASS** (341 tes otomatis), `npm run frontend:e2e` **2/2 PASS**, probe backend **141/141 PASS**, browser exploratory **42/42 PASS**, verifikasi restart **5/5 PASS**, verifikasi phrases UI **2/2 PASS**. Satu temuan baru **P3 / FS-01** ditemukan pada batas karakter frontend: **191 PASS, 1 FAIL, 0 BLOCKED** dari 192 skenario terarah. Tidak ada kegagalan integritas data backend pada skenario yang dijalankan.

> **Catatan arsip:** laporan ini merekam kondisi **sebelum** perbaikan FS-01. Hasil FAIL FS-01 dipertahankan apa adanya. Perbaikan dan hasil retest terpisah ada di [retest-fs01-2026-10-28.md](retest-fs01-2026-10-28.md).

## 1. Artefak yang diuji

| Item | Nilai |
| --- | --- |
| Commit | `eefef2efa48657e35e0da41586b91fcc15268bd1` — `feat(frontend): add precedent search and case management UI (#10)` |
| Kondisi working tree awal | Bersih (`git status --porcelain` kosong) |
| Kondisi akhir | Hanya artefak baru di `docs/testing/`; tidak ada diff kode aplikasi |
| Java | OpenJDK 21.0.8 LTS (Microsoft build) |
| Node.js / npm | 24.22.1 / 11.19.1 |
| Maven Wrapper | 3.9.11 |
| Spring Boot | 4.1.2 |
| Next.js / React | 16.4.3 / 19.3.1 |
| Playwright / Chromium | 1.64.1 / Chromium 156.0.8078.4 |
| `@axe-core/playwright` | 4.10.2 (terpasang di workspace frontend) |
| JAR awal | SHA-256 `1e8c7f224dae2388c8a1a6e9b02c9ad4d5d4b14a70d11b9ab9ef1d2538d93223` |
| JAR setelah `npm run verify` | `backend/target/finresolve-backend-0.1.0.jar`, SHA-256 **`aa04c6f1f06eb68f81028f0c5252d2144172705bb4dcc18d340a9b9d3e4a761c`** |
| Sampel | `data/cfpb/sample.csv`, 500 kasus, SHA-256 `682bb04cf77a7df10104c822ab24af4bd42e1a088cd2678c7c72921b7983a644` |
| Manifest | SHA-256 `fd3d1e569a645da76bbe469a5b2b0b4c0bd16d8f9b0673ae16e499029c7957fa` |
| SQLite | 3.53.4 (driver aplikasi), WAL, satu koneksi tulis |
| Mode similarity | `baseline` (`tfidf-cosine-v1`) dan `phrases` (`tfidf-phrases-v2`) |
| Bind pengujian | Loopback; probe 18210–18218, browser 8080/3000 |
| Database | SQLite sementara terpisah per sesi di `/tmp`; `FINRESOLVE_DEMO_ENABLED=false` |
| Database pengguna | Tidak dipakai. `backend/data/finresolve.db` tidak ada di workspace sebelum/sesudah sesi |

Detail lingkungan dan hash dicatat di [`environment.txt`](results/2026-10-28-full-155053/environment.txt).

## 2. Metode dan batas

- `npm run verify` dijalankan dari commit bersih; JAR yang diuji adalah hasil packaging pipeline tersebut, bukan JAR lama.
- `npm run frontend:e2e` memakai JAR yang sama, database sementara baru, dan build produksi Next.js.
- Probe backend dijalankan ulang terhadap JAR baru. Setiap sesi memakai database kosong baru; sesi persistensi sengaja memakai database yang sama sebelum/sesudah restart.
- Browser diuji pada Chromium headless 1440×1000 dan emulasi viewport 390×844. Bukan perangkat mobile fisik.
- Exploratory browser memakai build produksi, proxy rewrite Next.js, dan database sekali pakai. Skenario kegagalan API disimulasikan di batas browser (`route.abort`/`route.fulfill`), bukan dengan menghentikan backend pada tengah transaksi.
- Pemeriksaan integritas memakai API dan pembacaan langsung file SQLite sementara (`node:sqlite`) untuk menghitung kasus/batch.
- Autentikasi, deployment publik, Docker Compose, uji beban berkepanjangan, dan penilaian relevansi manusia berada di luar cakupan.

## 3. Hasil agregat

| Kelompok | Skenario/tes | PASS | FAIL | BLOCKED | Bukti |
| --- | ---: | ---: | ---: | ---: | --- |
| `npm run verify` (kontrak, generation, typecheck, unit, integration, build, live contract) | 341 tes + gerbang build | 341 | 0 | 0 | [`verify.log`](results/2026-10-28-full-155053/verify.log) |
| `npm run frontend:e2e` | 2 | 2 | 0 | 0 | [`frontend-e2e.log`](results/2026-10-28-full-155053/frontend-e2e.log) |
| Probe backend terarah | 141 | 141 | 0 | 0 | [`backend-*.json`](results/2026-10-28-full-155053/) |
| Browser exploratory | 42 | 42 | 0 | 0 | [`browser-exploratory.json`](results/2026-10-28-full-155053/browser-exploratory.json) |
| Verifikasi browser setelah restart | 5 | 5 | 0 | 0 | [`browser-restart.json`](results/2026-10-28-full-155053/browser-restart.json) |
| Phrases mode melalui UI | 2 | 2 | 0 | 0 | [`browser-phrases.json`](results/2026-10-28-full-155053/browser-phrases.json) |
| Batas karakter frontend (temuan FS-01) | 2 | 1 | 1 | 0 | [`emoji-input.json`](results/2026-10-28-full-155053/emoji-input.json) |
| **Total skenario terarah** | **192** | **191** | **1** | **0** |  |
| **Total termasuk gerbang otomatis** | **535** | **534** | **1** | **0** |  |

Angka total tersebut benar secara aritmetika, tetapi **bukan persentase cakupan**: beberapa pemeriksaan tumpang tindih dan memeriksa alur yang sama dari lapisan berbeda.

Angka 341 berasal dari 294 backend test, 22 frontend Vitest, 13 generation test, 4 tooling test, 3 client test, dan 5 live contract test. Validasi OpenAPI, generation check, typecheck, dan build juga lulus dalam perintah yang sama.

## 4. Hasil per kelompok

### 4.1 `npm run verify` — PASS

| Pemeriksaan | Hasil |
| --- | --- |
| `api:validate`, `api:check` | PASS; generated client sesuai kontrak, boundary Java abstrak dan lengkap |
| `test:generation` | 13/13 PASS |
| `test:tooling` | 4/4 PASS |
| `client:typecheck`, `client:test` | PASS; 3/3 client test |
| `frontend:typecheck`, `frontend:test` | PASS; 22/22 Vitest |
| `frontend:build` | PASS |
| `backend:test` | 294/294 PASS, 0 failure/error/skip |
| `backend:package` | PASS; JAR SHA-256 di atas |
| `test:contract` | 5/5 PASS, termasuk live contract terhadap server nyata |

Log lengkap: [`verify.log`](results/2026-10-28-full-155053/verify.log).

### 4.2 Browser E2E resmi — 2/2 PASS

- Alur impor 500 kasus → pencarian → pilih referensi → retensi → detail → edit → proteksi CFPB → mobile → keyboard → color-contrast.
- Unggah CSV 19 MiB melalui proxy Next.js.
- Waktu: 8,2 s dan 1,9 s; total 29,0 s. Tidak ada retry.
- Bukti: [`frontend-e2e.log`](results/2026-10-28-full-155053/frontend-e2e.log), [`playwright-test-results/`](results/2026-10-28-full-155053/playwright-test-results/).

### 4.3 Probe backend terarah — 141/141 PASS

| Sesi | Skenario | PASS | Bukti |
| --- | ---: | ---: | --- |
| Unicode integrity | 19 | 19 | [`backend-unicode.json`](results/2026-10-28-full-155053/backend-unicode.json) |
| Impor dan provenance | 15 | 15 | [`backend-import-provenance.json`](results/2026-10-28-full-155053/backend-import-provenance.json) |
| Kontrak dan validasi | 19 | 19 | [`backend-contract-validation.json`](results/2026-10-28-full-155053/backend-contract-validation.json) |
| Kapasitas 5.000 dan race slot terakhir | 8 | 8 | [`backend-capacity-race.json`](results/2026-10-28-full-155053/backend-capacity-race.json) |
| Konkurensi | 5 | 5 | [`backend-concurrency.json`](results/2026-10-28-full-155053/backend-concurrency.json) |
| Persistensi restart | 1 | 1 | [`backend-persistence-restart.json`](results/2026-10-28-full-155053/backend-persistence-restart.json) |
| Write lock / `DATABASE_BUSY` | 1 | 1 | [`backend-persistence-busy.json`](results/2026-10-28-full-155053/backend-persistence-busy.json) |
| Pencarian baseline | 39 | 39 | [`backend-search-phrases-baseline.json`](results/2026-10-28-full-155053/backend-search-phrases-baseline.json) |
| Pencarian phrases | 33 | 33 | [`backend-search-phrases.json`](results/2026-10-28-full-155053/backend-search-phrases.json) |
| Pencarian database kosong | 1 | 1 | [`backend-search-phrases-empty.json`](results/2026-10-28-full-155053/backend-search-phrases-empty.json) |
| **Total** | **141** | **141** |  |

Temuan penting yang direproduksi ulang:

- Sampel 500 kasus diimpor dengan `processed=500, imported=500, duplicates=0, rejected=0`; replay byte+metadata mengembalikan batch yang sama dan tidak menambah kasus.
- Baris invalid tidak membatalkan baris valid; campuran valid/invalid menghasilkan hitungan konsisten.
- Kapasitas: 4.998 → 4.999 → race impor/retensi untuk slot ke-5.000. Retensi menang, impor 409 `KNOWLEDGE_BASE_LIMIT_EXCEEDED`, total tepat 5.000, dan impor kalah tidak membuat batch.
- Konkurensi: empat impor identik menghasilkan satu `201` dan tiga `200` replay dengan batch yang sama; enam retensi paralel menghasilkan enam ID; delapan PUT paralel berakhir pada satu payload lengkap; pencarian tidak melihat korpus campuran.
- Write lock: PUT/POST/impor menghasilkan 503 `DATABASE_BUSY` (68–106 ms pada sesi ini), health dan read tetap 200, tidak ada perubahan data, lalu retry PUT 200 dan impor 201.
- Restart: 501 kasus, kategori, semua halaman, detail, referensi, catatan, timestamp, dan replay batch identik sebelum/sesudah restart.
- Unicode backend: U+0000 dan lone surrogate ditolak; emoji valid tetap utuh setelah round-trip.
- Q23 tetap lulus sebagai tes negatif `400 INVALID_CATEGORY` pada baseline, phrases, dan regression.

### 4.4 Browser exploratory — 42/42 PASS

Cakupan baru di luar suite resmi:

| Area | Bukti utama |
| --- | --- |
| Empty state katalog, pustaka, form, impor, dan routing 404 | `desktop-home-empty.png`, `desktop-cases-empty.png`, `desktop-route-not-found.png` |
| ID kasus invalid dan UUID tidak ditemukan | `desktop-case-invalid-id.png`, `desktop-case-not-found.png` |
| Validasi impor tanpa file, metadata invalid, file >20 MiB, CSV rusak | `desktop-import-metadata-errors.png`, `desktop-import-invalid-csv.png` |
| Impor 500, replay, 3 duplikat, dan campuran valid/invalid | `desktop-import-completed.png`, `desktop-import-replay.png`, `desktop-import-duplicates.png`, `desktop-import-mixed.png` |
| Cascade kategori, validasi narasi, tanpa token, tanpa hasil relevan | `desktop-search-cascade.png`, `desktop-search-no-tokens.png`, `desktop-search-no-results.png` |
| Hasil pencarian: skor, empat alasan kategori, shared terms, konfigurasi server, warning | `desktop-search-results.png` |
| Referensi, retensi, detail, edit, hapus field opsional, referensi tersimpan | `desktop-retention-prefilled.png`, `desktop-case-detail-analyst.png`, `desktop-case-detail-edited.png`, `desktop-case-reference-picker.png` |
| Filter dan pagination 20 per halaman | `desktop-cases-analyst-filter.png` |
| CFPB read-only dan rute edit CFPB | `desktop-case-detail-cfpb.png`, `desktop-case-edit-cfpb-blocked.png` |
| Kegagalan API dan pemulihan (search, detail 500, save transport) | `desktop-search-recovered.png`; detail JSON `B-25`, `B-34`, `B-35` |
| Navigasi maju/mundur, refresh, double submit | `B-23`, `B-24`, `B-28` |
| Keyboard skip link dan fokus terlihat | `B-37`, `B-38` |
| Desktop 1440×1000 dan mobile 390×844 (home, cases, imports, results, detail, edit, new) | `mobile-*.png`; overflow 0 px pada semua halaman; tombol 50 px dan select 48 px |
| Axe penuh pada 7 keadaan halaman | 0 violation; 1 aturan `color-contrast` berstatus *incomplete* untuk elemen form (lihat OBS-01) |

Angka konsistensi utama dari sesi ini:

- Impor pertama: 500 kasus, 85 tuple kategori.
- CSV duplikat: `processed=3, imported=0, duplicates=3`; jumlah kasus tetap 500.
- CSV campuran: `processed=2, imported=1, rejected=1`; 500 → 501 kasus.
- Retensi dengan double submit sintetis: satu `POST /api/cases`, 501 → 502 kasus.
- Pustaka: 502 kasus total, 501 CFPB + 1 ANALYST, 20 per halaman; filter CFPB 501, ANALYST 1.
- Edit: `createdAt` dipertahankan, field opsional yang dikosongkan menjadi `null`, referensi dapat dihapus/ditambah, PUT berhasil.

### 4.5 Persistensi dan phrases melalui browser — 7/7 PASS

- Setelah backend direstart pada file SQLite yang sama: hitungan akhir 503 total / 501 CFPB / 2 ANALYST, detail analis, referensi, catatan, outcome, detail CFPB, dan pencarian tetap sama. Bukti: [`browser-restart.json`](results/2026-10-28-full-155053/browser-restart.json), `post-restart-*.png`.
- Backend direstart dengan `FINRESOLVE_SIMILARITY_MODE=phrases`: UI menampilkan `tfidf-phrases-v2`, bukan baseline. Bukti: [`browser-phrases.json`](results/2026-10-28-full-155053/browser-phrases.json), `phrases-search-results.png`.

## 5. Temuan

### FS-01 — Batas karakter frontend menghitung UTF-16, sedangkan kontrak menghitung code point; input astral dipotong diam-diam

**Prioritas:** P3 — Low  
**Klasifikasi:** ketidaksesuaian validasi frontend dengan kontrak; kehilangan input pada karakter astral  
**Status:** terbuka, reproducible  
**Prasyarat:** katalog kategori sudah terisi; Chromium; narasi berisi karakter astral (emoji) mendekati batas.

**Expected:** UI menerima hingga 50.000 **code point** seperti yang dihitung penghitung karakter (`textLength` = `Array.from(value).length`) dan seperti yang diterima API. Untuk 26.009 code point, seluruh teks harus dipertahankan.

**Actual:** Textarea memakai atribut `maxLength={50_000}` yang dihitung browser dalam unit UTF-16 (`FindPrecedentsPage.tsx`; pola sama di `CaseForm.tsx`). Memasukkan `"transfer " + 26.000 emoji` (26.009 code point, 52.009 unit UTF-16) berhenti di **25.004 code point**; penghitung berubah menjadi `25,004 / 50,000 characters`, dan sisa teks hilang tanpa peringatan. API menerima teks yang sama dengan **HTTP 200**, status `NO_RELEVANT_CASES`.

**Langkah reproduksi:**

1. Jalankan build produksi dengan database baru dan impor satu baris CFPB agar katalog terisi.
2. Buka `/`, fokus ke **Complaint narrative**.
3. Masukkan `transfer ` diikuti 26.000 emoji `😀` melalui keyboard/paste.
4. Amati nilai textarea dan penghitung karakter.
5. Kirim teks yang sama langsung ke `POST /api/search`.

**Bukti:**

- [`emoji-input.json`](results/2026-10-28-full-155053/emoji-input.json): `EMOJI-API` PASS (`status=200`, 26.009 code point diterima); `EMOJI-UI` FAIL (`textarea kept 25004 code points of 26009; counter=25,004 / 50,000 characters`).
- [`screenshots/emoji-narrative-limit.png`](results/2026-10-28-full-155053/screenshots/emoji-narrative-limit.png).
- Request/response ringkas:

```http
POST /api/search
Content-Type: application/json

{"product":"Checking or savings account","issue":"Managing an account",
 "narrative":"transfer 😀…(26.000 emoji)…","limit":5}

HTTP/1.1 200 OK
{"status":"NO_RELEVANT_CASES", "...": "..."}
```

**Dampak:** Analis yang menempelkan narasi panjang dengan emoji atau aksara astral kehilangan sebagian teks sebelum dikirim. Tidak ada perubahan data backend dan tidak ada kegagalan kontrak API; namun isi yang disimpan dapat berbeda dari yang terlihat/dimaksudkan. Pola `maxLength` yang sama juga dipakai pada `handlingNotes` (10.000), `evaluationNotes` (10.000), `company` (500), dan `companyResponse` (2.000); keempat field tersebut belum diuji per-field pada sesi ini.

**Saran perbaikan (pekerjaan terpisah, bukan dilakukan di sesi ini):**

- Ganti ketergantungan pada `maxLength` HTML dengan validasi code point (`textLength`) saat input dan submit, atau naikkan `maxLength` ke batas unit UTF-16 yang setara dan tetap validasi code point di `validateCaseForm`/`validateSearch`.
- Terapkan pada seluruh field teks yang batasnya dinyatakan dalam code point.
- Tambahkan tes browser yang memasukkan emoji mendekati batas dan membandingkan panjang code point dengan API.

## 6. Observasi non-bug

| ID | Observasi | Klasifikasi |
| --- | --- | --- |
| OBS-01 | Axe penuh melaporkan 0 violation, tetapi 1 aturan `color-contrast` berstatus *incomplete* pada `#search-product`, `#search-sub-product`, `#search-issue`, `#search-sub-issue`, `#search-narrative` (desktop results) dan `#search-product`, `#search-narrative`, `.L-MlMa_mark` (mobile). Incomplete berarti axe tidak dapat menentukan kontras otomatis, bukan pelanggaran. | Perlu tinjauan manual; suite color-contrast resmi lulus |
| OBS-02 | Startup backend mencetak `HV000271 ... @Valid on JsonNullable ... RetainCaseRequest#getReceivedDate()` dari Hibernate Validator. Tidak memengaruhi hasil. | Peringatan dependensi; bersihkan terpisah |
| OBS-03 | `warnings` dari server ditampilkan dalam bahasa Indonesia di UI Inggris. README menyatakan narasi/label/warning server ditampilkan tanpa terjemahan. | Sesuai kebijakan yang didokumentasikan |
| OBS-04 | Baris kosong di CSV diabaikan dan tidak menambah `processed`/`rejected`; replay byte+metadata mengembalikan batch asli. | Perilaku kontrak yang dipertahankan |
| OBS-05 | Tombol dan select mobile yang diukur memenuhi target 48 px (tombol 50 px, select 48 px); overflow horizontal 0 px pada seluruh halaman yang diuji. | PASS |
| OBS-06 | Error 500 yang dibentuk tanpa `fieldErrors`/`timestamp` dipetakan client menjadi `TRANSPORT_ERROR`, bukan `INTERNAL_ERROR`. Ini adalah perilaku client terhadap body yang tidak sesuai skema, bukan temuan aplikasi. | Ditemukan saat harness; lihat bagian 7 |
| OBS-07 | `Accept: application/xml` menghasilkan **406 tanpa body** sesuai ekspektasi, tetapi log server mencatat `ERROR ... HTTP 500 INTERNAL_ERROR`, `WARN ... Failure in @ExceptionHandler`, dan stack trace `HttpMediaTypeNotAcceptableException` (lihat `server-contract.log`). | Observasi P4 — kebisingan log; respons klien tetap 406 |

## 7. Reproduksi kegagalan dan pembeda bug aplikasi vs alat uji

Kegagalan awal tidak dihapus; dipisahkan ke [`harness-error/`](results/2026-10-28-full-155053/harness-error/) agar dapat direproduksi dan dibedakan dari bug aplikasi.

| Kejadian awal | Penyebab | Klasifikasi | Tindakan |
| --- | --- | --- | --- |
| Persistensi `verify`/`busy` exit 1; `caseCount=0` setelah restart | Skrip orkestrasi menghapus file SQLite pada start kedua | **Masalah alat uji** | Dibuat [`rerun-persistence.sh`](results/2026-10-28-full-155053/rerun-persistence.sh) yang tidak menghapus DB; hasil PASS 501 kasus |
| B-15 gagal `INVALID_CSV` | Subset CSV diambil dari pemotongan baris teks; narasi sampel mengandung newline dalam quoted field | **Masalah fixture uji** | Subset dibangun dari `provenance.complaintId` melalui API; PASS |
| B-17 gagal menemukan `Sub-product`/`Sub-issue` | Label aksesibel memuat `(optional)` sehingga `getByLabel(..., exact: true)` tidak cocok | **Masalah locator uji** | Memakai ID elemen; PASS |
| B-32 gagal mengharapkan 500 CFPB | Impor campuran menambah satu kasus CFPB, sehingga total CFPB 501 | **Masalah ekspektasi uji** | Ekspektasi dihitung dari API; PASS |
| B-25 retry tidak menghasilkan match | Narasi uji tidak memiliki preseden di atas ambang | **Masalah fixture uji** | Memakai narasi yang dikenal menghasilkan match; PASS |
| B-34 menampilkan `Cannot reach the server`, bukan `Something went wrong` | Body 500 buatan tidak memuat field wajib `fieldErrors`/`timestamp` | **Masalah harness** | Body disesuaikan skema `ApiError`; PASS |
| B-16 strict mode violation | Dua elemen tabel memuat teks `REQUIRED` | **Masalah locator uji** | `exact: true`; PASS |
| Server pencarian baseline/phrases masih hidup setelah probe selesai | Runner awal memakai satu variabel PID global untuk tiga server yang berjalan bersamaan, sehingga stop kedua/ketiga menjadi no-op | **Masalah orkestrasi** | Proses dihentikan manual; runner diberi `kill_tree` dan trap cleanup. Port bersih diverifikasi di [`post-session-cleanup.txt`](results/2026-10-28-full-155053/post-session-cleanup.txt) |

Tidak ada kegagalan aplikasi lain pada skenario yang dijalankan. Satu-satunya FAIL pada eksekusi final adalah FS-01. Pemeriksaan akhir menunjukkan tidak ada port uji 3000/8080/18210–18218 yang mendengarkan dan tidak ada proses aplikasi yang tertinggal.

## 8. Skenario yang belum diuji

Ada **15 kelompok** skenario yang belum dijalankan atau berada di luar cakupan sesi ini:

1. Autentikasi, otorisasi, peran, dan deployment publik (di luar cakupan).
2. Docker Compose mode development/demo dan kesehatan container.
3. Uji beban, endurance, throughput, dan SLA/latensi.
4. Penilaian manusia atas relevansi/precision@5; skor tetap kemiripan, bukan probabilitas.
5. Firefox/WebKit dan perangkat mobile fisik/soft keyboard; mobile hanya emulasi viewport Chromium.
6. Pembaca layar manual (NVDA/VoiceOver) dan audit WCAG manual; axe hanya otomatis.
7. UI pada kapasitas tepat 5.000 kasus, pagination ekstrem, dan performa render korpus besar.
8. Perilaku browser saat `DATABASE_BUSY` 503 atau restart server di tengah save/import; bagian backend sudah diuji.
9. Batas tepat 20 MiB dan multipart 21 MiB melalui proxy; yang diuji adalah 19 MiB sukses dan >20 MiB ditolak client, serta batas backend melalui probe.
10. Konkurensi multi-tab/multi-pengguna pada UI; konkurensi API sudah diuji.
11. Slow network, timeout panjang, offline berkepanjangan, dan pemulihan setelah server mati total.
12. Pencarian `EMPTY_KNOWLEDGE_BASE` melalui UI; UI sengaja memblokir submit saat katalog kosong, sedangkan API/DB kosong diuji melalui probe.
13. Drag-and-drop unggahan, mode gelap, tablet, dan orientasi landscape.
14. Impor format selain CSV dan validasi keamanan berkas (di luar kontrak).
15. Validasi per-field untuk batas `handlingNotes`, `evaluationNotes`, `company`, dan `companyResponse` setelah FS-01; pola kode sama tetapi belum dieksekusi satu per satu.

## 9. Bukti dan cara menjalankan ulang

Semua bukti berada di [`results/2026-10-28-full-155053/`](results/2026-10-28-full-155053/):

| Berkas | Isi |
| --- | --- |
| `environment.txt` | Commit, versi runtime, checksum, kondisi awal |
| `evidence-summary.json` | Ringkasan agregat yang dihitung dari seluruh JSON hasil |
| `verify.log` | Log lengkap `npm run verify` |
| `frontend-e2e.log`, `playwright-test-results/` | E2E resmi |
| `backend-*.json`, `backend-*.log` | 141 probe backend + log server per sesi |
| `backend-persistence-state.json`, `backend-persistence-restart.json`, `backend-persistence-busy.json` | Persistensi dan write lock |
| `browser-exploratory.json`, `browser-exploratory.log`, `screenshots/` | 42 skenario browser + screenshot |
| `browser-restart.json` | Verifikasi UI setelah restart |
| `browser-phrases.json`, `phrases-search-results.png` | Phrases mode melalui UI |
| `emoji-input.json`, `emoji-input.log`, `screenshots/emoji-narrative-limit.png` | Temuan FS-01 |
| `run-backend-probes.sh`, `run-browser-session.sh`, `rerun-persistence.sh`, `run-emoji-check.sh` | Orkestrasi yang dapat dijalankan ulang |
| `harness-error/` | Kegagalan alat uji/fixture yang dipertahankan, bukan bug aplikasi |
| `post-session-cleanup.txt` | Bukti tidak ada port/proses uji yang tertinggal |

Perintah reproduksi dari root repository:

```bash
npm run verify
npm run frontend:e2e

bash docs/testing/results/2026-10-28-full-155053/run-backend-probes.sh
bash docs/testing/results/2026-10-28-full-155053/run-browser-session.sh
bash docs/testing/results/2026-10-28-full-155053/run-emoji-check.sh
```

Probe browser dapat dijalankan ulang secara terpisah dengan build produksi dan JAR yang sama:

```bash
node docs/testing/probes/browser-exploratory.mjs \
  http://127.0.0.1:3000 http://127.0.0.1:8080 \
  /tmp/finresolve-shots /tmp/browser-exploratory.json
```

Semua skrip memakai database sementara di `/tmp`; tidak menulis ke `backend/data/` dan tidak mengubah kode aplikasi.

## 10. Kesimpulan

- Kontrak API, generated client, typecheck, unit/integration test, build, live contract, dan alur E2E resmi lulus pada commit `eefef2e`.
- Backend tetap konsisten pada impor, validasi, provenance, Unicode, konkurensi, kapasitas 5.000, persistensi restart, serta write lock.
- Frontend lulus seluruh alur utama, empty/error state, navigasi, keyboard, layout desktop/mobile, dan axe tanpa violation; satu aturan `color-contrast` tetap *incomplete* sehingga aksesibilitas tidak dinyatakan sepenuhnya terverifikasi (lihat OBS-01).
- Satu temuan **P3** terbuka: batas karakter berbasis UTF-16 memotong input astral lebih awal daripada batas 50.000 code point yang dinyatakan UI/API. Temuan ini tidak menghambat demo normal, tetapi perlu diperbaiki agar konsisten dengan kontrak dan tidak kehilangan input.
- Sesi dihentikan setelah seluruh kelompok pengujian dijalankan; tidak ada BLOCKED. Hasil ini bukan penilaian relevansi manusia atau kesiapan deployment publik.
