(ns re-frame.source-coord-prod-elision-test
  "Per rf2-3un2g: source-coord production-elision contract.

  Two surfaces, two policies:

    A. **Public registry-meta strip in prod**. Under the JVM debug gate
       off posture (`interop/debug-enabled?` rebound to `false` —
       semantically equivalent to CLJS `:advanced` + `goog.DEBUG=false`),
       `(rf/handler-meta kind id)` MUST NOT carry `:ns` / `:file` /
       `:line` / `:column` coord-keys. Causa Open-in-editor and
       re-frame-pair are dev-only tools; they don't reach the registry
       at all in production bundles.

    B. **Error-emit substrate retains source-coord in prod**. The tight
       record passed to corpus-wide listeners (Sentry / Honeybadger /
       Rollbar shippers) AND the structured policy-event passed to the
       per-frame `:on-error` fn MUST include `:source-coord` even under
       the disabled debug gate. The coord rides the always-on parallel
       `error-coords-by-id` registry — NOT the public registry-meta.

  Naming convention: `_test.clj` (JVM-only) by design — the canonical
  CLJS production-elision verification rides the bundle-presence
  probes (`scripts/check-elision.cjs`, `scripts/check-perf-bundle.cjs`).
  This file pins the SEMANTIC contract on the JVM where we can
  `with-redefs` the gate; the bundle probes pin the CODE-PATH absence
  in CLJS-prod by negative grep."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.interop :as interop]
            [re-frame.source-coords :as source-coords]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]))

(use-fixtures :each
  (test-support/reset-runtime-fixture
    {:adapter plain-atom/adapter}))

;; ---- Policy A: registry-meta stripped under disabled debug gate ---------

(deftest registry-meta-strips-coord-keys-under-disabled-debug-gate
  (testing "Per rf2-3un2g Policy A: under `:advanced + goog.DEBUG=false`
            (modelled here as `with-redefs [interop/debug-enabled? false]`)
            the public registry-meta returned by `rf/handler-meta` MUST
            NOT carry `:ns` / `:file` / `:line` / `:column` coord-keys.
            The macro path still runs at JVM expansion time; only the
            `merge-coords` propagation into the public meta is
            suppressed."
    (with-redefs [interop/debug-enabled? false]
      (rf/reg-event-db :rf2-3un2g/prod-elide-event
                       {:doc "stripped"}
                       (fn [db _] db))
      (let [meta (rf/handler-meta :event :rf2-3un2g/prod-elide-event)]
        (is (some? meta))
        (is (= "stripped" (:doc meta))
            "user-supplied :doc is preserved — only auto-captured
             coord-keys are stripped")
        (is (not (contains? meta :ns))     ":ns absent from registry-meta in prod")
        (is (not (contains? meta :file))   ":file absent from registry-meta in prod")
        (is (not (contains? meta :line))   ":line absent from registry-meta in prod")
        (is (not (contains? meta :column)) ":column absent from registry-meta in prod")))))

(deftest registry-meta-keeps-coord-keys-under-enabled-debug-gate
  (testing "Per rf2-3un2g Policy A: the dev posture (default
            `interop/debug-enabled?` = true) preserves the historical
            behaviour — `(rf/handler-meta ...)` returns the full coord-
            map for Causa / re-frame-pair / IDE jump-to-source."
    (rf/reg-event-db :rf2-3un2g/dev-keep-event
                     {:doc "kept"}
                     (fn [db _] db))
    (let [meta (rf/handler-meta :event :rf2-3un2g/dev-keep-event)]
      (is (some? (:ns   meta)) ":ns present in registry-meta in dev")
      (is (some? (:line meta)) ":line present in registry-meta in dev")
      (is (some? (:file meta)) ":file present in registry-meta in dev"))))

;; ---- Policy B: error-emit substrate retains source-coord in prod --------

