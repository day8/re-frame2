# skills/shared/tests/

Regression suite for [`../retro-protocol.md`](../retro-protocol.md) —
the shared retro protocol consumed by `re-frame2-improver` and
`re-frame2-pair-retro`.

Closes audit Finding 4 from
`ai/findings/skills-shared-audit-verification-2026-05-15.md`. Findings
1, 2, 3 of that audit landed as prose-only locks; this suite is the
regression backstop.

## Two surfaces

| Surface | File | Runner | What it catches |
|---|---|---|---|
| Structural | [`retro_protocol_test.clj`](./retro_protocol_test.clj) | `bb` | Prose-weakening: a section renamed, an attacker class dropped, a "MUST" softened to "should", the stable-placeholder convention removed |
| Behavioural | [`fixtures/`](./fixtures/) | human / AI replay | Agent compliance: refusing injected `gh issue create`, masking JWTs in inline output, gating evidence-shaped Edits |

The structural test is CI-eligible (it's plain `clojure.test` over
file contents). The behavioural fixtures are document-runnable; see
[`fixtures/README.md`](./fixtures/README.md) for the replay mechanism.

## Why this directory exists

The corpus convention is that **only `re-frame2-pair/` ships a
`tests/` directory** — see `skills/README.md` §"Test-fixture
discipline." `re-frame2-pair/` is the exception because it drives a
live runtime (nREPL attach, app-db mutation, epoch reads).

`skills/shared/retro-protocol.md` warrants the second exception for a
different reason: it is a **security boundary**. A prior audit found
four issues there; three landed prose-only fixes. The audit's Finding
4 explicitly called for a regression suite so a future drift of the
prose doesn't silently re-open the boundary.

The structural test is the cheap class of drift detector (would catch
"someone deleted §Untrusted-evidence boundary"); the document
fixtures are the expensive but high-fidelity assertion (would catch
"agent obeys an injected `gh issue create`"). Together they cover
both axes.

## Running

```bash
# From the repo root, or from skills/shared/.
bb tests/retro_protocol_test.clj

# Or absolute:
bb skills/shared/tests/retro_protocol_test.clj
```

Exit code 0 = all structural locks pass. Non-zero = drift detected;
the failing assertion's message names which lock loosened and points
at the relevant retro-protocol.md section + audit finding.

The behavioural fixtures don't have a runner — see
[`fixtures/README.md`](./fixtures/README.md) for the manual replay
protocol.

## Wiring into CI

The structural test is wired into `.github/workflows/test.yml` through
the `skills-structural` job, but only when `skills/shared/**` or the
shared skill-test workflow surface changes. Behavioural replay fixtures
remain manual/diagnostic; they are intentionally not required PR
coverage.

## Cross-references

- [`../retro-protocol.md`](../retro-protocol.md) — the protocol leaf under test.
- [`../../README.md`](../../README.md) §"Test-fixture discipline" —
  documents why `re-frame2-pair/` is the only other skill with a
  `tests/` dir, and the carve-out this directory takes for security
  boundaries.
- `skills/re-frame2-pair/tests/prompts/prompt_regression_test.clj` —
  the corpus's other prose-regression suite (recipe-shape drift
  detector); this suite mirrors its substrate.
