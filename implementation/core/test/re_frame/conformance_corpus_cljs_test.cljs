(ns re-frame.conformance-corpus-cljs-test
  "CLJS LEAF of the conformance corpus runner (rf2-xurchk).

  All host-neutral logic — capability claims, fixture realisation, call
  execution, expectation matchers, result assembly, and reporting — lives in
  `re-frame.conformance-runner` (a test-only `.cljc` shared byte-for-byte
  with the JVM leaf `re-frame.conformance-test`). Before the rf2-xurchk
  consolidation this file DUPLICATED that logic and DRIFTED: it never
  defined `check-epoch-records`, so the eight runnable `:epoch-records`
  fixtures were SILENTLY IGNORED on CLJS while reporting conformance. The
  shared runner closes that gap — CLJS now evaluates every `:epoch-records`
  fixture, and an unknown `:fixture/expect` key fails loud rather than being
  ignored.

  This leaf owns only the genuinely host-specific seams handed to the runner
  as a HOST MAP:

    - FIXTURE LOADING — at compile time via the `conformance-fixtures` macro
      (the .edn files are inlined into the CLJS bytecode; no runtime fs).
    - RESET / ISOLATION — snapshot/restore the registrar between fixtures
      (CLJS has no `(require :reload)` analogue, so wiping the registrar
      would permanently lose framework registrations), plus the epoch
      ring/listener clear, adapter dispose/init, and trace-baseline restore.
    - TRACE-LISTENER registry access — `re-frame.trace.tooling` (the
      production-DCE split; the JVM leaf uses `re-frame.trace`).

  The requires below are the CLJS classpath + ns-load side-effect surface the
  corpus exercises; most handler calls now live in the shared runner, which
  pulls its own deps (including `re-frame.epoch`, so the epoch hooks publish
  on CLJS too)."
  (:require [cljs.test :refer-macros [deftest is]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.registrar :as registrar]
            [re-frame.flows :as flows]
            [re-frame.schemas :as schemas]
            [re-frame.subs :as subs]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.substrate.adapter :as substrate-adapter]
            [re-frame.trace :as trace]
            [re-frame.error-emit :as error-emit]
            [re-frame.late-bind :as late-bind]
            [re-frame.routing :as routing]
            ;; rf2-dbiv8 — the test-only `:rf.test/simulate-http-resolution`
            ;; fixture event lives in this test-support ns; require here so it
            ;; registers at ns-load (CLJS has no `:reload`, so it must be live
            ;; before `pretest-registrar` snapshots it).
            [re-frame.routing.test-support]
            [re-frame.machines :as machines]
            ;; Spec 014 — :rf.http/managed registers at ns-load; reset uses its
            ;; clear-* fns.
            [re-frame.http.managed :as http-managed]
            ;; rf2-cdmle — canned-stub fxs gate on explicit test-support require.
            [re-frame.http.test-support]
            ;; Spec 016 §Resources (rf2-rul3ov) — the host-cache reset.
            [re-frame.resources.test-support :as resources-test-support]
            ;; The shared, host-neutral runner (rf2-xurchk).
            [re-frame.conformance-runner :as runner])
  ;; Compile-time fixture inlining (see conformance_fixtures.clj). The macro
  ;; ns is .clj — shadow-cljs picks it up via :require-macros.
  (:require-macros [re-frame.conformance-fixtures :refer [all-fixtures]]))

;; ---- fixture loading (compile-time inlined) -------------------------------

(def fixtures
  "Vector of `[filename fixture-map]` pairs, materialised at compile time by
  `re-frame.conformance-fixtures/all-fixtures`. Sorted by filename so
  reporting order is stable."
  (all-fixtures))

;; ---- baseline snapshots ---------------------------------------------------
;;
;; Two snapshots, captured at DIFFERENT times — both load-bearing.
;;
;;  * `baseline-trace-listeners` is captured at NS-LOAD. The SSR artefact
;;    registers its error-projection-listener at ns-load; other test
;;    namespaces' `use-fixtures` blocks call `(trace/clear-listeners!)`, so
;;    by the time our deftest runs the registry may be empty. Capturing at
;;    ns-load is the only point at which the framework listeners are live.
;;
;;  * `pretest-registrar` is captured at DEFTEST START (lazy). Other example
;;    apps register handlers at ns-load; CLJS has no `(require :reload)`, so
;;    wiping the registrar before them would permanently destroy those
;;    registrations. Capturing AT DEFTEST START guarantees our inter-fixture
;;    reset doesn't strand them.

