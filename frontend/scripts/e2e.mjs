// One-command Playwright run on an isolated stack (docker-compose.e2e.yml, project cadence-e2e):
//   TimescaleDB (tmpfs, :15433) [+ Keycloak :18081 for oidc] -> backend bootRun on the host (:18080) -> web bundle
//   (expo export + scripts/e2e-serve.mjs) -> playwright test -> teardown. The teardown also runs when a step or a test fails and on
//   Ctrl-C: it stops the backend and the bundle server (process groups, then whatever still listens on their ports) and
//   removes the compose project with its volumes.
// Run: npm run e2e (dev-headers bundle, port 19005) or npm run e2e:oidc (Keycloak login, port 19006). Extra arguments go
// to `playwright test`, e.g. npm run e2e -- -g smoke. Needs Docker and JAVA_HOME for Gradle (JDK 21).
import { spawn, spawnSync } from 'node:child_process';
import { closeSync, mkdirSync, openSync, readdirSync, readFileSync, rmSync } from 'node:fs';
import net from 'node:net';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const FRONTEND = fileURLToPath(new URL('..', import.meta.url));
const ROOT = path.resolve(FRONTEND, '..');
const OUT = path.join(FRONTEND, 'e2e-results');
const COMPOSE_FILE = path.join(ROOT, 'docker-compose.e2e.yml');
const PROJECT = 'cadence-e2e';
const API_PORT = 18080;
const DB_PORT = 15433;
const KEYCLOAK_PORT = 18081;
const API_URL = `http://localhost:${API_PORT}`;
const ISSUER = `http://localhost:${KEYCLOAK_PORT}/realms/cadence`;

const MODES = {
  main: {
    config: 'e2e/playwright.config.ts',
    webPort: 19005,
    profile: [],
    ports: [DB_PORT, API_PORT, 19005],
    backendEnv: { SPRING_PROFILES_ACTIVE: 'dev-headers' },
    bundleEnv: { EXPO_PUBLIC_AUTH_MODE: 'dev-headers' },
  },
  oidc: {
    config: 'e2e/playwright.oidc.config.ts',
    webPort: 19006,
    profile: ['--profile', 'oidc'],
    ports: [DB_PORT, KEYCLOAK_PORT, API_PORT, 19006],
    backendEnv: { APP_SECURITY_ISSUER_URI: ISSUER },
    bundleEnv: { EXPO_PUBLIC_AUTH_MODE: 'oidc', EXPO_PUBLIC_OIDC_ISSUER: ISSUER },
  },
};

const modeName = process.argv[2];
const mode = MODES[modeName];
if (!mode) {
  console.error(`Usage: node scripts/e2e.mjs <${Object.keys(MODES).join('|')}> [playwright args]`);
  process.exit(2);
}
const playwrightArgs = process.argv.slice(3);
const webUrl = `http://localhost:${mode.webPort}`;
const bundleDir = path.join(OUT, `bundle-${modeName}`);
const logDir = path.join(OUT, 'logs');

// Nothing from the developer's shell may steer the run (dev datasource, AI key, other API URL).
const baseEnv = Object.fromEntries(
  Object.entries(process.env).filter(([key]) => !/^(SPRING_|APP_|FLYWAY_|DEEPSEEK_|EXPO_PUBLIC_|E2E_)/.test(key))
);

const started = Date.now();
const log = (message) => console.log(`[e2e ${((Date.now() - started) / 1000).toFixed(0).padStart(3)}s] ${message}`);
const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

function tail(file, lines = 40) {
  try {
    return readFileSync(file, 'utf8').split('\n').slice(-lines).join('\n');
  } catch {
    return '(no log)';
  }
}

function compose(args, { quiet = false } = {}) {
  return spawnSync('docker', ['compose', '-p', PROJECT, '-f', COMPOSE_FILE, ...args], {
    stdio: quiet ? 'pipe' : 'inherit',
    encoding: 'utf8',
  });
}

function listens(port) {
  const probe = (host) =>
    new Promise((resolve) => {
      const socket = net.connect({ port, host });
      socket.setTimeout(1000, () => socket.destroy());
      socket.once('connect', () => (socket.destroy(), resolve(true)));
      socket.once('error', () => resolve(false));
      socket.once('close', () => resolve(false));
    });
  return Promise.all([probe('127.0.0.1'), probe('::1')]).then(([v4, v6]) => v4 || v6);
}

function listeners(port) {
  const result = spawnSync('lsof', ['-nP', '-t', `-iTCP:${port}`, '-sTCP:LISTEN'], { encoding: 'utf8' });
  return result.stdout.split('\n').filter(Boolean).map(Number);
}

function describeOwner(port) {
  const result = spawnSync('lsof', ['-nP', `-iTCP:${port}`, '-sTCP:LISTEN'], { encoding: 'utf8' });
  return result.stdout.trim() || `(lsof shows no listener for ${port})`;
}

