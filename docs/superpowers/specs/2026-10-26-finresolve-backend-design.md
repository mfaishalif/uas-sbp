# Desain backend FinResolve untuk demo lokal

Tanggal: 26 Oktober 2026. Status: **disetujui pengguna** pada 26 Oktober 2026, termasuk kontrak API dan generation backend/frontend. Pengguna meminta berhenti sebelum implementasi; persetujuan desain ini tidak memberi izin untuk memulai implementasi atau generation sekarang.

## Maksud dan keputusan

Backend mendukung demo UAS Sistem Berbasis Pengetahuan: analis memasukkan pengaduan, membandingkan preseden, lalu menyimpan kasus yang telah ditangani beserta penilaiannya. Kemiripan dan respons historis adalah bahan pertimbangan, bukan bukti keberhasilan suatu solusi.

Keputusan pengguna: implementasi backend berdasarkan README, target demo lokal dengan penyimpanan permanen, database **SQLite**, kontrak API agar frontend dapat diintegrasikan kemudian, dan **generation backend serta frontend dari kontrak yang sama untuk menjaga sinkronisasi**. Java serta sumber narasi CFPB mengikuti README. Rincian teknis yang semula diusulkan penulis telah disetujui sebagai bagian desain; keputusan yang secara eksplisit diserahkan kepada pembuat tetap ditentukan pada tahap perencanaan berikutnya.

Ini pembangunan backend baru, bukan perubahan kecil. Repositori saat diperiksa hanya berisi README, konfigurasi devcontainer, dan log. Perubahan README serta file lokal yang sudah ada harus dipertahankan.

## Tujuan, batasan, dan hal yang harus dihindari

- API REST untuk kategori, daftar/detail kasus, pencarian CBR, dan retensi manual.
- Impor sampel CFPB yang tetap dan dapat ditelusuri; menjalankan demo berikutnya tidak memerlukan jaringan.
- Skor yang deterministik dan dapat dijelaskan, penanganan kategori opsional, serta hasil kosong jika tidak ada preseden relevan.
- Data dan catatan tetap ada setelah restart.
- Pengujian otomatis perilaku utama serta panduan evaluasi relevansi oleh manusia.
- Interface HTTP dan DTO backend, serta client HTTP dan tipe frontend, dihasilkan dari satu kontrak OpenAPI. Build dan CI memeriksa ketertinggalan generated code serta perilaku HTTP terhadap kontrak.

Di luar tahap ini: frontend Next.js, login dan peran pengguna, deployment publik, integrasi seluruh arsip CFPB, model bahasa/embedding, terjemahan otomatis, rekomendasi prosedur penanganan, persetujuan verifikator, serta pengukuran akurasi prediksi respons perusahaan. Riwayat pencarian dan draft pengaduan tidak disimpan otomatis.

Pengecualian untuk cakupan frontend: paket client TypeScript hasil generation, pengujian tipe/transport client, dan pemeriksaan CI termasuk tahap backend ini agar integrasi nanti sudah memiliki fondasi. Halaman, komponen, dan alur UI Next.js tetap tahap berikutnya.

Kegagalan desain mencakup: menyamakan respons CFPB dengan solusi terbukti; menghilangkan asal data; memasukkan setiap pencarian ke basis kasus; menganggap dua kategori kosong sebagai kecocokan; atau memberi klaim relevansi tanpa menjelaskan keterbatasan ambang awal.

## Approaches

| Pendekatan | Pertimbangan | Keputusan |
| --- | --- | --- |
| Spring Boot + H2 file | Mudah untuk demo Java; penyimpanan embedded | Ditawarkan, tidak dipilih |
| Spring Boot + PostgreSQL | Database server sejak awal; perlu layanan dan konfigurasi tambahan | Ditawarkan, tidak dipilih |
| Spring Boot + SQLite | File persisten lokal; JDBC langsung cukup untuk model data kecil; penulisan perlu diserialisasi | Pengguna meminta SQLite menggantikan pilihan sebelumnya |

