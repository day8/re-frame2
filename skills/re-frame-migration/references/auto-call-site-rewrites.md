# auto-call-site-rewrites

Type A — per-call-site mechanical rewrites the agent applies without asking. Covers namespace requires, effect-map consolidation, and dispatch-shape changes. The agent walks call sites, applies the search→rewrite shapes verbatim, and cites the rule id (`M-N`) in the migration report.

For the *why* of each rule, see [`MIGRATION.md`](../../../migration/from-re-frame-v1/README.md). This leaf is a shape catalogue, not a rationale. For cross-cutting renames (keywords, interceptor lists, views, init, per-feature artefacts), see [`auto-cross-cutting.md`](auto-cross-cutting.md). For judgment-call rewrites, see [`guided-handlers-state.md`](guided-handlers-state.md) and [`guided-interceptors-subs.md`](guided-interceptors-subs.md).

## Announce before each multi-file sweep

Per SKILL.md Cardinal rule 4: before executing any Type A rewrite that will edit more than a single file, post a one-line announcement and pause. Example shape:

```
About to apply M-8 (fold top-level :dispatch / :dispatch-n / :dispatch-later
into :fx). Matched 23 call sites across 14 files. Representative diff:

  - {:db new-db :dispatch [:foo 1]}
  + {:db new-db :fx [[:dispatch [:foo 1]]]}

Ctrl-C now to abort or scope-limit; continuing in a moment.
```

Per-file confirmation is not required — the author has already invoked the migration. The announcement gives a real window to abort or scope-limit before dozens of files change at once.

## Contents

- Dep-coord and namespace rewrites (M-0, M-1, M-38, M-23, M-25 — incl. the async-test recipe `run-test-async` / `wait-for-event`, M-50, M-52)
- Effect-map consolidation (M-8)
- Dispatch-shape rewrites (M-4, M-9, M-16)

---

## Dep-coord and namespace rewrites

### M-0 — Dep coord swap

See `references/setup.md` for the per-build-tool detail. Applied once, in Phase 2.

### M-1 — Off-contract `re-frame.*` namespace requires

**M-1 is a PRINCIPLE, not a fixed enumeration.** re-frame2's compatibility commitment covers the **public surface** only — every other `re-frame.*` namespace is off-contract and a require of one is an M-1 site. The public surface is:

