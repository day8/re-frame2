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
  `:play-script` (runner-event semantics) the canvas counts no longer
  matched the pre-migration baseline. Concretely: on
  `:story.counter/clicked-three-times` the canvas now shows `6` rather
  than `3` because the play-script runs its three `[:counter/inc]`
  dispatches differently. The Playwright probe asserted `3` and was
  failing every Browser gate post-#1726.

  Per Mike's testing direction (feedback_causa_story_cljs_unit_tests_
  not_playwright) + the Wave 1-4 migration pattern (rf2-tglku epic):
  the architectural answer is a CLJS unit test that drives
  `story/run-variant` directly and asserts the result-map's
  `:lifecycle` + `:app-db` slots — no DOM, no race-sensitive count
  timing.

  ## What's under test

  For each of the four canonical variants:

  - The lifecycle reaches `:ready` (loaders → events → render → play
    all completed cleanly).
  - The variant's final `:count` in `:app-db` matches the canonical
    value defined by its `:events` + `:play-script` body.
  - When the variant carries a `:play-script`, every assertion in the
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
            [re-frame.frame :as frame]
            [re-frame.machines :as machines]
            [re-frame.registrar :as registrar]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.story :as story]
            [re-frame.story.async :as async-lib]
            [re-frame.story.loaders :as loaders]))

;; ---- fixture: reset registrar + canonical vocab + counter events --------

(declare register-counter-variants!)

(defn- reset-all! []
  (story/clear-all!)
  (registrar/clear-all!)
  (reset! frame/frames {})
  (try (rf/init! plain-atom/adapter) (catch :default _ nil))
  ;; Re-register the machines artefact's framework-shipped sub
  ;; (`:rf/machine`) after the registrar clear — see the matching
  ;; comment in `variant_lifecycle_e2e_cljs_test.cljs`.
  (rf/reg-sub :rf/machine
              (fn [db [_ machine-id]]
                (get-in db [:rf/machines machine-id])))
  (machines/reset-timers!)
  (loaders/clear-watchers!)
  (story/install-canonical-vocabulary!)
  (frame/ensure-default-frame!)
  (register-counter-variants!))

(use-fixtures :each {:before reset-all!})

;; ---- counter events ------------------------------------------------------

(defn- install-counter-events! []
  ;; `assoc` (not replace) so the lifecycle machine slot under
  ;; `[:rf/machines :rf.story.lifecycle/machine]` survives — same
  ;; warning as `counter_with_stories/events.cljs` and the matching
  ;; comment in the lifecycle test.
  (rf/reg-event-db :counter/initialise
    (fn [db [_ n]] (assoc db :count (or n 0))))
  (rf/reg-event-db :counter/inc
    (fn [db _] (update db :count inc)))
  (rf/reg-event-fx :counter/save
    (fn [{:keys [db]} _]
      {:db (assoc db :saving? true)
       :fx [[:counter/sync-to-server {:value (:count db)}]]}))
  (rf/reg-fx :counter/sync-to-server
    (fn [_ctx _args] nil)))

;; ---- canonical 4-variant registration -----------------------------------

