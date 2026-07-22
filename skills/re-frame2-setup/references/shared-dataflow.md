# Shared counter dataflow

The **substrate-neutral** half of the greenfield counter — the event handlers, the subscription, and the app-db schema. These three files are **identical for Reagent and UIx**: the dataflow (event → handler → app-db change → sub recompute) is a framework concern, not a substrate concern. Only the *view + mount* layer is substrate-specific.

Use this leaf when you scaffold a **UIx** greenfield (or lay the Reagent counter out across split files instead of the one-file [`first-counter.md`](first-counter.md)). Copy the three files **verbatim** into `src/your_app/`, then add the substrate entry ns + `views.cljs` from [`entry-namespace.md` §UIx greenfield](entry-namespace.md). Together those files are the **complete** emitted project — nothing else is needed, and you do **not** copy any part of the Reagent-only `first-counter.md`.

> **Single-sourced.** These exact files serve every substrate — do not fork a per-substrate copy. The Reagent one-file counter in [`first-counter.md`](first-counter.md) inlines the same `:counter/initialise` / `:counter/increment` events, `:counter/value` sub, and `CounterDb` schema; this leaf is the split-file form the non-Reagent entry namespace `:require`s.

## Contents

- The three files
- Why the schema attaches at boot (not ns-load)
- How the entry namespace wires them

---

## The three files

```
src/your_app/
├── events.cljs   ; reg-event  — :counter/initialise, :counter/increment
├── subs.cljs     ; reg-sub    — :counter/value
└── schema.cljs   ; CounterDb + register-schema! (frame-local, called at boot)
```

### `src/your_app/events.cljs`

```clojure
(ns your-app.events
  "Event handlers — substrate-neutral. Copy verbatim for a UIx
   greenfield; identical to the events the Reagent first-counter registers."
  (:require [re-frame.core :as rf]
            ;; Side-effecting load: publishes Malli's validate/explain into the
            ;; framework's late-bind hooks so the schema registered in
            ;; schema.cljs actually validates rather than soft-passing. The
            ;; schemas artefact self-wires its Malli adapter — no separate
            ;; re-frame.schemas.malli require is needed.
            [re-frame.schemas]))

(rf/reg-event :counter/initialise
  (fn [_cofx _event] {:db {:counter/value 0}}))

(rf/reg-event :counter/increment
  (fn [{:keys [db]} _event] {:db (update db :counter/value inc)}))
```

`reg-event` is the one public event-registration form. A handler is a pure `(fn [cofx event] effects-map)`: destructure `:db` from the coeffects to read the current app-db, return `{:db <next-app-db>}`. `:counter/initialise` seeds a fresh counter; `:counter/increment` bumps it. Both are frame-agnostic global registrations, so they are fine at ns-load (contrast the schema, below).

### `src/your_app/subs.cljs`

```clojure
(ns your-app.subs
  "Subscriptions — substrate-neutral. Copy verbatim for a UIx greenfield."
  (:require [re-frame.core :as rf]))

(rf/reg-sub :counter/value
  (fn [db _query] (:counter/value db)))
```

One subscription: `[:counter/value]` reads `(:counter/value db)`. re-frame2 caches by query-vector, recomputes only when inputs change, and suppresses downstream re-render when the value is unchanged — automatic, nothing to configure.

### `src/your_app/schema.cljs`

```clojure
(ns your-app.schema
  "App-db schema — substrate-neutral. Copy verbatim for a UIx greenfield.
   register-schema! is FRAME-LOCAL: it names the app frame explicitly
   ({:frame :rf/default}), so core/init calls it at boot BEFORE the
   frame-root mount creates the frame (see the boot order below)."
  (:require [re-frame.core :as rf]))

;; A whole-app-db schema attached at the empty path `[]` (get-in/assoc-in grain:
;; `[]` is "the whole map"). Closed map: a typo like `:countr/value` is caught at
;; the write boundary instead of producing a silent nil. The framework validates
;; every registered path-schema after each handler mutation; a non-conforming
;; write rolls back the `:db` effect.
(def CounterDb
  [:map {:closed true}
   [:counter/value :int]])

;; App-db schemas are FRAME-LOCAL (EP-0002 carried-frame invariant): they target
;; a frame, and a bare two-slot ns-load reg-app-schema with no frame in scope
;; raises :rf.error/no-frame-context. So the attach lives in this fn, names the
;; app frame EXPLICITLY (the optional middle metadata slot), and runs at BOOT —
;; core/init calls it before the frame-root mount even creates the frame, so
;; the frame's :initial-events seed is validated from its first write. This is
;; the same register-schema! the generator template ships.
(defn register-schema! []
  (rf/reg-app-schema [] {:frame :rf/default} CounterDb))
```

## Why the schema attaches at boot (not ns-load)

Two contracts make the attach work — the same two the Reagent counter documents in [`first-counter.md` §Schema](first-counter.md):

- **`re-frame.schemas` must be loaded before any `reg-app-schema` runs.** Requiring it (in `events.cljs` above) publishes Malli's `validate` / `explain` into the framework's late-bind hook table — so the registration actually validates rather than throwing `:rf.error/schemas-artefact-missing`. It self-requires its Malli adapter; **no** separate `re-frame.schemas.malli` require is needed.
- **The attach is frame-local and runs at boot, not at ns-load.** `reg-app-schema` targets a frame (EP-0002); a bare two-slot registration at namespace-load time has no frame scope and raises `:rf.error/no-frame-context`. `register-schema!` therefore names the app frame **explicitly** (`{:frame :rf/default}`, the optional middle metadata slot) and runs from `init` — valid even before the `frame-root` mount creates the frame.

## How the entry namespace wires them

The substrate `core.cljs` (from [`entry-namespace.md` §UIx greenfield](entry-namespace.md)) `:require`s all three namespaces so their registrations load, then boots in this order — **matching the generator template's boot order**:

1. `(rf/init! <substrate>-adapter/adapter)` — install the adapter (no frame yet).
2. `(schema/register-schema!)` — attach the frame-local schema (explicit `{:frame :rf/default}` target; the frame does not exist yet).
3. Mount through the substrate's own root API, wrapped in the adapter's `frame-root` `{:id :rf/default :initial-events [[:counter/initialise]]}` ENSURE element — it creates the frame at mount, runs the seed synchronously at creation, and reuses the live frame without re-seeding on later mounts.

Registering the schema **before** the mount means the very first `:counter/initialise` write is validated against `CounterDb`. The full boot ceremony (adapter install, the `defonce` root, the ENSURE mount) is explained in [`entry-namespace.md`](entry-namespace.md).
