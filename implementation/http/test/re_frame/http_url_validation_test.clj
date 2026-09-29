(ns re-frame.http-url-validation-test
  "Spec 014 §Request envelope — required `:url` dispatch-time validation
  — JVM tests.

  `:url` is the only REQUIRED key in the request envelope. The
  `:rf.http/managed` fx body validates it at fx-call time — AFTER the
  `:before` interceptor chain runs, so a `:before` that sets the url (a
  base-URL-prefix interceptor) is honoured. A request whose final `:url`
  is missing / nil / a non-string / a blank string throws an
  `:rf.error/http-bad-request` ex-info per Spec 009 §Error catalogue,
  rather than falling through to the transport where a nil url surfaces
  as an opaque `:rf.http/transport` failure (JVM `(URI/create nil)` NPE /
  CLJS `(js/fetch nil)` vendor error).

  This mirrors the dispatch-time guarding the optional keys
  carry — `:retry :on` → `:rf.error/http-bad-retry-on` (see
  `http_retry_on_validation_test`); `:on-success` / `:on-failure` →
  `:rf.error/http-bad-reply-target`."
  (:require [clojure.test :refer [are deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.http.handlers :as rf.http.handlers]
            [re-frame.http.managed :as rf.http.managed]
            [re-frame.test-support :as rf.test-support]))

;; ---- per-test reset --------------------------------------------------------

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

;; ---- helpers ---------------------------------------------------------------

(defn- call-managed!
  "Invoke `:rf.http/managed` via the public handler with the given
  request map. Returns nil on success or the ex-info on a throw.

  Supplies a `:reply-to` so the request satisfies the mandatory reply
  addressing and this suite isolates the `:url` guard."
  [request]
  (try (rf.http.handlers/managed-handler {:frame :rf/default :event [:no-op]}
                                 {:request request :reply-to [:no-op]})
       nil
       (catch clojure.lang.ExceptionInfo e e)))

(defn- bad-request-throw?
  [ex offending-url]
  (and (some? ex)
       (= :rf.error/http-bad-request (:rf.error/id (ex-data ex)))
       (let [data (ex-data ex)]
         (and (= :rf.error/http-bad-request (:rf.error/id data))
              (= :rf.http/managed          (:where data))
              (= :no-recovery              (:recovery data))
              ;; the offending url value rides at :url
              (= offending-url             (:url data))
              (string?                     (:reason data))))))

;; ---- rejection -------------------------------------------------------------

(deftest missing-nil-blank-or-non-string-url-rejected
  (testing "an absent, nil, blank / whitespace-only or non-string `:url`
            throws :rf.error/http-bad-request at the dispatch site
            (rather than falling through to a transport NPE / vendor
            TypeError), carrying the offending value at `:url`"
    (are [request offending-url]
         (bad-request-throw? (call-managed! request) offending-url)
      {:method :get}           nil
      {:method :get :url nil}  nil
      {:url ""}                ""
      {:url "   "}             "   "
      {:url :not-a-string}     :not-a-string
      {:url 42}                42)))

;; ---- pass-through: a :before that SETS the url ---------------------------

(deftest before-interceptor-may-set-the-url
  (testing "the url is validated AFTER the `:before` chain, so
            a `:before` interceptor that SETS the url (a base-URL-prefix
            interceptor) is honoured: a request dispatched with NO url
            passes validation because the interceptor produced one. This
            is why the validation runs post-chain, not on the raw args."
    (rf/reg-http-interceptor :base-url
      {:before (fn [ctx]
                 ;; The dispatched request carries no :url; the interceptor
                 ;; supplies it.
                 (assoc-in ctx [:request :url] "http://localhost/from-interceptor"))})
    (let [ex (call-managed! {:method :get})]
      (is (not (and (some? ex)
                    (= :rf.error/http-bad-request (:rf.error/id (ex-data ex)))))
          "a :before-supplied url satisfies the required-:url contract"))))

(deftest before-interceptor-that-blanks-the-url-is-rejected
  (testing "complement — if a `:before` interceptor REMOVES /
            blanks the url, validation (post-chain) catches it: the final
            request, not the raw args, is what's checked."
    (rf/reg-http-interceptor :url-eraser
      {:before (fn [ctx] (assoc-in ctx [:request :url] nil))})
    ;; Dispatched WITH a valid url, but the interceptor nils it out.
    (is (bad-request-throw?
          (call-managed! {:method :get :url "http://localhost/x"})
          nil))))
