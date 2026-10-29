# Active retest runners

These runners replace the archived scripts that lived inside
`../results/2026-10-28-full-155053/`. The archived directory and its results are
kept unchanged as historical evidence.

Each execution:

- derives the repository root from this file (no `/workspaces/uas-sbp` literal);
- creates a brand-new directory under `../results/retest-<UTC timestamp>-<pid>/`;
- creates a unique temporary SQLite directory with `mktemp -d`;
- rejects ports that are already in use before starting anything;
- tracks a separate PID for every backend/frontend process;
- waits for `/api/health` (and the web root) before running probes;
- validates the sample seed report before contract tests;
- stops every process and removes the temporary database directory on exit,
  including failures;
- returns a non-zero exit code if any probe or validation step fails.

Persistence phases deliberately restart the same database (`reset=no`). Only a
seed phase may reset a database file.

## Commands

From the repository root after `npm run verify` and `npm run frontend:e2e` have
produced the JAR and the production Next.js build:

```bash
bash docs/testing/runners/run-backend-probes.sh
bash docs/testing/runners/run-regression.sh
bash docs/testing/runners/run-browser-session.sh
bash docs/testing/runners/run-emoji-check.sh
bash docs/testing/runners/run-unicode-boundary.sh
```

Set `FINRESOLVE_KEEP_TMP=1` to keep the temporary database directory for
debugging. Set `FINRESOLVE_RESULT_DIR` only when a specific, non-existing output
directory is needed; the runner appends a suffix rather than overwriting an
existing directory.

## What each runner covers

| Runner | Coverage |
| --- | --- |
| `run-backend-probes.sh` | 141 directed backend probes, including persistence restart on the same file and write lock |
| `run-regression.sh` | `tools/exploratory-backend.mjs`, 140 scenarios, on an empty database |
| `run-browser-session.sh` | 42 exploratory browser scenarios, 5 restart checks on the same database, 2 phrases-mode checks |
| `run-emoji-check.sh` | Original FS-01 26,009-code-point search scenario through the browser |
| `run-unicode-boundary.sh` | Every limited field with BMP/astral values at the limit and limit+1, plus create/GET/edit round-trips |

`run-unicode-boundary.sh` uses `../probes/unicode-boundary-check.mjs`.
`run-emoji-check.sh` uses the archived `../probes/emoji-input-check.mjs`
(updated only to correct its UTF-16 unit count comment).
