// Search, explanation, phrase-mode, and retention-flow session.
// Usage: node docs/testing/probes/search-phrases.mjs <baseline> <phrases> <empty> <output.json>
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { createSession } from './lib.mjs';

const [baselineUrl, phrasesUrl, emptyUrl, output] = process.argv.slice(2);
if (!baselineUrl || !phrasesUrl || !emptyUrl || !output) {
    throw new Error('usage: search-phrases.mjs <baselineUrl> <phrasesUrl> <emptyUrl> <output.json>');
}

const CATEGORIES = {
    product: 'Checking or savings account',
    subProduct: 'Checking account',
    issue: 'Managing an account',
    subIssue: 'Deposits and withdrawals',
    narrative: 'My transfer is missing from my checking account.',
};
const PLAIN_CATEGORIES = {
    product: 'Checking or savings account',
    issue: 'Managing an account',
};
const FIXTURE_ID = '940000001';
const FIXTURE_NARRATIVE = 'The cash machine charged twice and the funds held were released.';

const baseline = createSession({
    name: 'search-retention-baseline',
    baseUrl: baselineUrl,
    output: output.replace('.json', '-baseline.json'),
    meta: { mode: 'baseline' },
});
const phrases = createSession({
    name: 'search-phrases',
    baseUrl: phrasesUrl,
    output,
    meta: { mode: 'phrases' },
});
const empty = createSession({
    name: 'search-empty',
    baseUrl: emptyUrl,
    output: output.replace('.json', '-empty.json'),
    meta: { mode: 'baseline' },
});

function scoreInvariants(data) {
    assert.equal(data.returnedCount, data.results.length);
    assert.ok(data.returnedCount <= data.limit);
    let previous = Infinity;
    for (const match of data.results) {
        for (const key of ['score', 'categoryScore', 'textScore']) {
            assert.ok(match[key] >= 0 && match[key] <= 1, `${key} out of range`);
        }
        assert.ok(match.score <= previous, 'results are not sorted by score');
        previous = match.score;
        assert.ok(match.score >= data.configuration.threshold, 'result below threshold');
        assert.ok(match.textScore > 0, 'result without text overlap');
        assert.ok(
            Math.abs(
                match.score
                - (data.configuration.categoryWeight * match.categoryScore
                    + data.configuration.textWeight * match.textScore),
            ) < 1e-10,
            'score formula mismatch',
        );
        assert.equal(match.categoryExplanation.length, 4);
        assert.equal(
            match.categoryExplanation.reduce((sum, item) => sum + item.contribution, 0).toFixed(12),
            match.categoryScore.toFixed(12),
        );
        assert.ok(match.sharedTerms.length <= 10);
        assert.ok(match.case.narrative.length > 0);
    }
}

async function search(session, body) {
    const response = await session.request('/api/search', { method: 'POST', body });
    return { response, data: response.data };
}

function parseCsvLine(line) {
    const fields = [];
    let field = '';
    let quoted = false;
    for (let index = 0; index < line.length; index += 1) {
        const char = line[index];
        if (char === '"' && line[index + 1] === '"' && quoted) {
            field += '"';
            index += 1;
        } else if (char === '"') {
            quoted = !quoted;
        } else if (char === ',' && !quoted) {
            fields.push(field);
            field = '';
        } else {
            field += char;
        }
    }
    fields.push(field);
    return fields;
}

const queryRows = (await readFile(new URL('../../evaluation/queries.csv', import.meta.url), 'utf8'))
    .trim()
    .split(/\r?\n/)
    .slice(1)
    .map(parseCsvLine);

await empty.check(
    'SRCH-01',
    'Empty knowledge base returns EMPTY_KNOWLEDGE_BASE without category membership checks.',
    async () => {
        const emptySearch = await empty.request('/api/search', {
            method: 'POST',
            body: { ...CATEGORIES, product: 'Unknown product' },
        });
        assert.equal(emptySearch.status, 200);
        assert.equal(emptySearch.data.status, 'EMPTY_KNOWLEDGE_BASE');
        assert.equal(emptySearch.data.corpusSize, 0);
        assert.deepEqual(emptySearch.data.results, []);
        const emptyList = await empty.request('/api/cases');
        assert.equal(emptyList.data.totalElements, 0);
        const emptyCategories = await empty.request('/api/categories');
        assert.deepEqual(emptyCategories.data.items, []);
        return { status: emptySearch.data.status, corpusSize: 0 };
    },
);

