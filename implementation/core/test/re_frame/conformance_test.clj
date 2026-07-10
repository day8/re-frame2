(ns re-frame.conformance-test
  "JVM LEAF of the conformance corpus runner (rf2-xurchk).

  All host-neutral logic — capability claims, fixture realisation, call
  execution, expectation matchers (including `:epoch-records`), result
  assembly, and reporting — lives in `re-frame.conformance-runner` (a
  test-only `.cljc` shared byte-for-byte with the CLJS leaf
  `re-frame.conformance-corpus-cljs-test`). This leaf owns only the
  genuinely host-specific seams:

    - FIXTURE LOADING — `slurp` + `clojure.edn/read-string` over the on-disk
      `spec/conformance/fixtures/*.edn` corpus (CLJS inlines them at
      compile time instead).
    - RESET / ISOLATION — `registrar/clear-all!` + `(require … :reload)` to
      re-establish framework registrations between fixtures (CLJS has no
      `:reload`, so it snapshots/restores the registrar instead).
    - TRACE-LISTENER registry access — `re-frame.trace` on the JVM (CLJS
      uses `re-frame.trace.tooling` per the production-DCE split).

  These are handed to the shared runner as a HOST MAP. The requires below
  are the JVM classpath + ns-load side-effect surface the corpus exercises
  (Malli validator hook, canned HTTP stubs, test-support events); most
  handler calls now live in the runner, which pulls its own deps."
  (:require [clojure.test :refer [deftest is]]
            [clojure.java.io :as io]
            [clojure.edn :as edn]
            [clojure.string :as string]
            [re-frame.core :as rf]
            [re-frame.error-emit :as error-emit]
            [re-frame.frame :as frame]
            [re-frame.registrar :as registrar]
            [re-frame.flows :as flows]
            [re-frame.schemas :as schemas]
            ;; Per rf2-t0hq + rf2-qyfie — the Malli adapter ns must be
            ;; required at boot to publish the late-bind hook the default
            ;; validator routes through. Absent the require the validator
            ;; soft-passes (no failure traces) and the schema fixtures'
            ;; Malli-backed outcomes wouldn't surface. JVM-specific: the CLJS
            ;; leaf reaches the same outcomes without it.
            [re-frame.schemas.malli]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.trace :as trace]
            [re-frame.late-bind :as late-bind]
            ;; Side-effect requires — publish registrations the fixtures
            ;; reference. reset-runtime! `:reload`s these between fixtures.
            [re-frame.http.managed]
            [re-frame.http.test-support]
            [re-frame.routing.test-support]
            ;; rf2-v0jwt / rf2-xurchk — the epoch artefact publishes the
            ;; late-bind hooks (`:epoch/settle!`, `:epoch/clear-history!`,
            ;; `:epoch/clear-epoch-listeners!`, `:epoch/epoch-history`) the
            ;; router calls to commit drain-boundary records and the runner
            ;; reads for `:epoch-records`. The shared runner also requires it,
            ;; but keeping it explicit here documents the reset-runtime! epoch
            ;; clear below.
            [re-frame.epoch]
            [re-frame.resources]
            [re-frame.resources.test-support]
            ;; The shared, host-neutral runner (rf2-xurchk).
            [re-frame.conformance-runner :as runner]))

;; ---- fixture loader (JVM-specific: fs) ------------------------------------

(def fixtures-dir
  ;; The conformance corpus lives under spec/conformance/fixtures at the repo
  ;; root. Anchored to a CLASSPATH RESOURCE, not the working directory
  ;; (rf2-ywrwkl): this namespace's own source file is on the test classpath,
  ;; so resolving it via io/resource pins the anchor to the on-disk source
  ;; location regardless of cwd or which alias loaded the namespace. Walking
  ;; five parents (conformance_test.clj → re_frame → test → core →
  ;; implementation → repo root) reaches the repo root.
  (let [res (io/resource "re_frame/conformance_test.clj")]
    (assert res
            (str "conformance-test cannot locate its own source on the "
                 "classpath — the core test/ dir must be on the test "
                 "classpath for fixture discovery to anchor."))
    (-> (io/file res)        ; .../core/test/re_frame/conformance_test.clj
        .getParentFile       ; .../core/test/re_frame
        .getParentFile       ; .../core/test
        .getParentFile       ; .../core
        .getParentFile       ; .../implementation
        .getParentFile       ; repo root
        (io/file "spec" "conformance" "fixtures")
        .getCanonicalFile)))

