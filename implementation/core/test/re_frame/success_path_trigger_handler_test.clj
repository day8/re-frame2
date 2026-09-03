(ns re-frame.success-path-trigger-handler-test
  "Per rf2-lf84g — `:rf.trace/trigger-handler` rides success-path trace events.

  Spec 009 §Trace correlation widens the trigger-handler coverage post
  rf2-lf84g: every trace event emitted inside a handler's execution
  scope (event handler chain, fx handler body, sub recompute, cofx
  injector, view render) carries the in-scope handler's registration
  coord under the top-level `:rf.trace/trigger-handler` slot. Originally
  introduced (rf2-3nn8) for error events only; widened (rf2-lf84g) so
  success-path traces — `:rf.fx/handled`, `:rf.machine/transition`,
  `:rf.event/db-changed`, `:rf.fx/do-fx`, ... — also carry the coord, so
  consumer tools (Story, Xray, re-frame-pair) can render
  jump-to-source links from every event in a cascade, not just errors.

  Locked shape (per rf2-3nn8 / rf2-lf84g):

    {:kind         :event / :sub / :fx / :cofx / :view
     :id           <registered-id>
     :source-coord {:ns <sym> :file <string> :line <int> :column <int>}}

  Slot placement: top-level on the trace event, NOT under `:tags` —
  mirrors the error path exactly. Production elision: rides the same
  `rf.interop/debug-enabled?` gate as the rest of the trace surface; no
  separate elision contract.

  JVM-only — the dynamic-var binding mechanism is platform-agnostic.
  Mirror tests for the machine emit site live in
  `implementation/machines/test/re_frame/machine_transition_trigger_handler_test.clj`.

  ## Posture split (rf2-d2841)

  The docstring above is right that the SLOT \"rides the same
  `rf.interop/debug-enabled?` gate as the rest of the trace surface\", and there is
  no success-path production channel to move it to — unlike the error axis,
  where `error-emit/dispatch-on-error!` fans a tight record. So the
  trace-shape and trace-placement claims are guarded.

  What is NOT dev-only is the COORD ITSELF. `rf.registrar/register!` calls
  `source-coords/remember-error-coords!` unconditionally, so the always-on
  `error-coords-by-id` registry holds the registration coordinate for every
  macro-path `reg-fx` / `reg-sub` / `reg-cofx` / `reg-event` in BOTH postures
  — it is what an off-box error shipper reads in production. Every case here
  therefore keeps an always-on assertion on that registry, and the three
  \"better no-data than poison-data\" cases keep theirs as a NEGATIVE on the
  same registry with a macro-path control beside it, so the negative cannot
  pass for free. Alongside those sit the posture-independent semantics the
  trace was standing in for: the fx ran, the child dispatch committed, the sub
  recomputed to the right value, the cofx supplied its fact.

  TWELVE VACUOUS PASSES CAME OFF (rf2-d2841 class 3 — a positive assertion the
  gate short-circuits). The three `…-matches-registrar-coord` deftests each
  compare `(:ns handler-meta)` against `(:ns coord)` field by field, four
  fields apiece. Under the gate `handler-meta` has been stripped of coord keys
  AND the trace event does not exist, so every one of those twelve is
  `nil = nil` — parity certified between two absences. `fx-handled-trigger-
  matches-registrar-coord` was GREEN under the gate on nothing but those four.
  Four more, class 4, sat in the `…-omits-trigger-…` trio and
  `…-rides-at-top-level` pair: `(not (contains? handled …))` over the nil an
  empty trace ring yields."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.interop :as rf.interop]
            [re-frame.source-coords :as rf.source-coords]
            [re-frame.frame :as rf.frame]
            [re-frame.registrar :as rf.registrar]
            [re-frame.schemas :as rf.schemas]
            [re-frame.flows :as rf.flows]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.trace :as rf.trace]))

;; ---- fixtures -------------------------------------------------------------