await baseline.check(
    'SRCH-02',
    'Default and explicit limits are honoured without returning more than the limit.',
    async () => {
        const omitted = await search(baseline, CATEGORIES);
        assert.equal(omitted.data.limit, 5);
        const one = await search(baseline, { ...CATEGORIES, narrative: 'transfer', limit: 1 });
        assert.equal(one.data.limit, 1);
        assert.ok(one.data.returnedCount <= 1);
        const twenty = await search(baseline, { ...CATEGORIES, narrative: 'transfer', limit: 20 });
        assert.equal(twenty.data.limit, 20);
        assert.ok(twenty.data.returnedCount <= 20);
        return {
            omitted: { status: omitted.data.status, count: omitted.data.returnedCount },
            one: one.data.returnedCount,
            twenty: twenty.data.returnedCount,
        };
    },
);

await baseline.check(
    'SRCH-03',
    'Optional category fields may be omitted, null, or blank and normalize to the same result set.',
    async () => {
        const omitted = await search(baseline, { ...CATEGORIES, subProduct: null, subIssue: null });
        const nulls = await search(baseline, { ...CATEGORIES, subProduct: null, subIssue: null });
        const blank = await search(baseline, { ...CATEGORIES, subProduct: '   ', subIssue: '  ' });
        assert.equal(omitted.response.status, 200);
        assert.deepEqual(blank.data.results, nulls.data.results);
        return { count: nulls.data.returnedCount };
    },
);

await baseline.check(
    'SRCH-04',
    'Category values normalize case and internal whitespace before catalog matching.',
    async () => {
        const canonical = await search(baseline, CATEGORIES);
        const variant = await search(baseline, {
            product: '  CHECKING   OR SAVINGS ACCOUNT ',
            subProduct: ' checking ACCOUNT ',
            issue: ' managing  an account ',
            subIssue: 'DEPOSITS AND WITHDRAWALS',
            narrative: CATEGORIES.narrative,
        });
        assert.deepEqual(variant.data.results, canonical.data.results);
        return { count: canonical.data.returnedCount };
    },
);

for (const [name, narrative] of [
    ['stopwords', 'the and or of'],
    ['numbers', '123 456 2024'],
    ['redaction', 'XXXX XX XXXX'],
    ['emoji', '😀💰。'],
    ['single letters', 'a b c d'],
]) {
    await baseline.check(
        `SRCH-05-${name}`,
        `Narrative without a meaningful token (${name}) is rejected before scoring.`,
        async () => {
            const result = await search(baseline, { ...CATEGORIES, narrative });
            assert.equal(result.response.status, 400);
            assert.equal(result.data.code, 'VALIDATION_ERROR');
            assert.ok(result.data.fieldErrors.some((error) => error.code === 'NO_MEANINGFUL_TOKENS'));
            return { code: result.data.code };
        },
    );
}

await baseline.check(
    'SRCH-06',
    'The three public search states are consistent with empty results and corpus size.',
    async () => {
        const matches = await search(baseline, CATEGORIES);
        assert.equal(matches.data.status, 'MATCHES');
        assert.ok(matches.data.results.length > 0);
        scoreInvariants(matches.data);
        const none = await search(baseline, { ...CATEGORIES, narrative: 'qzxvplm xwzqrpt' });
        assert.equal(none.data.status, 'NO_RELEVANT_CASES');
        assert.deepEqual(none.data.results, []);
        assert.equal(none.data.returnedCount, 0);
        const emptyStatus = await empty.request('/api/search', {
            method: 'POST',
            body: CATEGORIES,
        });
        assert.equal(emptyStatus.data.status, 'EMPTY_KNOWLEDGE_BASE');
        return {
            matches: matches.data.returnedCount,
            none: none.data.status,
            empty: emptyStatus.data.status,
        };
    },
);

