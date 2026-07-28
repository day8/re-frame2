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
  registrar's stamp is carried onto the emitted error event.

  ## Posture split (rf2-d2841)

  Q4 above says `:rf.trace/trigger-handler` is \"NOT elided in production\", and
  that is true of the SUBSTANCE but not of the SLOT this file reads. The slot
  rides a TRACE event, and under `-Dre-frame.debug=false` no trace event is
  emitted at all, so every deftest here failed under
  `scripts/test-core-prod-gate.sh`. The substance — \"which registered
  component produced this error, and where was it registered\" — survives on a
  different channel: `error-emit/dispatch-on-error!` resolves
  `source-coords/error-coords-for` against the always-on `error-coords-by-id`
  registry and stamps `:source-coord` onto the tight record every
  `:errors`-stream listener receives. That is the channel a production error
  shipper actually reads, and nothing here had ever asserted it.

  So each case grew an ALWAYS-ON witness on the `:errors` stream beside its
  guarded trace assertions. The witness is not a restatement: the two channels
  attribute DIFFERENTLY, and the difference is now pinned. `:rf.error/
  fx-handler-exception`'s trace names the FX as the trigger handler, while the
  always-on record resolves its `:source-coord` from `:event-id` — see
  `fx-handler-exception-carries-trigger-handler` for which id that turns out
  to be.

  THREE VACUOUS PASSES CAME OFF THIS FILE (rf2-d2841 class 4 — absence of a
  key the gate elides wholesale): `trigger-handler-rides-at-top-level`'s
  `(not (contains? (:tags exc) …))`, `no-such-handler-omits-trigger-handler`'s
  `(not (contains? miss …))` and `programmatic-registration-omits-trigger-
  handler`'s ditto — all three read `contains?` off the nil the empty trace
  ring yields, and `(contains? nil k)` is false for every k. A FOURTH shape,
  class 3, sat in `source-coord-matches-registration-site`: it compares
  `(:ns reg-meta)` against `(:ns coord)` field by field, and under the gate
  BOTH sides are nil — four `nil = nil` comparisons certifying parity between
  two absences."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.interop :as interop]
            [re-frame.source-coords :as sc]
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
  (flows/reset-flows!)
  (schemas/clear-schemas-by-frame!)
  (trace/clear-listeners!)
  (rf/init! plain-atom/adapter)
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

(defn- errors-of
  "Filter captured traces to those whose `:operation` matches the supplied
  operation keyword."
  [evs op]
  (filterv #(and (= :error (:op-type %))
                 (= op     (:operation %)))
           evs))

(defn- record-both
  "ALWAYS-ON (rf2-d2841) capture: run `body-fn` with BOTH a
  dev-trace listener and an always-on `:errors`-stream listener attached, and
  return `{:traces [...] :errors [...]}`. The `:errors` half is the corpus-wide
  `error-emit` registry — not gated by `interop/debug-enabled?` — so it fills
  in both postures."
  [body-fn]
  (let [traces (atom [])
        errors (atom [])]
    (rf/register-listener! :trace  ::rec (fn [ev]  (swap! traces conj ev)))
    (rf/register-listener! :errors ::err (fn [rec] (swap! errors conj rec)))
    (try (body-fn)
         (finally
           (rf/unregister-listener! :trace  ::rec)
           (rf/unregister-listener! :errors ::err)))
    {:traces @traces :errors @errors}))

