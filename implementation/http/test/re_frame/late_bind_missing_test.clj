(ns re-frame.late-bind-missing-test
  "Per rf2-5b6x — assert the documented missing-artefact error contract for
  the http artefact's `re-frame.core` re-exports.

  Each per-feature split (schemas / machines / routing / flows / http /
  ssr) raises a documented `:rf.error/<artefact>-artefact-missing`
  ex-info when a consumer calls a re-exported surface but the artefact
  is absent from the classpath. The contract was previously only
  documented in prose; this test pins the runtime behaviour against
  regression.

  Strategy: the http artefact IS on the classpath here (the test ns
  requires `re-frame.http-managed`, which fires the late-bind hook
  registrations at ns-load). To simulate the absent-artefact state we
  flip the relevant late-bind hook to nil for the duration of the
  assertion, then restore it in `finally`. Identical mechanism as the
  test would use on CLJS.

  Per Spec 002 §The late-bind seam, rf2-5kpd (http split), and the
  prose at the call sites in `re-frame.core`."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.core :as rf]
            [re-frame.late-bind :as late-bind]
            ;; Loading http-managed registers its late-bind hooks. The
            ;; `with-hook-as-nil` helper below re-establishes the absent
            ;; state by flipping the hook value at runtime; restoration
            ;; in `finally` keeps cross-test isolation intact.
            [re-frame.http-managed]))

(defn- with-hook-as-nil
  "Run `f` with the named late-bind hook set to nil. Restores the
  original value after `f` returns or throws."
  [hook-key f]
  (let [original (late-bind/get-fn hook-key)]
    (try
      (late-bind/set-fn! hook-key nil)
      (f)
      (finally
        (late-bind/set-fn! hook-key original)))))

(deftest install-managed-request-stubs-raises-when-http-artefact-missing
  (testing "rf/install-managed-request-stubs! raises :rf.error/http-artefact-missing when the :http/install-managed-request-stubs! hook is nil"
    (with-hook-as-nil :http/install-managed-request-stubs!
      (fn []
        (let [thrown (try (rf/install-managed-request-stubs! {})
                          nil
                          (catch clojure.lang.ExceptionInfo e e))]
          (is (some? thrown)
              "install-managed-request-stubs! throws when the http artefact is absent")
          (is (= ":rf.error/http-artefact-missing" (.getMessage thrown))
              "the documented error category appears in the message")
          (let [data (ex-data thrown)]
            (is (= 'rf/install-managed-request-stubs! (:where data))
                "ex-data carries :where = 'rf/install-managed-request-stubs!")
            (is (= :no-recovery (:recovery data))
                "ex-data carries :recovery = :no-recovery")
            (is (string? (:reason data))
                "ex-data carries :reason as a string")))))))

(deftest uninstall-managed-request-stubs-raises-when-http-artefact-missing
  (testing "rf/uninstall-managed-request-stubs! raises :rf.error/http-artefact-missing when the :http/uninstall-managed-request-stubs! hook is nil"
    (with-hook-as-nil :http/uninstall-managed-request-stubs!
      (fn []
        (let [thrown (try (rf/uninstall-managed-request-stubs!)
                          nil
                          (catch clojure.lang.ExceptionInfo e e))]
          (is (some? thrown)
              "uninstall-managed-request-stubs! throws when the http artefact is absent")
          (is (= ":rf.error/http-artefact-missing" (.getMessage thrown))
              "the documented error category appears in the message")
          (let [data (ex-data thrown)]
            (is (= 'rf/uninstall-managed-request-stubs! (:where data))
                "ex-data carries :where = 'rf/uninstall-managed-request-stubs!")
            (is (= :no-recovery (:recovery data))
                "ex-data carries :recovery = :no-recovery")))))))

(deftest with-managed-request-stubs-fn-raises-when-http-artefact-missing
  (testing "rf/with-managed-request-stubs* (fn form) raises :rf.error/http-artefact-missing when the :http/with-managed-request-stubs* hook is nil"
    (with-hook-as-nil :http/with-managed-request-stubs*
      (fn []
        (let [thrown (try (rf/with-managed-request-stubs* {} (fn [] :unused))
                          nil
                          (catch clojure.lang.ExceptionInfo e e))]
          (is (some? thrown)
              "with-managed-request-stubs* throws when the http artefact is absent")
          (is (= ":rf.error/http-artefact-missing" (.getMessage thrown))
              "the documented error category appears in the message")
          (let [data (ex-data thrown)]
            (is (= 'rf/with-managed-request-stubs* (:where data))
                "ex-data carries :where = 'rf/with-managed-request-stubs*")
            (is (= :no-recovery (:recovery data))
                "ex-data carries :recovery = :no-recovery")))))))
