import { createHash } from 'node:crypto';
import { createWriteStream } from 'node:fs';
import { cp, mkdir, mkdtemp, readFile, rename, rm, stat } from 'node:fs/promises';
import { execFile } from 'node:child_process';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { Readable } from 'node:stream';
import { pipeline } from 'node:stream/promises';
import { fileURLToPath } from 'node:url';
import { promisify } from 'node:util';

import { validateContract } from './validate.mjs';

const execFileAsync = promisify(execFile);
const here = path.dirname(fileURLToPath(import.meta.url));
export const repoRoot = path.resolve(here, '..', '..');
const toolchainPath = path.join(here, 'toolchain.json');
const javaConfigPath = path.join(here, 'java.json');
const typescriptConfigPath = path.join(here, 'typescript.json');

async function readJson(filePath) {
  return JSON.parse(await readFile(filePath, 'utf8'));
}

export async function loadToolchain() {
  return readJson(toolchainPath);
}

export async function verifySha256(filePath, expectedSha256, label = filePath) {
  const expected = String(expectedSha256).toLowerCase();
  if (!/^[a-f0-9]{64}$/.test(expected)) {
    throw new Error(`${label} has an invalid expected SHA-256: ${expectedSha256}`);
  }
  const actual = createHash('sha256').update(await readFile(filePath)).digest('hex');
  if (actual !== expected) {
    throw new Error(
      `${label} checksum mismatch: expected ${expected}, got ${actual}. Aborting instead of using an unverified artifact.`,
    );
  }
  return actual;
}

async function exists(filePath) {
  try {
    await stat(filePath);
    return true;
  } catch {
    return false;
  }
}

export async function downloadVerified(url, destination, expectedSha256, label) {
  if (await exists(destination)) {
    await verifySha256(destination, expectedSha256, label);
    return destination;
  }

  await mkdir(path.dirname(destination), { recursive: true });
  const temporary = `${destination}.download-${process.pid}`;
  try {
    const response = await fetch(url);
    if (!response.ok || !response.body) {
      throw new Error(`${label} download failed: HTTP ${response.status} from ${url}`);
    }
    await pipeline(Readable.fromWeb(response.body), createWriteStream(temporary));
    await verifySha256(temporary, expectedSha256, label);
    await rename(temporary, destination);
    return destination;
  } catch (error) {
    await rm(temporary, { force: true });
    throw error;
  }
}

async function ensureGeneratorJar(toolchain) {
  const cacheDirectory = path.resolve(repoRoot, toolchain.cacheDirectory);
  const jarPath = path.join(
    cacheDirectory,
    `openapi-generator-cli-${toolchain.openapiGenerator.version}.jar`,
  );
  return downloadVerified(
    toolchain.openapiGenerator.url,
    jarPath,
    toolchain.openapiGenerator.sha256,
    `OpenAPI Generator ${toolchain.openapiGenerator.version}`,
  );
}

function additionalProperties(properties) {
  return Object.entries(properties ?? {})
    .map(([key, value]) => `${key}=${value}`)
    .join(',');
}

function typeMappings(mappings) {
  return Object.entries(mappings ?? {})
    .map(([source, target]) => `${source}=${target}`)
    .join(',');
}

async function runGenerator({ jarPath, inputSpec, output, config }) {
  const args = ['-jar', jarPath, 'generate', '-i', inputSpec, '-o', output, '-g', config.generatorName];

  if (config.apiPackage) args.push('--api-package', config.apiPackage);
  if (config.modelPackage) args.push('--model-package', config.modelPackage);
  if (config.invokerPackage) args.push('--invoker-package', config.invokerPackage);
  if (config.templateDir) {
    args.push('-t', path.resolve(repoRoot, config.templateDir));
  }

  const properties = additionalProperties(config.additionalProperties);
  if (properties) args.push('--additional-properties', properties);

  const mappings = typeMappings(config.typeMappings);
  if (mappings) args.push('--type-mappings', mappings);

  if (config.languageSpecificPrimitives?.length) {
    args.push(
      '--language-specific-primitives',
      config.languageSpecificPrimitives.join(','),
    );
  }

  try {
    await execFileAsync('java', args, {
      cwd: repoRoot,
      maxBuffer: 64 * 1024 * 1024,
    });
  } catch (error) {
    const outputText = [error.stdout, error.stderr].filter(Boolean).join('\n');
    throw new Error(
      `OpenAPI Generator failed for ${config.generatorName}:\n${outputText || error.message}`,
    );
  }
}

/**
 * Generate both HTTP boundaries under outputRoot/java and outputRoot/typescript.
 * Only those two dedicated directories are replaced. The source contract is never written.
 */
export async function generateAll(outputRoot) {
  const root = path.resolve(outputRoot);
  const toolchain = await loadToolchain();
  const inputSpec = path.resolve(repoRoot, toolchain.sourceContract);

  await validateContract(inputSpec);
  await mkdir(root, { recursive: true });

  const javaOutput = path.join(root, 'java');
  const typescriptOutput = path.join(root, 'typescript');
  await rm(javaOutput, { recursive: true, force: true });
  await rm(typescriptOutput, { recursive: true, force: true });
  await mkdir(javaOutput, { recursive: true });
  await mkdir(typescriptOutput, { recursive: true });

  const jarPath = await ensureGeneratorJar(toolchain);
  const [javaOptions, typescriptConfig] = await Promise.all([
    readJson(javaConfigPath),
    readJson(typescriptConfigPath),
  ]);
  const javaConfig = { ...javaOptions, templateDir: toolchain.templates.java };

  await runGenerator({
    jarPath,
    inputSpec,
    output: javaOutput,
    config: javaConfig,
  });
  await runGenerator({
    jarPath,
    inputSpec,
    output: typescriptOutput,
    config: typescriptConfig,
  });
}

export async function syncTree(source, destination) {
  await rm(destination, { recursive: true, force: true });
  await mkdir(path.dirname(destination), { recursive: true });
  await cp(source, destination, { recursive: true, force: true, errorOnExist: false });
}

async function main() {
  const toolchain = await loadToolchain();
  const temporaryRoot = await mkdtemp(path.join(tmpdir(), 'finresolve-api-generate-'));
  try {
    await generateAll(temporaryRoot);
    await syncTree(
      path.join(temporaryRoot, 'java'),
      path.resolve(repoRoot, toolchain.outputs.java),
    );
    await syncTree(
      path.join(temporaryRoot, 'typescript'),
      path.resolve(repoRoot, toolchain.outputs.typescript),
    );
    console.log(
      `Generated Java (${toolchain.outputs.java}) and TypeScript (${toolchain.outputs.typescript}) from ${toolchain.sourceContract}.`,
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
