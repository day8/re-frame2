(ns re-frame.story.ui.test-mode.view-variant-switch-dom-cljs-test
  "DOM-mount test: after the `:test` pane switches variant as a React PROP
  UPDATE, it follows the runs of the NEWLY-shown variant.

  ## Why this needs a REAL DOM mount

  shell.cljs mounts the pane with NO React key, and `:active-mode-tab` is
  per-variant persisted, so switching `:selected-variant` between two
  variants BOTH already on the `:test` mode-tab keeps the SAME component
  TYPE at the SAME tree position — React reconciles this as a PROP update
  on the existing instance rather than unmounting and remounting it.
  Whether that reconciliation actually happens is a React-commit fact; only
  a real render + a SECOND real render after the prop swap can show it. A
  pane that recorded its variant only from `:component-did-mount` would keep
  following the first variant, and the newly-shown variant's runs would
  never reach its slot.

  ## Pipeline under test

      mount [test-mode.view/test-view variant-a] (no React key)
            |
      a run of variant-a, started the way the canvas starts one
            -> lands in variant-a's slot
            |
      re-render the SAME root with [test-mode.view/test-view variant-b]
      -- same component type, no key, so React reconciles as a PROP
      UPDATE (no unmount/remount)
            |
      a run of variant-b -> lands in variant-b's slot; a later run of
      variant-a, no longer shown, leaves variant-a's slot as it was

  ns ends in `-dom-cljs-test` so shadow-cljs's `:browser-test` build
  discovers it and mounts real DOM via `react-dom/client`; `:node-test`
  also loads it (its `cljs-test$` regex matches the `-dom-cljs-test`
  suffix too) where the body self-gates on `(browser?)` and no-ops."
  (:require [cljs.test :refer-macros [async deftest is testing use-fixtures]]
            ["react-dom" :as react-dom]
            [reagent.dom.client :as rdc]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.machines :as rf.machines]
            [re-frame.registrar :as rf.registrar]
            [re-frame.adapter.reagent :as rf.adapter.reagent]
            [re-frame.story :as rf.story]
            [re-frame.story.loaders :as rf.story.loaders]
            [re-frame.story.runtime :as rf.story.runtime]
            [re-frame.story.ui.canvas :as rf.story.ui.canvas]
            [re-frame.story.ui.state :as rf.story.ui.state]
            [re-frame.story.ui.test-mode.state :as rf.story.ui.test-mode.state]
            [re-frame.story.ui.test-mode.view :as rf.story.ui.test-mode.view]
            [re-frame.subs :as rf.subs]))

;; ---- fixture --------------------------------------------------------------

(defn- reset-all! []
  (rf.story/clear-all!)
  (rf.registrar/clear-all!)
  (reset! rf.frame/frames {})
  (try (rf/init! rf.adapter.reagent/adapter)
       (catch :default _ nil))
  ;; Re-register the framework `:rf/machine` sub after the registrar
  ;; clear (mirrors the sibling viewport-toggle-app-db-dom-cljs-test's
  ;; reset-all!).
  (rf.subs/reg-runtime-sub :rf/machine
    (fn [runtime-db [_ machine-id]]
      (get-in runtime-db [:rf.runtime/machines :snapshots machine-id])))
  (rf.machines/reset-timers!)
  (rf.story.loaders/clear-watchers!)
  (rf.story.runtime/reset-run-owner!)
  (reset! rf.story.ui.test-mode.state/results-atom {})
  (rf.story.ui.state/reset-shell-state!)
  (rf.story/install-canonical-vocabulary!)
  (rf.frame/ensure-default-frame!))

(use-fixtures :each {:before reset-all!})

;; ---- browser gate -----------------------------------------------------

(defn- browser? []
  (and (exists? js/document)
       (some? (.-createElement js/document))))

(defn- make-mount-node! []
  (let [node (js/document.createElement "div")]
    (js/document.body.appendChild node)
    node))

(defn- canvas-run!
  "Run `vid` the way the canvas does: prepare the one run owner with the
  canvas's opts, then resume it. Returns the resume's promise."
  [vid]
  (rf.story.runtime/prepare-run!
    vid (rf.story.ui.canvas/run-opts
          (rf.story.ui.canvas/run-key (rf.story.ui.state/get-state) vid)))
  (rf.story.runtime/resume-run! vid))

(defn- slot-result [vid]
  (get-in @rf.story.ui.test-mode.state/results-atom [vid :result]))

;; ---- the reconciled switch ---------------------------------------------

(deftest switching-variant-prop-without-remount-follows-the-new-variant
  (testing "mounting test-view with NO React key (mirroring shell.cljs's
            call site) and then re-rendering the SAME root with a DIFFERENT
            variant-id (simulating :selected-variant changing while
            :active-mode-tab stays :test for both) reconciles as a prop
            update, not a remount. The pane must then follow the NEWLY-shown
            variant's runs."
    (if-not (browser?)
      (is true ":node-test — no DOM; :browser-test runs the real assertion")
      (let [va :story.testview-switch/a
            vb :story.testview-switch/b]
        (rf/reg-event :testview-switch/set-a
          (fn [{:keys [db]} _] {:db (assoc db :v "a")}))
        (rf/reg-event :testview-switch/set-b
          (fn [{:keys [db]} _] {:db (assoc db :v "b")}))
        (rf.story/reg-variant va
          {:setup      [[:testview-switch/set-a]]
           :script [[:dispatch-sync [:rf.assert/path-equals [:v] "a"]]]})
        (rf.story/reg-variant vb
          {:setup      [[:testview-switch/set-b]]
           :script [[:dispatch-sync [:rf.assert/path-equals [:v] "b"]]]})
        (async done
          (let [mount-node (make-mount-node!)
                root       (rdc/create-root mount-node)
                finish     (fn []
                             (try (.unmount root) (catch :default _ nil))
                             (.remove mount-node)
                             (done))]
            ;; Initial mount on variant A — no React key, mirroring
            ;; shell.cljs's call site.
            (react-dom/flushSync
              (fn [] (rdc/render root [rf.story.ui.test-mode.view/test-view va])))
            (is (some? (.querySelector mount-node "[data-test=\"story-test-view\"]"))
                "precondition: the pane actually rendered")
            (-> (canvas-run! va)
                (.then (fn [result]
                         (is (= result (slot-result va))
                             "a run of the shown variant A lands in A's slot")
                         ;; Re-render the SAME root with a DIFFERENT
                         ;; variant-id — same component type, no key, so
                         ;; React reconciles this as a PROP UPDATE.
                         (react-dom/flushSync
                           (fn [] (rdc/render root [rf.story.ui.test-mode.view/test-view vb])))
                         (canvas-run! vb)))
                (.then (fn [result]
                         (is (= result (slot-result vb))
                             "after the prop swap, a run of the newly-shown
                              variant B lands in B's slot")
                         (let [a-before (slot-result va)]
                           (-> (canvas-run! va)
                               (.then (fn [_]
                                        (is (identical? a-before (slot-result va))
                                            "a run of A, no longer shown, leaves A's
                                             slot as it was")))))))
                (.then (fn [_] (finish)))
                (.catch (fn [e]
                          (is false (str "a run rejected: " e))
                          (finish))))))))))
