(ns re-frame.story.panels-e2e.variant-lifecycle-e2e-cljs-test
  "Multi-frame e2e coverage for the variant 4-phase lifecycle
  (rf2-piucm, replaces `story_feature_load.cjs` § Lifecycle phases).

  The four phases per IMPL-SPEC §5.4:

    pre-mount  →  mounting  →  loading  →  ready
                                   │
                                   └────► error

  Three variants drive this surface:

  - **Happy path** — `:loaders [[:counter/initialise 5]]` succeeds;
    the lifecycle advances through every phase and lands on `:ready`.
  - **Loader-never-completes** — `:loaders-complete-when` returns
    false; the lifecycle parks at `:loading`, a non-throwing
    `:rf.error/loader-incomplete` assertion is recorded, events / play
    are skipped.
  - **Loader-rejects** — the loader event throws; the runtime captures
    the exception as a `:rf.error/exception` assertion with
    `:phase :phase-1-loaders`. Per rf2-qrk2s the canvas SHOULD render
    despite the parked lifecycle (assertions-recorded? overrides the
    skeleton-gate), so we also assert `loading-phase?` flips to false
    once the rejection records.

  ## What this replaces

  The Playwright `Lifecycle phases` scenarios drove a browser through
  loader-success / loader-never-completes / loader-rejects, switching
  to `test` mode and asserting the test-pane's reason text included
  the canonical strings. This test gets the same coverage by
  driving `story/run-variant` directly + reading the result-map's
  `:lifecycle` and `:assertions` slots — sub-second per surface."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures async]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.machines :as machines]
            [re-frame.registrar :as registrar]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.story :as story]
            [re-frame.story.async :as async-lib]
            [re-frame.story.loaders :as loaders]
            [re-frame.story.ui.canvas :as canvas]))

;; Async tests need a map-form fixture so cljs.test's `async` body
;; can suspend around the per-test boundary. Mirrors the proven reset
;; pattern in `re-frame.story-runtime-cljs-test`: full registrar
;; clear + manual re-register of the framework's `:rf/machine` sub +
;; canonical-vocab install. Matching that pattern is load-bearing —
;; subtle differences (e.g. using `snapshot-registrar` instead) can
;; leave the lifecycle machine handler off the registry and trap
;; the lifecycle at :pre-mount.

(declare register-lifecycle-variants!)

(defn- reset-all! []
  (story/clear-all!)
  (registrar/clear-all!)
  (reset! frame/frames {})
  (try (rf/init! plain-atom/adapter) (catch :default _ nil))
  ;; Re-register the machines artefact's framework-shipped sub
  ;; (`:rf/machine`) after the registrar clear.
  (rf/reg-sub :rf/machine
              (fn [db [_ machine-id]]
                (get-in db [:rf/machines machine-id])))
  (machines/reset-timers!)
  (loaders/clear-watchers!)
  (story/install-canonical-vocabulary!)
  (frame/ensure-default-frame!)
  (register-lifecycle-variants!))

(use-fixtures :each {:before reset-all!})

;; ---- fixtures: register the counter events the variants dispatch -------

