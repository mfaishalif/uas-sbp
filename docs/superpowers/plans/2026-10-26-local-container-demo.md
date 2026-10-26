# Local Container Demo Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the local API demo available with 500 sample cases using one Compose command.

**Architecture:** Build the backend with Maven Wrapper in a multi-stage image. An opt-in startup initializer reuses ImportService replay semantics; Compose supplies persistent SQLite storage and localhost publication.

**Tech Stack:** Java 21, Spring Boot, Maven Wrapper, Docker Compose, SQLite.

**Spec:** docs/superpowers/specs/2026-10-26-local-container-demo-design.md

## Global Constraints
- Java 21; preserve existing API and default non-container startup behavior.
- `docker compose up --build`; `http://localhost:8080`; 500 committed sample cases.
- Persistent SQLite named volume; host bind `127.0.0.1:8080:8080`.
- No frontend, public deployment, or runtime archive downloads.

## Review Focus
- Existing database: restarting preserves analyst cases and sample IDs.
- Disabled initialization: ordinary startup does not require CSV files.
- Missing or invalid sample: startup fails visibly rather than presenting an empty demo.
- Fresh volume permissions: non-root process can create database and WAL files.
- Readiness: healthy state must follow successful sample initialization.

### Task 1: Opt-in demo initialization

**Files:** Create `backend/src/main/java/id/ac/finresolve/demo/DemoDataInitializer.java`; create corresponding test `backend/src/test/java/id/ac/finresolve/demo/DemoDataInitializerTest.java`; modify `backend/src/main/resources/application.yml` if property declarations improve clarity.

**Interfaces:** Consumes `ImportService.importCsv(InputStream, ImportMetadataValue)` and existing migration lifecycle. Produces an opt-in startup initializer configured by `FINRESOLVE_DEMO_ENABLED` and `FINRESOLVE_DEMO_SAMPLE_PATH`, disabled by default. Use the archive URL, filename and dates from README's import example.

- [ ] Write failing tests: enabled initializer imports committed sample with 500 processed, zero rejected; repeated invocation preserves case IDs/count and existing analyst case; disabled initializer does not read a missing path; enabled initializer rejects missing/invalid input.
- [ ] Run targeted tests and confirm expected failures.
- [ ] Implement initializer after migrations using existing import transaction/replay; expose a readiness signal suitable for container healthcheck only after import completes.
- [ ] Run targeted tests and backend regression tests; confirm pass.

### Task 2: Runnable container demo and documentation

**Files:** Create root `Dockerfile`, `.dockerignore`, `compose.yaml`; modify `README.md`; add a runnable smoke script under `tests/container/` if Docker is available.

**Interfaces:** Consumes initializer environment configuration; produces API on localhost port 8080 and persistent `/data/finresolve.db` on a named volume.

- [ ] Create multi-stage Java 21 image using repository-root context, Maven Wrapper, contract and generator configuration; include sample CSV in runtime image. Run as non-root, create writable data directory, use exec-form JVM startup.
- [ ] Create Compose service with explicit loopback publication, named volume, demo initialization enabled, and a healthcheck that waits for imported data readiness.
- [ ] Exclude Git, local databases, node_modules and build outputs from build context while retaining wrapper, source, contract, generator config and sample CSV.
- [ ] Document prerequisites, first build internet requirement, startup/readiness, health/list/search examples, shutdown preserving data, and explicit destructive reset command. Explain API-only demo and unchanged devcontainer workflow.
- [ ] Validate Compose configuration and build image. On a fresh project volume, verify healthy state and 500 CFPB cases, successful search, and non-root runtime. Create an analyst case, recreate the container, and verify retained analyst data and stable CFPB IDs/count. Reset only the disposable smoke-test volume and verify fresh initialization. Run startup with missing/invalid sample and verify failure.
- [ ] If Docker is unavailable, record that limitation and verify packaged jar startup/restart with equivalent environment and temporary database; do not claim container runtime verification.
- [ ] Review final diff for spec coverage and unintended changes; record commands and outcomes.
