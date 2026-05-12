(ns re-frame.late-bind-missing-test
  "Per rf2-5b6x — assert the documented missing-artefact error contract for
  the flows artefact's `re-frame.core` re-exports.

  Each per-feature split (schemas / machines / routing / flows / http /
  ssr) raises a documented `:rf.error/<artefact>-artefact-missing`
  ex-info when a consumer calls a re-exported surface but the artefact
  is absent from the classpath. The contract was previously only
  documented in prose; this test pins the runtime behaviour against
  regression.

  Strategy: the flows artefact IS on the classpath here (the test ns
  requires `re-frame.flows`, which fires the late-bind hook
  registrations at ns-load). To simulate the absent-artefact state we
  flip the relevant late-bind hook to nil for the duration of the
  assertion, then restore it in `finally`. Identical mechanism as the
  test would use on CLJS.

  Per Spec 002 §The late-bind seam, rf2-tfw3 (flows split), and the
  prose at the call sites in `re-frame.core`."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.core :as rf]
            [re-frame.late-bind :as late-bind]
            ;; Loading flows registers its late-bind hooks. The
            ;; `with-hook-as-nil` helper below re-establishes the absent
            ;; state by flipping the hook value at runtime; restoration
            ;; in `finally` keeps cross-test isolation intact.
            [re-frame.flows]))

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

(deftest clear-flow-raises-when-flows-artefact-missing
  (testing "rf/clear-flow raises :rf.error/flows-artefact-missing when the :flows/clear-flow hook is nil"
    (with-hook-as-nil :flows/clear-flow
      (fn []
        (let [thrown (try (rf/clear-flow :no-such-flow)
                          nil
                          (catch clojure.lang.ExceptionInfo e e))]
          (is (some? thrown)
              "clear-flow throws when the flows artefact is absent")
          (is (= ":rf.error/flows-artefact-missing" (.getMessage thrown))
              "the documented error category appears in the message")
          (let [data (ex-data thrown)]
            (is (= 'rf/clear-flow (:where data))
                "ex-data carries :where = 'rf/clear-flow")
            (is (= :no-recovery (:recovery data))
                "ex-data carries :recovery = :no-recovery")
            (is (string? (:reason data))
                "ex-data carries :reason as a string")))))))

(deftest reg-flow-raises-when-flows-artefact-missing
  (testing "rf/reg-flow (macro) raises :rf.error/flows-artefact-missing when the :flows/reg-flow hook is nil"
    ;; The macro expands to a runtime late-bind lookup, so flipping the
    ;; hook at runtime is enough — we don't need to touch the test's
    ;; compile-time classpath.
    (with-hook-as-nil :flows/reg-flow
      (fn []
        (let [thrown (try (rf/reg-flow {:id     :late-bind-missing/probe
                                        :inputs []
                                        :output (fn [] 0)
                                        :path   [:probe]})
                          nil
                          (catch clojure.lang.ExceptionInfo e e))]
          (is (some? thrown)
              "reg-flow throws when the flows artefact is absent")
          (is (= ":rf.error/flows-artefact-missing" (.getMessage thrown))
              "the documented error category appears in the message")
          (let [data (ex-data thrown)]
            ;; Per rf2-hoiu the throw lives in `re-frame.core-flows/reg-flow`
            ;; — the sibling-namespace fn-form delegate the macro routes
            ;; through. Per rf2-j8icl the `:where` symbol is namespace-
            ;; qualified to the user-facing surface (`rf/reg-flow`).
            (is (= 'rf/reg-flow (:where data))
                "ex-data carries :where = 'rf/reg-flow")
            (is (= :no-recovery (:recovery data))
                "ex-data carries :recovery = :no-recovery")))))))
