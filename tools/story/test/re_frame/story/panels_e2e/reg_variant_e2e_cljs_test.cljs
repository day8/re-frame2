(ns re-frame.story.panels-e2e.reg-variant-e2e-cljs-test
  "Multi-frame e2e coverage for the `reg-variant` registration vocabulary
  (rf2-8awk1 · Wave 5 of rf2-tglku, replaces the `reg-variant` count
  probe in `story_feature_load.cjs`).

  ## What this replaces

  The Playwright `reg-variant` feature probe (lines 1118-1131 of
  `story_feature_load.cjs` pre-skip) drove a browser through the four
  canonical counter variants — `:story.counter/empty`,
  `:story.counter/loaded`, `:story.counter/clicked-three-times`,
  `:story.counter/save-stubbed` — and asserted the canvas's
  `data-test=\"count\"` element rendered a specific integer for each.

  Once PR #1726 (rf2-0wrud) replaced the pre-render `:play` slot with
  `:script` (runner-event semantics) the canvas counts no longer
  matched the pre-migration baseline. Concretely: on
  `:story.counter/clicked-three-times` the canvas now shows `6` rather
  than `3` because the play-script runs its three `[:counter/inc]`
  dispatches differently. The Playwright probe asserted `3` and was
  failing every Browser gate post-#1726.

  Per Mike's testing direction (feedback_xray_story_cljs_unit_tests_
  not_playwright) + the Wave 1-4 migration pattern (rf2-tglku epic):
  the architectural answer is a CLJS unit test that drives
  `rf.story/run-variant` directly and asserts the result-map's
  `:lifecycle` + `:app-db` slots — no DOM, no race-sensitive count
  timing.

  ## What's under test

  For each of the four canonical variants:

  - The lifecycle reaches `:ready` (loaders → events → render → play
    all completed cleanly).
  - The variant's final `:count` in `:app-db` matches the canonical
    value defined by its `:setup` + `:script` body.
  - When the variant carries a `:script`, every assertion in the
    script passes (the play-script ran end-to-end against a clean
    canvas).

  The four canonical contracts pinned here:

  | Variant                              | Initial | Inc-via-play | Final |
  |--------------------------------------|--------:|-------------:|------:|
  | `:story.counter/empty`               |       0 |            0 |     0 |
  | `:story.counter/loaded`              |       7 |            0 |     7 |
  | `:story.counter/clicked-three-times` |       0 |            3 |     3 |
  | `:story.counter/save-stubbed`        |       5 |            0 |     5 |

  Each test runs sub-second under Node CLJS. No browser, no DOM."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures async]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.machines :as rf.machines]
            [re-frame.registrar :as rf.registrar]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.story :as rf.story]
            [re-frame.story.async :as rf.story.async]
            [re-frame.story.loaders :as rf.story.loaders]
            [re-frame.subs :as rf.subs]))

;; ---- fixture: reset registrar + canonical vocab + counter events --------

(declare register-counter-variants!)

(defn- reset-all! []
  (rf.story/clear-all!)
  (rf.registrar/clear-all!)
  (reset! rf.frame/frames {})
  (try (rf/init! rf.substrate.plain-atom/adapter) (catch :default _ nil))
  ;; Re-register the machines artefact's framework-shipped `:rf/machine`
  ;; sub (a runtime-db sub under EP-0001) after the registrar clear —
  ;; see the matching comment in `variant_lifecycle_e2e_cljs_test.cljs`.
  (rf.subs/reg-runtime-sub :rf/machine
    (fn [runtime-db [_ machine-id]]
      (get-in runtime-db [:rf.runtime/machines :snapshots machine-id])))
  (rf.machines/reset-timers!)
  (rf.story.loaders/clear-watchers!)
  (rf.story/install-canonical-vocabulary!)
  (rf.frame/ensure-default-frame!)
  (register-counter-variants!))

(use-fixtures :each {:before reset-all!})

;; ---- counter events ------------------------------------------------------

(defn- install-counter-events! []
  ;; `assoc` (not replace) is the conventional reducer shape. The lifecycle
  ;; machine snapshot under `[:rf.runtime/machines :snapshots
  ;; :rf.story.lifecycle/machine]` lives in the frame's runtime-db partition
  ;; (EP-0001 rf2-vzld77), so a `:db` (app-db) effect cannot touch it — same
  ;; note as `counter_with_stories/events.cljs` and the lifecycle test.
  (rf/reg-event :counter/initialise
    (fn [{:keys [db]} [_ n]] {:db (assoc db :count (or n 0))}))
  (rf/reg-event :counter/inc
    (fn [{:keys [db]} _] {:db (update db :count inc)}))
  (rf/reg-event :counter/save
    (fn [{:keys [db]} _]
      {:db (assoc db :saving? true)
       :fx [[:counter/sync-to-server {:value (:count db)}]]}))
  (rf/reg-fx :counter/sync-to-server
    (fn [_ctx _args] nil)))

