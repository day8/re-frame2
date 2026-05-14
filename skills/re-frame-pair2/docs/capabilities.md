# Capabilities — what re-frame-pair2 covers

A scorecard against the real-world surface of re-frame2 + SPA development, mapped to concrete features. Two goals: (1) tell a prospective user honestly what the skill will and won't help with, (2) give the project a living matrix against which progress can be measured. Complements [`STATUS.md`](../STATUS.md) (per-phase implementation state).

## Status legend

- *done* — implemented and exercisable (caveats noted inline)
- *partial* — designed but limited
- *spike* — code path in place; depends on §8a spike items landing
- *not yet*
- *guardrail* — a *safety property*, encoded in design or protocol rather than a concrete op

---

## Context: what re-frame2 programmers face

Most failure modes carry over from v1 (path mismatches, destructive merge, shape drift, init order, wrong handler args, sub identity instability, etc.). re-frame2 introduces a few new ones:

- **Frame routing errors** — wrong dispatch landed in `:rf/default` because the call forgot `:frame :stories`.
- **Machine snapshot version drift** — hot-reload bumped a machine's version; old snapshots in app-db now reject restore.
- **Schema validation tightening** — a new app-schema added since the last snapshot, so `restore-epoch` fails with `:rf.epoch/restore-schema-mismatch`.
- **`:origin` mis-attribution** — UI handler dispatches without setting `:origin` to the right tag, the trace shows everything from `:app`.

re-frame-pair2 surfaces all of these via the documented restore-failure modes and the `:origin` filter on watch.

---

## Core runtime visibility

What re-frame-pair2 can see inside a live re-frame2 app.

| Capability | Status | Notes |
|---|---|---|
| Read all of `app-db` for any frame | *done* | `app-db/snapshot` via `rf/get-frame-db` |
| Read a specific path | *done* | `app-db/get` via `rf/snapshot-of` |
| Diff `app-db` before/after one event | *done* | Each `:rf/epoch-record` carries `:db-before` and `:db-after`; `epoch-diff` returns the projected `{:only-before :only-after :common}` |
| List registered handlers | *done* | `registrar/list <kind>` over `rf/registrations` |
| Inspect handler + interceptor chain + source coords | *done* | `registrar/describe :event <id>` over `rf/handler-meta` (returns `:ns` / `:line` / `:file` / `:column` / `:handler-fn`) |
| Sample a subscription on demand | *done* | `subs/sample [:query-v]` |
| Inspect the live sub cache | *done* | `subs/cache` returns `{query-v {:value v :ref-count n}}` (CLJS-only) |
| Show subs that re-ran for one epoch | *done* | `:sub-runs` projection per epoch (Spec-Schemas) |
| Show effects fired for one epoch | *partial* | `:effects` projection captures warning/error outcomes; successful-fx attribution requires walking `:trace-events` |
| Follow cascaded dispatch chains | *done* | `:dispatch-id` / `:parent-dispatch-id` correlation; `trace/cascade` walks the tree |
| Show components that re-rendered | *done* | `:renders` projection per epoch |
| Attach source location to renders | *done* | Source coords flow from registrar metadata; `:render-key` is opaque pending rf2-t5tx |
| List registered machines, see their state | *done* | `machines/list`, `machines/describe`, `machines/state` over `rf/machines` / `rf/machine-meta` / `rf/snapshot-of` |
| List registered app-schemas | *done* | `schemas` over `rf/app-schemas` |
| Frame enumeration / metadata | *done* | `frames/list`, `frames/meta` over `rf/frame-ids` / `rf/frame-meta` |

---

## Typical re-frame2 mistakes the tool supports