Usulan implementasi: Java 21, Maven Wrapper, Spring Boot Web + Validation + JDBC, driver Xerial SQLite, parser CSV yang mendukung kutipan dan baris baru. Gunakan JDBC dengan parameter SQL; ORM tidak diperlukan. Versi stabil yang kompatibel dipilih dan dikunci saat penyusunan rencana implementasi.

## Struktur dan penyimpanan

Satu aplikasi di `backend/`, port 8080. Lapisan controller menangani kontrak HTTP, service menangani validasi bisnis dan CBR, repository menangani SQL. Pisahkan modul kasus, impor, kategori, dan similarity agar rumus dapat diuji tanpa server.

Database default `backend/data/finresolve.db` ketika perintah dijalankan dari `backend/`; lokasi dapat diatur melalui konfigurasi dan startup mencatat lokasi absolut. File database, WAL, dan hasil build tidak masuk Git. Satu proses aplikasi dan satu koneksi database aktif cukup untuk demo; transaksi penulisan pendek, `foreign_keys=ON`, `busy_timeout=5000`, serta WAL diaktifkan. Error database terkunci menjadi HTTP 503 dengan pesan untuk mencoba lagi.

Skema dibuat sekali dengan migrasi SQL bernomor dan catatan versi; startup berikutnya tidak menghapus atau mengisi ulang data. Pilihan pustaka migrasi atau runner kecil diserahkan kepada pembuat, dengan syarat transaksi, pelacakan versi, dan pengujian restart.

Tabel utama:

- `import_batches`: ID, URL arsip, nama file sumber, periode mulai/akhir, SHA-256 file CSV yang diimpor, waktu impor, dan jumlah baris diterima/duplikat/ditolak.
- `cases`: ID internal UUID, `source` (`CFPB` atau `ANALYST`), ID pengaduan CFPB unik nullable, referensi batch nullable, tanggal diterima nullable, produk, subproduk nullable, masalah, submasalah nullable, narasi, perusahaan nullable, respons perusahaan nullable, waktu dibuat/diperbarui UTC.
- `analyst_notes`: satu catatan per kasus ANALYST, langkah penanganan, penilaian hasil (`UNKNOWN`, `RESOLVED`, `PARTIALLY_RESOLVED`, `UNRESOLVED`), komentar evaluasi opsional. Penilaian selalu ditandai sebagai laporan analis.
- `case_references`: pasangan ID kasus ANALYST dan ID preseden, unik; referensi dapat menuju CFPB atau kasus ANALYST lain. Tidak boleh merujuk diri sendiri.

Kasus CFPB tidak dapat diubah melalui API retensi. Kasus ANALYST memiliki catatan wajib dan tidak dapat membawa ID pengaduan/batch CFPB. Tidak ada endpoint hapus tahap ini. Update kasus ANALYST mengganti rincian dan referensinya secara atomik.

## Data awal dan impor

Arsip usulan: November–Desember 2024, mengikuti pemeriksaan awal README. Pemeriksaan baca sebagian ZIP pada 26 Oktober 2026 mengonfirmasi entri CSV dan header: `Date received`, `Product`, `Sub-product`, `Issue`, `Sub-issue`, `Consumer complaint narrative`, `Company`, `Company response to consumer`, `Complaint ID`, beserta kolom lain. Pemeriksaan ini belum menilai keseluruhan data.

Sediakan perintah persiapan data terpisah yang mengunduh arsip resmi dan menghasilkan CSV sampel beserta manifest. Ambil hingga 500 kasus pertama menurut urutan CSV sumber dengan narasi nonkosong, produk `Checking or savings account` atau `Money transfer, virtual currency, or money service`, masalah nonkosong, dan ID unik. Ini sampel deterministik untuk demo, bukan sampel representatif. Jika kurang dari 500, gunakan yang tersedia dan catat jumlah; jika nol, persiapan gagal dengan alasan jelas. Jangan memperluas produk/periode diam-diam. Simpan CSV sampel dan manifest agar demo dapat diulang; arsip besar tidak masuk Git.

