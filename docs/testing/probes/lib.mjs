// Shared evidence recorder for the 2026-10-28 exploratory backend session.
// The scripts in this directory only call the public HTTP API and read the
// disposable SQLite file directly for atomicity checks.
import { writeFile } from 'node:fs/promises';

// Sticky across sessions: any FAIL wins over BLOCKED, and BLOCKED wins over PASS.
let probeStatus = 0;
function recordStatus(code) {
    if (code === 1) probeStatus = 1;
    else if (code === 2 && probeStatus === 0) probeStatus = 2;
    process.exitCode = probeStatus;
}

const INTERESTING_HEADERS = [
    'content-type',
    'location',
    'allow',
    'access-control-allow-origin',
    'access-control-allow-methods',
    'access-control-allow-headers',
    'access-control-expose-headers',
    'vary',
];

function snapshot(value, depth = 0) {
    if (value === null || value === undefined) return value;
    if (typeof value === 'string') {
        return value.length <= 800 ? value : `${value.slice(0, 800)}...[${value.length} chars]`;
    }
    if (typeof value !== 'object') return value;
    if (depth >= 5) return '[depth limit]';
    if (Array.isArray(value)) {
        const head = value.slice(0, 8).map((item) => snapshot(item, depth + 1));
        return value.length <= 8 ? head : [...head, `...[${value.length - 8} more]`];
    }
    const out = {};
    for (const [key, item] of Object.entries(value)) out[key] = snapshot(item, depth + 1);
    return out;
}

function headerEvidence(headers) {
    const out = {};
    for (const name of INTERESTING_HEADERS) {
        const value = headers.get(name);
        if (value !== null) out[name] = value;
    }
    return out;
}

function bodySummary(body) {
    if (body === undefined) return undefined;
    if (body instanceof FormData) {
        const parts = [];
        for (const [name, value] of body) {
            parts.push({
                name,
                filename: value?.name,
                type: value?.type,
                size: value?.size,
            });
        }
        return { multipart: parts };
    }
    if (typeof body === 'string') {
        try {
            return { json: snapshot(JSON.parse(body)) };
        } catch {
            return { text: body.length <= 500 ? body : `${body.slice(0, 500)}...[${body.length} chars]` };
        }
    }
    return { binary: true };
}

export function createSession({ name, baseUrl, output, meta = {} }) {
    const startedAt = new Date().toISOString();
    const results = [];
    const requests = [];

    async function request(path, { method = 'GET', body, raw, headers = {}, timeout = 60000 } = {}) {
        const started = performance.now();
        const init = { method, headers: { ...headers }, signal: AbortSignal.timeout(timeout) };
        if (raw !== undefined) init.body = raw;
        else if (body instanceof FormData) init.body = body;
        else if (body !== undefined) {
            init.headers['Content-Type'] ??= 'application/json';
            init.body = JSON.stringify(body);
        }
        const response = await fetch(baseUrl + path, init);
        const text = await response.text();
        let data;
        try {
            data = text ? JSON.parse(text) : null;
        } catch {
            data = text;
        }
        const evidence = {
            method,
            path,
            requestBody: bodySummary(raw ?? body),
            status: response.status,
            ms: Math.round(performance.now() - started),
            headers: headerEvidence(response.headers),
            body: snapshot(data),
            bytes: text.length,
        };
        requests.push(evidence);
        return { status: response.status, headers: response.headers, data, ms: evidence.ms, text };
    }

    async function check(id, hypothesis, fn) {
        const first = requests.length;
        const started = performance.now();
        try {
            const value = await fn();
            const result = value?.result ?? 'PASS';
            const detail = value?.detail ?? value ?? null;
            results.push({
                id,
                hypothesis,
                result,
                durationMs: Math.round(performance.now() - started),
                detail: snapshot(detail),
                requests: requests.slice(first),
            });
            if (result === 'FAIL') recordStatus(1);
            else if (result === 'BLOCKED') recordStatus(2);
            console.log(`${result} ${id}`);
            return value;
        } catch (error) {
            recordStatus(1);
            results.push({
                id,
                hypothesis,
                result: 'FAIL',
                durationMs: Math.round(performance.now() - started),
                error: error.message,
                requests: requests.slice(first),
            });
            console.log(`FAIL ${id}: ${error.message}`);
            return null;
        }
    }

    async function observation(id, hypothesis, detail) {
        results.push({
            id,
            hypothesis,
            result: 'OBSERVATION',
            durationMs: 0,
            detail: snapshot(detail),
            requests: [],
        });
        console.log(`OBSERVATION ${id}`);
    }

    async function blocked(id, hypothesis, detail) {
        recordStatus(2);
        results.push({
            id,
            hypothesis,
            result: 'BLOCKED',
            durationMs: 0,
            detail: snapshot(detail),
            requests: [],
        });
        console.log(`BLOCKED ${id}`);
    }

    async function save(extra = {}) {
        const completedAt = new Date().toISOString();
        const summary = {
            scenarios: results.length,
            passed: results.filter((result) => result.result === 'PASS').length,
            failed: results.filter((result) => result.result === 'FAIL').length,
            blocked: results.filter((result) => result.result === 'BLOCKED').length,
            observations: results.filter((result) => result.result === 'OBSERVATION').length,
            httpRequests: requests.length,
        };
        recordStatus(summary.failed > 0 ? 1 : summary.blocked > 0 ? 2 : 0);
        const document = {
            session: name,
            startedAt,
            completedAt,
            baseUrl,
            ...meta,
            summary,
            results,
            requests,
            ...extra,
            exitCode: probeStatus,
        };
        await writeFile(output, `${JSON.stringify(document, null, 2)}\n`);
        console.log(JSON.stringify({ output, ...summary, exitCode: probeStatus }));
        return document;
    }

    return { request, check, observation, blocked, save, results, requests };
}
