(ns re-frame.schemas-cljs-test
  "CLJS-side smoke for Spec 010 — schema validation runs at dispatch
  time under the Reagent reactive substrate.

  The JVM smoke (re-frame.schemas-test) covers the elision toggle and
  the error-projector → :rf/public-error mapping shape; this file
  confirms that under the Reagent adapter — the production substrate
  for browser apps — a live dispatch with a malformed :db commit
  surfaces a :rf.error/schema-validation-failure trace through the
  same path it would on the JVM. The fact that the trace reaches the
  registered callback under Reagent is what locks the cross-substrate
  contract.

  This is a smoke — one happy path, one violation path. The conformance
  fixtures (schema-app-db-slice-validates.edn et al.) cover the broader
  contract."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            ;; Pull malli into the bundle. re-frame.schemas/malli-validate*
            ;; uses (resolve 'malli.core/validate) — which on CLJS only
            ;; finds vars already loaded into the runtime. Without this
            ;; require, the resolve returns nil and validation silently
            ;; passes (the JVM's requiring-resolve has no CLJS analogue).
            ;; The conformance fixtures for app-db slice validation rely
            ;; on user code requiring malli at app boot; this test does
            ;; the same.
            [malli.core]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.registrar :as registrar]
            [re-frame.flows :as flows]
            [re-frame.substrate.adapter :as adapter]
            [re-frame.substrate.reagent :as reagent-adapter]))

(defn- reset-runtime [test-fn]
  (registrar/clear-all!)
  (reset! frame/frames {})
  (reset! flows/flows {})
  (adapter/dispose-adapter!)
  (rf/init! reagent-adapter/adapter)
  (test-fn))

(use-fixtures :each reset-runtime)

;; ---- live dispatch fires app-db schema validation -------------------------

(deftest live-dispatch-validates-app-db-under-reagent
  (testing "a malformed :db commit emits :rf.error/schema-validation-failure under the Reagent adapter"
    (rf/reg-app-schema [:n] [:int])
    (rf/reg-event-db :n/init  (fn [_ _] {:n 0}))
    (rf/reg-event-db :n/break (fn [db _] (assoc db :n "boom")))
    (let [traces (atom [])]
      (rf/register-trace-cb! ::cljs-live (fn [ev] (swap! traces conj ev)))
      (rf/dispatch-sync [:n/init])
      (rf/dispatch-sync [:n/break])
      (rf/remove-trace-cb! ::cljs-live)
      (let [violations (filter #(= :rf.error/schema-validation-failure
                                   (:operation %))
                               @traces)]
        (is (= 1 (count violations))
            "exactly one schema-validation-failure trace fired under Reagent")
        (let [v (first violations)]
          (is (= :app-db (-> v :tags :where))
              ":where :app-db locates the failure in the post-handler validation step")
          (is (= [:n] (-> v :tags :path))
              ":path names the registered slot")
          (is (= "boom" (-> v :tags :value))
              ":value carries the offending value verbatim")
          (is (= :n/break (-> v :tags :failing-id))
              ":failing-id names the handler whose commit prompted the failure"))))))

(deftest live-dispatch-well-typed-passes-silently
  (testing "well-typed :db commits trigger no schema-validation-failure trace"
    (rf/reg-app-schema [:n] [:int])
    (rf/reg-event-db :n/init (fn [_ _] {:n 0}))
    (rf/reg-event-db :n/inc  (fn [db _] (update db :n inc)))
    (let [traces (atom [])]
      (rf/register-trace-cb! ::cljs-ok (fn [ev] (swap! traces conj ev)))
      (rf/dispatch-sync [:n/init])
      (rf/dispatch-sync [:n/inc])
      (rf/dispatch-sync [:n/inc])
      (rf/remove-trace-cb! ::cljs-ok)
      (is (empty? (filter #(= :rf.error/schema-validation-failure
                              (:operation %))
                          @traces))
          "no validation-failure traces — every commit conforms"))))

;; ---- rf2-jwm4 — event-payload validation under Reagent -------------------
;;
;; Smokes the validate-event! pre-handler wiring (rf2-jwm4) under the
;; Reagent reactive substrate. The JVM smoke covers the broader pre-
;; handler / sub-return / cofx contract; this CLJS path locks the
;; cross-substrate behaviour for at least one of the three new wirings.

(deftest live-dispatch-validates-event-payload-under-reagent
  (testing "a malformed event payload skips the handler and emits :where :event"
    (let [calls (atom 0)]
      (rf/reg-event-db :user/register
        {:spec [:cat [:= :user/register]
                     [:map [:email :string] [:age :int]]]}
        (fn [db _]
          (swap! calls inc)
          db))
      (let [traces (atom [])]
        (rf/register-trace-cb! ::cljs-ev (fn [ev] (swap! traces conj ev)))
        ;; Well-typed payload — handler runs.
        (rf/dispatch-sync [:user/register {:email "a@b.com" :age 30}])
        ;; Malformed — handler must NOT run.
        (rf/dispatch-sync [:user/register {:email "c@d.com" :age "no"}])
        (rf/remove-trace-cb! ::cljs-ev)
        (is (= 1 @calls)
            "handler ran exactly once — the bad payload was rejected pre-handler")
        (let [violations (filter #(= :rf.error/schema-validation-failure
                                     (:operation %))
                                 @traces)]
          (is (= 1 (count violations))
              "exactly one schema-validation-failure trace fired under Reagent")
          (let [v (first violations)]
            (is (= :event (-> v :tags :where))
                ":where :event locates the failure at pre-handler validation")
            (is (= :user/register (-> v :tags :failing-id)))))))))
