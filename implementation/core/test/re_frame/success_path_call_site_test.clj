(ns re-frame.success-path-call-site-test
  "Per rf2-twt7m Change 1 — `:rf.trace/call-site` rides success-path trace
  events.

  Mirror to `success_path_trigger_handler_test` (rf2-lf84g) for the
  call-site slot. Where trigger-handler names the registration site of
  the in-scope handler, call-site names the **invocation line** of the
  surface macro (`rf/dispatch`, `rf/dispatch-sync`, `rf/subscribe`,
  `rf/inject-cofx`).

  Originally introduced (rf2-ts1a) for error events only; widened by
  rf2-twt7m so success-path traces — starting with `:event/dispatched`
  itself — also carry the dispatch-site coord. The Event lens redesign
  (rf2-zh2qc) and any consumer building click-to-source UX on the
  enqueue trace would otherwise lose the slot.

  Locked shape (per rf2-ts1a):

    {:ns <sym> :file <string> :line <int> :column <int>}

  Slot placement: top-level on the trace event, NOT under `:tags` —
  mirrors the error / trigger-handler shape exactly. Production
  elision: rides the same `interop/debug-enabled?` gate the rest of
  the trace surface uses; no separate elision contract.

  JVM-only — the dynamic-var binding mechanism is platform-agnostic."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.registrar :as registrar]
            [re-frame.schemas :as schemas]
            [re-frame.flows :as flows]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.trace :as trace]))

;; ---- fixtures -------------------------------------------------------------

(defn reset-runtime [test-fn]
  (registrar/clear-all!)
  (reset! frame/frames {})
  (reset! flows/flows {})
  (reset! schemas/schemas-by-frame {})
  (trace/clear-trace-cbs!)
  (rf/init! plain-atom/adapter)
  (require 're-frame.routing :reload)
  (test-fn))

(use-fixtures :each reset-runtime)

;; ---- helpers --------------------------------------------------------------

(defn- record-traces
  [body-fn]
  (let [seen (atom [])]
    (rf/register-trace-cb! ::rec (fn [ev] (swap! seen conj ev)))
    (try (body-fn)
         (finally (rf/remove-trace-cb! ::rec)))
    @seen))

(defn- events-of [evs op]
  (filterv #(= op (:operation %)) evs))

;; ---- Change 1 — `:event/dispatched` carries `:rf.trace/call-site` ---------

(deftest event-dispatched-success-carries-call-site
  (testing ":event/dispatched (success path) carries :rf.trace/call-site
   when the dispatch came in via the macro form"
    (rf/reg-event-db :rf2-twt7m/noop (fn [db _] db))
    (let [evs       (record-traces
                      (fn []
                        (rf/dispatch-sync [:rf2-twt7m/noop])))
          [enqueue] (events-of evs :event/dispatched)]
      (is (some? enqueue) ":event/dispatched fired")
      (is (contains? enqueue :rf.trace/call-site)
          ":rf.trace/call-site hoisted onto the success-path emit")
      (let [cs (:rf.trace/call-site enqueue)]
        (is (symbol? (:ns cs))   ":ns is a symbol")
        (is (string? (:file cs)) ":file is a string")
        (is (integer? (:line cs)) ":line is an integer")
        (is (re-find #"success_path_call_site_test" (:file cs))
            (str ":file should point at this test file — got " (:file cs)))))))

(deftest event-dispatched-call-site-rides-at-top-level
  (testing ":rf.trace/call-site is a top-level field on success traces,
   NOT nested under :tags — mirrors the error / trigger-handler shape"
    (rf/reg-event-db :rf2-twt7m/top-level (fn [db _] db))
    (let [evs       (record-traces
                      (fn []
                        (rf/dispatch-sync [:rf2-twt7m/top-level])))
          [enqueue] (events-of evs :event/dispatched)]
      (is (contains? enqueue :rf.trace/call-site)
          ":rf.trace/call-site lives at top level")
      (is (not (contains? (:tags enqueue) :rf.trace/call-site))
          ":rf.trace/call-site does NOT live under :tags"))))

(deftest event-dispatched-fn-form-omits-call-site
  (testing "the fn-form `dispatch-sync*` does NOT stamp a call-site,
   so :event/dispatched carries no slot — better no-data than
   poison-data (mirrors the error-path contract)"
    (rf/reg-event-db :rf2-twt7m/fn-form (fn [db _] db))
    (let [evs       (record-traces
                      (fn []
                        (rf/dispatch-sync* [:rf2-twt7m/fn-form])))
          [enqueue] (events-of evs :event/dispatched)]
      (is (some? enqueue) ":event/dispatched fired")
      (is (not (contains? enqueue :rf.trace/call-site))
          ":rf.trace/call-site omitted on the fn-form path"))))

;; ---- inner cascade emits carry the same call-site -------------------------

(deftest cascade-success-traces-carry-call-site
  (testing "every success-path trace emitted INSIDE the cascade (e.g.
   :event/db-changed, :event/do-fx, :rf.fx/handled) carries the
   dispatch's call-site — rf2-twt7m Change 1 widens the hoist to
   match the trigger-handler treatment (rf2-lf84g)"
    (rf/reg-fx :rf2-twt7m/my-fx (fn [_ _] :ok))
    (rf/reg-event-fx :rf2-twt7m/cascade
                     (fn [_ _] {:db {:n 1} :fx [[:rf2-twt7m/my-fx {}]]}))
    (let [evs       (record-traces
                      (fn []
                        (rf/dispatch-sync [:rf2-twt7m/cascade])))
          [dbc]     (events-of evs :event/db-changed)
          [dof]     (events-of evs :event/do-fx)
          [handled] (events-of evs :rf.fx/handled)]
      (is (some? dbc) ":event/db-changed fired")
      (is (some? dof) ":event/do-fx fired")
      (is (some? handled) ":rf.fx/handled fired")
      ;; The macro stamps a call-site onto the envelope;
      ;; `process-event!` binds it via `with-dispatch-id+call-site`;
      ;; every emit inside the cascade hoists it (rf2-twt7m).
      (is (contains? dbc :rf.trace/call-site)
          ":event/db-changed carries the dispatch's call-site")
      (is (contains? dof :rf.trace/call-site)
          ":event/do-fx carries the dispatch's call-site")
      (is (contains? handled :rf.trace/call-site)
          ":rf.fx/handled carries the dispatch's call-site"))))