(defn- load-fixture [file]
  (try
    ;; A handful of fixtures use `::name` (auto-resolved keyword) which pure
    ;; clojure.edn cannot read without a *reader-resolver*. Rewrite ONLY a
    ;; standalone auto-resolved keyword `::name` (one that begins a token) to
    ;; a stable namespace so the fixture loads (rf2-lu3f). The lookbehind keeps
    ;; the rewrite from corrupting a `::` INSIDE a value (e.g. a CEDN-1 token
    ;; string `"k::answer"`).
    (let [raw   (slurp file)
          fixed (string/replace raw #"(?<=[\s(\[{])::([a-zA-Z][a-zA-Z0-9_-]*)"
                                ":rf.machine.timer/$1")]
      (edn/read-string fixed))
    (catch Throwable e
      {:fixture/load-error (.getMessage e)
       :fixture/file       (.getName file)})))

(defn all-fixtures []
  (->> (file-seq fixtures-dir)
       (filter #(.isFile %))
       (filter #(string/ends-with? (.getName %) ".edn"))
       (map (fn [f] [(.getName f) (load-fixture f)]))))

;; ---- runtime reset (JVM-specific: clear-all! + require :reload) ------------

(defn- reset-runtime! []
  (registrar/clear-all!)
  (reset! frame/frames {})
  (flows/reset-flows!)
  (schemas/clear-schemas-by-frame!)
  ;; rf2-wxe9t — drop every corpus-wide error-emit listener so a recorder
  ;; installed for one fixture can't fire against the next fixture's drains.
  (error-emit/clear-error-listeners!)
  ;; rf2-v0jwt — drop the per-frame epoch ring buffer (and the in-flight
  ;; capture buffer) between fixtures so `:epoch-records` assertions observe
  ;; THIS fixture's recorded epochs only.
  (when-let [f (late-bind/get-fn :epoch/clear-history!)]
    (f))
  (when-let [f (late-bind/get-fn :epoch/clear-epoch-listeners!)]
    (f))
  (rf/init! plain-atom/adapter)
  ;; The framework's provided recordable coeffect `:rf/time-ms` is registered
  ;; at `re-frame.cofx` ns-load; clear-all! wiped it. Re-seat it.
  (require 're-frame.cofx :reload)
  ;; Framework events / fx are registered at ns-load in routing.cljc /
  ;; ssr.cljc; clear-all! wiped them. Re-eval those registrations. Use the
  ;; fn-form re-frame.subs/reg-sub (rf/reg-sub is a macro).
  ((requiring-resolve 're-frame.subs/reg-sub) :rf/route
   (requiring-resolve 're-frame.routing/route-sub-fn))
  (require 're-frame.routing :reload)
  ;; rf2-dbiv8 — re-seat the test-only `:rf.test/simulate-http-resolution`
  ;; fixture event after clear-all!.
  (require 're-frame.routing.test-support :reload)
  (require 're-frame.ssr :reload)
  ;; Spec 014 — re-register :rf.http/managed and friends after clear-all!.
  (require 're-frame.http.managed :reload)
  ;; rf2-cdmle — re-fire re-frame.http.test-support's load body so its
  ;; canned-stub fx registrations re-seat.
  (require 're-frame.http.test-support :reload)
  ;; Spec 005 — re-register :rf.machine/spawn / :rf.machine/destroy fx + the
  ;; :rf/machine sub after clear-all!.
  (require 're-frame.machines :reload)
  ;; Spec 016 §Resources (rf2-rul3ov) — re-register the resource events / fx /
  ;; subs and reset host-side caches so each fixture's first load mints
  ;; generation 1 deterministically.
  (require 're-frame.resources :reload)
  (require 're-frame.resources.test-support :reload)
  ((requiring-resolve 're-frame.resources.test-support/reset-resources!))
  ;; Reset id-allocators so nav-token / pending-nav / rank-reg / spawn ids are
  ;; stable across runs.
  ((requiring-resolve 're-frame.routing/reset-counters!))
  ;; rf2-oosjmh — the nav-token / pending-nav counters are host-side transient
  ;; state now, so the `frames` reset above no longer clears them.
  ((requiring-resolve 're-frame.routing/reset-nav-counters!))
  ((requiring-resolve 're-frame.machines/reset-timers!))
  ;; Spec 014 — drop the in-flight request registry between fixtures.
  ((requiring-resolve 're-frame.http.managed/clear-all-in-flight!))
  ;; Spec 014 §Middleware (rf2-yhfgf) — clear the per-frame interceptor chain
  ;; (a `defonce` atom that persists across `:reload`).
  ((requiring-resolve 're-frame.http.managed/clear-all-http-interceptors!)))

;; ---- host map --------------------------------------------------------------

(def ^:private host
  "The genuinely host-specific seams handed to the shared runner. Trace
  registry access uses `re-frame.trace` (JVM); the CLJS leaf uses
  `re-frame.trace.tooling` per the production-DCE split. The fixture-end
  cleanup clears ALL listeners (the framework SSR error-projection listener
  is re-registered by the next reset-runtime!'s `(require 're-frame.ssr
  :reload)`), matching the pre-consolidation JVM behaviour."
  {:reset-runtime!             reset-runtime!
   :register-trace-listener!   (fn [fixture-id listener]
                                 (trace/register-listener! [fixture-id] listener))
   :unregister-trace-listener! (fn [_fixture-id]
                                 (trace/clear-listeners!))})

;; ---- the test entrypoint --------------------------------------------------

(deftest run-conformance-corpus
  (runner/run-corpus (all-fixtures) host "JVM"))

;; ---- rf2-xurchk acceptance self-tests -------------------------------------
;;
;; These pin the correctness fix directly on the JVM host (the CLJS leaf pins
;; the same on CLJS). Both prove the shared runner BITES rather than silently
;; ignoring an expectation.

;; A single-drain counter fixture (mirror of epoch-record-shape.edn) whose
;; `:epoch-records` expectation is DELIBERATELY WRONG. If the epoch matcher
;; were absent (the rf2-xurchk bug) this would pass; with it, it MUST fail.
(def ^:private epoch-mismatch-fixture
  {:fixture/id           :rf.test/epoch-records-deliberate-mismatch
   :fixture/spec-version "1.0"
   :fixture/capabilities #{:core/event-handler :core/trace}
   :fixture/handlers     {:event {:counter/inc [[:update [:count] [:fn :inc]]]}}
   :fixture/frame-config {}
   :fixture/dispatches   [[:counter/inc]]
   :fixture/expect
   {:epoch-records
    ;; The real settled drain records :outcome :ok; assert a wrong outcome so
    ;; a WORKING epoch matcher fails the fixture.
    [{:frame  :rf/default
      :record {:event-id :counter/inc
               :outcome  :rf.test/DELIBERATELY-WRONG}}]}})

(deftest epoch-records-checked-on-jvm
  (let [result (runner/run-fixture epoch-mismatch-fixture host)]
    (is (not (:passed? result))
        "a deliberately-mismatched :epoch-records expectation MUST fail the runner on JVM")
    (is (seq (:epoch-failures result))
        "the failure MUST be attributed to the epoch-records matcher (not silently ignored)")))

(deftest unknown-expect-key-fails-loud
  ;; A runnable fixture whose :fixture/expect names a key the runner neither
  ;; checks nor delegates MUST be reported as unknown — not silently ignored.
  (is (seq (runner/unknown-expect-keys
             {:fixture/expect {:rf.test/no-such-expectation 1}}))
      "an unrecognised :fixture/expect key must be flagged unknown")
  (is (empty? (runner/unknown-expect-keys
                {:fixture/expect {:final-app-db {} :epoch-records []}}))
      "corpus-checked expectation keys must NOT be flagged unknown"))

;; ---- rf2-ska8zk NEGATIVE self-test for the :expect-graph guard ------------
;;
;; The broad derivation-graph fixture pins the live graph's {:mode :live
;; :frame :rf/default} shape via :expect-graph. This proves the GUARD bites:
;; a :derivation-graph call whose :expect-graph misreports :mode/:frame must
;; FAIL the runner, otherwise the fixture's live-mode assertion is a no-op.
(deftest derivation-graph-expect-graph-guard
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
        "the true static graph shape must pass")))
