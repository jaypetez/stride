/**
 * Conventional Commits enforcement (GOAL.md §10 phase-3).
 * Applied locally via the Husky `commit-msg` hook and in CI via
 * `.github/workflows/policy.yml`.
 */
export default {
  extends: ['@commitlint/config-conventional'],
  // Dependabot hard-codes a capitalized "Bump ..." subject that trips
  // `subject-case`. Exempt only its dependency-bump commits; every other
  // commit stays fully linted.
  ignores: [(message) => /^build\(deps[^)]*\): Bump /.test(message)],
  rules: {
    // Allow the scopes/types this monorepo actually uses; keep the default set.
    'body-max-line-length': [0, 'always'],
    'footer-max-line-length': [0, 'always'],
  },
};
