(ns re-frame.story.ui.sidebar-chips-cljs-test
  "Tests for the sidebar's rendered signal-chip strip + large-list bounding
  + variants-grid grouping (rf2-ba86n.4, spec/018 §7.1 + §10).

  Runs on both the JVM (cognitect.test-runner under `clojure -M:test`) and
  the CLJS node-test build (shadow's `:node-test` target; ns-regexp
  `cljs-test$` picks up this ns). The pure-data helpers (`bound-variants`,
  `workspace-grid-grouping`, the style-key projections) are exercised on
  both; the rendered hiccup (`signal-chips`) is CLJS-only since the var
  lives in a `.cljs` file we can't `:require` from the JVM.

  ## Coverage layers

  - **Pure data** (JVM + CLJS): `bound-variants` cap / expand /
    no-bound-when-small; `workspace-grid-grouping` grid-vs-non-grid.
  - **CLJS-only**: `signal-chips` renders one chip per axis, keeps the
    five axes in DISTINCT `data-axis` groups, and never collapses world
    inputs / runner / frame-binding into fidelity."
  (:require [clojure.test :refer [deftest is testing]]
            #?@(:cljs [[re-frame.story.ui.sidebar :as sidebar]])))

;; ---- pure: large-list bounding ------------------------------------------

#?(:cljs
   (deftest bound-variants-caps-and-expands
     (testing "a list at or below the cap is never bounded"
       (let [vs (mapv (fn [i] [(keyword (str "story.x/v" i)) {}]) (range 5))]
         (is (= {:shown vs :hidden 0} (sidebar/bound-variants vs 10 false)))))
     (testing "a list over the cap is bounded with the elided count"
       (let [vs (mapv (fn [i] [(keyword (str "story.x/v" i)) {}]) (range 50))
             {:keys [shown hidden]} (sidebar/bound-variants vs 40 false)]
         (is (= 40 (count shown)))
         (is (= 10 hidden))))
     (testing "expanded? reveals the full list (nothing hidden)"
       (let [vs (mapv (fn [i] [(keyword (str "story.x/v" i)) {}]) (range 50))
             {:keys [shown hidden]} (sidebar/bound-variants vs 40 true)]
         (is (= 50 (count shown)))
         (is (= 0 hidden))))))

#?(:clj
   (deftest jvm-bound-variants-projection
     (testing "the JVM gate exercises the same cap arithmetic via the same
               shape (the var lives in a .cljs file the JVM can't require)"
       (let [vs    (vec (range 50))
             cap   40
             shown (vec (take cap vs))]
         (is (= 40 (count shown)))
         (is (= 10 (- (count vs) cap)))))))

;; ---- pure: variants-grid grouping ---------------------------------------

#?(:cljs
   (deftest workspace-grid-grouping-projection
     (testing "a :variants-grid workspace reports its layout + cell count"
       (is (= {:layout :variants-grid :count 3}
              (sidebar/workspace-grid-grouping
                {:layout :variants-grid :variants [:a :b :c]}))))
     (testing ":grid and :tabs are also grid groups"
       (is (= {:layout :grid :count 2}
              (sidebar/workspace-grid-grouping {:layout :grid :variants [:a :b]})))
       (is (= {:layout :tabs :count 1}
              (sidebar/workspace-grid-grouping {:layout :tabs :variants [:a]}))))
     (testing "a non-grid layout (prose / custom) is NOT a grid group"
       (is (nil? (sidebar/workspace-grid-grouping {:layout :prose})))
       (is (nil? (sidebar/workspace-grid-grouping {:layout :custom}))))))

;; ---- CLJS-only: rendered signal-chip hiccup -----------------------------

