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
            [re-frame.http.handlers :as handlers]
            [re-frame.http.managed :as http-managed]))

;; ---- per-test reset --------------------------------------------------------

(defn- reset-runtime [t]
  (registrar/clear-all!)
  (reset! frame/frames {})
  (flows/reset-flows!)
  (schemas/clear-schemas-by-frame!)
  (rf/init! plain-atom/adapter)
  ;; EP-0002 (rf2-nn0jqa): `init!` no longer synthesises `:rf/default`,
  ;; and the managed-HTTP / machine / routing fxs now require a carried
  ;; frame stamp. This suite exercises the ambient dispatch path against
  ;; a single conventional app frame, so register `:rf/default` explicitly
  ;; and pin it as the established scope for the whole body via with-frame.
  (frame/ensure-default-frame!)
  (require 're-frame.routing :reload)
  (require 're-frame.ssr     :reload)
  (require 're-frame.http.managed :reload)
  (http-managed/clear-all-in-flight!)
  (rf/with-frame :rf/default
    (t)))

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
       (let [data (ex-data ex)]
         (and (= :rf.error/http-bad-retry-on (:rf.error/id data))
              (= :rf.http/managed         (:where data))
              (= :no-recovery             (:recovery data))
              (= handlers/retryable-categories (:retryable-set data))
              (= bad-members               (:bad-members data))
              (string?                     (:reason data))))))

(defn- bad-retry-shape-throw?
  "rf2-4zldh — a non-set `:on` throws `:rf.error/http-bad-retry-on` with
  `:bad-shape` (the offending value) rather than `:bad-members`. The
  ex-data must carry the canonical error id, the offending value, and a
  string `:reason`."
  [ex bad-shape]
  (and (some? ex)
       (let [data (ex-data ex)]
         (and (= :rf.error/http-bad-retry-on (:rf.error/id data))
              (= :rf.http/managed            (:where data))
              (= :no-recovery                (:recovery data))
              (= bad-shape                   (:bad-shape data))
              (contains? data :bad-type)
              (string?                       (:reason data))))))

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

;; ---- rejection: non-set `:on` shapes (rf2-4zldh) --------------------------

(deftest keyword-on-rejected
  (testing "rf2-4zldh — a bare keyword `:on` throws
    :rf.error/http-bad-retry-on with :bad-shape. Previously this threw a
    raw IllegalArgumentException (\"Don't know how to create ISeq from:
    clojure.lang.Keyword\") from the `(remove …)` ISeq coercion."
    (let [ex (call-managed! {:on :rf.http/transport :max-attempts 3})]
      (is (bad-retry-shape-throw? ex :rf.http/transport))
      (is (not (instance? IllegalArgumentException ex))
          "must be the canonical :rf.error/http-bad-retry-on, not a raw IllegalArgumentException"))))

(deftest vector-on-rejected
  (testing "rf2-4zldh — a vector `:on` (even with retryable members)
    throws :rf.error/http-bad-retry-on. Previously a vector reached
    run-attempt! unchanged, where `(contains? on-set kind)` tests INDEX
    membership not category membership — silently disabling retry."
    (let [bad [:rf.http/transport]
          ex  (call-managed! {:on bad :max-attempts 3})]
      (is (bad-retry-shape-throw? ex bad)))))

(deftest list-on-rejected
  (testing "rf2-4zldh — a list `:on` is rejected; only a set is valid."
    (let [bad (list :rf.http/transport :rf.http/http-5xx)
          ex  (call-managed! {:on bad :max-attempts 3})]
      (is (bad-retry-shape-throw? ex bad)))))

(deftest string-on-rejected
  (testing "rf2-4zldh — a string `:on` is rejected; only a set is valid."
    (let [ex (call-managed! {:on "rf.http/transport" :max-attempts 3})]
      (is (bad-retry-shape-throw? ex "rf.http/transport")))))

(deftest map-on-rejected
  (testing "rf2-4zldh — a map `:on` is rejected; only a set is valid."
    (let [bad {:rf.http/transport true}
          ex  (call-managed! {:on bad :max-attempts 3})]
      (is (bad-retry-shape-throw? ex bad)))))

;; ---- pass-through: closed-set members and absences ------------------------

(deftest all-closed-set-members-pass-through
  (testing "rf2-apwkm — every member of the closed retryable set passes
    validation. The `run-attempt!` that follows attempts the network
    request synchronously on JVM; we don't care about the eventual
    failure here, only that the validator did NOT throw."
    (doseq [k handlers/retryable-categories]
      (let [ex (call-managed! {:on #{k} :max-attempts 1})]
        (is (not (and (some? ex)
                      (= :rf.error/http-bad-retry-on (:rf.error/id (ex-data ex)))))
            (str "single-member set #{" k "} must pass closed-set validation"))))))

(deftest full-closed-set-passes-through
  (testing "rf2-apwkm — the entire closed set as `:on` passes."
    (let [ex (call-managed!
               {:on  handlers/retryable-categories
                :max-attempts 1})]
      (is (not (and (some? ex)
                    (= :rf.error/http-bad-retry-on (:rf.error/id (ex-data ex)))))))))

(deftest absent-retry-passes-through
  (testing "rf2-apwkm — no `:retry` key at all: the validator is a
    no-op. Most calls don't configure retry."
    (let [args {:request {:method :get :url "http://localhost/x"}}
          ex   (try (handlers/managed-handler {:frame :rf/default :event [:no-op]} args)
                    nil
                    (catch clojure.lang.ExceptionInfo e e))]
      (is (not (and (some? ex)
                    (= :rf.error/http-bad-retry-on (:rf.error/id (ex-data ex)))))))))

(deftest empty-on-set-passes-through
  (testing "rf2-apwkm — `:retry {:on #{} ...}`: the validator is a
    no-op. The transport loop's `(contains? on-set kind)` gate is
    false for every kind — this disables retry, same as omitting
    `:retry` entirely. No bad members to report."
    (let [ex (call-managed! {:on #{} :max-attempts 3})]
      (is (not (and (some? ex)
                    (= :rf.error/http-bad-retry-on (:rf.error/id (ex-data ex)))))))))

(deftest retry-without-on-passes-through
  (testing "rf2-apwkm — `:retry {:max-attempts 3}` with no `:on` key:
    the validator is a no-op. Equivalent to no retry per the
    transport loop's `(or on #{})` defaulting."
    (let [ex (call-managed! {:max-attempts 3})]
      (is (not (and (some? ex)
                    (= :rf.error/http-bad-retry-on (:rf.error/id (ex-data ex)))))))))

(deftest explicit-nil-on-passes-through
  (testing "rf2-4zldh — `:retry {:on nil :max-attempts 3}`: an explicit
    nil `:on` is an intentional no-retry shape, not a malformed value.
    It passes the shape check (the `(some? on)` guard) the same as an
    absent `:on`. Only present, non-nil, non-set values are rejected."
    (let [ex (call-managed! {:on nil :max-attempts 3})]
      (is (not (and (some? ex)
                    (= :rf.error/http-bad-retry-on (:rf.error/id (ex-data ex)))))))))
