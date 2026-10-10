(ns re-frame.conformance-corpus-cljs-test
  "CLJS leaf of the conformance corpus runner. All host-neutral logic lives in
  `re-frame.conformance-runner`, shared with the JVM leaf
  `re-frame.conformance-test` (which also carries the runner's self-tests);
  this leaf owns only the CLJS seams handed to it as a host map: fixtures
  inlined at compile time, the inter-fixture reset (registrar snapshot and
  restore, since CLJS has no `(require … :reload)`), and trace-listener
  access through `re-frame.trace.tooling`."
  (:require [cljs.test :refer-macros [deftest is]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.registrar :as rf.registrar]
            [re-frame.source-store :as rf.source-store]
            [re-frame.image-assembly :as rf.image-assembly]
            [re-frame.flows :as rf.flows]
            [re-frame.schemas :as rf.schemas]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.substrate.adapter :as rf.substrate.adapter]
            [re-frame.trace.tooling :as rf.trace.tooling]
            [re-frame.error-emit :as rf.error-emit]
            [re-frame.events]
            [re-frame.late-bind :as rf.late-bind]
            [re-frame.routing :as rf.routing]
            ;; Registers `:rf.test/simulate-http-resolution` at ns-load, before
            ;; the baselines below are captured.
            [re-frame.routing.test-support]
            [re-frame.machines :as rf.machines]
            [re-frame.http.managed :as rf.http.managed]
            ;; Canned-stub fxs gate on explicit test-support require.
            [re-frame.http.test-support]
            [re-frame.resources.test-support :as rf.resources.test-support]
            [re-frame.conformance-runner :as rf.conformance-runner])
  ;; Compile-time fixture inlining (see conformance_fixtures.clj).
  (:require-macros [re-frame.conformance-fixtures :refer [all-fixtures]]))

;; ---- fixture loading (compile-time inlined) -------------------------------

(def fixtures
  "Vector of `[filename fixture-map]` pairs, sorted by filename."
  (all-fixtures))

;; ---- baseline snapshots ---------------------------------------------------
;;
;; The trace-listener and framework registrar baselines are captured at
;; NS-LOAD: that is the only point at which the SSR error-projection listener
;; is certainly live and no sibling test ns has registered handlers yet. The
;; pretest snapshots are captured at DEFTEST START and restored at the end,
;; so sibling namespaces that ran before the corpus keep their registrations.

;; No public surface hands back the listener registry map, so the private
;; atom is reached deliberately; the direct `re-frame.trace.tooling` require
;; lets clj-kondo resolve the symbol.
#_{:clj-kondo/ignore [:private-call]}
(def ^:private baseline-trace-listeners
  @re-frame.trace.tooling/listeners)

(def ^:private framework-baseline-registrar @rf.registrar/kind->id->metadata)
(def ^:private framework-baseline-source-store @rf.source-store/kind->id->ns->descriptor)

(def ^:private pretest-registrar
  (atom nil))

;; Frames resolve through the source store, so it rolls back in lockstep with
;; the registrar; a registrar-only rollback would leave stale handlers live.
(def ^:private pretest-source-store
  (atom nil))

;; ---- runtime reset (CLJS-specific: snapshot/restore) ----------------------

(defn- reset-runtime! []
  ;; Roll the registrar and source store back to the framework-only baseline;
  ;; the raw `reset!` does not bump the store generation, so drop the
  ;; resolved-generation cache too.
  (reset! rf.registrar/kind->id->metadata framework-baseline-registrar)
  (reset! rf.source-store/kind->id->ns->descriptor framework-baseline-source-store)
  (rf.image-assembly/clear-generation-cache!)
  ;; A suite that ran just before may have rolled the live store back past
  ;; this ns's load, so re-seed the routing test-support event.
  (re-frame.events/reg-event :rf.test/simulate-http-resolution
    re-frame.routing.test-support/simulate-http-resolution-handler)
  ;; Example apps register routes whose rank tuples can collide with the
  ;; fixtures'; each fixture re-registers the routes it needs.
  (rf.registrar/clear-kind! :route)
  (reset! rf.frame/frames {})
  (rf.flows/reset-flows!)
  (rf.schemas/clear-schemas-by-frame!)
  ;; Host-side counters, timers and caches outlive the `frames` reset.
  (rf.routing/reset-counters!)
  (rf.routing/reset-nav-counters!)
  (rf.machines/reset-timers!)
  (rf.http.managed/clear-all-in-flight!)
  (rf.http.managed/clear-all-http-interceptors!)
  (rf.substrate.adapter/dispose-adapter!)
  (rf/init! rf.substrate.plain-atom/adapter)
  ;; Keeps the SSR error-projection listener, drops per-fixture listeners.
  #_{:clj-kondo/ignore [:private-call]}
  (reset! re-frame.trace.tooling/listeners baseline-trace-listeners)
  (rf.error-emit/clear-error-listeners!)
  ;; `:epoch-records` must observe only this fixture's epochs.
  (when-let [f (rf.late-bind/get-fn :epoch/clear-history!)]
    (f))
  (when-let [f (rf.late-bind/get-fn :epoch/clear-epoch-listeners!)]
    (f))
  (rf.resources.test-support/reset-resources!))

;; ---- host map --------------------------------------------------------------