Manifest berisi URL, nama arsip/entri, periode, filter, batas jumlah, waktu pengambilan, checksum arsip dan sampel, serta ID terpilih. Hanya simpan kolom yang digunakan aplikasi; abaikan lokasi, ZIP, tags, dan atribut pribadi yang tidak diperlukan. Narasi sumber dipertahankan untuk penelusuran; tidak mengklaim telah melakukan anonimisasi tambahan.

Endpoint impor menerima CSV UTF-8 dengan header CFPB dan metadata sumber/periode. CSV sampel menggunakan nama header yang sama. Batas upload 20 MiB dan maksimal 5.000 baris, serta batas narasi 50.000 karakter. Impor di luar batas ditolak seluruhnya. Parser mendukung BOM, koma dan newline dalam kutipan. Header wajib: `Complaint ID`, `Product`, `Issue`, `Consumer complaint narrative`; metadata URL arsip dan periode wajib. Kolom opsional yang hilang menjadi null.

Baris dengan field wajib kosong, ID/tanggal tidak valid, tanggal di luar periode jika tersedia, atau hubungan kategori yang tidak valid secara struktural ditolak dan dihitung; ID CFPB duplikat dilewati tanpa menimpa data. Tanggal kosong dibolehkan dan ditandai tidak tersedia. Tidak memakai katalog yang sudah ada untuk menolak kategori baru dari impor. Importer tidak mengikuti URL metadata atau mengunduh URL dari request.

Validasi file dilakukan sebelum transaksi penyimpanan; CSV rusak, header wajib hilang, dan batas terlampaui tidak boleh meninggalkan sebagian data. Untuk file valid, simpan baris yang lolos dan laporan batch secara atomik. Respons menyertakan jumlah dan maksimal 20 contoh error dengan nomor record, bukan isi narasi. Impor ulang file dengan checksum dan metadata sama mengembalikan laporan batch terdahulu, tanpa membuat ulang kasus/batch. Impor baru dengan ID lama menghitung duplikat.

## Kategori dan validasi

Katalog berasal dari tuple unik `(product, subProduct, issue, subIssue)` pada kasus CFPB yang diimpor. `GET /api/categories` mengembalikan tuple yang tersedia sehingga frontend dapat memfilter pilihan secara berjenjang. Ini katalog sampel, bukan seluruh taksonomi CFPB.

Input pencarian dan retensi wajib memiliki produk, masalah, serta narasi; produk/masalah harus cocok dengan pasangan dalam katalog sampel. Subproduk dan submasalah boleh kosong. Bila diberikan, subproduk harus valid bagi produk dan submasalah harus valid bagi pasangan produk/masalah. Tidak mensyaratkan keduanya ada pada satu record sampel yang sama. Trim kategori, samakan spasi berulang, bandingkan tanpa membedakan kapital, simpan label kanonis sumber. Blank menjadi null. Narasi 1–50.000 karakter setelah trim; tokenisasi harus menghasilkan sedikitnya satu token bermakna, jika tidak HTTP 400.

Sebelum impor, katalog kosong: pencarian valid mengembalikan `EMPTY_KNOWLEDGE_BASE`; retensi ditolak HTTP 409 dengan petunjuk impor kategori lebih dahulu. Ini batas demo yang disengaja. Kasus CFPB yang baru diimpor memperluas katalog; kasus ANALYST tidak memperkenalkan kategori baru.

## Pencarian dan penjelasan

Request berisi `product`, `subProduct?`, `issue`, `subIssue?`, `narrative`, dan `limit` (default 5, rentang 1–20). Tidak meminta respons perusahaan. Semua kasus ber-narasi, termasuk hasil retensi, menjadi kandidat; tidak ada penyaringan produk secara diam-diam.

