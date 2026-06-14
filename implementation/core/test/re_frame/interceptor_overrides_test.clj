(ns re-frame.interceptor-overrides-test
  "Per rf2-4jci1.2 — Spec/002 §`:interceptor-overrides` (lines 1108-1139)
  + §Per-frame and per-call overrides §merge. REFERENCE-ONLY since the
  EP-0022 flip (rf2-0adhqs.9): chains carry interceptor REFS, and override
  replacements are a REF (or `nil` to remove) — value-valued overrides are
  retired.

  Per-call `{:interceptor-overrides {:my-app/logging nil}}` AND
  per-frame `(reg-frame :f {:interceptor-overrides {...}})` MUST walk
  the assembled interceptor chain and substitute entries by canonical
  reference:

    * `ref -> nil`   removes the interceptor.
    * `ref -> <ref>` replaces the entry with another registered ref.
    * ref not present in chain  leaves entry untouched.

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
  (rf/reg-frame :rf/default {})
  (rf/with-frame :rf/default
    (test-fn)))

(use-fixtures :each reset-runtime)

;; ---- helpers --------------------------------------------------------------
;;
;; EP-0022 reference-only: register a logging interceptor under `id` and return
;; `id` (the chain REF). Chains carry refs; override keys/replacements are refs.

(defn- reg-logger! [log id]
  (rf/reg-interceptor* id
    {:before (fn [ctx] (swap! log conj [id :before]) ctx)
     :after  (fn [ctx] (swap! log conj [id :after]) ctx)})
  id)

;; ---- per-call :interceptor-overrides ---------------------------------------

(deftest per-call-interceptor-override-removes-by-ref
  (testing "per-call {:ref nil} drops the interceptor from the chain"
    (let [log (atom [])]
      (reg-logger! log ::log-a)
      (reg-logger! log ::log-b)
      (rf/reg-event :test/run
        {:interceptors [::log-a ::log-b]}
        (fn [{:keys [db]} _] {:db (assoc db :ran? true)}))

      (rf/dispatch-sync [:test/run]
                        {:interceptor-overrides {::log-a nil}})

      (is (= [[::log-b :before]
              [::log-b :after]]
             @log)
          "::log-a was removed; ::log-b's before/after still fired"))))

(deftest per-call-interceptor-override-replaces-by-ref
  (testing "per-call {:ref <other-ref>} replaces the interceptor"
    (let [log (atom [])]
      (reg-logger! log ::log-x)
      (rf/reg-interceptor* ::stub-x
        {:before (fn [ctx] (swap! log conj [::stub :fired]) ctx)})
      (rf/reg-event :test/run
        {:interceptors [::log-x]}
        (fn [{:keys [db]} _] {:db db}))

      (rf/dispatch-sync [:test/run]
                        {:interceptor-overrides {::log-x ::stub-x}})

      (is (= [[::stub :fired]] @log)
          "the ::stub-x ref fired in place of the original ::log-x interceptor"))))

(deftest value-valued-override-replacement-rejected
  (testing "an inline interceptor VALUE as an override replacement is rejected (value-valued overrides retired)"
    (let [log (atom [])]
      (reg-logger! log ::log-y)
      (rf/reg-event :test/run
        {:interceptors [::log-y]}
        (fn [{:keys [db]} _] {:db db}))
      (is (thrown-with-msg?
            clojure.lang.ExceptionInfo
            #":rf\.error/interceptor-override-invalid"
            (rf/dispatch-sync [:test/run]
                              {:interceptor-overrides
                               {::log-y (interceptor/->interceptor*
                                          :id ::inline :before identity)}}))))))

;; ---- per-frame :interceptor-overrides --------------------------------------

(deftest per-frame-interceptor-override-applies-to-every-dispatch
  (testing "per-frame :interceptor-overrides walks the chain on every dispatch"
    (let [log (atom [])]
      (reg-logger! log ::log-a)
      (reg-logger! log ::log-b)
      (rf/reg-frame :test/silent
        {:interceptor-overrides {::log-a nil}})
      (rf/reg-event :test/run
        {:interceptors [::log-a ::log-b]}
        (fn [{:keys [db]} _] {:db db}))

      (rf/dispatch-sync [:test/run] {:frame :test/silent})

      (is (= [[::log-b :before]
              [::log-b :after]]
             @log)
          "::log-a was removed by the frame-config override"))))

;; ---- merge order: per-call wins over per-frame -----------------------------

(deftest per-call-overrides-per-frame-on-key-conflict
  (testing "when the same ref appears in per-call AND per-frame overrides, per-call wins"
    (let [log (atom [])]
      (reg-logger! log ::log)
      (rf/reg-interceptor* ::frame-stub
        {:before (fn [ctx] (swap! log conj :frame-stub) ctx)})
      (rf/reg-interceptor* ::call-stub
        {:before (fn [ctx] (swap! log conj :call-stub) ctx)})
      (rf/reg-frame :test/scoped
        {:interceptor-overrides {::log ::frame-stub}})
      (rf/reg-event :test/run
        {:interceptors [::log]}
        (fn [{:keys [db]} _] {:db db}))

      (rf/dispatch-sync [:test/run]
                        {:frame :test/scoped
                         :interceptor-overrides {::log ::call-stub}})

      (is (= [:call-stub] @log)
          "per-call override won over per-frame override on key conflict"))))

;; ---- ids absent from override map pass through unchanged -------------------

(deftest unmatched-ids-pass-through-unchanged
  (testing "interceptors whose ref is not a key in the override map fire normally"
    (let [log (atom [])]
      (reg-logger! log ::log-a)
      (reg-logger! log ::log-b)
      (rf/reg-event :test/run
        {:interceptors [::log-a ::log-b]}
        (fn [{:keys [db]} _] {:db db}))

      (rf/dispatch-sync [:test/run]
                        {:interceptor-overrides {::log-c nil}})    ;; ::log-c not in chain

      (is (= [[::log-a :before]
              [::log-b :before]
              [::log-b :after]
              [::log-a :after]]
             @log)
          "both interceptors fired in standard before/after sandwich"))))
