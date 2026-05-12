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

;; ---- :sub/run carries the sub's registration coord (rf2-npm2p) ------------
;;
;; Spec 009 §:rf.trace/trigger-handler table — "Inside a sub recompute (body
;; fn): the sub's coord". The `:sub/run` success-trace emits inside the
;; sub's recompute scope, so it carries the sub's own registration coord
;; (not the enclosing event handler's, even when the recompute fires
;; inside a dispatch's drain). Causa's event-detail panel + pair2's
;; jump-to-source UX render click-to-jump links from this slot on every
;; trace in a cascade, including sub recomputes.

(deftest sub-run-carries-sub-trigger
  (testing ":sub/run rides the sub's own registration coord — Causa /
   pair2 want jump-to-source to land on the reg-sub site of the sub
   that recomputed, not the upstream event handler whose db change
   caused the recompute"
    (rf/reg-sub :rf2-npm2p/n
                (fn [db _] (:n db)))
    (let [evs (record-traces
                (fn [] (deref (rf/subscribe [:rf2-npm2p/n]))))
          [run] (events-of evs :sub/run)]
      (is (some? run) ":sub/run trace fired on recompute")
      (assert-trigger-shape run :sub :rf2-npm2p/n))))

(deftest sub-run-trigger-rides-at-top-level
  (testing ":rf.trace/trigger-handler on :sub/run is a top-level field,
   NOT nested under :tags — mirrors the error / fx-handled / machine-
   transition shapes"
    (rf/reg-sub :rf2-npm2p/top-level
                (fn [db _] db))
    (let [evs   (record-traces
                  (fn [] (deref (rf/subscribe [:rf2-npm2p/top-level]))))
          [run] (events-of evs :sub/run)]
      (is (some? run))
      (is (contains? run :rf.trace/trigger-handler)
          ":rf.trace/trigger-handler lives at top level")
      (is (not (contains? (:tags run) :rf.trace/trigger-handler))
          ":rf.trace/trigger-handler does NOT live under :tags"))))

(deftest sub-run-trigger-matches-registrar-coord
  (testing "the :source-coord under :rf.trace/trigger-handler on :sub/run
   equals what the registrar holds on the sub's slot — same comparison
   the other scope tests do (fx, machine, event)"
    (rf/reg-sub :rf2-npm2p/coord
                (fn [db _] db))
    (let [sub-meta (rf/handler-meta :sub :rf2-npm2p/coord)
          evs      (record-traces
                     (fn [] (deref (rf/subscribe [:rf2-npm2p/coord]))))
          [run]    (events-of evs :sub/run)
          coord    (-> run :rf.trace/trigger-handler :source-coord)]
      (is (some? run))
      (is (= (:ns     sub-meta) (:ns coord)))
      (is (= (:file   sub-meta) (:file coord)))
      (is (= (:line   sub-meta) (:line coord)))
      (is (= (:column sub-meta) (:column coord))))))

(deftest sub-run-trigger-is-sub-not-enclosing-event
  (testing "when a sub fires during a dispatch (the event handler's
   db change is observed by a subsequent deref), :sub/run still carries
   the SUB's coord — not the enclosing event handler's. The runtime
   rebinds `*current-trigger-handler*` around the sub recompute for
   exactly this reason; otherwise tools would jump to the upstream
   event handler whenever a sub fired during a cascade."
    (rf/reg-sub :rf2-npm2p/from-cascade
                (fn [db _] (:n db)))
    ;; Register an event handler that dispatches the db change and
    ;; ALSO derefs the sub inside the same handler — that way the
    ;; recompute fires inside the in-flight event handler's binding
    ;; scope, and the trigger-handler hoist contract is what's under
    ;; test: does the inner sub-binding override the outer event-
    ;; handler-binding for the `:sub/run` emit? Per Spec 009 §:rf.trace
    ;; /trigger-handler table, yes (the inner scope wins).
    (rf/reg-event-db :rf2-npm2p/changes-n
                     (fn [db _]
                       (let [new-db (assoc db :n 1)]
                         ;; Touch the sub from inside the event body
                         ;; so the recompute fires while the event
                         ;; handler's binding is in scope.
                         @(rf/subscribe [:rf2-npm2p/from-cascade])
                         new-db)))
    (let [evs   (record-traces
                  (fn [] (rf/dispatch-sync [:rf2-npm2p/changes-n])))
          [run] (events-of evs :sub/run)]
      (is (some? run) ":sub/run fired inside the cascade")
      ;; The KIND under trigger-handler is :sub, not :event. Even
      ;; though the deref happens INSIDE the event handler's drain,
      ;; the sub-recompute body rebinds the trigger-handler. Same
      ;; shape as fx-handled — the inner binding wins over the outer.
      (assert-trigger-shape run :sub :rf2-npm2p/from-cascade))))

