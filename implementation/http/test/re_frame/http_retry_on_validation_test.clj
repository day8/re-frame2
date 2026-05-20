(ns re-frame.http-retry-on-validation-test
  "Spec 014 §Closed-set `:retry :on` validation (rf2-apwkm) — JVM tests.

  The `:rf.http/managed` fx body validates `:retry :on` at fx-call
  time. The closed retryable set is

      #{:rf.http/transport :rf.http/cors :rf.http/timeout
        :rf.http/http-4xx :rf.http/http-5xx}

  Any non-retryable `:rf.http/*` category (`:rf.http/aborted`,
  `:rf.http/decode-failure`, `:rf.http/accept-failure`) or any keyword
  outside `:rf.http/*` throws an `:rf.error/http-bad-retry-on`
  ex-info — per Spec 009 §Error event catalogue. The throw fires
  BEFORE the middleware chain and BEFORE any attempt is issued.

  Counter-tests: every member of the closed set, plus absent `:retry`,
  absent `:on`, and an empty `:on` set, all pass through cleanly."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.schemas :as schemas]
            [re-frame.flows :as flows]
            [re-frame.registrar :as registrar]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.http-handlers :as handlers]
            [re-frame.http-managed :as http-managed]))

;; ---- per-test reset --------------------------------------------------------

