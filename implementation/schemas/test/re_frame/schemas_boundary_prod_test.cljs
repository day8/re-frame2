(ns re-frame.schemas-boundary-prod-test
  "Production-mode CLJS smoke for `:rf.schema/at-boundary` (rf2-r2uh,
  rf2-84e9; renamed from `:spec/at-boundary` at rf2-ieu0i).

  The JVM smoke (`re-frame.schemas-test`) exercises the dev/prod gate by
  rebinding `re-frame.spec/dev-mode?` via `with-redefs`. That proves the
  *interceptor logic* under both branches, but it cannot prove the
  *real-world elision contract* — that under `:advanced` +
  `goog.DEBUG=false`, the Closure compiler constant-folds the dev gate
  to `false` so the production validation branch is the only code path
  that survives.

  The companion node-test smoke (`re-frame.schemas-cljs-test`) compiles
  with `goog.DEBUG=true` (cljs default) and asserts the boundary
  interceptor is a no-op in dev: step-1 validation in the router has
  already run.

  This namespace is the dual — it compiles under the dedicated
  `:browser-test-schemas-boundary-prod` shadow-cljs build with
  `:closure-defines {goog.DEBUG false}` + `release` (`:advanced`), so:

    1. `re-frame.spec/dev-mode?` constant-folds to `false`, and the
       boundary's production validation branch runs.
    2. `re-frame.trace/emit-error!` ALSO elides under the same gate
       (its body sits inside `(when interop/debug-enabled? ...)`) — the
       boundary's failure-trace emission is silent in production.
    3. The handler-skip recovery (`:rf/skip-handler?` set on the
       context) is the load-bearing observable surface.

  We deliberately use the ns suffix `-prod-test` (not `-cljs-test`) so
  the default `:browser-test` and `:node-test` builds (whose regexes
  match `-cljs-test$` / `cljs-test$`) do NOT pick this file up. The
  prod-mode assertions would fail under `goog.DEBUG=true` because the
  boundary is a no-op in dev (per Spec 010 L145)."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            ;; Per rf2-t0hq the CLJS default validator routes through
            ;; the late-bind hook `:schemas/malli-validate`, which the
            ;; `re-frame.schemas.malli` adapter ns publishes at load
            ;; time. Without it the default validator soft-passes and
            ;; the boundary interceptor never sees a failure. The
            ;; canonical opt-in for CLJS apps that want Malli validation
            ;; at the boundary is to require the adapter ns at boot.
            [re-frame.schemas.malli]
            [re-frame.core :as rf]
            [re-frame.schemas :as schemas]
            [re-frame.adapter.reagent :as reagent-adapter]
            [re-frame.test-support :as test-support])
  ;; rf2-bhh8my: the shared trace recorder supersedes the per-test
  ;; register-listener!/unregister-listener! capture bracket. The macro
  ;; ships from the `#?(:clj ...)` arm of re-frame.test-support, so CLJS
  ;; reaches it via :require-macros (mirrors re-frame.core's call-site macros).
  (:require-macros [re-frame.test-support :refer [with-trace-recorder!]]))

;; Mirror schemas_cljs_test.cljs's fixture: snapshot/restore the
;; registrar (rf2-am9d) and clear the schemas artefact's per-frame
;; side-table between tests (rf2-cq1ak — app-db schemas are NOT a
;; registrar kind).
(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter            reagent-adapter/adapter
     :clear-app-schemas? true}))

;; ---- boundary interceptor under `:advanced` + `goog.DEBUG=false` ---------

(deftest boundary-skips-handler-on-invalid-event-in-prod
  (testing "Per Spec 010 §Production builds (rf2-r2uh): under `:advanced`
            + `goog.DEBUG=false` the boundary interceptor takes its
            production validation branch. A malformed event against the
            handler's `:schema` causes `:rf/skip-handler?` to be set on
            the context, and the handler is NOT invoked.

            Under the same gate, the router's step-1 `validate-event!`
            body has DCE'd — only the boundary interceptor is enforcing
            the schema at this dispatch."
    (let [calls (atom 0)]
      (rf/reg-event :api/strict
        {:schema [:cat [:= :api/strict] :int]
         :interceptors [:rf.schema/at-boundary]}
        (fn [_ _] (swap! calls inc) {}))
      ;; Malformed payload: handler MUST be skipped.
      (rf/dispatch-sync [:api/strict "not-an-int"])
      (is (= 0 @calls)
          "handler was skipped — boundary interceptor set :rf/skip-handler? on the context"))))

(deftest boundary-passes-valid-event-through-in-prod
  (testing "Per Spec 010 §Production builds (rf2-r2uh): under `:advanced`
            + `goog.DEBUG=false` a valid event against the handler's
            `:schema` flows through the boundary interceptor unchanged.
            The handler runs exactly once."
    (let [calls (atom 0)]
      (rf/reg-event :api/strict
        {:schema [:cat [:= :api/strict] :int]
         :interceptors [:rf.schema/at-boundary]}
        (fn [_ _] (swap! calls inc) {}))
      (rf/dispatch-sync [:api/strict 42])
      (is (= 1 @calls)
          "handler ran exactly once — valid payload, boundary passed through"))))

