# Evidence ledger

SHA `98e8ffe9cb339295fe2fa459900d9d9647fab015`. 2026-09-14.

## Verified (this run)

| Claim | How | Result |
|---|---|---|
| Storybook stable is 10.6 | GitHub releases + npm `@storybook/addon-mcp` 10.6.0 (2026-09-02); docs assets `/docs-assets/10.6/` | Current major **10**, not 8/9 |
| Storybook has MCP | https://storybook.js.org/docs/ai/mcp/overview and `/api` 2026-09-14 | Preview; three toolsets |
| Chromatic is paid | https://www.chromatic.com/pricing 2026-09-14 | Free 5k; Starter $179/mo |
| Install is `npm create storybook@latest` | https://storybook.js.org/docs/get-started/install | One command |
| Ladle 5.1.1 | GitHub releases | React, Vite, CSF |
| Portfolio 2026.03.1 | GitHub README | Maintained CLJS workshop |
| Widgetbook v4 beta | widgetbook.io blog 2026-03-31; pub.dev 4.0.0-beta.13 | Constructor-as-truth |
| Lookbook 2.3.14 | GitHub release Dec 2025 | Rails preview+docs |
| `force-fx-stub` works on login-form | Playwright workspace: `/submitting` shows `:rf.assert/effect-emitted :rf.http/managed` | EXERCISED |
| Five isolated UI states | Playwright `all-states` grid | EXERCISED |
| Xray on failure/assertion | Playwright idle Epoch panel | EXERCISED |
| No-adapter run is `:error` | `clojure -M:test --var …/no-adapter-installed-run-is-an-error-not-a-pass` from `tools/story` — 1 test, 12 assertions, 0 failures | EXERCISED |
| `reg-check` terminal path includes checks | `runtime.cljc` `plan-checks` concat; commit `ac36ed93bd` | SOURCE-TRACED (review F1 stale) |
| Epoch baseline for tape assertions | `assertions.cljc` `run-slice`; `40f80973e2` | SOURCE-TRACED (review F2 stale) |
| Global decorators exist | `story.cljc` `reg-global-decorator` | SOURCE-TRACED |
| `:loaders-teardown` exists | `frames.cljc` `apply-loaders-teardown!` | SOURCE-TRACED |
| Story rollup docs exist | `docs.cljc` `docs-rollup-view`; `008` | SOURCE-TRACED |
| Canvas is not an iframe | `canvas.cljs` `data-rf-story-variant-root` | SOURCE-TRACED |
| MCP tool count 19 | `tool-descriptors.edn` `:tool-count 19` | SOURCE-TRACED |
| Skill split author vs run | `skills/re-frame2/references/tooling/story-mcp-loop.md` | SOURCE-TRACED |
| `:sub-equals` uses full frame-state | `assertions.cljc` ~1178–1184 | SOURCE-TRACED; login testbed ns docstring is **stale** |
| Auto-install canonical vocab | `docs/story/index.md`; `login_form/core.cljs` | SOURCE-TRACED |
| Not on Clojars | `tools/story/README.md` | SOURCE-TRACED |

## Refuted / stale

| Claim | Source | This run |
|---|---|---|
| No peer has MCP | Fable-style folklore; May SOTA "chase MCP" | **False** — official addon, preview |
| May audit 5 capability gaps still open | Feature-Parity-Audit.md TL;DR | C-1…C-4 closed in code; C-5 docs |
| D-2 mandatory install-canonical-vocabulary! | Same audit | Closed |
| story-review P1s are live gaps | `ai/findings/story-review.md` 2026-09-13 | Fixed at this SHA |
| `:sub-equals` cannot see runtime-db | `login_form/stories_cljs_test.cljs` header | Code now passes full frame-state |

## Absorbed from Astra (source-checked; not re-run here)

| Claim | Check | Result |
|---|---|---|
| Promotion drops failing assertion | `promotion.cljc` `artifact->variant-body` omits `:expect`; Astra JSON `promotionPreservesFailure: false` | **Holds.** J8/J9 `PARTIAL`. |
| Upgrade snippet unreadable | `upgrade-snippet` last line is a `;` comment; envelope appends `})` | **Holds.** |
| Upgraded plan keeps parent sub-overrides | `:extends` + no un-set; JSON `fidelity #{:real-setup :sub-overrides}` | **Holds.** |
| README `:component` function is invalid | `schemas.cljc` `:keyword` | **Holds.** Sharpens J1. |
| Lookbook 2.3.15; Histoire beta vs 0.17.17 | Astra versions | Landscape nits. |
| Storybook 10.6.0 fixture actually ran play + Vitest | Their `storybook-tests-*.log` / journeys | Corroborates J8 MATCH; we did not re-run. |
| Test Run-all 5/5; heading edit isolated | `story-journeys.json` | Corroboration only. |

## Absorbed from Fable (source-checked; their execution, not ours)

| Claim | Check | Result |
|---|---|---|
| `mount-shell!` 2-arg on the install page | `story.cljc` `[dom-node]` vs `docs/story/index.md:116` | **Holds.** J1 compile. |
| Missing npm `@xyflow/react` / `elkjs` | machines-viz chart require | **Holds** as unnamed closure. |
| No schema on login `reg-view` | `login_form/views.cljs` | **Holds.** |
| Explain empty args / "not available" | Astra Explain dump | **Holds.** |
| Evidence-row placeholder | `test_mode/view.cljs:559` | **Holds.** |
| Auto-grid `· 0` | `workspace-grid-grouping` counts `:variants` only | **Holds** for auto-grid. `GRID · 5` on all-states is honest. |
| `story/run` lacks promised hashes | `runtime.cljc` no `plan-hash`; `result.cljc` copies from `parts` | **Holds** as contract drift. |
| Weighted 0.82 vs 0.71; skip-capture on hash | instrument / identity tuple | **Rejected.** |
| Promotion is a WIN | registration ≠ keep assertion | **Rejected** (Astra's probe). |
| ARIA tabs missing | Astra `getByRole('tab')` worked | **Not absorbed.** |

## HEAD `35e3c00fb9` (second sibling review) — landed, source-checked

Install page, run hashes, explain/view-state args, evidence button, auto-grid `resolve-layout` count, login `:rf/props` + own colour, promotion `:assertions`, upgrade-snippet chain walk, ch.01 watch URL, README keyword `:component`, ch.02 isolation, ch.09 two hosts. **Not re-executed** in browser/JVM this pass.

## Unverified

- Live Story-MCP stdio loop
- Edit-to-render latency / large catalog
- axe panel click
- HMR via `shadow-cljs watch`
- CSS leak / portal escape (reasoned, not planted)
- Chromatic account / snapshot run
- Histoire/Ladle running locally
