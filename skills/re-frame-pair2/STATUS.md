# Implementation status

A living record of what's actually implemented, what's scaffolded, and what's blocked. Updated per release. See `docs/initial-spec.md` for the design this is measured against.

**Last updated:** 2026-05-09 (initial port from v1)

---

## TL;DR

| Area | State |
|---|---|
| Design spec | Complete (see `docs/initial-spec.md`) |
| `SKILL.md` | Written — the full vocabulary Claude learns |
| `preload/re_frame_pair2/runtime.cljs` | Written — helpers over re-frame2's public Tool-Pair surfaces. Loaded into the app via shadow-cljs `:devtools :preloads`. |
| `scripts/ops.clj` + shell shims | Written — babashka dispatches every op |
| `.claude-plugin/plugin.json` | Written |
| `package.json` + GH Actions (CI + release) | Written |
| Fixture app | Not yet — see `docs/initial-spec.md` §8a |
| End-to-end against a live re-frame2 app | Not done — this is the spike |

**Nothing in this repo has been exercised against a running re-frame2 + shadow-cljs build yet.** Pre-alpha. See *Known unknowns* below.

---

## Per-phase status (against `docs/initial-spec.md` §6)

| Phase | Deliverable | State | Notes |
|---|---|---|---|
| 0 | `eval-cljs.sh` round-trips a form | **Coded, not yet run** | `scripts/eval-cljs.sh` + `ops.clj` implement it; needs a live nREPL to verify. |
| 1 | Read surface (§4.1) | **Coded** | `app-db/snapshot`, `app-db/get`, `app-db/schemas`, `registrar/list`, `registrar/describe`, `subs/cache`, `subs/sample`, `machines/*` — all over `rf/get-frame-db`, `rf/snapshot-of`, `rf/handlers`, `rf/handler-meta`, `rf/sub-cache`, `rf/machines`, `rf/machine-meta`, `rf/app-schemas`. |
| 2 | Dispatch + trace (§4.2–§4.3) | **Coded** | `pair-dispatch!` / `pair-dispatch-sync!` / `dispatch-and-collect`; trace consumed via `rf/register-trace-cb`, `rf/trace-buffer`, `rf/register-epoch-cb!`, `rf/epoch-history`. No 10x dependency. |
| 3 | Live watch (§4.4) | **Coded, pull-mode only** | `scripts/watch-epochs.sh` runs repeated short evals at 100ms cadence against `epochs-since`; assembled-stream listener feeds an internal stash. Streaming-via-`:out` deferred. |
| 4 | Hot-swap (REPL) | **Coded** | Delivered by `reg-event-fx`/`reg-sub`/`reg-fx`/`reg-machine` via `eval-cljs.sh`. Re-registration emits `:rf.registry/handler-replaced` per Spec 001 §Hot-reload semantics. |
| 5 | Hot-reload coordination (§4.5) | **Coded** | `tail-build.sh` implements the probe-based protocol — preferred probes target `(rf/handler-meta ...)` since the meta map's `:line` / `:column` / `:handler-fn` change after re-registration. |
| 6 | Time-travel (§4.6) | **Coded — first-class** | `restore-epoch`, `undo-step-back`, `undo-to-epoch` all delegate to `rf/restore-epoch`. Six documented failure modes per Tool-Pair §Time-travel. No adapter — re-frame2 ships this directly. |
| 7 | Diagnostics recipes (§4.7) | **Coded as SKILL.md procedures** | Listed; will be refined as real usage surfaces needed ops. |
| 8 | Packaging | **Coded** | `package.json`, `plugin.json`, GH Actions for CI + npm release on tag. See `RELEASING.md`. |

---

## Known unknowns — the §8a spike deliverables

Three things need to be proven against a fixture before calling this beyond pre-alpha.

### 1. Runtime discovery

`scripts/discover-app.sh` needs to actually connect against a real re-frame2 app. Specific unknowns:

- nREPL port location across shadow-cljs versions (we try `target/shadow-cljs/nrepl.port`, `.shadow-cljs/nrepl.port`, `.nrepl-port`, and `$SHADOW_CLJS_NREPL_PORT` in that order).
- CLJS-mode switch — does `(shadow.cljs.devtools.api/cljs-eval <build-id> <form-str> {})` return the `:value` in a parseable edn form, or wrapped in a shadow-specific result map?
- `re-frame.interop/debug-enabled?` — verify the symbol is reachable post-init. The current health check reads the var directly, which works in CLJS but may need adjustment if the symbol is moved.

