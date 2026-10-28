
# ImportMetadata

periodEnd harus >= periodStart. Semua field wajib. Teks dengan U+0000 atau lone UTF-16 surrogate ditolak VALIDATION_ERROR/INVALID_FORMAT sebelum import; emoji/pasangan surrogate valid dipertahankan.

## Properties

Name | Type
------------ | -------------
`archiveUrl` | string
`sourceFileName` | string
`periodStart` | string
`periodEnd` | string

## Example

```typescript
import type { ImportMetadata } from ''

// TODO: Update the object below with actual values
const example = {
  "archiveUrl": null,
  "sourceFileName": null,
  "periodStart": null,
  "periodEnd": null,
} satisfies ImportMetadata

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as ImportMetadata
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


