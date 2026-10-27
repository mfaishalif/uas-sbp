# Rancangan generation dari kontrak FinResolve

Status: **diimplementasikan dan diverifikasi pada 26 Oktober 2026**. Task 1 membuktikan kontrak `docs/api/openapi.json` menghasilkan batas Java yang dikompilasi dan client TypeScript yang di-commit serta lolos typecheck. Task 10 menambahkan client ESM, adapter, live contract test, dan CI. Logika CBR dan UI tetap bukan bagian generation. Bagian rancangan di bawah dipertahankan; bagian bukti mencatat hasil nyata.

## Implementasi dan bukti Task 1

### Perintah reproduksi

Dari root repository, dengan Java 21 dan Node.js 24:

```sh
npm ci
npm run api:validate
npm run api:generate
npm run api:check
npm run client:typecheck
npm run client:test
npm run test:generation
cd backend && ./mvnw -Dtest=GeneratedModelsTest test
```

`npm run api:generate` memvalidasi kontrak lalu menghasilkan kedua batas. `npm run api:check` menghasilkan ulang ke direktori sementara dan membandingkan seluruh pohon TypeScript yang di-commit; ia juga memastikan lima interface Java tetap ada dan tidak berisi default method. `./mvnw` memakai Maven Wrapper 3.9.11 yang checksum-nya dikunci.

### Versi dan checksum yang dikunci

| Komponen | Versi | Kunci |
| --- | --- | --- |
| Java | 21 | `maven.compiler.release=21` |
| Maven Wrapper | 3.9.11 | `distributionSha256Sum=0d7125e8c91097b36edb990ea5934e6c68b4440eef4ea96510a0f6815e7eeadb` |
| Spring Boot | 4.1.2 | parent `backend/pom.xml` |
| Xerial SQLite JDBC | 3.53.4.0 | `backend/pom.xml` |
| Apache Commons CSV | 1.14.1 | `backend/pom.xml` |
| OpenAPI Generator | 7.26.0 | JAR SHA-256 `1760050094997b9cc790cc1350be095f15da8c08a996b184d3eadc4ccda5e35e` |
| Node.js | 24 | `engines` di root `package.json`; diuji pada 24.22.1 |
| TypeScript | 7.0.2 | `package-lock.json` |
| Ajv / ajv-formats | 8.20.0 / 3.0.1 | `package-lock.json` |
| OpenAPI 3.1 meta-schema | `2022-10-07` | SHA-256 `da01ba28852cac0de53893797cb8d1942bc3b05084f526dcc216717dec314ed0` |

Meta-schema disalin persis dari `https://spec.openapis.org/oas/3.1/schema/2022-10-07` ke `tools/openapi/schema/openapi-3.1-schema.json`; sumber dan hash dicatat di `tools/openapi/schema/README.md`.

### Signature Java yang dihasilkan

Signature diambil dari hasil kompilasi pertama, bukan ditebak:

```java
ResponseEntity<Health> SystemApi.getHealth();

ResponseEntity<Categories> CategoriesApi.getCategories();

ResponseEntity<CaseDetail> CasesApi.getCase(
        @PathVariable("id") UUID id);

ResponseEntity<CasePage> CasesApi.listCases(
        @Min(0) @Valid @RequestParam(value = "page", required = false, defaultValue = "0") Integer page,
        @Min(1) @Max(100) @Valid @RequestParam(value = "size", required = false, defaultValue = "20") Integer size,
        @Valid @RequestParam(value = "source", required = false) @Nullable String source);

ResponseEntity<CaseDetail> CasesApi.retainCase(
        @Valid @RequestBody RetainCaseRequest retainCaseRequest);

ResponseEntity<CaseDetail> CasesApi.replaceAnalystCase(
        @PathVariable("id") UUID id,
        @Valid @RequestBody RetainCaseRequest retainCaseRequest);

ResponseEntity<SearchResponse> SearchApi.searchCases(
        @Valid @RequestBody SearchRequest searchRequest);

ResponseEntity<ImportReport> ImportsApi.importCfpb(
        @RequestPart(value = "file", required = true) MultipartFile file,
        @Valid @RequestPart(value = "metadata", required = true) ImportMetadata metadata);
```

Semua interface tetap abstrak: `interfaceOnly=true` dan `skipDefaultInterface=true`, tanpa `default ResponseEntity<...>`. Nama tag menghasilkan `SystemApi`, `CategoriesApi`, `CasesApi`, `SearchApi`, dan `ImportsApi`; model memakai nama skema kontrak (`CfpbCase`, `AnalystCase`, `CaseDetail`, `CasePage`, `ImportMetadata`, dan seterusnya).

