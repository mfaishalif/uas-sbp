# CasesApi

All URIs are relative to *http://localhost:8080/api*

| Method | HTTP request | Description |
|------------- | ------------- | -------------|
| [**getCase**](CasesApi.md#getcase) | **GET** /cases/{id} | Detail kasus dan asal informasinya |
| [**listCases**](CasesApi.md#listcases) | **GET** /cases | Daftar kasus dengan pagination |
| [**replaceAnalystCase**](CasesApi.md#replaceanalystcase) | **PUT** /cases/{id} | Mengganti lengkap kasus ANALYST |
| [**retainCase**](CasesApi.md#retaincaseoperation) | **POST** /cases | Menyimpan kasus ANALYST secara manual |



## getCase

> CaseDetail getCase(id)

Detail kasus dan asal informasinya

### Example

```ts
import {
  Configuration,
  CasesApi,
} from '';
import type { GetCaseRequest } from '';

async function example() {
  console.log("🚀 Testing  SDK...");
  const api = new CasesApi();

  const body = {
    // string | UUID internal kasus; bukan Complaint ID CFPB.
    id: 38400000-8cf0-11bd-b23e-10b96e4ef00d,
  } satisfies GetCaseRequest;

  try {
    const data = await api.getCase(body);
    console.log(data);
  } catch (error) {
    console.error(error);
  }
}

// Run the test
example().catch(console.error);
```

### Parameters


| Name | Type | Description  | Notes |
|------------- | ------------- | ------------- | -------------|
| **id** | `string` | UUID internal kasus; bukan Complaint ID CFPB. | [Defaults to `undefined`] |

### Return type

[**CaseDetail**](CaseDetail.md)

### Authorization

No authorization required

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: `application/json`


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **200** | Detail sesuai diskriminator source. Nullable field selalu hadir. |  -  |
| **400** | Request tidak valid. code: VALIDATION_ERROR, MALFORMED_REQUEST, INVALID_CATEGORY, INVALID_REFERENCE, INVALID_CSV, atau IMPORT_LIMIT_EXCEEDED. |  -  |
| **404** | CASE_NOT_FOUND untuk UUID valid yang tidak ada; ROUTE_NOT_FOUND untuk route tidak dikenal. UUID salah format adalah 400. |  -  |
| **405** | METHOD_NOT_ALLOWED untuk metode yang tidak tersedia pada route. |  -  |
| **500** | INTERNAL_ERROR: pesan umum tanpa detail internal. |  -  |
| **503** | DATABASE_BUSY atau SERVICE_UNAVAILABLE. Jangan otomatis mengulang POST retensi karena request awal mungkin sudah tersimpan. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


## listCases

> CasePage listCases(page, size, source)

Daftar kasus dengan pagination

Urutan tetap createdAt menurun lalu id menaik. Halaman di luar jangkauan mengembalikan items&#x3D;[] dengan metadata total yang benar. totalPages&#x3D;ceil(totalElements/size), 0 jika kosong. hasNext&#x3D;(page+1)&lt;totalPages. Filter diterapkan sebelum menghitung total. Halaman bukan snapshot lintas-request.

### Example

```ts
import {
  Configuration,
  CasesApi,
} from '';
import type { ListCasesRequest } from '';

async function example() {
  console.log("🚀 Testing  SDK...");
  const api = new CasesApi();

  const body = {
    // number (optional)
    page: 56,
    // number (optional)
    size: 56,
    // 'CFPB' | 'ANALYST' | Hilangkan untuk semua sumber; string kosong atau enum tidak dikenal ditolak. (optional)
    source: source_example,
  } satisfies ListCasesRequest;

  try {
    const data = await api.listCases(body);
    console.log(data);
  } catch (error) {
    console.error(error);
  }
}

// Run the test
example().catch(console.error);
```

### Parameters


| Name | Type | Description  | Notes |
|------------- | ------------- | ------------- | -------------|
| **page** | `number` |  | [Optional] [Defaults to `0`] |
| **size** | `number` |  | [Optional] [Defaults to `20`] |
| **source** | `CFPB`, `ANALYST` | Hilangkan untuk semua sumber; string kosong atau enum tidak dikenal ditolak. | [Optional] [Defaults to `undefined`] [Enum: CFPB, ANALYST] |

### Return type

[**CasePage**](CasePage.md)

### Authorization

No authorization required

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: `application/json`


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **200** | Ringkasan tanpa narasi, provenance lengkap, dan catatan. |  -  |
| **400** | Request tidak valid. code: VALIDATION_ERROR, MALFORMED_REQUEST, INVALID_CATEGORY, INVALID_REFERENCE, INVALID_CSV, atau IMPORT_LIMIT_EXCEEDED. |  -  |
| **405** | METHOD_NOT_ALLOWED untuk metode yang tidak tersedia pada route. |  -  |
| **500** | INTERNAL_ERROR: pesan umum tanpa detail internal. |  -  |
| **503** | DATABASE_BUSY atau SERVICE_UNAVAILABLE. Jangan otomatis mengulang POST retensi karena request awal mungkin sudah tersimpan. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


## replaceAnalystCase

> CaseDetail replaceAnalystCase(id, retainCaseRequest)

Mengganti lengkap kasus ANALYST

Hanya ANALYST. Field opsional yang dihilangkan dihapus menjadi null atau []; referensi lama diganti seluruhnya. createdAt dipertahankan, updatedAt diperbarui. Tanpa ETag/version lock; penulisan terakhir menang untuk demo satu analis. Bukan upsert: ID yang belum ada menghasilkan 404.

### Example

```ts
import {
  Configuration,
  CasesApi,
} from '';
import type { ReplaceAnalystCaseRequest } from '';

async function example() {
  console.log("🚀 Testing  SDK...");
  const api = new CasesApi();

  const body = {
    // string | UUID internal kasus; bukan Complaint ID CFPB.
    id: 38400000-8cf0-11bd-b23e-10b96e4ef00d,
    // RetainCaseRequest
    retainCaseRequest: {product=Checking or savings account, subProduct=Checking account, issue=Managing an account, subIssue=null, narrative=My transfer is missing from my checking account., receivedDate=null, company=null, companyResponse=null, handlingNotes=Reviewed transfer evidence and requested a trace., outcomeAssessment=UNKNOWN, evaluationNotes=Awaiting confirmation from the consumer., referenceCaseIds=[11111111-1111-4111-8111-111111111111]},
  } satisfies ReplaceAnalystCaseRequest;

  try {
    const data = await api.replaceAnalystCase(body);
    console.log(data);
  } catch (error) {
    console.error(error);
  }
}

// Run the test
example().catch(console.error);
```

### Parameters


| Name | Type | Description  | Notes |
|------------- | ------------- | ------------- | -------------|
| **id** | `string` | UUID internal kasus; bukan Complaint ID CFPB. | [Defaults to `undefined`] |
| **retainCaseRequest** | [RetainCaseRequest](RetainCaseRequest.md) |  | |

### Return type

[**CaseDetail**](CaseDetail.md)

### Authorization

No authorization required

### HTTP request headers

- **Content-Type**: `application/json`
- **Accept**: `application/json`


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **200** | Detail kasus sesudah penggantian. |  -  |
| **400** | Request tidak valid. code: VALIDATION_ERROR, MALFORMED_REQUEST, INVALID_CATEGORY, INVALID_REFERENCE, INVALID_CSV, atau IMPORT_LIMIT_EXCEEDED. |  -  |
| **404** | CASE_NOT_FOUND untuk UUID valid yang tidak ada; ROUTE_NOT_FOUND untuk route tidak dikenal. UUID salah format adalah 400. |  -  |
| **405** | METHOD_NOT_ALLOWED untuk metode yang tidak tersedia pada route. |  -  |
| **409** | IMMUTABLE_CFPB_CASE, EMPTY_CATEGORY_CATALOG, atau KNOWLEDGE_BASE_LIMIT_EXCEEDED. Tidak ada penulisan parsial. |  -  |
| **415** | UNSUPPORTED_MEDIA_TYPE: tipe body atau part metadata tidak didukung. |  -  |
| **500** | INTERNAL_ERROR: pesan umum tanpa detail internal. |  -  |
| **503** | DATABASE_BUSY atau SERVICE_UNAVAILABLE. Jangan otomatis mengulang POST retensi karena request awal mungkin sudah tersimpan. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


## retainCase

> CaseDetail retainCase(retainCaseRequest)

Menyimpan kasus ANALYST secara manual

Tidak idempoten. Mengulang request membuat kasus baru. Tidak membutuhkan pencarian sebelumnya. Validasi kategori/referensi dan simpan kasus+catatan+referensi secara atomik. Katalog kosong atau kapasitas &gt;5.000 adalah 409.

### Example

```ts
import {
  Configuration,
  CasesApi,
} from '';
import type { RetainCaseOperationRequest } from '';

async function example() {
  console.log("🚀 Testing  SDK...");
  const api = new CasesApi();

  const body = {
    // RetainCaseRequest
    retainCaseRequest: {"product":"Checking or savings account","subProduct":"Checking account","issue":"Managing an account","subIssue":null,"narrative":"My transfer is missing from my checking account.","receivedDate":null,"company":null,"companyResponse":null,"handlingNotes":"Reviewed transfer evidence and requested a trace.","outcomeAssessment":"UNKNOWN","evaluationNotes":"Awaiting confirmation from the consumer.","referenceCaseIds":["11111111-1111-4111-8111-111111111111"]},
  } satisfies RetainCaseOperationRequest;

  try {
    const data = await api.retainCase(body);
    console.log(data);
  } catch (error) {
    console.error(error);
  }
}

// Run the test
example().catch(console.error);
```

### Parameters


| Name | Type | Description  | Notes |
|------------- | ------------- | ------------- | -------------|
| **retainCaseRequest** | [RetainCaseRequest](RetainCaseRequest.md) |  | |

### Return type

[**CaseDetail**](CaseDetail.md)

### Authorization

No authorization required

### HTTP request headers

- **Content-Type**: `application/json`
- **Accept**: `application/json`


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **201** | Kasus tersimpan; Location menunjuk detail kasus. |  * Location - Path relatif /api/cases/{id}. Diekspos melalui CORS. <br>  |
| **400** | Request tidak valid. code: VALIDATION_ERROR, MALFORMED_REQUEST, INVALID_CATEGORY, INVALID_REFERENCE, INVALID_CSV, atau IMPORT_LIMIT_EXCEEDED. |  -  |
| **405** | METHOD_NOT_ALLOWED untuk metode yang tidak tersedia pada route. |  -  |
| **409** | IMMUTABLE_CFPB_CASE, EMPTY_CATEGORY_CATALOG, atau KNOWLEDGE_BASE_LIMIT_EXCEEDED. Tidak ada penulisan parsial. |  -  |
| **415** | UNSUPPORTED_MEDIA_TYPE: tipe body atau part metadata tidak didukung. |  -  |
| **500** | INTERNAL_ERROR: pesan umum tanpa detail internal. |  -  |
| **503** | DATABASE_BUSY atau SERVICE_UNAVAILABLE. Jangan otomatis mengulang POST retensi karena request awal mungkin sudah tersimpan. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)

