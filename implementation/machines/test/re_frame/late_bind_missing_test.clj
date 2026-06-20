(ns re-frame.late-bind-missing-test
  "Assert the documented missing-artefact error contract for the machines
  artefact's `re-frame.core` re-exports.

  Each per-feature split (schemas / machines / routing / flows / http /
  ssr) raises a documented `:rf.error/<artefact>-artefact-missing`
  ex-info when a consumer calls a re-exported surface but the artefact
  is absent from the classpath. This test pins that runtime behaviour
  against regression.

  Strategy: the machines artefact IS on the classpath here (the test ns
  requires `re-frame.machines`, which fires the late-bind hook
  registrations at ns-load). To simulate the absent-artefact state we
  flip the relevant late-bind hook to nil for the duration of the
  assertion, then restore it in `finally`. Identical mechanism as the
  test would use on CLJS.

  Per Spec 002 §The late-bind seam and the prose at the call sites in
  `re-frame.core`.

  The ONLY machine surface on the `re-frame.core` façade is the
  `reg-machine` / `defmachine` registration MACRO (source-coord capture;
  no owned-ns macro form), so the façade missing-artefact contract is
  tested here for `reg-machine` only. The fn surfaces (`reg-machine*`,
  `make-machine-handler`, `machine-transition`) and the read-only query
  surfaces (`machines`, `machine-meta`, `machine-by-system-id`) live on
  `re-frame.machines` (the owned namespace; requiring it means the
  artefact is present, so the façade artefact-missing contract does not
  apply to them)."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.core :as rf]
            [re-frame.late-bind :as late-bind]
            ;; Loading machines registers its late-bind hooks. The
            ;; `with-hook-as-nil` helper below re-establishes the absent
            ;; state by flipping the hook value at runtime; restoration
            ;; in `finally` keeps cross-test isolation intact.
            [re-frame.machines]))

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


(deftest reg-machine-macro-raises-when-machines-artefact-missing
  (testing "rf/reg-machine (macro) raises :rf.error/machines-artefact-missing when the :machines/reg-machine hook is nil"
    (with-hook-as-nil :machines/reg-machine
      (fn []
        (let [thrown (try (rf/reg-machine :probe/macro-machine
                                          {:initial :idle
                                           :states  {:idle {}}})
                          nil
                          (catch clojure.lang.ExceptionInfo e e))]
          (is (some? thrown)
              "reg-machine throws when the machines artefact is absent")
          ;; The message is the human :reason + trailing
          ;; [:rf.error/<id>] token; assert the token + canonical :rf.error/id,
          ;; not exact keyword-equality.
          (is (re-find #"\[:rf\.error/machines-artefact-missing\]" (.getMessage thrown))
              "the message carries the [:rf.error/machines-artefact-missing] token")
          (is (= :rf.error/machines-artefact-missing (:rf.error/id (ex-data thrown)))
              "ex-data carries the canonical :rf.error/id discriminator")
          (let [data (ex-data thrown)]
            ;; The throw lives in
            ;; `re-frame.core-machines/reg-machine` — the sibling-namespace
            ;; fn-form delegate the macro routes through. The `:where` symbol
            ;; is namespace-qualified to the user-facing surface
            ;; (`rf/reg-machine`).
            (is (= 'rf/reg-machine (:where data))
                "ex-data carries :where = 'rf/reg-machine")
            (is (= :probe/macro-machine (:machine-id data))
                "ex-data carries :machine-id from the call site")
            (is (= :no-recovery (:recovery data))
                "ex-data carries :recovery = :no-recovery")))))))
