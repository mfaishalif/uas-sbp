# SystemApi

All URIs are relative to *http://localhost:8080/api*

| Method | HTTP request | Description |
|------------- | ------------- | -------------|
| [**getHealth**](SystemApi.md#gethealth) | **GET** /health | Memeriksa layanan dan database |



## getHealth

> Health getHealth()

Memeriksa layanan dan database

### Example

```ts
import {
  Configuration,
  SystemApi,
} from '';
import type { GetHealthRequest } from '';

async function example() {
  console.log("🚀 Testing  SDK...");
  const api = new SystemApi();

  try {
    const data = await api.getHealth();
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

[**Health**](Health.md)

### Authorization

No authorization required

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: `application/json`


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **200** | Layanan dan koneksi database tersedia. |  -  |
| **405** | METHOD_NOT_ALLOWED untuk metode yang tidak tersedia pada route. |  -  |
| **500** | INTERNAL_ERROR: pesan umum tanpa detail internal. |  -  |
| **503** | DATABASE_BUSY atau SERVICE_UNAVAILABLE. Jangan otomatis mengulang POST retensi karena request awal mungkin sudah tersimpan. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)

