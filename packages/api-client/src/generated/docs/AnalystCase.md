
# AnalystCase


## Properties

Name | Type
------------ | -------------
`id` | string
`source` | string
`product` | string
`subProduct` | string
`issue` | string
`subIssue` | string
`receivedDate` | string
`company` | string
`companyResponse` | string
`createdAt` | string
`updatedAt` | string
`narrative` | string
`provenance` | null
`analystNotes` | [AnalystNotes](AnalystNotes.md)
`referenceCaseIds` | Set&lt;string&gt;

## Example

```typescript
import type { AnalystCase } from ''

// TODO: Update the object below with actual values
const example = {
  "id": null,
  "source": null,
  "product": null,
  "subProduct": null,
  "issue": null,
  "subIssue": null,
  "receivedDate": null,
  "company": null,
  "companyResponse": null,
  "createdAt": null,
  "updatedAt": null,
  "narrative": null,
  "provenance": null,
  "analystNotes": null,
  "referenceCaseIds": null,
} satisfies AnalystCase

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as AnalystCase
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


