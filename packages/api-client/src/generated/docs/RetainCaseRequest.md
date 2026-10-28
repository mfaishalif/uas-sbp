
# RetainCaseRequest

POST membuat kasus ANALYST. PUT mengganti lengkap: field opsional yang dihilangkan dihapus menjadi null atau []; bukan PATCH. referenceCaseIds null ditolak. Duplikat UUID identik maupun yang hanya berbeda kapitalisasi ditolak DUPLICATE; UUID uppercase tunggal tetap diterima. Semua referensi wajib ada dan bukan ID kasus itu sendiri. Teks dengan U+0000 atau lone UTF-16 surrogate ditolak VALIDATION_ERROR/INVALID_FORMAT sebelum penulisan. source, id, provenance, timestamps tidak boleh dikirim.

## Properties

Name | Type
------------ | -------------
`product` | string
`subProduct` | string
`issue` | string
`subIssue` | string
`narrative` | string
`receivedDate` | string
`company` | string
`companyResponse` | string
`handlingNotes` | string
`outcomeAssessment` | string
`evaluationNotes` | string
`referenceCaseIds` | Set&lt;string&gt;

## Example

```typescript
import type { RetainCaseRequest } from ''

// TODO: Update the object below with actual values
const example = {
  "product": null,
  "subProduct": null,
  "issue": null,
  "subIssue": null,
  "narrative": null,
  "receivedDate": null,
  "company": null,
  "companyResponse": null,
  "handlingNotes": null,
  "outcomeAssessment": null,
  "evaluationNotes": null,
  "referenceCaseIds": null,
} satisfies RetainCaseRequest

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as RetainCaseRequest
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


