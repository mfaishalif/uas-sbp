
# TermContribution

Perkalian bobot TF-IDF ternormalisasi query dan kasus. Maksimal 10 token; jumlahnya dapat kurang dari textScore bila ada lebih dari 10 token bersama.

## Properties

Name | Type
------------ | -------------
`term` | string
`contribution` | number

## Example

```typescript
import type { TermContribution } from ''

// TODO: Update the object below with actual values
const example = {
  "term": null,
  "contribution": null,
} satisfies TermContribution

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as TermContribution
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


