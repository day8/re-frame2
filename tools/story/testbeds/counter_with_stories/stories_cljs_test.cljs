(ns counter-with-stories.stories-cljs-test
  "Integration tests for the counter-with-stories example. Per Stage 8
  these run under the top-level `node-test` build
  (`npm run test:cljs`) to assert the example's stories registry is
  intact end-to-end:

  - Every story / variant / workspace / mode / tag / decorator / panel
    registers cleanly against the side-table.
  - `run-variant` resolves to a result map and the four play
    sequences pass (record-don't-throw shape from IMPL-SPEC §2.3).
  - `mount-shell!` / `unmount-shell!` round-trip without throwing on
    a DOM stub.

  The example's stories ns is required at the top — loading the ns
  is what fires the `reg-*` macros — so the side-table is populated
  by the time tests run."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures async]]
            [re-frame.core      :as rf]
            [re-frame.frame     :as frame]
            [re-frame.machines  :as machines]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.story     :as story]
            [re-frame.story.async      :as async-lib]
            [re-frame.story.assertions :as assertions]
            [re-frame.story.loaders    :as loaders]
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

(def ^:private registrar-snapshot (atom nil))

(defn- before! []
  (reset! registrar-snapshot (test-support/snapshot-registrar))
  (reset! frame/frames {})
  (try (rf/init! plain-atom/adapter) (catch :default _ nil))
  (frame/ensure-default-frame!)
  (machines/reset-timers!)
  (loaders/clear-watchers!)
  (reset! assertions/trace-accumulators {})
  ;; Always re-fire the Story registrations so each test starts with
  ;; a freshly-resolved registry (clears any leftover stories from
  ;; previous tests and ensures the lifecycle machine is freshly
  ;; registered against the post-snapshot registrar).
  (story/clear-all!)
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

(deftest example-story-registered
  (testing "the parent story registered"
    (is (story/registered? :story :story.counter))))

(deftest example-four-variants-registered
  (testing "all four canonical variants registered, parent is :story.counter"
    (let [vs (story/variants-of :story.counter)]
      (is (contains? vs :story.counter/empty))
      (is (contains? vs :story.counter/loaded))
      (is (contains? vs :story.counter/clicked-three-times))
      (is (contains? vs :story.counter/save-stubbed))
      (is (= 4 (count vs))))))

(deftest diagnostic-variants-registered
  (testing "the diagnostics story exposes deterministic failure surfaces"
    (let [vs (story/variants-of :story.counter-diagnostics)]
      (is (contains? vs :story.counter-diagnostics/failing-play))
      (is (contains? vs :story.counter-diagnostics/event-throws))
      (is (contains? vs :story.counter-diagnostics/loader-throws))
      (is (= 3 (count vs))))))

(deftest matrix-variants-registered
  (testing "the matrix story exposes deterministic browser-gate affordances"
    (let [vs (story/variants-of :story.counter-matrix)]
      (doseq [vid [:story.counter-matrix/no-play
                   :story.counter-matrix/loader-success
                   :story.counter-matrix/schema-invalid
                   :story.counter-matrix/nested-controls
                   :story.counter-matrix/decorator-throws
                   :story.counter-matrix/multi-substrate
                   :story.counter-matrix/isolation-a
                   :story.counter-matrix/isolation-b]]
        (is (contains? vs vid) (str vid " registered")))
      (is (= 8 (count vs))))))

(deftest example-workspaces-registered
  (testing "both workspaces registered"
    (is (story/registered? :workspace :Workspace.counter/all-states))
    (is (story/registered? :workspace :Workspace.counter/auto-grid))
    (is (story/registered? :workspace :Workspace.counter/prose))
    (is (story/registered? :workspace :Workspace.counter/tabs))
    (is (story/registered? :workspace :Workspace.counter/custom))))

;; ---- variants resolve cleanly ------------------------------------------

(deftest variant-edn-roundtrip
  (testing "variant->edn returns the registered body for each variant"
    (doseq [vid [:story.counter/empty
                 :story.counter/loaded
                 :story.counter/clicked-three-times
                 :story.counter/save-stubbed]]
      (let [body (story/variant->edn vid)]
        (is (map? body) (str vid " variant->edn returned a map"))
        (is (some? (:events body)) (str vid " has :events"))))))

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
  (testing ":story.counter-diagnostics/event-throws projects handler exceptions into assertions"
    (async done
      (-> (story/run-variant :story.counter-diagnostics/event-throws)
          (async-lib/then
            (fn [result]
              (is (not (story/assertions-passing? result)))
              (is (some #(and (= :rf.error/exception (:assertion %))
                              (= :phase-4-play (:phase %))
                              (re-find #"story-load deterministic event handler failure"
                                       (get-in % [:error :message] "")))
                        (:assertions result)))
              (story/destroy-variant! :story.counter-diagnostics/event-throws)
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
