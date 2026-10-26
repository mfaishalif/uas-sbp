# FinResolve Backend Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a persistent local FinResolve backend with explainable CBR search, traceable CFPB imports, manual analyst retention, and Java/TypeScript HTTP boundaries generated from one contract.

**Architecture:** A Java 21 Spring Boot application uses SQLite through JDBC, with focused packages for cases, categories, imports, and similarity. OpenAPI generates the server interfaces and DTOs plus a local TypeScript fetch client; handwritten services own business rules, repositories own SQL, and controllers map internal records to generated DTOs. Every search materializes a consistent database snapshot before calculating TF-IDF outside the database connection.

**Tech Stack:** Java 21; Spring Boot 4.1.2; Maven 3.9.11; OpenAPI Generator 7.26.0 (`spring`, `typescript-fetch`); Xerial SQLite JDBC 3.53.4.0; Commons CSV 1.14.1; Node.js 24; TypeScript 7.0.2; Ajv 8.20.0 and ajv-formats 3.0.1; JUnit and AssertJ from the Spring Boot dependency BOM.

**Spec:** `docs/superpowers/specs/2026-10-26-finresolve-backend-design.md`. Also read `docs/api/openapi.json`, `docs/api/README.md`, and `docs/api/generation.md`; all four are implementation inputs.

## Global Constraints

The following quoted requirements retain the spec's exact values and wording:

- “Java 21, Maven Wrapper, Spring Boot Web + Validation + JDBC, driver Xerial SQLite, parser CSV yang mendukung kutipan dan baris baru.”
- “Satu aplikasi di `backend/`, port 8080.”
- “Satu proses aplikasi dan satu koneksi database aktif cukup untuk demo; transaksi penulisan pendek, `foreign_keys=ON`, `busy_timeout=5000`, serta WAL diaktifkan.”
- “Batas total basis kasus 5.000 diberlakukan pada impor dan retensi; melebihi batas membatalkan penulisan dengan HTTP 409.”
- “Batas upload 20 MiB dan maksimal 5.000 baris, serta batas narasi 50.000 karakter.”
- “maksimum label kategori/company 500 karakter, companyResponse 2.000 karakter, file CSV 20 MiB dan total multipart 21 MiB.”
- “Retain mensyaratkan `handlingNotes` nonblank, maksimum 10.000 karakter; `evaluationNotes` opsional maksimum 10.000”; “Maksimal 20 ID referensi berbeda yang semuanya harus ada.”
- “Kategori: bobot awal produk 0,35; subproduk 0,15; masalah 0,35; submasalah 0,15.”
- “TF = jumlah token; IDF = `ln((N+1)/(df+1))+1`.”
- “Skor gabungan awal = `0,4 × kategori + 0,6 × teks`”; “skor gabungan >= 0,50 **dan** skor teks > 0.”
- “`limit` (default 5, rentang 1–20).”
- “JSON memakai camelCase tanpa envelope `data`. Field nullable pada respons selalu hadir; koleksi kosong berupa array. UUID kasus dan ID CFPB keduanya string.”
- “PUT mengganti lengkap kasus ANALYST dan mengosongkan field opsional yang dihilangkan. POST retensi tidak idempoten.”
- “`docs/api/openapi.json` menjadi satu-satunya sumber definisi HTTP publik yang diedit”; “Generated files tidak diedit manual.”
- “Versi kontrak yang disetujui adalah `0.1.0`”; “Part metadata wajib application/json.”
- “Tidak mencatat body request pengaduan dalam log”; errors must not expose SQL, paths, stacktraces, or narratives.
- “server bind loopback secara default”; “CORS mengizinkan `http://localhost:3000` dan origin tambahan melalui konfigurasi; tidak wildcard.”
- “Jangan menulis test yang mengunduh arsip internet.”

Preserve existing README edits, `.devcontainer/`, and `plantuml-mcp.log`. Do not implement UI, authentication, public deployment, draft/search storage, embeddings, translation, or outcome prediction. This document authorizes no execution: the design explicitly stops before implementation and generation; obtain the user's plan review and execution choice first.

## Review Focus

1. Simultaneous writers near 5,000 cases: exactly one can take the last slot; the other rolls back its entire case/batch write (Tasks 5 and 8).
2. Malformed CSV near EOF, invalid UTF-8, or duplicate required headers: reject the entire file, with no partial cases or batch (Task 5).
3. Required-nullable response fields and omitted versus explicit-null request collections: preserve nulls in both generated runtimes, reject `limit:null` and `referenceCaseIds:null` (Tasks 1, 4, and 10).
4. Huge page numbers and SQL-looking category/ID strings: reject invalid formats before SQL, avoid offset overflow, and preserve the database (Task 4).
5. Turkish default locale, redaction-only text, and query-only vocabulary: locale-independent tokenization, a 400 for meaningless input, and zero cosine for out-of-vocabulary input (Tasks 3 and 7).

---

## Repository map and implementation decisions

The repository contains documentation and devcontainer tooling, with no application or test conventions to extend. Keep this one plan: the client, generator, and backend have a required shared compatibility gate rather than independent shipping boundaries. Execute tasks in order; commit only paths owned by the task, leaving pre-existing changes untouched.

Java paths below use `J = backend/src/main/java/id/ac/finresolve` and `T = backend/src/test/java/id/ac/finresolve`. These are exact prefix substitutions, not alternative locations. Public generated types live in `id.ac.finresolve.api.model`; generated interfaces in `id.ac.finresolve.api`. Internal records use Java UUID, LocalDate, Instant, and enums, never generated transport types.