### Konfigurasi dan override tool

Konfigurasi opsi generator Java ada di `tools/openapi/java.json`, lalu `openapi-generator-maven-plugin` memuat file yang sama lewat `<configurationFile>` di `backend/pom.xml`. Perubahan package, type mapping, atau additional properties pada `java.json` karena itu berlaku pada jalur Maven maupun CLI. Hanya nilai spesifik build yang ditetapkan terpisah: input contract, output, generator name, dan direktori template absolut. Direktori template didefinisikan sekali di `tools/openapi/toolchain.json`; tes generation memastikan path Maven menunjuk direktori yang sama.

- Java: `null=Void` mencegah `Object` untuk skema `type: null`; `interfaceOnly`, `skipDefaultInterface`, `useSpringBoot4`, `useJackson3`, `useOneOfInterfaces`, Bean Validation, dan `ResponseEntity` diaktifkan. Timestamp dan anotasi JSON yang tidak diperlukan dimatikan.
- TypeScript: `null=null` dengan `null` sebagai language-specific primitive mencegah import `Null` yang tidak ada pada keluaran 7.26.0; `modelPropertyNaming=original`, `useSingleRequestParameter=true`, `dateLibrary=string`, dan `legacyDiscriminatorBehavior=false`. Tanggal tetap string ISO, required-nullable tetap wajib, dan `CaseDetail` menjadi discriminated union.
- Override template: dua file di `backend/src/main/resources/openapi-templates/`. `enumClass.mustache` memperbaiki generator 7.26.0 yang menulis `const: true` sebagai `TRUE("true")` pada field `Boolean`; override memakai `rawValue`. `beanValidationCore.mustache` membungkus regex `@Pattern` agar semantik pencarian JSON Schema tetap berlaku, dan mengganti `@Size` untuk string dengan `@id.ac.finresolve.presentation.validation.CodePointSize` karena Bean Validation menghitung UTF-16, sedangkan JSON Schema `minLength`/`maxLength` menghitung code point Unicode. Constraint `@Size` untuk koleksi tetap dipertahankan. Kedua jalur generation memakai direktori template yang sama; tidak ada file generated yang diedit manual. Paket generated `id.ac.finresolve.api` tetap menjadi batas HTTP; implementasi controller dan validasi transport berada di layer `presentation`.

### Bukti kompatibilitas

- `cd backend && ./mvnw -Dtest=GeneratedModelsTest test`: **3 tes lulus**. Kedua contoh `CaseDetail` contract dideserialisasi oleh Jackson 3 mandiri dengan ISO dates dan explicit null inclusion ke subtipe konkret `CfpbCase`/`AnalystCase`; string JSON mentah hasil serialisasi memuat tepat satu key `source`, dan field null yang hadir tetap ada setelah round-trip; tidak ada method default.
- `npm run client:typecheck`: lulus. Assertion compile-time tetap bermakna: `CfpbCase['subProduct']` menerima `null`, `Pick<CfpbCase, 'subProduct'> = {}` ditolak karena required-nullable bukan optional, dan `CaseDetail['source'] = 'OTHER'` ditolak.
- `npm run client:test`: **3 tes lulus**. Serializer generated mempertahankan nullable dan discriminator; string JSON mentah hasil `JSON.stringify` memuat tepat satu key `source` sebelum di-parse; request multipart memakai `FormData`, mengirim byte file utuh, mengirim `metadata` sebagai `Blob` bertipe `application/json`, dan tidak menulis header `Content-Type`/boundary manual.
- `npm run test:generation`: **11 tes lulus**. Mencakup file hilang/tambahan/berubah, dua generation bersih dengan path dan byte identik, checksum salah berhenti tanpa download, tidak ada properti model `any`/`Object`, interface Java abstrak, multipart, kontrak sumber tidak berubah, dan paritas konfigurasi `tools/openapi/java.json` dengan plugin Maven.
- `npm run api:check`: lulus; seluruh pohon TypeScript generated di direktori sementara identik dengan yang di-commit, dan lima interface Java tetap lengkap serta abstrak. Perintah ini **tidak** menjalankan Maven dan tidak membandingkan output Java. Paritas Java diperiksa terpisah: `./mvnw generate-sources` memakai `tools/openapi/java.json` yang sama, lalu sumber Java hasil Maven dibandingkan byte-per-byte dengan hasil CLI. Pada Task 1 keduanya identik; satu-satunya perbedaan metadata adalah `.openapi-generator/FILES` milik plugin tidak mencantumkan `.openapi-generator-ignore`.
- Probe operasi baru memakai kontrak dan output sementara di luar repository: implementer konkret `ProbeSystemApi` berhasil dikompilasi terhadap boundary sebelum probe, lalu gagal terhadap boundary baru dengan `ProbeSystemApi is not abstract and does not override abstract method probeNewOperation() in SystemApi`. `docs/api/openapi.json` tidak disentuh; tes generation membandingkan byte kontrak sebelum/sesudah.

