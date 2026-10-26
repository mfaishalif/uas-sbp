-- Enforce the contract's canonical complaint-ID form at the storage boundary.
-- The API pattern is ^[1-9][0-9]*$; leading zeros would otherwise produce a
-- response that fails the published ArchiveProvenance schema.
CREATE TRIGGER trg_cases_complaint_id_canonical_insert
BEFORE INSERT ON cases
FOR EACH ROW
WHEN NEW.complaint_id IS NOT NULL
    AND (
        NEW.complaint_id NOT GLOB '[1-9]*'
        OR NEW.complaint_id GLOB '*[^0-9]*'
    )
BEGIN
    SELECT RAISE(ABORT, 'complaint id must be a canonical positive decimal integer');
END;

CREATE TRIGGER trg_cases_complaint_id_canonical_update
BEFORE UPDATE OF complaint_id ON cases
FOR EACH ROW
WHEN NEW.complaint_id IS NOT NULL
    AND (
        NEW.complaint_id NOT GLOB '[1-9]*'
        OR NEW.complaint_id GLOB '*[^0-9]*'
    )
BEGIN
    SELECT RAISE(ABORT, 'complaint id must be a canonical positive decimal integer');
END;