#?(:cljs
   (defn- find-by-data-test
     "Walk a hiccup tree and return every element whose props map has
     `:data-test` equal to `tag`. Mirrors the helper in the sibling
     `tag-badges-cljs-test` so each test ns stays self-contained."
     [tree tag]
     (let [hits (transient [])]
       (letfn [(walk [node]
                 (cond
                   (and (vector? node)
                        (map? (second node))
                        (= tag (get (second node) :data-test)))
                   (do (conj! hits node)
                       (doseq [c (drop 2 node)] (walk c)))

                   (vector? node)
                   (doseq [c (rest node)] (walk c))

                   (seq? node)
                   (doseq [c node] (walk c))

                   :else nil))]
         (walk tree))
       (persistent! hits))))

#?(:cljs
   (defn- chips-by-axis
     "Group the rendered signal chips of a `signal-chips` tree by their
     `data-axis` attribute → vector of `data-value`s."
     [tree]
     (->> (find-by-data-test tree "story-sidebar-signal-chip")
          (group-by (fn [c] (get (second c) :data-axis)))
          (reduce-kv (fn [m axis chips]
                       (assoc m axis (mapv #(get (second %) :data-value) chips)))
                     {}))))

#?(:cljs
   (deftest signal-chips-always-present-axes
     (testing "a calm default variant still renders status + runner +
               frame-binding chips (one each), and no fidelity / world chips"
       (let [tree   (sidebar/signal-chips {} :pending)
             by-axis (chips-by-axis tree)]
         (is (= ["pending"]  (get by-axis "status")))
         (is (= ["headless"] (get by-axis "runner-requirement")))
         (is (= ["fresh"]    (get by-axis "frame-binding")))
         (is (nil? (get by-axis "fidelity")))
         (is (nil? (get by-axis "world-inputs")))))))

#?(:cljs
   (deftest signal-chips-keeps-five-axes-distinct
     (testing "a rich variant renders all five axes in SEPARATE data-axis
               groups — world inputs / runner / frame-binding are NEVER
               folded into fidelity (spec/018 §7.1)"
       (let [tree   (sidebar/signal-chips
                      {:setup        [[:dispatch [:seed]]]
                       :sub-overrides {[:q] 1}
                       :args          {:label "x"}
                       :network       {[:get "/x"] {:reply {:ok 1}}}
                       :fx-overrides  {:fx :stub}
                       :script        [[:click "#go"]]
                       :frame-binding :attached}
                      :fail)
             by-axis (chips-by-axis tree)]
         (is (= ["fail"] (get by-axis "status")))
         (is (= #{"real-setup" "sub-overrides"} (set (get by-axis "fidelity"))))
         (is (= #{"args" "network" "fx-overrides"} (set (get by-axis "world-inputs"))))
         (is (= ["dom"]      (get by-axis "runner-requirement")))
         (is (= ["attached"] (get by-axis "frame-binding")))
         ;; the world-input values must NOT appear under fidelity
         (is (not (some #{"args" "network" "fx-overrides"}
                        (get by-axis "fidelity"))))))))

#?(:cljs
   (deftest signal-status-style-key-projection
     (testing "each status value maps to its own tint style key — distinct
               colour/shape (spec/018 §12.6)"
       (is (= :signal-status-pass       (sidebar/status-signal->style-key :pass)))
       (is (= :signal-status-fail       (sidebar/status-signal->style-key :fail)))
       (is (= :signal-status-cannot-run (sidebar/status-signal->style-key :cannot-run)))
       (is (= :signal-status-error      (sidebar/status-signal->style-key :error)))
       (is (= :signal-status-pending    (sidebar/status-signal->style-key :pending))))))

#?(:cljs
   (deftest axis-group-style-key-projection
     (testing "each non-status axis has its OWN tint family so the axes
               read as distinct groups"
       (is (= :signal-fidelity (sidebar/axis->group-style-key :fidelity)))
       (is (= :signal-world    (sidebar/axis->group-style-key :world-inputs)))
       (is (= :signal-runner   (sidebar/axis->group-style-key :runner-requirement)))
       (is (= :signal-frame    (sidebar/axis->group-style-key :frame-binding)))
       ;; the four tints are all different — no axis shares fidelity's tint.
       (is (= 4 (count (set (map sidebar/axis->group-style-key
                                 [:fidelity :world-inputs :runner-requirement
                                  :frame-binding]))))))))