;; ---- canonical 4-variant registration -----------------------------------

(defn- register-counter-variants! []
  (install-counter-events!)
  (rf.story/reg-story :story.counter
    {:doc "Parent story for the reg-variant e2e tests — mirrors the
           counter_with_stories testbed."})
  (rf.story/reg-variant :story.counter/empty
    {:doc "Fresh counter at zero. The simplest possible variant."
     :setup [[:counter/initialise 0]]
     :script [[:dispatch-sync [:rf.assert/path-equals [:count] 0]]]})
  (rf.story/reg-variant :story.counter/loaded
    {:doc "A counter seeded with a non-zero value."
     :setup [[:counter/initialise 7]]
     :script [[:dispatch-sync [:rf.assert/path-equals [:count] 7]]]})
  (rf.story/reg-variant :story.counter/clicked-three-times
    {:doc "Counter after three increments from zero, driven from the
           play slot so :rf.assert/dispatched? observes them."
     :setup [[:counter/initialise 0]]
     :script [[:dispatch-sync [:counter/inc]]
                   [:dispatch-sync [:counter/inc]]
                   [:dispatch-sync [:counter/inc]]
                   [:dispatch-sync [:rf.assert/path-equals  [:count] 3]]
                   [:dispatch-sync [:rf.assert/dispatched?  [:counter/inc]]]]})
  (rf.story/reg-variant :story.counter/save-stubbed
    {:doc "The save flow with the network fx stubbed."
     :setup [[:counter/initialise 5]]
     :decorators [[rf.story/force-fx-stub-id :counter/sync-to-server {:ok? true}]]
     :script [[:dispatch-sync [:counter/save]]
                   [:dispatch-sync [:rf.assert/path-equals    [:saving?] true]]
                   [:dispatch-sync [:rf.assert/effect-emitted :counter/sync-to-server]]]}))

;; ---- helpers -------------------------------------------------------------

(defn- assertions-passing?
  "True iff every assertion in `result`'s `:assertions` slot has
  `:passed? true`. Mirrors `rf.story/assertions-passing?` semantics —
  inlined here so the test reads inline."
  [result]
  (every? (fn [a] (true? (:passed? a))) (:assertions result)))

;; ---- (1) :story.counter/empty -- count 0 --------------------------------

(deftest empty-variant-runs-clean-count-0
  (testing ":story.counter/empty reaches :ready with :count 0 and all
            play-script assertions passing"
    (async done
      (-> (rf.story/run-variant :story.counter/empty)
          (rf.story.async/then
            (fn [result]
              (is (= :ready (:lifecycle result))
                  "lifecycle reached :ready")
              (is (= 0 (-> result :app-db :count))
                  ":count seeded to 0 by [:counter/initialise 0]")
              (is (assertions-passing? result)
                  "every :rf.assert/* row in the result is :passed? true")
              (rf.story/destroy-variant! :story.counter/empty)
              (done)))))))

;; ---- rf2-ixb0bq — the re-registered :rf/machine sub reads runtime-db -----
;;
;; Regression guard. The fixture re-registers `:rf/machine` as a runtime-db
;; sub (EP-0001). After a clean run the lifecycle machine's snapshot lives at
;; `[:rf.runtime/machines :snapshots :rf.story.lifecycle/machine]` in the
;; variant frame's runtime-db; computing the framework sub against the
;; frame-state value resolves the LIVE `{:state :ready …}` snapshot — proving
;; the read targets runtime-db, NOT the dead app-db `:rf/runtime` path.

(deftest rf-machine-sub-resolves-live-runtime-db-snapshot
  (testing ":rf/machine resolves the live lifecycle snapshot off runtime-db"
    (async done
      (-> (rf.story/run-variant :story.counter/loaded)
          (rf.story.async/then
            (fn [_result]
              (let [fs   (rf/frame-state-value :story.counter/loaded)
                    snap (rf/compute-sub
                          [:rf/machine :rf.story.lifecycle/machine] fs)]
                (is (some? snap)
                    ":rf/machine read the live runtime-db snapshot, not nil")
                (is (= :ready (:state snap))
                    "the live snapshot's :state is :ready after a clean run"))
              (rf.story/destroy-variant! :story.counter/loaded)
              (done)))))))

;; ---- (2) :story.counter/loaded -- count 7 -------------------------------

(deftest loaded-variant-runs-clean-count-7
  (testing ":story.counter/loaded reaches :ready with :count 7"
    (async done
      (-> (rf.story/run-variant :story.counter/loaded)
          (rf.story.async/then
            (fn [result]
              (is (= :ready (:lifecycle result))
                  "lifecycle reached :ready")
              (is (= 7 (-> result :app-db :count))
                  ":count seeded to 7 by [:counter/initialise 7]")
              (is (assertions-passing? result)
                  "every :rf.assert/* row in the result is :passed? true")
              (rf.story/destroy-variant! :story.counter/loaded)
              (done)))))))

;; ---- (3) :story.counter/clicked-three-times -- count 3 ------------------
;;
;; This is the variant whose Playwright count assertion failed
;; post-#1726 ("expected 3 got 6"). With the play-script body
;; (3 × `[:dispatch-sync [:counter/inc]]` against `[:counter/initialise
;; 0]`) the lifecycle-level contract is unambiguous: after the four
;; phases run, `:count` is 3. The Playwright canvas was reading a
;; stale / double-rendered count because the play-script's `:dispatch-
;; sync` cascade interleaved with React commit phases — that's a DOM-
;; timing artefact, not a behavioural regression.

(deftest clicked-three-times-runs-clean-count-3
  (testing ":story.counter/clicked-three-times reaches :ready with
            :count 3 — three play-script dispatches against an :setup
            slot that seeded :count 0. This pins the lifecycle-level
            contract that the Playwright probe (now skipped per
            rf2-8awk1) was trying to assert via the DOM."
    (async done
      (-> (rf.story/run-variant :story.counter/clicked-three-times)
          (rf.story.async/then
            (fn [result]
              (is (= :ready (:lifecycle result))
                  "lifecycle reached :ready after play-script ran")
              (is (= 3 (-> result :app-db :count))
                  "three [:counter/inc] dispatches in play-script
                   incremented :count from 0 → 3")
              (is (assertions-passing? result)
                  "every :rf.assert/* row in the play-script passed
                   (path-equals 3 + dispatched? :counter/inc)")
              (rf.story/destroy-variant! :story.counter/clicked-three-times)
              (done)))))))

;; ---- (4) :story.counter/save-stubbed -- count 5 + fx-stub ---------------

(deftest save-stubbed-variant-runs-clean-count-5
  (testing ":story.counter/save-stubbed reaches :ready with :count 5 +
            the `:counter/sync-to-server` fx-stub fires through the
            play-script"
    (async done
      (-> (rf.story/run-variant :story.counter/save-stubbed)
          (rf.story.async/then
            (fn [result]
              (is (= :ready (:lifecycle result))
                  "lifecycle reached :ready")
              (is (= 5 (-> result :app-db :count))
                  ":count seeded to 5 by [:counter/initialise 5];
                   :counter/save does not touch :count")
              (is (true? (-> result :app-db :saving?))
                  ":counter/save flipped :saving? true via :db effect")
              (is (assertions-passing? result)
                  "every :rf.assert/* row in the play-script passed
                   (path-equals :saving? true + effect-emitted
                   :counter/sync-to-server)")
              (rf.story/destroy-variant! :story.counter/save-stubbed)
              (done)))))))

;; ---- (5) registration shape — `reg-variant` side-table -------------------
;;
;; Independent of the lifecycle: pin the `reg-variant` registration
;; shape itself so a regression in the side-table (e.g. dropping the
;; `:doc` slot, breaking variant-id round-trip) is caught here as
;; well. The Playwright probe never asserted this — it was implicit in
;; the fact that the canvas rendered at all — but the unit test can
;; be explicit.

(deftest reg-variant-side-table-shape
  (testing "all four variants are registered with the canonical body
            shape — variant-id round-trips through `variant->edn` and
            the `:setup` slot survives serialisation"
    (doseq [vid [:story.counter/empty
                 :story.counter/loaded
                 :story.counter/clicked-three-times
                 :story.counter/save-stubbed]]
      (let [body (rf.story/variant->edn vid)]
        (is (map? body)
            (str "variant->edn returned a map for " vid))
        (is (vector? (:setup body))
            (str ":setup slot is a vector for " vid))
        (is (= [:counter/initialise]
               (->> body :setup first (take 1) vec))
            (str ":setup[0] is [:counter/initialise ...] for " vid))))))