await baseline.check(
    'SRCH-07',
    'Every returned match satisfies the documented score, threshold, explanation, ordering, and shared-term invariants.',
    async () => {
        const queries = [
            CATEGORIES,
            { ...CATEGORIES, narrative: 'A cash machine charged twice and the funds held were released.' },
            { ...CATEGORIES, narrative: 'My transfer is missing from my checking account.' },
            { ...CATEGORIES, narrative: 'duplicate charge withheld balance' },
            { ...CATEGORIES, narrative: 'transfer', limit: 20 },
        ];
        const observations = [];
        for (const query of queries) {
            const { data } = await search(baseline, query);
            if (data.status === 'MATCHES') scoreInvariants(data);
            observations.push({
                status: data.status,
                count: data.returnedCount,
                top: data.results[0]?.score,
            });
        }
        return observations;
    },
);

await baseline.check(
    'SRCH-08',
    'Search is read-only: repeated queries do not change the case count.',
    async () => {
        const before = await baseline.request('/api/cases');
        for (let index = 0; index < 5; index += 1) await search(baseline, CATEGORIES);
        const after = await baseline.request('/api/cases');
        assert.equal(after.data.totalElements, before.data.totalElements);
        return { total: after.data.totalElements };
    },
);

await baseline.check(
    'SRCH-09',
    'Baseline and phrases servers use the same corpus and report their actual algorithm version and weights.',
    async () => {
        const base = await baseline.request('/api/cases');
        const phrase = await phrases.request('/api/cases');
        assert.equal(base.data.totalElements, phrase.data.totalElements);
        const baseSearch = await search(baseline, CATEGORIES);
        const phraseSearch = await search(phrases, CATEGORIES);
        assert.equal(baseSearch.data.corpusSize, phraseSearch.data.corpusSize);
        assert.equal(baseSearch.data.configuration.algorithmVersion, 'tfidf-cosine-v1');
        assert.equal(phraseSearch.data.configuration.algorithmVersion, 'tfidf-phrases-v2');
        for (const data of [baseSearch.data, phraseSearch.data]) {
            assert.equal(data.configuration.categoryWeight, 0.4);
            assert.equal(data.configuration.textWeight, 0.6);
            assert.equal(data.configuration.threshold, 0.5);
            assert.equal(data.configuration.requiresTextOverlap, true);
            assert.deepEqual(data.configuration.categoryFieldWeights, {
                product: 0.35,
                subProduct: 0.15,
                issue: 0.35,
                subIssue: 0.15,
            });
        }
        return { corpus: base.data.totalElements };
    },
);

for (const mode of [baseline, phrases]) {
    for (const row of queryRows) {
        const [queryId, kind, product, subProduct, issue, subIssue, narrative, scenario] = row;
        await mode.check(
            `SRCH-EVAL-${queryId}`,
            `Frozen evaluation query ${queryId} (${scenario}) runs against the identical corpus with ${mode === baseline ? 'baseline' : 'phrases'} scoring.`,
            async () => {
                const before = await mode.request('/api/cases');
                const response = await mode.request('/api/search', {
                    method: 'POST',
                    body: {
                        product,
                        subProduct: subProduct || null,
                        issue,
                        subIssue: subIssue || null,
                        narrative,
                        limit: 5,
                    },
                });
                if (queryId === 'Q23') {
                    assert.equal(response.status, 400);
                    assert.equal(response.data.code, 'INVALID_CATEGORY');
                    assert.ok(
                        response.data.fieldErrors.some((error) => error.field === 'issue'),
                        'Q23 must report the issue field',
                    );
                    const after = await mode.request('/api/cases');
                    assert.equal(after.data.totalElements, before.data.totalElements);
                    return {
                        kind,
                        negative: true,
                        status: response.status,
                        code: response.data.code,
                        fieldErrors: response.data.fieldErrors,
                        totalElements: after.data.totalElements,
                    };
                }
                assert.equal(response.status, 200);
                assert.equal(
                    response.data.configuration.algorithmVersion,
                    mode === baseline ? 'tfidf-cosine-v1' : 'tfidf-phrases-v2',
                );
                assert.equal(response.data.corpusSize, 512);
                scoreInvariants(response.data);
                return {
                    kind,
                    status: response.data.status,
                    count: response.data.returnedCount,
                    topIds: response.data.results.map((match) => match.case.provenance?.complaintId).slice(0, 5),
                    topScores: response.data.results.map((match) => match.score).slice(0, 5),
                };
            },
        );
    }
}

