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
;; restore preserves them and still rolls back per-test app-schema /
;; reg-event / reg-sub on the way out.
;;
;; `:clear-app-schemas? true` gives the test a clean app-schema slate
;; per-test (per rf2-cq1ak app-db schemas live OUTSIDE the registrar in
;; the schemas artefact's per-frame side-table). Without this,
;; nine-states.core's ns-load app-schemas (registered for :todos and
;; :new-todo) survive in the snapshot and produce extra schema-
;; validation-failure traces that this smoke doesn't expect. The
;; snapshot still holds those entries, so the restore on the way out
;; leaves nine-states.core's schemas intact for downstream tests.
(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter            reagent-adapter/adapter
     :clear-app-schemas? true}))

;; ---- live dispatch fires app-db schema validation -------------------------

(deftest live-dispatch-validates-app-db-under-reagent
  (testing "a malformed :db commit emits :rf.error/schema-validation-failure under the Reagent adapter"
    (rf/reg-app-schema [:n] {:schema [:int]})
    (rf/reg-event :n/init  (fn [{:keys [db]} _] {:db {:n 0}}))
    (rf/reg-event :n/break (fn [{:keys [db]} _] {:db (assoc db :n "boom")}))
    (let [traces (atom [])]
      (trace-tooling/register-listener! ::cljs-live (fn [ev] (swap! traces conj ev)))
      (rf/dispatch-sync [:n/init])
      (rf/dispatch-sync [:n/break])
      (trace-tooling/unregister-listener! ::cljs-live)
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
    (rf/reg-app-schema [:n] {:schema [:int]})
    (rf/reg-event :n/init (fn [{:keys [db]} _] {:db {:n 0}}))
    (rf/reg-event :n/inc  (fn [{:keys [db]} _] {:db (update db :n inc)}))
    (let [traces (atom [])]
      (trace-tooling/register-listener! ::cljs-ok (fn [ev] (swap! traces conj ev)))
      (rf/dispatch-sync [:n/init])
      (rf/dispatch-sync [:n/inc])
      (rf/dispatch-sync [:n/inc])
      (trace-tooling/unregister-listener! ::cljs-ok)
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

(deftest cljs-malli-adapter-enables-validation
  (testing "Per rf2-t0hq: with `re-frame.schemas.malli` required at
            app boot, the default validator consults Malli on CLJS and
            a malformed commit fires :rf.error/schema-validation-failure.
            The fix's load-bearing contract — historically the CLJS
            runtime `resolve` returned nil and Malli was never consulted,
            so this trace silently never fired."
    (rf/reg-app-schema [:user :age] {:schema :int})
    (rf/reg-event :user/set-age-bad
      (fn [{:keys [db]} _] {:db (assoc-in db [:user :age] "twenty-three")}))
    (let [traces (atom [])]
      (trace-tooling/register-listener! ::t0hq (fn [ev] (swap! traces conj ev)))
      (rf/dispatch-sync [:user/set-age-bad])
      (trace-tooling/unregister-listener! ::t0hq)
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

(deftest cljs-no-adapter-soft-passes
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
        (rf/reg-app-schema [:n] {:schema :int})
        (rf/reg-event :n/break (fn [{:keys [db]} _] {:db (assoc db :n "definitely-not-an-int")}))
        (let [traces (atom [])]
          (trace-tooling/register-listener! ::no-adapter (fn [ev] (swap! traces conj ev)))
          (rf/dispatch-sync [:n/break])
          (trace-tooling/unregister-listener! ::no-adapter)
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
      (rf/reg-event :user/register
        {:schema [:cat [:= :user/register]
                       [:map [:email :string] [:age :int]]]}
        (fn [{:keys [db]} _]
          (swap! calls inc)
          {:db db}))
      (let [traces (atom [])]
        (trace-tooling/register-listener! ::cljs-ev (fn [ev] (swap! traces conj ev)))
        ;; Well-typed payload — handler runs.
        (rf/dispatch-sync [:user/register {:email "a@b.com" :age 30}])
        ;; Malformed — handler must NOT run.
        (rf/dispatch-sync [:user/register {:email "c@d.com" :age "no"}])
        (trace-tooling/unregister-listener! ::cljs-ev)
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

;; ---- rf2-lo28u — `:cat` + bare-predicate event-args schema --------------
;;
;; Regression for the standard_epochs button-18 symptom (rf2-lo28u): an
;; event declared with `:schema [:cat [:= :id] pos-int?]` dispatched
;; with a string where a `pos-int?` is required MUST fire
;; `:rf.error/schema-validation-failure :where :event` and SKIP the
;; handler. This pins the exact schema SHAPE the testbed uses — a
;; `:cat` whose tail slot is a bare CLJS predicate function (`pos-int?`,
;; a registered Malli predicate keyed by its function value) — so a
;; regression that makes the bare-predicate `:cat` form silently
;; soft-pass (or throw + be swallowed by the router's catch) is caught
;; in CLJS, not just at the JVM. The earlier
;; `live-dispatch-validates-event-payload-under-reagent` test uses a
;; `[:map ...]` tail; this one uses the bare-predicate tail the
;; testbed's button 18 carries verbatim.

(deftest cat-with-bare-predicate-event-args-fires-where-event
  (testing "a `:cat` schema with a bare `pos-int?` tail rejects a bad
            arg, skips the handler, and emits :where :event"
    (let [calls (atom 0)]
      (rf/reg-event :rf2-lo28u/bad-event-args
        {:schema [:cat [:= :rf2-lo28u/bad-event-args] pos-int?]}
        (fn [{:keys [db]} _ev] (swap! calls inc) {:db db}))
      (let [traces (atom [])]
        (trace-tooling/register-listener! ::lo28u (fn [ev] (swap! traces conj ev)))
        ;; Well-typed arg (a pos-int) — handler runs.
        (rf/dispatch-sync [:rf2-lo28u/bad-event-args 7])
        ;; Bad arg (a string where a pos-int is required) — handler must
        ;; NOT run; a :where :event violation must fire.
        (rf/dispatch-sync [:rf2-lo28u/bad-event-args "not-a-number"])
        (trace-tooling/unregister-listener! ::lo28u)
        (is (= 1 @calls)
            "handler ran exactly once — the bad arg was rejected pre-handler")
        (let [violations (filter #(= :rf.error/schema-validation-failure
                                     (:operation %))
                                 @traces)]
          (is (= 1 (count violations))
              "exactly one schema-validation-failure trace fired for the bad arg")
          (let [v (first violations)]
            (is (= :event (-> v :tags :where))
                ":where :event locates the failure at pre-handler validation")
            (is (= :rf2-lo28u/bad-event-args (-> v :tags :failing-id)))))))))

;; ---- rf2-lo28u — DIAGNOSTIC: app-schema present + dispatch-sync ----------
;; Isolates the app-schema-registered variable (the live-wiring difference
;; from the passing synthetic test) WITHOUT the async confound.
(deftest diag-app-schema-present-sync-bad-event-args
  (testing "DIAGNOSTIC — with an app-db schema registered for the frame
            (live wiring), a dispatch-SYNC bad event arg"
    (rf/reg-app-schema [:auth] {:schema [:map [:token :string]]})
    (let [calls (atom 0)]
      (rf/reg-event :rf2-lo28u/diag-bad-event-args
        {:schema [:cat [:= :rf2-lo28u/diag-bad-event-args] pos-int?]}
        (fn [{:keys [db]} _ev] (swap! calls inc) {:db db}))
      (let [traces (atom [])]
        (trace-tooling/register-listener! ::lo28u-diag (fn [ev] (swap! traces conj ev)))
        (rf/dispatch-sync [:rf2-lo28u/diag-bad-event-args "not-a-number"])
        (trace-tooling/unregister-listener! ::lo28u-diag)
        (let [event-violations (filter #(and (= :rf.error/schema-validation-failure (:operation %))
                                             (= :event (-> % :tags :where)))
                                       @traces)]
          (is (= 0 @calls) "DIAG: handler skipped?")
          (is (= 1 (count event-violations)) "DIAG: :where :event fired?"))))))

;; ---- rf2-lo28u — FAITHFUL LIVE-WIRING repro (async dispatch) -------------
;;
;; The two tests above use `dispatch-sync`. The standard_epochs testbed's
;; button 18 uses the ASYNC `(rf/dispatch event)` form, AND the testbed has
;; an app-db schema registered for the frame (button 19's `[:auth]`). Mike's
;; live check: pressing button 18 fires NO `:where :event` violation in any
;; epoch — the handler runs and the bad string passes straight through.
;;
;; The faithful difference is the ASYNC enqueue path. To exercise an
;; async-enqueued event through a REAL router drain (not dispatch-sync's
;; front-seed bypass) WITHOUT the cljs.test/async + `:each`-fixture teardown
;; race (the fixture's `finally` wipes `frame/frames` before a `nextTick`
;; drain fires, so a pure `poll-until` repro is a fixture artefact, not the
;; bug), we (a) `rf/dispatch` the bad event so it lands at the BACK of the
;; queue exactly as a live button-click would, then (b) drive the drain to
;; fixed point synchronously by seeding a no-op via `dispatch-sync`. The
;; sync drain dequeues the already-queued bad event through the identical
;; `process-event! -> run-handler-pipeline!` path the async nextTick drain
;; uses — so this faithfully reproduces "a queued (async-dispatched) event
;; drains" while staying deterministic in the node fixture.
;;
;; RED (pre-fix): no `:where :event` violation, handler ran (matches Mike).
;; GREEN (post-fix): the violation fires and the handler is skipped.

(deftest async-dispatch-bad-event-args-fires-where-event-live-wiring
  (testing "an ASYNC-dispatched (back-of-queue) bad event arg under a frame
            that ALSO has an app-db schema registered (the live
            standard_epochs wiring) fires :rf.error/schema-validation-failure
            :where :event and skips the handler when the queue drains"
    ;; Mirror the live testbed: an app-db schema is registered for this
    ;; frame (button 19). The bad-event-args event is registered with the
    ;; inline `:schema` meta verbatim from button 18.
    (rf/reg-app-schema [:auth] {:schema [:map [:token :string]]})
    (let [calls (atom 0)]
      (rf/reg-event :rf2-lo28u/async-bad-event-args
        {:schema [:cat [:= :rf2-lo28u/async-bad-event-args] pos-int?]}
        (fn [{:keys [db]} _ev] (swap! calls inc) {:db (assoc db :baseline 1)}))
      (rf/reg-event :rf2-lo28u/noop (fn [{:keys [db]} _] {:db db}))
      (let [traces (atom [])]
        (trace-tooling/register-listener! ::lo28u-async (fn [ev] (swap! traces conj ev)))
        ;; (a) ASYNC dispatch — lands at the BACK of the queue (live button path).
        (rf/dispatch [:rf2-lo28u/async-bad-event-args "not-a-number"])
        ;; (b) Drain to fixed point: the queued bad event is dequeued by the
        ;; real drain through the same cascade the nextTick drain would use.
        (rf/dispatch-sync [:rf2-lo28u/noop])
        (trace-tooling/unregister-listener! ::lo28u-async)
        (let [violations (filter #(= :rf.error/schema-validation-failure (:operation %)) @traces)
              event-violations (filter #(= :event (-> % :tags :where)) violations)]
          (is (= 0 @calls)
              "handler must be SKIPPED — the bad arg was rejected pre-handler
               (RED here: handler ran)")
          (is (= 1 (count event-violations))
              "exactly one :where :event schema-validation-failure fired for
               the async-queued event (RED here: zero)")
          (when-let [v (first event-violations)]
            (is (= :rf2-lo28u/async-bad-event-args (-> v :tags :failing-id)))))))))

;; ---- rf2-0z1z — app-schemas-digest under CLJS ----------------------------

(deftest app-schemas-digest-cljs-smoke
  (testing "Per Spec 010 §Digest algorithm (rf2-0z1z): the digest fn
            is wired under CLJS (goog.crypt.Sha256) and produces the
            canonical wire form."
    (rf/reg-app-schema [:user]  {:schema [:map [:id :uuid]]})
    (rf/reg-app-schema [:todos] {:schema [:vector :string]})
    (let [d (schemas/app-schemas-digest)]
      (is (string? d)
          "digest returns a string")
      (is (re-matches #"sha256:[0-9a-f]{16}" d)
          "digest matches \"sha256:\" + 16 lowercase hex chars")
      ;; Empty schema set — registered against a frame with no schemas
      ;; — produces the well-defined empty-set digest.
      (rf/reg-frame :test/empty-cljs {})
      (is (= "sha256:e3b0c44298fc1c14"
             (schemas/app-schemas-digest :test/empty-cljs))
          "empty-set digest is byte-identical with the JVM path"))))

;; ---- rf2-froe — set-schema-validator! seam under CLJS ---------------------

(deftest custom-validator-runs-under-reagent
  (testing "Per Spec 010 §Non-Malli validators (rf2-froe): a custom
            validator installed via rf/set-schema-validator! is invoked
            on the Reagent reactive substrate just as it is on the JVM.
            Locks the cross-substrate seam contract."
    (let [calls (atom 0)
          custom (fn [_s v] (swap! calls inc) (= v 42))]
      (schemas/set-schema-validator! custom)
      (try
        (rf/reg-app-schema [:n] {:schema :int})
        (rf/reg-event :n/init  (fn [{:keys [db]} _] {:db {:n 42}}))
        (rf/reg-event :n/break (fn [{:keys [db]} _] {:db (assoc db :n 99)}))
        (let [traces (atom [])]
          (trace-tooling/register-listener! ::cv (fn [ev] (swap! traces conj ev)))
          (rf/dispatch-sync [:n/init])
          (rf/dispatch-sync [:n/break])
          (trace-tooling/unregister-listener! ::cv)
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
    (schemas/set-schema-validator! nil)
    (try
      (rf/reg-app-schema [:n] {:schema :int})
      (rf/reg-event :n/break (fn [{:keys [db]} _] {:db (assoc db :n "definitely-not-an-int")}))
      (let [traces (atom [])]
        (trace-tooling/register-listener! ::nv (fn [ev] (swap! traces conj ev)))
        (rf/dispatch-sync [:n/break])
        (trace-tooling/unregister-listener! ::nv)
        (is (empty? (filter #(= :rf.error/schema-validation-failure
                                (:operation %))
                            @traces))
            "no validation trace fires when the validator is nil"))
      (finally
        (schemas/reset-schema-validator!)))))

;; ---- rf2-r2uh — :rf.schema/at-boundary dev-mode no-op (rf2-84e9) ---------
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
    (rf/reg-event :api/strict
      {:schema [:cat [:= :api/strict] :int]
       :interceptors [:rf.schema/at-boundary]}
      (fn [_ _] {}))
    (let [traces (atom [])]
      (trace-tooling/register-listener! ::boundary-dev (fn [ev] (swap! traces conj ev)))
      ;; Direct :before invocation isolates the boundary's behaviour
      ;; from the surrounding router/step-1 path so we observe the
      ;; boundary's own dev-mode contract.
      (let [before    (:before rf/validate-at-boundary-interceptor)
            valid-ctx (before {:coeffects {:event [:api/strict 42]}})
            bad-ctx   (before {:coeffects {:event [:api/strict "not-an-int"]}})]
        (trace-tooling/unregister-listener! ::boundary-dev)
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
      (rf/reg-event :api/strict
        {:schema [:cat [:= :api/strict] :int]
         :interceptors [:rf.schema/at-boundary]}
        (fn [_ _] (swap! calls inc) {}))
      (let [traces (atom [])]
        (trace-tooling/register-listener! ::dev-dispatch (fn [ev] (swap! traces conj ev)))
        (rf/dispatch-sync [:api/strict "not-an-int"])
        (trace-tooling/unregister-listener! ::dev-dispatch)
        (is (= 0 @calls)
            "handler was skipped — router's step-1 validation fired in dev")
        (let [boundary-violations (filter #(and (= :rf.error/schema-validation-failure (:operation %))
                                                (= :boundary (-> % :tags :source)))
                                          @traces)]
          (is (empty? boundary-violations)
              "no :source :boundary trace fired — only the dev-mode step-1 trace ran"))))))
