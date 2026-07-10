(ns counter-with-stories.stories-cljs-test
  "Integration tests for the counter-with-stories example. These run under
  the top-level `node-test` build
  (`npm run test:cljs`) to assert the example's stories registry is
  intact end-to-end:

  - Every story / variant / workspace / mode / tag / decorator / panel
    registers cleanly against the side-table.
  - `run-variant` resolves to a result map and the four play
    sequences pass (record-don't-throw shape from `004-Assertions.md` §Record-don't-throw semantics).
  - `mount-shell!` / `unmount-shell!` round-trip without throwing on
    a DOM stub.

  The example's stories ns is required at the top — loading the ns
  is what fires the `reg-*` macros — so the side-table is populated
  by the time tests run."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures async]]
            [re-frame.core      :as rf]
            [re-frame.frame     :as frame]
            [re-frame.machines  :as machines]
            [re-frame.registrar :as registrar]
            [re-frame.source-store :as source-store]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.story     :as story]
            [re-frame.story.async      :as async-lib]
            [re-frame.story.loaders    :as loaders]
            [re-frame.story.play       :as play]
            [re-frame.test-support     :as test-support]
            [counter-with-stories.events]
            [counter-with-stories.subs]
            [counter-with-stories.stories :as cw-stories]))

;; ---- fixtures ------------------------------------------------------------
;;
;; Snapshot/restore the registrar around each test: the
;; framework-shipped events / fxs / subs registered at ns-load
;; (machines `:rf/machine` sub, Story lifecycle machine, the counter
;; app's events / subs / fx, the canonical `:rf.assert/*` handlers)
;; survive the snapshot; per-test registrations roll back. Map-form
;; fixture is needed for cljs.test's async test bodies to suspend.
;;
;; ---- Why this cluster stays HAND-ROLLED (rf2-y90h6h / rf2-vyzqca) --------
;;
;; This testbed cluster — this ns, its sibling `elision_demo_cljs_test`, and
;; the `login_form` story test — DELIBERATELY does NOT migrate its per-test
;; reset onto `re-frame.test-support/make-reset-runtime-fixture` (nor the
;; Story-flavoured `re-frame.story.test-support/use-fixtures` that composes
;; it). rf2-vyzqca (#5578) proved a file-by-file migration breaks 16
;; run-order-coupled tests here. DO NOT re-attempt the migration in a corpus
;; "one-idiom" sweep without first addressing ALL THREE reasons below — they
;; are intrinsic to how these co-located testbeds demo EP-0026:
;;
;;   1. ASYNC story tests need the MAP-form fixture. Every variant test
;;      below drives `story/run-variant` (a Promise) under `(async done …)`.
;;      cljs.test HARD-ERRORS on a fn-form fixture for an async body
;;      ("Async tests require fixtures to be specified as maps"), and
;;      `story-test/use-fixtures` returns ONLY the sync fn-form — it composes
;;      `make-reset-runtime-fixture` WITHOUT threading `:async?`. Migrating
;;      would first require extending that production test-support surface
;;      for async and converting these suites to the map shape.
;;
;;   2. The sibling `elision_demo_cljs_test` is NOT a story test (EP-0025
;;      elision, no variants) and does NOT `:require` this ns's app slices
;;      (`counter-with-stories.{events,subs,stories}`). So ITS
;;      `make-reset-runtime-fixture` source-store baseline — captured at that
;;      ns's load from its own require chain — MISSES the counter app
;;      descriptors. The fixture restores the SHARED process-global source
;;      store to that incomplete baseline on teardown, dropping
;;      `:counter/initialise` / `:count` for this ns's variant-frame images
;;      (`:select-ns {:include ["counter-with-stories.**"]}`) — the exact
;;      14-in-`stories`/2-in-elision-family break rf2-vyzqca observed.
;;
;;   3. This ns's app events / subs register at NS-LOAD via top-level
;;      `reg-event` / `reg-sub` forms (see events.cljs / subs.cljs) — there
;;      is NO `install!` thunk to re-fire them per test. The only way to keep
;;      them present across a per-test source-store reset is to restore the
;;      ns-load baseline, which is exactly what the hand-rolled reset below
;;      does (mirroring `login_form/stories_cljs_test`).
;;
;; So the cluster stays on `story/clear-all!` + `register-all!` + an ns-load
;; SOURCE-STORE baseline restore — the async-compatible map-form idiom — and
;; is uniformly run-order-INDEPENDENT without the fixture.

(def ^:private registrar-snapshot (atom nil))

;; EP-0026 §Default Image — STABLE ns-load source-store baseline. Mirrors
;; `login_form/stories_cljs_test`'s baseline and `make-reset-runtime-fixture`'s
;; `source-store-baseline` (rf2-7hwnu). Captured ONCE here at ns-load, AFTER
;; the `:require` chain above has fired every `reg-*`, so the
;; provenance-tagged `counter-with-stories.*` app descriptors this ns's
;; variant-frame images select (`:select-ns {:include
;; ["counter-with-stories.**"]}`) are live. Restored per-`before!` so each
;; test's scoped projection resolves the counter app slices regardless of
;; what a sibling ns's fixture last left the SHARED source store at — a
;; per-ns-baseline fixture (make-reset-runtime-fixture) resetting the store to
;; ITS own incomplete baseline is exactly the run-order coupling this restore
;; makes us immune to (rf2-vyzqca / rf2-y90h6h).
(def ^:private source-store-baseline @source-store/kind->id->ns->descriptor)

(defn- before! []
  (reset! registrar-snapshot (test-support/snapshot-registrar))
  (reset! frame/frames {})
  (try (rf/init! plain-atom/adapter) (catch :default _ nil))
  (frame/ensure-default-frame!)
  (machines/reset-timers!)
  (loaders/clear-watchers!)
  ;; Always re-fire the Story registrations so each test starts with
  ;; a freshly-resolved registry (clears any leftover stories from
  ;; previous tests and ensures the lifecycle machine is freshly
  ;; registered against the post-snapshot registrar).
  (story/clear-all!)
  ;; Restore the STABLE ns-load source-store baseline (see the capture
  ;; above). `story/clear-all!` only wipes the Story side-table, but a
  ;; sibling ns's per-ns-baseline fixture may have left the SHARED source
  ;; store missing this app's descriptors; folding the ns-load baseline back
  ;; makes each test's `:select-ns` projection resolve the counter app
  ;; slices run-order-independently. Idempotent w.r.t. the `register-all!`
  ;; below (stories land in the Story side-table, not the source store).
  (reset! source-store/kind->id->ns->descriptor source-store-baseline)
  (cw-stories/register-all!))

(defn- after! []
  (when-let [snap @registrar-snapshot]
    (test-support/restore-registrar! snap)
    (reset! registrar-snapshot nil))
  (reset! frame/frames {}))

(use-fixtures :each {:before before! :after after!})

;; ---- registrations: the seven kinds all populated -----------------------

(deftest example-tag-registered
  (testing "the project's reg-tag landed"
    (is (contains? (story/list-tags) :counter-with-stories/canonical))))

(deftest example-modes-registered
  (testing "both Modes registered against the side-table"
    (let [modes (story/list-modes)]
      (is (contains? modes :Mode.app/dark))
      (is (contains? modes :Mode.app/light)))))

(deftest example-decorator-registered
  (testing "the project's custom decorator registered"
    (is (story/registered? :decorator :counter-with-stories/log-decorator))))

(deftest example-panel-registered
  (testing "the project's story-panel registered"
    (is (story/registered? :story-panel :Panel.counter-with-stories/notes))))

(deftest example-broken-render-panel-registered
  (testing "rf2-76wo5 testbed — the broken-render panel registered, and
            its :render id is NOT registered as a view. Together those
            two facts drive the panel-host into the broken-render
            fallback branch (asserted live in story_browser_scenarios.cjs)."
    (is (story/registered? :story-panel
                           :Panel.counter-with-stories/broken-render)
        "the testbed panel is registered")
    (is (not (registrar/handler
               :view :counter-with-stories.views/not-registered))
        "the panel's :render view id is NOT registered — the panel-host
         will hit the 'no registered :render view' fallback when it tries
         to resolve it")))

(deftest example-story-registered
  (testing "the parent story registered"
    (is (story/registered? :story :story.counter))))

(deftest example-five-variants-registered
  (testing "the five canonical variants registered on :story.counter
            (four authoring shapes + the rf2-9jfo1.2 events-only loader-
            body shape folded in from the retired xray_rhs_smoke testbed)"
    (let [vs (story/variants-of :story.counter)]
      (is (contains? vs :story.counter/empty))
      (is (contains? vs :story.counter/loaded))
      (is (contains? vs :story.counter/clicked-three-times))
      (is (contains? vs :story.counter/save-stubbed))
      (is (contains? vs :story.counter/events-only-loaded))
      (is (= 5 (count vs))))))

(deftest diagnostic-variants-registered
  (testing "the diagnostics story exposes deterministic failure surfaces"
    (let [vs (story/variants-of :story.counter-diagnostics)]
      (is (contains? vs :story.counter-diagnostics/failing-play))
      (is (contains? vs :story.counter-diagnostics/failing-event-throws))
      (is (contains? vs :story.counter-diagnostics/loader-throws))
      (is (contains? vs :story.counter-diagnostics/render-throws))
      (is (= 4 (count vs))))))

(deftest matrix-variants-registered
  (testing "the matrix story exposes deterministic browser-gate affordances"
    (let [vs (story/variants-of :story.counter-matrix)]
      (doseq [vid [:story.counter-matrix/no-play
                   :story.counter-matrix/loader-success
                   :story.counter-matrix/loader-never-completes
                   :story.counter-matrix/loader-rejects
                   :story.counter-matrix/schema-invalid
                   :story.counter-matrix/nested-controls
                   :story.counter-matrix/decorator-throws
                   :story.counter-matrix/multi-substrate
                   :story.counter-matrix/isolation-a
                   :story.counter-matrix/isolation-b
                   :story.counter-matrix/recorder-redaction
                   :story.counter-matrix/a11y-known-good
                   :story.counter-matrix/a11y-known-bad
                   ;; failing-fx-stub-miss testbed (parent story
                   ;; :story.counter-matrix).
                   :story.counter-matrix/failing-fx-stub-miss]]
        (is (contains? vs vid) (str vid " registered")))
      (is (= 14 (count vs))))))

;; ---- recorder-redaction variant is self-contained ----------------------
;;
;; This test ns requires `counter-with-stories.events` + `.stories`
;; (which itself requires events + views) — but NOT
;; `counter-with-stories.elision-demo`. The recorder-redaction-card
;; dispatches `:counter/sign-in`, a `:sensitive?` handler owned by the
;; counter events slice. The card depends only on the counter events
;; slice, not on elision-demo's `:auth/sign-in`, so this handler-meta
;; probe resolves without booting elision-demo.

(deftest recorder-redaction-sensitive-handler-registered-self-contained
  (testing "the `:counter/sign-in` handler the recorder-redaction-card
            dispatches is registered by the counter events slice — the
            same requires that bring in the variant — and carries
            `:sensitive? true` in its registry meta, with NO reliance on
            elision-demo being booted by core.cljs."
    (let [m (rf/handler-meta :event :counter/sign-in)]
      (is (some? m)
          ":counter/sign-in registration meta is populated by
           counter-with-stories.events (required here; elision-demo is not)")
      (is (true? (:sensitive? m))
          "the registrar copied :sensitive? true from the handler metadata"))))

(deftest recorder-redaction-variant-runs-self-contained
  (testing ":story.counter-matrix/recorder-redaction runs to :ready and its
            play assertion passes WITHOUT elision-demo boot wiring. The
            variant's card dispatches the counter-owned `:counter/sign-in`
            handler, so the variant's own requires satisfy every event it
            can dispatch."
    (async done
      (-> (story/run-variant :story.counter-matrix/recorder-redaction)
          (async-lib/then
            (fn [result]
              (is (= :ready (:lifecycle result)) "lifecycle reached :ready")
              (is (= 11 (-> result :app-db :count))
                  "the :counter/initialise 11 seed applied")
              (is (story/assertions-passing? result)
                  (str "play assertions: " (pr-str (:assertions result))))
              (story/destroy-variant! :story.counter-matrix/recorder-redaction)
              (done)))))))

(deftest failing-fx-stub-miss-variant-fails-with-canonical-reason
  (testing "rf2-0uo4e testbed — :story.counter-matrix/failing-fx-stub-miss runs
            and its :rf.assert/effect-emitted assertion FAILS with the
            canonical reason text 'fx :never-stubbed was not emitted
            during play'. This is the source-side fixture the test pane
            renders in its failing-row reason-text surface."
    (async done
      (-> (story/run-variant :story.counter-matrix/failing-fx-stub-miss)
          (async-lib/then
            (fn [result]
              (let [assertions (:assertions result)
                    miss       (first
                                 (filter (fn [a]
                                           (= :rf.assert/effect-emitted
                                              (:assertion a)))
                                         assertions))]
                (is (= 1 (count assertions))
                    "exactly one assertion declared on the variant")
                (is (some? miss)
                    ":rf.assert/effect-emitted record present")
                (is (false? (:passed? miss))
                    "the assertion FAILED — no force-fx-stub covers
                     :never-stubbed so the play-runner never observed it")
                (is (= "fx :never-stubbed was not emitted during play"
                       (:reason miss))
                    "the canonical reason text the test pane surfaces"))
              (story/destroy-variant! :story.counter-matrix/failing-fx-stub-miss)
              (done)))))))

(deftest example-workspaces-registered
  (testing "both workspaces registered"
    (is (story/registered? :workspace :Workspace.counter/all-states))
    (is (story/registered? :workspace :Workspace.counter/auto-grid))
    (is (story/registered? :workspace :Workspace.counter/prose))
    (is (story/registered? :workspace :Workspace.counter/tabs))
    (is (story/registered? :workspace :Workspace.counter/custom))))

;; ---- variants resolve cleanly ------------------------------------------

(deftest variant-edn-roundtrip
  (testing "variant->edn returns the registered body for each variant.
            rf2-5x1wt.11: the variants now AUTHOR the public `:setup`
            vocabulary, but the registrar's `lower-public-vocabulary`
            step folds `:setup` → `:events` into the stored body so the
            shipping runtime/snapshot/recorder readers see the shipping
            slot. The round-trip therefore surfaces `:events` (the
            lowered form), not `:setup` — this assertion pins that
            lowering until the runtime is routed through the variant
            plan (rf2-5x1wt.17 / .22)."
    (doseq [vid [:story.counter/empty
                 :story.counter/loaded
                 :story.counter/clicked-three-times
                 :story.counter/save-stubbed]]
      (let [body (story/variant->edn vid)]
        (is (map? body) (str vid " variant->edn returned a map"))
        (is (some? (:events body))
            (str vid " has :events (the lowered shipping form of authored :setup)"))))))

;; ---- play sequences pass ------------------------------------------------

(deftest empty-variant-runs-and-passes
  (testing ":story.counter/empty runs and its play sequence passes"
    (async done
      (-> (story/run-variant :story.counter/empty)
          (async-lib/then
            (fn [result]
              (is (= :ready (:lifecycle result)) "lifecycle reached :ready")
              (is (= {:count 0} (select-keys (:app-db result) [:count])))
              (is (story/assertions-passing? result)
                  (str "play assertions: " (pr-str (:assertions result))))
              (story/destroy-variant! :story.counter/empty)
              (done)))))))

(deftest loaded-variant-runs-and-passes
  (testing ":story.counter/loaded runs and all three play assertions pass"
    (async done
      (-> (story/run-variant :story.counter/loaded)
          (async-lib/then
            (fn [result]
              (is (= :ready (:lifecycle result)))
              (is (= 7 (-> result :app-db :count)))
              (is (= 3 (count (:assertions result)))
                  "all three :rf.assert/* assertions ran")
              (is (story/assertions-passing? result))
              (story/destroy-variant! :story.counter/loaded)
              (done)))))))

(deftest clicked-three-times-variant-runs-and-passes
  (testing ":story.counter/clicked-three-times reaches count=3 and dispatch? passes"
    (async done
      (-> (story/run-variant :story.counter/clicked-three-times)
          (async-lib/then
            (fn [result]
              (is (= 3 (-> result :app-db :count)))
              (is (story/assertions-passing? result))
              (story/destroy-variant! :story.counter/clicked-three-times)
              (done)))))))

(deftest save-stubbed-variant-runs-and-effect-emitted-passes
  (testing ":story.counter/save-stubbed has the fx stubbed; effect-emitted passes"
    (async done
      (-> (story/run-variant :story.counter/save-stubbed)
          (async-lib/then
            (fn [result]
              (is (= :ready (:lifecycle result)))
              ;; The stub fired in place of the real fx.
              (is (true? (-> result :app-db :saving?))
                  "the :counter/save handler set :saving? true")
              (is (story/assertions-passing? result)
                  (str "play assertions: " (pr-str (:assertions result))))
              (story/destroy-variant! :story.counter/save-stubbed)
              (done)))))))

(deftest diagnostic-failing-play-records-failure
  (testing ":story.counter-diagnostics/failing-play records a failed assertion without throwing"
    (async done
      (-> (story/run-variant :story.counter-diagnostics/failing-play)
          (async-lib/then
            (fn [result]
              (is (= 1 (-> result :app-db :count)))
              (is (not (story/assertions-passing? result)))
              (is (some #(and (= :rf.assert/path-equals (:assertion %))
                              (false? (:passed? %)))
                        (:assertions result)))
              (story/destroy-variant! :story.counter-diagnostics/failing-play)
              (done)))))))

(deftest diagnostic-event-exception-records-failure
  (testing ":story.counter-diagnostics/failing-event-throws — the handler
            exception is captured by the play module's trace listener
            and drained into `:rf.story/assertions` as a
            :rf.error/exception record. Per rf2-z2dq8 the router
            catches the handler throw, the script step resolves
            cleanly, and `runner-events` drains the captured
            exception into the assertions list so the test-mode UI
            + Xray assertions panel see the failure."
    (async done
      (-> (story/run-variant :story.counter-diagnostics/failing-event-throws)
          (async-lib/then
            (fn [result]
              (is (not (story/assertions-passing? result)))
              (is (some #(and (= :rf.error/exception (:assertion %))
                              (= :phase-4-play (:phase %))
                              (= [:counter/throw-deterministic] (:event %))
                              (re-find #"story-load deterministic event handler failure"
                                       (get-in % [:error :message] "")))
                        (:assertions result))
                  "an :rf.error/exception assertion was recorded for the
                  throwing event in phase-4-play")
              (story/destroy-variant! :story.counter-diagnostics/failing-event-throws)
              (done)))))))

(deftest diagnostic-loader-exception-records-failure
  (testing ":story.counter-diagnostics/loader-throws projects loader exceptions into assertions"
    (async done
      (-> (story/run-variant :story.counter-diagnostics/loader-throws)
          (async-lib/then
            (fn [result]
              (is (not (story/assertions-passing? result)))
              (is (some #(and (= :rf.error/exception (:assertion %))
                              (= :phase-1-loaders (:phase %))
                              (re-find #"story-load deterministic event handler failure"
                                       (get-in % [:error :message] "")))
                        (:assertions result)))
              (story/destroy-variant! :story.counter-diagnostics/loader-throws)
              (done)))))))

;; ---- mount-shell / unmount-shell / active-shell --------------------------
;;
;; Under node-test there is no DOM, so we don't actually mount. We
;; confirm the surface is callable + that `active-shell` returns nil
;; before any mount. (The browser-mount path is exercised by the
;; Playwright spec at counter_with_stories.spec.cjs.)

(deftest shell-surface-callable
  (testing "mount-shell! / unmount-shell! / active-shell are public fns"
    (is (fn? story/mount-shell!))
    (is (fn? story/unmount-shell!))
    (is (fn? story/active-shell))
    (is (nil? (story/active-shell))
        "no shell mounted before any test mounts one")))
