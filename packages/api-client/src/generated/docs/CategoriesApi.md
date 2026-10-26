# CategoriesApi

All URIs are relative to *http://localhost:8080/api*

| Method | HTTP request | Description |
|------------- | ------------- | -------------|
| [**getCategories**](CategoriesApi.md#getcategories) | **GET** /categories | Mengambil pilihan kategori dari sampel CFPB |



## getCategories

> Categories getCategories()

Mengambil pilihan kategori dari sampel CFPB

Tuple unik, diurutkan product/subProduct/issue/subIssue menaik dengan perbandingan Unicode, null sebelum string. items&#x3D;[] jika belum ada impor. Frontend menghitung pilihan dependen dari tuple; null bukan pilihan wajib.

### Example

```ts
import {
  Configuration,
  CategoriesApi,
} from '';
import type { GetCategoriesRequest } from '';

async function example() {
  console.log("🚀 Testing  SDK...");
  const api = new CategoriesApi();

  try {
    const data = await api.getCategories();
    console.log(data);
  } catch (error) {
    console.error(error);
  }
}

// Run the test
example().catch(console.error);
```

### Parameters

This endpoint does not need any parameter.

### Return type

[**Categories**](Categories.md)

### Authorization

No authorization required

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: `application/json`


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **200** | Katalog sampel; bukan seluruh taksonomi CFPB. |  -  |
| **405** | METHOD_NOT_ALLOWED untuk metode yang tidak tersedia pada route. |  -  |
| **500** | INTERNAL_ERROR: pesan umum tanpa detail internal. |  -  |
| **503** | DATABASE_BUSY atau SERVICE_UNAVAILABLE. Jangan otomatis mengulang POST retensi karena request awal mungkin sudah tersimpan. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)

