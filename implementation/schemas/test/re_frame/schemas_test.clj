(ns re-frame.schemas-test
  "JVM smoke tests for Spec 010 — Schemas (Malli runtime validation).

  Two surfaces here:

    1. **Elision toggle**. In dev builds (per Spec 010 §Dev builds) all
       registered schemas are checked at every validation point. In
       production builds (per Spec 010 §Production builds) validation is
       compile-time-elided via a host gate — `goog.DEBUG` on CLJS, the
       JVM mirror `re-frame.interop/debug-enabled?` here. These tests
       flip the gate via `with-redefs` and assert the dev-mode trace
       fires while the prod-mode call is silent.

    2. **Error projector → :rf/public-error mapping**. Per Spec 010 + 011
       §Default projector, the runtime ships a default projector mapping
       internal trace events to the locked four-key public-error shape
       (`:status :code :message :retryable?`). The schema-validation
       failure category maps to a 400 :bad-request. The default-projector
       fn proper isn't yet wired into the registrar (tracked as rf2-6528);
       we test the *mapping contract* here as a pure fn so the contract
       is locked even before the registry-side wiring lands.

  These tests exercise schemas on the JVM via the plain-atom adapter —
  the conformance fixtures cover the dispatch-time integration; this
  file covers the elision toggle (which fixtures cannot flip from EDN)
  and the projector-mapping shape (which is a separate surface from the
  trace emission)."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.late-bind]
            [re-frame.interop :as interop]
            [re-frame.schemas :as schemas]
            ;; rf2-pk8ur public-surface printer tests need run-printer
            ;; to assert the hot path observes the registered fn.
            [re-frame.schemas.validator :as validator]
            ;; Per rf2-t0hq the default validator routes through the
            ;; late-bind hook `:schemas/malli-validate`, which the
            ;; `re-frame.schemas.malli` adapter ns publishes at load
            ;; time. Per rf2-qyfie the JVM `requiring-resolve`
            ;; fallback was removed — the require is now the canonical
            ;; opt-in on both runtimes; without it the default
            ;; validator soft-passes (Spec 010 §Recommended soft-pass).
            [re-frame.schemas.malli]
            [re-frame.schemas.test-fixture :as tf]
            [re-frame.registrar :as registrar]
            [re-frame.spec :as spec]
            [re-frame.trace :as trace]))

(use-fixtures :each tf/reset-runtime)

;; ---- elision toggle -------------------------------------------------------

(deftest app-db-validation-fires-when-debug-enabled
  (testing "validate-app-db! emits :rf.error/schema-validation-failure when debug-enabled? is true"
    (rf/reg-app-schema [:count] [:int])
    (let [traces (atom [])]
      (rf/register-trace-listener! ::dev (fn [ev] (swap! traces conj ev)))
      ;; Dev mode is the JVM default; validate-app-db! should walk the
      ;; registered schemas and emit on a malformed value.
      (with-redefs [interop/debug-enabled? true]
        (schemas/validate-app-db! {:count "not-an-int"} :test/handler))
      (rf/unregister-trace-listener! ::dev)
      (let [violations (filter #(= :rf.error/schema-validation-failure
                                   (:operation %))
                               @traces)]
        (is (= 1 (count violations))
            "exactly one schema-validation-failure trace fired")
        (let [v (first violations)]
          (is (= :app-db (-> v :tags :where)))
          (is (= [:count] (-> v :tags :path)))
          (is (= "not-an-int" (-> v :tags :value)))
          (is (= :test/handler (-> v :tags :failing-id))))))))

(deftest app-db-validation-elides-when-debug-disabled
  (testing "validate-app-db! is a no-op when debug-enabled? is false (production)"
    (rf/reg-app-schema [:count] [:int])
    (let [traces (atom [])]
      (rf/register-trace-listener! ::prod (fn [ev] (swap! traces conj ev)))
      ;; Production mode — the validation site elides; even with a
      ;; malformed value, no trace fires.
      (with-redefs [interop/debug-enabled? false]
        (schemas/validate-app-db! {:count "not-an-int"} :test/handler))
      (rf/unregister-trace-listener! ::prod)
      (is (empty? (filter #(= :rf.error/schema-validation-failure
                              (:operation %))
                          @traces))
          "no schema-validation-failure trace when validation is elided"))))

(deftest well-typed-value-passes-silently
  (testing "validate-app-db! with a conforming value emits no trace"
    (rf/reg-app-schema [:count] [:int])
    (let [traces (atom [])]
      (rf/register-trace-listener! ::ok (fn [ev] (swap! traces conj ev)))
      (with-redefs [interop/debug-enabled? true]
        (schemas/validate-app-db! {:count 42} :test/handler))
      (rf/unregister-trace-listener! ::ok)
      (is (empty? (filter #(= :rf.error/schema-validation-failure
                              (:operation %))
                          @traces))
          "well-typed value triggers no validation-failure trace"))))

(deftest dispatch-fires-app-db-validation
  (testing "live dispatch through the runtime triggers app-db validation post-:db commit"
    (rf/reg-app-schema [:n] [:int])
    (rf/reg-event-db :n/init (fn [_ _] {:n 0}))
    (rf/reg-event-db :n/break (fn [db _] (assoc db :n "boom")))
    (let [traces (atom [])]
      (rf/register-trace-listener! ::live (fn [ev] (swap! traces conj ev)))
      (rf/dispatch-sync [:n/init])
      (rf/dispatch-sync [:n/break])
      (rf/unregister-trace-listener! ::live)
      (let [violations (filter #(= :rf.error/schema-validation-failure
                                   (:operation %))
                               @traces)]
        (is (= 1 (count violations))
            "the malformed :n/break commit fires exactly one schema trace")
        (is (= :n/break (-> violations first :tags :failing-id))
            ":failing-id names the handler whose commit prompted the failure")
        (is (true? (-> violations first :tags :rollback?))
            "Per rf2-wkxng / rf2-6m0se the trace carries :rollback? true")))))

;; ---- rf2-wkxng / rf2-6m0se — app-db rollback on schema-validation failure --

