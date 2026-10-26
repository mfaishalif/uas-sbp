# FinResolve

**Aplikasi web full stack berbasis pengetahuan untuk membantu penanganan pengaduan konsumen jasa keuangan dengan pendekatan Case-Based Reasoning (CBR).**

## Tentang proyek

FinResolve adalah proyek mata kuliah **Sistem Berbasis Pengetahuan (SBP)** yang mengeksplorasi penggunaan pengalaman kasus historis untuk membantu analis menangani pengaduan baru di sektor perbankan dan jasa keuangan.

Ketika ada pengaduan baru, sistem mencari pengaduan terdahulu yang relevan, memperlihatkan alasan kemiripannya, dan menyajikan informasi tentang penanganan kasus tersebut sebagai **bahan pertimbangan**. Sistem mendukung keputusan manusia, bukan mengambil keputusan final atau menjamin suatu solusi akan berhasil.

Dokumen desain awal proyek ini berjudul *Sistem CBR Keluhan Konsumen*.

Proyek saat ini memiliki **backend Java yang dapat dijalankan, kontrak OpenAPI 3.1, dan client TypeScript hasil generation**. Frontend Next.js, autentikasi, dan deployment publik belum diimplementasikan. README ini mencatat arah produk, keputusan implementasi yang sudah diverifikasi, dan batas demo lokal.

## Masalah dan tujuan

Pengaduan keuangan kerap memiliki pola berulang. Namun, pengalaman penanganan sebelumnya tidak selalu mudah ditemukan atau dibandingkan. FinResolve bertujuan membuat pengetahuan tersebut lebih mudah ditelusuri, dijelaskan, dan dimanfaatkan secara hati-hati.

Pengguna utama yang dibayangkan adalah **analis atau petugas penanganan pengaduan** di organisasi jasa keuangan.

## Arah implementasi

| Aspek | Pilihan |
| --- | --- |
| Bentuk aplikasi | Web full stack; backend sudah ada, frontend belum |
| Frontend | Next.js (belum diimplementasikan pada repository ini) |
| Bahasa backend | Java 21 |
| Framework backend | Spring Boot 4.1.2, Maven Wrapper 3.9.11 |
| Database | SQLite dengan migrasi berversi, WAL, dan satu koneksi tulis |
| Kontrak API | OpenAPI 3.1; batas Java dan client TypeScript dihasilkan dari kontrak yang sama |
| Pencarian kasus | Gabungan kemiripan kategori dan TF-IDF cosine (`tfidf-cosine-v1`) |
| Bahasa input pengaduan | Mengikuti bahasa narasi dataset sumber (Inggris) |
| Sumber kasus historis | Arsip resmi narasi CFPB, sampel tetap 500 kasus |
| Retensi kasus baru | Penyimpanan manual oleh analis, tanpa peran verifikator atau alur persetujuan khusus |

Frontend akan menyediakan antarmuka kerja analis. Backend menangani proses CBR dan pengelolaan kasus; SQLite menyimpan basis kasus serta catatan analis. Frontend belum dibuat, sehingga repository ini bukan aplikasi web yang siap dipakai pengguna akhir.

## Lingkungan pengembangan

Konfigurasi [Dev Container](.devcontainer/devcontainer.json) menyediakan lingkungan Linux untuk pengembangan frontend Next.js dan backend Java:

| Komponen | Konfigurasi |
| --- | --- |
| Sistem dasar | Debian Bookworm, pengguna `vscode` non-root |
| Frontend | Node.js 24 LTS dan npm |
| Backend | JDK 21, Maven 3.9, dan Gradle 9 |
| Asisten terminal | Codex CLI (`codex`) dan OpenCode CLI (`opencode`), terpasang global melalui npm |
| GitHub CLI | `gh`, dipasang melalui devcontainer feature; login dengan scope `repo` dan `workflow` |
| Port yang diteruskan | `3000` untuk Next.js dan `8080` untuk backend Java |
| Ekstensi VS Code | ESLint, Prettier, dan Extension Pack for Java |

Toolchain disiapkan melalui [Dockerfile devcontainer](.devcontainer/Dockerfile): image Java Dev Containers sudah berisi JDK, sedangkan Node.js, npm, Maven, dan Gradle disalin dari image resmi masing-masing. Dockerfile memasang Codex CLI dan OpenCode CLI secara global melalui npm. Build tidak menjalankan instalasi melalui SDKMAN. Versi Node.js, JDK, Maven, dan Gradle mengikuti tag pada Dockerfile; versi patch mengikuti image yang tersedia saat build.

