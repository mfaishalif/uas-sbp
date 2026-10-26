
# ArchiveProvenance


## Properties

Name | Type
------------ | -------------
`complaintId` | string
`importBatchId` | string
`archiveUrl` | string
`sourceFileName` | string
`periodStart` | string
`periodEnd` | string
`csvSha256` | string

## Example

```typescript
import type { ArchiveProvenance } from ''

// TODO: Update the object below with actual values
const example = {
  "complaintId": null,
  "importBatchId": null,
  "archiveUrl": null,
  "sourceFileName": null,
  "periodStart": null,
  "periodEnd": null,
  "csvSha256": null,
} satisfies ArchiveProvenance

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as ArchiveProvenance
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


