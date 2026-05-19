(ns day8.re-frame2-causa.test-helpers.host-fixtures.counter
  "Headless host fixture matching the canonical counter example at
  `examples/reagent/counter/core.cljs`.

  The full testbed registers Reagent views (`reg-view`) and mounts
  via `rdc/create-root`; this fixture extracts ONLY the event /
  sub graph the testbed exercises — every host dispatch that
  matters for Causa observation is event-shaped, not view-shaped.

  The Node CLJS test process uses the plain-atom substrate, so views
  cannot mount; for multi-frame e2e tests we want fast pure-pipeline
  coverage anyway (the rendering surface is browser-level concern,
  out of scope per the multi-frame e2e finding).

  ## Contract

  `(install!)` is the canonical entry point used by
  `with-host-and-causa-frames`. Idempotent — re-running is a no-op
  beyond the registrar's harmless `:rf.warning/handler-replaced`
  emit (which the test fixtures' `reset-runtime-fixture` rolls back
  via the captured registrar snapshot)."
  (:require [re-frame.core :as rf]))

(defn install!
  "Register the counter app's events + subs. Matches the canonical
  counter example one-for-one so the bug surface is identical:
  `:counter/initialise` seeds the slot at `5`, `:counter/inc` and
  `:counter/dec` walk it."
  []
  (rf/reg-event-db :counter/initialise
    (fn [_db _event] {:counter/value 5}))

  (rf/reg-event-db :counter/inc
    (fn [db _event] (update db :counter/value inc)))

  (rf/reg-event-db :counter/dec
    (fn [db _event] (update db :counter/value dec)))

  (rf/reg-sub :counter/value
    (fn [db _query] (:counter/value db)))

  nil)

(defn install-and-init!
  "Install the counter fixture AND fire `:counter/initialise` so the
  host's `app-db` starts at `{:counter/value 5}` (matching the
  example's `run` fn). Tests that care about the initial state should
  use this entry point; tests that want to assert the boot-empty
  state can call `install!` alone."
  []
  (install!)
  (rf/dispatch-sync [:counter/initialise])
  nil)
