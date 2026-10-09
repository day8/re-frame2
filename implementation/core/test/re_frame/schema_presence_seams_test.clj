(ns re-frame.schema-presence-seams-test
  "Declaration presence is KEY-presence at the core-side Spec 010 validation
  seams: a present nil / false `:schema` token reaches the validator verbatim,
  exactly once, and the seam takes its documented recovery; an omitted key
  never consults the validator. A truthiness test of the schema VALUE would
  silently skip the present falsey token. The schemas artefact's own
  `re-frame.schemas-presence-test` pins `run-validation` and the production
  boundary interceptor."
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
            [re-frame.trace.tooling :as rf.trace.tooling])
  (:import [clojure.lang ExceptionInfo]))

(defn- reset-runtime [test-fn]
  (rf.registrar/clear-all!)
  (reset! rf.frame/frames {})
  (rf.schemas/clear-schemas-by-frame!)
  (rf.schemas/set-schema-fns! rf.schemas/default-schema-fns)
  (rf.trace.tooling/clear-listeners!)
  (rf.error-emit/clear-error-listeners!)
  (rf.event-emit/clear-event-listeners!)
  (rf/init! rf.substrate.plain-atom/adapter)
  ;; `init!` re-seeds the framework standards `clear-all!` dropped; the reload
  ;; republishes the late-bind validation hooks these seams reach through.
  (require 're-frame.schemas :reload)
  (rf/make-frame {:id :rf/default})
  (try
    (rf/with-frame :rf/default
      (test-fn))
    (finally
      (rf.schemas/set-schema-fns! rf.schemas/default-schema-fns))))

(use-fixtures :each reset-runtime)

(defn- spy-validator!
  "Install a validator that records every schema token it is handed and
  returns false. Returns the recording atom."
  []
  (let [seen (atom [])]
    (rf.schemas/set-schema-fns!
      {:validate (fn [schema _value] (swap! seen conj schema) false)})
    seen))

;; Private: its public surface is the recordable-cofx satisfaction pipeline.
(def ^:private validate-recordable-value!
  @#'rf.cofx/validate-recordable-value!)

(deftest recordable-cofx-schema-is-key-presence
  (doseq [token [nil false]]
    (testing (pr-str token)
      (let [seen (spy-validator!)]
        (is (thrown? ExceptionInfo
              (validate-recordable-value!
                :cofx/x {:v 1} {:schema token} :ev/x nil (constantly true)))
            "the false verdict takes the always-on hard error")
        (is (= [token] @seen)))))
  (let [seen (spy-validator!)]
    (is (= {:v 1}
           (validate-recordable-value!
             :cofx/x {:v 1} {:doc "no schema"} :ev/x nil (constantly true))))
    (is (= [] @seen) "an omitted :schema never consults the validator")))

(deftest sub-override-schema-is-key-presence
  (doseq [token [nil false]]
    (testing (pr-str token)
      (let [seen (spy-validator!)]
        (is (nil? (rf.subs.override-schema/validate-sub-override!
                    {:pinned :state} [:sub/x] {:schema token} nil))
            "the false verdict recovers to nil")
        (is (= [token] @seen)))))
  (let [seen (spy-validator!)]
    (is (= {:pinned :state}
           (rf.subs.override-schema/validate-sub-override!
             {:pinned :state} [:sub/x] {:doc "no schema"} nil)))
    (is (= [] @seen) "an omitted :schema never consults the validator")))

;; ^:requires-debug — the consult delegates to the schemas artefact's
;; `validate-sub!`, whose body is dev-only (it returns true under
;; `-Dre-frame.debug=false`).
(deftest ^:requires-debug memo-gate-schema-is-key-presence
  (doseq [token [nil false]]
    (testing (pr-str token)
      (let [seen (spy-validator!)]
        (is (nil? (rf.subs.memo/maybe-validate-sub! 42 [:sub/x] :sub/x
                                                    {:schema token} nil))
            "the false verdict recovers to nil")
        (is (= [token] @seen)))))
  (let [seen (spy-validator!)]
    (is (= 42 (rf.subs.memo/maybe-validate-sub! 42 [:sub/x] :sub/x {:doc "x"} nil)))
    (is (= 42 (rf.subs.memo/maybe-validate-sub! 42 [:sub/x] :sub/x nil nil))
        "nil sub-meta is no declaration too")
    (is (= [] @seen) "an omitted :schema never consults the validator")))

;; ^:requires-debug — the fx walk's consult delegates to the schemas
;; artefact's `validate-fx!`, whose body is dev-only (the fx runs unchecked
;; under `-Dre-frame.debug=false`).
(deftest ^:requires-debug fx-gate-schema-is-key-presence
  (testing "a present false token is validated during the real :fx walk and the fx is skipped"
    (let [seen     (spy-validator!)
          fx-calls (atom 0)]
      (rf/reg-fx :fxp/guarded {:schema false}
                 (fn [_ctx _args] (swap! fx-calls inc)))
      (rf/reg-event :evp/emit
        (fn [{:keys [db]} _] {:db db :fx [[:fxp/guarded {:a 1}]]}))
      (rf/dispatch-sync [:evp/emit])
      (is (= 0 @fx-calls))
      (is (= [false] @seen))))
  (testing "an omitted :schema runs the fx unchecked"
    (let [seen     (spy-validator!)
          fx-calls (atom 0)]
      (rf/reg-fx :fxp/plain (fn [_ctx _args] (swap! fx-calls inc)))
      (rf/reg-event :evp/emit-plain
        (fn [{:keys [db]} _] {:db db :fx [[:fxp/plain {:a 1}]]}))
      (rf/dispatch-sync [:evp/emit-plain])
      (is (= 1 @fx-calls))
      (is (= [] @seen)))))
