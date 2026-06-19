;; tests/fixture — minimal re-frame2 app for re-frame2-pair validation.
;;
;; A deliberately tiny re-frame2 counter (mirrors examples/reagent/counter)
;; with `re-frame2-pair.runtime` wired in as a shadow-cljs `:devtools :preloads`
;; entry. re-frame2-pair's tests/shim, tests/e2e, and tests/prompts surfaces target
;; this fixture.
;;
;; The fixture is intentionally trivial — one event, one sub, one view,
;; one frame. Validation here proves re-frame2-pair's runtime ↔ Tool-Pair contract
;; without taking on examples/ scope.

# re-frame2-pair fixture app

Minimal re-frame2 counter app used by `tests/shim`, `tests/e2e`, and
`tests/prompts`. Mirrors `examples/reagent/counter/core.cljs`, with
`re-frame2-pair.runtime` preloaded.

## Layout

| Path | Purpose |
|---|---|
| `shadow-cljs.edn` | Build config with `:devtools :preloads` set to `re-frame2-pair.runtime`. |
| `deps.edn` | `:local/root` to `../../../../implementation` (the re-frame2 repo) plus Reagent. |
| `src/counter/core.cljs` | Counter (`reg-event`, `reg-sub`, `reg-view`) — four events: `:counter/initialise`, `:counter/inc`, `:counter/dec`, and `:counter/stamp` (declares `:rf.cofx/requires [:rf/time-ms]` and folds the recorded wall-clock fact into `:stamped-at` — the EP-0017 recordable-coeffects target the `tools/mcp-conformance` live cofx gate dispatches with a scripted `cofx`). |
| `public/index.html` | Page host; loads `out/main.js`. |
| `README.md` | This file. |

## Run

From the repo root:

```bash
# 1. install npm + the re-frame2-pair preload-source-path entry
cd skills/re-frame2-pair/tests/fixture
npm install

# 2. start the shadow-cljs dev server (this is what re-frame2-pair's discover-app attaches to)
npx shadow-cljs watch app
```

shadow-cljs prints its nREPL port to `target/shadow-cljs/nrepl.port`
once it's ready (the file re-frame2-pair's `discover-app` probes). Open
http://localhost:8030 in a browser tab — the counter should render and
`re-frame2-pair.runtime/session-id` should be set in the browser console.

## Verify the preload landed

```bash
# bash shim path
cd ../..   # back to skills/re-frame2-pair/
SHADOW_CLJS_BUILD_ID=app scripts/discover-app.sh
# => {:ok? true :session-id "..." :debug-enabled? true :frames [:rf/default] ...}
```

## Counter contract

| Op | Effect |
|---|---|
| `[:counter/initialise]` | seeds `{:count 5}` |
| `[:counter/inc]` | `update :count inc` |
| `[:counter/dec]` | `update :count dec` |
| `(subscribe [:count])` | reads `:count` |
| view `counter.core/counter-buttons` | rendered with `data-rf2-source-coord` annotation |

Source-coord DOM annotation is automatic in debug builds (mandatory per
Spec 006 §Source-coord annotation — re-frame2 stamps registered-view roots
with `data-rf2-source-coord`; there is no `configure!` opt-in), so the DOM
bridge surfaces have something to find with no extra setup — see
`docs/initial-spec.md` §8a item 3 and Spec 006.
