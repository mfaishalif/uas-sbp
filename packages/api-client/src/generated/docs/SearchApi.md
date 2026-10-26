# SearchApi

All URIs are relative to *http://localhost:8080/api*

| Method | HTTP request | Description |
|------------- | ------------- | -------------|
| [**searchCases**](SearchApi.md#searchcases) | **POST** /search | Mencari preseden tanpa menyimpan query |



## searchCases

> SearchResponse searchCases(searchRequest)

Mencari preseden tanpa menyimpan query

Validasi tipe/batas/token terlebih dahulu. Jika korpus kosong, kembalikan EMPTY_KNOWLEDGE_BASE tanpa validasi keanggotaan kategori; jika ada data, validasi kategori. Skor dihitung dari snapshot semua kasus, termasuk ANALYST; urut score turun lalu id naik. Hanya score&gt;&#x3D;threshold dan textScore&gt;0 dikembalikan. Respons memuat konfigurasi aktual server; frontend tidak mengasumsikan nilai default tetap.

### Example

```ts
import {
  Configuration,
  SearchApi,
} from '';
import type { SearchCasesRequest } from '';

async function example() {
  console.log("🚀 Testing  SDK...");
  const api = new SearchApi();

  const body = {
    // SearchRequest
    searchRequest: {"product":"Checking or savings account","subProduct":"Checking account","issue":"Managing an account","subIssue":null,"narrative":"My transfer is missing from my checking account.","limit":5},
  } satisfies SearchCasesRequest;

  try {
    const data = await api.searchCases(body);
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
| **searchRequest** | [SearchRequest](SearchRequest.md) |  | |

### Return type

[**SearchResponse**](SearchResponse.md)

### Authorization

No authorization required

### HTTP request headers

- **Content-Type**: `application/json`
- **Accept**: `application/json`


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **200** | Semua status pencarian adalah respons sukses. |  -  |
| **400** | Request tidak valid. code: VALIDATION_ERROR, MALFORMED_REQUEST, INVALID_CATEGORY, INVALID_REFERENCE, INVALID_CSV, atau IMPORT_LIMIT_EXCEEDED. |  -  |
| **405** | METHOD_NOT_ALLOWED untuk metode yang tidak tersedia pada route. |  -  |
| **415** | UNSUPPORTED_MEDIA_TYPE: tipe body atau part metadata tidak didukung. |  -  |
| **500** | INTERNAL_ERROR: pesan umum tanpa detail internal. |  -  |
| **503** | DATABASE_BUSY atau SERVICE_UNAVAILABLE. Jangan otomatis mengulang POST retensi karena request awal mungkin sudah tersimpan. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)

