import Ajv2020 from 'ajv/dist/2020.js';
import addFormats from 'ajv-formats';

import { loadContract } from '../../tools/openapi/validate.mjs';

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

const DOCUMENT_ID = 'urn:finresolve:live-contract:0.1.0';
const contract = await loadContract();

function resolvePointer(pointer) {
    if (typeof pointer !== 'string' || !pointer.startsWith('#/')) {
        throw new Error(`Only local contract references are supported: ${pointer}`);
    }
    const tokens = pointer
        .slice(2)
        .split('/')
        .map((token) => token.replaceAll('~1', '/').replaceAll('~0', '~'));
    let current = contract;
    for (const token of tokens) {
        if (current === null || typeof current !== 'object' || !(token in current)) {
            throw new Error(`Unresolved contract reference: ${pointer}`);
        }
        current = current[token];
    }
    return current;
}

function resolve(value) {
    return value && typeof value === 'object' && typeof value.$ref === 'string'
        ? resolvePointer(value.$ref)
        : value;
}

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
ajv.addSchema({ ...contract, $id: DOCUMENT_ID }, DOCUMENT_ID);

const operations = new Map();
for (const [pathName, pathItem] of Object.entries(contract.paths ?? {})) {
    const resolvedPath = resolve(pathItem);
    for (const [method, operation] of Object.entries(resolvedPath ?? {})) {
        if (!HTTP_METHODS.has(method) || !operation?.operationId) {
            continue;
        }
        operations.set(operation.operationId, {
            method: method.toUpperCase(),
            path: pathName,
            operation,
        });
    }
}

const validatorCache = new Map();

function validatorFor(schema) {
    if (validatorCache.has(schema)) {
        return validatorCache.get(schema);
    }
    const validate = ajv.compile({
        $schema: 'https://json-schema.org/draft/2020-12/schema',
        ...schema,
        components: contract.components,
    });
    validatorCache.set(schema, validate);
    return validate;
}

function assertSchema(schema, value, label) {
    const validate = validatorFor(resolve(schema));
    if (!validate(value)) {
        throw new Error(
            `${label} does not match its contract schema:\n${ajv.errorsText(validate.errors, {
                separator: '\n',
            })}`,
        );
    }
}

/**
 * Assert one live response against the source contract's status, declared headers, and JSON body.
 *
 * <p>The caller passes the raw status/headers/body before generated deserialization. Unknown
 * operation IDs or undeclared statuses fail loudly rather than skipping validation.
 */
export function assertResponse(operationId, status, headers, body) {
    const entry = operations.get(operationId);
    if (!entry) {
        throw new Error(`Unknown operationId: ${operationId}`);
    }
    const response = resolve(entry.operation.responses?.[String(status)]);
    if (!response) {
        throw new Error(
            `${entry.method} ${entry.path} (${operationId}) does not declare status ${status}`,
        );
    }

    for (const [name, headerSchema] of Object.entries(response.headers ?? {})) {
        const resolvedHeader = resolve(headerSchema);
        const value = headers.get(name) ?? headers.get(name.toLowerCase());
        if (resolvedHeader.required && value == null) {
            throw new Error(
                `${operationId} ${status} is missing the required ${name} response header`,
            );
        }
        if (value != null && resolvedHeader.schema) {
            assertSchema(
                resolvedHeader.schema,
                value,
                `${operationId} ${status} response header ${name}`,
            );
        }
    }

    const media = response.content?.['application/json'];
    if (!media) {
        if (body != null && body !== '') {
            throw new Error(`${operationId} ${status} declares no JSON response body`);
        }
        return body;
    }
    if (!media.schema) {
        throw new Error(`${operationId} ${status} JSON response has no schema`);
    }
    if (body === undefined || body === null) {
        throw new Error(`${operationId} ${status} requires a JSON response body`);
    }
    assertSchema(media.schema, body, `${operationId} ${status} response body`);
    return body;
}

export function assertErrorSchema(body) {
    assertSchema({ $ref: '#/components/schemas/ApiError' }, body, 'ApiError response body');
    return body;
}

export { contract, operations };
