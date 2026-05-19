(ns day8.re-frame2-causa.test-helpers.host-fixtures.deliberate-throw
  "Headless host fixture matching `testbeds/deliberate_throw/core.cljs`.

  Only Buttons A and B (handler-exception, fx-handler-exception) are
  exercised here — they cover the most common error paths Causa
  surfaces in the Issues / Trace / Event Detail panels and don't
  drag in `re-frame.flows` / `re-frame.machines` (which require
  additional test-runtime wiring).

  Tests that need the flow-exception or machine-action-exception
  surface can extend this fixture; covering those two is out of
  scope for the Phase 1 e2e helper (rf2-7icrs)."
  (:require [re-frame.core :as rf]))

(defn install!
  "Register the deliberate-throw subset (handler + fx categories).
  Each entry's throw site is identical to the canonical testbed so
  the bug surface is preserved."
  []
  (rf/reg-event-db :deliberate-throw/initialise
    (fn [_db _ev]
      {:click-count {:handler 0 :fx 0}}))

  ;; Button A — synchronous throw in event handler
  (rf/reg-event-db :deliberate-throw/throw-in-handler
    (fn [_db _ev]
      (throw (ex-info "deliberate-throw / handler" {:where :handler}))))

  ;; Button B — throw inside fx body
  (rf/reg-fx :deliberate-throw/boom
    (fn [_frame-ctx _args]
      (throw (ex-info "deliberate-throw / fx" {:where :fx}))))

  (rf/reg-event-fx :deliberate-throw/throw-in-fx
    (fn [{:keys [db]} _ev]
      {:db (update-in db [:click-count :fx] inc)
       :fx [[:deliberate-throw/boom {}]]}))

  (rf/reg-sub :deliberate-throw/handler-count
    (fn [db _] (get-in db [:click-count :handler])))

  (rf/reg-sub :deliberate-throw/fx-count
    (fn [db _] (get-in db [:click-count :fx])))

  nil)

(defn install-and-init!
  "Install + seed the click-count map. Tests that want the boot-
  empty surface skip the init dispatch."
  []
  (install!)
  (rf/dispatch-sync [:deliberate-throw/initialise])
  nil)
