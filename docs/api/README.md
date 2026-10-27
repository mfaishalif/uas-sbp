# Kontrak API FinResolve

Status: **kontrak `0.1.0` disetujui pengguna pada 26 Oktober 2026; backend, client TypeScript, dan live contract test sudah diimplementasikan dan diverifikasi**. Kontrak ini melengkapi [desain backend](../superpowers/specs/2026-10-26-finresolve-backend-design.md). [openapi.json](openapi.json) adalah acuan nama field, tipe, batas input, enum, endpoint, dan bentuk respons. Dokumen ini menjelaskan aturan bisnis dan penggunaan dari frontend. Perubahan kontrak harus memperbarui kedua dokumen secara bersamaan.

Format menggunakan [OpenAPI 3.1](https://spec.openapis.org/oas/v3.1.0.html). File dapat dibuka di alat yang mendukung versi tersebut untuk melihat skema atau menyiapkan client dan mock. Semua contoh di OpenAPI bersifat sintetis, termasuk ID CFPB dan skor; kategori aktual harus diambil dari API. Server nyata dan generated client tersedia; UI Next.js belum.

**Kebutuhan generation:** backend dan frontend wajib menggunakan kode HTTP yang dihasilkan dari kontrak ini. [Rancangan generation](generation.md) menetapkan output Java/TypeScript, batas kode manual, dan pemeriksaan sinkronisasi di CI. Alat yang disetujui dalam desain adalah OpenAPI Generator untuk kedua sisi. Pembuatan paket client termasuk tahap backend; pembangunan UI Next.js tetap tahap berikutnya.

## Aturan bersama

- Base URL lokal: `http://localhost:8080/api`; simpan dalam konfigurasi frontend, misalnya `NEXT_PUBLIC_API_BASE_URL`. Jangan memasukkan `/api` dua kali.
- JSON menggunakan `camelCase`, UTF-8, `Content-Type: application/json`, dan `Accept: application/json`. Tidak ada envelope `data`: baca objek respons langsung.
- Tidak ada login, token, cookie sesi, atau kebutuhan `credentials: include` pada demo lokal.
- ID internal adalah UUID string. `complaintId` CFPB juga string, tetapi berupa digit; keduanya tidak dapat dipertukarkan.
- Tanggal kalender menggunakan `YYYY-MM-DD`; timestamp menggunakan UTC RFC 3339, misalnya `2026-10-26T10:00:00Z`.
- Semua properti yang didefinisikan pada objek respons selalu hadir. Data yang tidak tersedia dikirim sebagai `null`; koleksi kosong sebagai `[]`.
- Field opsional request boleh dihilangkan. Field string nullable boleh `null`; string blank dinormalisasi menjadi null. String wajib ditrim dan tidak boleh kosong. `limit` dan `referenceCaseIds` tidak menerima null.
- Properti JSON request di luar skema ditolak dengan `VALIDATION_ERROR` / `UNKNOWN_FIELD`. Pengecualian: part multipart `metadata` mengikuti binding Spring; properti asing pada part tersebut menghasilkan `400 MALFORMED_REQUEST` dengan `fieldErrors=[]`. Frontend tidak boleh mengirim `id`, `source`, timestamp, atau provenance saat retain/update.
- Nama dan enum stabil dalam versi kontrak ini. Frontend menggunakan `code`/enum untuk logika, bukan teks `message` yang dapat disempurnakan. Pesan default bahasa Indonesia; narasi dan label kategori mengikuti sumber Inggris.
- CORS mengizinkan `http://localhost:3000`, metode `GET`, `POST`, `PUT`, `OPTIONS`, header `Content-Type`/`Accept`, dan mengekspos `Location`. Preflight origin yang diizinkan mendapat 204 tanpa body. Origin tambahan diatur server. Request dari origin yang tidak diizinkan dapat terlihat sebagai kegagalan jaringan di browser, bukan error JSON aplikasi.

## Endpoint

| Metode dan path | Input | Sukses |
| --- | --- | --- |
| `GET /health` | Tidak ada | 200 `Health`; 503 bila layanan/database tidak tersedia |
| `GET /categories` | Tidak ada | 200 `Categories`, termasuk `items: []` sebelum impor |
| `GET /cases` | Query `page`, `size`, `source?` | 200 `CasePage` |
| `GET /cases/{id}` | UUID internal | 200 `CaseDetail` |
| `POST /search` | `SearchRequest` JSON | 200 `SearchResponse` untuk ketiga status pencarian |
| `POST /cases` | `RetainCaseRequest` JSON | 201 `CaseDetail` + header `Location` |
| `PUT /cases/{id}` | UUID dan `RetainCaseRequest` lengkap | 200 `CaseDetail` |
| `POST /imports/cfpb` | Multipart file CSV dan metadata JSON | 201 batch baru, 200 replay batch lama |

Detail skema dan contoh request/response untuk operasi JSON tersedia pada `paths` dan `components.schemas` di [OpenAPI](openapi.json). Bentuk multipart dan contoh penggunaannya tersedia di bawah. Tidak ada endpoint hapus, PATCH, autentikasi, polling job impor, atau penyimpanan otomatis query pada tahap ini.

## Kategori dan form pengaduan

`GET /categories` menghasilkan `{ "items": [...], "scope": "IMPORTED_CFPB_SAMPLE" }`. Setiap item berisi `product`, `subProduct`, `issue`, dan `subIssue`, dengan dua field subkategori nullable. Ini tuple unik dari sampel CFPB, bukan katalog global.

Bangun opsi produk dari semua tuple. Opsi subproduk dan masalah difilter oleh produk; opsi submasalah difilter oleh produk dan masalah. Mengganti produk perlu membersihkan pilihan turunannya yang tidak lagi valid; mengganti masalah perlu membersihkan submasalah yang tidak valid. Subproduk/submasalah selalu boleh dikosongkan. Kombinasi keduanya tidak harus pernah muncul bersama pada satu tuple, selama masing-masing hubungan induknya valid.

Server menormalisasi trim, spasi kategori berulang, dan kapitalisasi untuk pencocokan. Label respons menggunakan label kanonis sumber. Produk dan masalah wajib; tiap label maksimum 500 karakter. Narasi wajib 1–50.000 karakter setelah trim dan harus mengandung token bermakna. Input hanya stopword, angka, atau placeholder redaksi ditolak. Company opsional maksimum 500 karakter; companyResponse opsional maksimum 2.000 karakter.

Untuk korpus kosong, server memvalidasi bentuk input dan token narasi terlebih dahulu, lalu mengembalikan `EMPTY_KNOWLEDGE_BASE` tanpa memeriksa keanggotaan kategori. Retensi sebelum ada katalog ditolak dengan 409 `EMPTY_CATEGORY_CATALOG`.

## Pencarian dan cara membaca skor

`POST /search` menerima produk, masalah, narasi, dua subkategori opsional, dan `limit` opsional (default 5, rentang 1–20). Pencarian tidak membutuhkan respons perusahaan dan tidak menyimpan pengaduan baru.

| `status` | Perilaku frontend |
| --- | --- |
| `MATCHES` | Tampilkan `results`; hasil berjumlah 1 hingga `limit` |
| `NO_RELEVANT_CASES` | Tampilkan keterbatasan pencarian dan serahkan penilaian ke analis; bukan kegagalan HTTP |
| `EMPTY_KNOWLEDGE_BASE` | Tampilkan instruksi impor sampel terlebih dahulu |

`returnedCount` sama dengan panjang `results`. `corpusSize` adalah jumlah kasus pada snapshot yang digunakan untuk menghitung skor, termasuk kasus ANALYST. Pencarian mengembalikan top-N tanpa pagination. Respons membawa konfigurasi server yang benar-benar dipakai; frontend tidak menghitung ulang atau mengasumsikan bobot default selalu sama.

Server mendukung dua mode teks. `baseline` memakai tokenisasi saat ini dan mengembalikan `algorithmVersion=tfidf-cosine-v1`. `phrases` menerapkan kamus frasa domain berversi sebelum penghapusan stopword dan mengembalikan `algorithmVersion=tfidf-phrases-v2`; bentuk request/response dan status HTTP tidak berubah. Kamus awal memetakan `ATM`, `cash machine`, `automated teller machine` ke `atm`; lima varian penagihan ganda ke `duplicatecharge`; dan delapan varian saldo ditahan ke `heldfunds`. Pencocokan tidak membedakan kapital, memakai frasa terpanjang lebih dahulu, menghormati batas kata, tidak mengganti substring, tidak memproses ulang hasil penggantian, dan tidak mencocokkan lintas tanda akhir kalimat. Narasi asli tidak diubah; normalisasi hanya memengaruhi tokenisasi. Mode tidak dikenal menggagalkan startup. Default saat ini tetap `baseline` karena kandidat frasa gagal gerbang query negatif pada studi v2; lihat [hasil evaluasi](../evaluation/v2/README.md).

Setiap hasil memiliki `case` (detail lengkap), `score`, `categoryScore`, `textScore`, `categoryExplanation`, dan `sharedTerms`. Semua skor berada pada 0–1. Bila ditampilkan sebagai persen, beri label **skor kemiripan**, bukan peluang keberhasilan. Pertahankan peringatan dalam `warnings` dan bedakan sumber CFPB dari ANALYST.

Penjelasan kategori selalu memiliki empat elemen berurutan: product, subProduct, issue, subIssue. `MATCH` berarti cocok, `MISMATCH` berbeda, `MISSING_IN_CASE` berarti nilai query ada tetapi nilai kandidat kosong, dan `NOT_USED` berarti field query kosong. `configuredWeight` adalah bobot awal; `effectiveWeight` adalah bobot sesudah normalisasi field aktif. Jumlah `contribution` sama dengan `categoryScore` dalam toleransi floating point.

`sharedTerms` memuat maksimal 10 token dengan kontribusi cosine terbesar, urut kontribusi turun lalu token naik. `term` adalah token kanonis yang benar-benar dipakai dalam skor: bentuk asli pada mode `baseline`, atau hasil kamus (`atm`, `duplicatecharge`, `heldfunds`) pada mode `phrases`. Jumlah kontribusinya dapat kurang dari `textScore` bila token bersama lebih dari 10. Skor gabungan = categoryWeight × categoryScore + textWeight × textScore. Respons hanya memuat hasil dengan score >= threshold dan textScore > 0. `algorithmVersion` selalu mencerminkan pipeline aktual. Nilai default serta rumus lengkap ada dalam desain; hasil studi relevansi v2 tidak mengubah default karena gerbang pengujian gagal.

## Daftar, detail, dan retensi kasus

Pagination daftar dimulai pada `page=0`, `size=20`, maksimum size 100. `source` opsional, hanya `CFPB` atau `ANALYST`; hilangkan parameter untuk menampilkan semua. Respons berisi `items`, `page`, `size`, `totalElements`, `totalPages`, dan `hasNext`. Urutan createdAt turun lalu ID naik. Halaman di luar jangkauan menghasilkan 200 dan items kosong. Daftar tidak memuat narasi atau catatan lengkap; gunakan detail kasus.

`CaseDetail` dibedakan oleh `source`:

- `CFPB`: `provenance` berisi complaintId, batch, URL, nama CSV, periode, dan checksum; `analystNotes=null`, `referenceCaseIds=[]`.
- `ANALYST`: `provenance=null`; `analystNotes` berisi catatan dan penilaian analis; `referenceCaseIds` berisi preseden yang dipilih, jika ada. CompanyResponse, jika diisi, adalah laporan analis dan tidak boleh ditampilkan seolah respons yang diverifikasi CFPB.

Retain membutuhkan `handlingNotes` nonblank maksimum 10.000 karakter dan `outcomeAssessment`: `UNKNOWN`, `RESOLVED`, `PARTIALLY_RESOLVED`, atau `UNRESOLVED`. `evaluationNotes` opsional maksimum 10.000 karakter. Status hasil adalah penilaian analis, bukan hasil verifikasi sistem. `referenceCaseIds` default [], maksimum 20 UUID berbeda, semuanya harus ada dan tidak merujuk kasus itu sendiri. Tanggal diterima opsional; tanggal yang diberikan harus tanggal kalender valid.

`POST /cases` membuat kasus ANALYST baru. Ini tidak idempoten: nonaktifkan tombol selama request berlangsung dan jangan melakukan retry otomatis ketika hasil request tidak diketahui. `PUT` memakai bentuk input sama tetapi mengganti keseluruhan kasus ANALYST; field opsional yang dihilangkan menjadi null/[], termasuk referensi. Muat detail dan isi form lengkap sebelum menyimpan. PUT mempertahankan createdAt dan memperbarui updatedAt, tidak membuat ID yang belum ada, serta tidak memakai version lock pada demo satu analis. Penggantian terakhir yang tersimpan berlaku.

## Impor CSV

Part `file` berisi CSV UTF-8, maksimum 20 MiB (20.971.520 byte), maksimum 5.000 record data. Total request multipart maksimum 21 MiB. Header wajib: `Complaint ID`, `Product`, `Issue`, `Consumer complaint narrative`. Parser mendukung BOM, quoted comma, dan newline dalam field. Metadata wajib dikirim sebagai part JSON dengan `Content-Type: application/json`:

```json
{
  "archiveUrl": "https://files.consumerfinance.gov/f/documents/CCDB_Export_8_November_2024_through_December_2024.zip",
  "sourceFileName": "finresolve-sample.csv",
  "periodStart": "2024-11-01",
  "periodEnd": "2024-12-31"
}
```

URL harus HTTPS dan hanya dicatat. Nama sumber berupa nama CSV tanpa path lokal. PeriodEnd tidak boleh lebih awal dari periodStart. ID CFPB harus digit bilangan bulat positif; subkategori kosong dibolehkan. Untuk kolom CSV `Date received`, importer menerima tanggal ISO `YYYY-MM-DD` atau format arsip `MM/DD/YYYY`; hasil API selalu ISO. Jika tanggal tersedia, harus berada dalam periode metadata. Narasi mentah dipertahankan, kategori dinormalisasi; field opsional blank menjadi null. Panjang field mengikuti batas API. Record baru di luar katalog lama dibolehkan dan memperluas katalog.

Impor sinkron. CSV rusak, header wajib hilang, file hanya header, atau batas file/record terlampaui membatalkan semua penulisan. Untuk file yang strukturnya valid, record invalid ditolak satu per satu; ID valid yang sudah ada dilewati. Klasifikasi record berurutan: validasi dahulu, baru pemeriksaan duplikat. Bila sebuah ID muncul beberapa kali, record valid pertama yang diterima dipertahankan. Tidak ada overwrite kasus lama.

Laporan selalu memiliki `processed = imported + duplicates + rejected`, maksimal 20 contoh error (satu error pertama per record), dan `errorsTruncated = rejected > 20`. `recordNumber` berbasis 1 setelah header, bukan nomor baris fisik. Frontend perlu menampilkan jumlah rejected meski status HTTP sukses. File dengan semua record invalid tetap menghasilkan laporan batch baru 201; tidak menambah kasus.

Replay CSV dengan byte/checksum dan seluruh metadata yang sama setelah trim mengembalikan 200, `replayed=true`, ID batch, waktu, serta hitungan asli. Angka tersebut bukan jumlah tambahan pada replay. CSV dengan metadata berbeda dianggap batch baru; ID lama tetap dihitung duplikat. Batas total basis kasus 5.000: jika kasus baru yang valid akan melampauinya, seluruh transaksi dibatalkan dengan 409.

```js
// file: File dari input; metadata: objek seperti contoh di atas.
const form = new FormData();
form.append("file", file);
form.append("metadata", new Blob([JSON.stringify(metadata)], {
  type: "application/json",
}));
const response = await fetch(`${apiBase}/imports/cfpb`, {
  method: "POST",
  headers: { Accept: "application/json" },
  body: form,
});
// Jangan menetapkan Content-Type multipart sendiri; browser menambahkan boundary.
const reportOrError = await response.json();
if (!response.ok) throw reportOrError;
```

## Error dan contoh integrasi JSON

Error aplikasi memiliki bentuk tetap:

```json
{
  "code": "VALIDATION_ERROR",
  "message": "Input tidak valid.",
  "fieldErrors": [
    { "field": "narrative", "code": "REQUIRED", "message": "Narasi wajib diisi." }
  ],
  "timestamp": "2026-10-26T10:00:00Z"
}
```

| HTTP | `code` | Tindakan frontend |
| --- | --- | --- |
| 400 | `VALIDATION_ERROR`, `MALFORMED_REQUEST` | Tampilkan error field atau body; perbaiki input |
| 400 | `INVALID_CATEGORY`, `INVALID_REFERENCE` | Muat ulang pilihan/detail referensi dan perbaiki input |
| 400 | `INVALID_CSV`, `IMPORT_LIMIT_EXCEEDED` | Perbaiki format atau kurangi jumlah record |
| 404 | `CASE_NOT_FOUND`, `ROUTE_NOT_FOUND` | Kasus/route tidak tersedia; jangan retry otomatis |
| 409 | `IMMUTABLE_CFPB_CASE` | Batalkan pengeditan kasus arsip |
| 409 | `EMPTY_CATEGORY_CATALOG` | Impor sampel dahulu |
| 409 | `KNOWLEDGE_BASE_LIMIT_EXCEEDED` | Tampilkan batas kapasitas demo |
| 413 | `PAYLOAD_TOO_LARGE` | Kurangi ukuran file/request |
| 415 | `UNSUPPORTED_MEDIA_TYPE` | Perbaiki Content-Type JSON atau part metadata |
| 405 | `METHOD_NOT_ALLOWED` | Perbaiki metode HTTP |
| 503 | `DATABASE_BUSY`, `SERVICE_UNAVAILABLE` | Beri pilihan mencoba lagi; jangan otomatis mengulang retain |
| 500 | `INTERNAL_ERROR` | Tampilkan pesan umum dan pertahankan isi form |

`fieldErrors` selalu array, boleh kosong untuk error global. Nama field memakai camelCase/dotted path untuk request JSON, misalnya `metadata.periodEnd` atau `referenceCaseIds[0]`. Laporan error per-record impor memakai nama header CSV. Jangan menampilkan SQL, path lokal, atau stacktrace. Kegagalan jaringan/CORS atau proxy yang tidak mengembalikan JSON harus ditangani sebagai kegagalan transport terpisah.

Contoh `fetch` di atas hanya menjelaskan protokol HTTP. Frontend wajib memakai client hasil generation melalui adapter `createClient` di package `@finresolve/api-client`; jangan membuat ulang DTO atau panggilan endpoint secara manual. Adapter menangani base URL `/api`, serialisasi, dan pemetaan error.

```bash
npm run client:build
```

```ts
import { ApiClientError, createClient } from '@finresolve/api-client';

const client = createClient('http://localhost:8080');
try {
  const result = await client.search.searchCases({
    searchRequest: {
      product: 'Checking or savings account',
      issue: 'Managing an account',
      narrative: 'My transfer is missing from my checking account.',
      limit: 5,
    },
  });
  // result.status: MATCHES | NO_RELEVANT_CASES | EMPTY_KNOWLEDGE_BASE.
} catch (error) {
  if (error instanceof ApiClientError) {
    // error.status, error.code, error.fieldErrors.
    // status 0 + TRANSPORT_ERROR berarti tidak ada respons JSON yang dapat dibaca.
  }
}
```

`createClient` mengembalikan instance `SystemApi`, `CategoriesApi`, `CasesApi`, `SearchApi`, dan `ImportsApi` yang berbagi satu `Configuration`. Mutasi tidak di-retry otomatis. Package memakai ESM dengan import relatif berakhiran `.js`; build menghasilkan `packages/api-client/dist/` dan tidak di-commit.

Dua detail tipe generated perlu diketahui: properti kontrak `SearchMatch.case` diekspos sebagai `_case` (serialisasi tetap memakai `case`), dan `AnalystCase.referenceCaseIds` bertipe `Set<string>` meskipun JSON-nya array. Ini adalah perilaku generator 7.26.0 yang dipertahankan; jangan mengubah generated secara manual.

Verifikasi kontrak dan client terhadap server nyata:

```bash
npm run api:check
npm run client:typecheck
npm run test:contract
npm run verify
```

`api:check` membandingkan pohon TypeScript hasil generation dengan yang di-commit. `test:contract` menjalankan packaged jar dengan database sementara, memvalidasi setiap respons terhadap skema operasi/status di `docs/api/openapi.json`, lalu menguji adapter client. `verify` menjalankan seluruh rangkaian termasuk 267 tes backend. Pengujian ini tidak mengunduh arsip CFPB.