Cara menggunakan:

1. Instal Docker Desktop dan VS Code beserta ekstensi **Dev Containers** (`ms-vscode-remote.remote-containers`). Di Windows, pastikan Docker Desktop memakai Linux containers dan backend WSL 2 aktif.
2. Jalankan Docker Desktop, lalu buka folder proyek ini di VS Code.
3. Buka Command Palette (`Ctrl+Shift+P`) dan pilih **Dev Containers: Reopen in Container**. Pembuatan pertama membutuhkan koneksi internet untuk mengunduh image, runtime, dan build tools.
4. Setelah container siap, buka terminal di VS Code. Jalankan `codex` untuk memakai Codex CLI atau `opencode` untuk memakai OpenCode CLI dari folder proyek. Hubungkan provider AI lewat perintah `/connect` di OpenCode. Versi kedua CLI beserta toolchain diperiksa otomatis saat container pertama kali dibuat.

Devcontainer menyiapkan toolchain pengembangan. Backend sudah dapat dijalankan dari repository ini; frontend Next.js belum tersedia. Setelah frontend ditambahkan, jalankan server Next.js dengan bind ke `0.0.0.0` (misalnya `npm run dev -- --hostname 0.0.0.0` dari folder frontend) agar dapat diakses melalui port yang diteruskan. Backend memakai port `8080`. Build Java memakai Maven Wrapper yang sudah di-commit agar versi build tool konsisten.

SQLite adalah database demo. File default `backend/data/finresolve.db` dibuat otomatis saat backend dijalankan dari folder `backend/`; lokasi absolut dicatat pada log startup dan dapat diubah melalui `FINRESOLVE_DB_PATH`. Jika konfigurasi devcontainer diubah, pilih **Dev Containers: Rebuild Container** melalui Command Palette.

