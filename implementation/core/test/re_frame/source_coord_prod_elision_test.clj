(ns re-frame.source-coord-prod-elision-test
  "Per rf2-3un2g: source-coord production-elision contract.

  Two surfaces, two policies:

    A. **Public registry-meta strip in prod**. Under the JVM debug gate
       off posture (`rf.interop/debug-enabled?` rebound to `false` —
       semantically equivalent to CLJS `:advanced` + `goog.DEBUG=false`),
       `(rf/handler-meta {:source :store :kind kind :id id})` MUST NOT carry `:ns` / `:file` /
       `:line` / `:column` coord-keys. Xray Open-in-editor and
       re-frame-pair are dev-only tools; they don't reach the registry
       at all in production bundles.

    B. **Error-emit substrate retains source-coord in prod**. The tight
       record passed to corpus-wide listeners (Sentry / Honeybadger /
       Rollbar shippers) MUST include `:source-coord` even under the
       disabled debug gate. The coord rides the always-on parallel
       `error-coords-by-id` registry — NOT the public registry-meta.

  Naming convention: `_test.clj` (JVM-only) by design — the canonical
  CLJS production-elision verification rides the bundle-presence
  probes (`scripts/check-elision.cjs`, `scripts/check-perf-bundle.cjs`).
  This file pins the SEMANTIC contract on the JVM where we can
  `with-redefs` the gate; the bundle probes pin the CODE-PATH absence
  in CLJS-prod by negative grep.

  ## Posture split (rf2-d2841)

  Policy A's PROD half and the whole of Policy B are posture-independent and
  run under `scripts/test-core-prod-gate.sh` unchanged. Under the real gate
  Policy A's `with-redefs` becomes a no-op over an already-false flag, which
  means the prod-lane run re-asserts the same claim against the LOAD-TIME
  gate rather than a rebind — the stronger evidence of the two, since
  `merge-coords` reads `rf.interop/debug-enabled?` at the point the macro-emitted
  `*pending-coords*` binding is consumed.

  Policy A's DEV half — the public `handler-meta` still carrying `:ns` /
  `:line` / `:file` for Xray / jump-to-source — is a claim about the gate
  being ON, and is kept verbatim inside a `(when rf.interop/debug-enabled? …)`
  arm marked `rf2-d2841`. Its always-on partner sits in the same body: the
  PARALLEL `error-coords-by-id` registry is populated for that same
  registration in BOTH postures. That pairing is the actual contract of this
  file — one registration, coords stripped from the public surface and
  retained on the observability surface — and stating both halves in one
  deftest is what stops the dev half from being a namespace-shaped hole under
  the gate."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.interop :as rf.interop]
            [re-frame.source-coords :as rf.source-coords]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.substrate.plain-atom/adapter}))

;; ---- Policy A: registry-meta stripped under disabled debug gate ---------

(deftest registry-meta-strips-coord-keys-under-disabled-debug-gate
  (testing "Per rf2-3un2g Policy A: under `:advanced + goog.DEBUG=false`
            (modelled here as `with-redefs [rf.interop/debug-enabled? false]`)
            the public registry-meta returned by `rf/handler-meta` MUST
            NOT carry `:ns` / `:file` / `:line` / `:column` coord-keys.
            The macro path still runs at JVM expansion time; only the
            `merge-coords` propagation into the public meta is
            suppressed."
    (with-redefs [rf.interop/debug-enabled? false]
      (rf/reg-event :rf2-3un2g/prod-elide-event
                       {:doc "stripped"}
                       (fn [{:keys [db]} _] {:db db}))
      (let [meta (rf/handler-meta {:source :store :kind :event :id :rf2-3un2g/prod-elide-event})]
        (is (some? meta))
        ;; rf2-9wwkcm: `:doc` is now ALSO stripped from public registry-meta
        ;; in prod (it is pure-documentation — zero production runtime /
        ;; observability use). This supersedes the prior "doc preserved"
        ;; assertion (the coord-keys were never the only strip — the
        ;; classification widened to include the pure-documentation key).
        (is (not (contains? meta :doc))
            "pure-documentation :doc absent from registry-meta in prod (rf2-9wwkcm)")
        (is (not (contains? meta :ns))     ":ns absent from registry-meta in prod")
        (is (not (contains? meta :file))   ":file absent from registry-meta in prod")
        (is (not (contains? meta :line))   ":line absent from registry-meta in prod")
        (is (not (contains? meta :column)) ":column absent from registry-meta in prod")))))

(deftest registry-meta-keeps-coord-keys-under-enabled-debug-gate
  (testing "Per rf2-3un2g Policy A: the dev posture (default
            `rf.interop/debug-enabled?` = true) preserves the historical
            behaviour — `(rf/handler-meta ...)` returns the full coord-
            map for Xray / re-frame-pair / IDE jump-to-source."
    (rf/reg-event :rf2-3un2g/dev-keep-event
                     {:doc "kept"}
                     (fn [{:keys [db]} _] {:db db}))
    (let [meta (rf/handler-meta {:source :store :kind :event :id :rf2-3un2g/dev-keep-event})
          parallel (rf.source-coords/error-coords-for :event :rf2-3un2g/dev-keep-event)]
      ;; ALWAYS-ON PARTNER (rf2-d2841). Policy A strips the PUBLIC surface in
      ;; production; the parallel observability registry keeps the coords in
      ;; BOTH postures. Asserting the retained half beside the stripped half
      ;; is what makes "stripped" mean stripped-from-here rather than
      ;; never-captured — and it is exactly the pair a regression in
      ;; `with-coords-form`'s prod arm would break.
      (is (some? parallel)
          "the parallel error-coord registry carries the coords in BOTH postures")
      (is (some? (:ns   parallel)) ":ns survives on the always-on registry")
      (is (some? (:line parallel)) ":line survives on the always-on registry")
      (is (some? (:file parallel)) ":file survives on the always-on registry")
      ;; rf2-d2841 — dev-instrumentation arm (see ns docstring §Posture split).
      (when rf.interop/debug-enabled?
        (is (some? (:ns   meta)) ":ns present in registry-meta in dev")
        (is (some? (:line meta)) ":line present in registry-meta in dev")
        (is (some? (:file meta)) ":file present in registry-meta in dev")))))

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
    (rf/reg-event :rf2-3un2g/prod-error-handler
                     (fn [{:keys [db]} _]
                       {:db (throw (ex-info "boom" {:cause :test}))}))
    (with-redefs [rf.interop/debug-enabled? false]
      (let [seen (atom nil)]
        (rf/register-listener! :errors
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
    (with-redefs [rf.interop/debug-enabled? false]
      (let [reg-fn (requiring-resolve 're-frame.events/reg-event)]
        (reg-fn :rf2-3un2g/programmatic
                (fn [_cofx _]
                  (throw (ex-info "boom" {})))))
      (let [seen (atom nil)]
        (rf/register-listener! :errors
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
    (rf/reg-event :rf2-3un2g/parallel-reg
                     (fn [{:keys [db]} _] {:db db}))
    (let [sc (rf.source-coords/error-coords-for :event :rf2-3un2g/parallel-reg)]
      (is (some? sc)
          "parallel registry carries coords for the registered id")
      (is (some? (:ns   sc)))
      (is (some? (:line sc)))
      (is (some? (:file sc))))))
