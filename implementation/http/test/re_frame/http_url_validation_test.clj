(ns re-frame.http-url-validation-test
  "Spec 014 §Request envelope — required `:url` dispatch-time validation
  (rf2-93bck) — JVM tests.

  `:url` is the only REQUIRED key in the request envelope. The
  `:rf.http/managed` fx body validates it at fx-call time — AFTER the
  `:before` interceptor chain runs, so a `:before` that sets the url (a
  base-URL-prefix interceptor) is honoured. A request whose final `:url`
  is missing / nil / a non-string / a blank string throws an
  `:rf.error/http-bad-request` ex-info per Spec 009 §Error catalogue,
  rather than falling through to the transport where a nil url surfaces
  as an opaque `:rf.http/transport` failure (JVM `(URI/create nil)` NPE /
  CLJS `(js/fetch nil)` vendor error).

  This mirrors the dispatch-time guarding the optional keys already
  carry — `:retry :on` → `:rf.error/http-bad-retry-on` (see
  `http_retry_on_validation_test`); `:on-success` / `:on-failure` →
  `:rf.error/http-bad-reply-target`. Before rf2-93bck the required field
  had weaker guarding than the optional ones."
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
  (http-managed/clear-all-http-interceptors!)
  (rf/with-frame :rf/default
    (t)))

(use-fixtures :each reset-runtime)

;; ---- helpers ---------------------------------------------------------------

(defn- call-managed!
  "Invoke `:rf.http/managed` via the public handler with the given
  request map. Returns nil on success or the ex-info on a throw."
  [request]
  (try (handlers/managed-handler {:frame :rf/default :event [:no-op]}
                                 {:request request})
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

;; ---- rejection: missing / nil / blank / non-string url --------------------

(deftest missing-url-rejected
  (testing "rf2-93bck — a request with NO `:url` key throws
            :rf.error/http-bad-request at the dispatch site"
    (is (bad-request-throw? (call-managed! {:method :get}) nil))))

(deftest nil-url-rejected
  (testing "rf2-93bck — an explicit nil `:url` throws
            :rf.error/http-bad-request (previously fell through to a
            transport NPE / vendor TypeError)"
    (is (bad-request-throw? (call-managed! {:method :get :url nil}) nil))))

(deftest blank-url-rejected
  (testing "rf2-93bck — a blank / whitespace-only `:url` throws
            :rf.error/http-bad-request"
    (is (bad-request-throw? (call-managed! {:url ""}) ""))
    (is (bad-request-throw? (call-managed! {:url "   "}) "   "))))

(deftest non-string-url-rejected
  (testing "rf2-93bck — a non-string `:url` (keyword / number) throws
            :rf.error/http-bad-request"
    (is (bad-request-throw? (call-managed! {:url :not-a-string}) :not-a-string))
    (is (bad-request-throw? (call-managed! {:url 42}) 42))))

;; ---- pass-through: a valid url, and a :before that SETS the url -----------

(deftest valid-url-passes-validation
  (testing "rf2-93bck — a non-blank string `:url` passes the validator.
            (The `run-attempt!` that follows attempts the network request
            synchronously on JVM; we don't care about the eventual failure
            here, only that the validator did NOT throw
            :rf.error/http-bad-request.)"
    (let [ex (call-managed! {:method :get :url "http://localhost/x"})]
      (is (not (and (some? ex)
                    (= :rf.error/http-bad-request (:rf.error/id (ex-data ex)))))
          "a valid url must NOT trigger the bad-request guard"))))

(deftest before-interceptor-may-set-the-url
  (testing "rf2-93bck — the url is validated AFTER the `:before` chain, so
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
  (testing "rf2-93bck (complement) — if a `:before` interceptor REMOVES /
            blanks the url, validation (post-chain) catches it: the final
            request, not the raw args, is what's checked."
    (rf/reg-http-interceptor :url-eraser
      {:before (fn [ctx] (assoc-in ctx [:request :url] nil))})
    ;; Dispatched WITH a valid url, but the interceptor nils it out.
    (is (bad-request-throw?
          (call-managed! {:method :get :url "http://localhost/x"})
          nil))))
