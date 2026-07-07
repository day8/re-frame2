(ns re-frame.story.ui.viewport-toggle-app-db-dom-cljs-test
  "DOM-mount regression for rf2-j8hklm: toggling the toolbar viewport
  across the `:full`-vs-sized boundary must NOT force-remount the
  canvas / re-run the variant, wiping the live variant's interactive
  app-db.

  ## Why this needs a REAL DOM mount

  The bug is a React RECONCILIATION-identity defect: `shell.cljs`'s
  `framed-canvas` used to pick between TWO DIFFERENT hiccup shapes —
  `[:div {...} [canvas/canvas]]` when a sized viewport was active, or
  bare `[canvas/canvas]` otherwise — at the SAME tree position. Toggling
  `sized?` therefore changed the React element TYPE at that slot, which
  forces React to unmount the old subtree and mount a fresh one — no
  amount of pure hiccup-tree inspection proves whether that actually
  happens; only a real React commit + a SECOND real React commit after
  the toggle can. This is also the acceptance criterion the bead itself
  names as the test gap: 'no test mounts the real shell and toggles
  viewport to check state survival.'

  ## Pipeline under test

      mount [shell/framed-canvas] (viewport :full)
            |
      canvas/canvas's component-did-mount -> run-if-needed! -> run-variant
            |  (variant's :events seed app-db)
            v
      simulate user interaction: dispatch a NON-setup event into the
      variant's frame directly (mirrors 'user increments a counter')
            |
      vs/select! :tablet  (crosses the :full -> sized boundary)
            |
      flushSync a second commit
            v
      ASSERT: the variant frame's app-db still carries the interactive
      mutation -- canvas was NOT remounted / re-run.

  ns ends in `-dom-cljs-test` so shadow-cljs's `:browser-test` build
  discovers it and mounts real DOM via `react-dom/client`; `:node-test`
  also loads it (its `cljs-test$` regex matches the `-dom-cljs-test`
  suffix too) where the body self-gates on `(browser?)` and no-ops."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            ["react-dom" :as react-dom]
            [reagent.core :as r]
            [reagent.dom.client :as rdc]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.machines :as machines]
            [re-frame.registrar :as registrar]
            [re-frame.adapter.reagent :as reagent-adapter]
            [re-frame.story :as story]
            [re-frame.story.loaders :as loaders]
            [re-frame.story.ui.canvas :as canvas]
            [re-frame.story.ui.shell :as shell]
            [re-frame.story.ui.state :as state]
            [re-frame.story.ui.viewport-switcher :as vs]
            [re-frame.subs :as subs]))