(def ^:private host
  ;; Fixture-end cleanup drops only this fixture's listener, so the SSR
  ;; error-projection listener survives.
  {:reset-runtime!             reset-runtime!
   :register-trace-listener!   (fn [fixture-id listener]
                                 (re-frame.trace.tooling/register-listener! [fixture-id] listener))
   :unregister-trace-listener! (fn [fixture-id]
                                 (re-frame.trace.tooling/unregister-listener! fixture-id))})

;; ---- the test entrypoint --------------------------------------------------

(deftest run-conformance-corpus-cljs
  (reset! pretest-registrar @rf.registrar/kind->id->metadata)
  (reset! pretest-source-store @rf.source-store/kind->id->ns->descriptor)
  (try
    (rf.conformance-runner/run-corpus fixtures host "CLJS")
    (finally
      (reset! rf.registrar/kind->id->metadata @pretest-registrar)
      (reset! rf.source-store/kind->id->ns->descriptor @pretest-source-store)
      (rf.image-assembly/clear-generation-cache!))))

;; Mirror of the JVM `classification-op-map-guard`. Saves / restores the
;; registrar (like the corpus runner) so it doesn't leak into siblings.
;;
;; `realise-classification-effects!` refuses a `:fixture/classification-effects`
;; op-map that does not carry EXACTLY ONE of the four commit-plane axes, and
;; the FixtureFile schema in `spec/Spec-Schemas.md` states the same contract.
;; Nothing in the corpus exercises the refusal: every live op is a valid
;; single-axis map, so deleting the guard leaves the corpus green.

(defn- classification-op-fixture [ops]
  {:fixture/id           :rf.test/classification-op-guard
   :fixture/spec-version "1.0"
   :fixture/capabilities #{:core/event-handler}
   :fixture/handlers     {:event {:dc/store [[:set [:secret] "s"]]}}
   :fixture/frame-config {}
   :fixture/classification-effects ops
   :fixture/dispatches   [[:dc/store]]
   :fixture/expect       {:final-app-db {:secret "s"}}})

(def ^:private refused-classification-ops
  [["an empty op-map"        [{}]]
   ["a multi-axis op-map"    [{:sensitive [[:secret]] :large [[:secret]]}]]
   ["an unknown-axis op-map" [{:rf.test/bogus [[:secret]]}]]
   ["a known axis beside an unknown key"
    [{:sensitive [[:secret]] :rf.test/bogus [[:secret]]}]]])

;; Mirror of the JVM `unknown-top-level-key-fails-loud`: the conformance README
;; binds every host's harness to fail naming a top-level key it does not
;; implement.

(def ^:private top-level-key-fixture
  {:fixture/id           :rf.test/top-level-key-guard
   :fixture/spec-version "1.0"
   :fixture/capabilities #{:core/event-handler}
   :fixture/handlers     {:event {:counter/set [[:set [:count] 1]]}}
   :fixture/frame-config {}
   :fixture/dispatches   [[:counter/set]]
   :fixture/expect       {:final-app-db {:count 1}}})

(deftest unknown-top-level-key-fails-loud-cljs
  (reset! pretest-registrar @rf.registrar/kind->id->metadata)
  (reset! pretest-source-store @rf.source-store/kind->id->ns->descriptor)
  (try
    (is (:passed? (rf.conformance-runner/run-corpus-fixture
                    "top-level-key-guard.edn" top-level-key-fixture host))
        "control: the fixture passes the corpus gates as written")
    (let [result (rf.conformance-runner/run-corpus-fixture
                   "top-level-key-guard.edn"
                   (assoc top-level-key-fixture :fixture/dispatchess [[:counter/set]])
                   host)]
      (is (not (:passed? result)) "a misspelt setup key must fail the fixture")
      (is (= [:fixture/dispatchess] (:unknown-fixture-keys result)))
      (is (some? (re-find #":fixture/dispatchess" (str (:error result))))
          "the failure names the key"))
    (finally
      (reset! rf.registrar/kind->id->metadata @pretest-registrar)
      (reset! rf.source-store/kind->id->ns->descriptor @pretest-source-store)
      (rf.image-assembly/clear-generation-cache!))))

(deftest classification-op-map-guard-cljs
  (reset! pretest-registrar @rf.registrar/kind->id->metadata)
  (reset! pretest-source-store @rf.source-store/kind->id->ns->descriptor)
  (try
    (doseq [[label ops] refused-classification-ops]
      (let [result (rf.conformance-runner/run-fixture (classification-op-fixture ops) host)]
        (is (not (:passed? result))
            (str label " in :fixture/classification-effects MUST fail the fixture, not silently no-op"))
        (is (some? (re-find #"unrecognised :fixture/classification-effects op-map"
                            (str (:error result))))
            (str label " must be refused BY THE CLASSIFICATION-OP GUARD, not by some later check"))))
    ;; Control: the valid single-axis shape the whole live corpus uses still
    ;; runs the fixture to a pass, so the guard is not refusing everything.
    (let [result (rf.conformance-runner/run-fixture
                   (classification-op-fixture [{:sensitive [[:secret]]}]) host)]
      (is (:passed? result)
          (str "a valid single-axis op-map must still run the fixture to a pass; got "
               (pr-str (select-keys result [:error :final-db :expected-db])))))
    (finally
      (reset! rf.registrar/kind->id->metadata @pretest-registrar)
      (reset! rf.source-store/kind->id->ns->descriptor @pretest-source-store)
      (rf.image-assembly/clear-generation-cache!))))
