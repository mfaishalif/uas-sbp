# Pinned OpenAPI 3.1 meta-schema

`openapi-3.1-schema.json` is the official OpenAPI Specification 3.1 meta-schema used by
`tools/openapi/validate.mjs`.

- Source: <https://spec.openapis.org/oas/3.1/schema/2022-10-07>
- Retrieved: 2026-10-26
- SHA-256: `da01ba28852cac0de53893797cb8d1942bc3b05084f526dcc216717dec314ed0`
- `$id`: `https://spec.openapis.org/oas/3.1/schema/2022-10-07`

The file is copied byte-for-byte from the source. Do not edit it. The meta-schema describes
the OpenAPI document envelope; request and response examples are validated separately
against their resolved JSON Schema 2020-12 schemas.

At validation time `validate.mjs` clones this schema in memory and rewrites the envelope's
`$dynamicRef: "#meta"` to `$ref: "#/$defs/schema"`. Ajv 8 cannot resolve that dynamic anchor
without a dialect scope, so the rewrite lets Ajv validate the document envelope against the
same pinned definitions. The committed resource stays unchanged; named component schemas
are still compiled and validated separately as JSON Schema 2020-12.