### Batas yang belum dibuktikan Task 1

Generation dan kompilasi tidak membuktikan perilaku HTTP nyata, aturan bisnis CBR, akses SQLite, atau kesesuaian UI. Pengujian respons nyata, controller, repository, dan client adapter dikerjakan pada Task 10 dan tercatat di bawah.

## Implementasi dan bukti Task 10

### Client ESM dan adapter

- `tools/openapi/typescript.json` memakai `importFileExtension: ".js"` agar import relatif generated valid di Node ESM. `packages/api-client/package.json` bertipe `module`; `npm run client:build` mengompilasi `src/` ke `dist/` (di-ignore Git) dengan deklarasi tipe.
- `packages/api-client/src/client.ts` menyediakan `createClient(baseUrl, fetchApi?)` dan `ApiClientError`. Adapter menambahkan `/api` sekali, memakai satu `Configuration`, mengubah `ApiError` menjadi `status/code/fieldErrors`, memetakan kegagalan non-JSON/jaringan ke `TRANSPORT_ERROR`, dan tidak me-retry mutasi. Middleware meneruskan error asli agar runtime generated tidak membungkusnya menjadi `FetchError`.
- `packages/api-client/src/index.ts` mengekspor generated API/model dan adapter. Root package memakai npm workspace agar test dapat mengimpor `@finresolve/api-client` tanpa path relatif rapuh.

### Live contract test

- `tools/test-server.mjs` menjalankan packaged jar pada `127.0.0.1` dengan port loopback acak dan database sementara; `stop()` idempoten dan menghapus SQLite/WAL/SHM serta direktori sementara.
- `tests/contract/schema.mjs` memuat `docs/api/openapi.json`, mengompilasi skema respons per `operationId`/status dengan Ajv 2020 + formats, serta memeriksa header yang dideklarasikan (termasuk `Location`) dan body JSON sebelum deserialisasi generated.
- `tests/contract/api.test.mjs` menguji health, pencarian kosong, impor/replay, kategori, daftar/detail, pencarian MATCHES/OOV, retensi+referensi, PUT penuh, CORS, serta kelas error 400/404/405/409/413/415/500/503. Kapasitas diuji dengan fixture lokal; arsip CFPB tidak diunduh.
- `tests/contract/client.test.ts` membuktikan adapter mengarah ke `/api` sekali, memetakan `ApiError`, menangani respons non-JSON/jaringan, dan tidak me-retry mutasi.
- `.github/workflows/ci.yml` memakai Java 21 + Node 24, `npm ci`, lalu `npm run verify`, `git diff --check`, dan memastikan working tree tidak berubah.

### Perintah dan hasil

```bash
npm run test:generation   # 13 tes
npm run test:contract     # 5 tes (1 live + 4 adapter)
npm run verify            # generation, client, 267 tes backend, package, live contract
```

`npm run verify` selesai sukses pada 27 Oktober 2026 dan tidak mengubah file generated yang di-track. `docs/api/openapi.json` tetap sumber tunggal; `api:check` membandingkan pohon TypeScript hasil generation dengan yang di-commit.

## Sumber dan kepemilikan


`docs/api/openapi.json` adalah satu-satunya sumber kontrak HTTP publik yang diedit. Jangan menulis kontrak tandingan dari annotation controller atau menyalin skema ke proyek frontend. Jika dokumentasi HTTP nanti disajikan backend, gunakan kontrak sumber yang sama, bukan hasil inferensi dari controller.

| Bagian | Hasil generation | Kode manual |
| --- | --- | --- |
| Backend Java | Interface endpoint, DTO request/response, enum dan anotasi yang didukung generator | Implementasi interface/controller, validasi bisnis, pemetaan domain, CBR, repository, penanganan error |
| Client TypeScript | Tipe request/response, enum/discriminated union, metode HTTP dan serialisasi | Konfigurasi base URL, adapter error, pemakaian client dalam UI |
| Frontend Next.js berikutnya | Mengimpor client dan tipe dari paket yang sama | Halaman, form, state, tampilan hasil dan pesan |