### 2. CLJS eval round-trip

Does `scripts/eval-cljs.sh '(+ 1 2)'` return `{:ok? true :value 3}`? If not, `ops.clj`'s `cljs-eval-value` parsing needs adjustment.

### 3. `data-rf2-source-coord` format — RESOLVED 2026-05-09 (rf2-7g2q)

Resolved against re-frame2 rf2-z7f7 (PR #135). Per [Spec 006 §Source-coord annotation](https://github.com/day8/re-frame2/blob/master/spec/006-ReactiveSubstrate.md) and [Tool-Pair §Source-mapping](https://github.com/day8/re-frame2/blob/master/spec/Tool-Pair.md) the emitted attribute value is:

```
data-rf2-source-coord="<ns>:<handler-id>:<line>:<col>"
```

Four colon-separated segments, where `<ns>` and `<handler-id>` derive from the registry id keyword (`(namespace id)` / `(name id)`). Either coord segment may be the literal `?` for programmatic `reg-view*` calls that bypassed the macro path. Non-DOM roots (Fragment `:<>`, `:>` interop, fn-component head) are exempt — pair tools fall back to `(rf/handler-meta :view id)` for those.

`preload/re_frame_pair2/runtime.cljs` `parse-rf2-coord` now returns `{:ns :handler-id :line :col}` (or nil for malformed / non-4-segment input). Verified by `tests/runtime/parse_rf2_coord_test.clj` (run via `bb`).

> **Compatibility note.** Tool-Pair.md declares the attribute's value format opaque to consumers — pair2 parses it pragmatically so the DOM-to-source bridge can be useful, but skill consumers MUST NOT depend on the parsed shape's stability across re-frame2 versions. If the format shifts, update the parser (and these tests) in one place.

---

## What's genuinely verified

- `re-frame.core` exposes the Tool-Pair surfaces this skill consumes — `register-trace-cb`, `trace-buffer`, `register-epoch-cb!`, `epoch-history`, `restore-epoch`, `configure`, `handlers`, `handler-meta`, `frame-ids`, `frame-meta`, `get-frame-db`, `snapshot-of`, `sub-cache`, `machines`, `machine-meta`, `app-schemas` — confirmed in `re-frame2/implementation/src/re_frame/core.cljc`.
- Epoch records carry the documented `:rf/epoch-record` shape — confirmed in `re-frame2/implementation/src/re_frame/epoch.cljc` (`:epoch-id`, `:frame`, `:committed-at`, `:event-id`, `:trigger-event`, `:db-before`, `:db-after`, `:trace-events`, `:sub-runs`, `:renders`, `:effects`).
- `restore-epoch` implements the six documented failure modes per Tool-Pair §Time-travel.
- shadow-cljs nREPL accepts JVM `(shadow.cljs.devtools.api/cljs-eval ...)` calls (well-known).

Everything else is structurally correct per the Tool-Pair Spec but not runtime-verified.

---

## Next actions

In order:

1. Stand up a fixture re-frame2 app (minimal Reagent v2 + shadow-cljs).
2. Ground-truth the three items under *Known unknowns*.
3. Adjust `runtime.cljs` and `ops.clj` to match.
4. Wire `tests/runtime/` into an actual shadow-cljs test build.
5. Graduate out of pre-alpha and cut `v0.1.0-beta.1`.

---

## Asymmetries to monitor in the spec

These are documented gaps in re-frame2's `:rf/epoch-record` projection that affect what recipes can return today. Each is a candidate `bd` bead if real usage shows it materially blocks a recipe:

- **`:effects` projection captures only warning/error outcomes** — successful fx execution is not in the projection (Spec-Schemas §`:rf/epoch-record`). The skill's "What effects fired?" recipe falls back to walking `:trace-events` directly when successful-fx attribution is needed.
- **`:render-key` shape is TBD** (Spec-Schemas §`:rf/epoch-record`, rf2-t5tx). Treated as opaque by the skill; recipes that route by render-key compare via `str` until the shape stabilises.
- **`:sub-runs` `:result-changed?` is currently always true when the sub recomputed** — the raw trace doesn't yet carry the prior value. Tools requiring fine-grained change-tracking consume the raw trace stream.
