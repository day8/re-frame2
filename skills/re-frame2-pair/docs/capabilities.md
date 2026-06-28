# Capabilities — what re-frame2-pair covers

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
- **Machine snapshot version drift** — hot-reload bumped a machine's version; old snapshots in the runtime-db partition (`[:rf.runtime/machines :snapshots …]`) now reject restore.
- **Schema validation tightening** — a new app-schema added since the last snapshot, so `restore-epoch` fails with `:rf.epoch/restore-schema-mismatch`.
- **`:origin` mis-attribution** — UI handler dispatches without setting `:origin` to the right tag, the trace shows everything from `:app`.

re-frame2-pair surfaces all of these via the documented restore-failure modes and the `:origin` filter on watch.

---

## Core runtime visibility

What re-frame2-pair can see inside a live re-frame2 app.

| Capability | Status | Notes |
|---|---|---|
| Read all of `app-db` for any frame | *done* | `snapshot` (`:app-db` slice) via `rf/app-db-value` |
| Read a specific path | *done* | `get-path` via `rf/snapshot-of`; `snapshot {path}` for a bounded subtree |
| Diff `app-db` before/after one event | *done* | Each `:rf/epoch-record` carries `:db-before` and `:db-after`; `dispatch` / `trace-window` surface the depth-1 `:db-diff` projection |
| List registered handlers | *done* | `list-handlers {kind}` over `rf/registrations` |
| Inspect handler + interceptor chain + source coords | *done* | `handler-meta {kind: "event", id}` over `rf/handler-meta` (returns `:ns` / `:line` / `:file` / `:column` / `:handler-fn`) |
| Sample a subscription on demand | *done* | `read-sub {sub: "[:query-v]"}` (validated; subscribes a not-yet-mounted sub) |
| Inspect the live sub cache | *done* | `list-subscriptions` (and `snapshot`'s `:sub-cache` slice) return the per-frame materialised cache `{query-v {:value v :ref-count n}}` (CLJS-only) |
| Show subs that re-ran for one epoch | *done* | `:sub-runs` projection per epoch (Spec-Schemas) |
| Show effects fired for one epoch | *partial* | `:effects` projection captures warning/error outcomes; successful-fx attribution requires walking `:trace-events` |
| Follow cascaded dispatch chains | *done* | `:dispatch-id` / `:parent-dispatch-id` correlation; the `:cascades` bundle on the `subscribe` trace topics walks the tree |
| Show components that re-rendered | *done* | `:renders` projection per epoch |
| Attach source location to renders | *done* | Source coords flow from registrar metadata; `:render-key` is a finalised **tuple** `[<view-id-or-:rf.view/anonymous> <instance-token>]` — resolve coords from `(first render-key)` via `handler-meta {kind: "view"}`, or `read-ui`'s `:source-coord` for anonymous fns (see `references/recipes.md` "Explain this dispatch") |
| List registered machines, see their state | *done* | `list-handlers {kind: "machine"}`, `handler-meta {kind: "machine"}` over `re-frame.machines/machines` / `re-frame.machines/machine-meta` / `rf/snapshot-of` |
| List registered app-schemas | *done* | `re-frame.schemas/app-schemas` (schemas are not a registrar kind — query via the runtime helper) |
| Frame enumeration / metadata | *done* | `get-operating-frame` (lists all registered frames) over `rf/frame-ids` / `rf/frame-meta` |

---

## Typical re-frame2 mistakes the tool supports

| Mistake | Status | How |
|---|---|---|
| Wrong write path in `app-db` | *done* | `dispatch`'s `:db-diff` shows the exact path(s) mutated; compare to what the sub reads |
| Event fired but no visible UI change | *done* | "Why didn't my view update?" recipe walks `:sub-runs` and identifies the equality gate |
| View didn't update because sub result stayed `=` | *done* | Same recipe — the sub's *absence* from `:sub-runs` is the equality-gate evidence (value-equal recompute suppression) |
| View re-rendered too broadly | *done* | `:renders` per epoch + `:sub-runs` shows which over-broad sub recomputed |
| Async effects make the app look "wrong for a moment" | *partial* | `:effects` flags non-pure outcomes; for successful-fx attribution, walk `:trace-events` directly |
| Interceptor order changes behaviour | *done* | `handler-meta` lists ordered interceptor ids; `:event/run` traces carry per-step timing |
| Hot reload leaves stale registrations behind | *done* | Probe-based `tail-build` against `(rf/handler-meta ...)`; `:rf.registry/handler-replaced` trace fires on every replace |
| Wrong frame routing | *done* | `frame: ":foo"` on dispatch + `watch-epochs {frame}`; `:ambiguous-frame` refuses unsafe ops |
| Machine snapshot drift after hot-reload | *done* | `restore-epoch` returns false with `:rf.epoch/restore-version-mismatch`; recipe explains and proposes fix |
| Form bug mixes edit / validation / saved state | *not yet* | General re-frame pattern; not a specific recipe |

---

## Time-travel

| Capability | Status | Notes |
|---|---|---|
| List recorded epochs per frame | *done* | `trace-window` / `snapshot` (`:epochs` slice) over `rf/epoch-history` |
| Restore an epoch | *done* | `restore-epoch` (dedicated tool, `--allow-writes`-gated) over `rf/restore-epoch!` |
| Inject an arbitrary app-db state | *done* | `replace-app-db` (dedicated tool, `--allow-writes`-gated) over `rf/replace-app-db!` — the JSON-loaded-bug-repro case |
| Restore failure surfaces | *done* | Seven modes, all documented (Tool-Pair §Time-travel); `(re-frame.trace.tooling/trace-buffer {:op-type :error})` carries the structured tags |
| Configure ring depth | *done* | `(rf/configure! {:epoch-history {:depth N}})` |
| Reverse side effects | *guardrail* | Restore rewinds durable **frame-state** (both partitions); it does NOT reverse side effects or transient host state. `restore-epoch`'s `:unreplayable-effects` enumerates the non-pure fx the original cascade fired that the restore cannot undo |

---

## Safety / guardrails

| Guardrail | Status | Notes |
|---|---|---|
| `replace-app-db` is logged via `tap>` and `--allow-writes`-gated | *done* | Previous + next + timestamp are tap'd so the human sees the change. Delegates to `rf/replace-app-db!` (Tool-Pair §Pair-tool writes) so the synthetic `:rf.epoch/db-replaced` record is appended and `restore-epoch` can rewind past the injection. The tool is OFF unless the server is launched with `--allow-writes`. |
| `eval-cljs` treated as full-authority | *guardrail* | Default-ON (opt out with `--no-eval`); SKILL.md instructs Claude to prefer the structured tools, and flags that `eval-cljs` returns its value un-elided and is not governed by `--allow-sensitive-reads` |
| `snapshot` `:machines` slice is runtime-db state, redacted off-box by default | *done* | The `:machines` slice is runtime-db-partition state; per Spec 011 ruling #14 it egresses as `:rf/redacted` unless the operator opted in. The opt-in is **folded onto the existing sensitive axis** (`redact-runtime-db? = (not incl?)`, `incl?` = `--allow-sensitive-reads` gate + `:include-sensitive`) — there is **no separate `:include-runtime-db?` arg** (deliberately asymmetric with Xray's dedicated `:include-runtime-db?` axis; both fail closed, both satisfy ruling #14). See SKILL.md §privacy posture and `skills/re-frame2/references/cross-cutting/privacy-and-elision.md`. |
| Ops refuse on `:ambiguous-frame` | *done* | Both writes and reads refuse rather than guess: the structured `snapshot` / `get-path` / `dispatch` tools refuse, and the lower-level read helpers (`subs-sample` / `read-sub!` / `sub-cache-info`) return `:reason :ambiguous-frame` rather than silently reading `:rf/default` |
| Watches and background processes always stop cleanly | *done* | Auto-terminate on disconnect, idle (default 30s), hard-cap (default 5min), or count cap (default 5) |
| Restore-failure traces are structured | *done* | Seven `:rf.epoch/*` operations with `:tags` — Tool-Pair contract |
| Time-travel does NOT reverse side effects — surface limit | *guardrail* | SKILL.md style guidance + recipe text. (Restore *does* rewind durable frame-state — both partitions — but not the fx the cascade already fired or transient host state.) |

---

## Debugging recipes

All in SKILL.md's Recipes section.

| Recipe | Status |
|---|---|
| "Why didn't this view update?" | *done* — walks `:sub-runs`, names the equality gate |
| "Why did this view re-render?" | *done* — reverses from `:renders` to `:sub-runs` to `:trigger-event` |
| "What changed in app-db after this event?" | *done* — `dispatch`'s `:db-diff` |
| "What effects fired?" | *partial* — successful-fx attribution requires `:trace-events` walk |
| "What event caused this render?" | *done* |
| "Where in source did this DOM element come from?" | *done* — `dom/source-at` reads `data-rf2-source-coord` first, `data-rc-src` second |
| "Replay this bug from the same starting state" | *done* — first-class via `restore-epoch` |
| "Watch all `:foo/*` events while I click around" | *done* |
| "Post-mortem — how did I get into this state?" | *done* — bounded by `epoch-history` depth |
| "Inspect this machine" | *done* — `list-handlers {kind: "machine"}`, `handler-meta {kind: "machine"}` |
| "Stub an effect for an experiment" | *done* — `:fx-overrides` per call |

---

## What re-frame2-pair does *not* address

- **Visual / pixel-level inspection.** No screenshots, no layout reasoning. Pair with [Chrome DevTools MCP](https://github.com/ChromeDevTools/chrome-devtools-mcp).
- **Cross-browser / mobile testing.** Single browser runtime at a time.
- **Intermittent / race-condition bugs** that don't reproduce on command.
- **Third-party widget internals** not exposed through the DOM or a public JS API.
- **Ambient "watch for anything weird"** observation. `watch/*` is predicate-scoped.
- **UX judgment.** Whether a flow *feels* right is beyond scope.

---

*Last updated: 2026-06-11 — Notes column conformed to the MCP-primary 30-tool surface (describe-image added).*
