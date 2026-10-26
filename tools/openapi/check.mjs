import { mkdtemp, readFile, readdir, rm, stat } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

import { generateAll, loadToolchain, repoRoot } from './generate.mjs';

async function exists(filePath) {
  try {
    await stat(filePath);
    return true;
  } catch {
    return false;
  }
}

async function listRelativeFiles(root) {
  if (!(await exists(root))) {
    throw new Error(`Directory does not exist: ${root}`);
  }
  const result = [];
  async function walk(directory, prefix) {
    const entries = await readdir(directory, { withFileTypes: true });
    entries.sort((left, right) => left.name.localeCompare(right.name));
    for (const entry of entries) {
      const relative = prefix ? `${prefix}/${entry.name}` : entry.name;
      if (entry.isDirectory()) {
        await walk(path.join(directory, entry.name), relative);
      } else if (entry.isFile()) {
        result.push(relative);
      }
    }
  }
  await walk(root, '');
  return result;
}

/**
 * Compare complete generated trees by relative path and byte content.
 * Missing, extra, and changed files all fail the comparison.
 */
export async function compareTrees(expectedDir, actualDir) {
  const expected = await listRelativeFiles(expectedDir);
  const actual = await listRelativeFiles(actualDir);
  const expectedSet = new Set(expected);
  const actualSet = new Set(actual);

  const missing = expected.filter((file) => !actualSet.has(file));
  const extra = actual.filter((file) => !expectedSet.has(file));
  const changed = [];

  for (const file of expected) {
    if (!actualSet.has(file)) {
      continue;
    }
    const expectedBytes = await readFile(path.join(expectedDir, file));
    const actualBytes = await readFile(path.join(actualDir, file));
    if (!expectedBytes.equals(actualBytes)) {
      changed.push(file);
    }
  }

  if (missing.length || extra.length || changed.length) {
    const lines = [`Generated tree mismatch between ${expectedDir} and ${actualDir}.`];
    for (const file of missing) lines.push(`  missing: ${file}`);
    for (const file of extra) lines.push(`  extra: ${file}`);
    for (const file of changed) lines.push(`  changed: ${file}`);
    throw new Error(lines.join('\n'));
  }
}

async function assertGeneratedJavaBoundary(javaDir) {
  const apiDir = path.join(javaDir, 'src', 'main', 'java', 'id', 'ac', 'finresolve', 'api');
  if (!(await exists(apiDir))) {
    throw new Error(`Generated Java API directory is missing: ${apiDir}`);
  }
  const interfaces = (await readdir(apiDir))
    .filter((name) => name.endsWith('Api.java'))
    .sort();
  const expected = [
    'CasesApi.java',
    'CategoriesApi.java',
    'ImportsApi.java',
    'SearchApi.java',
    'SystemApi.java',
  ];
  if (interfaces.join(',') !== expected.join(',')) {
    throw new Error(
      `Generated Java API interfaces changed: expected ${expected.join(', ')}, got ${interfaces.join(', ')}`,
    );
  }
  for (const interfaceFile of interfaces) {
    const source = await readFile(path.join(apiDir, interfaceFile), 'utf8');
    if (/\bdefault\s+ResponseEntity</.test(source)) {
      throw new Error(`${interfaceFile} contains a default endpoint implementation`);
    }
  }
}

async function main() {
  const toolchain = await loadToolchain();
  const committedClient = path.resolve(repoRoot, toolchain.outputs.typescript);
  const temporaryRoot = await mkdtemp(path.join(tmpdir(), 'finresolve-api-check-'));
  try {
    await generateAll(temporaryRoot);
    await assertGeneratedJavaBoundary(path.join(temporaryRoot, 'java'));
    await compareTrees(path.join(temporaryRoot, 'typescript'), committedClient);
    console.log(
      `api:check passed: generated TypeScript matches ${toolchain.outputs.typescript}; Java interface boundary is complete and abstract.`,
    );
  } finally {
    await rm(temporaryRoot, { recursive: true, force: true });
  }
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  main().catch((error) => {
    console.error(error.message);
    process.exitCode = 1;
  });
}