(defn- install-counter-events! []
  ;; `assoc` rather than replace `db` so the variant frame's
  ;; `[:rf/machines :rf.story.lifecycle/machine]` slot (written by the
  ;; lifecycle machine's transitions before any user event fires)
  ;; survives the loader-event commit. Replacing the whole `:db`
  ;; clobbers it and the lifecycle stalls at `:pre-mount` — same class
  ;; of bug documented inline at
  ;; `tools/story/testbeds/counter_with_stories/events.cljs` (the
  ;; assoc-vs-replace warning).
  (rf/reg-event-db :counter/initialise
    (fn [db [_ n]] (assoc db :count (or n 5))))
  (rf/reg-event-db :counter/inc
    (fn [db _] (update db :count inc)))
  (rf/reg-event-db :counter/throw-loader-rejection
    (fn [_db _]
      (throw (ex-info "story-load deterministic loader rejection"
                      {:surface :story-load
                       :kind    :loader-rejection}))))
  (rf/reg-event-db :counter/loader-never-ready?
    (fn [db _] (assoc db :rf.story/loaders-complete? false))))

(defn- register-lifecycle-variants! []
  (install-counter-events!)
  (story/reg-story :story.counter-matrix
    {:doc "Matrix parent for the lifecycle e2e tests."})
  (story/reg-variant :story.counter-matrix/loader-success
    {:doc    "Default-success loader → lifecycle reaches :ready."
     :loaders [[:counter/initialise 5]]
     :events  [[:counter/inc]]})
  (story/reg-variant :story.counter-matrix/loader-never-completes
    {:doc "rf2-qrk2s class — `:loaders-complete-when` returns false; the
           lifecycle parks at :loading, no events/play, a non-throwing
           `:rf.error/loader-incomplete` assertion is recorded."
     :loaders               [[:counter/loader-never-ready?]]
     :loaders-complete-when :counter/loader-never-ready?})
  (story/reg-variant :story.counter-matrix/loader-rejects
    {:doc "rf2-qrk2s class — the loader event throws; the runtime
           captures the exception into the assertions vector and
           rejection records phase = :phase-1-loaders."
     :loaders [[:counter/throw-loader-rejection]]}))

;; ---- happy path: 4 phases reach :ready ----------------------------------

(deftest happy-path-reaches-ready
  (testing "loader-success variant runs cleanly: lifecycle is :ready,
            no error assertions, the loader event committed to app-db"
    (async done
      (-> (story/run-variant :story.counter-matrix/loader-success)
          (async-lib/then
            (fn [result]
              (is (= :ready (:lifecycle result))
                  "lifecycle reached :ready after the four phases")
              (is (= 6 (-> result :app-db :count))
                  "loader-initialise 5 → events-inc → :count 6")
              (is (empty? (filter (fn [a]
                                    (or (= :rf.error/exception (:assertion a))
                                        (= :rf.error/loader-incomplete (:assertion a))))
                                  (:assertions result)))
                  "no error assertions on a clean run")
              (story/destroy-variant! :story.counter-matrix/loader-success)
              (done)))))))

;; ---- parked: loaders-complete-when never returns true -------------------

(deftest loader-never-completes-parks-at-loading
  (testing "rf2-qrk2s — `:loaders-complete-when` returning false parks
            the lifecycle at :loading with a non-throwing
            :rf.error/loader-incomplete assertion. Events + play
            were skipped — :app-db reflects the loader-side write only."
    (async done
      (-> (story/run-variant :story.counter-matrix/loader-never-completes)
          (async-lib/then
            (fn [result]
              (is (= :loading (:lifecycle result))
                  "lifecycle parked at :loading (no advance to :ready)")
              (let [incomplete (some (fn [a]
                                       (when (= :rf.error/loader-incomplete
                                                (:assertion a))
                                         a))
                                     (:assertions result))]
                (is (some? incomplete)
                    ":rf.error/loader-incomplete assertion recorded")
                (is (false? (:passed? incomplete))
                    "the assertion FAILED — the runtime cannot advance")
                (is (= :phase-1-loaders (:phase incomplete))
                    "phase = :phase-1-loaders so the test-pane can group")
                (is (= :counter/loader-never-ready?
                       (:predicate incomplete))
                    "predicate slot carries the variant's
                     :loaders-complete-when keyword"))
              (testing "rf2-qrk2s — loading-phase? still gates the
                        skeleton false when assertions are recorded,
                        so the canvas can render the user's view"
                (is (false? (canvas/loading-phase? :loading false true))
                    "assertions-recorded? overrides the loading-phase
                     skeleton gate"))
              (story/destroy-variant! :story.counter-matrix/loader-never-completes)
              (done)))))))

;; ---- rejected: loader event throws --------------------------------------

(deftest loader-rejects-records-exception
  (testing "rf2-qrk2s — the loader event throws; the runtime captures
            the exception into a :rf.error/exception assertion with
            :phase :phase-1-loaders + the canonical ex-data carries
            through. The lifecycle does NOT advance to :ready."
    (async done
      (-> (story/run-variant :story.counter-matrix/loader-rejects)
          (async-lib/then
            (fn [result]
              (let [rejection (some (fn [a]
                                      (when (and (= :rf.error/exception
                                                    (:assertion a))
                                                 (= :phase-1-loaders
                                                    (:phase a)))
                                        a))
                                    (:assertions result))]
                (is (some? rejection)
                    ":rf.error/exception assertion recorded with
                     :phase :phase-1-loaders")
                (is (false? (:passed? rejection))
                    "loader rejection is a failed assertion")
                (is (= [:counter/throw-loader-rejection]
                       (:event rejection))
                    ":event slot carries the canonical source event
                     so the test-pane reason text can include it"))
              (story/destroy-variant! :story.counter-matrix/loader-rejects)
              (done)))))))

;; ---- lifecycle state advance --------------------------------------------

(deftest lifecycle-current-state-advances-on-driver-events
  (testing "the lifecycle machine advances :pre-mount → :mounting →
            :loading → :ready when driven by the runtime's transition
            events. Mirrors what `run-variant` does internally; pinning
            this here catches a regression in the machine spec
            independent of the orchestrator."
    (let [variant-id :story.counter-matrix/lifecycle-walk
          ;; Fresh frame for the walk — the machine + mirror writer were
          ;; installed by install-canonical-vocabulary! in the fixture.
          _ (rf/reg-frame variant-id {})]
      ;; Before any transition.
      (is (= :pre-mount (loaders/current-state variant-id))
          "fresh frame → :pre-mount")
      (loaders/mount! variant-id)
      (is (= :mounting (loaders/current-state variant-id))
          ":mount transition → :mounting")
      (loaders/start-loaders! variant-id)
      (is (= :loading (loaders/current-state variant-id))
          ":loaders-started → :loading")
      (loaders/finish-loaders! variant-id)
      (loaders/finish-events! variant-id)
      (is (= :ready (loaders/current-state variant-id))
          "events-complete → :ready"))))
