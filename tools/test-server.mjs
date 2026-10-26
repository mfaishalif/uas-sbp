import { spawn } from 'node:child_process';
import { createServer } from 'node:net';
import { mkdtemp, readdir, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const repoRoot = path.resolve(here, '..');

function delay(milliseconds) {
    return new Promise((resolve) => setTimeout(resolve, milliseconds));
}

async function findJar() {
    const target = path.join(repoRoot, 'backend', 'target');
    let entries;
    try {
        entries = await readdir(target);
    } catch {
        throw new Error(
            'Packaged backend jar not found. Run (cd backend && ./mvnw -DskipTests package) first.',
        );
    }
    const jars = entries.filter((name) => /^finresolve-backend-.*\.jar$/.test(name));
    if (jars.length !== 1) {
        throw new Error(
            `Expected exactly one packaged backend jar in ${target}, found ${JSON.stringify(jars)}.`,
        );
    }
    return path.join(target, jars[0]);
}

async function allocatePort() {
    const server = createServer();
    await new Promise((resolve, reject) => {
        server.once('error', reject);
        server.listen(0, '127.0.0.1', resolve);
    });
    const address = server.address();
    const port = typeof address === 'object' && address ? address.port : 0;
    await new Promise((resolve) => server.close(resolve));
    if (!port) {
        throw new Error('Could not allocate a loopback port.');
    }
    return port;
}

function tail(text, lines = 40) {
    return text.split(/\r?\n/).slice(-lines).join('\n');
}

async function waitForExit(child, milliseconds) {
    if (child.exitCode !== null || child.signalCode !== null) {
        return true;
    }
    return await new Promise((resolve) => {
        const timer = setTimeout(() => resolve(false), milliseconds);
        child.once('exit', () => {
            clearTimeout(timer);
            resolve(true);
        });
    });
}

/**
 * Start the packaged backend against a fresh temporary SQLite file on a loopback port.
 *
 * <p>{@code stop()} is idempotent and removes the database, WAL/SHM sidecars, and temporary
 * directory. It is safe to call after a failed startup.
 */
export async function startTestServer({ jarPath } = {}) {
    const jar = jarPath ?? (await findJar());
    const directory = await mkdtemp(path.join(tmpdir(), 'finresolve-live-'));
    const database = path.join(directory, 'finresolve.db');
    const port = await allocatePort();
    const baseUrl = `http://127.0.0.1:${port}`;
    let output = '';

    const child = spawn('java', ['-jar', jar], {
        cwd: repoRoot,
        env: {
            ...process.env,
            FINRESOLVE_DB_PATH: database,
            SPRING_DATASOURCE_URL: `jdbc:sqlite:${database}`,
            SERVER_ADDRESS: '127.0.0.1',
            SERVER_PORT: String(port),
        },
        stdio: ['ignore', 'pipe', 'pipe'],
    });
    child.stdout.on('data', (chunk) => {
        output += chunk.toString();
    });
    child.stderr.on('data', (chunk) => {
        output += chunk.toString();
    });

    let stopped = false;
    async function stop() {
        if (stopped) {
            return;
        }
        stopped = true;
        if (child.exitCode === null && child.signalCode === null) {
            child.kill('SIGTERM');
            if (!(await waitForExit(child, 10_000))) {
                child.kill('SIGKILL');
                await waitForExit(child, 5_000);
            }
        }
        await rm(directory, { recursive: true, force: true });
    }

    try {
        const deadline = Date.now() + 30_000;
        for (;;) {
            if (child.exitCode !== null || child.signalCode !== null) {
                throw new Error(
                    `Backend exited before becoming healthy.\n${tail(output)}`,
                );
            }
            try {
                const response = await fetch(`${baseUrl}/api/health`);
                if (response.ok) {
                    await response.body?.cancel();
                    break;
                }
                await response.body?.cancel();
            } catch {
                // The port is not accepting requests yet.
            }
            if (Date.now() >= deadline) {
                throw new Error(`Backend did not become healthy within 30s.\n${tail(output)}`);
            }
            await delay(200);
        }
    } catch (error) {
        await stop();
        throw error;
    }

    return { baseUrl, databasePath: database, stop };
}
