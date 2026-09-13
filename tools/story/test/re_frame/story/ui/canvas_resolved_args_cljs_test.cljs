(ns re-frame.story.ui.canvas-resolved-args-cljs-test
  "rf2-gwye.7 — the canvas renders the RESOLVED scenario's args.

  `canvas-inner` hands the variant's view its effective args. Before the fix
  it read them from `rf.story.args/resolve-args`, which folds only the
  variant's OWN `:args`, so a variant that `:extends` a parent (or
  `:compose`s a fragment) carrying args rendered the STORY DEFAULT instead,
  while `run-variant` (which reads the compiled plan) reported the inherited
  value. The canvas and the run described two different scenarios.

  Each test observes the value the view actually rendered: the probe view
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
  ;; Every variant declares `:loaders` so `events-only-variant?` is false on
  ;; both sides of the fix: the skeleton gate is then governed only by the
  ;; lifecycle `ready-tree` drives, and the args are the one thing that varies.
  (rf.story/reg-variant :story.canvas-args/parent
    {:args {:count 42 :nested {:v 7}} :loaders [[:noop/loader]]})
  (rf.story/reg-variant :story.canvas-args/child
    {:extends :story.canvas-args/parent :loaders [[:noop/loader]]})
  (rf.story/reg-fragment :fragment.canvas-args/args
    {:args {:count 42 :nested {:v 7}}})
  (rf.story/reg-variant :story.canvas-args/composed
    {:compose [:fragment.canvas-args/args] :loaders [[:noop/loader]]})
  (rf.story/reg-variant :story.canvas-args/direct
    {:args {:count 3} :loaders [[:noop/loader]]}))

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

(deftest canvas-renders-inherited-and-composed-args
  (testing "an :extends child and a :compose-only variant render the args
            their compiled plan resolves (parent / fragment beat the story
            default, nested maps deep-merge), not the story default"
    (is (= "count=42 nested=7" (rendered :story.canvas-args/child))
        ":extends — the parent's args reach the view")
    (is (= "count=42 nested=7" (rendered :story.canvas-args/composed))
        ":compose — the fragment's args reach the view")))

(deftest canvas-args-keep-mode-and-cell-precedence
  (testing "the run layers still fold AROUND the resolved variant layer:
            an active mode sits BELOW the inherited variant args, a cell
            override sits above everything"
    (shell! #(assoc % :active-modes [:Mode.canvas-args/loud]))
    (is (= "count=42 nested=7" (rendered :story.canvas-args/child))
        "the inherited variant arg beats the active mode's")
    (shell! #(assoc-in % [:cell-overrides :story.canvas-args/child] {:count 99}))
    (is (= "count=99 nested=7" (rendered :story.canvas-args/child))
        "a cell override beats the inherited arg")))

(deftest canvas-args-direct-variant-control
  (testing "control — a variant with no :extends / :compose renders its own
            args, unchanged by the fix"
    (is (= "count=3 nested=0" (rendered :story.canvas-args/direct)))))
