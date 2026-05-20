(ns re-frame.story.panels-e2e.loading-skeleton-lifecycle-e2e-cljs-test
  "Multi-frame e2e coverage for the amber-shimmer loading skeleton
  (rf2-qkcjr, replaces the Playwright
  `amber-shimmer-loading-skeleton-during-phase-1` scenario).

  Per spec/014 §Loading skeleton (rf2-0s4p1) the canvas renders a
  three-bar amber-shimmer skeleton with an inset amber edge while the
  variant's lifecycle machine is in `:pre-mount` / `:mounting` /
  `:loading` AND no first render has committed yet AND no assertions
  have been recorded against the variant frame. Once `:ready` /
  `:error` lands the skeleton hides; once a render has committed the
  skeleton never re-flashes for hot-reload re-runs.

  ## Pipeline under test

      run-variant   /   manual lifecycle drive
            │
            ▼
      lifecycle machine transitions   ← :pre-mount → :mounting → :loading
            │
            ▼
      story-loaders/current-state variant-id  ← :loading
            │
            ▼
      canvas-inner reads (story-loaders/current-state ...)
            │
            ▼
      (loading-phase? :loading first? assertions? events-only?) → true
            │
            ▼
      Hiccup: [:div {:data-test \"story-canvas-loading-skeleton\"
                      :role \"status\" :aria-live \"polite\"
                      :aria-label \"Variant loading\"}
               ... three skeleton bars ...]

      Then driver lands the machine on :ready →
      (loading-phase? :ready ...) → false → user view renders, no skeleton.

  ## What the unit + state tests already cover

  - `canvas_skeleton_cljs_test` — pure `loading-phase?` predicate +
    canonical `loading-skeleton` hiccup shape + first-rendered
    sentinel + assertions-recorded? + events-only? overrides.
  - `variant_lifecycle_e2e_cljs_test` — happy-path / never-completes /
    rejects scenarios drive `run-variant`; assertions-recorded? arm
    pinned via `loading-phase?` directly.

  ## What this e2e adds

  Drives the FULL lifecycle → canvas-render seam in one process:
    1. Phase-1 (lifecycle in `:loading`, no first-render) → skeleton
       node present in canvas-inner's hiccup tree.
    2. Lifecycle transitions to `:ready` → user view renders, no
       skeleton node.
    3. After first render, hot-reload re-run (back to `:loading`) does
       NOT re-flash the skeleton — the first-rendered sentinel pins it
       off.

  Mirrors `variant_lifecycle_e2e_cljs_test`'s lifecycle-driver pattern
  but exercises the canvas-inner render seam (not just the
  result-map's `:lifecycle` slot).

  Sub-millisecond per case; no DOM mount, no React, no Playwright."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.machines :as machines]
            [re-frame.registrar :as registrar]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.story :as story]
            [re-frame.story.loaders :as loaders]
            [re-frame.story.ui.canvas :as canvas]
            [re-frame.story.test-helpers.e2e-multi-frame :as e2e]))

;; Mirror the proven reset pattern in variant_lifecycle_e2e — full
;; registrar clear + manual re-register of the framework's `:rf/machine`
;; sub + canonical-vocab install. Without this the lifecycle machine
;; cannot resolve and the canvas reads `:pre-mount` indefinitely.

(declare register-skeleton-variants!)

(defn- reset-all! []
  (story/clear-all!)
  (registrar/clear-all!)
  (reset! frame/frames {})
  (try (rf/init! plain-atom/adapter) (catch :default _ nil))
  (rf/reg-sub :rf/machine
              (fn [db [_ machine-id]]
                (get-in db [:rf/machines machine-id])))
  (machines/reset-timers!)
  (loaders/clear-watchers!)
  (canvas/reset-first-rendered!)
  (story/install-canonical-vocabulary!)
  (frame/ensure-default-frame!)
  (register-skeleton-variants!))

(use-fixtures :each {:before reset-all!})

;; ---- variant fixture ----------------------------------------------------

;; A minimal variant whose `:component` resolves to a registered view.
;; The skeleton render depends on `canvas-inner` reading the variant
;; body + view-id; without a registered component the canvas's
;; `(nil? view-id)` branch wins and the skeleton never gets a chance.

(defn- skeleton-view [_args] [:div {:data-test "skeleton-test-user-view"} "user view"])

(defn- register-skeleton-variants! []
  (rf/reg-view* :story.skeleton/view skeleton-view)
  (story/reg-story* :story.skeleton {:doc "skeleton-e2e parent story"})
  ;; The test drives the lifecycle machine directly via `loaders/mount!`
  ;; / `start-loaders!` / `finish-loaders!` — `:loaders` doesn't need a
  ;; real event since we never go through `run-variant`. The slot is
  ;; declared so `events-only-variant?` returns false (which is the
  ;; only condition that matters for the `loading-phase?` gate).
  (story/reg-variant* :story.skeleton/v
    {:doc       "Variant declared with :loaders so events-only? is false."
     :component :story.skeleton/view
     :loaders   [[:noop/loader]]}))

;; ---- helper: read the canvas-inner hiccup tree --------------------------

