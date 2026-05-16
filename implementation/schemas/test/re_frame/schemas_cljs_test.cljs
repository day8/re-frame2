(ns re-frame.schemas-cljs-test
  "CLJS-side smoke for Spec 010 — schema validation runs at dispatch
  time under the Reagent reactive substrate.

  The JVM smoke (re-frame.schemas-test) covers the elision toggle and
  the error-projector → :rf/public-error mapping shape; this file
  confirms that under the Reagent adapter — the production substrate
  for browser apps — a live dispatch with a malformed :db commit
  surfaces a :rf.error/schema-validation-failure trace through the
  same path it would on the JVM. The fact that the trace reaches the
  registered callback under Reagent is what locks the cross-substrate
  contract.

  This is a smoke — one happy path, one violation path. The conformance
  fixtures (schema-app-db-slice-validates.edn et al.) cover the broader
  contract."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            ;; Per rf2-t0hq the CLJS default validator routes through
            ;; the late-bind hook `:schemas/malli-validate`, which the
            ;; `re-frame.schemas.malli` adapter ns publishes at load
            ;; time. The require is the canonical opt-in pattern for
            ;; CLJS apps that want Malli validation; without it, the
            ;; default validator soft-passes (per Spec 010
            ;; §Recommended soft-pass) and no failure trace fires.
            [re-frame.schemas.malli]
            [re-frame.core :as rf]
            ;; rf2-qwm0a: listener / buffer surface lives in re-frame.trace.tooling.
            [re-frame.trace.tooling :as trace-tooling]
            [re-frame.late-bind :as late-bind]
            [re-frame.schemas :as schemas]
            [re-frame.adapter.reagent :as reagent-adapter]
            [re-frame.test-support :as test-support]))

;; Snapshot/restore the registrar around each test (rf2-am9d). The earlier
;; (registrar/clear-all!) wiped framework registrations (routing,
;; machines) — fine while these tests ran in isolation, but hostile to
;; cross-ns CLJS test runs because CLJS cannot reload them. Snapshot/
;; restore preserves them and still rolls back per-test :app-schema /
;; reg-event-db / reg-sub on the way out.
;;
;; :clear-kinds [:app-schema] gives the test a clean :app-schema slate
;; per-test. Without this, nine-states.core's ns-load app-schemas
;; (registered for :todos and :new-todo) survive in the snapshot and
;; produce extra schema-validation-failure traces that this smoke
;; doesn't expect. The snapshot still holds those entries, so the
;; restore on the way out leaves nine-states.core's schemas intact for
;; downstream tests.
(use-fixtures :each
  (test-support/reset-runtime-fixture
    {:adapter     reagent-adapter/adapter
     :clear-kinds [:app-schema]}))

;; ---- live dispatch fires app-db schema validation -------------------------

(deftest live-dispatch-validates-app-db-under-reagent
  (testing "a malformed :db commit emits :rf.error/schema-validation-failure under the Reagent adapter"
    (rf/reg-app-schema [:n] [:int])
    (rf/reg-event-db :n/init  (fn [_ _] {:n 0}))
    (rf/reg-event-db :n/break (fn [db _] (assoc db :n "boom")))
    (let [traces (atom [])]
      (trace-tooling/register-trace-cb! ::cljs-live (fn [ev] (swap! traces conj ev)))
      (rf/dispatch-sync [:n/init])
      (rf/dispatch-sync [:n/break])
      (trace-tooling/remove-trace-cb! ::cljs-live)
      (let [violations (filter #(= :rf.error/schema-validation-failure
                                   (:operation %))
                               @traces)]
        (is (= 1 (count violations))
            "exactly one schema-validation-failure trace fired under Reagent")
        (let [v (first violations)]
          (is (= :app-db (-> v :tags :where))
              ":where :app-db locates the failure in the post-handler validation step")
          (is (= [:n] (-> v :tags :path))
              ":path names the registered slot")
          (is (= "boom" (-> v :tags :value))
              ":value carries the offending value verbatim")
          (is (= :n/break (-> v :tags :failing-id))
              ":failing-id names the handler whose commit prompted the failure"))))))

