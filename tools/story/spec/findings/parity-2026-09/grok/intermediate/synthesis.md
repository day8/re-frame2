# Synthesis after sibling reports

Independent Grok draft first. Two later sibling reads. **This file's third pass (2026-09-14 ~14:30):** Fable's 14:10 revision + trunk `35e3c00fb9` (honesty beads landed). Not a second independent pass.

## Second sibling review (this pass)

Fable rewrote after absorbing Grok+Astra. They quantified doorstep vs substance, named the CSS-inheritance specimen, explained Explain's `:run-args` hole, and **filed beads**. At HEAD those beads are mostly **merged** (install page, hashes, explain/view-state args, evidence link, auto-grid count, login schema+colour, promotion assertions, upgrade-snippet, ch.01/README, ch.02 isolation, ch.09 two hosts).

Still open: D-1 template, orphan `register-variant` (`rf2-bf12o`), visual-review recipe (held), standing-test re-run at HEAD.

Rejected again: weighted score; skip-on-hash (Fable now agrees); circular Astra↔Grok citation.

**Consensus:** thesis and do-not-build list have converged. Scoring (Fable's ratio vs Grok/Astra categorical) and "how unique is MCP / SPA journeys" have not. See `report.md` §6 Consensus.

New useful Fable caveats kept: collapsing the five "plan/tape" rows makes the ratio ~1; MCP stdio proved transport not app-repair; A4 state isolation ≠ A7 document isolation.

## Material

| Folder | When | What |
|---|---|---|
| `astra/` | After Grok draft | Report, Storybook 10.6 fixture, promotion/fidelity probes, later sibling-synthesis of *us* |
| `fable/` | After first Grok synthesis (prompt-only then) | `report.md`, `matrix.md`, `rubric.md`, `evidence.md` — tutorial compile, MCP stdio, timed Storybook init, JVM `explain`/hashes |

Astra's revised report already cites Grok; re-importing those citations as corroboration would be circular. Fable's experiments are independent.

## Absorbed (source-checked this SHA)

| Lead | Check | Use |
|---|---|---|
| Promotion drops `:expect` (Astra) | `artifact->variant-body` | Rec 1 |
| Upgrade snippet EOF + inherited pin (Astra) | `upgrade-snippet` + envelope | Rec 2; **same mechanism: `:extends` as mutation** |
| README function `:component` (Astra) | `schemas.cljc` `:keyword` | J1 |
| Install page 2-arg `mount-shell!` (Fable) | `mount-shell!` is `[dom-node]` | J1 compile failure |
| Adapter ns `my-app.adapters.reagent` (Fable) | `docs/story/index.md:110` | J1; placeholder, not proof no app can define it |
| Unnamed `@xyflow/react` / `elkjs` (Fable/Astra) | machines-viz chart require | J1 npm closure |
| No schema on login `reg-view` (Fable) | `login_form/views.cljs` | J3 invisible WIN |
| Explain empty args (Fable; Astra dump) | Explain JSON "ARGS not available" while heading renders | Rec 6 |
| Evidence-row placeholder (Fable; Astra reproduced) | `test_mode/view.cljs:559` | Rec 5 |
| Auto-grid count 0 (Fable; we photographed it) | `workspace-grid-grouping` = `(count (:variants body))`; auto-grid has `:for`, no `:variants` | Rec 8. Not "the five-cell GRID is zero" — `GRID · 5` on all-states is honest. |
| Frozen hashes missing on `story/run` (Fable) | `result.cljc` only copies hashes from `parts`; `runtime.cljc` has no `plan-hash` | Rec 7 |
| Orphan `register-variant` succeeds (Fable) | skill leaf vs their MCP #17 | Rec 9 |
| Storybook play() does fail/retry/success (Astra) | their fixture | **Narrow J6** |
| Fable MCP 12s loop | their evidence; Astra inspected receipts | J12 sibling-exercised |
| Fable R10 skip capture on unchanged hash | `identity.cljc` is declared inputs | **Reject** |
| Fable 0.82 vs 0.71 | different instrument | **Reject** |
| Fable promotion WIN | registration succeeded ≠ assertion preserved | Keep Astra's row |
| Fable ARIA tabs missing | Astra `getByRole('tab')` succeeded | **Do not absorb** |

## New analysis (ours)

Intent preservation across projections is the product hypothesis. `:extends` as a mutation is the shared cause of the two upgrade bugs. Sidebar auto-grid count is the same class (different enumerator than the canvas). Two workshops (chrome vs application-state) were being averaged.

## Ranking now

1–2 generators that keep intent. 3–4 install compiles + schema on the demo. 5–9 honesty cluster. Stop. No skip-on-hash VR gate. No score.
