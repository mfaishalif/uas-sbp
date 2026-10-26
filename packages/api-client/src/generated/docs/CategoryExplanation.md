
# CategoryExplanation

Selalu empat field dalam urutan product, subProduct, issue, subIssue. NOT_USED jika query kosong; effectiveWeight=0. Selain itu effectiveWeight adalah bobot setelah normalisasi field aktif. contribution=effectiveWeight untuk MATCH, 0 untuk lainnya; jumlah contribution adalah categoryScore.

## Properties

Name | Type
------------ | -------------
`field` | string
`queryValue` | string
`caseValue` | string
`status` | string
`configuredWeight` | number
`effectiveWeight` | number
`contribution` | number

## Example

```typescript
import type { CategoryExplanation } from ''

// TODO: Update the object below with actual values
const example = {
  "field": null,
  "queryValue": null,
  "caseValue": null,
  "status": null,
  "configuredWeight": null,
  "effectiveWeight": null,
  "contribution": null,
} satisfies CategoryExplanation

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as CategoryExplanation
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


