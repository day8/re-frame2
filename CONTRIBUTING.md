# Contributing to re-frame2

Thank you for the interest. re-frame2 is currently pre-alpha and the public
contribution surface is intentionally narrow while we stabilise the
specification. This document explains where to start and what shape a useful
contribution takes today.

## The spec is the artefact

Before changing code, please read [`spec/README.md`](spec/README.md). The
normative description of re-frame2 lives in [`spec/`](spec/) — about 22K lines
across 35+ documents. The CLJS reference in [`implementation/`](implementation/)
exists to validate the spec, not the other way round. A change to behaviour
almost always belongs in the spec first; the implementation change is the
downstream proof.

If a proposal would change behaviour, file an issue describing the spec
implication before opening a code-only PR. This saves both sides time when the
right answer turns out to be a spec edit you had not seen.

## Pre-alpha posture

We have not yet published artefacts to Clojars or npm. There is no backwards
compatibility surface to preserve: if a design is wrong, the fix is to make it
right, not to ship a shim. Please optimise contributions for elegance,
correctness, and completeness rather than minimal-diff caution. The bar is
high on purpose.

## Reporting bugs and proposing changes

For now, please use GitHub issues at
<https://github.com/day8/re-frame2/issues>. Useful issues include:

- a one-line summary of the problem or proposal;
- the spec section or file path the issue touches;
- a reproduction (link to a failing test, minimal example, or trace excerpt);
- what you think the right resolution looks like, even if tentative.

Internally we use [beads](https://github.com/gastownhall/beads) for issue
tracking (see [`docs/the-mayor-method.md`](docs/the-mayor-method.md) for the
workflow this repo is built with). External contributors do not need to learn
beads; a GitHub issue is sufficient and will be mirrored into the bead graph
by a maintainer when appropriate.

## Project setup

Top-level orientation lives in the repo's [`README.md`](README.md). For the
CLJS reference:

```bash
cd implementation
npm install            # one-time — installs shadow-cljs + React
npm run test:cljs      # Node-runtime CLJS tests (fast)
```

The full test matrix and per-artefact entry-points are documented in
[`TESTING.md`](TESTING.md). The fast pre-PR gate is `scripts/test-fast-pr.sh`
from the repo root.

## Pull request flow

- Branch from `main`.
- One coherent change per PR. Smaller is better; reviewers can hold one idea
  at a time.
- Descriptive title in the same shape as recent commits (run `git log
  --oneline` for examples).
- All CI gates must be green before review.
- Spec edits and impl edits can sit in the same PR when the impl change is the
  direct proof of the spec change; otherwise split them.
- No AI-attribution trailers in commit messages or PR descriptions.

## Version-pin convention

All published artefacts in this repo version-lockstep. The single source of
truth is the top-level [`VERSION`](VERSION) file. Pre-1.0, third-party tooling
pins (shadow-cljs, React, Reagent, Malli) are **exact** versions — no caret,
no tilde — so every artefact's lockfile and `deps.edn` resolves identically.
If you bump a shared dep, bump it everywhere in the same PR; CI gates this
across the matrix.

## Linting

Two complementary linters run on every PR:

- **clj-kondo** — bug-class linter (unresolved symbols, arity mismatches,
  redefined vars, unused bindings). Config lives at
  [`.clj-kondo/config.edn`](.clj-kondo/config.edn); editor integrations (Calva,
  CIDER, Cursive) read the same file.
- **Splint** — style-shape linter inspired by the
  [Clojure Style Guide](https://guide.clojure.style). Catches idiomatic-Clojure
  smells outside kondo's lexical analysis. Per-project config in
  [`.splint.edn`](.splint.edn).

**First-iteration posture:** both jobs are **report-only** — they surface
findings in the PR's check log but do not block the merge. The current
baseline is ~4 000 kondo warnings + 25 errors and ~1 600 Splint warnings
+ 4 errors, a mix of pre-existing test-fixture quirks (slashed keywords
in trace tests), kondo's "Unresolved var" noise on the framework's
dynamically-defined registration macros, and Splint's style suggestions
that need triaging before they earn their teeth. The gates will tighten
once the baseline is triaged.

Run locally before opening a PR:

```bash
# clj-kondo (install via https://github.com/clj-kondo/clj-kondo#installation)
clj-kondo --lint implementation tools examples testbeds

# Splint (no install — the alias pins the dep)
cd implementation
clojure -M:splint ../implementation ../tools ../examples ../testbeds
```

Editor integration is kondo-only for now — Calva/CIDER/Cursive consume the
shared `.clj-kondo/config.edn` automatically. Splint is CI-only at first
iteration.

## Security issues

Please do not file security-relevant problems as public issues or pull
requests. See [`SECURITY.md`](SECURITY.md) for the disclosure path.

## Code of Conduct

By participating you agree to abide by the project's
[Code of Conduct](CODE_OF_CONDUCT.md).
