(ns re-frame.schema-presence-seams-test
  "rf2-6eh5h — declaration presence is KEY-presence at every core-side
  Spec 010 validation seam.

  The schemas artefact's own suite (`re-frame.schemas-presence-test`) pins
  the meta-bearing hot path (`run-validation`) and the production boundary
  interceptor. This namespace pins the census of core-owned consumer seams
  that used to test the schema VALUE for truthiness:

   - **Recordable cofx** (`re-frame.cofx/validate-recordable-value!`) —
     used `(if-let [schema (:schema meta)] …)`, so a present nil / false
     token silently skipped the always-on production hard error.
   - **Sub override** (`re-frame.subs.override-schema/validate-sub-override!`)
     — used `(and schema …)`.
   - **Sub return outer gate** (`re-frame.subs.memo/maybe-validate-sub!`)
     — gated the `:schemas/validate-sub!` consult on `(:schema sub-meta)`
     truthiness, bypassing the (now presence-correct) validator seam.
   - **Fx-args outer gate** (`re-frame.fx` walk) — gated `validate-fx!`
     on `(:schema meta)` truthiness.

  Contract pinned per AC 3: a present falsey token is delegated exactly
  once and the surface takes its documented recovery; an omitted key
  remains a no-op (the validator is never consulted)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.cofx :as rf.cofx]
            [re-frame.error-emit :as rf.error-emit]
            [re-frame.event-emit :as rf.event-emit]
            [re-frame.frame :as rf.frame]
            [re-frame.registrar :as rf.registrar]
            [re-frame.schemas :as rf.schemas]
            [re-frame.schemas.malli]
            [re-frame.subs.memo :as rf.subs.memo]
            [re-frame.subs.override-schema :as rf.subs.override-schema]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.trace :as rf.trace])
  (:import [clojure.lang ExceptionInfo]))

