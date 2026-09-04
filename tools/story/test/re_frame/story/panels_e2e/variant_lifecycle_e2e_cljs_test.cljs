(ns re-frame.story.panels-e2e.variant-lifecycle-e2e-cljs-test
  "Multi-frame e2e coverage for the variant 4-phase lifecycle
  (rf2-piucm, replaces `story_feature_load.cjs` § Lifecycle phases).

  The four phases per `002-Runtime.md` §Four-phase lifecycle with `:loaders-complete-when`:

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
  driving `rf.story/run-variant` directly + reading the result-map's
  `:lifecycle` and `:assertions` slots — sub-second per surface."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures async]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.machines :as rf.machines]
            [re-frame.registrar :as rf.registrar]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.story :as rf.story]
            [re-frame.story.async :as rf.story.async]
            [re-frame.story.loaders :as rf.story.loaders]
            [re-frame.story.ui.canvas :as rf.story.ui.canvas]
            [re-frame.subs :as rf.subs]))

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
  (rf.story/clear-all!)
  (rf.registrar/clear-all!)
  (reset! rf.frame/frames {})
  (try (rf/init! rf.substrate.plain-atom/adapter) (catch :default _ nil))
  ;; Re-register the machines artefact's framework-shipped `:rf/machine`
  ;; sub after the registrar clear. EP-0001 (rf2-vzld77 / rf2-ixb0bq):
  ;; machine snapshots are durable RUNTIME-DB state at
  ;; [:rf.runtime/machines :snapshots <id>], so this is a runtime-db sub
  ;; (db-position arg is the runtime-db value) — mirror `re-frame.machines`,
  ;; NOT the retired app-db `:rf/runtime` path.
  (rf.subs/reg-runtime-sub :rf/machine
    (fn [runtime-db [_ machine-id]]
      (get-in runtime-db [:rf.runtime/machines :snapshots machine-id])))
  (rf.machines/reset-timers!)
  (rf.story.loaders/clear-watchers!)
  (rf.story/install-canonical-vocabulary!)
  (rf.frame/ensure-default-frame!)
  (register-lifecycle-variants!))

(use-fixtures :each {:before reset-all!})

;; ---- fixtures: register the counter events the variants dispatch -------

(defn- install-counter-events! []
  ;; `assoc` rather than replace `db` is the conventional reducer shape —
  ;; it preserves any app-db keys seeded earlier. The variant frame's
  ;; lifecycle machine snapshot at `[:rf.runtime/machines :snapshots
  ;; :rf.story.lifecycle/machine]` lives in the frame's runtime-db
  ;; partition (EP-0001 rf2-vzld77 — durable runtime-db state, not app-db),
  ;; so a `:db` (app-db) effect cannot touch it regardless of assoc-vs-
  ;; replace. Same note inline at
  ;; `tools/story/testbeds/counter_with_stories/events.cljs`.
  (rf/reg-event :counter/initialise
    (fn [{:keys [db]} [_ n]] {:db (assoc db :count (or n 5))}))
  (rf/reg-event :counter/inc
    (fn [{:keys [db]} _] {:db (update db :count inc)}))
  (rf/reg-event :counter/throw-loader-rejection
    (fn [_cofx _]
      (throw (ex-info "story-load deterministic loader rejection"
                      {:surface :story-load
                       :kind    :loader-rejection}))))
  (rf/reg-event :counter/loader-never-ready?
    (fn [{:keys [db]} _] {:db (assoc db :rf.story/loaders-complete? false)})))

(defn- register-lifecycle-variants! []
  (install-counter-events!)
  (rf.story/reg-story :story.counter-matrix
    {:doc "Matrix parent for the lifecycle e2e tests."})
  (rf.story/reg-variant :story.counter-matrix/loader-success
    {:doc    "Default-success loader → lifecycle reaches :ready."
     :loaders [[:counter/initialise 5]]
     :setup  [[:counter/inc]]})
  (rf.story/reg-variant :story.counter-matrix/loader-never-completes
    {:doc "rf2-qrk2s class — `:loaders-complete-when` returns false; the
           lifecycle parks at :loading, no events/play, a non-throwing
           `:rf.error/loader-incomplete` assertion is recorded."
     :loaders               [[:counter/loader-never-ready?]]
     :loaders-complete-when :counter/loader-never-ready?})
  (rf.story/reg-variant :story.counter-matrix/loader-rejects
    {:doc "rf2-qrk2s class — the loader event throws; the runtime
           captures the exception into the assertions vector and
           rejection records phase = :phase-1-loaders."
     :loaders [[:counter/throw-loader-rejection]]}))

