import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { spawn } from 'node:child_process';

const root = path.resolve(import.meta.dirname, '../../..');
const jar = path.join(root, 'backend/target/finresolve-backend-0.1.0.jar');
const tempDirectory = fs.mkdtempSync(path.join(os.tmpdir(), 'finresolve-e2e-'));
const database = path.join(tempDirectory, 'finresolve.db');

if (!fs.existsSync(jar)) {
  console.error(
    `Missing ${jar}. Run "npm run backend:package" before the browser tests.`,
  );
  process.exit(1);
}

const child = spawn(
  'java',
  ['-jar', jar],
  {
    cwd: root,
    env: {
      ...process.env,
      FINRESOLVE_DB_PATH: database,
      FINRESOLVE_DEMO_ENABLED: 'false',
      FINRESOLVE_CORS_ORIGINS:
        'http://localhost:3000,http://127.0.0.1:3000',
      SERVER_ADDRESS: '127.0.0.1',
    },
    stdio: 'inherit',
  },
);

let stopping = false;
function stop(signal = 'SIGTERM') {
  if (stopping) return;
  stopping = true;
  child.kill(signal);
  fs.rmSync(tempDirectory, { recursive: true, force: true });
}

for (const signal of ['SIGINT', 'SIGTERM', 'SIGHUP']) {
  process.on(signal, () => {
    stop(signal);
    process.exit(0);
  });
}

child.on('exit', (code, signal) => {
  fs.rmSync(tempDirectory, { recursive: true, force: true });
  if (signal) process.kill(process.pid, signal);
  else process.exit(code ?? 0);
});

process.on('exit', () => {
  fs.rmSync(tempDirectory, { recursive: true, force: true });
});
