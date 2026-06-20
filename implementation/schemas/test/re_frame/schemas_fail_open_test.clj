(ns re-frame.schemas-fail-open-test
  "JVM tests for the validator-throw / explainer-throw fail-OPEN closure on
  the three meta-bearing validate-*! surfaces and the production boundary
  seam (rf2-a5kzs findings 2 + 3).

  ## Finding 2 — malformed-schema fail-open

  `run-validation` (the shared core of validate-event! /
  validate-fx! / validate-sub!) used to invoke the registered validator
  DIRECTLY. Malli validates schema FORMS lazily — a childless `[:vector]`
  / unknown op registers fine, then makes the validator THROW on the first
  validate call. That throw propagated out of validate-*! and the runtime
  call-sites (`router` / `fx` / `subs`) coerced it to a validation
  PASS via their defensive `(catch … true)`: the event handler ran, the
  fx handler ran, the sub returned its invalid value — all with no trace and
  no recovery. Same fail-open class already closed for app-db schemas
  (rf2-ss06u.3). (The cofx surface's malformed-schema fail-closed now rides
  the EP-0017 `:rf.error/cofx-value-invalid` recordable path; the
  injection-time `validate-cofx!` was retired per rf2-nkf4l3.)

  The fix isolates the throw inside `run-validation` (via
  `validate-entry-result`), emits a distinct `:rf.error/malformed-schema`
  trace, and returns `false` so each caller runs its normal recovery
  (skip handler / skip fx / replace sub return). The boundary seam
  `validate-with-registered-fn` isolates the same throw and returns `false`
  (fail CLOSED → skip the handler).

  ## Finding 3 — explainer-throw catch-as-pass

  After a validator returns false, `run-explainer` / `validate-app-schema!`
  called the registered explainer WITHOUT a try/catch. A throwing custom
  explainer aborted trace construction; the throw unwound PAST the
  legitimate `false` and the runtime caught it as a PASS (for app-db: the
  router's swallowed-backstop returned true / no rollback, so the invalid
  commit installed). The fix routes every explainer call through
  `safe-explain`: a throw degrades to a nil explanation and the `false`
  verdict is preserved."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.schemas :as schemas]
            [re-frame.schemas.malli]
            [re-frame.schemas.test-fixture :as tf]
            [re-frame.schemas.validator :as validator]))

(use-fixtures :each tf/reset-runtime)

(defn- capture
  "Run `body-fn` while collecting trace events; return the captured vector."
  [body-fn]
  (let [traces (atom [])
        cb-id  (keyword (gensym "capture"))]
    (rf/register-listener! :trace cb-id (fn [ev] (swap! traces conj ev)))
    (try (body-fn) (finally (rf/unregister-listener! :trace cb-id)))
    @traces))

