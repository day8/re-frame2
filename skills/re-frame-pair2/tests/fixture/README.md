;; tests/fixture — minimal re-frame2 app for pair2 validation.
;;
;; A deliberately tiny re-frame2 counter (mirrors examples/reagent/counter)
;; with `re-frame-pair2.runtime` wired in as a shadow-cljs `:devtools :preloads`
;; entry. Pair2's tests/shim, tests/e2e, and tests/prompts surfaces target
;; this fixture.
;;
;; The fixture is intentionally trivial — one event, one sub, one view,
;; one frame. Validation here proves pair2's runtime ↔ Tool-Pair contract
;; without taking on examples/ scope.

# Pair2 fixture app

Minimal re-frame2 counter app used by `tests/shim`, `tests/e2e`, and
`tests/prompts`. Mirrors `examples/reagent/counter/core.cljs`, with
`re-frame-pair2.runtime` preloaded.

## Layout

| Path | Purpose |
|---|---|
| `shadow-cljs.edn` | Build config with `:devtools :preloads` set to `re-frame-pair2.runtime`. |
| `deps.edn` | `:local/root` to `../../../../implementation` (the re-frame2 repo) plus Reagent. |
| `src/counter/core.cljs` | Counter (`reg-event-db`, `reg-sub`, `reg-view`) — three events: `:counter/initialise`, `:counter/inc`, `:counter/dec`. |
| `public/index.html` | Page host; loads `out/main.js`. |
| `README.md` | This file. |

## Run

From the repo root:

```bash
# 1. install npm + the pair2 preload-source-path entry
cd skills/re-frame-pair2/tests/fixture
npm install

# 2. start the shadow-cljs dev server (this is what pair2's discover-app attaches to)
npx shadow-cljs watch app
```

shadow-cljs prints its nREPL port to `target/shadow-cljs/nrepl.port`
once it's ready (the file pair2's `discover-app` probes). Open
http://localhost:8030 in a browser tab — the counter should render and
`re-frame-pair2.runtime/session-id` should be set in the browser console.

## Verify the preload landed

```bash
# bash shim path
cd ../..   # back to skills/re-frame-pair2/
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

The `(rf/configure :source-coord {:annotate-dom? true})` call is made
in `run` so the DOM bridge surfaces have something to find — see
`docs/initial-spec.md` §8a item 3 and Spec 006.
