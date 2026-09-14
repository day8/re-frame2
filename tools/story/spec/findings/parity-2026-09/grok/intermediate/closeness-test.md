# Phase B — Standing closeness test

Reusable protocol. This file is the test; `scoreboard.md` is one run's measurements. Do not mix them.

- **Invalidation:** Storybook major (or MCP/Test addon leaving preview); a Story UI EPIC landing; `docs/story/01` rewritten; a P1 honesty failure on the run/test path.
- **Budget:** a re-run should fit in one research session: docs+spec+spot-check all 17 jobs; **execute** the subset in §Execute. Cut jobs before adding taxonomies.
- **Comparator default:** Storybook 10.6 practical workflow (OSS + official extras named). Name a second comparator when Storybook is the wrong peer.

## Fields (do not collapse)

| Field | Vocabulary |
|---|---|
| **Capability** | `ABSENT` / `PARTIAL` / `COMPLETE` / `SPEC-ONLY` / `TARGET` / `OUT` |
| **Discoverability** | `TAUGHT` / `UNDERMARKETED` / `INTERNAL` |
| **Evidence** | `EXERCISED` / `SOURCE-TRACED` / `DOCUMENTED-ONLY` / `UNVERIFIED` |
| **Comparison** | `BEHIND` / `MATCH` / `AHEAD` / `DIVERGENT` vs a **named** comparator |

Rules:

- `DIVERGENT` only if the *job* is still served another way you can name.
- `AHEAD` needs a structural reason, not enthusiasm.
- `COMPLETE` + `DOCUMENTED-ONLY` is weaker than `COMPLETE` + `EXERCISED`.
- Ceremony: concepts, encodings, files, boot calls. A `MATCH` at 2× ceremony is `BEHIND`.
- Code at trunk is ground truth for "today." Spec 007 wins contract disputes.
- Many minor `MATCH`es do not cancel one adoption-blocker.
- Fingerprint ≠ pixel diff ≠ approval UI.
- Built-in vs official addon vs paid service must be named on the comparator side.

## Jobs

| Id | Job | Primary comparator | Positive/negative notes |
|---|---|---|---|
| J1 | First hour *and* existing-app install; change app code and see reload | Storybook `npm create storybook@latest` | **Negative control candidate:** Story is pre-Clojars. |
| J2 | Navigate a large catalog (search, keyboard, restore) | Storybook | Budgets live in `018` §10.1 — cite, do not re-measure unless unmeasurable. |
| J3 | Cheap view-state (controls/args), including schema-derived | Storybook Controls; Widgetbook v4 as schema peer | |
| J4 | Several states at once without leakage — reset, stale async, CSS, focus, portals | Histoire/Portfolio for the grid; Storybook iframe for CSS/focus | **Positive control:** frame isolation (if verified). |
| J5 | Mock a failure of any `reg-fx` | Storybook+MSW (HTTP only as the common extra) | **Positive control:** `force-fx-stub` (if verified). |
| J6 | Real SPA journey (loading/empty/error/retry) with clocks and real events | Storybook play+loaders+MSW | |
| J7 | Document a component and its states in the workshop | Storybook Autodocs/MDX; Lookbook | MDX refusal is not a docs pass by itself. |
| J8 | Visible state *is* a test, UI + CI | Storybook Test / portable stories | `:cannot-run` is a sub-axis. |
| J9 | Failure explains itself; restore vs replay | Storybook Interactions; Chromatic diffs | |
| J10 | Record an interaction as data | Storybook play + test codegen | |
| J11 | Share/export honestly | Storybook share + `storybook build`; Chromatic hosting | |
| J12 | Agent: discover, run, read failure, propose fix, same artefact | Storybook MCP (preview) | Do not assume peers lack MCP. |
| J13 | Design-system matrix (theme × viewport × locale or nine-states) | Chromatic Modes | |
| J14 | A11y of subject *and* chrome; automated vs keyboard vs human | Storybook addon-a11y | |
| J15 | VR *hook* + honest review path, not a first-party service | Chromatic (paid extra) | **Negative control:** we refuse to *be* Chromatic. Job = stable key + iframe + a review path. |
| J16 | Extend (decorator, panel) without a marketplace | Storybook addons | |
| J17 | Upgrade a cheap state toward real integration in place | Storybook `useArgs` / loaders | Fidelity ladder. |