(deftest app-db-rollback-restores-pre-handler-value-on-failure
  (testing "Per Spec 010 §Per-step recovery row 4 (rf2-wkxng / rf2-6m0se):
            a post-handler app-db schema-validation failure rolls back
            :db to the pre-handler value. The dispatch is treated as
            failed; the bad commit does NOT stand."
    (rf/reg-app-schema [:n] [:int])
    (rf/reg-event-db :n/init  (fn [_ _]  {:n 0}))
    (rf/reg-event-db :n/ok    (fn [db _] (assoc db :n 42)))
    (rf/reg-event-db :n/break (fn [db _] (assoc db :n "boom")))
    (rf/dispatch-sync [:n/init])
    (is (= {:n 0} (rf/get-frame-db (rf/current-frame)))
        "baseline post-init state")
    (rf/dispatch-sync [:n/ok])
    (is (= {:n 42} (rf/get-frame-db (rf/current-frame)))
        "well-typed commit is durable")
    (rf/dispatch-sync [:n/break])
    (is (= {:n 42} (rf/get-frame-db (rf/current-frame)))
        "malformed commit was ROLLED BACK to the pre-handler value
         (was {:n 42} before :n/break; would be {:n \"boom\"} without
         rollback)")))

(deftest app-db-rollback-skips-fx-on-failure
  (testing "Per rf2-wkxng / rf2-6m0se: on rollback the dispatch is
            'treated as failed' — :fx does NOT walk. Sibling fx that
            would have fired do not run."
    (let [fx-calls (atom [])]
      (rf/reg-fx :test/note (fn [v] (swap! fx-calls conj v)))
      (rf/reg-app-schema [:n] [:int])
      (rf/reg-event-fx :n/init
        (fn [_ _] {:db {:n 0}}))
      (rf/reg-event-fx :n/break-with-fx
        (fn [_ _] {:db {:n "boom"}    ;; bad commit
                   :fx [[:test/note :should-not-fire]]}))
      (rf/dispatch-sync [:n/init])
      (rf/dispatch-sync [:n/break-with-fx])
      (is (= {:n 0} (rf/get-frame-db (rf/current-frame)))
          "db rolled back")
      (is (empty? @fx-calls)
          "sibling fx did not walk — dispatch treated as failed"))))

(deftest app-db-rollback-emits-second-db-changed-with-phase-rollback
  (testing "Per rf2-wkxng / rf2-6m0se: rollback emits a second
            :event/db-changed trace stamped :phase :rollback so
            listeners observe the restored state without ambiguity.
            Trace ordering: forward db-changed → schema-failure error
            → rollback db-changed."
    (rf/reg-app-schema [:n] [:int])
    (rf/reg-event-db :n/init  (fn [_ _]  {:n 0}))
    (rf/reg-event-db :n/break (fn [db _] (assoc db :n "boom")))
    (let [events (atom [])]
      (rf/register-trace-listener! ::ord
        (fn [ev] (when (#{:event/db-changed
                          :rf.error/schema-validation-failure}
                        (:operation ev))
                   (swap! events conj
                          [(:operation ev)
                           (-> ev :tags :phase)]))))
      (rf/dispatch-sync [:n/init])
      (reset! events [])
      (rf/dispatch-sync [:n/break])
      (rf/unregister-trace-listener! ::ord)
      ;; Three emissions in this exact order: forward commit (no phase),
      ;; schema-failure error (no phase slot), rollback commit (phase
      ;; :rollback).
      (is (= [[:event/db-changed                     nil]
              [:rf.error/schema-validation-failure   nil]
              [:event/db-changed                     :rollback]]
             @events)
          "trace ordering: forward commit → schema-failure → rollback commit"))))

(deftest validate-app-db-returns-boolean
  (testing "Per rf2-wkxng / rf2-6m0se: validate-app-db! returns true on
            conform (or no schemas / no validator), false on any
            failure. The router consumes this to decide rollback."
    (rf/reg-app-schema [:n] [:int])
    (with-redefs [interop/debug-enabled? true]
      (is (true?  (schemas/validate-app-db! {:n 42}))
          "conforming value returns true")
      (is (false? (schemas/validate-app-db! {:n "boom"}))
          "non-conforming value returns false")
      (is (true?  (schemas/validate-app-db! {:n 42} :some/handler))
          "conforming + event-id arity returns true")
      (is (false? (schemas/validate-app-db! {:n "boom"} :some/handler))
          "non-conforming + event-id arity returns false"))
    (with-redefs [interop/debug-enabled? false]
      (is (true? (schemas/validate-app-db! {:n "boom"} :some/handler))
          "production mode (debug-enabled? false) returns true unconditionally"))))

;; ---- rf2-jwm4 — event-payload validation ---------------------------------

(deftest dispatch-validates-event-payload-pre-handler
  (testing "Per Spec 010 §step 1 (rf2-jwm4): a malformed event vector fires
            :rf.error/schema-validation-failure :where :event before the
            handler runs; the handler is NOT invoked"
    (let [calls (atom 0)]
      (rf/reg-event-db :user/register
        {:schema [:cat [:= :user/register]
                     [:map [:email :string] [:age :int]]]}
        (fn [db [_ payload]]
          (swap! calls inc)
          (update db :users (fnil conj []) payload)))
      (let [traces (atom [])]
        (rf/register-trace-listener! ::ev (fn [ev] (swap! traces conj ev)))
        ;; Well-typed payload — passes; handler runs.
        (rf/dispatch-sync [:user/register {:email "alice@example.com" :age 30}])
        ;; Malformed payload — fails; handler must NOT run.
        (rf/dispatch-sync [:user/register {:email "carol@example.com" :age "no"}])
        (rf/unregister-trace-listener! ::ev)
        (is (= 1 @calls)
            "handler ran exactly once — once for the well-typed payload, skipped for the bad one")
        (let [violations (filter #(= :rf.error/schema-validation-failure
                                     (:operation %))
                                 @traces)]
          (is (= 1 (count violations)))
          (let [v (first violations)]
            (is (= :event (-> v :tags :where)))
            (is (= :user/register (-> v :tags :failing-id)))
            (is (= :user/register (-> v :tags :schema-id)))))))))

(deftest event-payload-validation-elides-when-debug-disabled
  (testing "validate-event! is a no-op when debug-enabled? is false (production)"
    (let [calls (atom 0)]
      (rf/reg-event-db :user/strict
        {:schema [:cat [:= :user/strict] :int]}
        (fn [db _] (swap! calls inc) db))
      (let [traces (atom [])]
        (rf/register-trace-listener! ::ev2 (fn [ev] (swap! traces conj ev)))
        (with-redefs [interop/debug-enabled? false]
          (rf/dispatch-sync [:user/strict "not-an-int"]))
        (rf/unregister-trace-listener! ::ev2)
        (is (empty? (filter #(= :rf.error/schema-validation-failure
                                (:operation %))
                            @traces))
            "no validation trace when debug-enabled? is false")
        (is (= 1 @calls)
            "handler runs anyway — production validation is elided")))))

;; ---- rf2-wcam — sub-return validation ------------------------------------

(deftest sub-return-validation-fires-and-replaces-with-default
  (testing "Per Spec 010 §step 6 (rf2-wcam): a sub whose return value fails
            its :spec emits :rf.error/schema-validation-failure :where :sub-return
            and the caller sees nil (default :replaced-with-default recovery)"
    (rf/reg-event-db :items/init (fn [_ _] {:items ["a" "b" "c"]}))
    (rf/reg-event-db :items/break (fn [db _] (assoc db :items [1 2 3])))
    (rf/reg-sub :items
      {:schema [:vector :string]}
      (fn [db _] (:items db)))
    (let [traces (atom [])]
      (rf/register-trace-listener! ::sr (fn [ev] (swap! traces conj ev)))
      (rf/dispatch-sync [:items/init])
      ;; Well-typed: sub returns the vec.
      (is (= ["a" "b" "c"] (rf/subscribe-once [:items])))
      (rf/dispatch-sync [:items/break])
      ;; Malformed: sub yields nil per :replaced-with-default recovery.
      (is (nil? (rf/subscribe-once [:items])))
      (rf/unregister-trace-listener! ::sr)
      (let [violations (filter #(= :rf.error/schema-validation-failure
                                   (:operation %))
                               @traces)]
        (is (pos? (count violations))
            "at least one sub-return validation failure fired")
        (let [v (first violations)]
          (is (= :sub-return (-> v :tags :where)))
          (is (= :items (-> v :tags :sub-id)))
          (is (= :items (-> v :tags :schema-id)))
          (is (= :replaced-with-default (:recovery v))))))))

