(ns re-frame.http-retry-on-validation-test
  "Spec 014 §Closed-set `:retry :on` validation — JVM tests.

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
            [re-frame.registrar :as rf.registrar]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.http.handlers :as rf.http.handlers]
            [re-frame.http.managed :as rf.http.managed]
            [re-frame.http.test-support :as rf.http.test-support]
            [re-frame.test-support :as rf.test-support]))

;; ---- per-test reset --------------------------------------------------------

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

;; ---- closed-set assertion --------------------------------------------------

(deftest retryable-categories-is-the-closed-set
  (testing "handlers/retryable-categories pins the closed set
    documented in Spec 014 §Closed-set `:retry :on` validation"
    (is (= #{:rf.http/transport
             :rf.http/cors
             :rf.http/timeout
             :rf.http/http-4xx
             :rf.http/http-5xx}
           rf.http.handlers/retryable-categories))))

;; ---- helpers ---------------------------------------------------------------

(defn- call-managed!
  "Invoke `:rf.http/managed` via the public handler with the given
  `:retry` map. Returns nil on success or the ex-info on a throw."
  [retry]
  (let [args {:request {:method :get :url "http://localhost/x"}
              :retry   retry}]
    (try (rf.http.handlers/managed-handler {:frame :rf/default :event [:no-op]} args)
         nil
         (catch clojure.lang.ExceptionInfo e e))))

(defn- bad-retry-on-throw?
  [ex bad-members]
  (and (some? ex)
       (let [data (ex-data ex)]
         (and (= :rf.error/http-bad-retry-on (:rf.error/id data))
              (= :rf.http/managed         (:where data))
              (= :no-recovery             (:recovery data))
              (= rf.http.handlers/retryable-categories (:retryable-set data))
              (= bad-members               (:bad-members data))
              (string?                     (:reason data))))))

