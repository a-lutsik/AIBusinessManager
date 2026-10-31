import { spawnSync } from 'node:child_process';
import { COMPOSE_FILE, COMPOSE_PROJECT } from './env';

/** Runs one statement as the schema owner in the TimescaleDB container of the e2e compose project; returns psql's rows. */
export function sql(statement: string): string {
  const result = spawnSync(
    'docker',
    ['compose', '-p', COMPOSE_PROJECT, '-f', COMPOSE_FILE, 'exec', '-T', 'timescaledb', 'psql', '-U', 'cadence', '-d', 'cadence', '-v', 'ON_ERROR_STOP=1', '-At', '-c', statement],
    { encoding: 'utf8' }
  );
  if (result.status !== 0) throw new Error(`SQL failed (${result.status}): ${result.stderr.trim()}\n${statement}`);
  return result.stdout.trim();
}