Codex dan OpenCode berjalan di terminal container dan membaca proyek dari lingkungan pengembangan tersebut. Dokumentasi resmi: [Codex CLI](https://developers.openai.com/codex/cli) dan [OpenCode CLI](https://dev.opencode.ai/v2/docs).

### Autentikasi GitHub dengan GitHub CLI

Devcontainer memasang [GitHub CLI](https://cli.github.com/) (`gh`) melalui devcontainer feature. Setiap anggota tim login dengan akunnya sendiri; jangan berbagi token.

```bash
gh auth login --hostname github.com --git-protocol https --web -s repo,workflow
gh auth setup-git
gh auth status
```

Bentuk singkat yang setara:

```bash
gh auth login -s repo,workflow
```

Scope `workflow` diperlukan agar push yang membuat atau mengubah `.github/workflows/` tidak ditolak GitHub. Untuk pekerjaan aplikasi biasa scope tersebut tidak dibutuhkan, tetapi menyertakannya sejak awal menghindari kegagalan saat mengubah CI. Jika sudah login tanpa scope itu:

```bash
gh auth refresh -h github.com -s repo,workflow
```

`gh auth setup-git` membuat Git memakai kredensial `gh` untuk `github.com`. Verifikasi dengan `gh auth status`; jangan menulis token ke repository, shell history, atau dokumen. Kredensial tersimpan per pengguna di `~/.config/gh` dan dapat perlu diulang setelah container dibuat ulang.

Jika log menampilkan `No space left on device` saat memasang VS Code Server di `/root/.vscode-remote-containers` pada distro `docker-desktop`, periksa ruang di WSL dan disk Docker Desktop. Jika Docker Desktop tersangkut pada status `starting` dan perintah Docker tidak merespons, restart Docker Desktop sebelum mencoba membuka container kembali.

## Sumber data dan batasannya

Sumber data awal adalah **[CFPB Consumer Complaint Database Narratives Archive](https://www.consumerfinance.gov/foia-requests/foia-electronic-reading-room/cfpb-consumer-complaint-database-narratives-archive/)**, yaitu arsip resmi pengaduan yang sebelumnya dipublikasikan dalam Consumer Complaint Database, dengan cakupan pengaduan hingga 14 Agustus 2026.

Pemilihan arsip diperlukan karena narasi pengaduan telah dihapus dari database aktif menurut [catatan rilis CFPB September 2026](https://cfpb.github.io/api/ccdb/release-notes.html). Pencarian gabungan membutuhkan data kategori sekaligus teks narasi.

Pemeriksaan awal terhadap sebagian arsip November–Desember 2024 menemukan data produk, subproduk, masalah, submasalah, narasi pengaduan, perusahaan, kategori respons perusahaan, dan ID pengaduan. Contoh narasi yang diperiksa berbahasa Inggris dan mencakup pengaduan rekening serta transfer uang. Pemeriksaan ini belum merupakan evaluasi kualitas seluruh arsip.

Basis kasus awal memakai **sampel tetap yang memiliki narasi**. Sampel yang di-commit berisi 500 kasus dari arsip CFPB periode `2024-11-01` sampai `2024-12-31`, produk **Checking or savings account** dan **Money transfer, virtual currency, or money service**. Sumber arsip, periode, ID pengaduan, dan checksum dicatat agar asal kasus dapat ditelusuri. Bahasa input mengikuti narasi sumber; bahasa antarmuka belum ditetapkan.

### Menyiapkan sampel demo

Sampel tetap yang dipakai demo sudah tersedia di repository: `data/cfpb/sample.csv` (500 baris) dan `data/cfpb/manifest.json`. Untuk demo offline, gunakan kedua file tersebut; tidak perlu mengunduh arsip lagi.

Regenerasi hanya dilakukan melalui perintah eksplisit berikut, membutuhkan internet, dan tidak dijalankan oleh pengujian otomatis. Jalankan dari folder proyek:

```bash
cd backend
./mvnw -Pprepare-sample compile exec:java -Dexec.args="--output-dir ../data/cfpb"
```

Perintah ini menulis `data/cfpb/sample.csv` dan `data/cfpb/manifest.json` secara atomik. Manifest mencatat URL sumber, nama arsip dan entri CSV, periode `2024-11-01` sampai `2024-12-31`, filter yang dipakai, batas 500 kasus, waktu pengambilan, checksum SHA-256 arsip dan sampel, serta ID kasus terpilih. Arsip ZIP tidak disimpan atau di-commit. Narasi dipertahankan apa adanya; tidak ada klaim anonimisasi tambahan.

Checksum sampel yang di-commit adalah `682bb04cf77a7df10104c822ab24af4bd42e1a088cd2678c7c72921b7983a644`; checksum arsip sumber adalah `24b3326a954c0edeff33318b157d12eef5a59547f6f274f9236f1ceaba988a74`. `manifest.json` adalah acuan bila nilai berubah setelah regenerasi.

## Menjalankan backend

Kebutuhan: **Java 21** dan **Node.js 24** (dengan npm). Maven tidak perlu dipasang global karena repository memakai Maven Wrapper 3.9.11. Unduhan dependensi pertama membutuhkan internet; demo setelahnya dapat berjalan offline dengan sampel yang sudah di-commit.

```bash
npm ci
npm run verify                 # kontrak, generation, backend, client, dan live contract
cd backend && ./mvnw test      # hanya tes backend
```

`npm run verify` menjalankan validasi kontrak, generation check, 209 tes backend, pembuatan packaged jar, serta contract/client test terhadap server nyata. Perintah ini tidak mengunduh arsip CFPB.

Menjalankan server dengan database default:

```bash
cd backend
./mvnw -DskipTests package
java -jar target/finresolve-backend-0.1.0.jar
```

Server mendengarkan `http://127.0.0.1:8080` dan membuat `backend/data/finresolve.db`. Log startup mencatat lokasi absolut database. Untuk database sementara:

```bash
FINRESOLVE_DB_PATH=/tmp/finresolve-demo.db \
  java -jar backend/target/finresolve-backend-0.1.0.jar
```

Bind default adalah loopback. Di devcontainer, gunakan `SERVER_ADDRESS=0.0.0.0` hanya bila port perlu diteruskan ke host; jangan memakai bind publik untuk demo tanpa autentikasi.

### Impor sampel, pencarian, dan retensi

```bash
curl -sS -X POST http://127.0.0.1:8080/api/imports/cfpb \
  -F 'file=@data/cfpb/sample.csv;type=text/csv' \
  -F 'metadata={"archiveUrl":"https://files.consumerfinance.gov/f/documents/CCDB_Export_8_November_2024_through_December_2024.zip","sourceFileName":"finresolve-sample.csv","periodStart":"2024-11-01","periodEnd":"2024-12-31"};type=application/json'
```

Impor pertama menghasilkan **201** dengan `processed=500, imported=500, duplicates=0, rejected=0`. Mengulang byte CSV dan metadata yang sama menghasilkan **200** dengan `replayed=true` dan hitungan asli, bukan tambahan.

```bash
curl -sS -X POST http://127.0.0.1:8080/api/search \
  -H 'Content-Type: application/json' \
  -d '{"product":"Checking or savings account","subProduct":"Checking account","issue":"Managing an account","subIssue":"Deposits and withdrawals","narrative":"Money left my bank balance twice even though I only authorized one payment.","limit":5}'
```

Respons sukses berstatus 200 dengan `status` `MATCHES`, `NO_RELEVANT_CASES`, atau `EMPTY_KNOWLEDGE_BASE`. `results=[]` pada dua status terakhir bukan kegagalan HTTP. Skor adalah kemiripan, bukan probabilitas keberhasilan.

Retensi membutuhkan katalog yang sudah terisi. Ambil satu UUID CFPB dari `GET /api/cases?source=CFPB`, lalu:

```bash
curl -sS -i -X POST http://127.0.0.1:8080/api/cases \
  -H 'Content-Type: application/json' \
  -d '{"product":"Checking or savings account","subProduct":"Checking account","issue":"Managing an account","subIssue":null,"narrative":"My transfer is missing from my checking account.","handlingNotes":"Reviewed transfer evidence and requested a trace.","outcomeAssessment":"UNKNOWN","referenceCaseIds":["<UUID-CFPB>"]}'
```

Sukses adalah **201** dengan header `Location: /api/cases/{id}`. `POST` tidak idempoten: klik ganda atau retry otomatis dapat membuat dua kasus. `PUT /api/cases/{id}` mengganti lengkap kasus ANALYST; field opsional yang dihilangkan menjadi `null`/`[]`, `createdAt` dipertahankan, `updatedAt` diperbarui, dan penulisan terakhir menang. Kasus CFPB tidak dapat diubah (**409** `IMMUTABLE_CFPB_CASE`).

### Client TypeScript hasil generation

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
    },
  });
  // Baca result.status; jangan menganggap results selalu berisi.
} catch (error) {
  if (error instanceof ApiClientError) {
    // error.status, error.code, error.fieldErrors
  }
}
```

Client membawa `basePath` `/api` sekali, tidak mengulang mutasi, dan memetakan kegagalan transport/non-JSON ke `TRANSPORT_ERROR`. Repositori ini tidak menyertakan UI Next.js; client hanya dapat diperiksa melalui typecheck, tes, dan live contract test.

### Konfigurasi, batas, dan operasi

- Lokasi database: `FINRESOLVE_DB_PATH` (default `backend/data/finresolve.db` bila dijalankan dari `backend/`). Log startup mencetak path absolut.
- Backup: hentikan server terlebih dahulu, lalu salin file database beserta sidecar `-wal` dan `-shm` bila ada. Jangan menyalin hanya file utama saat server masih menulis.
- Kapasitas total: 5.000 kasus gabungan CFPB dan ANALYST. Impor/retensi yang melewati batas dibatalkan dengan **409** `KNOWLEDGE_BASE_LIMIT_EXCEEDED`.
- Impor: file maksimum 20 MiB, request multipart 21 MiB, 5.000 record per file. Narasi maksimum 50.000 karakter; perusahaan 500; respons perusahaan 2.000.
- Retensi: `handlingNotes` wajib maksimum 10.000 karakter; `evaluationNotes` opsional maksimum 10.000; maksimum 20 referensi berbeda.
- Pencarian: `limit` default 5, maksimum 20. Tidak ada pagination pencarian. Korpus dan TF-IDF dibangun ulang pada setiap pencarian; tidak ada cache query atau penyimpanan query.
- Konfigurasi skor ada di `finresolve.similarity.*` pada `backend/src/main/resources/application.yml` (bobot kategori/teks, bobot empat field, ambang 0.50, limit). Nilai tidak valid membuat startup gagal. Batas kontrak tidak dapat dinaikkan oleh konfigurasi.
- Ambang dan bobot awal **belum dikalibrasi**. Respons historis adalah konteks, bukan bukti keberhasilan penanganan.
- Katalog kategori hanya berasal dari kasus CFPB yang diimpor; retensi ANALYST tidak memperluas katalog. Sebelum impor, katalog kosong dan retensi ditolak **409** `EMPTY_CATEGORY_CATALOG`.
- Tidak ada autentikasi, peran, deployment publik, atau UI pada demo ini.

## Menjalankan demo dengan Docker Compose

Demo API lengkap dapat dijalankan tanpa memasang Java atau Node.js di host. Prasyaratnya adalah Docker Engine atau Docker Desktop dengan Compose v2. Build pertama membutuhkan internet untuk mengunduh image Java 21 dan dependensi Maven. Sampel 500 kasus sudah tersedia di repository dan tidak diunduh saat startup.

Dari root repository:

```bash
docker compose up --build
```

Build pertama juga mengompilasi jar melalui Maven Wrapper. Setelah container berjalan, periksa status kesehatan:

```bash
docker compose ps
```

Status `healthy` baru muncul setelah inisialisasi 500 kasus selesai. Selama inisialisasi berlangsung, endpoint kesehatan mengembalikan **503**; setelah siap, endpoint tersebut mengembalikan **200** `{"status":"UP","database":"UP"}`. API hanya dipublikasikan pada loopback `http://127.0.0.1:8080`, tanpa autentikasi dan tanpa akses dari jaringan lain.