(defn- reset-runtime [test-fn]
  (rf.registrar/clear-all!)
  (reset! rf.frame/frames {})
  (rf.schemas/clear-schemas-by-frame!)
  (rf.schemas/reset-schema-validator!)
  (rf.trace/clear-listeners!)
  (rf.error-emit/clear-error-listeners!)
  (rf.event-emit/clear-event-listeners!)
  (rf/init! rf.substrate.plain-atom/adapter)
  ;; `rf.registrar/clear-all!` drops the framework-standard registrations;
  ;; `init!` re-seeds them. Reloading the schemas artefact republishes the
  ;; late-bind validation hooks these seams reach through (mirrors
  ;; `re-frame.always-on-validation-production-test`).
  (require 're-frame.schemas :reload)
  (rf/make-frame {:id :rf/default})
  (try
    (rf/with-frame :rf/default
      (test-fn))
    (finally
      (rf.schemas/reset-schema-validator!))))

(use-fixtures :each reset-runtime)

(defn- spy-validator!
  "Install a validator that records every schema token it is handed and
  returns false. Returns the recording atom."
  []
  (let [seen (atom [])]
    (rf.schemas/set-schema-validator!
      (fn [schema _value] (swap! seen conj schema) false))
    seen))

;; The recordable-cofx validator is deliberately private (its public
;; surface is the EP-0017 satisfaction pipeline); reach it through its var
;; for the focused seam pin.
(def ^:private validate-recordable-value!
  @#'rf.cofx/validate-recordable-value!)

;; ===========================================================================
;; Recordable cofx — present falsey token delegated; the hard error fires
;; ===========================================================================

(deftest recordable-cofx-delegates-present-falsey-tokens
  (doseq [token [nil false]]
    (testing (str "a present " (pr-str token) " :schema on a recordable "
                  "cofx registration is delegated verbatim; the false "
                  "verdict takes the documented production hard error")
      (let [seen (spy-validator!)]
        (is (thrown? ExceptionInfo
              (validate-recordable-value!
                :cofx/x {:v 1} {:schema token} :ev/x nil (constantly true)))
            ":rf.error/cofx-value-invalid THROWS — the always-on recovery")
        (is (= [token] @seen)
            "the EXACT declared token reached the validator, exactly once")))))

(deftest recordable-cofx-omitted-key-is-a-no-op
  (testing "an ABSENT :schema key on a recordable cofx registration never
            consults the validator and returns the value"
    (let [seen (spy-validator!)]
      (is (= {:v 1}
             (validate-recordable-value!
               :cofx/x {:v 1} {:doc "no schema"} :ev/x nil (constantly true)))
          "value returned unchanged")
      (is (= [] @seen) "the validator was never consulted"))))

;; ===========================================================================
;; Sub override — present falsey token delegated; recover-to-nil
;; ===========================================================================

(deftest sub-override-delegates-present-falsey-tokens
  (doseq [token [nil false]]
    (testing (str "a present " (pr-str token) " :schema on the overridden "
                  "sub's metadata is delegated verbatim; the false verdict "
                  "recovers to nil (:replaced-with-default)")
      (let [seen (spy-validator!)]
        (is (nil? (rf.subs.override-schema/validate-sub-override!
                    {:pinned :state} [:sub/x] {:schema token} nil))
            "override value replaced with nil on the false verdict")
        (is (= [token] @seen)
            "the EXACT declared token reached the validator, exactly once")))))

(deftest sub-override-omitted-key-is-a-no-op
  (testing "an ABSENT :schema key passes the override value through unchecked"
    (let [seen (spy-validator!)]
      (is (= {:pinned :state}
             (rf.subs.override-schema/validate-sub-override!
               {:pinned :state} [:sub/x] {:doc "no schema"} nil)))
      (is (= [] @seen) "the validator was never consulted"))))

;; ===========================================================================
;; Sub return outer gate (memo) — the consult itself is presence-gated
;; ===========================================================================

;; ^:requires-debug — the consult delegates to the schemas artefact's
;; `validate-sub!`, whose body is Spec 010 dev-only (elided under
;; `-Dre-frame.debug=false`, where it returns true by design). The
;; production-side presence enforcement is pinned by the boundary /
;; recordable-cofx tests, which run under the gate untagged.
(deftest ^:requires-debug memo-gate-consults-the-validator-for-present-falsey-tokens
  (doseq [token [nil false]]
    (testing (str "maybe-validate-sub! consults the :schemas/validate-sub! "
                  "seam for a present " (pr-str token) " :schema; the false "
                  "verdict recovers to nil")
      (let [seen (spy-validator!)]
        (is (nil? (rf.subs.memo/maybe-validate-sub! 42 [:sub/x] :sub/x
                                            {:schema token} nil))
            "sub return replaced with nil on the false verdict")
        (is (= [token] @seen)
            "the EXACT declared token reached the validator, exactly once")))))

(deftest memo-gate-omitted-key-is-a-no-op
  (testing "an ABSENT :schema key returns the sub value unchecked"
    (let [seen (spy-validator!)]
      (is (= 42 (rf.subs.memo/maybe-validate-sub! 42 [:sub/x] :sub/x {:doc "x"} nil)))
      (is (= 42 (rf.subs.memo/maybe-validate-sub! 42 [:sub/x] :sub/x nil nil))
          "nil sub-meta is 'no declaration' too")
      (is (= [] @seen) "the validator was never consulted"))))

;; ===========================================================================
;; Fx-args outer gate — through the real effect walk
;; ===========================================================================

;; ^:requires-debug — the fx walk's consult delegates to the schemas
;; artefact's `validate-fx!`, whose body is Spec 010 dev-only (elided
;; under `-Dre-frame.debug=false`, where the fx runs unchecked by design).
(deftest ^:requires-debug fx-gate-delegates-a-present-false-token-through-the-real-walk
  (testing "a reg-fx registration declaring {:schema false} is validated
            during the :fx walk (spy sees the exact false token once) and
            the offending fx is SKIPPED (recovery :skipped); siblings and
            the cascade are unaffected"
    (let [seen     (spy-validator!)
          fx-calls (atom 0)]
      (rf/reg-fx :fxp/guarded {:schema false}
                 (fn [_ctx _args] (swap! fx-calls inc)))
      (rf/reg-event :evp/emit
        (fn [{:keys [db]} _] {:db db :fx [[:fxp/guarded {:a 1}]]}))
      (rf/dispatch-sync [:evp/emit])
      (is (= 0 @fx-calls) "the fx handler was skipped on the false verdict")
      (is (= [false] @seen)
          "the EXACT false token reached the validator, exactly once"))))

(deftest fx-gate-omitted-key-is-a-no-op
  (testing "a reg-fx registration with no :schema key runs unchecked"
    (let [seen     (spy-validator!)
          fx-calls (atom 0)]
      (rf/reg-fx :fxp/plain (fn [_ctx _args] (swap! fx-calls inc)))
      (rf/reg-event :evp/emit
        (fn [{:keys [db]} _] {:db db :fx [[:fxp/plain {:a 1}]]}))
      (rf/dispatch-sync [:evp/emit])
      (is (= 1 @fx-calls) "the fx ran")
      (is (= [] @seen) "the validator was never consulted"))))