(deftest compute-sub-validates-return-value
  (testing "compute-sub validates the return against :spec — the pure
            test-time path mirrors the live reactive path"
    (rf/reg-sub :nums
      {:schema [:vector :int]}
      (fn [db _] (:nums db)))
    (let [traces (atom [])]
      (rf/register-trace-listener! ::cs (fn [ev] (swap! traces conj ev)))
      (is (= [1 2 3] (#'re-frame.subs/compute-sub [:nums] {:nums [1 2 3]})))
      (is (nil? (#'re-frame.subs/compute-sub [:nums] {:nums ["bad"]}))
          "compute-sub yields nil on validation failure")
      (rf/unregister-trace-listener! ::cs)
      (let [violations (filter #(= :rf.error/schema-validation-failure
                                   (:operation %))
                               @traces)]
        (is (= 1 (count violations))
            "exactly one trace from the malformed compute-sub call")))))

;; ---- rf2-7leq — cofx validation ------------------------------------------

(deftest cofx-validation-fires-and-skips-handler
  (testing "Per Spec 010 §step 2 (rf2-7leq): a cofx whose injected value
            fails its :spec emits :rf.error/schema-validation-failure
            :where :cofx and the handler is NOT invoked"
    (rf/reg-cofx :app-version/bad
      {:schema :string}
      (fn [ctx]
        (assoc-in ctx [:coeffects :app-version/bad] 42)))
    (let [calls (atom 0)]
      (rf/reg-event-fx :cap/seed
        [(rf/inject-cofx :app-version/bad)]
        (fn [_cofx _]
          (swap! calls inc)
          {:db {:app-version "should-not-stash"}}))
      (let [traces (atom [])]
        (rf/register-trace-listener! ::cf (fn [ev] (swap! traces conj ev)))
        (rf/dispatch-sync [:cap/seed])
        (rf/unregister-trace-listener! ::cf)
        (is (= 0 @calls)
            "handler was skipped because the cofx :spec failed")
        (let [violations (filter #(= :rf.error/schema-validation-failure
                                     (:operation %))
                                 @traces)]
          (is (= 1 (count violations)))
          (let [v (first violations)]
            (is (= :cofx (-> v :tags :where)))
            (is (= :cap/seed (-> v :tags :failing-id)))
            (is (= :app-version/bad (-> v :tags :cofx-id)))
            (is (= :app-version/bad (-> v :tags :schema-id)))
            (is (= :no-recovery (:recovery v)))))))))

(deftest cofx-validation-passes-when-conforming
  (testing "well-typed cofx values flow through to the handler — no trace, handler runs"
    (rf/reg-cofx :app-version/well
      {:schema :string}
      (fn [ctx]
        (assoc-in ctx [:coeffects :app-version/well] "1.4.5")))
    (let [seen-version (atom nil)]
      (rf/reg-event-fx :cap/seed-good
        [(rf/inject-cofx :app-version/well)]
        (fn [cofx _]
          (reset! seen-version (:app-version/well cofx))
          {}))
      (let [traces (atom [])]
        (rf/register-trace-listener! ::cf2 (fn [ev] (swap! traces conj ev)))
        (rf/dispatch-sync [:cap/seed-good])
        (rf/unregister-trace-listener! ::cf2)
        (is (= "1.4.5" @seen-version)
            "handler ran and saw the well-typed cofx value")
        (is (empty? (filter #(= :rf.error/schema-validation-failure
                                (:operation %))
                            @traces))
            "no schema-validation-failure trace fires for a conforming cofx")))))

;; ---- rf2-xp2o3 — fx-args validation (Spec 010 step 5) --------------------

(deftest fx-args-validation-fires-and-skips-only-the-offending-fx
  (testing "Per Spec 010 §step 5 (rf2-xp2o3): an fx whose args fail its :spec
            emits :rf.error/schema-validation-failure :where :fx-args; the
            offending fx is skipped, sibling fx in the same :fx vector
            continue to run (recovery: :skipped)"
    (let [bad-fx-calls  (atom 0)
          good-fx-calls (atom 0)]
      (rf/reg-fx :my/notify
        {:schema [:map [:level :keyword] [:message :string]]}
        (fn [_ctx _args] (swap! bad-fx-calls inc)))
      (rf/reg-fx :my/log
        (fn [_ctx _args] (swap! good-fx-calls inc)))
      (rf/reg-event-fx :ui/announce
        (fn [_ _]
          {:fx [[:my/notify {:level "error"          ;; bad: needs keyword
                             :message "boom"}]
                [:my/log    "anything"]]}))           ;; sibling — must still run
      (let [traces (atom [])]
        (rf/register-trace-listener! ::fxv (fn [ev] (swap! traces conj ev)))
        (rf/dispatch-sync [:ui/announce])
        (rf/unregister-trace-listener! ::fxv)
        (is (= 0 @bad-fx-calls)
            "the offending fx handler was skipped — its body did NOT run")
        (is (= 1 @good-fx-calls)
            "the sibling fx in the same :fx vector still ran (cascade continues)")
        (let [violations (filter #(= :rf.error/schema-validation-failure
                                     (:operation %))
                                 @traces)]
          (is (= 1 (count violations)))
          (let [v (first violations)]
            (is (= :fx-args (-> v :tags :where)))
            (is (= :my/notify (-> v :tags :failing-id)))
            (is (= :my/notify (-> v :tags :fx-id)))
            (is (= :my/notify (-> v :tags :schema-id)))
            (is (= :ui/announce (-> v :tags :event-id))
                "the originating event-id threads through to the fx-args trace")
            (is (= :skipped (:recovery v))
                "fx-args failure recovery is :skipped per Spec 010 row 5")))
        (let [handled (filter #(= :rf.fx/handled (:operation %)) @traces)]
          (is (= 1 (count handled))
              ":rf.fx/handled fires only for the sibling that actually ran"))))))

(deftest fx-args-validation-passes-when-conforming
  (testing "well-typed fx args flow through to the fx handler — no trace, handler runs"
    (let [calls (atom 0)
          seen (atom nil)]
      (rf/reg-fx :my/email
        {:schema [:map [:to :string]]}
        (fn [_ctx args]
          (swap! calls inc)
          (reset! seen args)))
      (rf/reg-event-fx :user/welcome
        (fn [_ _]
          {:fx [[:my/email {:to "alice@example.com"}]]}))
      (let [traces (atom [])]
        (rf/register-trace-listener! ::fxv2 (fn [ev] (swap! traces conj ev)))
        (rf/dispatch-sync [:user/welcome])
        (rf/unregister-trace-listener! ::fxv2)
        (is (= 1 @calls) "fx handler ran exactly once")
        (is (= {:to "alice@example.com"} @seen) "fx handler saw the well-typed args")
        (is (empty? (filter #(= :rf.error/schema-validation-failure
                                (:operation %))
                            @traces))
            "no schema-validation-failure trace fires for a conforming fx-args")))))

(deftest fx-args-validation-elides-when-debug-disabled
  (testing "validate-fx! is a no-op when debug-enabled? is false (production)"
    (let [calls (atom 0)]
      (rf/reg-fx :strict/fx
        {:schema [:map [:x :int]]}
        (fn [_ctx _args] (swap! calls inc)))
      (rf/reg-event-fx :strict/trigger
        (fn [_ _]
          {:fx [[:strict/fx {:x "not-an-int"}]]}))
      (let [traces (atom [])]
        (rf/register-trace-listener! ::fxv3 (fn [ev] (swap! traces conj ev)))
        (with-redefs [interop/debug-enabled? false]
          (rf/dispatch-sync [:strict/trigger]))
        (rf/unregister-trace-listener! ::fxv3)
        (is (empty? (filter #(= :rf.error/schema-validation-failure
                                (:operation %))
                            @traces))
            "no validation trace when debug-enabled? is false")
        (is (= 1 @calls)
            "fx handler runs anyway — production validation is elided")))))

(deftest fx-args-validation-direct-call-shape
  (testing "validate-fx! returns true on pass, false on fail; emits the canonical
            :where :fx-args trace with the locked tag shape"
    (let [traces (atom [])]
      (rf/register-trace-listener! ::fxv4 (fn [ev] (swap! traces conj ev)))
      ;; Direct call — exercises the validate-fx! fn itself, not the integration.
      (is (true? (schemas/validate-fx! :my/fx :ev/origin {:x 1} {:schema [:map [:x :int]]}))
          "well-typed args pass")
      (is (false? (schemas/validate-fx! :my/fx :ev/origin {:x "bad"} {:schema [:map [:x :int]]}))
          "malformed args fail")
      (is (true? (schemas/validate-fx! :my/fx :ev/origin {:x 1} {}))
          "no :spec → soft pass")
      (rf/unregister-trace-listener! ::fxv4)
      (let [violations (filter #(= :rf.error/schema-validation-failure
                                   (:operation %))
                               @traces)]
        (is (= 1 (count violations)))
        (let [v (first violations)]
          (is (= :fx-args   (-> v :tags :where)))
          (is (= :my/fx     (-> v :tags :fx-id)))
          (is (= :my/fx     (-> v :tags :failing-id)))
          (is (= :my/fx     (-> v :tags :schema-id)))
          (is (= :ev/origin (-> v :tags :event-id)))
          (is (= {:x "bad"} (-> v :tags :fx-args)))
          (is (= {:x "bad"} (-> v :tags :value)))
          (is (= {:x "bad"} (-> v :tags :received)))
          (is (= :skipped   (:recovery v))))))))

(deftest fx-args-validation-redacts-when-sensitive
  (testing "validate-fx! consults the schema tree for `:sensitive?` props
            (the fx-meta `:sensitive?` annotation has been removed); on
            redaction it scrubs `:value`/`:received`/`:explain`/`:fx-args`
            and stamps `:sensitive? true`. Per rf2-4fbsd the earlier
            `:malli-error` duplicate slot is gone."
    (let [traces (atom [])]
      (rf/register-trace-listener! ::fxv5 (fn [ev] (swap! traces conj ev)))
      ;; Sensitivity is now path-marked on the schema slot (the handler/
      ;; fx-meta `:sensitive?` annotation has been removed); a `:sensitive?
      ;; true` prop on the failing slot's schema drives redaction.
      (is (false? (schemas/validate-fx! :my/secret
                                        :ev/origin
                                        {:token 42}
                                        {:schema [:map [:token {:sensitive? true} :string]]})))
      (rf/unregister-trace-listener! ::fxv5)
      (let [violations (filter #(= :rf.error/schema-validation-failure
                                   (:operation %))
                               @traces)]
        (is (= 1 (count violations)))
        (let [v (first violations)]
          ;; `:sensitive?` is hoisted from `:tags` to the top-level per
          ;; Spec 009 §Trace-event field `:sensitive?` (rf2-isdwf).
          (is (true? (:sensitive? v))
              "top-level :sensitive? stamp consumers filter on")
          (is (= :rf/redacted (-> v :tags :value)))
          (is (= :rf/redacted (-> v :tags :received)))
          (is (= :rf/redacted (-> v :tags :fx-args)))
          (is (= :rf/redacted (-> v :tags :explain)))
          (is (not (contains? (:tags v) :malli-error))
              ":malli-error slot is gone (rf2-4fbsd)")
          ;; Non-redacted slots survive redaction.
          (is (= :my/secret (-> v :tags :fx-id)))
          (is (= :my/secret (-> v :tags :failing-id)))
          (is (= :fx-args   (-> v :tags :where))))))))

(deftest fx-args-validation-late-bind-hook-published
  (testing "the :schemas/validate-fx! late-bind hook IS published — the
            schemas artefact's contract surface includes this fn alongside
            the four siblings"
    (let [resolved (re-frame.late-bind/get-fn :schemas/validate-fx!)]
      (is (some? resolved) "hook resolves to a fn when schemas is loaded")
      (is (= schemas/validate-fx! resolved) "hook points at the public fn"))))

;; ---- error projector → :rf/public-error mapping --------------------------

(defn- default-error-projector
  "The default projector contract per Spec 011 §Default projector. The
  runtime-resident registry-backed projector isn't yet wired (rf2-6528),
  but the mapping is locked. This fn implements the table verbatim so
  these tests pin the mapping contract.

  Returns the locked four-key public-error shape:
    {:status :code :message :retryable?}

  In dev mode (:dev-error-detail? true) the public shape carries an
  additional :details key with the original trace event."
  ([trace-event] (default-error-projector trace-event {}))
  ([trace-event {:keys [dev-error-detail?]}]
   (let [base (case (:operation trace-event)
                :rf.error/no-such-handler
                {:status 404 :code :not-found
                 :message "Page not found" :retryable? false}

                :rf.error/schema-validation-failure
                {:status 400 :code :bad-request
                 :message "Invalid input" :retryable? false}

                :rf.error/handler-exception
                {:status 500 :code :internal-error
                 :message "Something went wrong" :retryable? false}

                :rf.error/sub-exception
                {:status 500 :code :internal-error
                 :message "Something went wrong" :retryable? false}

                :rf.error/fx-handler-exception
                {:status 500 :code :internal-error
                 :message "Something went wrong" :retryable? false}

                :rf.error/drain-depth-exceeded
                {:status 500 :code :internal-error
                 :message "Something went wrong" :retryable? false}

                ;; default — generic 500
                {:status 500 :code :internal-error
                 :message "Something went wrong" :retryable? false})]
     (cond-> base
       dev-error-detail? (assoc :details trace-event)))))