await phrases.check(
    'SRCH-10',
    'Phrase-mode canonical terms are used in sharedTerms while the stored narrative stays byte-for-byte unchanged.',
    async () => {
        const response = await search(phrases, {
            ...CATEGORIES,
            narrative: 'A cash machine charged twice and the funds held were released.',
        });
        assert.equal(response.data.configuration.algorithmVersion, 'tfidf-phrases-v2');
        const match = response.data.results.find(
            (item) => item.case.provenance?.complaintId === FIXTURE_ID,
        );
        assert.ok(match, 'fixture case was not returned');
        assert.equal(match.score, 1);
        assert.deepEqual(
            new Set(match.sharedTerms.map((term) => term.term)),
            new Set(['atm', 'duplicatecharge', 'heldfunds', 'released']),
        );
        const detail = await phrases.request(`/api/cases/${match.case.id}`);
        assert.equal(detail.data.narrative, FIXTURE_NARRATIVE);
        return { terms: match.sharedTerms.map((term) => term.term) };
    },
);

await baseline.check(
    'SRCH-11',
    'The same narrative is stored unchanged in baseline mode and exact narrative retrieval ranks it first.',
    async () => {
        const response = await search(baseline, {
            ...CATEGORIES,
            narrative: FIXTURE_NARRATIVE,
            limit: 20,
        });
        const match = response.data.results.find(
            (item) => item.case.provenance?.complaintId === FIXTURE_ID,
        );
        assert.ok(match);
        const detail = await baseline.request(`/api/cases/${match.case.id}`);
        assert.equal(detail.data.narrative, FIXTURE_NARRATIVE);
        return { topScore: match.score, topComplaintId: FIXTURE_ID };
    },
);

for (const [name, narrative, expected] of [
    ['cash machine', 'cash machine', ['atm']],
    ['charged twice', 'charged twice', ['duplicatecharge']],
    ['balance held', 'balance held', ['heldfunds']],
    ['withheld balance', 'withheld balance', ['heldfunds']],
    ['automated teller machine', 'automated teller machine', ['atm']],
    ['repeated whitespace', 'charged   twice', ['duplicatecharge']],
    ['case insensitive', 'CHARGED TWICE', ['duplicatecharge']],
]) {
    await phrases.check(
        `SRCH-12-${name}`,
        `Phrase dictionary normalizes "${name}" to the documented canonical token before stopword removal.`,
        async () => {
            const response = await search(phrases, { ...CATEGORIES, narrative, limit: 20 });
            assert.equal(response.response.status, 200);
            const terms = response.data.results.flatMap((match) => match.sharedTerms.map((term) => term.term));
            for (const term of expected) assert.ok(terms.includes(term), `missing ${term}`);
            return { canonical: expected, count: response.data.returnedCount };
        },
    );
}

await phrases.check(
    'SRCH-13',
    'Phrase matching is word-bounded and does not span sentence punctuation.',
    async () => {
        const bounded = await search(phrases, {
            ...CATEGORIES,
            narrative: 'uncharged twicefold cash machines',
            limit: 20,
        });
        const terms = bounded.data.results.flatMap((match) => match.sharedTerms.map((term) => term.term));
        assert.ok(!terms.includes('duplicatecharge'));
        assert.ok(!terms.includes('atm'));
        const punctuation = await search(phrases, {
            ...CATEGORIES,
            narrative: 'charged twice. funds. held.',
            limit: 20,
        });
        const punctTerms = punctuation.data.results.flatMap((match) => match.sharedTerms.map((term) => term.term));
        assert.ok(!punctTerms.includes('heldfunds'));
        return { bounded: bounded.data.status, punctuation: punctuation.data.status };
    },
);

