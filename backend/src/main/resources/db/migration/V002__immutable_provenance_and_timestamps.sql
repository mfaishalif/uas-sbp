-- Normalize legacy case timestamps to the fixed-width, sortable UTC form.
UPDATE cases
SET created_at = CASE
        WHEN instr(created_at, '.') = 0 THEN
            substr(created_at, 1, length(created_at) - 1) || '.000000000Z'
        ELSE
            substr(created_at, 1, instr(created_at, '.') - 1)
            || '.'
            || substr(
                substr(
                    created_at,
                    instr(created_at, '.') + 1,
                    length(created_at) - instr(created_at, '.') - 1
                ) || '000000000',
                1,
                9
            )
            || 'Z'
    END,
    updated_at = CASE
        WHEN instr(updated_at, '.') = 0 THEN
            substr(updated_at, 1, length(updated_at) - 1) || '.000000000Z'
        ELSE
            substr(updated_at, 1, instr(updated_at, '.') - 1)
            || '.'
            || substr(
                substr(
                    updated_at,
                    instr(updated_at, '.') + 1,
                    length(updated_at) - instr(updated_at, '.') - 1
                ) || '000000000',
                1,
                9
            )
            || 'Z'
    END
WHERE length(created_at) <> 30 OR length(updated_at) <> 30;

CREATE TRIGGER trg_cases_timestamp_insert
BEFORE INSERT ON cases
FOR EACH ROW
WHEN length(NEW.created_at) <> 30
    OR NEW.created_at NOT GLOB '[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]T[0-9][0-9]:[0-9][0-9]:[0-9][0-9].[0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9]Z'
    OR length(NEW.updated_at) <> 30
    OR NEW.updated_at NOT GLOB '[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]T[0-9][0-9]:[0-9][0-9]:[0-9][0-9].[0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9]Z'
BEGIN
    SELECT RAISE(ABORT, 'case timestamps must use nine UTC fractional digits');
END;

CREATE TRIGGER trg_cases_timestamp_update
BEFORE UPDATE OF created_at, updated_at ON cases
FOR EACH ROW
WHEN length(NEW.created_at) <> 30
    OR NEW.created_at NOT GLOB '[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]T[0-9][0-9]:[0-9][0-9]:[0-9][0-9].[0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9]Z'
    OR length(NEW.updated_at) <> 30
    OR NEW.updated_at NOT GLOB '[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]T[0-9][0-9]:[0-9][0-9]:[0-9][0-9].[0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9]Z'
BEGIN
    SELECT RAISE(ABORT, 'case timestamps must use nine UTC fractional digits');
END;

CREATE TRIGGER trg_cases_cfpb_provenance_immutable
BEFORE UPDATE OF complaint_id, batch_id ON cases
FOR EACH ROW
WHEN OLD.source = 'CFPB'
BEGIN
    SELECT RAISE(ABORT, 'CFPB provenance is immutable');
END;

CREATE TRIGGER trg_import_batches_immutable
BEFORE UPDATE ON import_batches
FOR EACH ROW
BEGIN
    SELECT RAISE(ABORT, 'import batch records are immutable');
END;
