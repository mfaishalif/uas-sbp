
# SearchMatch


## Properties

Name | Type
------------ | -------------
`_case` | [CaseDetail](CaseDetail.md)
`score` | number
`categoryScore` | number
`textScore` | number
`categoryExplanation` | [Array&lt;CategoryExplanation&gt;](CategoryExplanation.md)
`sharedTerms` | [Array&lt;TermContribution&gt;](TermContribution.md)

## Example

```typescript
import type { SearchMatch } from ''

// TODO: Update the object below with actual values
const example = {
  "_case": null,
  "score": null,
  "categoryScore": null,
  "textScore": null,
  "categoryExplanation": null,
  "sharedTerms": null,
} satisfies SearchMatch

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as SearchMatch
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


