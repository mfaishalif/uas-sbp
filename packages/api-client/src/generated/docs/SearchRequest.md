
# SearchRequest

Subproduk/submasalah boleh dihilangkan atau null; string blank dinormalisasi ke null. Properti lain ditolak. Limit null ditolak. Teks dengan U+0000 atau lone UTF-16 surrogate ditolak VALIDATION_ERROR/INVALID_FORMAT; emoji/pasangan surrogate valid, tab, dan newline tetap mengikuti aturan normalisasi field.

## Properties

Name | Type
------------ | -------------
`product` | string
`subProduct` | string
`issue` | string
`subIssue` | string
`narrative` | string
`limit` | number

## Example

```typescript
import type { SearchRequest } from ''

// TODO: Update the object below with actual values
const example = {
  "product": null,
  "subProduct": null,
  "issue": null,
  "subIssue": null,
  "narrative": null,
  "limit": null,
} satisfies SearchRequest

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as SearchRequest
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


