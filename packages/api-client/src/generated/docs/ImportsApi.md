# ImportsApi

All URIs are relative to *http://localhost:8080/api*

| Method | HTTP request | Description |
|------------- | ------------- | -------------|
| [**importCfpb**](ImportsApi.md#importcfpb) | **POST** /imports/cfpb | Mengimpor CSV CFPB dengan metadata sumber |



## importCfpb

> ImportReport importCfpb(file, metadata)

Mengimpor CSV CFPB dengan metadata sumber

Sinkron, bukan job background. file maksimal 20 MiB, request multipart maksimal 21 MiB, maksimal 5.000 record. Validasi file sebelum transaksi; error file membatalkan semua penulisan. Error per-record dilaporkan pada respons sukses. Replay dikenali dari SHA-256 byte CSV beserta seluruh nilai metadata setelah trim. Duplicate ID dalam file atau database dilewati setelah validasi record. CSV sumber mendukung BOM, kutipan dan newline. Content-Type file text/csv atau application/octet-stream; metadata harus application/json.

### Example

```ts
import {
  Configuration,
  ImportsApi,
} from '';
import type { ImportCfpbRequest } from '';

async function example() {
  console.log("🚀 Testing  SDK...");
  const api = new ImportsApi();

  const body = {
    // Blob | CSV UTF-8 maksimal 20 MiB (20.971.520 byte), maksimal 5.000 record data; header CFPB wajib.
    file: BINARY_DATA_HERE,
    // ImportMetadata
    metadata: ...,
  } satisfies ImportCfpbRequest;

  try {
    const data = await api.importCfpb(body);
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
| **file** | `Blob` | CSV UTF-8 maksimal 20 MiB (20.971.520 byte), maksimal 5.000 record data; header CFPB wajib. | [Defaults to `undefined`] |
| **metadata** | [ImportMetadata](ImportMetadata.md) |  | [Defaults to `undefined`] |

### Return type

[**ImportReport**](ImportReport.md)

### Authorization

No authorization required

### HTTP request headers

- **Content-Type**: `multipart/form-data`
- **Accept**: `application/json`


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **200** | Replay file+metadata yang identik. Angka merupakan laporan batch asli. |  -  |
| **201** | Batch baru tersimpan; lihat rejected/imported sebelum menganggap semua baris berhasil. |  -  |
| **400** | Request tidak valid. code: VALIDATION_ERROR, MALFORMED_REQUEST, INVALID_CATEGORY, INVALID_REFERENCE, INVALID_CSV, atau IMPORT_LIMIT_EXCEEDED. |  -  |
| **405** | METHOD_NOT_ALLOWED untuk metode yang tidak tersedia pada route. |  -  |
| **409** | IMMUTABLE_CFPB_CASE, EMPTY_CATEGORY_CATALOG, atau KNOWLEDGE_BASE_LIMIT_EXCEEDED. Tidak ada penulisan parsial. |  -  |
| **413** | PAYLOAD_TOO_LARGE: file &gt;20 MiB atau multipart total &gt;21 MiB. |  -  |
| **415** | UNSUPPORTED_MEDIA_TYPE: tipe body atau part metadata tidak didukung. |  -  |
| **500** | INTERNAL_ERROR: pesan umum tanpa detail internal. |  -  |
| **503** | DATABASE_BUSY atau SERVICE_UNAVAILABLE. Jangan otomatis mengulang POST retensi karena request awal mungkin sudah tersimpan. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)

