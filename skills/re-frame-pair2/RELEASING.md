# Releasing re-frame-pair2

> **Note (consolidation):** As of re-frame2 `v0.0.1.alpha` prep, this skill lives at
> `re-frame2/skills/re-frame-pair2/`. The standalone `day8/re-frame-pair2`
> repo is no longer the source of truth. The standalone `release.yml`
> workflow described below has been removed from this copy; release flow
> now happens (or will happen) as part of re-frame2's release pipeline.
> This document is retained as historical reference for the npm publish
> mechanics and version-bump checklist.

Releases go out through the `release.yml` GitHub Actions workflow when
a semver tag is pushed. This doc captures the checklist and the expected
tag formats.

## Prerequisites

- `NPM_TOKEN` secret set on the repo, with publish scope on `@day8` (npm Automation token).
- Locally: a checkout of `main`, npm-cli installed (only needed if you want to dry-run `npm pack` before tagging).
- You have maintainer rights on the `day8` npm scope.

## Version numbers

Single source of truth: `package.json` `version`. One other file must match:

- `package.json`
- `.claude-plugin/plugin.json`

The release workflow cross-checks these at publish time and fails if they drift.

## Cutting a release

1. **Decide the version.** Follow semver. Pre-1.0, everything is pre-release: use `0.x.y-alpha.N`, `0.x.y-beta.N`, `0.x.y-rc.N`.
2. **Update the two version strings** (package.json, plugin.json):
   ```bash
   npm version 0.1.0-alpha.2 --no-git-tag-version
   # then bump .claude-plugin/plugin.json's "version" by hand to match
   ```
3. **Commit the version bump** on `main`:
   ```bash
   git add package.json .claude-plugin/plugin.json
   git commit -m "Release v0.1.0-alpha.2"
   ```
4. **Tag and push:**
   ```bash
   git tag v0.1.0-alpha.2
   git push origin main --tags
   ```
5. **Watch the workflow.** `release.yml` will:
   - verify tag == package.json version
   - verify plugin.json matches
   - smoke-test the babashka shims
   - (future) run CLJS unit tests against the fixture
   - `npm publish` with public access under `@day8` scope
   - create a GitHub release with auto-generated notes; marked prerelease for `-alpha`/`-beta`/`-rc`
6. **Verify publish:** `npm view @day8/re-frame-pair2 versions --json` should list the new version.
7. **Smoke-test install** from a clean machine:
   ```bash
   npx skills add day8/re-frame-pair2
   # or, when using the plugin path:
   # /plugin install re-frame-pair2@day8
   ```

## Tag format

Regex the workflow accepts: `v[0-9]+.[0-9]+.[0-9]+*`

Examples:
- `v0.1.0-alpha.1`
- `v0.1.0-rc.1`
- `v1.0.0`

## Rolling back a bad release

npm doesn't allow unpublishing published versions after 72 hours, and even inside 72 hours it's discouraged. The normal path is:

1. Publish a patched version (e.g., `v0.1.0-alpha.2` -> `v0.1.0-alpha.3`).
2. `npm deprecate @day8/re-frame-pair2@0.1.0-alpha.2 "Superseded by 0.1.0-alpha.3 — bug X"`.

## Release notes

Generated automatically from commits + PRs since the previous tag by `action-gh-release`. To shape them, use conventional-commit-ish prefixes on PR titles (`feat:`, `fix:`, `docs:`, `chore:`).

## Pre-1.0 release cadence

While pre-alpha:

- Tag `v0.1.0-alpha.N` for major surface changes.
- Tag `v0.1.0-beta.N` once the surfaces are validated against a fixture re-frame2 app.
- Tag `v0.1.0-rc.N` once there's a working end-to-end path.
- `v0.1.0` when the full v1 scope is implemented and tested.

## Distributing via Claude Code's plugin path

`npm publish` handles the Agent Skill distribution (`npx skills add day8/re-frame-pair2`). For Claude Code Plugin distribution (`/plugin install`), the plugin discovery currently pulls from the same repo — no separate publish step needed.
