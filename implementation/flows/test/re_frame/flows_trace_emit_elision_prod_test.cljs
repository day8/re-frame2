(ns re-frame.flows-trace-emit-elision-prod-test
  "Per Spec 009 §Production builds — RUNTIME prod-elision contract for the
  `re-frame.flows` trace surface. Companion to the
  string-grep sentinel sweep in `scripts/check-elision.cjs`: the grep
  catches keyword-literal survival in the bundle blob; this file pins
  the BEHAVIOUR — under `:advanced` + `goog.DEBUG=false`, a registered
  trace listener observes NO `:rf.flow/*` events from the flows
  lifecycle.

  Gating contract: each `:rf.flow/*` emit site sits inside `(when
  interop/debug-enabled? ...)` AND the body of `trace/emit!` itself is
  gated. Under prod-mode both gates constant-fold to false. The flow's
  `:derive` fn still runs (recomputation is not gated; only the trace
  fan-out and the `elision/elide-wire-value` walker that builds the tag
  map are); the app-db slot at `:output-path` is still populated; only the
  trace emission elides.

  Surfaces exercised:

  - `:rf.flow/registered`  (emitted by `reg-flow` on first-time register)
  - `:rf.flow/computed`    (emitted by `evaluate-flow!` on successful run)
  - `:rf.flow/skip`        (emitted by `evaluate-flow!` on value-equal inputs)
  - `:rf.flow/failed`      (emitted by `evaluate-flow!` when :derive throws)
  - `:rf.flow/cleared`     (emitted by `clear-flow`)

  Naming convention: files ending in `-elision-prod-test.cljs` are
  picked up ONLY by the `:browser-test-prod-elision` build. Running
  under `goog.DEBUG=true` would FAIL — the trace surface delivers under
  dev-mode (which is the dev contract pinned in
  `re-frame.flows-trace-test`).

  Note: the cascade-level `:rf.error/flow-eval-exception` carried by
  the always-on error-emit substrate is the production observability
  signal for `:derive` failures per rf2-gmrks; the per-flow
  `:rf.flow/failed` trace is detail-grain only and DCEs in prod. The
  prod-survival of the error-emit substrate is pinned by
  `re-frame.flow-eval-exception-elision-prod-test`."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.adapter.reagent :as reagent-adapter]
            [re-frame.test-support :as test-support]
            ;; Require flows directly so its ns body — including every
            ;; gated trace/emit! call site — sits in the reachability
            ;; graph for the closure compiler.
            [re-frame.flows :as flows]
            [re-frame.flows.registry]
            ;; Require schemas so a registered validator IS available —
            ;; the prod test must prove the validation surface elides even
            ;; when the seam is wired, not merely when it is absent.
            [re-frame.schemas :as schemas]
            ;; The listener surface lives in `re-frame.trace.tooling`.
            [re-frame.trace.tooling :as trace-tooling]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter reagent-adapter/adapter}))

;; ---- helpers --------------------------------------------------------------

(defn- listener-fixture
  "Install a recording trace listener, run `body-fn`, and return the
  captured events vector. Records EVERY trace event so the test asserts
  on `empty?` without filtering — any leak surfaces."
  [body-fn]
  (let [seen   (atom [])
        cb-key (keyword (str "elision-prod-" (gensym)))]
    (trace-tooling/register-listener!
      cb-key
      (fn [ev] (swap! seen conj ev)))
    (try
      (body-fn)
      @seen
      (finally
        (trace-tooling/unregister-listener! cb-key)))))

;; ---- :rf.flow/registered elides under prod --------------------------------

(deftest reg-flow-emits-no-registered-trace-under-prod
  (testing "Per Spec 009 §Production-elision (rf2-xxd6z): `reg-flow`
            installs the flow via the registrar but emits NO
            `:rf.flow/registered` trace under `:advanced` +
            `goog.DEBUG=false`. The registrar's own
            `:rf.registry/handler-registered` would also elide (its
            gate is symmetric); the assertion below is total."
    (let [seen (listener-fixture
                 (fn []
                   (rf/reg-flow
                     {:id     :prod-elision/area
                      :inputs [[:w] [:h]]
                      :derive (fn [w h] (* (or w 0) (or h 0)))
                      :output-path   [:rect :area]})))]
      (is (empty? seen)
          "no trace events delivered under :advanced + goog.DEBUG=false"))
    ;; Cross-check: the flow IS registered — the registrar mutation
    ;; happened, only the trace surface elided. The per-frame flow
    ;; registry (read via `flows/flows-snapshot`) is keyed
    ;; `{frame-id {flow-id flow}}`; the flow registers under
    ;; `:rf/default` by default.
    (is (some? (get-in (flows/flows-snapshot) [:rf/default :prod-elision/area]))
        "flow registered in the per-frame index — only trace surface elided")))

;; ---- :rf.flow/computed elides under prod ----------------------------------

(deftest flow-computed-emits-no-trace-under-prod
  (testing "Per Spec 009 §Production-elision: a successful flow
            recompute runs the `:derive` fn and writes the result into
            the app-db slot but emits NO `:rf.flow/computed` trace
            under prod. The wire-value elision walker is also gated,
            so the result/input-values payload construction elides too."
    (let [seen (listener-fixture
                 (fn []
                   (rf/reg-event :prod-elision/seed-rect
                     (fn [{:keys [db]} _] {:db (assoc db :w 3 :h 4)}))
                   (rf/reg-flow
                     {:id     :prod-elision/area
                      :inputs [[:w] [:h]]
                      :derive (fn [w h] (* (or w 0) (or h 0)))
                      :output-path   [:rect :area]})
                   ;; Seed inputs; the drain runs the flow.
                   (rf/dispatch-sync [:prod-elision/seed-rect])))]
      (is (empty? seen)
          "no trace events delivered for the recompute under prod"))
    ;; Cross-check: the flow computed the correct value into app-db.
    (is (= 12
           (get-in (rf/app-db-value :rf/default) [:rect :area]))
        "flow output written to app-db slot — only trace surface elided")))