| Mistake | Status | How |
|---|---|---|
| Wrong write path in `app-db` | *done* | `epoch-diff` shows the exact path(s) mutated; compare to what the sub reads |
| Event fired but no visible UI change | *done* | "Why didn't my view update?" recipe walks `:sub-runs` and identifies the equality gate |
| View didn't update because sub result stayed `=` | *done* | Same recipe — the sub's *absence* from `:sub-runs` is the equality-gate evidence (rf2-719e suppression) |
| View re-rendered too broadly | *done* | `:renders` per epoch + `:sub-runs` shows which over-broad sub recomputed |
| Async effects make the app look "wrong for a moment" | *partial* | `:effects` flags non-pure outcomes; for successful-fx attribution, walk `:trace-events` directly |
| Interceptor order changes behaviour | *done* | `registrar/describe` lists ordered interceptor ids; `:event/run` traces carry per-step timing |
| Hot reload leaves stale registrations behind | *done* | Probe-based `tail-build.sh` against `(rf/handler-meta ...)`; `:rf.registry/handler-replaced` trace fires on every replace |
| Wrong frame routing | *done* | `--frame :foo` on dispatch + `--frame :foo` on watch; `:ambiguous-frame` refuses unsafe ops |
| Machine snapshot drift after hot-reload | *done* | `restore-epoch` returns false with `:rf.epoch/restore-version-mismatch`; recipe explains and proposes fix |
| Form bug mixes edit / validation / saved state | *not yet* | General re-frame pattern; not a specific recipe |

---

## Time-travel

| Capability | Status | Notes |
|---|---|---|
| List recorded epochs per frame | *done* | `epoch/history` over `rf/epoch-history` |
| Restore an epoch | *done* | `epoch/restore` over `rf/restore-epoch` |
| Step back one epoch | *done* | `undo/step-back` (sugar) |
| Restore failure surfaces | *done* | Six modes, all documented (Tool-Pair §Time-travel); `(rf/trace-buffer {:op-type :error})` carries the structured tags |
| Configure ring depth | *done* | `(rf/configure :epoch-history {:depth N})` |
| Reverse side effects | *guardrail* | Restore rewinds `app-db` only; SKILL.md asks Claude to enumerate non-pure effects from the cascade and warn before restoring |

---

## Safety / guardrails

| Guardrail | Status | Notes |
|---|---|---|
| `app-db/reset` is logged via `tap>` | *done* | Previous + next + timestamp are tap'd so the human sees the change. Delegates to `rf/reset-frame-db!` (Tool-Pair §Pair-tool writes, rf2-zq55) so the synthetic `:rf.epoch/db-replaced` record is appended and `restore-epoch` can rewind past the injection. |
| `repl/eval` treated as full-authority | *guardrail* | SKILL.md instructs Claude to prefer structured ops; the escape hatch is acknowledged |
| Mutating ops refuse on `:ambiguous-frame` | *done* | Reads proceed against `:rf/default` after warning |
| Watches and background processes always stop cleanly | *done* | Auto-terminate on disconnect, idle (default 30s), hard-cap (default 5min), or count cap (default 5) |
| Restore-failure traces are structured | *done* | Six `:rf.epoch/*` operations with `:tags` — Tool-Pair contract |
| Time-travel rewinds app-db only — surface limit | *guardrail* | SKILL.md style guidance + recipe text |

---

## Debugging recipes

All in SKILL.md's Recipes section.

| Recipe | Status |
|---|---|
| "Why didn't this view update?" | *done* — walks `:sub-runs`, names the equality gate (rf2-719e) |
| "Why did this view re-render?" | *done* — reverses from `:renders` to `:sub-runs` to `:trigger-event` |
| "What changed in app-db after this event?" | *done* — `epoch-diff` |
| "What effects fired?" | *partial* — successful-fx attribution requires `:trace-events` walk |
| "What event caused this render?" | *done* |
| "Where in source did this DOM element come from?" | *done* — `dom/source-at` reads `data-rf2-source-coord` first, `data-rc-src` second |
| "Replay this bug from the same starting state" | *done* — first-class via `restore-epoch` |
| "Watch all `:foo/*` events while I click around" | *done* |
| "Post-mortem — how did I get into this state?" | *done* — bounded by `epoch-history` depth |
| "Inspect this machine" | *done* — `machines/list`, `machines/describe`, `machines/state` |
| "Stub an effect for an experiment" | *done* — `:fx-overrides` per call |

---

## What re-frame-pair2 does *not* address

- **Visual / pixel-level inspection.** No screenshots, no layout reasoning. Pair with [Chrome DevTools MCP](https://github.com/ChromeDevTools/chrome-devtools-mcp).
- **Cross-browser / mobile testing.** Single browser runtime at a time.
- **Intermittent / race-condition bugs** that don't reproduce on command.
- **Third-party widget internals** not exposed through the DOM or a public JS API.
- **Ambient "watch for anything weird"** observation. `watch/*` is predicate-scoped.
- **UX judgment.** Whether a flow *feels* right is beyond scope.

---

*Last updated: 2026-05-09.*
