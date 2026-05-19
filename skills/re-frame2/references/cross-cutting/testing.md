# Writing re-frame2 tests

Load when the task is **authoring a `deftest` / `cljs.test` test** against re-frame2 application code: an event-fx handler, a sub graph, a machine snapshot, a tag query, a view that reads from a frame. This leaf teaches only the **re-frame2-specific binding** — `clojure.test` / `cljs.test` themselves are assumed.

This skill does **not** teach the AI to *run* tests; that is the user's job. It teaches how to **write** them so they pass when the user runs the suite.

## The single import

```clojure
(:require [re-frame.core         :as rf]
          [re-frame.test-support :as ts]
          #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
             :cljs [cljs.test    :refer-macros [deftest is testing use-fixtures]]))
```

Everything you need — fixtures, helpers — lives under `re-frame.test-support`. Do not reach into `re-frame.registrar` or `re-frame.frame` directly.

## The per-test fixture (always use it)

```clojure
(use-fixtures :each
  (ts/reset-runtime-fixture {:adapter reagent-adapter/adapter}))
```

`reset-runtime-fixture` snapshot/restores the registrar around each test, resets every frame's `app-db` to `{}`, disposes any installed substrate adapter and reinstalls the one in `:adapter`, and ensures `:rf/default` is present. Per-test `reg-event-db` / `reg-sub` / `reg-machine` calls land cleanly inside the test and are rolled back on the way out — **without** wiping framework registrations (e.g. `:rf/route`, `:rf/machine` subs) that landed at namespace-load time.

JVM tests pass the plain-atom adapter:

```clojure
(:require [re-frame.substrate.plain-atom :as plain-atom])

(use-fixtures :each (ts/reset-runtime-fixture {:adapter plain-atom/adapter}))
```

Optional opts: `:init-fn` (zero-arg fn run after adapter install, before the test), `:clear-kinds` (e.g. `[:app-schema]` to start each test with an empty schema slate while preserving the snapshot on exit).

Do **not** call `(registrar/clear-all!)` from a fixture — under CLJS, framework registrations cannot be reloaded and will be gone for the rest of the run.

## Driving events: `dispatch-sync` and `dispatch-sequence`

`rf/dispatch-sync` drains to fixed point synchronously — by the time it returns, the handler has run, fx have fired, and the queue is empty. Use it instead of `rf/dispatch` in tests; `dispatch` is async and the test would assert before the handler ran.

```clojure
(rf/dispatch-sync [:counter/inc])
(is (= 1 (:n (rf/get-frame-db :rf/default))))
```

`ts/dispatch-sequence` fires a vector of events in order, each drained before the next:

```clojure
(ts/dispatch-sequence [[:counter/init] [:counter/inc] [:counter/inc]])
;; => the final app-db value
```

Capture intermediate states with `:after-each`:

```clojure
(let [seen (atom [])]
  (ts/dispatch-sequence
    [[:counter/inc] [:counter/inc]]
    {:after-each (fn [db ev] (swap! seen conj [(:n db) ev]))})
  @seen)
;; => [[1 [:counter/inc]] [2 [:counter/inc]]]
```

Target a non-default frame with `{:frame :feature/frame-id}` in the trailing opts map.

## Pinning a frame: `with-frame`

`with-frame` binds the active frame inside the body. Two shapes:

```clojure
(rf/with-frame :stories
  (rf/dispatch-sync [:counter/inc])
  (ts/assert-state [:n] 1))

(rf/with-frame [f :stories]      ;; bind a symbol AND the dynamic var
  (is (= :stories f))
  (is (= :stories (rf/current-frame))))
```

On CLJS reach the macro via `rf/with-frame` after `(:require [re-frame.core :as rf])`, or `:require-macros [re-frame.core :refer [with-frame]]`. On JVM use the `(rf/with-frame frame-id (fn [] ...))` function form.

## Asserting state: `assert-state` and `get-frame-db`

Two shapes:

```clojure
(ts/assert-state {:n 0})           ;; full-db match against current frame
(ts/assert-state [:n] 7)           ;; path match — equivalent to (= 7 (get-in db [:n]))
(ts/assert-state [:n] 7 {:frame :stories})   ;; trailing opts pins the frame
```

Failure reports through `clojure.test/is` with both expected and actual, so the diagnostic is one line. For ad-hoc reads outside an `assert-state`:

```clojure
(rf/get-frame-db :rf/default)              ;; whole app-db, any frame
(rf/snapshot-of [:cart :items])             ;; get-in over the current frame
(rf/snapshot-of [:cart :items] {:frame :stories})
```

These are value-form accessors — there is no `deref`. They work identically on JVM and CLJS.

## Asserting subscriptions: `compute-sub` (preferred) and `subscribe-once`

For a sub graph under test, **prefer `compute-sub`** — it runs the registered sub against a supplied db with no reactive cache involvement, so the test does not depend on prior subscribe state:

```clojure
(rf/reg-sub :items    (fn [db _] (:items db)))
(rf/reg-sub :item-sum :<- [:items] (fn [items _] (reduce + items)))

(is (= 60 (rf/compute-sub [:item-sum] {:items [10 20 30]})))
```

