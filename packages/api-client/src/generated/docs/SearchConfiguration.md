
# SearchConfiguration

Konfigurasi server yang benar-benar dipakai. Mode baseline menghasilkan tfidf-cosine-v1; mode phrases menghasilkan tfidf-phrases-v2 setelah normalisasi frasa domain sebelum stopword.

## Properties

Name | Type
------------ | -------------
`categoryWeight` | number
`textWeight` | number
`categoryFieldWeights` | [SearchConfigurationCategoryFieldWeights](SearchConfigurationCategoryFieldWeights.md)
`threshold` | number
`requiresTextOverlap` | boolean
`algorithmVersion` | string

## Example

```typescript
import type { SearchConfiguration } from ''

// TODO: Update the object below with actual values
const example = {
  "categoryWeight": null,
  "textWeight": null,
  "categoryFieldWeights": null,
  "threshold": null,
  "requiresTextOverlap": null,
  "algorithmVersion": null,
} satisfies SearchConfiguration

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as SearchConfiguration
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


