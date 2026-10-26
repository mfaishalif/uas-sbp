-- FinResolve initial persistent schema.
-- Dates are ISO calendar dates (YYYY-MM-DD); timestamps are UTC Instant strings (RFC 3339, Z).

CREATE TABLE import_batches (
    id TEXT PRIMARY KEY NOT NULL CHECK (length(id) = 36),
    archive_url TEXT NOT NULL CHECK (length(trim(archive_url)) > 0),
    source_file_name TEXT NOT NULL CHECK (length(trim(source_file_name)) > 0),
    period_start TEXT NOT NULL
        CHECK (period_start GLOB '[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]'),
    period_end TEXT NOT NULL
        CHECK (period_end GLOB '[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]'),
    csv_sha256 TEXT NOT NULL CHECK (length(csv_sha256) = 64),
    imported_at TEXT NOT NULL CHECK (imported_at GLOB '*Z'),
    processed INTEGER NOT NULL CHECK (processed >= 0),
    imported INTEGER NOT NULL CHECK (imported >= 0),
    duplicates INTEGER NOT NULL CHECK (duplicates >= 0),
    rejected INTEGER NOT NULL CHECK (rejected >= 0),
    errors_json TEXT NOT NULL DEFAULT '[]'
        CHECK (json_valid(errors_json) AND json_type(errors_json) = 'array'),
    CHECK (processed = imported + duplicates + rejected),
    CHECK (period_end >= period_start),
    UNIQUE (csv_sha256, archive_url, source_file_name, period_start, period_end)
);

CREATE TABLE cases (
    id TEXT PRIMARY KEY NOT NULL CHECK (length(id) = 36),
    source TEXT NOT NULL CHECK (source IN ('CFPB', 'ANALYST')),
    complaint_id TEXT,
    batch_id TEXT REFERENCES import_batches (id) ON DELETE RESTRICT,
    received_date TEXT
        CHECK (received_date IS NULL OR received_date GLOB '[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]'),
    product TEXT NOT NULL CHECK (length(trim(product)) > 0 AND length(product) <= 500),
    sub_product TEXT CHECK (sub_product IS NULL OR length(sub_product) <= 500),
    issue TEXT NOT NULL CHECK (length(trim(issue)) > 0 AND length(issue) <= 500),
    sub_issue TEXT CHECK (sub_issue IS NULL OR length(sub_issue) <= 500),
    narrative TEXT NOT NULL CHECK (length(trim(narrative)) > 0 AND length(narrative) <= 50000),
    company TEXT CHECK (company IS NULL OR length(company) <= 500),
    company_response TEXT CHECK (company_response IS NULL OR length(company_response) <= 2000),
    created_at TEXT NOT NULL CHECK (created_at GLOB '*Z'),
    updated_at TEXT NOT NULL CHECK (updated_at GLOB '*Z'),
    CHECK (julianday(updated_at) >= julianday(created_at)),
    CHECK (
        (source = 'CFPB'
            AND complaint_id IS NOT NULL
            AND batch_id IS NOT NULL
            AND complaint_id GLOB '[0-9]*'
            AND complaint_id NOT GLOB '*[^0-9]*'
            AND CAST(complaint_id AS INTEGER) > 0)
        OR
        (source = 'ANALYST'
            AND complaint_id IS NULL
            AND batch_id IS NULL)
    )
);

CREATE UNIQUE INDEX ux_cases_complaint_id
    ON cases (complaint_id)
    WHERE complaint_id IS NOT NULL;

CREATE INDEX ix_cases_created_at_id
    ON cases (created_at DESC, id ASC);

CREATE INDEX ix_cases_source_created_at_id
    ON cases (source, created_at DESC, id ASC);

CREATE INDEX ix_cases_batch_id
    ON cases (batch_id);

CREATE TABLE analyst_notes (
    case_id TEXT PRIMARY KEY NOT NULL REFERENCES cases (id) ON DELETE CASCADE,
    handling_notes TEXT NOT NULL
        CHECK (length(trim(handling_notes)) > 0 AND length(handling_notes) <= 10000),
    outcome_assessment TEXT NOT NULL
        CHECK (outcome_assessment IN (
            'UNKNOWN', 'RESOLVED', 'PARTIALLY_RESOLVED', 'UNRESOLVED'
        )),
    evaluation_notes TEXT
        CHECK (evaluation_notes IS NULL OR length(evaluation_notes) <= 10000)
);

CREATE TABLE case_references (
    case_id TEXT NOT NULL REFERENCES cases (id) ON DELETE CASCADE,
    referenced_case_id TEXT NOT NULL REFERENCES cases (id) ON DELETE RESTRICT,
    position INTEGER NOT NULL CHECK (position >= 0),
    PRIMARY KEY (case_id, referenced_case_id),
    UNIQUE (case_id, position),
    CHECK (case_id <> referenced_case_id)
);

CREATE INDEX ix_case_references_target
    ON case_references (referenced_case_id);

CREATE TRIGGER trg_analyst_notes_insert_source
BEFORE INSERT ON analyst_notes
FOR EACH ROW
WHEN COALESCE((SELECT source FROM cases WHERE id = NEW.case_id), '') <> 'ANALYST'
BEGIN
    SELECT RAISE(ABORT, 'analyst notes require an ANALYST case');
END;

CREATE TRIGGER trg_analyst_notes_update_source
BEFORE UPDATE OF case_id ON analyst_notes
FOR EACH ROW
WHEN COALESCE((SELECT source FROM cases WHERE id = NEW.case_id), '') <> 'ANALYST'
BEGIN
    SELECT RAISE(ABORT, 'analyst notes require an ANALYST case');
END;

CREATE TRIGGER trg_case_references_insert_owner
BEFORE INSERT ON case_references
FOR EACH ROW
WHEN COALESCE((SELECT source FROM cases WHERE id = NEW.case_id), '') <> 'ANALYST'
BEGIN
    SELECT RAISE(ABORT, 'references may only belong to ANALYST cases');
END;

CREATE TRIGGER trg_case_references_update_owner
BEFORE UPDATE OF case_id ON case_references
FOR EACH ROW
WHEN COALESCE((SELECT source FROM cases WHERE id = NEW.case_id), '') <> 'ANALYST'
BEGIN
    SELECT RAISE(ABORT, 'references may only belong to ANALYST cases');
END;

CREATE TRIGGER trg_cases_source_immutable
BEFORE UPDATE OF source ON cases
FOR EACH ROW
WHEN NEW.source <> OLD.source
BEGIN
    SELECT RAISE(ABORT, 'case source is immutable');
END;
