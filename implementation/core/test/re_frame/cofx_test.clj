(ns re-frame.cofx-test
  "Edge-case coverage for the cofx subsystem (rf2-mq9o).

  Per Spec 002 §Effects and coeffects and Spec 009 §Error contract.
  Pre-rf2-mq9o the inject-cofx interceptor used `println` to warn on
  a missing cofx-id. This file pins the structured-trace replacement:

    1. inject-cofx against an unregistered cofx-id emits
       :rf.error/no-such-cofx (not println) and leaves the ctx
       unchanged so sibling interceptors continue.
    2. The 1-arity form carries :cofx-id and :event-id; no :cofx-value.
    3. The 2-arity form additionally carries :cofx-value."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.registrar :as registrar]
            [re-frame.schemas :as schemas]
            [re-frame.flows :as flows]
            [re-frame.substrate.plain-atom :as plain-atom]))

(defn- reset-runtime [test-fn]
  (registrar/clear-all!)
  (reset! frame/frames {})
  (reset! flows/flows {})
  (reset! schemas/schemas-by-frame {})
  (rf/init! plain-atom/adapter)
  ;; Framework registrations live at namespace-load time; clear-all!
  ;; wiped them. Reload so :rf/route, :rf.route/* subs and the framework
  ;; fx (e.g. :rf.fx/reg-flow) survive between tests.
  (require 're-frame.routing :reload)
  (require 're-frame.ssr     :reload)
  (require 're-frame.machines :reload)
  (test-fn))

(use-fixtures :each reset-runtime)

(defn- collect-traces!
  "Register a trace listener under `id`, returning the atom that
  accumulates events. Tests must (rf/remove-trace-cb! id) to detach."
  [id]
  (let [acc (atom [])]
    (rf/register-trace-cb! id (fn [ev] (swap! acc conj ev)))
    acc))

;; ---- Missing cofx-id → :rf.error/no-such-cofx -----------------------------
;;
;; Per Spec 009 §Error contract, an `inject-cofx` interceptor referencing
;; an unregistered cofx-id emits :rf.error/no-such-cofx with :recovery
;; :no-recovery, and the ctx flows through unchanged so sibling
;; interceptors (and the handler) still run.

(deftest unknown-cofx-id-emits-structured-trace-1-arity
  (testing "the 1-arity inject-cofx form emits :rf.error/no-such-cofx
            with :cofx-id and :event-id and leaves ctx unchanged"
    (let [traces (collect-traces! ::no-cofx-1)
          fired? (atom false)]
      (rf/reg-event-fx :cofx-test/run-no-cofx
        [(rf/inject-cofx :cofx-test/never-registered)]
        (fn [_ _]
          (reset! fired? true)
          {}))
      (rf/dispatch-sync [:cofx-test/run-no-cofx])
      (rf/remove-trace-cb! ::no-cofx-1)

      (is (true? @fired?)
          "the event handler still fired — the unknown cofx did not halt the chain")

      (let [missing (filter #(= :rf.error/no-such-cofx (:operation %)) @traces)]
        (is (= 1 (count missing))
            "exactly one :rf.error/no-such-cofx trace was emitted")
        (let [t (first missing)]
          (is (= :error (:op-type t))
              ":op-type is :error per Spec 009 §Error contract")
          (is (= :rf.error/no-such-cofx (get-in t [:tags :category]))
              ":tags :category mirrors :operation per Spec 009 §Core fields")
          (is (= :cofx-test/never-registered (get-in t [:tags :cofx-id]))
              ":cofx-id identifies the offending cofx")
          (is (= :cofx-test/run-no-cofx (get-in t [:tags :event-id]))
              ":event-id carries the event-id whose interceptor chain missed")
          (is (= :no-recovery (:recovery t))
              ":recovery is :no-recovery per Spec 009 §Recovery table")
          (is (not (contains? (:tags t) :cofx-value))
              "1-arity form: no :cofx-value in the tags"))))))

(deftest unknown-cofx-id-emits-structured-trace-2-arity
  (testing "the 2-arity inject-cofx form additionally carries :cofx-value"
    (let [traces (collect-traces! ::no-cofx-2)]
      (rf/reg-event-fx :cofx-test/run-no-cofx-2
        [(rf/inject-cofx :cofx-test/also-missing {:k :payload})]
        (fn [_ _] {}))
      (rf/dispatch-sync [:cofx-test/run-no-cofx-2])
      (rf/remove-trace-cb! ::no-cofx-2)

      (let [missing (filter #(= :rf.error/no-such-cofx (:operation %)) @traces)]
        (is (= 1 (count missing))
            "exactly one :rf.error/no-such-cofx trace was emitted")
        (let [t (first missing)]
          (is (= :cofx-test/also-missing (get-in t [:tags :cofx-id])))
          (is (= {:k :payload} (get-in t [:tags :cofx-value]))
              "2-arity form: :cofx-value carries the value arg")
          (is (= :cofx-test/run-no-cofx-2 (get-in t [:tags :event-id]))))))))

(deftest unknown-cofx-id-does-not-println
  (testing "the missing-cofx path no longer writes to *out* — the trace is the
            sole diagnostic surface (the println pre-rf2-mq9o is gone)"
    (let [out (java.io.StringWriter.)]
      (rf/reg-event-fx :cofx-test/silent-no-cofx
        [(rf/inject-cofx :cofx-test/silent-missing)]
        (fn [_ _] {}))
      (binding [*out* out]
        (rf/dispatch-sync [:cofx-test/silent-no-cofx]))
      (is (not (clojure.string/includes? (str out) "re-frame2: no cofx registered"))
          "no stray println of the legacy `re-frame2: no cofx registered` warning"))))
