(ns re-frame.http-retry-on-validation-test
  "Spec 014 §Closed-set `:retry :on` validation. `:rf.http/managed` validates
  `:retry :on` at fx-call time, before the middleware chain and before any
  attempt: a member outside the closed retryable set, or an `:on` that is not
  a set, throws `:rf.error/http-bad-retry-on`. The test-support stand-ins
  refuse the same args the same way."
  (:require [clojure.test :refer [are deftest is use-fixtures]]
            [re-frame.registrar :as rf.registrar]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.http.handlers :as rf.http.handlers]
            [re-frame.http.managed :as rf.http.managed]
            [re-frame.http.test-support :as rf.http.test-support]
            [re-frame.test-support :as rf.test-support]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(def ^:private closed-set
  #{:rf.http/transport :rf.http/cors :rf.http/timeout :rf.http/http-4xx :rf.http/http-5xx})

(deftest retryable-categories-is-the-closed-set
  (is (= closed-set rf.http.handlers/retryable-categories)))

(defn- call-managed!
  "Invoke the live handler with `retry` and no reply target; returns the
  thrown ex-data. A `:retry` the validator accepts reaches the reply-target
  check and throws `:rf.error/http-no-reply-target` there instead."
  [retry]
  (try (rf.http.handlers/managed-handler {:frame :rf/default :event [:no-op]}
                                         {:request {:method :get :url "http://localhost/x"}
                                          :retry   retry})
       nil
       (catch clojure.lang.ExceptionInfo e (ex-data e))))

(defn- bad-members [data]
  (select-keys data [:rf.error/id :where :recovery :retryable-set :bad-members]))

(defn- expected-bad-members [members]
  {:rf.error/id :rf.error/http-bad-retry-on :where :rf.http/managed :recovery :no-recovery
   :retryable-set closed-set :bad-members members})

(deftest mixed-good-and-bad-reports-only-bad
  (is (= (expected-bad-members #{:rf.http/aborted :rf.http/decode-failure})
         (bad-members (call-managed! {:on #{:rf.http/transport :rf.http/http-5xx
                                             :rf.http/aborted :rf.http/decode-failure}
                                      :max-attempts 3})))))

(deftest non-set-on-shapes-rejected
  ;; A bare keyword would otherwise throw a raw ISeq coercion error, and a
  ;; vector would make `contains?` test index membership, silently disabling retry.
  (are [on] (= {:rf.error/id :rf.error/http-bad-retry-on :where :rf.http/managed :recovery :no-recovery
                :bad-shape on :bad-type (type on)}
               (select-keys (call-managed! {:on on :max-attempts 3})
                            [:rf.error/id :where :recovery :bad-shape :bad-type]))
    :rf.http/transport
    [:rf.http/transport]))

(deftest retry-without-members-passes-through
  ;; The whole closed set, an empty set, no :on and a nil :on all pass.
  (are [retry] (not= :rf.error/http-bad-retry-on (:rf.error/id (call-managed! retry)))
    {:on closed-set :max-attempts 3}
    {:on #{} :max-attempts 3}
    {:max-attempts 3}
    {:on nil :max-attempts 3}))

(deftest the-stubs-refuse-a-bad-retry-on-as-the-live-fx-does
  ;; The stand-ins replace :rf.http/managed as :fx-overrides targets, so a test
  ;; must not go green on a call site production rejects.
  (let [url        "http://localhost/x"
        args       {:request {:method :get :url url} :on-success nil :retry {:on #{:rf.http/aborted}}}
        call-stub  (fn [stub] (try (stub {:frame :rf/default :event [:no-op]} args) nil
                                   (catch clojure.lang.ExceptionInfo e (ex-data e))))
        scope-stub (rf.registrar/handler :fx :rf.test/managed-http-scope-stub)]
    (are [data] (= (expected-bad-members #{:rf.http/aborted}) (bad-members data))
      (rf.http.test-support/with-request-stubs {[:get url] {:reply {:ok :stubbed}}}
        #(call-stub scope-stub))
      (call-stub rf.http.test-support/canned-success-handler)
      (call-stub rf.http.test-support/canned-failure-handler))))
