#!/usr/bin/env node
// `pnpm check:android` (GOV-001): runs the Android gate (`./gradlew -p android check`)
// when android/ exists; otherwise prints a skip notice and exits 0. Extra args are
// passed through to Gradle, e.g. `pnpm check:android -Proborazzi.test.verify=true`.
import { spawnSync } from 'node:child_process';
import { existsSync } from 'node:fs';
import { join } from 'node:path';

const root = join(import.meta.dirname, '..');
const androidDir = join(root, 'android');
const isWindows = process.platform === 'win32';
const gradlew = join(androidDir, isWindows ? 'gradlew.bat' : 'gradlew');

if (!existsSync(gradlew)) {
  console.log('check:android — skipped: no android/ Gradle build in this checkout.');
  process.exit(0);
}

const args = ['-p', 'android', 'check', ...process.argv.slice(2)];
console.log(`check:android — ${isWindows ? 'gradlew.bat' : './gradlew'} ${args.join(' ')}`);
const result = spawnSync(gradlew, args, { cwd: root, stdio: 'inherit', shell: isWindows });
process.exit(result.status ?? 1);
