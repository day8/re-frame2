(ns re-frame.trigger-handler-coord-test
  "Per rf2-3nn8 — `:rf.trace/trigger-handler` on `:rf.error/*` trace events.

  Every error trace emitted while a handler is in scope (event, sub, fx,
  cofx, view) carries an optional top-level `:rf.trace/trigger-handler`
  field that names the handler whose execution produced the error, along
  with the handler's registration-site source-coord. Errors emitted
  outside any handler scope (e.g. the outermost-dispatch
  `:rf.error/no-such-handler`) omit the field.

  Locked shape (per rf2-3nn8):

    {:kind         :event / :sub / :fx / :cofx / :view
     :id           <registered-id>
     :source-coord {:ns <sym> :file <string> :line <int> :column <int>}}

  Q1 — `:rf.trace/trigger-handler` (nested), NOT flat `:rf.handler/source-coord`.
  Q2 — Optional field; present when handler in scope, absent otherwise.
  Q3 — Registration-site coord (not call-site).
  Q4 — NOT elided in production.

  JVM-only here — the dynamic-var binding mechanism is platform-agnostic
  and CLJS adds no signal beyond the source-coord macro path which
  rf2-mdjp + rf2-ulxi already cover. Mirror tests under cljs are slim
  smoke checks driven by the same fixture pattern.

  Source-coord parity with the registrar is established by the
  `source-coords-test` suite (rf2-k84s); this file only checks that the
  registrar's stamp is carried onto the emitted error event."
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
  (trace/clear-trace-listeners!)
  (rf/init! plain-atom/adapter)
  (require 're-frame.routing :reload)
  (test-fn))

(use-fixtures :each reset-runtime)

;; ---- helpers --------------------------------------------------------------

(defn- record-traces
  "Run `body-fn` with a trace listener attached and return the captured
  events."
  [body-fn]
  (let [seen (atom [])]
    (rf/register-trace-listener! ::rec (fn [ev] (swap! seen conj ev)))
    (try (body-fn)
         (finally (rf/unregister-trace-listener! ::rec)))
    @seen))

(defn- errors-of
  "Filter captured traces to those whose `:operation` matches the supplied
  operation keyword."
  [evs op]
  (filterv #(and (= :error (:op-type %))
                 (= op     (:operation %)))
           evs))

(defn- assert-trigger-shape
  "Assert the value at `:rf.trace/trigger-handler` on `ev` carries the
  locked shape — `:kind`, `:id`, and a `:source-coord` map with at
  least `:ns` / `:file` / `:line` (column may be absent on
  metadata-stripped registrations)."
  [ev expected-kind expected-id]
  (let [t (:rf.trace/trigger-handler ev)]
    (is (some? t)
        (str "expected :rf.trace/trigger-handler on " (:operation ev)))
    (is (= expected-kind (:kind t)))
    (is (= expected-id   (:id t)))
    (let [c (:source-coord t)]
      (is (map? c) ":source-coord present")
      (is (symbol? (:ns c))   ":ns is a symbol")
      (is (string? (:file c)) ":file is a string")
      (is (integer? (:line c)) ":line is an integer"))))

;; ---- Q1 — top-level placement, nested shape -------------------------------

(deftest trigger-handler-rides-at-top-level
  (testing ":rf.trace/trigger-handler is a top-level field, not nested under :tags"
    (rf/reg-event-fx :rf2-3nn8/throws
                     (fn [_cofx _event]
                       (throw (ex-info "boom" {}))))
    (let [evs (record-traces
               (fn []
                 (rf/dispatch-sync [:rf2-3nn8/throws])))
          [exc] (errors-of evs :rf.error/handler-exception)]
      (is (some? exc) "handler-exception trace fired")
      (is (contains? exc :rf.trace/trigger-handler)
          ":rf.trace/trigger-handler lives at the top level of the event")
      (is (not (contains? (:tags exc) :rf.trace/trigger-handler))
          ":rf.trace/trigger-handler does NOT live under :tags"))))

;; ---- Q2 — present when handler in scope -----------------------------------

