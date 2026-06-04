(ns re-frame.http-abort-config-validation-test
  "Spec 014 §`:abort-signal` (external) — `:abort-signal` / `:request-id`
  mutual-exclusivity dispatch-time validation (rf2-culoe) — JVM tests.

  Spec 014 states `:abort-signal` and `:request-id` are mutually
  exclusive — \"pick one\". A request supplying BOTH wires two
  independent abort mechanisms (the external Fetch signal forwarded into
  the internal controller AND the `:request-id` supersede/managed-abort
  path) against one request; their simultaneous-abort behaviour is
  undefined-by-spec. The `:rf.http/managed` fx body now rejects that
  combination at the dispatch site with an `:rf.error/http-bad-abort-config`
  ex-info — matching the at-source guarding the rest of the surface
  carries (`:rf.error/http-bad-retry-on`, `:rf.error/http-bad-request`,
  `:rf.error/http-bad-reply-target`).

  Presence (not truthiness) is the test: an explicit `:request-id nil`
  opts out of internal abort, so only a present non-nil `:request-id`
  alongside a present non-nil `:abort-signal` is the conflicting shape."
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
  (flows/reset-flows!)
  (reset! schemas/schemas-by-frame {})
  (rf/init! plain-atom/adapter)
  (require 're-frame.routing :reload)
  (require 're-frame.ssr     :reload)
  (require 're-frame.http-managed :reload)
  (http-managed/clear-all-in-flight!)
  (http-managed/clear-all-http-interceptors!)
  (t))

(use-fixtures :each reset-runtime)

;; ---- helpers ---------------------------------------------------------------

(defn- call-managed!
  "Invoke `:rf.http/managed` via the public handler with the given full
  args-map. Returns nil on success / no-throw, or the ex-info on a throw."
  [args-map]
  (try (handlers/managed-handler {:frame :rf/default :event [:no-op]}
                                 args-map)
       nil
       (catch clojure.lang.ExceptionInfo e e)))

(defn- bad-abort-config-throw?
  [ex request-id]
  (and (some? ex)
       (= ":rf.error/http-bad-abort-config" (.getMessage ex))
       (let [data (ex-data ex)]
         (and (= :rf.error/http-bad-abort-config (:rf.error/id data))
              (= :rf.http/managed              (:where data))
              (= :no-recovery                  (:recovery data))
              (= request-id                    (:request-id data))
              (string?                         (:reason data))))))

;; a stand-in for an AbortController.signal handle — any non-nil value.
(def ^:private signal-stub (Object.))
(def ^:private base-request {:method :get :url "http://localhost/x"})

;; ---- rejection: BOTH present and non-nil ----------------------------------

(deftest both-abort-signal-and-request-id-rejected
  (testing "rf2-culoe — supplying BOTH a non-nil :abort-signal AND a
            non-nil :request-id throws :rf.error/http-bad-abort-config at
            the dispatch site"
    (is (bad-abort-config-throw?
          (call-managed! {:request      base-request
                          :request-id   :article/load
                          :abort-signal signal-stub})
          :article/load))))

(deftest both-with-vector-request-id-rejected
  (testing "rf2-culoe — the conflicting shape is rejected regardless of the
            :request-id value shape (a compound vector id here)"
    (is (bad-abort-config-throw?
          (call-managed! {:request      base-request
                          :request-id   [:articles :load "hello"]
                          :abort-signal signal-stub})
          [:articles :load "hello"]))))

;; ---- pass-through: exactly one (or neither) -------------------------------

(deftest only-request-id-passes
  (testing "rf2-culoe — :request-id alone (no :abort-signal) is the
            canonical internal-abort case and must NOT trigger the guard"
    (let [ex (call-managed! {:request    base-request
                             :request-id :article/load})]
      (is (not (and (some? ex)
                    (= ":rf.error/http-bad-abort-config" (.getMessage ex))))
          ":request-id alone must not be rejected"))))

(deftest only-abort-signal-passes
  (testing "rf2-culoe — :abort-signal alone (no :request-id) is the
            external-abort case and must NOT trigger the guard. (On JVM
            :abort-signal is a no-op with a degradation trace; here we only
            assert the abort-config guard did not fire.)"
    (let [ex (call-managed! {:request      base-request
                             :abort-signal signal-stub})]
      (is (not (and (some? ex)
                    (= ":rf.error/http-bad-abort-config" (.getMessage ex))))
          ":abort-signal alone must not be rejected"))))

(deftest neither-passes
  (testing "rf2-culoe — neither key present: no guard"
    (let [ex (call-managed! {:request base-request})]
      (is (not (and (some? ex)
                    (= ":rf.error/http-bad-abort-config" (.getMessage ex))))
          "a request with neither abort key must not be rejected"))))

;; ---- presence-not-truthiness boundary -------------------------------------

(deftest explicit-nil-request-id-with-signal-passes
  (testing "rf2-culoe — an explicit :request-id nil opts OUT of internal
            abort (the registry never indexes a nil id), so :abort-signal +
            (request-id nil) is NOT the conflicting shape and must pass"
    (let [ex (call-managed! {:request      base-request
                             :request-id   nil
                             :abort-signal signal-stub})]
      (is (not (and (some? ex)
                    (= ":rf.error/http-bad-abort-config" (.getMessage ex))))
          "an explicit nil :request-id alongside :abort-signal must pass"))))

(deftest explicit-nil-abort-signal-with-request-id-passes
  (testing "rf2-culoe — symmetric: an explicit :abort-signal nil alongside a
            real :request-id is not the conflicting shape"
    (let [ex (call-managed! {:request      base-request
                             :request-id   :article/load
                             :abort-signal nil})]
      (is (not (and (some? ex)
                    (= ":rf.error/http-bad-abort-config" (.getMessage ex))))
          "an explicit nil :abort-signal alongside :request-id must pass"))))