(def ^:private canvas-inner @#'canvas/canvas-inner)

(defn- canvas-inner-tree
  "Drive `canvas-inner` for the test variant and expand the resulting
  hiccup so DOM-walker queries match. canvas-inner is the inner render
  that reads the lifecycle phase + first-rendered + assertions and
  branches on `show-skeleton?`."
  [variant-id]
  (e2e/expand-tree (canvas-inner variant-id)))

(defn- skeleton-node [tree]
  (e2e/find-by-test-id tree "story-canvas-loading-skeleton"))

(defn- user-view-node [tree]
  (e2e/find-by-test-id tree "skeleton-test-user-view"))

;; ---- pipeline (1): phase-1 lifecycle → skeleton renders -----------------

(deftest phase-1-loading-renders-skeleton
  (testing "rf2-qkcjr — with the lifecycle machine in `:loading` AND no
            first render committed AND no assertions recorded, the
            canvas-inner hiccup tree carries the skeleton node with the
            canonical data-test attribute. Pins the phase-1 → skeleton
            seam."
    (let [variant-id :story.skeleton/v]
      (rf/reg-frame variant-id {})
      ;; Drive the lifecycle into :loading. Without an explicit
      ;; transition, `current-state` reports :pre-mount which also
      ;; lights up the skeleton — but pinning :loading specifically
      ;; matches the bead's user-facing acceptance criterion ("canvas
      ;; shows the skeleton when loaders run").
      (loaders/mount! variant-id)
      (loaders/start-loaders! variant-id)
      (is (= :loading (loaders/current-state variant-id))
          "lifecycle parked at :loading for the assertion below")
      (let [tree (canvas-inner-tree variant-id)]
        (is (some? (skeleton-node tree))
            "canvas-inner emits the `story-canvas-loading-skeleton`
             node during phase-1")
        (is (nil? (user-view-node tree))
            "user view does NOT render while the skeleton is showing
             (the skeleton replaces the user view in the cond branch)"))
      (story/destroy-variant! variant-id))))

;; ---- pipeline (2): pre-mount also renders skeleton ----------------------

(deftest pre-mount-also-renders-skeleton
  (testing "rf2-qkcjr — `:pre-mount` is also a loading phase per
            `loading-phase?`. The canvas-inner skeleton lights up the
            moment the user selects a variant — before the lifecycle
            has even transitioned to :mounting. Pins the
            'pre-mount → skeleton' arm so a regression that gated only
            on :loading wouldn't pass."
    (let [variant-id :story.skeleton/v]
      (rf/reg-frame variant-id {})
      ;; No transitions — machine reports :pre-mount.
      (is (= :pre-mount (loaders/current-state variant-id))
          "fresh frame → :pre-mount")
      (let [tree (canvas-inner-tree variant-id)]
        (is (some? (skeleton-node tree))
            "skeleton renders during :pre-mount"))
      (story/destroy-variant! variant-id))))

;; ---- pipeline (3): :ready phase elides the skeleton ---------------------

(deftest ready-phase-elides-skeleton-and-renders-user-view
  (testing "rf2-qkcjr — once the lifecycle reaches `:ready`, the
            skeleton hides and the user-view renders. Pins the
            phase-3 transition (skeleton off, user content on)."
    (let [variant-id :story.skeleton/v]
      (rf/reg-frame variant-id {})
      ;; Drive the lifecycle all the way through :ready.
      (loaders/mount! variant-id)
      (loaders/start-loaders! variant-id)
      (loaders/finish-loaders! variant-id)
      (loaders/finish-events! variant-id)
      (is (= :ready (loaders/current-state variant-id))
          "lifecycle reached :ready before the assertion below")
      (let [tree (canvas-inner-tree variant-id)]
        (is (nil? (skeleton-node tree))
            "skeleton is NOT in the tree once :ready lands")
        (is (some? (user-view-node tree))
            "user view renders post-:ready"))
      (story/destroy-variant! variant-id))))

;; ---- pipeline (4): first-rendered sentinel pins skeleton off ------------

(deftest first-rendered-sentinel-pins-skeleton-off
  (testing "rf2-qkcjr — once a variant has committed its first render
            (sentinel flipped via `mark-variant-rendered!`), subsequent
            renders never re-show the skeleton — even if the lifecycle
            slips back to `:loading` for a hot-reload re-run. Reading
            as a glitch is the regression class this gate prevents."
    (let [variant-id :story.skeleton/v]
      (rf/reg-frame variant-id {})
      ;; Lifecycle in :loading, sentinel set → skeleton MUST NOT show.
      (loaders/mount! variant-id)
      (loaders/start-loaders! variant-id)
      (canvas/mark-variant-rendered! variant-id)
      (is (= :loading (loaders/current-state variant-id))
          "machine still in :loading — sentinel is the only off-switch")
      (let [tree (canvas-inner-tree variant-id)]
        (is (nil? (skeleton-node tree))
            "first-rendered sentinel pins the skeleton off")
        (is (some? (user-view-node tree))
            "user view renders despite the lifecycle being in :loading"))
      (story/destroy-variant! variant-id))))

;; ---- pipeline (5): skeleton hiccup shape is the canonical one ------------

(deftest skeleton-node-carries-canonical-aria-shape
  (testing "rf2-qkcjr — the rendered skeleton carries the canonical
            `role=\"status\"` + `aria-live=\"polite\"` shape so screen
            readers announce the loading state. Pins the
            accessibility-bearing slots on the emitted hiccup so a
            simplification refactor that dropped them would surface."
    (let [variant-id :story.skeleton/v]
      (rf/reg-frame variant-id {})
      (loaders/mount! variant-id)
      (loaders/start-loaders! variant-id)
      (let [tree  (canvas-inner-tree variant-id)
            node  (skeleton-node tree)
            attrs (when (and (vector? node) (map? (second node)))
                    (second node))]
        (is (some? attrs) "skeleton node has an attrs map")
        (is (= "status" (:role attrs))
            "skeleton announces as role=status to AT")
        (is (= "polite" (:aria-live attrs))
            "aria-live=polite — non-interrupting loading announcement")
        (is (= "Variant loading" (:aria-label attrs))
            "aria-label names the loading region for screen readers"))
      (story/destroy-variant! variant-id))))