;; ---- :rf.flow/failed elides under prod ------------------------------------

(deftest flow-failed-emits-no-trace-under-prod
  (testing "Per Spec 009 §Production-elision: when a flow's `:derive`
            fn throws, the per-flow `:rf.flow/failed` trace elides
            under prod. The cascade-level `:rf.error/flow-eval-exception`
            still flows through the always-on error-emit substrate
            (see `re-frame.flow-eval-exception-elision-prod-test` for
            that pin); a `register-error-listener!` here would
            fire. We assert only on the TRACE listener, which must see
            nothing."
    (let [seen (listener-fixture
                 (fn []
                   (rf/reg-event :prod-elision/seed-throw-input
                     (fn [{:keys [db]} _] {:db (assoc db :trigger? true)}))
                   (rf/reg-flow
                     {:id     :prod-elision/throwing
                      :inputs [[:trigger?]]
                      :derive (fn [t?]
                                (when t?
                                  (throw (ex-info "prod-elision throw" {}))))
                      :output-path   [:prod-elision/result]})
                   (rf/dispatch-sync [:prod-elision/seed-throw-input])))]
      (is (empty? seen)
          "no :rf.flow/failed (or any other) trace events under prod"))))

;; ---- :rf.flow/skip elides under prod --------------------------------------

(deftest flow-skip-emits-no-trace-under-prod
  (testing "Per Spec 009 §Production-elision: a value-equal recompute
            suppression — the flow's inputs hash equal to the prior
            run's — emits NO `:rf.flow/skip` trace under prod. The
            dirty-check still runs (it's necessary for correctness);
            only the trace emit elides."
    ;; First drain installs the flow + populates :last-inputs.
    (rf/reg-event :prod-elision/seed-skip
      (fn [{:keys [db]} _] {:db (assoc db :a 1)}))
    (rf/reg-event :prod-elision/touch-skip
      (fn [{:keys [db]} _] {:db (update db :touched (fnil inc 0))}))
    (rf/reg-flow
      {:id     :prod-elision/skipper
       :inputs [[:a]]
       :derive (fn [a] (* (or a 0) 2))
       :output-path   [:prod-elision/double]})
    (rf/dispatch-sync [:prod-elision/seed-skip])
    ;; Second dispatch leaves :a unchanged → inputs value-equal → skip.
    (let [seen (listener-fixture
                 (fn []
                   (rf/dispatch-sync [:prod-elision/touch-skip])))]
      (is (empty? seen)
          "no :rf.flow/skip trace under prod for the value-equal recompute"))))

;; ---- :rf.flow/cleared elides under prod -----------------------------------

(deftest clear-flow-emits-no-trace-under-prod
  (testing "Per Spec 009 §Production-elision: `clear-flow` removes the
            flow registration but emits NO `:rf.flow/cleared` trace
            under prod. The registry-mutation side-effect happens; the
            trace fan-out elides."
    (rf/reg-flow
      {:id     :prod-elision/clearable
       :inputs [[:w]]
       :derive (fn [w] (or w 0))
       :output-path   [:prod-elision/copy]})
    (let [seen (listener-fixture
                 (fn []
                   (flows/clear-flow :prod-elision/clearable)))]
      (is (empty? seen)
          "no :rf.flow/cleared trace under prod"))
    (is (nil? (get-in (flows/flows-snapshot) [:rf/default :prod-elision/clearable]))
        "flow removed from per-frame index — only trace surface elided")))

;; ---- :schema output validation elides under prod --------------------------

(deftest flow-output-schema-validation-elides-under-prod
  (testing "Per Spec 009 §Production-elision (rf2-ee38b.9): a flow whose
            computed `:derive` output VIOLATES its `:schema` emits NO
            `:rf.error/schema-validation-failure :where :flow-output`
            trace under `:advanced` + `goog.DEBUG=false`. A predicate
            validator is registered so the seam is fully wired — the
            whole validate-output! surface still DCEs because it sits
            inside the `interop/debug-enabled?` gate. The value is still
            written (recompute is never gated)."
    ;; Register a validator that REJECTS every value — if validation ran,
    ;; a violation would surface.
    (schemas/set-schema-fns! {:validate (fn [_ _] false)})
    (rf/reg-event :prod-elision/seed-validate
      (fn [{:keys [db]} _] {:db (assoc db :w 3 :h 4)}))
    (rf/reg-flow
      {:id     :prod-elision/validated
       :inputs [[:w] [:h]]
       :derive (fn [w h] (* (or w 0) (or h 0)))
       :output-path   [:prod-elision/area]
       :schema (fn [_] false)})
    (let [seen (listener-fixture
                 (fn []
                   (rf/dispatch-sync [:prod-elision/seed-validate])))]
      (is (empty? seen)
          "no schema-validation-failure (or any other) trace under prod"))
    (is (= 12 (get-in (rf/app-db-value :rf/default) [:prod-elision/area]))
        "flow output still written — only the validation/trace surface elided")))