(deftest programmatic-sub-omits-trigger-on-run
  (testing "a sub registered without the macro path (no source-coord
   stamp on the registrar slot) emits :sub/run with no
   :rf.trace/trigger-handler field — better no-data than poison-data
   (mirrors the fx-handled programmatic path)"
    (let [reg-fn (requiring-resolve 're-frame.subs/reg-sub)]
      (reg-fn :rf2-npm2p/programmatic
              (fn [db _] db)))
    (let [evs   (record-traces
                  (fn [] (deref (rf/subscribe [:rf2-npm2p/programmatic]))))
          [run] (events-of evs :sub/run)]
      (is (some? run) ":sub/run fired")
      (is (not (contains? run :rf.trace/trigger-handler))
          "programmatic sub-registration → no coord → field omitted"))))

;; ---- cofx body carries the cofx's registration coord (rf2-npm2p) ----------
;;
;; Spec 009 §:rf.trace/trigger-handler table — "Inside a cofx fn body:
;; the cofx's coord". `cofx.cljc` rebinds `*current-trigger-handler*`
;; around the cofx fn invocation so traces emitted from inside the
;; cofx body (e.g. an instrumented http cofx emitting `:rf.http/issued`)
;; carry the cofx's own registration coord, not the enclosing event
;; handler's.
;;
;; The framework's stock cofx surface emits no success-path trace of its
;; own (`cofx.cljc` only emits `:rf.error/no-such-cofx` on the miss
;; path). To exercise the success-path binding contract, the test
;; registers a cofx whose body itself calls `trace/emit!` — exactly the
;; pattern an instrumented cofx (http, persistence, websocket) would
;; use to surface its work into the trace stream. The emitted event
;; rides the cofx's trigger-handler binding because it fires from
;; inside the cofx fn's invocation scope.

(deftest cofx-body-trace-carries-cofx-trigger
  (testing "a trace emitted from inside the cofx fn body rides the
   cofx's registration coord — the cofx rebinds
   `*current-trigger-handler*` around the body, overriding the
   enclosing event handler's binding"
    (rf/reg-cofx :rf2-npm2p/instrumented-cofx
                 (fn [ctx]
                   ;; Emit a custom trace from inside the cofx body —
                   ;; this is exactly what an instrumented cofx
                   ;; (http issuance, persistence read, etc.) does to
                   ;; surface its work into the trace stream. The
                   ;; binding established by `cofx.cljc`'s `:before`
                   ;; phase MUST cause this emit to carry the cofx's
                   ;; coord (not the enclosing event handler's).
                   (trace/emit! :rf2-npm2p/probe :rf2-npm2p/probe {:from :cofx})
                   ctx))
    (rf/reg-event-fx :rf2-npm2p/uses-cofx
                     [(rf/inject-cofx :rf2-npm2p/instrumented-cofx)]
                     (fn [_cofx _event] {}))
    (let [evs     (record-traces
                    (fn [] (rf/dispatch-sync [:rf2-npm2p/uses-cofx])))
          [probe] (events-of evs :rf2-npm2p/probe)]
      (is (some? probe) "custom trace fired from inside the cofx body")
      (assert-trigger-shape probe :cofx :rf2-npm2p/instrumented-cofx))))

(deftest cofx-body-trigger-rides-at-top-level
  (testing ":rf.trace/trigger-handler on a cofx-body trace is a
   top-level field, NOT nested under :tags"
    (rf/reg-cofx :rf2-npm2p/top-level-cofx
                 (fn [ctx]
                   (trace/emit! :rf2-npm2p/probe :rf2-npm2p/probe {})
                   ctx))
    (rf/reg-event-fx :rf2-npm2p/use-top-level-cofx
                     [(rf/inject-cofx :rf2-npm2p/top-level-cofx)]
                     (fn [_ _] {}))
    (let [evs     (record-traces
                    (fn [] (rf/dispatch-sync [:rf2-npm2p/use-top-level-cofx])))
          [probe] (events-of evs :rf2-npm2p/probe)]
      (is (some? probe))
      (is (contains? probe :rf.trace/trigger-handler)
          ":rf.trace/trigger-handler lives at top level")
      (is (not (contains? (:tags probe) :rf.trace/trigger-handler))
          ":rf.trace/trigger-handler does NOT live under :tags"))))

(deftest cofx-body-trigger-matches-registrar-coord
  (testing "the :source-coord under :rf.trace/trigger-handler on a
   cofx-body trace equals what the registrar holds on the cofx's slot"
    (rf/reg-cofx :rf2-npm2p/coord-cofx
                 (fn [ctx]
                   (trace/emit! :rf2-npm2p/probe :rf2-npm2p/probe {})
                   ctx))
    (rf/reg-event-fx :rf2-npm2p/use-coord-cofx
                     [(rf/inject-cofx :rf2-npm2p/coord-cofx)]
                     (fn [_ _] {}))
    (let [cofx-meta (rf/handler-meta :cofx :rf2-npm2p/coord-cofx)
          evs       (record-traces
                      (fn [] (rf/dispatch-sync [:rf2-npm2p/use-coord-cofx])))
          [probe]   (events-of evs :rf2-npm2p/probe)
          coord     (-> probe :rf.trace/trigger-handler :source-coord)]
      (is (some? probe))
      (is (= (:ns     cofx-meta) (:ns coord)))
      (is (= (:file   cofx-meta) (:file coord)))
      (is (= (:line   cofx-meta) (:line coord)))
      (is (= (:column cofx-meta) (:column coord))))))

(deftest programmatic-cofx-omits-trigger-on-body-trace
  (testing "a cofx registered without the macro path (no source-coord
   stamp on the registrar slot) — traces emitted from inside its body
   carry no :rf.trace/trigger-handler field. Better no-data than
   poison-data, mirroring the fx + sub programmatic paths."
    (let [reg-fn (requiring-resolve 're-frame.cofx/reg-cofx)]
      (reg-fn :rf2-npm2p/prog-cofx
              (fn [ctx]
                (trace/emit! :rf2-npm2p/probe :rf2-npm2p/probe {})
                ctx)))
    (rf/reg-event-fx :rf2-npm2p/use-prog-cofx
                     [(rf/inject-cofx :rf2-npm2p/prog-cofx)]
                     (fn [_ _] {}))
    (let [evs     (record-traces
                    (fn [] (rf/dispatch-sync [:rf2-npm2p/use-prog-cofx])))
          [probe] (events-of evs :rf2-npm2p/probe)]
      (is (some? probe))
      (is (not (contains? probe :rf.trace/trigger-handler))
          "programmatic cofx-registration → no coord → field omitted"))))