Contoh pemeriksaan:

```bash
curl -i http://127.0.0.1:8080/api/health
curl -sS 'http://127.0.0.1:8080/api/cases?source=CFPB&size=2'
curl -sS -X POST http://127.0.0.1:8080/api/search \
  -H 'Content-Type: application/json' \
  -d '{"product":"Checking or savings account","subProduct":"Checking account","issue":"Managing an account","subIssue":"Deposits and withdrawals","narrative":"Money left my bank balance twice even though I only authorized one payment.","limit":5}'
```

Database SQLite disimpan pada named volume di `/data/finresolve.db`. Menghentikan atau membuat ulang container tidak menghapus data:

```bash
docker compose down          # container berhenti, volume dan data tetap
docker compose up            # 500 kasus CFPB dan kasus analis tetap tersedia
```

Untuk mereset demo secara eksplisit dan destruktif, hapus volume demo:

```bash
docker compose down --volumes
docker compose up --build
```

Perintah reset tersebut menghapus seluruh kasus analis pada volume demo. Jangan jalankan bila data tersebut ingin dipertahankan. Startup akan gagal dengan pesan yang jelas bila `data/cfpb/sample.csv` hilang atau tidak valid, sehingga aplikasi tidak menyajikan demo kosong. Inisialisasi demo hanya diaktifkan oleh Compose; startup non-container tetap memakai perilaku default dan tidak membaca CSV. Alur devcontainer tidak berubah, dan demo ini tetap hanya API tanpa frontend, autentikasi, atau deployment publik.