(defn reset-runtime [test-fn]
  (rf.registrar/clear-all!)
  (reset! rf.frame/frames {})
  (rf.flows/reset-flows!)
  (rf.schemas/clear-schemas-by-frame!)
  (rf.trace/clear-listeners!)
  (rf/init! rf.substrate.plain-atom/adapter)
  (require 're-frame.routing :reload)
  ;; EP-0002 (rf2-9o48ih): `init!` no longer synthesises `:rf/default`;
  ;; framework operation surfaces require a carried frame stamp. Register
  ;; `:rf/default` + pin it as the body's ambient scope (the carried-
  ;; invariant equivalent of `(with-frame :rf/default …)`); explicit
  ;; `{:frame …}` opts in the test bodies still win.
  (rf/make-frame {:id :rf/default})
  (rf/with-frame :rf/default
    (test-fn)))

(use-fixtures :each reset-runtime)

;; ---- helpers --------------------------------------------------------------

(defn- record-traces
  [body-fn]
  (let [seen (atom [])]
    (rf/register-listener! :trace ::rec (fn [ev] (swap! seen conj ev)))
    (try (body-fn)
         (finally (rf/unregister-listener! :trace ::rec)))
    @seen))

(defn- events-of [evs op]
  (filterv #(= op (:operation %)) evs))

(defn- assert-always-on-coord
  "ALWAYS-ON (rf2-d2841): the registration coordinate the always-on
  `error-coords-by-id` registry holds for `[kind id]`. `rf.registrar/register!`
  populates it unconditionally, so this is the production-posture statement of
  the handler registration site is known — the substance
  `:rf.trace/trigger-handler` carries on the dev trace."
  [kind id]
  (let [c (rf.source-coords/error-coords-for kind id)]
    (is (map? c)
        (str "always-on registration coord present for " kind " " id))
    (is (symbol? (:ns c))    ":ns is a symbol")
    (is (string? (:file c))  ":file is a string")
    (is (integer? (:line c)) ":line is an integer")
    c))

(defn- assert-no-always-on-coord
  "ALWAYS-ON (rf2-d2841): a programmatic registration bypassed the macro path,
  so the always-on registry has NOTHING for it — better no-data than
  poison-data, stated where it survives production. `control-kind` /
  `control-id` name a macro-path sibling registered in the same test, so the
  negative cannot pass merely because the registry is empty."
  [kind id control-kind control-id]
  (is (nil? (rf.source-coords/error-coords-for kind id))
      (str "programmatic " kind " " id " stored no always-on coord"))
  (is (some? (rf.source-coords/error-coords-for control-kind control-id))
      (str "control: macro-path " control-kind " " control-id
           " DID store one, so the negative above is not free")))

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
   (not the enclosing event handler's) — Story/Xray want jump-to-source
   to land on the fx's reg-fx site, not the event-handler that produced
   the fx vector"
    (let [ran (atom nil)]
      (rf/reg-fx :rf2-lf84g/my-fx
                 (fn [_ctx args] (reset! ran args) :ok))
      (rf/reg-event :rf2-lf84g/uses-my-fx
                       (fn [_cofx _event]
                         {:fx [[:rf2-lf84g/my-fx {:k 1}]]}))
      (let [evs (record-traces #(rf/dispatch-sync [:rf2-lf84g/uses-my-fx]))
            [handled] (events-of evs :rf.fx/handled)]
        ;; ALWAYS-ON (rf2-d2841): the fx really ran with its args, and its
        ;; registration coord is on the always-on registry — the production
        ;; half of "jump-to-source lands on the reg-fx site".
        (is (= {:k 1} @ran) "the fx handler ran with its authored args")
        (assert-always-on-coord :fx :rf2-lf84g/my-fx)
        (when rf.interop/debug-enabled?
          (is (some? handled) ":rf.fx/handled trace fired")
          (assert-trigger-shape handled :fx :rf2-lf84g/my-fx))))))

(deftest fx-handled-trigger-rides-at-top-level
  (testing ":rf.trace/trigger-handler is a top-level field on success
   traces, NOT nested under :tags — mirrors the error path shape"
    (rf/reg-fx :rf2-lf84g/top-level-fx (fn [_ _] :ok))
    (rf/reg-event :rf2-lf84g/use-top-level
                     (fn [_ _] {:fx [[:rf2-lf84g/top-level-fx {}]]}))
    (let [evs (record-traces #(rf/dispatch-sync [:rf2-lf84g/use-top-level]))
          [handled] (events-of evs :rf.fx/handled)]
      (assert-always-on-coord :fx :rf2-lf84g/top-level-fx)
      ;; rf2-d2841 — GUARDED: top-level-vs-`:tags` is a trace-SHAPE claim, and
      ;; the `:tags` negative was class-4 vacuous under the gate (`handled` is
      ;; nil there, and `(contains? nil k)` is false for every k).
      (when rf.interop/debug-enabled?
        (is (contains? handled :rf.trace/trigger-handler)
            ":rf.trace/trigger-handler lives at top level")
        (is (not (contains? (:tags handled) :rf.trace/trigger-handler))
            ":rf.trace/trigger-handler does NOT live under :tags")))))

(deftest fx-handled-trigger-matches-registrar-coord
  (testing "the :source-coord under :rf.trace/trigger-handler on
   :rf.fx/handled equals what the fx registrar holds"
    (rf/reg-fx :rf2-lf84g/coord-fx (fn [_ _] :ok))
    (rf/reg-event :rf2-lf84g/use-coord
                     (fn [_ _] {:fx [[:rf2-lf84g/coord-fx {}]]}))
    (let [fx-meta (rf/handler-meta :fx :rf2-lf84g/coord-fx)
          evs     (record-traces #(rf/dispatch-sync [:rf2-lf84g/use-coord]))
          [hdl]   (events-of evs :rf.fx/handled)
          coord   (-> hdl :rf.trace/trigger-handler :source-coord)
          errc    (assert-always-on-coord :fx :rf2-lf84g/coord-fx)]
      ;; rf2-d2841 — GUARDED, and this arm was the file's worst false green:
      ;; under the gate `fx-meta` has been stripped of coord keys AND `coord`
      ;; is nil, so all four comparisons were `nil = nil` and the whole deftest
      ;; reported PASS on nothing. The always-on registry is where the coord
      ;; still exists in production, so the parity claim is made there too.
      (when rf.interop/debug-enabled?
        (is (= (:ns   fx-meta) (:ns errc)))
        (is (= (:file fx-meta) (:file errc)))
        (is (= (:line fx-meta) (:line errc)))
        (is (= (:ns     fx-meta) (:ns coord)))
        (is (= (:file   fx-meta) (:file coord)))
        (is (= (:line   fx-meta) (:line coord)))
        (is (= (:column fx-meta) (:column coord)))))))

;; ---- the reserved-fx-id path stamps the enclosing event handler -----------

(deftest dispatch-fx-success-trigger-is-event-handler
  (testing "the reserved fx-id `:dispatch` has no registration of its
   own — the trigger-handler is the enclosing event handler. Reserved
   fx-id success traces stamp the outermost in-scope handler."
    (rf/reg-event :rf2-lf84g/parent
                     (fn [_ _] {:fx [[:dispatch [:rf2-lf84g/child]]]}))
    (rf/reg-event :rf2-lf84g/child (fn [{:keys [db]} _] {:db (assoc db :child? true)}))
    (let [evs       (record-traces #(rf/dispatch-sync [:rf2-lf84g/parent]))
          handled   (events-of evs :rf.fx/handled)
          parent-fx (first (filter #(= :dispatch (get-in % [:tags :rf.fx/id])) handled))]
      ;; ALWAYS-ON (rf2-d2841): the reserved `:dispatch` fx has no registration
      ;; of its own, and the coord the trace attributes to it is the ENCLOSING
      ;; event's — which the always-on registry holds. The child commit proves
      ;; the reserved-fx path actually ran in this posture.
      (assert-always-on-coord :event :rf2-lf84g/parent)
      (is (true? (:child? (rf/app-db-value :rf/default)))
          "the reserved :dispatch fx delivered the child event")
      (when rf.interop/debug-enabled?
        (is (some? parent-fx) ":dispatch :rf.fx/handled fired")
        ;; The reserved-fx-id path runs inside the parent event's
        ;; *current-trigger-handler* binding (no inner fx binding kicks in
        ;; for reserved fx-ids since there's no registered handler).
        (assert-trigger-shape parent-fx :event :rf2-lf84g/parent)))))

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
    (let [reg-ev (requiring-resolve 're-frame.events/reg-event)]
      (reg-ev :rf2-lf84g/uses-prog-fx
              (fn [_ _] {:fx [[:rf2-lf84g/programmatic-fx {}]]})))
    ;; A macro-path sibling in the same test, so the negative below has a
    ;; control that discriminates rather than an empty registry.
    (rf/reg-fx :rf2-lf84g/macro-fx (fn [_ _] :ok))
    (let [evs       (record-traces #(rf/dispatch-sync [:rf2-lf84g/uses-prog-fx]))
          [handled] (events-of evs :rf.fx/handled)]
      ;; ALWAYS-ON (rf2-d2841): "better no-data than poison-data" is a
      ;; PRODUCTION claim — the always-on registry is the sink that reaches an
      ;; off-box shipper, and a programmatic registration must leave it empty.
      (assert-no-always-on-coord :fx :rf2-lf84g/programmatic-fx
                                 :fx :rf2-lf84g/macro-fx)
      ;; rf2-d2841 — class-4 vacuous under the gate.
      (when rf.interop/debug-enabled?
        (is (some? handled) ":rf.fx/handled fired")
        (is (not (contains? handled :rf.trace/trigger-handler))
            "programmatic fx-registration → no coord → field omitted")))))

;; ---- the field rides on every event in the cascade -----------------------

(deftest event-db-changed-and-do-fx-carry-event-handler-trigger
  (testing "`:rf.event/db-changed` and `:rf.fx/do-fx` fire inside the event
   handler's *current-trigger-handler* binding — they carry the event
   handler's registration coord under :rf.trace/trigger-handler"
    (rf/reg-event :rf2-lf84g/changes-db
                     (fn [_ _] {:db {:n 1} :fx []}))
    (let [evs   (record-traces #(rf/dispatch-sync [:rf2-lf84g/changes-db]))
          [dbc] (events-of evs :rf.event/db-changed)
          [dof] (events-of evs :rf.fx/do-fx)]
      ;; ALWAYS-ON (rf2-d2841): the db change these traces report happened, and
      ;; the event's registration coord is on the always-on registry.
      (is (= 1 (:n (rf/app-db-value :rf/default)))
          "the db change the guarded traces report actually committed")
      (assert-always-on-coord :event :rf2-lf84g/changes-db)
      (when rf.interop/debug-enabled?
        (is (some? dbc) ":rf.event/db-changed fired")
        (is (some? dof) ":rf.fx/do-fx fired")
        (assert-trigger-shape dbc :event :rf2-lf84g/changes-db)
        (assert-trigger-shape dof :event :rf2-lf84g/changes-db)))))

;; ---- out-of-band emits omit trigger-handler -------------------------------

(deftest registration-traces-omit-trigger-handler
  (testing "trace events emitted OUTSIDE any handler's scope (registration
   time, frame creation) carry no :rf.trace/trigger-handler"
    (let [seen (atom [])]
      (rf/register-listener! :trace ::rec (fn [ev] (swap! seen conj ev)))
      (try
        (rf/reg-event :rf2-lf84g/reg-time-event (fn [{:keys [db]} _] {:db db}))
        ;; ALWAYS-ON (rf2-d2841): registration itself is production behaviour —
        ;; only the registration TRACE is dev-only. The handler is resolvable
        ;; and its coord is on the always-on registry in both postures.
        (is (some? (rf/handler-meta :event :rf2-lf84g/reg-time-event))
            "the registration succeeded in this posture")
        (assert-always-on-coord :event :rf2-lf84g/reg-time-event)
        (when rf.interop/debug-enabled?
          (let [reg-traces (filter #(= :rf.registry/handler-registered (:operation %))
                                   @seen)]
            (is (seq reg-traces) "we saw at least one registration trace")
            (doseq [ev reg-traces]
              (is (not (contains? ev :rf.trace/trigger-handler))
                  (str "out-of-band " (:operation ev)
                       " must omit :rf.trace/trigger-handler")))))
        (finally
          (rf/unregister-listener! :trace ::rec))))))

;; ---- :rf.sub/run carries the sub's registration coord (rf2-npm2p) ------------
;;
;; Spec 009 §:rf.trace/trigger-handler table — "Inside a sub recompute (body
;; fn): the sub's coord". The `:rf.sub/run` success-trace emits inside the
;; sub's recompute scope, so it carries the sub's own registration coord
;; (not the enclosing event handler's, even when the recompute fires
;; inside a dispatch's drain). Xray's event-detail panel + re-frame2-pair's
;; jump-to-source UX render click-to-jump links from this slot on every
;; trace in a cascade, including sub recomputes.

(deftest sub-run-carries-sub-trigger
  (testing ":rf.sub/run rides the sub's own registration coord — Xray /
   re-frame2-pair want jump-to-source to land on the reg-sub site of the sub
   that recomputed, not the upstream event handler whose db change
   caused the recompute"
    (rf/reg-sub :rf2-npm2p/n
                (fn [db _] (:n db)))
    (let [seen-value (atom ::unset)
          evs (record-traces
                (fn [] (reset! seen-value (deref (rf/subscribe [:rf2-npm2p/n])))))
          [run] (events-of evs :rf.sub/run)]
      ;; ALWAYS-ON (rf2-d2841): the recompute the trace reports really happened
      ;; (the sub yielded a value), and the sub's registration coord is on the
      ;; always-on registry — resolved there under `[:sub …]`.
      (is (not= ::unset @seen-value) "the sub recomputed and yielded a value")
      (assert-always-on-coord :sub :rf2-npm2p/n)
      (when rf.interop/debug-enabled?
        (is (some? run) ":rf.sub/run trace fired on recompute")
        (assert-trigger-shape run :sub :rf2-npm2p/n)))))

(deftest sub-run-trigger-rides-at-top-level
  (testing ":rf.trace/trigger-handler on :rf.sub/run is a top-level field,
   NOT nested under :tags — mirrors the error / fx-handled / machine-
   transition shapes"
    (rf/reg-sub :rf2-npm2p/top-level
                (fn [db _] db))
    (let [evs   (record-traces
                  (fn [] (deref (rf/subscribe [:rf2-npm2p/top-level]))))
          [run] (events-of evs :rf.sub/run)]
      (assert-always-on-coord :sub :rf2-npm2p/top-level)
      ;; rf2-d2841 — GUARDED trace-shape claim; the `:tags` negative was
      ;; class-4 vacuous under the gate.
      (when rf.interop/debug-enabled?
        (is (some? run))
        (is (contains? run :rf.trace/trigger-handler)
            ":rf.trace/trigger-handler lives at top level")
        (is (not (contains? (:tags run) :rf.trace/trigger-handler))
            ":rf.trace/trigger-handler does NOT live under :tags")))))

(deftest sub-run-trigger-matches-registrar-coord
  (testing "the :source-coord under :rf.trace/trigger-handler on :rf.sub/run
   equals what the registrar holds on the sub's slot — same comparison
   the other scope tests do (fx, machine, event)"
    (rf/reg-sub :rf2-npm2p/coord
                (fn [db _] db))
    (let [sub-meta (rf/handler-meta :sub :rf2-npm2p/coord)
          evs      (record-traces
                     (fn [] (deref (rf/subscribe [:rf2-npm2p/coord]))))
          [run]    (events-of evs :rf.sub/run)
          coord    (-> run :rf.trace/trigger-handler :source-coord)
          errc     (assert-always-on-coord :sub :rf2-npm2p/coord)]
      ;; rf2-d2841 — GUARDED. Four `nil = nil` comparisons under the gate
      ;; (stripped `handler-meta` vs an absent trace); the surviving parity
      ;; claim is against the always-on registry above.
      (when rf.interop/debug-enabled?
        (is (some? run))
        (is (= (:ns   sub-meta) (:ns errc)))
        (is (= (:file sub-meta) (:file errc)))
        (is (= (:line sub-meta) (:line errc)))
        (is (= (:ns     sub-meta) (:ns coord)))
        (is (= (:file   sub-meta) (:file coord)))
        (is (= (:line   sub-meta) (:line coord)))
        (is (= (:column sub-meta) (:column coord)))))))

(deftest sub-run-trigger-is-sub-not-enclosing-event
  (testing "when a sub fires during a dispatch (the event handler's
   db change is observed by a subsequent deref), :rf.sub/run still carries
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
    ;; handler-binding for the `:rf.sub/run` emit? Per Spec 009 §:rf.trace
    ;; /trigger-handler table, yes (the inner scope wins).
    (rf/reg-event :rf2-npm2p/changes-n
                     (fn [{:keys [db]} _]
                       {:db (let [new-db (assoc db :n 1)]
                         ;; Touch the sub from inside the event body
                         ;; so the recompute fires while the event
                         ;; handler's binding is in scope.
                         @(rf/subscribe [:rf2-npm2p/from-cascade])
                         new-db)}))
    (let [evs   (record-traces
                  (fn [] (rf/dispatch-sync [:rf2-npm2p/changes-n])))
          [run] (events-of evs :rf.sub/run)]
      ;; ALWAYS-ON (rf2-d2841): both coords exist and are DISTINCT in the
      ;; always-on registry — which is what makes "the inner scope wins" a
      ;; claim with teeth rather than a coincidence of two equal values.
      (let [sub-c (assert-always-on-coord :sub   :rf2-npm2p/from-cascade)
            ev-c  (assert-always-on-coord :event :rf2-npm2p/changes-n)]
        (is (not= sub-c ev-c)
            "the sub and the enclosing event have different coords"))
      (is (= 1 (:n (rf/app-db-value :rf/default)))
          "the cascade committed, so the recompute really fired inside it")
      (when rf.interop/debug-enabled?
        (is (some? run) ":rf.sub/run fired inside the cascade")
        ;; The KIND under trigger-handler is :sub, not :event. Even
        ;; though the deref happens INSIDE the event handler's drain,
        ;; the sub-recompute body rebinds the trigger-handler. Same
        ;; shape as fx-handled — the inner binding wins over the outer.
        (assert-trigger-shape run :sub :rf2-npm2p/from-cascade)))))

(deftest programmatic-sub-omits-trigger-on-run
  (testing "a sub registered without the macro path (no source-coord
   stamp on the registrar slot) emits :rf.sub/run with no
   :rf.trace/trigger-handler field — better no-data than poison-data
   (mirrors the fx-handled programmatic path)"
    (let [reg-fn (requiring-resolve 're-frame.subs/reg-sub)]
      (reg-fn :rf2-npm2p/programmatic
              (fn [db _] db)))
    (rf/reg-sub :rf2-npm2p/macro-sub (fn [db _] db))
    (let [evs   (record-traces
                  (fn [] (deref (rf/subscribe [:rf2-npm2p/programmatic]))))
          [run] (events-of evs :rf.sub/run)]
      ;; ALWAYS-ON (rf2-d2841), with a macro-path control so the negative
      ;; cannot pass merely because the registry is empty.
      (assert-no-always-on-coord :sub :rf2-npm2p/programmatic
                                 :sub :rf2-npm2p/macro-sub)
      (when rf.interop/debug-enabled?
        (is (some? run) ":rf.sub/run fired")
        (is (not (contains? run :rf.trace/trigger-handler))
            "programmatic sub-registration → no coord → field omitted")))))

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
;; own (`cofx.cljc` only emits `:rf.error/unregistered-cofx` on the miss
;; path). To exercise the success-path binding contract, the test
;; registers a cofx whose body itself calls `rf.trace/emit!` — exactly the
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
                 (fn []
                   ;; Emit a custom trace from inside the cofx body —
                   ;; this is exactly what an instrumented cofx
                   ;; (http issuance, persistence read, etc.) does to
                   ;; surface its work into the trace stream. The
                   ;; handler-scope established around the supplier run
                   ;; MUST cause this emit to carry the cofx's coord (not
                   ;; the enclosing event handler's).
                   (rf.trace/emit! :rf2-npm2p/probe :rf2-npm2p/probe {:from :cofx})
                   :ok))
    (rf/reg-event :rf2-npm2p/uses-cofx
                     {:rf.cofx/requires [:rf2-npm2p/instrumented-cofx]}
                     (fn [_cofx _event] {}))
    (let [evs     (record-traces
                    (fn [] (rf/dispatch-sync [:rf2-npm2p/uses-cofx])))
          [probe] (events-of evs :rf2-npm2p/probe)]
      ;; ALWAYS-ON (rf2-d2841): the cofx's registration coord survives on the
      ;; always-on registry; the custom trace it emits does not.
      (assert-always-on-coord :cofx :rf2-npm2p/instrumented-cofx)
      (when rf.interop/debug-enabled?
        (is (some? probe) "custom trace fired from inside the cofx body")
        (assert-trigger-shape probe :cofx :rf2-npm2p/instrumented-cofx)))))

(deftest cofx-body-trigger-rides-at-top-level
  (testing ":rf.trace/trigger-handler on a cofx-body trace is a
   top-level field, NOT nested under :tags"
    (rf/reg-cofx :rf2-npm2p/top-level-cofx
                 (fn []
                   (rf.trace/emit! :rf2-npm2p/probe :rf2-npm2p/probe {})
                   :ok))
    (rf/reg-event :rf2-npm2p/use-top-level-cofx
                     {:rf.cofx/requires [:rf2-npm2p/top-level-cofx]}
                     (fn [_ _] {}))
    (let [evs     (record-traces
                    (fn [] (rf/dispatch-sync [:rf2-npm2p/use-top-level-cofx])))
          [probe] (events-of evs :rf2-npm2p/probe)]
      (assert-always-on-coord :cofx :rf2-npm2p/top-level-cofx)
      ;; rf2-d2841 — GUARDED trace-shape claim; class-4 vacuous `:tags`
      ;; negative under the gate.
      (when rf.interop/debug-enabled?
        (is (some? probe))
        (is (contains? probe :rf.trace/trigger-handler)
            ":rf.trace/trigger-handler lives at top level")
        (is (not (contains? (:tags probe) :rf.trace/trigger-handler))
            ":rf.trace/trigger-handler does NOT live under :tags")))))

(deftest cofx-body-trigger-matches-registrar-coord
  (testing "the :source-coord under :rf.trace/trigger-handler on a
   cofx-body trace equals what the registrar holds on the cofx's slot"
    (rf/reg-cofx :rf2-npm2p/coord-cofx
                 (fn []
                   (rf.trace/emit! :rf2-npm2p/probe :rf2-npm2p/probe {})
                   :ok))
    (rf/reg-event :rf2-npm2p/use-coord-cofx
                     {:rf.cofx/requires [:rf2-npm2p/coord-cofx]}
                     (fn [_ _] {}))
    (let [cofx-meta (rf/handler-meta :cofx :rf2-npm2p/coord-cofx)
          evs       (record-traces
                      (fn [] (rf/dispatch-sync [:rf2-npm2p/use-coord-cofx])))
          [probe]   (events-of evs :rf2-npm2p/probe)
          coord     (-> probe :rf.trace/trigger-handler :source-coord)
          errc      (assert-always-on-coord :cofx :rf2-npm2p/coord-cofx)]
      ;; rf2-d2841 — GUARDED. Four `nil = nil` comparisons under the gate; the
      ;; parity claim that survives is against the always-on registry above.
      (when rf.interop/debug-enabled?
        (is (some? probe))
        (is (= (:ns   cofx-meta) (:ns errc)))
        (is (= (:file cofx-meta) (:file errc)))
        (is (= (:line cofx-meta) (:line errc)))
        (is (= (:ns     cofx-meta) (:ns coord)))
        (is (= (:file   cofx-meta) (:file coord)))
        (is (= (:line   cofx-meta) (:line coord)))
        (is (= (:column cofx-meta) (:column coord)))))))

(deftest programmatic-cofx-omits-trigger-on-body-trace
  (testing "a cofx registered without the macro path (no source-coord
   stamp on the registrar slot) — traces emitted from inside its body
   carry no :rf.trace/trigger-handler field. Better no-data than
   poison-data, mirroring the fx + sub programmatic paths."
    (let [reg-fn (requiring-resolve 're-frame.cofx/reg-cofx)]
      (reg-fn :rf2-npm2p/prog-cofx
              (fn []
                (rf.trace/emit! :rf2-npm2p/probe :rf2-npm2p/probe {})
                :ok)))
    (rf/reg-event :rf2-npm2p/use-prog-cofx
                     {:rf.cofx/requires [:rf2-npm2p/prog-cofx]}
                     (fn [_ _] {}))
    (rf/reg-cofx :rf2-npm2p/macro-cofx (fn [] :ok))
    (let [evs     (record-traces
                    (fn [] (rf/dispatch-sync [:rf2-npm2p/use-prog-cofx])))
          [probe] (events-of evs :rf2-npm2p/probe)]
      ;; ALWAYS-ON (rf2-d2841), with a macro-path control.
      (assert-no-always-on-coord :cofx :rf2-npm2p/prog-cofx
                                 :cofx :rf2-npm2p/macro-cofx)
      (when rf.interop/debug-enabled?
        (is (some? probe))
        (is (not (contains? probe :rf.trace/trigger-handler))
            "programmatic cofx-registration → no coord → field omitted")))))
