(ns re-frame.interceptor-overrides-test
  "Per rf2-4jci1.2 — Spec/002 §`:interceptor-overrides` (lines 1108-1139)
  + §Per-frame and per-call overrides §merge.

  Per-call `{:interceptor-overrides {:my-app/logging nil}}` AND
  per-frame `(reg-frame :f {:interceptor-overrides {...}})` MUST walk
  the assembled interceptor chain and substitute entries by their `:id`:

    * `id -> nil`            removes the interceptor.
    * `id -> <interceptor>`  replaces the entry.
    * id not present in map  leaves entry untouched.

  Per-call wins over per-frame on key conflict (matches `:fx-overrides`
  precedence per Spec 002 §Per-frame and per-call overrides §merge).

  Pre-fix the `:interceptor-overrides` key was accepted by
  `build-envelope` and stored on the envelope but no code path read
  it back. This test pins the consume-side wiring."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.interceptor :as interceptor]
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

(defn- logger-interceptor [log id]
  (interceptor/->interceptor
    :id     id
    :before (fn [ctx] (swap! log conj [id :before]) ctx)
    :after  (fn [ctx] (swap! log conj [id :after]) ctx)))

;; ---- per-call :interceptor-overrides ---------------------------------------

(deftest per-call-interceptor-override-removes-by-id
  (testing "per-call {:icpt nil} drops the interceptor from the chain"
    (let [log (atom [])]
      (rf/reg-event-db :test/run
        [(logger-interceptor log ::log-a)
         (logger-interceptor log ::log-b)]
        (fn [db _] (assoc db :ran? true)))

      (rf/dispatch-sync [:test/run]
                        {:interceptor-overrides {::log-a nil}})

      (is (= [[::log-b :before]
              [::log-b :after]]
             @log)
          "::log-a was removed; ::log-b's before/after still fired"))))

(deftest per-call-interceptor-override-replaces-by-id
  (testing "per-call {:icpt <new>} replaces the interceptor"
    (let [log (atom [])
          original-icpt (logger-interceptor log ::log-x)
          stub-icpt     (interceptor/->interceptor
                          :id     ::log-x
                          :before (fn [ctx] (swap! log conj [::stub :fired]) ctx))]
      (rf/reg-event-db :test/run
        [original-icpt]
        (fn [db _] db))

      (rf/dispatch-sync [:test/run]
                        {:interceptor-overrides {::log-x stub-icpt}})

      (is (= [[::stub :fired]] @log)
          "the stub fired in place of the original ::log-x interceptor"))))

;; ---- per-frame :interceptor-overrides --------------------------------------

(deftest per-frame-interceptor-override-applies-to-every-dispatch
  (testing "per-frame :interceptor-overrides walks the chain on every dispatch"
    (let [log (atom [])]
      (rf/reg-frame :test/silent
        {:interceptor-overrides {::log-a nil}})
      (rf/reg-event-db :test/run
        [(logger-interceptor log ::log-a)
         (logger-interceptor log ::log-b)]
        (fn [db _] db))

      (rf/dispatch-sync [:test/run] {:frame :test/silent})

      (is (= [[::log-b :before]
              [::log-b :after]]
             @log)
          "::log-a was removed by the frame-config override"))))

;; ---- merge order: per-call wins over per-frame -----------------------------

(deftest per-call-overrides-per-frame-on-key-conflict
  (testing "when the same :id appears in per-call AND per-frame overrides, per-call wins"
    (let [log (atom [])
          frame-stub (interceptor/->interceptor
                       :id     ::log
                       :before (fn [ctx] (swap! log conj :frame-stub) ctx))
          call-stub  (interceptor/->interceptor
                       :id     ::log
                       :before (fn [ctx] (swap! log conj :call-stub) ctx))]
      (rf/reg-frame :test/scoped
        {:interceptor-overrides {::log frame-stub}})
      (rf/reg-event-db :test/run
        [(logger-interceptor log ::log)]
        (fn [db _] db))

      (rf/dispatch-sync [:test/run]
                        {:frame :test/scoped
                         :interceptor-overrides {::log call-stub}})

      (is (= [:call-stub] @log)
          "per-call override won over per-frame override on key conflict"))))

;; ---- ids absent from override map pass through unchanged -------------------

(deftest unmatched-ids-pass-through-unchanged
  (testing "interceptors whose :id is not a key in the override map fire normally"
    (let [log (atom [])]
      (rf/reg-event-db :test/run
        [(logger-interceptor log ::log-a)
         (logger-interceptor log ::log-b)]
        (fn [db _] db))

      (rf/dispatch-sync [:test/run]
                        {:interceptor-overrides {::log-c nil}})    ;; ::log-c not in chain

      (is (= [[::log-a :before]
              [::log-b :before]
              [::log-b :after]
              [::log-a :after]]
             @log)
          "both interceptors fired in standard before/after sandwich"))))
