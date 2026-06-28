# skills/shared/tests/

Regression suite for the shared skill leaves —
[`../retro-protocol.md`](../retro-protocol.md) (the retro protocol
consumed by `re-frame2-improver` and `re-frame2-pair-retro`) and
[`../tool-pair-surfaces.md`](../tool-pair-surfaces.md) (the Tool-Pair
direct-read privacy / surface enumeration).

Closes audit Finding 4 from
`ai/findings/skills-shared-audit-verification-2026-05-15.md`. Findings
1, 2, 3 of that audit landed as prose-only locks; this suite is the
regression backstop.

## Structural surfaces

Two structural tests, both plain `clojure.test` over file contents,
both CI-eligible. The CI step loops `skills/shared/tests/*_test.clj`,
so a new structural test added here is gated automatically.

| Test | Leaf under test | What it catches |
|---|---|---|
| [`retro_protocol_test.clj`](retro_protocol_test.clj) | [`../retro-protocol.md`](../retro-protocol.md) (plus `../issue-filing.md` + the consumer `issue-template.md`) | Prose-weakening: a section renamed, an attacker class dropped, a "MUST" softened to "should", the stable-placeholder convention removed; the shell-safety locks on the `gh issue` surfaces — body `--body-file`, inline `--title`, per-filing OS-temp body path, and the inline `gh issue list --search` query (Lock 4d) — going stale or losing the agent-authored / never-paste-evidence rule |
| [`tool_pair_surfaces_test.clj`](tool_pair_surfaces_test.clj) | [`../tool-pair-surfaces.md`](../tool-pair-surfaces.md) | Direct-read privacy drift: the fail-closed `rf/elide-wire-value` egress MUST stripped, the suppress-by-default opts / sentinels / `--allow-sensitive-reads` gate dropped, the one-shot direct-read tool names (`snapshot` / `get-path` / `read-sub` / `list-subscriptions` / `dispatch-dry-run`) going stale, the STREAMING reads (`subscribe` / `trace-window` / `watch-epochs`, with `projected-record` for epoch records) or the signal-recorder elision clause dropped from the privacy catalogue, the four partition-aware state-injection mutators (`replace-app-db!` / `reset-app-db!` / `replace-runtime-db!` / `replace-frame-state!`) under-named, or `restore-epoch` mis-taught as app-db-only rather than whole-frame-state |

## Behavioural surface

| Surface | File | Runner | What it catches |
|---|---|---|---|
| Behavioural | [`fixtures/`](fixtures) | human / AI replay | Agent compliance: refusing injected `gh issue create`, masking JWTs in inline output, gating evidence-shaped Edits |

The structural test is CI-eligible (it's plain `clojure.test` over
file contents). The behavioural fixtures are document-runnable; see
[`fixtures/README.md`](fixtures/README.md) for the replay mechanism.

## Why this directory exists

The corpus convention is that **only `re-frame2-pair/` and `shared/`
ship a `tests/` directory** — see `skills/README.md` §"Test-fixture
discipline." `re-frame2-pair/` qualifies because it drives a live
runtime (nREPL attach, app-db mutation, epoch reads), so its surface is
testable in the conventional sense.

`skills/shared/` (this directory) is the second exception, and it earns
it for a different reason: `retro-protocol.md` is a **security
boundary**, not just a doc leaf. A prior audit found four issues there;
three landed prose-only fixes. The audit's Finding 4 explicitly called
for a regression suite so a future drift of the prose doesn't silently
re-open the boundary.

The structural tests are the cheap class of drift detector (would catch
"someone deleted §Untrusted-evidence boundary" or "the direct-read MUST
was downgraded"); the document fixtures are the expensive but
high-fidelity assertion (would catch "agent obeys an injected
`gh issue create`"). Together they cover both axes.

## Running

`bb` resolves the test path relative to the current working directory,
so the path you pass depends on where you run it from:

```bash
# Run every shared structural test (the shape CI uses) — from the repo root:
for f in skills/shared/tests/*_test.clj; do bb "$f"; done

# Or run one at a time. From the REPO ROOT (repo-relative paths):
bb skills/shared/tests/retro_protocol_test.clj
bb skills/shared/tests/tool_pair_surfaces_test.clj

# Or from skills/shared/ (the paths relative to that dir):
bb tests/retro_protocol_test.clj
bb tests/tool_pair_surfaces_test.clj
```

Exit code 0 = all structural locks pass. Non-zero = drift detected;
the failing assertion's message names which lock loosened and points
at the relevant leaf section + audit / bead finding.

The behavioural fixtures don't have a runner — see
[`fixtures/README.md`](fixtures/README.md) for the manual replay
protocol.

## Wiring into CI

The structural tests are wired into `.github/workflows/test.yml`
through the `skills-structural` job, but only when `skills/shared/**`
or the shared skill-test workflow surface changes. The job's "Run
shared structural tests" step loops `skills/shared/tests/*_test.clj`,
so every `*_test.clj` added here is gated automatically — no per-file
workflow edit needed. Behavioural replay fixtures remain
manual/diagnostic; they are intentionally not required PR coverage.

## Cross-references

- [`../retro-protocol.md`](../retro-protocol.md) — the protocol leaf under test.
- [`../../README.md`](../../README.md) §"Test-fixture discipline" —
  documents why `re-frame2-pair/` is the only other skill with a
  `tests/` dir, and the carve-out this directory takes for security
  boundaries.
- `skills/re-frame2-pair/tests/prompts/prompt_regression_test.clj` —
  the corpus's other prose-regression suite (recipe-shape drift
  detector); this suite mirrors its substrate.
