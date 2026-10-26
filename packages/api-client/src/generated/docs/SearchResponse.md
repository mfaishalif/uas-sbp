
# SearchResponse

MATCHES iff results nonkosong. Dua status lainnya memiliki results=[], returnedCount=0. EMPTY_KNOWLEDGE_BASE iff corpusSize=0. returnedCount=results.length; hasil <= limit. Tidak ada pagination pencarian. Skor 0–1 bukan probabilitas keberhasilan.

## Properties

Name | Type
------------ | -------------
`status` | string
`message` | string
`results` | [Array&lt;SearchMatch&gt;](SearchMatch.md)
`returnedCount` | number
`limit` | number
`corpusSize` | number
`_configuration` | [SearchConfiguration](SearchConfiguration.md)
`warnings` | Array&lt;string&gt;

## Example

```typescript
import type { SearchResponse } from ''

// TODO: Update the object below with actual values
const example = {
  "status": null,
  "message": null,
  "results": null,
  "returnedCount": null,
  "limit": null,
  "corpusSize": null,
  "_configuration": null,
  "warnings": null,
} satisfies SearchResponse

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as SearchResponse
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


