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
  requires `re-frame.http.managed`, which fires the late-bind hook
  registrations at ns-load). To simulate the absent-artefact state we
  flip the relevant late-bind hook to nil for the duration of the
  assertion, then restore it in `finally`. Identical mechanism as the
  test would use on CLJS.

  Per Spec 002 §The late-bind seam, rf2-5kpd (http split), and the
  prose at the call sites in `re-frame.core`.

  The stub family — `with-request-stubs` and the raw install/uninstall
  pair — is NOT a `re-frame.core` façade export (rf2-ntwwyt, rf2-kuky.13)
  and carries no hook / throw contract, so it is not exercised here.
  `reg-http-interceptor` is the http artefact's re-export that does."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.core :as rf]
            [re-frame.late-bind :as rf.late-bind]
            ;; Loading http-managed registers its production late-bind
            ;; hooks (middleware, registry). The `with-hook-as-nil`
            ;; helper below re-establishes the absent state by flipping
            ;; the hook value at runtime; restoration in `finally` keeps
            ;; cross-test isolation intact.
            [re-frame.http.managed]))

(defn- with-hook-as-nil
  "Run `f` with the named late-bind hook set to nil. Restores the
  original value after `f` returns or throws."
  [hook-key f]
  (let [original (rf.late-bind/get-fn hook-key)]
    (try
      (rf.late-bind/set-fn! hook-key nil)
      (f)
      (finally
        (rf.late-bind/set-fn! hook-key original)))))

;; The stub family (`with-request-stubs` plus the raw install/uninstall pair)
;; is not a `re-frame.core` façade export (rf2-ntwwyt, rf2-kuky.13) — it
;; carries no late-bind hook and no missing-artefact throw contract; tests
;; call all three directly on `re-frame.http.test-support`. The per-frame
;; interceptor re-exports are what retain the contract.

(deftest reg-http-interceptor-raises-when-http-artefact-missing
  (testing "rf/reg-http-interceptor raises :rf.error/http-artefact-missing when the :http/reg-http-interceptor hook is nil"
    (with-hook-as-nil :http/reg-http-interceptor
      (fn []
        (let [thrown (try (rf/reg-http-interceptor ::probe {:before identity})
                          nil
                          (catch clojure.lang.ExceptionInfo e e))]
          (is (some? thrown)
              "reg-http-interceptor throws when the http artefact is absent")
          ;; rf2-vvixub — message is the human :reason + trailing
          ;; [:rf.error/<id>] token; assert the token + canonical :rf.error/id,
          ;; not exact keyword-equality.
          (is (re-find #"\[:rf\.error/http-artefact-missing\]" (.getMessage thrown))
              "the message carries the [:rf.error/http-artefact-missing] token")
          (is (= :rf.error/http-artefact-missing (:rf.error/id (ex-data thrown)))
              "ex-data carries the canonical :rf.error/id discriminator")
          (let [data (ex-data thrown)]
            (is (= 'rf/reg-http-interceptor (:where data))
                "ex-data carries :where = 'rf/reg-http-interceptor")
            (is (= :no-recovery (:recovery data))
                "ex-data carries :recovery = :no-recovery")))))))
