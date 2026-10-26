# FinResolve

**Aplikasi web full stack berbasis pengetahuan untuk membantu penanganan pengaduan konsumen jasa keuangan dengan pendekatan Case-Based Reasoning (CBR).**

## Tentang proyek

FinResolve adalah proyek mata kuliah **Sistem Berbasis Pengetahuan (SBP)** yang mengeksplorasi penggunaan pengalaman kasus historis untuk membantu analis menangani pengaduan baru di sektor perbankan dan jasa keuangan.

Ketika ada pengaduan baru, sistem mencari pengaduan terdahulu yang relevan, memperlihatkan alasan kemiripannya, dan menyajikan informasi tentang penanganan kasus tersebut sebagai **bahan pertimbangan**. Sistem mendukung keputusan manusia, bukan mengambil keputusan final atau menjamin suatu solusi akan berhasil.

Dokumen desain awal proyek ini berjudul *Sistem CBR Keluhan Konsumen*.

Proyek saat ini berada pada tahap perumusan spesifikasi. README ini mencatat arah produk dan keputusan implementasi yang sudah dipilih; aplikasi belum diimplementasikan.

## Masalah dan tujuan

Pengaduan keuangan kerap memiliki pola berulang. Namun, pengalaman penanganan sebelumnya tidak selalu mudah ditemukan atau dibandingkan. FinResolve bertujuan membuat pengetahuan tersebut lebih mudah ditelusuri, dijelaskan, dan dimanfaatkan secara hati-hati.

Pengguna utama yang dibayangkan adalah **analis atau petugas penanganan pengaduan** di organisasi jasa keuangan.

## Arah implementasi

| Aspek | Pilihan |
| --- | --- |
| Bentuk aplikasi | Web full stack |
| Frontend | Next.js |
| Bahasa backend | Java |
| Pencarian kasus | Gabungan kemiripan kategori dan kemiripan teks pengaduan |
| Bahasa input pengaduan | Mengikuti bahasa narasi dataset sumber |
| Sumber kasus historis | Arsip resmi narasi CFPB |
| Retensi kasus baru | Penyimpanan manual oleh analis, tanpa peran verifikator atau alur persetujuan khusus |

Frontend menyediakan antarmuka kerja analis. Backend menangani proses CBR dan pengelolaan kasus, sementara penyimpanan persisten menampung basis kasus serta catatan analis. Framework Java dan teknologi database belum ditetapkan.

## Lingkungan pengembangan

Konfigurasi [Dev Container](.devcontainer/devcontainer.json) menyediakan lingkungan Linux untuk pengembangan frontend Next.js dan backend Java:

| Komponen | Konfigurasi |
| --- | --- |
| Sistem dasar | Debian Bookworm, pengguna `vscode` non-root |
| Frontend | Node.js 24 LTS dan npm |
| Backend | JDK 21, Maven 3.9, dan Gradle 9 |
| Asisten terminal | Codex CLI (`codex`) dan OpenCode CLI (`opencode`), terpasang global melalui npm |
| Port yang diteruskan | `3000` untuk Next.js dan `8080` untuk backend Java |
| Ekstensi VS Code | ESLint, Prettier, dan Extension Pack for Java |

Toolchain disiapkan melalui [Dockerfile devcontainer](.devcontainer/Dockerfile): image Java Dev Containers sudah berisi JDK, sedangkan Node.js, npm, Maven, dan Gradle disalin dari image resmi masing-masing. Dockerfile memasang Codex CLI dan OpenCode CLI secara global melalui npm. Build tidak menjalankan instalasi melalui SDKMAN. Versi Node.js, JDK, Maven, dan Gradle mengikuti tag pada Dockerfile; versi patch mengikuti image yang tersedia saat build.

Cara menggunakan:

1. Instal Docker Desktop dan VS Code beserta ekstensi **Dev Containers** (`ms-vscode-remote.remote-containers`). Di Windows, pastikan Docker Desktop memakai Linux containers dan backend WSL 2 aktif.
2. Jalankan Docker Desktop, lalu buka folder proyek ini di VS Code.
3. Buka Command Palette (`Ctrl+Shift+P`) dan pilih **Dev Containers: Reopen in Container**. Pembuatan pertama membutuhkan koneksi internet untuk mengunduh image, runtime, dan build tools.
4. Setelah container siap, buka terminal di VS Code. Jalankan `codex` untuk memakai Codex CLI atau `opencode` untuk memakai OpenCode CLI dari folder proyek. Hubungkan provider AI lewat perintah `/connect` di OpenCode. Versi kedua CLI beserta toolchain diperiksa otomatis saat container pertama kali dibuat.

Devcontainer saat ini menyiapkan toolchain saja karena aplikasi belum diimplementasikan. Instalasi dependensi dan server pengembangan belum dijalankan otomatis. Setelah frontend tersedia, jalankan server Next.js dengan bind ke `0.0.0.0` (misalnya `npm run dev -- --hostname 0.0.0.0` dari folder frontend) agar dapat diakses melalui port yang diteruskan. Backend dapat menggunakan port `8080` saat framework sudah dipilih. Untuk build Java, utamakan Maven Wrapper atau Gradle Wrapper setelah tersedia di proyek agar versi build tool konsisten.