/** A long-running child in its own process group, output in logs/<name>.log. */
function start(name, command, args, { cwd, env }) {
  const file = path.join(logDir, `${name}.log`);
  const fd = openSync(file, 'w');
  const child = spawn(command, args, { cwd, env, detached: true, stdio: ['ignore', fd, fd] });
  closeSync(fd);
  const proc = { name, child, file, exited: false };
  child.once('exit', (code, signal) => {
    proc.exited = true;
    log(`${name} exited (code ${code}, signal ${signal})`);
  });
  return proc;
}

/** Runs a command to completion with its output in logs/<name>.log; rejects with the log tail on failure. */
function runLogged(name, command, args, { cwd, env }) {
  const proc = start(name, command, args, { cwd, env });
  return new Promise((resolve, reject) => {
    proc.child.once('exit', (code) =>
      code === 0 ? resolve() : reject(new Error(`${name} failed (exit ${code}), tail of ${proc.file}:\n${tail(proc.file)}`))
    );
  });
}

async function waitFor(label, check, { timeoutMs, proc }) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    if (proc?.exited) throw new Error(`${label}: ${proc.name} exited before it was ready, tail of ${proc.file}:\n${tail(proc.file)}`);
    try {
      if (await check()) return;
    } catch {
      // not ready yet
    }
    await sleep(1000);
  }
  throw new Error(`${label}: not ready after ${timeoutMs / 1000}s${proc ? `, tail of ${proc.file}:\n${tail(proc.file)}` : ''}`);
}

const httpOk = async (url) => (await fetch(url, { signal: AbortSignal.timeout(3000) })).ok;

async function stop(proc, port) {
  if (!proc) return;
  if (!proc.exited) {
    try {
      process.kill(-proc.child.pid, 'SIGTERM');
    } catch {
      // group already gone
    }
    for (let i = 0; i < 30 && !proc.exited; i += 1) await sleep(500);
    if (!proc.exited) {
      log(`${proc.name} ignored SIGTERM, sending SIGKILL`);
      try {
        process.kill(-proc.child.pid, 'SIGKILL');
      } catch {
        // group already gone
      }
    }
  }
  // bootRun runs the application in a JVM forked by the Gradle daemon, outside the process group: whatever still
  // listens on a port this run started (it was checked free) belongs to the run.
  for (let i = 0; i < 20 && (await listens(port)); i += 1) {
    for (const pid of listeners(port)) {
      log(`${proc.name}: stopping leftover pid ${pid} on port ${port}`);
      try {
        process.kill(pid, i < 10 ? 'SIGTERM' : 'SIGKILL');
      } catch {
        // already gone
      }
    }
    await sleep(500);
  }
  if (await listens(port)) log(`WARNING: port ${port} is still in use after ${proc.name} was stopped`);
}

function projectLeftovers() {
  const label = `label=com.docker.compose.project=${PROJECT}`;
  const query = (args) => spawnSync('docker', args, { encoding: 'utf8' }).stdout.split('\n').filter(Boolean);
  return [
    ...query(['ps', '-a', '-q', '--filter', label]).map((id) => `container ${id}`),
    ...query(['volume', 'ls', '-q', '--filter', label]).map((name) => `volume ${name}`),
    ...query(['network', 'ls', '-q', '--filter', label]).map((id) => `network ${id}`),
  ];
}

function composeDown() {
  log(`docker compose -p ${PROJECT} down -v --remove-orphans`);
  const result = compose(['--profile', '*', 'down', '-v', '--remove-orphans'], { quiet: true });
  if (result.status !== 0) log(`WARNING: compose down failed: ${(result.stderr || result.stdout).trim()}`);
}

function verifyBundle() {
  const dir = path.join(bundleDir, '_expo', 'static', 'js', 'web');
  const files = readdirSync(dir).filter((name) => name.endsWith('.js'));
  const expected = modeName === 'oidc' ? [API_URL, ISSUER] : [API_URL];
  for (const needle of expected) {
    if (!files.some((name) => readFileSync(path.join(dir, name), 'utf8').includes(needle))) {
      throw new Error(`The exported bundle does not contain ${needle}: it would talk to a different backend`);
    }
  }
  log(`bundle checked: it points to ${expected.join(' and ')}`);
}