(deftest event-handler-exception-carries-trigger-handler
  (testing ":rf.error/handler-exception carries the event handler's coord"
    (rf/reg-event-fx :rf2-3nn8/throwing-event
                     (fn [_cofx _event]
                       (throw (ex-info "boom" {}))))
    (let [evs (record-traces #(rf/dispatch-sync [:rf2-3nn8/throwing-event]))
          [exc] (errors-of evs :rf.error/handler-exception)]
      (assert-trigger-shape exc :event :rf2-3nn8/throwing-event))))

(deftest fx-handler-exception-carries-trigger-handler
  (testing ":rf.error/fx-handler-exception names the fx as the trigger handler,
   not the enclosing event (the fx body is what threw)"
    (rf/reg-fx :rf2-3nn8/throwing-fx
               (fn [_ctx _args] (throw (ex-info "fx boom" {}))))
    (rf/reg-event-fx :rf2-3nn8/use-throwing-fx
                     (fn [_cofx _event]
                       {:fx [[:rf2-3nn8/throwing-fx {}]]}))
    (let [evs (record-traces #(rf/dispatch-sync [:rf2-3nn8/use-throwing-fx]))
          [exc] (errors-of evs :rf.error/fx-handler-exception)]
      (assert-trigger-shape exc :fx :rf2-3nn8/throwing-fx))))

(deftest sub-exception-carries-trigger-handler
  (testing ":rf.error/sub-exception names the failing sub"
    (rf/reg-sub :rf2-3nn8/throwing-sub
                (fn [_db _q] (throw (ex-info "sub boom" {}))))
    (let [evs (record-traces #(deref (rf/subscribe [:rf2-3nn8/throwing-sub])))
          [exc] (errors-of evs :rf.error/sub-exception)]
      (assert-trigger-shape exc :sub :rf2-3nn8/throwing-sub))))

(deftest no-such-cofx-carries-enclosing-event-trigger-handler
  (testing ":rf.error/no-such-cofx — emitted during the event handler chain
   while no specific cofx is bound (the cofx-id doesn't exist), the
   enclosing event's coord is what we carry"
    (rf/reg-event-fx :rf2-3nn8/uses-missing-cofx
                     [(rf/inject-cofx :rf2-3nn8/no-such-cofx)]
                     (fn [_cofx _event] {}))
    (let [evs (record-traces #(rf/dispatch-sync [:rf2-3nn8/uses-missing-cofx]))
          [miss] (errors-of evs :rf.error/no-such-cofx)]
      (assert-trigger-shape miss :event :rf2-3nn8/uses-missing-cofx))))

(deftest no-such-fx-carries-enclosing-event-trigger-handler
  (testing ":rf.error/no-such-fx fires from the fx walker while the event
   handler scope is still bound — the enclosing event's coord is carried"
    (rf/reg-event-fx :rf2-3nn8/uses-missing-fx
                     (fn [_cofx _event]
                       {:fx [[:rf2-3nn8/no-such-fx {}]]}))
    (let [evs (record-traces #(rf/dispatch-sync [:rf2-3nn8/uses-missing-fx]))
          [miss] (errors-of evs :rf.error/no-such-fx)]
      (assert-trigger-shape miss :event :rf2-3nn8/uses-missing-fx))))

;; ---- Q2 — negative — absent when no handler is in scope -------------------

(deftest no-such-handler-omits-trigger-handler
  (testing ":rf.error/no-such-handler fires at outermost dispatch with no
   handler in scope; :rf.trace/trigger-handler is absent"
    (let [evs (record-traces
               #(rf/dispatch-sync [:rf2-3nn8/no-such-event]))
          [miss] (errors-of evs :rf.error/no-such-handler)]
      (is (some? miss) "no-such-handler trace fired")
      (is (not (contains? miss :rf.trace/trigger-handler))
          ":rf.trace/trigger-handler is absent when no handler is in scope"))))

;; ---- Q3 — registration-site coord, not call-site --------------------------

(deftest source-coord-matches-registration-site
  (testing "the :source-coord under :rf.trace/trigger-handler equals the
   value the registrar holds on the handler's slot"
    (rf/reg-event-fx :rf2-3nn8/registration-site
                     (fn [_cofx _event]
                       (throw (ex-info "boom" {}))))
    (let [reg-meta (rf/handler-meta :event :rf2-3nn8/registration-site)
          evs      (record-traces
                    #(rf/dispatch-sync [:rf2-3nn8/registration-site]))
          [exc]    (errors-of evs :rf.error/handler-exception)
          coord    (-> exc :rf.trace/trigger-handler :source-coord)]
      ;; The registrar stamps :ns / :file / :line / :column flat on the
      ;; meta map; the trigger-handler value picks them up. Compare
      ;; field-by-field rather than via equality so a future addition
      ;; to the registrar slot doesn't break the test.
      (is (= (:ns     reg-meta) (:ns coord)))
      (is (= (:file   reg-meta) (:file coord)))
      (is (= (:line   reg-meta) (:line coord)))
      (is (= (:column reg-meta) (:column coord))))))

;; ---- programmatic registration → no coord -> no trigger-handler -----------

(deftest programmatic-registration-omits-trigger-handler
  (testing "an event handler registered without the macro (bypassing
   source-coord capture) emits errors with no :rf.trace/trigger-handler
   field — better no-data than poison-data"
    (let [reg-fn (requiring-resolve 're-frame.events/reg-event-fx)]
      (reg-fn :rf2-3nn8/no-coords
              (fn [_cofx _event] (throw (ex-info "boom" {})))))
    (let [evs   (record-traces #(rf/dispatch-sync [:rf2-3nn8/no-coords]))
          [exc] (errors-of evs :rf.error/handler-exception)]
      (is (some? exc))
      (is (not (contains? exc :rf.trace/trigger-handler))
          "programmatic registration → no coord → field omitted"))))