Model domain/database boleh berbeda dari DTO publik; pemetaan dilakukan di batas aplikasi. Tidak membuat DTO transport atau alamat endpoint paralel yang ditulis manual. Generated files tidak boleh diedit untuk memperbaiki kontrak atau memasukkan logika bisnis.

## Approaches dan usulan alat

Pengguna sudah memilih pendekatan generation untuk kedua sisi. Alternatif berikut dipertimbangkan penulis; pendekatan pertama disetujui pengguna sebagai bagian desain:

1. **OpenAPI Generator untuk backend dan frontend — dipilih.** Generator `spring` menghasilkan interface dan DTO Java; `typescript-fetch` menghasilkan client dan model TypeScript. Satu versi engine dan satu input mengurangi perbedaan konfigurasi dua tool. Ada lebih banyak output generated dan runtime serializer yang harus diuji.
2. **OpenAPI Generator untuk Java; openapi-typescript + openapi-fetch untuk frontend.** Tipe frontend dihasilkan dari kontrak dan dipakai transport generik, dengan output lebih kecil. Perlu mengelola dua toolchain generator berbeda dan membuktikan perilaku serialisasi yang sama, terutama multipart.

Pendekatan pertama dipakai dalam rancangan di bawah. Versi engine Maven dan CLI frontend harus sama persis dan dikunci; jangan memakai `latest`, rentang versi, atau download generator tanpa versi. Versi stabil yang dipilih beserta checksum/distribusi dicatat saat rencana implementasi. Wrapper npm (bila digunakan), TypeScript, dan dependency runtime juga dikunci dalam manifest/lockfile.

## Output dan build yang direncanakan

- Java: `backend/target/generated-sources/openapi/`, diregenerasi pada fase Maven `generate-sources`, tidak di-commit. Konfigurasi generator di-commit dan build menambahkan output sebagai source root. Controller manual mengimplementasikan interface generated. Usulan `interfaceOnly=true`, `skipDefaultInterface=true` supaya endpoint baru memerlukan implementasi eksplisit, dan validasi Bean Validation aktif. Opsi Jakarta/Spring Boot harus cocok dengan versi framework yang dikunci.
- TypeScript: `packages/api-client/src/generated/`, di-commit agar perubahan kontrak/client bisa direview dan dipakai frontend tanpa generator global. Paket lokal bernama `@finresolve/api-client`, memiliki package manifest, ekspor, serta typecheck sendiri; tidak perlu membangun UI atau menerbitkan paket ke registry. Kode adapter berada di luar direktori generated.
- Generator frontend memakai `typescript-fetch`, nama properti mengikuti kontrak, parameter method berbentuk objek, dan tanggal dipertahankan sebagai string (`dateLibrary=string` pada versi yang mendukung). Ini menghindari pergeseran tanggal kalender karena konversi zona waktu. Required-nullable harus tetap dapat dibedakan dari optional.
- Tiap generation membersihkan direktori output khususnya terlebih dahulu agar file endpoint/model yang dihapus tidak tersisa. Pembersihan tidak boleh menyentuh kode manual. Timestamp generation dinonaktifkan; konfigurasi/urutan output harus deterministik.
- Nama operasi pada client berasal dari `operationId` kontrak. UI mengimpor metode dan tipe hasil generation; wrapper aplikasi tidak menulis ulang kontrak sebagai interface TypeScript atau melakukan `fetch` terpisah per endpoint.
- Konfigurasi script menyediakan perintah root untuk validasi+generation kedua sisi (`npm run api:generate`) dan pemeriksaan tanpa mengubah working tree (`npm run api:check`); perintah tersebut sudah tersedia dan diuji.
- Backend build tetap bisa berjalan melalui Maven Wrapper. Typecheck client dapat berjalan sendiri tanpa Next.js; setelah frontend tersedia, build frontend juga menjalankan pemeriksaan tipe terhadap paket client yang sama.

## Kompatibilitas yang harus dibuktikan

OpenAPI Generator mendokumentasikan dukungan OpenAPI 3.1 sebagai beta. Kontrak saat ini memakai `type: [string, null]`, `type: null`, `const`, serta `oneOf` dengan discriminator. Jangan menganggap keluaran generator otomatis mempertahankan semuanya dengan benar.

