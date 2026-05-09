(ns re-frame.late-bind-missing-test
  "Per rf2-5b6x — assert the documented missing-artefact error contract for
  the schemas artefact's `re-frame.core` re-exports.

  Each per-feature split (schemas / machines / routing / flows / http /
  ssr) raises a documented `:rf.error/<artefact>-artefact-missing`
  ex-info when a consumer calls a re-exported surface but the artefact
  is absent from the classpath. The contract was previously only
  documented in prose; this test pins the runtime behaviour against
  regression.

  Strategy: the schemas artefact IS on the classpath here (the test ns
  requires `re-frame.schemas`, which fires the late-bind hook
  registrations at ns-load). To simulate the absent-artefact state we
  flip the relevant late-bind hook to nil for the duration of the
  assertion, then restore it in `finally`. Identical mechanism as the
  test would use on CLJS.

  Per Spec 002 §The late-bind seam, rf2-p7va (schemas split), and the
  prose at the call sites in `re-frame.core`.

  Note: only `reg-app-schema` raises a missing-artefact error. The
  introspection surfaces (`app-schema-at`, `app-schemas`,
  `app-schemas-digest`) deliberately return safe defaults (nil / {} /
  nil) when the schemas artefact is absent — Spec 010 §Schemas as a
  tooling and agent surface. We test both branches here."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.core :as rf]
            [re-frame.late-bind :as late-bind]
            ;; Loading schemas registers its late-bind hooks. The
            ;; `with-hook-as-nil` helper below re-establishes the absent
            ;; state by flipping the hook value at runtime; restoration
            ;; in `finally` keeps cross-test isolation intact.
            [re-frame.schemas]))

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

(deftest reg-app-schema-raises-when-schemas-artefact-missing
  (testing "rf/reg-app-schema (macro) raises :rf.error/schemas-artefact-missing when the :schemas/reg-app-schema hook is nil"
    (with-hook-as-nil :schemas/reg-app-schema
      (fn []
        (let [thrown (try (rf/reg-app-schema [:probe] :int)
                          nil
                          (catch clojure.lang.ExceptionInfo e e))]
          (is (some? thrown)
              "reg-app-schema throws when the schemas artefact is absent")
          (is (= ":rf.error/schemas-artefact-missing" (.getMessage thrown))
              "the documented error category appears in the message")
          (let [data (ex-data thrown)]
            ;; The macro syntax-quotes 'reg-app-schema at expansion
            ;; site, so the symbol stamped onto :where is namespace-
            ;; qualified against `re-frame.core` (the macro's home ns).
            (is (= 're-frame.core/reg-app-schema (:where data))
                "ex-data carries :where = 're-frame.core/reg-app-schema (macro form)")
            (is (= [:probe] (:path data))
                "ex-data carries :path from the call site")
            (is (= :no-recovery (:recovery data))
                "ex-data carries :recovery = :no-recovery")
            (is (string? (:reason data))
                "ex-data carries :reason as a string")))))))

(deftest introspection-surfaces-return-safe-defaults-when-schemas-artefact-missing
  (testing "Per Spec 010 §Schemas as a tooling and agent surface, the
  read-only schema-introspection surfaces deliberately do NOT throw
  when the schemas artefact is absent — they return safe defaults
  (nil / {} / nil). This branch must stay distinct from the active
  surfaces' missing-artefact contract."
    (with-hook-as-nil :schemas/app-schema-at
      (fn []
        (is (nil? (rf/app-schema-at [:any]))
            "app-schema-at returns nil when the schemas artefact is absent")))
    (with-hook-as-nil :schemas/app-schemas
      (fn []
        (is (= {} (rf/app-schemas))
            "app-schemas returns {} when the schemas artefact is absent")))
    (with-hook-as-nil :schemas/app-schemas-digest
      (fn []
        (is (nil? (rf/app-schemas-digest))
            "app-schemas-digest returns nil when the schemas artefact is absent")))))