(defn- bad-retry-shape-throw?
  "A non-set `:on` throws `:rf.error/http-bad-retry-on` with
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
  (testing "`:rf.http/aborted` in `:retry :on` throws
    :rf.error/http-bad-retry-on. The dispatch site catches it, rather
    than leaving the retry-attempt path to reject it silently."
    (is (bad-retry-on-throw?
          (call-managed! {:on #{:rf.http/aborted} :max-attempts 3})
          #{:rf.http/aborted}))))

(deftest decode-failure-rejected
  (testing "`:rf.http/decode-failure` in `:retry :on` throws
    :rf.error/http-bad-retry-on. The next attempt would deterministically
    reproduce the same schema/parser failure — retrying buys nothing."
    (is (bad-retry-on-throw?
          (call-managed! {:on #{:rf.http/decode-failure} :max-attempts 3})
          #{:rf.http/decode-failure}))))

(deftest accept-failure-rejected
  (testing "`:rf.http/accept-failure` in `:retry :on` throws
    :rf.error/http-bad-retry-on. Domain-level retry of an `:accept`
    projection belongs to a state machine, not the transport-retry slot."
    (is (bad-retry-on-throw?
          (call-managed! {:on #{:rf.http/accept-failure} :max-attempts 3})
          #{:rf.http/accept-failure}))))

(deftest non-rf-http-keyword-rejected
  (testing "any keyword outside the `:rf.http/*` namespace
    is rejected; the set is closed."
    (is (bad-retry-on-throw?
          (call-managed! {:on #{:rf.error/something} :max-attempts 3})
          #{:rf.error/something}))))

(deftest mixed-good-and-bad-reports-only-bad
  (testing "when `:on` contains a mix, `:bad-members`
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

;; ---- rejection: non-set `:on` shapes --------------------------------------

(deftest keyword-on-rejected
  (testing "a bare keyword `:on` throws
    :rf.error/http-bad-retry-on with :bad-shape, not a
    raw IllegalArgumentException (\"Don't know how to create ISeq from:
    clojure.lang.Keyword\") from a `(remove …)` ISeq coercion."
    (let [ex (call-managed! {:on :rf.http/transport :max-attempts 3})]
      (is (bad-retry-shape-throw? ex :rf.http/transport))
      (is (not (instance? IllegalArgumentException ex))
          "must be the canonical :rf.error/http-bad-retry-on, not a raw IllegalArgumentException"))))

(deftest vector-on-rejected
  (testing "a vector `:on` (even with retryable members)
    throws :rf.error/http-bad-retry-on. A vector reaching run-attempt!
    would make `(contains? on-set kind)` test INDEX membership, not
    category membership — silently disabling retry."
    (let [bad [:rf.http/transport]
          ex  (call-managed! {:on bad :max-attempts 3})]
      (is (bad-retry-shape-throw? ex bad)))))

(deftest list-on-rejected
  (testing "a list `:on` is rejected; only a set is valid."
    (let [bad (list :rf.http/transport :rf.http/http-5xx)
          ex  (call-managed! {:on bad :max-attempts 3})]
      (is (bad-retry-shape-throw? ex bad)))))

(deftest string-on-rejected
  (testing "a string `:on` is rejected; only a set is valid."
    (let [ex (call-managed! {:on "rf.http/transport" :max-attempts 3})]
      (is (bad-retry-shape-throw? ex "rf.http/transport")))))

(deftest map-on-rejected
  (testing "a map `:on` is rejected; only a set is valid."
    (let [bad {:rf.http/transport true}
          ex  (call-managed! {:on bad :max-attempts 3})]
      (is (bad-retry-shape-throw? ex bad)))))

;; ---- pass-through: closed-set members and absences ------------------------

(deftest all-closed-set-members-pass-through
  (testing "every member of the closed retryable set passes
    validation. The `run-attempt!` that follows attempts the network
    request synchronously on JVM; we don't care about the eventual
    failure here, only that the validator did NOT throw."
    (doseq [k rf.http.handlers/retryable-categories]
      (let [ex (call-managed! {:on #{k} :max-attempts 1})]
        (is (not (and (some? ex)
                      (= :rf.error/http-bad-retry-on (:rf.error/id (ex-data ex)))))
            (str "single-member set #{" k "} must pass closed-set validation"))))))

(deftest full-closed-set-passes-through
  (testing "the entire closed set as `:on` passes."
    (let [ex (call-managed!
               {:on  rf.http.handlers/retryable-categories
                :max-attempts 1})]
      (is (not (and (some? ex)
                    (= :rf.error/http-bad-retry-on (:rf.error/id (ex-data ex)))))))))

(deftest absent-retry-passes-through
  (testing "no `:retry` key at all: the validator is a
    no-op. Most calls don't configure retry."
    (let [args {:request {:method :get :url "http://localhost/x"}}
          ex   (try (rf.http.handlers/managed-handler {:frame :rf/default :event [:no-op]} args)
                    nil
                    (catch clojure.lang.ExceptionInfo e e))]
      (is (not (and (some? ex)
                    (= :rf.error/http-bad-retry-on (:rf.error/id (ex-data ex)))))))))

(deftest empty-on-set-passes-through
  (testing "`:retry {:on #{} ...}`: the validator is a
    no-op. The transport loop's `(contains? on-set kind)` gate is
    false for every kind — this disables retry, same as omitting
    `:retry` entirely. No bad members to report."
    (let [ex (call-managed! {:on #{} :max-attempts 3})]
      (is (not (and (some? ex)
                    (= :rf.error/http-bad-retry-on (:rf.error/id (ex-data ex)))))))))

(deftest retry-without-on-passes-through
  (testing "`:retry {:max-attempts 3}` with no `:on` key:
    the validator is a no-op. Equivalent to no retry per the
    transport loop's `(or on #{})` defaulting."
    (let [ex (call-managed! {:max-attempts 3})]
      (is (not (and (some? ex)
                    (= :rf.error/http-bad-retry-on (:rf.error/id (ex-data ex)))))))))

(deftest explicit-nil-on-passes-through
  (testing "`:retry {:on nil :max-attempts 3}`: an explicit
    nil `:on` is an intentional no-retry shape, not a malformed value.
    It passes the shape check (the `(some? on)` guard) the same as an
    absent `:on`. Only present, non-nil, non-set values are rejected."
    (let [ex (call-managed! {:on nil :max-attempts 3})]
      (is (not (and (some? ex)
                    (= :rf.error/http-bad-retry-on (:rf.error/id (ex-data ex)))))))))

;; ---- the test-support stubs validate as the live fx does ------------------

(defn- call-stub!
  "Invoke a test-support stand-in for `:rf.http/managed` with `args`.
  Returns nil on success or the ex-info on a throw."
  [stub args]
  (try (stub {:frame :rf/default :event [:no-op]} args)
       nil
       (catch clojure.lang.ExceptionInfo e e)))

(deftest the-stubs-refuse-a-bad-retry-on-as-the-live-fx-does
  (testing "the route-map stub (`with-request-stubs`) and the canned stubs stand
            in for `:rf.http/managed` as `:fx-overrides` targets, so they refuse
            the same `:retry :on` with the same error — a test must not go green
            on a call site production rejects"
    (let [url        "http://localhost/x"
          scope-stub (rf.registrar/handler :fx :rf.test/managed-http-scope-stub)
          stubs      {[:get url] {:reply {:ok :stubbed}}}
          route-map  (fn [args]
                       (rf.http.test-support/with-request-stubs stubs
                         #(call-stub! scope-stub args)))]
      (doseq [[label stub] [["route-map stub"  route-map]
                            ["canned success"  #(call-stub! rf.http.test-support/canned-success-handler %)]
                            ["canned failure"  #(call-stub! rf.http.test-support/canned-failure-handler %)]]]
        (is (bad-retry-on-throw?
              (stub {:request {:method :get :url url}
                     :on-success nil
                     :retry   {:on #{:rf.http/aborted}}})
              #{:rf.http/aborted})
            (str label ": a non-retryable member is refused"))
        (is (bad-retry-shape-throw?
              (stub {:request {:method :get :url url}
                     :on-success nil
                     :retry   {:on [:rf.http/transport]}})
              [:rf.http/transport])
            (str label ": a non-set :on is refused"))))))