## Evaluasi relevansi

Panduan walkthrough dan lembar evaluasi manual ada di [docs/evaluation/README.md](docs/evaluation/README.md). Label relevansi **belum diisi**; jangan mengutip precision@5 atau kualitas relevansi sebelum ada penilaian manusia. Hasil pengujian otomatis hanya membuktikan kontrak dan perilaku teknis, bukan kualitas pencarian.

Penting untuk membedakan **respons yang tercatat** dengan **solusi yang terbukti berhasil**. Label respons dari CFPB tidak otomatis menjelaskan langkah investigasi, prosedur internal, atau tingkat kepuasan konsumen. Jika proyek kelak memberikan rekomendasi tindakan, rekomendasi tersebut membutuhkan sumber prosedur atau verifikasi tambahan.

Arah fokus yang dipertimbangkan adalah pengaduan terkait **transfer uang dan rekening**. Cakupan akhirnya dapat disesuaikan setelah meninjau kualitas dan ketersediaan data; sampel yang digunakan tidak diasumsikan mewakili seluruh pengaduan jasa keuangan.

## Input dan pencarian kasus

Rancangan input awal mencakup kategori produk, subproduk, masalah, submasalah, dan deskripsi pengaduan. Narasi menjadi bagian dari pencarian, sehingga kasus baru perlu memiliki deskripsi untuk menjalankan pencarian gabungan.