;; `framed-canvas` is `defn-` in shell.cljs; the established Story-test
;; seam for reaching a private fn is the var-quote (e.g.
;; `re-frame.story.ui.shell-cljs-test`'s `mount-time-autorun-vid`).
(def ^:private framed-canvas @#'shell/framed-canvas)

;; ---- fixture --------------------------------------------------------------

(defn- reset-all! []
  (story/clear-all!)
  (registrar/clear-all!)
  (reset! frame/frames {})
  (try (rf/init! reagent-adapter/adapter)
       (catch :default _ nil))
  ;; Re-register the framework `:rf/machine` sub after the registrar
  ;; clear (mirrors render-shell-cljs-test's reset-all!).
  (subs/reg-runtime-sub :rf/machine
    (fn [runtime-db [_ machine-id]]
      (get-in runtime-db [:rf.runtime/machines :snapshots machine-id])))
  (machines/reset-timers!)
  (loaders/clear-watchers!)
  (state/reset-shell-state!)
  (story/install-canonical-vocabulary!)
  (frame/ensure-default-frame!)
  (canvas/reset-first-rendered!))

(use-fixtures :each {:before reset-all!})

;; ---- browser gate -----------------------------------------------------

(defn- browser? []
  (and (exists? js/document)
       (some? (.-createElement js/document))))

(defn- make-mount-node! []
  (let [node (js/document.createElement "div")]
    (js/document.body.appendChild node)
    node))

;; ---- the regression ---------------------------------------------------

(deftest viewport-toggle-across-full-vs-sized-boundary-preserves-app-db
  (testing "rf2-j8hklm: selecting a sized viewport preset after the
            canvas has already mounted + run its variant does NOT wipe
            interactive app-db state accumulated since that run"
    (if-not (browser?)
      (is true ":node-test — no DOM; :browser-test runs the real assertion")
      (let [variant-id :story.viewport-toggle/probe]
        (rf/reg-event :vp-toggle/set
          (fn [{:keys [db]} [_ v]] {:db (assoc db :n v)}))
        (rf/reg-event :vp-toggle/inc
          (fn [{:keys [db]} _] {:db (update db :n (fnil inc 0))}))
        ;; Deliberately NO `:component` — this test only needs the
        ;; variant's FRAME (allocated by `run-variant`'s `component-did-
        ;; mount`) and its app-db, not a rendered view. Registering one
        ;; would route `canvas-inner` through `rf/frame-provider {:frame
        ;; variant-id}` on the SAME synchronous render pass that first
        ;; builds the vdom — BEFORE `component-did-mount` has had a
        ;; chance to allocate that frame — which fails loud
        ;; (`:rf.error/frame-provider-frame-absent`) for this variant's
        ;; events-only fast path (no loaders means no skeleton gates that
        ;; first pass). That race is orthogonal to rf2-j8hklm and not
        ;; this test's concern; omitting `:component` keeps `canvas-inner`
        ;; on its `nil? view-id` branch (a harmless placeholder message)
        ;; so only the mechanism under test — `framed-canvas` /
        ;; `canvas/canvas`'s mount lifecycle — is exercised.
        (story/reg-variant variant-id
          {:events [[:vp-toggle/set 1]]})
        (state/swap-state! state/select-variant variant-id)
        (let [mount-node (make-mount-node!)
              root       (rdc/create-root mount-node)]
          (try
            ;; Initial mount: viewport defaults to :full (unsized).
            ;; `component-did-mount` -> `run-if-needed!` -> `run-variant`
            ;; runs synchronously for this loader-less, events-only
            ;; variant (phases 0-2 execute inside the `js/Promise`
            ;; executor, which JS runs synchronously before `run-variant`
            ;; returns), so the seed event has already landed by the time
            ;; `flushSync` returns.
            (react-dom/flushSync
              (fn [] (rdc/render root [framed-canvas])))
            (is (= 1 (:n (rf/app-db-value variant-id)))
                "the variant's setup :events seeded app-db on first mount")
            (is (nil? (.querySelector mount-node "[data-test=\"story-canvas-frame-sized\"]"))
                "precondition: the default :full viewport is UNSIZED —
                 the sized wrapper marker is absent")

            ;; Simulate a user interaction: a dispatch the variant's
            ;; OWN :events never fire (not a setup re-run artifact).
            (rf/dispatch-sync [:vp-toggle/inc] {:frame variant-id})
            (is (= 2 (:n (rf/app-db-value variant-id)))
                "the simulated interaction landed")

            ;; Cross the :full -> sized boundary. If the canvas remounts
            ;; here, `component-will-unmount` clears the run-key sentinel
            ;; and the fresh `component-did-mount` unconditionally re-runs
            ;; `run-variant`, resetting the frame to the bare :n 1 seed —
            ;; wiping the :n 2 interactive state asserted above.
            (react-dom/flushSync
              (fn []
                (vs/select! :tablet)
                (r/flush)))

            (is (some? (.querySelector mount-node "[data-test=\"story-canvas-frame-sized\"]"))
                "the toggle actually took effect — :tablet is a sized preset")
            (is (= 2 (:n (rf/app-db-value variant-id)))
                "rf2-j8hklm: the interactive app-db mutation SURVIVES the
                 viewport toggle — the canvas was not force-remounted /
                 re-run across the :full-vs-sized boundary")

            (finally
              (try (.unmount root) (catch :default _ nil)))))))))

(deftest viewport-toggle-back-to-full-also-preserves-app-db
  (testing "rf2-j8hklm: the inverse direction — sized back to :full —
            is the SAME reconciliation boundary and must be equally
            stable"
    (if-not (browser?)
      (is true ":node-test — no DOM; :browser-test runs the real assertion")
      (let [variant-id :story.viewport-toggle/probe-reverse]
        (rf/reg-event :vp-toggle-rev/set
          (fn [{:keys [db]} [_ v]] {:db (assoc db :n v)}))
        (rf/reg-event :vp-toggle-rev/inc
          (fn [{:keys [db]} _] {:db (update db :n (fnil inc 0))}))
        ;; No `:component` — see the sibling test above for why.
        (story/reg-variant variant-id
          {:events [[:vp-toggle-rev/set 1]]})
        ;; Start already on a sized preset.
        (vs/select! :tablet)
        (state/swap-state! state/select-variant variant-id)
        (let [mount-node (make-mount-node!)
              root       (rdc/create-root mount-node)]
          (try
            (react-dom/flushSync
              (fn [] (rdc/render root [framed-canvas])))
            (is (= 1 (:n (rf/app-db-value variant-id))))
            (is (some? (.querySelector mount-node "[data-test=\"story-canvas-frame-sized\"]"))
                "precondition: mounted already sized")

            (rf/dispatch-sync [:vp-toggle-rev/inc] {:frame variant-id})
            (is (= 2 (:n (rf/app-db-value variant-id))))

            (react-dom/flushSync
              (fn []
                (vs/select! :full)
                (r/flush)))

            (is (nil? (.querySelector mount-node "[data-test=\"story-canvas-frame-sized\"]"))
                "the toggle actually took effect — back to :full")
            (is (= 2 (:n (rf/app-db-value variant-id)))
                "the interactive mutation survives the reverse toggle too")

            (finally
              (try (.unmount root) (catch :default _ nil)))))))))