(deftest projector-maps-schema-failure-to-bad-request
  (testing "schema-validation-failure projects to a locked 400 :bad-request"
    (let [trace-event {:operation :rf.error/schema-validation-failure
                       :op-type   :error
                       :tags      {:where :event :event-id :user/register
                                   :received [:user/register {:age "no"}]}
                       :recovery  :no-recovery}
          public      (default-error-projector trace-event)]
      (is (= 400 (:status public)))
      (is (= :bad-request (:code public)))
      (is (= "Invalid input" (:message public)))
      (is (false? (:retryable? public))
          "schema validation failure is NOT retryable — the input is the bug")
      (is (= #{:status :code :message :retryable?} (set (keys public)))
          "prod-mode public shape is the locked four keys only — no :details leak"))))

(deftest projector-includes-details-in-dev
  (testing "with :dev-error-detail? true the public shape carries the original trace under :details"
    (let [trace-event {:operation :rf.error/schema-validation-failure
                       :tags      {:where :app-db :path [:user] :value "bad"}}
          public      (default-error-projector trace-event {:dev-error-detail? true})]
      (is (= 400 (:status public)))
      (is (= :bad-request (:code public)))
      (is (contains? public :details)
          ":details carries the original trace event in dev mode")
      (is (= trace-event (:details public))
          ":details is the trace event verbatim — full internal detail"))))

(deftest projector-falls-back-to-generic-500
  (testing "an unknown error category projects to the locked generic-500 shape"
    (let [trace-event {:operation :rf.error/something-unmapped
                       :tags      {}}
          public      (default-error-projector trace-event)]
      (is (= 500 (:status public)))
      (is (= :internal-error (:code public)))
      (is (= "Something went wrong" (:message public)))
      (is (false? (:retryable? public))))))

(deftest projector-maps-no-such-handler-to-404
  (testing ":rf.error/no-such-handler in routing context projects to 404"
    (let [trace-event {:operation :rf.error/no-such-handler
                       :tags      {:url "/no-such-page"}}
          public      (default-error-projector trace-event)]
      (is (= 404 (:status public)))
      (is (= :not-found (:code public))))))

(deftest projector-output-shape-is-stable
  (testing "every projection returns the locked four keys (no extras in prod)"
    (doseq [op [:rf.error/no-such-handler
                :rf.error/schema-validation-failure
                :rf.error/handler-exception
                :rf.error/sub-exception
                :rf.error/fx-handler-exception
                :rf.error/drain-depth-exceeded
                :rf.error/some-future-category]]
      (let [public (default-error-projector {:operation op :tags {}})]
        (is (= #{:status :code :message :retryable?} (set (keys public)))
            (str "projection for " op " must carry exactly the four locked keys"))
        (is (integer? (:status public)))
        (is (keyword? (:code public)))
        (is (string? (:message public)))
        (is (boolean? (:retryable? public)))))))

;; ---- rf2-xfa2 — frame-scoped app-db schemas ------------------------------

(deftest reg-app-schema-defaults-to-current-frame
  (testing "Per Spec 010 §Per-frame schemas — reg-app-schema with no opts
            registers against (current-frame), which is :rf/default outside
            (with-frame ...)."
    (rf/reg-app-schema [:user] [:map [:id :uuid]])
    (is (= [:map [:id :uuid]] (rf/app-schema-at [:user]))
        "schema is visible from the active frame's lookup")
    (is (= [:map [:id :uuid]] (rf/app-schema-at [:user] :rf/default))
        "schema is visible from explicit :rf/default lookup")
    (is (= {[:user] [:map [:id :uuid]]} (rf/app-schemas))
        "app-schemas returns the active frame's schema set")
    (is (= {[:user] [:map [:id :uuid]]} (rf/app-schemas :rf/default))
        "app-schemas with explicit :rf/default returns the same map")))

(deftest reg-app-schema-explicit-frame-opt-isolates-schemas
  (testing "Per Spec 010 §Per-frame schemas — :frame opt registers against
            a named frame; sibling frames don't see that schema."
    (rf/reg-frame :test/story {})
    (rf/reg-app-schema [:user] [:map [:id :uuid]] {:frame :rf/default})
    (rf/reg-app-schema [:user] [:map [:nick :string]] {:frame :test/story})
    (is (= [:map [:id :uuid]]   (rf/app-schema-at [:user] :rf/default))
        "default frame keeps its own schema at [:user]")
    (is (= [:map [:nick :string]] (rf/app-schema-at [:user] :test/story))
        "story frame has its own (different) schema at [:user]")
    (is (= {[:user] [:map [:id :uuid]]}     (rf/app-schemas :rf/default)))
    (is (= {[:user] [:map [:nick :string]]} (rf/app-schemas {:frame :test/story})))))

(deftest sibling-frame-schemas-do-not-fire-on-each-others-dispatches
  (testing "Per Spec 010 §Per-frame schemas — validate-app-db! only walks the
            schemas registered against THIS dispatch's frame; a malformed
            commit on frame A must not fire a schema-validation-failure for
            a schema registered against frame B."
    (rf/reg-frame :test/main  {})
    (rf/reg-frame :test/other {})
    ;; Schema only on :test/other; commit happens on :test/main.
    (rf/reg-app-schema [:n] [:int] {:frame :test/other})
    (rf/reg-event-db :n/break-on-main (fn [db _] (assoc db :n "not-an-int")))
    (let [traces (atom [])]
      (rf/register-trace-listener! ::sib (fn [ev] (swap! traces conj ev)))
      (rf/dispatch-sync [:n/break-on-main] {:frame :test/main})
      (rf/unregister-trace-listener! ::sib)
      (is (empty? (filter #(= :rf.error/schema-validation-failure (:operation %))
                          @traces))
          "no schema fires on :test/main because the schema lives on :test/other"))))

(deftest schema-fires-only-on-the-frame-it-registers-against
  (testing "Per Spec 010 §Per-frame schemas — a malformed commit on the same
            frame the schema is registered against DOES fire the failure trace."
    (rf/reg-frame :test/main {})
    (rf/reg-app-schema [:n] [:int] {:frame :test/main})
    (rf/reg-event-db :n/break (fn [db _] (assoc db :n "not-an-int")))
    (let [traces (atom [])]
      (rf/register-trace-listener! ::same (fn [ev] (swap! traces conj ev)))
      (rf/dispatch-sync [:n/break] {:frame :test/main})
      (rf/unregister-trace-listener! ::same)
      (let [violations (filter #(= :rf.error/schema-validation-failure (:operation %))
                               @traces)]
        (is (= 1 (count violations))
            "the schema registered against :test/main fires when :test/main commits a violation")
        (is (= :test/main (-> violations first :tags :frame))
            ":frame tag carries the failing frame's id")))))

(deftest app-schemas-with-keyword-and-opts-arities-agree
  (testing "(app-schemas frame-id) is documented as sugar for
            (app-schemas {:frame frame-id}); both must return the same map."
    (rf/reg-frame :test/k {})
    (rf/reg-app-schema [:k] [:int] {:frame :test/k})
    (is (= (rf/app-schemas :test/k)
           (rf/app-schemas {:frame :test/k}))
        "keyword form == opts-map form")))

;; ---- rf2-0z1z — app-schemas-digest --------------------------------------

(deftest app-schemas-digest-is-canonical-wire-form
  (testing "Per Spec 010 §Digest algorithm — the digest is the literal
            prefix \"sha256:\" followed by 16 lowercase hex characters."
    (rf/reg-app-schema [:user] [:map [:id :uuid]])
    (let [d (rf/app-schemas-digest)]
      (is (string? d))
      (is (re-matches #"sha256:[0-9a-f]{16}" d)
          "digest is exactly \"sha256:\" + 16 lowercase hex chars"))))

(deftest app-schemas-digest-is-stable
  (testing "Per Spec 010 §Digest algorithm — registering the same schema
            set produces the same digest (cross-runtime byte-stable)."
    (rf/reg-app-schema [:user]  [:map [:id :uuid]])
    (rf/reg-app-schema [:todos] [:vector :string])
    (let [d1 (rf/app-schemas-digest)]
      ;; Re-register the SAME schemas — last-write-wins, but the map is
      ;; structurally identical, so the digest must not move.
      (rf/reg-app-schema [:todos] [:vector :string])
      (rf/reg-app-schema [:user]  [:map [:id :uuid]])
      (is (= d1 (rf/app-schemas-digest))
          "byte-identical schema set → byte-identical digest"))))

(deftest app-schemas-digest-changes-on-schema-change
  (testing "A schema-set change perturbs the digest. Two different schema
            sets must produce distinct digests."
    (rf/reg-app-schema [:user] [:map [:id :uuid]])
    (let [before (rf/app-schemas-digest)]
      (rf/reg-app-schema [:user] [:map [:id :string]])
      (is (not= before (rf/app-schemas-digest))
          "tightening / changing a schema flips the digest"))))

(deftest app-schemas-digest-frame-isolated
  (testing "Per Spec 010 §Per-frame schemas — two frames with different
            schema sets produce different digests; a frame with no schemas
            has a stable empty-set digest distinct from any non-empty
            frame's digest."
    (rf/reg-frame :test/a {})
    (rf/reg-frame :test/b {})
    (rf/reg-app-schema [:user] [:map [:id :uuid]] {:frame :test/a})
    ;; :test/b has no schemas registered.
    (let [da (rf/app-schemas-digest :test/a)
          db (rf/app-schemas-digest :test/b)]
      (is (not= da db)
          "frames with different schema sets have different digests")
      (is (= db (rf/app-schemas-digest :test/b))
          "the empty-schema digest is stable across calls")
      (is (= db (rf/app-schemas-digest {:frame :test/b}))
          "keyword-sugar arity equals opts-map arity"))))

(deftest app-schemas-digest-keyword-and-opts-arities-agree
  (testing "(app-schemas-digest frame-id) is sugar for
            (app-schemas-digest {:frame frame-id}); both must return the
            same string."
    (rf/reg-frame :test/d {})
    (rf/reg-app-schema [:k] [:int] {:frame :test/d})
    (is (= (rf/app-schemas-digest :test/d)
           (rf/app-schemas-digest {:frame :test/d}))
        "keyword form == opts-map form")))

(deftest app-schemas-digest-empty-set-is-defined
  (testing "Empty schema set has a defined, stable digest (the SHA-256 of
            the empty string, prefixed). Hosts with no schemas registered
            still get a usable digest, not nil."
    (rf/reg-frame :test/empty {})
    (let [d (rf/app-schemas-digest :test/empty)]
      (is (string? d))
      (is (re-matches #"sha256:[0-9a-f]{16}" d))
      ;; SHA-256 of "" is e3b0c44298fc1c14...; first 16 hex chars are
      ;; e3b0c44298fc1c14.
      (is (= "sha256:e3b0c44298fc1c14" d)
          "empty schema set hashes the empty concatenation per Spec 010"))))

(deftest app-schemas-digest-independent-of-registration-order
  (testing "Per Spec 010 §Digest algorithm step 4 — lines are sorted
            lexicographically before final hashing, so the registration
            order of schemas must not affect the digest."
    (rf/reg-frame :test/o1 {})
    (rf/reg-frame :test/o2 {})
    ;; Same schemas, different registration order.
    (rf/reg-app-schema [:user]  [:map [:id :uuid]]   {:frame :test/o1})
    (rf/reg-app-schema [:todos] [:vector :string]    {:frame :test/o1})
    (rf/reg-app-schema [:todos] [:vector :string]    {:frame :test/o2})
    (rf/reg-app-schema [:user]  [:map [:id :uuid]]   {:frame :test/o2})
    (is (= (rf/app-schemas-digest :test/o1)
           (rf/app-schemas-digest :test/o2))
        "same schema set, different registration order → same digest")))

;; ---- rf2-froe — set-schema-validator! seam -------------------------------
;;
;; Per Spec 010 §Non-Malli validators (rf2-froe) the validator and
;; explainer fns are pluggable via `(rf/set-schema-validator! ...)` /
;; `(rf/set-schema-explainer! ...)`. Default delegates to Malli; apps
;; that want to drop the ~24 KB gzipped Malli surface (rf2-qnxf bundle
;; audit) substitute another fn (or `nil` for no-op).

(deftest default-validator-delegates-to-malli
  (testing "Per Spec 010 §Non-Malli validators — out of the box the
            validator delegates to Malli; apps that don't call
            set-schema-validator! get the same behaviour they had
            before the seam landed."
    (rf/reg-app-schema [:n] [:int])
    (let [traces (atom [])]
      (rf/register-trace-listener! ::default-malli (fn [ev] (swap! traces conj ev)))
      ;; Malformed value triggers the default Malli validate to return
      ;; falsey -> trace fires.
      (schemas/validate-app-db! {:n "not-an-int"} :test/handler)
      (rf/unregister-trace-listener! ::default-malli)
      (let [violations (filter #(= :rf.error/schema-validation-failure (:operation %))
                               @traces)]
        (is (= 1 (count violations))
            "default Malli validator catches the type mismatch")))))

(deftest custom-validator-is-invoked-instead-of-malli
  (testing "Per Spec 010 §Non-Malli validators — set-schema-validator! swaps
            in any (fn [schema value] truthy?); the framework calls it on
            every validation site instead of the default."
    (let [calls (atom [])
          ;; Mock validator: fail on any value containing the substring
          ;; \"bad\"; pass everything else. Records every (schema, value)
          ;; pair it sees so the test can assert the call.
          custom (fn [schema value]
                   (swap! calls conj [schema value])
                   (not (and (string? value) (str/includes? value "bad"))))]
      (rf/set-schema-validator! custom)
      (rf/reg-app-schema [:label] :string)
      (let [traces (atom [])]
        (rf/register-trace-listener! ::custom (fn [ev] (swap! traces conj ev)))
        (schemas/validate-app-db! {:label "hello"} :h/ok)        ;; passes
        (schemas/validate-app-db! {:label "totally-bad"} :h/no)  ;; fails
        (rf/unregister-trace-listener! ::custom)
        (is (= 2 (count @calls))
            "custom validator was invoked for both validation calls")
        (let [violations (filter #(= :rf.error/schema-validation-failure (:operation %))
                                 @traces)]
          (is (= 1 (count violations))
              "exactly one trace fired — for the value the custom validator rejected")
          (is (= "totally-bad" (-> violations first :tags :value))))))))

(deftest nil-validator-disables-validation-everywhere
  (testing "Per Spec 010 §Non-Malli validators — passing nil to
            set-schema-validator! disables validation entirely; every
            validate-* fn returns true without inspecting the schema."
    (rf/set-schema-validator! nil)
    (rf/reg-app-schema [:n] [:int])
    (let [traces (atom [])]
      (rf/register-trace-listener! ::nilv (fn [ev] (swap! traces conj ev)))
      ;; Malformed value would normally fire a trace; with nil
      ;; validator the call site short-circuits.
      (schemas/validate-app-db! {:n "definitely-not-an-int"} :test/h)
      (rf/unregister-trace-listener! ::nilv)
      (is (empty? (filter #(= :rf.error/schema-validation-failure (:operation %))
                          @traces))
          "nil validator: no validation, no trace, no surprise"))))

(deftest nil-validator-also-disables-event-validation
  (testing "Per Spec 010 §Non-Malli validators — nil validator covers every
            validation site, not just app-db. The event-validation site
            short-circuits too."
    (rf/set-schema-validator! nil)
    (let [calls (atom 0)]
      (rf/reg-event-db :user/strict
        {:schema [:cat [:= :user/strict] :int]}
        (fn [db _] (swap! calls inc) db))
      (let [traces (atom [])]
        (rf/register-trace-listener! ::nile (fn [ev] (swap! traces conj ev)))
        ;; A wildly malformed payload — but with no validator the
        ;; check is skipped and the handler runs anyway.
        (rf/dispatch-sync [:user/strict "not-an-int"])
        (rf/unregister-trace-listener! ::nile)
        (is (= 1 @calls)
            "handler ran — nil validator means no pre-handler check")
        (is (empty? (filter #(= :rf.error/schema-validation-failure (:operation %))
                            @traces))
            "no validation trace fires when validator is nil")))))

(deftest set-schema-validator-map-arity-installs-both-fns
  (testing "Per Spec 010 §Non-Malli validators — the map arity of
            set-schema-validator! installs validate and explain
            atomically. Apps that want a custom explainer alongside
            their custom validator use this form."
    (let [validate-calls (atom 0)
          explain-calls  (atom 0)
          v-fn (fn [_s v] (swap! validate-calls inc) (= v :good))
          e-fn (fn [s v]  (swap! explain-calls inc) {:my-explanation [s v]})]
      (rf/set-schema-validator! {:validate v-fn :explain e-fn})
      (rf/reg-app-schema [:k] :keyword)
      (let [traces (atom [])]
        (rf/register-trace-listener! ::map (fn [ev] (swap! traces conj ev)))
        (schemas/validate-app-db! {:k :good}   :h/pass)
        (schemas/validate-app-db! {:k :nope}   :h/fail)
        (rf/unregister-trace-listener! ::map)
        (is (= 2 @validate-calls) "custom validate fn ran for both calls")
        (is (= 1 @explain-calls)  "custom explain fn ran only on the failure path")
        (let [violations (filter #(= :rf.error/schema-validation-failure (:operation %))
                                 @traces)]
          (is (= 1 (count violations)))
          (is (= {:my-explanation [:keyword :nope]}
                 (-> violations first :tags :explain))
              "the trace's :explain key carries the custom explainer's output"))))))

(deftest set-schema-explainer-only-leaves-validator-untouched
  (testing "set-schema-explainer! swaps just the explainer; the validator
            (default Malli) keeps catching real failures."
    (let [explained (atom nil)
          custom-e  (fn [_s v] (reset! explained v) {:custom-said v})]
      ;; Validator stays at its default (Malli); only the explainer is
      ;; substituted.
      (rf/set-schema-explainer! custom-e)
      (rf/reg-app-schema [:n] [:int])
      (let [traces (atom [])]
        (rf/register-trace-listener! ::eonly (fn [ev] (swap! traces conj ev)))
        (schemas/validate-app-db! {:n "broken"} :h/oops)
        (rf/unregister-trace-listener! ::eonly)
        (is (= "broken" @explained) "custom explainer ran with the bad value")
        (let [v (first (filter #(= :rf.error/schema-validation-failure (:operation %))
                               @traces))]
          (is (= {:custom-said "broken"} (-> v :tags :explain))))))))

(deftest reset-schema-validator-restores-defaults
  (testing "reset-schema-validator! brings the framework default back —
            test-support helper for cleaning up after a custom
            registration. After reset, the default Malli behaviour
            resumes."
    ;; Install a sabotage validator: passes everything (so a known-bad
    ;; value would slip past).
    (rf/set-schema-validator! (fn [_ _] true))
    (rf/reg-app-schema [:n] [:int])
    ;; First confirm the sabotage is in effect.
    (let [traces (atom [])]
      (rf/register-trace-listener! ::sab (fn [ev] (swap! traces conj ev)))
      (schemas/validate-app-db! {:n "bad"} :h/sabotage)
      (rf/unregister-trace-listener! ::sab)
      (is (empty? (filter #(= :rf.error/schema-validation-failure (:operation %))
                          @traces))
          "sabotage validator passes everything — no trace"))
    ;; Reset back to default Malli, retry the bad value, expect a trace.
    (schemas/reset-schema-validator!)
    (let [traces (atom [])]
      (rf/register-trace-listener! ::rst (fn [ev] (swap! traces conj ev)))
      (schemas/validate-app-db! {:n "bad"} :h/back-to-default)
      (rf/unregister-trace-listener! ::rst)
      (is (= 1 (count (filter #(= :rf.error/schema-validation-failure (:operation %))
                              @traces)))
          "default Malli validator is back in place — bad value catches"))))

(deftest validate-with-registered-fn-bypasses-debug-gate
  (testing "Per rf2-r2uh integration — validate-with-registered-fn is the
            public seam the boundary-validation interceptor will call. It
            does NOT consult interop/debug-enabled? (the boundary
            interceptor runs in production by design); it routes through
            the registered validator the same way the dev hot path does."
    (rf/set-schema-validator! (fn [_ v] (= v :good)))
    (with-redefs [interop/debug-enabled? false]
      (is (true?  (schemas/validate-with-registered-fn :keyword :good))
          "valid value passes — debug gate ignored")
      (is (false? (schemas/validate-with-registered-fn :keyword :bad))
          "invalid value fails — debug gate ignored"))))

(deftest validator-set-via-public-api-is-visible-on-schemas-ns
  (testing "The public re-export rf/set-schema-validator! flows through to
            the schemas namespace's validator-fn atom."
    (let [my-fn (fn [_ _] :sentinel)]
      (rf/set-schema-validator! my-fn)
      (is (= my-fn @schemas/validator-fn)
          "the atom carries the fn the user registered"))))

;; ---- rf2-pk8ur — set-schema-printer! public-surface contract -------------
;;
;; Per Spec 010 §Schema digest line 491 (rf2-wla45) the printer fn is the
;; third leg of the validator-surface seam: substitute-Malli ports register
;; their own (validate, explain, print) triple so the digest reflects the
;; port's own serialisation contract rather than the framework's Malli-EDN
;; default. The artefact-side contract — atom swap, default fallback,
;; map-arity, hot-path read — is locked by `printer_seam_test.clj`.
;;
;; This file pins the PUBLIC-SURFACE contract: a call to the re-exported
;; `re-frame.core/set-schema-printer!` flows through the late-bind directory
;; and reaches the schemas artefact's printer atom + run-printer hot path
;; + digest pipeline. Parallel to `validator-set-via-public-api-is-visible-
;; on-schemas-ns` above; closes the rf2-kp835 Phase-1 audit gap (the public
;; symbol had no end-to-end caller exercising the wiring).

(deftest printer-set-via-public-api-is-visible-on-schemas-ns
  (testing "rf2-pk8ur — the public re-export rf/set-schema-printer! flows
            through the late-bind directory to the schemas artefact's
            printer-fn atom. Parallels the rf/set-schema-validator! /
            rf/set-schema-explainer! end-to-end pins above."
    (let [my-fn (fn [_schema-value] "::PUBLIC-SURFACE::")]
      (rf/set-schema-printer! my-fn)
      (is (= my-fn @schemas/printer-fn)
          "the atom carries the printer the public-surface caller registered")
      (is (= "::PUBLIC-SURFACE::" (validator/run-printer :int))
          "run-printer's hot path reaches the public-surface registration"))))

(deftest printer-set-via-public-api-flips-digest-bytes
  (testing "rf2-pk8ur — the canonical end-to-end use of the public surface:
            a custom printer registered via rf/set-schema-printer! changes
            the digest pipeline's output. This is what a non-Malli port
            (a Zod port, a clojure.spec port) does at boot — and what the
            existing 0-caller audit on the public symbol failed to
            exercise. Spec 010 §Schema digest line 491: 'two ports using
            different schema languages produce different digests by
            construction'."
    (rf/reg-app-schema [:n] :int)
    (let [default-digest (rf/app-schemas-digest)]
      (rf/set-schema-printer! (fn [_] "::CUSTOM-PORT::"))
      (let [custom-digest (rf/app-schemas-digest)]
        (is (re-matches #"^sha256:[0-9a-f]{16}$" custom-digest)
            "digest is still the wire-form '\"sha256:\" + 16-hex'")
        (is (not= default-digest custom-digest)
            "registering a different printer through the public surface
             produces a different digest — the bytes the printer emits
             are what the digest pipeline hashes")))))

(deftest printer-set-via-public-api-nil-restores-default
  (testing "rf2-pk8ur — passing nil to the public rf/set-schema-printer!
            reinstalls the default EDN canonicaliser. The digest is
            never undefined for a present schema set, even after a
            port-specific printer has been registered and then
            withdrawn. Mirrors the artefact-side `set-schema-printer!-
            nil-falls-back-to-default` test on the public symbol."
    (rf/set-schema-printer! (fn [_] "::TRANSIENT::"))
    (is (= "::TRANSIENT::" (validator/run-printer :int)))
    (rf/set-schema-printer! nil)
    (is (= ":int" (validator/run-printer :int))
        "nil through the public surface falls back to default-edn-print")))

(deftest printer-set-via-public-api-map-arity-installs-printer
  (testing "rf2-pk8ur — the public rf/set-schema-validator! map arity
            installs a `:print` printer alongside `:validate` / `:explain`
            atomically. End-to-end pin of the documented one-call
            substitute-Malli boot pattern via the public surface."
    (let [v-fn (fn [_ _] true)
          e-fn (fn [_ _] {:explained true})
          p-fn (fn [_] "::FROM-PUBLIC-MAP-ARITY::")]
      (rf/set-schema-validator! {:validate v-fn :explain e-fn :print p-fn})
      (is (= v-fn @schemas/validator-fn))
      (is (= e-fn @schemas/explainer-fn))
      (is (= p-fn @schemas/printer-fn))
      (is (= "::FROM-PUBLIC-MAP-ARITY::" (validator/run-printer :int))
          "the printer installed via the map arity reaches the hot path"))))

;; ---- rf2-r2uh — :rf.schema/at-boundary interceptor ---------------------
;;
;; Per Spec 010 §Production builds — the boundary-validation interceptor
;; runs the handler's :schema check inline in production builds (where
;; dev-time validation has been elided). Re-uses the dev-time validator
;; seam (rf2-froe) so a substituted validator covers both surfaces.
;;
;; The interceptor's dev/prod gate is `re-frame.spec/dev-mode?` — a
;; private fn wrapping `interop/debug-enabled?`. The indirection lets
;; tests rebind the boundary's dev-vs-prod decision INDEPENDENTLY of
;; the trace surface's `interop/debug-enabled?` read, so a JVM test can
;; (a) keep `debug-enabled?` true so emit-error! / emit! actually fire
;; their bodies, and (b) flip `dev-mode?` to false so the boundary
;; takes its production validation branch.
;;
;; In genuine `:advanced` + `goog.DEBUG=false` production both flags
;; resolve to false together: the boundary validates inline, but the
;; trace surface elides — so the handler-skip is silent. The tests
;; below are JVM tests that decouple the two flags to make the
;; emission observable.

(deftest boundary-interceptor-passes-valid-event-through
  (testing "Per Spec 010 §Production builds (rf2-r2uh) — a valid event
            against the handler's :spec passes through, the handler runs."
    (let [calls (atom 0)]
      (rf/reg-event-fx :api/response
        {:schema [:cat [:= :api/response]
                     [:map [:status :int] [:body :string]]]}
        [rf/at-boundary]
        (fn [_ [_ payload]]
          (swap! calls inc)
          {:db {:last-response payload}}))
      (let [traces (atom [])]
        (rf/register-trace-listener! ::ok (fn [ev] (swap! traces conj ev)))
        ;; Production build path — flip the boundary's gate without
        ;; killing the trace surface. The router's step-1
        ;; validation also fires (debug-enabled? still true on JVM)
        ;; and passes for the well-typed payload, so the chain runs
        ;; and the boundary interceptor then validates again — both
        ;; passes silently.
        (with-redefs [spec/dev-mode? (constantly false)]
          (rf/dispatch-sync [:api/response {:status 200 :body "OK"}]))
        (rf/unregister-trace-listener! ::ok)
        (is (= 1 @calls)
            "handler ran exactly once for the well-typed payload")
        (is (empty? (filter #(= :rf.error/schema-validation-failure (:operation %))
                            @traces))
            "no validation-failure trace fired for the valid payload")))))

(deftest boundary-interceptor-skips-handler-on-invalid-event
  (testing "Per Spec 010 §Production builds (rf2-r2uh) — an invalid event
            against the handler's :spec causes the handler to be
            skipped. Under genuine `:advanced` + `goog.DEBUG=false` the
            router's step-1 validate-event! body elides and the
            boundary path is the only validation site; on the JVM
            test the router's step-1 also fires (debug-enabled? is
            true), but the handler-skip behaviour is what the spec
            promises in either path."
    (let [calls (atom 0)]
      (rf/reg-event-fx :api/response
        {:schema [:cat [:= :api/response]
                     [:map [:status :int] [:body :string]]]}
        [rf/at-boundary]
        (fn [_ [_ payload]]
          (swap! calls inc)
          {:db {:last-response payload}}))
      (with-redefs [spec/dev-mode? (constantly false)]
        (rf/dispatch-sync [:api/response {:status "not-an-int" :body 42}]))
      (is (= 0 @calls)
          "handler was skipped on the malformed payload"))))

(deftest boundary-interceptor-emits-failure-trace-with-source-tag
  (testing "Per Spec 010 L149 — the boundary failure trace flows through
            the same `:rf.error/schema-validation-failure :where :event`
            path as dev-mode step-1 failures, and carries `:source
            :boundary` so consumers can distinguish the boundary
            emission from the dev step-1 emission.

            We exercise the trace shape via direct interceptor
            invocation — the dispatch path's router-side step-1
            short-circuits the chain when the schema fails, so the
            boundary :before never reaches its emit body. Direct
            invocation isolates the boundary's emission for shape
            assertion."
    (rf/reg-event-fx :api/strict
      {:schema [:cat [:= :api/strict] :int]}
      [rf/at-boundary]
      (fn [_ _] {}))
    (let [traces (atom [])]
      (rf/register-trace-listener! ::tr (fn [ev] (swap! traces conj ev)))
      ;; dev-mode? false → boundary takes its prod branch; but
      ;; debug-enabled? stays true on the JVM so emit-error! actually
      ;; fires its body and the trace is observable.
      (with-redefs [spec/dev-mode? (constantly false)]
        (let [before (:before rf/at-boundary)]
          (before {:coeffects {:event [:api/strict "not-an-int"]}})))
      (rf/unregister-trace-listener! ::tr)
      (let [violations (filter #(= :rf.error/schema-validation-failure (:operation %))
                               @traces)]
        (is (= 1 (count violations))
            "exactly one schema-validation-failure trace fired from the boundary path")
        (let [v (first violations)]
          (is (= :event (-> v :tags :where))
              ":where is :event — same path as dev-mode step-1 failures (Spec 010 L149)")
          (is (= :api/strict (-> v :tags :event-id))
              ":event-id names the boundary-validated handler")
          (is (= :api/strict (-> v :tags :failing-id)))
          (is (= :api/strict (-> v :tags :schema-id)))
          (is (= :boundary (-> v :tags :source))
              ":source :boundary tags this as the boundary emission")
          (is (= [:api/strict "not-an-int"] (-> v :tags :received))
              ":received carries the failing event vector verbatim")
          (is (= [:api/strict "not-an-int"] (-> v :tags :value))
              ":value mirrors :received per Spec 010 §`:sensitive?`")
          (is (not (contains? (:tags v) :event))
              ":event slot is gone (rf2-4fbsd) — consumers reach for :received")
          (is (string? (-> v :tags :reason))
              ":reason carries a human-readable explanation per Spec 009 §Style rubric")
          (is (= :no-recovery (:recovery v))
              ":recovery is :no-recovery — handler is not invoked"))))))

(deftest boundary-interceptor-sets-skip-handler-on-context
  (testing "Per Spec 010 §Per-step recovery step 1 — the boundary
            interceptor's :before sets :rf/skip-handler? on the context
            when validation fails, so the handler-as-interceptor
            (events.cljc :rf/db-handler / :rf/fx-handler) short-circuits.

            The recovery is identical to the dev-mode step-1 path
            (validate-event! returning false), so the runtime's existing
            skip mechanism carries the boundary failure through without
            additional plumbing."
    (rf/reg-event-fx :api/strict
      {:schema [:cat [:= :api/strict] :int]}
      [rf/at-boundary]
      (fn [_ _] {}))
    ;; Direct invocation of the interceptor's :before fn — gives us a
    ;; deterministic surface for asserting the recovery contract
    ;; without the dispatch's other moving parts. Production-side path
    ;; (dev-mode? false); the boundary interceptor takes its prod
    ;; branch and validates inline.
    (with-redefs [spec/dev-mode? (constantly false)]
      (let [before    (:before rf/at-boundary)
            valid-ctx (before {:coeffects {:event [:api/strict 42]}})
            bad-ctx   (before {:coeffects {:event [:api/strict "not-an-int"]}})]
        (is (not (:rf/skip-handler? valid-ctx))
            ":rf/skip-handler? unset when the event conforms — handler will run")
        (is (true? (:rf/skip-handler? bad-ctx))
            ":rf/skip-handler? set when the event fails the schema — handler is skipped")))))

(deftest boundary-interceptor-honours-custom-validator
  (testing "Per Spec 010 §Boundary-validation seam (rf2-froe + rf2-r2uh) —
            the boundary interceptor routes through the registered
            validator the same way the dev-time hot path does. A
            substituted validator covers both surfaces with one
            registration."
    ;; Sentinel custom validator — passes the literal :good value,
    ;; fails everything else. Records every call. We use a
    ;; predicate that overrides Malli so we can observe routing
    ;; through the late-bind hook end-to-end.
    (let [validator-calls (atom 0)
          custom (fn [_schema value]
                   (swap! validator-calls inc)
                   (= value [:api/custom :good]))
          handler-calls (atom 0)]
      (rf/set-schema-validator! custom)
      (rf/reg-event-fx :api/custom
        {:schema :rf/any}                    ;; opaque to the custom validator
        [rf/at-boundary]
        (fn [_ _] (swap! handler-calls inc) {}))
      (with-redefs [spec/dev-mode? (constantly false)]
        ;; Direct :before invocation so we observe the boundary's
        ;; validator call path without the router-side step-1 also
        ;; firing the same custom validator (which would double-count
        ;; the calls).
        (let [before (:before rf/at-boundary)
              ok     (before {:coeffects {:event [:api/custom :good]}})
              bad    (before {:coeffects {:event [:api/custom :bad]}})]
          (is (not (:rf/skip-handler? ok))
              "custom validator passed — boundary did not set :rf/skip-handler?")
          (is (true? (:rf/skip-handler? bad))
              "custom validator failed — boundary set :rf/skip-handler?")
          (is (>= @validator-calls 2)
              "custom validator was invoked at least once per boundary check"))))))

(deftest boundary-interceptor-noop-when-validator-is-nil
  (testing "Per Spec 010 §Non-Malli validators — set-schema-validator! nil
            disables every validation surface, including the boundary
            interceptor. The handler runs even with a malformed payload."
    (rf/set-schema-validator! nil)
    (let [calls (atom 0)]
      (rf/reg-event-fx :api/disabled
        {:schema [:cat [:= :api/disabled] :int]}
        [rf/at-boundary]
        (fn [_ _] (swap! calls inc) {}))
      (let [traces (atom [])]
        (rf/register-trace-listener! ::nil (fn [ev] (swap! traces conj ev)))
        (with-redefs [spec/dev-mode? (constantly false)]
          (rf/dispatch-sync [:api/disabled "wildly-malformed"]))
        (rf/unregister-trace-listener! ::nil)
        (is (= 1 @calls)
            "handler ran — nil validator means no boundary check")
        (is (empty? (filter #(and (= :rf.error/schema-validation-failure (:operation %))
                                  (= :boundary (-> % :tags :source)))
                            @traces))
            "no boundary-emitted validation trace fires when the validator is nil")))))

(deftest boundary-interceptor-noop-in-dev-mode
  (testing "Per Spec 010 L145 — in dev builds (dev-mode? true), the
            boundary interceptor is a no-op. Dev-mode step-1
            validation in the router has already run; the boundary
            interceptor doesn't validate a second time."
    (let [calls (atom 0)]
      (rf/reg-event-fx :api/dev
        {:schema [:cat [:= :api/dev] :int]}
        [rf/at-boundary]
        (fn [_ _] (swap! calls inc) {}))
      (let [traces (atom [])]
        (rf/register-trace-listener! ::dev (fn [ev] (swap! traces conj ev)))
        ;; Dev mode (the JVM default). The router's step-1
        ;; validate-event! call fires for the malformed payload; the
        ;; boundary interceptor SHOULD NOT fire a second trace.
        (rf/dispatch-sync [:api/dev "not-an-int"])
        (rf/unregister-trace-listener! ::dev)
        (is (= 0 @calls)
            "handler skipped — but by the dev-mode step-1 path, not the boundary")
        (let [boundary-violations (filter #(and (= :rf.error/schema-validation-failure (:operation %))
                                                (= :boundary (-> % :tags :source)))
                                          @traces)]
          (is (empty? boundary-violations)
              "no boundary-tagged trace fired — only the dev-mode step-1 trace ran"))))))

(deftest boundary-interceptor-warns-when-handler-has-no-spec
  (testing "Per Spec 010 L147 — the boundary interceptor attached to a
            handler with no :spec emits :rf.warning/boundary-without-spec
            once and no-ops. The misconfiguration is flagged but the
            handler still runs (no schema means nothing to validate
            against)."
    (let [calls (atom 0)]
      (rf/reg-event-fx :api/no-spec
        ;; No metadata-map at all — the middle slot is the interceptor
        ;; vector. Handler carries no :spec.
        [rf/at-boundary]
        (fn [_ _] (swap! calls inc) {}))
      (let [traces (atom [])]
        (rf/register-trace-listener! ::nospec (fn [ev] (swap! traces conj ev)))
        (with-redefs [spec/dev-mode? (constantly false)]
          ;; First dispatch — emits the warning, handler runs.
          (rf/dispatch-sync [:api/no-spec :anything])
          ;; Second dispatch — warning is suppressed (warn-once),
          ;; handler runs.
          (rf/dispatch-sync [:api/no-spec :again]))
        (rf/unregister-trace-listener! ::nospec)
        (is (= 2 @calls)
            "handler ran for both dispatches — no schema, no skip")
        (let [warnings (filter #(= :rf.warning/boundary-without-spec (:operation %))
                               @traces)]
          (is (= 1 (count warnings))
              "exactly one boundary-without-spec warning fired — second dispatch was suppressed")
          (let [w (first warnings)]
            (is (= :api/no-spec (-> w :tags :event-id)))
            (is (string? (-> w :tags :reason)))
            (is (str/includes? (-> w :tags :reason) "no `:schema`"))))))))

;; ---- snapshot / restore / clear schemas-by-frame (rf2-6lka) --------------
;;
;; Per Spec 010 / rf2-h96i and schemas.cljc:748: the per-frame schema
;; registry is fixture-friendly via three test-support hooks:
;;
;;   (schemas/snapshot-schemas-by-frame)  ;; capture current state
;;   (schemas/clear-schemas-by-frame!)    ;; drop everything
;;   (schemas/restore-schemas-by-frame! s) ;; rehydrate from snapshot
;;
;; These are the fixture-style affordance the test-support reset-runtime
;; fixture relies on; a wire-up regression would surface only in user
;; tooling.

(deftest snapshot-restore-clear-round-trip
  (testing "snapshot → clear → restore round-trips the schemas-by-frame
            atom byte-for-byte and validation still works after restore"
    ;; Set up two frames and register a schema under each. Per-frame
    ;; isolation is the load-bearing contract.
    (rf/reg-frame :test.6lka/other {:doc "second frame for round-trip test"})
    (rf/reg-app-schema [:n] [:int])
    (rf/reg-app-schema [:label] [:string]
                       {:frame :test.6lka/other})

    ;; 1. Snapshot.
    (let [snap (schemas/snapshot-schemas-by-frame)]
      (is (map? snap) "snapshot is a map")
      (is (contains? snap :rf/default)
          "snapshot covers :rf/default")
      (is (contains? snap :test.6lka/other)
          "snapshot covers :test.6lka/other")
      ;; Schemas are keyed by their full path (a vector) inside the
      ;; per-frame map; the storage shape is {frame-id {path meta}}.
      (is (some? (get-in snap [:rf/default [:n]]))
          "snapshot retains the schema under [:rf/default [:n]]")
      (is (some? (get-in snap [:test.6lka/other [:label]]))
          "snapshot retains the schema under [:test.6lka/other [:label]]")

      ;; 2. Clear.
      (schemas/clear-schemas-by-frame!)
      (is (= {} @schemas/schemas-by-frame)
          "clear-schemas-by-frame! emptied the atom")

      ;; 3. Restore.
      (schemas/restore-schemas-by-frame! snap)
      (is (= snap @schemas/schemas-by-frame)
          "restore-schemas-by-frame! reproduces the atom byte-for-byte")

      ;; 4. Semantic faithfulness: validation against a restored
      ;;    schema fires exactly like it did before the round-trip.
      (let [traces (atom [])]
        (rf/register-trace-listener! ::rt (fn [ev] (swap! traces conj ev)))
        ;; A malformed value under [:n] on :rf/default — should fire.
        (schemas/validate-app-db! {:n "not-an-int"} :test.6lka/handler)
        (rf/unregister-trace-listener! ::rt)
        (let [violations (filter #(= :rf.error/schema-validation-failure
                                     (:operation %))
                                 @traces)]
          (is (= 1 (count violations))
              "post-restore validation fires for malformed value — round-trip is semantically faithful")
          (is (= [:n] (-> violations first :tags :path))
              ":path tag identifies the registered schema"))))))

(deftest clear-empties-and-leaves-schemas-by-frame-empty
  (testing "clear-schemas-by-frame! drops all per-frame entries"
    (rf/reg-app-schema [:a] [:int])
    (rf/reg-app-schema [:b] [:string])
    (is (seq @schemas/schemas-by-frame)
        "pre-clear: registry is populated")
    (schemas/clear-schemas-by-frame!)
    (is (empty? @schemas/schemas-by-frame)
        "post-clear: registry is empty")))

(deftest restore-replaces-not-merges
  (testing "restore-schemas-by-frame! REPLACES the atom (does not merge);
            schemas registered after the snapshot disappear on restore"
    ;; Capture an empty snapshot.
    (let [empty-snap (schemas/snapshot-schemas-by-frame)]
      (is (= {} empty-snap)
          "fresh atom is empty (reset-runtime-fixture cleared it)")
      ;; Now register some schemas.
      (rf/reg-app-schema [:transient] [:int])
      (is (seq @schemas/schemas-by-frame)
          "post-reg: schemas present")
      ;; Restore to the empty snapshot.
      (schemas/restore-schemas-by-frame! empty-snap)
      (is (= {} @schemas/schemas-by-frame)
          "restore replaced the atom — the transient schemas are gone, not merged"))))

;; ---- rf/reg-app-schemas (plural, rf2-jzs9) -------------------------------

(deftest reg-app-schemas-bulk-registers-map
  (testing "rf/reg-app-schemas registers every entry in the supplied {path -> schema} map"
    (rf/reg-app-schemas
      {[:auth]                  [:map [:user :string]]
       [:auth :token]           [:string]
       [:cart]                  [:map [:items [:vector :string]]]
       [:cart :items]           [:vector :string]})
    (is (= [:map [:user :string]]      (rf/app-schema-at [:auth])))
    (is (= [:string]                   (rf/app-schema-at [:auth :token])))
    (is (= [:map [:items [:vector :string]]] (rf/app-schema-at [:cart])))
    (is (= [:vector :string]           (rf/app-schema-at [:cart :items])))))

(deftest reg-app-schemas-returns-paths-registered
  (testing "rf/reg-app-schemas returns the vector of paths"
    (let [paths (rf/reg-app-schemas
                  {[:a] [:int]
                   [:b] [:int]
                   [:c] [:int]})]
      (is (= 3 (count paths)))
      (is (= #{[:a] [:b] [:c]} (set paths))
          "every input path appears in the returned vector"))))

(deftest reg-app-schemas-honours-frame-opt
  (testing "rf/reg-app-schemas applies the :frame opt to every entry"
    (rf/reg-frame :tenant/a {})
    (rf/reg-app-schemas
      {[:auth] [:map [:user :string]]
       [:cart] [:map [:items :any]]}
      {:frame :tenant/a})
    (is (= [:map [:user :string]]
           (rf/app-schema-at [:auth] {:frame :tenant/a})))
    (is (= [:map [:items :any]]
           (rf/app-schema-at [:cart] {:frame :tenant/a})))
    (is (nil? (rf/app-schema-at [:auth]))
        "the default frame did NOT receive any of the entries")))

(deftest reg-app-schemas-empty-map-no-op
  (testing "rf/reg-app-schemas on an empty map is a no-op and returns an empty vector"
    (let [paths (rf/reg-app-schemas {})]
      (is (= [] paths))
      (is (= {} (rf/app-schemas))
          "no schemas registered on the active frame"))))

;; ---- rf2-ieu0i — :schema canonical ---------------------------------------
;;
;; Per Mike's decision at rf2-ieu0i the framework collapses the dual
;; vocabulary (`:spec` / `schema` / `validation` / `violation`) under a
;; single name — `:schema`. Alpha posture: no back-compat shims, no
;; deprecation aliases. v1→v2 rename is recorded in MIGRATION §M-54.

(deftest boundary-interceptor-reads-schema-key
  (testing "rf2-ieu0i — `:rf.schema/at-boundary` interceptor reads the
            canonical `:schema` key."
    ;; Verify the interceptor id was renamed.
    (is (= :rf.schema/at-boundary (:id rf/at-boundary))
        ":id of the boundary interceptor is :rf.schema/at-boundary (rf2-ieu0i)")
    ;; Canonical :schema path — validation reads :schema.
    (rf/reg-event-fx :api/schema-key
      {:schema [:cat [:= :api/schema-key] :int]}
      [rf/at-boundary]
      (fn [_ _] {}))
    (with-redefs [spec/dev-mode? (constantly false)]
      (let [before  (:before rf/at-boundary)
            valid   (before {:coeffects {:event [:api/schema-key 7]}})
            invalid (before {:coeffects {:event [:api/schema-key "no"]}})]
        (is (not (:rf/skip-handler? valid))
            ":schema metadata + valid payload → handler proceeds")
        (is (true? (:rf/skip-handler? invalid))
            ":schema metadata + invalid payload → handler skipped")))))
