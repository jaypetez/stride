#!/usr/bin/env node
// Android versioning (REL-003). android/version.json holds versionName/versionCode;
// Gradle reads it. Run as part of `pnpm version` (after `changeset version`):
// when android/ changed since the last `android-v*` tag, versionCode goes up by
// one and versionName gets a patch bump (or `--minor` / `--major`).
//   node scripts/android-version.mjs            # bump if android/ changed
//   node scripts/android-version.mjs --check    # CI: fail unless versionCode > last tag's
import { execFileSync } from 'node:child_process';
import { readFileSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';

const root = join(import.meta.dirname, '..');
const versionFile = join(root, 'android', 'version.json');

export function bump(version, level = 'patch') {
  const [major, minor, patch] = version.versionName.split(/[.-]/).map(Number);
  const versionName =
    level === 'major'
      ? `${major + 1}.0.0`
      : level === 'minor'
        ? `${major}.${minor + 1}.0`
        : `${major}.${minor}.${patch + 1}`;
  return { versionName, versionCode: version.versionCode + 1 };
}

export function isIncreasing(previous, next) {
  return next.versionCode > previous.versionCode;
}

function git(...args) {
  return execFileSync('git', args, { cwd: root, encoding: 'utf8' }).trim();
}

function lastAndroidTag() {
  try {
    return git('describe', '--tags', '--abbrev=0', '--match', 'android-v*');
  } catch {
    return null;
  }
}

function readVersion(ref) {
  if (!ref) return JSON.parse(readFileSync(versionFile, 'utf8'));
  return JSON.parse(git('show', `${ref}:android/version.json`));
}

function main(argv) {
  const tag = lastAndroidTag();
  if (argv.includes('--check')) {
    if (!tag) {
      console.log('android-version: no android-v* tag yet; nothing to compare.');
      return 0;
    }
    let previous;
    try {
      previous = readVersion(tag);
    } catch {
      console.log(`android-version: ${tag} has no android/version.json; nothing to compare.`);
      return 0;
    }
    const current = readVersion(null);
    if (!isIncreasing(previous, current)) {
      console.error(
        `android-version: versionCode ${current.versionCode} must be > ${previous.versionCode} (${tag}).`,
      );
      return 1;
    }
    console.log(`android-version: ${previous.versionCode} → ${current.versionCode} ok.`);
    return 0;
  }
  const changed = tag
    ? git('diff', '--name-only', `${tag}..HEAD`, '--', 'android').length > 0
    : true;
  if (!changed) {
    console.log('android-version: android/ unchanged since last release; not bumping.');
    return 0;
  }
  const level = argv.includes('--major') ? 'major' : argv.includes('--minor') ? 'minor' : 'patch';
  const next = bump(readVersion(null), level);
  writeFileSync(versionFile, `${JSON.stringify(next, null, 2)}\n`);
  console.log(`android-version: ${next.versionName} (${next.versionCode})`);
  return 0;
}

if (import.meta.url === `file://${process.argv[1]}`) process.exit(main(process.argv.slice(2)));