| Files/directories | Responsibility |
| --- | --- |
| `backend/pom.xml`, `backend/mvnw`, `backend/mvnw.cmd`, `backend/.mvn/wrapper/maven-wrapper.properties` | Pinned build, dependencies, generation phase, executable jar |
| `tools/openapi/{toolchain.json,java.json,typescript.json,generate.mjs,check.mjs,validate.mjs}` | One pinned engine, source-contract validation, reproducible generation and complete tree comparison |
| `tools/openapi/templates/` (only if necessary) | Versioned generator overrides with regression tests and a documented reason |
| `packages/api-client/src/generated/` | Committed generated fetch client/models; generated Java stays under `backend/target/` |
| `packages/api-client/src/{index.ts,client.ts}`, `packages/api-client/test/` | Package exports, base URL/error adapter, type and transport checks |
| `J/FinresolveApplication.java`, `J/config/`, `J/db/`, `backend/src/main/resources/{application.yml,db/migration/}` | Application, one-connection SQLite, migrations, transactions, configuration |
| `J/cases/`, `J/categories/`, `J/imports/`, `J/similarity/` | Focused domain records, repositories, services, scoring, and controllers |
| `J/http/` | Generated DTO mapping, common errors, strict JSON/request rules, local CORS |
| `J/sample/` | Separate command that downloads the official archive and writes reproducible sample artifacts |
| `T/`, `backend/src/test/resources/fixtures/` | Unit and real file-backed SQLite integration tests; synthetic data only |
| `tests/contract/`, `tools/test-server.mjs` | HTTP schema checks and generated-client tests against a real temporary backend |
| `data/cfpb/{sample.csv,manifest.json}`, `docs/evaluation/` | Traceable demo sample and unfilled human relevance evaluation |
| `.github/workflows/ci.yml`, `README.md`, `docs/api/{README.md,generation.md}` | Repeatable checks and operating instructions |

### Version and generation choices

Pin all direct npm dependencies with exact versions and commit `package-lock.json`; let the pinned Spring Boot BOM manage Spring/Jackson/JUnit transitive versions. Use Boot's Web MVC, Validation, JDBC, and test starters; Jackson 3 must match generator `useJackson3=true`. Pin Commons CSV and SQLite explicitly. Use Hikari maximum/minimum pool size 1. Use a small transactional SQL migration runner rather than adding Flyway SQLite support dependencies.

Use the same Maven engine and CLI JAR version **7.26.0**. Do not add the npm generator wrapper; the Node orchestration invokes the verified JAR. Record these distributions in `tools/openapi/toolchain.json`:

| Artifact | URL | SHA-256 |
| --- | --- | --- |
| Generator | `https://repo.maven.apache.org/maven2/org/openapitools/openapi-generator-cli/7.26.0/openapi-generator-cli-7.26.0.jar` | `1760050094997b9cc790cc1350be095f15da8c08a996b184d3eadc4ccda5e35e` |
| Maven | `https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.9.11/apache-maven-3.9.11-bin.zip` | `0d7125e8c91097b36edb990ea5934e6c68b4440eef4ea96510a0f6815e7eeadb` |

These artifacts were fetched into memory and hashed while planning; neither generator nor Maven was executed. Verify again on download, reject checksum mismatch, and cache outside generated source directories. Commit Maven Wrapper's script-only distribution with `distributionSha256Sum` above.

Java generator options: `interfaceOnly=true`, `skipDefaultInterface=true`, `useTags=true`, `useSpringBoot4=true`, `useSpringBoot3=false`, `useJackson3=true`, `useJakartaEe=true`, `useOneOfInterfaces=true`, `useBeanValidation=true`, `useResponseEntity=true`, `annotationLibrary=none`, `documentationProvider=none`, `hideGenerationTimestamp=true`; generated root `backend/target/generated-sources/openapi`. TypeScript: `modelPropertyNaming=original`, `useSingleRequestParameter=true`, `dateLibrary=string`, `legacyDiscriminatorBehavior=false`, `hideGenerationTimestamp=true`. Keep runtime conversion enabled and test it.

Root scripts: `npm run api:validate`, `npm run api:generate` (validate and generate both sides), `npm run api:check` (temporary output, no tracked-tree mutation), `npm run client:typecheck`, `npm run test:contract`, and `npm run verify` (all checks plus Maven verify). Maven builds independently with `cd backend && ./mvnw verify`. Integration classes end in `Test` so the standard Maven test phase includes them.

