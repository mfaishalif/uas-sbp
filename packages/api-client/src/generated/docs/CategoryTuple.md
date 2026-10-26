
# CategoryTuple


## Properties

Name | Type
------------ | -------------
`product` | string
`subProduct` | string
`issue` | string
`subIssue` | string

## Example

```typescript
import type { CategoryTuple } from ''

// TODO: Update the object below with actual values
const example = {
  "product": null,
  "subProduct": null,
  "issue": null,
  "subIssue": null,
} satisfies CategoryTuple

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as CategoryTuple
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