(defn- reset-runtime [t]
  (registrar/clear-all!)
  (reset! frame/frames {})
  (reset! flows/flows {})
  (reset! schemas/schemas-by-frame {})
  (rf/init! plain-atom/adapter)
  (require 're-frame.routing :reload)
  (require 're-frame.ssr     :reload)
  (require 're-frame.http-managed :reload)
  (http-managed/clear-all-in-flight!)
  (t))

(use-fixtures :each reset-runtime)

;; ---- closed-set assertion --------------------------------------------------

(deftest retryable-categories-is-the-closed-set
  (testing "rf2-apwkm — handlers/retryable-categories pins the closed set
    documented in Spec 014 §Closed-set `:retry :on` validation"
    (is (= #{:rf.http/transport
             :rf.http/cors
             :rf.http/timeout
             :rf.http/http-4xx
             :rf.http/http-5xx}
           handlers/retryable-categories))))

;; ---- helpers ---------------------------------------------------------------

(defn- call-managed!
  "Invoke `:rf.http/managed` via the public handler with the given
  `:retry` map. Returns nil on success or the ex-info on a throw."
  [retry]
  (let [args {:request {:method :get :url "http://localhost/x"}
              :retry   retry}]
    (try (handlers/managed-handler {:frame :rf/default :event [:no-op]} args)
         nil
         (catch clojure.lang.ExceptionInfo e e))))

(defn- bad-retry-on-throw?
  [ex bad-members]
  (and (some? ex)
       (= ":rf.error/http-bad-retry-on" (.getMessage ex))
       (let [data (ex-data ex)]
         (and (= :rf.http/managed         (:where data))
              (= :no-recovery             (:recovery data))
              (= handlers/retryable-categories (:retryable-set data))
              (= bad-members               (:bad-members data))
              (string?                     (:reason data))))))

;; ---- rejection: non-retryable :rf.http/* categories -----------------------

(deftest aborted-rejected
  (testing "rf2-apwkm — `:rf.http/aborted` in `:retry :on` throws
    :rf.error/http-bad-retry-on. Previously the runtime silently
    rejected this only at retry-attempt time; the schema tighten
    catches it at the dispatch site."
    (is (bad-retry-on-throw?
          (call-managed! {:on #{:rf.http/aborted} :max-attempts 3})
          #{:rf.http/aborted}))))

(deftest decode-failure-rejected
  (testing "rf2-apwkm — `:rf.http/decode-failure` in `:retry :on` throws
    :rf.error/http-bad-retry-on. The next attempt would deterministically
    reproduce the same schema/parser failure — retrying buys nothing."
    (is (bad-retry-on-throw?
          (call-managed! {:on #{:rf.http/decode-failure} :max-attempts 3})
          #{:rf.http/decode-failure}))))

(deftest accept-failure-rejected
  (testing "rf2-apwkm — `:rf.http/accept-failure` in `:retry :on` throws
    :rf.error/http-bad-retry-on. Domain-level retry of an `:accept`
    projection belongs to a state machine, not the transport-retry slot."
    (is (bad-retry-on-throw?
          (call-managed! {:on #{:rf.http/accept-failure} :max-attempts 3})
          #{:rf.http/accept-failure}))))

(deftest non-rf-http-keyword-rejected
  (testing "rf2-apwkm — any keyword outside the `:rf.http/*` namespace
    is rejected; the set is closed."
    (is (bad-retry-on-throw?
          (call-managed! {:on #{:rf.error/something} :max-attempts 3})
          #{:rf.error/something}))))

(deftest mixed-good-and-bad-reports-only-bad
  (testing "rf2-apwkm — when `:on` contains a mix, `:bad-members`
    surfaces only the offending members; the good ones are not
    flagged."
    (let [ex (call-managed!
               {:on #{:rf.http/transport
                      :rf.http/http-5xx
                      :rf.http/aborted
                      :rf.http/decode-failure}
                :max-attempts 3})]
      (is (bad-retry-on-throw? ex
            #{:rf.http/aborted :rf.http/decode-failure})))))

;; ---- pass-through: closed-set members and absences ------------------------

(deftest all-closed-set-members-pass-through
  (testing "rf2-apwkm — every member of the closed retryable set passes
    validation. The `run-attempt!` that follows attempts the network
    request synchronously on JVM; we don't care about the eventual
    failure here, only that the validator did NOT throw."
    (doseq [k handlers/retryable-categories]
      (let [ex (call-managed! {:on #{k} :max-attempts 1})]
        (is (not (and (some? ex)
                      (= ":rf.error/http-bad-retry-on" (.getMessage ex))))
            (str "single-member set #{" k "} must pass closed-set validation"))))))

(deftest full-closed-set-passes-through
  (testing "rf2-apwkm — the entire closed set as `:on` passes."
    (let [ex (call-managed!
               {:on  handlers/retryable-categories
                :max-attempts 1})]
      (is (not (and (some? ex)
                    (= ":rf.error/http-bad-retry-on" (.getMessage ex))))))))

(deftest absent-retry-passes-through
  (testing "rf2-apwkm — no `:retry` key at all: the validator is a
    no-op. Most calls don't configure retry."
    (let [args {:request {:method :get :url "http://localhost/x"}}
          ex   (try (handlers/managed-handler {:frame :rf/default :event [:no-op]} args)
                    nil
                    (catch clojure.lang.ExceptionInfo e e))]
      (is (not (and (some? ex)
                    (= ":rf.error/http-bad-retry-on" (.getMessage ex))))))))

(deftest empty-on-set-passes-through
  (testing "rf2-apwkm — `:retry {:on #{} ...}`: the validator is a
    no-op. The transport loop's `(contains? on-set kind)` gate is
    false for every kind — this disables retry, same as omitting
    `:retry` entirely. No bad members to report."
    (let [ex (call-managed! {:on #{} :max-attempts 3})]
      (is (not (and (some? ex)
                    (= ":rf.error/http-bad-retry-on" (.getMessage ex))))))))

(deftest retry-without-on-passes-through
  (testing "rf2-apwkm — `:retry {:max-attempts 3}` with no `:on` key:
    the validator is a no-op. Equivalent to no retry per the
    transport loop's `(or on #{})` defaulting."
    (let [ex (call-managed! {:max-attempts 3})]
      (is (not (and (some? ex)
                    (= ":rf.error/http-bad-retry-on" (.getMessage ex))))))))
