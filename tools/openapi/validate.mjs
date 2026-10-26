import { readFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

import Ajv2020 from 'ajv/dist/2020.js';
import addFormats from 'ajv-formats';

const here = path.dirname(fileURLToPath(import.meta.url));
const repoRoot = path.resolve(here, '..', '..');
const DEFAULT_CONTRACT = path.join(repoRoot, 'docs', 'api', 'openapi.json');
const META_SCHEMA_PATH = path.join(here, 'schema', 'openapi-3.1-schema.json');
const DOCUMENT_ID = 'urn:finresolve:openapi:0.1.0';
const HTTP_METHODS = new Set([
  'get',
  'put',
  'post',
  'delete',
  'options',
  'head',
  'patch',
  'trace',
]);

function pointerEscape(token) {
  return token.replaceAll('~', '~0').replaceAll('/', '~1');
}

function resolvePointer(document, pointer, label) {
  if (!pointer.startsWith('#/')) {
    throw new Error(`${label} points outside the document: ${pointer}`);
  }
  const tokens = pointer
    .slice(2)
    .split('/')
    .map((token) => token.replaceAll('~1', '/').replaceAll('~0', '~'));
  let current = document;
  for (const token of tokens) {
    if (current === null || typeof current !== 'object' || !(token in current)) {
      throw new Error(`${label} has an unresolved reference: ${pointer}`);
    }
    current = current[token];
  }
  return current;
}

function resolveReference(document, value, label) {
  if (value && typeof value === 'object' && typeof value.$ref === 'string') {
    return resolvePointer(document, value.$ref, label);
  }
  return value;
}

function assertReferencesResolve(document) {
  const visit = (node, cursor) => {
    if (Array.isArray(node)) {
      node.forEach((item, index) => visit(item, `${cursor}/${index}`));
      return;
    }
    if (node === null || typeof node !== 'object') {
      return;
    }
    if (typeof node.$ref === 'string') {
      resolvePointer(document, node.$ref, `${cursor}/$ref`);
    }
    for (const [key, value] of Object.entries(node)) {
      visit(value, `${cursor}/${pointerEscape(key)}`);
    }
  };
  visit(document, '#');
}

function assertUniqueOperationIds(document) {
  const seen = new Map();
  for (const [pathName, pathItem] of Object.entries(document.paths ?? {})) {
    const resolved = resolveReference(document, pathItem, `paths/${pathName}`);
    for (const [method, operation] of Object.entries(resolved ?? {})) {
      if (!HTTP_METHODS.has(method)) {
        continue;
      }
      if (!operation || typeof operation !== 'object') {
        continue;
      }
      if (typeof operation.operationId !== 'string' || operation.operationId.length === 0) {
        throw new Error(`${method.toUpperCase()} ${pathName} has no operationId`);
      }
      if (seen.has(operation.operationId)) {
        throw new Error(
          `Duplicate operationId ${operation.operationId}: ${seen.get(operation.operationId)} and ${method.toUpperCase()} ${pathName}`,
        );
      }
      seen.set(operation.operationId, `${method.toUpperCase()} ${pathName}`);
    }
  }
}

function assertApprovedContract(document) {
  if (typeof document.openapi !== 'string' || !/^3\.1\.\d+(-.+)?$/.test(document.openapi)) {
    throw new Error(`Expected an OpenAPI 3.1 document, found ${JSON.stringify(document.openapi)}`);
  }
  if (document.info?.version !== '0.1.0') {
    throw new Error(`Expected contract version 0.1.0, found ${JSON.stringify(document.info?.version)}`);
  }
  const serverUrls = (document.servers ?? []).map((server) => server.url);
  if (!serverUrls.includes('http://localhost:8080/api')) {
    throw new Error(`Expected server URL http://localhost:8080/api, found ${JSON.stringify(serverUrls)}`);
  }
  if (!document.components?.schemas || !document.paths) {
    throw new Error('Contract must define paths and components.schemas');
  }
}

async function createValidator(document) {
  const ajv = new Ajv2020({
    allErrors: true,
    strict: false,
    validateFormats: true,
    allowUnionTypes: true,
  });
  addFormats(ajv);
  ajv.addFormat('binary', true);
  ajv.addFormat('byte', true);
  ajv.addFormat('int32', { type: 'number', validate: () => true });
  ajv.addFormat('int64', { type: 'number', validate: () => true });
  ajv.addFormat('float', { type: 'number', validate: () => true });
  ajv.addFormat('double', { type: 'number', validate: () => true });
  ajv.addFormat('password', { type: 'string', validate: () => true });
  ajv.addFormat('media-range', { type: 'string', validate: () => true });

  const metaSchema = JSON.parse(await readFile(META_SCHEMA_PATH, 'utf8'));

  // The published envelope meta-schema uses `$dynamicRef: "#meta"` for opaque
  // Schema Objects. Ajv cannot resolve that reference unless a dialect anchor is
  // in scope, so materialize the schema's own `#/$defs/schema` anchor for
  // envelope validation. The pinned resource itself stays byte-for-byte intact;
  // each named schema is additionally compiled as JSON Schema 2020-12 below.
  const envelopeSchema = JSON.parse(JSON.stringify(metaSchema));
  const materializeDynamicRefs = (node) => {
    if (Array.isArray(node)) {
      node.forEach(materializeDynamicRefs);
      return;
    }
    if (node === null || typeof node !== 'object') {
      return;
    }
    if (node.$dynamicRef === '#meta') {
      delete node.$dynamicRef;
      node.$ref = '#/$defs/schema';
    }
    Object.values(node).forEach(materializeDynamicRefs);
  };
  materializeDynamicRefs(envelopeSchema);
  ajv.addSchema(envelopeSchema, metaSchema.$id);
  const validateDocument = ajv.getSchema(metaSchema.$id);
  if (!validateDocument) {
    throw new Error('the pinned OpenAPI meta-schema could not be compiled');
  }
  if (!validateDocument(document)) {
    throw new Error(
      `OpenAPI meta-schema validation failed:\n${ajv.errorsText(validateDocument.errors, {
        separator: '\n',
      })}`,
    );
  }

  for (const [name, schema] of Object.entries(document.components?.schemas ?? {})) {
    if (typeof schema === 'object' && schema !== null && !ajv.validateSchema(schema, true)) {
      throw new Error(
        `components.schemas.${name} is not a valid JSON Schema 2020-12 schema:\n${ajv.errorsText(
          ajv.errors,
          { separator: '\n' },
        )}`,
      );
    }
  }

  ajv.addSchema({ ...document, $id: DOCUMENT_ID }, DOCUMENT_ID);

  const schemaValidators = new Map();
  const validateExample = (schema, value, label) => {
    if (!schema) {
      throw new Error(`${label} has no schema`);
    }
    let validate = schemaValidators.get(schema);
    if (!validate) {
      const ref = schema.$ref;
      if (typeof ref === 'string' && ref.startsWith('#/')) {
        validate = ajv.compile({
          $schema: 'https://json-schema.org/draft/2020-12/schema',
          $ref: `${DOCUMENT_ID}${ref}`,
        });
      } else {
        // Inline schemas need to be registered under a stable synthetic id so that
        // their local references and nested components still resolve.
        const inlineId = `urn:finresolve:inline:${schemaValidators.size}`;
        ajv.addSchema(
          { $schema: 'https://json-schema.org/draft/2020-12/schema', $id: inlineId, ...schema },
          inlineId,
        );
        validate = ajv.compile({ $ref: inlineId });
      }
      schemaValidators.set(schema, validate);
    }
    if (!validate(value)) {
      throw new Error(
        `${label} does not match its schema:\n${ajv.errorsText(validate.errors, {
          separator: '\n',
        })}`,
      );
    }
  };

  return { ajv, validateExample };
}

function eachExample(content, callback) {
  if (!content || typeof content !== 'object') {
    return;
  }
  for (const [mediaType, media] of Object.entries(content)) {
    if (media?.example !== undefined) {
      callback(media.schema, media.example, `${mediaType} example`);
    }
    for (const [name, example] of Object.entries(media?.examples ?? {})) {
      const exampleValue = resolveReference(
        media,
        example,
        `${mediaType}/examples/${name}`,
      );
      if (exampleValue?.value !== undefined) {
        callback(
          media.schema,
          exampleValue.value,
          `${mediaType}/examples/${name}/value`,
        );
      }
    }
  }
}

function validateAllExamples(document, validateExample) {
  let validated = 0;
  const check = (schema, value, label) => {
    validateExample(schema, value, label);
    validated += 1;
  };

  for (const [pathName, pathItem] of Object.entries(document.paths ?? {})) {
    const resolvedPath = resolveReference(document, pathItem, `paths/${pathName}`);
    for (const [method, operation] of Object.entries(resolvedPath ?? {})) {
      if (!HTTP_METHODS.has(method) || !operation || typeof operation !== 'object') {
        continue;
      }
      const operationLabel = `${method.toUpperCase()} ${pathName}`;
      const requestBody = resolveReference(
        document,
        operation.requestBody,
        `${operationLabel} requestBody`,
      );
      eachExample(requestBody?.content, (schema, value, label) =>
        check(schema, value, `${operationLabel} requestBody ${label}`),
      );

      for (const [status, response] of Object.entries(operation.responses ?? {})) {
        const resolvedResponse = resolveReference(
          document,
          response,
          `${operationLabel} response ${status}`,
        );
        eachExample(resolvedResponse?.content, (schema, value, label) =>
          check(schema, value, `${operationLabel} response ${status} ${label}`),
        );
      }
    }
  }

  for (const [name, response] of Object.entries(document.components?.responses ?? {})) {
    const resolvedResponse = resolveReference(
      document,
      response,
      `components/responses/${name}`,
    );
    eachExample(resolvedResponse?.content, (schema, value, label) =>
      check(schema, value, `components/responses/${name} ${label}`),
    );
  }

  return validated;
}

export async function loadContract(contractPath = DEFAULT_CONTRACT) {
  return JSON.parse(await readFile(contractPath, 'utf8'));
}

export async function validateContract(contractPath = DEFAULT_CONTRACT) {
  const document = await loadContract(contractPath);
  assertApprovedContract(document);
  assertReferencesResolve(document);
  assertUniqueOperationIds(document);

  const { validateExample } = await createValidator(document);
  const examples = validateAllExamples(document, validateExample);

  return {
    path: contractPath,
    version: document.info.version,
    operations: Object.values(document.paths).flatMap((pathItem) =>
      Object.entries(resolveReference(document, pathItem, 'path'))
        .filter(([method]) => HTTP_METHODS.has(method))
        .map(([, operation]) => operation.operationId),
    ),
    examples,
  };
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try {
    const result = await validateContract();
    console.log(
      `Validated ${result.path}: OpenAPI ${result.version}, ${result.operations.length} operations, ${result.examples} examples.`,
    );
  } catch (error) {
    console.error(`Contract validation failed: ${error.message}`);
    process.exitCode = 1;
  }
}
