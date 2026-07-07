(ns re-frame.story.ui.events-only-first-render-frame-provider-dom-cljs-test
  "DOM-mount regression for rf2-4iu7tu: an EVENTS-ONLY variant's first
  render must not reach `frame-provider` before its frame is allocated.

  ## The race

  `loading-phase?` DELIBERATELY suppresses the loading skeleton for
  events-only variants (`loaders/events-only-variant?` — no `:loaders`,
  no `:loaders-complete-when`, no `:frame-setup` decorator), so
  `canvas-inner`'s first render falls straight through to
  `[rf/frame-provider {:frame variant-id} ...]`. That SCOPE-ONLY shape
  FAILS LOUD (`:rf.error/frame-provider-frame-absent`) when the frame is
  absent — and `component-did-mount` (which normally allocates it via
  `run-if-needed!`) fires AFTER React commits this very render, so a
  variant selected BEFORE the canvas ever mounts (a deep link, a
  persisted selection, or — as here — a bare pre-seeded
  `:selected-variant`) never got a pre-allocated frame: the
  `selection-watcher` pre-allocation (rf2-zme7) only fires on a
  `:selected-variant` CHANGE, and there is none to observe when the
  selection predates the watcher's own installation.

  This was surfaced by the rf2-j8hklm viewport-toggle DOM test (#5265),
  which had to omit `:component` from its probe variant specifically to
  dodge this crash (see `viewport_toggle_app_db_dom_cljs_test.cljs`'s
  comment) — orthogonal to what THAT test verifies. This test drives the
  race head-on: a `:component` IS registered, so the first commit must
  reach (and survive) `frame-provider`.

  ## Why this needs a REAL DOM mount

  The bug is a React render-vs-commit-vs-componentDidMount ORDERING
  defect — no pure-hiccup inspection can observe whether `frame-provider`
  runs before or after `component-did-mount` allocates the frame; only a
  real `react-dom` commit proves it. Ns ends in `-dom-cljs-test` so
  shadow-cljs's `:browser-test` build discovers it and mounts real DOM via
  `react-dom/client`; `:node-test` also loads it (its `cljs-test$` regex
  matches the `-dom-cljs-test` suffix too) where the body self-gates on
  `(browser?)` and no-ops."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            ["react-dom" :as react-dom]
            [reagent.dom.client :as rdc]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.machines :as machines]
            [re-frame.registrar :as registrar]
            [re-frame.adapter.reagent :as reagent-adapter]
            [re-frame.story :as story]
            [re-frame.story.loaders :as loaders]
            [re-frame.story.ui.canvas :as canvas]
            [re-frame.story.ui.state :as state]
            [re-frame.subs :as subs]))

;; ---- fixture --------------------------------------------------------------

(defn- reset-all! []
  (story/clear-all!)
  (registrar/clear-all!)
  (reset! frame/frames {})
  (try (rf/init! reagent-adapter/adapter)
       (catch :default _ nil))
  ;; Re-register the framework `:rf/machine` sub after the registrar
  ;; clear (mirrors viewport_toggle_app_db_dom_cljs_test's reset-all!).
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

(deftest events-only-variant-first-render-does-not-fail-loud-on-absent-frame
  (testing "rf2-4iu7tu: an events-only variant selected BEFORE the canvas
            ever mounts reaches `canvas-inner`'s FIRST render with NO
            frame allocated yet. Pre-fix that render fell through to
            `frame-provider` on the absent frame and threw
            `:rf.error/frame-provider-frame-absent`; post-fix
            `canvas-inner` ensures the frame during THIS render (mirroring
            `re-frame.views.owned-frame/ensure-frame-fc`'s render-phase
            ensure), so the mount succeeds and the variant's view actually
            paints with its seeded state."
    (if-not (browser?)
      (is true ":node-test — no DOM; :browser-test runs the real assertion")
      (let [variant-id :story.eofr/probe]
        (rf/reg-event :eofr/seed
          (fn [{:keys [db]} [_ v]] {:db (assoc db :n v)}))
        (rf/reg-sub :eofr/n (fn [db _] (:n db)))
        ;; Deliberately WITH a :component — the #5265 test omitted this to
        ;; route AROUND the exact race this test drives straight at.
        (rf/reg-view* :views/eofr-probe
          (fn [_]
            [:div {:data-test "eofr-probe"}
             (str "n=" @(rf/subscribe [:eofr/n]))]))
        (story/reg-variant variant-id
          {:component :views/eofr-probe
           :events    [[:eofr/seed 7]]})
        ;; Pre-select BEFORE mount — no selection-watcher pre-allocation
        ;; edge ever fires (the watcher doesn't exist yet; there's no
        ;; :selected-variant CHANGE for it to observe even once installed).
        ;; This is the exact condition #5265's comment names: "the SAME
        ;; synchronous render pass that first builds the vdom" reaches
        ;; frame-provider before component-did-mount ever runs.
        (state/swap-state! state/select-variant variant-id)
        (let [mount-node (make-mount-node!)
              root       (rdc/create-root mount-node)]
          (try
            (is (nil?
                  (try
                    (react-dom/flushSync
                      (fn [] (rdc/render root [canvas/canvas])))
                    nil
                    (catch :default e e)))
                "the first commit must NOT throw
                 :rf.error/frame-provider-frame-absent")
            (is (= "n=7"
                   (some-> (.querySelector mount-node "[data-test=\"eofr-probe\"]")
                           .-textContent))
                "the variant's view actually rendered — the frame existed
                 by the time frame-provider scoped it, and the seed
                 :events landed before the view's subscription read it")
            (finally
              (try (.unmount root) (catch :default _ nil)))))))))
