
# ApiError


## Properties

Name | Type
------------ | -------------
`code` | string
`message` | string
`fieldErrors` | [Array&lt;FieldError&gt;](FieldError.md)
`timestamp` | string

## Example

```typescript
import type { ApiError } from ''

// TODO: Update the object below with actual values
const example = {
  "code": null,
  "message": null,
  "fieldErrors": null,
  "timestamp": null,
} satisfies ApiError

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as ApiError
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