Kategori: bobot awal produk 0,35; subproduk 0,15; masalah 0,35; submasalah 0,15. Field opsional yang kosong pada query dikeluarkan dari penyebut. Jika query memiliki nilai tetapi kandidat kosong, kontribusi nol dan bobot tetap dihitung. Kecocokan bernilai 1 hanya untuk nilai nonkosong yang sama. Skor kategori = jumlah bobot kecocokan / jumlah bobot field query yang dipakai.

Teks: lowercase `Locale.ROOT`, token alfanumerik, buang stopword Inggris versi tetap, token angka saja, token panjang satu, dan placeholder redaksi `xx`, `xxx`, dst. Narasi asli tidak berubah. Tidak memakai stemming atau model eksternal. TF = jumlah token; IDF = `ln((N+1)/(df+1))+1`. Fit IDF hanya pada korpus kasus, lalu gunakan bobot yang sama untuk query. Token query di luar vocabulary diabaikan; vektor tanpa norma menghasilkan skor teks nol. L2-normalisasi dan cosine menghasilkan skor 0–1. Daftar stopword dan versi algoritme dicatat di repo.

Skor gabungan awal = `0,4 × kategori + 0,6 × teks`. Kandidat ditampilkan bila skor gabungan >= 0,50 **dan** skor teks > 0. Ini mencegah kasus tanpa satu pun bukti tekstual lolos hanya dari kategori. Nilai ini hipotesis demo yang belum dikalibrasi, bukan persentase probabilitas relevansi. Bobot kategori, bobot gabungan, threshold, dan batas hasil dikonfigurasi server; konfigurasi invalid menggagalkan startup.

Urutkan skor gabungan menurun lalu ID internal menaik sebagai pemecah seri, tanpa pembulatan sebelum penyaringan/pengurutan. Setiap hasil berisi rincian kasus/sumber, skor total/kategori/teks, per-field match/missing/not-used, dan maksimal 10 token bersama dengan kontribusi cosine terbesar. Respons juga menyertakan bobot, threshold, versi algoritme, ukuran korpus, serta peringatan bahwa respons historis bukan solusi terbukti.

Jika korpus kosong, `status=EMPTY_KNOWLEDGE_BASE`; jika tidak ada yang memenuhi syarat, `status=NO_RELEVANT_CASES`; selain itu `status=MATCHES`. Semua HTTP 200 dan hasil kosong sesuai status, dengan pesan keterbatasan. Pengaduan query tidak disimpan.

Untuk batas demo 5.000 kasus, bangun snapshot TF-IDF dari seluruh korpus pada setiap pencarian. Ini memilih kesederhanaan dan konsistensi tanpa cache invalidation. Baca snapshot database dalam transaksi singkat, lalu hitung di luar koneksi database. Retensi/impor yang sudah commit sebelum pembacaan berikutnya langsung tercakup. Batas total basis kasus 5.000 diberlakukan pada impor dan retensi; melebihi batas membatalkan penulisan dengan HTTP 409.

## Kontrak API dan alur analis

### Generation backend dan frontend

Pendekatan yang diminta pengguna adalah contract-first generation. `docs/api/openapi.json` menjadi satu-satunya sumber definisi HTTP publik yang diedit. OpenAPI Generator untuk kedua sisi telah disetujui sebagai bagian desain, dengan detail ownership, build, dan pengujian pada [rancangan generation](../../api/generation.md). Kompatibilitas teknis tetap harus dibuktikan saat implementasi diizinkan.

Backend mengimplementasikan interface hasil generation dan memakai DTO hasil generation pada batas HTTP. Service CBR, repository SQLite, dan model internal tetap ditulis manual. Frontend menggunakan paket client dan tipe hasil generation; tidak menduplikasi endpoint atau DTO transport. Generated files tidak diedit manual. Generation menyelaraskan bentuk API; contract test tetap diperlukan untuk semantik null, status HTTP, error, dan aturan bisnis.

### Endpoint dan perilaku