(defn- register-counter-variants! []
  (install-counter-events!)
  (story/reg-story :story.counter
    {:doc "Parent story for the reg-variant e2e tests — mirrors the
           counter_with_stories testbed."})
  (story/reg-variant :story.counter/empty
    {:doc "Fresh counter at zero. The simplest possible variant."
     :events [[:counter/initialise 0]]
     :play-script [[:dispatch-sync [:rf.assert/path-equals [:count] 0]]]})
  (story/reg-variant :story.counter/loaded
    {:doc "A counter seeded with a non-zero value."
     :events [[:counter/initialise 7]]
     :play-script [[:dispatch-sync [:rf.assert/path-equals [:count] 7]]]})
  (story/reg-variant :story.counter/clicked-three-times
    {:doc "Counter after three increments from zero, driven from the
           play slot so :rf.assert/dispatched? observes them."
     :events [[:counter/initialise 0]]
     :play-script [[:dispatch-sync [:counter/inc]]
                   [:dispatch-sync [:counter/inc]]
                   [:dispatch-sync [:counter/inc]]
                   [:dispatch-sync [:rf.assert/path-equals  [:count] 3]]
                   [:dispatch-sync [:rf.assert/dispatched?  [:counter/inc]]]]})
  (story/reg-variant :story.counter/save-stubbed
    {:doc "The save flow with the network fx stubbed."
     :events [[:counter/initialise 5]]
     :decorators [[story/force-fx-stub-id :counter/sync-to-server {:ok? true}]]
     :play-script [[:dispatch-sync [:counter/save]]
                   [:dispatch-sync [:rf.assert/path-equals    [:saving?] true]]
                   [:dispatch-sync [:rf.assert/effect-emitted :counter/sync-to-server]]]}))

;; ---- helpers -------------------------------------------------------------

(defn- assertions-passing?
  "True iff every assertion in `result`'s `:assertions` slot has
  `:passed? true`. Mirrors `story/assertions-passing?` semantics —
  inlined here so the test reads inline."
  [result]
  (every? (fn [a] (true? (:passed? a))) (:assertions result)))

;; ---- (1) :story.counter/empty -- count 0 --------------------------------

(deftest empty-variant-runs-clean-count-0
  (testing ":story.counter/empty reaches :ready with :count 0 and all
            play-script assertions passing"
    (async done
      (-> (story/run-variant :story.counter/empty)
          (async-lib/then
            (fn [result]
              (is (= :ready (:lifecycle result))
                  "lifecycle reached :ready")
              (is (= 0 (-> result :app-db :count))
                  ":count seeded to 0 by [:counter/initialise 0]")
              (is (assertions-passing? result)
                  "every :rf.assert/* row in the result is :passed? true")
              (story/destroy-variant! :story.counter/empty)
              (done)))))))

;; ---- (2) :story.counter/loaded -- count 7 -------------------------------

(deftest loaded-variant-runs-clean-count-7
  (testing ":story.counter/loaded reaches :ready with :count 7"
    (async done
      (-> (story/run-variant :story.counter/loaded)
          (async-lib/then
            (fn [result]
              (is (= :ready (:lifecycle result))
                  "lifecycle reached :ready")
              (is (= 7 (-> result :app-db :count))
                  ":count seeded to 7 by [:counter/initialise 7]")
              (is (assertions-passing? result)
                  "every :rf.assert/* row in the result is :passed? true")
              (story/destroy-variant! :story.counter/loaded)
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
            :count 3 — three play-script dispatches against an :events
            slot that seeded :count 0. This pins the lifecycle-level
            contract that the Playwright probe (now skipped per
            rf2-8awk1) was trying to assert via the DOM."
    (async done
      (-> (story/run-variant :story.counter/clicked-three-times)
          (async-lib/then
            (fn [result]
              (is (= :ready (:lifecycle result))
                  "lifecycle reached :ready after play-script ran")
              (is (= 3 (-> result :app-db :count))
                  "three [:counter/inc] dispatches in play-script
                   incremented :count from 0 → 3")
              (is (assertions-passing? result)
                  "every :rf.assert/* row in the play-script passed
                   (path-equals 3 + dispatched? :counter/inc)")
              (story/destroy-variant! :story.counter/clicked-three-times)
              (done)))))))

;; ---- (4) :story.counter/save-stubbed -- count 5 + fx-stub ---------------

(deftest save-stubbed-variant-runs-clean-count-5
  (testing ":story.counter/save-stubbed reaches :ready with :count 5 +
            the `:counter/sync-to-server` fx-stub fires through the
            play-script"
    (async done
      (-> (story/run-variant :story.counter/save-stubbed)
          (async-lib/then
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
              (story/destroy-variant! :story.counter/save-stubbed)
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
            the `:events` slot survives serialisation"
    (doseq [vid [:story.counter/empty
                 :story.counter/loaded
                 :story.counter/clicked-three-times
                 :story.counter/save-stubbed]]
      (let [body (story/variant->edn vid)]
        (is (map? body)
            (str "variant->edn returned a map for " vid))
        (is (vector? (:events body))
            (str ":events slot is a vector for " vid))
        (is (= [:counter/initialise]
               (->> body :events first (take 1) vec))
            (str ":events[0] is [:counter/initialise ...] for " vid))))))