(def ^:private baseline-trace-listeners
  ;; Per rf2-qwm0a the listener registry atom moved from
  ;; `re-frame.trace/listeners` to `re-frame.trace.tooling/listeners` (the
  ;; production-DCE split). This access is the CLJS bridge (the JVM leaf
  ;; achieves the same effect via `(require 're-frame.ssr :reload)`).
  @re-frame.trace.tooling/listeners)

(def ^:private pretest-registrar
  ;; Mutable cell, set on deftest / self-test entry.
  (atom nil))

;; ---- runtime reset (CLJS-specific: snapshot/restore) ----------------------

(defn- reset-runtime! []
  ;; 1. Roll the registrar back to the pretest-snapshot, then drop `:route`
  ;;    specifically (example apps register routes at ns-load whose rank
  ;;    tuples can collide with the fixture's equal-score cases). The fixture
  ;;    re-registers every route it needs.
  (reset! registrar/kind->id->metadata @pretest-registrar)
  (registrar/clear-kind! :route)
  ;; 2. Clear per-process state held outside the registrar.
  (reset! frame/frames {})
  (flows/reset-flows!)
  (schemas/clear-schemas-by-frame!)
  ;; 3. Reset id-allocators so routing / machine fixtures see deterministic
  ;;    counters.
  (routing/reset-counters!)
  ;; rf2-oosjmh — the nav-token / pending-nav counters are host-side transient
  ;; state now, so the `frames` reset above no longer clears them.
  (routing/reset-nav-counters!)
  (machines/reset-timers!)
  ;; 4. Drop the in-flight HTTP request registry between fixtures.
  (http-managed/clear-all-in-flight!)
  ;; 4a. Spec 014 §Middleware (rf2-yhfgf) — drop the per-frame request-side
  ;;     interceptor chain (a `defonce` atom).
  (http-managed/clear-all-http-interceptors!)
  ;; 5. Dispose the currently-installed adapter and re-install plain-atom.
  (substrate-adapter/dispose-adapter!)
  (rf/init! plain-atom/adapter)
  ;; 6. Restore the baseline trace-listener set (preserves the SSR
  ;;    error-projection listener while dropping per-fixture listeners).
  (reset! re-frame.trace.tooling/listeners baseline-trace-listeners)
  ;; 7. rf2-wxe9t — drop every corpus-wide error-emit listener so a recorder
  ;;    installed for one fixture can't fire against the next fixture's drains.
  (error-emit/clear-error-listeners!)
  ;; 7a. rf2-v0jwt / rf2-xurchk — drop the per-frame epoch ring buffer (and
  ;;     the in-flight capture buffer) between fixtures so `:epoch-records`
  ;;     assertions observe THIS fixture's recorded epochs only. Pre-rf2-xurchk
  ;;     the CLJS runner never checked `:epoch-records`, so it never cleared
  ;;     the ring — now the shared runner asserts against it, this clear is
  ;;     the CLJS counterpart of the JVM reset's epoch clear.
  (when-let [f (late-bind/get-fn :epoch/clear-history!)]
    (f))
  (when-let [f (late-bind/get-fn :epoch/clear-epoch-listeners!)]
    (f))
  ;; 8. Spec 016 §Resources (rf2-rul3ov) — drop the resource host-side caches
  ;;    so each `resources-*.edn` fixture's first load mints generation 1.
  (resources-test-support/reset-resources!))

;; ---- host map --------------------------------------------------------------

(def ^:private host
  "The genuinely host-specific seams handed to the shared runner. Trace
  registry access uses `re-frame.trace.tooling` (the production-DCE split);
  the fixture-end cleanup unregisters ONLY this fixture's listener so the
  framework SSR error-projection listener survives (reset-runtime! restores
  the full baseline at the next fixture's start)."
  {:reset-runtime!             reset-runtime!
   :register-trace-listener!   (fn [fixture-id listener]
                                 (re-frame.trace.tooling/register-listener! [fixture-id] listener))
   :unregister-trace-listener! (fn [fixture-id]
                                 (re-frame.trace.tooling/unregister-listener! fixture-id))})

;; ---- the test entrypoint --------------------------------------------------

(deftest run-conformance-corpus-cljs
  ;; Capture the live registrar NOW (after every example / framework ns-load
  ;; has registered). try/finally restores it even if a fixture assertion
  ;; throws mid-suite, leaving subsequent namespaces' state intact.
  (reset! pretest-registrar @registrar/kind->id->metadata)
  (try
    (runner/run-corpus fixtures host "CLJS")
    (finally
      (reset! registrar/kind->id->metadata @pretest-registrar))))

;; ---- rf2-xurchk acceptance self-tests -------------------------------------
;;
;; The CLJS counterpart of the JVM leaf's self-tests: prove the shared runner
;; BITES on CLJS rather than silently ignoring expectations — the exact
;; drift this consolidation fixes.

;; A single-drain counter fixture (mirror of epoch-record-shape.edn) whose
;; `:epoch-records` expectation is DELIBERATELY WRONG. Pre-rf2-xurchk CLJS
;; ignored `:epoch-records`, so this would have PASSED; the shared runner
;; MUST now fail it on CLJS too.
(def ^:private epoch-mismatch-fixture
  {:fixture/id           :rf.test/epoch-records-deliberate-mismatch
   :fixture/spec-version "1.0"
   :fixture/capabilities #{:core/event-handler :core/trace}
   :fixture/handlers     {:event {:counter/inc [[:update [:count] [:fn :inc]]]}}
   :fixture/frame-config {}
   :fixture/dispatches   [[:counter/inc]]
   :fixture/expect
   {:epoch-records
    [{:frame  :rf/default
      :record {:event-id :counter/inc
               :outcome  :rf.test/DELIBERATELY-WRONG}}]}})

(deftest epoch-records-checked-on-cljs
  (reset! pretest-registrar @registrar/kind->id->metadata)
  (try
    (let [result (runner/run-fixture epoch-mismatch-fixture host)]
      (is (not (:passed? result))
          "a deliberately-mismatched :epoch-records expectation MUST fail the runner on CLJS")
      (is (seq (:epoch-failures result))
          "the failure MUST be attributed to the epoch-records matcher (not silently ignored)"))
    (finally
      (reset! registrar/kind->id->metadata @pretest-registrar))))

(deftest unknown-expect-key-fails-loud
  (is (seq (runner/unknown-expect-keys
             {:fixture/expect {:rf.test/no-such-expectation 1}}))
      "an unrecognised :fixture/expect key must be flagged unknown")
  (is (empty? (runner/unknown-expect-keys
                {:fixture/expect {:final-app-db {} :epoch-records []}}))
      "corpus-checked expectation keys must NOT be flagged unknown"))

;; ---- rf2-ska8zk NEGATIVE self-test for the :expect-graph guard ------------
;; Mirror of the JVM `derivation-graph-expect-graph-guard`. Saves / restores
;; the registrar (like the corpus runner) so it doesn't leak into siblings.
(deftest derivation-graph-expect-graph-guard-cljs
  (reset! pretest-registrar @registrar/kind->id->metadata)
  (try
    (reset-runtime!)
    (let [run (fn [call] (runner/run-call call))]
      (is (:passed? (run {:call :derivation-graph :mode :live
                          :expect-graph {:mode :live :frame :rf/default}}))
          "the true live graph shape must pass")
      (is (not (:passed? (run {:call :derivation-graph :mode :live
                               :expect-graph {:mode :static}})))
          "a wrong live graph :mode must fail the runner")
      (is (not (:passed? (run {:call :derivation-graph :mode :live
                               :expect-graph {:mode :live :frame :rf/other}})))
          "a wrong live graph :frame must fail the runner")
      (is (not (:passed? (run {:call :derivation-graph :mode :static
                               :expect-graph {:mode :static :frame :rf/default}})))
          "asserting a :frame on the frame-agnostic static graph must fail")
      (is (:passed? (run {:call :derivation-graph :mode :static
                          :expect-graph {:mode :static}}))
          "the true static graph shape must pass"))
    (finally
      (reset! registrar/kind->id->metadata @pretest-registrar))))
