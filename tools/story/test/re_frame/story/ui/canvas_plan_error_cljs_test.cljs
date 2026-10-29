(ns re-frame.story.ui.canvas-plan-error-cljs-test
  "A variant whose plan does not compile renders a contained error card in
  its subject's place, on the canvas, in a workspace cell and in the
  side-by-side grid, while everything around it keeps rendering.

  Nothing above a render catches, so a compile that throws during render
  unmounts the whole React root: one bad variant would blank the sidebar,
  the toolbar and every other variant. The broken variant here leaves a
  `[:arg k]` nothing supplies, an everyday authoring state.

  The trees are expanded to plain hiccup, as the canvas and workspace
  routing tests do, so no DOM or React runs. `canvas-inner` and
  `variant-cell-inner` sit inside CLJS-only code, so only `npm run
  test:cljs` witnesses this namespace."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.machines :as rf.machines]
            [re-frame.registrar :as rf.registrar]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.story :as rf.story]
            [re-frame.story.loaders :as rf.story.loaders]
            [re-frame.story.plan :as rf.story.plan]
            [re-frame.story.test-helpers.e2e-multi-frame :as rf.story.test-helpers.e2e-multi-frame]
            [re-frame.story.ui.canvas :as rf.story.ui.canvas]
            [re-frame.story.ui.multi-substrate :as rf.story.ui.multi-substrate]
            [re-frame.story.ui.state :as rf.story.ui.state]
            [re-frame.story.ui.workspace :as rf.story.ui.workspace]
            [re-frame.subs :as rf.subs]))

;; ---- fixtures ------------------------------------------------------------

(defn- probe-view [args]
  [:div {:data-test "plan-error-probe"} (str "label=" (:label args))])

(defn- register-variants! []
  (rf/reg-view* :views/plan-error-probe probe-view)
  (rf.story/reg-story* :story.plan-error
    {:doc "plan-error witness story" :component :views/plan-error-probe})
  (rf.story/reg-variant* :story.plan-error/healthy
    {:doc "Compiles." :args {:label "ok"}})
  (rf.story/reg-variant* :story.plan-error/broken
    {:doc "Leaves an [:arg] nothing supplies, so its plan does not compile."
     :args {:label [:arg :nobody-supplies-this]}}))

(defn- reset-all! []
  (rf.story/clear-all!)
  (rf.registrar/clear-all!)
  (reset! rf.frame/frames {})
  (try (rf/init! rf.substrate.plain-atom/adapter) (catch :default _ nil))
  (rf.subs/reg-runtime-sub :rf/machine
    (fn [runtime-db [_ machine-id]]
      (get-in runtime-db [:rf.runtime/machines :snapshots machine-id])))
  (rf.machines/reset-timers!)
  (rf.story.loaders/clear-watchers!)
  (rf.story.ui.canvas/reset-first-rendered!)
  (rf.story.ui.state/reset-shell-state!)
  (rf.story/install-canonical-vocabulary!)
  (rf.frame/ensure-default-frame!)
  (register-variants!))

(use-fixtures :each {:before reset-all!})

;; ---- helpers -------------------------------------------------------------

(def ^:private canvas-inner @#'rf.story.ui.canvas/canvas-inner)
(def ^:private variant-cell-inner @#'rf.story.ui.workspace/variant-cell-inner)

(defn- expand [hiccup]
  (rf.story.test-helpers.e2e-multi-frame/expand-tree hiccup))

(defn- find-test-id [tree id]
  (rf.story.test-helpers.e2e-multi-frame/find-by-test-id tree id))

(defn- card-text [tree]
  (some->> (find-test-id tree "story-plan-error")
           rf.story.test-helpers.e2e-multi-frame/text-nodes
           (str/join " ")))

(defn- ready! [variant-id]
  (rf/make-frame {:id variant-id})
  (rf.story.loaders/mount! variant-id)
  (rf.story.loaders/start-loaders! variant-id)
  (rf.story.loaders/finish-loaders! variant-id)
  (rf.story.loaders/finish-events! variant-id)
  (rf.story.ui.canvas/mark-variant-rendered! variant-id))

;; ===========================================================================

(deftest the-broken-variant-really-does-not-compile
  (is (thrown? :default
               (rf.story.plan/variant-plan :story.plan-error/broken {}))
      "PRECONDITION — the broken variant's plan compile throws"))

(deftest a-broken-plan-renders-a-contained-card-on-the-canvas
  (testing "the canvas keeps its title row and shows the card, naming the
            error id and message, in the subject's place"
    (ready! :story.plan-error/broken)
    (let [tree (expand (canvas-inner :story.plan-error/broken))
          text (card-text tree)]
      (is (some? (find-test-id tree "story-plan-error")) "the error card renders")
      (is (str/includes? (or text "") "does not compile"))
      (is (str/includes? (or text "") ":rf.error/")
          "the card names the error id")
      (is (str/includes? (str/join " " (rf.story.test-helpers.e2e-multi-frame/text-nodes tree))
                         ":story.plan-error/broken")
          "the title row around the card still renders")
      (is (nil? (find-test-id tree "plan-error-probe")) "no subject renders"))))

(deftest a-broken-plan-is-contained-to-its-workspace-cell
  (testing "a workspace holding a broken and a healthy variant renders both
            cells: the card in one, the subject in the other"
    (rf/make-frame {:id :story.plan-error/broken})
    (rf/make-frame {:id :story.plan-error/healthy})
    (let [tree (expand [:div
                        (variant-cell-inner :story.plan-error/broken)
                        (variant-cell-inner :story.plan-error/healthy)])]
      (is (some? (find-test-id tree "story-plan-error")))
      (is (= "label=ok" (last (find-test-id tree "plan-error-probe")))
          "the healthy neighbour still renders its subject"))))

(deftest a-broken-plan-renders-a-card-in-the-side-by-side-grid
  (rf/make-frame {:id :story.plan-error/broken})
  (let [tree (expand (rf.story.ui.multi-substrate/multi-substrate-grid
                       :story.plan-error/broken))]
    (is (some? (find-test-id tree "story-plan-error")))))