References used for these choices: [Spring generator options](https://openapi-generator.tech/docs/generators/spring/), [TypeScript fetch options](https://openapi-generator.tech/docs/generators/typescript-fetch/), [tagged Spring generator source](https://github.com/OpenAPITools/openapi-generator/blob/v7.26.0/modules/openapi-generator/src/main/java/org/openapitools/codegen/languages/SpringCodegen.java), [tagged fetch generator source](https://github.com/OpenAPITools/openapi-generator/blob/v7.26.0/modules/openapi-generator/src/main/java/org/openapitools/codegen/languages/TypeScriptFetchClientCodegen.java), [Commons CSV release](https://commons.apache.org/proper/commons-csv/download_csv.cgi), and [Spring releases](https://github.com/spring-projects/spring-boot/releases). Version existence was also checked against Maven Central/npm metadata on 2026-10-26. Actual compatibility remains Task 1's deliverable.

## Task 1: Prove contract generation and compile both boundaries

**Files:** Create root `package.json`, `package-lock.json`, `.gitignore`; all build/generation/package files in the repository map; `tools/openapi/generation.test.mjs`; `packages/api-client/test/models.test.ts`; `packages/api-client/test/types.ts`; `T/http/GeneratedModelsTest.java`. Modify `docs/api/generation.md` with reproducible commands and compatibility evidence. Create template files only if a demonstrated failure requires them.

**Interfaces:** Consumes the approved OpenAPI unchanged. Produces `SystemApi.getHealth`, `CategoriesApi.getCategories`, `CasesApi.{listCases,getCase,retainCase,replaceAnalystCase}`, `SearchApi.searchCases`, `ImportsApi.importCfpb`, and generated schema-named DTOs. The generated method parameter types/order are authoritative: record their actual signatures in `docs/api/generation.md` after the first compile rather than guessing overloads here. TypeScript exports the generated tag API classes and schema-named models through `@finresolve/api-client`.

- [ ] **Step 1: Write compatibility tests.** Java `GeneratedModelsTest` deserializes both synthetic `CaseDetail` examples with a standalone Jackson 3 mapper configured for ISO dates and explicit null inclusion (the same policy Task 4 must use), asserts concrete CFPB/ANALYST subtype, a single `source` key, and present null fields after round-trip. TypeScript tests consume the same contract examples with generated serializers. Add compile-time assertions:

```ts
const nullable: CfpbCase['subProduct'] = null;
const calendar: CfpbCase['receivedDate'] = '2024-11-12';
// @ts-expect-error required-nullable is not optional
const omitted: Pick<CfpbCase, 'subProduct'> = {};
// @ts-expect-error discriminator must reject unknown sources
const wrong: CaseDetail['source'] = 'OTHER';
```

`generation.test.mjs` asserts absent/extra/changed generated files fail comparison, two clean runs have identical relative paths and bytes, and checksum mismatch aborts. Check generated transport models contain no unconstrained `any`/`Object` substitute for a defined contract schema. Inspect a generated multipart request: `metadata` must be a JSON Blob, file bytes intact, and no manually supplied multipart boundary.

- [ ] **Step 2: Run the failing checks.** `node --test tools/openapi/generation.test.mjs` and `npm run client:typecheck` must fail because generation/tooling is absent; record this before creating production tooling.
- [ ] **Step 3: Implement the pinned builds and generation.** `generate.mjs` exports `generateAll(outputRoot: string): Promise<void>`; `check.mjs` exports `compareTrees(expectedDir: string, actualDir: string): Promise<void>`. Delete only dedicated output directories; suppress volatile metadata including absolute output paths. `validate.mjs` validates OpenAPI document structure/references/unique operation IDs and every request/response example against its resolved JSON Schema 2020-12 schema with Ajv2020 plus formats (discriminator is an annotation; validate `oneOf` and `const`). Add an exact pinned OpenAPI 3.1 meta-schema resource under `tools/openapi/schema/` with its source URL/hash. Generate and compile before adding application behavior. If built-ins fail, first try configuration, then minimal committed templates based on 7.26.0; document every override. A semantic contract/tool change requires explicit design revision, not a weaker test or manual generated-file edit.
- [ ] **Step 4: Verify compatibility.** Run `npm ci`, `npm run api:generate`, `npm run api:check`, `npm run client:typecheck`, and `cd backend && ./mvnw -Dtest=GeneratedModelsTest test`. Expect all pass and no default endpoint implementations. Verify a temporary new operation causes a concrete implementer compile failure; use a temporary contract/output, leaving the source contract untouched.
- [ ] **Step 5: Commit only Task 1 paths.** `git commit -m "build: generate Java and TypeScript API boundaries from OpenAPI"`.

## Task 2: Add persistent SQLite schema, transactions, and case reads

**Files:** Create `J/FinresolveApplication.java`, `J/config/DatabaseConfiguration.java`, `J/db/{Database.java,MigrationRunner.java}`, `J/cases/{CaseRecord.java,CaseRepository.java}`, `J/categories/CategoryValues.java`, `J/imports/{BatchRecord.java,ImportRowProblem.java}`, `backend/src/main/resources/application.yml`, `backend/src/main/resources/db/migration/V001__initial.sql`, `T/db/DatabaseTest.java`, `T/support/TestDatabase.java`.

**Interfaces:** `Database.read(Supplier<T>): T` and `Database.write(Supplier<T>): T` manage transactions through the same transaction-aware JdbcTemplate; callbacks never return live result sets. `CaseRepository.find(UUID): Optional<CaseRecord>`, `page(int page,int size,Source source): Page`, `snapshot(): List<CaseRecord>`, `count(): long`, `insert(CaseRecord): void`, `replaceAnalyst(CaseRecord): void`. `Page(List<CaseRecord> items,int page,int size,long totalElements)` is nested in the repository; nullable source means no filter. Repository methods run inside caller transactions.

`CaseRecord` components: `UUID id`, `Source source`, `CategoryValues categories`, `String narrative`, nullable `LocalDate receivedDate`, nullable `String company/companyResponse`, `Instant createdAt/updatedAt`, nullable nested `Provenance`, nullable nested `Notes`, `List<UUID> referenceCaseIds`. `Source={CFPB,ANALYST}`. `Provenance(UUID batchId,String complaintId,String archiveUrl,String sourceFileName,LocalDate periodStart,LocalDate periodEnd,String csvSha256)`; `Notes(String handlingNotes,Outcome outcomeAssessment,String evaluationNotes)`; `Outcome` has the four contract values. `CategoryValues(String product,String subProduct,String issue,String subIssue)`. `ImportRowProblem(int recordNumber,String field,String code,String message)`. `BatchRecord(UUID id,String archiveUrl,String sourceFileName,LocalDate periodStart,LocalDate periodEnd,String csvSha256,Instant importedAt,int processed,int imported,int duplicates,int rejected,List<ImportRowProblem> errors)` retains replayable original results.

- [ ] **Step 1: Write `DatabaseTest` assertions.** With a fresh file-backed database: PRAGMA foreign_keys=1, busy_timeout=5000, journal_mode=wal; one migration version survives a close/reopen; inserted case/notes/references and UTC timestamps survive restart unchanged. Foreign-key violations, self-reference, duplicate complaint IDs, references owned by CFPB, and incompatible source/notes/provenance writes fail. A deliberately failing migration records neither DDL changes nor version. An independent SQLite connection holding a write lock causes the application write to fail after its busy timeout and the HTTP mapping to return 503 DATABASE_BUSY. Existing migrations with changed checksums fail startup. Use `@TempDir`, not in-memory SQLite.
- [ ] **Step 2: Run** `cd backend && ./mvnw -Dtest=DatabaseTest test`; expect missing database classes/schema failures.
- [ ] **Step 3: Implement schema and repositories.** Create `schema_versions(version,checksum,applied_at)` and the four specified tables; add indexes for complaint ID, stable page ordering, and references. Add source CHECKs and triggers for cross-table source ownership; services enforce the final mandatory analyst note within the same transaction. Persist row errors in a batch JSON column so replay reproduces the report. Migration runner uses a fixed ordered resource list, each migration and its version row in one transaction. Configure `FINRESOLVE_DB_PATH` default `data/finresolve.db` relative to `backend/`, create parent directories, log the absolute path once at startup, and never reseed. One connection serializes transactions; activate PRAGMAs before migrations, with auto-initialization disabled. Use prepared SQL, stable `created_at DESC,id ASC` ordering, long arithmetic for offsets, and deterministic reference order.
- [ ] **Step 4: Run** `cd backend && ./mvnw -Dtest=DatabaseTest test`; expect PASS, including reopening the same file without data loss.
- [ ] **Step 5: Commit Task 2 paths** with `feat: persist cases and version SQLite migrations`.

## Task 3: Normalize input, tokenize text, and validate sample categories

**Files:** Create `J/categories/{CategoryRepository.java,CategoryService.java}`, `J/similarity/{EnglishTokenizer.java,TextRules.java}`, `J/cases/{ComplaintInput.java,InputRules.java}`, `J/http/BusinessException.java`, `backend/src/main/resources/similarity/stopwords-en-v1.txt`, `T/categories/CategoryServiceTest.java`, `T/similarity/EnglishTokenizerTest.java`, `T/cases/InputRulesTest.java`.

**Interfaces:** `EnglishTokenizer.tokens(String): List<String>`; `TextRules.ALGORITHM_VERSION="tfidf-cosine-v1"`. `CategoryRepository.list(): List<CategoryValues>` reads CFPB tuples only. `CategoryService.canonicalize(CategoryValues): CategoryValues` validates against the current catalog and returns canonical labels, throwing a typed business error on mismatch/empty catalog. `InputRules.normalizeCategory(String): String` trims/collapses category whitespace (null/blank becomes null); `InputRules.complaint(ComplaintInput): ComplaintInput` validates/normalizes shape and meaningful narrative without catalog membership. `ComplaintInput(CategoryValues categories,String narrative,LocalDate receivedDate,String company,String companyResponse)`. `BusinessException(int status,String code,String message,List<Problem> fieldErrors)` with nested `Problem(String field,String code,String message)` carries safe Indonesian errors.

- [ ] **Step 1: Write tests with these fixed assertions:**

```java
assertThat(tokenizer.tokens("THE transfer XX xxx 123 a account")).containsExactly("transfer", "account");
assertThat(tokenizer.tokens("fee fee refund")).containsExactly("fee", "fee", "refund");
// Repeat under Turkish default locale, restoring the original locale afterward.
assertThat(tokenizer.tokens("INVALID TRANSFER")).containsExactly("invalid", "transfer");
assertThat(normalizeCategory(" Checking   account ")).isEqualTo("Checking account");
```

Test `CategoryServiceTest.independentOptionalBranchesAreValid`: tuples `(P,A,I,X)` and `(P,B,I,Y)` allow `(P,A,I,Y)`; subproduct of another product and subissue of another product/issue fail. Case/spacing variants resolve to the first source canonical label; null sorts before a string in the catalog; ANALYST rows add no tuples. `InputRulesTest` rejects narrative `"the 123 XX"`, narrative length 50,001, labels 501, companyResponse 2,001; accepts 50,000 characters with meaningful tokens and optional blanks as null. Measure limits in Unicode code points rather than splitting surrogate pairs.

- [ ] **Step 2: Run** `cd backend && ./mvnw -Dtest=CategoryServiceTest,EnglishTokenizerTest,InputRulesTest test`; expect missing implementation failures.
- [ ] **Step 3: Implement normalization and validators.** Tokenize Unicode letter/digit runs after `Locale.ROOT` lowercase; discard numeric-only tokens, code-point length one, `x{2,}`, and the committed fixed stopword set. File contents, one word per line: `a an and are as at be been being but by for from had has have he her hers him his i if in into is it its me my no not of on or our ours she so that the their theirs them they this to us was we were will with you your yours`. Preserve raw imported narrative; trim analyst/query narrative. Collapse Unicode whitespace in category labels; canonical identity is lowercase normalized text, labels use the first accepted CFPB spelling across the catalog. Structural import validation rejects child values without their required parent but never consults the old catalog. `CategoryService` is invoked inside the caller's read/write transaction so catalog validation and case operations see one snapshot.
- [ ] **Step 4: Run the Step 2 command**; expect all pass, including catalog consistency and locale tests.
- [ ] **Step 5: Commit Task 3 paths** with `feat: validate complaints and normalize sample categories`.

## Task 4: Expose health, categories, case reads, and consistent HTTP errors

**Files:** Create `J/http/{ApiExceptionHandler.java,ApiErrorController.java,ApiMapper.java,WebConfiguration.java,RequestRules.java}`, `J/cases/{CaseReadService.java,CasesController.java}`, `J/categories/CategoriesController.java`, `J/http/SystemController.java`, `T/http/ReadApiTest.java`, `T/http/ErrorApiTest.java`; modify application configuration.

**Interfaces:** Controllers implement Task 1 generated interfaces; `CaseReadService.get(UUID): CaseRecord` and `list(int,int,Source): CaseRepository.Page` wrap repository reads. `ApiMapper.caseDetail(CaseRecord): CaseDetail`, `casePage(CaseRepository.Page): CasePage`, `categories(List<CategoryValues>): Categories`, `error(BusinessException,Instant): ApiError`. `RequestRules` applies source-schema presence/unknown/null rules before DTO binding; it reads the source contract rather than defining duplicate DTOs. Remaining `CasesApi` mutation methods are temporary explicit 503 `SERVICE_UNAVAILABLE` implementations until Task 8 (never generated default stubs).

- [ ] **Step 1: Write HTTP assertions.** `ReadApiTest` checks `/api/health` equals `{"status":"UP","database":"UP"}`, empty categories and page metadata, CFPB detail provenance plus present `analystNotes:null`, and null-first category ordering. `ErrorApiTest` checks unknown UUID→404, malformed UUID/source/page/size→400, unknown route→404, wrong method→405, wrong content type→415, unavailable DB→503. Assert exactly the four ApiError keys and `fieldErrors:[]` for global errors.

```java
// Use random-port HTTP tests and a real temporary SQLite file.
assertThat(listAtPage(Integer.MAX_VALUE).items()).isEmpty();
assertThat(listWithSource("CFPB' OR 1=1 --").statusCode()).isEqualTo(400);
assertThat(allowedPreflight().statusCode()).isEqualTo(204);
```

Also assert preflight has no body, allowed origin headers include GET/POST/PUT/OPTIONS and Content-Type/Accept, disallowed origins have no allow-origin header, and `Location` is exposed. Error bodies and captured logs must not contain SQL, the database path, stacktrace, or a sentinel narrative.

- [ ] **Step 2: Run** `cd backend && ./mvnw -Dtest=ReadApiTest,ErrorApiTest test`; expect missing routes/mappings failures.
- [ ] **Step 3: Implement the HTTP boundary.** Bind `127.0.0.1:8080` and ensure generated paths have `/api` exactly once (test the source-contract server URL against Spring mappings); allow configured extra origins without wildcard. Disable request-body logging. Configure strict unknown-field rejection, ISO calendar dates and UTC Z timestamps, and inclusion of response null properties. Enforce request required/nullable rules from the contract, particularly nonnullable optional collections/numbers, before generated defaults hide explicit null. Use a scoped JSON request advice and metadata handling; preserve Bean Validation for field limits. Map framework errors including invalid multipart, route/method/media failures, and upload limits into generated ApiError; map SQLite busy/locked to 503 DATABASE_BUSY and unexpected failures to 500 INTERNAL_ERROR. Health executes a database probe; omit filesystem details from its response. Validate full canonical UUID syntax, enum and pagination before repository access; reject abbreviated UUID forms that Java UUID parsing alone might accept; out-of-range pages remain empty without offset overflow.
- [ ] **Step 4: Run the Step 2 command**; expect PASS with schema-shaped error bodies and correct CORS.
- [ ] **Step 5: Commit Task 4 paths** with `feat: expose local read APIs and structured HTTP errors`.

## Task 5: Import CFPB CSV atomically with replayable batch reports

**Files:** Create `J/imports/{ImportMetadataValue.java,ParsedImport.java,CfpbCsvParser.java,ImportRepository.java,ImportService.java,ImportsController.java}`, `T/imports/{CfpbCsvParserTest.java,ImportServiceTest.java,ImportApiTest.java}`, `backend/src/test/resources/fixtures/cfpb-synthetic.csv`; modify `J/http/ApiMapper.java`.

**Interfaces:** `ImportMetadataValue(String archiveUrl,String sourceFileName,LocalDate periodStart,LocalDate periodEnd)` normalized after validation. `CfpbCsvParser.parse(InputStream,ImportMetadataValue): ParsedImport` returns materialized bounded records and per-record errors without writing. `ParsedImport(String csvSha256,int processed,List<Row> rows,List<ImportRowProblem> errors,int rejected)`; nested `Row(int recordNumber,String complaintId,ComplaintInput complaint)` contains valid rows before duplicate classification. `ImportRepository.findReplay(String sha256,ImportMetadataValue): Optional<BatchRecord>`, `insert(BatchRecord): void`, `existingComplaintIds(): Set<String>`. `ImportService.importCsv(InputStream,ImportMetadataValue): Result`, nested `Result(BatchRecord batch,boolean replayed)`; mapper produces generated ImportReport. Controller returns 201 new batch or 200 replay.

- [ ] **Step 1: Write parser, service, and HTTP tests.** Pin this classification and replay behavior:

```java
assertThat(report.processed()).isEqualTo(report.imported()+report.duplicates()+report.rejected());
assertThat(replay.batchId()).isEqualTo(first.batchId());
assertThat(replay.importedAt()).isEqualTo(first.importedAt());
assertThat(replay.replayed()).isTrue();
assertThat(databaseCaseCountAfterReplay).isEqualTo(databaseCaseCountBeforeReplay);
```

Test BOM, multiline/quoted commas, both date formats, nullable missing optional columns, exact preserved narrative bytes as decoded text, positive digit IDs longer than JS safe integers, first valid duplicate retained, invalid duplicate rejected before dedupe, optional blank dates, out-of-period/invalid dates, invalid structural categories, and novel valid categories. Assert 21 rejected records produce 20 errors, record numbers count logical records, and all-invalid CSV creates a 201 report with imported=0. Header-only, missing/duplicate mandatory headers, broken quote at EOF, invalid UTF-8, 5,001 records, or oversized fields/file leave both case and batch counts unchanged. Metadata URL is never fetched. Same bytes with trimmed-identical metadata replay; changed metadata creates a new duplicate-counting batch.

`ImportApiTest` checks file size 20 MiB accepted structurally, 20 MiB+1→413; request total 21 MiB+1→413; 5,001 records→400 IMPORT_LIMIT_EXCEEDED; missing parts/invalid metadata→400 and metadata text/plain→415. At 4,999 cases, two concurrent valid two-row imports both fail atomically with 409; two concurrent one-row imports yield one success and one 409, total 5,000. Use distinct IDs/metadata so replay cannot mask concurrency.

- [ ] **Step 2: Run** `cd backend && ./mvnw -Dtest=CfpbCsvParserTest,ImportServiceTest,ImportApiTest test`; expect missing importer failures.
- [ ] **Step 3: Implement parse and validation.** Use Commons CSV plus a strict UTF-8 decoder and BOM handling; hash original bytes, enforce byte/record bounds while reading, then validate all file structure before entering a write transaction. Required header matching is exact. Per-row validation uses API lengths, meaningful narrative rules, and structural parents; preserve narrative text rather than tokenized/trimmed replacement. Reject header duplicates as ambiguous file structure. Metadata requires HTTPS URI ≤2,048 characters, CSV basename ≤255 with no slash/backslash, and ordered valid dates. Never dereference the supplied URL.
- [ ] **Step 4: Implement batch persistence.** In one `Database.write`, check the composite replay key (hash plus all normalized metadata), validate rows before checking duplicate IDs against database and earlier valid records, count only unique new cases against 5,000, insert cases and original report atomically, and generate UUIDs/UTC times from injectable suppliers/Clock. Replay returns original counts/errors/time without touching cases. Keep at most 20 safe row errors in the report while retaining the full rejected count. Catch lock errors at the HTTP boundary.
- [ ] **Step 5: Run the Step 2 command**; expect PASS, including rollback, concurrency, multipart, and post-import category expansion.
- [ ] **Step 6: Commit Task 5 paths** with `feat: import CFPB CSV with atomic reports and replay detection`.

## Task 6: Prepare the traceable offline demo sample

**Files:** Create `J/sample/{PrepareSample.java,SampleSelector.java,SampleManifest.java}`, `T/sample/SampleSelectorTest.java`, `data/cfpb/{sample.csv,manifest.json}`. Modify `.gitignore`, `backend/pom.xml`, and `README.md` only to introduce the preparation command.

**Interfaces:** `SampleSelector.select(InputStream csv,Writer sample): Selection` streams source CSV and writes only the nine application columns in source-header naming; `Selection(List<String> selectedIds,int scanned,int selected)` records selection. `PrepareSample.main(String[]): void` accepts `--output-dir`, downloads only the fixed approved archive, hashes the complete archive, selects its CSV entry, writes the sample plus manifest, and exits nonzero with a clear reason for zero candidates. Expose through `cd backend && ./mvnw -Pprepare-sample exec:java -Dexec.args="--output-dir ../data/cfpb"` with pinned exec-maven-plugin 3.6.3.

- [ ] **Step 1: Write `SampleSelectorTest`.** Synthetic ZIP/CSV fixtures yield IDs in original order, no more than 500 unique selected IDs, nonblank product/issue/narrative, and only the two approved products. Verify fewer than 500 stays smaller, zero fails without replacing prior artifacts, excluded location/ZIP/tags never reach output, and multiline narratives survive re-import through Task 5's parser. A repeated run on identical archive bytes yields identical sample bytes and IDs; retrieval timestamp may differ in the manifest.
- [ ] **Step 2: Run** `cd backend && ./mvnw -Dtest=SampleSelectorTest test`; expect missing selector failures.
- [ ] **Step 3: Implement streaming preparation.** Fix URL to `https://files.consumerfinance.gov/f/documents/CCDB_Export_8_November_2024_through_December_2024.zip`, period `2024-11-01`–`2024-12-31`, products exactly `Checking or savings account` and `Money transfer, virtual currency, or money service`, limit 500, first occurrence of an eligible unique ID. Use Java HttpClient and ZipFile, downloading to an ignored temporary archive; no traversal extraction. Manifest records URL, archive/entry names, period, exact filters, limit, retrieval UTC time, archive/sample SHA-256, selected count and IDs. Write via temporary files and atomically replace artifacts after success. Use LF/UTF-8 and deterministic CSV column order. If full inspection reveals material incompatibility, report evidence and revise the design before changing filters or source.
- [ ] **Step 4: Verify** `cd backend && ./mvnw -Dtest=SampleSelectorTest test`, then run the preparation command once as an explicit network operation outside tests. Verify manifest checksums and selected IDs against output and import the sample into a disposable DB. Expect selected count 1–500 and traceable provenance; keep original narratives and disclose no additional anonymization claim.
- [ ] **Step 5: Commit sample, manifest, and Task 6 code** with `feat: prepare a traceable fixed CFPB demo sample`; never stage the downloaded ZIP.

## Task 7: Implement deterministic explainable CBR scoring

**Files:** Create `J/similarity/{SimilaritySettings.java,CategoryScorer.java,TfidfSnapshot.java,SimilarityEngine.java,SimilarityResult.java}`, `T/similarity/{CategoryScorerTest.java,TfidfSnapshotTest.java,SimilarityEngineTest.java,SimilaritySettingsTest.java}`; modify `application.yml`.

**Interfaces:** `CategoryScorer.score(CategoryValues query,CategoryValues candidate,SimilaritySettings): CategoryScore`, nested `CategoryScore(double score,List<FieldMatch> fields)` and `FieldMatch(String field,String queryValue,String caseValue,String status,double configuredWeight,double effectiveWeight,double contribution)`. `TfidfSnapshot.fit(List<CaseRecord>,EnglishTokenizer): TfidfSnapshot`; `compare(String query,UUID caseId): TextScore`, nested `TextScore(double score,List<Term> sharedTerms)` and `Term(String term,double contribution)`. `SimilarityEngine.rank(ComplaintInput,List<CaseRecord>,int limit,SimilaritySettings): List<SimilarityResult>`; `SimilarityResult(CaseRecord caseRecord,double score,double categoryScore,double textScore,List<FieldMatch> categoryExplanation,List<Term> sharedTerms)`. Settings expose category/text weights, four field weights, threshold, default/max limit and algorithm version.

- [ ] **Step 1: Write numerical tests:**

```java
// All required categories match; absent query subcategories are unused.
assertThat(categoryScore).isCloseTo(1.0, within(1e-12));
// Query has all four fields; candidate is missing subProduct only.
assertThat(missingSubProductScore).isCloseTo(0.85, within(1e-12));
// Corpus: ["transfer fee", "transfer refund"].
assertThat(idfTransfer).isCloseTo(1.0, within(1e-12));
assertThat(idfFee).isCloseTo(Math.log(3.0/2.0)+1.0, within(1e-12));
assertThat(identicalNonzeroCosine).isCloseTo(1.0, within(1e-12));
assertThat(outOfVocabularyCosine).isZero();
assertThat(zeroNormCosine).isZero();
```

Assert repeated TF counts, IDF never fitted on query, optional null/null→NOT_USED rather than MATCH, provided query/missing case→MISSING_IN_CASE, four explanations in prescribed order, and contribution sum equals category score. Direct scorer fixtures pin total 0.50 included, just below excluded, and total from category alone with text=0 excluded. Equal totals sort by UUID string ascending; no rounding before ordering. Eleven tied shared terms return exactly ten in lexical order; ranking includes candidates from other products and both sources. Settings tests reject NaN/infinity, negative weights, combined weights not summing to 1, field weights not summing to 1, zero product+issue denominator, threshold outside [0,1], and default/max limit outside 1–20 or default>max. Use tolerance 1e-9 for weight-sum validation only.

- [ ] **Step 2: Run** `cd backend && ./mvnw -Dtest=CategoryScorerTest,TfidfSnapshotTest,SimilarityEngineTest,SimilaritySettingsTest test`; expect absent scoring classes failures.
- [ ] **Step 3: Implement independent scorers.** Fit smoothed IDF only on every case document; use sparse frequency maps and L2-normalized vectors, ignoring query OOV tokens. Bound roundoff of cosine to [0,1] without decimal rounding. Shared terms are normalized-weight products ordered contribution descending then token ascending, limited to 10. Rank with configured `categoryWeight*categoryScore+textWeight*textScore`, text-overlap gate, then UUID string tie-break. Configure defaults 0.4/0.6, 0.35/0.15/0.35/0.15, 0.50, 5/20. Fail application startup on invalid configuration; configuration cannot raise contract/demo ceilings. Set immutable version `tfidf-cosine-v1` tied to the committed stopword version.
- [ ] **Step 4: Run the Step 2 command**; expect PASS with deterministic scores and no HTTP/database dependency in scorer tests.
- [ ] **Step 5: Commit Task 7 paths** with `feat: score and explain category and TF-IDF case similarity`.

## Task 8: Retain and replace analyst cases transactionally

**Files:** Create `J/cases/{RetainCommand.java,RetentionService.java}`, `T/cases/RetentionServiceTest.java`, `T/http/RetentionApiTest.java`; modify `CasesController.java`, `ApiMapper.java`, and repository mutation implementation if needed.

**Interfaces:** `RetainCommand(ComplaintInput complaint,CaseRecord.Notes notes,List<UUID> referenceCaseIds)`; `RetentionService.create(RetainCommand): CaseRecord`, `replace(UUID,RetainCommand): CaseRecord`. Consume InputRules, CategoryService, Database, and CaseRepository from earlier tasks. Controller maps generated RetainCaseRequest to this internal command and replaces Task 4's temporary mutation errors.

- [ ] **Step 1: Write retention assertions:**

```java
assertThat(created.source()).isEqualTo(Source.ANALYST);
assertThat(created.provenance()).isNull();
assertThat(replaced.id()).isEqualTo(created.id());
assertThat(replaced.createdAt()).isEqualTo(created.createdAt());
assertThat(replaced.updatedAt()).isAfter(created.updatedAt());
assertThat(replaced.referenceCaseIds()).isEmpty(); // omitted on full PUT
assertThat(replaced.company()).isNull();           // omitted on full PUT
```

Use an advancing fixed Clock. Assert notes and references survive restart, CFPB references and ANALYST references work, missing/duplicate/self references fail without changing prior data, notes blank/10,001 and >20 references fail, all four outcome enums succeed, missing ID update→404, CFPB update→409, empty catalog→409, and POST repeated twice creates two IDs. At capacity, new retention→409 but replacement succeeds. Two writers competing for the last slot produce one complete analyst case and one 409 with no orphan notes/references. Retain/import competition also never exceeds 5,000. HTTP tests pin 201 and exact relative Location `/api/cases/{id}`, 200 PUT, omitted optional fields cleared, explicit-null referenceCaseIds→400, and unknown source/id/provenance fields→400 UNKNOWN_FIELD.

- [ ] **Step 2: Run** `cd backend && ./mvnw -Dtest=RetentionServiceTest,RetentionApiTest test`; expect mutation endpoints/services to fail the behavior assertions.
- [ ] **Step 3: Implement validation and writes.** Validate shape first; check catalog, target source/existence, references, and capacity in the same serialized write transaction as case/notes/reference changes. On PUT preserve ID/createdAt and replace every mutable field, notes, and the full reference set. Validate the complete command before destructive reference replacement; any later SQL error still rolls back. Require an existing catalog and do not extend it from analyst cases. Do not automatically retry POST.
- [ ] **Step 4: Run the Step 2 command**; expect PASS including restart, capacity race, rollback and complete replacement.
- [ ] **Step 5: Commit Task 8 paths** with `feat: retain and replace analyst cases atomically`.

## Task 9: Search a fresh consistent snapshot without retaining queries

**Files:** Create `J/similarity/{SearchService.java,SearchOutcome.java,SearchController.java}`, `T/http/SearchApiTest.java`, `T/similarity/SearchSnapshotTest.java`; modify `ApiMapper.java`.

**Interfaces:** `SearchService.search(ComplaintInput query,Integer limit): SearchOutcome` (null Java argument means omitted/default only after RequestRules has rejected explicit JSON null). `SearchOutcome(String status,String message,List<SimilarityResult> results,int limit,int corpusSize,SimilaritySettings configuration,List<String> warnings)`. Controller implements generated `SearchApi.searchCases` and mapper produces generated SearchResponse/SearchMatch with complete CaseDetail results.

- [ ] **Step 1: Write assertions for all three 200 statuses.** With no corpus, meaningful shape-valid input using unknown categories yields EMPTY_KNOWLEDGE_BASE and `results=[]`, returnedCount=0, corpusSize=0; meaningless narrative still yields 400. With corpus and OOV text, NO_RELEVANT_CASES; with matching text, MATCHES, count equals result length and ≤limit. Limit omitted uses 5; 0/21/null fails 400. Responses carry actual nondefault configured weights/threshold, four category explanations, ≤10 shared terms and complete provenance/analyst notes.

```java
assertThat(caseCountAfterSearch).isEqualTo(caseCountBeforeSearch);
assertThat(nextSearch.corpusSize()).isEqualTo(previousSize + 1); // committed retain
assertThat(nextSearch.results()).extracting(r -> r.caseRecord().id()).contains(retainedId);
```

Repeat after import and update to prove no stale TF-IDF data. `SearchSnapshotTest.calculatesAfterReleasingConnection` uses a blocking test scorer hook/engine dependency: after snapshot materialization, a concurrent read/write completes while scoring is paused. A write committed during scoring must not mix new categories/cases into that existing response; the next search sees it.

- [ ] **Step 2: Run** `cd backend && ./mvnw -Dtest=SearchApiTest,SearchSnapshotTest test`; expect missing search endpoint/service failures.
- [ ] **Step 3: Implement search orchestration.** Validate shape/tokens and limit, materialize corpus and catalog validation together in one short Database.read, skip category membership only for an empty corpus, release the transaction, then fit and rank the immutable copied records. Never prefilter products or persist the query. Set returnedCount from results. Use Indonesian status messages from the contract examples and warning: `Kemiripan dan respons historis bukan bukti keberhasilan penanganan. Bobot serta ambang awal belum dikalibrasi.` Include configured algorithm version and true snapshot corpus size.
- [ ] **Step 4: Run the Step 2 command**; expect PASS including connection-release and next-search visibility assertions.
- [ ] **Step 5: Commit Task 9 paths** with `feat: expose explainable search over fresh case snapshots`.

## Task 10: Verify the live contract and generated client in CI

**Files:** Create `tools/test-server.mjs`, `tests/contract/{schema.mjs,api.test.mjs,client.test.ts}`, `packages/api-client/src/client.ts`, `.github/workflows/ci.yml`; extend package scripts and `tools/openapi/generation.test.mjs`. Modify client package exports if needed.

**Interfaces:** `startTestServer(): Promise<{baseUrl:string,stop():Promise<void>}>` launches the packaged jar with temporary DB and random/allocated loopback port, waits on health with bounded retries, and always stops/removes temporary resources. `assertResponse(operationId:string,status:number,headers:Headers,body:unknown): void` resolves source-contract response schema and asserts headers/status/body through Ajv2020. `createClient(baseUrl:string,fetchApi?:typeof fetch)` returns generated tag API instances sharing Configuration. `ApiClientError` exposes status, code and fieldErrors from generated ApiError; non-JSON/network failures use TRANSPORT_ERROR. This adapter contains no handwritten per-endpoint fetch/DTOs and retries no mutations.

- [ ] **Step 1: Write live tests.** Run the generated client against a temporary real backend: health → empty search → multipart import → replay → categories → filtered/paged list → CFPB detail → matching/nonmatching search → retain → ANALYST detail → search again → full PUT → structured error. Validate every raw response against its operation/status schema before generated deserialization. Assert JSON metadata part content type, intact CSV bytes, strings for date/date-time and large complaint IDs, present null fields, correct case discriminator, 201 Location, replay 200, no `/api/api` paths, and no unexpected properties. Explicitly test null collections/numbers, malformed dates/JSON, invalid references, CFPB mutation, all documented error status classes, preflight, size and capacity errors using local fixtures. For 500/503, use controlled test-profile fault injection/local lock fixtures rather than production debug endpoints.

`generation.test.mjs` creates an extra file, deletes a file, and edits a file in temporary generated trees: each must fail api:check; a normal check leaves tracked/untracked workspace state unchanged. Client transport tests pin non-JSON proxy error, network failure and no implicit POST retry.

- [ ] **Step 2: Run** `npm run test:contract`; expect absent runner/adapter or contract behavior failures, then preserve those tests while fixing implementation.
- [ ] **Step 3: Implement contract test tooling and adapter.** Load schemas directly from `docs/api/openapi.json`; preserve nullable unions, const and oneOf while resolving references. Compile client to `dist/` with strict TypeScript and Node-compatible ESM imports before Node's test runner consumes it. Implement byte/path comparison using temporary directories outside the working tree, including newly introduced files. Contract checks are additional to semantic scorer/service tests, not substitutes.
- [ ] **Step 4: Add CI and run final automated gate.** On a clean checkout use Java 21, Node 24, `npm ci`, then `npm run verify`: validate contract/examples, generate/check temporary Java and committed client outputs, compile generated interfaces with implementations, run all Maven tests, typecheck client and negative type assertions, package jar and run live contract/client tests. CI uses no archive download and no committed runtime DB. Pin GitHub Actions to full commit SHAs resolved from their official repositories when creating the workflow. Run `git diff --check` and confirm the verification command does not change generated tracked files. Expect all checks pass from a second clean checkout as well.
- [ ] **Step 5: Commit Task 10 paths** with `test: verify generated client and live OpenAPI contract in CI`.

## Task 11: Document operations and provide an honest evaluation worksheet

**Files:** Modify `README.md`, `docs/api/README.md`, `docs/api/generation.md`; create `docs/evaluation/{README.md,queries.csv,judgments.csv}`. Preserve unrelated user README edits.

**Interfaces:** Document the exact commands already implemented. `queries.csv` columns: `queryId,product,subProduct,issue,subIssue,narrative,scenario`. `judgments.csv`: `queryId,rank,caseId,relevant,reviewer,notes`; five rows per query, missing result slots marked irrelevant during completed evaluation. No labels or quality scores are fabricated.

- [ ] **Step 1: Define the acceptance walkthrough in `docs/evaluation/README.md`.** A new checkout can prepare or use committed sample, start the backend with temporary SQLite at port 8080, import via multipart curl, search, retain with a reference, restart, verify detail persistence, replace with omitted optional values, and inspect generated client without installing Next.js. Include exact expected HTTP statuses and compare import counts to manifest, allowing record rejections only with a documented explanation.
- [ ] **Step 2: Update operating documentation.** Cover Java/Node requirements, Maven Wrapper, `api:generate`/`api:check`, run/test commands, sample provenance/checksums, offline repeat demo, curl examples, database path override/absolute startup log, backup while stopped, WAL sidecars, limits, score configuration and invalid-startup behavior. Default bind is loopback; document `SERVER_ADDRESS=0.0.0.0` only for devcontainer forwarding. Explain sample-catalog restrictions, manual retain, analyst assessments, last-write-wins PUT, non-idempotent POST, initial uncalibrated threshold, and historical responses as context. Update outdated README framework/database decisions without erasing unrelated content; update implementation status only after the automated and smoke checks actually pass.
- [ ] **Step 3: Create at least ten unlabelled English evaluation queries using actual imported category labels.** Include close relevant wording, paraphrases with low lexical overlap, omitted optional fields, independent valid optional branches, cross-product overlap, numeric/redacted filler mixed with meaningful text, and unrelated narrative. For each query record returned count and reviewer labels of top five. Document `precision@5 = relevant returned slots / 5`, with missing slots counted irrelevant; report per-query values and mean only after a person judges them. Keep synthetic test fixtures explicitly separate from the CFPB demo sample. Do not claim relevance quality before human assessment.
- [ ] **Step 4: Execute the documented walkthrough and smoke verification.** Run `npm run verify` once after final changes, build via `cd backend && ./mvnw clean verify`, run the packaged jar on port 8080 with a temporary DB, and execute import/search/retain/restart checks. Confirm the retained case survives and root/generated checks leave the working tree unchanged except intended documentation. Document actual results and any limitations; stop the server and remove only temporary artifacts.
- [ ] **Step 5: Commit Task 11 paths** with `docs: document local demo and manual relevance evaluation`.

## Self-review and execution handoff

Coverage checked against every design section: contract generation/ownership (1, 10), SQLite/migrations/source invariants (2), normalization/catalog/input (3), HTTP/errors/CORS/read pagination (4), import limits/validation/replay/atomicity (5), traceable fixed sample (6), formula/token explanations/configuration (7), retention/replacement/references (8), snapshots/statuses/no automatic storage (9), full client/HTTP/CI verification (10), and operating documentation/manual precision@5 (11). The five Review Focus conditions each have explicit tests in their owning tasks. Shared signatures use the same records and prefixes throughout; generated public signatures remain sourced from generation rather than a handwritten competing API.

No implementation or generation has been performed as part of this plan. Task 1 must prove generator compatibility before dependent work; Task 6 must inspect the full source before claiming the demo sample is usable. Neither is asserted to have passed during planning. Human relevance labels remain a later human action, supported by the delivered worksheet.

Please review this plan and choose an execution method. Recommended: **Subagent-driven**, because eleven tasks cross generated Java/TypeScript boundaries, transaction invariants, and numerical behavior that benefit from independent task reviews. **Native** remains available for lower cost and quicker execution with one whole-branch review at the end. Wait for the user's review and choice before implementation, as required by the writing-plans skill's Execution Handoff and the approved design's explicit stop.
