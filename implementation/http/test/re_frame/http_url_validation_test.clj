(ns re-frame.http-url-validation-test
  "Spec 014 §Request envelope — the required `:url`, validated at dispatch time.

  `:url` is the only REQUIRED key in the request envelope. The
  `:rf.http/managed` fx validates it AFTER the `:before` interceptor chain
  runs, so a `:before` that sets the url (a base-URL-prefix interceptor) is
  honoured. A final `:url` that is missing / nil / a non-string / blank throws
  `:rf.error/http-bad-request` (Spec 009 §Error catalogue) rather than reaching
  the transport as an opaque `:rf.http/transport` failure."
  (:require [clojure.test :refer [are deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.http.handlers :as rf.http.handlers]
            [re-frame.http.managed :as rf.http.managed]
            [re-frame.test-support :as rf.test-support]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(defn- call-managed!
  "Invoke `:rf.http/managed` via the public handler with the given
  request map. Returns nil on success or the ex-info on a throw."
  [request]
  (try (rf.http.handlers/managed-handler {:frame :rf/default :event [:no-op]}
                                 {:request request :reply-to [:no-op]})
       nil
       (catch clojure.lang.ExceptionInfo e e)))

(defn- bad-request-throw?
  [ex offending-url]
  (and (some? ex)
       (let [data (ex-data ex)]
         (and (= :rf.error/http-bad-request (:rf.error/id data))
              (= :rf.http/managed          (:where data))
              (= :no-recovery              (:recovery data))
              ;; the offending url value rides at :url
              (= offending-url             (:url data))
              (string?                     (:reason data))))))

(deftest missing-nil-blank-or-non-string-url-rejected
  (testing "an absent, blank / whitespace-only or non-string `:url` throws
            :rf.error/http-bad-request at the dispatch site, carrying the
            offending value at `:url`"
    (are [request offending-url]
         (bad-request-throw? (call-managed! request) offending-url)
      {:method :get}  nil
      {:url "   "}    "   "
      {:url 42}       42)))

(deftest before-interceptor-may-set-the-url
  (testing "a request dispatched with NO url passes validation when a
            `:before` interceptor supplies one: validation runs on the
            post-chain request, not the raw args"
    (rf/reg-http-interceptor :base-url
      {:before (fn [ctx] (assoc-in ctx [:request :url] "http://localhost/from-interceptor"))})
    (let [ex (call-managed! {:method :get})]
      (is (not (and (some? ex)
                    (= :rf.error/http-bad-request (:rf.error/id (ex-data ex)))))
          "a :before-supplied url satisfies the required-:url contract"))))

(deftest before-interceptor-that-blanks-the-url-is-rejected
  (testing "a `:before` that nils the url of a request dispatched with a valid
            one is caught: the final request, not the raw args, is checked"
    (rf/reg-http-interceptor :url-eraser
      {:before (fn [ctx] (assoc-in ctx [:request :url] nil))})
    (is (bad-request-throw?
          (call-managed! {:method :get :url "http://localhost/x"})
          nil))))