(defn- error-of
  "The first always-on error record whose `:error` is `kw`."
  [recs kw]
  (first (filterv #(= kw (:error %)) recs)))

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
    (rf/reg-event :rf2-3nn8/throws
                     (fn [_cofx _event]
                       (throw (ex-info "boom" {}))))
    (let [{:keys [traces errors]} (record-both
                                    (fn []
                                      (rf/dispatch-sync [:rf2-3nn8/throws])))
          [exc] (errors-of traces :rf.error/handler-exception)
          rec   (error-of errors :rf.error/handler-exception)]
      ;; ALWAYS-ON (rf2-d2841): the failure reaches the production error
      ;; stream, carrying the registration coord under `:source-coord`.
      (is (some? rec) "the always-on error record fired")
      (is (map? (:source-coord rec))
          "the always-on record carries the registration :source-coord")
      (is (= (sc/error-coords-for :event :rf2-3nn8/throws) (:source-coord rec))
          "…and it is exactly what the always-on coord registry holds")
      ;; rf2-d2841 — the trace SLOT and its placement are dev-only. Under
      ;; `-Dre-frame.debug=false` `exc` is nil, which made the `:tags`
      ;; negative below a class-4 vacuous pass: `(contains? nil k)` is false
      ;; for every k.
      (when interop/debug-enabled?
        (is (some? exc) "handler-exception trace fired")
        (is (contains? exc :rf.trace/trigger-handler)
            ":rf.trace/trigger-handler lives at the top level of the event")
        (is (not (contains? (:tags exc) :rf.trace/trigger-handler))
            ":rf.trace/trigger-handler does NOT live under :tags")))))

;; ---- Q2 — present when handler in scope -----------------------------------

(deftest event-handler-exception-carries-trigger-handler
  (testing ":rf.error/handler-exception carries the event handler's coord"
    (rf/reg-event :rf2-3nn8/throwing-event
                     (fn [_cofx _event]
                       (throw (ex-info "boom" {}))))
    (let [{:keys [traces errors]} (record-both
                                    #(rf/dispatch-sync [:rf2-3nn8/throwing-event]))
          [exc] (errors-of traces :rf.error/handler-exception)
          rec   (error-of errors :rf.error/handler-exception)]
      ;; ALWAYS-ON (rf2-d2841): same attribution on the production channel —
      ;; the record names the failing event and resolves its coord.
      (is (= :rf2-3nn8/throwing-event (:event-id rec))
          "the always-on record names the failing event")
      (is (= (sc/error-coords-for :event :rf2-3nn8/throwing-event)
             (:source-coord rec))
          "the always-on record carries the event's registration coord")
      (when interop/debug-enabled?
        (assert-trigger-shape exc :event :rf2-3nn8/throwing-event)))))

(deftest fx-handler-exception-carries-trigger-handler
  (testing ":rf.error/fx-handler-exception names the fx as the trigger handler,
   not the enclosing event (the fx body is what threw)"
    (rf/reg-fx :rf2-3nn8/throwing-fx
               (fn [_ctx _args] (throw (ex-info "fx boom" {}))))
    (rf/reg-event :rf2-3nn8/use-throwing-fx
                     (fn [_cofx _event]
                       {:fx [[:rf2-3nn8/throwing-fx {}]]}))
    (let [{:keys [traces errors]} (record-both
                                    #(rf/dispatch-sync [:rf2-3nn8/use-throwing-fx]))
          [exc] (errors-of traces :rf.error/fx-handler-exception)
          rec   (error-of errors :rf.error/fx-handler-exception)]
      ;; ALWAYS-ON (rf2-d2841), AND THE TWO CHANNELS DISAGREE ON PURPOSE.
      ;; The trace names the FX as the trigger handler (the fx body threw);
      ;; the always-on record's `:source-coord` is resolved by
      ;; `error-emit/error-source-coord` from `:event-id` under `[:event …]`,
      ;; so it names the DISPATCHED EVENT. The fx id reaches production on
      ;; `:failing-id` — the Spec 009 §Component-attribution lift — not on the
      ;; coord. Pinning both keeps a future "just read the record" refactor
      ;; from quietly losing the fx attribution.
      (is (some? rec) "the always-on fx-handler-exception record fired")
      (is (= :rf2-3nn8/use-throwing-fx (:event-id rec))
          "the always-on record's :event-id is the dispatched event")
      (is (= :rf2-3nn8/throwing-fx (:failing-id rec))
          "the failing FX id is lifted onto the always-on record")
      (is (= (sc/error-coords-for :event :rf2-3nn8/use-throwing-fx)
             (:source-coord rec))
          "the always-on :source-coord resolves under [:event event-id]")
      (when interop/debug-enabled?
        (assert-trigger-shape exc :fx :rf2-3nn8/throwing-fx)))))

(deftest sub-exception-carries-trigger-handler
  (testing ":rf.error/sub-exception names the failing sub"
    (rf/reg-sub :rf2-3nn8/throwing-sub
                (fn [_db _q] (throw (ex-info "sub boom" {}))))
    (let [{:keys [traces errors]} (record-both
                                    #(deref (rf/subscribe [:rf2-3nn8/throwing-sub])))
          [exc] (errors-of traces :rf.error/sub-exception)
          rec   (error-of errors :rf.error/sub-exception)]
      ;; ALWAYS-ON (rf2-d2841): `:rf.error/sub-exception` is one of
      ;; `error-emit`'s `sub-error-categories`, so its coord resolves under
      ;; `[:sub …]` — the realm-aware lookup rf2-xgkgx installed. That is the
      ;; production-posture statement of "the record names the FAILING SUB".
      (is (some? rec) "the always-on sub-exception record fired")
      (is (= :rf2-3nn8/throwing-sub (:event-id rec))
          "the always-on record's id slot carries the SUB id")
      (is (= (sc/error-coords-for :sub :rf2-3nn8/throwing-sub)
             (:source-coord rec))
          "the always-on :source-coord resolves under [:sub sub-id]")
      (when interop/debug-enabled?
        (assert-trigger-shape exc :sub :rf2-3nn8/throwing-sub)))))

;; The former `no-such-cofx-carries-enclosing-event-trigger-handler` deftest is
;; retired with `inject-cofx` (EP-0017 slice A.3) — `:rf.error/no-such-cofx` no
;; longer fires. Its successor `:rf.error/unregistered-cofx` (a declared typo'd
;; id) is registration / context-assembly-time, outside the in-chain
;; trigger-handler scope this file pins; its coverage lives in
;; `re-frame.cofx-test`.

(deftest no-such-fx-carries-enclosing-event-trigger-handler
  (testing ":rf.error/no-such-fx fires from the fx walker while the event
   handler scope is still bound — the enclosing event's coord is carried"
    (rf/reg-event :rf2-3nn8/uses-missing-fx
                     (fn [_cofx _event]
                       {:fx [[:rf2-3nn8/no-such-fx {}]]}))
    (let [{:keys [traces errors]} (record-both
                                    #(rf/dispatch-sync [:rf2-3nn8/uses-missing-fx]))
          [miss] (errors-of traces :rf.error/no-such-fx)
          rec    (error-of errors :rf.error/no-such-fx)]
      ;; ALWAYS-ON (rf2-d2841): `:rf.error/no-such-fx` is a PROMOTED category,
      ;; so the enclosing event's coord reaches production on the record.
      (is (some? rec) "the always-on no-such-fx record fired")
      (is (= (sc/error-coords-for :event :rf2-3nn8/uses-missing-fx)
             (:source-coord rec))
          "the always-on record carries the enclosing event's coord")
      (when interop/debug-enabled?
        (assert-trigger-shape miss :event :rf2-3nn8/uses-missing-fx)))))

;; ---- Q2 — negative — absent when no handler is in scope -------------------

(deftest no-such-handler-omits-trigger-handler
  (testing ":rf.error/no-such-handler fires at outermost dispatch with no
   handler in scope; :rf.trace/trigger-handler is absent"
    (let [{:keys [traces errors]} (record-both
                                    #(rf/dispatch-sync [:rf2-3nn8/no-such-event]))
          [miss] (errors-of traces :rf.error/no-such-handler)
          rec    (error-of errors :rf.error/no-such-handler)]
      ;; ALWAYS-ON (rf2-d2841): the production analogue of "no handler was in
      ;; scope" is that the record carries NO `:source-coord` — there is no
      ;; registration to point at. Non-vacuous because the sibling deftests
      ;; above assert the slot IS present for a registered handler, on the
      ;; same channel in the same posture.
      (is (some? rec) "the always-on no-such-handler record fired")
      (is (not (contains? rec :source-coord))
          "no registration in scope → the always-on record omits :source-coord")
      ;; rf2-d2841 — dev-only trace slot. Under the gate `miss` is nil, which
      ;; made the negative below a class-4 vacuous pass.
      (when interop/debug-enabled?
        (is (some? miss) "no-such-handler trace fired")
        (is (not (contains? miss :rf.trace/trigger-handler))
            ":rf.trace/trigger-handler is absent when no handler is in scope")))))

;; ---- Q3 — registration-site coord, not call-site --------------------------

(deftest source-coord-matches-registration-site
  (testing "the :source-coord under :rf.trace/trigger-handler equals the
   value the registrar holds on the handler's slot"
    (rf/reg-event :rf2-3nn8/registration-site
                     (fn [_cofx _event]
                       (throw (ex-info "boom" {}))))
    (let [reg-meta (rf/handler-meta :event :rf2-3nn8/registration-site)
          {:keys [traces errors]} (record-both
                                    #(rf/dispatch-sync [:rf2-3nn8/registration-site]))
          [exc]    (errors-of traces :rf.error/handler-exception)
          coord    (-> exc :rf.trace/trigger-handler :source-coord)
          rec      (error-of errors :rf.error/handler-exception)
          errc     (sc/error-coords-for :event :rf2-3nn8/registration-site)]
      ;; ALWAYS-ON (rf2-d2841): the same "the emitted coord IS the registration
      ;; site" claim, made against the always-on registry — which is the
      ;; registration-site record of truth in production, `handler-meta`
      ;; having been stripped of coord keys there.
      (is (map? errc) "the always-on registry holds the registration coord")
      (is (= errc (:source-coord rec))
          "the always-on record's :source-coord IS the registration coord")
      (is (symbol? (:ns errc)))
      (is (string? (:file errc)))
      (is (integer? (:line errc)))
      ;; rf2-d2841 — GUARDED, and this arm was a class-3 vacuous pass: under
      ;; the gate `reg-meta` carries no coord keys AND `coord` is nil, so all
      ;; four comparisons were `nil = nil` — parity certified between two
      ;; absences.
      ;;
      ;; The registrar stamps :ns / :file / :line / :column flat on the
      ;; meta map; the trigger-handler value picks them up. Compare
      ;; field-by-field rather than via equality so a future addition
      ;; to the registrar slot doesn't break the test.
      (when interop/debug-enabled?
        (is (= (:ns     reg-meta) (:ns coord)))
        (is (= (:file   reg-meta) (:file coord)))
        (is (= (:line   reg-meta) (:line coord)))
        (is (= (:column reg-meta) (:column coord)))))))

;; ---- programmatic registration → no coord -> no trigger-handler -----------

(deftest programmatic-registration-omits-trigger-handler
  (testing "an event handler registered without the macro (bypassing
   source-coord capture) emits errors with no :rf.trace/trigger-handler
   field — better no-data than poison-data"
    (let [reg-fn (requiring-resolve 're-frame.events/reg-event)]
      (reg-fn :rf2-3nn8/no-coords
              (fn [_cofx _event] (throw (ex-info "boom" {})))))
    (let [{:keys [traces errors]} (record-both
                                    #(rf/dispatch-sync [:rf2-3nn8/no-coords]))
          [exc] (errors-of traces :rf.error/handler-exception)
          rec   (error-of errors :rf.error/handler-exception)]
      ;; ALWAYS-ON (rf2-d2841): "better no-data than poison-data" is a
      ;; PRODUCTION claim — a programmatic registration must leave the
      ;; always-on registry empty, so the shipped record omits `:source-coord`
      ;; rather than pointing an operator at someone else's line.
      (is (some? rec) "the always-on record still fired")
      (is (nil? (sc/error-coords-for :event :rf2-3nn8/no-coords))
          "programmatic registration stored no always-on coords")
      (is (not (contains? rec :source-coord))
          "…so the production record omits the :source-coord slot")
      ;; rf2-d2841 — dev-only trace slot; class-4 vacuous under the gate.
      (when interop/debug-enabled?
        (is (some? exc))
        (is (not (contains? exc :rf.trace/trigger-handler))
            "programmatic registration → no coord → field omitted")))))