Layanan database akan ditambahkan setelah teknologi penyimpanan dipilih. Jika konfigurasi devcontainer diubah, pilih **Dev Containers: Rebuild Container** melalui Command Palette.

Codex dan OpenCode berjalan di terminal container dan membaca proyek dari lingkungan pengembangan tersebut. Dokumentasi resmi: [Codex CLI](https://developers.openai.com/codex/cli) dan [OpenCode CLI](https://dev.opencode.ai/v2/docs).

Jika log menampilkan `No space left on device` saat memasang VS Code Server di `/root/.vscode-remote-containers` pada distro `docker-desktop`, periksa ruang di WSL dan disk Docker Desktop. Jika Docker Desktop tersangkut pada status `starting` dan perintah Docker tidak merespons, restart Docker Desktop sebelum mencoba membuka container kembali.

## Sumber data dan batasannya

Sumber data awal adalah **[CFPB Consumer Complaint Database Narratives Archive](https://www.consumerfinance.gov/foia-requests/foia-electronic-reading-room/cfpb-consumer-complaint-database-narratives-archive/)**, yaitu arsip resmi pengaduan yang sebelumnya dipublikasikan dalam Consumer Complaint Database, dengan cakupan pengaduan hingga 14 Agustus 2026.

Pemilihan arsip diperlukan karena narasi pengaduan telah dihapus dari database aktif menurut [catatan rilis CFPB September 2026](https://cfpb.github.io/api/ccdb/release-notes.html). Pencarian gabungan membutuhkan data kategori sekaligus teks narasi.

Pemeriksaan awal terhadap sebagian arsip November–Desember 2024 menemukan data produk, subproduk, masalah, submasalah, narasi pengaduan, perusahaan, kategori respons perusahaan, dan ID pengaduan. Contoh narasi yang diperiksa berbahasa Inggris dan mencakup pengaduan rekening serta transfer uang. Pemeriksaan ini belum merupakan evaluasi kualitas seluruh arsip.

Basis kasus awal akan menggunakan **sampel tetap yang memiliki narasi**, dengan sumber arsip, periode, dan ID pengaduan dicatat agar asal kasus dapat ditelusuri. Jumlah sampel dan periode final ditentukan setelah eksplorasi data. Bahasa input mengikuti narasi sumber; bahasa antarmuka belum ditetapkan.

Penting untuk membedakan **respons yang tercatat** dengan **solusi yang terbukti berhasil**. Label respons dari CFPB tidak otomatis menjelaskan langkah investigasi, prosedur internal, atau tingkat kepuasan konsumen. Jika proyek kelak memberikan rekomendasi tindakan, rekomendasi tersebut membutuhkan sumber prosedur atau verifikasi tambahan.

Arah fokus yang dipertimbangkan adalah pengaduan terkait **transfer uang dan rekening**. Cakupan akhirnya dapat disesuaikan setelah meninjau kualitas dan ketersediaan data; sampel yang digunakan tidak diasumsikan mewakili seluruh pengaduan jasa keuangan.

## Input dan pencarian kasus

Rancangan input awal mencakup kategori produk, subproduk, masalah, submasalah, dan deskripsi pengaduan. Narasi menjadi bagian dari pencarian, sehingga kasus baru perlu memiliki deskripsi untuk menjalankan pencarian gabungan.

Pilihan kategori mengikuti struktur data sumber. Tidak semua produk memiliki subproduk, dan tidak semua masalah memiliki submasalah; ketiadaan atribut tersebut perlu ditangani dalam validasi input dan perhitungan kemiripan. Hubungan antaratribut dijelaskan pada [referensi atribut CFPB](https://cfpb.github.io/api/ccdb/fields.html).

Pencarian menggabungkan dua komponen:

- **Kemiripan kategori:** membandingkan jenis layanan dan masalah pengaduan.
- **Kemiripan teks:** membandingkan detail deskripsi kasus baru dengan narasi kasus historis.

Rancangan awal menghitung kedua komponen secara terpisah, lalu menggabungkannya untuk mengurutkan kasus. Metode pengolahan teks, bobot setiap komponen, jumlah hasil, dan ambang relevansi belum ditetapkan. Pilihan tersebut perlu diuji menggunakan sampel data dan penilaian manusia atas relevansi hasil.

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

README ini menjadi dasar penyusunan spesifikasi implementasi. Keputusan yang masih terbuka meliputi:

- Framework Java, teknologi database, rancangan API, dan deployment.
- Kebutuhan autentikasi serta hak akses selain keputusan untuk tidak memakai verifikator khusus.
- Periode arsip, jumlah sampel, cakupan produk dan subproduk, serta pembersihan data.
- Metode kemiripan teks, bobot kategori dan teks, penanganan atribut kosong, serta ambang relevansi.
- Desain dan bahasa antarmuka, termasuk rincian interaksi evaluasi serta retensi kasus.
- Skenario pengujian dan metode evaluasi relevansi pencarian oleh manusia.

Pilihan tersebut dapat ditetapkan berdasarkan eksplorasi data dan kebutuhan implementasi, dengan tetap mengikuti keputusan yang sudah dicatat di atas.

**Acuan:** dokumen *Sistem CBR Keluhan Konsumen.pdf* dan arah proyek FinResolve yang telah didiskusikan. Rancangan awal merupakan referensi, bukan spesifikasi yang harus diikuti tanpa peninjauan.