;; ---- happy path: 4 phases reach :ready ----------------------------------

(deftest happy-path-reaches-ready
  (testing "loader-success variant runs cleanly: lifecycle is :ready,
            no error assertions, the loader event committed to app-db"
    (async done
      (-> (rf.story/run-variant :story.counter-matrix/loader-success)
          (rf.story.async/then
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
              (rf.story/destroy-variant! :story.counter-matrix/loader-success)
              (done)))))))

;; ---- the re-registered :rf/machine sub reads the LIVE runtime-db snapshot
;;
;; rf2-ixb0bq regression guard. The fixture re-registers `:rf/machine` as a
;; runtime-db sub (EP-0001). After a clean run the lifecycle machine's
;; snapshot lives at `[:rf.runtime/machines :snapshots
;; :rf.story.lifecycle/machine]` in the variant frame's runtime-db. We
;; compute the framework sub against the variant frame's `frame-state-value`
;; (the two-partition value `subscribe` resolves reactively) and assert it
;; returns the LIVE `{:state :ready …}` snapshot — NOT nil. Reverting the
;; fixture to the dead app-db `:rf/runtime` path makes this read nil → red.

(deftest rf-machine-sub-resolves-live-runtime-db-snapshot
  (testing ":rf/machine computed against the variant frame-state resolves
            the live lifecycle snapshot off runtime-db, not nil"
    (async done
      (-> (rf.story/run-variant :story.counter-matrix/loader-success)
          (rf.story.async/then
            (fn [_result]
              (let [variant-id :story.counter-matrix/loader-success
                    fs   (rf/frame-state-value variant-id)
                    snap (rf/compute-sub
                          [:rf/machine :rf.story.lifecycle/machine] fs)]
                (is (some? snap)
                    ":rf/machine resolved a non-nil snapshot — the sub read
                     the live runtime-db partition, not the dead app-db path")
                (is (= :ready (:state snap))
                    "the live snapshot's :state is :ready after a clean run"))
              (rf.story/destroy-variant! :story.counter-matrix/loader-success)
              (done)))))))

;; ---- parked: loaders-complete-when never returns true -------------------

(deftest loader-never-completes-parks-at-loading
  (testing "rf2-qrk2s — `:loaders-complete-when` returning false parks
            the lifecycle at :loading with a non-throwing
            :rf.error/loader-incomplete assertion. Events + play
            were skipped — :app-db reflects the loader-side write only."
    (async done
      (-> (rf.story/run-variant :story.counter-matrix/loader-never-completes)
          (rf.story.async/then
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
                (is (false? (rf.story.ui.canvas/loading-phase? :loading false true))
                    "assertions-recorded? overrides the loading-phase
                     skeleton gate"))
              (rf.story/destroy-variant! :story.counter-matrix/loader-never-completes)
              (done)))))))

;; ---- rejected: loader event throws --------------------------------------

(deftest loader-rejects-records-exception
  (testing "rf2-qrk2s — the loader event throws; the runtime captures
            the exception into a :rf.error/exception assertion with
            :phase :phase-1-loaders + the canonical ex-data carries
            through. The lifecycle does NOT advance to :ready."
    (async done
      (-> (rf.story/run-variant :story.counter-matrix/loader-rejects)
          (rf.story.async/then
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
              (rf.story/destroy-variant! :story.counter-matrix/loader-rejects)
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
          _ (rf/make-frame {:id variant-id})]
      ;; Before any transition.
      (is (= :pre-mount (rf.story.loaders/current-state variant-id))
          "fresh frame → :pre-mount")
      (rf.story.loaders/mount! variant-id)
      (is (= :mounting (rf.story.loaders/current-state variant-id))
          ":mount transition → :mounting")
      (rf.story.loaders/start-loaders! variant-id)
      (is (= :loading (rf.story.loaders/current-state variant-id))
          ":loaders-started → :loading")
      (rf.story.loaders/finish-loaders! variant-id)
      (rf.story.loaders/finish-events! variant-id)
      (is (= :ready (rf.story.loaders/current-state variant-id))
          "events-complete → :ready"))))
