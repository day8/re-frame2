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
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.registrar :as registrar]
            [re-frame.flows :as flows]
            [re-frame.interop :as interop]
            [re-frame.schemas :as schemas]
            [re-frame.trace :as trace]))

(defn- reset-runtime [test-fn]
  (registrar/clear-all!)
  (reset! frame/frames {})
  (reset! flows/flows {})
  (rf/init!)
  (test-fn))

(use-fixtures :each reset-runtime)

;; ---- elision toggle -------------------------------------------------------

(deftest app-db-validation-fires-when-debug-enabled
  (testing "validate-app-db! emits :rf.error/schema-validation-failure when debug-enabled? is true"
    (rf/reg-app-schema [:count] [:int])
    (let [traces (atom [])]
      (rf/register-trace-cb! ::dev (fn [ev] (swap! traces conj ev)))
      ;; Dev mode is the JVM default; validate-app-db! should walk the
      ;; registered schemas and emit on a malformed value.
      (with-redefs [interop/debug-enabled? true]
        (schemas/validate-app-db! {:count "not-an-int"} :test/handler))
      (rf/remove-trace-cb! ::dev)
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
      (rf/register-trace-cb! ::prod (fn [ev] (swap! traces conj ev)))
      ;; Production mode — the validation site elides; even with a
      ;; malformed value, no trace fires.
      (with-redefs [interop/debug-enabled? false]
        (schemas/validate-app-db! {:count "not-an-int"} :test/handler))
      (rf/remove-trace-cb! ::prod)
      (is (empty? (filter #(= :rf.error/schema-validation-failure
                              (:operation %))
                          @traces))
          "no schema-validation-failure trace when validation is elided"))))

(deftest well-typed-value-passes-silently
  (testing "validate-app-db! with a conforming value emits no trace"
    (rf/reg-app-schema [:count] [:int])
    (let [traces (atom [])]
      (rf/register-trace-cb! ::ok (fn [ev] (swap! traces conj ev)))
      (with-redefs [interop/debug-enabled? true]
        (schemas/validate-app-db! {:count 42} :test/handler))
      (rf/remove-trace-cb! ::ok)
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
      (rf/register-trace-cb! ::live (fn [ev] (swap! traces conj ev)))
      (rf/dispatch-sync [:n/init])
      (rf/dispatch-sync [:n/break])
      (rf/remove-trace-cb! ::live)
      (let [violations (filter #(= :rf.error/schema-validation-failure
                                   (:operation %))
                               @traces)]
        (is (= 1 (count violations))
            "the malformed :n/break commit fires exactly one schema trace")
        (is (= :n/break (-> violations first :tags :failing-id))
            ":failing-id names the handler whose commit prompted the failure")))))

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
                                   :event [:user/register {:age "no"}]}
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