/** Gradle 8.14 runs on JDK 17-24 and fails on JDK 25 with a bare "25.0.2": say so before anything is started or cleared. */
function requireGradleJdk() {
  const java = process.env.JAVA_HOME ? path.join(process.env.JAVA_HOME, 'bin', 'java') : 'java';
  const major = Number(spawnSync(java, ['-version'], { encoding: 'utf8' }).stderr?.match(/version "(\d+)/)?.[1]);
  if (!(major >= 17 && major <= 24)) {
    throw new Error(`Gradle needs JDK 21 (17-24 work), but ${java} is ${major || 'not runnable'}. Set JAVA_HOME to a JDK 21.`);
  }
}

let backend;
let web;
let cleaned;
let exitCode = 1;

async function cleanup() {
  cleaned ??= (async () => {
    log('teardown');
    await stop(web, mode.webPort);
    await stop(backend, API_PORT);
    composeDown();
    const left = projectLeftovers();
    if (left.length > 0) {
      log(`ERROR: compose project ${PROJECT} left behind: ${left.join(', ')}`);
      exitCode = 1;
    } else {
      log(`teardown done: no containers, volumes or networks of ${PROJECT} left`);
    }
  })();
  return cleaned;
}

for (const signal of ['SIGINT', 'SIGTERM']) {
  process.on(signal, () => {
    log(`${signal} received`);
    exitCode = 130;
    cleanup().finally(() => process.exit(exitCode));
  });
}

try {
  if (!process.env.JAVA_HOME) log('WARNING: JAVA_HOME is not set, Gradle uses the java found on PATH');
  requireGradleJdk();
  rmSync(OUT, { recursive: true, force: true });
  mkdirSync(logDir, { recursive: true });

  log(`mode ${modeName}: stale ${PROJECT} resources are removed first, then ports ${mode.ports.join(', ')} must be free`);
  composeDown();
  for (const port of mode.ports) {
    if (await listens(port)) {
      throw new Error(`Port ${port} is in use by another process, the run does not touch it:\n${describeOwner(port)}`);
    }
  }

  log(`docker compose up (${mode.profile.length > 0 ? 'timescaledb + keycloak' : 'timescaledb'})`);
  const up = compose([...mode.profile, 'up', '-d', '--wait']);
  if (up.status !== 0) throw new Error('docker compose up failed');

  log(`backend: ./gradlew :modules:app:bootRun on ${API_URL} (log: ${path.join(logDir, 'backend.log')})`);
  backend = start('backend', './gradlew', [':modules:app:bootRun', '--console=plain'], {
    cwd: path.join(ROOT, 'backend'),
    env: {
      ...baseEnv,
      ...mode.backendEnv,
      SERVER_PORT: String(API_PORT),
      SPRING_DATASOURCE_URL: `jdbc:postgresql://localhost:${DB_PORT}/cadence`,
      // The scenarios book far more than the production limits allow.
      APP_RATE_LIMIT_SLOTS_PER_MINUTE_PER_IP: '1000',
      APP_RATE_LIMIT_BOOKINGS_PER_HOUR_PER_TENANT: '1000',
      APP_RATE_LIMIT_BOOKINGS_PER_HOUR_PER_IP: '1000',
      APP_RATE_LIMIT_BOOKINGS_PER_DAY_PER_PHONE: '100',
      // Never a live model in a test run.
      APP_AI_PROVIDER: 'stub',
    },
  });

  log(`bundle: expo export (EXPO_PUBLIC_API_URL=${API_URL}, ${Object.entries(mode.bundleEnv).map(([k, v]) => `${k}=${v}`).join(', ')})`);
  await runLogged('export', 'npx', ['expo', 'export', '--platform', 'web', '--clear', '--output-dir', bundleDir], {
    cwd: FRONTEND,
    env: { ...baseEnv, ...mode.bundleEnv, EXPO_PUBLIC_API_URL: API_URL, CI: '1', EXPO_NO_TELEMETRY: '1' },
  });
  verifyBundle();

  web = start('serve', 'node', ['scripts/e2e-serve.mjs', bundleDir, String(mode.webPort)], { cwd: FRONTEND, env: baseEnv });
  await waitFor(`bundle server ${webUrl}`, () => httpOk(webUrl), { timeoutMs: 60_000, proc: web });
  log(`bundle served on ${webUrl}`);

  // The seeder is an ApplicationRunner: the port answers before the demo data is in, its closing log line is the signal.
  await waitFor(
    `backend ${API_URL}`,
    async () => (await httpOk(`${API_URL}/api/public/health`)) && /Demo (tenants .* seeded|data already present)/.test(tail(backend.file, 400)),
    { timeoutMs: 420_000, proc: backend }
  );
  log('backend is up, demo data seeded');

  log(`playwright test -c ${mode.config} ${playwrightArgs.join(' ')}`.trimEnd());
  const playwright = spawn(path.join(FRONTEND, 'node_modules', '.bin', 'playwright'), ['test', '-c', mode.config, ...playwrightArgs], {
    cwd: FRONTEND,
    stdio: 'inherit',
    env: {
      ...baseEnv,
      E2E_MODE: modeName,
      E2E_API_URL: API_URL,
      E2E_BASE_URL: webUrl,
      E2E_OUT_DIR: OUT,
      E2E_COMPOSE_FILE: COMPOSE_FILE,
      E2E_COMPOSE_PROJECT: PROJECT,
      ...(modeName === 'oidc' ? { E2E_ISSUER: ISSUER } : {}),
    },
  });
  exitCode = await new Promise((resolve) => playwright.once('exit', (code) => resolve(code ?? 1)));
  log(`playwright finished with exit code ${exitCode}`);
} catch (error) {
  console.error(`\n[e2e] FAILED: ${error.message}\n`);
  exitCode = 1;
} finally {
  await cleanup();
  log(`total ${((Date.now() - started) / 1000).toFixed(0)}s, exit code ${exitCode}`);
}
process.exit(exitCode);