Kontrak terperinci tersedia dalam [OpenAPI 3.1](../../api/openapi.json) dan [panduan integrasi frontend](../../api/README.md). OpenAPI menetapkan endpoint, DTO, nullability, enum, batas input, pagination, status HTTP, dan contoh sintetis request/response; panduan menetapkan semantik bisnis dan contoh `fetch`, termasuk multipart. Keduanya adalah bagian desain ini dan menjadi acuan implementasi. Versi kontrak yang disetujui adalah `0.1.0`; versi ini tidak berarti endpoint sudah tersedia.

JSON memakai camelCase tanpa envelope `data`. Field nullable pada respons selalu hadir; koleksi kosong berupa array. UUID kasus dan ID CFPB keduanya string. PUT mengganti lengkap kasus ANALYST dan mengosongkan field opsional yang dihilangkan. POST retensi tidak idempoten. Respons pencarian memuat konfigurasi yang benar-benar digunakan; hasil kosong tetap HTTP 200. Angka, ID dan skor contoh dokumentasi bersifat sintetis.

Rincian yang ditetapkan kontrak: maksimum label kategori/company 500 karakter, companyResponse 2.000 karakter, file CSV 20 MiB dan total multipart 21 MiB. Part metadata wajib application/json. CSV Date received menerima YYYY-MM-DD atau MM/DD/YYYY; API memakai tanggal ISO. CSV hanya header ditolak, sedangkan file dengan seluruh record invalid menghasilkan laporan batch dengan imported=0. Validasi record mendahului pemeriksaan duplikat. CORS mencakup preflight OPTIONS dan mengekspos header Location setelah retensi.

| Endpoint | Perilaku |
| --- | --- |
| `GET /api/health` | Status layanan dan keterhubungan database; tanpa membuka lokasi file |
| `GET /api/categories` | Tuple kategori sampel CFPB |
| `GET /api/cases?page=0&size=20&source=CFPB` | Daftar ringkas tanpa narasi penuh, filter source opsional; size 1–100; urutan createdAt turun lalu ID naik |
| `GET /api/cases/{id}` | Detail, asal arsip atau catatan analis, serta ID referensi |
| `POST /api/imports/cfpb` | Multipart `file` CSV + `metadata` JSON (`archiveUrl`, `sourceFileName`, `periodStart`, `periodEnd`); laporan batch |
| `POST /api/search` | Query dan hasil pencarian yang dijelaskan di atas |
| `POST /api/cases` | Retensi manual dengan field pengaduan, `handlingNotes`, `outcomeAssessment`, `evaluationNotes?`, dan `referenceCaseIds` opsional; HTTP 201 |
| `PUT /api/cases/{id}` | Penggantian lengkap kasus ANALYST dan catatannya; HTTP 200 |

Reuse dilakukan dengan membaca hasil/detail preseden. Revise dilakukan analis di luar backend, lalu direkam dalam `handlingNotes` dan `evaluationNotes` saat retain. Tidak perlu draft tersimpan atau endpoint revisi terpisah. Retain mensyaratkan `handlingNotes` nonblank, maksimum 10.000 karakter; `evaluationNotes` opsional maksimum 10.000; assessment wajib salah satu enum (UNKNOWN dibolehkan). Maksimal 20 ID referensi berbeda yang semuanya harus ada. Persetujuan hasil penanganan tidak diverifikasi oleh sistem.

Kasus yang tidak ditemukan menghasilkan 404; request invalid 400; perubahan kasus CFPB 409; upload terlalu besar 413; kegagalan internal 500 dengan pesan umum. Format error konsisten: `code`, `message`, `fieldErrors`, dan `timestamp`; tanpa SQL, path, stacktrace, atau narasi. Tidak mencatat body request pengaduan dalam log. Validasi UUID, source enum, pagination, metadata, dan limit sebelum query SQL.

Untuk demo lokal, server bind loopback secara default; dokumentasikan override `0.0.0.0` untuk devcontainer sesuai kebutuhan port forwarding. CORS mengizinkan `http://localhost:3000` dan origin tambahan melalui konfigurasi; tidak wildcard. Tidak ada autentikasi, sehingga tidak dimaksudkan untuk diakses publik.

## Verifikasi dan evaluasi

