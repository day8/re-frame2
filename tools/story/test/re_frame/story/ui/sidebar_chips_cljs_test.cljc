(ns re-frame.story.ui.sidebar-chips-cljs-test
  "The sidebar's large-list bounding, variants-grid grouping and rendered
  five-axis signal-chip strip (spec/018 §7.1 + §10).

  Every test here is CLJS-only: `re-frame.story.ui.sidebar` is a `.cljs`
  file the JVM cannot `:require`, so the JVM runner loads this namespace and
  finds no tests in it."
  #?(:cljs
     (:require [clojure.test :refer [are deftest is testing]]
               [re-frame.story :as rf.story]
               [re-frame.story.ui.sidebar :as rf.story.ui.sidebar]
               [re-frame.story.ui.workspace :as rf.story.ui.workspace])))

;; ---- pure: large-list bounding ------------------------------------------

#?(:cljs
   (deftest bound-variants-caps-and-expands
     (let [vs (mapv (fn [i] [(keyword (str "story.x/v" i)) {}]) (range 50))]
       (are [cap expanded? out] (= out (rf.story.ui.sidebar/bound-variants vs cap expanded?))
         ;; a list at the cap is never bounded
         50 false {:shown vs :hidden 0}
         ;; over the cap: the prefix, with the elided count
         40 false {:shown (subvec vs 0 40) :hidden 10}
         ;; expanded? reveals the full list
         40 true  {:shown vs :hidden 0}))))

;; ---- pure: variants-grid grouping ---------------------------------------

#?(:cljs
   (deftest workspace-grid-grouping-projection
     (are [id body out] (= out (rf.story.ui.sidebar/workspace-grid-grouping id body))
       :Workspace.x/g {:layout :grid :variants [:a :b]} {:layout :grid :count 2}
       ;; a non-grid layout (prose) is NOT a grid group
       :Workspace.x/p {:layout :prose}                  nil)))

;; A `:variants-grid` enumerates its anchor story's variants from the
;; registry and carries no `:variants` slot, so a count read off that slot
;; would show "VARIANTS-GRID · 0" for a grid rendering five cells.

#?(:cljs
   (deftest variants-grid-count-is-the-cells-it-renders-rf2-dacnd
     (rf.story/clear-all!)
     (try
       (doseq [v [:story.rf2-dacnd/a :story.rf2-dacnd/b :story.rf2-dacnd/c
                  :story.rf2-dacnd/d :story.rf2-dacnd/e]]
         (rf.story/reg-variant v {:setup []}))
       (testing "a `:for`-anchored grid counts the five cells it renders"
         (let [body {:layout :variants-grid :for :story.rf2-dacnd}]
           (is (= 5 (count (rf.story.ui.workspace/resolve-layout :Workspace.any/auto body)))
               "control: the grid itself resolves five cells")
           (is (= {:layout :variants-grid :count 5}
                  (rf.story.ui.sidebar/workspace-grid-grouping :Workspace.any/auto body)))))
       (testing "an id-anchored grid (`:Workspace.<path>` → `:story.<path>`) counts them too"
         (is (= {:layout :variants-grid :count 5}
                (rf.story.ui.sidebar/workspace-grid-grouping
                  :Workspace.rf2-dacnd/auto {:layout :variants-grid}))))
       (finally
         (rf.story/clear-all!)))))

;; ---- rendered signal-chip hiccup ----------------------------------------

#?(:cljs
   (defn- chips-by-axis
     "The `story-sidebar-signal-chip` elements of a `signal-chips` tree,
     grouped by their `data-axis` → vector of `data-value`s in render order."
     [tree]
     (let [hits (transient [])]
       (letfn [(walk [node]
                 (cond
                   (and (vector? node)
                        (map? (second node))
                        (= "story-sidebar-signal-chip" (:data-test (second node))))
                   (conj! hits (second node))

                   (vector? node) (doseq [c (rest node)] (walk c))
                   (seq? node)    (doseq [c node] (walk c))
                   :else          nil))]
         (walk tree))
       (reduce (fn [m {:keys [data-axis data-value]}]
                 (update m data-axis (fnil conj []) data-value))
               {}
               (persistent! hits)))))

#?(:cljs
   (deftest signal-chips-keeps-five-axes-distinct
     ;; World inputs / runner / frame-binding are NEVER folded into fidelity
     ;; (spec/018 §7.1). Status / runner / frame-binding always render one
     ;; chip; fidelity / world-inputs render none when the variant has none.
     (are [body status by-axis] (= by-axis (chips-by-axis (rf.story.ui.sidebar/signal-chips body status)))
       {} :pending
       {"status"             ["pending"]
        "runner-requirement" ["headless"]
        "frame-binding"      ["fresh"]}

       {:setup         [[:dispatch [:seed]]]
        :sub-overrides {[:q] 1}
        :args          {:label "x"}
        :network       {[:get "/x"] {:reply {:ok 1}}}
        :fx-overrides  {:fx :stub}
        :script        [[:click "#go"]]
        :frame-binding :attached}
       :fail
       {"status"             ["fail"]
        "fidelity"           ["real-setup" "sub-overrides"]
        "world-inputs"       ["args" "network" "fx-overrides"]
        "runner-requirement" ["dom"]
        "frame-binding"      ["attached"]})))

#?(:cljs
   (deftest axis-group-style-key-projection
     ;; each non-status axis has its OWN tint family, so the axes read as
     ;; distinct groups
     (is (= 4 (count (set (keep rf.story.ui.sidebar/axis->group-style-key
                                [:fidelity :world-inputs :runner-requirement
                                 :frame-binding])))))))
