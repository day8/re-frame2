(ns re-frame.story.ui.canvas-resolved-args-cljs-test
  "The canvas renders the RESOLVED scenario's args.

  `canvas-inner` hands the variant's view its effective args, read off the
  compiled plan. Reading them from `rf.story.args/resolve-args`, which folds
  only the variant's OWN `:args`, would render the STORY DEFAULT for a
  variant that `:extends` a parent carrying args, while `run-variant`
  (which reads the compiled plan) reports the inherited value — the canvas
  and the run would describe two different scenarios.

  The test observes the value the view actually rendered: the probe view
  prints the props it was handed, read back through the same expanded-hiccup
  walk `canvas-substrate-routing-cljs-test` uses. No DOM, no React."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.machines :as rf.machines]
            [re-frame.registrar :as rf.registrar]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.story :as rf.story]
            [re-frame.story.loaders :as rf.story.loaders]
            [re-frame.story.ui.canvas :as rf.story.ui.canvas]
            [re-frame.story.ui.state :as rf.story.ui.state]
            [re-frame.story.test-helpers.e2e-multi-frame :as rf.story.test-helpers.e2e-multi-frame]
            [re-frame.subs :as rf.subs]))

;; ---- fixtures ------------------------------------------------------------

(defn- probe-view [args]
  [:div {:data-test "resolved-args-probe"}
   (str "count=" (:count args) " nested=" (get-in args [:nested :v]))])

(defn- register-probes! []
  (rf/reg-view* :views/resolved-args-probe probe-view)
  (rf.story/reg-story :story.canvas-args
    {:component :views/resolved-args-probe
     :args      {:count 0 :nested {:v 0}}})
  (rf.story/reg-mode :Mode.canvas-args/loud {:args {:count 5 :theme :loud}})
  ;; `:loaders` keeps `events-only-variant?` false, so the skeleton gate is
  ;; governed only by the lifecycle `rendered` drives.
  (rf.story/reg-variant :story.canvas-args/parent
    {:args {:count 42 :nested {:v 7}} :loaders [[:noop/loader]]})
  (rf.story/reg-variant :story.canvas-args/child
    {:extends :story.canvas-args/parent :loaders [[:noop/loader]]}))

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
  (register-probes!))

(use-fixtures :each {:before reset-all!})

;; ---- helpers -------------------------------------------------------------

(def ^:private canvas-inner @#'rf.story.ui.canvas/canvas-inner)

(defn- rendered
  "Drive `variant-id`'s lifecycle to `:ready` so the skeleton is elided, render
  the canvas's inner tree, and return the text the probe view painted."
  [variant-id]
  (rf/make-frame {:id variant-id})
  (rf.story.loaders/mount! variant-id)
  (rf.story.loaders/start-loaders! variant-id)
  (rf.story.loaders/finish-loaders! variant-id)
  (rf.story.loaders/finish-events! variant-id)
  (rf.story.ui.canvas/mark-variant-rendered! variant-id)
  (let [tree (rf.story.test-helpers.e2e-multi-frame/expand-tree (canvas-inner variant-id))
        text (some-> (rf.story.test-helpers.e2e-multi-frame/find-by-test-id
                       tree "resolved-args-probe")
                     last)]
    (rf.story/destroy-variant! variant-id)
    text))

(defn- shell! [f] (rf.story.ui.state/swap-state! f))

;; ===========================================================================

(deftest canvas-args-keep-mode-and-cell-precedence
  (testing "an :extends child renders the args its compiled plan resolves —
            not the story default — with the run layers folded AROUND the
            variant layer: an active mode sits BELOW the inherited variant
            args, a cell override above everything"
    (shell! #(assoc % :active-modes [:Mode.canvas-args/loud]))
    (is (= "count=42 nested=7" (rendered :story.canvas-args/child))
        "the inherited variant arg beats the active mode's")
    (shell! #(assoc-in % [:cell-overrides :story.canvas-args/child] {:count 99}))
    (is (= "count=99 nested=7" (rendered :story.canvas-args/child))
        "a cell override beats the inherited arg")))