## Execute (minimum each run)

Separate protocol from what you obtained.

1. **J1 stall log.** Follow `docs/story/index.md` Install + `docs/story/01-first-variant.md` *literally*. Record every stall, skip, or retired spelling. Do not execute `npm create storybook` unless you need a timing; the published recipe is enough for ceremony count.
2. **J3/J4 in a real shell** if a browser or Playwright is available: login-form testbed `#/stories`.
3. **J5 or J6** from testbed source + a pinning test you actually read (or run).
4. **J8/J9** a deliberate failing assertion → evidence. Distinguish restore vs replay.
5. **J12** name tools from `tools/story-mcp/tool-descriptors.edn`. If MCP is not driven, mark `UNVERIFIED`, not `AHEAD`.
6. **J8/J9 promotion.** Register (or inline) a variant whose terminal assertion is false. `story/run` must be `:fail`. Promote via `artifact->variant-body` / the UI helper. Re-run the child: it must still `:fail` for the **same** expectation. A `:pass` with empty `:assertions` is a red row, not a win.
7. **J17 upgrade.** Call `upgrade-snippet` on a `:sub-overrides` parent targeting `:real-setup`. `read-string` must succeed. The compiled child plan must not still carry the parent's pin unless the author kept it.
8. **Install snippet compile.** Copy `docs/story/index.md` §Install into a scratch app. Count compile errors. A 2-arg `mount-shell!` or missing `@xyflow/react` is a J1 red cell, not a reader failure.
9. **Projection agreement.** Explain args vs canvas heading; auto-grid sidebar count vs cells rendered; Test evidence-row is a control, not a placeholder string.

If the browser cannot open: UI-shaped jobs are `UNVERIFIED` on evidence, not `ABSENT` on capability.

## Controls

- **Positive:** J4 frame isolation; J5 `force-fx-stub` — at least one must be `EXERCISED` or `SOURCE-TRACED` with a pinning test.
- **Negative:** J1 pre-Clojars / no `create storybook` analogue; J15 we are not a VR service. An all-green scoreboard is suspicious.
- **Honesty prior:** `ai/findings/story-review.md` (2026-09-13) found P1s on checks/tape/no-adapter. **Re-verify against the SHA of this run** before treating them as current. Commits on this tree already name fixes (`rf2-b2mt`, `rf2-3okc`, `rf2-poty`).

## Automatable residue

Candidates to pin later (no new platform): `015-Test-Coverage.md` already tracks many chrome contracts. Prefer extending that matrix over a new gate.

| Job | Could a structural test guard it? |
|---|---|
| J5 `force-fx-stub` | Yes — already in testbeds (`login_form/stories.cljs`, `counter_with_stories`). |
| J8 `story/run` / `:cannot-run` / no-adapter is `:error` | Yes — `story_runtime_test.clj` `no-adapter-installed-run-is-an-error-not-a-pass`. |
| J8 `reg-check` actually executes | Yes — `rf2-b2mt` / runtime `plan-checks`. |
| J11 snapshot-identity stability | Yes — identity tests. |
| J12 tool names vs `tool-descriptors.edn` | Already drift-gated. |
| J2 search latency, J3 edit-to-render | Only if `018` §10.1 already has a deterministic gate; do not invent a browser perf CI. |
| J4 CSS/focus/portals | Human/Playwright — do not gate on vibes. |
| J1 install ceremony | Human. |
| J15 pixel review | Human + downstream service. |

## Re-run procedure (no this prompt)

1. Pin SHA + date + Storybook current major (npm/`releases`).
2. Skim `018` §3.1 and this file; add/cut jobs only if a job appeared or died.
3. Fill `scoreboard.md` with the four fields + citations.
4. Execute the subset; record limitations.
5. Re-score May named gaps in an appendix (not as the main table).
6. Headline is categorical: adoption-blocking / match / ahead / unknown — not a fake percentage.

## What this test is not

A 33-axis Storybook-tutorial re-walk; a 200-row addon matrix; a recreation of `018` latency budgets; an implementation review of every panel under `ui/`.
