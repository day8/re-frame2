(ns re-frame.http-jvm-relative-url-test
  "rf2-4y04lq — JVM HTTP transport: relative `:url` contract.

  The browser Fetch transport resolves a relative `:url` (`\"/api/items\"`)
  against the page's document base; the JVM `java.net.http.HttpClient`
  transport has no equivalent base. Left unguarded, `jvm-build-request`
  handed the relative url straight to `HttpRequest/newBuilder`, which threw
  the JDK's own `IllegalArgumentException: URI with undefined scheme` — a
  correct-but-unhelpful message that names neither the offending url nor
  the fix, and (via `classify-jvm-error`'s catch-all) still surfaced as an
  ordinary `:rf.http/transport` failure, just an opaque one.

  Per Spec 014 §JVM transport — absolute URLs required, a relative `:url`
  reaching the JVM transport is now rejected AT REQUEST-CONSTRUCTION TIME
  with a clear message naming the offending url and the fix (supply an
  absolute URL, or rewrite it via a `:before` HTTP interceptor). The
  failure category is unchanged — still `:rf.http/transport`, the existing
  catch-all — so no new `:rf.error/*` id or failure kind was needed.

  Two layers of coverage:
   - a unit test calling `jvm-build-request` directly (mirrors the
     `http-transport-security-test` direct-call idiom) pins the throw
     message contract precisely, plus the absolute-url non-regression
     complement; and
   - an end-to-end dispatch test (mirrors `http-managed-test`'s
     `jvm-transport-failure`) proves a REAL (non-stub, non-canned)
     `:rf.http/managed` request with a relative url resolves to a
     `:status :error` / `:rf.http/transport` reply carrying the clear
     message, rather than hanging, crashing, or silently mis-issuing the
     request against the wrong host."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.flows :as flows]
            [re-frame.frame :as frame]
            [re-frame.http.managed :as http-managed]
            [re-frame.http.transport-jvm :as transport-jvm]
            [re-frame.registrar :as registrar]
            [re-frame.schemas :as schemas]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]))

;; ---- per-test reset --------------------------------------------------------

(defn- reset-runtime [t]
  (registrar/clear-all!)
  (reset! frame/frames {})
  (flows/reset-flows!)
  (schemas/clear-schemas-by-frame!)
  (rf/init! plain-atom/adapter)
  ;; EP-0002 (rf2-nn0jqa): `init!` no longer synthesises `:rf/default`,
  ;; and the managed-HTTP fx now requires a carried frame stamp. Register
  ;; `:rf/default` explicitly and pin it as the established scope.
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

(defn- await-reply!
  "Wait up to `timeout-ms` for `(pred db)` to be truthy against
  `(rf/app-db-value :rf/default)`. Mirrors `http-managed-test`'s
  `await-reply!` (rf2-fun38's `poll-until` idiom)."
  ([pred] (await-reply! pred 5000))
  ([pred timeout-ms]
   (test-support/poll-until
     #(let [db (rf/app-db-value :rf/default)] (when (pred db) db))
     {:timeout-ms timeout-ms :label "jvm relative-url reply"})))

;; ---- 1. unit — jvm-build-request rejects a relative url --------------------

(deftest relative-url-throws-clear-ex-info
  (testing "rf2-4y04lq — a relative :url throws an ex-info naming the url
            and the absolute-url requirement, NOT the JDK's opaque
            \"URI with undefined scheme\" message"
    (let [ex (try (transport-jvm/jvm-build-request
                    {:method :get :url "/api/items"})
                  nil
                  (catch clojure.lang.ExceptionInfo e e))]
      (is (some? ex) "a relative url must throw")
      (is (str/includes? (.getMessage ^Throwable ex) "absolute")
          "the message explains the absolute-url requirement")
      (is (str/includes? (.getMessage ^Throwable ex) "/api/items")
          "the message names the offending url")
      (is (= "/api/items" (:url (ex-data ex)))
          "the offending url rides ex-data for programmatic access"))))

(deftest scheme-relative-url-also-rejected
  (testing "rf2-4y04lq — a protocol-relative url (`//host/path`, no scheme)
            is ALSO relative per `URI/isAbsolute` and is rejected the same
            way as a path-only relative url"
    (let [ex (try (transport-jvm/jvm-build-request
                    {:method :get :url "//example.invalid/api/items"})
                  nil
                  (catch clojure.lang.ExceptionInfo e e))]
      (is (some? ex))
      (is (str/includes? (.getMessage ^Throwable ex) "absolute")))))

(deftest absolute-url-does-not-throw
  (testing "rf2-4y04lq (complement) — an absolute :url builds normally,
            unaffected by the new guard"
    (let [req (transport-jvm/jvm-build-request
                {:method :get :url "https://example.invalid/api/items"})]
      (is (some? req)))))

;; ---- 2. end-to-end — a real (non-stub) dispatch classifies cleanly --------

(deftest real-dispatch-with-relative-url-fails-clearly
  (testing "rf2-4y04lq — a real (non-stub, non-canned) :rf.http/managed
            dispatch with a relative :url resolves to a :status :error /
            :rf.http/transport reply carrying the clear message. The
            request never reaches a live socket; the failure names the fix
            rather than surfacing the JDK's opaque rejection."
    (rf/reg-event :jvm-relative/load
      (fn [{:keys [db]} [_ msg]]
        (if-let [reply (:rf/reply msg)]
          {:db (assoc db :reply reply)}
          {:fx [[:rf.http/managed
                 {:request {:method :get :url "/api/items"}
                  :decode  :json}]]})))
    (rf/dispatch-sync [:jvm-relative/load])
    (let [db (await-reply! #(some? (:reply %)) 5000)]
      (is (= :error (get-in db [:reply :status])))
      (is (= :rf.http/transport (get-in db [:reply :error :kind])))
      (is (str/includes? (get-in db [:reply :error :message]) "absolute")
          "the delivered failure message explains the absolute-url requirement")
      (is (not (str/includes? (get-in db [:reply :error :message]) "undefined scheme"))
          "the delivered failure message is the clear guard message, not the
           JDK's raw \"URI with undefined scheme\" text"))))