(deftest error-record-includes-source-coord-under-disabled-debug-gate
  (testing "Per rf2-3un2g Policy B: under the disabled debug gate, the
            tight error-record passed to corpus-wide listeners (the
            Sentry/Honeybadger/Rollbar fan-out) MUST carry the failing
            handler's `:source-coord`. The coord rides the always-on
            parallel `error-coords-by-id` registry — NOT the public
            registry-meta (which Policy A strips). This pins the
            production observability contract."
    ;; Register the handler in DEV (default gate) so the parallel
    ;; registry gets populated by the macro expansion's `*pending-
    ;; coords*` binding. Then re-bind the gate to false and dispatch
    ;; — the public meta would carry coords either way under JVM
    ;; (registrations happened in dev); the load-bearing assertion is
    ;; that the error-emit substrate stamps `:source-coord` on the
    ;; tight record FROM the parallel registry, not from registry-meta.
    (rf/reg-event-db :rf2-3un2g/prod-error-handler
                     (fn [_db _]
                       (throw (ex-info "boom" {:cause :test}))))
    (with-redefs [interop/debug-enabled? false]
      (let [seen (atom nil)]
        (rf/register-error-emit-listener!
          :rf2-3un2g/recorder
          (fn [record] (reset! seen record)))
        (rf/dispatch-sync [:rf2-3un2g/prod-error-handler])
        (is (some? @seen)
            "error-emit listener fired under disabled debug gate")
        (let [sc (:source-coord @seen)]
          (is (some? sc)
              "`:source-coord` rides the tight error-record under
               disabled debug gate — Sentry-style shippers see it")
          (is (symbol? (:ns sc))
              ":source-coord :ns is a symbol")
          (is (integer? (:line sc))
              ":source-coord :line is an integer")
          (is (string? (:file sc))
              ":source-coord :file is a string"))))))

(deftest policy-event-tags-include-source-coord-under-disabled-debug-gate
  (testing "Per rf2-3un2g Policy B: under the disabled debug gate, the
            structured `error-event` passed to the per-frame `:on-error`
            policy fn MUST include `:source-coord` under `:tags`. In-app
            recovery surfaces (custom error overlays, breadcrumb
            recorders) get the same observability signal as the
            corpus-wide listener."
    (rf/reg-event-db :rf2-3un2g/policy-error-handler
                     (fn [_db _]
                       (throw (ex-info "boom" {:cause :test}))))
    (with-redefs [interop/debug-enabled? false]
      (let [seen (atom nil)]
        (rf/reg-frame :rf/default
                      {:on-error (fn [ev] (reset! seen ev) nil)})
        (rf/dispatch-sync [:rf2-3un2g/policy-error-handler])
        (is (some? @seen)
            ":on-error policy fired under disabled debug gate")
        (let [sc (get-in @seen [:tags :source-coord])]
          (is (some? sc)
              "`:source-coord` rides `:tags` on the structured error-event
               under disabled debug gate")
          (is (symbol? (:ns sc)))
          (is (integer? (:line sc)))
          (is (string? (:file sc))))))))

;; ---- programmatic registrations bypass the parallel registry -----------

(deftest programmatic-registration-no-source-coord-in-error-record
  (testing "Per rf2-3un2g: programmatic registrations (HoF, runtime
            registration via the fn aliases — bypassing the macro
            path) leave `*pending-coords*` unbound, so the parallel
            `error-coords-by-id` registry stays empty for that
            `(kind, id)`. The error-record's `:source-coord` slot is
            ABSENT (not nil) for those cases — `cond->` skips the
            assoc. This mirrors the dev-side behaviour where
            programmatic registrations carry no `:ns` / `:line` /
            `:file` on `handler-meta`."
    (with-redefs [interop/debug-enabled? false]
      (let [reg-fn (requiring-resolve 're-frame.events/reg-event-db)]
        (reg-fn :rf2-3un2g/programmatic
                (fn [_db _]
                  (throw (ex-info "boom" {})))))
      (let [seen (atom nil)]
        (rf/register-error-emit-listener!
          :rf2-3un2g/programmatic-recorder
          (fn [record] (reset! seen record)))
        (rf/dispatch-sync [:rf2-3un2g/programmatic])
        (is (some? @seen)
            "error-emit listener fired even for programmatic registration")
        (is (not (contains? @seen :source-coord))
            "`:source-coord` slot absent when no macro coords were
             captured at registration time")))))

;; ---- error-coords-by-id atom semantics ----------------------------------

(deftest error-coords-by-id-populated-on-registration
  (testing "Per rf2-3un2g: every macro-driven registration populates the
            parallel `error-coords-by-id` registry under `[:kind :id]`.
            The atom is the single source of truth for error-emit
            source-coord lookup."
    (rf/reg-event-db :rf2-3un2g/parallel-reg
                     (fn [db _] db))
    (let [sc (source-coords/error-coords-for :event :rf2-3un2g/parallel-reg)]
      (is (some? sc)
          "parallel registry carries coords for the registered id")
      (is (some? (:ns   sc)))
      (is (some? (:line sc)))
      (is (some? (:file sc))))))
