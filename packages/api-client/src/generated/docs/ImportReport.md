
# ImportReport

processed=imported+duplicates+rejected. Baris fisik kosong di luar quoted field tidak dihitung sebagai record. Satu error pertama per record ditolak; maksimal 20 contoh. errorsTruncated iff rejected>20. HTTP 200 replay mengembalikan batch dan hitungan asli, replayed=true; hitungan bukan tambahan baru. Header-only atau header-plus-blank-lines CSV ditolak INVALID_CSV. File dengan semua record invalid tetap membuat laporan batch 201 dengan imported=0.

## Properties

Name | Type
------------ | -------------
`batchId` | string
`replayed` | boolean
`metadata` | [ImportMetadata](ImportMetadata.md)
`csvSha256` | string
`processed` | number
`imported` | number
`duplicates` | number
`rejected` | number
`errors` | [Array&lt;ImportRowError&gt;](ImportRowError.md)
`errorsTruncated` | boolean
`importedAt` | string

## Example

```typescript
import type { ImportReport } from ''

// TODO: Update the object below with actual values
const example = {
  "batchId": null,
  "replayed": null,
  "metadata": null,
  "csvSha256": null,
  "processed": null,
  "imported": null,
  "duplicates": null,
  "rejected": null,
  "errors": null,
  "errorsTruncated": null,
  "importedAt": null,
} satisfies ImportReport

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as ImportReport
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