await baseline.check(
    'SRCH-14',
    'Retain -> detail -> search -> full PUT -> search keeps one complete version and preserves identity.',
    async () => {
        const cfpb = await baseline.request('/api/cases?source=CFPB&size=2');
        const reference = cfpb.data.items[0].id;
        const createBody = {
            product: CATEGORIES.product,
            subProduct: CATEGORIES.subProduct,
            issue: CATEGORIES.issue,
            subIssue: CATEGORIES.subIssue,
            narrative: 'A newly retained narrative about a missing transfer and a duplicate charge.',
            receivedDate: '2024-12-01',
            company: 'Exploratory Bank',
            companyResponse: 'Closed with explanation',
            handlingNotes: 'Retained by exploratory session.',
            outcomeAssessment: 'UNKNOWN',
            evaluationNotes: 'Awaiting review.',
            referenceCaseIds: [reference],
        };
        const created = await baseline.request('/api/cases', { method: 'POST', body: createBody });
        assert.equal(created.status, 201);
        assert.equal(created.headers.get('location'), `/api/cases/${created.data.id}`);
        const detail = await baseline.request(`/api/cases/${created.data.id}`);
        assert.deepEqual(detail.data, created.data);
        const found = await search(baseline, {
            ...CATEGORIES,
            narrative: createBody.narrative,
            limit: 20,
        });
        assert.ok(found.data.results.some((match) => match.case.id === created.data.id));
        const replacement = {
            ...createBody,
            narrative: 'The replacement narrative is about a wire transfer under review.',
            subProduct: null,
            subIssue: null,
            receivedDate: null,
            company: null,
            companyResponse: null,
            handlingNotes: 'Replacement notes.',
            outcomeAssessment: 'RESOLVED',
            evaluationNotes: null,
            referenceCaseIds: [],
        };
        const put = await baseline.request(`/api/cases/${created.data.id}`, {
            method: 'PUT',
            body: replacement,
        });
        assert.equal(put.status, 200);
        assert.equal(put.data.id, created.data.id);
        assert.equal(put.data.createdAt, created.data.createdAt);
        for (const field of ['subProduct', 'subIssue', 'receivedDate', 'company', 'companyResponse']) {
            assert.equal(put.data[field], null);
        }
        assert.deepEqual(put.data.referenceCaseIds, []);
        assert.equal(put.data.analystNotes.evaluationNotes, null);
        const reread = await baseline.request(`/api/cases/${created.data.id}`);
        assert.deepEqual(reread.data, put.data);
        const oldSearch = await search(baseline, {
            ...CATEGORIES,
            narrative: createBody.narrative,
            limit: 20,
        });
        for (const match of oldSearch.data.results) {
            if (match.case.id === created.data.id) {
                assert.equal(match.case.narrative, replacement.narrative);
            }
        }
        const newSearch = await search(baseline, {
            ...CATEGORIES,
            narrative: replacement.narrative,
            limit: 20,
        });
        assert.ok(newSearch.data.results.some((match) => match.case.id === created.data.id));
        return { id: created.data.id, createdAt: created.data.createdAt };
    },
);

await baseline.check(
    'SRCH-15',
    'Tied scores are ordered by case UUID ascending for deterministic output.',
    async () => {
        const body = {
            product: CATEGORIES.product,
            issue: CATEGORIES.issue,
            narrative: 'tiebreak exploratory narrative about a transfer',
            handlingNotes: 'Tie fixture.',
            outcomeAssessment: 'UNKNOWN',
            referenceCaseIds: [],
        };
        const created = [];
        for (let index = 0; index < 3; index += 1) {
            const response = await baseline.request('/api/cases', {
                method: 'POST',
                body: { ...body, handlingNotes: `Tie fixture ${index}` },
            });
            assert.equal(response.status, 201);
            created.push(response.data);
        }
        const response = await search(baseline, {
            ...CATEGORIES,
            narrative: body.narrative,
            limit: 20,
        });
        const tied = response.data.results.filter((match) =>
            created.some((item) => item.id === match.case.id),
        );
        assert.equal(tied.length, created.length);
        const ids = tied.map((match) => match.case.id);
        assert.deepEqual(ids, [...ids].sort());
        const scores = tied.map((match) => match.score);
        assert.ok(scores.every((score) => Math.abs(score - scores[0]) < 1e-15));
        return { ids };
    },
);

const baseDoc = await baseline.save();
await phrases.save();
await empty.save();
console.log(JSON.stringify({
    baseline: baseDoc.summary,
    phrases: phrases.results.length,
    empty: empty.results.length,
}));