- Unit test rumus kategori, optional/null, stopword/redaksi, IDF, cosine nol, urutan seri, threshold dan konfigurasi bobot.
- Integration test memakai SQLite file sementara sungguhan: migrasi, foreign key, restart/persistensi, duplikasi impor, parser CSV multiline/BOM, rollback file invalid, batas total, dan transaksi retensi/referensi.
- API test alur impor → kategori → pencarian → detail → retain → pencarian ulang → update; input invalid, korpus kosong, tidak relevan, dan larangan update sumber CFPB.
- Verifikasi implementasi terhadap OpenAPI: nama/type field, required/null, diskriminator sumber, response error, status pencarian kosong, pagination, multipart, replay impor, dan semantik penggantian PUT. Contoh kontrak sintetis tidak boleh diperlakukan sebagai pengukuran kualitas algoritme.
- Dari checkout bersih, generate interface/DTO Java dan client TypeScript dari file kontrak yang sama; kompilasi keduanya, periksa generated client yang tertinggal, dan jalankan client tersebut terhadap backend nyata dengan SQLite sementara. Generator beserta konfigurasi dan versi dependency harus reproducible. Uji kompatibilitas OpenAPI 3.1 sebelum menyelesaikan implementasi integrasi.
- Smoke test Maven Wrapper build dan menjalankan server port 8080 dengan database sementara. Jangan menulis test yang mengunduh arsip internet.
- Dataset test kecil boleh sintetis dan harus jelas terpisah dari sampel demo CFPB. Demo tidak boleh mengklaim fixture sintetis berasal dari arsip.
- Sediakan lembar evaluasi manual minimal 10 query Inggris yang mencakup kasus relevan, variasi kata, atribut opsional, dan tidak relevan. Penilai memberi label relevansi pada top-5; dokumentasikan precision@5 dengan penyebut 5 (hasil kurang dari 5 dianggap slot kosong tidak relevan), jumlah hasil, serta kasus kegagalan. Belum ada klaim kualitas sebelum penilaian manusia dilakukan.
- README diperbarui saat implementasi: prasyarat, persiapan/impor sampel, run/test, contoh curl, lokasi database, backup saat server berhenti, konfigurasi skor, dan batas demo.

## Diserahkan kepada pembuat

Nama package/class internal, versi dependency stabil yang kompatibel, pustaka migrasi, organisasi fixture, dan perintah persiapan data boleh dipilih saat perencanaan. DTO publik dan interface HTTP dihasilkan dari OpenAPI; nama field, tipe, enum, status, dan semantik integrasi bukan lagi keputusan bebas pembuat. Detail generator harus mengikuti batasan dan pemeriksaan pada rancangan generation. Keputusan implementasi tidak boleh memperluas scope atau mengubah rumus, asal data, penyimpanan manual, dan kontrak perilaku di atas. Bila inspeksi penuh data atau generation menemukan ketidakcocokan material, laporkan bukti dan sesuaikan desain secara terbuka sebelum mengubah cakupan.

## Referensi yang diperiksa

- README lokal: arah produk dan batas interpretasi CFPB.
- [Spring Boot SQL](https://docs.spring.io/spring-boot/reference/data/sql.html): dukungan JDBC dan konfigurasi sumber data.
- [Xerial SQLite JDBC](https://github.com/xerial/sqlite-jdbc): akses SQLite dari Java.
- [SQLite foreign keys](https://www.sqlite.org/foreignkeys.html): aktivasi constraint per koneksi.
- [Arsip resmi CFPB](https://www.consumerfinance.gov/foia-requests/foia-electronic-reading-room/cfpb-consumer-complaint-database-narratives-archive/).
- [ZIP November–Desember 2024](https://files.consumerfinance.gov/f/documents/CCDB_Export_8_November_2024_through_December_2024.zip): nama entri dan header diverifikasi dari sebagian unduhan.
- [Referensi field CFPB](https://cfpb.github.io/api/ccdb/fields.html): hubungan kategori dan field opsional.
