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

  Counter-tests: every member of the closed set, plus absent `:on`, an
  explicit nil `:on`, and an empty `:on` set, all pass through cleanly. An
  absent `:retry` passes on every managed request that configures none."
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
  `:retry` map and no reply target, returning the ex-info it throws.
  `:retry` is validated first, so a `:retry` the validator accepts reaches
  the reply-target check and throws `:rf.error/http-no-reply-target`
  there; no request is issued either way."
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

(deftest non-retryable-members-rejected
  (testing "a keyword outside the closed retryable set in `:retry :on`
    throws :rf.error/http-bad-retry-on at the dispatch site, naming it in
    `:bad-members`. `:rf.http/aborted` is not a failure to retry;
    `:rf.http/decode-failure` would reproduce deterministically;
    `:accept-failure` retry belongs to a state machine; and any keyword
    outside `:rf.http/*` is outside the closed set."
    (doseq [member [:rf.http/aborted
                    :rf.http/decode-failure
                    :rf.http/accept-failure
                    :rf.error/something]]
      (testing (pr-str member)
        (is (bad-retry-on-throw?
              (call-managed! {:on #{member} :max-attempts 3})
              #{member}))))))

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

(deftest non-set-on-shapes-rejected
  (testing "a keyword, vector, list, string or map `:on` throws
    :rf.error/http-bad-retry-on carrying the value at `:bad-shape`; only a
    set is valid. A bare keyword reaching the membership check would throw a
    raw IllegalArgumentException (\"Don't know how to create ISeq from:
    clojure.lang.Keyword\") from a `(remove …)` ISeq coercion, which escapes
    `call-managed!` and errors this test. A vector reaching run-attempt!
    would make `(contains? on-set kind)` test INDEX membership, not
    category membership — silently disabling retry."
    (doseq [bad [:rf.http/transport
                 [:rf.http/transport]
                 (list :rf.http/transport :rf.http/http-5xx)
                 "rf.http/transport"
                 {:rf.http/transport true}]]
      (testing (pr-str bad)
        (is (bad-retry-shape-throw?
              (call-managed! {:on bad :max-attempts 3})
              bad))))))

;; ---- pass-through: closed-set members and absences ------------------------

(deftest all-closed-set-members-pass-through
  (testing "every member of the closed retryable set passes
    validation: the handler moves on to the reply-target check, so what
    it throws is anything but `:rf.error/http-bad-retry-on`."
    (doseq [k rf.http.handlers/retryable-categories]
      (let [ex (call-managed! {:on #{k} :max-attempts 1})]
        (is (not (and (some? ex)
                      (= :rf.error/http-bad-retry-on (:rf.error/id (ex-data ex)))))
            (str "single-member set #{" k "} must pass closed-set validation"))))))

(deftest retry-without-members-passes-through
  (testing "an empty `:on` set, a `:retry` with no `:on` key, and an
    explicit nil `:on` are all intentional no-retry shapes, not malformed
    values: the validator is a no-op for each, exactly as for an absent
    `:on` (the transport loop's `(or on #{})` defaulting). Only present,
    non-nil, non-set values are rejected."
    (doseq [retry [{:on #{} :max-attempts 3}
                   {:max-attempts 3}
                   {:on nil :max-attempts 3}]]
      (testing (pr-str retry)
        (let [ex (call-managed! retry)]
          (is (not (and (some? ex)
                        (= :rf.error/http-bad-retry-on (:rf.error/id (ex-data ex)))))))))))

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