(deftest live-dispatch-well-typed-passes-silently
  (testing "well-typed :db commits trigger no schema-validation-failure trace"
    (rf/reg-app-schema [:n] [:int])
    (rf/reg-event-db :n/init (fn [_ _] {:n 0}))
    (rf/reg-event-db :n/inc  (fn [db _] (update db :n inc)))
    (let [traces (atom [])]
      (trace-tooling/register-trace-cb! ::cljs-ok (fn [ev] (swap! traces conj ev)))
      (rf/dispatch-sync [:n/init])
      (rf/dispatch-sync [:n/inc])
      (rf/dispatch-sync [:n/inc])
      (trace-tooling/remove-trace-cb! ::cljs-ok)
      (is (empty? (filter #(= :rf.error/schema-validation-failure
                              (:operation %))
                          @traces))
          "no validation-failure traces — every commit conforms"))))

;; ---- rf2-t0hq — Malli adapter late-bind seam ------------------------------
;;
;; Two contract tests pin the rf2-t0hq fix. The first asserts that with
;; the canonical opt-in (`(:require [re-frame.schemas.malli])` — done at
;; the top of this file) the default validator DOES consult Malli on
;; CLJS, so a malformed commit fires :rf.error/schema-validation-failure.
;; This is the contract the historical bug silently violated — the
;; `:cljs (resolve 'malli.core/validate)` runtime resolve returned nil
;; and every value was treated as conforming.
;;
;; The second test pins the soft-pass arm: with the late-bind hook
;; cleared (simulating an app that doesn't require `re-frame.schemas.
;; malli`), the default validator returns true for every value per
;; Spec 010 §Recommended soft-pass and no failure trace fires. We
;; restore the hook on the way out so downstream tests see the canonical
;; opt-in's behaviour.

(deftest rf2-t0hq-cljs-malli-adapter-enables-validation
  (testing "Per rf2-t0hq: with `re-frame.schemas.malli` required at
            app boot, the default validator consults Malli on CLJS and
            a malformed commit fires :rf.error/schema-validation-failure.
            The fix's load-bearing contract — historically the CLJS
            runtime `resolve` returned nil and Malli was never consulted,
            so this trace silently never fired."
    (rf/reg-app-schema [:user :age] :int)
    (rf/reg-event-db :user/set-age-bad
      (fn [db _] (assoc-in db [:user :age] "twenty-three")))
    (let [traces (atom [])]
      (trace-tooling/register-trace-cb! ::t0hq (fn [ev] (swap! traces conj ev)))
      (rf/dispatch-sync [:user/set-age-bad])
      (trace-tooling/remove-trace-cb! ::t0hq)
      (let [violations (filter #(= :rf.error/schema-validation-failure
                                   (:operation %))
                               @traces)]
        (is (= 1 (count violations))
            "the default Malli validator fired on CLJS via the late-bind
             hook — this is the contract rf2-t0hq restored")
        (let [v (first violations)]
          (is (= :app-db (-> v :tags :where)))
          (is (= [:user :age] (-> v :tags :path)))
          (is (= "twenty-three" (-> v :tags :value))))))))

(deftest rf2-t0hq-cljs-no-adapter-soft-passes
  (testing "Per Spec 010 §Recommended soft-pass: when an app does NOT
            require `re-frame.schemas.malli`, the late-bind hook
            `:schemas/malli-validate` is absent, the default validator
            returns true for every value, and no failure trace fires.
            We simulate the no-adapter case by temporarily clearing the
            hook — the implementation must take its soft-pass arm."
    (let [prior-v (late-bind/get-fn :schemas/malli-validate)
          prior-e (late-bind/get-fn :schemas/malli-explain)]
      (late-bind/set-fn! :schemas/malli-validate nil)
      (late-bind/set-fn! :schemas/malli-explain  nil)
      (try
        (rf/reg-app-schema [:n] :int)
        (rf/reg-event-db :n/break (fn [db _] (assoc db :n "definitely-not-an-int")))
        (let [traces (atom [])]
          (trace-tooling/register-trace-cb! ::no-adapter (fn [ev] (swap! traces conj ev)))
          (rf/dispatch-sync [:n/break])
          (trace-tooling/remove-trace-cb! ::no-adapter)
          (is (empty? (filter #(= :rf.error/schema-validation-failure
                                  (:operation %))
                              @traces))
              "soft-pass arm — no validator hook, no failure trace, the
               malformed commit silently 'conforms' to the spec's
               new-user-friendly default"))
        (finally
          (late-bind/set-fn! :schemas/malli-validate prior-v)
          (late-bind/set-fn! :schemas/malli-explain  prior-e))))))

;; ---- rf2-jwm4 — event-payload validation under Reagent -------------------
;;
;; Smokes the validate-event! pre-handler wiring (rf2-jwm4) under the
;; Reagent reactive substrate. The JVM smoke covers the broader pre-
;; handler / sub-return / cofx contract; this CLJS path locks the
;; cross-substrate behaviour for at least one of the three new wirings.

(deftest live-dispatch-validates-event-payload-under-reagent
  (testing "a malformed event payload skips the handler and emits :where :event"
    (let [calls (atom 0)]
      (rf/reg-event-db :user/register
        {:spec [:cat [:= :user/register]
                     [:map [:email :string] [:age :int]]]}
        (fn [db _]
          (swap! calls inc)
          db))
      (let [traces (atom [])]
        (trace-tooling/register-trace-cb! ::cljs-ev (fn [ev] (swap! traces conj ev)))
        ;; Well-typed payload — handler runs.
        (rf/dispatch-sync [:user/register {:email "a@b.com" :age 30}])
        ;; Malformed — handler must NOT run.
        (rf/dispatch-sync [:user/register {:email "c@d.com" :age "no"}])
        (trace-tooling/remove-trace-cb! ::cljs-ev)
        (is (= 1 @calls)
            "handler ran exactly once — the bad payload was rejected pre-handler")
        (let [violations (filter #(= :rf.error/schema-validation-failure
                                     (:operation %))
                                 @traces)]
          (is (= 1 (count violations))
              "exactly one schema-validation-failure trace fired under Reagent")
          (let [v (first violations)]
            (is (= :event (-> v :tags :where))
                ":where :event locates the failure at pre-handler validation")
            (is (= :user/register (-> v :tags :failing-id)))))))))

;; ---- rf2-0z1z — app-schemas-digest under CLJS ----------------------------

(deftest app-schemas-digest-cljs-smoke
  (testing "Per Spec 010 §Digest algorithm (rf2-0z1z): the digest fn
            is wired under CLJS (goog.crypt.Sha256) and produces the
            canonical wire form."
    (rf/reg-app-schema [:user]  [:map [:id :uuid]])
    (rf/reg-app-schema [:todos] [:vector :string])
    (let [d (rf/app-schemas-digest)]
      (is (string? d)
          "digest returns a string")
      (is (re-matches #"sha256:[0-9a-f]{16}" d)
          "digest matches \"sha256:\" + 16 lowercase hex chars")
      ;; Empty schema set — registered against a frame with no schemas
      ;; — produces the well-defined empty-set digest.
      (rf/reg-frame :test/empty-cljs {})
      (is (= "sha256:e3b0c44298fc1c14"
             (rf/app-schemas-digest :test/empty-cljs))
          "empty-set digest is byte-identical with the JVM path"))))

;; ---- rf2-froe — set-schema-validator! seam under CLJS ---------------------

(deftest custom-validator-runs-under-reagent
  (testing "Per Spec 010 §Non-Malli validators (rf2-froe): a custom
            validator installed via rf/set-schema-validator! is invoked
            on the Reagent reactive substrate just as it is on the JVM.
            Locks the cross-substrate seam contract."
    (let [calls (atom 0)
          custom (fn [_s v] (swap! calls inc) (= v 42))]
      (rf/set-schema-validator! custom)
      (try
        (rf/reg-app-schema [:n] :int)
        (rf/reg-event-db :n/init  (fn [_ _] {:n 42}))
        (rf/reg-event-db :n/break (fn [db _] (assoc db :n 99)))
        (let [traces (atom [])]
          (trace-tooling/register-trace-cb! ::cv (fn [ev] (swap! traces conj ev)))
          (rf/dispatch-sync [:n/init])
          (rf/dispatch-sync [:n/break])
          (trace-tooling/remove-trace-cb! ::cv)
          (is (pos? @calls)
              "the custom validator was invoked through the live dispatch path")
          (let [violations (filter #(= :rf.error/schema-validation-failure
                                       (:operation %))
                                   @traces)]
            (is (= 1 (count violations))
                "the custom validator's falsey result fired the failure trace once
                 — for the :n/break commit; :n/init's value of 42 passed.")))
        (finally
          (schemas/reset-schema-validator!))))))

(deftest nil-validator-disables-reagent-validation
  (testing "Per Spec 010 §Non-Malli validators (rf2-froe): nil validator
            disables every validation site under Reagent — including
            the live :db commit that would otherwise fire."
    (rf/set-schema-validator! nil)
    (try
      (rf/reg-app-schema [:n] :int)
      (rf/reg-event-db :n/break (fn [db _] (assoc db :n "definitely-not-an-int")))
      (let [traces (atom [])]
        (trace-tooling/register-trace-cb! ::nv (fn [ev] (swap! traces conj ev)))
        (rf/dispatch-sync [:n/break])
        (trace-tooling/remove-trace-cb! ::nv)
        (is (empty? (filter #(= :rf.error/schema-validation-failure
                                (:operation %))
                            @traces))
            "no validation trace fires when the validator is nil"))
      (finally
        (schemas/reset-schema-validator!)))))

;; ---- rf2-r2uh — :spec/at-boundary dev-mode no-op (rf2-84e9) --------------
;;
;; The :node-test build compiles with `goog.DEBUG=true` (cljs default,
;; no closure-define override) — the runtime-equivalent of a dev build.
;; Per Spec 010 §Production builds (L145), in dev the boundary
;; interceptor is a no-op: the router's step-1 validate-event! call has
;; already run, and running validation a second time would just duplicate
;; the trace.
;;
;; A complementary `:browser-test` build (`:browser-test-schemas-boundary-prod`)
;; compiles `schemas_boundary_prod_test.cljs` under `:advanced` +
;; `goog.DEBUG=false`, where Closure constant-folds the dev gate to false
;; and the boundary takes its production validation branch. The pair of
;; CLJS smokes pins the cross-substrate dev/prod-gate contract that the
;; JVM tests cover via `with-redefs spec/dev-mode?` (which cannot prove
;; the genuine `:advanced` constant-fold).

(deftest boundary-interceptor-noop-in-dev-cljs
  (testing "Per Spec 010 §Production builds (rf2-r2uh): under `:node-test`
            (goog.DEBUG=true) the boundary interceptor's :before slot is
            a no-op even on a malformed event — it does NOT set
            :rf/skip-handler? and does NOT emit a boundary-tagged trace.
            Step-1 validation in the router is what enforces the schema
            in dev; the boundary interceptor's prod-mode body never runs."
    (rf/reg-event-fx :api/strict
      {:spec [:cat [:= :api/strict] :int]}
      [rf/at-boundary]
      (fn [_ _] {}))
    (let [traces (atom [])]
      (trace-tooling/register-trace-cb! ::boundary-dev (fn [ev] (swap! traces conj ev)))
      ;; Direct :before invocation isolates the boundary's behaviour
      ;; from the surrounding router/step-1 path so we observe the
      ;; boundary's own dev-mode contract.
      (let [before    (:before rf/at-boundary)
            valid-ctx (before {:coeffects {:event [:api/strict 42]}})
            bad-ctx   (before {:coeffects {:event [:api/strict "not-an-int"]}})]
        (trace-tooling/remove-trace-cb! ::boundary-dev)
        (is (not (:rf/skip-handler? valid-ctx))
            "valid event: boundary did not set :rf/skip-handler? (no-op)")
        (is (not (:rf/skip-handler? bad-ctx))
            "MALFORMED event in dev: boundary STILL did not set :rf/skip-handler? — the dev-mode no-op contract")
        (let [boundary-violations (filter #(and (= :rf.error/schema-validation-failure (:operation %))
                                                (= :boundary (-> % :tags :source)))
                                          @traces)]
          (is (empty? boundary-violations)
              "no boundary-tagged trace fired — boundary was a no-op in dev"))))))

(deftest boundary-interceptor-dev-dispatch-skips-via-step-1
  (testing "Per Spec 010 §Production builds (rf2-r2uh): under `:node-test`
            (goog.DEBUG=true) a full dispatch of a malformed payload
            still skips the handler — but via the router's step-1
            validate-event! path, NOT via the boundary. The boundary's
            contract is silent in dev. We observe the handler-skip
            (router did its job) and the absence of a :source :boundary
            trace tag (boundary itself stayed quiet)."
    (let [calls (atom 0)]
      (rf/reg-event-fx :api/strict
        {:spec [:cat [:= :api/strict] :int]}
        [rf/at-boundary]
        (fn [_ _] (swap! calls inc) {}))
      (let [traces (atom [])]
        (trace-tooling/register-trace-cb! ::dev-dispatch (fn [ev] (swap! traces conj ev)))
        (rf/dispatch-sync [:api/strict "not-an-int"])
        (trace-tooling/remove-trace-cb! ::dev-dispatch)
        (is (= 0 @calls)
            "handler was skipped — router's step-1 validation fired in dev")
        (let [boundary-violations (filter #(and (= :rf.error/schema-validation-failure (:operation %))
                                                (= :boundary (-> % :tags :source)))
                                          @traces)]
          (is (empty? boundary-violations)
              "no :source :boundary trace fired — only the dev-mode step-1 trace ran"))))))
