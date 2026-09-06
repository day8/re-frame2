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
       boundary's failure-TRACE emission is silent in production, and
       stays that way: the rich `:value` / `:explain` diagnosis is
       exactly what must not egress.
    3. The handler-skip recovery (`:rf/skip-handler?` set on the
       context) is the load-bearing SECURITY surface.
    4. rf2-mwv4e — and the rejection is now OBSERVABLE here too. The
       silence in (2) is the trace axis ONLY; the refusal additionally
       fans one always-on STRUCTURAL-ONLY record through
       `register-error-listener!`, which carries no gate and survives
       `:advanced`. That axis is the whole point: before it, this
       `:advanced` build was the exact configuration in which a refused
       untrusted payload told nobody. Pinning both here — trace EMPTY,
       always-on record PRESENT — is what proves the two axes are
       genuinely separate rather than one surface everyone assumed
       survived.

  We deliberately use the ns suffix `-prod-test` (not `-cljs-test`) so
  the default `:browser-test` and `:node-test` builds (whose regexes
  match `-cljs-test$` / `cljs-test$`) do NOT pick this file up. The
  prod-mode assertions would fail under `goog.DEBUG=true` because the
  boundary is a no-op in dev (per Spec 010 L145)."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.late-bind :as rf.late-bind]
            ;; Per rf2-t0hq the CLJS default validator routes through the
            ;; late-bind hook `:schemas/malli-validate`, published at load
            ;; time by the `re-frame.schemas.malli` adapter ns — which the
            ;; facade below `:require`s in its own ns-form (rf2-v96fh,
            ;; Ruling A). Requiring `re-frame.schemas` is the whole opt-in,
            ;; so the boundary interceptor sees real Malli verdicts here
            ;; with no second require at app boot.
            [re-frame.schemas :as rf.schemas]
            [re-frame.adapter.reagent :as rf.adapter.reagent]
            [re-frame.test-support :as rf.test-support])
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
  (rf.test-support/make-reset-runtime-fixture
    {:adapter            rf.adapter.reagent/adapter
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
            emission therefore does NOT fire a TRACE in production.

            This pins the dual-elision contract: trace gate AND boundary
            gate both fold under the same closure-define. A registered
            trace callback would never see a boundary emission because
            the entire emit body has DCE'd. rf2-mwv4e keeps this pin
            deliberately: the trace is where the rejected VALUE and the
            validator's `:explain` ride, and those are precisely what a
            production build must not carry. The production REPORT lives
            on the always-on axis instead — see the deftest below."
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

(deftest boundary-rejection-is-observable-on-the-always-on-axis-in-prod
  (testing "rf2-mwv4e — the counterpart to the elision pin above, in the ONE
            configuration where it mattered most. Under `:advanced` +
            `goog.DEBUG=false` the boundary check runs, the handler is skipped,
            and the trace surface is gone. Until this bead that was the whole
            story: a refused untrusted payload emitted nothing anywhere, and
            the always-on `:events` record for the dispatch read `:outcome
            :ok` — a shipper saw a dispatch that succeeded.

            `register-error-listener!` carries no debug gate, so the structural
            record below survives the same closure-define that erased the
            trace. Red here means the promotion did not survive `:advanced`,
            which is the only build where it was needed."
    (rf/reg-event :api/strict
      {:schema [:cat [:= :api/strict] :int]
       :interceptors [:rf.schema/at-boundary]}
      (fn [_ _] {}))
    (let [errors (atom [])
          events (atom [])]
      (rf/register-listener! :errors ::rec (fn [r] (swap! errors conj r)))
      (rf/register-listener! :events ::rec (fn [r] (swap! events conj r)))
      (try
        (rf/dispatch-sync [:api/strict "not-an-int"])
        (finally
          (rf/unregister-listener! :errors ::rec)
          (rf/unregister-listener! :events ::rec)))
      (let [rec (first (filter #(= :rf.error/schema-validation-failure (:error %))
                               @errors))
            evt (first (filter #(= :api/strict (:event-id %)) @events))]
        (is (some? rec)
            "the always-on boundary record survived :advanced + goog.DEBUG=false")
        (is (= :boundary (:source rec)) ":source :boundary")
        (is (= :event (:where rec)) ":where :event")
        (is (= :api/strict (:event-id rec)) "attributed to the dispatch")
        ;; Structural-only: the rejected payload must not egress. The JVM
        ;; namespace `re-frame.always-on-validation-production-test` pins the
        ;; key set CLOSED; this arm pins the one fact `:advanced` could change.
        (is (not (re-find #"not-an-int" (pr-str rec)))
            "and carries NOTHING from the rejected payload")
        (is (= :rejected (:outcome evt))
            "the :events record reports :rejected, not the old :ok lie")))))

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
    (rf.schemas/set-schema-validator! nil)
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
        (rf.schemas/reset-schema-validator!)))))

;; ---- rf2-tiymn — the humanizer is NOT published in production ------------
;;
;; The adapter publishes `malli.error/humanize` under
;; `:schemas/humanize-explain!` inside `(when interop/debug-enabled? ...)`.
;; Under this build's `:advanced` + `goog.DEBUG=false` that form folds
;; away, so the hook is unbound while the validator hook from the same
;; ns-load is bound. `schemas_cljs_test.cljs` pins the dev dual (bound, and
;; a failure carries `:explain-humanized`); `scripts/check-schemas-bundle.cjs`
;; pins the bundle shape (the keyword never survives into the probe).

(deftest humanizer-unpublished-in-prod-cljs
  (testing "rf2-tiymn — under `:advanced` + `goog.DEBUG=false` the humanize
            hook is unbound while the validator hook is bound"
    (is (nil? (rf.late-bind/get-fn :schemas/humanize-explain!))
        "no humanizer in a production build")
    (is (fn? (rf.late-bind/get-fn :schemas/malli-validate))
        "the validator from the same adapter ns-load IS bound — the absence above is the gate, not a missing adapter")))

;; ---- rf2-6eh5h — a present NIL :schema cannot run a boundary handler -----
;;
;; Declaration presence is KEY-presence, not value truthiness. The
;; registrar accepts `{:schema nil :interceptors [:rf.schema/at-boundary]}`
;; (it checks `contains?`), and before rf2-6eh5h the boundary interceptor's
;; production branch treated nil as impossible and returned the context
;; unchanged — in THIS build configuration (step-1 DCE'd, boundary as the
;; only guard) the handler ran UNGUARDED on the untrusted payload the
;; interceptor exists to gate. These pins are the release-resident
;; regression: the boundary must delegate the exact nil token and reject.

(deftest boundary-rejects-explicit-nil-schema-in-prod
  (testing "rf2-6eh5h — under `:advanced` + `goog.DEBUG=false` a handler
            registered with {:schema nil} + the boundary interceptor does
            NOT run on dispatch: the nil delegates to default Malli, which
            fails CLOSED through the seam's malformed-schema isolation"
    (let [calls (atom 0)]
      (rf/reg-event :wire/received
        {:schema       nil
         :interceptors [:rf.schema/at-boundary]}
        (fn [_ _] (swap! calls inc) {}))
      (rf/dispatch-sync [:wire/received {:untrusted "payload"}])
      (is (= 0 @calls)
          "handler NOT invoked — the present-nil declaration fails closed, never fail-open"))))

(deftest boundary-delegates-nil-token-to-custom-validator-in-prod
  (testing "rf2-6eh5h — the production path hands the EXACT nil token to a
            substituted validator (the value is opaque per Spec 010); its
            false verdict rejects the event and the handler is not invoked"
    (let [seen  (atom [])
          calls (atom 0)]
      (rf.schemas/set-schema-validator!
        (fn [schema _value] (swap! seen conj schema) false))
      (try
        (rf/reg-event :wire/custom
          {:schema       nil
           :interceptors [:rf.schema/at-boundary]}
          (fn [_ _] (swap! calls inc) {}))
        (rf/dispatch-sync [:wire/custom {:untrusted 1}])
        (is (= 0 @calls) "handler NOT invoked — rejected on the false verdict")
        (is (= [nil] @seen)
            "the validator RECEIVED the exact nil token in the production path")
        (finally
          (rf.schemas/reset-schema-validator!))))))
