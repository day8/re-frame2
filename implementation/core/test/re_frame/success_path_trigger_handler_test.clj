(ns re-frame.success-path-trigger-handler-test
  "Per rf2-lf84g — `:rf.trace/trigger-handler` rides success-path trace events.

  Spec 009 §Trace correlation widens the trigger-handler coverage post
  rf2-lf84g: every trace event emitted inside a handler's execution
  scope (event handler chain, fx handler body, sub recompute, cofx
  injector, view render) carries the in-scope handler's registration
  coord under the top-level `:rf.trace/trigger-handler` slot. Originally
  introduced (rf2-3nn8) for error events only; widened (rf2-lf84g) so
  success-path traces — `:rf.fx/handled`, `:rf.machine/transition`,
  `:event/db-changed`, `:event/do-fx`, ... — also carry the coord, so
  consumer tools (Story, Causa, re-frame-pair) can render
  jump-to-source links from every event in a cascade, not just errors.

  Locked shape (per rf2-3nn8 / rf2-lf84g):

    {:kind         :event / :sub / :fx / :cofx / :view
     :id           <registered-id>
     :source-coord {:ns <sym> :file <string> :line <int> :column <int>}}

  Slot placement: top-level on the trace event, NOT under `:tags` —
  mirrors the error path exactly. Production elision: rides the same
  `interop/debug-enabled?` gate as the rest of the trace surface; no
  separate elision contract.

  JVM-only — the dynamic-var binding mechanism is platform-agnostic.
  Mirror tests for the machine emit site live in
  `implementation/machines/test/re_frame/machine_transition_trigger_handler_test.clj`."
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

(defn- assert-trigger-shape
  "Assert the value at top-level `:rf.trace/trigger-handler` on `ev`
  carries the locked shape — `:kind`, `:id`, and a `:source-coord` map
  with at least `:ns` / `:file` / `:line`."
  [ev expected-kind expected-id]
  (let [t (:rf.trace/trigger-handler ev)]
    (is (some? t)
        (str "expected :rf.trace/trigger-handler on " (:operation ev)))
    (is (= expected-kind (:kind t)) "kind matches")
    (is (= expected-id   (:id t))   "id matches")
    (let [c (:source-coord t)]
      (is (map? c) ":source-coord present")
      (is (symbol? (:ns c))    ":ns is a symbol")
      (is (string? (:file c))  ":file is a string")
      (is (integer? (:line c)) ":line is an integer"))))

;; ---- :rf.fx/handled carries the fx-handler's registration coord -----------

(deftest fx-handled-carries-fx-handler-trigger
  (testing ":rf.fx/handled rides the FX handler's registration coord
   (not the enclosing event handler's) — Story/Causa want jump-to-source
   to land on the fx's reg-fx site, not the event-handler that produced
   the fx vector"
    (rf/reg-fx :rf2-lf84g/my-fx
               (fn [_ctx _args] :ok))
    (rf/reg-event-fx :rf2-lf84g/uses-my-fx
                     (fn [_cofx _event]
                       {:fx [[:rf2-lf84g/my-fx {:k 1}]]}))
    (let [evs (record-traces #(rf/dispatch-sync [:rf2-lf84g/uses-my-fx]))
          [handled] (events-of evs :rf.fx/handled)]
      (is (some? handled) ":rf.fx/handled trace fired")
      (assert-trigger-shape handled :fx :rf2-lf84g/my-fx))))

(deftest fx-handled-trigger-rides-at-top-level
  (testing ":rf.trace/trigger-handler is a top-level field on success
   traces, NOT nested under :tags — mirrors the error path shape"
    (rf/reg-fx :rf2-lf84g/top-level-fx (fn [_ _] :ok))
    (rf/reg-event-fx :rf2-lf84g/use-top-level
                     (fn [_ _] {:fx [[:rf2-lf84g/top-level-fx {}]]}))
    (let [evs (record-traces #(rf/dispatch-sync [:rf2-lf84g/use-top-level]))
          [handled] (events-of evs :rf.fx/handled)]
      (is (contains? handled :rf.trace/trigger-handler)
          ":rf.trace/trigger-handler lives at top level")
      (is (not (contains? (:tags handled) :rf.trace/trigger-handler))
          ":rf.trace/trigger-handler does NOT live under :tags"))))

(deftest fx-handled-trigger-matches-registrar-coord
  (testing "the :source-coord under :rf.trace/trigger-handler on
   :rf.fx/handled equals what the fx registrar holds"
    (rf/reg-fx :rf2-lf84g/coord-fx (fn [_ _] :ok))
    (rf/reg-event-fx :rf2-lf84g/use-coord
                     (fn [_ _] {:fx [[:rf2-lf84g/coord-fx {}]]}))
    (let [fx-meta (rf/handler-meta :fx :rf2-lf84g/coord-fx)
          evs     (record-traces #(rf/dispatch-sync [:rf2-lf84g/use-coord]))
          [hdl]   (events-of evs :rf.fx/handled)
          coord   (-> hdl :rf.trace/trigger-handler :source-coord)]
      (is (= (:ns     fx-meta) (:ns coord)))
      (is (= (:file   fx-meta) (:file coord)))
      (is (= (:line   fx-meta) (:line coord)))
      (is (= (:column fx-meta) (:column coord))))))

;; ---- the reserved-fx-id path stamps the enclosing event handler -----------

(deftest dispatch-fx-success-trigger-is-event-handler
  (testing "the reserved fx-id `:dispatch` has no registration of its
   own — the trigger-handler is the enclosing event handler. Reserved
   fx-id success traces stamp the outermost in-scope handler."
    (rf/reg-event-fx :rf2-lf84g/parent
                     (fn [_ _] {:fx [[:dispatch [:rf2-lf84g/child]]]}))
    (rf/reg-event-db :rf2-lf84g/child (fn [db _] (assoc db :child? true)))
    (let [evs       (record-traces #(rf/dispatch-sync [:rf2-lf84g/parent]))
          handled   (events-of evs :rf.fx/handled)
          parent-fx (first (filter #(= :dispatch (get-in % [:tags :fx-id])) handled))]
      (is (some? parent-fx) ":dispatch :rf.fx/handled fired")
      ;; The reserved-fx-id path runs inside the parent event's
      ;; *current-trigger-handler* binding (no inner fx binding kicks in
      ;; for reserved fx-ids since there's no registered handler).
      (assert-trigger-shape parent-fx :event :rf2-lf84g/parent))))

;; ---- programmatic registration → no coord -> no trigger-handler -----------

(deftest fx-handled-omits-trigger-when-no-coord
  (testing "an fx registered without the macro path (no source-coord
   stamp on the registrar slot) emits :rf.fx/handled with no
   :rf.trace/trigger-handler field — better no-data than poison-data
   (mirrors the error-path contract)"
    (let [reg-fn (requiring-resolve 're-frame.fx/reg-fx)]
      (reg-fn :rf2-lf84g/programmatic-fx (fn [_ _] :ok)))
    ;; Register the event handler programmatically too so the cascade
    ;; carries no coord at any layer — otherwise the outer event
    ;; handler's coord would ride on `:rf.fx/handled` for reserved-fx-id
    ;; emits. Here we exercise the user-fx path: the binding is the fx
    ;; handler's, which has no coord, so the field is omitted.
    (let [reg-ev (requiring-resolve 're-frame.events/reg-event-fx)]
      (reg-ev :rf2-lf84g/uses-prog-fx
              (fn [_ _] {:fx [[:rf2-lf84g/programmatic-fx {}]]})))
    (let [evs       (record-traces #(rf/dispatch-sync [:rf2-lf84g/uses-prog-fx]))
          [handled] (events-of evs :rf.fx/handled)]
      (is (some? handled) ":rf.fx/handled fired")
      (is (not (contains? handled :rf.trace/trigger-handler))
          "programmatic fx-registration → no coord → field omitted"))))

;; ---- the field rides on every event in the cascade -----------------------

(deftest event-db-changed-and-do-fx-carry-event-handler-trigger
  (testing "`:event/db-changed` and `:event/do-fx` fire inside the event
   handler's *current-trigger-handler* binding — they carry the event
   handler's registration coord under :rf.trace/trigger-handler"
    (rf/reg-event-fx :rf2-lf84g/changes-db
                     (fn [_ _] {:db {:n 1} :fx []}))
    (let [evs   (record-traces #(rf/dispatch-sync [:rf2-lf84g/changes-db]))
          [dbc] (events-of evs :event/db-changed)
          [dof] (events-of evs :event/do-fx)]
      (is (some? dbc) ":event/db-changed fired")
      (is (some? dof) ":event/do-fx fired")
      (assert-trigger-shape dbc :event :rf2-lf84g/changes-db)
      (assert-trigger-shape dof :event :rf2-lf84g/changes-db))))

;; ---- out-of-band emits omit trigger-handler -------------------------------

(deftest registration-traces-omit-trigger-handler
  (testing "trace events emitted OUTSIDE any handler's scope (registration
   time, frame creation) carry no :rf.trace/trigger-handler"
    (let [seen (atom [])]
      (rf/register-trace-cb! ::rec (fn [ev] (swap! seen conj ev)))
      (try
        (rf/reg-event-db :rf2-lf84g/reg-time-event (fn [db _] db))
        (let [reg-traces (filter #(= :rf.registry/handler-registered (:operation %))
                                 @seen)]
          (is (seq reg-traces) "we saw at least one registration trace")
          (doseq [ev reg-traces]
            (is (not (contains? ev :rf.trace/trigger-handler))
                (str "out-of-band " (:operation ev)
                     " must omit :rf.trace/trigger-handler"))))
        (finally
          (rf/remove-trace-cb! ::rec))))))