`compute-sub` supports the `:<-` chain shape exactly like `subscribe` does and validates the return value against any `:spec` metadata on the sub.

When the test is exercising the live cache (e.g. layer-2 sub on top of a real dispatch), use `subscribe-once`:

```clojure
(rf/dispatch-sync [:seed])
(is (= 60 (rf/subscribe-once [:item-sum])))
```

`subscribe-once` materialises the reaction, reads `@`, and unsubscribes — one line, no leaked subscription. Prefer it over `@(rf/subscribe ...)` in tests.

## Machine snapshots and tag queries

A machine's snapshot lives at `(get-in app-db [:rf/machines machine-id])` — a map of `{:state ... :data ... :tags ...}` (`:tags` is absent when the active state-configuration's tag union is empty).

```clojure
(rf/reg-machine :loader
  {:initial :idle
   :data    {}
   :states  {:idle    {:tags #{:empty}     :on {:fetch :loading}}
             :loading {:tags #{:transient} :on {:done :ready}}
             :ready   {:tags #{:terminal}}}})

(rf/dispatch-sync [:loader [:fetch]])

;; Direct snapshot access — full-shape assertions
(let [s (get-in (rf/get-frame-db :rf/default) [:rf/machines :loader])]
  (is (= :loading      (:state s)))
  (is (= #{:transient} (:tags s))))

;; Same assertion through the framework sub (preferred for reaction-driven tests)
(is (= :loading      (:state @(rf/sub-machine :loader))))
(is (= true          @(rf/machine-has-tag? :loader :transient)))
(is (= false         @(rf/machine-has-tag? :loader :terminal)))
```

For compound machines, `:state` is a path vector (`[:auth :dashboard]`) and `:tags` is the union along the path. `machine-has-tag?` is null-tolerant: a missing or uninitialised machine returns `false` rather than throwing.

The pure transition fn — `(rf/machine-transition machine snapshot event)` — returns `[new-snapshot fx]` with no frame and no dispatch loop. Use it when the test wants to assert transition tables in isolation.

## HTTP and other side-effecting fx

For `:rf.http/managed`, install per-call stubs around the body:

```clojure
(rf/with-managed-request-stubs
  {[:get "/api/items"] {:reply :ok :body {:items [...]}}
   [:post "/api/cart"] {:reply :failure :status 500}}
  (rf/dispatch-sync [:cart/fetch])
  (ts/assert-state [:cart :status] :ready))
```

For arbitrary fx, override the registered handler from inside the test — the fixture rolls the registration back on the way out:

```clojure
(let [calls (atom [])]
  (rf/reg-fx :app/persist (fn [_ v] (swap! calls conj v)))
  (rf/dispatch-sync [:cart/save])
  (is (= [{:items [...]}] @calls)))
```

### `:fx-overrides` per-call function value

The dispatch-opts `:fx-overrides` map also accepts **function values** — a one-shot lambda that runs in place of the registered fx-handler for this dispatch only. No registry mutation, nothing to roll back. The signature matches `reg-fx`'s binary contract: `(fn [m args] ...)`, where `m` carries `:frame` (and `:event` when the fx ran from an event handler) and `args` is the fx's arg payload.

```clojure
(let [calls (atom [])]
  (rf/dispatch-sync [:cart/save]
    {:fx-overrides {:app/persist (fn [_m args] (swap! calls conj args))}})
  (is (= [{:items [...]}] @calls)))
```

For HTTP stubs, the fn form lets the test return a canned response shape without registering a parallel `:rf.http/managed-canned-success` fx:

```clojure
(rf/dispatch-sync [:user/login {:email "user@example.com"}]
  {:fx-overrides {:rf.http/managed
                  (fn [m args]
                    (when-let [on-success (:on-success args)]
                      (rf/dispatch (conj on-success {:status 200 :body {:user "u1"}})
                                   {:frame (:frame m)})))}})
```

Per-frame `:fx-overrides` in `reg-frame` accepts the same fn-value form, so a test frame can install a stub once for every dispatch routed to it. The id-keyword form (`{:rf.http/managed :rf.http/managed-canned-success}`) is the portable pattern-level form — use it when the stub is shared across many tests or when SSR / serialisation is in play; reach for the fn form when one test wants a bespoke response.

## Checklist before declaring a test done

- The ns uses `re-frame.test-support` and `re-frame.core` only — no reach into internal namespaces.
- A `:each` `reset-runtime-fixture` is installed with the right `:adapter`.
- Event drive is `dispatch-sync` (not `dispatch`) or `ts/dispatch-sequence`.
- Sub assertions go through `compute-sub` (preferred) or `subscribe-once`; no bare `@(rf/subscribe ...)` left subscribed at test exit.
- Machine assertions use `sub-machine` / `machine-has-tag?` or `(get-in db [:rf/machines id])` — not internal machine namespaces.
- Schema-validation, fx-stubs, and frame-scoping each use the public surface above. No fixture lifts `registrar/clear-all!`.

---

*Derived from `implementation/core/src/re_frame/test_support.cljc` (public test-support surface) and `implementation/core/src/re_frame/substrate/plain_atom.cljc` (JVM adapter) @ main `89bd9c3`. Re-verify after test-support surface changes.*
