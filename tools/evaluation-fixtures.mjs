// Header-driven parsing for the frozen evaluation query fixture.
const REQUIRED_COLUMNS = [
    'queryId',
    'kind',
    'product',
    'subProduct',
    'issue',
    'subIssue',
    'narrative',
    'scenario',
];

export function parseCsvRecords(text) {
    const records = [];
    let record = [];
    let field = '';
    let quoted = false;
    for (let i = 0; i < text.length; i++) {
        const c = text[i];
        if (quoted) {
            if (c === '"' && text[i + 1] === '"') { field += '"'; i++; }
            else if (c === '"') quoted = false;
            else field += c;
        } else if (c === '"') quoted = true;
        else if (c === ',') { record.push(field); field = ''; }
        else if (c === '\r' || c === '\n') {
            if (c === '\r' && text[i + 1] === '\n') i++;
            record.push(field); field = ''; records.push(record); record = [];
        } else field += c;
    }
    if (quoted) throw new Error('evaluation fixture has an unterminated quoted field');
    if (field !== '' || record.length > 0) { record.push(field); records.push(record); }
    return records;
}

export function evaluationRows(text) {
    const records = parseCsvRecords(text.replace(/^\uFEFF/, ''));
    if (records.length === 0) throw new Error('evaluation fixture is empty');
    const header = records[0].map((name) => name.trim());
    const indexes = {};
    for (const name of REQUIRED_COLUMNS) {
        const matches = header.flatMap((column, index) => column === name ? [index] : []);
        if (matches.length === 0) throw new Error(`evaluation fixture is missing required column: ${name}`);
        if (matches.length > 1) throw new Error(`evaluation fixture has ambiguous duplicate column: ${name}`);
        indexes[name] = matches[0];
    }
    const seen = new Set();
    return records.slice(1).map((fields, rowIndex) => {
        if (fields.length !== header.length) {
            throw new Error(`evaluation fixture row ${rowIndex + 2} has ${fields.length} fields; header has ${header.length}`);
        }
        const row = Object.fromEntries(REQUIRED_COLUMNS.map((name) => [name, fields[indexes[name]]]));
        if (!row.queryId) throw new Error(`evaluation fixture row ${rowIndex + 2} has no queryId`);
        if (seen.has(row.queryId)) throw new Error(`evaluation fixture has duplicate queryId: ${row.queryId}`);
        seen.add(row.queryId);
        return row;
    });
}
