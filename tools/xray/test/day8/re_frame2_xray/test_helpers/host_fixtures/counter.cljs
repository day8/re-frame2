(ns day8.re-frame2-xray.test-helpers.host-fixtures.counter
  "Headless host fixture matching the canonical counter example at
  `examples/core/counter/core.cljs`.

  The full testbed registers Reagent views (`reg-view`) and mounts
  via `rdc/create-root`; this fixture extracts ONLY the event /
  sub graph the testbed exercises — every host dispatch that
  matters for Xray observation is event-shaped, not view-shaped.

  The Node CLJS test process uses the plain-atom substrate, so views
  cannot mount; for multi-frame e2e tests we want fast pure-pipeline
  coverage anyway (the rendering surface is browser-level concern,
  out of scope per the multi-frame e2e finding).

  ## Contract

  `(install!)` is the canonical entry point used by
  `with-host-and-xray-frames`. Idempotent — re-running is a no-op
  beyond the registrar's harmless `:rf.warning/handler-replaced`
  emit (which the test fixtures' `make-reset-runtime-fixture` rolls back
  via the captured registrar snapshot)."
  (:require [re-frame.core :as rf]
            [re-frame.source-store :as rf.source-store]))

(defn install!
  "Register the counter app's events + subs. Matches the canonical
  counter example one-for-one so the bug surface is identical:
  `:counter/initialise` seeds the slot at `5`, `:counter/inc` and
  `:counter/dec` walk it."
  []
  ;; rf2-h1vqa4 bundle co-load hygiene: the story testbed
  ;; `counter-with-stories.events` registers the SAME canonical counter ids
  ;; at its ns load (the whole point of both is to mirror the canonical
  ;; counter example). Two provenance rows for one id fail the host frame's
  ;; default-image assembly loud, so the fixture CLAIMS each id — dropping
  ;; any sibling-namespace rows before registering its own. Suites that own
  ;; those siblings restore them from their fixture baselines per test.
  (doseq [[kind id] [[:event :counter/initialise]
                     [:event :counter/inc]
                     [:event :counter/dec]
                     [:sub   :counter/value]]]
    (rf.source-store/forget-id! kind id))
  (rf/reg-event :counter/initialise
    (fn [{:keys [db]} _event] {:db {:counter/value 5}}))

  (rf/reg-event :counter/inc
    (fn [{:keys [db]} _event] {:db (update db :counter/value inc)}))

  (rf/reg-event :counter/dec
    (fn [{:keys [db]} _event] {:db (update db :counter/value dec)}))

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
