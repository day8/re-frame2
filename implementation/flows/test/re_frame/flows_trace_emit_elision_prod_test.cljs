(ns re-frame.flows-trace-emit-elision-prod-test
  "Per Spec 009 §Production builds (bead rf2-xxd6z) — RUNTIME prod-elision
  contract for the `re-frame.flows` trace surface. Companion to the
  string-grep sentinel sweep in `scripts/check-elision.cjs`: the grep
  catches keyword-literal survival in the bundle blob; this file pins
  the BEHAVIOUR — under `:advanced` + `goog.DEBUG=false`, a registered
  trace listener observes NO `:rf.flow/*` events from the flows
  lifecycle.

  Gating contract: each `:rf.flow/*` emit site sits inside `(when
  interop/debug-enabled? ...)` AND the body of `trace/emit!` itself is
  gated. Under prod-mode both gates constant-fold to false. The flow's
  `:output` fn still runs (recomputation is not gated; only the trace
  fan-out and the `elision/elide-wire-value` walker that builds the tag
  map are); the app-db slot at `:path` is still populated; only the
  trace emission elides.

  Surfaces exercised:

  - `:rf.flow/registered`  (emitted by `reg-flow` on first-time register)
  - `:rf.flow/computed`    (emitted by `evaluate-flow!` on successful run)
  - `:rf.flow/skip`        (emitted by `evaluate-flow!` on value-equal inputs)
  - `:rf.flow/failed`      (emitted by `evaluate-flow!` when :output throws)
  - `:rf.flow/cleared`     (emitted by `clear-flow`)

  Naming convention: files ending in `-elision-prod-test.cljs` are
  picked up ONLY by the `:browser-test-prod-elision` build. Running
  under `goog.DEBUG=true` would FAIL — the trace surface delivers under
  dev-mode (which is the dev contract pinned in
  `re-frame.flows-trace-test`).

  Note: the cascade-level `:rf.error/flow-eval-exception` carried by
  the always-on error-emit substrate is the production observability
  signal for `:output` failures per rf2-gmrks; the per-flow
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
            ;; rf2-qwm0a — listener surface lives in `re-frame.trace.tooling`.
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
                      :output (fn [w h] (* (or w 0) (or h 0)))
                      :path   [:rect :area]})))]
      (is (empty? seen)
          "no trace events delivered under :advanced + goog.DEBUG=false"))
    ;; Cross-check: the flow IS registered — the registrar mutation
    ;; happened, only the trace surface elided. `flows/flows` is keyed
    ;; `{frame-id {flow-id flow}}`; the flow registers under
    ;; `:rf/default` by default.
    (is (some? (get-in @flows/flows [:rf/default :prod-elision/area]))
        "flow registered in the per-frame index — only trace surface elided")))

;; ---- :rf.flow/computed elides under prod ----------------------------------

(deftest flow-computed-emits-no-trace-under-prod
  (testing "Per Spec 009 §Production-elision: a successful flow
            recompute runs the `:output` fn and writes the result into
            the app-db slot but emits NO `:rf.flow/computed` trace
            under prod. The wire-value elision walker is also gated,
            so the result/input-values payload construction elides too."
    (let [seen (listener-fixture
                 (fn []
                   (rf/reg-event-db :prod-elision/seed-rect
                     (fn [db _] (assoc db :w 3 :h 4)))
                   (rf/reg-flow
                     {:id     :prod-elision/area
                      :inputs [[:w] [:h]]
                      :output (fn [w h] (* (or w 0) (or h 0)))
                      :path   [:rect :area]})
                   ;; Seed inputs; the drain runs the flow.
                   (rf/dispatch-sync [:prod-elision/seed-rect])))]
      (is (empty? seen)
          "no trace events delivered for the recompute under prod"))
    ;; Cross-check: the flow computed the correct value into app-db.
    (is (= 12
           (get-in (rf/get-frame-db :rf/default) [:rect :area]))
        "flow output written to app-db slot — only trace surface elided")))

;; ---- :rf.flow/failed elides under prod ------------------------------------

(deftest flow-failed-emits-no-trace-under-prod
  (testing "Per Spec 009 §Production-elision: when a flow's `:output`
            fn throws, the per-flow `:rf.flow/failed` trace elides
            under prod. The cascade-level `:rf.error/flow-eval-exception`
            still flows through the always-on error-emit substrate
            (see `re-frame.flow-eval-exception-elision-prod-test` for
            that pin); a `register-error-listener!` here would
            fire. We assert only on the TRACE listener, which must see
            nothing."
    (let [seen (listener-fixture
                 (fn []
                   (rf/reg-event-db :prod-elision/seed-throw-input
                     (fn [db _] (assoc db :trigger? true)))
                   (rf/reg-flow
                     {:id     :prod-elision/throwing
                      :inputs [[:trigger?]]
                      :output (fn [t?]
                                (when t?
                                  (throw (ex-info "prod-elision throw" {}))))
                      :path   [:prod-elision/result]})
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
    (rf/reg-event-db :prod-elision/seed-skip
      (fn [db _] (assoc db :a 1)))
    (rf/reg-event-db :prod-elision/touch-skip
      (fn [db _] (update db :touched (fnil inc 0))))
    (rf/reg-flow
      {:id     :prod-elision/skipper
       :inputs [[:a]]
       :output (fn [a] (* (or a 0) 2))
       :path   [:prod-elision/double]})
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
       :output (fn [w] (or w 0))
       :path   [:prod-elision/copy]})
    (let [seen (listener-fixture
                 (fn []
                   (rf/clear-flow :prod-elision/clearable)))]
      (is (empty? seen)
          "no :rf.flow/cleared trace under prod"))
    (is (nil? (get-in @flows/flows [:rf/default :prod-elision/clearable]))
        "flow removed from per-frame index — only trace surface elided")))