- **`re-frame.core`** — the public façade. The single import for application code (`[re-frame.core :as rf]`).
- **The per-feature artefact namespaces** `re-frame.<feature>` you require *only when the feature is in use* (M-27..M-33): `re-frame.schemas`, `re-frame.machines`, `re-frame.routing`, `re-frame.flows`, `re-frame.http-managed` / `re-frame.http`, `re-frame.ssr`, `re-frame.epoch`, and the test-side `re-frame.test-support` / `re-frame.http-test-support`.
- **`re-frame.interop`** (JVM interop) and **`re-frame.std-interceptors`** — explicitly preserved (see [`breaking-changes.md` §What stays the same](breaking-changes.md#what-stays-the-same-do-not-change)). Not off-contract; leave them.

Everything else under `re-frame.*` is off-contract. `re-frame.alpha` is **not** a public surface in v2 — it does not exist as a v2 namespace; a v1 `re-frame.alpha` require is removed by **M-23**, not M-1. The internals that an `:enumerate-the-list` sweep tends to miss include `re-frame.db`, `re-frame.router`, `re-frame.subs`, `re-frame.events`, `re-frame.registrar`, **`re-frame.utils`**, `re-frame.loggers`, `re-frame.interceptor`, `re-frame.fx`, `re-frame.cofx`, `re-frame.spec` (reach the interceptor through `re-frame.core` instead). `re-frame.utils` in particular is a classic surprise: a v1 app that used `re-frame.utils/map-vals` sails through an enumerated sweep and only fails later with `The required namespace "re-frame.utils" is not available` — *after* the obvious db/router/subs/events/registrar requires are cleared, so it reads as a regression rather than a known M-1 site.

**Grep all off-contract requires up front** so the migrator fixes them in one pass, not one compile error at a time (the "march the wall" effect — each fix reveals the next missing-namespace error). The skill's only shell verb is `rg` (SKILL.md `allowed-tools`):

```bash
# Every re-frame.* require that is NOT on the public surface.
# Stage 1: broad-scan every re-frame.* require. Stage 2: invert-filter out
# core + the preserved/feature namespaces. Each surviving line is an M-1 site.
rg -n '\[\s*re-frame\.[a-z-]+' . \
  | rg -v '\[\s*re-frame\.(core|interop|std-interceptors|schemas|machines|routing|flows|http|http-managed|http-test-support|ssr|epoch|test-support)\b'
```

(**Do not** reach for an inline negative-lookahead — `rg -n '\[\s*re-frame\.(?!core\b|…)…'` — in the *default* engine: ripgrep's default Rust `regex` engine rejects look-around and exits non-zero *before scanning* with "look-around … is not supported", so the M-1 inventory silently produces nothing and reads as a false-clean sweep. The broad-scan-then-invert-filter form above works on **any** ripgrep build; an equivalent single-command form needs `rg -P`/`--pcre2`, which only works on a ripgrep compiled with PCRE2. Each surviving hit is an M-1 site: find a public equivalent in `re-frame.core`, or — if none — flag for human review with the call site and what it is doing.)

```clojure
;; SEARCH (the common internals — illustrative, NOT exhaustive: the principle above governs)
(:require [re-frame.db :as db])
(:require [re-frame.db :refer [app-db]])
(:require [re-frame.router :as router])
(:require [re-frame.subs :as subs])
(:require [re-frame.events :as events])
(:require [re-frame.registrar :as reg])
(:require [re-frame.utils :as u])          ; off-contract — gone in v2
(:require [re-frame.loggers :as log])      ; off-contract — gone in v2

;; REWRITE
;; Remove the :require entirely; replace usages per the table:
@db/app-db           → (rf/app-db-value :rf/default)
@re-frame.db/app-db  → (rf/app-db-value :rf/default)
(reset! re-frame.db/app-db v) → flag (Type B — see M-15) — propose
                                 (rf/dispatch-sync [::reset-app-db v])
(subs/clear-sub-cache!) → (rf/clear-sub-cache! :rf/default)
(re-frame.core/clear-subscription-cache!) → (rf/clear-sub-cache! :rf/default) ; public v1 no-arg name
(reg/get-handler kind id) → (rf/handler-meta kind id) ; public registrar query; returns the registration metadata map (no raw handler fn is exposed publicly in v2)
(re-frame.utils/map-vals f m) → (clojure.core/update-vals m f) ; Clojure 1.11+ (note arg order: update-vals takes the map first)
```

**Note (M-1 `get-handler` rewrite)**: the rewrite above targets `rf/handler-meta`, which is the actual public registrar-query surface in `re-frame.core`. The MIGRATION.md M-1 row was corrected to match — there is no `rf/get-handler` in v2.

**Caveat (M-1 `@app-db` → `app-db-value` is NOT semantically identical — reactivity loss):** `(rf/app-db-value :rf/default)` returns a **non-reactive snapshot** — a plain `app-db` map value, no deref, no reactive subscription (per `re-frame.core/app-db-value`'s docstring: *"current `app-db` VALUE (a plain map)… no deref, no container"*). v1's `@re-frame.db/app-db` is a **reactive** deref — `app-db` is a Reagent `ratom`, so a deref **inside a reactive context** (a `reaction`, a component render body, a `track`) subscribes that render to db changes and re-renders when `app-db` changes.

For sites in **plain functions / event-handler bodies / effect bodies** (the common case) the mechanical swap is faithful — there's no reactive context to preserve. But for a site that derefs `@app-db` **inside a reactive context**, the mechanical swap **silently removes reactivity**: the component stops updating, with **no compile error and no warning** — a latent runtime regression a blind sweep introduces.

So before rewriting each `@app-db` site, **check whether it sits inside a reactive context** (reaction / component render / `track`). If it does, the correct rewrite is a **subscription** (`@(rf/subscribe [...])` against a `reg-sub` that reads the slice), **not** `app-db-value` — **flag those sites for the author** rather than swapping them mechanically. Only the non-reactive sites take the mechanical `app-db-value` swap.

**Edge case → Type B**: `(reset! re-frame.db/app-db ...)` is intent-sensitive (real bypass vs. test reset vs. seeding); promote to M-15 review.

### M-38 — Substrate adapter ns rename

```clojure
;; SEARCH
(:require [re-frame.substrate.reagent :as reagent-adapter])

;; REWRITE
(:require [re-frame.adapter.reagent :as reagent-adapter])
```

Same for `uix` / `helix` variants.

### M-23 — `re-frame.alpha` removal (mechanical half)

```clojure
;; SEARCH
(:require [re-frame.alpha :as rf])  ; or :refer [reg sub]

;; REWRITE — remove the require; rewrite each call site:
(reg :event-fx :id ...)              → (reg-event-fx :id ...)
(reg :event-db :id ...)              → (reg-event-db :id ...)
(reg :event-ctx :id ...)             → (reg-event-ctx :id ...)
(reg :sub :id ...)                   → (reg-sub :id ...)
(reg :fx :id ...)                    → (reg-fx :id ...)
(reg :cofx :id ...)                  → (reg-cofx :id ...)
(reg :flow :id ...)                  → (reg-flow ...)
(sub <vector>)                       → (subscribe <vector>)
(sub {:re-frame/q ::id :param 1})    → (subscribe [::id 1])   ; vectorize the query-map
```

**Edge case → Type B**: any `:re-frame/lifecycle` annotation in the original — drop the annotation; if the user explicitly wanted non-default lifecycle, flag it (and, with their approval, file a GitHub issue against `day8/re-frame2` per the shared [`issue-filing.md`](../../shared/issue-filing.md) recipe). See [`guided-interceptors-subs.md` §M-23](guided-interceptors-subs.md#m-23--re-framelifecycle-annotation-drop).

### M-25 — `re-frame.test` rename

```clojure
;; SEARCH
(:require [re-frame.test :as rf-test])
(:require [day8.re-frame.test :as rf-test])

;; REWRITE
(:require [re-frame.test-support :as rf-test])
```

`dispatch-sequence` keeps its v1 name; `assert-state` is split into `assert-path-equals` + `assert-db-equals` per **M-62** (the fn-side mirrors the `:rf.assert/*` Story event-family). `run-test-sync` is dropped in v2 — see **M-52** below to rewrite call sites.
Also: drop `day8/re-frame-test` from the Maven coords.

### M-52 — `run-test-sync` removed

```clojure
;; SEARCH
(ts/run-test-sync
  body...)
(rf-test/run-test-sync
  body...)
(re-frame.test-support/run-test-sync
  body...)

;; REWRITE — hoist body; per-test fixture handles registrar isolation
;; (assumes the ns already installs make-reset-runtime-fixture via use-fixtures :each;
;; if not, add it — see M-52 in MIGRATION.md for the full pattern)
body...

;; AD-HOC ALTERNATIVE — one-off bracket without converting the ns to use a :each fixture
(ts/with-fresh-registrar
  (fn []
    body...))
```

v2's `dispatch-sync` is already settle-by-default, so the macro added nothing on the synchronicity axis; the registrar snapshot/restore half is covered by the per-test fixture every v2 suite installs.

### M-25 (async tests) — `run-test-async` + `wait-for` / `wait-for-event`

M-52 above covers the **synchronous** test surface (`run-test-sync` → `dispatch-sync` under `make-reset-runtime-fixture`). It does **not** cover the v1 **async** test pattern — `re-frame.test/run-test-async` wrapping `wait-for` / `wait-for-event` — used wherever a test awaits an event that fires *asynchronously*: a `:http-xhrio` GET resolving, a debounce / throttle window elapsing, a `core.async` step, an `async-flow-fx` cascade settling. There is no v2 `run-test-async` and no v2 `wait-for-event`, so a v1 suite with async tests dead-ends at this rule. The three v1 surfaces map as follows (all part of M-25's `re-frame.test` → `re-frame.test-support` rename):

1. **`run-test-async` → `cljs.test/async`.** v1's macro bound an `async`-style completion callback; the v2-canonical form is the stock `cljs.test/async` macro, which binds a `done` fn you call once the awaited assertions have run. No re-frame surface is involved — this is the standard ClojureScript async-test shape.
2. **`wait-for-event` → a trace listener matching `:rf.event/run-end`.** v1 `wait-for` / `wait-for-event` registered a one-shot `add-post-event-callback` that fired when the awaited event's handler had run to completion. Per **M-26**, `add-post-event-callback` → `register-listener!` / `unregister-listener!` (the dev-only trace listener API — live under `cljs.test` / JVM test runs). Match the `:rf.event/run-end` trace marker — it is emitted **after** the handler's interceptor chain, db commit, and fx walk, i.e. the exact handler-complete timing v1's post-event callback gave (NOT `:rf.event/run-start`, which fires before the handler body). The awaited event id rides the trace event under `[:tags :rf.trace/event-id]`.
3. **The fixture `make-restore-fn` → an epoch-free snapshot/restore.** MIGRATION.md **M-26** maps v1's `make-restore-fn` to the epoch surface (`(let [snap (rf/app-db-value frame-id)] (fn [] (rf/reset-frame-db! frame-id snap)))`). `reset-frame-db!` is late-bound on the **`day8/re-frame2-epoch`** artefact (M-33) and raises `:rf.error/epoch-artefact-missing` when it is absent — a plain (non-Xray, no-epoch) test suite does not carry epoch on its classpath. For those suites, snapshot `(rf/app-db-value :rf/default)` and restore via a one-shot `reg-event-db` dispatched synchronously — no epoch dependency:

```clojure
;; SEARCH (v1)
(:require [re-frame.test :as rf-test :refer [run-test-async wait-for]]
          [day8.re-frame.test :refer [run-test-async wait-for-event]])

(use-fixtures :each (rf-test/make-restore-fn))   ; v1 snapshot+closure fixture

(deftest fetches-the-thing
  (run-test-async
    (rf/dispatch [:thing/fetch 42])              ; fires an async :http-xhrio GET
    (wait-for-event :thing/fetch-success         ; await the reply event
      (is (= :loaded (:thing/status @app-db))))))

;; REWRITE (v2)
(:require [cljs.test :refer-macros [deftest is use-fixtures async]]
          [re-frame.core :as rf]
          [re-frame.test-support :as ts])

;; Epoch-free :each fixture — snapshot on entry, restore via a one-shot
;; reg-event-db on exit. (If the suite DOES pull day8/re-frame2-epoch, the
;; M-26 reset-frame-db! restore is the simpler path — use that instead.)
;; make-reset-runtime-fixture (M-64) handles registrar/runtime isolation;
;; this fixture stacks the app-db snapshot on top.
(defn restore-app-db-fixture [t]
  (let [snap (rf/app-db-value :rf/default)]
    (rf/reg-event-db ::restore-app-db (fn [_ [_ v]] v))   ; one-shot restorer
    (try (t)
      (finally (rf/dispatch-sync [::restore-app-db snap])))))

(use-fixtures :each
  (ts/make-reset-runtime-fixture {:adapter adapter})       ; M-64
  restore-app-db-fixture)

;; wait-for-event → a one-shot trace listener matching :rf.event/run-end.
;; `done` is from cljs.test/async; `f` runs once the awaited event's handler
;; has run to completion.
(defn wait-for-event* [event-id done f]
  (let [k (keyword "test" (str "wait-" (name event-id)))]
    (rf/register-listener! k
      (fn [ev]
        (when (and (= :rf.event/run-end (:operation ev))
                   (= event-id (get-in ev [:tags :rf.trace/event-id])))
          (rf/unregister-listener! k)        ; one-shot
          (f)
          (done))))))

(deftest fetches-the-thing
  (async done                                  ; run-test-async → cljs.test/async
    (wait-for-event* :thing/fetch-success done
      (fn [] (is (= :loaded (:thing/status (rf/app-db-value :rf/default))))))
    (rf/dispatch [:thing/fetch 42])))          ; kick off the async cascade
```

**Notes.**
- The listener is **dev/test-only** (`register-listener!` rides the `re-frame.trace` surface, DCE'd under `:advanced` + `goog.DEBUG=false`) — that is correct for a test runner; do **not** reach for the always-on `register-event-listener!` here (its tight per-event record is not trace-shaped and is for production observability, not test waits).
- For a pure **state-observable** wait (the awaited effect lands in `app-db` rather than via a discrete event — e.g. a debounce that just updates a slice), prefer `re-frame.test-support/poll-until`, which returns a `js/Promise` that composes directly with `cljs.test/async` (`(-> (ts/poll-until pred) (.then ...) (.catch ...))`). Use the `:rf.event/run-end` listener when the contract is "*this event* fired", `poll-until` when it is "*this state* settled".
- After the `re-frame.test` → `re-frame.test-support` require swap, also drop the `day8/re-frame-test` Maven coord (see M-25 above) — `run-test-async` / `wait-for-event` shipped from it and have no v2 successor symbol; they become the inline shapes above.

### M-50 — `with-overrides` → `with-fx-overrides`

```clojure
;; SEARCH
(rf/with-overrides {<override-map>}
  body...)
(re-frame.core/with-overrides {<override-map>}
  body...)

;; REWRITE — rename the macro; body and override-map shape unchanged
(rf/with-fx-overrides {<override-map>}
  body...)
(re-frame.core/with-fx-overrides {<override-map>}
  body...)
```

Mechanical name-rename only. The macro's `binding` over `re-frame.router/*fx-overrides*`, the override-map shape, precedence rules, and composition with `with-frame` are unchanged — three names (macro / `:fx-overrides` opt key / `*fx-overrides*` dynvar) now share the `fx-overrides` stem. See [MIGRATION.md §M-50](../../../migration/from-re-frame-v1/README.md#m-50-with-overrides-macro-renamed-to-with-fx-overrides).

---

## Effect-map consolidation (M-8)

The single highest-impact mechanical rewrite. The transformation is structural.

```clojure
;; SEARCH
{:db   ...
 :dispatch       <event-vec>}

{:dispatch-later <map-or-vec-of-maps>}

{:dispatch-n     [<event-vec> ...]}

{:db   ...
 :<user-fx-id>   <args>}

;; REWRITE — fold every non-:db key into :fx
{:db ...
 :fx [[:dispatch <event-vec>]]}

{:fx [[:dispatch-later <map>] ...]}           ; one entry per map in the original vector

{:fx [[:dispatch <e1>] [:dispatch <e2>] ...]} ; one entry per event-vec

{:db ...
 :fx [[:<user-fx-id> <args>]]}
```

**Procedure** (sweep first, then per-handler rewrite):

1. Discover the project's user-fx ids: `rg "\(rf/reg-fx\s+:" src/` (the skill's only shell verb is `rg` — see SKILL.md `allowed-tools`).
2. Add the built-ins: `:dispatch`, `:dispatch-later`, `:dispatch-n`.
3. For each `reg-event-fx` body, walk the returned effect map literal.
4. For each top-level key other than `:db`:
   - In the discovered set → rewrite per the rules above.
   - Not in the set → **flag** (might be a destructure key, not an effect).
5. If `:fx` already exists, concatenate: existing `:fx` first, new entries after.

**Edge case → flag**: an unknown top-level key. Could be a destructure or a typo'd fx-id.

---

## Dispatch-shape rewrites

### M-4 — `dispatch-with` / `dispatch-sync-with`

```clojure
;; SEARCH
(rf/dispatch-with      <event-vec> <opts-shape>)
(rf/dispatch-sync-with <event-vec> <opts-shape>)

;; REWRITE
(rf/dispatch      <event-vec> {:fx-overrides <opts-shape>})
(rf/dispatch-sync <event-vec> {:fx-overrides <opts-shape>})
```

The `opts-shape` shape carries the same content; only the slot key changes (it now lives inside `:fx-overrides`).

### M-9 — `dispatch-sync` inside a handler

```clojure
;; SEARCH — lexically inside (reg-event-* :id ... (fn ...))
(rf/dispatch-sync <event-vec>)

;; REWRITE — move the event into :fx
{:db ...
 :fx [[:dispatch <event-vec>]]}
```

If the handler was `reg-event-db`, promote it to `reg-event-fx` so it can return an `:fx` slot.

### M-16 — `^:flush-dom` metadata

```clojure
;; SEARCH
^:flush-dom <event-vec>

;; REWRITE
;; Wrap in a :dispatch-later fx
{:fx [[:dispatch-later {:ms 0 :dispatch <event-vec>}]] ...}
```

Old form:
```clojure
{:dispatch ^:flush-dom [:do-work]
 :db       (assoc db :processing true)}
```

New form:
```clojure
{:db (assoc db :processing true)
 :fx [[:dispatch-later {:ms 0 :dispatch [:do-work]}]]}
```

---

## What this leaf is NOT

- It is not the full Type A catalogue — cross-cutting renames, view rewrites, init wiring, and per-feature artefact adds live in [`auto-cross-cutting.md`](auto-cross-cutting.md).
- It is not a substitute for [`MIGRATION.md`](../../../migration/from-re-frame-v1/README.md)'s per-rule rationale — when you apply a rewrite, you cite the rule id; you don't quote the rule's text inline.
- It is not exhaustive. The shapes here are the most common Type A trigger patterns. If a call site matches the *intent* of a Type A rule but not the *shape* here, apply the rewrite — the shapes are illustrative.

When the rewrite shape doesn't fit a real call site exactly, **stop and consult the full rule in [`MIGRATION.md`](../../../migration/from-re-frame-v1/README.md)**. Don't improvise.
