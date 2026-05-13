# auto-call-site-rewrites

Type A — per-call-site mechanical rewrites the agent applies without asking. Covers namespace requires, effect-map consolidation, and dispatch-shape changes. The agent walks call sites, applies the search→rewrite shapes verbatim, and cites the rule id (`M-N`) in the migration report.

For the *why* of each rule, see [`MIGRATION.md`](../../../spec/MIGRATION.md). This leaf is a shape catalogue, not a rationale. For cross-cutting renames (keywords, interceptor lists, views, init, per-feature artefacts), see [`auto-cross-cutting.md`](auto-cross-cutting.md). For judgment-call rewrites, see [`guided-handlers-state.md`](guided-handlers-state.md) and [`guided-interceptors-subs.md`](guided-interceptors-subs.md).

## Contents

- Dep-coord and namespace rewrites (M-0, M-1, M-38, M-23, M-25, M-52)
- Effect-map consolidation (M-8)
- Dispatch-shape rewrites (M-4, M-9, M-16)

---

## Dep-coord and namespace rewrites

### M-0 — Dep coord swap

See `reference/setup.md` for the per-build-tool detail. Applied once, in Phase 2.

### M-1 — Private-namespace requires

```clojure
;; SEARCH
(:require [re-frame.db :as db])
(:require [re-frame.db :refer [app-db]])
(:require [re-frame.router :as router])
(:require [re-frame.subs :as subs])
(:require [re-frame.events :as events])
(:require [re-frame.registrar :as reg])

;; REWRITE
;; Remove the :require entirely; replace usages per the table:
@db/app-db           → (rf/get-frame-db :rf/default)
@re-frame.db/app-db  → (rf/get-frame-db :rf/default)
(reset! re-frame.db/app-db v) → flag (Type B — see M-15) — propose
                                 (rf/dispatch-sync [::reset-app-db v])
(subs/clear-subscription-cache!) → (rf/clear-subscription-cache! :rf/default)
(reg/get-handler kind id) → (rf/get-handler kind id)
```

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

**Edge case → Type B**: any `:re-frame/lifecycle` annotation in the original — drop the annotation, file a follow-up bead if the user explicitly wanted non-default lifecycle.

### M-25 — `re-frame.test` rename

```clojure
;; SEARCH
(:require [re-frame.test :as rf-test])
(:require [day8.re-frame.test :as rf-test])

;; REWRITE
(:require [re-frame.test-support :as rf-test])
```

Surviving helper names (`dispatch-sequence`, `assert-state`) unchanged. `run-test-sync` is dropped in v2 — see **M-52** below to rewrite call sites.
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
;; (assumes the ns already installs reset-runtime-fixture via use-fixtures :each;
;; if not, add it — see M-52 in MIGRATION.md for the full pattern)
body...

;; AD-HOC ALTERNATIVE — one-off bracket without converting the ns to use a :each fixture
(ts/with-fresh-registrar
  (fn []
    body...))
```

v2's `dispatch-sync` is already settle-by-default, so the macro added nothing on the synchronicity axis; the registrar snapshot/restore half is covered by the per-test fixture every v2 suite installs.

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

1. Discover the project's user-fx ids: `grep -E "\\(rf/reg-fx\\s+:" -r src/`.
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
- It is not a substitute for [`MIGRATION.md`](../../../spec/MIGRATION.md)'s per-rule rationale — when you apply a rewrite, you cite the rule id; you don't quote the rule's text inline.
- It is not exhaustive. The shapes here are the most common Type A trigger patterns. If a call site matches the *intent* of a Type A rule but not the *shape* here, apply the rewrite — the shapes are illustrative.

When the rewrite shape doesn't fit a real call site exactly, **stop and consult the full rule in [`MIGRATION.md`](../../../spec/MIGRATION.md)**. Don't improvise.