Pilihan kategori mengikuti struktur data sumber. Tidak semua produk memiliki subproduk, dan tidak semua masalah memiliki submasalah; ketiadaan atribut tersebut perlu ditangani dalam validasi input dan perhitungan kemiripan. Hubungan antaratribut dijelaskan pada [referensi atribut CFPB](https://cfpb.github.io/api/ccdb/fields.html).

Pencarian menggabungkan dua komponen:

- **Kemiripan kategori:** membandingkan jenis layanan dan masalah pengaduan.
- **Kemiripan teks:** membandingkan detail deskripsi kasus baru dengan narasi kasus historis.

Implementasi memakai `tfidf-cosine-v1`: TF-IDF yang hanya dilatih pada narasi kasus, cosine yang dinormalisasi, bobot kategori 0.4 dan teks 0.6, empat bobot field kategori 0.35/0.15/0.35/0.15, ambang awal 0.50, serta syarat tumpang tindih teks. Kandidat dengan skor di bawah ambang tidak ditampilkan. Nilai ini adalah titik awal yang belum dikalibrasi; respons server selalu memuat konfigurasi aktual dan peringatan.

Respons perusahaan merupakan informasi historis yang ditampilkan bersama kasus hasil pencarian, bukan informasi yang harus diketahui analis ketika memasukkan pengaduan baru.

## Alur konseptual CBR

- **Retrieve:** temukan kasus terdahulu menggunakan gabungan kemiripan kategori dan teks pengaduan.
- **Reuse:** tampilkan kasus serupa, alasan kemiripannya, serta respons historis atau pengetahuan penanganan yang tersedia.
- **Revise:** analis menilai apakah informasi tersebut sesuai dan menyesuaikan tindak lanjut dengan konteks kasus baru.
- **Retain:** analis dapat menambahkan kasus yang telah ditangani beserta catatan penanganannya ke basis pengetahuan secara manual. Tidak diperlukan peran verifikator atau tahap persetujuan terpisah.

Kasus yang disimpan analis tetap dibedakan asalnya dari kasus arsip CFPB. Penyimpanan kasus tidak otomatis membuktikan bahwa penanganannya berhasil; catatan dan penilaian analis perlu dipertahankan sebagai konteks.

Apabila tidak ada preseden yang cukup relevan, sistem perlu **menyatakan keterbatasannya** dan menyerahkan penilaian kepada analis.

## Gambaran hasil yang diharapkan

Sistem diharapkan dapat memperlihatkan kasus-kasus serupa, alasan suatu kasus dianggap relevan, ringkasan hasil atau respons historis, dan ruang untuk evaluasi analis. Penjelasan asal informasi lebih penting daripada menghasilkan satu jawaban yang tampak pasti.

Keberhasilan proyek terutama berkaitan dengan **relevansi dan kegunaan preseden**, bukan sekadar akurasi memprediksi kategori respons perusahaan. Evaluasi perlu mempertimbangkan penilaian manusia atas kasus yang ditemukan.

## Prinsip pengembangan

- **Human-in-the-loop:** keputusan, tindakan penanganan, evaluasi, dan penambahan kasus baru tetap dilakukan analis.
- **Explainability:** hasil pencarian kasus perlu dapat ditelusuri dan dipahami.
- **Tidak menyamakan kemiripan dengan kebenaran:** kasus mirip tidak selalu membutuhkan penyelesaian yang sama.
- **Retensi manual:** analis memilih kasus yang ditambahkan tanpa alur persetujuan khusus; data baru tidak otomatis dianggap sebagai pengalaman yang telah terbukti.
- **Perlindungan data:** hindari informasi pribadi yang tidak diperlukan, terutama bila kelak memakai data internal perusahaan.

## Ruang keputusan yang masih terbuka

Keputusan yang sudah ditetapkan meliputi Spring Boot 4.1.2 + Java 21, SQLite, OpenAPI 3.1 sebagai sumber kontrak, client TypeScript hasil generation, formula `tfidf-cosine-v1`, batas demo 5.000 kasus, dan retensi manual tanpa verifikator. Yang masih terbuka:

- Frontend Next.js, desain dan bahasa antarmuka, serta rincian interaksi evaluasi dan retensi.
- Autentikasi, hak akses, dan deployment publik.
- Kalibrasi bobot/ambang relevansi berdasarkan penilaian manusia; nilai 0.4/0.6 dan 0.50 belum diklaim optimal.
- Perluasan cakupan produk/periode dan pembersihan data di luar sampel demo 500 kasus.
- Skenario evaluasi tambahan selain precision@5 manual yang sudah disediakan.

**Acuan:** dokumen *Sistem CBR Keluhan Konsumen.pdf* dan arah proyek FinResolve yang telah didiskusikan. Rancangan awal merupakan referensi, bukan spesifikasi yang harus diikuti tanpa peninjauan.