(deftest boundary-failure-trace-elides-in-prod
  (testing "Per Spec 009 §Production builds + Spec 010 §Production
            builds: under `:advanced` + `goog.DEBUG=false`,
            `trace/emit-error!` also elides (its body sits inside
            `(when interop/debug-enabled? ...)`). The boundary's failure
            emission therefore does NOT fire a trace in production —
            the handler-skip is silent.

            This pins the dual-elision contract: trace gate AND boundary
            gate both fold under the same closure-define. A registered
            trace callback would never see a boundary emission because
            the entire emit body has DCE'd."
    (let [calls (atom 0)]
      (rf/reg-event :api/strict
        {:schema [:cat [:= :api/strict] :int]
         :interceptors [:rf.schema/at-boundary]}
        (fn [_ _] (swap! calls inc) {}))
      (with-trace-recorder! [traces]
        (rf/dispatch-sync [:api/strict "not-an-int"])
        (is (= 0 @calls)
            "handler skipped (boundary did its job)")
        ;; Under `:advanced` + `goog.DEBUG=false` the trace surface is
        ;; entirely elided — register-listener! / emit-* bodies all
        ;; live inside `(when interop/debug-enabled? ...)`. No traces
        ;; reach the callback by design.
        (is (empty? @traces)
            "no traces observed — Spec 009 §Production builds elision contract holds")))))

(deftest boundary-direct-before-invocation-in-prod
  (testing "Per Spec 010 §Per-step recovery step 1: directly invoking the
            boundary interceptor's `:before` slot is a deterministic
            surface for asserting the recovery contract. Under
            `:advanced` + `goog.DEBUG=false` (production), the
            `:before` slot's prod branch validates the event and sets
            `:rf/skip-handler?` on the context when the schema fails."
    (rf/reg-event :api/strict
      {:schema [:cat [:= :api/strict] :int]
       :interceptors [:rf.schema/at-boundary]}
      (fn [_ _] {}))
    (let [before    (:before rf/validate-at-boundary-interceptor)
          valid-ctx (before {:coeffects {:event [:api/strict 42]}})
          bad-ctx   (before {:coeffects {:event [:api/strict "not-an-int"]}})]
      (is (not (:rf/skip-handler? valid-ctx))
          "valid event: :rf/skip-handler? unset — handler will run")
      (is (true? (:rf/skip-handler? bad-ctx))
          "MALFORMED event in prod: :rf/skip-handler? set true — handler is skipped"))))

;; ---- EP-0022 by-ref chain form (rf2-i3uxo2) ------------------------------
;;
;; Per EP-0022 + API.md §`validate-at-boundary-interceptor`: a public
;; `:interceptors` chain carries interceptor REFERENCES, not inline values.
;; The canonical opt-in is `{:interceptors [:rf.schema/at-boundary]}` (a
;; bare-keyword ref) — NOT the inline `rf/validate-at-boundary-interceptor`
;; Var. rf2-i3uxo2 registers `:rf.schema/at-boundary` under the `:interceptor`
;; registrar kind (re-seeded by the fixture's `rf/init!`), so the bare-keyword
;; ref resolves at chain assembly and runs the SAME boundary validation as the
;; inline value. These two tests are the by-ref counterparts of
;; `boundary-skips-handler-on-invalid-event-in-prod` /
;; `boundary-passes-valid-event-through-in-prod` above.

(deftest boundary-ref-form-skips-handler-on-invalid-event-in-prod
  (testing "Per rf2-i3uxo2 — the EP-0022 by-ref chain form
            `{:interceptors [:rf.schema/at-boundary]}` resolves at chain
            assembly and runs boundary validation. Under `:advanced` +
            `goog.DEBUG=false`, a malformed event causes the handler to be
            SKIPPED — identical behaviour to the inline-value form."
    (let [calls (atom 0)]
      (rf/reg-event :api/strict-ref
        {:schema [:cat [:= :api/strict-ref] :int]
         :interceptors [:rf.schema/at-boundary]}   ;; EP-0022 ref by id
        (fn [_ _] (swap! calls inc) {}))
      (rf/dispatch-sync [:api/strict-ref "not-an-int"])
      (is (= 0 @calls)
          "by-ref boundary interceptor resolved and skipped the handler on a malformed payload"))))

(deftest boundary-ref-form-passes-valid-event-through-in-prod
  (testing "Per rf2-i3uxo2 — the by-ref form passes a VALID event through
            the boundary unchanged; the handler runs exactly once. Confirms
            the resolved ref is a live boundary interceptor, not a no-op."
    (let [calls (atom 0)]
      (rf/reg-event :api/strict-ref
        {:schema [:cat [:= :api/strict-ref] :int]
         :interceptors [:rf.schema/at-boundary]}
        (fn [_ _] (swap! calls inc) {}))
      (rf/dispatch-sync [:api/strict-ref 42])
      (is (= 1 @calls)
          "valid payload — by-ref boundary passed through, handler ran once"))))

(deftest boundary-noop-when-validator-is-nil-in-prod
  (testing "Per Spec 010 §Non-Malli validators (rf2-froe): even under
            `:advanced` + `goog.DEBUG=false`, setting the validator to
            `nil` disables every validation surface — including the
            boundary interceptor. The handler runs on a wildly malformed
            payload because validation has been opted out."
    (schemas/set-schema-validator! nil)
    (try
      (let [calls (atom 0)]
        (rf/reg-event :api/disabled
          {:schema [:cat [:= :api/disabled] :int]
           :interceptors [:rf.schema/at-boundary]}
          (fn [_ _] (swap! calls inc) {}))
        (rf/dispatch-sync [:api/disabled "wildly-malformed"])
        (is (= 1 @calls)
            "handler ran — nil validator means no boundary check, even in production"))
      (finally
        (schemas/reset-schema-validator!)))))