Langkah awal implementasi setelah desain disetujui adalah generate dan kompilasi kedua output, kemudian periksa serialisasi/deserialisasi kasus CFPB/ANALYST dan nullable fields. Pastikan tidak ada model API yang diam-diam menjadi tipe bebas (`Object`/`any`) akibat kegagalan membaca skema. Discriminator harus menentukan tipe kasus, bukan menambahkan field `source` ganda atau mengubah enum.

Multipart wajib diuji dari client generated sampai controller Java: file tetap biner, metadata menjadi part JSON dengan Content-Type application/json, serta boundary dikelola transport. Pilihan generator yang tidak menghormati encoding bukan alasan mengganti endpoint secara manual atau mem-patch file generated. Perbaikan dilakukan melalui konfigurasi, penyesuaian representasi kontrak yang mempertahankan semantik, atau template generator yang di-commit dan diuji.

Utamakan template bawaan. Jika template khusus diperlukan, dokumentasikan alasan, kunci versinya bersama engine, dan masukkan pemeriksaan regresi. Jangan mengendurkan required/null, menghapus discriminator, atau mengganti OpenAPI 3.1 ke 3.0 secara diam-diam. Jika penyesuaian mengubah perilaku kontrak atau alat yang disepakati, ajukan perubahan desain sebelum melanjutkan.

## Pemeriksaan sinkronisasi di CI

CI dari checkout bersih harus:

1. Memvalidasi dokumen OpenAPI dan contoh-contohnya, termasuk referensi, operationId unik, dan batas/enum.
2. Menghasilkan output Java baru dan mengompilasi implementasi backend terhadap interface/DTO tersebut. Default method stub tidak boleh membuat endpoint baru lolos tanpa implementasi.
3. Menghasilkan client TypeScript ke direktori sementara bersih, lalu membandingkan seluruh file dan daftar path dengan direktori generated yang di-commit. File hilang, berubah, atau tambahan harus menggagalkan check; `git diff` saja tidak cukup bila file baru tidak terlacak.
4. Menjalankan typecheck paket client dan adapter. Setelah UI tersedia, menjalankan typecheck/build frontend terhadap paket yang sama.
5. Menjalankan pengujian respons HTTP nyata terhadap kontrak sumber, memakai SQLite sementara dan validator yang mendukung OpenAPI/JSON Schema 3.1. Status HTTP, header penting, required-nullable, enum, unknown property rejection, dan error tidak cukup diverifikasi oleh kompilasi Java.
6. Menjalankan client generated terhadap backend test untuk health, kategori, impor multipart, detail kedua jenis kasus, pencarian dengan tiga status, retain/update, pagination, dan error terstruktur. Gunakan fixture sintetis terpisah, tanpa unduhan CFPB saat test.

Sinkronisasi dianggap tercapai bila generation dapat diulang, kode pemakai berhasil dikompilasi, serta respons/perilaku aktual memenuhi kontrak. Generation sendiri tidak membuktikan rumus CBR, akses database, atau aturan bisnis benar.

## Mengubah API

Edit kontrak terlebih dahulu; regenerasi kedua sisi; sesuaikan implementasi/controller dan pemakaian client; jalankan pemeriksaan CI; commit kontrak, konfigurasi bila berubah, generated client, dan kode pemakai bersama. Output Java tetap artefak build. Jangan menerbitkan kontrak baru sambil membiarkan client lama tersimpan di repo.

Perubahan yang memutus kompatibilitas harus dijelaskan dalam perubahan versi kontrak dan catatan perubahan. Saat backend dan frontend nantinya dideploy terpisah, keduanya masih perlu memakai versi kontrak yang kompatibel; generation tidak menyinkronkan deployment secara otomatis.

## Referensi

- [OpenAPI Generator Spring](https://openapi-generator.tech/docs/generators/spring/): interfaceOnly, skipDefaultInterface, dan opsi framework.
- [OpenAPI Generator TypeScript Fetch](https://openapi-generator.tech/docs/generators/typescript-fetch/): client, opsi tanggal, dan discriminator.
- [Integrasi Maven](https://openapi-generator.tech/docs/plugins/): generation pada fase generate-sources.
- [Kompatibilitas OpenAPI Generator](https://github.com/OpenAPITools/openapi-generator#11---compatibility): status dukungan OpenAPI 3.1.
- [openapi-typescript](https://openapi-ts.dev/introduction): alternatif tipe TypeScript dari kontrak.