(defn- malformed-traces [traces]
  (filter #(= :rf.error/malformed-schema (:operation %)) traces))

(defn- validation-failures [traces]
  (filter #(= :rf.error/schema-validation-failure (:operation %)) traces))

;; ===========================================================================
;; Finding 2 — malformed-schema fails CLOSED on every meta-bearing surface
;; ===========================================================================

(deftest event-malformed-schema-fails-closed
  (testing "rf2-a5kzs (finding 2) — a childless [:vector] :schema on a
            reg-event handler does NOT throw-as-pass: the handler is
            skipped and a :rf.error/malformed-schema trace fires."
    (let [calls (atom 0)]
      (rf/reg-event :ev/malformed
        {:schema [:vector]}                       ;; childless — Malli throws at validate-time
        (fn [{:keys [db]} _] (swap! calls inc) {:db db}))
      (let [traces (capture #(rf/dispatch-sync [:ev/malformed :anything]))]
        (is (= 0 @calls)
            "handler skipped — the malformed-schema throw no longer coerces to a pass")
        (let [mal (malformed-traces traces)]
          (is (= 1 (count mal)) "exactly one malformed-schema trace fired")
          (let [m (first mal)]
            (is (= :event (-> m :tags :where)))
            (is (= [:vector] (-> m :tags :schema)) ":schema carries the malformed form to fix")
            (is (string? (-> m :tags :reason)))
            ;; rf2-mxs7a — the malformed trace reports the surface's
            ;; recovery; the event handler is skipped (:no-recovery).
            ;; `:recovery` is hoisted to the top-level envelope by
            ;; trace/build-event (Spec 009 §Core fields hoist contract).
            (is (= :no-recovery (:recovery m)))
            ;; fail-closed: no value-bearing slot leaks into the malformed trace.
            (is (not (contains? (:tags m) :received)))
            (is (not (contains? (:tags m) :value)))))))))

(deftest event-unknown-op-schema-fails-closed
  (testing "rf2-a5kzs (finding 2) — an unknown-op :schema on a reg-event
            handler also fails closed with a distinct trace, no throw."
    (let [calls (atom 0)]
      (rf/reg-event :ev/unknown
        {:schema [:not-a-real-op :int]}
        (fn [{:keys [db]} _] (swap! calls inc) {:db db}))
      (let [traces (capture #(rf/dispatch-sync [:ev/unknown 1]))]
        (is (= 0 @calls) "handler skipped")
        (is (= 1 (count (malformed-traces traces))) "one malformed-schema trace fired")))))

(defn- cofx-value-invalid-traces [traces]
  (filter #(= :rf.error/cofx-value-invalid (:operation %)) traces))

;; EP-0017 (replaces the disabled rf2-oa2dun inject-cofx test): the LIVE cofx
;; schema path is the recordable-value check
;; (`re-frame.cofx/validate-recordable-value!`), which also FAILS CLOSED on a
;; malformed schema — the validator throw is caught as a plain `false`, so the
;; path emits `:rf.error/cofx-value-invalid` and THROWS rather than coercing
;; the throw to a pass. (Unlike the meta-bearing `run-validation` surfaces, the
;; recordable path does not raise the distinct `:rf.error/malformed-schema`
;; trace — it folds a malformed-schema throw into the same cofx-value-invalid
;; hard error, since either way an out-of-contract value must not reach the
;; durable ledger.)
(deftest recordable-cofx-malformed-schema-fails-closed
  (testing "EP-0017 / rf2-a5kzs (finding 2) — a malformed recordable-cofx
            :schema does NOT run the handler via throw-as-pass; the validator
            throw is caught as false, :rf.error/cofx-value-invalid fires, and
            the handler is skipped (the recordable-value throw pre-empts it)."
    (rf/reg-cofx :cf/malformed
      {:recordable? true :provided? true
       :schema [:vector]})                          ;; childless — Malli throws at validate-time
    (let [calls (atom 0)]
      (rf/reg-event :use/malformed-cofx
        {:rf.cofx/requires [:cf/malformed]}
        (fn [_ _] (swap! calls inc) {}))
      (let [traces (capture
                     #(try
                        (rf/dispatch-sync [:use/malformed-cofx]
                                          {:rf.cofx {:cf/malformed :whatever}})
                        (catch clojure.lang.ExceptionInfo _)))]
        (is (= 0 @calls)
            "handler skipped — recordable-cofx malformed-schema fails closed")
        (let [inv (cofx-value-invalid-traces traces)]
          (is (= 1 (count inv)) "exactly one cofx-value-invalid trace fired")
          (is (= :no-recovery (:recovery (first inv)))))))))

(deftest fx-malformed-schema-fails-closed-skipping-only-offender
  (testing "rf2-a5kzs (finding 2) — a malformed fx :schema skips ONLY the
            offending fx (recovery :skipped); the sibling fx still runs."
    (let [bad-calls  (atom 0)
          good-calls (atom 0)]
      (rf/reg-fx :fx/malformed
        {:schema [:vector]}
        (fn [_ _] (swap! bad-calls inc)))
      (rf/reg-fx :fx/sibling
        (fn [_ _] (swap! good-calls inc)))
      (rf/reg-event :emit/both
        (fn [_ _] {:fx [[:fx/malformed {:any :thing}]
                        [:fx/sibling   :ok]]}))
      (let [traces (capture #(rf/dispatch-sync [:emit/both]))]
        (is (= 0 @bad-calls) "the offending fx was skipped — not run via throw-as-pass")
        (is (= 1 @good-calls) "the sibling fx still ran")
        (let [mal (malformed-traces traces)]
          (is (= 1 (count mal)))
          (is (= :fx-args (-> (first mal) :tags :where)))
          ;; rf2-mxs7a — the malformed fx trace must report the ACTUAL
          ;; recovery the data plane took: only the offending fx is
          ;; skipped (:skipped), NOT :no-recovery. The trace previously
          ;; lied with :no-recovery even though the sibling fx ran.
          ;; `:recovery` hoists to the top-level envelope.
          (is (= :skipped (:recovery (first mal)))
              "malformed fx trace reports :skipped (sibling fx still ran)"))))))

(deftest sub-malformed-schema-fails-closed-replaced-with-default
  (testing "rf2-a5kzs (finding 2) — a malformed sub :schema yields the default
            (nil) per :replaced-with-default recovery instead of returning the
            unvalidated value via throw-as-pass; a malformed-schema trace fires."
    (rf/reg-event :sub/init (fn [{:keys [db]} _] {:db {:items [1 2 3]}}))
    (rf/reg-sub :sub/malformed
      {:schema [:vector]}                          ;; childless — throws at validate-time
      (fn [db _] (:items db)))
    (let [traces (capture
                   (fn []
                     (rf/dispatch-sync [:sub/init])
                     (is (nil? (rf/subscribe-once [:sub/malformed]))
                         "malformed sub schema → nil (replaced-with-default), not the raw value")))]
      (let [mal (malformed-traces traces)]
        (is (pos? (count mal)) "a malformed-schema trace fired")
        (is (= :sub-return (-> (first mal) :tags :where)))
        ;; rf2-mxs7a — the malformed sub trace must report
        ;; :replaced-with-default (the sub yielded nil), NOT the
        ;; previous unconditional :no-recovery lie. `:recovery` hoists
        ;; to the top-level envelope.
        (is (= :replaced-with-default (:recovery (first mal)))
            "malformed sub trace reports :replaced-with-default (yielded the default)")))))

(deftest boundary-seam-malformed-schema-returns-false
  (testing "rf2-a5kzs (finding 2, boundary seam) — validate-with-registered-fn
            isolates a malformed-schema throw and returns FALSE (fail closed)
            rather than propagating to the interceptor's (catch … true)."
    (is (false? (schemas/validate-with-registered-fn [:vector] [:anything]))
        "childless [:vector] → false (not a throw, not a pass)")
    (is (false? (schemas/validate-with-registered-fn [:not-a-real-op :int] 1))
        "unknown op → false")
    ;; A well-formed schema still returns a real verdict.
    (is (true?  (schemas/validate-with-registered-fn [:cat [:= :ok] :int] [:ok 1]))
        "well-formed conforming → true")
    (is (false? (schemas/validate-with-registered-fn [:cat [:= :ok] :int] [:ok "no"]))
        "well-formed non-conforming → false")))

(deftest direct-validate-event-malformed-schema-returns-false
  (testing "rf2-a5kzs (finding 2) — the direct validate-event! call returns
            false (not a throw) on a malformed schema and emits the trace."
    (let [traces (capture
                   (fn []
                     (is (false? (schemas/validate-event! :ev/x [:ev/x 1] {:schema [:vector]}))
                         "malformed schema → false, no throw")))]
      (is (= 1 (count (malformed-traces traces)))))))

;; ===========================================================================
;; Finding 3 — a throwing explainer must NOT become a catch-as-pass
;; ===========================================================================

(def ^:private throwing-explainer
  (fn [_schema _value] (throw (ex-info "explainer boom" {}))))

(deftest app-db-explainer-throw-preserves-failure
  (testing "rf2-a5kzs (finding 3) — when validate returns false and the
            registered explainer THROWS, validate-app-schema! still returns
            false (the commit rolls back) and emits the failure trace with
            :explain nil — the throw does not abort trace construction nor
            unwind into the router's catch-as-pass."
    ;; validate fails; explain throws.
    (schemas/set-schema-fns! {:validate (fn [_ _] false)
                              :explain  throwing-explainer})
    (try
      (rf/reg-app-schema [:n] {:schema [:int]})
      (let [traces (capture
                     (fn []
                       (is (false? (schemas/validate-app-schema! {:n "bad"} :n/bad))
                           "fail-closed: false (rollback) — the explainer throw did not flip the verdict")))]
        (let [v (first (validation-failures traces))]
          (is (some? v) "the failure trace still fired")
          (is (= :app-db (-> v :tags :where)))
          (is (nil? (-> v :tags :explain)) ":explain degraded to nil — explainer threw")))
      (finally (schemas/reset-schema-validator!)))))

(deftest sub-return-explainer-throw-preserves-failure
  (testing "rf2-a5kzs (finding 3) — a throwing explainer on the meta-bearing
            run-validation path (sub-return) still returns false; the sub
            yields the default and the failure trace fires with :explain nil."
    (schemas/set-schema-fns! {:validate (fn [_ _] false)
                              :explain  throwing-explainer})
    (try
      (rf/reg-event :s/init (fn [{:keys [db]} _] {:db {:v [1 2 3]}}))
      (rf/reg-sub :s/strict
        {:schema [:vector :string]}
        (fn [db _] (:v db)))
      (let [traces (capture
                     (fn []
                       (rf/dispatch-sync [:s/init])
                       (is (nil? (rf/subscribe-once [:s/strict]))
                           "sub yields default — the explainer throw did not become a pass")))]
        (let [v (first (validation-failures traces))]
          (is (some? v) "the sub-return failure trace fired")
          (is (= :sub-return (-> v :tags :where)))
          (is (nil? (-> v :tags :explain)) ":explain degraded to nil")))
      (finally (schemas/reset-schema-validator!)))))

(deftest direct-validate-event-explainer-throw-preserves-false
  (testing "rf2-a5kzs (finding 3) — validate-event! returns false (not a
            throw) when the explainer throws on a real validation failure."
    (schemas/set-schema-fns! {:validate (fn [_ _] false)
                              :explain  throwing-explainer})
    (try
      (let [traces (capture
                     (fn []
                       (is (false? (schemas/validate-event!
                                     :ev/x [:ev/x "bad"]
                                     {:schema [:cat [:= :ev/x] :int]}))
                           "false despite the explainer throwing")))]
        (let [v (first (validation-failures traces))]
          (is (some? v))
          (is (= :event (-> v :tags :where)))
          (is (nil? (-> v :tags :explain)))))
      (finally (schemas/reset-schema-validator!)))))

(deftest boundary-explain-seam-isolates-explainer-throw
  (testing "rf2-a5kzs (finding 3, boundary seam) — explain-with-registered-fn
            degrades a throwing explainer to nil rather than propagating."
    (schemas/set-schema-explainer! throwing-explainer)
    (try
      (is (nil? (schemas/explain-with-registered-fn [:int] "bad"))
          "explainer throw → nil, not a propagated exception")
      (finally (schemas/reset-schema-validator!)))))

;; ===========================================================================
;; Belt-and-braces — a non-throwing default explainer still attaches :explain
;; ===========================================================================

(deftest healthy-explainer-still-attaches-explain
  (testing "rf2-a5kzs — the safe-explain wrapper is transparent on the happy
            path: a normal Malli failure still carries a non-nil :explain."
    (rf/reg-app-schema [:n] {:schema [:int]})
    (let [traces (capture
                   #(schemas/validate-app-schema! {:n "bad"} :n/bad))
          v      (first (validation-failures traces))]
      (is (some? v))
      (is (some? (-> v :tags :explain))
          ":explain is the real Malli explanation — safe-explain didn't swallow it"))))
