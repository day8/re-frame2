(ns re-frame.story.ui.test-mode.view-variant-switch-dom-cljs-test
  "DOM-mount regression for rf2-4e545l finding 3: the `:test` mode
  pane's `test-view` must auto-run a NEWLY-focused variant even when
  React reconciles the switch as a prop update rather than a fresh
  mount.

  ## Why this needs a REAL DOM mount

  shell.cljs mounts the pane with NO React key
  (`[test-mode-view/test-view variant-id]`), and `:active-mode-tab` is
  per-variant persisted, so switching `:selected-variant` between two
  variants BOTH already on the `:test` mode-tab keeps the SAME
  component TYPE at the SAME tree position — React reconciles this as a
  PROP update on the existing instance rather than unmounting and
  remounting it. Whether that reconciliation actually happens (as
  opposed to a fresh mount) is a React-commit fact; no amount of pure
  hiccup-tree inspection proves it — only a real render + a SECOND real
  render after the prop swap can. `test-view`'s prior implementation
  drove the auto-run from `:component-did-mount` alone, which never
  re-fires on a reconciled prop update, so the newly-focused variant's
  pane rendered blank until the user clicked Re-run.

  ## Pipeline under test

      mount [test-mode.view/test-view variant-a] (no React key)
            |
      test-view's r/with-let body -> variant-has-tests? + no stored
      result -> state/run-variant-pane! variant-a
            |
      re-render the SAME root with [test-mode.view/test-view variant-b]
      -- same component type, no key, so React reconciles as a PROP
      UPDATE (no unmount/remount)
            |
      ASSERT: variant-b's slot in test-mode.state/results-atom shows
      the run fired (:running? true, stamped synchronously by
      run-variant-pane!'s begin-run! prelude before the async
      reset-variant promise even settles) -- proving auto-run re-fired
      for the reconciled variant, not just the fresh mount.

  ns ends in `-dom-cljs-test` so shadow-cljs's `:browser-test` build
  discovers it and mounts real DOM via `react-dom/client`; `:node-test`
  also loads it (its `cljs-test$` regex matches the `-dom-cljs-test`
  suffix too) where the body self-gates on `(browser?)` and no-ops."
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
            [re-frame.story.ui.state :as state]
            [re-frame.story.ui.test-mode.state :as tm-state]
            [re-frame.story.ui.test-mode.view :as view]
            [re-frame.subs :as subs]))

;; ---- fixture --------------------------------------------------------------

(defn- reset-all! []
  (story/clear-all!)
  (registrar/clear-all!)
  (reset! frame/frames {})
  (try (rf/init! reagent-adapter/adapter)
       (catch :default _ nil))
  ;; Re-register the framework `:rf/machine` sub after the registrar
  ;; clear (mirrors the sibling viewport-toggle-app-db-dom-cljs-test's
  ;; reset-all!).
  (subs/reg-runtime-sub :rf/machine
    (fn [runtime-db [_ machine-id]]
      (get-in runtime-db [:rf.runtime/machines :snapshots machine-id])))
  (machines/reset-timers!)
  (loaders/clear-watchers!)
  (reset! tm-state/results-atom {})
  (state/reset-shell-state!)
  (story/install-canonical-vocabulary!)
  (frame/ensure-default-frame!))

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

(deftest switching-variant-prop-without-remount-autoruns-new-variant
  (testing "rf2-4e545l finding 3 — mounting test-view with NO React key
            (mirroring shell.cljs's `[test-mode-view/test-view
            variant-id]` call site) and then re-rendering the SAME root
            with a DIFFERENT variant-id (simulating :selected-variant
            changing while :active-mode-tab stays :test for both)
            reconciles as a prop update, not a remount. The
            newly-focused variant must still auto-run."
    (if-not (browser?)
      (is true ":node-test — no DOM; :browser-test runs the real assertion")
      (let [va :story.testview-switch/a
            vb :story.testview-switch/b]
        (rf/reg-event :testview-switch/set-a
          (fn [{:keys [db]} _] {:db (assoc db :v "a")}))
        (rf/reg-event :testview-switch/set-b
          (fn [{:keys [db]} _] {:db (assoc db :v "b")}))
        (story/reg-variant va
          {:events      [[:testview-switch/set-a]]
           :play-script [[:dispatch-sync [:rf.assert/path-equals [:v] "a"]]]})
        (story/reg-variant vb
          {:events      [[:testview-switch/set-b]]
           :play-script [[:dispatch-sync [:rf.assert/path-equals [:v] "b"]]]})
        (let [mount-node (make-mount-node!)
              root       (rdc/create-root mount-node)]
          (try
            ;; Initial mount on variant A — no React key, mirroring
            ;; shell.cljs's call site.
            (react-dom/flushSync
              (fn [] (rdc/render root [view/test-view va])))
            (is (true? (get-in @tm-state/results-atom [va :running?]))
                "variant A auto-ran on first mount (run-variant-pane!'s
                 begin-run! prelude stamps :running? synchronously)")
            (is (some? (.querySelector mount-node "[data-test=\"story-test-view\"]"))
                "precondition: the pane actually rendered")

            ;; Re-render the SAME root with a DIFFERENT variant-id —
            ;; same component type, no key, so React reconciles this as
            ;; a PROP UPDATE (no unmount/remount) at the same tree
            ;; position.
            (react-dom/flushSync
              (fn [] (rdc/render root [view/test-view vb])))
            (is (true? (get-in @tm-state/results-atom [vb :running?]))
                "rf2-4e545l: variant B auto-ran too, even though React
                 reconciled the swap as a prop update rather than a
                 fresh mount — the pre-fix :component-did-mount-only
                 auto-run never re-fired here, leaving the pane blank
                 until a manual Re-run")

            (finally
              (try (.unmount root) (catch :default _ nil)))))))))
